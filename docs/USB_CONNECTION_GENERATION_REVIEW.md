# USB连接代次与设备地址复用判定审查（007-X）

> 007-F归档（2026-11-02）：本文件为历史调查/实现记录，不再推进其中待建议的物理拔出修复。USB-001为Deferred，保留现有隔离及Strict；最终验收见[007归档](TOURBOX_INPUT_ACCEPTANCE_007_FINAL.md)，恢复建议以[已知问题](KNOWN_ISSUES.md)为准。

状态：第1–8节保留007-X调查快照；007-Y已获准实施，代码记录和当前限制见第9节。范围为当前未提交工作区的007-V实现及后续按钮补充；没有修改Android代码、测试、构建配置或原有协议。没有连接ADB、访问USB、安装APK或运行新真机实验。本文路径采用`docs/USB_CONNECTION_GENERATION_REVIEW.md`（任务路径末尾反斜杠按排版转义处理）。

结论：**当前永久地址集合把“再次open”混同为“物理插拔后的地址复用”，错误缩减补充回收资格；但正常关闭后再次open本身已经允许。** 推荐拆开应用open代次、物理事件观察和资源所有权，取消“曾用地址即永久否决”的分类，保留不确定证据下的隔离。不能以清空集合证明旧广播已经消失，也不能承诺调整后一定取得异步完成。

## 1. 当前实现与调用链（代码事实）

行号基于本轮工作区，不对应上游或HEAD旧版本。

| 位置 | 实际行为 | 含义 |
|---|---|---|
|TourBoxUsbManager.kt:17–20|companion object中保存进程级leases和seenAddresses|Activity/Manager重建不清空|
|同文件:35–44 acquireConnection|先leases.acquire取得token；38行向集合add(deviceName)，add=false即reused=true；然后才openDevice|失败/返回null也已经留下地址记录；释放租约不撤销集合写入|
|同文件:239–301 connect/connectInternal|先撤销Manager epoch，worker关闭旧连接；检查枚举/权限后273行走acquireConnection|单接口/仅open诊断也会污染同一集合|
|同文件:415起 startDualListening|检查隔离、关闭旧连接、检查描述符，438行走同一acquireConnection；两次claim后创建session|双端点功能没有独立的地址历史|
|同文件:495–550 closeInternal|正常finish成功则release(token)、清resourceRecord/identity/leaseToken；失败retain(token,record)|**没有seenAddresses.remove/clear**；只有进程静态状态消失才整体重建|
|同文件:48–69 detached|捕获当前Identity，取枚举是否有该地址；检查当前租约token、广播name/id、!reused及缺失；标记逻辑断开，排队后再检查identity===key|防止已经捕获旧Identity的清理队列作用于替代Identity，但不是广播发生时的物理归属证明|
|UsbAsyncCleanupState.kt:107–109|matches必须!reusedAddress|同名第二次open以后，永久不能凭该判定授予补充观察|
|TourBoxUsbManager.kt:519–538|record.detachConfirmed且async.lifecycle.canSupplement才允许补充drain|标志主要影响原因分类、日志和这条门控，不直接禁止open|

集合仅在38行add，源码没有其他写入/清理。首次无在途资源且有权限时通常可open；正常完整close后租约释放，下一次同地址open可以成功，但Identity.reused必为true；不同Manager同地址也如此。打开失败同样可能使下一次成功open被标记复用。**“正常停止后不能重连”不是本次日志事实；“正常重开后的拔出被永久地址标志拒绝补充观察”才是事实。**

### 已有独立标识及对象

| 标识/对象 | 生命周期及用途 | 不能证明什么 |
|---|---|---|
|leases token|进程级计数器，在open之前预留；成功open唯一，失败可留下编号空洞；lease.release不重置计数器|不是系统USB插拔代次，也不是广播附带token|
|Manager epoch / request / command.epoch|每个Manager自己的AtomicLong，多种stop/close/connect/失败都递增；拒绝旧语义与旧控制任务|不等于open次数，跨Manager数值可相同|
|Identity对象与===|每次acquire创建，捕获在sessionKey和detach排队闭包中|是应用绑定身份；不代表物理设备身份|
|UsbDeviceConnection opened/connection|实际open返回对象；DualEndpointSession及UsbRequestResources持有原连接|不能由同name/id重建，也不能由地址集合推断请求已完成|
|UsbRequest及direct buffer|原0x81读取任务创建，只有返回原request才确认取回|cancel真假、线程退出、广播和枚举缺失都不能代替完成|
|UsbConnectionResources|隔离时保留原token/connection/interfaces/request，无Activity回调|当前主要靠worker串行与租约保护；补充判断未显式比较全部资源引用|
|deviceName/deviceId/UsbDevice对象|系统枚举及广播描述设备；connect再次按name/id查列表|不能作为唯一应用会话或持久物理代次|

MainActivity.kt:83–97传detach设备给Manager，attach目前仅日志，然后refresh；:310起refresh按地址缺失调用通用close；onStop/onDestroy走close/shutdown。通用close:306起立即递增当前Manager全局epoch，不带捕获token。因此方案不仅要检查detached的队列，还要把“刷新发现旧设备缺失”这类延迟观察绑定到原会话，避免未来异步刷新沿用无token的close。当前是否实际误停新会话没有日志证据。

## 2. 007-V实测现象与原因

只读重检电脑上此前导出的日志，未重新读取手机。352行，SHA256 `42ef69e2642eed3a52827fb64148762a94465817208a6ef9d82218b6a519307b`；不在本文记录原文件路径、设备地址、序列号或原始附件。

| 行号 | 证据 | 可得结论 |
|---|---|---|
|213–221|cancel=true；Original完成；request.close成功；双方退出；两接口release=true；connection.close成功|token=1正常停止完整回收通过|
|225–230|重新枚举及两接口claim成功|第二次连接实际成功，不是open被历史集合阻止|
|337–339|requestWait=null、cancel=false、初次drain=Null|I/O错误，原请求仍未确认；不能推定泄漏|
|340–342|拔出广播；“地址复用/代次不确定”；枚举0设备|当前代码将广播降为不确定，没有给补充资格|
|343–347|初次共5次Null，约20ms间隔；双线程退出，state=Quarantined、owner=coordinator|原线程退出、应用所有权交接完成，但原request未取回|
|348–352|token=2隔离；未见supplement=true或handoff记录|没有执行额外回收；未关闭未知pending符合保守退路|

**代码与日志共同支持**：第二次open使38行集合add失败，足以解释341行及后续门控被拒绝。中间没有可见detach/attach记录，符合“同一连接正常停止后重开”的操作；但日志不能排除未观察到的系统事件，不把“未记录重枚举”当绝对物理连续性证明。

两轮121个普通输入字节与121条语义逐一对应；第二轮仅session=4，未见session=1旧事件。每轮94字节一次，协议接收正常。末尾停在广播后约91ms，无测试标记或5秒观察证据。**不能证明补充观察若执行就一定成功，也不能将此次资源隔离等同于已证实内核泄漏。**

## 3. 应用代次与物理代次的边界

### 公共API事实

- [UsbDevice.getDeviceId](https://developer.android.com/reference/android/hardware/usb/UsbDevice#getDeviceId())说明ID不跨USB断开持久；getDeviceName标准实现为usbfs设备文件路径。公共契约没有给本应用可校验的持久插拔代次，不能推导“断开后ID必不同”。
- [UsbManager广播及EXTRA_DEVICE](https://developer.android.com/reference/android/hardware/usb/UsbManager#ACTION_USB_DEVICE_DETACHED)提供UsbDevice，不提供本应用open token、connection引用或物理发生时的应用epoch。[getDeviceList](https://developer.android.com/reference/android/hardware/usb/UsbManager#getDeviceList())是查询时枚举结果；列表为空也可能是Host模式不活动，不是针对某个连接的原子detach证明。
- [requestWait(long)](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#requestWait(long))返回完成请求，错误时可为null，超时抛TimeoutException。补充回收即便绑定准确，也不能保证物理拔出后还取得完成。

### AOSP实现线索（不是小米系统保证）

本轮只读AOSP main `core/java/android/hardware/usb/UsbDevice.java`，blob `71074df382fd0d4dae75723563bc7ead64d42c55`：172–173行getDeviceId从mName转换；311–318行equals比较mName（还兼容String），321–323行hashCode同样基于mName。参考[源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/usb/UsbDevice.java)。据此不能把name+id看成两份独立的物理身份凭据，或用UsbDevice.equals解决地址复用。不同Java对象可能描述同一枚举，同一Java对象被应用保留也可能已过时；`===`最多用于应用记录的引用绑定。

### 必须分开的三层

1. **OpenGeneration**：本应用成功open的唯一代次；保留现有进程租约token即可，不要求连续无空洞。
2. **ObservedAttachmentEpoch**：应用按收到attach/detach、枚举变更产生的观察编号。它只能记录“应用观察到的拓扑变化”，不能命名为Android真实物理generation；后台接收空窗、进程重建、延迟或重复广播都可使其不完整。
3. **CleanupOwnership**：原connection/request/buffer及线程交接状态。无论物理身份是否确定，未知pending都不得转给新连接或强制free。

### 当前旧广播风险

当前matches的observedToken参数来自`leases.owns(key.token) ? key.token : -1`，即再次使用当前key，不是从广播读取旧token。故它证明“仍拥有当前租约”，**并不证明广播属于该租约**。identity===key能挡住已捕获后排队的旧任务，挡不住广播很晚才到达、进入detached时已经捕获新Identity的情况。地址仍存在时当前代码会忽略；若同时枚举暂缺/又一次拔出，则同name/id可能仍造成逻辑停止。永久reused标志只拒绝补充观察，不能彻底消除此逻辑停止歧义。以上为可构造竞态分析，不是本次已经发生的误停事实。

## 4. 最小修改建议（待批准）

不建议只执行seenAddresses.remove或把reused改成false。建议保持现有worker、协议和进程独占结构，增加小型纯状态记录，限于Manager、MainActivity的观察入口、纯判定类及其测试；不需要改DualEndpointSession的读取参数或007事件模型。

### 4.1 Open记录与清理收据

- 仍在open前以进程级租约预留，只有open返回非null才提交不可变OpenRecord：token、Manager作用域、原connection引用、name/id描述符快照、open单调时刻、当时观察序号及来源。失败只释放预留，不产生“已使用物理地址”的永久事实。
- 同一UsbDevice重复open也必须生成不同token和connection记录。旧任务携带原OpenRecord/epoch，执行前同时检查租约token、当前记录引用及原connection；不要临时查当前connection再把旧任务贴到新连接。
- 正常停止的CleanCloseReceipt仅在读/控制任务已退出、无未知pending、request.close及connection.close已完成时生成；接口释放结果单列（拔出后false不等于请求仍pending）。明确close成功且流程为正常停止，才允许退役当前应用记录。
- **允许清除的是该次open的“复用即禁止”派生标记、已退役任务资格和正常收据中的资源强引用。不能据正常close清除物理插拔风险、未消费广播风险或其他记录的隔离对象。** 同地址再次open已被允许，修正的是不要仅因正常重开自动赋予PhysicalReuseAmbiguous。
- 有未知pending、request/connection关闭异常、未join或仍在native时，不发正常收据，不release租约；新Manager/Activity也不能重新open。保持单进程最多一个活动/隔离所有者，不重置token计数器。

### 4.2 用观察状态代替永久曾用地址布尔值

维护进程级轻量AddressObservation（无Activity回调），记录观察序号、当前Present/Absent/Unknown、最近已观察attach/detach、接收空窗/冲突及必要的退役代次元数据。正常close不是物理detach；同地址正常重开且枚举仍存在，记作“ObservedContinuity/无已观察拓扑变化”，**不记作已证实同一物理设备**。确有detach/attach或空窗后同址出现，记PhysicalGenerationUnknown，不因为新的app token自动解除。

观察入口应串行化，更新记录和预留/提交/关闭判定使用短锁或单一协调器；锁内不调用USB native、不join、不等待事件消费者。接收线程只采集不可变快照，原worker负责资源操作；若open发生在快照与提交之间，重新验证后拒绝旧观察。只在attach分支加日志或单纯递增计数器不能解决。

不能用“等几秒”“多查两次deviceList”“排空应用Handler”作为系统广播队列已无旧事件的证明。正常收据让重新open安全，不让未知广播自动变成可信。

### 4.3 不可变detach观察快照

建议DetachObservation至少保存：本地观察ID、接收单调时间、接收时捕获的OpenRecord/token/epoch、当时的原connection引用（仅有效记录生命周期内）、广播name/id描述、枚举Present/Absent/QueryFailed及观察序号、当前connection的独立I/O故障观察（若有）、歧义原因。捕获的token只叫capturedOpenToken，禁止叫broadcastGeneration。

Worker再次核对原记录仍为当前或唯一隔离记录、lease仍归其所有、connection仍为该记录原对象，才能处理。晚到旧快照不能撤销新epoch；刷新发现缺失也应传同类观察，不走无token的close。用户明确点击“关闭当前连接”可以保留通用close语义，设备观察与用户意图分开。

重复通知复用/去重同一判定，不把可变detachConfirmed反复覆盖；新矛盾应追加Ambiguous观察，使尚未执行的补充观察失效，不能静默从不确定改成确定。原始观察保留必要元数据，隔离记录不保留Activity回调。

## 5. 补充回收门控调整建议

拆成两个独立判断，不再让“曾用过该地址”代表一切：

| 判断 | 有效依据 | 无效替代品 |
|---|---|---|
|OwnerProof：操作原请求的资格|原OpenRecord、原connection/request/buffer引用、租约token仍属于该记录；双方读线程真正join，控制任务结束，唯一交接完成|name/id相同、VID/PID、getFileDescriptor非负、一个线程退出标志|
|DetachEvidence：触发额外观察的证据|不可变观察与记录一致，枚举与独立I/O状态可审计；没有观察空窗/拓扑冲突/未归属旧广播；按批准的关联规则得到Correlated|把当前token填进旧广播、清seenAddresses、正常close收据、相同UsbDevice对象|

推荐决策为`Eligible / Rejected / Unknown`并附理由，避免布尔量命名为“物理代次已确认”。**正常同地址重开不再因历史add失败直接Rejected，但也不能因此直接Eligible。**

执行补充观察还必须满足：原请求确为CleanupUnconfirmed/可补充Quarantined（不是已关闭或close异常）、原线程和控制操作全部退出、仍持有原连接且没有替代open、尚未消耗本次补充机会、日志可用。执行前消费该记录单次机会，最多5次/1000ms、有限timeout与退避；不再次cancel/queue，不发送命令。只有返回原request才正常关闭，仍未知继续隔离。native不返回时不能并发free。

### 不能隐藏的策略选择

公共API并不能在所有情况下证明广播属于哪次物理插拔。即使观察连续、I/O失败紧邻广播，也只是关联增强；不能把它们写成物理身份证明。

**推荐本次后续实现先采用严格保守版本**：OwnerProof失败直接Rejected；快照跨代、地址重新枚举、接收空窗、枚举查询错误或广播归属无法消歧时为Unknown，不进行额外requestWait。只有在后续审核明确认可的无歧义观察路径上才Eligible。对于“旧广播刚好遇上新会话也失效”的不可区分轨迹，必须Unknown。

这会修正“每次第二open永久判复用”的事实模型，但**不承诺007-V同一顺序下一次必执行补充drain**。在目前证据下，没有足够依据把该次广播物理归属提升为严格证明。

另一候选是把补充观察改成“原连接独立I/O失败+枚举缺失+OwnerProof”触发，广播只作诊断。它能减少无关旧广播的影响，且只wait原对象，但属于对007-V触发策略的改变，不是删除一个布尔量的等价修复；若用户希望采用，应另行批准并给出平台/真机验证。**本报告不默认采用，也不通过它放宽未知pending关闭规则。**

### 元数据清理边界

成功正常close：可去除该open资源强引用、取消旧动作、解除lease；保留必要的地址观察/历史歧义元数据，不能宣称旧广播已排空。物理detach/重枚举：保留已观察转换、歧义及隔离资源；若原请求取回并完整close，可退役资源，不自动删除物理歧义。无法限定系统旧广播最晚到达时间，不能用任意TTL把Unknown升级Eligible；元数据压缩后若失去判别能力应更保守。

## 6. 无设备单元测试计划（未实施、未运行）

当前UsbAsyncCleanupCheck的20场景/105断言覆盖低层回收及租约，但第11场景是手工构造reused=true，第15场景仅测租约；没有把正常close→地址历史→再open→detach入口串起来。因此当前通过并未排除本问题。原输入497断言及94字节回归是先前执行结果，本轮未重跑。

新增纯OpenRecord/Observation判定测试和可控调度的Manager等价协调器集成测试，使用fake connection引用、fake request、单调时钟、枚举快照与事件队列；不依赖真实Android UsbDevice实例或平台驱动。应断言“实际调用了哪个原连接的wait/close以及次数”，不能只断言一个分类布尔值。

| 场景 | 验收断言 |
|---|---|
|1 首次连接|唯一租约预留并提交；无历史不代表物理证明；记录与返回connection绑定|
|2 正常停止后同地址重开|完整正常收据后可open，token不同；不因曾用地址直接PhysicalReuse；未知广播仍不能自动Eligible|
|3 正常停止后不同地址重开|新记录独立，旧地址观察不能关闭或wait新连接|
|4 拔出后同地址重新插入|观察序号更新为新观察段，物理真实代次仍Unknown；旧token/快照拒绝|
|5 旧detach晚于新open|测试已捕获后排队与很晚才收到两种；新设备Present时不停止；无法区分归属时不执行补充wait|
|6 同名不同UsbDevice描述对象|不凭对象不同认定重插，不凭equals相等认定同代；只操作捕获记录|
|7 同一描述对象再次open|token/connection仍全新；对象复用不能继承旧请求/补充机会|
|8 旧未知pending隔离|没有正常收据；同址/异址及新Activity/Manager都不能open或覆盖旧资源|
|9 多Manager并发open|并发争租约仅一方进入native open；失败释放预留，隔离不释放；旧token不能release新owner|
|10 重复detach|去重或新增观察但不重复cancel/补充；矛盾通知不能静默覆盖证据|
|11 detach早于I/O错误|先只逻辑失效原记录，未join不能wait；证据不足不进入额外观察|
|12 I/O错误早于detach|原reader初次回收及join后才可交接；晚到快照核验仍指向原记录；Unknown保持隔离|
|13 地址复用歧义|枚举Present/Absent交错、隐藏拔插、旧广播与新失效同现：Unknown，不wait/free任何新连接|
|14 open=null/异常|释放仅自己的预留，不写入成功open/物理复用历史，不污染下次分类|
|15 close/request.close异常|不生成正常收据，不解除隔离，不通过“正常重开”清地址记录绕过|
|16 快照后发生新open|两个观察阶段间故意切换记录，旧设备刷新/广播任务拒绝；不递增新会话epoch|
|17 接收空窗与重建|MainActivity后台/销毁/重新注册及进程观察未知标记保留；没有新物理证据不提升|
|18 收到原请求完成|补充调用严格属于原connection，只消费一次机会；成功才close；关闭后零cancel/wait/release重用|

保留007输入、四组新增按钮、初始化回复边界、94字节门控、低层异步生命周期测试。测试“Eligible”可注入明确的应用策略证据，但不得据fixture声称Android实际提供了物理generation。

## 7. 不能由公共API消除的风险

1. 广播没有本应用open身份；本地接收时间不是事件物理发生时间，也没有可依赖的系统物理代次序号。
2. 同址重枚举、描述对象重建/复用、name/id相等与VID/PID相等均不足以证明同一物理代次；设备序列号即使可读也不能区分同一设备两次插拔，本方案不读取它。
3. 枚举快照、权限、广播、open和native I/O不是一个原子事务；连续两次列表相同也不能排除中间变化。Host停用可能造成缺失。
4. 若“真正当前detach”和“旧detach在当前也缺失时到达”产生相同可见输入，纯应用判定无法区分，必须Unknown。新增本地计数器/immutable快照解决应用竞态，不解决不可见历史。
5. 原pending可能永远取不回；不可以以新代次或设备已拔出为理由强制释放。native超时不履约时保留隔离/工作线程，无法承诺硬性等待上限。
6. 跨Activity/Manager的进程独占是应用保护，不是系统全局USB独占证明。所有open必须继续走同一个租约入口，不能只在双端点模式检查。

## 8. 后续真机验收建议（实施批准、构建、安装授权后）

1. 保留旧日志，明确APK哈希与版本，记录app token、Manager epoch、观察序号及证据级别，不输出设备敏感信息。
2. 同一设备不拔出，用户原流程初始化并验证输入，然后正常停止；确认request取回、关闭、双方退出、connection.close及收据成功。连续同址正常重开至少3次，确认每次token独立、没有仅因历史曾用而判物理复用。
3. 随后新会话原流程初始化，加“即将拔出”标记，物理拔出、前台等待至少5秒、结束标记。检查OwnerProof、不可变观察、判定理由和是否有资格补充；若Unknown，明确记受限，不强行把它算通过。
4. 若补充被允许，检查只在原线程/控制操作结束后最多一次5次/1000ms观察，无重复cancel；只有原request返回才释放关闭。若仍未知，停止后续重连；不通过重启标志或强开绕过。
5. 仅完整回收后再拔插重连，观察地址相同/不同两种实际分配（不强制系统分配地址）；验证新token不接受旧任务。多Manager、迟到广播和查询竞态优先用fake调度验证，不为造竞态直接重放系统广播或USB命令。
6. 正常停止及物理拔出分别至少5轮，前台/后台与旋转单独标记，权限允许时以后再做只读FD/线程趋势检查。本轮没有执行这些步骤。

## 本轮交付与检查

新增本文，并在CHANGELOG登记调查。只读检查代码、测试、方案、既有电脑日志及Android官方资料；没有改代码、编译或运行测试。保留全部既有未提交文件、原日志和抓包，不自动提交Git。实际检查：调查前后20个Android源码、测试及app构建配置文件SHA256全部一致；git diff --check通过，新增文档8项结构及逐行空白检查通过。未执行编译或单元测试；本轮仅新增本文并追加CHANGELOG条目，其余已有修改/未跟踪文件保留。


## 9. 007-Y实施记录（2026-11-02）

### 已实现的生命周期与身份

- 新增纯Kotlin `UsbConnectionGeneration.kt`：UsbOpenGeneration绑定不可复用的进程租约token、Manager作用域、原connection引用、描述符快照、open单调时间及接收覆盖观察编号；只有native open返回非null才登记。没有seenAddresses或“曾用地址”黑名单。
- OpenPhase为Open/Stopping/Quarantined/Closed；OpenOrigin为FirstObservedOpen/NormalReopen/ObservationUnknown。正常停止且完整关闭后，同地址重新open记录NormalReopen；失败open只释放原预留，不留下成功代次。Activity重建不自动形成“物理地址复用”结论；接收空窗只影响观察可信度。
- 原有ExclusiveUsbLease仍在open之前预留，所有Manager共用；只有关闭成功才能解除所有权。记录建立、清理所有权异常或未知pending继续隔离，不覆盖已有隔离资源。CloseReceipt只记录token、单调关闭时间和正常性，不持有connection/request或Activity。
- 正常收据必须有明确正常停止请求、没有独立I/O失败或设备逻辑缺失/观察冲突，并且原请求及connection已关闭。异常关闭即使无在途请求且close成功，也不伪报正常停止。releaseInterface结果单独保留。
- 同步BULK读取、CDC/8/94控制任务、detach清理和补充观察都核对原记录、进程租约及connection引用；DualEndpointSession只增加原连接身份查询，不改变读取参数/协议。

### 观察与门控

MainActivity把attach和receiver注册/注销传给轻量进程观察账本；refresh不再用设备名称缺失直接调用无token的close，而由Manager在枚举前捕获原记录。广播/枚举快照不可变，包含原token、connection、Manager epoch、源、单调时间、设备描述、枚举状态、接收覆盖编号、open阶段、已知request状态（未知则null）及先前I/O故障时间。

分类为Correlated/Unknown/Unrelated。旧记录、旧token、不同connection或当前仍存在的设备为Unrelated；接收空窗、重新attach冲突、查询失败、证据不足为Unknown。重复广播不覆盖第一份原快照；矛盾存在/查询失败会使原证据失效。身份一致且枚举缺失可以让原会话逻辑断开，但Unknown不能执行额外requestWait；逻辑断开不代表物理代次已确认。

**运行时使用任务要求的严格默认 `DetachPolicy.Strict`。Android普通detach广播本身没有物理代次凭据，因此当前裸广播路径仍为Unknown，不会被伪装成Correlated来触发补充回收。** 同址正常重开的误分类已修正，不等于广播归属问题已解决。新增的CorroboratedApplicationEvidence候选判定（原连接独立I/O失败在先、缺失、绑定快照及无已观察冲突）仅在无设备测试中用于核实正向门控/回收路径；生产入口没有启用它，也没有UI开关。是否接受此应用层关联标准需要用户单独明确，不把它当物理身份证明。

SupplementReadiness统一核对：原资源/租约匹配、读线程已join、控制/OUT已结束、协调器持有唯一wait权、请求仍可补充、枚举仍缺失、完整日志可用、观察为Correlated。实际observe入口再次核对生命周期资格和owner，再使用既有一次5次/1000ms drain；不重新cancel/queue，不新增任何USB命令。纯测试验证Correlated fixture的成功和持续Null；不据此声称当前Android广播能提供这种证据。

当前保留007-V旧UsbDetachIdentity纯帮助类供旧回归测试使用；生产Manager不调用它。它不再决定应用连接资格或补充回收；后续清理兼容测试可另行评估，不在本轮大范围改测试历史。

### 验证与待真机项

新增 `:app:testUsbGenerations`，覆盖首次/正常同址异址重开、失败open、Activity作用域变化、旧快照、同名新代次、资源隔离、20线程争租约、重复/未知观察、全部补充先决条件、成功与持续Null、关闭异常、广播/I/O先后、覆盖空窗、重插冲突及连接对象复用。测试只使用JVM对象、fake RequestIo和时钟；生产关联、门控和低层回收类共用。

实际最终验证：离线 :app:testInputEvents（497断言）、:app:testConfig94、:app:testUsbAsyncCleanup（20场景/105断言）、:app:testUsbGenerations（27场景/48断言）、:app:assembleDebug、:app:lintDebug 全部通过；49项任务，17执行/32最新。Lint 0错误/7既有警告（AGP版本建议1、备份规则1、诊断文字国际化5），未屏蔽。git diff --check及新增文件空白检查通过；输入层4文件修改前后SHA256一致，CDC/8/94实现与检查点一致。最终APK签名验证通过，SHA256 B87854A63166CECF0979D40A142083DF870F1A0C2D269FFB004C0DAB0D85144A。apksigner仍有JDK未来native-access提示，不影响当前验证。未安装、访问设备或提交Git；不以模拟Correlated夹具证明真实广播可关联。原输入映射、8/94数据、CDC、端点、手动初始化不变；没有ADB、USB设备访问、安装或Git暂存/提交。原有源码/文档修改和抓包均保留。

后续获准安装后：同一设备不拔出，按原流程手动初始化/正常停止至少3次，核对新token、NormalReopen及close receipt；再标记拔出，前台观察至少5秒并加结束标记。严格策略下Unknown并隔离是预期安全结果，不能算自动恢复通过；未确认资源回收前停止重连。若用户另行认可应用层关联证据，再单独验证Correlated与一次补充观察，不能直接改常量强行放行。
