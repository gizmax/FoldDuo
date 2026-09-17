package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [HingeTrajectoryEstimator], [CriticallyDampedFollower] and the duration store, isolated from the fixture (see [HingeTrajectoryReplayTest] for the fixture/synthetic replays). */
class HingeTrajectoryEstimatorTest {

    @Test fun opensAndClosesThroughTheAnchorBands() {
        val traj = HingeTrajectoryEstimator()
        assertEquals(HingeTrajectoryEstimator.Phase.AT_CLOSED, traj.phase)
        var t = 0L
        traj.onStep(t, 0f) // still closed, no-op
        assertEquals(HingeTrajectoryEstimator.Phase.AT_CLOSED, traj.phase)
        t += 100_000_000L
        traj.onStep(t, 90f)
        assertEquals(HingeTrajectoryEstimator.Phase.OPENING, traj.phase)
        // feed some ticks so the follower has moved off the closed anchor
        repeat(20) { t += 10_000_000L; traj.onGyroHingeRate(t, 0f) }
        assertTrue("mid-open angle ${traj.angleDeg}", traj.angleDeg in 10f..170f)
        t += 900_000_000L
        traj.onStep(t, 180f)
        assertEquals(HingeTrajectoryEstimator.Phase.AT_FLAT, traj.phase)
        repeat(10) { t += 10_000_000L; traj.onGyroHingeRate(t, 0f) }
        assertEquals(HingeTrajectoryEstimator.OPEN_STEP180_ANGLE, traj.angleDeg, 1f)

        t += 500_000_000L
        traj.onStep(t, 90f)
        assertEquals(HingeTrajectoryEstimator.Phase.CLOSING, traj.phase)
        t += 700_000_000L
        traj.onStep(t, 0f)
        assertEquals(HingeTrajectoryEstimator.Phase.AT_CLOSED, traj.phase)
        repeat(10) { t += 10_000_000L; traj.onGyroHingeRate(t, 0f) }
        assertEquals(HingeTrajectoryEstimator.CLOSE_STEP0_ANGLE, traj.angleDeg, 1f)
    }

    @Test fun monotonicityHoldsWhenGyroTrustedDespiteANoisyBackwardsSample() {
        val traj = HingeTrajectoryEstimator(gyroTrustThresholdRadS = 0.1f, gravitySteadySigma = 100f)
        var t = 0L
        // A rest with clearly changing gravity so "gravitySteady()" is false from the first gravity sample.
        traj.onStep(t, 90f)
        var lastAngle = Float.NEGATIVE_INFINITY
        var sawBackwardsInput = false
        for (i in 1..200) {
            t += 5_000_000L
            traj.onGravity(t, i.toFloat(), 0f, 9.8f) // steadily changing -> "not steady"
            // Mostly opening at a trusted rate, but inject one noisy backwards sample.
            val rate = if (i == 100) { sawBackwardsInput = true; -0.5f } else 1.0f
            repeat(HingeTrajectoryEstimator.TRUST_CONSECUTIVE_SAMPLES + 1) { traj.onGyroHingeRate(t, rate) }
            assertTrue("angle went backwards at i=$i: ${traj.angleDeg} < $lastAngle", traj.angleDeg >= lastAngle - 0.01f)
            lastAngle = traj.angleDeg
        }
        assertTrue(sawBackwardsInput)
    }

    @Test fun releaseClosingFromMotion_needsAccumulatedIntegral() {
        val traj = HingeTrajectoryEstimator()
        var t = 0L
        traj.onStep(t, 90f); t += 500_000_000L; traj.onStep(t, 180f) // reach AT_FLAT
        assertEquals(HingeTrajectoryEstimator.Phase.AT_FLAT, traj.phase)
        // A small closing rate for a short time: well under 5 deg accumulated.
        repeat(5) { t += 10_000_000L; traj.onGyroHingeRate(t, -0.2f) }
        assertFalse(traj.releaseClosingFromMotion(t))
        assertEquals(HingeTrajectoryEstimator.Phase.AT_FLAT, traj.phase)
        // Sustained closing rotation for long enough to clear 5 deg (accounting for the flat-rest bias EMA and decay).
        repeat(400) { t += 10_000_000L; traj.onGyroHingeRate(t, -2.0f) }
        assertTrue("integral only ${traj.flatClosingIntegralDeg()}", traj.flatClosingIntegralDeg() < -5f)
        assertTrue(traj.releaseClosingFromMotion(t))
        assertEquals(HingeTrajectoryEstimator.Phase.CLOSING, traj.phase)
    }

    @Test fun releaseClosingFromMotion_noOpFromOtherPhases() {
        val traj = HingeTrajectoryEstimator()
        assertFalse(traj.releaseClosingFromMotion(0L)) // AT_CLOSED
        traj.onStep(0L, 90f)
        assertFalse(traj.releaseClosingFromMotion(1L)) // OPENING
    }

    @Test fun durationStore_learnsMedianOfLastTen() {
        val store = InMemoryHingeTrajectoryDurationStore(defaultOpeningMs = 900L, historySize = 10)
        assertEquals(900L, store.openingMedianMs())
        for (d in listOf(500L, 600L, 700L, 800L, 900L)) store.recordOpening(d)
        assertEquals(700L, store.openingMedianMs())
        // Push past the history size: only the last 10 count.
        for (d in 1..10) store.recordOpening(1000L + d)
        assertEquals(1006L, store.openingMedianMs())
    }

    @Test fun criticallyDampedFollower_convergesWithoutOvershoot() {
        val f = CriticallyDampedFollower(0f)
        var maxSeen = 0f
        for (i in 1..200) {
            val v = f.update(5L, 100f, 15f)
            maxSeen = maxOf(maxSeen, v)
        }
        assertTrue("overshot: $maxSeen", maxSeen <= 100.01f)
        assertEquals(100f, f.value, 0.05f)
    }

    @Test fun criticallyDampedFollower_snapIsInstant() {
        val f = CriticallyDampedFollower(0f)
        f.update(5L, 50f, 15f)
        f.snap(42f)
        assertEquals(42f, f.value, 0f)
    }
}
