# TuxBox 与 TourBox Lite USB 协议研究

研究日期：2026-11-01。仅源码研究，未连接设备、未运行驱动、未编译 APK。推荐设计均待用户审核。

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
