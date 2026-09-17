package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Pacing morphu" (STATUS.md, 17. 9. 2026): [TiltPacing] in isolation — the release floor's own
 * timeline, its re-arm when a paused hand catches back down to it, the rise slew, B18-scaled
 * durations, and the NaN-angle fallback ([cz.pflanzer.foldduo.systemfrost.SystemFrostPlan] has no
 * smoother in between, so its raw angle sample genuinely can be NaN).
 */
class TiltPacingTest {

    @Test fun `floor holds at fromTilt through the delay then eases to 0 along clearEasing`() {
        assertEquals(0f, TiltPacing().floorAt(0L), 0f) // never armed
        assertEquals(0f, TiltPacing().floorAt(50_000L), 0f)

        val pacing = TiltPacing()
        pacing.armRelease(nowMs = 1_000L, delayMs = 900L, durationMs = 750, fromTilt = 45f)
        // Before the arm: 0 (nothing armed yet at an earlier time is meaningless here, but the
        // delay itself holds full tilt).
        assertEquals(45f, pacing.floorAt(1_000L), 0f)
        assertEquals(45f, pacing.floorAt(1_899L), 0f)
        assertEquals(45f, pacing.floorAt(1_900L), 0f) // delay just elapsed, t = 0
        // Matches MorphCurve.clearEasing directly at an arbitrary point in the ease.
        val atMs = 1_900L + 187L
        val expected = 45f * (1f - MorphCurve.clearEasing.transform(187f / 750f))
        assertEquals(expected, pacing.floorAt(atMs), 0.01f)
        // Monotonically non-increasing all the way through the ease.
        var prev = 45f
        var ms = 1_900L
        while (ms <= 2_650L) {
            val v = pacing.floorAt(ms)
            assertTrue("floor at $ms was $v > prev $prev", v <= prev + 1e-4f)
            prev = v
            ms += 10L
        }
        // Done at (and past) delay + duration.
        assertEquals(0f, pacing.floorAt(1_900L + 750L), 0f)
        assertEquals(0f, pacing.floorAt(999_999L), 0f)
    }

    @Test fun `pace is the larger of the rise-limited angle and the floor`() {
        val pacing = TiltPacing()
        pacing.armRelease(nowMs = 0L, delayMs = 100L, durationMs = 200, fromTilt = 40f)
        // During the delay the floor alone already exceeds a much lower angle reading.
        assertEquals(40f, pacing.pace(nowMs = 50L, prevDisplayed = 40f, angleTiltDeg = 0f, dtMs = 20L, fullRiseMs = 500), 0f)
        // A reading above the current floor wins outright (angle wins) — modulo the rise slew,
        // which still caps how fast it may climb above where the floor already has it.
        val floorAt150 = pacing.floorAt(150L)
        val aboveFloor = floorAt150 + 5f
        val paced = pacing.pace(nowMs = 150L, prevDisplayed = floorAt150, angleTiltDeg = aboveFloor, dtMs = 10L, fullRiseMs = 500)
        assertTrue("expected the angle to pull the displayed tilt above the floor, got $paced (floor was $floorAt150)", paced > floorAt150)
        assertTrue("expected the rise slew to still cap it, got $paced (target was $aboveFloor)", paced <= aboveFloor)
        // With enough time to fully rise, it reaches the target exactly.
        val caughtUp = pacing.pace(nowMs = 151_000L, prevDisplayed = paced, angleTiltDeg = aboveFloor, dtMs = 1_000L, fullRiseMs = 500)
        assertEquals(aboveFloor, caughtUp, 0.01f)
    }

    @Test fun `a hand holding more frost than the floor wins, and dropping below it re-arms a fresh full release`() {
        val pacing = TiltPacing()
        val fullRiseMs = 500
        pacing.armRelease(nowMs = 0L, delayMs = 0L, durationMs = 750, fromTilt = 45f)
        var now = 0L
        var displayed = 45f
        val heldAngle = 20f
        // The hand pauses holding 20 (angle mode: "still frosted") well past the point the
        // original floor schedule would have released past it — the angle ends up the larger of
        // the two and wins.
        repeat(60) { // 60 * 20 ms = 1200 ms, past the 750 ms release
            now += 20L
            displayed = pacing.pace(now, displayed, angleTiltDeg = heldAngle, dtMs = 20L, fullRiseMs = fullRiseMs)
        }
        assertEquals(heldAngle, displayed, 0.05f)
        assertEquals(0f, pacing.floorAt(now), 0f) // the original floor already fully released

        // The hand finally drops below what is on screen: the floor re-arms from here so the
        // final clear still takes a full 750 ms from wherever it starts, instead of finishing at
        // once (the original schedule already ran its course).
        now += 20L
        val beforeDrop = displayed
        displayed = pacing.pace(now, displayed, angleTiltDeg = 0f, dtMs = 20L, fullRiseMs = fullRiseMs)
        assertEquals(beforeDrop, displayed, 0.05f) // no pop at the instant of the re-arm
        val rearmedAt = now
        now += 700L
        val midway = pacing.pace(now, pacing.floorAt(now), angleTiltDeg = 0f, dtMs = 700L, fullRiseMs = fullRiseMs)
        assertTrue("should not have finished 700 ms into a fresh 750 ms release, was $midway", midway > 0f)
        now = rearmedAt + 750L + 10L
        assertEquals(0f, pacing.floorAt(now), 0f)
    }

    @Test fun `riseLimited caps a frost-up but never a clear`() {
        assertEquals(0f, TiltPacing.riseLimited(0f, 0f, 20L, 500), 0f)
        // MAX_TILT (45) over a 500 ms full rise: 20 ms allows 45 * 20 / 500 = 1.8 deg.
        assertEquals(1.8f, TiltPacing.riseLimited(0f, 45f, 20L, 500), 0.001f)
        assertEquals(21.8f, TiltPacing.riseLimited(20f, 45f, 20L, 500), 0.001f)
        // A small enough rise is not clamped.
        assertEquals(21f, TiltPacing.riseLimited(20f, 21f, 20L, 500), 0.001f)
        // A fall of any size is immediate, never limited.
        assertEquals(0f, TiltPacing.riseLimited(45f, 0f, 20L, 500), 0f)
        assertEquals(10f, TiltPacing.riseLimited(45f, 10f, 1L, 500), 0f)
        // NaN target (no usable angle) holds at prevDisplayed.
        assertEquals(12f, TiltPacing.riseLimited(12f, Float.NaN, 20L, 500), 0f)
        // Zero dt never rises; a non-positive fullRiseMs never divides by zero (coerced to 1 ms).
        assertEquals(0f, TiltPacing.riseLimited(0f, 45f, 0L, 500), 0f)
        assertEquals(45f, TiltPacing.riseLimited(0f, 45f, 20L, 0), 0f)
    }

    @Test fun `durations passed in are used as-is, so a B18-scaled duration paces a slower floor`() {
        val normal = TiltPacing()
        normal.armRelease(0L, delayMs = 0L, durationMs = MorphCurve.DURATION_MS, fromTilt = 45f)
        val scale = 0.5f
        val scaledDuration = MorphCurve.scaledDurationMs(MorphCurve.DURATION_MS, scale)
        assertEquals(MorphCurve.DURATION_MS * 2, scaledDuration) // halved animator scale doubles the real-time duration
        val scaled = TiltPacing()
        scaled.armRelease(0L, delayMs = 0L, durationMs = scaledDuration, fromTilt = 45f)
        // Halfway through the *unscaled* duration, the scaled floor has released far less.
        val t = MorphCurve.DURATION_MS / 2L
        assertTrue(scaled.floorAt(t) > normal.floorAt(t))
        // The unscaled floor is already done by the unscaled duration; the scaled one is not.
        assertEquals(0f, normal.floorAt(MorphCurve.DURATION_MS.toLong() + 1), 0f)
        assertTrue(scaled.floorAt(MorphCurve.DURATION_MS.toLong() + 1) > 0f)
        assertEquals(0f, scaled.floorAt(scaledDuration.toLong() + 1), 0f)
    }

    @Test fun `a NaN angle falls back to the floor alone, ignoring the hand entirely`() {
        val pacing = TiltPacing()
        // Never armed: floor is 0, so a NaN angle paces to 0 regardless of prevDisplayed.
        assertEquals(0f, pacing.pace(100L, prevDisplayed = 30f, angleTiltDeg = Float.NaN, dtMs = 20L, fullRiseMs = 500), 0f)
        // Armed: the floor's own schedule, unaffected by prevDisplayed or the rise slew params.
        pacing.armRelease(nowMs = 0L, delayMs = 300L, durationMs = 750, fromTilt = 45f)
        assertEquals(45f, pacing.pace(200L, prevDisplayed = 0f, angleTiltDeg = Float.NaN, dtMs = 999L, fullRiseMs = 1), 0f)
        val expectedMid = pacing.floorAt(600L)
        assertEquals(expectedMid, pacing.pace(600L, prevDisplayed = 999f, angleTiltDeg = Float.NaN, dtMs = 20L, fullRiseMs = 500), 0f)
    }
}
