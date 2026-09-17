package cz.pflanzer.foldduo

/**
 * Pure geometry/timing math for the DEBUG_OVERLAY spike (IDEAS.md B13): where the overlay window
 * sits (the whole display, or its left half) and how the blur radius ramps in, holds, and ramps
 * back out over the requested duration. Kept free of Android framework types so it runs as a
 * plain JVM unit test; [OverlayDebug] is the framework-facing half.
 */
internal object OverlayPlan {
    data class Bounds(val leftPx: Int, val topPx: Int, val widthPx: Int, val heightPx: Int)

    /** Full display, or just its left half (`--ez left true`). Always anchored at the top-left. */
    fun bounds(displayWidthPx: Int, displayHeightPx: Int, leftHalf: Boolean): Bounds =
        if (leftHalf) Bounds(0, 0, displayWidthPx / 2, displayHeightPx)
        else Bounds(0, 0, displayWidthPx, displayHeightPx)

    /**
     * The ramp is 400 ms in and 400 ms out, but never more than half of [totalMs] each way, so a
     * very short debug duration still visibly ramps both ways instead of snapping.
     */
    fun rampMs(totalMs: Long, requestedRampMs: Long = DEFAULT_RAMP_MS): Long =
        requestedRampMs.coerceAtMost(totalMs / 2).coerceAtLeast(0L)

    /**
     * 0 -> [maxRadius] over the first [rampMs], held at [maxRadius], then back to 0 over the last
     * [rampMs] before [totalMs]. Clamped for any out-of-range [elapsedMs].
     */
    fun blurRadiusAt(
        elapsedMs: Long,
        totalMs: Long,
        maxRadius: Int,
        rampMs: Long = rampMs(totalMs),
    ): Int {
        if (totalMs <= 0L || maxRadius <= 0) return 0
        val t = elapsedMs.coerceIn(0L, totalMs)
        if (rampMs <= 0L) return maxRadius
        val outStart = totalMs - rampMs
        return when {
            t < rampMs -> ((t.toFloat() / rampMs) * maxRadius).toInt().coerceIn(0, maxRadius)
            t >= outStart -> (((totalMs - t).toFloat() / rampMs) * maxRadius).toInt().coerceIn(0, maxRadius)
            else -> maxRadius
        }
    }

    const val DEFAULT_RAMP_MS = 400L
}
