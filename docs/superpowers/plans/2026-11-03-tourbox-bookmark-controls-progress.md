# SDD ledger — plan: docs/superpowers/plans/2026-11-03-tourbox-bookmark-controls.md

用户批准设计/计划，Native顺序执行，基线9398f95。
Ruling: 沿用当前分支和工作区，不新建worktree或提交 — 用户要求本会话复用已验证功能；最终diff可能包含设计/计划，明确范围即可。
Pre-flight: 导航状态→宿主；keeping快照→快捷保存；映射动作→双击桥接→宿主。接口已核对，无冲突。

Protected hashes:
```json
{
  "android/app/src/main/java/io/tabletennisreview/usbdiagnostics/TourBoxUsbManager.kt": "70043d7c6733cef47629ae43ec4d20fa3d7eb75a080be94d72c3b190c46df855",
  "android/app/src/main/java/io/tabletennisreview/usbdiagnostics/DualEndpointSession.kt": "d6d5a239cd99b4f658ca7ee414e9dff661ea824543432d3170285b837d872fe6",
  "android/app/src/main/java/io/tabletennisreview/input/TourBoxEventStream.kt": "44d7d09bd8b211cdb65a26205b7e36e460822f012cd9fcaa8e35217d52b6e59c",
  "android/app/src/main/java/io/tabletennisreview/input/TourBoxInputDecoder.kt": "af23000add6dbc1e2610f78bf1f88c25d8d755e2602fa6986a9156dcdbc0e953",
  "android/app/src/main/java/io/tabletennisreview/input/TourBoxInputEvent.kt": "001428d481a1516af3ff58b23c773f8d755a6a8bb0d64201ff0829a4ae271be6",
  "android/app/src/main/java/io/tabletennisreview/input/TourBoxInputPipeline.kt": "90e77b8805018511ef87dc4f853762fbfdfe8a288c6393697a00e6897efffb5f",
  "android/app/src/main/java/io/tabletennisreview/playback/RotationSensitivity.kt": "6955678c970b3adc3f2ebd994c11dc0b98c9deb43d6c562e47a89ba001c8b16b"
}
```

Task 1/2: 新增纯模型/快照计划测试，先观察RED。

Task 1/2: complete — 缺失接口RED→GREEN：导航/名称65、书签92、快照/恢复128、播放器186、绑定483断言通过；真实native状态待验收。
Task 3: 新增映射一次性升级与失败测试，先RED。

Task 3: complete — 缺失接口19错误RED→GREEN，映射956、旋转2892通过，迁移成功/失败/重启/用户后改均覆盖。
Task 4: 新增纯手势与FakeTimer/宿主路由测试，先RED。

Task 4: 模型20断言GREEN；旧绑定第209断言失败，原因是旧用例仍将Short/Tall当默认None，已显式设None保留原测试目的，新默认另有专测。增加定时拒绝、过期、屏幕取消、重新启用后迟到回调场景；待GREEN。

Task 4: complete — 缺失接口10错误RED→GREEN；手势20，绑定69场景543，映射956，旋转2892通过。定时拒绝/迟到/过期与自身停用租约均覆盖。
Task 5: 新增实际成功结果接受方法及HUD组合测试，先RED；补结果接受方法使保存失败/旧来源的行为有纯模型实测，而非只在宿主写if。

Task 5: complete (first pass) — 缺失结果接受/HUD12错误RED→GREEN，相关JVM/Debug集成构建成功。实际宿主早退分支静态发现可保留持有，补统一Rejected清理；真实Android宿主分支未执行，明确保留平台测试边界。模型构造参数异常改为提示而非抛出UI；成功前同步最新目录，防旧缓存清掉新标题。
Ruling: 新增acceptSave/acceptRestore纯结果接受入口和BookmarkHudPolicy供实际宿主使用 — 让失败与旧来源/标题规则可直接测试，不复制宿主if；代价是多两个小接口，未改变协议。
Task 6: 文档与code21已更新，开始完整检查。

Task 6: full GREEN — 导航/HUD81、手势20、书签92、快照128、映射956、旋转2892、绑定69场景543、UI74、截图377、AutoInit124、Player186、Input497、Async105、Generations48、Export191及Config94全部通过；Debug/三模块Lint成功。
Lint: app27/player0/export1警告且均0错误，新1项SetTextI18n来自设置双击说明，未抽取翻译资源；当前中文功能无确认缺陷，不扩展国际化重构。
APK: code21、v2签名、16KB ZIP对齐、SHA-256 714BE4573A9E9F68DEFD5C982FA7E6B43343D267B124FA457BE6052284BC1A0D；USB/input/RotationSensitivity哈希与起点一致。
Final independent review: 唯一只读审查完成，无可确认Critical/Important/Minor缺陷。
Ruling: 审查未作实测判断的Handler延迟、真实native/持久化故障、HUD布局触摸保留待验收；已提交旧视频保存可以完成但不操作新视频，符合用户批准设计。代价是平台行为还可能需实机后修正，不能据模型与源码宣称已验证。
Task 1–6: complete，保留当前分支及用户文件；无APK安装、ADB/USB访问、暂存、提交、推送或清理。
