# mpv-android 技术可行性研究

研究日期：2026-11-01。性质：源码与官方文档研究，未编译、未安装、未进行真机测试。本文推荐均待用户审核，不确定最终架构。

## 1. 结论摘要与证据范围

**结论：适合作为优先验证的播放器候选，但尚不能确认满足全部复盘体验要求。** 通用 JNI 接口足以表达所需命令，预计 TourBox 映射不需要修改 mpv 核心。主要不确定性是后退逐帧延迟与精度、循环边界、0.1x 音频表现和目标真机硬解兼容性。

- **源码确认**：mpv-android 是完整 Android 应用，README 明确说明不是可直接导入的 library/AAR。UI 主要为 Kotlin，包含 Java 文件选择器和 C++ JNI。
- **源码确认**：命令、属性读写及事件观察已有桥接；专用逐帧和 A/B 触控界面未在 main 源码检索中找到。默认键盘绑定的能力不等于已有触控按钮。
- **根据源码推断**：可以增加应用层命令适配器，将独立 USB 模块事件映射到播放器；尚未证明 TourBox Lite 能在 Android 无 Root 环境正常通信。
- **待验证**：构建成功、视频格式支持、逐帧实际误差及延迟、USB 与播放并发稳定性。

### 研究版本与复现边界

mpv-android 浅克隆于项目外临时目录 `%TEMP%/tabletennis-mpv-research-003/mpv-android`，没有运行依赖下载或构建脚本，也未修改其源码。

- Android 源码提交：`255901dd586fa750000fa0075f69d6ffdd26cd9c`，提交时间 `2026-10-30T16:58:11+01:00`。
- 补充读取 mpv 核心提交：`48fca90e8ce5011d0a4c689178ad739329ea253d`，仅下载 `player/playloop.c`、`player/command.c`、`DOCS/man/input.rst`、`DOCS/man/options.rst` 和 HEAD 元数据。
- 该核心提交是独立研究快照，**不是已证明被上述 Android 提交打包使用的核心版本**。未构建 native 库，无法提供实际链接版本；后续必须固定并记录全部依赖提交。

固定源码入口：[Android 源码树](https://github.com/mpv-android/mpv-android/tree/255901dd586fa750000fa0075f69d6ffdd26cd9c)、[mpv 核心源码树](https://github.com/mpv-player/mpv/tree/48fca90e8ce5011d0a4c689178ad739329ea253d)。下文 Android 路径均相对此 Android 快照；核心路径相对上述核心快照。

## 2. 工程架构

### Android 与 native 分层

`app/src/main/java/is/xyz/mpv/` 放置主要 Kotlin 应用代码，`is/xyz/filepicker/` 包含 Java 文件选择与文档提供器逻辑。传统 Activity、Fragment、XML 布局及 ViewBinding 构成界面，非 Compose 架构。`res/layout/`、`res/menu/`、`res/xml/` 分别承载布局、菜单和偏好设置。

`MPVActivity` 负责播放界面、交互、生命周期与状态更新；`FilePickerActivity`、`MainScreenFragment` 处理选片入口；`BackgroundPlaybackService` 与媒体会话相关逻辑处理后台播放。`MPVView` 配置播放器、提供属性便捷封装和键盘映射；`BaseMPVView` 是 SurfaceView，管理 native 初始化和 Surface 绑定。

`MPVLib` 加载 `mpv` 和 `player` 动态库，声明 Kotlin external 方法。`app/src/main/jni/` 的 C++ 将其转为 libmpv client API：`main.cpp` 管理实例与命令，`property.cpp` 处理属性，`render.cpp` 处理 Surface，`event.cpp` 用独立 pthread 调用 `mpv_wait_event` 并回调 Kotlin observer。回调线程不是 UI 线程，未来更新控件须切回主线程。

native 使用全局 `g_mpv` 单实例；不能直接假定支持多播放器。`BaseMPVView.surfaceDestroyed` 还保留 Surface 解绑竞态的 FIXME，集成时需关注后台、旋转和销毁顺序。

### 构建与依赖（配置确认，未验证执行）

- 根 `build.gradle`：AGP `9.2.1`，Kotlin 版本变量 `2.2.21`；Gradle wrapper `9.4.1`。
- `app/build.gradle`：compile/target SDK 36，min SDK 23；allstorage flavor min SDK 30；ViewBinding。AndroidX AppCompat 1.7.1、RecyclerView 1.4.0、Media 1.8.0、Preference 1.2.1、Material 1.14.0。
- `buildscripts/include/depinfo.sh`：NDK r30 / `30.0.16248370`，SDK platform 36，build tools 36.0.0；Gradle 从生成的 `ndk.properties` 读取 NDK 位置。
- `.github/workflows/build.yml` 声明 Java 17；其与当前 AGP/Gradle 组合的实际兼容性需构建核实，不能据此声称 Java 17 已够用。
- native 依赖含 mpv、FFmpeg、libass、libplacebo、字体/文本库、Lua、TLS/网络库等；脚本涉及 Meson、Ninja、autoconf、pkgconf、libtool、Python 等工具。
- `jni/Android.mk` 导入预构建共享库并编译 `libplayer`；`buildscripts/scripts/mpv.sh` 开启 libmpv、关闭命令行播放器，采用 Meson/Ninja；native 构建独立于 Gradle，单跑 Android Studio 不会生成全部 native 库。
- 上游 `buildscripts/README.md` 明确支持 Linux/macOS，说明 Windows 或 WSL 不可用。本项目当前 Windows 环境的构建安排须另行确认，不能直接给出可用编译承诺。
- 支持 arm64-v8a 等 ABI；目标真机应优先验证 arm64。未安装 SDK/NDK/JDK 或其他依赖。

参考：[构建说明](https://github.com/mpv-android/mpv-android/blob/255901dd586fa750000fa0075f69d6ffdd26cd9c/buildscripts/README.md)、[依赖配置](https://github.com/mpv-android/mpv-android/blob/255901dd586fa750000fa0075f69d6ffdd26cd9c/buildscripts/include/depinfo.sh)。

## 3. 播放功能支持情况

“桥接可调用”表示已有通用接口可传递命令，不表示已在 Android 实机验证对应核心能力。

| 功能 | mpv 核心 | Android 层接口 | 当前 Android UI / 所需改动 |
|---|---|---|---|
| 本地视频 | 支持 loadfile | BaseMPVView.playFile、MPVLib.command | 已有选片、路径/content URI 入口；需验证授权、provider 可 seek 性和格式 |
| 播放/暂停 | pause 属性、cycle pause | MPVView.paused、cyclePause | 已有按钮与媒体键，无需核心改动 |
| 快进/快退/跳转 | seek 支持相对、绝对、keyframes、exact | 通用 command，MPVView.timePos | 已有手势、进度条及键盘；可增设复盘专用步长；此处是时间跳转而非倒放 |
| 前进一帧 | frame-step | command 可传；无专用 Kotlin 方法 | 未找到专用触控 UI；键盘字符可转发至核心默认绑定；增加命令适配及按钮 |
| 后退一帧 | frame-back-step（核心快照等价 frame-step -1 seek） | command 可传；无专用 Kotlin 方法 | 同上；需节流、状态反馈，实测精度与延迟 |
| speed | 支持 0.01—100，覆盖全部目标速度 | playbackSpeed / setPropertyDouble | 已有速度滑块，最低 0.2x；循环预设为 0.5/0.75/1/1.25/1.5/1.75/2；需增加 0.1/0.25 等明确预设 |
| A/B loop | ab-loop 命令及 ab-loop-a/b 属性 | 通用 command、double/string 属性接口 | 未找到专用 A/B UI、状态封装；增加标点、清除、显示及文件切换策略；核心默认键盘 l 可间接操作 |
| 当前时间 | time-pos，time-pos/full | MPVView.timePos 使用 Double 获取 full | 已有时间显示，但观察 time-pos 使用 INT64；帧级 UI 应新增 Double 观察，不能沿用整数秒显示作为精度证据 |
| 视频帧率 | estimated-vf-fps 等属性 | MPVView.fps 读取 estimated-vf-fps | 已有 getter，未确认专门 FPS 控件；容器帧率可通过通用属性访问，但须核对实际核心版本和可用性 |

估算 FPS 不是精确帧编号；VFR 视频不能用 `time + 1/fps` 保证逐帧。上述核心定义见[固定版命令及属性文档](https://github.com/mpv-player/mpv/blob/48fca90e8ce5011d0a4c689178ad739329ea253d/DOCS/man/input.rst)和[选项文档](https://github.com/mpv-player/mpv/blob/48fca90e8ce5011d0a4c689178ad739329ea253d/DOCS/man/options.rst)。

## 4. 关键类、方法与调用链

以下行号是所研究快照的定位线索，最终以固定版本源码为准。

- `MPVLib.kt:18–39`：create/init/destroy、command、属性读写与 observeProperty；EventObserver 接收属性和事件。
- `BaseMPVView.initialize` → create → initOptions → init；`surfaceCreated` → attachSurface → `command(loadfile)`；销毁时解绑 Surface 并终止实例。
- `MPVActivity.translateContentUri`（约 1123 行）用 ContentResolver 打开描述符尝试解析真实路径，否则保留 content URI 交由 FFmpeg 打开；不是所有 provider 都保证可随机定位。
- `MPVActivity.dispatchKeyEvent`（约 796 行）→ 拦截部分按键 → `MPVView.onKey`（135 行）→ `keydown`/`keyup` 命令。打印字符回退映射和修饰键已有处理；Android repeat 被吞掉，mpv 自己重复按键。
- `MPVView`：paused（278 行）、timePos（282 行）、playbackSpeed（290 行）、fps（302 行）、cyclePause（346 行）、cycleSpeed（约 351 行）。
- `SpeedPickerDialog.toSpeed/fromSpeed`：滑块下界 0.2；`MPVActivity.pickSpeed` 附近使用通用 picker 写 speed；不能只改循环数组就认为滑块支持 0.1。
- `main.cpp` 的 JNI command → 同步 `mpv_command(g_mpv, arguments)`，返回类型 void 且丢弃核心返回码；需要可靠错误反馈时应完善桥接或日志/状态确认。
- `property.cpp` → libmpv 属性 API；`event.cpp:event_thread` → mpv_wait_event → MPVLib.eventProperty/event → observer → Activity 状态更新。
- 核心 `player/playloop.c:add_step_frame`：正向播放式步进累计 step_frames；反向/seek 路径排入 MPSEEK_FRAMESTEP、MPSEEK_VERY_EXACT 并暂停；已有 hrseek 时不重复排入该路径，密集旋钮事件不能假定每次均会完成一帧。

参考：[MPVView](https://github.com/mpv-android/mpv-android/blob/255901dd586fa750000fa0075f69d6ffdd26cd9c/app/src/main/java/is/xyz/mpv/MPVView.kt)、[JNI command](https://github.com/mpv-android/mpv-android/blob/255901dd586fa750000fa0075f69d6ffdd26cd9c/app/src/main/jni/main.cpp)、[核心步进逻辑](https://github.com/mpv-player/mpv/blob/48fca90e8ce5011d0a4c689178ad739329ea253d/player/playloop.c)。

## 5. Android 控制示意（伪代码，不是本项目实现）

前提：libmpv 已初始化、文件和 Surface 就绪；所有调用受生命周期保护，由统一控制队列调度；具体线程策略待验证。

```kotlin
MPVLib.command(arrayOf("loadfile", authorizedLocalPathOrContentUri))
MPVLib.setPropertyBoolean("pause", true)  // false 恢复
MPVLib.command(arrayOf("cycle", "pause"))
MPVLib.command(arrayOf("seek", "5", "relative+exact"))
MPVLib.command(arrayOf("seek", "-5", "relative+exact"))
MPVLib.command(arrayOf("seek", "12.5", "absolute+exact"))
MPVLib.command(arrayOf("frame-step"))
MPVLib.command(arrayOf("frame-back-step"))
MPVLib.setPropertyDouble("speed", 0.1) // 同理 0.25/0.5/0.75/1.0
val time = MPVLib.getPropertyDouble("time-pos/full") // 可能为空
val fps = MPVLib.getPropertyDouble("estimated-vf-fps") // 非精确帧号
// 校验 0 <= a < b，先禁用旧循环再更新两个端点
MPVLib.setPropertyString("ab-loop-b", "no")
MPVLib.setPropertyDouble("ab-loop-a", a)
MPVLib.setPropertyDouble("ab-loop-b", b)
MPVLib.setPropertyString("ab-loop-a", "no") // 清除
MPVLib.setPropertyString("ab-loop-b", "no")
MPVLib.observeProperty("time-pos", MPV_FORMAT_DOUBLE)
```

命令返回不等于画面已完成定位；需结合属性、seek/playback-restart 事件与实际图像验证，不能仅以 JNI 无异常判断成功。

## 6. TourBox 接入与预计修改

本次未研究 USB 私有协议，也未读取 TuxBox 源码。

Android 有两条不同输入路径：若设备被系统识别为标准输入设备，可通过 KeyEvent、MotionEvent（例如 ACTION_SCROLL 的轴值）接入 Activity/View；mpv-android 已有键盘转发，但不能假定 TourBox 的旋钮与滚轮必然表现为系统输入事件。若需要原始 USB 通信，则通过 UsbManager 枚举、申请用户权限、openDevice、claimInterface，再按端点类型使用 UsbRequest 或适用的传输 API，最后把解析结果转换为应用事件。权限、接口占用、传输类型和设备初始化均待阶段 1.5/2 验证。

官方依据：[Android USB Host](https://developer.android.com/develop/connectivity/usb/host)。该 API 路径不以 Root 为前提，但不保证所有设备接口均可被应用访问。不要把直接读取 `/dev/input` 作为无 Root 方案。

建议候选分层：USB 连接与解析 → 控制器事件 → 映射/节流/组合键策略 → 播放器抽象接口 → mpv 适配器。USB 模块不依赖 MPVActivity 或 JNI；播放器不解析 USB 报文。按键重复、旋钮高速输入、后台断连和界面销毁须统一处理。此方案理论上无需改 mpv 核心，也无需电脑运行时中介，仍需用户确认及后续验证。

| 修改模块 | 预计难度 | 说明 |
|---|---|---|
| 播放器适配器与速度预设 | 低至中 | 复用命令/属性接口，补空值、状态及错误处理 |
| 帧步进、A/B 复盘 UI | 中 | 增加控件、精细时间显示、标点校验及状态同步 |
| 命令调度和 JNI 错误反馈 | 中 | 防止密集 seek 丢失意图，保障生命周期与线程边界 |
| USB 传输和协议模块 | 尚不能估计 | 私有协议、权限和真机兼容性未研究，不用 UI 改动难度代替协议难度 |
| native 构建、依赖固定和发布合规 | 中至高 | 当前 Windows 环境与上游工具链不匹配，需确定构建环境及依赖版本 |

## 7. 精度与主要风险

1. **后退逐帧**：核心快照中 frame-back-step 等价 frame-step -1 seek；是精确 seek 尝试，不是保留完整反向解码帧缓存。官方文档说明可能很慢或异常，受精确 seek 能力、滤镜时序等影响；VFR 的其他帧数 seek 更不可靠，-1 是特殊处理。不能将其解释为实时连续倒放。
2. **编码与关键帧**：根据解码依赖推断，长 GOP、B 帧和索引质量会影响 seek 的预滚解码成本；精确 seek 不是只显示最近关键帧，但也不承诺每种编码都能准确快速。需比较硬解与软解、文件头尾及连续反向操作。
3. **A/B 边界**：核心使用时间戳，播放越过 B 后回到 A；主动 seek 越过 B 不一定触发循环，是文档定义的行为。两个端点均设定才启用；不能把浮点秒配置能力等同于严格逐帧闭区间或无缝音频循环。需核查首尾显示帧、重复/漏帧、音频尾部和间隙。
4. **低速与高帧率**：核心 speed 范围覆盖 0.1，但音频变速滤镜、音画同步、解码吞吐、显示刷新率和热降频均需验证。120/240 fps 素材不代表屏幕能在 1x 显示全部帧；慢动作文件也可能只以 30/60 fps 时间轴存储，须检查真实时间戳。软件解码与复杂渲染可能增加耗电和延迟。
5. **文件与状态**：content provider 不可 seek、权限失效、后台 Surface 重建、全局 native 实例和同步 JNI 调用均可能影响交互稳定性。
6. **许可**：Android 仓库 LICENSE 是 MIT；不等于整个 APK 仅受 MIT 约束。libmpv、FFmpeg 及可选依赖的实际构建选项、许可证及源码提供义务需发布前逐项核查，本次未完成全依赖许可审计。

## 8. 推荐方案（待用户确认）

优先保留 libmpv + Kotlin 控制层作为验证方向，以 mpv-android 的 MPVLib/BaseMPVView/JNI 为参考。先验证现有核心能力，不预先修改核心。完整 fork 可复用选片和播放界面但维护面较大；抽取最小桥接可控制范围但需自行承担生命周期、native 构建和许可整合。两者暂不作最终取舍，不把 mpv-android 当作现成 Maven/AAR 依赖。

阶段顺序建议仍为 1.5 协议研究 → 2 最小 USB 真机验证。播放器构建验证涉及 Linux/macOS 构建环境和大型依赖，应另行获准后安排。

## 9. 下一阶段验证清单与本次实际检查

- 固定实际 libmpv/FFmpeg 提交及构建配置，确认 JDK/AGP/Gradle/NDK 组合和 arm64 产物。
- 用带可见帧号的 30/60/120/240 fps、CFR/VFR、H.264/HEVC、长短 GOP 素材对比软硬解；记录正反向每步目标帧、实际帧和延迟分布，包含首尾边界与快速旋钮输入。
- 测试全部目标速度的图像、音频、同步、掉帧和持续播放温升；明确 0.1x 是否需要静音策略。
- 测量 A/B 端点误差、循环间隙及跨端点 seek；用观察事件与可见帧交叉验证，不以整数秒进度条作为依据。
- 检查本地路径及 SAF content URI 的授权、重开和随机定位能力。
- 阶段 1.5 调查协议，阶段 2 验证小米 14 Ultra USB Host、供电、权限与端点可访问性；尚未确认 TourBox 可产生标准输入事件。

实际执行：初始 git status 干净；浅克隆并读取固定 Android 源码；检索关键类、JNI、构建脚本及 UI；补充读取固定核心源码/文档和 Android 官方 USB Host 文档。研究仓库 git status 无改动。未运行 Gradle、构建脚本、自动化测试、设备测试或性能测试。主项目文档检查结果见本次交付报告。
