# 截图与画线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task if Native execution is chosen; use superpowers:subagent-driven-development only if the user chooses that execution method. Steps use checkbox syntax for tracking.

**Goal:** 暂停后截取当前可见视频区域，在静态图片上标注并另存PNG。

**Architecture:** 在现有播放器View读取渲染层像素，按冻结的缩放/平移合成视口图片。独立纯模型负责图片坐标、标注与任务代次；编辑面板和后台PNG保存不进入USB层。

**Tech Stack:** Kotlin、Android View/Canvas、PixelCopy/TextureView、现有Gradle/JVM测试；不加依赖。

**Spec:** docs/superpowers/specs/2026-11-02-screenshot-annotation-design.md

## Global Constraints

- 只覆盖播放器视频视口，保留其中可见黑边；不包含应用按钮、HUD、进度条和系统状态栏。
- 直线、箭头、圆/椭圆，红/黄/蓝三色；撤销、清空、另存PNG、关闭。
- 同一时刻一个截图请求；截图与视频导出互斥。进入编辑暂停，退出仍暂停。
- 保留当前未提交的0.5.6播放按钮与自动播放修改；后台编码不引用Activity。
- 禁止改USB协议、8/26/94字节流程、端点、回收机制、007映射、视频导出范围。
- 不自动安装APK、访问设备或提交Git；不因技能流程自动提交或清理用户文件。

## Review Focus

- 预览留白、旋转及缩放后的触点必须映射到同一图片坐标：Task 1测试坐标往返和范围。
- 极近/零长度形状、两指及拖出边界不能生成错误标注：Task 1和3测试提交判定。
- 旧捕获回调不得打开新视频的编辑面板：Task 1代次测试、Task 2核对播放器及布局身份。
- 另存选择器触发后台后，冻结图像须保留且播放器不能自动播放：Task 1门控测试、Task 3恢复测试。
- 保存中关闭/重复点击/写入或close失败不能释放仍在使用的图片或伪报成功：Task 1状态测试、Task 3输出流测试。

## Task 1：可测试的截图和标注模型

**Files:** 新建android/app/src/main/java/io/tabletennisreview/playback/ShotModel.kt；新建android/app/src/testInput/java/ScreenshotCheck.java；修改android/app/build.gradle.kts。

**Interfaces:** ShotGeometry将屏幕宽高/scale/x/y转换为只读画布变换；ImagePoint为归一化坐标；MarkShape含tool/color/start/end，AnnotationDocument提供commit/undo/clear及revision；ShotSessionGate提供唯一token、Capturing/Editing/Picking/Saving/Closed迁移和旧回调拒绝。模型不依赖Android。

- [x] 先写ScreenshotCheck，验证中心2倍/偏移裁剪、1/4倍边界、留白触点拒绝和坐标往返、三种形状及反向拖动、零长度/越界、多次撤销/清空、旧token、重复开始/保存/关闭和选择器取消恢复。
- [x] 在compileInputChecks中加入该测试，注册testScreenshot并接入check；执行`:app:testScreenshot`，确认因缺少新功能失败，而非命令或拼写错误。
- [x] 实现上述纯模型，标注最多200项，达到上限明确拒绝；实现状态只允许既定迁移。
- [x] 执行`:app:testScreenshot :app:testPlayerUi`，确认通过。实际断言数量记录到交付文档。

## Task 2：冻结播放并捕获可见区域

**Files:** 修改player-mpv的PlayerApi.kt、MpvPlayerController.kt、ReviewPlayerView.kt；新增app/playback/ViewportCapture.kt；修改现有Java FakePlayer实现以适配新接口。

**Interfaces:** PlayerController.prepareScreenshot(callback:(ScreenshotPreparation)->Unit):CommandAdmission，结果Ready(sessionId)/Rejected(reason)；initializePaused(contentUri:String):CommandAdmission供编辑返回时按暂停重建。ViewportCapture读取ReviewPlayerView中的实际SurfaceView或TextureView，回调Bitmap或明确错误。

- [x] 截图门控已测试会话失效和布局变化拒绝，缺失能力RED已确认；原生暂停与恢复由现有JVM运行时无法执行，按progress.md裁决留待真机，并以旧映射回归保证不误触发截图API。
- [x] 实现prepareScreenshot：沿用核心串行忙状态，设置pause=yes并读取pause属性核实；每步检查epoch/核心身份和超时，不与导出/其他控制并行。
- [x] 将初始化内部实现参数化为初始pause；普通initialize仍自动播放，initializePaused只在截图恢复中使用，保留原URI校验、生命周期及错误清理。
- [x] 冻结视口尺寸、VideoViewport及会话，等待可绘制帧；SurfaceView用PixelCopy，TextureView用位图读取，严格核对实际渲染View结构和尺寸；按Task 1变换合成视口Bitmap，排除所有叠加UI。
- [x] 捕获超时/后台/旋转/新会话使token失效，不自动重试。原生复制尚在使用的Bitmap仅在回调返回后处理；失败或迟到结果不得进入编辑。对像素数设内存上限，超限明确报错。
- [x] 执行`:app:testScreenshot :player-mpv:testPlayer :app:testTourBoxPlayerBinding :app:assembleDebug`；代码审查确认未改USB及自动初始化命令。

## Task 3：编辑、另存与宿主集成

**Files:** 新建app/playback/AnnotationView.kt、ScreenshotEditor.kt、ScreenshotWriter.kt；修改PlayerPanel.kt；新增截图保存失败测试（沿用项目纯JVM测试方式）。

**Interfaces:** AnnotationView以AnnotationDocument和Bitmap渲染，使用统一ShapeRenderer绘制预览及PNG；ScreenshotEditor管理工具/颜色/撤销/清空/关闭；ScreenshotWriter使用applicationContext、独立Executor和不可变图像/标注快照执行写入。宿主以shotHolding屏蔽播放/自动载入/视频导出，仍保留USB原始监听。

- [x] 先写保存事务测试：PNG输出writer关闭成功才提交；写入/flush/close异常、取消、旧token、并发双击均不报告成功。运行确认缺少新保存能力失败。
- [x] 实现图片fit-center预览和归一化形状；触点从图片内部开始，单指有效拖动后才提交，跨出/多指/CANCEL撤销预览；绘制与PNG共享形状算法。
- [x] 实现红黄蓝、直线/箭头/椭圆及撤销/清空；未保存图片或标注在关闭时确认丢弃，另存成功后修改revision重新标记未保存。
- [x] 实现ACTION_CREATE_DOCUMENT image/png，返回取消保留编辑；后台将原截图和固定标注快照合成、压缩并写入，不持有Activity。失败保留编辑并尽力清理本次新建半成品，关闭成功才提示已保存，不写源视频。
- [x] 普通界面在USB诊断同行增加截图按钮，全屏增加小入口；捕获/编辑/保存期间屏幕及TourBox不控制播放器，停止旧待办，截图和视频导出互斥。
- [x] 区分捕获中后台取消与编辑中另存选择器后台保留；关闭/取消清理有单一所有权。编辑退出保持暂停，必要时用initializePaused恢复视频，不让原自动加载默认播放抢跑；断连/新视频不接受旧回调。
- [x] 运行全部既有JVM测试及新截图测试，再执行Debug和三个模块Lint、git diff --check；将错误/警告和未运行的真机项如实记录。

## Task 4：文档和交付

**Files:** README.md、CHANGELOG.md、ROADMAP.md、新增docs/SCREENSHOT_ANNOTATION.md及本计划状态。

- [x] 更新版本为0.6.0-screenshot/code17；记录入口、操作、图片清晰度/黑边/内存与HDR限制，保留用户已验收的0.5.6及导出反馈。
- [x] 记录准确测试和构建结果、APK路径。核对diff只含本功能和用户已知的既有修改，不暂存截图/日志/抓包，不创建Git提交。
- [x] 给出真机步骤：普通/全屏、1倍/放大拖动、横竖屏、三形状与颜色、撤销清空、保存取消/重试、后台返回和保持暂停；设备截图效果未测前明确标记待验收。

## 执行方式（用户已选择Native）

建议Native：本会话直接顺序实施，理由是捕获、宿主互斥、Bitmap所有权和编辑状态紧密关联，拆给多个实现者会增加交接成本。用户如选择Subagent-driven，则使用对应技能逐任务实现并独立审查。任何方式均不覆盖项目未经确认不得提交的规则。

实施状态：功能、文档及无设备验证已完成，具体结果与裁决见同目录progress.md；独立审查完成且唯一P2已由失败回归验证后修正；原生截图/视觉验收待真机。全程无自动Git提交。
