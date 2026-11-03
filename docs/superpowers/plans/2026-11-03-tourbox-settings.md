# TourBox用户设置与自定义映射 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 删除用户不需要的USB诊断界面命令，为12个已确认输入提供可保存的单次动作映射。

**Architecture:** 纯映射配置与草稿模型独立于USB；现有绑定消费不可变配置并使旧待办失效。SharedPreferences后台持久化，设置对话框取代原诊断视图，仍沿用原自动连接及日志健康门控。

**Tech Stack:** 现有Kotlin/Android View、SharedPreferences、JVM检查，不增加依赖或改工具链。

**Spec:** docs/superpowers/specs/2026-11-03-tourbox-settings-design.md

## Global Constraints

- 首版只有8个实体按键的ButtonDown和4种旋转，ButtonUp/未知码不操作；不做长按、双击、组合键、学习模式。
- 12项输入、默认动作、完整可选动作以Spec为准；保存统一生效，关闭不保存，恢复默认只重置草稿。
- 不改USB协议、8/26/94字节流程、端点、解码码表和资源隔离，不新增自动重试或实验性命令。
- 日志继续后台捕获；移除诊断UI不得破坏日志健康与实验文件存在性门控。
- 保留当前0.5.6及0.6.0全部未提交修改；不自动安装、访问ADB/USB、暂存或提交Git。
- 用户已选择Native，本会话顺序实施；收尾只做一次独立只读审查，不分派实现子代理。

## Review Focus

- 修改/恢复默认后取消不能改到活动映射：Task 1草稿隔离及Task 4关闭流程测试。
- 保存失败、Activity销毁和迟到回调不得伪报成功或覆盖更新配置：Task 2持久化故障/版本令牌测试。
- 保存新映射后旧已排队甚至已取出动作不能继续执行：Task 3配置代次及Guarded授权回归。
- 截图接纳后自身暂停分发不能反过来取消本次截图，但旧USB/配置/播放器仍须失效：Task 3独立来源租约测试。
- 旋转映射为截图/切换类动作后不能沿用“所有旋转均可淘汰”的假设：Task 3积压策略及重复接纳测试。

## Task 1：配置模型与完整映射

**Files:** Create android/app/src/main/java/io/tabletennisreview/playback/TourBoxMapping.kt；Create android/app/src/testInput/java/TourBoxMappingCheck.java；Modify android/app/build.gradle.kts。

**Interfaces:** MappingInput包含12个稳定字符串ID/中文标签和fromEvent(event):MappingInput?；MappingAction为Spec动作集合，含稳定ID、label、coreCommand:PlayerCommand?、是否可连续重复。MappingConfig为不可变12项配置，defaults()/action(input)/with(input,action)/encode()/decode(text)。MappingDraft提供set/resetDefaults/dirty/snapshot，不直接写磁盘或绑定。

- [x] 先写缺失能力与行为测试：默认映射逐项兼容、8种按下和4类旋转、释放/未知忽略、五档速度及全部动作、None明确禁用、多个输入可同动作。
- [x] 注册testTourBoxMappings并接入check，运行确认新功能缺失导致失败。
- [x] 实现模型及版本化ASCII配置编码：version=1及显式输入/动作ID，不用enum.ordinal，不依赖Android JSON。缺项/未知动作/重复已知键按项恢复默认；未知版本或超长/完全损坏配置整体安全默认，保留恢复说明。
- [x] 测试草稿set/默认恢复不会改原配置，脏标志正确；坏值、重复键、升级未知键、保留None和往返序列化后运行GREEN。

## Task 2：后台保存与失败恢复

**Files:** Create app/playback/TourBoxMappingStore.kt；新增同一Java测试中的纯保存协调测试。

**Interfaces:** MappingPreferencesPort.read()/write(value):Boolean为可注入持久化边界；MappingSaveCoordinator串行处理配置版本和请求令牌。TourBoxMappingStore用applicationContext、单个进程级串行Executor和SharedPreferences实现，load()/save(expectedRevision,config,callback)，回调含真实保存结果和新revision，closed/旧回调不碰旧宿主。

- [x] 先写失败测试：write=false/异常不替换活动配置；重复保存、旧revision、关闭后回调、串行成功及重新读取。
- [x] 实现后台commit并检查结果；失败恢复SharedPreferences内存旧值，当前绑定仍用旧配置，不宣称已持久化。下次读取仍经配置校验，不把内存apply当磁盘成功。
- [x] 配置成功保存才投递当前宿主应用；进程级序列防多窗口旧配置覆盖新保存。请求源过期时不调用旧Activity，已完成持久化值仍可供新宿主加载。
- [x] 运行testTourBoxMappings确认保存和失败分支GREEN；无需Android运行时的测试覆盖协调层，SharedPreferences/系统进程重建实际行为列入真机验收。

## Task 3：绑定、配置代次与截图动作

**Files:** Modify app/playback/TourBoxPlayerBinding.kt、PlayerPanel.kt；必要时Modify player-mpv PlayerApi.kt/MpvPlayerController.kt；Modify TourBoxPlayerBindingCheck.java与截图检查。

**Interfaces:** Binding.updateMappings(config,revision)原子更新配置并撤销旧待办；Pending记录解析后的MappingAction及当时配置revision。截图宿主回调接受来源授权租约及origin，返回CommandAdmission；PlayerPanel截图入口返回实际接纳结果，必要时新增prepareScreenshotGuarded而保留原屏幕入口。

- [x] 先写绑定测试：默认兼容、全部输入配置可换、None忽略、更新配置清空待办、已取出的旧动作授权失败、旧USB/播放器会话拒绝。
- [x] 把事件解析移入同一配置/队列临界区，保证动作和revision来自同一配置。核验、日志、磁盘和UI均不得在USB来源锁内执行。
- [x] 核心动作继续executeGuarded；截图先核验来源，再转交现有受控截图流程。已接纳截图的来源租约只核对USB/配置/播放器身份，不因自身setEnabled(false)而失效；对真正断连/换配置/换视频的迟到操作仍拒绝。
- [x] 补充截图授权测试：自身暂停分发不误取消、初始化未完成/截图或导出中拒绝、旧来源不能开面板、重复旋转不生成多个截图面板。
- [x] 队列32/2秒过期策略继续；只有来自旋转的连续重复类动作可被优先淘汰。截图、播放/AB切换等已排队离散动作受到保护，满队列明确拒绝新请求，仍记录统计且不自动停用健康会话。
- [x] 运行testTourBoxPlayerBinding、testTourBoxMappings、testScreenshot、player-mpv:testPlayer，确认新旧映射和截图回归GREEN。

## Task 4：面向用户的设置对话框

**Files:** Create app/playback/TourBoxSettingsDialog.kt；Modify usbdiagnostics/MainActivity.kt及playback/PlayerPanel.kt；按需新增纯设置状态测试。

**Interfaces:** 对话框构造接收连接状态、已加载配置/revision、保存回调；12项Spinner编辑MappingDraft。PlayerPanel暴露读取/应用映射方法及设置打开/关闭门控；MainActivity继续拥有UsbManager/权限/连接/日志生命周期。

- [x] 先写草稿和提交状态测试：取消/返回不改活动配置，恢复默认未保存不生效，保存中重复点击和迟到回调不重复提交，写失败保留草稿。
- [x] 入口和标题改为“TourBox设置”；内容只保留简短连接状态、12项映射、恢复默认/保存/关闭和版本。滚动适配小屏与横屏，未保存关闭需确认。
- [x] 删除旧诊断UI装配及其字段/回调：描述符、原始日志、计数、接口选择、强制claim、监听/关闭、CDC与8/94字节手动实验入口。不是仅隐藏按钮，也不创建可映射私有命令。
- [x] 清理showSelected/updateInterfaces/render等旧UI引用；仍保留自动枚举/系统权限广播/attach-detach处理、diagnostics.startCapture、captureFailure导致停止和manager日志健康回调。普通刷新不再因隐藏Spinner选择回调意外关闭连接。
- [x] 简短状态区分未连接、初始化中、就绪、失败、回收未知需重启；不展示协议细节。设置打开暂停播放及分发，关闭恢复可控但不自动播放；后台仍沿用原清理，返回恢复正确页面状态。
- [x] 配置只有保存成功才在绑定统一生效，应用新代次后取消旧待办；失败继续旧配置。运行完整无设备回归及Debug，原生对话框/SharedPreferences持久化留待真机。

## Task 5：审查、文档与交付

**Files:** README.md、ROADMAP.md、CHANGELOG.md、新增docs/TOURBOX_CUSTOM_MAPPING.md及计划进度记录；Modify android/app/build.gradle.kts版本。

- [x] 版本0.6.1-tourbox-settings/code18，文档记录12项、动作、默认值、取消/保存/恢复语义和状态恢复边界，区分前序截图功能未收到完整真机验收反馈。
- [x] 执行全部JVM自定义检查、Debug构建、app/player-mpv/video-export Lint、git diff --check；签名、版本与16KB ZIP对齐核对，记录准确断言数、警告和失败。
- [x] 按Native流程进行一次独立只读审查，覆盖Review Focus；重要发现先RED复现再集中修正并重跑全套。小问题明确记录，不擅自扩展范围。
- [x] 核对USB核心/端点/协议与原始输入解码未变；旧截图、抓包、缓存和未提交修改保留，不执行Git提交/推送或设备操作。
- [x] 输出APK路径与真机步骤：无旧调试命令、改12项/保存/重启/恢复默认/取消、核心与截图动作、快速旋转、断连/权限拒绝反馈及日志后台仍存在。未经授权不安装或访问设备。

## 执行方式

沿用用户此前明确选择的Native本会话顺序实施，不重新选择执行方式。计划审核后使用executing-plans连续完成，不在任务之间停下来索要确认；保留现有功能分支和全部未提交内容，不自动创建提交。

状态：Native实施、完整无设备验证与一次独立只读审查完成；发现的问题已RED→GREEN修复。实际结果与裁决见同目录progress.md，真实设备/存储与UI验收待用户执行。未创建Git提交。
