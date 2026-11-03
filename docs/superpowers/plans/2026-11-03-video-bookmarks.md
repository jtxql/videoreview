# 按视频管理的片段书签 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 保存当前视频的多组命名A/B及实际倍速，点击书签后从A点循环播放。

**Architecture:** 纯书签目录/编码/变更模型、进程级后台原子存储、轻量列表对话框及播放器受控快照/复合恢复。URI仅用于应用私有文件关联；宿主用独立交互代次隔离列表、保存和恢复，USB不变。

**Tech Stack:** 当前Kotlin/Android View、Java标准库和Android AtomicFile、既有libmpv封装，无新增依赖。

**Spec:** docs/superpowers/specs/2026-11-03-video-bookmarks-design.md（用户已确认）

## Global Constraints

- 按完整content URI区分视频，只显示当前视频，不凭同名文件合并、不复制视频；名称相同可独立存在。
- 保存ID、URI、名称、A/B、核心实际倍速、创建时间；恢复前须满足0≤A<B≤当前实际时长及支持的五档倍速。
- 点击即从A按保存倍速无限循环；不能先播放再改区间，不宣称多条native请求具备事务原子性。
- 列表内保存/重命名/确认删除，成功落盘才更新可见目录；后台串行I/O，失败保留旧数据，不自动覆盖坏格式或未知版本。
- 打开列表暂停并撤销TourBox待办，关列表不自动续播；点击书签才播放。切片、后台、销毁使旧交互失效，不能重开旧文件。
- 只加一个主界面入口，全屏入口随现有进度浮层显隐；不加缩略图、搜索、分类、跨视频库、云同步或USB映射。
- 不修改8/26/94字节、CDC、端点、USB回收、解码、灵敏度；不安装、访问ADB/USB、暂存、提交或推送。
- 沿用现有分支和Native顺序执行，保留所有前置功能与用户未提交文件；进度账本独立保存，不清理工作区。

## Review Focus

- 刚改倍速立即保存不能存入250ms旧状态：Task2核心读取快照，Task4拒绝busy操作并绑定来源。
- 同名视频或同名书签不能串用/覆盖：Task1按URI和ID测试，Task3期望revision防旧覆盖。
- 写入/重命名/删除失败不能先改界面，坏文件不能变空后被覆盖：Task1/3失败与不可写测试。
- 设置/截图/后台重建不应意外恢复播放，旧回调不操作新视频：Task4交互租约和暂停意图测试。
- 快速连点、旧TourBox队列或原生中途失败不能交错两次恢复：Task2单busy操作+逐步授权，Task4单次恢复状态机测试。

## 文件职责

下列路径均相对项目根目录。
- 新建`android/app/src/main/java/io/tabletennisreview/playback/ClipBookmarks.kt`：纯条目、目录、编码、变更与保存协调器。
- 新建同目录`ClipBookmarkStore.kt`：唯一进程存储、AtomicFile适配、worker/Main订阅。
- 新建同目录`ClipBookmarkSession.kt`、`ClipBookmarkDialog.kt`：纯交互租约与原生列表UI。
- 新建`android/player-mpv/src/main/java/io/tabletennisreview/player/ReviewPreset.kt`：纯快照/恢复结果及请求计划；修改PlayerApi/MpvPlayerController接入。
- 修改PlayerPanel/PlayerUiPolicy作最小宿主集成；修改绑定测试中的FakePlayer补新增接口，不更改绑定行为。
- 新建`android/app/src/testInput/java/ClipBookmarkCheck.java`和`android/player-mpv/src/testPlayer/java/ReviewPresetCheck.java`；相应build脚本注册测试并接入check。
- 更新README、ROADMAP、CHANGELOG，新增`docs/VIDEO_BOOKMARKS.md`及本计划进度文件。

## Task 1：可测试的书签模型与编码

**Interfaces:** `ClipBookmark(id:String,videoUri:String,name:String,a:Double,b:Double,speed:Double,createdAtMillis:Long)`；`BookmarkCatalog(items:List<ClipBookmark>)`提供`forVideo(uri):List<ClipBookmark>`、`add(item):BookmarkCatalog`、`rename(id,uri,name):BookmarkCatalog`、`delete(id,uri):BookmarkCatalog`；不可变副本。`BookmarkCodec.encode(catalog):String`、`decode(text:String?):BookmarkLoad(catalog,writable,notice)`。

- [x] 先编写ClipBookmarkCheck并注册`:app:testClipBookmarks`：不同URI隔离、同名独立ID、按创建时间倒序/ID稳定排序、五档数值、改名不改区间、跨视频改删拒绝、删除取消不变。运行RED（缺失类/接口）。
- [x] 实现验证与编码：UTF-8版本化文本首行`bookmarks-version=1`，每行七列以TAB分隔：ID、Base64 UTF-8 URI、Base64 UTF-8名称、A、B、speed、createdAtMillis。double用locale无关标准字符串；不使用split猜测完整USB事件，此编码仅是本地文件。
- [x] 本轮实现值：名称trim后1～40个Unicode码点，拒绝换行/控制字符；URI仅content且≤8192字符；ID采用UUID，时间≥0；有限非负A/B且A<B，speed严格属于现有五档。每视频最多200条、全库2000条，编码文件最大2MiB；满时拒绝，不删旧项。
- [x] null表示首次空库可写；任何损坏、重复ID、超限或未知版本返回不可写加载状态，不丢弃坏行后覆盖原文件。有效内容往返完全一致；测试中文/emoji、分隔符、非法数字、NaN/Infinity、重复ID、容量边界、非content URI。运行testClipBookmarks，Expected: PASS。

## Task 2：核心实际快照和串行恢复

**Interfaces:** `ReviewPreset(a:Double,b:Double,speed:Double).validated(duration:Double):LoopPoints`；`ReviewSnapshot(sessionId:Long,preset:ReviewPreset,duration:Double)`；`ReviewPreparation.Ready(snapshot)/Rejected(reason)`；`ReviewRestoreResult.Ready(sessionId)/Rejected(reason)`；`ReviewRestorePlan.requests(preset,duration):List<NativeRequest>`。

`PlayerController`新增`prepareReviewSnapshot(authorization:()->Boolean,callback:(ReviewPreparation)->Unit):CommandAdmission`和`restoreReview(preset:ReviewPreset,authorization:()->Boolean,callback:(ReviewRestoreResult)->Unit):CommandAdmission`。现有PlayerCommand和USB映射不变。

- [x] 先写ReviewPresetCheck并注册`:player-mpv:testReviewPresets`，覆盖五档、边界、原循环已开启时覆盖、未知时长/超时长/无效倍率拒绝、请求次序；先运行RED。
- [x] 复合请求固定为：pause=yes → ab-loop-b=no → ab-loop-a=no → speed=保存值 → ab-loop-a=A → ab-loop-count=inf → ab-loop-b=B → seek A absolute+exact → pause=no。校验在生成任何请求前完成。Expected测试用`A=2,B=4,speed=.25,duration=10`核对全部九项及唯一末尾pause=no。
- [x] MpvPlayerController新增两入口，均主线程接纳、busy串行化，原scope内15秒有限超时；开始及每次native读写前检查core引用/epoch/authorization。快照暂停后读核心实际Speed/Duration，使用同一接纳时的LoopPoints并校验；不复用带导出语义的prepareExport，不依赖UI定时刷新倍速。
- [x] 恢复先读取实际Duration并校验preset，再顺序应用请求，成功更新loop/speed并回调Ready。来源失效取消、不自动重试；非法参数在写入前拒绝；部分原生失败按既有fail/release停用，返回失败而非假装回滚；回调一次且旧会话不能更新新state。finally仅对原会话清busy。
- [x] 同步补TourBoxPlayerBindingCheck FakePlayer的两个接口（非相关路径直接抛AssertionError），保证原有绑定测试仍检验正确入口；运行testReviewPresets、testPlayer及testTourBoxPlayerBinding，Expected: PASS。纯请求计划/授权测试不等于已测试真实mpv读写。

## Task 3：后台可靠保存

**Interfaces:** `BookmarkSnapshot(catalog:BookmarkCatalog,revision:Long,writable:Boolean,notice:String?)`；`BookmarkMutation.Add(item)/Rename(id,uri,name)/Delete(id,uri)`；`BookmarkSaveResult(success:Boolean,snapshot:BookmarkSnapshot,message:String)`；`BookmarkFilePort.read():String?`和`write(text:String):Unit`（失败抛异常，原文件不变）。`BookmarkSaveCoordinator(port).snapshot()`、`apply(expectedRevision,mutation):BookmarkSaveResult`。

`ClipBookmarkStore.get(applicationContext)`、`snapshot()`、`subscribe(callback):AutoCloseable`、`apply(expectedRevision,mutation,callback):AutoCloseable`供UI调用。

- [x] 先在ClipBookmarkCheck增加假文件端口测试：新增/重命名/删除落盘成功后revision+1，失败目录/revision不变；读取异常/坏文件禁写；旧revision在写前拒绝；重启重读；同名不覆盖。执行RED。
- [x] 实现协调器，发布不可变快照。Store进程单例只持applicationContext；首次revision=-1只读，worker串行读写，Main投递已提交快照。取消UI回调不撤销已完成写入或丢失全局更新；订阅关闭不再持有Activity。
- [x] Android文件位置`files/video-bookmarks.v1`，AtomicFile.startWrite/finishWrite/failWrite；输入有界读取，缺文件为null，出错不写空库。只在finishWrite成功返回后发布；备份/失败恢复遵循AtomicFile，不自创覆盖协议。不得输出URI或原始库内容到普通日志。
- [x] 追加成功与失败、迟到回调、保存中关闭窗口、并发过期修改测试，Expected: testClipBookmarks PASS。真实AtomicFile异常/磁盘故障仍需Android环境，不用假端口测试替代实测。

## Task 4：书签列表与宿主生命周期

**Interfaces:** `BookmarkSource(videoUri:String,playerSession:Long)`；`ClipBookmarkSession(source)`使用单调交互token，提供`beginSnapshot():Long`、`beginRestore():Long`（不允许重复进行）、`isCurrent(token,source):Boolean`、`finish(token)`、`close()`。过程阶段至少区分Idle/Snapshotting/Restoring/Closed；Snapshotting持续到命名/写入结果或取消，finish仅结束匹配token并转Idle，close永久失效所有token。新的begin只在Idle返回唯一正token，其余返回0。

`ClipBookmarkDialog`只管理当前URI条目/命名/重命名/删除确认及异步Store订阅，接受宿主onSaveCurrent/onRestore回调；提供show/resize/dispose，接收快照和播放器可用状态，不接触USB/native。

- [x] 先给ClipBookmarkCheck/PlayerUiCheck增加交互测试：重复点击只给一个token；同URI新session、不同URI、close/后台后的回调均无效；未选书签关闭保持暂停；后台保存状态保留暂停意图。运行RED，随后实现纯租约与UI策略。
- [x] PlayerPanel加入bookmarkHolding并纳入controlHolding；两个入口（底部与全屏浮层）同一可用性门控，初始化/busy/截图/导出/拖进度时禁用。打开时先撤销映射待办并暂停，列表显示仅当前URI；播放器暂停未结束时禁止保存或恢复。
- [x] 保存调用prepareReviewSnapshot，授权同时核对UI交互token、前台、选中URI、播放器会话。快照返回后命名；最终Add必须仍匹配原来源，期间切片/后台/关闭不误保存为另一个视频；保存/改名失败保留输入及旧列表。重命名/删除绑定ID+URI及revision，不用列表下标作为身份。
- [x] 选中后冻结preset和来源，禁止重复选中；关闭Dialog但保留本次恢复租约，调用restoreReview，完成/失败后结束租约、更新标记和恢复正常TourBox接纳。只关闭列表时停在暂停；关闭动作不能误使已选中恢复立即失效。保存/改名/删除不改变正在播放区间。
- [x] 操作期间使用现有syncMapping和取消待办边界；onHostStop/onDestroy及原视频/播放器换代撤销租约/关闭弹窗，保护reloadPaused与Bundle暂停意图；orientation变更仅resize，旧Store订阅释放。后台回来不自动重复恢复。Store已提交更新仍可供新窗口读取。
- [x] 用现有progressOverlay显隐结果同步全屏书签入口，单击仍可显隐；不增加独立手势。构建并运行书签/UI/绑定/截图回归，Expected: PASS。真实控件、SAF及全屏显示留待真机。

## Task 5：完整检查、文档和独立审查

- [x] 版本设`0.7.0-clip-bookmarks`/code20，更新README/ROADMAP/CHANGELOG和docs/VIDEO_BOOKMARKS.md，说明URI关联限制、容量、备份/清数据行为、错误状态及操作步骤。记录准确检查结果，不把模拟/编译成功写成真机通过。
- [x] 使用进程级JBR与既有离线依赖执行：

```powershell
$env:JAVA_HOME='G:\Program Files\Android\Android Studio\jbr'
& .\android\gradlew.bat -p android --offline --no-daemon --console=plain `
 :app:testClipBookmarks :player-mpv:testReviewPresets `
 :app:testRotationSensitivity :app:testTourBoxMappings :app:testPlayerUi `
 :app:testTourBoxPlayerBinding :app:testScreenshot :app:testAutoInit `
 :app:testInputEvents :app:testConfig94 :app:testUsbAsyncCleanup `
 :app:testUsbGenerations :player-mpv:testPlayer :video-export:testClipExport `
 :app:assembleDebug :app:lintDebug :player-mpv:lintDebug :video-export:lintDebug
```

Expected: 所有JavaExec检查及Debug/Lint成功；读取真实断言数量和Lint XML，比较0.6.2基线app25/player0/export1警告，新增项必须解释。不自动安装；当前环境首次签名检查须同样设置进程级JAVA_HOME。

- [x] 核验apksigner、版本、zipalign -c -P16、SHA-256、git diff --check及新未跟踪源码格式；USB核心/异步/input/灵敏度相对本轮起点未改，用户日志、图片、pcapng和缓存不纳入任何暂存或清理。
- [x] 按Native流程进行唯一最终独立只读审查；重要缺陷先RED复现再修正并重跑完整检查，非阻断测试补强记录待办。不创建提交或自动安装，不重新选择执行方式。

## 自审与交付边界

模型→Store→Dialog、ReviewPreset→控制器→宿主恢复的接口已逐项对齐；Review Focus分别有测试任务。真实mpv逐步失败、系统权限持久性、AtomicFile底层故障/掉电持久性及Dialog布局不能用纯JVM证明，必须在交付中保留待验收标识。按现有Native偏好连续实施，任务之间不重复询问；本计划需用户审核后才进入实现。


实施收尾说明：模型/请求计划/同步协调器/独立租约的JVM测试及完整检查通过；runReview与Android Store消息投递的真实集成测试未执行，作为非阻断覆盖待办详见进度账本与docs/VIDEO_BOOKMARKS.md，不用勾选状态冒充平台测试通过。
