# 008-B 主工程播放器集成方案（已批准并实施，基础集成通过用户反馈）

日期：2026-11-02。用户对独立验证结果满意，并明确结束验证、进入下一步。该决定结束独立探针追加测试，不等于所有编码/精度/生命周期已经通过，也尚未等同正式选定主工程播放器底座。该段记录方案提出时的边界；用户后续明确批准执行，现已完成代码与无设备检查，实际结果见[实施记录](PLAYER_MAIN_INTEGRATION.md)。未安装主应用或提交Git。

## 1. 用户已确认的范围

建议采用已经完成隔离编译和目标机功能试用的 **libmpvKt 0.3.0** 作为主工程当前播放器底座；普通Android View，固定版本，不再自行抽取/构建mpv-android的JNI/native。Task1和008-A的命令、精度及USB解耦结论继续复用，自建Linux产物路径保留为历史备选，不是当前建议的前置门槛。

主工程仅通过官方buildscript classpath将KGP升为 **2.4.21**，保留AGP9.2.1、Gradle9.4.1、现有JBR25、JVM17、SDK37/Build-Tools36.0.0/min26。这不是仅修改版本就宣称007无回归，必须执行全部现有检查。

新增一个本地 **:player-mpv** Android library模块作为适配边界，内部引用预编译库，向app提供PlayerController/PlayerCommand/PlayerState，不暴露USB/TourBox；不增加player-api等多层模块，不复制native二进制入Git，不改mpv核心。按实际GPL原生组合保留版本、来源和许可材料，正式分发前完成审计。

沿用 **MainActivity同一宿主**，保留USB诊断页并增加播放器页切换，避免启动另一Activity自动触发现有onStop关闭USB后再误以为输入仍可用。视频Surface不嵌入诊断滚动日志。离开播放器页暂停，应用后台沿用现有USB停止策略并释放播放器；不增加后台Service或自动初始化USB。

以上底座、模块及KGP升级已由用户明确批准执行，当前按本方案实施；主应用真机范围仍需实际验证。

## 2. 首个实现范围

1. 固定Maven仓库group/版本及AAR来源、校验信息，增加适配模块，先验证本地选片、创建、Surface绑定、播放/暂停和退出。
2. 迁移独立探针已经通过功能试用的前后逐帧、五档速度、AB标点/启用/清除及回A，保留失败结果/会话撤销/未知事件ID日志；去掉重复content注册，不盲目复制探针全部Activity。
3. 补齐屏幕快进/快退（建议±5秒，使用核心seek命令），与屏幕其他动作共用控制接口，单独测试命令参数和边界。
4. 选片后按钮刷新保持正确；真实时间/速度/AB状态与错误可见。保留手动初始化、日志导出及不自动播放策略，不把探针的临时大字体/全部实验采样原样作为最终产品界面。
5. TourBox仅预留适配接口，本步不改语义队列、不启用按键映射、不自动执行8/94字节或改USB初始化。下一步再确认控制器键位与播放器积压策略。

## 3. 预计文件范围

| 路径 | 修改目的 |
|---|---|
|android/build.gradle.kts|KGP2.4.21 classpath与同版本Android library插件|
|android/settings.gradle.kts|登记:player-mpv及受限第三方Maven来源|
|android/app/build.gradle.kts|依赖本地播放器模块，保留现有四组检查入口|
|android/player-mpv/build.gradle.kts、src/main/AndroidManifest.xml|模块SDK/JVM/ABI与固定库依赖|
|android/player-mpv/src/main/java/io/tabletennisreview/player/*|纯控制模型、mpv适配、View会话/生命周期、规则及错误处理|
|android/app/src/main/java/io/tabletennisreview/playback/PlayerPanel.kt|屏幕控件、SAF与状态，不解析USB|
|android/app/src/main/java/io/tabletennisreview/usbdiagnostics/MainActivity.kt|增加同Activity页切换与播放器生命周期转发；保留USB连接/回收流程|
|模块/应用无设备测试、依赖来源许可说明|规则/指令/旧会话回归及分发材料|
|README、ROADMAP、CHANGELOG、集成说明|记录真实构建及待验收范围|

不修改TourBoxUsbManager、DualEndpointSession、输入解码/映射、8/94字节常量、CDC参数、端点或隔离策略；如果实现时发现必须触及这些边界，先报告，不扩大本任务。

## 4. 检查与交付门槛

- 主工程原有testInputEvents、testConfig94、testUsbAsyncCleanup、testUsbGenerations全部运行；新增播放器规则/命令参数/会话失效的无设备测试，覆盖新seek入口。
- assembleDebug、lintDebug、git diff --check，记录实际测试数量及Lint；核对ABI、签名、16KB打包对齐和产物依赖，不复制个人SDK路径/日志/pcapng/APK到Git。
- 不宣称模拟检查证明实际首帧/相邻帧/AB边界；主工程与独立探针的UI/生命周期不同，主工程出画仍需独立真机复核。
- 完成代码和构建后报告APK和改动；主应用安装/数据保留范围与用户安排协调，不在文档方案阶段安装、自动启动或执行USB实验；不创建Git提交。

## 5. 独立验证结束状态

用户已满意并结束本阶段。已有基础播放、前后各3次方向/时间变化、五档变速、AB4次回跳与清除证据；原始日志/样本在手机及项目外保留。已知未覆盖的帧号精度、H264/HEVC长GOP/高帧率、音频压力、完整生命周期及native关闭完成保留为后续回归边界，不继续以此阻塞进入主工程。最后日志无最终释放证明，不擅自操作应用补造结论。

## 6. 实施完成状态

先KGP单独升级及007回归，再实现主工程播放器与同Activity页切换。两阶段构建/检查通过，新播放器66断言、原四组回归通过，app Lint0错/16警告、模块0错/0警告；APK静态验证通过，主应用尚未安装。详细文件与记录见[008-B实施](PLAYER_MAIN_INTEGRATION.md)。

后续安装已按授权完成；用户反馈主应用视频播放和USB读取正常，基础集成进入下一阶段。以实施记录§7为最新范围，不将历史“尚未安装”当当前状态或补造详细验收。
