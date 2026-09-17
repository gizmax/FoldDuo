package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Výkon 4 "recompositions při swipe" (17. 9. noc): [topRecompositionCounts]/[formatRecompositionLog]
 * are the pure half of `RecompositionCounter.endEpisode` (app/src/debug — this test compiles
 * against the debug variant, `:app:testDebugUnitTest`, so it can see that source set, same split
 * as `MainThreadWatchdogTest`'s `topSlowestFrames`). The counters themselves (a
 * `ConcurrentHashMap`, `SideEffect`) need a running composition and are not exercised here.
 */
class RecompositionCounterTest {

    @Test fun `empty input returns empty output`() {
        assertEquals(emptyList<Pair<String, Int>>(), topRecompositionCounts(emptyMap(), 10))
        assertEquals("", formatRecompositionLog(emptyMap(), 10))
    }

    @Test fun `returns the largest counts, descending`() {
        val counts = mapOf("AppTile" to 340, "SharedHomeGrid" to 2, "ExpandedWorkspace" to 58, "MovableWidget" to 12)
        assertEquals(
            listOf("AppTile" to 340, "ExpandedWorkspace" to 58, "MovableWidget" to 12, "SharedHomeGrid" to 2),
            topRecompositionCounts(counts, 10),
        )
    }

    @Test fun `limit smaller than the input keeps only the top entries`() {
        val counts = mapOf("A" to 1, "B" to 9, "C" to 5, "D" to 7)
        assertEquals(listOf("B" to 9, "D" to 7), topRecompositionCounts(counts, 2))
    }

    @Test fun `limit larger than the input returns everything, sorted`() {
        val counts = mapOf("A" to 3, "B" to 1)
        assertEquals(listOf("A" to 3, "B" to 1), topRecompositionCounts(counts, 10))
    }

    @Test fun `zero or negative limit returns nothing`() {
        val counts = mapOf("A" to 3, "B" to 1)
        assertEquals(emptyList<Pair<String, Int>>(), topRecompositionCounts(counts, 0))
        assertEquals(emptyList<Pair<String, Int>>(), topRecompositionCounts(counts, -5))
    }

    @Test fun `format renders tag=count pairs in descending order`() {
        val counts = mapOf("AppTile" to 340, "SharedHomeGrid" to 2, "ExpandedWorkspace" to 58)
        assertEquals("AppTile=340, ExpandedWorkspace=58, SharedHomeGrid=2", formatRecompositionLog(counts, 10))
    }

    @Test fun `format respects the limit`() {
        val counts = mapOf("A" to 1, "B" to 9, "C" to 5)
        assertEquals("B=9, C=5", formatRecompositionLog(counts, 2))
    }
}
