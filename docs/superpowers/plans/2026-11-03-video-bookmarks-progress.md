# SDD ledger — plan: docs/superpowers/plans/2026-11-03-video-bookmarks.md

用户已批准设计与实施计划，Native顺序实施。
Ruling: 沿用现有codex/screenshot-annotation工作区，不新建worktree或提交 — 前置功能尚未提交，必须保留并复用；代价是最终diff包含此前修改，需明确本轮范围。
Pre-flight: 目录/codec→存储协调器→Dialog；ReviewPreset→PlayerController→宿主；交互token独立于播放器/USB会话。接口一致，依次测试。
Task 1: 开始，先新增纯书签模型测试。

Task 1: complete — 缺失模型27个编译错误RED→GREEN，51书签断言通过，包含Unicode、容量、同名隔离及坏数据。
Task 2: complete (first pass) — 缺失ReviewPreset/Plan的6编译错误RED→GREEN，108请求顺序断言及Player186/绑定483通过。新增实际核心读取/受控恢复，真实native行为待验收。
Ruling: 新增CommitUncertain失败分类 — AtomicFile.finishWrite返回void，若提交后无法核实内容则禁写并保留文件，避免后续覆盖；不能保证真实硬件故障的持久性，需明确待验证。
Task 3/4: 增加协调器及纯交互token测试，先运行RED。

Task 3: complete — 25缺失接口编译错误RED→GREEN，书签92断言及UI65/恢复108通过。曾遇Java检查型异常不兼容，定位并改为未检查的CommitUncertain，未改保存语义。
Task 4: 纯交互token验证已包含92断言；列表/命名/删除已接入Store，现新增宿主可用性测试，先RED。

Task 4: complete (first pass) — 可用性测试缺失7错误RED→GREEN：UI74、书签92、恢复108、绑定483、截图377通过，Debug构建成功。窗口/浮层及后台、来源代次接入；真实UI/磁盘/mpv尚未验证。
Task 5: 文档/版本code20已更新，开始完整离线检查。文件适配lazy创建，使filesDir访问异常由已有读取失败路径处理，不将首次读取永远停留在-1。

Task 5: 首轮完整GREEN — 书签92、恢复108、灵敏度2712、映射802、UI74、绑定54场景483、截图377、自动初始化124、播放器186、输入497、异步105、代次48、导出191及94字节回归全部通过；Debug/三模块Lint成功。
Lint: app26/player0/export1警告且均0错误；新增1项StaticFieldLeak指向仅持applicationContext的进程级Store，实际无Activity字段；该引用符合进程所有权，保留警告并说明，不掩盖检查结果。
APK: code20、v2签名、16KB ZIP对齐通过；SHA-256 70F81BC7F200104C6B201E72822C38646D025FDDE85A69AF6BF4A2A14184775B。
唯一最终独立只读审查已派发，等待反馈；未访问设备或暂存/提交。

Final independent review: 完成，未发现可确认Critical/Important功能缺陷，可交付为无设备检查通过、待真机验收版本。新增Application单例警告未见Activity泄漏。
minor (deferred): 测试未实际执行runReview逐步失败/启动前取消/恰好一次回调，以及Store Main消息投递/窗口关闭回调清理；只有请求计划、同步协调器、独立租约与静态审查证据。已在用户文档明确，后续可注入执行端口或Android集成测试，不为本轮重构。
Ruling: 审查未判断的mpv实机、底层磁盘/掉电、Android UI、SAF均保留待验收 — 当前工具范围不包含设备。关闭列表后再后台的策略沿用既有行为，不扩大成永久播放状态记忆；此前功能修改保留并做回归，不宣称本轮重新真机验收。代价是这些平台情形仍可能需要后续修正。
Task 5: complete with documented coverage limitation — 完整测试、Debug、Lint、签名、版本、16KB ZIP对齐、格式与一次独立审查已完成。没有安装、访问设备、暂存、提交、推送或清理用户文件。
Finish: 保留当前分支与所有未提交工作，用户安排下一步。
