# TableTennis Review

用于乒乓球比赛视频复盘的 Android 应用，阶段 2A：Android USB 基础通信与初始化探索已完成，旋转协议仍未解决。播放器最终架构尚未确定。

## 目标设备

- 手机：小米 14 Ultra。
- 控制器：USB-C 有线 TourBox Lite。
- 运行约束：不要求 Root，不依赖电脑作为运行时中介。

## 计划功能

- 播放本地视频，支持播放、暂停、快进和快退。
- 前进和后退一帧。
- 支持 0.1x、0.25x、0.5x、0.75x、1x 等播放速度。
- A/B 区间循环播放。
- 通过 TourBox Lite 的旋钮、滚轮和按键控制播放器。

## 候选技术与待验证事项

Android 应用层优先考虑 Kotlin，但最终架构尚未确定。

- mpv-android：https://github.com/mpv-android/mpv-android —— 候选播放器参考，需研究构建方式、集成边界、逐帧与变速能力及许可要求。
- TuxBox：https://github.com/AndyCappDev/tuxbox —— 候选 USB 协议参考，需研究设备适配范围、协议信息和许可要求。
- Android USB 枚举、授权、打开、强制占用接口及按钮接收已有用户真机结果；旋转事件、完整协议兼容性与稳定性仍待验证。

以上方案未完成可行性验证，不构成已确定架构或功能保证。

## 当前状态与构建

阶段 1、1.5 源码研究及阶段 2A 诊断探索已完成；播放器最终架构尚未确定。用户真机确认 Lite CAFE:4001 可枚举、授权、打开并以 force=true 占用接口 0/1。双 IN 实验中，0x82 收到所有按钮按下/松开的单字节数据，0x81 未观察到数据，全部旋转控件仍无数据。

A1/A2/A3 标准 CDC 请求分别返回 7/0/0；固定八字节候选经 0x02 单次发送返回 8，均未使旋转出现。这些是主机侧传输结果，不代表解锁成功。同一设备在 Windows 官方 Console 中所有旋转控件正常。完整实测表及证据边界见 [TourBox 研究总结](docs/TOURBOX_RESEARCH.md#阶段-2a-当前检查点任务-006l)。下一阶段是 [Windows 被动抓包对照](docs/USB_CAPTURE_PLAN.md)，不直接尝试94字节配置。

`android/` 是独立的 **TourBox USB Diagnostics** 工程，使用 Kotlin 和系统 View，具备枚举、授权、单接口连接、受控 force=true、单/双 IN 原始监听、三个手动 CDC 请求，以及二次确认后每连接最多一次的固定候选发送。日志包含独立关键结果、原始 HEX、计数、测试标记及文件导出。它不是播放器，不解析或映射 TourBox 事件。

安全边界：不自动初始化或重发，不提供任意 USB 写入，不发送94字节配置，不修改固件，不集成 mpv，不引入串口库、JNI/NDK。后台或旋转会结束连接；异步请求无法确认回收时隔离连接，禁止继续使用。生命周期极端竞态及高频接收完整性仍待专项验证。

工具链固定为 AGP 9.2.1、Gradle 9.4.1、AGP 内置 Kotlin 2.2.10、Studio JBR 25，字节码目标17；compile/target SDK 37、min SDK 26、Build-Tools 36.0.0。SDK路径仅保存在被忽略的 local.properties。

Android Studio 打开 `android/` 并选择兼容 JDK。命令与检查结果见 [诊断工程说明](docs/USB_DIAGNOSTICS.md)；debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`，构建产物不提交。命令行构建通过不等于 IDE GUI Sync 或新增真机测试通过。

阶段计划见 [ROADMAP.md](ROADMAP.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)，开发规则见 [AGENTS.md](AGENTS.md)。
