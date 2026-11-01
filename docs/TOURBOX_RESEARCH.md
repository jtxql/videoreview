# TuxBox 与 TourBox Lite USB 协议研究

研究日期：2026-11-01。初稿为源码研究，后续补充诊断实验与用户实测；各任务历史验证范围独立记录，当前状态见下方阶段2A检查点。

## 阶段 2A 当前检查点（任务 006L）

截至2026-11-01，阶段2A基础通信与初始化探索完成，**旋转协议仍未解决**。以下为用户在本项目对话中报告的真机实测，不是Codex本轮连接设备复测；未附完整原始抓包，不扩大到所有固件。后文按任务保留历史研究与当时状态，若与早期“未知/待测”表述不同，以本表为当前进度。

| 项目 | 用户真机结果 |
|---|---|
| 设备 | TourBox Lite，VID=0xCAFE、PID=0x4001 |
| 接口0 | CDC Control，Interrupt IN 0x81 |
| 接口1 | CDC Data，Bulk IN 0x82、Bulk OUT 0x02 |
| Android基础访问 | 枚举、授权、openDevice成功 |
| 接口占用 | 两个接口分别force=true可claim；双接口监听实验已执行 |
| 原始接收 | 接口0未观察到数据；接口1收到所有按钮按下/松开，每次1字节；全部旋转控件未观察到数据 |
| 006I标准请求 | A1 SET_LINE_CODING返回7；A2 DTR返回0；A3 DTR+RTS返回0；均未使旋转出现 |
| 006J候选发送 | 55 00 07 88 94 00 1A FE 经0x02单次发送返回8；旋转仍无数据 |
| Windows对照 | 同一台设备在官方TourBox Console中所有旋转控件正常 |

**判定边界**：7/0/0/8仅为相应主机传输返回，不证明设备解锁或协议兼容成功；官方正常显著降低硬件失效解释的优先级，但不能确定缺失初始化是哪一步。TuxBox八字节及94字节共享模板是源码证据，Lite适用性和额外初始化属于未验证假设。没有发送94字节配置。

下一阶段按 [USB_CAPTURE_PLAN.md](USB_CAPTURE_PLAN.md) 获取同设备Windows官方通信证据，不先增加Android命令。高频接收完整性、断连/后台/旋转及异步回收异常等稳定性场景仍待专项真机验证。006K的证据与代码风险审查继续有效；其中“尚缺Windows正常基线”已由本次用户确认补齐。

## 1. 结论摘要

- **源码确认**：README 明确列出 TourBox Lite (USB) 为 USB only、无震动、Full support；GUI 有 Lite 专属控件集合。但未找到 Lite 专属 VID/PID、USB 描述符、握手分支或抓包。支持声明不能替代本项目真机验证。
- **源码确认**：USB 实现是 Python pyserial + Linux CDC-ACM 串口，非 HID report 读取；事件经过共享单字节码表处理。代码说明 USB 与 BLE 共用事件码，但没有提供 Lite USB 单独测得的码表。
- **根据源码推断**：Android 可尝试用 UsbManager 和 CDC-ACM 用户态实现替代 Linux tty，通过 Kotlin/Java 完成通信，无需预先引入 JNI/NDK。能否申请权限、正确初始化和持续接收仍需真机验证。
- **推荐**：继续把 TuxBox 当作协议线索和候选事件语义参考，不能把它当作已验证的 Lite Android 驱动或完整 USB 协议规范。

## 2. 源码版本与证据边界

- 仓库：https://github.com/AndyCappDev/tuxbox
- 提交：`f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81`，`2026-10-29T17:10:15-05:00`，版本 3.8.0。
- 临时浅克隆位置：`%TEMP%/tabletennis-tuxbox-research-004/tuxbox`，位于主仓库外。未修改源码、运行安装脚本或安装 Python 依赖。
- 下列行号均基于该快照。[固定源码树](https://github.com/AndyCappDev/tuxbox/tree/f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81)。
- README:15–21 是支持声明；`tuxbox/gui/controller_models.py:12–21` 的 LITE_CONTROLS/get_model_controls 是 Lite 专属 GUI 证据。配置文档说明 controller_model 只影响展示，不改变驱动行为。故不能从选择 lite 推出使用了 Lite 专属协议。
- 本次仅将 BLE/Elite 相关文件用于区分边界，不把其解锁、震动或 GATT 实现当作 Lite USB 已确认行为。

## 3. 设备识别与通信方式

### Linux 发现流程（源码确认）

`tuxbox/__main__.py`：

1. `find_tuxbox_usb_port:229–265` 优先尝试配置路径，再扫描 `/dev/ttyACM*`；默认路径为 `/dev/ttyACM0`，不是固定唯一设备路径。
2. `_read_sysfs_usb_attrs:87–117` 从 `/sys/class/tty/<name>/device` 解析 USB 父目录，读取 idVendor、idProduct、manufacturer。
3. `_is_likely_tourbox:120–147` 通过已知 VID/PID 或 manufacturer 包含 tourbox 筛选；sysfs 不可读时退回主动探测。
4. `probe_usb_device:171–226` 打开 115200 串口、清输入、发送解锁序列，等待 0.3 秒，最多读 100 字节；回复长度 >=20 就判定为设备，不验证型号、字段或校验。
5. 否定探测有缓存，设备路径消失时清除（81–84、150–168、247–249）。

### VID/PID 的真实归属

| 源码位置 | ID | 代码归属/限制 |
|---|---|---|
| `__main__.py:55–61` TOURBOX_USB_IDS | c251:2005 | 注释为 Elite / Elite Plus，非 Lite 证据 |
| 同上 | 2e3c:5740 | 注释为 Neo，可能与通用 AT32 板共享 |
| `device_usb.py:44–48` 常量 | 0483:5741、0483:5740 | 注释为 Elite / Neo或早期Elite；检索未见这些常量参与该文件的型号判断 |
| `install.sh:259–267` udev 规则 | 另含 cafe:4001 | 注释为 Neo 通用 ID；并未同时加入上面的发现白名单 |
| Lite USB | **未确认** | 不得用上述其他型号 ID 填入 Lite 设备过滤器 |

**源码确认**：`device_usb.py:4–5,121–126` 声明 CDC ACM 并通过 pyserial 打开串口，设置 115200、读超时 0.1 秒；未显式改 pyserial 的默认 8N1。没有直接枚举 USB interface/endpoint 的代码，也没有 Lite 原始描述符。

**根据源码推断**：如果实际 Lite 是标准 CDC-ACM，通常应检查通信接口 class 0x02 / ACM subclass 0x02、数据接口 class 0x0A，通信接口可能带 interrupt IN，数据接口用 bulk IN/OUT。设备级 class 可以是复合设备值，不能只过滤 deviceClass。

**需要真机验证**：Lite 的 VID/PID、设备级/接口级 class、subclass/protocol、接口号、端点地址、传输类型、最大包长及 CDC union 描述符。源码无法回答确切接口号或端点地址，不在报告中编造。

## 4. 初始化、报文与断连流程

### 初始化（共享 USB 实现，源码确认）

`device_usb.py:107–150 TuxBoxUSB.connect`：检查路径 → serial.Serial → reset_input_buffer → 写入 `55 00 07 88 94 00 1A FE` → flush → 等 0.3 秒 → read(100)，注释预期约 26 字节。即使没有回复也只是记录 warning，随后仍设置 connected=True。这个标志不能当作握手验证成功。

`send_haptic_config:80–105` 随后发送配置；`haptic.py:157–166,398–409` 用 94 字节模板，开头 `B5 00 5D 04`、末尾 `FE`，按震动配置修改字段。该模板属于共享/Elite 震动逻辑，**不是已确认的 Lite 初始化必需报文**。尤其 `device_usb.py:78` 将 haptics_enabled 初始化为 True，虽然注释称后续检测赋值，但检索该实现只找到初始化和发送前判断，未找到按 Lite 型号关闭的赋值。不能因 README 声称 Lite 无震动便假定代码自动跳过发送。

**源码确认**：所读 USB connect/read/run 路径未发现周期性心跳或独立状态查询；路径存活检查是主机侧检查，不是设备心跳。没有回复字段的完整解析。不能据此断言 Lite 协议永远不需要保活。

### 事件解析与边界（源码确认）

`device_usb.py:175–216 _read_loop` 每 10ms 左右检查 in_waiting，用 executor 读可用字节；小于 20 字节的数据逐字节交给 `process_button_code`。`device_base.py:264–280 get_control_name_from_code` 精确匹配 BUTTON_CODES；未匹配返回 None。

事件在此实现中为单字节 ID，无逐事件报文头、长度或校验解析。按钮释放通常为按下码加 0x80；旋转方向各自有 active/stop 配对。不能把这一共享解析约定称为完整 Lite USB 线协议。

`device_usb.py:30–39,189–195` 把单次读取 >=20 字节视为响应帧并整块丢弃，以防响应中的 00 被当作 tall 按下。**根据源码推断**：串口读取边界不是消息边界，快速输入可被合并成大块，响应也可分片或与事件混合；此启发式可能丢事件或产生伪按键。Android 端应保留原始流，先测回复边界，再设计状态化解析，不能照搬长度阈值。

解锁字节及配置模板有固定前缀/尾字节，但源码没有解释其完整字段、长度编码和校验算法，未发现通用 CRC 校验实现。不要擅自赋予字节含义。

### 断开与重连（源码确认）

`disconnect:159–173` 取消读任务并关闭串口；`_read_loop:206–214` 将 SerialException、errno 5 视为断连；`run_connection:218–274` 每 0.5 秒检查路径，正常断连后清 modifier state；`start:276` 以后重连循环等待同一路径出现，5 秒起、乘 1.5、上限 10 秒退避。顶层等待发现循环间隔 3 秒。

**根据源码推断**：选定 USB 后主要等待原路径，重新插入后若 tty 编号变化，可能无法跟随；部分异常清理路径也需验证按键状态是否完全复位。Android 应按 attach/detach 与连接会话重新枚举，断连时统一清除按下状态及定时器。

## 5. Lite 控件候选事件表

以下是 **源码确认的共享 BUTTON_CODES**（`config_loader.py:110–134`）与 **Lite GUI 子集**（`controller_models.py:12–16`）的交集；**根据源码推断**可用作 Lite 首轮采样假设；**全部需要真实 Lite USB 验证**，不是 Lite 专属抓包结果。

| Lite 控件名称 | 按下/活动码 | 释放/停止码 | 说明 |
|---|---|---|---|
| top | 02 | 82 | 按钮 |
| tall | 00 | 80 | 00 也可能出现在响应中，须分流 |
| short | 03 | 83 | 按钮 |
| c1 | 22 | A2 | 按钮 |
| c2 | 23 | A3 | 按钮 |
| tour | 2A | AA | 按钮 |
| scroll_click | 0A | 8A | 滚轮按下 |
| knob_click | 37 | B7 | 旋钮按下 |
| scroll_up | 49 | C9 | 活动/停止，非普通按钮释放语义 |
| scroll_down | 09 | 89 | 活动/停止 |
| knob_cw | 44 | C4 | 顺时针活动/停止 |
| knob_ccw | 04 | 84 | 逆时针活动/停止 |

Lite 集合不含 side、dpad、dial，不能套入 Elite 的额外控件。命名与物理位置及观察方向应通过逐个操作确认。

**源码确认**：`device_base.py:341 process_button_code` 处理按下/释放、修饰键、组合动作、双击与释放触发；`get_modified_action:282` 查询组合映射。组合键是主机跟踪按下状态后合成的动作，不是单独组合报文。未发现专用 long_press/long-press 事件码或明确长按阈值实现；持续按下、桌面按键重复与应用长按应区分。Android 可基于按下/释放时间自行派生长按，属于后续设计。

**需要真机验证**：旋转一格对应多少活动事件、stop 是否总到达、快速反向/连续旋转顺序与吞吐、多键同时按下、释放次序、长按期间旋转，以及丢包恢复。Linux 的桌面事件注入逻辑不代表设备原始流有同样能力保证。

## 6. Android 移植可行性与模块边界

**官方 API 确认**：UsbManager 提供枚举与权限流程；取得权限后可 openDevice、选择/claim 接口，通过 UsbDeviceConnection 或 UsbRequest 通信。这是支持用户态 USB 的路径，不保证目标设备可见、授权成功或接口可 claim。[Android USB Host 官方文档](https://developer.android.com/develop/connectivity/usb/host)。

**根据源码推断**：若描述符符合 CDC-ACM，可在用户态通过 controlTransfer 设置串口线编码（115200/8N1）及按需设置 DTR/RTS，使用数据接口 bulk IN/OUT；通知端点是否必须读取、具体控制请求与时序须按描述符和实测决定。TuxBox 的 pyserial 隐藏了 termios 和串口驱动行为，不能认为只写八个解锁字节就等价于串口初始化。

Android 不应假定存在或可打开 `/dev/ttyACM*`，也不移植 sysfs 路径、udev、dialout/uucp 用户组、ModemManager、systemd、evdev/uinput 或桌面窗口监控。它们是 Linux 发现、权限和输出适配；报文字节与事件语义可独立重写。应用内部直接发布控制事件即可，不需要模拟系统键盘。

**候选依赖，未决定采用**：usb-serial-for-android 有 Java 用户态 CDC 驱动，可减少串口控制实现工作，官方 README 说明无需 Root、无需特殊内核驱动。但第三方库并非必需，纯 Kotlin/Java 也可直接实现 USB Host 传输；采用前需固定版本、检查许可证与设备兼容性。其支持 CDC 不等于支持 TourBox Lite。[库源码与说明](https://github.com/mik3y/usb-serial-for-android)。本次未下载该库或添加依赖。

协议和 USB 模块不要求 JNI/NDK，mpv 自身的 native 依赖是另一件事。建议候选职责：

| 模块 | 职责与边界 |
|---|---|
| TourBoxUsbManager | 枚举描述符、授权、端点选择、连接会话、I/O、断连清理；不解释播放器动作 |
| TourBoxProtocol | 初始化状态机、流缓冲、响应与事件解析、未知数据保留；不依赖 Activity/mpv |
| TourBoxEvent | ButtonDown/Up、RotaryActivity/Stop 等带单调时间戳和会话 ID 的语义事件；未测之前不承诺角度/格数 |
| TourBoxController | 组合、长按、重复、积压与节流策略；输出播放器抽象意图；逐帧事件不能无记录地合并 |
| TourBoxDiagnostics | 描述符、权限、TX/RX 十六进制、时间戳、未知码、错误与导出；避免无必要记录设备序列号 |

以上均为建议，未经用户确认不创建模块或选定最终架构。

## 7. 尚未确认的问题与风险

1. **Lite 身份缺口**：没有 Lite 专属 VID/PID 和 USB 描述符。TuxBox 白名单可能漏掉某些 Lite 固件，manufacturer 回退也不是可靠型号认证。
2. **握手验证弱**：探测仅看 >=20 字节；connect 无回复也成功，不能据日志 ready 判定初始化通过。
3. **配置风险**：共享代码默认可能发送 Elite 震动配置；Lite 是否需要/接受/忽略该报文未知。阶段 2 不默认发送 94 字节模板。
4. **流解析风险**：读取长度阈值不能可靠区分回复和快速事件；没有完整响应字段/校验规范。
5. **平台与设备风险**：小米 USB Host/供电、授权拒绝、接口占用、系统策略、DTR/RTS 行为、屏幕熄灭和重连均待测。
6. **许可（源码确认）**：`LICENSE.txt:1–21` 为 MIT，允许使用、复制、修改、分发和移植；复制代码或实质性部分须保留版权与许可声明，包含免责声明。引用协议事实与直接复制实现须区分；其他依赖另查许可。本次未复制驱动实现进项目。此处仅记录仓库许可文本，不代表完整发布合规审计。

## 8. 阶段 2 最小 USB 测试 APK 建议（尚未实施）

先经用户确认再建工程。APK 仅显示设备与事件，不集成 mpv、不注入系统输入、不要求 Root、不依赖电脑中介。

1. 枚举并显示 device/interface/endpoint 描述符；先不凭其他型号 VID/PID自动写数据，让用户选择实际设备并导出描述符。
2. 明确显示授权结果、open/claim 结果和失败原因；拒绝、设备拔出、重复授权均有可见状态。串口候选库或自实现须先确认。
3. 先记录连接前后的被动 RX；核实 CDC 后配置 115200/8N1并记录线状态，再通过明确操作发送已知八字节解锁候选，完整记录回复、分片和时间。无回复保持未确认，不能直接显示就绪。
4. 不默认发送震动模板、不尝试未知固件/配置写入、不循环向未知设备主动探测；先验证最小握手是否足以产生事件。
5. 同时展示原始 RX 与候选解析结果；不丢弃 >=20 字节块。响应尚未确定时将其标为未知，避免误报 tall 按下。
6. 逐个验证表中控件、双向旋转、按住旋转、组合与快速连续输入；记录事件数、原始流、延迟和未识别码。长按先观察原始按下/释放，不声称设备有专用长按报文。
7. 测试多次拔插、权限拒绝后重试、应用后台与恢复、熄屏、断连时按钮按住。断连清状态，重连重新枚举；记录手机系统、设备固件和供电连接方式。

阶段通过标准建议：实际 Lite 可枚举、可授权、可连接；得到可复现的最小初始化与各控件原始事件；快速输入无已知解析丢失、断连可恢复。若仅枚举成功，应只标记枚举验证完成，不将整个协议判定可用。无设备/无权限/未知报文都应作为真实结果记录。

## 9. 是否继续采用 TuxBox 参考及实际验证

**推荐继续作为参考，暂不认定协议已验证。** 它提供了明确 Lite 支持声明、共享 USB 解锁线索和可读事件码表，MIT 允许保留声明后移植实现；但型号证据、描述符、响应分帧与配置兼容性不足，不能机械移植 Linux 驱动。建议先批准阶段 2 的描述符/权限/最小串口实验，再决定库与协议实现。

实际执行：检查主项目 Git 状态，发现任务 003 的 ROADMAP/CHANGELOG 修改和 docs 未跟踪文件并予保留；浅克隆固定 TuxBox 提交；阅读/检索 README、LICENSE、__main__、device_usb、device_base、config_loader、controller_models、haptic 及安装规则；查阅 Android 和串口库官方说明；研究仓库 git status 干净。未运行上游安装/驱动/测试脚本，未连接 TourBox，未修改系统配置或 mpv-android，未创建 Android 工程，未进行 Git 提交。

## 任务 006G 增补：用户真机观察与双端点实验

2026-11-01，用户报告（非本次 Codex 采集）：Lite CAFE:4001 的接口 0/1 分别 force=true claim 成功；接口 1 BULK IN 0x82 已收到所有按钮按下/松开各 1 字节，旋转控件尚无数据。接口 0 为 CDC Control class=2/subclass=2，INTERRUPT IN 0x81/maxPacketSize=8；接口 1 class=10，BULK IN 0x82 和 OUT 0x02/maxPacketSize=64。该实物观察补充了原报告的 Lite 身份缺口，但不推翻历史源码证据的型号边界，也不能推断旋转事件一定走 0x81。

本次增加明确确认后同连接双接口 force=true 的只读实验。0x81 用 UsbRequest/queue/requestWait(200) 异步读取，0x82 独立任务用 bulkTransfer(200ms)，不向 OUT 写入，不初始化 CDC。官方 UsbRequest 文档规定 interrupt 只能异步接收；同连接只设一个 requestWait 消费者，避免互相取走完成请求：[Android UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest)。这仍需真机证明具体系统/设备并发行为。

不能把 CDC interrupt 数据当成旋转事件；其可能仅含串口状态通知。报告保留完整原始数据，不做语义解析，也不将无数据等同于硬件故障。bulk 负返回分开统计 timeoutOrError，不能虚构为已确定的超时或非超时错误。双接口同时占用是否改变 0x82 行为、0x81 是否有原始数据、停止撤销是否可靠，均待用户实测。
## 任务 006H：CDC-ACM 初始化与旋转缺失再调查

日期：2026-11-01。范围仅为源码调查和实验设计；没有修改 Android 源码、编译 APK、访问 USB、发送命令、安装驱动或创建提交。本文方案不是执行授权。

### H1. 实测、版本与证据边界

**用户实测报告**：双接口同时监听成功启动；0x81 无数据；0x82 收到所有按钮按下/松开各一字节，旋转件无数据。尚未进行 CDC 控制初始化或私有 OUT 写入。以上不是本次 Codex 重复采集结果，也不能证明主机侧没有遗漏。

重新查询远端 HEAD，仍为 `f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81`，与前次固定研究快照相同；本地研究副本 git status 干净。补充阅读 pyserial v3.5 与 Linux v6.12 CDC-ACM 代码。TuxBox 依赖是 `pyserial>=3.5`，不是固定 3.5；以下用于解释默认机制，不声称已查明某位上游用户实际安装的 pyserial/内核版本，更不将 Linux v6.12 当作小米正在运行的内核版本。

固定来源：

- [TuxBox USB 实现](https://github.com/AndyCappDev/tuxbox/blob/f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81/tuxbox/device_usb.py)
- [发现与探测](https://github.com/AndyCappDev/tuxbox/blob/f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81/tuxbox/__main__.py)
- [pyserial v3.5 serialutil](https://github.com/pyserial/pyserial/blob/v3.5/serial/serialutil.py)、[serialposix](https://github.com/pyserial/pyserial/blob/v3.5/serial/serialposix.py)
- [Linux v6.12 cdc-acm.c](https://github.com/torvalds/linux/blob/v6.12/drivers/usb/class/cdc-acm.c)、[CDC 请求/结构定义](https://github.com/torvalds/linux/blob/v6.12/include/uapi/linux/usb/cdc.h)

### H2. 源码逐项回答

| 问题 | 源码证据 | 可以确认及不能推导的内容 |
|---|---|---|
| 如何打开 ttyACM | `device_usb.py:TuxBoxUSB.connect:121–129`；`__main__.py:probe_usb_device:192–193` | `serial.Serial(port, baudrate=115200, timeout=0.1)`；探测 timeout=0.5；均清输入缓存。pyserial 在提供 port 时立即 open。不是简单只打开 USB 设备句柄 |
| 115200/8N1 | 上述显式 baudrate；`serialutil.py:SerialBase.__init__:180–190` | 115200 显式设置；8 数据位、无奇偶、1 停止位来自库默认，软件流控 xonxoff=False、硬件流控 rtscts=False/dsrdtr=False。不是 TuxBox 显式逐个传入 8N1 |
| DTR/RTS | `serialutil.py:216–217`；`serialposix.py:open:332–342`、`_update_rts_state:703–708`、`_update_dtr_state:710–715` | TuxBox 未显式操作两条线；pyserial 默认状态均 True，打开时在非自动流控模式尝试通过 ioctl 应用。关闭硬件流控不等于 DTR/RTS=False；特定 ioctl 错误可被库忽略，故代码尝试不等于设备端已接受 |
| 是否先解锁 | `device_usb.py:28,131–147`；`__main__.py:195–213` | 正式连接读循环之前发八字节、flush、等 0.3 秒、read(100)；顶层主动探测也发送。因此 Linux 正式读事件前可能已经经历一次探测解锁、关口及再打开解锁 |
| 是否仅有解锁 | `device_usb.py:80–105,146–147`；`haptic.py:157–166,398–409` | connect 随后调用共享配置发送；默认 haptics_enabled=True，未找到 Lite 自动关闭分支。故“Linux 能工作”不能单独归因于八字节命令，94 字节配置是混杂变量，本实验不复制 |
| Lite 专用分支 | `gui/controller_models.py:12–21` 与 USB 全文件检索 | 有 Lite GUI 控件子集，未发现 Lite 专用串口参数、DTR/RTS、握手或旋转启用分支。未发现不是协议不存在的证明 |
| 专用旋转启用命令 | USB、共享解析、haptic 与协议文档检索 | 未找到明确命名或注释为“启用 Lite 旋转事件”的命令。震动/步感参数不等于已证明的旋转上报开关；不能猜测模板中的某字节后发送 |
| 是否分通道读取 | `device_usb.py:_read_loop:175–199`；`config_loader.py:110–134`；`device_base.py:get_control_name_from_code:264–280,process_button_code:341` | 同一个 serial.read(in_waiting) 字节流，逐字节查同一张按钮/旋转表；无按事件类型选择 USB endpoint 的逻辑。旋转在应用动作层有瞬时/组合等特殊处理，不是另一条 USB 读取通道 |

### H3. 八字节候选的准确出处与局限

**源码直接支持**：`device_usb.py:27–28`、`__main__.py:43–44`、`usb_test_tuxbox.py:18–19` 均定义 `55 00 07 88 94 00 1A FE` 为 UNLOCK_COMMAND。`device_ble.py:32–33` 注释明确说来自 Windows BLE capture；`unlock_device:270–286` 的描述对象是 TourBox Elite，通过 GATT 写入，随后配置。

上游 [TOURBOX_ELITE_PROTOCOL_SOLVED.md](https://github.com/AndyCappDev/tuxbox/blob/f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81/docs/technical/TOURBOX_ELITE_PROTOCOL_SOLVED.md) 第 27–42 行叙述 Windows/BTVS 抓包方法，第 78–89 行将该序列称为认证/解锁以便配置；第 288–302 行称 Elite USB 复用同一序列。**这是上游的逆向描述，不是本次核验了原始 Lite USB 抓包或厂商规范。** 字节含义、校验以及对 Lite 固件的适用性未获独立确认；“认证 token/session key”等仅是上游推测，不作为确定字段解释。

`usb_test_tuxbox.py:38,49–84,99` 的测试先开串口、清缓冲、发解锁、等 0.5 秒并读回复，再发多段配置，最后 read(1) 打印事件。其文件说明对象为 Elite，也不能拿它当作“Lite 单独发送解锁就能产生旋转”的证据。主实现 connect 即便无回复也继续；探测仅回复长度 >=20 就接受，并非严谨型号/成功判据。实验必须记录完整回复与行为变化，不使用“0x07 开头/26 字节”等单独判成功。

### H4. Linux 串口层的实际影响

**源码确认**：pyserial `serialposix.open:322–344` 经 os.open → _reconfigure_port → 更新 DTR/RTS → 清输入；`_reconfigure_port:376` 以后设置 raw/noncanonical、字符位、速度、关闭默认 XON/XOFF 等。Linux `acm_ctrl_msg:119–135` 用 class/interface 控制请求发送到 control 接口号；`acm_set_control:137–143` 和 acm_set_line 宏 146–147 对应控制线状态及串口编码。`acm_port_dtr_rts:666–681` 在激活/撤销时组合 DTR/RTS；`acm_tty_set_termios:1077–1116` 将波特率转 little-endian、填停止位/奇偶/位数后按需发 SET_LINE_CODING。实际调用次数和顺序受先前状态、tty 设置及内核版本影响，不能把源码阅读当 USB 抓包。

**源码确认**：Linux `acm_process_read_urb` 附近 487–502 行把 bulk URB 字节送入 tty 缓冲；notify 路径 304 行起处理 SERIAL_STATE。未见识别 TourBox 旋转码并特殊抑制/转发的代码。内核并不知道哪些字节代表 knob/scroll。pyserial raw 模式也没有“只让旋转字节通过”的特殊规则。

**推断**：Android 目前绕过 tty 层，只 open/claim，不会自动复现上述 class control 请求，因此设备看到的主机会话状态与 Linux 可能不同。DTR/RTS 或 line coding、私有初始化均是合理待测变量，优先级高于继续假设旋转在 0x81。CDC 控制端点可能只在状态变化时通知，因此 0x81 一直空闲本身不异常。

**无直接证据**：没有证据证明 Linux 驱动“生成/修复了旋转事件”，也没有证据证明当前缺失必由 DTR 或解锁导致。TuxBox 的 >=20 字节丢块规则会潜在误丢密集输入，但 Android 当前不采用它，不能用该规则解释当前原始日志中旋转完全无字节。软件流控若错误启用理论上影响 0x11/0x13 等字节，TuxBox 默认关闭；也不足解释所有旋转均无数据。

### H5. 分步最小实验设计（只设计，尚未实施）

统一前提：用户批准下一实现任务后才加入控制/OUT 路径；当前 APK 不执行本节操作。每轮确认实际 Lite CAFE:4001、CDC 描述符和 control→data 对应关系；wIndex 来自控制接口 ID=0，不从数据接口号或端点地址推导。用现有已授权方式明确确认 force=true 占用 0/1，保持读取方式、手机、线缆、操作序列和观察窗口一致。监视 0x82；0x81 仅作为状态/未知原始数据辅助观察，所有未知数据保留。

每一步人工标记时间，静置 5 秒，再做固定次数的按钮按下/松开与每个旋转件双向操作；建议每个方向 10 个明确操作，观察至少 10 秒并及时导出，避免环形日志淘汰造成假阴性。记录每端点计数/错误、请求返回值和完整原始流，区分“未收到”和“未观察到/日志不完整”。不清输入缓存以免丢失过渡期证据。

#### 实验 A：仅标准 CDC-ACM 请求，无私有 OUT

按风险/信息价值先做被动基线 A0，再分拆 A1、A2、A3；不提供一键全部初始化。

| 步骤 | 控制传输（EP0，目标 control interface 0） | 数据与来源 | 必须记录/预期观察 |
|---|---|---|---|
| A0 基线 | 不发请求 | 沿用双 IN 监听 | 复现按钮有/旋转无；若基线不能复现，先排查而不继续叠加初始化 |
| A1 line coding | bmRequestType=0x21，bRequest=0x20，wValue=0，wIndex=0，wLength=7，timeout 建议 1000ms | `00 C2 01 00 00 00 08`：115200=0x0001C200 小端，1 停止位格式码 0、无奇偶 0、8 位；来自 TuxBox 的速度、pyserial 默认8N1、Linux usb_cdc_line_coding 定义 | 返回应为 7 才记该数据阶段成功；并不证明事件模式已改变。若旋转此时出现，是 line coding 或该动作关联的变化，不归因 DTR/解锁 |
| A2 仅 DTR | bmRequestType=0x21，bRequest=0x22，wValue=0x0001，wIndex=0，wLength=0，timeout 1000ms | 无数据 payload；DTR=bit0，RTS=bit1；来自 CDC 头文件 | 无数据控制请求成功返回通常为 0。观察是否出现事件、重枚举、0x81 状态变化；若出现旋转，RTS 尚未被本步骤置位 |
| A3 DTR+RTS | 同上，wValue=0x0003 | 无 payload；本步骤保留 DTR，再置 RTS，接近 pyserial 默认状态 | 对比 A2 与 A3，观察增量；不称为自动硬件流控，也不把请求接受视为功能通过 |

可选读回：若实际 ACM functional capability 支持，独立 GET_LINE_CODING（bmRequestType=0xA1，bRequest=0x21，wValue=0，wIndex=0，wLength=7）在独立记录点读回编码；不默认强制支持，不把读回等价于设备按该速度处理事件。0x21/0x22 是标准 CDC 请求值，不是要发到 BULK OUT 的报文字节。SET_CONTROL_LINE_STATE 本身无 payload 但仍是会改变设备状态的控制操作，不属于“完全被动只读”。

隔离变量：先做累计 A0→A1→A2→A3；如果某一步改变结果，停止后从断电/拔插基线再仅加入该步骤组合重做，验证可重复。RTS 需要区分时另做“新基线+A1+RTS-only wValue=2”对照；不能从累计步骤推导顺序无关，DTR-only 会主动把 RTS 设低，记录这一状态变化。可先读回原 line coding（若支持）记录，但无通用 GET_CONTROL_LINE_STATE 能恢复未知原 DTR/RTS；不要假装保存了原始线状态。

失败与恢复：权限/描述符不符、STALL/负值、A1 短传输、设备重枚举或监听任务异常时停止本轮，保留请求和 RX 日志，不自动重试或执行下一步。单次请求使用有限超时，class 控制动作与连接生命周期由同一协调器管理，不在已关闭连接上发送。正常停止先回收所有 IN 请求，必要时在新确认的恢复动作中 SET_CONTROL_LINE_STATE=0；撤销 DTR 也可能改变/重置设备，不能偷偷在后台发送。释放接口并 close，再拔掉控制器 USB 使其断电、重新连接复测 A0；普通 close 或线状态归零不保证恢复原始固件状态。出现异步隔离时先遵循现有隔离流程，不继续发送恢复请求。

#### 实验 B：在可复现的 A 状态下，单次私有候选写入

仅在 A 的标准动作已能稳定完成、且仍有值得回答的问题时考虑 B；若 A 已使所有旋转产生数据，优先重复 A 对照，B 可以不做。B 必须单独获得实现及现场发送确认。

- 固定 A 步骤组合，发送前再记录一段基线。保持接口 0/1 的同一个有效连接，IN 接收不中断；用户明确点击并核对八字节，向接口 1 的 BULK OUT 0x02 单次发送 `55 00 07 88 94 00 1A FE`，有限超时建议 1000ms。来源仅为 H3 固定源码，不生成额外字节、换行、校验或发送94字节配置。
- 日志记录全部 OUT 数据、实际返回长度及起止时间；返回恰为 8 才记“主机数据阶段完成”，不是“设备解锁已证实”。短写/负值禁止自动补发或重发：设备可能已收到一部分，自动重试会引入重复/残留报文；停止当前轮并物理重连基线。
- 上游正式实现等待约 0.3 秒、测试脚本约 0.5 秒；本实验不 sleep 阻塞 UI，也不在此期间丢弃回复。连续捕获回复/事件，再做与 A 相同操作。不要按长度、首字节或“预期26字节”丢弃或判定成功。
- 若 A 状态稳定无旋转、B 后出现旋转且重新断电 A/B 对照可复现，支持“该候选命令或其引起的状态转换影响当前 Lite 固件上报”；仍不能解释八字节语义或推广全部固件。若无变化，只能否定本轮“标准初始化+该命令足够”的假设，不能据此认为一定还需94字节配置。
- 故障、重新枚举、异常报文或系统状态变化时停止，不扩展到其他私有命令。恢复同 A：停止回收→释放关闭→实际断电拔插→A0→固定 A。无法保证私有命令不持久化或断电必然撤销；上游源码没有提供 Lite 的可靠“反解锁”命令，因此事前告知这个未知，不用猜测的反命令恢复。

### H6. 推荐顺序与下一项实现建议

1. 固定并复现 A0，确认日志完整、按钮与旋转操作有明确时间标记。
2. 只实现 A 的手动分步标准请求和结果日志，依次验证 A1/A2/A3；每一步单独按钮/确认，自动重连不重发，任何失效都回到未初始化状态。先不实现私有命令。
3. 根据 A 的实测选择重复/单变量对照；仅在仍需验证私有初始化假设时，再以独立任务实现 B 的严格单次八字节 OUT 发送。
4. A/B 均无效时优先获取目标 Lite 的真实描述符、固件与可对照的官方/Linux USB 抓包，而不是猜测新字节、修改94字节模板或固件。

下一项 Android 任务建议为“006I：手动、分步 CDC line coding / DTR / RTS 诊断”，明确放宽仅标准控制请求这一项限制，继续禁止 BULK OUT 和私有命令；保留现有双 IN 监听、停止/隔离机制，所有控制操作有限超时且与连接清理协调。是否执行、具体按钮和恢复动作由用户审核确认，本次不实施。

本次验证仅包括：固定源码/远端版本核对、pyserial 与 Linux 代码阅读、参数小端编码及调用链检查、文档格式检查；不包含编译、任何 USB 实验或协议成功判定。
## 任务 006J：单次八字节候选实验实现边界

用户报告 A1/A2/A3 未使旋转出现，但截至本次初始检查没有找到其真实 END 返回值日志；已请求用户提供 A1/A2/A3 日志，不能据功能无变化推断控制请求成功。程序现要求当前连接内实际记录 A1=7、A2=0、A3=0 才开放候选按钮，并在界面、二次确认及 TX 日志中列出证据；历史观察不能绕过此条件。未来用户提供日志时另行补记，不把此处未知自动改为成功。

八字节仍严格来自固定 TuxBox device_usb.py:28 UNLOCK_COMMAND；源头为上游对 Elite Windows BLE 逆向的说明（见006H），不是 Lite 适用性证明。当前只实现一次 0x02 Bulk OUT/1000ms，固定55 00 07 88 94 00 1A FE；8字节返回仅表示主机侧完整数据阶段。任何非8返回、异常或失效都停止，不补发/重试，也不加入94字节配置。

双 IN 已启动并就绪后才可发送；候选确认绑定连接代号，一次机会在用户确认排队时即消耗，旧确认不能用于新连接。当前连接在候选后不再开放 CDC 追加或第二次候选。重连仅重置证据/机会，不会自动初始化或发送。程序提供完整实验文件导出，保留 TX 和随后所有正常采集的 RX 原始块，不按长度剔除或解释为按钮；日志容量/磁盘错误有显式故障并停止，不声称故障记录完整。本次未运行真机候选发送。
006J 用户补充确认：未找到 A1/A2/A3 实际 END 日志或返回值，因此历史请求结果保持未确认；不得将未出现旋转归因为标准 CDC 初始化已完成但无效。新会话必须以应用实际返回值重新核实。

## 任务 006K：旋转事件缺失专项调查（2026-11-01）

### K1. 已知结果和证据边界

本轮用户明确补充实测：A1 返回7、A2/A3返回0、固定八字节候选 OUT 返回8；两个接口已占用，按钮在0x82收到，旋转仍无数据，0x81无观察数据。此为用户报告，本轮未连接设备、核对原始日志或独立复测。此前006J“历史返回值未知”保留为当时状态，不再代表当前用户提供的信息。完整传输不证明请求语义生效或设备解锁成功。

### K2. 公开证据及可靠性

1. **TuxBox 支持不止 README 声明，但没有完整 Lite 冷启动抓包证据。** 研究快照沿用 `f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81`。README 的 Lite USB 支持表与贡献者致谢属于维护者声明；[issue #10](https://github.com/AndyCappDev/tuxbox/issues/10) 有 Lite 使用者报告按钮注册、Top+scroll wheel 组合触发以及组合误触，提供实际使用与至少滚轮相关操作的自述证据。不能扩大为所有 Lite 固件、全部旋转控件通过测试，也没有原始USB包证明初始化。 [issue #5](https://github.com/AndyCappDev/tuxbox/issues/5) 称有线 Lite 已确认；[PR #61](https://github.com/AndyCappDev/tuxbox/pull/61) 的 Lite 测试对应外观/GUI适配，不是新的初始化实现。检查公开 issues（单页少于100项）、首100条提交、Lite关键词搜索及相关评论；不能声称穷尽全部历史。讨论区可发现[招募测试的 discussion #6](https://github.com/AndyCappDev/tuxbox/discussions/6)，动态内容加载不完整，未据此证明旋转协议。
2. **VID/PID不唯一标识Lite。** [PR #64](https://github.com/AndyCappDev/tuxbox/pull/64) 的 Neo 用户也报告 CAFE:4001、产品字符串 TourBox NEO。因此后续必须记录产品、序列号、bcdDevice及实物型号，不能把同ID的Neo结果当成Lite结果。
3. **独立Lite候选证据：macOS项目。** [halfrightlab/tourbox-lite-macos](https://github.com/halfrightlab/tourbox-lite-macos/tree/1f8ffe4d290afb80ec3058f15ed9814eb81504e8)，固定提交 `1f8ffe4d290afb80ec3058f15ed9814eb81504e8`。README:35、215起声称CAFE:4001 Lite实测；`tourboxd.py:85`起事件表、`:412–418`串口115200/8N1、`:710–725`打开并read(64)，没有发现向串口写私有命令的调用。旋转码包括旋钮44/04、滚轮49/09；作者描述旋转为重复单字节而非按下/释放对。**源码确认的是代码行为，设备表现仍属作者自述。** README要求退出官方Console，未提供完全断电后不经官方软件即可工作的对照；不能排除设备先前被初始化。它反驳“所有实现都必须显式发送94字节”的断言，但不证明本机无需配置。
4. **94字节与Lite的关联证据存在，但不是Lite专属报文证明。** [Sciman101/detour](https://codeberg.org/Sciman101/detour/src/commit/0820b61cbea4f208add561f4ebb2736527399c2f) 固定提交 `0820b61cbea4f208add561f4ebb2736527399c2f`：README:4声称测试过Neo和Lite；`src/device.rs:10–18`定义94字节DEFAULT_VIB_CONFIG，`:34–39`在共享初始化中115200打开串口并write配置，没有型号分支；`src/buttons.rs`的from_code统一解析旋转码。其write未校验完整94字节，listen的两字节缓冲却只解首字节，也不能直接照搬。TuxBox同样使用共享配置；变量名“震动”及“Lite测试过”均不能证明每个字段的真实语义、旋转启用作用或安全性。**本轮绝不发送，也不建议凭此直接发送。**
5. `k-trash/tourbox_lite_driver` 提交 `5d954ca6d3b93019298e867029ef3555ffe65b3a` 的cpp为空，不能作为实现证据；`batkiz/tuxbox-macos` 提交 `ead36aee77f7ee9216a92752611ae0674ae58ccc` 是TuxBox移植，不算独立Lite抓包验证；`batkiz/tourbox` 提交 `a10e0c2f2c78188ead4328a8a7c81d2cfb5f375c` 的支持表伴随基于Elite逆向、Lite初始化可能不同的警告，不能据表格确定兼容。

本次没有找到可直接用于本设备的、具有型号/固件及冷启动过程的公开Lite官方USB原始抓包或权威初始化规格。公开事件码可作为后续对照线索，不能补足当前缺失的初始化证据。搜索未发现不等于不存在。

### K3. Windows官方软件是否还有额外初始化

[官方软件下载页](https://www.tourboxtech.com/en/downloads.html)提供Windows Console，但没有公开Lite初始化字段。未运行、反汇编官方软件，也未获取本机官方通信抓包。因此“另有初始化/配置”目前是合理假设，不能写成事实；尤其不能把共享94字节模板视为已捕获的Lite官方发送内容。Windows串口驱动可能产生标准控制请求，Console可能产生应用数据，需按时间、进程开启对照区分，不能将全部OUT都归因于Console。

### K4. Android接收审查：哪些可能漏、哪些没有证据

源码根目录：`android/app/src/main/java/io/tabletennisreview/usbdiagnostics/`。

- `TourBoxUsbManager.kt:253–284`及`DualEndpointSession.kt:79–101`：0x82读取缓冲64字节、有限200ms超时；正长度均记录完整字节，没有未知码过滤、没有TuxBox的长包丢弃规则。200ms是阻塞等待上限，不是采样间隔；64字节也不意味着更长的流必然被截断，可跨多次读取。不能由此推断必然漏掉短脉冲。
- `DualEndpointSession.kt:118–146`：0x81单独异步请求，超时保留同一个排队请求；零长度完成、null/未知请求会停止实验。0x82连续5次小于100ms的非正返回也会停止。零长度/快速失败策略可能提前停读，需查对应错误及统计；本轮未证明它实际发生。
- `DiagnosticLog.kt:29–57`：接收路径同步格式化并在共享锁内逐条appendBytes。磁盘延迟、两个端点争锁会延长两次读取之间的空隙；若设备FIFO有限，密集旋转可能溢出。这是代码可见风险，未测吞吐或证明丢包；对于持续、缓慢旋转始终零数据，解释力弱于突发漏少量事件。
- 屏幕/复制快照上限200条或约128Ki字符，按完整记录淘汰并提示；不应只凭屏幕旧记录判断没有数据。完整实验文件上限8MiB，写失败/达上限明确标记CAPTURE FAILED并触发停止，不能称该文件完整。应导出实验文件而非仅复制快照。
- `MainActivity.kt:379–387`进入后台关闭连接；旋转屏幕也会重建会话。切到ChatGPT、分享/导出界面或让应用后台后再转控制器，不是在连续监听。需要保持前台、固定方向，确认两端点已启动且读取统计持续有效。
- Android bulkTransfer的负返回本身不能精确区分所有超时/传输错误，错误期间是否有部分数据未暴露不能凭当前日志排除。本轮只做静态审查，未做压力/时序/真机验证，也未修改任何应用代码。

### K5. 原因排序（待验证假设）

1. **设备状态、固件差异或尚缺配置/初始化步骤。** 标准请求和八字节完整发送仍不足以产生旋转；上游共享配置及不同实现存在差异，值得对照，但尚不知缺什么。
2. **测试对象/启动状态差异。** CAFE:4001可共用；他人设备可能已被官方软件初始化，不能跨型号/固件/热启动照搬结论。
3. **观测链路问题。** 后台断连、读取提前停止、快照淘汰、同步磁盘写入间隙均需排除；目前没有证明这些足以解释本机所有旋转全无。
4. **硬件、固件或官方配置异常。** 尚缺“同一设备在Windows官方软件下旋转正常”的基线；若该基线失败，此项应立即上升为首要排查。
5. “旋转只走0x81”的解释已被双端点无观察削弱，且没有找到Lite专属支持证据；不能继续据此猜测端点或命令。

### K6. Windows USBPcap + Wireshark可行性与最小实验

**可行，但本轮未安装或实际捕获。** [Wireshark USB捕获说明](https://wiki.wireshark.org/CaptureSetup/USB)、[USBPcap教程](https://desowin.org/usbpcap/tour.html)和[捕获限制](https://desowin.org/usbpcap/capture_limitations.html)说明Windows可捕获USB请求块URB，通常能观察控制请求及BULK/INTERRUPT有效载荷；这不是物理总线分析，不能看到全部底层事务、NAK或电气问题。USBPcap驱动安装可能需要管理员与重启，实际Windows版本兼容性待确认；须另行授权安装。本轮没有安装驱动、替换设备驱动或修改系统。不要用Zadig替换官方/usbser驱动，这会改变所研究的软件栈。

建议下一任务只做以下被动对照，不增加Android命令：

1. **W0功能基线**：同一Lite接Windows，使用官方Console确认每个旋转控件双向实际触发，记录Console版本、设备固件（若可见）、完整描述符和原有配置。不升级固件。若官方也失败，先排设备/配置问题。
2. **W1冷启动无Console**：退出Console及其后台进程，物理拔出控制器断电。在正确USB root hub上开始完整捕获后再插入；记录空闲、按钮及各旋转动作的时间标记。保留原Windows驱动，不另开串口工具。此对照仍可能包含系统驱动初始化，不等于完全无初始化。
3. **W2冷启动有Console**：重新断电建立独立会话，先开始捕获，再启动Console并插入，或固定另一顺序但明确记录。捕获完整连接到就绪过程；空闲数秒，再逐一慢转每个控件双向各10步，按钮作参照。全程同一配置，避免同时调整设置。必要时另做有电关闭/重开Console对照，检验状态是否保留，不把热重开当冷启动。
4. **定位传输**：按物理端口、产品/序列号确认设备，再用`usb.bus_id`和`usb.device_address`过滤；重枚举可能换地址。只用VID/PID过滤会遗漏不携带描述符的数据包。端点0x00/0x80检查EP0 setup的bmRequestType、bRequest、wValue、wIndex、wLength和数据；区分CDC标准类请求与厂商请求。0x02观察BULK OUT的真实长度、完整数据、时序和状态；0x82观察按钮/旋转对应BULK IN；0x81记录INTERRUPT IN，但不预先认定含旋转。
5. **比较和保存**：用URB ID关联提交/完成，避免重复计算同一传输；记录帧号、时间、长度和完成状态。对照有无Console的控制请求和OUT序列，检查八字节/94字节是否真的出现，也允许发现完全不同数据。保留原始pcapng、动作时间表与完整payload，确认未截载荷或丢捕获。不要只发截图，更不要直接重放发现的报文。
6. **恢复及风险**：停止捕获、正常退出软件并拔插设备；不保证官方软件不会保存既有配置，因此不改设置/固件。抓整个hub可能含其他USB设备数据，选择尽量独立端口、避免输入敏感内容，分享前仅保留目标设备所需数据。Android与Windows不能同时作为同一控制器的USB主机，分别测试并保持硬件记录对应。

若W2旋转正常且0x82能看到数据，可获得真正的Lite对照证据；若官方正常但抓不到，先检查root hub、地址、捕获范围及是否重枚举，不宣称设备无数据。确认初始化差异后再提出单一、可审查的Android实验，仍不直接尝试94字节模板。

### K7. 本轮实际执行与未执行

实际执行：检查Git状态与项目规则；阅读Android接收/生命周期/日志源码；获取公开GitHub issue/评论/部分提交信息；在项目外临时目录浅克隆候选小型源码，仅阅读；核对官方抓包文档；补充本报告与CHANGELOG。未运行第三方项目、安装依赖或抓包驱动、编译APK、运行USB实验、修改Android代码、修改固件或创建Git提交。上游自述、源码行为、用户实测与本地验证在上述各节分别标明。
