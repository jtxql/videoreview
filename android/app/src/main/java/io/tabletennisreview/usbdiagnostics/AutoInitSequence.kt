package io.tabletennisreview.usbdiagnostics

/** Pure ordered, once-per-connection plan. Calls no USB API and never retries. */
enum class AutoInitStep { A1, A0, Init8, Reply26, Config94, Ready, Failed }
class AutoInitSequence(val session: Long) {
    @Volatile var step = AutoInitStep.A1
        private set
    @Volatile var failure: String? = null
        private set
    private var waiting: AutoInitStep? = null
    private var deadline = Long.MAX_VALUE
    @Synchronized fun next(token: Long, nowMs: Long, replyMatched: Boolean): AutoInitStep? {
        if (token != session || step == AutoInitStep.Ready || step == AutoInitStep.Failed) return null
        if (nowMs > deadline) { fail("等待${step}超时；不重试"); return null }
        if (step == AutoInitStep.Reply26) {
            if (!replyMatched) return null
            step = AutoInitStep.Config94
        }
        if (waiting != null) return null
        waiting = step; deadline = nowMs + 5000
        return step
    }
    @Synchronized fun complete(token: Long, action: AutoInitStep, result: Int, nowMs: Long) {
        if (token != session || step == AutoInitStep.Failed || step == AutoInitStep.Ready) return
        if (nowMs > deadline) { fail("初始化结果已超时；不重试"); return }
        if (waiting != action || step != action) { fail("初始化完成顺序异常"); return }
        val expected = when (action) { AutoInitStep.A1 -> 7; AutoInitStep.A0 -> 0; AutoInitStep.Init8 -> 8; AutoInitStep.Config94 -> 94; else -> -999 }
        if (result != expected) { fail("${action}返回$result，期望$expected；不重试"); return }
        waiting = null
        step = when (action) { AutoInitStep.A1 -> AutoInitStep.A0; AutoInitStep.A0 -> AutoInitStep.Init8; AutoInitStep.Init8 -> AutoInitStep.Reply26; AutoInitStep.Config94 -> AutoInitStep.Ready; else -> AutoInitStep.Failed }
        deadline = if (step == AutoInitStep.Reply26) nowMs + 3000 else Long.MAX_VALUE
    }
    @Synchronized fun fail(reason: String) { step = AutoInitStep.Failed; failure = reason; waiting = null }
}
