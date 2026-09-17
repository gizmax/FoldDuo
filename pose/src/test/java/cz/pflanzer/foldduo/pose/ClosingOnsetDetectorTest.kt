package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Zavírání jako Duo" (STATUS.md, 17. 9. noc): [ClosingOnsetDetector] bridges the closing angle
 * from ~172° down while the raw hinge step still reads Flat, instead of waiting for the step to
 * actually leave Flat at ~145°. Two qualifying paths (a coarse gyro spike, or a sustained 10°
 * integral within 600 ms), a hard guard against a mere tilt of the phone in the hand, and a
 * cancel-back-to-0 once a confirmed run goes calm without the real transition ever arriving.
 */
class ClosingOnsetDetectorTest {

    private val flatStep = 180f
    private val midStep = 90f

    // ---- coarse spike qualification ----

    @Test fun `a coarse closing-motion spike activates instantly at the onset floor`() {
        val d = ClosingOnsetDetector()
        val justActive = d.onGyro(0L, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        assertTrue(justActive)
        assertTrue(d.active)
        assertEquals(ClosingOnsetDetector.Source.MOTION, d.source)
        assertEquals(180f - ClosingOnsetDetector.MOTION_ANGLE_DEG, d.angleDeg(), 0.01f)
    }

    // ---- integral qualification: 10 deg within 600 ms ----

    @Test fun `a sustained closing rotation reaching 10 deg within 600 ms activates via the integral`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        var justActive = false
        // -0.6 rad/s (~34.4 deg/s) closing for 350 ms accumulates ~12 deg, well within the window.
        repeat(18) {
            now += 20L
            val fired = d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -0.6f, closingMotionFired = false)
            if (fired) justActive = true
        }
        assertTrue("expected the integral to confirm the onset", justActive)
        assertEquals(ClosingOnsetDetector.Source.INTEGRAL, d.source)
        assertTrue("onset angle ${d.angleDeg()} should read close to 170 deg", d.angleDeg() <= 170f + 0.5f)
    }

    @Test fun `onGyro returns true only on the sample the onset first confirms`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        var trueCount = 0
        repeat(30) {
            now += 20L
            if (d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -0.6f, closingMotionFired = false)) trueCount++
        }
        assertEquals(1, trueCount)
    }

    // ---- never from a mere tilt of the phone in the hand ----

    @Test fun `calm samples throughout (the phone merely held) never activate`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        repeat(50) {
            now += 20L
            d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = 0.05f, closingMotionFired = false)
        }
        assertFalse(d.active)
        assertEquals(0f, d.depthDeg, 0f)
    }

    @Test fun `brief interrupted motion bursts (adjusting grip in the hand) never accumulate to the threshold`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        // Three separate ~140 ms bursts of closing-direction motion, each well short of
        // MOTION_ANGLE_DEG on its own, each fully calmed down before the next one starts — a
        // hand re-adjusting its grip, not one continuous close. Every burst is abandoned instead
        // of carrying its partial depth into the next.
        repeat(3) {
            repeat(7) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -0.6f, closingMotionFired = false) }
            repeat(10) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = 0f, closingMotionFired = false) }
        }
        assertFalse(d.active)
        assertEquals(ClosingOnsetDetector.Source.NONE, d.source)
    }

    @Test fun `an abandoned candidate does not stop a later, genuinely sustained close from qualifying`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        // Abandoned burst (as above)...
        repeat(7) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -0.6f, closingMotionFired = false) }
        repeat(10) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = 0f, closingMotionFired = false) }
        assertFalse(d.active)
        // ...then a real, sustained close still qualifies fresh from here.
        var justActive = false
        repeat(20) {
            now += 20L
            if (d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -0.6f, closingMotionFired = false)) justActive = true
        }
        assertTrue(justActive)
    }

    // ---- cancel back to 0 ----

    @Test fun `an active onset cancels back to 0 after 700 ms calm with the step still at Flat`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        assertTrue(d.active)
        // Calm for less than CANCEL_CALM_MS: still active.
        repeat(30) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = 0f, closingMotionFired = false) }
        assertTrue("should still be active at ${ClosingOnsetDetector.CANCEL_CALM_MS - 100}ms of calm", d.active)
        // Past CANCEL_CALM_MS of calm: cancelled back to 0.
        repeat(15) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = 0f, closingMotionFired = false) }
        assertFalse(d.active)
        assertEquals(0f, d.depthDeg, 0f)
        assertTrue(d.angleDeg().isNaN())
    }

    @Test fun `renewed motion before the cancel timeout keeps the onset alive`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        // Calm for a while, but short of the cancel timeout...
        repeat(20) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = 0f, closingMotionFired = false) }
        // ...then motion resumes: still active, never cancelled.
        now += 20L
        d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -1f, closingMotionFired = false)
        assertTrue(d.active)
    }

    // ---- the real step transition arriving hands off silently ----

    @Test fun `the hinge step actually leaving Flat resets the bridge silently`() {
        val d = ClosingOnsetDetector()
        d.onGyro(0L, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        assertTrue(d.active)
        d.onHingeStep(panelInner = true, stepDeg = midStep)
        assertFalse(d.active)
        assertEquals(0f, d.depthDeg, 0f)
    }

    @Test fun `a gyro sample once the step has left Flat also resets`() {
        val d = ClosingOnsetDetector()
        d.onGyro(0L, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        assertTrue(d.active)
        val justActive = d.onGyro(20L, panelInner = true, stepDeg = midStep, rateRadS = -1f, closingMotionFired = false)
        assertFalse(justActive)
        assertFalse(d.active)
    }

    @Test fun `not the inner panel never activates, whatever the motion`() {
        val d = ClosingOnsetDetector()
        val justActive = d.onGyro(0L, panelInner = false, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        assertFalse(justActive)
        assertFalse(d.active)
    }

    @Test fun `angleDeg is NaN whenever inactive and 180 minus depth whenever active`() {
        val d = ClosingOnsetDetector()
        assertTrue(d.angleDeg().isNaN())
        d.onGyro(0L, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        assertEquals(180f - d.depthDeg, d.angleDeg(), 0f)
    }

    @Test fun `depth never exceeds MAX_DEPTH_DEG`() {
        val d = ClosingOnsetDetector()
        var now = 0L
        d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        repeat(200) { now += 20L; d.onGyro(now, panelInner = true, stepDeg = flatStep, rateRadS = -8f, closingMotionFired = false) }
        assertTrue(d.depthDeg <= ClosingOnsetDetector.MAX_DEPTH_DEG)
    }

    @Test fun `reset clears every field back to idle`() {
        val d = ClosingOnsetDetector()
        d.onGyro(0L, panelInner = true, stepDeg = flatStep, rateRadS = -6f, closingMotionFired = true)
        d.reset()
        assertFalse(d.active)
        assertEquals(0f, d.depthDeg, 0f)
        assertEquals(ClosingOnsetDetector.Source.NONE, d.source)
        assertTrue(d.angleDeg().isNaN())
    }
}
