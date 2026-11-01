# TourBox USB Diagnostics

阶段2A检查点，2026-11-01。当前含手动单/双IN读取、三个CDC标准请求和一次固定候选OUT实验，不实现事件解析或播放器。各006B–006J章节是演进历史，早期只读限制、未实测状态及APK哈希仅对应当时版本。当前实测统一见 [研究总结](TOURBOX_RESEARCH.md#阶段-2a-当前检查点任务-006l)，下一阶段见 [抓包计划](USB_CAPTURE_PLAN.md)。

## 工具链依据

- [AGP 9.2 官方兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)：最高支持 API 37，Gradle 9.4.1，Build-Tools 36.0.0，JDK 至少 17，默认 KGP 2.2.10。采用修订版 AGP 9.2.1。
- [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)：JDK 25 从 Gradle 9.1 起支持运行。
- [内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)：AGP 9 默认启用，不重复应用 kotlin-android 插件；Gradle Kotlin DSL 自身的 Kotlin 版本与应用编译器不是同一概念。

用户已确认工具链并授权 Gradle/Maven 构建下载。仅对子进程设置 JAVA_HOME，不改变系统/用户环境变量。SDK 路径仅放在 local.properties（已忽略）。未安装 SDK/NDK。

## 编译与打开

Android Studio 打开仓库内 `android/`，在 Gradle JDK 选项选择已安装的 Studio JBR。命令行使用兼容 JDK 运行 `android/gradlew.bat`。本机 PowerShell 示例（环境变量仅本终端生效）：

```powershell
$env:JAVA_HOME = '<Android Studio安装目录>\jbr'
Set-Location '<项目目录>\android'
.\gradlew.bat help --console=plain
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

实际验证结果：

- 已校验 Gradle 9.4.1 官方发行包 SHA-256，使用该发行包在现有 JBR 25.0.4 下运行命令行配置及构建。标准 Wrapper 由同版本 Gradle 的 wrapper 任务生成并包含 distributionSha256Sum。
- AGP 9.2.1 / 内置 Kotlin 2.2.10 实际依赖已解析；最终离线运行 `help :app:assembleDebug :app:lintDebug --offline --no-daemon --console=plain` 成功。说明已缓存的所选工具链可完成配置和 debug 构建，不代表 Android Studio GUI Sync 已通过。
- Lint 首轮发现 local.properties 盘符冒号未转义，已改为转义格式；也补充图标、备份规则和状态字符串资源。最终 lint 为 0 错误、2 条警告：固定的 Gradle 9.4.1 有更新版本提示；minSdk 26 下未另设 fullBackupContent 的兼容提示（已设置 allowBackup=false，且应用不持久化诊断数据）。未屏蔽检查或创建 baseline。
- 曾遇到直接 HTTP 查询 AGP 返回 404、首轮依赖等待、离线缓存不全和一次 PowerShell 参数拆分错误；实际在线解析后同版本构建成功，未因此升级或更换工具链。首次挂起构建由人工停止；不把其 daemon disappeared 记录误认为 JDK 崩溃。
- Android Studio GUI Sync：尝试桌面助手，但重试和重置均因 sandbox helper 初始化错误退出，未能进入 IDE 操作。请手动打开 android/ 并点击 Sync Project with Gradle Files；这属于桌面工具限制，不是已证明的工程 Sync 错误。
- 最终 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，907206 字节；apksigner verify 通过，SHA-256 `50E9974FD74BFB135CCD22BB5EE4B3432A132E7092741BB338E6BF67E6D7331B`。这是 debug 签名，不是发布包。APK/build 目录与本机配置均被 Git 忽略。
- 已检查 Manifest XML、源码无 USB 数据传输调用及 Git 空白格式；未执行设备安装、界面运行、USB 或自动化设备测试。

## 手机验证步骤（未执行）

1. 将 debug APK 传到小米 14 Ultra，经用户操作安装；也可先用 USB 调试/ADB 安装，再断开电脑接控制器。电脑不参与应用运行。
2. 不连接控制器时打开应用，应显示“未检测到 USB 设备”，刷新不崩溃。
3. 使用支持数据的 USB-C 连接线或合适 OTG 转接连接 TourBox Lite。确认手机进入 Host 并供电，点击刷新；记录 VID/PID、设备及每个接口和端点描述符，不预设其他型号 ID。
4. 选择实际设备，点击申请权限；分别检查允许与拒绝状态。允许后不会自动连接，仍需点击连接。
5. 默认选择“仅 openDevice”，点击连接，记录返回结果。然后根据描述符手动选择一个需要验证的接口，再点连接；采用 force=false，失败时不强行分离内核驱动。不能从 claim 失败推定唯一原因，系统可能只返回 false。
6. 点击关闭，检查释放日志；连接时拔出、切到后台、旋转屏幕再返回，确认关闭并可再次手动连接。断连后权限可能需要重申。
7. 记录系统版本、手机型号、线缆/供电、所有诊断结果及界面截图。没有设备时不声称接口或权限已验证。

界面日志最多 200 条，只保留当前 Activity 会话内存；旋转/重建后重新开始，不持久化。设备下拉框展示所有系统可见设备，不根据未知 Lite VID/PID 自动探测。应用无互联网权限，无串口读写或协议报文发送路径。

## 本版限制

连接接口是用户明确选择，不自动识别 CDC 接口组合，也不自动 setInterface/setConfiguration。非默认 alternate setting 是否可 claim 不代表该 alternate 已激活；此版不激活 alternate 或读取端点数据。仅 open/claim 成功不能证明 CDC 串口通信或 TourBox 按键可用。生命周期与授权回调、插拔时序、长屏布局均待目标手机实测。

## 任务 006E：受控 force=true 测试

用户报告的真机结果（非本次由 Codex 执行）：TourBox Lite VID=CAFE、PID=4001；接口 0 class/subclass/protocol=2/2/0，端点 81 INTERRUPT IN；接口 1 为 10/0/0，端点 02 BULK OUT、82 BULK IN。枚举、授权、openDevice 成功，接口 0 和 1 的 claimInterface(force=false) 均返回 false。接口占用原因尚不能确定，不能据此断言已绑定内核驱动。

现提供默认关闭的“尝试强制占用接口”复选框，仅对下一次连接点击生效。必须选择一个接口后才能 force=true；不选接口则拒绝该次连接。刷新、设备选择更新、进入后台和 Activity 重建都会复位选项。未勾选时仍使用 force=false；默认仅 openDevice，不自动 claim。每次连接最多尝试一个接口，不做 false 失败后自动 true 重试，也不自动占用 CDC 接口对。

测试步骤：选择并核对实际 Lite → 授权 → 选择接口 0 → 勾选 force=true 并阅读提示 → 点击连接 → 保存含 index/id/force/返回值或异常的日志 → 点击关闭并确认 releaseInterface/close 日志。再单独选择接口 1，重新勾选并重复。不要同时占用两个接口。之后分别验证拔出、后台和旋转清理；返回后应默认关闭强制占用且不自动重连。另测试不选接口时勾选应被拒绝、不勾选时仍为 force=false。

风险：force=true 可能暂时分离该接口的系统驱动；releaseInterface/close 不保证系统驱动立即重新绑定，必要时手动拔插。成功只说明该接口此次可占用，不证明 CDC 通信或 TourBox 输入可用；false 或异常不表明唯一故障原因。本版本仍无 bulkTransfer/controlTransfer、解锁、震动、串口初始化或协议解析，不要求 Root，不修改固件。

本次代码具备异常捕获与 finally 关闭路径，但真实设备断开竞态、驱动恢复和系统限制均待手机验证，不能保证所有系统故障均可恢复。上文 006B 的 APK 哈希与验证为历史记录，不作为本次 APK 标识。
006E 实际构建检查：现有 Gradle 9.4.1/JBR 25 工具链离线执行 help、:app:assembleDebug、:app:lintDebug，BUILD SUCCESSFUL；apksigner verify 通过，源码 USB 传输调用扫描和 git diff --check 通过。APK 为 android/app/build/outputs/apk/debug/app-debug.apk，892913 字节，SHA-256 89523687A6698066D927055239B0503BFE5A890E58D5979F2D35AE48EF74498A。本次未执行手机安装、运行或 force=true 占用测试。

## 任务 006F：原始 BULK IN 监听（当前行为）

用户报告接口 0/1 force=true 均已占用成功；描述符 maxPacketSize 分别为 0x81=8、0x02/0x82=64。以上为用户提供的实测信息，本次未连接手机重复验证。前述 006B/006E 的“不读取”限制与 APK 哈希仅适用于历史版本。

本版保留单接口显式 claim。只有 CAFE:4001、权限有效、openDevice 和接口索引/ID 1 claim 成功、alternate=0、class=10 且存在 BULK IN 0x82 时，才允许用户点击开始监听。连接后不自动读取，不 claim 接口 0，不访问 OUT 0x02，也无 controlTransfer、CDC 初始化或任何协议写入。

线程与停止：一个后台执行器串行管理 open/claim、读取和 release/close。每次 bulkTransfer 使用 64 字节缓冲及 200ms 超时。停止设置标志，当前有限超时读取返回后任务退出；不在 UI join 或并发 close。断开/换设备/后台/旋转取消旧会话，清理排在读取之后；销毁时 shutdown 让已排队清理完成。正常停止保留连接以便手动重启，重新连接不会自动开始读取。后台工作线程在 Activity 存活时可空闲复用，销毁后退出，不留下读取循环。

Android bulkTransfer 的负返回并非可靠 errno：首次标明“超时或 I/O 错误”，重复只计数、结束时汇总；同时检查设备和权限，发现失效或异常则结束并关闭。连续五次耗时小于 100ms 的无数据/错误返回停止，避免故障忙循环；小于 20ms 的返回另加 20ms 退避。该启发式不是超时/硬件故障的确诊，200ms 到期负返回可继续等待。0 字节返回不算成功 RX。参考 [官方 bulkTransfer API](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)。

每次正返回记录采集线程上的 HH:mm:ss.SSS、累计读取序号、实际字节数和全部 HEX。USB 读取块不等于事件或完整报文；不解析、不丢未知字节、不按 20 字节阈值过滤。累计统计跨当前 Activity 会话中的多次监听/连接，不在停止时清零，重建 Activity 后重置。

复制：先停止监听并等待“读取任务已退出”，点击“复制诊断日志（含累计统计）”，粘贴到文本文件保存。日志最多保留 200 条及约 128Ki 字符，整条淘汰旧记录，首行显示淘汰数量，不截断单次 RX；若需要长时间全量捕获，应分段停止、及时复制。本版没有全会话持久化或文件导出，重建/退出可能丢失内存日志。UI 每 100ms 合并刷新一次，接收记录先进入线程安全有界缓存，不为每包创建 UI 消息。

真机步骤：安装本次 APK → 选择 Lite 并授权 → 选择接口 1 → 明确勾选 force=true → 点击连接并确认 claim=true → 点击开始监听 → 分别按键、释放、滚轮双向和旋钮双向 → 停止并复制日志。未初始化设备时可能一直无数据；此时只记录结果，不追加解锁或控制传输。再测试接口 0/仅 open 时不能开始、重复开始无并行循环、停止后重启，以及监听中关闭、拔出、后台、旋转和返回后不自动监听。

仍需验证：无初始化是否有 RX、驱动实际超时上限、快速输入是否完整、断连竞态、force 后系统驱动恢复。若底层驱动不遵守有限超时，清理会等待读取返回；本版宁可等待也不并发关闭正在读取的连接，不承诺设备/内核异常下能强制终止 native 调用。成功 claim 不证明任何协议已通。
006F 实际检查：最终离线 :app:assembleDebug / :app:lintDebug 成功（BUILD SUCCESSFUL）；Lint 0 错误、3 条非阻断警告（固定 Gradle 版本、旧版备份配置及初始计数器文本国际化）。APK 签名验证通过，大小 923646 字节，SHA-256 09FE4F7E626DC38E54C26A7FE63FE2FC161B32B579A62CEFC8546A17B5D04EE0。临时 JVM 检查通过：日志条数限制、字符容量、淘汰提示、完整记录及并发 add/snapshot。源码仅一处经端点 IN 校验的 bulkTransfer，无 OUT/controlTransfer/协议写入；git diff --check 通过。上述不等于真实 USB 读取、停止延迟或断连测试通过。

## 任务 006G：双接口只读实验（当前新增行为）

保留单接口连接和 BULK IN 监听；新增“双接口只读监听（实验）”按钮及确认对话框，明确同时 force=true 占用接口 0/1。用户确认后关闭旧连接，用同一个 openDevice 连接依次 claim，两者均成功才启动读取；后者失败会反向释放已经 claim 的接口并 close，不自动退回其他模式。要求 Lite CAFE:4001、接口 ID/索引 0/1、alternate 0、类型和已知包长匹配。不自动尝试额外接口，不操作 OUT 0x02 或控制端点。

线程安全：连接协调器持有连接所有权，创建两个读取任务后在后台等待两者退出；不会在 UI join，不在读任务活跃时 release/close。0x82 用 200ms bulkTransfer，0x81 用一个 UsbRequest 和唯一 requestWait(200) 消费者；超时继续等待同一已排队请求，不重复 queue。按停止、拔出、后台、旋转或任一读取失败会让双任务一起退出。双模式停止后释放两接口并关闭，单接口的停止行为仍保留连接。

异步停止时 cancel 并最多五次 requestWait(200) 回收。停止竞态中已完成的数据仍完整记录。如无法确认回收，保留请求/缓冲及连接到进程退出，不释放或并发 close，隔离并禁止该进程后续 USB 连接；界面日志提示拔插设备并强制停止/重启应用。旋转 Activity 不能绕过隔离。这是设备/API 清理异常的显式限制，不宣称能保证异常内核下的强制终止。native 若不遵守超时，协调器等待而不冒险关闭。

每个端点独立显示 reads/bytes/errors/timeouts/timeoutOrError，并随日志复制；统计在当前 Activity 内累计。errors 计异常、失败或设备失效；0x81 TimeoutException 单列 timeouts；0x82 的负返回只计不确定项，不冒充确定错误。首次不确定负返回提示、重复仅统计；确诊 queue/initialize 失败、异常或快速连续失败只记一次并停止会话。原始 RX 日志均标来源端点，包含时间、实际长度、完整 HEX；不推断事件边界，旧记录仍按完整条目淘汰并明确提示。

测试：先用单接口模式复核 0x82 按下/松开；停止现有监听，再选择 Lite 点击双接口实验、阅读并确认 force=true。观察两个 claim 结果和 0x81/0x82 来源日志。分别按键、松开、转动每个旋转件；停止后等“双端点读取任务均已退出”和两个 release/close，复制日志和端点统计。再测试拒绝确认、停止后重启、监听中拔出/后台/旋转。若任一 claim 失败，检查回滚；若出现隔离提示，停止本轮，不追加控制/解锁写入。无 0x81 数据也应如实记录，不能断定旋转事件在其他端点。

本次未运行真机双端点采集，不能承诺手机 USB 实现支持可靠并发或取消；编译与静态检查另记。过去 APK 哈希仅标识其对应历史版本。
006G 实际验证：最终离线 :app:assembleDebug 与 :app:lintDebug 成功；Lint 0 错误/5 条非阻断警告（固定 Gradle、旧版备份配置、诊断文字国际化）。APK 签名通过，路径 android/app/build/outputs/apk/debug/app-debug.apk，909057 字节，SHA-256 F028EFA30EA4BDA13358CB34C57C3A4ACBC25BA885B53EB83DB25CAC372C93A3。临时 JVM 假实现检查通过：双读取任务进展、停止/撤销回收、join 后关闭、queue 失败及未回收请求隔离；不等同于 Android 内核或硬件测试。首轮假实现错误地将 queue=false 标为 pending，修正夹具后通过，未据此修改真实 USB 行为。禁止 OUT/控制传输/协议写入扫描与 git diff --check 通过。未执行手机安装或双端点真机采集。

## 任务 006I：三个手动标准 CDC 请求（当前新增行为）

已阅读 AGENTS 与 006H；本任务只实现 A，不实现私有实验 B。过去“无控制传输”描述适用于此前版本。本版只允许封闭 CdcAction 的三个动作：

| 按钮 | bmRequestType | bRequest | wValue | wIndex | wLength/payload | 完整完成判定 |
|---|---|---|---|---|---|---|
| A1：设置 115200/8N1 | 21 | 20 | 0000 | 0000 | 7 / 00 C2 01 00 00 00 08 | 返回 7 |
| A2：启用 DTR | 21 | 22 | 0001 | 0000 | 0 / 无 payload | 返回 0 |
| A3：启用 DTR+RTS | 21 | 22 | 0003 | 0000 | 0 / 无 payload | 返回 0 |

表中字段为十六进制，长度和返回数为十进制；均用 EP0、1000ms 超时。A2 是设置 DTR=1/RTS=0，不是保留之前 RTS。没有 GET_LINE_CODING、DTR/RTS=0 恢复、RTS-only、Break、Bulk OUT、私有解锁或94字节配置；不会在连接或重连时自动执行任何 A 步骤。

操作前提：选择 Lite、授权，明确确认双接口 force=true 并启动双 IN 监听。只有此会话两个接口均已 claim、设备/权限有效、尚未停止/隔离且无排队/执行中请求时，三个按钮开放。单接口模式保留，但本版不在该模式发 CDC 请求，便于在相同双 IN 捕获条件下对照。

线程/连接：UI 将一个动作交给原子单槽队列，携带会话代号，重复点击不会排成 A1/A2/A3 链。原连接协调器每轮等待读取任务时检查动作，执行 controlTransfer，独立 0x81/0x82 读取任务继续；UI 不等待控制传输。控制操作前核对会话、权限、设备身份和双 claim 状态；关闭连接的工作仍排在协调器之后，故不会并发 close/release 正在发送控制请求的连接。停止/拔出/后台可使已排队动作取消，已经进入 native 的动作不能假装已撤销，需等其有限超时返回，再完成读取回收和清理。若底层不遵守超时，仍不冒险并发关闭。

负返回、短传输、非预期返回、异常或请求完成时会话失效都会停止当前双模式，不自动补发、继续下一步、恢复或执行其他命令。IN 任务故障也会使协调器停止接受动作。完成仅表示该请求数据阶段符合预期，不判断旋转是否成功。正常停止只回收/释放/close，不发送线状态归零。保留异步请求无法回收时的进程隔离机制。

日志：BEGIN/END 带时间戳、动作名、全部控制字段、payload、返回值、耗时以及前后独立端点计数；IN 原始日志不暂停、不清空、不丢未知字节。用户可填写最长160字符的测试标记后点击记录（只记本地日志，不发送 USB），该标记与端点统计一起可复制。采集时间是主机侧完成/记录时间，跨端点并发日志不等于精确总线因果顺序。

真机步骤：物理重连并启动双 IN → 标记 A0，验证按钮/旋转基线 → 单独点 A1，确认 END 返回7，标记测试每个旋转件双向固定次数 → 单独点 A2，确认返回0并重复标记/操作 → 单独点 A3，确认返回0并重复。任何一步异常立即停止本轮，保留日志；不追加 B。若有变化，停止/关闭后物理断电重连，再做同样步骤对照。分别测试请求排队/执行时点击停止、拔出、后台和旋转，确认无后续自动请求且控制完成在 release/close 之前。日志满会整条淘汰并提示，应分段停止后复制保存。

风险：DTR/RTS/line coding 可能使设备重新枚举或改变状态，close 不保证恢复上电前状态；A 请求接受不保证固件按配置运行，也不证明旋转上报。Android controlTransfer 与双 IN 并发效果、实际超时、断连时序尚待目标手机验证。本次未在手机执行 CDC 请求，具体构建检查另记。
006I 实际验证：最终 debug APK 构建和 lint 成功（0 错误/6 条非阻断警告），APK 签名通过；APK 914841 字节，SHA-256 A4594091E82283FCA64ED647C58E9867E66F64CAAC462D00D7FE433D41906E54。无新增依赖的临时 JVM 检查通过：三项白名单参数/长度/完整 payload、每次独立 payload，以及双 IN 任务停止/回收/隔离回归；不代表真实 controlTransfer 或手机并发实验已通过。静态检查确认只有一处 controlTransfer，固定 requestType=0x21/wIndex=0/timeout=1000ms，参数仅来自三个 enum 动作；无 Bulk OUT/私有命令。git diff --check 通过，未进行设备安装或 A1/A2/A3 真机发送，未创建提交。

## 任务 006J：固定候选单次发送（当前新增行为）

先核实标准请求：本次未收到可核对的真机 A1/A2/A3 返回日志，状态仍未确认。当前连接需手动执行三个标准动作，且实际成功记录 A1=7/A2=0/A3=0，才开放候选；界面显示未确认值，重连清零。请求失败仍按006I停止会话，不能通过“旋转没变化”的观察绕过成功条件。

发送流程：启动已明确确认 force=true 的双接口监听 → 观察基线并记录测试标记 → 分步执行A1/A2/A3并检查真实END返回 → 点击单次候选按钮 → 阅读固定HEX 55 00 07 88 94 00 1A FE、0x02/1000ms、当前CDC证据和Lite适用性未知风险 → 第二次确认。确认携带连接代号，停止/后台/重连使其失效；取消不发送。每个连接最多一次机会，确认排队即消耗，取消待发或失败也不允许该连接再试。重连后仍必须重新核实CDC并人工确认，不自动重发。

连接协调器在两个 IN 任务均启动（0x81 已 queue、0x82 已进入读取循环）且健康时处理单槽请求，与CDC共用忙状态。再次核对设备CAFE:4001、权限、双claim、接口1/alternate0/class10和BULK OUT 0x02/包长64，实验文件建立且写入正常后才执行唯一一次固定8字节bulkTransfer。两个IN任务持续运行，日志保留完整原始块，不清接收缓存、不按长度过滤。TX开始/结束记录时间、HEX、实际返回、耗时和CDC证据；结果8也不记录“解锁成功”。短写/负值/异常/失效终止会话，不续写、不补发、不追加其他配置。停止后等待已进入native的有限超时写返回及读任务回收，再释放/close；隔离规则仍有效。

完整文件：二次确认后、实际写前，在应用私有 files/unlock-experiments 下建立唯一TXT，先包含当前屏幕保留快照（其中可能标明历史淘汰），再完整追加本轮TX/RX/标记/清理日志。屏幕仍限200条/约128Ki字符，但文件不按环形缓存淘汰。文件每条追加、每轮上限8MiB；达到上限或I/O失败明确标CAPTURE FAILED并停止USB实验，文件可能不完整，不能假装全量。写前检查捕获健康状态，无法建立文件时不发送。文件在应用内保留，Activity重建可找到最近一份，卸载/清除应用数据会删除，历史实验需自行及时导出。

停止后等“读取任务均已退出”及release/close日志，再点击“导出本轮实验完整日志”，通过系统文件选择器保存TXT；该复制工作在后台，不申请广泛存储权限，也不上传。现有剪贴板按钮仅复制屏幕保留快照，不能代替完整文件。此前已淘汰的A0/CDC原始记录不可能恢复，文件会明确说明快照边界，实际CDC返回值另由程序会话记录列出。

真机测试：先导出/保存A1=7/A2=0/A3=0的END日志；固定输入操作验证基线。确认发送后先观察所有回复（不限26字节/0x07开头），再依次测试按钮、松开、旋钮双向、滚轮双向并加标记，检查每端点计数。停止并导出完整文件；如要复测，物理拔插断电后开始全新会话重新做基线/CDC/二次确认。分别验证取消确认、重复点击、停止/拔出/后台使旧确认或未发送请求失效，以及短写/失败后无第二次发送。遇到失败不得追加未知命令。

风险：该私有序列尚未确认适用于Lite，可能改变固件状态且没有可靠反命令；不能保证close或断电必能撤销。手机控制器驱动可能不遵守超时，并发行为仍待真机验证。私有文件写入与日志锁可能影响极高频输入吞吐，磁盘故障不保证全量；超过容量会停止而不是静默覆盖。当前实现与JVM检查都不等于真实USB已验证。
006J 实际检查：同版工具链离线全任务重跑 assembleDebug/lintDebug 成功，未升级或屏蔽检查；首轮lint遇到Kotlin内部索引异常，重跑通过。最终lint： 0 errors, 8 warnings。APK签名通过，大小924921 字节，SHA-256 FE6A24E392490D13D7429D34E7D0136D30021A08F465E6CCCFDF62A05ECDE6F1。临时JVM检查通过：真实CDC结果门控（错误/未知值不能开放）、20线程竞争仅一次reserve、连接复位需重新核实、精确八字节/独立payload、文件保留屏幕已淘汰RX、容量/写入故障可见；既有CDC参数和双IN停止/回收/隔离回归通过。源码扫描为两个IN bulk调用、一处固定候选OUT和一处原标准CDC control调用，无其他私有配置。未运行真机命令，不声称Lite解锁已验证。

### 006J 日志修正版：无需先发送才能提供返回值

修正此前日志获取流程：界面标题显示“006J 日志修正版”；顶部关键结果面板独立保留本轮CDC/候选的请求、END返回、失败消息，RX刷屏和停止连接不清除此面板。当前连接门控仍在close时复位，两者意义不同：面板是历史证据，不能用于新连接解锁资格。未操作时明确显示暂无结果，不能理解为成功。

每次Activity创建即建立独立TXT并从启动开始追加，不要求候选按钮开放或真正发出命令。停止并等任务退出后，点击“导出当前/历史日志”，从最新在上的文件列表选择相应轮次，再选系统保存位置。旋转/重启会创建新文件，前一轮可从历史列表导出。剪贴板也包含关键面板；其普通RX部分仍是有界快照，完整本轮记录以文件为准，容量/写入故障仍会明确提示并停止。

本修正不改变标准请求、候选字节、实际返回值判定或单次限制，不自动发送USB数据。界面与手机真实显示仍待用户安装验证；此前找不到日志的具体真机原因不能仅靠源码检查确定，标题标记用于排除安装了旧APK的情况。
日志修正版实际验证：debug构建/lint成功、APK签名通过；JVM验证关键结果与失败不受400条RX淘汰影响、门控复位不清证据、发送前文件保留CDC结果。未进行真机UI或USB测试。APK SHA-256 804090D552EA75A7A41D7B8997B9509399589ED03DCFED3F68462AC664987D84。


## 006L 检查点验证

本轮未改Android文件。离线 `help :app:assembleDebug :app:lintDebug --offline --no-daemon --console=plain` 成功，45项任务中9项执行、36项最新；没有强制重跑全部任务。Lint为0错误/8项既有警告（Gradle更新提示、旧API备份配置、诊断文字国际化），未屏蔽或升级处理。

APK签名验证通过，路径仍为 `android/app/build/outputs/apk/debug/app-debug.apk`；SHA-256 `804090D552EA75A7A41D7B8997B9509399589ED03DCFED3F68462AC664987D84`，与日志修正版一致。JDK对apksigner打印native-access未来限制提示，当前验证成功。本轮未重跑历史临时JVM夹具，仓库无独立自动化测试套件；未执行IDE GUI Sync或任何USB/手机操作。用户实测统一见阶段2A研究总结，不将构建结果替代硬件验证。


## 006P：控制线状态0单变量实验（待真机验证）

新增手动A0：EP0 bmRequestType=0x21、bRequest=0x22、wValue=0、wIndex=0、wLength=0、无payload、timeout=1000ms，预期返回0。A1仍设置115200/8N1；A2/A3原功能保留。双claim、IN线程/200ms等待、固定8字节/1000ms及单次二次确认均不变，无动态16字节或94字节配置。

新门控要求A1返回7，且最后一个控制线动作A0成功；旧A1/A2/A3路径也保留。界面及确认显示各返回值、lastControl，便于区分路径。A0之后若误点A2/A3，不得继续称为006P单变量实验，应停止并断电新建会话，不通过追加A0掩盖混杂。

步骤：控制器实际断电重连，保持应用前台和固定方向 → 授权、手动确认双接口force=true并监听 → 添加006P基线标记 → A1返回7 → A0返回0 → 二次确认固定8字节一次，检查TX返回8和26字节RX（允许跨块） → 分别标记Knob顺/逆、Scroll上/下，每组间停约3秒 → 停止，等待读取退出/release/close → 导出当前/历史日志中的本轮完整TXT。不要只复制屏幕快照，不把传输成功当旋转成功。文件记录未知字节，不过滤旋转候选码。

A0关闭DTR/RTS可能改变设备状态或导致重枚举；失败按已有机制停止，不自动重试/恢复。若观察到44/04/49/09，应重复新会话确认；若无，仅表明本轮A1+A0+8字节不足，不证明线状态完全无关。未自动连接、安装、访问设备或执行本实验。

006P实际验证：修改后的debug构建及lint通过（0错误/8条既有警告），APK签名通过；SHA256 `404D5061E77856587EBB95637C42CADFC46E85C916761EF6B2BEDB5ABE613FE0`。项目外JVM检查对实际编译类验证A0参数、未知/失败门控、最后控制线覆盖、旧路径兼容、单次reserve及reset通过。首次测试使用旧AGP类目录导致classpath错误，定位built_in_kotlinc实际产物后通过；未据此改应用逻辑。构建/检查不代表真机实验成功。未安装APK或访问设备，未提交Git。


## 006Q-B：独立94字节配置实验（未真机验证，等待批准）

实现依据[006Q-A方案](TOURBOX_94BYTE_EXPERIMENT_PLAN.md)。标题为006Q-B 94字节实验版；新增“单次94字节配置实验（需二次确认）”。固定Windows原样94字节，SHA256 bd5c3ea003dc6cee06182d710381f71844a4f7a38183f15e952224f21f8414a1，已与W2/W3全部13条逐字节核对，不使用TuxBox/Detour差异版本。

门控：本连接严格A1成功→A0成功且不混入A2/A3，原八字节返回8；从其native发送前启用3秒回复观察窗，完整匹配指定26字节，可跨读取块且允许回复早于TX END。超窗未匹配不开放；只旁路匹配，未知字节仍完整记录。匹配记录RX结束序号和主机时间。失败不能开放，旧会话证据不能复用。原legacy按钮流程保留但不开放配置。

用户再次确认才原子reserve并排队，取消对话框不发送；确认后本连接机会已用，未发即取消/失败也不返还。协调器发送前重核epoch、双claim、权限、端点和完整日志；OUT02一次bulkTransfer94/1000ms，返回94仅表示主机完整发送，不表示固件接受或旋转成功。短写/异常/日志失败停止不重试、不补尾、不恢复，IN原线程继续，停止先等写/读退出再释放。无法回收异步请求仍隔离。

真机步骤须另行批准后进行：物理重连→授权双IN→A1→A0→八字节及26字节证据→同会话原始输入基线及标记→94字节入口阅读完整HEX/哈希/风险并确认一次→标记四方向与按钮测试→停止等退出及release/close→导出完整TXT。应用不发送动态16字节，不自动配置；关闭应用/断电不保证恢复未知持久状态。原始日志/抓包不提交。


006Q-B实际验证：离线assembleDebug/lintDebug成功（44项任务，18执行/26最新），lint 0错误/9警告；增加的诊断文字国际化提示未屏蔽。APK签名通过，SHA256 `0D0DB9A08091381A38BC46E41CE8F027E6393ABBD74A8F81DE8C9CE2A2699C6D`。apksigner的JDK native-access提示不影响本次成功验证。

纯JVM夹具保存在 `scripts/tests/Config94Check.java`，已对实际编译类运行：94字节/哈希及独立payload、25个分割点、回复早于TX返回、缺失/错误/超时回复、A2混入/反序、八字节短写、未确认拒绝、32线程单次reserve、旧epoch/reset全部通过。完整常量另与W2/W3共13条逐字节核对。静态审查确认配置执行函数只有一个bulkTransfer且无重试循环、UI仅positive确认回调排队，原RX记录不删改。夹具不是Android系统/实际USB竞态验证，不声称真机通过。

复现夹具：先构建debug，再用JDK的javac（UTF-8）将测试编译到项目外临时目录；classpath包含 `android/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` 和已缓存的Kotlin stdlib 2.2.10 JAR。用相同classpath加测试输出目录运行 `java Config94Check`；不调用ADB或USB。本轮未安装/启动APK、访问设备或创建Git提交，等待用户明确批准真机测试。
