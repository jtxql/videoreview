# mpv-android集成方案（008-A）

日期：2026-11-02。状态：**方案已编写，等待用户确认；未实现播放器。** 主要技术依据为已完成、按用户本次说明已审核通过的[Task1源码研究报告](MPV_RESEARCH.md)。研究报告审核通过不等于已确认具体模块划分、native构建环境或已通过逐帧真机验收。本方案只处理该报告留下的本项目集成问题，不重新选播放器、不修改Task1报告历史内容。

证据标签：**源码已确认**表示Task1固定源码及本轮定向复核；**Task1推断**表示研究提出的可行性判断；**008建议**表示待确认设计；**待验证**表示编译/目标设备/性能或依赖尚无实际结果。007 USB通信、事件映射及USB-001 Deferred均原样保留。

## 1. 继承的版本与本轮复核范围

- mpv-android：`255901dd586fa750000fa0075f69d6ffdd26cd9c`。使用Task1原研究副本，本轮确认HEAD一致、工作区未改；没有clone、fetch或运行下载/构建脚本。
- Task1补充mpv核心：`48fca90e8ce5011d0a4c689178ad739329ea253d`。它是独立阅读快照，仍**不是已确认被Android native产物链接的版本**；本轮未重新研究核心。
- 本项目基线：007归档提交`4275e6c`，有用户保留的两份pcapng及Python缓存未跟踪，不读取/改写它们。
- 定向复核MPVActivity、MPVView、BaseMPVView、MPVLib、JNI main/property/render/event及其必要头文件/工具文件，以及Android.mk、Application.mk、Gradle、构建脚本与CI。没有进行大范围新选型或新协议研究。

固定源码：[Android树](https://github.com/mpv-android/mpv-android/tree/255901dd586fa750000fa0075f69d6ffdd26cd9c)、[核心阅读快照](https://github.com/mpv-player/mpv/tree/48fca90e8ce5011d0a4c689178ad739329ea253d)。以下上游路径均相对前者，本项目路径明确带android/或docs/。

## 2. Task1成果复用表

| Task1已有结论 | 报告章节/固定源码位置 | 008直接复用方式 | 尚需补充验证 |
|---|---|---|---|
|完整应用，不是现成AAR|Task1 §1、§8；README Library段|延续libmpv方向，从重要桥接层抽取，不写一个不存在的Maven坐标|本地Android library模块装配与native交付|
|MPVActivity承担UI、选片与生命周期|§2、§4；MPVActivity.kt:254、305–307、345–376、796、1123–1140|参考initialize/observer/remove/destroy顺序和URI处理，不整体复制Activity/Service/Manifest|本项目同Activity宿主及SAF授权、后台策略|
|MPVView有pause/time/speed/FPS封装|§3、§4；MPVView.kt:278–303、346–355|复用属性名与命令语义，在自己的adapter封装；不复制偏好、资源和!!非空假设|实际属性可用性、速度回读、硬解行为|
|BaseMPVView是最小SurfaceView边界|§2、§4；BaseMPVView.kt:17、45、62、83、98–106|抽取为Mpv视频View参考，继承实现小范围初始化/观察|Surface解绑FIXME、销毁顺序、重建与首帧|
|MPVLib通用external与observer足够|§2、§4、§5；MPVLib.kt:18–39、44–99、EventObserver|作为JNI桥接基础，保留原package/JNI名称；应用只访问PlayerController|动态库加载、缺库反馈、release混淆规则|
|main.cpp单例与同步命令|§4；main.cpp:55、71、84–95、99–113|单实例MpvSession；使用command字符串数组，不拼shell或模拟键盘|无返回码、重复初始化及销毁竞态|
|property.cpp已有属性读写|§4、§5；property.cpp:25、39、54、118|使用Boolean/Double/String及setOptionString返回码，处理null|setter多数void，需日志/回读而非假定成功|
|render.cpp绑定Android Surface|§2、§4；render.cpp:16–36|复用wid绑定、global ref与解绑模型；不是自写OpenGL解码器|Surface生命周期与持有引用是否完整释放|
|event.cpp独立pthread事件循环|§2、§4；event.cpp:71–118|observer只投递短状态通知到会话/UI线程|事件无业务请求ID、回调锁、退出join、错误细节|
|本地视频与content URI已有入口|§3、§4、§7；MPVActivity.translateContentUri:1123–1140|用系统SAF授权，先验证可seek本地provider；参考URI直传策略|实际FFmpeg是否支持该URI、provider能否随机定位|
|前后逐帧核心有命令|§3、§4、§7；核心add_step_frame，frame-step/frame-back-step|适配层直接发命令，新增屏幕按钮；不自行加减1/fps|后退耗时、CFR/VFR、长GOP/硬软解实际画面|
|speed范围覆盖需求，原UI最低0.2x|§3、§4、§5|自己提供0.1/0.25/0.5/0.75/1.0预设，不整搬SpeedPicker|音画同步、音频策略、高帧率负载|
|AB属性可调用但未有专用UI|§3、§5、§7|使用ab-loop-a/b，自己维护A/B标点、清除及文件切换|真实首尾帧、越界seek、间隙，不保证无缝/帧闭区间|
|时间显示必须用Double，FPS非帧号|§3–§5；MPVView.kt:176/177、282、302|观察time-pos Double，回读time-pos/full及duration/full；估算FPS只诊断|属性缺失与时间显示误差；估算FPS不能充当帧计数|
|native与Gradle分开构建|§2、§8、§9；Android.mk、buildscripts/README.md|独立提供完整arm64 native bundle，再装配本项目|实际core/FFmpeg/NDK版本、全部依赖、Windows打包|
|USB→事件→策略→播放器应解耦|§6、§8；现007 TourBoxInputEvents|直接消费007 subscribe/isCurrent/withCurrent，USB不依赖mpv|未来映射与播放器并发；不重做USB私有协议|
|许可/真实性风险未完成|§7、§9；LICENSE及真实构建参数|保留来源、修改记录、依赖清单与许可证材料|不能只看Android MIT就宣布整个APK纯MIT|

### “直接复用”的准确范围

MPVLib的API定义、BaseMPVView的基础框架及JNI目录适合受控抽取；**不是无需集成即可直接运行**。MPVActivity和MPVView适合复用流程/属性语义，不是本项目应整类原样导入：它们引用上游资源、偏好、文件浏览器、后台服务及AndroidX UI。

本轮定位细化：Task1表述的FPS getter，在此固定源码中准确名为`estimatedVfFps`（MPVView.kt:302），不是名为fps的字段；语义仍是estimated-vf-fps，不能视为精确帧号。

## 3. 推荐的最小集成方式（待确认）

建议：**现有:app保留007，新增一个本地Android library模块:player-mpv，抽取MPVLib/BaseMPVView/JNI边界；不fork整个mpv-android应用，不修改mpv核心。** 本地模块是我们创建的边界，不是上游已经发布的AAR。只增加一个模块，不先拆出player-api、USB库等多个Gradle模块。

模块内的`PlayerCommand/PlayerState/PlayerController`不引用USB或TourBox类型；MpvPlayerController是具体实现。app中的屏幕控件和TourBoxPlayerBinding都提交同一PlayerCommand。未来需要换内部实现时保持调用接口稳定，本轮不重新比较其他播放器。

依赖方向：

```text
007 TourBoxInputEvents -> app/TourBoxPlayerBinding --+
                                                   +-> PlayerController -> MpvSession -> MPVLib -> libmpv
app/屏幕按钮 --------------------------------------+
mpv native event -> 轻量observer -> PlayerState -> UI
```

模块对app无反向依赖，USB层不解析播放器命令，播放器不读取USB数据或申请USB权限。初始一个播放器实例；不能假定上游全局g_mpv支持双播放器。模块边界不是进程隔离：native exit/崩溃会影响同进程的诊断和007会话，本方案不默认增加独立进程。

### UI宿主的项目特有约束

当前MainActivity.onStop会usb.close，onDestroy会shutdown。因此不能把现有Manager/订阅留在旧Activity，启动另一个PlayerActivity后还假定控制器继续有效。

**008建议**：保持同一个MainActivity宿主，增加独立PlayerPanel，与既有诊断ScrollView放在宿主FrameLayout中切换；不把视频Surface塞进可滚动日志区。007 Manager、手动初始化和接收器仍由原宿主管理；播放器panel只拿只读事件接口。诊断页仍能用于手动完成初始化。跨页可暂停/释放播放器，但不偷偷改变USB策略。

这是界面接线建议，不是重构007。008-B最小入口仅改宿主布局/导航及增加播放器生命周期转发；不改USB连接/回收/映射源码。若用户希望独立Activity/后台播放，则需另审会话宿主方案，不能以本计划默认批准Service或改变007 onStop。

## 4. libmpv命令、状态与控制入口

以下是方案伪代码，不是新增实现；须在native、文件、Surface及会话就绪后执行。

| PlayerCommand | mpv调用 | 成功依据/注意 |
|---|---|---|
|Load(media)|command(["loadfile", uriOrPath, "replace"])|FILE_LOADED、视频输出及实际首帧；不是JNI返回即成功|
|SetPaused(true/false)|setPropertyBoolean("pause", value)|pause属性回读；Toggle在统一owner上读取/更新，避免两入口竞态|
|SeekRelative(±5秒)|command(["seek", delta, "relative+exact"])|时间/画面与seek事件；不是倒放|
|StepForward|command(["frame-step"])|暂停状态和实际下一帧，不能假定每个请求都完成|
|StepBackward|command(["frame-back-step"])|核心的反向精确seek尝试，可能慢或失败|
|SetSpeed(v)|setPropertyDouble("speed", v)|0.1/0.25/0.5/0.75/1.0，回读实际值|
|SetLoop(a,b)|先清旧B，再写ab-loop-a和ab-loop-b|Double秒，0≤a<b且文件匹配，检查两个端点与实际循环|
|ClearLoop|setPropertyString("ab-loop-a", "no")及B同样清除|换文件/退出时清两端点与UI标记|

观察pause、time-pos(Double)、duration/full(Double)、speed(Double)、已知端点以及FILE_LOADED/SEEK/PLAYBACK_RESTART/END_FILE等事件。先核对所用实际核心支持的属性，不可用时显示未知/错误，不用0伪装有效值。时间用于诊断/标点，显示毫秒不证明帧级精度。观察事件只是完成线索，现有event.cpp不提供应用命令的可靠一一完成ID。

BaseMPVView.playFile只是暂存“下一次surfaceCreated加载”的文件，不是Surface已就绪后立即换文件的方法；后续换片须经session调度loadfile，避免双重加载。

### JNI错误边界

- **源码已确认**：command和多数setter返回void；main.cpp丢弃mpv_command错误码。setOptionString返回Int，getter失败通常null。不能在Kotlin仅把command声明改成Int而继续使用旧libplayer.so，这会造成JNI ABI不一致。
- **本轮补充源码确认**：log.cpp:5–8的die调用exit(1)；create/init及未初始化检查可能走此路径。普通try/catch不能拦截native进程退出。应用会话状态必须阻止重复create、释放后调用；初始化失败反馈仍需检查。
- **源码已确认**：event.cpp的普通事件回调仅传eventId；当前桥接不会把END_FILE结构中的完整reason/error交给Kotlin。不能在UI凭空读取尚未桥接的数据，可靠错误说明需补事件载荷或明确以日志为有限证据。
- **008建议**：首轮参考接口可用日志/状态回读区分Submitted与Observed，但不伪造Applied。若需要可恢复初始化和命令错误，最小修改限于JNI桥接：明确返回/Java异常、创建/初始化/事件线程是否启动的标志及错误事件，不改mpv核心。Kotlin与libplayer必须一起重建，变更单独可审查；不得把改native ABI混在不明预编译库中。

## 5. 屏幕与TourBox统一控制

屏幕只生成语义PlayerCommand，不直接调用MPVLib；TourBoxPlayerBinding把已验证的输入事件翻译到同一入口。ButtonUp、UnknownRawCode默认仅诊断，不产生第二次动作；不把USB重新伪装成KeyEvent再走上游onKey，避免双重触发。

候选默认映射如下，**需用户确认，不是Task1已经确定的键位**：

| 007事件 | 候选动作 |
|---|---|
|KnobClockwise / CounterClockwise|前进一帧 / 后退一帧|
|ScrollUp / Down|快进5秒 / 快退5秒|
|ButtonDown(KNOB)|播放/暂停|
|ButtonDown(SCROLL)|依次切换五档目标速度|
|ButtonDown(TOP) / SHORT|记A / 记B|
|ButtonDown(C1) / C2|清除AB / 恢复1x|
|ButtonDown(TOURBOX)|显示/隐藏播放器控件（仅UI动作）|

每个旋转字节仍是一个007事件，不改其意义、角度假设或1024队列。播放器策略另设显式有界待办（候选上限128、一项逐帧/seek进行中）；积压/拒绝计数要可见，不暗中吞掉输入，也不把StateFlow最后值当离散事件。最小验证先慢速单步，不将高速旋转等价为已经完成N帧；加速/合并策略如需启用须另确认，不能反向修改007解码器。

### 线程及旧事件

**008-B建议采用单一主线程会话owner**：UI命令通过Handler排队、Surface生命周期回调按上游同步顺序执行；native事件线程仅复制小状态并post，绝不等待主线程，防止destroy的pthread_join与回调互等。不得从USB接收线程调用mpv，不在observer锁内做重活。该选择尽量保持上游Surface顺序，不是声称同步JNI一定不阻塞；命令耗时必须测量，发现明显UI阻塞后暂停性能验收，另审JNI异步命令/专属线程方案，不盲目把Surface解绑异步化。

TourBox后台回调只排队；动作执行前核对当前USB事件及playerGeneration/fileGeneration。withCurrent只包短的“核验/登记开始”步骤，不把可能阻塞的同步JNI或销毁join放进007 deliveryGate；已登记开始的外部动作不能强行中断，后续释放须等该播放器动作返回。USB断开使未开始事件失效；新播放器/新文件不接收旧目标任务。此处是候选接线策略，须用可控调度测试验证取消边界，不宣称物理断开瞬间可撤销正在native中执行的命令。

屏幕输入不依赖USB是否已连接，USB-001隔离只禁用控制器通道，不应阻止视频继续由屏幕控制。本计划不修复USB-001，也不启用自动重连/初始化。

## 6. 初始化、暂停、恢复、Surface及释放

候选状态：Unloaded/LoadingNative/Created/Initialized/WaitingSurface/LoadingMedia/Ready/Paused/Releasing/Released/Error，另有playerGeneration与fileGeneration；用明确状态替代多个无约束布尔值。所有native工作限定一个owner，create与destroy各有单次门控。

1. 用户进入播放器页后才加载native；缺SO/ABI不符应显示错误，不能让诊断页启动就触发System.loadLibrary。原MPVLib对象初始化失败可能无法在当前进程重试，不能靠无限重试解决。
2. 建立仅播放器使用的配置/缓存目录；不导入用户外部mpv.conf或任意输入脚本，保证试验可复现。参照MPVView设置Android GLES、音频及硬解选项；先gpu、mediacodec/mediacodec-copy，保留显式软解no对照。参数是参考值，实际库是否支持需检查返回与日志。
3. 按Base流程create→pre-init options→init→post-init→Surface callback/属性观察。全局g_mpv只由当前会话拥有；先注册轻量observer，日志/事件带代次，未初始化不调用命令。
4. Surface有效后attach并加载首文件；尺寸变更传android-surface-size。Surface销毁必须遵守绑定所有权，暂停/停止新命令；不在回调结束后才异步使用已失效Surface。
5. 后台默认暂停，不做后台播放、PiP或上游BackgroundPlaybackService；恢复时仅在文件/Surface/native仍有效时恢复UI。008-B优先保持暂停，用户主动播放；不因onResume自动发送USB初始化。
6. 页面最终退出：先撤销待办/输入订阅及代次，停止发新命令，协调当前已开始的动作，移除observer；在native仍有效时正确处理Surface解绑，最后destroy并等待原事件线程退出。反复释放幂等，Released之后不得setProperty/command。
7. 旋转/Activity重建先释放旧单例，再创建新会话；只保存URI授权、位置、速度及UI标点等可恢复数据，不保存旧Surface/native引用。008-B换文件默认清AB，避免旧标点污染新文件；恢复位置只称时间恢复，不是严格帧恢复。

**待验证**：BaseMPVView.surfaceDestroyed已有VO解绑FIXME；destroy移除callback并不自动证明render.cpp保存的Surface global ref已释放。不能直接加一个不分状态的detach导致重复解绑；要核对“Surface是否已绑定、是否已解绑、VO是否仍使用”的状态及实际设备行为。此风险要在B的加载/退出/旋转回归早期暴露，不先承诺无泄漏或所有后台场景通过。

## 7. 本地文件与权限

008-B用ACTION_OPEN_DOCUMENT、video/*和临时/可持久授权（以系统实际授予flags为准）；优先设备本地、可seek的视频provider，不引入上游allstorage flavor或全盘访问权限。

参考MPVActivity.translateContentUri：尝试合法的ContentResolver访问，最终可将content URI传给FFmpeg；但实际产物必须包含相应Android支持，授权和provider随机定位都要实测，不能把URI转换成猜测的真实路径。先验证本机本地视频，不把云盘provider全部兼容作为MVP门槛。

若provider不可seek或当前native不支持URI，显示明确原因；经用户选择后可评估复制到应用私有缓存再播放，必须提示大文件空间/耗时并检查容量。不能悄悄复制整场比赛，不能只把URI.toString替换成不存在的文件路径。FD协议及FD生命周期需要独立核实，不默认增加本报告未验证的fd://适配。

## 8. Android工程与原生依赖交付

### 当前工具链与最小模块

本项目已验证AGP9.2.1、Gradle9.4.1、内置Kotlin2.2.10、JBR25、compile/target37、min26、Build-Tools36.0.0。上游固定版用SDK36/min23、Kotlin变量2.2.21和自身UI依赖。**008建议保留本项目工具链**，library用同AGP版本，不复制上游整个根Gradle/Kotlin插件/flavor。可用性仍要实际构建，Task1上游Java17 CI配置不等于本项目必须切换JDK。

首个目标仅arm64-v8a；不要要求当前x86模拟器能加载arm64。SDK/NDK机器路径放忽略的本机配置，不提交绝对路径、APK、native构建目录或大体积SO。

### 原生产物是008-B的先决门槛

源码已确认：MPVLib先load mpv、再player；Android.mk导入libmpv及FFmpeg共享库，并以main/render/log/jni_utils/property/event/thumbnail.cpp等完整闭包构建libplayer。不能只复制四个cpp而丢头文件/工具/缩略图符号。

buildscripts/scripts/mpv-android.sh先对prefix调用ndk-build，再Gradle；Application.mk为c++_shared、声明flexible page sizes，Android.mk按PREFIX64等变量找预构建依赖。所有实际DT_NEEDED库必须完整交付，包括需要的libc++_shared及FFmpeg/其他动态依赖；不能只放两个入口SO。ELF ABI、符号依赖、最低API及页面/打包对齐需检查，声明flexible不等于每个依赖已验证。

本轮确认研究副本没有buildscripts/prefix和app/src/main/libs产物。`app/src/main/jniLibs`在Git树是指向libs/的符号链接，本Windows研究副本为5字节链接占位文本，**不是已有可用SO目录**。新模块应使用真实jniLibs目录，不直接复制占位文件，也不擅自修改全局Git符号链接配置。

上游构建文档明确Linux/macOS支持，Windows/WSL不支持。**建议经用户同意使用受控Linux原生构建环境，固定依赖后导出完整arm64 bundle；Windows仅进行Kotlin/Android装配与验证。** 这是开发时产物构建，不是手机运行时依赖电脑。当前没有为本项目准备或验证该环境；不得将此步骤写成已完成。

不能直接运行download.sh：它可安装SDK/NDK与系统包。需要先说明环境、NDK及依赖下载内容/用途，取得授权；本轮没有下载、安装或执行。也不把从陌生APK抽出的SO或不明AAR作为默认交付；官方APK不是本项目可直接使用的开发SDK。

### 可复现与许可门槛

原构建配置NDK r30/30.0.16248370、CI FFmpeg n9.0.2只是源码声明。ci.sh:67下载mpv master，说明固定Android commit仍不足以固定实际core。008-B须交付NativeManifest：Android/JNI来源commit及patch、实际mpv/FFmpeg/全部依赖版本与SHA、NDK/编译器、ABI/minAPI、构建选项、SO SHA256及许可证材料；运行版本与manifest核对后，才解释帧/速度测试结果。

上游Android代码LICENSE为MIT，抽取文件需保留许可与来源；ffmpeg.sh明确启用gpl/version3。因此最终APK不能只贴MIT就认定全依赖许可完成；正式分发前按实际构建审计，008-A没有完成发布许可判断。

可采用“Linux构建完整库，Windows用本地jniLibs打包”的最小交付方式；本机是否另需NDK参与strip/打包取决Gradle配置，不能未经构建就承诺完全无需NDK。不得为了首次编译盲目升级AGP、改NDK或重写上游构建链。

## 9. Task1已排除/未排除的路线

Task1已明确否定或限制：把mpv-android当现成AAR；只Gradle就生成全部native库；把上游Windows/WSL构建当受支持方案；为已有能力先改mpv核心；把旋转当必然Android KeyEvent；使用Root或电脑作运行时中介；用time+1/fps冒充精确逐帧；用命令返回或浮点时间戳证明画面精度。

**不要扩大Task1结论**：完整fork与抽取桥接当时是两种待取舍方案，并未排除完整fork；本报告基于现有007选择推荐抽取。Task1没有完成所有播放器比较，也没有排除所有其他库，本轮不补做大范围选型。USB不可用的旧风险已经由007按验收范围更新，不应继续写成“尚不知能否通信”；USB-001仍Deferred。

## 10. 008-B最小可运行实施步骤

008-B的目标是“同一app中打开授权本地视频、实际出画面/声音、播放暂停、退出释放”，**不以完整逐帧/AB精度或TourBox联动作为首个可运行门槛**。完整008功能按后续小步追加，不偷换为只存在空UI或只有上游APK可运行。

| 步骤 | 具体工作 | 可审查产物/验收 |
|---|---|---|
|B0 方案与native路径确认|确认本地:player-mpv、同Activity宿主、单实例/主线程owner及Linux产物路径；说明下载后另取授权|用户确认；若无可追溯native环境/产物则标受阻，不默换播放器|
|B1 固定并验证native bundle|固定实际依赖，构建完整arm64 libmpv/libplayer闭包，必要JNI安全反馈补丁随产物一起重建|NativeManifest、SO hashes、依赖/ABI检查、来源许可，不能只说Gradle Sync成功|
|B2 最小模块装配|加入Android library、MPVLib/Base及完整JNI源码来源，保留is.xyz.mpv JNI类名和keep规则|assembleDebug能打包本项目APK；缺库/错误ABI有明确失败，不触碰007实现|
|B3 选片与首次出画|同宿主PlayerPanel、系统SAF选择、本地可seek样本、单实例初始化与Surface绑定|实际视频首帧/音频/FILE_LOADED；未安装真机前不得称出画成功|
|B4 播放暂停及状态|按钮统一走PlayerController；时间Double、pause回读、无文件/加载中/错误态|反复播放暂停可见状态一致，未知属性不崩溃|
|B5 生命周期与错误|暂停/后台/返回、Surface销毁重建、重复进入、错误文件/权限撤销、缺库及初始化失败|无重复create/destroy或释放后JNI；上游FIXME若复现单列限制，不修改USB-001|
|B6 验证与交付|纯控制器/命令映射测试、四组007回归、debug/lint、产物哈希；安装另获准|报告实际结果、APK路径及未验证事项，无自动USB动作/自动提交|

首个MVP完成后，另行安排008的小步：速度预设/精确时间seek→单步按钮→AB按钮与边界→确认键位后接007 mapper→高频及编码矩阵。这样能把“JNI集成是否能运行”和“逐帧体验是否合格”分开诊断。

### 建议新增/修改文件（此轮未创建）

| 文件/目录 | 阶段及用途 |
|---|---|
|android/settings.gradle.kts、android/build.gradle.kts|B2登记本地library与同版本Android library插件|
|android/app/build.gradle.kts|B2依赖:player-mpv，保留007测试任务|
|android/player-mpv/build.gradle.kts、src/main/AndroidManifest.xml|B2库边界、SDK/ABI、native打包|
|android/player-mpv/src/main/java/is/xyz/mpv/{MPVLib,BaseMPVView}.kt|抽取桥接，保留原JNI名称|
|android/player-mpv/src/main/jni/*|完整JNI源码/头文件及构建说明；若改桥接须可追溯patch|
|android/player-mpv/src/main/java/io/tabletennisreview/player/*|PlayerCommand/State/Controller、MpvSession/MpvPlayerController、ReviewMpvView|
|android/player-mpv/consumer-rules.pro|JNI callback/class保留，不能假定debug通过即release混淆安全|
|android/app/src/main/java/io/tabletennisreview/playback/PlayerPanel.kt|B3屏幕UI与状态，不解析USB|
|android/app/src/main/java/io/tabletennisreview/usbdiagnostics/MainActivity.kt|仅宿主导航/布局和播放器生命周期转发；不是改USB策略|
|android/app/src/main/java/io/tabletennisreview/playback/TourBoxPlayerBinding.kt|后续008映射步骤，B首个出画MVP可暂不创建|
|本地jniLibs/arm64-v8a/*.so及.gitignore相应路径|B1/B2真实native目录；二进制/构建产物不提交Git|
|第三方LICENSE/来源说明、docs/MPV_NATIVE_DEPENDENCIES.md|B1来源、版本、哈希和选项；不是本轮声称已有的产物|
|控制器/代次/映射的无设备测试及构建检查入口|B4/B6；复用现有测试习惯，不默认引入大测试库|

## 11. 逐帧、AB及低速的验证设计

### frame-step / frame-back-step

用带可见帧号/时间戳的已知样本，先30/60fps CFR H.264短GOP、静音暂停单步；记录命令序号、起始可见帧、目标相邻帧、实际帧、time-pos Double和延迟。逐一验证前/后、首帧/末帧、反复切向、文件切换后旧命令失效。

再测HEVC、长GOP/B帧、VFR及120/240fps，硬解与软解对照。估算FPS只作样本诊断，不用来计算VFR下一帧。最后才测连续旋转及队列积压，分别报告提交/拒绝/观察到的完成，不以发出N条命令宣布前进N帧。命令同步返回、SEEK/PLAYBACK_RESTART也不能单独证明画面目标正确。

008-B不预设“任何素材都一帧不差/低于某延迟”的通过结论；延迟目标由用户结合复盘手感确认。若反向能力不足，先记录样本与可重复证据，不能直接宣布要改mpv核心。

### AB循环

选可见帧号和已知PTS样本：同GOP/跨GOP标点、A≈0/B≈结尾、短区间、边播边改端点、清除/切文件、主动seek越过B。记录实际首尾帧、漏/重帧、回跳延迟及音频间隙，比较硬软解、各目标速度。A>=B、无有效时间或未知duration时明确禁止/提示，不自动猜端点。Task1指出主动seek越过B不必触发循环，要按实际核心文档解释，不把它误判为一定实现错误。

### 速度与高帧率

分别0.1/0.25/0.5/0.75/1.0，检查属性回读、视觉速度、音频/同步、持续播放掉帧及温升；音频是否静音/变调需产品确认，不能以静音成功替代音频兼容通过。验证源文件真实PTS，区分高采集帧率与导出时间轴；120/240fps素材不等于屏幕1x能显示全部帧。所有结果按样本、解码模式、native版本记录。

## 12. 工作量、关键风险与待确认项

粗估是工程量区间，不是工期承诺：**可追溯native bundle就绪后，008-B最小选片/出画/暂停/释放约3–5个开发日**（抽取装配约1日、选片/UI约1日、生命周期与错误回归约1–3日）。首次Linux环境/全native依赖构建另计，具备可用环境时先预留约2–5日，若依赖/工具链不匹配或无环境则不能给可靠上限；下载、审批、真机操作等待不包含在估算内。完整逐帧/AB/低速/控制器压力验收不包含在MVP估计，可另预留数日并由样本结果调整。

关键未解决风险：

1. 本项目没有可追溯native产物或已验证的Linux构建环境；不能声称现有Windows工具链单独够用。
2. 实际core版本未固定，CI使用master；API/属性与Task1快照可能不同。
3. Surface解绑FIXME、全局单例、native exit及同步命令阻塞，需要最早的生命周期/错误验证。
4. content URI可seek性及FFmpeg Android支持、目标手机硬解与高帧率温升。
5. 反向步进速度/准确性、AB实际显示边界、低速音频，均没有本项目真机结果。
6. JNI包名/回调/混淆、完整动态库依赖与许可材料；这些不能由复制Kotlin类解决。
7. 007 USB-001维持Deferred；屏幕播放器应可独立使用，不扩大USB验收范围。

待用户确认：推荐抽取方式与单一library、同Activity宿主、B的最小出画范围、native构建/产物来源。具体TourBox键位、逐帧性能目标和低速音频策略可在后续功能步骤确认；本轮不擅自定稿。

## 13. 本轮实际检查与停止点

完整阅读Task1报告、AGENTS/路线图/变更记录及007接口；只读复核原固定源码与本项目配置。确认研究副本commit、无改动及native产物缺失/链接占位状态；未运行下载或构建脚本，未新增Android模块、播放器或映射代码，未安装APK、访问ADB/USB、提交Git。

只新增本文并更新ROADMAP、CHANGELOG。实际检查：13节结构、Task1成果复用表、本地链接和逐行空白检查通过；git diff --check通过；45个Android源码/配置及Task1、007接口文档的前后SHA256一致，原研究副本git status无改动。仅两份跟踪文档修改和一份新方案文档；已有两份pcapng/Python缓存保留。未运行编译、测试或性能验证。等待用户确认后再安排008-B；不把本方案当已通过编译或真机验证的实现。


## 14. 后续已批准路径（2026-11-02）

本文保留008-A自建native建议的历史证据；用户后续验证并批准改用固定libmpvKt0.3.0预编译库。主工程KGP2.4.21、:player-mpv及同Activity页面已实现并通过无设备检查，不再按本文Linux自建产物作为当前前置门槛。准确实施与未安装/真机边界见[008-B记录](PLAYER_MAIN_INTEGRATION.md)，不改写Task1研究快照或宣称精度全通过。
