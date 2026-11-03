# SDD ledger — plan: docs/superpowers/plans/2026-11-03-tourbox-settings.md

- 用户已确认设计/计划，沿用Native本会话顺序实施。
- Ruling: 在现有codex/screenshot-annotation工作分支保留前序未提交0.5.6/0.6.0与文档；不复制或覆盖用户文件，不自动提交/安装/访问USB。
- Pre-flight: 配置模型由保存层、绑定和草稿共同使用；持久化成功快照通过进程级订阅通知当前宿主，Dialog回调关闭不丢失已保存配置。
- Pre-flight: 截图动作需独立来源租约，不使用会被自身暂停分发撤销的普通permit；租约仍核对USB、配置代次及播放器来源。
- Task 1: in progress，先缺失模型RED，再实现并GREEN。

- RED: :app:testTourBoxMappings因MappingConfig缺失明确失败；原有映射318断言通过。已写全部配置/草稿/持久化故障行为测试，待GREEN。

- Task 1/2纯模型GREEN：testTourBoxMappings 620断言；旧映射30场景318断言通过。持久化协调器覆盖失败/异常、恢复内存旧值、旧revision拒绝与旧回调。开始绑定接入RED。

- Task 3 RED: 旧绑定缺少updateMappings导致明确失败。新增配置代次、受保护旋转动作和独立截图来源租约，保留普通executeGuarded。新增默认/禁用/旧队列/旧授权/截图自身停用/断连及积压测试，待GREEN。

- Task 2/3 GREEN：后台适配及设置View编译成功；模型620断言、绑定36场景349断言通过。
- Task 4: 已替换MainActivity诊断UI装配，删除手动USB实验入口与隐藏诊断视图；保留既有自动连接条件、权限广播、detach/后台回收和原始文件捕获健康门控。
- Ruling: 首次配置异步读取期间不启用TourBox播放器分发；主屏视频不受影响。持久化成功通过进程级订阅通知宿主，旧设置Dialog销毁不会丢失已保存配置。

- Lifecycle RED: testPlayerUi确认缺少PlayerResumePolicy失败；新增实际消费的后台暂停恢复规则和设置入口可用性策略。设置对话框在仅后台时保留草稿，销毁时取消订阅/旧回调；视频在设置后重建时保持暂停。
- Ruling: 忙/初始化/载入期间暂时禁用设置入口，避免原pauseForPageChange的忙状态释放分支破坏正在加载的视频；无视频时设置可用。初次Preferences对象读取异常也走默认配置与可见提示，不永久卡在读取状态。

- 完整首轮GREEN：配置625、UI57、绑定36场景349、截图377、自动初始化124、播放器186、输入497、异步回收105、连接代次48、导出191断言及94字节回归通过；Debug和三模块Lint成功。app0错/25警告、player0/0、export0/1；签名、16KB对齐、code18验证通过。
- 增补逐项核对23个动作的实际PlayerCommand参数及8按钮精确输入映射，避免仅序列化测试掩盖错按钮/错参数；待收尾全套验证。
- 最终只读审查进行中；USB核心、DualEndpointSession与input解码源相对e981705无修改。

- Final review: 一次独立只读审查完成，无Critical；一个Important/P2：系统重建会丢失设置造成的暂停意图。已通过PlayerUiCheck第58断言重现失败，现保存并恢复review.reloadPaused，保存策略覆盖onSaveInstanceState早于onStop，选择新文件仍清除；待最终GREEN。
- Final Ruling: 审查未判断的真实Spinner/SharedPreferences磁盘失败/OEM后台重建、设备权限/连接、截图与快速旋转需真机验收；本轮不访问设备，不能据JVM与静态审查推断已通过。
- Finish: 依用户规则保留分支及全部未提交内容，不执行提交/推送/安装或清理用户文件。

- Final fixed: 系统重建暂停意图P2，UI policy 58 RED→GREEN；Bundle保存与恢复reloadPaused，覆盖保存早于onStop及截图/设置隐藏状态；新选片仍清除。
- Final GREEN：配置656、UI65、绑定36场景349、截图377及全部原有JVM回归通过，Debug/三模块Lint成功；app25/player0/export1警告，均0错误。
- Final verification: APK签名/版本code18/16KB ZIP对齐通过，SHA-256 C056DF154D43FC78C6DA8796B15DB46D0169AC066EDB420A2AD24852445BF1F8；git diff --check通过，原始USB核心/异步回收/输入解码未改。独立审查无其他可核实Critical/Important，保留实际Android/设备验收边界。
- Tasks 1–5: complete。分支与未提交内容保留，无安装、设备访问、暂存、提交或推送。
