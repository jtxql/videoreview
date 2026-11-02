# 预编译 libmpv 库第一轮验证

日期：2026-11-02。用户已同意优先验证预编译 libmpv；这不是最终底座或升级工具链的批准。本轮不修改 Android 工程，不安装 APK，不访问 ADB/USB，不提交 Git。

## 1. 结论与验证边界

优先候选 **libmpvKt 0.3.0** 确实有可下载的 Android AAR，不是只有构建脚本。真实发布包包含 arm64 原生链，发布源码有逐帧、速度和 AB 接口。因此有机会省去自行 Linux 构建；但**尚未证明本项目可以直接编译引用，更未验证手机播放**。

关键门槛：发布 POM 声明 Kotlin stdlib 2.4.10、协程 1.11.0；本项目 AGP 9.2.1 内置 Kotlin 2.2.10。依赖版本差异不等于已复现编译失败，但不能因 README 仅列 JVM/SDK 门槛便宣布兼容。不得盲目强制降 stdlib、跳过 metadata 检查或自动升级项目。

## 2. 固定来源与实际发布链

- 项目：https://github.com/yuroyami/libmpvKt
- 版本：v0.3.0；已通过 GitHub commit API 确认标签对应 `b001dd97538e7d3e700df80029fd72be99ceba3f`，本次 main 同一提交。
- Maven 仓库：https://yuroyami.github.io/maven/ （不是 Maven Central）。应限定 group/module 范围并固定版本，后续配置依赖校验。
- 拟验证依赖：`io.github.yuroyami:libmpvkt-view:0.3.0`，普通 Android View，无须 Compose。
- 实际变体链：libmpvkt-view → libmpvkt-view-android → libmpvkt-android → libmpvkt-native-android；不能只下载顶层 KMP metadata JAR 当成播放器。
- 0.2.0 的 POM 同样依赖 stdlib 2.4.10，简单退到该版本不能消除此门槛。

已读取远端 POM、Gradle module metadata、发布源码 JAR；小型包装 AAR 直接读入内存。54,290,860 字节 native AAR 未完整下载；使用 HTTP 206 Range 读取 ZIP 目录和全部 arm64 SO 压缩片段（约13MB）到内存，未写入项目或依赖缓存。

发布 metadata 声明 native AAR SHA256：`400173ab543f396934966dc9ad3fa6af1cd204f0b0de054593a6b3218fc12b50`。**这是发布者声明，本轮未下载整包，未独立核验整包 SHA256**。arm64 条目逐一解压并核验 ZIP CRC32 和长度；CRC32 不等于来源认证。包装 AAR 的整包 SHA256 本轮也未独立计算。

## 3. 实际二进制检查

native AAR 中 arm64-v8a 包含：

`libavcodec.so`、`libavdevice.so`、`libavfilter.so`、`libavformat.so`、`libavutil.so`、`libc++_shared.so`、`libmpv.so`、`libmpvkt_jni.so`、`libswresample.so`、`libswscale.so`。

实际解析十个 ELF：e_machine 均为183（AArch64），全部 PT_LOAD 的 p_align 为0x4000（16KB）。DT_NEEDED 的非系统库均在上述集合中；其他名字为 Android 系统库（如 libc、libm、libandroid、libmediandk、libOpenSLES、libEGL）。这是依赖名称闭包检查，不证明运行设备符号/API可用，也没有完整核对 Android 最低 API、APK ZIP 对齐或实际动态加载。

发布说明称 mpv 0.41.0、FFmpeg 9.0.1、NDK r29。它们是源码/发布声明，未在运行时回读版本。不要用 Task1 的独立核心快照代表这些二进制。

## 4. 已从发布源码确认的控制接口

发布源码 JAR：`libmpvkt-android:0.3.0` 的 androidMain/io/github/yuroyami/libmpvkt/：

| 文件/位置 | 已确认接口 | 边界 |
|---|---|---|
|MpvCommands.kt:47–51|frameStep(frames, mode)、frameBackStep()|构造 frame-step / frame-back-step；不证明目标视频实际相邻帧准确或低延迟|
|MpvProperties.kt:20|Speed，Double 属性|可提交0.1/0.25/0.5/0.75/1.0；音画与高帧率表现待测|
|MpvProperties.kt:56–57|AbLoopA / AbLoopB|类型化 AB 属性；实际边界和循环间隙待测|

固定源码路径为同名 `libmpvkt/src/androidMain/kotlin/...`。README 还说明 Mpv.command、commandAsync、Surface 绑定、MpvView、content stream provider；这些入口存在不代表本轮已测试生命周期与本地 URI。

## 5. 工具链与依赖门槛

实际读取 view/core 包装 AAR 的 aar-metadata.properties：minCompileSdk=35、minAndroidGradlePluginVersion=1.0.0；本项目 compileSdk37 满足声明下限。不能把生产者使用 AGP9.4.0/Gradle9.7.1 当成消费者必须同版本；也不能把这个宽松 metadata 当成整个 Kotlin/运行时链兼容保证。

源码声明 minSdk21、JVM11，项目 minSdk26/JVM17 在数值上满足。仍需实际依赖解析、Kotlin 编译、Manifest/native 合并与运行验证。其库引入协程，现有007回调队列不因此改造。

## 6. 生命周期、许可与范围风险

- 固定版 README 支持普通 MpvView；initialize 在主线程启动，另有 prepare/attach 分拆。不能照抄旧 BaseMPVView 的单例/线程假设。
- 页面/Activity切换、Surface销毁、后台及重复进入要重新验证。不得为适配播放器偷偷改变007的onStop或安全隔离策略。
- native 中包含 libc++_shared；以后若出现重复库，须核对版本，不盲目 pickFirst。
- NOTICE/POM 明确包装层 Apache-2.0，原生组合 GPL-3.0-or-later；不是整套 MIT/Apache。分发前须审核实际组合及对应源码/许可材料，本轮未完成法律合规审计。
- README 默认网络 TLS 验证关闭；本项目首轮限定本地视频，不照搬该网络默认值作为产品安全配置。
- 小型第三方项目的维护连续性、完整可复现构建、产物真实性仍待独立审核。发布者与产物同源的哈希不构成独立信任来源。

## 7. 下一步最小验证

1. 在项目外独立 smoke 工程验证消费依赖，主仓库007源码/工具链不变。优先用现有工具链复现兼容性，不先升级。
2. 下载固定0.3.0完整AAR及必要的常规Maven传递依赖，核对整包SHA256，记录依赖解析和实际 Kotlin metadata；执行最小 View 引用编译。若需新工具链，先给出准确兼容组合及下载清单，再获确认。
3. 编译通过后才讨论受控加入项目的PlayerController适配层。首轮打开本地视频、播放/暂停、退出释放；不接入USB，不安装APK，安装与真机测试另获批准。
4. 真机用带可见帧号样本验证前/后各20步、五档速度、AB重复和Surface生命周期；之后才接007事件。编译成功不等于逐帧验收通过。

上述是建议，不表示本轮已建立 smoke 工程或下载完整依赖。用户当前授权验证方向；本轮完成产物/API静态初筛，未自动更换架构或主工具链。

## 8. 实际执行与未执行

实际：读项目规则/原集成方案/路线图/变更/现有Gradle；检查Git；读固定源码及发布POM/module/source JAR；读小型包装AAR与native ZIP目录；Range获取并解压arm64条目，核对长度/CRC/ELF/DT_NEEDED/对齐；新增本文、更新ROADMAP/CHANGELOG。

未执行：完整native AAR SHA验证、Gradle依赖解析、编译、单元测试、Lint、手机动态加载/播放/逐帧/AB、ADB/USB、APK安装、Git暂存/提交。现有007代码没有修改，因此不宣称本轮重新跑过007回归。

可核对来源：[固定源码](https://github.com/yuroyami/libmpvKt/tree/b001dd97538e7d3e700df80029fd72be99ceba3f)、[0.3.0发布](https://github.com/yuroyami/libmpvKt/releases/tag/v0.3.0)、[view POM](https://yuroyami.github.io/maven/io/github/yuroyami/libmpvkt-view-android/0.3.0/libmpvkt-view-android-0.3.0.pom)、[core POM](https://yuroyami.github.io/maven/io/github/yuroyami/libmpvkt-android/0.3.0/libmpvkt-android-0.3.0.pom)、[native module metadata](https://yuroyami.github.io/maven/io/github/yuroyami/libmpvkt-native-android/0.3.0/libmpvkt-native-android-0.3.0.module)。


## 9. 第二轮：现有工具链消费编译（2026-11-02）

用户同意项目外最小工程验证，授权下载固定AAR及必要常规构建依赖。建立 `%TEMP%/tabletennis-libmpv-smoke-d65a_9ft`；原始构建输出为该目录 `build.log`。该临时目录可能被系统清理，失败摘要在本文长期保留。没有把本机SDK配置、日志或二进制复制进主仓库。

### 实际工程与执行

- 从主项目复制标准Wrapper（Gradle9.4.1及原distributionSha256Sum）、AGP9.2.1根配置、本地SDK配置；使用现有JBR25，compile/target37、min26、Build-Tools36.0.0、JVM17；只在本次子进程设置JAVA_HOME，未修改系统环境。
- 单独app依赖固定 `io.github.yuroyami:libmpvkt-view:0.3.0`，限定第三方Maven仓库group，APK ABI限定arm64-v8a。
- Compile-only Activity引用MpvView、MpvCommands.frameStep/frameBackStep、MpvProperties.Speed/AbLoopA/AbLoopB。没有调用initialize、loadfile、USB或网络播放代码；未运行Activity。
- 执行 `gradlew.bat --no-daemon --console=plain :app:assembleDebug`。必要的正常构建依赖下载到用户Gradle缓存，未安装SDK/NDK/新工具链。

### 完整AAR下载及哈希核对

三个完整AAR保存于独立工程 `verified-artifacts/`，独立计算SHA256，全部匹配此前发布metadata值：

| 构件0.3.0 | 大小（字节） | 实测SHA256 |
|---|---:|---|
|libmpvkt-native-android|54290860|400173ab543f396934966dc9ad3fa6af1cd204f0b0de054593a6b3218fc12b50|
|libmpvkt-android|400217|16f535429f914ebd248da16e0271280ddf96506ea9c77e221ae3a2cefef1686c|
|libmpvkt-view-android|26063|3c3839fe05a210010bf539bca9db612b8a4a194fb4c0faeb48d94f742c9b37f7|

这是完整文件匹配，不代表第三方产物已经通过独立安全审计。第一轮“尚未完整下载/核验”的记录保留其历史边界，本轮已补做此项。

### 实际失败原因

**BUILD FAILED，退出码1；耗时2m13s；失败任务 `:app:compileDebugKotlin`。没有生成debug APK。**

依赖解析已取得View/core/native及stdlib2.4.10；错误明确为：

```text
Module was compiled with an incompatible version of Kotlin.
The binary version of its metadata is 2.4.0, expected version is 2.2.0.
The actual metadata version is 2.4.0, but the compiler version 2.2.0
can read versions up to 2.3.0.
```

其中2.2.0是编译器诊断的版本/metadata表述，不据此擅自改写原项目已记录的内置Kotlin补丁版本。View、core、native包装及stdlib均报告metadata不兼容；`listOf`未解析等伴随错误不应当成播放器API缺失。使用现有JDK javap直接读取下载core AAR中的MpvCommands，实际 `kotlin.Metadata mv=[2,4,0]`，与错误一致。

结论：**此固定发布版不能通过本项目当前工具链直接编译消费。** 这不否定原生mpv运行能力，也不证明整个预编译路线不可行。编译尚未成功，因此原生打包/动态加载、Surface、本地URI和逐帧精度仍待验证。

### 停止点与下一步建议

未启用 `-Xskip-metadata-version-check`，未强制降stdlib，未升级主项目或独立工程工具链，未自动改选播放器。未运行无意义的设备测试/安装，也未宣称Lint或单元测试通过；本探针没有新增业务算法单元测试。

建议下一步**仅在独立工程核实并试用可消费metadata2.4.0的Kotlin/AGP兼容组合**，先确认准确版本、支持矩阵及必要下载，再获用户确认。生产者的AGP9.4.0/Gradle9.7.1/Kotlin2.4.10是源码声明，不能直接视作已批准、已验证的消费者组合。主项目007及其工具链继续冻结，独立工程成功后再决定集成与回归范围。


## 10. 第三轮：独立工程升级Kotlin后的成功编译（2026-11-02）

用户批准继续在独立工程验证兼容工具链。本轮只修改项目外smoke工程的根 `build.gradle.kts`，保留原文件副本；主项目工具链及Android源码未改。前两轮的失败与未执行记录保留为历史，不代表本轮当前状态。

### 兼容性依据及实际选择

- [Kotlin官方支持矩阵](https://kotlinlang.org/docs/gradle-configure-project.html)：KGP2.4.20–2.4.21支持Gradle7.6.3–9.7.0、AGP8.5.2–9.3.1；覆盖现有Gradle9.4.1/AGP9.2.1。KGP2.4.10的完整支持上限AGP9.1.0，因此没有机械照搬库生产者版本。
- [Android官方内置Kotlin升级说明](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin)：允许通过根buildscript classpath使用较高KGP，保留内置Kotlin，不额外应用kotlin-android插件。
- [AGP9.2兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)：Gradle最低9.4.1、JDK最低17，SDK37/Build-Tools36.0.0受支持；已有JBR25沿用并实际构建通过。

唯一工具链配置变化：

```kotlin
buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
plugins {
    id("com.android.application") version "9.2.1" apply false
}
```

实际组合：AGP9.2.1、Gradle9.4.1、KGP/compiler2.4.21、现有JBR25、JVM17、compile/target37、min26、Build-Tools36.0.0、libmpvkt-view0.3.0、arm64-v8a。没有升级AGP/Gradle/JDK，没有安装SDK/NDK，不使用跳过metadata检查或强制降库版本。下载的是KGP及必要常规Maven传递依赖。

### 实际执行结果

在项目外原smoke工程执行：

```text
gradlew.bat --no-daemon --console=plain :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 2m 10s
46 actionable tasks: 46 executed
```

离线追加 `:app:dependencyInsight --dependency kotlin-compiler-embeddable --configuration kotlinCompilerClasspath`，确认解析到compiler2.4.21（不是仅改配置但实际仍使用旧编译器）。

Lint报告：0错误、2警告：

1. AndroidGradlePluginVersion：提示存在更新Gradle9.8.2；这不要求当前组合升级，本轮固定9.4.1以保持实验可比。
2. ChromeOsAbiSupport：没有x86_64；本轮限定小米目标ABI arm64，不承担ChromeOS兼容。

其他非Lint提示：stripDebugDebugSymbols无法再次裁剪十个原生库，按原样打包，任务及APK构建成功。未为消除此提示安装NDK。apksigner在JBR25下打印Java native-access未来限制提示，当前验证退出码0，没有把提示隐藏或当成签名失败。

### APK及静态打包验收

项目外产物：`%TEMP%/tabletennis-libmpv-smoke-d65a_9ft/app/build/outputs/apk/debug/app-debug.apk`。

- 大小31,009,105字节；SHA256 `26ecde2be5db000b08b2f64aed4dbe569c352dc046dab2dc2706e2584466101c`（本次debug产物，重建可能改变）。
- ZIP内实际包含十个 `lib/arm64-v8a/*.so`，无其他ABI。与第一轮确认的原生链一致。
- 使用已有Build-Tools36.0.0执行 `zipalign -c -P 16 4 <apk>`，退出码0。
- 执行 `apksigner verify <apk>`，退出码0。
- 43个主项目Android源码/配置文件的构建前后SHA256比较一致；`git diff --name-only -- android`为空。
- 本次不新增算法，无设备单元测试未运行，不把Gradle的preDebugUnitTestBuild或Lint测试模型任务称为业务测试通过。

项目外证据：`build-kotlin2421.log`、`compiler-dependency.log`、`app/build/reports/lint-results-debug.xml/html`、`README.md`。它们及下载AAR/APK没有纳入Git。

### 结论、限制及下一步

**预编译0.3.0可在Windows现有AGP/Gradle/JBR组合中，通过升级独立工程Kotlin到2.4.21完成消费编译、打包和Lint。** 这解决了第二轮编译门槛，并说明首次使用不必自行Linux构建native。

这只是compile-only探针：Activity仅引用View和控制API，未initialize、loadfile或调用实际逐帧/AB命令；APK不是可用播放器，不建议直接安装作功能验收。本轮没有安装、启动、访问ADB/USB；没有验证JNI动态加载、解码、Surface、SAF、逐帧、低速、AB或真实生命周期，也未证明与007同进程集成后仍无问题。

建议下一阶段在独立播放器验证程序中加入本地选片、初始化/出画/暂停及释放，再经安装授权进行目标机验证；先不接USB。主项目是否采用本路径和升级KGP仍待确认，不能把本次隔离成功自动当成主工程迁移批准。之后若迁入，必须运行007/94字节/异步清理/代次全部既有回归，USB-001仍Deferred。


## 11. 第四轮：独立本地播放验证程序（2026-11-02）

用户同意后，在项目外新建 `%TEMP%/tabletennis-libmpv-local-8_1lcd6h`，保留上一轮compile-only工程；不是向主工程集成播放器。功能、源码目录、边界与真机步骤见[独立播放器说明](LOCAL_PLAYER_PROBE.md)。

已实现系统View选片、手动后台初始化及content流、初始暂停、播放/暂停、状态与时间、主动释放、后台/旋转撤销旧会话、迟到初始化结果关闭、测试标记、屏幕局部复制及完整应用诊断日志导出。包名research.localplayer.libmpv，显示名称“Libmpv 本地播放验证”；与USB应用独立，无USB或INTERNET权限，不添加新依赖，使用上一轮缓存离线构建。

实际执行及结果：

- `:app:testSessionGate :app:assembleDebug :app:lintDebug`，最终离线构建 **BUILD SUCCESSFUL in 56s**，48 tasks（12 executed / 36 up-to-date）。
- SessionGate **11项断言通过**：初始无会话、首次token、生效/撤销、重复撤销、新token不同、旧token失效、再次替换与最终关闭。仅证明纯会话门控，不证明native/Activity实物时序。
- Lint **0错误、6警告**：ChromeOsAbiSupport 1项（限定arm64）；SetTextI18n 5项（原型状态/诊断中文写法尚未资源化）。不屏蔽Lint，不新增baseline。
- 开发中首次有界面字符串换行语法错误，随后Lint报告URI授权常量写法错误；均已修正重跑。中间失败日志仍保留，不当成库兼容性失败；最后Kotlin编译无该冗余非空断言告警。
- 完整路径 `%TEMP%/tabletennis-libmpv-local-8_1lcd6h/app/build/outputs/apk/debug/app-debug.apk`；最终31,049,431字节，SHA256 `2d5eb008b783a33c09c596f27f302b697492523c38f099259519ab48c8c54d46`。
- zipalign `-c -P 16 4`及apksigner verify均退出0；实际ZIP含十个arm64-v8a SO；aapt dump permissions仅显示包名，没有请求权限。签名工具JBR25 native-access提示仍存在但验证成功。
- 源文件逐行空白及禁止USB/OUT/API/INTERNET代码扫描通过。主工程43个Android源码/配置SHA256未变，git的android路径diff为空。未重新运行无改动的007原有测试。
- 独立工程记录 `build-local-player-releasecheck.log`、Lint XML/HTML及README；源代码、日志、APK、local.properties均在仓库外。

**尚未安装或运行：不能声称成功首帧、音频、解码、后台/旋转回收或无泄漏。** 库destroy仅异步请求关闭，日志明确不把返回当原生关闭完成。初始化native长期不返回、旋转时新旧实例重叠、诊断Flow丢旧值等边界均见说明文档；007输入队列不受影响。没有速度/逐帧/AB功能实现及精度结论。

下一步需另行授权安装此独立APK，用户手动按说明执行本地视频出画、播放/暂停、后台、旋转和重复释放测试，再分析导出日志。主项目仍未采用或升级此库，USB-001不在本轮处理范围，未执行Git暂存/提交。


## 12. 第五轮：首次真机失败分析及content注册修正（2026-11-02）

用户已授权安装独立应用，0.1.0安装后由用户手动测试；只读run-as导出完整应用日志到项目外，保留手机文件。21行中5次INIT FAILED INVALID_PARAMETER（第6/9/12/16/21行），无INIT READY/FILE_LOADED；没有证据表明视频进入解码。

源码及实际SO线索指向重复注册内建content协议，属于探针接线问题的高可信候选，而非已经排除其他错误的真机结论。依据和具体修复见[独立播放器说明§7](LOCAL_PLAYER_PROBE.md)。没有仅凭此日志认定视频损坏、权限无效或libmpv不可用。

用户同意修正后，独立程序：移除addStreamProtocol/content自定义注册及其provider引用，使用FFmpeg内建content；增加create/prepare-options-and-initialize/attach/load阶段、失败堆栈、运行时protocol-list及initial-pause；版本升至versionCode2、0.1.1-content-fix。初始化失败清理由库prepare原有契约承担，不再追加重复close。未改核心、工具链、USB或007逻辑，旧源码备份在独立工程before-content-fix目录。

实际验证：

- 离线 `:app:testSessionGate :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL in 2m3s；48 tasks（21 executed/27 up-to-date），11项会话断言通过。
- Lint0错误/6警告（1 ChromeOS ABI、5 SetTextI18n）；没有跳过检查。APK签名与16KB打包对齐检查退出0。
- 修复APK 31,026,692字节，SHA256 `d5371300e0d0994bf4685d2f64d6bfdaa65cccf5bd694525d4611c6001b08368`。构建输出仍为项目外app/build/outputs/apk/debug/app-debug.apk；日志build-content-fix.log。
- 与建立独立工程时43文件快照比较：42个文件一致，唯一差异为已忽略的android/.idea/workspace.xml；本轮未修改/覆盖该IDE状态文件，不纳入Git。git的android路径diff为空。
- 沿用用户对同一独立应用的安装授权，执行adb install -r成功；只读核对手机versionCode2/versionName0.1.1-content-fix。原2043字节诊断日志仍存在，未清除数据，未卸载或覆盖USB应用。
- **未自动启动，未发送USB命令；修复后首帧/播放/声音仍未验证。** 不把更新成功称作初始化问题已真机修复。下一步用户使用同一视频手动初始化，检查新阶段日志、FILE_LOADED和实际画面，异常时再只读导出日志定位。

主项目只更新相关文档；未暂存或创建Git提交，已有pcapng/Python缓存保留。


## 13. 第六轮：首次选片按钮刷新（2026-11-02）

用户反馈0.1.1基础播放大致正常；这是用户手动观察，非完整格式、速度/逐帧/AB或资源验收。首次选择本地视频后初始化按钮灰色，第二次才生效。只读查码确认：release先按旧selected刷新按钮，赋新URI后漏刷新；仅在状态更新后补refreshButtons，保持选片/后台/手动初始化、核心协议、工具链及007不变，标记versionCode3/0.1.2-picker-fix。

离线testSessionGate、assembleDebug、lintDebug成功（1m22s，48tasks：21executed/27up-to-date）；已有11项会话断言通过，Lint0错误/6警告。此单行UI修复未新增镜像实现的测试；真实首次选片是否恢复仍待用户复测。签名与16KB对齐检查通过；APK31,026,696字节，SHA256 `4ad2326099ef6afdfe2f0906a9454563f0c168566f556e9d112817ae0554e389`。构建日志位于独立工程build-picker-fix.log。

沿用同一独立应用的已有安装授权，adb install -r成功，手机版本核对code3/0.1.2-picker-fix；保留数据，不启动应用，不操作TourBox。相关行为与复测步骤见[独立程序说明§8](LOCAL_PLAYER_PROBE.md)。未修改主项目Android源码、未提交Git。


## 14. 第七轮：0.1.2基础播放真机复核（2026-11-02）

用户反馈首次选片恢复正常。只读导出并二次核对稳定快照：0.1.2最新113行，3次初始化READY、3次真实FILE_LOADED、运行时mpv v0.41.0及内建content、7次播放/4次暂停请求均Ok，观察到5次Playing/7次Paused状态，3次释放请求；无记录的初始化/加载/控制失败或队列溢出。第一条选片后已能开始初始化，无第二次选片，支持修复结果。两条EVENT Unknown缺少ID，保留未解释项；异步关闭完成没有证据，不扩大为回收验收。

原始日志均保留手机及项目外，不纳入Git；详细行号及局限见[日志复核](LOCAL_PLAYER_PROBE.md#9-012真机日志核对2026-11-02)。本轮未修改Android代码、编译、安装、启动应用或发送USB命令。基础播放范围可继续推进，逐帧/慢速/AB和主项目集成仍待独立实现验证。


## 15. 第八轮：复盘功能专项验证准备（2026-11-02）

用户同意继续，独立应用0.2.0新增前后逐帧、五档速度、AB及回A，保留已成功的内建content/手动初始化与007隔离。新增纯规则层，45项无设备断言、debug、Lint通过（0错误/9警告），签名/16KB打包对齐通过，安装更新成功但未运行。用已有Windows组件生成并校验带帧号300帧30fps静音全关键帧MJPEG，安全放置手机下载目录并核对哈希；不当成H264/HEVC验收或样本已成功解码。完整范围、脚本/源码、日志策略及用户操作见[专项验证](PLAYER_REVIEW_VALIDATION.md)。原始产物不加入Git，尚无本轮新增功能真机通过结论。


## 16. 第九轮：复盘专项首轮日志分析（2026-11-02）

用户反馈基本正常。只读导出并复核稳定520行日志：26项复盘操作均有RESULT，无记录的FAILED/超时/溢出；前/后各3次均观察±1/30秒且恢复暂停，五档均有实际Playing时间推进，AB约5.566667–7.133333秒记录4次回跳及清除后越B。不能将片尾仅设置speed算作实际慢放，不能把时间差当画面帧号；距各10步/10圈及真实编码高帧率/声音压力验收仍有差距。Unknown id11查明本条为空闲通知；末尾暂停未见最终释放。详细行号、采样比例和边界见[专项复核](PLAYER_REVIEW_VALIDATION.md#7-首轮专项真机日志复核2026-11-02)。本轮仅分析及文档，无代码/安装/自动播放/USB控制或Git提交。
