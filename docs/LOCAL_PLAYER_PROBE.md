# 独立本地视频播放验证程序

日期：2026-11-02。用户同意制作项目外本地播放验证程序；初版按代码、无设备测试、构建与静态检查交付；随后用户授权安装并手动测试。当前修复版已按同一独立应用安装授权覆盖更新，未自动启动或复测。不是主工程播放器集成或007工具链升级。

## 1. 工程及界面

- 工程位于项目外 `%TEMP%/tabletennis-libmpv-local-8_1lcd6h`，从上一轮成功compile-only工程的配置建立新目录，原探针保留。临时目录可能被系统清理，后续继续开发前需核实；未把APK、私有路径配置或构建输出加入Git。
- 应用名称：**Libmpv 本地播放验证**；包名 `research.localplayer.libmpv`，与USB Diagnostics不同。
- 沿用隔离验证组合：AGP9.2.1、Gradle9.4.1、KGP2.4.21、现有JBR25、JVM17、compile/target37、min26、Build-Tools36.0.0、libmpvkt-view0.3.0、仅arm64。
- 无Compose、无USB代码/权限、无INTERNET或全盘存储权限，不安装SDK/NDK、新JDK或驱动。
- 主项目Android源码/配置与007仍冻结；USB-001仍Deferred。

界面：选择本地视频、初始化/重新载入、播放、暂停、释放播放器、添加测试标记、复制屏幕日志、导出完整日志；显示播放器状态、时间与seekable。此轮不增加速度、逐帧、AB和TourBox控制按钮。

## 2. 源码与调用路径

独立工程的主要文件：

| 文件 | 用途 |
|---|---|
|app/build.gradle.kts|固定依赖、arm64与JVM17；testSessionGate检查任务|
|app/src/main/AndroidManifest.xml|独立启动入口、应用名称及禁止备份；无USB/网络权限|
|app/src/main/java/research/localplayer/LocalPlayerActivity.kt|系统View、SAF、初始化/加载、播放/暂停、状态和生命周期|
|app/src/main/java/research/localplayer/ProbeLog.kt|串行后台日志写入/导出，磁盘失败可见|
|app/src/main/java/research/localplayer/SessionGate.kt|纯Kotlin会话token及撤销判定|
|app/src/check/java/SessionGateCheck.java|不依赖设备/native的11项会话断言|
|app/src/main/res/xml/data_extraction_rules.xml|排除root/file诊断数据的备份/迁移|
|README.md|独立工程复现及人工验收步骤|

本轮读取固定0.3.0发布源码JAR，复核MpvView.prepare/attach/destroy、PreparedCore、Mpv、MpvEvent、MpvPlaybackState、MpvResult、ContentResolverStreamProvider；未改库源码。来源见[固定版本](https://github.com/yuroyami/libmpvKt/tree/b001dd97538e7d3e700df80029fd72be99ceba3f)。

调用链：用户选择文档 → ACTION_OPEN_DOCUMENT临时/可持久读取授权 → 手动初始化 → 后台Mpv.create → prepare（使用内建content，不重复注册） → 当前会话核对 → 主线程attach → 先订阅事件 → playFile → FILE_LOADED/状态 → 用户点击播放/暂停时setAsync(Pause)。

初始pause=yes，从文件起点暂停。FILE_LOADED、属性变化或命令返回成功不等于首帧及声音已通过实测。持久授权只在系统返回相应flags时申请，失败明确记录；不猜content URI真实路径，不复制整个比赛文件。优先本地provider；管道/云端provider可能不可seek，须根据实际状态判断。

## 3. 生命周期与错误边界

- 初始化每个Activity最多一个后台任务；未返回不接受下一次初始化。初始化异常（含动态库加载失败）记录到界面及文件。
- 每次初始化生成应用token。释放/后台先撤销token，再取消该会话的观察及控制协程，解绑View并调用库destroy；迟到事件不更新当前UI，迟到PreparedCore不绑定而请求关闭。
- 不在onResume自动初始化/恢复播放；返回前台、选片或旋转后，用户手动重新载入。从起点暂停，不把估计时间恢复称为精确帧恢复。
- View/Surface在主线程，创建及初始化在工作线程，播放属性操作使用异步API；原生阻塞/黑屏/设备兼容性仍待真机。
- 事件订阅先于loadfile建立；收到QueueOverflow停止验证并记录。此库的诊断SharedFlow内部有DROP_OLDEST，**不替换007输入队列**，不保证原生事件审计无损。
- MpvView.destroy和PreparedCore.close会启动库的异步关闭，程序日志只写“请求关闭”，不声称底层回收已完成。没有强行关闭另一个会话或强制中断native。
- 初始化若底层长期不返回，本程序没有能够安全强杀native的超时方案；旧Activity可被任务暂时持有，新Activity也需独立防迟到结果，旋转时初始化/关闭重叠是待真机检查项。

## 4. 诊断日志

文件为本独立应用私有 `files/player-probes/player-<时间>.txt`。单独线程串行写入，记录时间、会话、初始化、运行时mpv版本、加载/结束/状态、操作结果及释放请求；不记录每帧位置，也不当作完整native调试trace。日志可能在错误文本中包含选中文档信息，导出后需审核再共享，不自动纳入Git。

屏幕只保留最近80行；“复制屏幕日志”明确是局部，完整应用诊断用“导出完整日志”。导出通过ACTION_CREATE_DOCUMENT，先前写入与导出使用同一队列；发生写入失败会显示状态并拒绝声称完整导出。目录可能积累多次实验记录，本轮不自动删除旧日志；长期使用前需另定保留策略。

打开选片器/导出器可能触发onStop并释放播放器，因此建议测试结束后导出。导出完成提示本身发生在文件复制之后，不在刚导出的快照中。保留旧实验文件，不清除应用数据。

## 5. 真机步骤（尚未执行）

独立应用已按授权更新到0.1.1-content-fix；以下为用户手动复测步骤，不会覆盖USB Diagnostics。无需连接TourBox，不发送任何USB命令。

1. 启动独立应用，选择设备本地短H.264 MP4，先避免云盘provider。
2. 点击“初始化/重新载入”，记录INIT READY、运行时mpv版本、FILE_LOADED及seekable，观察暂停首帧是否可见。
3. 点击播放：确认画面与声音正常、时间推进；点击暂停：确认画面稳定、状态一致。反复操作数次。
4. 点击释放，再手动初始化；选择另一视频测试切换。重新载入从起点暂停属本轮设计。
5. 回桌面再返回，确认不自动播放；手动初始化。旋转后同样手动重载，观察是否有旧回调、崩溃、卡顿或黑屏。
6. 在初始化过程中切后台/旋转，专门检查迟到结果处理。异常先保留日志，不反复盲目重试。
7. 重复进入/释放约10次并加测试标记；这不是内核/原生无泄漏的证明。
8. 结束后导出完整日志，记录素材编码/帧率、操作顺序和实际画面/声音结果；先解决基础播放，再安排逐帧/五档速度/AB。

## 6. 验证结果

实际测试、Lint及APK核验结果见[预编译验证报告§11](PREBUILT_LIBMPV_VALIDATION.md)。无设备断言仅覆盖会话判定，不模拟native、Surface、解码或真正Activity调度。初版真机初始化失败；当前0.1.1已覆盖安装但未运行复测，没有播放器播放通过结论。


## 7. 0.1.1-content-fix：初始化失败修正

用户首次真机测试的独立日志共21行，5次初始化均INVALID_PARAMETER（第6/9/12/16/21行），没有INIT READY、FILE_LOADED；不能据此判断视频解码或权限成功与否。原始日志只读导出到项目外，手机文件保留，未将日志内容/个人路径加入Git。

高可信根因候选：探针在Mpv.create后自定义注册content，而FFmpeg9.0.1已有android_content协议；mpv0.41.0的mpv_stream_cb_add_ro在stream_has_proto匹配时返回INVALID_PARAMETER。发布库JNI直接返回该错误；实际AAR也含android_content二进制字符串。当前旧日志没有阶段/堆栈，不能单凭该证据宣称已完全确定触发点。

依据：[mpv0.41.0注册检查](https://github.com/mpv-player/mpv/blob/v0.41.0/player/client.c#L2199)、[FFmpeg9.0.1内建协议](https://github.com/FFmpeg/FFmpeg/blob/n9.0.1/libavformat/file.c#L682)、[库JNI注册实现](https://github.com/yuroyami/libmpvKt/blob/b001dd97538e7d3e700df80029fd72be99ceba3f/libmpvkt-native/native/jni/stream_cb.cpp#L146)。

经用户同意，独立程序移除重复注册，不修改库/核心/工具链；新增create、prepare-options-and-initialize、attach、load阶段日志及失败堆栈，运行时记录protocol-list和initial-pause；versionCode2、versionName0.1.1-content-fix，界面显示0.1.1。先前接线描述属于旧版本，当前按内建content路径执行。主项目/007保持冻结。

复测时选择同一视频，手动初始化，先核对阶段日志是否到INIT READY、protocol-list是否含content、是否收到FILE_LOADED，再实际观察首帧/播放/声音。若仍失败，按新日志具体阶段定位，不盲目换视频、升级依赖或反复重试。完整APK检查及安装结果另见预编译报告最新小节。


修复交付：11项会话断言、assembleDebug、lintDebug成功（0错误/6警告），签名及16KB对齐通过；0.1.1已通过adb install -r更新成功，版本核对为versionCode2，旧2043字节日志仍在。未自动启动应用，不清除数据。初始化是否恢复及内建content能否正常读取，等待用户手动复测。


## 8. 用户反馈及首次选片按钮修正

用户反馈0.1.1“播放器工作大致正常”，但首次选择本地视频后初始化按钮灰色，第二次选择同一文件才生效。记录为用户实测反馈，不扩大为逐帧/AB、压力或全部生命周期验收通过；本轮未自行访问手机日志来证明这些结果。

源码确认：onActivityResult调用release时refreshButtons仍读取旧selected，之后赋selected=uri并更新状态文字，却遗漏再次刷新。修正为赋值及状态更新后调用refreshButtons；在选片回调和onStart的不同顺序下，都由最新选择和foreground/preparing状态决定启用。没有改自动初始化策略、播放流程、协议/参数或007。

独立版标记为versionCode3、0.1.2-picker-fix，界面0.1.2。用户复测：新打开应用→仅选择一次本地视频→按钮应立即可用→手动初始化/播放；另外取消选片、后台返回、重新选片均不能自动启动播放。构建/安装实际结果见预编译验证报告最新小节。

0.1.2交付结果：11项已有会话断言、debug构建、Lint（0错误/6警告）、签名及16KB对齐通过；已覆盖更新并核对版本3，未自动启动。首次选片按钮恢复仍待用户手动复测，未声称无设备断言覆盖了Android文件选择器回调。


## 9. 0.1.2真机日志核对（2026-11-02）

用户反馈“已正常”。经ADB run-as只读导出0.1.1两份旧日志及0.1.2最新日志到项目外，手机文件保留；未启动、重装、清数据或操作TourBox。最新导出快照113行/11,470字节，SHA256 `ca3d0cf66cb1d65b494cd1b6616a3a3f886d4a88752af34ea6db9b8a73aaedc1`；二次只读读取内容一致。列目录时大小较小，分析严格基于随后稳定导出快照。原始TXT及个人导出路径未加入Git。

以下行号相对最新0.1.2日志：

| 项目 | 证据 | 结论 |
|---|---|---|
|首次选择后可初始化|第1行版本0.1.2，第3行首次选片，第4行开始初始化，第11行READY，第22行FILE_LOADED；期间无第二次选片|结合用户观察，首次选片灰色按钮问题本次未复现|
|初始化及加载|READY第11/51/84行；实际FILE_LOADED第22/63/94行|3次初始化、3次文件加载成功；“等待FILE_LOADED”提示不计为加载事件|
|实际核心/本地协议|第12/52/85行mpv v0.41.0；第13/53/86行protocol-list含content；第15/56/88行内建content加载|本次实际使用预编译核心及内建content路径|
|初始暂停及可seek|第14/54/87行initial-pause=true；第27/68/99行Paused、seekable=true|初始暂停属性与加载后状态一致；可seek报告不等于精确seek已验收|
|播放/暂停|7次PAUSE=false返回Ok，第28/34/39/41/69/100/107行；4次PAUSE=true返回Ok，第32/72/104/110行；5次Playing状态，7次Paused状态|重复控制成功并观察到状态切换；不按命令数量冒称7段实际播放|
|播放结束|第38行Ended；随后第39/41行仅设置pause=false，未见新Playing|片尾不自动回到开头属当前探针边界；重载后再次播放成功|
|释放请求|第43/75/113行，分别为重载前、选新文件、用户释放|3次逻辑撤销/异步关闭请求；不能证明native关闭已全部完成|

最新日志未记录INIT FAILED、LOAD FAILED、控制Fail或QUEUE OVERFLOW。第17/59行出现2条EVENT Unknown，仅记录类名，未记事件ID/载荷，不能确定含义或当成已解释；这不是TourBox UnknownRawCode。没有据此新增代码或自动忽略。日志不是完整native错误trace，不能宣称所有层面错误为零。

验收建议：**基础本地播放在本次样本及用户观察范围通过，首次选片修复通过本次复核。** 画面/声音正常依据用户反馈，不是仅凭FILE_LOADED推断。后台/旋转、关闭完成/泄漏、完整格式矩阵、压力、速度/逐帧/AB、与007集成仍未通过本轮验收。下一步可单独安排复盘功能验证，不据此自动迁移主工程、修USB或提交Git。


## 10. 0.2.0逐帧/慢速/AB验证入口

用户同意继续专项验证，独立应用已增逐帧、五档速度、AB标点/启用/清除和回A；版本code4/0.2.0-review-probe，控件区可滚动。45项无设备断言、构建及Lint通过（0错误/9警告），已覆盖更新并提供带帧号30fps静音MJPEG参考片到手机下载子目录；未自动运行，新增能力等待手动验证。旧节“不含速度/逐帧/AB”是早期版本边界，当前按[专项步骤与实际检查](PLAYER_REVIEW_VALIDATION.md)执行；主项目仍未集成。
