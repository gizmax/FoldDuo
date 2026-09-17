package cz.pflanzer.foldduo

import kotlin.math.roundToInt

/**
 * B22, "120 Hz při morphu": pure aggregation (no Android types, JVM-testable) of a morph or
 * SystemFrost episode's per-frame durations into what the one-line log wants: how many frames,
 * the 95th percentile total frame time, and how many were jank (> [JANK_THRESHOLD_MS], the classic
 * budget for a 60 Hz frame; at 120 Hz most real frames are well under it, so the same threshold
 * still flags the ones that dropped to 60 Hz or worse).
 */
data class FrameStats(
    val frames: Int,
    val p95Ms: Double,
    val jankCount: Int,
    /** "Zavírání jako Duo" item 6: the closing episode this frame-stats episode overlapped, if any — see [ClosingEpisodeStats]. Attached by the caller ([MorphFrameMetricsCollector.stop]); [from] never sets it. */
    val closing: ClosingEpisodeStats? = null,
) {
    companion object {
        const val JANK_THRESHOLD_MS = 16.7

        /** Empty input is a valid "no frames observed" result, not an error: 0 frames, 0 jank. */
        fun from(durationsMs: List<Double>, jankThresholdMs: Double = JANK_THRESHOLD_MS): FrameStats {
            if (durationsMs.isEmpty()) return FrameStats(0, 0.0, 0)
            val sorted = durationsMs.sorted()
            val index = (0.95 * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
            return FrameStats(durationsMs.size, sorted[index], durationsMs.count { it > jankThresholdMs })
        }
    }

    /** The log line's own body (without the tag), shared by [cz.pflanzer.foldduo.HighRefreshRate]'s
     * `FoldDuoMorph` and `systemfrost`'s `FoldDuoSystemFrost` sink so the two read the same way.
     * Appends [closing]'s own line when this episode overlapped a closing episode. */
    fun logLine(): String {
        val base = "frames=$frames p95=${"%.1f".format(java.util.Locale.ROOT, p95Ms)}ms jank=$jankCount"
        return closing?.let { "$base ${it.logLine()}" } ?: base
    }
}
