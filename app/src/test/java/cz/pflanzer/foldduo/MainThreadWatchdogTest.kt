package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Výkon 3 "kreslení na inneru" (17. 9. noc), item 5: [topSlowestFrames] is the pure half of
 * `MainThreadWatchdog.logStall`'s new "slowest recent frames" list (app/src/debug — this test
 * compiles against the debug variant, `:app:testDebugUnitTest`, so it can see that source set).
 * The Choreographer ring buffer and the log line itself need a running Looper/Choreographer and
 * are not exercised here, same split as the rest of this app's Android-glue-vs-pure-logic files.
 */
class MainThreadWatchdogTest {

    @Test fun `empty input returns empty output`() {
        assertEquals(emptyList<Double>(), topSlowestFrames(emptyList(), 8))
    }

    @Test fun `returns the largest values, descending`() {
        val durations = listOf(5.0, 251.0, 16.7, 180.0, 8.0, 130.0, 12.0, 300.0, 1.0)
        val top3 = topSlowestFrames(durations, 3)
        assertEquals(listOf(300.0, 251.0, 180.0), top3)
    }

    @Test fun `count larger than the input returns everything, sorted`() {
        val durations = listOf(2.0, 9.0, 5.0)
        assertEquals(listOf(9.0, 5.0, 2.0), topSlowestFrames(durations, 8))
    }

    @Test fun `count of zero or negative returns empty, never throws`() {
        val durations = listOf(1.0, 2.0)
        assertTrue(topSlowestFrames(durations, 0).isEmpty())
        assertTrue(topSlowestFrames(durations, -1).isEmpty())
    }

    @Test fun `the task's own top-8 default keeps exactly 8 of a longer history`() {
        val durations = (1..20).map { it.toDouble() }
        val top8 = topSlowestFrames(durations, 8)
        assertEquals(8, top8.size)
        assertEquals(listOf(20.0, 19.0, 18.0, 17.0, 16.0, 15.0, 14.0, 13.0), top8)
    }
}
