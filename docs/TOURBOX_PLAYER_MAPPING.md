# TourBox播放器映射（首版）

> 0.3.0首版实施快照。用户随后反馈基本正常；当前默认控制偏好、自动初始化及全屏行为以[新版说明](PLAYER_AUTOMATION_FULLSCREEN.md)为准。下文“默认关闭/手动初始化”保留原验收历史，不代表0.4.0当前操作。

## 范围与映射

用户确认后实施；仅消费007已有语义事件，通过PlayerController控制播放器，不改变USB协议。未安装或执行本轮真机验收。

| 语义事件 | 播放动作 |
|---|---|
|KnobClockwise|前进一帧（先暂停）|
|KnobCounterClockwise|后退一帧（先暂停）|
|ScrollUp|向前跳转5秒|
|ScrollDown|向后跳转5秒|
|ButtonDown(KNOB)|读取核心当前pause属性后切换播放/暂停|
|ButtonDown(SCROLL)|读取核心当前speed属性后循环0.1→0.25→0.5→0.75→1→0.1|
|ButtonUp、其他按钮、UnknownRawCode|不触发播放动作；原输入诊断保留|

速度不是已知档位时从0.1开始；无效属性值拒绝执行。AB仍由屏幕操作；AB启用时逐帧沿用原策略拒绝，不自动清除AB。逐帧精度、反向定位成本仍取决于mpv和视频，未新增精度验收结论。

## 接线与线程

- MainActivity仅将usb.inputEvents注入PlayerPanel。播放器模块不依赖USB或Activity中的USB实现。
- TourBoxPlayerBinding订阅007事件，源回调只做映射、入队和异步日志交接，不调用播放器。Handler主线程串行提交PlayerController；native操作沿用后台协程。
- FIFO最多32项，重复旋转不合并、不加速、不去抖。播放器busy时等待状态通知再继续，输入接收不等待播放器完成。
- 超出队列容量或输入年龄超过2秒/时钟异常时，明确记录原因、取消待办并停用映射，须手动重新勾选；不悄悄丢弃、补发或自动重试。慢速定位优先；快速旋转可能触发此保护，吞吐及阈值待真机反馈。
- 使用与输入戳相同的elapsedRealtimeNanos时钟。仅一个待执行UI调度任务；调度拒绝、播放器接纳异常/拒绝、日志故障会停用，原USB读取继续。
- 播放控制接纳不等于核心执行完毕。界面区分接纳、待办、取消、拒绝、忽略；日志中的CONTROL RESULT/REJECT/CANCEL/FAILED用于核对最终执行。

## 授权与生命周期

默认关闭，视频加载且播放器页处于前台才能手动启用。离页、后台、换片、重新初始化、释放和销毁撤销授权与待办；回到页面或新播放器会话不自动启用。屏幕控制优先，取消已排队旧动作；之后的新输入可继续进入新修订号。

每个待办绑定USB输入会话、输入sequence、播放器会话和桥接revision。提交前及每次native请求前都重新核对输入当前性、播放器代次和页面授权。source.withCurrent临界区内仅检查不可变目标快照，不执行mpv、UI或磁盘操作；不在桥接锁内调用source.withCurrent或关闭订阅，避免与输入分发锁互相等待。

USB断开后旧事件无法取得新的操作授权；尚在队列的旧事件在下次调度时取消。一次短授权检查结束后至native调用之间仍存在不可完全消除的时间窗口：已注册/已开始的native操作不能保证随拔出瞬间撤回。不持有USB分发锁等待native完成，不把此机制宣称为跨USB/mpv原子事务。USB-001仍Deferred，未修改隔离及回收机制。

宿主销毁先关闭订阅和撤销授权，再销毁播放器/关闭日志写入队列。不恢复先前的映射勾选状态。

## 日志

原RAW及语义日志保持原有USB诊断路径；映射上下文和播放器命令写入播放器会话日志，可在播放器页导出。QUEUED/ADMISSION/CANCEL包含usbSession、seq、raw和playerSession；CONTROL BEGIN/RESOLVED/CANCEL记录来源。原始事件不被重复解码；QUEUED是映射审计，并非第二个输入事件。

文件写入复用PlayerLog单后台writer，不在接收回调或UI同步写磁盘。异步日志失败被健康检查发现后停用映射，不宣称失败前尚未落盘内容一定完整。导出可能触发后台并释放播放器/停止USB，完成测试后再导出。

## 无设备验证

实际执行（2026-11-02）：

- testTourBoxPlayerBinding：24场景、155断言通过，覆盖六个动作、忽略事件、重复/顺序、busy排队、溢出、过期、来源/播放器换代、后台/离页、手动优先、关闭、调度/日志/来源/接纳异常及撤销后的授权。
- testPlayer：81断言通过，含核心值切换和速度环策略；testInputEvents：497断言通过。
- testConfig94通过（包含精确payload/hash、26字节分段及并发单次预约）；testUsbAsyncCleanup：20场景/105断言通过；testUsbGenerations：27场景/48断言通过。
- Debug构建成功；app Lint 0错误/18警告（比上次增加2条SetTextI18n），player-mpv 0错误/0警告；git diff --check通过。
- app警告分类：AndroidGradlePluginVersion、ChromeOsAbiSupport、DataExtractionRules、ViewConstructor各1条，SetTextI18n 14条；新增两条为诊断界面文字拼接的国际化提示，无新增Lint错误。未为消除提示升级工具链。
- APK签名验证和zipalign -c -P 16 4检查通过；SDK签名工具提示JDK native-access未来限制，验证本身成功。
- 14个USB/输入源保护文件的修改前后SHA256完全相同。MainActivity只增加inputEvents注入一行，未改USB命令、监听、解码或回收。

测试使用假的输入源、播放器和手动UI执行器，不访问Android USB/native；不能等同于真实视频时序、性能或设备断连联动通过。完整Gradle命令见README（包含新testTourBoxPlayerBinding）。

构建产物：0.3.0-tourbox/code3，31,226,081字节，SHA256 `6df838aed1834a3f1a82610fb08cafb36c055aaf3b9108765bd22dddaf7d0a78`。

## 待执行真机步骤

1. 本轮APK尚未安装，需用户另行授权覆盖安装；不清除应用数据。
2. 先选择本地视频并手动初始化，确认屏幕播放/暂停、逐帧正常；选片器可能导致USB停止，因此放在USB初始化之前。
3. 切到USB诊断，按原流程手动双接口claim/开始读取→A1→A0→单次8字节→完整26字节回复→二次确认94字节。
4. 返回播放器页，勾选启用TourBox控制。慢转Knob验证前后帧；滚动Scroll验证±5秒；按Knob切播放/暂停；按Scroll循环五档速度。松开不重复触发。
5. 连续相同方向慢转确认逐项执行；快速旋转观察容量/2秒积压保护，出现保护后检查日志并手动启用，避免把取消当成USB丢包。
6. 检查屏幕操作优先、离页/后台取消；回页后不自动启用；播放器重新加载后旧动作不执行。
7. USB正常停止后核对旧会话取消；物理拔出仍遵循USB-001限制，不作为本轮自动恢复承诺。
8. 测试后分别导出USB与播放器完整日志，按usbSession/seq及CONTROL op核对接收→接纳→执行/拒绝/取消，不要求物理刻度数等于事件数。

APK：android/app/build/outputs/apk/debug/app-debug.apk；版本0.3.0-tourbox（code3）。本轮未安装、未访问ADB/USB、未创建Git提交。
