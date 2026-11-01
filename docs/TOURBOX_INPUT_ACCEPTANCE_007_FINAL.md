# 007阶段最终归档与验收结论

日期：2026-11-02。状态：**Completed with known limitation**。通过范围为已测试USB协议、语义输入、正常停止和同地址正常重新打开；**不是所有USB生命周期场景均通过**。USB物理拔出自动恢复作为[USB-001](KNOWN_ISSUES.md#usb-001物理拔出后异步usbrequest可能无法确认回收)延期，不阻塞008。

## 资料与证据边界

- [007-T历史验收](TOURBOX_INPUT_ACCEPTANCE_007T.md)保留当时140条对应、未知码及清理问题，不重写历史事实。
- 007-Y日志已由用户完成真机操作后只读导出分析，手机与电脑SHA256一致：`098f50de4509521464442cb25475cdfd924cbdb584bb9b8eb0a5b51db367e9ca`，442行，2026-11-02 00:31:04.893–00:35:06.175。本文不含原件、路径、设备序列号。
- 对应007-Y APK SHA256：`B87854A63166CECF0979D40A142083DF870F1A0C2D269FFB004C0DAB0D85144A`。本归档不修改代码/版本标记，最终无设备重建与协议哈希核对结果另记CHANGELOG。
- 下面行号均为007-Y日志。日志支持字节与语义对应，不证明设备至主机绝无丢包或物理刻度数等于事件数。

## 已通过的协议与输入

三个连接均通过双接口force=true、A1=7、A0=0、固定8字节发送=8、完整26字节回复验证、Windows原样94字节单次发送=94；实际收到按钮与旋转，支持该成功流程在被测设备有效。每连接94字节一次，未发送动态16字节。不能据传输返回值单独称设备解锁成功，也不推断94字节全部字段语义或持久性。

| 项目 | 证据与结果 |
|---|---|
|Knob顺/逆|44→KnobClockwise共16条，04→KnobCounterClockwise共14条|
|Scroll上/下|49→ScrollUp共16条，09→ScrollDown共20条|
|Knob/Scroll按钮|按下/释放分别6对、1对|
|Top/Short按钮|分别6对、5对|
|C1/C2按钮|分别4对、4对|
|Tall/TourBox按钮|分别5对、3对；Tall为按钮，不是长按派生事件|
|回复分流|三次精确26字节回复均未生成控制事件，包含00也未误触发Tall|
|原始与语义|137次0x82正长度读取、212字节；减去3×26字节回复，134个输入字节与134条语义的raw和sequence逐一一致|
|队列及未知码|本轮无UnknownRawCode、输入溢出、消费者故障或CAPTURE FAILED可见记录；重复旋转逐条保留|

各连接的输入数量45、52、37，语义session分别1、4、7，对应open token分别1、2、3；epoch/session不等于open计数。第二轮在新的回复之前已有6个合法输入字节，精确移除流内26字节后仍逐字节对应；不能假设回复总在该轮字节流最前。

第二轮在重新发送8/94字节前已收到旋转，说明正常关闭应用连接不保证设备初始化状态恢复。不能据此删减成功流程或宣称恢复出厂/断电即可清配置。

## 正常停止与连接代次

| 范围 | 结果 |
|---|---|
|137–144行，token1|cancel=true后取得Original，request.close成功，双方读任务退出，接口1/0释放及connection.close成功，normal=true关闭收据|
|149行，token2|同地址正常重开为NormalReopen，独立token，不再“曾用地址即危险复用”|
|290–297行，token2|同样取得原请求并完整释放关闭，normal=true|
|302–309行|正常关闭后实际拔出/插入，token3为ObservationUnknown；不把物理重新枚举当成已证明同一物理代次|

正常停止完整回收及同地址正常重新open通过本轮验证；只有两次正常停止、一次同址正常重开，未达到原计划三轮/完整多轮稳定性覆盖。新会话中未发现旧session事件，停止或最终拔出后没有可见新增语义事件，但仅限本日志窗口。

## 物理拔出与延期项目

422行requestWait=null，423行cancel=false；424、428、430–432行共5次Null；433行双方退出、Quarantined。434行资源匹配、读任务结束、控制结束、唯一wait、请求未确认、设备缺失和日志健康均true，但association=Unknown，故补充wait不执行。439–442行是重复门控记录，不是重新发送或额外USB回收尝试；没有补充成功证据。

结论：隔离机制按设计生效，**物理拔出自动恢复未通过**；不强制关闭未知pending。未确认内核资源泄漏。只有一个空白测试标记，末尾在广播后约96ms，缺少有效5秒结束标记；完整高频压力、多轮资源趋势及全部后台/旋转竞态均不能记为通过。

归档策略及恢复建议统一以[USB-001](KNOWN_ISSUES.md)为准。本阶段结束，不继续研究或修复；未来仅有新的实际问题或资源证据时另行评估。

## 008复用边界

008 Ready to start：Android视频播放器基础功能。复用[TourBoxInputEvents接口](TOURBOX_INPUT_EVENTS.md)，保持USB与播放器解耦；播放器消费者在后台订阅离散事件，调度到播放器线程时再次检查当前会话，结束时关闭订阅。不重写USB协议、不自动初始化、不自动重连，播放器底座仍需重要技术选择确认。


## 归档检查与提交范围

归档最终验证：四组JVM任务全部通过（输入497断言、94字节回归、异步清理20场景/105断言、代次27场景/48断言）；Debug构建通过，49项任务中5执行/44最新；Lint 0错误/7既有警告，签名和格式通过。归档前后22个源码、测试及app构建配置哈希全部一致；CDC/8/94实现及USB bulk/control调用参数与成功检查点一致。APK SHA256保持B87854A63166CECF0979D40A142083DF870F1A0C2D269FFB004C0DAB0D85144A，不纳入提交。apksigner有既有JDK未来native-access提示，当前验证成功。

本次仅归档文档变化；以下代码和测试为此前007阶段已实现的内容，按用户授权一起固化。精确提交清单共24个文件：

- `README.md`
- `ROADMAP.md`
- `CHANGELOG.md`
- `android/app/build.gradle.kts`
- `android/app/src/main/java/io/tabletennisreview/usbdiagnostics/MainActivity.kt`
- `android/app/src/main/java/io/tabletennisreview/usbdiagnostics/TourBoxUsbManager.kt`
- `android/app/src/main/java/io/tabletennisreview/usbdiagnostics/DualEndpointSession.kt`
- `android/app/src/main/java/io/tabletennisreview/usbdiagnostics/UsbAsyncCleanupState.kt`
- `android/app/src/main/java/io/tabletennisreview/usbdiagnostics/UsbConnectionGeneration.kt`
- `android/app/src/main/java/io/tabletennisreview/usbdiagnostics/UsbRequestResources.kt`
- `android/app/src/main/java/io/tabletennisreview/input/TourBoxInputEvent.kt`
- `android/app/src/main/java/io/tabletennisreview/input/TourBoxInputDecoder.kt`
- `android/app/src/main/java/io/tabletennisreview/input/TourBoxInputPipeline.kt`
- `android/app/src/main/java/io/tabletennisreview/input/TourBoxEventStream.kt`
- `android/app/src/testInput/java/TourBoxInputCheck.java`
- `android/app/src/testInput/java/UsbAsyncCleanupCheck.java`
- `android/app/src/testInput/java/UsbGenerationCheck.java`
- `docs/KNOWN_ISSUES.md`
- `docs/TOURBOX_INPUT_ACCEPTANCE_007_FINAL.md`
- `docs/TOURBOX_INPUT_ACCEPTANCE_007T.md`
- `docs/TOURBOX_INPUT_EVENTS.md`
- `docs/USB_ASYNC_DETACH_RECOVERY_PLAN.md`
- `docs/USB_CONNECTION_GENERATION_REVIEW.md`
- `docs/USB_DIAGNOSTICS.md`

排除两份本地原始pcapng、Python缓存、诊断TXT、本机配置、签名密钥、APK和build目录；原件不删除。Git提交身份沿用现有配置，不修改姓名/邮箱，不push。
