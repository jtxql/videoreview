# TableTennis Review

用于乒乓球比赛视频复盘的 Android 应用，当前处于环境与文档初始化阶段。

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
- Android 无 Root USB 访问、设备识别、权限申请、数据收发及 TourBox Lite 兼容性均待真机验证。

以上方案未完成可行性验证，不构成已确定架构或功能保证。

## 当前状态与构建

仅完成项目文档和 Git 基础初始化，尚无 Android 工程、播放器代码或依赖配置，尚未下载候选项目源码。

编译步骤：**待验证**。当前没有可执行的构建流程，也未生成 APK；后续研究明确工具链及版本后再补充经过验证的命令。

阶段计划见 [ROADMAP.md](ROADMAP.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)，开发规则见 [AGENTS.md](AGENTS.md)。
