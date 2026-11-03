# TourBox旋转灵敏度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Knob和Scroll分别用五档滑块控制逐帧粒度与跳转距离，保持默认手感和离散动作安全。

**Architecture:** 纯旋转处理模型按来源、动作、已保存倍率生成0～4个派生命令或一个缩放秒数的跳转。配置/草稿保留两个倍率，复用既有后台保存和队列授权，原始事件不变。

**Tech Stack:** 现有Kotlin、Android SeekBar/View、SharedPreferences、JVM测试，无新依赖。

**Spec:** docs/superpowers/specs/2026-11-03-tourbox-sensitivity-design.md

## Global Constraints

- 五档0.25×、0.5×、1×、2×、4×；Knob/Scroll独立，默认1×；不根据旋转速度自动加速。
- 逐帧低档同方向累计4/2次旋转事件触发1帧，高档每事件2/4帧；不假定事件数等于物理刻度或角度。
- 跳转一次按基础5秒乘倍率，即1.25/2.5/5/10/20秒；不靠多次相对seek叠加。
- 播放/暂停、截图、AB、速度切换和普通按钮均不重复、不缩放。
- 反向、同控制件超过1秒无输入、配置改变、停用、USB/播放器会话变化清除余量；两控制件不串用余量。
- 继续32项队列、2秒过期和旧会话授权；保留原始USB/语义记录，派生动作不能冒充新原始事件。
- 不改USB协议、8/26/94字节、解码器、资源回收、视频倍速/导出/屏幕手势；不自动安装、访问设备或提交Git。
- 沿用Native顺序实施和已有分支，保留此前全部未提交内容。

## Review Focus

- 反向或另一控制件不能“借用”旧余量：Task 2正反与交叉输入测试。
- 暂停控制、换配置或断开后不能因旧半帧突然跳动：Task 2/3生命周期清零测试。
- 任意映射成截图/切换/调速的旋转必须仍只触发一次：Task 2/3全部非运动动作与按钮测试。
- 旧配置迁移或改映射不能丢失倍率/有效按钮设置：Task 1字段往返、迁移与草稿测试。
- 高倍率不能绕过有界队列或让旧派生命令继续执行：Task 3满队列/过期/来源失效测试。

## Task 1：配置与保存兼容

**Files:** Modify app/playback/TourBoxMapping.kt、app/src/testInput/java/TourBoxMappingCheck.java（路径均相对android/）；可新增app/playback/RotationSensitivity.kt定义纯枚举。

**Interfaces:** RotaryControl.Knob/Scroll；SensitivityLevel枚举Quarter/Half/Normal/Double/Quadruple，对应稳定IDquarter/half/normal/double/quadruple，multiplier为0.25/0.5/1/2/4。MappingConfig新增sensitivity(control)/withSensitivity(control,level)；MappingDraft新增setSensitivity(control,level)。

- [x] 先写缺失能力及行为测试：默认1×、两控制件独立、改映射保留倍率、改倍率保留12项映射、草稿取消/恢复默认、保存成功/失败和重读。
- [x] 实际运行testTourBoxMappings确认缺失能力RED，再实现不可变配置字段、equals/hashCode和草稿。
- [x] 保持version=1，新增可选字段knob-sensitivity与scroll-sensitivity；旧配置缺项静默按normal，不丢按钮映射；非法/重复值仅该字段恢复normal并提示。编码使用稳定ID，不用浮点或enum序号。
- [x] 测试五档往返、旧配置原样迁移、坏值/重复/未知字段，运行testTourBoxMappings确认GREEN。

## Task 2：旋转计划与余量

**Files:** Create app/playback/RotationSensitivity.kt；Create app/src/testInput/java/RotationSensitivityCheck.java；Modify app/build.gradle.kts注册testRotationSensitivity并接入check。

**Interfaces:** RotationProcessor.plan(input:MappingInput,action:MappingAction,level:SensitivityLevel,nowNanos:Long):RotationPlan；RotationPlan包含repeats（0～4）和可选commandOverride（仅SeekRelative）。RotationProcessor.reset()清除两个来源状态。

- [x] 先写手算期望测试并运行RED：1×与默认一致；0.25×第4次、0.5×第2次触发；2/4×重复数；两方向和两个控制件互不借余量。
- [x] 实现整数累计，来源内记录lastInput/lastTime/余量；反向、非运动动作或时间倒退清空该来源，超过1_000_000_000ns无输入清空（恰好1秒不算超过），不创建后台定时器。
- [x] SeekForward/Backward一次生成±5×倍率的SeekRelative；逐帧返回重复数。非旋转输入、None及所有非逐帧/跳转动作不重复；普通按钮直接沿用原动作。
- [x] 覆盖交错来源、超时边界、反向、reset、所有非运动动作、按钮、无效时钟与长序列，再运行testRotationSensitivity及testTourBoxMappings确认GREEN。

## Task 3：队列与授权集成

**Files:** Modify app/playback/TourBoxPlayerBinding.kt；Modify app/src/testInput/java/TourBoxPlayerBindingCheck.java。

**Interfaces:** Pending保留原event/action/configEpoch/target，新增冻结的PlayerCommand?及part/parts；截图仍走原独立来源租约。Binding持有一个RotationProcessor，仅在现有lock内使用。

- [x] 先写绑定回归并运行RED：低倍率多事件才有命令、高倍率准确派生、缩放seek只有一条、截图/切换/普通按下不重复、更新/停用/USB或播放器换代清零。
- [x] 同一临界区取动作/倍率、生成计划并入队；累计不足记录原因，不更改解码器事件。所有discardLocked调用清除余量，普通busy状态更新不清零。
- [x] 每个派生命令独立经过原队列容量、压力、过期和permit检查，最多4条；Seek使用冻结覆盖命令，其他仍action.command。截图不派生重复。
- [x] received按原始语义事件计，admitted/cancelled按实际派生命令计；映射日志包含来源seq及part/parts，原始字节和输入事件仍只记录原事件。
- [x] 测试32项容量、慢消费者、旧配置授权、旧USB/播放器、时间过期、派生动作顺序及1×旧回归，通过绑定/映射/旋转完整测试。

## Task 4：两个滑块与用户语义

**Files:** Modify app/playback/TourBoxSettingsDialog.kt；按需补充纯设置草稿测试。

- [x] 在设置滚动内容中增加Knob灵敏度和Scroll灵敏度两组SeekBar，每组max=4、旁边显示对应倍率；不增加主界面控件。
- [x] 用户拖动只更改草稿及倍率标签；populate更新滑块不产生假修改，保存/读取中禁用滑块；保存失败保留草稿，取消不生效。
- [x] 恢复默认同时把两倍率设normal；关闭/后台/系统重建沿用已有设置和暂停意图保护。
- [x] 运行配置/保存/草稿与UI策略回归，原生滑块布局/触感明确留待真机。

## Task 5：收尾

**Files:** README.md、ROADMAP.md、CHANGELOG.md、docs/TOURBOX_CUSTOM_MAPPING.md与计划进度；android/app/build.gradle.kts版本。

- [x] 版本0.6.2-rotary-sensitivity/code19；写清五档作用、事件非物理刻度、只有运动动作缩放和保存/取消规则。
- [x] 执行testRotationSensitivity、testTourBoxMappings及全部已有JVM检查、Debug和三模块Lint；核对签名、版本、16KB ZIP对齐、git diff --check，记录准确结果及警告。
- [x] Native流程安排一次最终独立只读审查；重要问题先RED复现再修复并重跑全套，不重复派审查或自动提交。
- [x] 核对USB核心/解码与原始事件未改，保留用户截图/抓包/缓存及全部既有修改，报告APK与真机步骤，不安装或访问ADB/USB。

## 执行

用户已选择Native本会话顺序实施；计划审核后连续完成，不在任务之间暂停征求继续。原生手感、实际逐帧/跳转和存储兼容需真机，未执行不得声称通过。
