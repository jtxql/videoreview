# TableTennis Review

用于乒乓球比赛视频复盘的Android应用。**007：Completed with known limitation；008：Ready to start。** TourBox Lite协议与输入事件已在目标真机验证；物理拔出自动恢复作为USB-001延期。播放器尚未实现，最终底座/架构待确认。

## 目标设备与功能

- 小米14 Ultra，USB-C有线TourBox Lite；运行不要求Root，不依赖电脑中介。
- 播放本地视频，播放/暂停、快进/快退、前进/后退一帧。
- 0.1x、0.25x、0.5x、0.75x、1x等速度与A/B区间循环。
- 后续通过TourBox旋钮、滚轮和按键触发播放器操作。

## 007归档状态

`android/`当前是Kotlin/系统View的TourBox USB Diagnostics工程，不是视频播放器。支持枚举/授权、单接口连接、受控force=true、双IN读取、原始日志及导出，和独立的类型安全输入事件层。

成功流程保持用户手动执行：双接口claim/启动读取→A1 115200/8N1→A0控制线0→单次固定8字节→完整26字节回复验证→二次确认后单次Windows原样94字节配置→0x82输入。四方向旋转及已测试按钮按下/松开、回复分流、正常停止回收和同地址正常重开已通过；007-Y的134条语义与原始字节逐一对应。详见[最终验收](docs/TOURBOX_INPUT_ACCEPTANCE_007_FINAL.md)与[输入接口](docs/TOURBOX_INPUT_EVENTS.md)。

**已知限制：[USB-001](docs/KNOWN_ISSUES.md)。** 物理拔出时异步请求可能无法确认回收，Strict策略下可能拒绝补充观察并隔离；当前进程可能无法再连接。保留隔离，不强制释放。自动恢复、5秒拔出后观察及完整压力/资源趋势测试未通过或未完成，不宣称所有生命周期场景通过。完全结束进程后重启仅为当前恢复建议，不保证底层资源已释放；关闭Activity/划掉最近任务不等于可靠结束进程。

无自动初始化或重发，无任意USB命令入口，不发送动态16字节命令，不修改固件；无串口驱动库/JNI/NDK/mpv集成。007已结束，USB-001 Deferred，不阻塞008。

## 008准备与候选技术

008仅准备开始Android播放器基础功能：本地文件、播放暂停、变速/慢动作、双向逐帧、快进快退、AB循环；后续订阅007语义事件，无需重写USB协议。本轮不实现008代码。

Android应用层采用Kotlin。mpv-android仍是候选播放器参考，见[mpv源码研究](docs/MPV_RESEARCH.md)；编译/真机集成、逐帧精度及许可边界待验证。TuxBox是协议参考，见[协议研究](docs/TOURBOX_RESEARCH.md)，开源声明不等于全部Lite行为保证。最终播放器架构和重要方案仍先征求确认。

## 构建与无设备检查

固定AGP 9.2.1、Gradle 9.4.1、内置Kotlin 2.2.10、Studio JBR25，字节码17；compile/target SDK37、minSDK26、Build-Tools36.0.0。SDK路径只在被忽略的local.properties中；Android Studio打开`android/`并选择兼容JDK。

在`android/`下执行（离线需已有依赖缓存）：

```powershell
.\gradlew.bat :app:testInputEvents :app:testConfig94 :app:testUsbAsyncCleanup :app:testUsbGenerations :app:assembleDebug :app:lintDebug --offline --no-daemon --console=plain
```

命令行构建和上述JVM检查已执行通过，不代表IDE GUI Sync、播放器或全部设备生命周期验证通过。APK位于`android/app/build/outputs/apk/debug/app-debug.apk`，不纳入Git。原始日志、抓包、Python缓存、签名密钥、本机配置及构建产物均不提交。

历史诊断步骤见[工程说明](docs/USB_DIAGNOSTICS.md)；当前进度见[ROADMAP](ROADMAP.md)，记录见[CHANGELOG](CHANGELOG.md)，规则见[AGENTS](AGENTS.md)。
