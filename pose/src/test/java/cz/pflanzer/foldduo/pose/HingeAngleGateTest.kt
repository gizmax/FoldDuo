package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeAngleGateTest {
    private val ms = 1_000_000L

    @Test fun entersAtEnter_andIgnoresBriefDipsBelowIt() {
        val g = HingeAngleGate()
        assertFalse(g.update(0, 0.9f, anchored = false))
        assertTrue(g.update(10 * ms, 0.5f, anchored = true))
        // the device pattern: 0.49 / 0.51 flips every few hundred ms
        assertTrue(g.update(20 * ms, 0.49f, true))
        assertTrue(g.update(200 * ms, 0.51f, true))
        assertTrue(g.update(400 * ms, 0.45f, true))
        assertTrue(g.update(900 * ms, 0.45f, true)) // >= exit: never leaves
    }

    @Test fun leavesOnlyAfterDebouncedTimeBelowExit() {
        val g = HingeAngleGate()
        assertTrue(g.update(0, 0.8f, true))
        assertTrue(g.update(10 * ms, 0.3f, true))
        assertTrue(g.update(200 * ms, 0.3f, true))
        assertTrue(g.update(250 * ms, 0.45f, true)) // back above exit resets the timer
        assertTrue(g.update(300 * ms, 0.3f, true))
        assertTrue(g.update(500 * ms, 0.3f, true))
        assertFalse(g.update(610 * ms, 0.3f, true))
        // re-entry needs the enter level again
        assertFalse(g.update(700 * ms, 0.45f, true))
        assertTrue(g.update(800 * ms, 0.5f, true))
    }

    @Test fun losingTheAnchorDropsImmediately() {
        val g = HingeAngleGate()
        assertTrue(g.update(0, 0.8f, true))
        assertFalse(g.update(1 * ms, 0.8f, false))
    }
}
