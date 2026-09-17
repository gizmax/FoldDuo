package cz.pflanzer.foldduo.pose

import kotlin.math.exp

/**
 * Wallpaper depth parallax (IDEAS.md B27): a small screen-space dp offset for the wallpaper layer
 * only, driven by the low-passed gravity vector. Pure Kotlin, no Android types, so it runs as a
 * JVM test; the launcher's background composable and the live wallpaper engine each feed it
 * gravity samples ([update]) and read [offsetDpX] / [offsetDpY] on every frame.
 *
 * Two time constants shape the feel: [GRAVITY_TAU_MS] low-passes the raw gravity sample (hides
 * sensor noise and hand tremor without adding lag worth noticing), and [RECENTRE_TAU_MS] slowly
 * drifts a *rest point* toward wherever that smoothed gravity has settled, so the wallpaper ends
 * up centred on however the phone is typically held rather than on "flat against the earth" —
 * pick the phone up at an angle and the parallax re-centres under you within a few seconds. Only
 * the gap between the fast-smoothed gravity and that slow rest point drives the offset: a
 * [DEAD_ZONE_MS2] dead zone around zero absorbs the rest, the remainder is scaled so
 * [SATURATION_RANGE_MS2] of excess reaches the full [maxOffsetDp], and the result is clamped.
 *
 * Sign convention: `gx`/`gy` are `PoseSnapshot.gravity`'s device-frame X/Y (`TYPE_GRAVITY`, as
 * read in [PoseRepository]). Rolling the phone so its left edge dips shows up as a falling `gx`
 * relative to the rest point; [offsetDpX] comes out *positive* there, so the wallpaper is drawn
 * shifted right — like iOS's parallax, as if the icons were a window onto a wallpaper sitting a
 * little behind them. `gy` is treated the same way for the vertical axis. (Flip the sign in
 * [shape] if a device test shows the physical mapping runs the other way.)
 */
class ParallaxModel(val maxOffsetDp: Float) {
    private var lowPassX = 0f
    private var lowPassY = 0f
    private var restX = 0f
    private var restY = 0f
    private var lastMs = Long.MIN_VALUE
    private var started = false

    /** Current offset in dp, applied as a `graphicsLayer` translation on the wallpaper only. */
    var offsetDpX = 0f
        private set
    var offsetDpY = 0f
        private set

    /** One gravity sample at [nowMs] (device-frame `gx`/`gy`, m/s^2, `PoseSnapshot.gravity`). */
    fun update(nowMs: Long, gx: Float, gy: Float) {
        if (!started) {
            started = true
            lowPassX = gx; lowPassY = gy
            restX = gx; restY = gy
        } else {
            val dtMs = (nowMs - lastMs).coerceIn(0L, MAX_DT_MS)
            val gravityAlpha = 1f - exp(-dtMs / GRAVITY_TAU_MS)
            lowPassX += (gx - lowPassX) * gravityAlpha
            lowPassY += (gy - lowPassY) * gravityAlpha
            val recentreAlpha = 1f - exp(-dtMs / RECENTRE_TAU_MS)
            restX += (lowPassX - restX) * recentreAlpha
            restY += (lowPassY - restY) * recentreAlpha
        }
        lastMs = nowMs
        offsetDpX = shape(lowPassX - restX)
        offsetDpY = shape(lowPassY - restY)
    }

    /** Dead zone, then a linear gain so [SATURATION_RANGE_MS2] of excess reaches [maxOffsetDp], then clamp. */
    private fun shape(delta: Float): Float {
        val excess = when {
            delta > DEAD_ZONE_MS2 -> delta - DEAD_ZONE_MS2
            delta < -DEAD_ZONE_MS2 -> delta + DEAD_ZONE_MS2
            else -> 0f
        }
        return (-excess * (maxOffsetDp / SATURATION_RANGE_MS2)).coerceIn(-maxOffsetDp, maxOffsetDp)
    }

    /** Back to a fresh rest point at the next [update] sample (a panel swap or the effect restarting). */
    fun reset() {
        started = false
        offsetDpX = 0f
        offsetDpY = 0f
    }

    companion object {
        /** Low-pass time constant on the raw gravity sample. */
        const val GRAVITY_TAU_MS = 250f
        /** Recentre time constant: how fast the neutral point follows the smoothed gravity. */
        const val RECENTRE_TAU_MS = 3_000f
        /** Below this many m/s^2 off the rest point, no offset at all. */
        const val DEAD_ZONE_MS2 = 0.3f
        /** m/s^2 of excess beyond the dead zone that reaches the full [maxOffsetDp]. */
        const val SATURATION_RANGE_MS2 = 3f
        /** A sample gap longer than this (backgrounded, sensor hiccup) is treated as this long, not replayed instantly. */
        private const val MAX_DT_MS = 1_000L

        /** IDEAS.md B27: +/-8 dp on the cover, the smaller of the two panels. */
        const val MAX_OFFSET_COVER_DP = 8f
        /** IDEAS.md B27: +/-12 dp on the inner panel. */
        const val MAX_OFFSET_INNER_DP = 12f

        /**
         * The wallpaper's over-scale so its edges never show through [maxOffsetDp] of travel on a
         * [widthDp]-wide surface: `1 + 2 * max / width` (drawn [maxOffsetDp] larger than needed on
         * each side). 1 (no over-scale) for a non-positive width.
         */
        fun overscale(maxOffsetDp: Float, widthDp: Float): Float =
            if (widthDp <= 0f) 1f else 1f + 2f * maxOffsetDp / widthDp
    }
}
