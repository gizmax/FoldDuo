package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B48 "Pant jako ovladač": [HingeSqueezeDetector] against the four cases IDEAS.md/STATUS.md call
 * out — a real squeeze, a squeeze that turns into a real close, ordinary handling jitter that
 * never qualifies, and the debounce/release edges.
 */
class HingeSqueezeDetectorTest {
    private val flat = 180f
    private val mid = 90f

    /**
     * Feeds `count` gyro samples 20 ms apart starting at [t0], returning the last signal seen and
     * the updated clock. Primes `lastSampleMs` at [t0] with a calm no-op sample first, so the very
     * first real sample below gets a normal 20 ms dt instead of the detector's own "first sample
     * ever has dt=0" edge case (real hardware never starts this timeline from nothing — the gyro
     * has been sampling at rest all along).
     */
    private fun feedGyro(d: HingeSqueezeDetector, t0: Long, count: Int, rateRadS: Float, closingMotionFired: Boolean = false): Pair<Long, SqueezeSignal?> {
        d.onGyro(t0, true, flat, 0f, false)
        var t = t0
        var last: SqueezeSignal? = null
        repeat(count) { t += 20L; last = d.onGyro(t, true, flat, rateRadS, closingMotionFired) }
        return t to last
    }

    @Test fun `a light closing squeeze held still is recognized`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        // ~1.15 deg/sample closing (negative = closing, opening-positive convention); 6 samples clears 6 deg within 120 ms.
        val (afterMotion, _) = feedGyro(d, 0L, 6, -1.0f)
        assertEquals(HingeSqueezeDetector.Phase.MOVING, d.phase)
        assertTrue("depth should already read >= the 6 deg motion threshold", d.depthDeg >= 6f)
        // Motion stops: calm samples for >= SETTLE_MS (250 ms) recognize the squeeze.
        val (_, signal) = feedGyro(d, afterMotion, 13, 0f) // 13 * 20ms = 260ms >= 250ms
        assertTrue(signal is SqueezeSignal.Recognized)
        assertEquals(HingeSqueezeDetector.Phase.HELD, d.phase)
        val depth = (signal as SqueezeSignal.Recognized).depthDeg
        assertTrue("recognized depth should be the accumulated closing angle", depth in 5f..10f)
        assertTrue("holdMs should be at least the settle dwell", signal.holdMs >= 250L)
    }

    @Test fun `depth follows the hand while held, drag-like`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        val (afterMotion, _) = feedGyro(d, 0L, 6, -1.0f)
        val (afterSettle, recognized) = feedGyro(d, afterMotion, 13, 0f)
        check(recognized is SqueezeSignal.Recognized)
        val heldDepth = d.depthDeg
        // Squeeze a little further while held: depth should grow, not stay pinned to the recognized value.
        val (_, midSignal) = feedGyro(d, afterSettle, 3, -1.0f)
        assertNull(midSignal)
        assertTrue(d.depthDeg > heldDepth)
        assertEquals(HingeSqueezeDetector.Phase.HELD, d.phase)
    }

    @Test fun `depth is capped at MAX_DEPTH_DEG`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        val (afterMotion, _) = feedGyro(d, 0L, 6, -3.0f)
        feedGyro(d, afterMotion, 13, 0f)
        // Keep closing hard for a long time; the live depth (held or not) must never exceed the cap.
        feedGyro(d, afterMotion + 300L, 500, -3.0f)
        assertTrue(d.depthDeg <= HingeSqueezeDetector.MAX_DEPTH_DEG)
    }

    @Test fun `re-flattening after a hold releases the squeeze`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        val (afterMotion, _) = feedGyro(d, 0L, 6, -1.0f)
        val (afterSettle, recognized) = feedGyro(d, afterMotion, 13, 0f)
        check(recognized is SqueezeSignal.Recognized)
        // Opening rotation (positive rate) subtracts from the integral until it drops under the
        // release threshold; find the first sample that reports Released.
        var t = afterSettle
        var signal: SqueezeSignal? = null
        var i = 0
        while (signal == null && i < 40) { t += 20L; signal = d.onGyro(t, true, flat, 1.0f, false); i++ }
        assertTrue("expected a Released signal as the squeeze re-flattens", signal is SqueezeSignal.Released)
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
        assertEquals(0f, d.depthDeg, 0f)
    }

    @Test fun `a real close arriving mid-squeeze cancels silently`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        feedGyro(d, 0L, 6, -1.0f)
        assertEquals(HingeSqueezeDetector.Phase.MOVING, d.phase)
        val signal = d.onHingeStep(true, mid) // the hinge step reached 90: a real close
        assertNull("a real close must cancel without a signal", signal)
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
        assertEquals(0f, d.depthDeg, 0f)
    }

    @Test fun `a real close arriving while already held also cancels silently, not Released`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        val (afterMotion, _) = feedGyro(d, 0L, 6, -1.0f)
        val (_, recognized) = feedGyro(d, afterMotion, 13, 0f)
        check(recognized is SqueezeSignal.Recognized)
        val signal = d.onHingeStep(true, mid)
        assertNull("a real close overriding a held squeeze is still silent, never Released", signal)
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
    }

    @Test fun `the coarse closing-motion detector firing qualifies instantly`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        // A single sample with closingMotionFired=true, even though this detector's own integral
        // has barely started, must already count as a qualifying motion (the task's "OR").
        val signal = d.onGyro(20L, true, flat, -5.5f, true)
        assertNull(signal)
        assertEquals(HingeSqueezeDetector.Phase.MOVING, d.phase)
        assertTrue(d.depthDeg >= 6f)
    }

    @Test fun `ordinary handling jitter that never reaches the motion threshold is ignored`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        // 5 samples at a rate that accumulates well under 6 deg, then goes still.
        var t = 0L
        repeat(5) { t += 20L; d.onGyro(t, true, flat, -0.5f, false) }
        assertTrue("candidate should stay well under the motion threshold", d.depthDeg < 6f)
        repeat(5) { t += 20L; val s = d.onGyro(t, true, flat, 0f, false); assertNull(s) }
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
        assertEquals(0f, d.depthDeg, 0f)
    }

    @Test fun `a candidate that arrives past the motion window is dropped and a later one can still succeed`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        // First non-calm sample only starts the candidate (its own dt is 0, the very first sample
        // fed to a fresh detector); a second one 500ms later would cross the 6 deg threshold, but
        // arrives past MOTION_WINDOW_MS (400ms) since the first sample, so it is dropped instead
        // of qualifying as MOVING.
        d.onGyro(20L, true, flat, -0.5f, false)
        d.onGyro(520L, true, flat, -0.5f, false)
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
        assertEquals(0f, d.depthDeg, 0f)
        // A real, promptly-completed squeeze right after must still be recognized normally.
        val (afterMotion, _) = feedGyro(d, 520L, 6, -1.0f)
        val (_, recognized) = feedGyro(d, afterMotion, 13, 0f)
        assertTrue(recognized is SqueezeSignal.Recognized)
    }

    @Test fun `ignored entirely while the panel is not Inner`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        var t = 0L
        repeat(20) {
            t += 20L
            val s = d.onGyro(t, false, flat, -3.0f, false)
            assertNull(s)
        }
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
        assertEquals(0f, d.depthDeg, 0f)
    }

    @Test fun `debounce suppresses a second squeeze within 1_5s of the last`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        val (afterMotion1, _) = feedGyro(d, 0L, 6, -1.0f)
        val (afterSettle1, recognized1) = feedGyro(d, afterMotion1, 13, 0f)
        check(recognized1 is SqueezeSignal.Recognized)
        // Release, then immediately try again well within the 1.5s debounce window.
        var t = afterSettle1
        repeat(20) { t += 20L; d.onGyro(t, true, flat, 1.0f, false) } // opens back past the release threshold
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
        val (afterMotion2, _) = feedGyro(d, t, 6, -1.0f)
        val (_, recognized2) = feedGyro(d, afterMotion2, 13, 0f)
        assertNull("a second squeeze inside the debounce window must not fire", recognized2)
        assertEquals(HingeSqueezeDetector.Phase.IDLE, d.phase)
    }

    @Test fun `a squeeze after the debounce window elapses succeeds`() {
        val d = HingeSqueezeDetector(motionAngleDeg = 6f, settleMs = 250L) // the original tuning these fixtures were written against
        val (afterMotion1, _) = feedGyro(d, 0L, 6, -1.0f)
        val (afterSettle1, recognized1) = feedGyro(d, afterMotion1, 13, 0f)
        check(recognized1 is SqueezeSignal.Recognized)
        var t = afterSettle1
        repeat(20) { t += 20L; d.onGyro(t, true, flat, 1.0f, false) }
        t += HingeSqueezeDetector.DEBOUNCE_MS + 50L
        val (afterMotion2, _) = feedGyro(d, t, 6, -1.0f)
        val (_, recognized2) = feedGyro(d, afterMotion2, 13, 0f)
        assertTrue(recognized2 is SqueezeSignal.Recognized)
    }
}
