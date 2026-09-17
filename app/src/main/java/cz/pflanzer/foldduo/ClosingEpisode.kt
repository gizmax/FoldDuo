package cz.pflanzer.foldduo

import java.util.Locale

/**
 * "Zavírání jako Duo" (STATUS.md, 17. 9. noc), item 6: one measurement per closing episode —
 * which signal noticed the close first ([onsetSource]: `motion` the coarse gyro spike,
 * `integral` [cz.pflanzer.foldduo.pose.ClosingOnsetDetector]'s sustained-rotation bridge, `step`
 * neither — the plain hinge-step transition, same as before this feature), the hinge angle at
 * that moment, and how the frost's own timing played out relative to the eventual swap. Pure
 * Kotlin (a plain elapsed-ms clock, same convention as [MorphController]'s own `now`), so both
 * this and [ClosingEpisodeTracker] are unit-testable on the JVM.
 */
data class ClosingEpisodeStats(
    val onsetSource: String,
    val onsetAngleDeg: Float,
    /** ms from onset to the tilt first reaching full frost; null if it never did before [swapped] ended the episode. */
    val onsetToFullFrostMs: Long?,
    /** ms from full frost to the swap; null when there was no swap ([swapped] false) or full frost was never reached. */
    val fullFrostToSwapMs: Long?,
    val swapped: Boolean,
) {
    /** The log line's own body (without the tag), matching [FrameStats.logLine]'s convention. */
    fun logLine(): String {
        val angle = if (onsetAngleDeg.isNaN()) "-" else "%.0f".format(Locale.ROOT, onsetAngleDeg)
        val toFull = onsetToFullFrostMs?.let { "${it}ms" } ?: "-"
        val toSwap = fullFrostToSwapMs?.let { "${it}ms" } ?: "-"
        return "onset=$onsetSource angle=$angle onset->fullFrost=$toFull fullFrost->swap=$toSwap swapped=$swapped"
    }
}

/**
 * Tracks one running (or just-finished) closing episode: [start] on whichever sample/trigger
 * first notices the close, [noteTilt] fed every subsequent left-half tilt sample (records the
 * first one to reach full frost), [end] however the episode finishes — the swap, or a
 * cancellation/re-open with no swap. A call to [start] while already [active] is ignored (the
 * first onset of a given close wins); [end] while not active returns null.
 */
class ClosingEpisodeTracker(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var onsetAtMs = -1L
    private var onsetSource: String? = null
    private var onsetAngleDeg = Float.NaN
    private var fullFrostAtMs = -1L

    val active: Boolean get() = onsetSource != null

    fun start(source: String, angleDeg: Float) {
        if (active) return
        onsetAtMs = now()
        onsetSource = source
        onsetAngleDeg = angleDeg
        fullFrostAtMs = -1L
    }

    fun noteTilt(tilt: Float) {
        if (active && fullFrostAtMs < 0L && tilt >= MorphCurve.MAX_TILT - 0.05f) fullFrostAtMs = now()
    }

    fun end(swapped: Boolean): ClosingEpisodeStats? {
        val source = onsetSource ?: return null
        val nowMs = now()
        val toFull = if (fullFrostAtMs >= 0L) fullFrostAtMs - onsetAtMs else null
        val toSwap = if (swapped && fullFrostAtMs >= 0L) nowMs - fullFrostAtMs else null
        val stats = ClosingEpisodeStats(source, onsetAngleDeg, toFull, toSwap, swapped)
        onsetSource = null
        onsetAtMs = -1L
        fullFrostAtMs = -1L
        onsetAngleDeg = Float.NaN
        return stats
    }
}
