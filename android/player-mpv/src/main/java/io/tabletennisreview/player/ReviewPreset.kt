package io.tabletennisreview.player

/** No URI, Android or USB dependencies: the host owns file identity. */
data class ReviewPreset(val a: Double, val b: Double, val speed: Double) {
    fun validated(duration: Double): LoopPoints {
        require(speed in PlayerPolicy.speeds) { "书签倍速无效" }
        return LoopPoints(a,b).enabled(duration)
    }
}
data class ReviewSnapshot(val sessionId: Long, val preset: ReviewPreset, val duration: Double)
sealed interface ReviewPreparation {
    data class Ready(val snapshot: ReviewSnapshot) : ReviewPreparation
    data class Rejected(val reason: String) : ReviewPreparation
}
sealed interface ReviewRestoreResult {
    data class Ready(val sessionId: Long) : ReviewRestoreResult
    data class Rejected(val reason: String) : ReviewRestoreResult
}
object ReviewRestorePlan {
    fun requests(preset: ReviewPreset, duration: Double): List<NativeRequest> {
        preset.validated(duration)
        return listOf(
            NativeRequest.Property("pause","yes"), NativeRequest.Property("ab-loop-b","no"), NativeRequest.Property("ab-loop-a","no"),
            NativeRequest.Property("speed",preset.speed.toString()), NativeRequest.Property("ab-loop-a",preset.a.toString()),
            NativeRequest.Property("ab-loop-count","inf"), NativeRequest.Property("ab-loop-b",preset.b.toString()),
            NativeRequest.Command(listOf("seek",preset.a.toString(),"absolute+exact")), NativeRequest.Property("pause","no"))
    }
}
