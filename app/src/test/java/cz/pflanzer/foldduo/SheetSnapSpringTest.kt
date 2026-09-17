package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B37 "Snap flat": [SheetSnapSpring]'s overshoot curve from -2° back to 0. */
class SheetSnapSpringTest {
    @Test fun `idle before the first trigger`() {
        val spring = SheetSnapSpring()
        assertFalse(spring.isRunning)
        assertEquals(0f, spring.overshootDeg, 0f)
        spring.advance(16f)
        assertEquals(0f, spring.overshootDeg, 0f)
        assertFalse(spring.isRunning)
    }

    @Test fun `trigger starts at the overshoot floor and runs`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        assertTrue(spring.isRunning)
        assertEquals(SheetSnapSpring.START_DEG, spring.overshootDeg, 0f)
    }

    @Test fun `advances from START_DEG toward zero, overshoots past it, then settles exactly at zero`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        var previous = spring.overshootDeg
        var risingCrossings = 0
        var t = 0f
        while (spring.isRunning && t < 1_000f) {
            spring.advance(4f)
            t += 4f
            val current = spring.overshootDeg
            // Started negative (past flat); an underdamped spring can ring across zero more than
            // once as it decays, but it must cross upward (the crack's overshoot) at least once.
            if (previous < 0f && current >= 0f) risingCrossings++
            previous = current
        }
        assertFalse("expected the spring to settle within 1s", spring.isRunning)
        assertEquals(0f, spring.overshootDeg, 0f)
        assertTrue("expected at least one upward zero-crossing (the crack's overshoot), got $risingCrossings",
            risingCrossings >= 1)
    }

    @Test fun `settles by SETTLE_S`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        spring.advance(SheetSnapSpring.SETTLE_S * 1000f)
        assertFalse(spring.isRunning)
        assertEquals(0f, spring.overshootDeg, 0f)
    }

    @Test fun `the overshoot past zero is small, well under the START_DEG magnitude`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        var peakPositive = 0f
        var t = 0f
        while (spring.isRunning && t < 1_000f) {
            spring.advance(2f)
            t += 2f
            if (spring.overshootDeg > peakPositive) peakPositive = spring.overshootDeg
        }
        assertTrue("expected a small positive overshoot, got $peakPositive",
            peakPositive > 0f && peakPositive < -SheetSnapSpring.START_DEG)
    }

    @Test fun `reset clears the overshoot and stops the spring immediately`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        spring.advance(10f)
        spring.reset()
        assertFalse(spring.isRunning)
        assertEquals(0f, spring.overshootDeg, 0f)
    }

    @Test fun `a negative dt is treated as zero elapsed time`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        val before = spring.overshootDeg
        spring.advance(-50f)
        assertEquals(before, spring.overshootDeg, 0f)
        assertTrue(spring.isRunning)
    }

    @Test fun `retrigger while running restarts from START_DEG`() {
        val spring = SheetSnapSpring()
        spring.trigger()
        spring.advance(50f)
        assertTrue(spring.overshootDeg != SheetSnapSpring.START_DEG)
        spring.trigger()
        assertEquals(SheetSnapSpring.START_DEG, spring.overshootDeg, 0f)
        assertTrue(spring.isRunning)
    }
}
