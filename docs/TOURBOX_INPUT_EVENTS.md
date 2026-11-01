# TourBox Lite输入事件层（任务007）

当前状态：007 Completed with known limitation，实测结论见[最终验收](TOURBOX_INPUT_ACCEPTANCE_007_FINAL.md)。物理拔出限制见[USB-001](KNOWN_ISSUES.md)，Deferred，不阻塞008；以下历史验证记录保留其当时范围。

## 范围与保护边界

只实现语义输入及诊断，不开发播放器、逐帧、AB循环、映射动作、加速/合并/节流/去抖。006Q/006R成功流程仍为手动双claim/接收→A1 115200/8N1→A0控制线0→固定8字节→完整26字节验证→Windows原样94字节一次→0x82事件。旧单接口诊断及旧CDC实验保留。本次按钮补充不改变USB命令字节、参数、端点、超时或007-V既有读取/释放流程；未新增自动初始化。

## 事件模型及映射

纯Kotlin包`io.tabletennisreview.input`不依赖Activity、View、Android USB API。

`TourBoxInputEvent`为sealed interface，含ButtonDown(button)、ButtonUp(button)、KnobClockwise、KnobCounterClockwise、ScrollUp、ScrollDown、UnknownRawCode。按钮枚举为KNOB、SCROLL、TOP、SHORT、C1、C2、TALL、TOURBOX。每个事件带InputStamp：sessionId、每字节sequence、receivedNanos（单调时间）、wallTimeMillis及unsigned rawCode(0..255)。接收时间在原始文件写入前捕获，不是设备动作发生时间；同读取块共用时间，每字节序号唯一，协议回复占用原始序号但不产生事件。

| 原始码 | 事件 | 依据及边界 |
|---|---|---|
|44 / 04|KnobClockwise / KnobCounterClockwise|006Q成功日志及W2/W3时序|
|49 / 09|ScrollUp / ScrollDown|同上|
|37 / B7|ButtonDown(KNOB) / ButtonUp(KNOB)|006J/006P/006Q原始日志|
|0A / 8A|ButtonDown(SCROLL) / ButtonUp(SCROLL)|006J原始日志与已有协议资料交叉支持|
|02 / 82|ButtonDown(TOP) / ButtonUp(TOP)|W2抓包与已有协议资料；此82是数据，不是端点|
|03 / 83|ButtonDown(SHORT) / ButtonUp(SHORT)|006P原始日志及既有码表支持|
|22 / A2|ButtonDown(C1) / ButtonUp(C1)|TuxBox固定源码；007-Y已观察语义及按下/松开配对|
|23 / A3|ButtonDown(C2) / ButtonUp(C2)|同上|
|00 / 80|ButtonDown(TALL) / ButtonUp(TALL)|源码名tall；用户称Long，非长按事件；初始化回复必须分流|
|2A / AA|ButtonDown(TOURBOX) / ButtonUp(TOURBOX)|源码名tour|
|其他|UnknownRawCode|保留无符号值，不猜释放规律或其他控件|

每个旋转字节一个事件，重复字节不合并，不推断角度、速度或刻度数。新增四组依据：TuxBox固定提交`f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81`，`tuxbox/config_loader.py:115–124`明确按下/释放对，`tuxbox/gui/controller_models.py:12–16`将四者列入Lite，`tuxbox/device_base.py:273`起按元组首项/次项识别按下/释放。007-V日志出现四组数据；用户提供四个控件名称但不完全确定对应关系，因此逐组映射以固定源码为依据，加入时并非新增真机逐键验证；后续007-Y已观察上述按下/松开语义且配对正常，详见最终验收。此前验收报告中的Unknown统计保留为当时版本事实。上游旋转停止候选C4/84/C9/89仍不擅自解释。

固定源码：[码表](https://github.com/AndyCappDev/tuxbox/blob/f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81/tuxbox/config_loader.py#L110-L124)、[Lite控件集合](https://github.com/AndyCappDev/tuxbox/blob/f1ac4d8ab3346dac1bb2220c587a7c4f083f4c81/tuxbox/gui/controller_models.py#L12-L16)。

## 解码边界与流式策略

`TourBoxInputDecoder.begin(sessionId)`建立逻辑接收会话，正常模式逐字节解码。只有已知八字节发送前，由现有连接协调器调用`expectInitializationReply`，开启与现有证据窗口一致的3秒观察窗；不按USB块长度猜测消息。`feed`输出Batch(events, responses, boundaryFault)，每次读取可以0或多个事件。

等待回复期间仅缓存预期26字节的最长匹配前缀（最多25个未完成字节，暂存原时间/序号）；非匹配前导字节以UnknownRawCode诊断，不派发控制动作。完整精确26字节从事件流分离，记录协议消息；同一块紧随其后的字节立即正常解码。支持26个单字节分片、所有分割点、回复与事件合并及回复早于TX END。已有Config94Experiment门控仍独立按原逻辑核实回复，输入层不替代或放宽初始化资格。

若等待超时，下一次RX将缓存及后续字节作为UnknownRawCode，明确记录boundaryFault，本轮不再将可疑回复尾部转换为控制动作。结束会话时未完成前缀不投递旧控制事件，输出待判定字节数，完整原始块仍在原日志内。没有新RX时不会凭空产生超时事件或新增USB轮询。

此边界依赖已知请求上下文，不是任意TourBox消息格式解码：未请求的未知回复、未来新增命令的响应及相同字节流的语义歧义不能仅靠单字节码解决。当前不发送动态16字节，没有为未知回复猜测长度或按>=20丢弃。扩展命令前须补独立响应边界，不能把USB读取块当报文。

## 分发接口、队列及生命周期

选择有界离散事件队列+后台回调，避免StateFlow相同值合并；使用现有JDK Executor，无协程/新库。`TourBoxInputEvents`只暴露subscribe、isCurrent和withCurrent；Manager.inputEvents提供此只读接口，消费者不能通过该接口begin/end/publish。

- 每个Manager一个单工作线程，容量1024条待分发事件（另有最多1条正在执行），FIFO，订阅者按注册顺序接收同一事件；订阅不回放旧事件，AutoCloseable取消订阅。
- producer只持短队列锁，消费者耗时不在USB接收线程执行。默认订阅者在该线程写标准事件日志，每事件只记一次，格式含session、原序号、时间、RAW与语义。原始USB日志另保留，这是双层证据而不是重复派发。
- 容量满显式记录溢出及取消条数，禁用本轮语义分发、清空队列，后续publish返回false；不静默覆盖最旧事件，不自动节流，也不改变USB采集。新会话才恢复；若消费者异常/Executor拒绝，也明确故障并失效。诊断日志故障沿用现有原始捕获故障停止机制。
- 停止、拔出、切换、后台/销毁及通信失败推进原epoch，并使解码器/队列失效；每次派发和withCurrent还对照Manager当前epoch，避免清理窗口或迟到开始复活旧会话。会话是该Manager的逻辑接收epoch，停止后重启即新会话，不能用旧事件控制新连接。single监听finally、dual清理也结束语义分发，原生USB等待/释放顺序不改。
- `end`等待已经进入的回调完成，之后不会再派发旧回调；不能强行中断已执行的外部动作。消费者回调不应无限阻塞，否则会拖延结束会话。内部事件诊断为短回调；未来播放器应异步安排重工作，并在真正执行操作时用`withCurrent(event) { ... }`再次验证。只保存event.sessionId然后无校验执行是不符合接口约定的。
- 正常断连清空的是明确失效会话的待办，不视为隐蔽丢事件；应用销毁有序关闭输入Executor，订阅取消也等待已进入回调，晚到旧事件拒绝。

示例（伪代码，不含播放器实现）：

```kotlin
val events = usbManager.inputEvents
val subscription = events.subscribe { event ->
    // 此处是后台输入线程。耗时任务另排队；执行时再次校验。
    playerExecutor.execute {
        events.withCurrent(event) {
            // 按event类型提交短控制操作，UnknownRawCode仅诊断。
        }
    }
}
// 页面/模块退出时：subscription.close()
```

MainActivity的UI交互日志已交给独立后台Writer，标准事件磁盘记录在输入工作线程；UI仅合并刷新诊断文本。原始RX文件追加仍在既有后台读取路径，未重构采集时序。启动建立一次日志文件的元数据写入保留，不在主线程逐事件写盘。屏幕200条/约128Ki字符和文件8MiB上限及故障提示仍有效，文件故障后不声称记录完整。

## 无设备单元测试与运行

使用既有Kotlin运行库和JDK，无JUnit下载；新增明确的Gradle JavaCompile/JavaExec测试任务，避免把“没有测试被发现”当作通过。测试源码`android/app/src/testInput/java/TourBoxInputCheck.java`；原94字节夹具继续复用`scripts/tests/Config94Check.java`。

从android目录、兼容JDK下执行：

```powershell
.\gradlew.bat :app:testInputEvents :app:testConfig94 :app:testUsbAsyncCleanup :app:testUsbGenerations :app:assembleDebug :app:lintDebug --offline --no-daemon --console=plain
```

`check`也依赖四个验证任务。测试只在JVM运行，不调用ADB，不加载Android/USB实现。

覆盖：四方向、八按钮按下/释放、未知码、批量/多次读取、负Byte、重复字节/顺序/时间/会话；完整26字节及全部分割点、逐字节分片、与后续事件同块、前导未知码、超时保守隔离；断连/旧会话、迟到操作、溢出/消费者异常、慢消费者不在RX执行、结束等待在途回调、取消订阅。配置回归覆盖固定payload、分片回复门控、二次确认及32线程单次reserve。静态对比成功检查点的CDC/8/94字节常量、读取任务和全部USB调用不变。

以上为最初无设备验证边界；后续真机结果见最终验收，不将JVM成功等同Android内核、USB接收吞吐或生命周期全部通过。


任务007最终验证：离线 `:app:testInputEvents :app:testConfig94 :app:assembleDebug :app:lintDebug` 全部成功，输入检查200项断言及原配置回归通过；最终47项任务中16执行、31最新，lint 0错误/9条非阻断警告（固定Gradle更新、旧版备份规则、诊断文字国际化），没有屏蔽或新依赖。首次编译遇到新增字符串插值缺括号，已修正；后续取消订阅/断连竞态完善后重跑最终检查通过。APK签名验证通过，SHA256 `D2311FEAB3757169E5BF913C6A8821785E6329C86E41053632C39A526B769905`；apksigner有JDK未来native-access提示，当前验证成功。静态比对确认CDC、8/94常量与门控、原读取类/原始日志存储和所有USB传输/claim/release/close调用行不变；只增加语义生命周期及RX旁路，不简化初始化。没有安装或执行真机测试、未提交Git，旧抓包/缓存未动。


## 007-T真机验收进度

语义逐字节对应及初始化分流已获得本轮真机日志支持；拔出时进入已有0x81异步隔离，压力时长及拔出后观察缺标记，整体验收待补项。详见[007-T验收报告](TOURBOX_INPUT_ACCEPTANCE_007T.md)。这不修改上文历史构建及测试范围，也不代表资源清理已全面通过。


## 008消费约定

008可直接使用Manager.inputEvents（只读TourBoxInputEvents）订阅，无需重写协议：subscribe返回AutoCloseable，结束消费者生命周期时关闭；事件为离散FIFO，不用StateFlow覆盖最后值。消费者回调在输入后台线程，不能直接操作View，也不应阻塞或同步等待USB/UI线程。调度到播放器线程执行前，再用isCurrent/withCurrent核验会话；不能只在最初收到时检查后永远信任旧任务。

原有容量1024、溢出/消费者异常显式失效语义、会话撤销、原始日志等行为原样保留。播放器只消费按钮/旋转事件，不执行USB初始化；007手动流程和USB-001隔离边界不因008改变。


007-F归档：四方向及已测试八按钮、134条逐字节对应、回复分流、正常停止/同址重开已通过；物理拔出自动恢复、5秒观察及完整压力覆盖不记通过。当前无设备输入回归为497断言通过；USB-001 Deferred，008可直接复用本接口。
