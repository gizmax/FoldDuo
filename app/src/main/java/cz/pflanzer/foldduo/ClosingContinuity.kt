package cz.pflanzer.foldduo

/**
 * "Zavírání jako Duo" (STATUS.md, 17. 9. noc), item 4: the reverse cover settle
 * ([MorphController.playCoverSettle]) should start from whatever frost level the CLOSING inner
 * half ended at (full, per item 2's "hold until swap") instead of the old dramatized
 * [MorphCurve.coverSettleStartTilt] guess — but the panel swap relaunches the activity
 * (UnfoldMorph.kt's file comment), so the inner half's [MorphController] instance and the cover's
 * fresh one are not the same object. This process-wide stash bridges that gap: whichever
 * [MorphController] last saw a real closing tilt writes it here continuously
 * ([MorphController.noteAngle], [MorphController.playCloseFrost]) right up until the process is
 * killed for the relaunch, and the next controller's cover settle consumes it once.
 *
 * `@Volatile` (not a lock) is enough: at most one [MorphController] instance is ever writing at a
 * time in practice (a fresh one only starts once the old activity's is gone), and a torn read
 * would only ever cost one settle's start value being slightly stale, never a crash.
 */
object ClosingContinuity {
    @Volatile
    private var lastClosingTilt: Float = Float.NaN

    /** Record the latest closing tilt seen, while it is genuinely nonzero. */
    fun note(tilt: Float) {
        if (tilt > 0f) lastClosingTilt = tilt
    }

    /**
     * Consumed once by a fresh controller's cover settle: the stashed tilt, or [fallback] if none
     * was ever recorded (a cold start straight to the cover) or it had already eased back to 0
     * before the swap arrived.
     */
    fun takeForCoverSettle(fallback: Float): Float {
        val v = lastClosingTilt
        lastClosingTilt = Float.NaN
        return if (v.isNaN() || v <= 0f) fallback else v
    }

    /** Test-only: drop whatever was stashed, so tests do not leak state to one another. */
    internal fun clearForTest() {
        lastClosingTilt = Float.NaN
    }
}
