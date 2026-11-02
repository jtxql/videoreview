# TableTennis Review

用于乒乓球比赛视频复盘的Android应用。**007已按已知限制归档；主工程播放和TourBox映射基本正常（用户反馈），现已实现自动初始化与横屏全屏，待新版真机验收。** TourBox Lite协议与输入事件已在目标真机验证；物理拔出自动恢复作为USB-001延期。已按用户批准接入libmpvKt0.3.0与独立播放器适配模块；主应用已安装，用户反馈视频播放与USB读取正常；新版本在Lite初始化成功后默认勾选控制，并提供选片自动出首帧、进度条与横屏全屏手势。

## 目标设备与功能

- 小米14 Ultra，USB-C有线TourBox Lite；运行不要求Root，不依赖电脑中介。
- 播放本地视频，播放/暂停、快进/快退、前进/后退一帧。
- 0.1x、0.25x、0.5x、0.75x、1x等速度与A/B区间循环。
- 通过TourBox旋钮、滚轮和按键触发播放器操作（映射基本正常，新自动化/全屏待真机验收）。

## 007归档状态

`android/`现含`:app`宿主和`:player-mpv`播放器模块。应用名TableTennis Review，保留USB诊断页并增加播放器页，同一Activity切换；不要求Root或电脑作运行时中介。支持枚举/授权、单接口连接、受控force=true、双IN读取、原始日志及导出，和独立的类型安全输入事件层。

007已验证流程为：双接口claim/启动读取→A1 115200/8N1→A0控制线0→单次固定8字节→完整26字节回复验证→二次确认后单次Windows原样94字节配置→0x82输入。四方向旋转及已测试按钮按下/松开、回复分流、正常停止回收和同地址正常重开已通过；007-Y的134条语义与原始字节逐一对应。详见[最终验收](docs/TOURBOX_INPUT_ACCEPTANCE_007_FINAL.md)与[输入接口](docs/TOURBOX_INPUT_EVENTS.md)。

**已知限制：[USB-001](docs/KNOWN_ISSUES.md)。** 物理拔出时异步请求可能无法确认回收，Strict策略下可能拒绝补充观察并隔离；当前进程可能无法再连接。保留隔离，不强制释放。自动恢复、5秒拔出后观察及完整压力/资源趋势测试未通过或未完成，不宣称所有生命周期场景通过。完全结束进程后重启仅为当前恢复建议，不保证底层资源已释放；关闭Activity/划掉最近任务不等于可靠结束进程。

007原诊断模式不自动初始化；当前已按用户新要求复用成功流程自动初始化，仍无自动重发或任意USB命令入口，不发送动态16字节命令，不修改固件；007 USB层无串口驱动库/JNI/NDK实现；播放器所需原生库来自固定预编译AAR，不改变USB协议。007已结束，USB-001 Deferred，不阻塞008。

## 008-B播放器集成

已按批准方案完成本地SAF选片、自动初始化（保留手动重载）、播放/暂停、±5秒、前后逐帧、五档速度、AB标点/启用后自动循环/清除与回A（AB暂停时也可逐帧）；命令/状态通过独立PlayerController接口，与USB解耦。TourBox控制：Knob前后逐帧/按下切播放，Scroll±5秒/按下循环五档速度，C1记A、C2记B、Top启用/清除AB；横竖屏均有效。现在前台发现唯一Lite后自动申请系统权限并按A1→A0→8字节→核实26字节→94字节初始化，系统授权后默认勾选控制（实际动作等待初始化完成）；初始化失败不重试；播放器32项FIFO过载时优先淘汰旧旋转，过期动作单独跳过并计数，普通积压不取消勾选。详见[自动连接与全屏操作](docs/PLAYER_AUTOMATION_FULLSCREEN.md)，[首版映射记录](docs/TOURBOX_PLAYER_MAPPING.md)保留历史。

用户已满意结束项目外探针验证；主应用已安装且用户反馈基本播放/USB读取正常；详细精度、压力与生命周期边界保留，不把该反馈当成相邻帧/AB精度全部通过。详见[008-B实施记录](docs/PLAYER_MAIN_INTEGRATION.md)、[专项证据](docs/PLAYER_REVIEW_VALIDATION.md)，Task1[源码研究](docs/MPV_RESEARCH.md)保留历史。

默认播放器页，顶部切换USB诊断。两页标题显示实际安装包的版本号及构建号，可据此确认升级；全屏仍隐藏标题。选择视频后自动初始化并暂停显示首帧；进度条可拖动定位。勾选横屏全屏后隐藏控件，单击显示/隐藏悬浮进度；横竖屏均可双击左侧后退、中间播放/暂停、右侧快进，五档倍速分别跳1/2/3/4/5秒。视频滑动不跳转，保留拖动进度条；横屏左侧纵滑调五档速度、右侧纵滑调系统媒体音量，右上角持续显示倍速及当前位置/总时长，系统返回键退出全屏。离开播放器页请求暂停，进行中操作则撤销/释放；后台释放播放器并沿用原USB停止策略。系统选片器/导出器可能触发后台并停止USB，普通标签切换不主动关闭USB。选片器返回前台后会按需自动重建USB连接；全屏方向切换不重建Activity。播放器日志与USB日志独立，完整播放器记录在播放器页导出。

预编译依赖及哈希记录见[dependency-manifest](android/player-mpv/dependency-manifest.json)；APK带第三方许可文本，GPL原生组合的正式发布材料尚待审计，未据此擅自设置项目根许可证。

## 构建与无设备检查

固定AGP 9.2.1、Gradle 9.4.1、内置Kotlin支持＋显式KGP 2.4.21、Studio JBR25，字节码17；compile/target SDK37、minSDK26、Build-Tools36.0.0。SDK路径只在被忽略的local.properties中；Android Studio打开`android/`并选择兼容JDK。

在`android/`下执行（离线需已有依赖缓存）：

```powershell
.\gradlew.bat :player-mpv:testPlayer :app:testTourBoxPlayerBinding :app:testAutoInit :app:testPlayerUi :app:testInputEvents :app:testConfig94 :app:testUsbAsyncCleanup :app:testUsbGenerations :app:assembleDebug :app:lintDebug :player-mpv:lintDebug --offline --no-daemon --console=plain
```

KGP先单独升级后原检查通过，集成后上述JVM检查/debug/Lint也通过，不代表IDE GUI Sync、主应用出画、USB编译后真机回归或全部生命周期已验证。APK位于`android/app/build/outputs/apk/debug/app-debug.apk`，不纳入Git。原始日志、抓包、Python缓存、签名密钥、本机配置及构建产物均不提交。

历史诊断步骤见[工程说明](docs/USB_DIAGNOSTICS.md)；当前进度见[ROADMAP](ROADMAP.md)，记录见[CHANGELOG](CHANGELOG.md)，规则见[AGENTS](AGENTS.md)。
