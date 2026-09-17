package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B22, "120 Hz při morphu": [FrameStats.from]'s aggregation of raw per-frame durations (ms) into
 * frame count, 95th percentile, and jank count — shared by the launcher's own
 * [MorphFrameMetricsCollector] (`FoldDuoMorph`) and the overlay's `ChoreographerFrameSampler`
 * (`FoldDuoSystemFrost`).
 */
class FrameStatsTest {

    @Test fun `empty input is zero frames, zero p95, zero jank - not an error`() {
        val stats = FrameStats.from(emptyList())
        assertEquals(0, stats.frames)
        assertEquals(0.0, stats.p95Ms, 1e-9)
        assertEquals(0, stats.jankCount)
    }

    @Test fun `single frame - p95 is that frame, jank iff over threshold`() {
        assertEquals(8.0, FrameStats.from(listOf(8.0)).p95Ms, 1e-9)
        assertEquals(0, FrameStats.from(listOf(8.0)).jankCount)
        assertEquals(1, FrameStats.from(listOf(20.0)).jankCount)
    }

    @Test fun `p95 of 100 evenly spaced frames sits near the 95th value, not skewed by a handful of outliers`() {
        // 1..100 ms, one frame each: the 95th percentile (index 94 zero-based of 100 sorted values) is 95.
        val frames = (1..100).map { it.toDouble() }
        val stats = FrameStats.from(frames)
        assertEquals(100, stats.frames)
        assertEquals(95.0, stats.p95Ms, 1e-9)
    }

    @Test fun `jank count only counts frames strictly over the threshold`() {
        val frames = listOf(16.0, 16.7, 16.71, 20.0, 33.0)
        val stats = FrameStats.from(frames)
        assertEquals(3, stats.jankCount) // 16.71, 20.0 and 33.0 (16.7 itself is not "over")
    }

    @Test fun `a smooth 120 Hz episode (all ~8_3 ms) reports no jank`() {
        val frames = List(120) { 8.3 }
        val stats = FrameStats.from(frames)
        assertEquals(120, stats.frames)
        assertEquals(0, stats.jankCount)
        assertTrue(stats.p95Ms < FrameStats.JANK_THRESHOLD_MS)
    }

    @Test fun `a custom jank threshold is honoured`() {
        val frames = listOf(5.0, 10.0, 15.0)
        assertEquals(1, FrameStats.from(frames, jankThresholdMs = 12.0).jankCount)
        assertEquals(3, FrameStats.from(frames, jankThresholdMs = 1.0).jankCount)
    }

    @Test fun `log line reports frames, p95 to one decimal, and jank count`() {
        val stats = FrameStats(frames = 42, p95Ms = 12.345, jankCount = 3)
        assertEquals("frames=42 p95=12.3ms jank=3", stats.logLine())
    }
}
