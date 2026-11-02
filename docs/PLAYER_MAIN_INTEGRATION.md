# 008-B 主工程播放器集成记录

日期：2026-11-02。用户已批准具体方案。**代码、无设备回归、debug构建及静态打包核验完成；主应用已获授权覆盖安装，用户反馈视频播放和USB读取正常；其余详细边界见末节。** 独立验证结束状态保留，不把其结果自动扩大为主应用验收。

## 1. 先升级Kotlin，再集成

第一步仅修改android/build.gradle.kts的KGP classpath为2.4.21，21个原app源码/资源文件SHA256一致。保持AGP9.2.1、Gradle9.4.1、已有Studio JBR25、JVM17、compile/target37、min26、Build-Tools36.0.0；未改系统环境变量、安装SDK/NDK或操作设备。

第一步独立执行007四组检查及assembleDebug/lintDebug，BUILD SUCCESSFUL in 2m34s。输入497断言、异步回收20场景/105断言、连接代次27场景/48断言通过；94配置回归输出PASS（包括固定payload/hash、25分割点、早到/错/迟回复、CDC次序、短写、二次确认、32线程单reserve、旧epoch及reset；该检查未报告断言总数，不编造数字）。当时Lint0错误/7警告。通过后才引入播放器。

第二步新增:player-mpv、屏幕面板及同Activity页切换。第一次离线集成因缺com.android.library9.2.1插件标记失败，补齐常规Maven依赖后保持版本不变。旧自定义JVM检查在新增项目依赖后产生Android产物变体歧义，修正为延迟获取debugRuntimeClasspath、通过artifactView明确请求android-classes-jar；提前获取配置的中间错误也已定位修正。**没有修改旧测试断言或为通过构建跳过检查。**

## 2. 工程与文件

```text
android/
  app/                         原USB诊断宿主＋播放器页
    .../playback/PlayerPanel.kt  屏幕控件、SAF、状态及生命周期转发
    .../playback/PlayerLog.kt    私有播放器日志、串行后台写入/导出
    .../usbdiagnostics/MainActivity.kt  同Activity标签与结果路由
  player-mpv/                  libmpv适配边界
    .../player/PlayerApi.kt      无Android/USB/mpv类型的控制协议与状态
    .../player/PlayerPolicy.kt   规则、原生请求计划、独立播放器epoch
    .../player/ReviewPlayerView.kt   视频View宿主
    .../player/MpvPlayerController.kt   私有mpv实现
    src/testPlayer/java/PlayerCheck.java
    src/main/assets/licenses/  第三方NOTICE/Apache/GPL文本
    dependency-manifest.json   固定来源、版本及三个AAR哈希
```

修改root/app Gradle及settings登记模块、KGP与受限Maven仓库group；app依赖本地模块。:player-mpv内部implementation固定io.github.yuroyami:libmpvkt-view:0.3.0，app不直接import mpv或USB驱动作为播放器接口。Manifest仅应用标签改为TableTennis Review，保留原包名io.tabletennisreview.usbdiagnostics、USB Host feature及备份规则；versionCode2/versionName0.2.0-player，只打包arm64。

原21文件快照中，只有MainActivity和Manifest按计划改变，其余19个源码/资源（含USB Manager、DualEndpointSession、输入解码、8/94配置、回收/代次及原测试）SHA256一致。MainActivity原USB停止、关闭、receiver及权限流程保留，新增播放器清理用runCatching隔离，避免播放器异常跳过旧USB清理。不会自动发送USB初始化或重发94字节，不实现TourBox映射。

## 3. 控制与状态边界

PlayerController只暴露initialize、execute、state/监听、pauseForPageChange、release/close。PlayerCommand包括播放/暂停、五档速度、相对seek、双向单步、记A/B、启用/清除AB和回A；PlayerState含session、阶段、busy、Double时间/时长、速度、seekable及AB标点。输入接口不依赖USB、mpv或Activity，控制方法由主线程owner调用；未来TourBox回调需要转交主线程并核对原输入会话，当前不接线。

- ±5秒使用seek的relative+exact参数，不模拟帧或用1/fps做逐帧。
- 单步先pause=yes，再发frame-step/frame-back-step，启用AB时拒绝单步；不宣称命令返回证明画面帧准确。
- 速度限五档有限值，使用speed属性；属性状态/命令回读可诊断，不自动静音或播放。
- A/B读取实际time-pos；先A后B、0≤A<B≤已知有效duration；启用时先清B再写A/count=inf/B、暂停并精确seek到A，仍需用户点播放；清除顺序B/A，换片/重建不恢复旧标点。
- 单次操作接纳，busy时明确Rejected，无隐形待办或静默丢掉的内部旋转队列。UI控件按状态禁用；这不是高速TourBox策略，后续需要另定积压方案。
- 原生控制/异步加载在Default工作协程，启动/prepare在单一工作线程；View/Surface绑定销毁保持主线程顺序。控制与加载异步等待15秒，超时停止会话且不自动重试；超时不能强杀不返回的同步JNI。
- 每次initialize分配进程内唯一epoch，旧操作绑定原core及token；释放先撤销、取消观察/控制，再解绑并请求库异步关闭。迟到PreparedCore关闭而不绑定；native已经开始的动作不能瞬时撤销。
- 命令参数无效只记录REJECT，原生失败/超时停止播放器；未确认native关闭完成不伪装成已回收，未触及USB隔离。

## 4. 页面、选片、日志

默认显示播放器，顶部可切USB诊断；视频Surface不进入诊断/控件滚动区域。正常离开播放器页请求暂停；若已有操作进行中则撤销/释放该播放器会话，防止隐藏页继续操作；返回需手动初始化的情况有状态说明。应用进入后台释放播放器，保留原USB onStop停止行为，不增加Service、自动重连或自动恢复播放。

SAF选片使用授权content URI，沿用已修正的内建content协议，不重复addStreamProtocol、不猜真实路径或复制整场视频。首次赋新URI后立即刷新按钮，纯UI资格检查纳入测试。取消选片不自动恢复播放；重建仅保留选择的URI/页面，不声称恢复原帧。

**系统选片器/导出器可能使Activity进入后台，因此也会按既有策略停止USB。** 未来联动测试建议先选视频，再手动完成控制器初始化；改变文件/导出后需核对USB状态。普通同Activity标签切换不主动调用USB关闭，不擅自改变007生命周期。

播放器诊断独立保存在app私有files/player-sessions/player-<时间>.txt，后台串行写/导出；屏幕仅最近100条摘要，完整日志用播放器页导出。USB日志仍在原目录和原导出入口，不混写。错误文本可能含所选文件信息，导出后审查再共享，不纳入Git。没有把探针全部250ms连续实验采样搬入主界面；记录命令前/结果/250ms观察、状态变化、原生事件id/错误及释放请求。库诊断Flow不是无损审计，异步关闭结果仍待真机。

## 5. 最终检查及产物

最终离线执行：

```powershell
.\gradlew.bat :player-mpv:testPlayer :app:testInputEvents :app:testConfig94 :app:testUsbAsyncCleanup :app:testUsbGenerations :app:assembleDebug :app:lintDebug :player-mpv:lintDebug --offline --no-daemon --console=plain
```

BUILD SUCCESSFUL in 1m45s，95 tasks（20 executed /75 up-to-date）。新播放器66项纯规则/请求计划/epoch/UI资格断言通过；原四组结果同第一阶段全部通过，共有明确计数716项断言，另94回归没有总计数。不把这些断言当作Android界面/原生帧/USB设备测试。标准:app:check也接入播放器检查。

Lint：app0错误/16警告，player-mpv0错误/0警告；最终Kotlin编译无前一轮多余lateinit告警。app警告为Gradle更新提示1、ChromeOS缺x86_64 1、原备份配置兼容提示1、Activity专用PlayerPanel非XML构造器1、SetTextI18n12。没有抑制/新增baseline；目标arm64、中文编程UI及非XML面板的警告不阻塞本轮，正式产品资源化另行处理。

APK：android/app/build/outputs/apk/debug/app-debug.apk，31,179,529字节，SHA256 `155c0c27cbc4a67ece4441270c035b101d93bc593bbc0e41736283b4ab26f203`。zipalign -c -P16 4与apksigner verify均退出0。JBR25签名工具打印native-access未来限制提示，验证仍成功；未为去提示改系统/JDK。

核对Gradle缓存中三个0.3.0 AAR SHA256均匹配dependency-manifest；APK中10个arm64 SO与已核验native AAR逐字节相同，三个license资产已打包。NDK strip任务提示无法再次strip、按原样打包，未安装NDK或自建native。没有网络或全盘存储权限，现有USB Host feature保留。

固定来源commit b001dd97538e7d3e700df80029fd72be99ceba3f；NOTICE声明包装Apache2.0、原生GPL3-or-later组合。APK已含声明/文本，正式分发的完整对应源码/依赖许可审计尚未完成，不把Android包装许可证当整个APK许可结论；未擅自创建项目根LICENSE。

实际格式检查：git diff --check、7个新增源码逐行空白及USB API边界扫描通过；既有19个受保护文件哈希一致。无原始pcap/TXT、APK、native SO、本机SDK路径、密钥、缓存纳入新增文件。原工作区文档修改、研究文档及用户两份pcapng/Python缓存均保留，未暂存或提交。

## 6. 尚未执行与下一步

本轮未调用ADB、访问手机/TourBox、安装/启动主应用、进行GUI Sync或主应用真机回归，未编译release/验证R8。独立探针的功能试用不等于主应用验收；新的页面/Surface、相对seek、与USB并存的资源/线程行为都待验证。源码未改只能证明协议实现没有变更，不能代替真机排除编译/时序回归。

下一步取得**覆盖安装主应用**授权（此APK与原USB Diagnostics同包名，会更新它；之前独立应用安装授权不扩大为本轮自动安装）。保持数据，不清除旧日志。先测试不接控制器的首次选片、播放/暂停、±5秒、单步/速度/AB、换文件、后台/返回/旋转与重复进入；检查两页切换。然后用户手动做一次USB正常枚举/授权/已验证初始化、按钮/旋转诊断和正常停止回收；此阶段仍不会控制视频。物理拔出USB-001继续Deferred，不因本集成追加修复。确认主应用基本回归后，再安排控制器映射与策略。

回退须同时撤回本轮播放器引用/模块接线及KGP改动，再跑原检查；单独降编译器而保留要求2.4元数据的库不能恢复编译。保留用户已有修改，回退不使用破坏性清理或改Git历史。


## 7. 覆盖安装与用户真机反馈

用户已明确授权覆盖安装主工程APK；实际adb install -r成功，versionCode2/versionName0.2.0-player核对正确，原unlock-experiments目录仍在，未自动启动或发送TourBox命令。独立验证应用保留，用户随后已识别主应用入口。

用户报告“已经测试通过，usb读取和视频播放都工作正常”。按**用户真机反馈**记录：主应用基本视频播放、USB读取在本轮集成后可正常使用，KGP/模块集成没有出现用户观察到的这两项回归。本次没有新导出日志、核对返回值或观察画面，不编造接收次数、按钮/旋转覆盖、全部CDC返回、正常停止回收或同时运行的精确时序。

据此008-B基础集成验收可进入下一阶段。精确逐帧/AB显示边界、长GOP/高帧率/音频压力、后台旋转、关闭完成及release/R8等未覆盖内容仍保留；USB-001继续Deferred。当前USB事件仍仅诊断，不控制播放器，下一步是确认并实现TourBox语义事件→PlayerCommand映射，原协议不重写。

本轮只同步文档并执行git diff --check，未改代码、重跑构建、访问ADB/USB、安装/启动或提交Git。
