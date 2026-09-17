package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeTiltSmootherTest {
    @Test fun easesTowardTheTargetWithTau60ms() {
        val s = HingeTiltSmoother()
        assertEquals(60f, s.tauMs, 0f)
        s.update(0L, 100f, 30f)
        assertEquals(0f, s.tilt, 0f) // the first sample has no dt
        s.update(60L, 100f, 30f)
        assertEquals(30f * (1f - kotlin.math.exp(-1f)), s.tilt, 0.05f)
        var t = 60L
        repeat(20) { t += 20L; s.update(t, 100f, 30f) }
        assertEquals(30f, s.tilt, 0.05f)
        // 100 Hz steps of 1.5 deg (the estimator's resolution): the tilt follows the moving target without overshooting it
        var prev = s.tilt; var worst = 0f
        for (i in 1..20) { t += 10L; s.update(t, 100f + i * 1.5f, 30f - i * 0.8f); worst = maxOf(worst, kotlin.math.abs(s.tilt - prev)); prev = s.tilt }
        assertTrue("step $worst", worst <= 0.8f) // never more than the target moved
    }

    @Test fun clampsTheTarget() {
        val s = HingeTiltSmoother(maxTilt = 45f)
        var t = 0L
        repeat(50) { t += 20L; s.update(t, 90f, 80f) }
        assertEquals(45f, s.tilt, 0.05f)
        repeat(50) { t += 20L; s.update(t, 179f, -5f) }
        assertEquals(0f, s.tilt, 0.05f)
    }

    @Test fun aJumpOver40DegreesHoldsThePreviousValueFor200ms() {
        val s = HingeTiltSmoother()
        var t = 0L
        repeat(30) { t += 20L; s.update(t, 120f, 28f) }
        assertEquals(28f, s.tilt, 0.05f)
        // an Earth-field glitch: 120 -> 170 in one sample, then back
        t += 20L; s.update(t, 170f, 1f)
        assertEquals(28f, s.target, 0f)
        assertEquals(1, s.glitches)
        repeat(9) { t += 20L; s.update(t, 170f, 1f) }
        assertEquals(28f, s.target, 0f) // still held at 200 ms
        assertEquals(10, s.glitches)
        // the hold is over: the next sample is taken whatever it says (a real jump survives)
        t += 20L; s.update(t, 170f, 1f)
        assertEquals(1f, s.target, 0f)
        assertEquals(170f, s.lastAngleDeg, 0f)
        // a jump under 40 deg is taken at once
        t += 20L; s.update(t, 140f, 17f)
        assertEquals(17f, s.target, 0f)
        assertEquals(10, s.glitches)
    }

    @Test fun nanEasesBackToZeroAndForgetsTheLastAngle() {
        val s = HingeTiltSmoother()
        var t = 0L
        repeat(30) { t += 20L; s.update(t, 120f, 28f) }
        t += 20L; s.update(t, Float.NaN, 99f)
        assertEquals(0f, s.target, 0f)
        assertTrue(s.lastAngleDeg.isNaN())
        repeat(30) { t += 20L; s.update(t, Float.NaN, 0f) }
        assertEquals(0f, s.tilt, 0.05f)
        // back from NaN: no glitch check against the stale angle
        t += 20L; s.update(t, 175f, 0f)
        assertEquals(0, s.glitches)
        s.snap(12f)
        assertEquals(12f, s.tilt, 0f); assertEquals(12f, s.target, 0f)
    }
}
