package cz.pflanzer.foldduo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B43 "Výkon jako feature": [PerfHistory]'s ring buffer of frame-stats episodes and its startup
 * timing — what `DEBUG_PERF` (app/src/debug/.../DebugPerf.kt, not JVM-testable itself) dumps.
 * [PerfHistory] is a process-wide singleton, so every test clears it first/after to stay isolated.
 */
class PerfHistoryTest {
    @Before fun clear() = PerfHistory.clearForTest()
    @After fun clearAfter() = PerfHistory.clearForTest()

    private fun stats(frames: Int) = FrameStats(frames = frames, p95Ms = 10.0, jankCount = 0)

    @Test fun `no episodes yet - empty list`() {
        assertTrue(PerfHistory.episodes().isEmpty())
    }

    @Test fun `a zero-frame episode is not worth keeping`() {
        PerfHistory.record("morph", stats(0), atElapsedMs = 100)
        assertTrue(PerfHistory.episodes().isEmpty())
    }

    @Test fun `recorded episodes come back oldest first`() {
        PerfHistory.record("morph", stats(10), atElapsedMs = 100)
        PerfHistory.record("systemfrost", stats(20), atElapsedMs = 200)
        val episodes = PerfHistory.episodes()
        assertEquals(listOf("morph", "systemfrost"), episodes.map { it.label })
        assertEquals(listOf(100L, 200L), episodes.map { it.atElapsedMs })
    }

    @Test fun `the ring buffer keeps only the most recent MAX_EPISODES`() {
        repeat(25) { i -> PerfHistory.record("morph", stats(i + 1), atElapsedMs = i.toLong()) }
        val episodes = PerfHistory.episodes()
        assertEquals(20, episodes.size)
        // Oldest 5 (frames 1..5, i.e. i=0..4) were evicted; the buffer starts at frames=6.
        assertEquals(6, episodes.first().stats.frames)
        assertEquals(25, episodes.last().stats.frames)
    }

    @Test fun `episodes(n) returns only the last n, still oldest first`() {
        repeat(5) { i -> PerfHistory.record("morph", stats(i + 1), atElapsedMs = i.toLong()) }
        val last2 = PerfHistory.episodes(2)
        assertEquals(listOf(4, 5), last2.map { it.stats.frames })
    }

    @Test fun `startup is null until noted`() {
        assertNull(PerfHistory.startup)
    }

    @Test fun `noteStartup then noteInteractive fill in both halves`() {
        PerfHistory.noteStartup(coldStartToFirstFrameMs = 250)
        assertEquals(250L, PerfHistory.startup?.coldStartToFirstFrameMs)
        assertNull(PerfHistory.startup?.firstFrameToInteractiveMs)
        PerfHistory.noteInteractive(firstFrameToInteractiveMs = 900)
        assertEquals(250L, PerfHistory.startup?.coldStartToFirstFrameMs)
        assertEquals(900L, PerfHistory.startup?.firstFrameToInteractiveMs)
    }

    @Test fun `noteInteractive before noteStartup still records something sensible`() {
        PerfHistory.noteInteractive(firstFrameToInteractiveMs = 900)
        assertEquals(900L, PerfHistory.startup?.firstFrameToInteractiveMs)
        assertEquals(-1L, PerfHistory.startup?.coldStartToFirstFrameMs)
    }

    @Test fun `a second noteStartup does not erase an already-noted interactive time`() {
        PerfHistory.noteStartup(coldStartToFirstFrameMs = 250)
        PerfHistory.noteInteractive(firstFrameToInteractiveMs = 900)
        PerfHistory.noteStartup(coldStartToFirstFrameMs = 260)
        assertEquals(260L, PerfHistory.startup?.coldStartToFirstFrameMs)
        assertEquals(900L, PerfHistory.startup?.firstFrameToInteractiveMs)
    }
}
