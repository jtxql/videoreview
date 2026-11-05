# TourBox书签快捷控制与名称浮层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 双击TourBox默认名保存A/B；Long/Short按A点首尾循环切换；视频右上角显示当前书签名称。

**Architecture:** 纯双击识别器与可注入单任务定时器接入现有有界映射队列；书签宿主动作使用独立来源租约；复用持久化与受控恢复。纯导航/标题状态按ID跟踪，保持播放的实际快照用于快捷保存。

**Tech Stack:** 当前Kotlin/Android View、Handler、SharedPreferences及既有书签文件/libmpv，无新增依赖。

**Spec:** docs/superpowers/specs/2026-11-03-tourbox-bookmark-controls-design.md（用户已确认）

## Global Constraints

- 基线9398f95，沿用codex/screenshot-annotation和Native顺序实施，保留用户图片/抓包/缓存，不自动安装、访问ADB/USB、暂存、提交或推送。
- 默认双击TourBox保存当前A/B和核心实际倍速；Long/Tall单按上一条，Short单按下一条，其他映射与两旋钮灵敏度保留。
- 400ms双击窗口，按下之间须有释放，不重叠配对、不重复生成原始事件。保留单击映射；双击为None时单击即时。
- 列表与导航按A升序、创建时间升序、ID升序，首尾循环；游标使用ID，只有操作成功后更新。
- 快速保存不弹窗、不改变播放/暂停状态，不用UI旧倍速；现有列表保存/恢复行为保持。
- 32项队列、2秒过期及旧会话/配置授权保留。宿主操作自行暂停分发不得误使自身授权失效，旧来源必须失效。
- 名称只指向已播放书签或保存成功且正在相同区间循环的书签；暂停保留，手动改/清AB、换视频/代次/释放隐藏。
- 不修改USB协议、8/26/94字节、CDC、端点、解码、回收，不添加其他按键手势，不把模型测试当成真机。

## Review Focus

- 双击不能先执行单击，旧定时回调不能在停用/新视频后执行：Task4假时钟和迟到定时器测试。
- 已保存设置升级不能每次启动重置，失败不能假装迁移成功：Task3真实协调器假端口失败/重读测试。
- 快速保存不能暂停/续播，也不能把旧来源的成功回调显示到新视频：Task2请求计划和Task5实际纯操作门控测试。
- 相同A、插入/删除和首尾不能依赖旧下标：Task1稳定ID与目录变化测试。
- 只保存未播放片段不能显示错名，旧窗口/GUI和硬件控制不能混淆游标与名称：Task1/5标题状态与恢复失败测试。

## 文件职责

所有路径相对项目根目录。以下playback目录为`android/app/src/main/java/io/tabletennisreview/playback/`。
- 新建playback/BookmarkPlaybackState.kt：纯导航游标、活动名称及来源状态。
- 修改playback/ClipBookmarks.kt、ClipBookmarkDialog.kt：统一A排序与默认名方法。
- 修改player-mpv下PlayerApi.kt、ReviewPreset.kt、MpvPlayerController.kt：保持播放快照计划和入口。
- 修改playback/TourBoxMapping.kt、TourBoxMappingStore.kt、TourBoxSettingsDialog.kt：三个动作、双击输入与一次性迁移。
- 新建playback/TourBoxButtonGesture.kt、HandlerGestureTimer.kt；修改TourBoxPlayerBinding.kt：纯手势、单定时器、宿主动作路由。
- 修改PlayerPanel.kt及必要纯UI策略：快捷保存/导航/标题，原书签列表路径复用。
- 新建app/src/testInput/java/BookmarkPlaybackCheck.java、TourBoxButtonGestureCheck.java；补现有ClipBookmarkCheck、TourBoxMappingCheck、TourBoxPlayerBindingCheck、PlayerUiCheck及player-mpv的ReviewPresetCheck。相应build.gradle.kts注册检查并接入check。
- 更新README、ROADMAP、CHANGELOG、docs/VIDEO_BOOKMARKS.md、TOURBOX_CUSTOM_MAPPING.md和本计划进度账本。

## Task 1：按时间导航与标题状态

**Interfaces:** `BookmarkCatalog.forVideo(uri)`改为统一排序；新增`defaultName(uri):String`返回`片段${数量+1}`，与现有规则一致，允许删除后重名。

`BookmarkPlaybackState`提供`observe(source:BookmarkSource?,loop:LoopPoints)`、`candidate(catalog:BookmarkCatalog,direction:Int):ClipBookmark?`、`restored(id:String,source:BookmarkSource)`、`saved(item:ClipBookmark,source:BookmarkSource,loop:LoopPoints)`、`activeName(catalog:BookmarkCatalog):String?`、`reset()`；source包含URI和播放器session。direction只接受-1/+1，状态不依赖Activity/USB。

- [x] 先写BookmarkPlaybackCheck并注册`:app:testBookmarkPlayback`；修改旧ClipBookmarkCheck的排序期望并观察RED。用A=10/2/5的创建顺序验证返回2/5/10，A相同按createdAt/ID稳定；跨视频隔离，不能改原文件内容。
- [x] 实现两个独立ID：cursorId与activeId。没有游标/游标不存在时Next取首、Previous取末；零条null，一条循环自身。restored仅接受同源成功通知，saved总更新游标，只有当前loop.active且A/B一致才激活名称。
- [x] observe在来源变更/未加载时清两个ID，手动AB变化或清除时只清activeId；倍速、播放位置与暂停变化不清。activeName仅从当前目录按ID取名，删除后隐藏，不凭相同名称/AB猜测ID；改名即时读取新名。
- [x] 测试首尾、多次循环、同A、增删当前/非当前项、恢复失败不调用restored、保存未循环不激活、暂停/变速保留、改AB隐藏、换源/旧源通知无效。将GUI默认名改用defaultName。Run `:app:testBookmarkPlayback :app:testClipBookmarks`，Expected: PASS。

## Task 2：不改变播放状态的核心快照

**Interfaces:** `ReviewSnapshotRead`枚举Pause/Speed/Duration；`ReviewSnapshotPlan.reads(pausePlayback:Boolean):List<ReviewSnapshotRead>`、`phaseAfter(current:PlayerPhase,pausePlayback:Boolean):PlayerPhase`。

PlayerController新增`prepareReviewSnapshotKeepingPlayback(authorization:()->Boolean,callback:(ReviewPreparation)->Unit):CommandAdmission`；现有prepareReviewSnapshot签名/暂停行为不变。runReview内部增加snapshotPause参数，仅用于snapshot分支，恢复九项请求保持原样。

- [x] 先补ReviewPresetCheck，验证false计划严格为Speed/Duration且无Pause，true为Pause/Speed/Duration，false结束保持Playing/Paused/Buffering等原phase，true为Paused。运行RED后实现计划，让实际runReview逐项执行该计划，不能测试一份列表而实际硬编码另一份。
- [x] 新入口同样单busy/15秒期限，读取接纳时LoopPoints和核心实际Speed/Duration，逐步校验原core、epoch与authorization；keeping路径绝不set pause，完成不强制改成Paused。列表原路径仍暂停，restore路径九项顺序和失败处理不变。
- [x] FakePlayer补该入口；新增快照计划检查并保留原108请求检查，运行`:player-mpv:testReviewPresets :player-mpv:testPlayer :app:testTourBoxPlayerBinding`，Expected: PASS。注明计划测试不执行真实mpv/native取消。

## Task 3：映射动作与一次性升级

**Interfaces:** MappingInput新增`TourBoxDouble("tourbox-double","TourBox 双击")`；MappingAction新增`SaveBookmark("bookmark-save",...)`、`PreviousBookmark("bookmark-previous",...)`、`NextBookmark("bookmark-next",...)`，三个均repeatable=false、command()为null，由宿主路由。默认Tall=Previous、Short=Next、TourBoxDouble=Save，其余默认不变。

MappingLoad新增可选`upgrade:MappingConfig?`（保留Java构造兼容）；encode保留version=1并写`bookmark-controls=1`。decode与保存协调器负责一次升级，不在Activity直接改设置。

- [x] 先扩充TourBoxMappingCheck：13输入/26动作精确映射和往返，新缺字段、旧字段、自定义TourBox单击、其他按钮和两灵敏度保留；旧配置第一次升级Long/Short/Double指定动作，保存重读后不再重置用户后改值。运行RED。
- [x] 新装defaults直接是新映射。没有bookmark-controls标记的有效旧配置按旧默认解码（旧Long/Short/Double均None），生成只替换上述三个输入的upgrade候选；只有单一合法marker=1时视为已升级。未知/重复marker提示且不自动迁移，避免损坏数据强制覆盖。
- [x] MappingSaveCoordinator在原后台初始化中尝试持久化候选；成功才发布新活动映射并更新stored，失败尝试恢复旧缓存/保留旧活动配置并提示“选择书签动作后保存或重启重试”。显式保存包含marker=1，尊重用户当前选择；不每次启动覆写。默认恢复也包含新动作。
- [x] TourBox设置自动显示13行/26动作，说明双击启用时单击约400ms确认，不重置倍率。测试迁移写入false/异常、再读重试、用户主动None、marker异常、所有动作与草稿/存储回归，Expected: `:app:testTourBoxMappings :app:testRotationSensitivity` PASS。

## Task 4：纯双击识别、定时与有界桥接

**Interfaces:** `GestureDispatch(input:MappingInput,event:TourBoxInputEvent)`、`GestureDecision(dispatches:List<GestureDispatch>,deadlineNanos:Long?)`；`TourBoxButtonGesture.accept(event,doubleEnabled:Boolean,nowNanos:Long):GestureDecision`、`expire(nowNanos):GestureDecision`、`reset()`。

`GestureTimer.schedule(deadlineNanos:Long,task:()->Unit):AutoCloseable`必须异步、close非阻塞；HandlerGestureTimer使用Main Handler和同一elapsedRealtimeNanos时钟，延迟向上取整到毫秒，不新建线程；主线程繁忙可能延后回调，仍须过期核验。TourBoxPlayerBinding增加必需timer参数（在now之后，更新所有实际调用及Java测试夹具），追加可选`bookmarkAction:((MappingAction,()->Boolean,String)->CommandAdmission)?`宿主回调。

- [x] 新增TourBoxButtonGestureCheck注册`:app:testTourBoxButtonGestures`，先RED：一次按下后到期发单击，400ms内Down/Up/Down只发Double；无Up/重复Down不算；恰好400ms及以上按过期处理；长按不重复；三击不重叠、四击两双击；Double映射None时立即单击；其他按键/旋转不延迟。
- [x] 实现纯模型，绑定层同一lock内调用；接收原事件只计一次received，定时补发仅入队，不伪造新raw或语义序号。候选手势记录来源stamp，日志区分pending/单击/双击；非TourBox释放仍不执行动作。
- [x] 每次只保留一个取消句柄/单调timerSerial；discardLocked同步清手势和取消定时器。迟到/已经取消的Runnable必须先核serial和target/config/USB来源，不可复活旧事件。定时器拒绝调度走可见故障路径，不无限重试；接收和到期不能重复发同一单击。
- [x] 将分派入队提取为共享短临界区，单/双击复用原队列容量、压力、2秒过期与permit；不在源事件锁中执行播放器/磁盘。排队的书签动作属于受保护离散动作，旋转映射成书签也不被灵敏度放大。
- [x] drain对三个书签动作调用bookmarkAction，并给出独立operationLease：接纳前仍permit，接纳后的自身停用/队列撤销不应否定租约；配置epoch、原USB事件身份、可见性、播放器session或日志失效必须否定。可复用截图captureLease规则，截图既有行为保留。
- [x] 用FakeTimer/Fake UI executor测试全部窗口/调度顺序、取消后手工执行旧Runnable、变配置/停用/换USB/换播放器/后台、到期过期、接收数量不重复、队列上限、宿主自我停用后授权仍有效而旧来源无效；更新旧测试预期。Run `:app:testTourBoxButtonGestures :app:testTourBoxPlayerBinding :app:testTourBoxMappings :app:testRotationSensitivity`，Expected: PASS。

## Task 5：宿主快捷保存、循环导航和HUD

**Interfaces:** PlayerPanel新增`handleBookmarkAction(action,authorization,origin):CommandAdmission`供绑定回调；使用现有ClipBookmarkSession并组合外部来源授权。GUI和硬件恢复共用一个受控恢复完成路径，只有Ready后通知BookmarkPlaybackState.restored。

- [x] 先补BookmarkPlaybackCheck/PlayerUiCheck与绑定夹具测试，RED覆盖：无书签正常拒绝、忙时不重复、保存失败不移动游标、成功但loop未启用只更新游标、旧来源成功不能更新新名称、自我停用租约仍有效。
- [x] 快捷保存创建原视频租约，暂停事件分发但不暂停mpv，调用keeping快照路径；生成UUID/defaultName/时间并后台apply Add。只有接纳前来源有效才提交；成功后若仍同源更新saved和Toast，失败提示，任何结果均结束同一旧租约。已提交的旧视频写入可完成，但其回调不得改新视频/启动播放。
- [x] Previous/Next从当前库和当前视频状态取candidate，复用restoreReview；空库简短提示，不打开窗口。完成后才更新ID/标题，失效或失败不前移游标；导航过程中正常busy与持有门控阻止重入，结束恢复健康映射。原GUI选择恢复同样更新状态，GUI保存仍用原暂停快照。
- [x] PlayerPanel随前台订阅Store最新目录用于改名/删除标题更新，后台/销毁释放订阅；每次玩家状态observe来源及LoopPoints，未加载/换代清状态。HUD更新从统一函数进入，避免250ms observer覆盖名称。
- [x] 右上角用纵向容器容纳独立名称TextView与原hud：名称maxLines=1、ellipsize=END、最大宽度视频60%，半透明背景/右对齐、不消费触摸；横屏/全屏两行，竖屏仅有名时显示名称。尺寸变化更新宽度，不隐藏/改动原进度条、倍速信息或截图实际渲染层。
- [x] 覆盖纯状态/授权/标题文案策略，运行书签/映射/手势/UI/截图/播放器回归及Debug，Expected: PASS。实际双击时序、画面不中断、HUD截断和系统窗口交互待真机。

## Task 6：检查、文档与唯一独立审查

- [x] 版本`0.7.1-tourbox-bookmarks`/code21。更新README、ROADMAP、CHANGELOG、VIDEO_BOOKMARKS与TOURBOX_CUSTOM_MAPPING，写明一次性迁移、400ms单击延迟、导航初始/首尾规则、改名/删除/手动AB对标题的影响和测试边界。保留以前的历史检查结果与用户已验证记录。
- [x] 使用进程级JBR和缓存依赖运行完整无设备检查（不改全局环境）：

```powershell
$env:JAVA_HOME='G:\Program Files\Android\Android Studio\jbr'
& .\android\gradlew.bat -p android --offline --no-daemon --console=plain `
 :app:testBookmarkPlayback :app:testTourBoxButtonGestures `
 :app:testClipBookmarks :player-mpv:testReviewPresets `
 :app:testRotationSensitivity :app:testTourBoxMappings :app:testPlayerUi `
 :app:testTourBoxPlayerBinding :app:testScreenshot :app:testAutoInit `
 :app:testInputEvents :app:testConfig94 :app:testUsbAsyncCleanup `
 :app:testUsbGenerations :player-mpv:testPlayer :video-export:testClipExport `
 :app:assembleDebug :app:lintDebug :player-mpv:lintDebug :video-export:lintDebug
```

Expected: 所有JavaExec与Debug/Lint成功，读取真实断言数量和Lint XML。基线app26/player0/export1警告，新增项解释；不因警告盲目升级依赖。核验签名、code21、SHA-256、16KB ZIP对齐、git diff --check及新文件空白格式。

- [x] 对比9398f95确认USB核心、input解码、端点和协议常量不变；本轮不改变RotationProcessor语义。用户未跟踪文件原样保留，不装APK、不暂存或提交。
- [x] Native流程仅一次最终独立只读审查；重要问题先RED再修复并重跑完整检查，非阻断建议记账。保留真实native/Android消息队列/文件系统未执行范围，不用纯计划测试宣称全平台通过。

## 自审与实施交接

Task1的ID状态与Task5来源/目录一致；Task2新增入口保留原暂停接口；Task3的3动作与Task4宿主路由一致；Task4定时器必需注入，所有调用与Java测试统一更新。旧迁移失败后的显式保存尊重当前选择，提示用户选新动作后保存，避免后台反复覆盖自定义值。既有Android实际集成测试覆盖缺口继续记录。

已保留用户Native本会话顺序实施偏好，不再选择执行方式；本计划需审核确认后实施，任务之间不重复暂停询问。


自审结果：六个任务覆盖设计全部功能；13输入/26动作、保持播放接口、三个宿主动作、必需定时器和导航来源类型一致。测试重点已分配到对应任务；真实Handler/播放器/持久化故障仍需Android集成或真机，未提前声称已验证。本轮仅编写计划与设计状态，不运行构建、不修改产品代码。
