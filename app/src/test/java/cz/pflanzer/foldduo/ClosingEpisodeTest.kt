package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Zavírání jako Duo" (STATUS.md, 17. 9. noc), item 6: [ClosingEpisodeTracker]'s pure bookkeeping
 * and [ClosingEpisodeStats.logLine]'s format.
 */
class ClosingEpisodeTest {

    @Test fun `end without a start returns null`() {
        val tracker = ClosingEpisodeTracker(now = { 0L })
        assertNull(tracker.end(swapped = true))
    }

    @Test fun `a full episode reports onset, time to full frost, and time to swap`() {
        var now = 1_000L
        val tracker = ClosingEpisodeTracker(now = { now })
        tracker.start("integral", 170f)
        now += 120L
        tracker.noteTilt(20f) // not yet full
        now += 80L
        tracker.noteTilt(MorphCurve.MAX_TILT) // full frost at +200ms from onset
        now += 300L
        val stats = tracker.end(swapped = true) // swap at +500ms from onset, +300ms from full frost
        assertEquals(ClosingEpisodeStats("integral", 170f, 200L, 300L, true), stats)
    }

    @Test fun `a cancelled episode (no swap) reports no time-to-swap`() {
        var now = 0L
        val tracker = ClosingEpisodeTracker(now = { now })
        tracker.start("motion", 170f)
        now += 50L
        tracker.noteTilt(MorphCurve.MAX_TILT)
        now += 40L
        val stats = tracker.end(swapped = false)
        assertEquals(ClosingEpisodeStats("motion", 170f, 50L, null, false), stats)
    }

    @Test fun `an episode that never reaches full frost reports neither duration`() {
        var now = 0L
        val tracker = ClosingEpisodeTracker(now = { now })
        tracker.start("step", Float.NaN)
        now += 900L
        val stats = tracker.end(swapped = true)
        // Field-by-field, not a structural equals: Float.NaN != Float.NaN under IEEE 754.
        assertEquals("step", stats?.onsetSource)
        assertEquals(true, stats?.onsetAngleDeg?.isNaN())
        assertNull(stats?.onsetToFullFrostMs)
        assertNull(stats?.fullFrostToSwapMs)
        assertEquals(true, stats?.swapped)
    }

    @Test fun `start is a no-op while an episode is already active`() {
        var now = 0L
        val tracker = ClosingEpisodeTracker(now = { now })
        tracker.start("motion", 170f)
        now += 10L
        tracker.start("integral", 160f) // ignored: the first onset of a close wins
        val stats = tracker.end(swapped = true)
        assertEquals("motion", stats?.onsetSource)
        assertEquals(170f, stats?.onsetAngleDeg)
    }

    @Test fun `noteTilt before full frost, or after end, is a no-op`() {
        val tracker = ClosingEpisodeTracker(now = { 0L })
        tracker.noteTilt(MorphCurve.MAX_TILT) // no active episode: ignored
        assertNull(tracker.end(swapped = true))
    }

    @Test fun `logLine formats every field, dashes for missing durations and angle`() {
        val stats = ClosingEpisodeStats("integral", 170f, 200L, 300L, true)
        assertEquals("onset=integral angle=170 onset->fullFrost=200ms fullFrost->swap=300ms swapped=true", stats.logLine())
        val cancelled = ClosingEpisodeStats("step", Float.NaN, null, null, false)
        assertEquals("onset=step angle=- onset->fullFrost=- fullFrost->swap=- swapped=false", cancelled.logLine())
    }

    // ---- FrameStats' optional attachment, item 6 "add these to the FrameStats episode too" ----

    @Test fun `FrameStats logLine appends the closing episode when attached, unchanged when absent`() {
        val plain = FrameStats(frames = 10, p95Ms = 8.0, jankCount = 0)
        assertEquals("frames=10 p95=8.0ms jank=0", plain.logLine())
        val withClosing = plain.copy(closing = ClosingEpisodeStats("motion", 170f, 400L, 150L, true))
        assertEquals("frames=10 p95=8.0ms jank=0 onset=motion angle=170 onset->fullFrost=400ms fullFrost->swap=150ms swapped=true",
            withClosing.logLine())
    }
}
