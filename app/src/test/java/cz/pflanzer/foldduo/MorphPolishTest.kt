package cz.pflanzer.foldduo

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseSnapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B16-B18 (STATUS.md "Morph polish B16-B18", 15. 9. noc): the settle-after-unfold spring and
 * specular sweep, the reverse cover settle and closing seam darken, and the animator-duration-
 * scale correction. Pure logic gets full coverage here; the shared plumbing these build on
 * (playEnter, playCloseFrost, noteSnapshot) is already exercised in UnfoldMorphTest.kt and
 * AngleMorphTest.kt.
 */
class MorphPolishTest {

    // ---- B16: settle stagger ----

    @Test fun `settle delay is 40 ms per item, in reading order, never negative`() {
        assertEquals(40L, MorphCurve.SETTLE_STAGGER_MS)
        assertEquals(0L, MorphCurve.settleDelayMs(0))
        assertEquals(40L, MorphCurve.settleDelayMs(1))
        assertEquals(400L, MorphCurve.settleDelayMs(10))
        assertEquals(0L, MorphCurve.settleDelayMs(-3)) // clamped, never negative
    }

    @Test fun `settle start scale and alpha are below rest, spring is only slightly underdamped`() {
        assertTrue(MorphCurve.SETTLE_START_SCALE in 0f..1f)
        assertTrue(MorphCurve.SETTLE_START_ALPHA in 0f..1f)
        assertTrue(MorphCurve.SETTLE_START_SCALE < 1f)
        assertTrue(MorphCurve.SETTLE_START_ALPHA < 1f)
        assertTrue(MorphCurve.SETTLE_DAMPING_RATIO in 0f..1f)
        assertTrue(MorphCurve.SETTLE_STIFFNESS > 0f)
    }

    // ---- B16: settle-once-per-unfold state ----

    @Test fun `settle once gate fires exactly once`() {
        val gate = SettleOnceGate()
        assertFalse(gate.hasFired)
        assertTrue(gate.fire())
        assertTrue(gate.hasFired)
        assertFalse(gate.fire())
        assertFalse(gate.fire())
        assertTrue(gate.hasFired)
    }

    @Test fun `settle angle edge fires only on the inner panel, genuinely near flat`() {
        // Was frosted (tilt above 0), now clear (tilt 0), the angle itself at or past Flat: fires.
        assertTrue(MorphCurve.settleAngleEdge(Panel.Inner, 5f, 0f, MorphCurve.ANGLE_FLAT))
        assertTrue(MorphCurve.settleAngleEdge(Panel.Inner, 5f, 0f, MorphCurve.ANGLE_FLAT - MorphCurve.SETTLE_FLAT_MARGIN_DEG))
        // Never frosted to begin with (previousTilt already 0): no edge.
        assertFalse(MorphCurve.settleAngleEdge(Panel.Inner, 0f, 0f, MorphCurve.ANGLE_FLAT))
        // Still frosted (tilt above 0): no edge yet.
        assertFalse(MorphCurve.settleAngleEdge(Panel.Inner, 5f, 1f, MorphCurve.ANGLE_FLAT))
        // Angle mode simply stopped driving (NaN): not a real Flat.
        assertFalse(MorphCurve.settleAngleEdge(Panel.Inner, 5f, 0f, Float.NaN))
        // Short of the margin around Flat: not genuinely flat yet.
        assertFalse(MorphCurve.settleAngleEdge(Panel.Inner, 5f, 0f, MorphCurve.ANGLE_FLAT - MorphCurve.SETTLE_FLAT_MARGIN_DEG - 1f))
        // Today lives on the inner panel only; the cover never fires this.
        assertFalse(MorphCurve.settleAngleEdge(Panel.Cover, 5f, 0f, MorphCurve.ANGLE_FLAT))
    }

    // ---- B16: specular sweep ----

    @Test fun `sweep progress runs from tilt 20 down to 0, clamped and monotone`() {
        assertEquals(0f, MorphCurve.sweepProgress(20f), 0f)
        assertEquals(0f, MorphCurve.sweepProgress(45f), 0f) // not due yet
        assertEquals(0.5f, MorphCurve.sweepProgress(10f), 1e-6f)
        assertEquals(1f, MorphCurve.sweepProgress(0f), 0f)
        assertEquals(1f, MorphCurve.sweepProgress(-5f), 0f) // clamped past the end
        var previous = -1f
        for (tiltInt in 45 downTo 0) {
            val p = MorphCurve.sweepProgress(tiltInt.toFloat())
            assertTrue("progress at tilt=$tiltInt must not run back", p >= previous)
            previous = p
        }
        assertEquals(1f, previous, 0f)
    }

    @Test fun `sweep band travels from the hinge seam to past the outward edge`() {
        val half = MorphCurve.SWEEP_WIDTH_FRACTION / 2f
        assertEquals(1f + half, MorphCurve.sweepCenterFraction(0f), 1e-6f)
        assertEquals(-half, MorphCurve.sweepCenterFraction(1f), 1e-6f)
        var previous = Float.MAX_VALUE
        for (i in 0..20) {
            val c = MorphCurve.sweepCenterFraction(i / 20f)
            assertTrue("center at $i must move from the seam outward", c <= previous)
            previous = c
        }
        assertEquals(MorphCurve.sweepCenterFraction(0f), MorphCurve.sweepCenterFraction(-1f), 0f)
        assertEquals(MorphCurve.sweepCenterFraction(1f), MorphCurve.sweepCenterFraction(2f), 0f)
    }

    @Test fun `sweep intensity is a symmetric triangular feather, one band-width wide`() {
        assertEquals(1f, MorphCurve.sweepIntensity(0.5f, 0.5f), 0f)
        val half = MorphCurve.SWEEP_WIDTH_FRACTION / 2f
        assertEquals(0f, MorphCurve.sweepIntensity(0.5f + half, 0.5f), 1e-6f)
        assertEquals(0f, MorphCurve.sweepIntensity(0.5f - half, 0.5f), 1e-6f)
        assertEquals(0.5f, MorphCurve.sweepIntensity(0.5f + half / 2f, 0.5f), 1e-6f)
        assertEquals(MorphCurve.sweepIntensity(0.5f + half / 3f, 0.5f), MorphCurve.sweepIntensity(0.5f - half / 3f, 0.5f), 1e-6f)
        assertEquals(0f, MorphCurve.sweepIntensity(0.5f + half * 3f, 0.5f), 0f) // clamped, never negative
    }

    // ---- B17: reverse cover settle ----

    @Test fun `cover settle starts tilted at 40 deg of hinge and clears to sharp along clearEasing`() {
        val config = FoldConfig()
        assertEquals(40f, MorphCurve.COVER_SETTLE_START_ANGLE, 0f)
        val startTilt = MorphCurve.coverSettleStartTilt(config)
        assertEquals(FoldShader.coverTiltForHinge(40f, config), startTilt, 0f)
        assertTrue(startTilt > 0f)
        assertEquals(startTilt, MorphCurve.coverSettleTiltAt(0f, startTilt), 0f)
        assertTrue(MorphCurve.coverSettleTiltAt(1f, startTilt) < FoldShader.FLAT_EPSILON)
        var previous = Float.MAX_VALUE
        for (i in 0..100) {
            val t = MorphCurve.coverSettleTiltAt(i / 100f, startTilt)
            assertTrue("tilt at $i% must not run back up", t <= previous + 1e-4f)
            previous = t
        }
        assertEquals(MorphCurve.coverSettleTiltAt(0f, startTilt), MorphCurve.coverSettleTiltAt(-1f, startTilt), 0f)
        assertEquals(MorphCurve.coverSettleTiltAt(1f, startTilt), MorphCurve.coverSettleTiltAt(2f, startTilt), 0f)
    }

    @Test fun `cover settle tilt folds into coverTilt alongside the frost and angle mode`() {
        assertEquals(20f, MorphCurve.coverTilt(0f, 0f, 20f), 0f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.coverTilt(0f, 0f, 90f), 0f) // clamped
        assertEquals(30f, MorphCurve.coverTilt(0f, 30f, 10f), 0f) // whichever is larger wins
        // Backward compatible: omitting the settle tilt behaves exactly as before it existed.
        assertEquals(MorphCurve.coverTilt(0.5f, 12f), MorphCurve.coverTilt(0.5f, 12f, 0f), 0f)
    }

    // ---- B17: closing seam darken ----

    @Test fun `closing seam darken is linear in tilt, capped at 12 percent`() {
        assertEquals(0.12f, MorphCurve.CLOSING_DARKEN_MAX_ALPHA, 0f)
        assertEquals(0f, MorphCurve.closingDarkenAlpha(0f), 0f)
        assertEquals(0.06f, MorphCurve.closingDarkenAlpha(FoldShader.MAX_TILT / 2f), 1e-6f)
        assertEquals(0.12f, MorphCurve.closingDarkenAlpha(FoldShader.MAX_TILT), 0f)
        assertEquals(0.12f, MorphCurve.closingDarkenAlpha(FoldShader.MAX_TILT * 2f), 0f) // clamped
        assertEquals(0f, MorphCurve.closingDarkenAlpha(-5f), 0f) // clamped
    }

    // ---- B18: the morph's own clock ----

    @Test fun `scaled duration inverts the animator scale, treating 0 as 1x`() {
        assertEquals(750, MorphCurve.scaledDurationMs(750, 1f))
        assertEquals(1500, MorphCurve.scaledDurationMs(750, 0.5f))
        assertEquals(375, MorphCurve.scaledDurationMs(750, 2f))
        assertEquals(750, MorphCurve.scaledDurationMs(750, 0f)) // animations off: treated as 1x
        assertEquals(750, MorphCurve.scaledDurationMs(750, -1f)) // never negative
        assertEquals(750, MorphCurve.scaledDurationMs(750, Float.NaN)) // never NaN
        assertEquals(1, MorphCurve.scaledDurationMs(1, 100f)) // never under 1 ms
        assertEquals(700, MorphCurve.scaledDurationMs(350, 0.5f))
        assertEquals(600, MorphCurve.scaledDurationMs(300, 0.5f))
    }

    // ---- LeadingPane.kt: reading order for the settle stagger ----

    @Test fun `leading reading rank orders by row then column`() {
        assertEquals(0, leadingReadingRank(0, 0))
        assertEquals(GRID_COLUMNS - 1, leadingReadingRank(0, GRID_COLUMNS - 1))
        assertEquals(GRID_COLUMNS, leadingReadingRank(1, 0))
        assertTrue(leadingReadingRank(0, GRID_COLUMNS - 1) < leadingReadingRank(1, 0))
        assertTrue(leadingReadingRank(1, 2) < leadingReadingRank(2, 0))
    }

    // ---- MorphController wiring: the settle fires once per unfold, from either edge ----

    private class TestFrameClock(private val frameMs: Long = 8L) : MonotonicFrameClock {
        var nowMs = 0L
            private set

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            nowMs += frameMs
            val result = onFrame(nowMs * 1_000_000L)
            Snapshot.sendApplyNotifications()
            return result
        }
    }

    @Test fun `settle fires once when the timed unfold clear completes`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, msSinceTransition = Long.MAX_VALUE)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        assertEquals(0, controller.settleGeneration)
        runBlocking(TestFrameClock()) { controller.playEnter() }
        assertEquals(1, controller.settleGeneration)
        // A second play (a debug replay, say) must not fire the settle again on this instance.
        runBlocking(TestFrameClock()) { controller.requestEnter(80); controller.playEnter() }
        assertEquals(1, controller.settleGeneration)
    }

    @Test fun `settle does not fire when the timed unfold clear is cancelled`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, msSinceTransition = Long.MAX_VALUE)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        runBlocking(TestFrameClock()) {
            val play = launch { controller.playEnter() }
            while (!controller.running) yield()
            play.cancelAndJoin()
        }
        assertEquals(0, controller.settleGeneration)
    }

    @Test fun `settle fires once when angle mode eases the inner tilt to flat, not again on a later wiggle`() {
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f, hingeAngleDeg = 120f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val controller = MorphController(now = { now }, snapshot = { snap }, log = {}, info = {})
        controller.noteSnapshot(snap)
        repeat(30) { now += 20L; controller.noteSnapshot(snap) } // settle into the smoothed tilt at 120 deg
        assertEquals(0, controller.settleGeneration)
        // Ease the angle up to Flat: the tilt clears to 0 and the settle fires exactly once.
        for (a in 121..180) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertEquals(1, controller.settleGeneration)
        // Wiggling the hinge afterward (re-frosting, re-clearing) must not fire it again.
        for (a in 179 downTo 150) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        for (a in 150..180) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertEquals(1, controller.settleGeneration)
    }

    // ---- MorphController wiring: closing-only tilt for the seam darken ----

    @Test fun `closing tilt only counts angle mode while the hinge angle is decreasing`() {
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f, hingeAngleDeg = 100f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val controller = MorphController(now = { now }, snapshot = { snap }, log = {}, info = {})
        controller.noteSnapshot(snap)
        // Opening (angle increasing): not counted as closing.
        for (a in 101..150) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertTrue(controller.angleTilt.value > 0f)
        assertEquals(0f, controller.closingTilt, 0f)
        // Closing (angle decreasing): counted, and matches the angle tilt.
        for (a in 149 downTo 120) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertTrue(controller.closingTilt > 0f)
        assertEquals(controller.angleTilt.value, controller.closingTilt, 0.01f)
    }

    @Test fun `closing tilt also counts a held hinge-triggered closing frost`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        assertEquals(0f, controller.closingTilt, 0f)
        runBlocking(TestFrameClock()) {
            val play = launch { controller.playCloseFrost("hinge") }
            while (controller.closeFrost.value < 1f) yield()
            assertEquals(MorphCurve.closeFrostTilt(controller.closeFrost.value), controller.closingTilt, 0f)
            play.cancelAndJoin()
        }
    }

    // ---- "Zavírání jako Duo" (17. 9. noc), item 2: the closing-only curve, 172 -> 100 with ease-in ----

    @Test fun `closingAngleTilt is 0 at Flat, MAX_TILT at and below CLOSING_PANEL_ON_HINGE`() {
        assertEquals(0f, MorphCurve.closingAngleTilt(MorphCurve.ANGLE_FLAT), 0f)
        assertEquals(0f, MorphCurve.closingAngleTilt(180f), 0f) // clamped past Flat
        assertEquals(MorphCurve.MAX_TILT, MorphCurve.closingAngleTilt(MorphCurve.CLOSING_PANEL_ON_HINGE), 1e-4f)
        assertEquals(MorphCurve.MAX_TILT, MorphCurve.closingAngleTilt(50f), 0f) // full well before the eventual swap
        assertEquals(0f, MorphCurve.closingAngleTilt(Float.NaN), 0f)
    }

    @Test fun `closingAngleTilt is monotonic and eases in (slow start, fast end)`() {
        var previous = 0f
        for (a in MorphCurve.ANGLE_FLAT.toInt() downTo MorphCurve.CLOSING_PANEL_ON_HINGE.toInt()) {
            val t = MorphCurve.closingAngleTilt(a.toFloat())
            assertTrue("angle=$a tilt=$t should not be below previous=$previous", t >= previous - 1e-4f)
            previous = t
        }
        assertEquals(MorphCurve.MAX_TILT, previous, 1e-3f)
        // Ease-in: the first quarter of the angle range covers less than a quarter of the tilt range.
        val quarterAngle = MorphCurve.ANGLE_FLAT - (MorphCurve.ANGLE_FLAT - MorphCurve.CLOSING_PANEL_ON_HINGE) * 0.25f
        assertTrue(MorphCurve.closingAngleTilt(quarterAngle) < MorphCurve.MAX_TILT * 0.25f)
    }

    @Test fun `closingAngleTilt reaches full a full 100 - 90 = 10 degrees before the panel swap`() {
        // FoldShader.PANEL_ON_HINGE is where the swap actually happens (~90 deg); the closing
        // curve is already saturated at MAX_TILT the whole 10 deg of runway before it.
        assertTrue(MorphCurve.CLOSING_PANEL_ON_HINGE > FoldShader.PANEL_ON_HINGE)
        for (a in MorphCurve.CLOSING_PANEL_ON_HINGE.toInt() downTo FoldShader.PANEL_ON_HINGE.toInt()) {
            assertEquals(MorphCurve.MAX_TILT, MorphCurve.closingAngleTilt(a.toFloat()), 0f)
        }
    }

    // ---- item 2, "never releasing early if the estimate wobbles": the closing tilt ratchet ----

    @Test fun `once full frost is reached while closing, a small angle wobble upward does not lower the displayed tilt`() {
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f,
            hingeAngleDeg = 172f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val controller = MorphController(now = { now }, snapshot = { snap }, log = {}, info = {})
        controller.noteSnapshot(snap)
        // Close all the way down to full frost.
        for (a in 171 downTo 100) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertEquals(MorphCurve.MAX_TILT, controller.angleTilt.value, 0.05f)
        // A wobble a couple of degrees back up (well short of the CLOSING_DIRECTION_HYSTERESIS_DEG
        // that would read as a genuine re-open) must not drop the displayed tilt at all.
        now += 20L; snap = snap.copy(hingeAngleDeg = 102f); controller.noteSnapshot(snap)
        assertEquals(MorphCurve.MAX_TILT, controller.angleTilt.value, 0.05f)
    }

    @Test fun `the closing ratchet releases once the direction genuinely flips back to opening`() {
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f,
            hingeAngleDeg = 172f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val controller = MorphController(now = { now }, snapshot = { snap }, log = {}, info = {})
        controller.noteSnapshot(snap)
        for (a in 171 downTo 100) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertEquals(MorphCurve.MAX_TILT, controller.angleTilt.value, 0.05f)
        // A genuine re-open (well past the hysteresis) lets the tilt fall again.
        for (a in 101..172) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); controller.noteSnapshot(snap) }
        repeat(20) { now += 20L; controller.noteSnapshot(snap) }
        assertEquals(0f, controller.angleTilt.value, 1e-3f)
    }

    // ---- MorphController wiring: the reverse cover settle ----

    @Test fun `cover settle starts frosted and tilted then straightens to sharp`() {
        ClosingContinuity.clearForTest()
        val snapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        var tiltAfterReset = -1f
        runBlocking(TestFrameClock()) {
            val play = launch { controller.playCoverSettle() }
            // coverProgress starts at 1 (idle) and is snapped to 0 as the play's first action;
            // wait for that before reading the tilt it snaps alongside, then for the play to finish.
            while (controller.coverProgress.value >= 1f) yield()
            tiltAfterReset = controller.coverSettleTilt.value
            while (controller.coverProgress.value < 1f) yield()
            play.join()
        }
        assertTrue("the settle must start tilted, was $tiltAfterReset", tiltAfterReset > 0f)
        assertEquals(0f, controller.coverSettleTilt.value, 0f)
        assertEquals(1f, controller.coverProgress.value, 0f)
    }

    // ---- item 4, "continuity": the cover settle starts from the CLOSING inner half's own last tilt ----

    @Test fun `cover settle starts from the closing tilt the inner half ended at, across a fresh controller`() {
        ClosingContinuity.clearForTest()
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f,
            hingeAngleDeg = 172f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        // The inner half's own controller, closing all the way to full frost (item 2).
        val closingController = MorphController(now = { now }, snapshot = { snap }, log = {}, info = {})
        closingController.noteSnapshot(snap)
        for (a in 171 downTo 100) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); closingController.noteSnapshot(snap) }
        repeat(20) { now += 20L; closingController.noteSnapshot(snap) }
        assertEquals(MorphCurve.MAX_TILT, closingController.angleTilt.value, 0.05f)

        // The swap relaunches the activity: a brand-new controller, which never saw the above,
        // plays the cover's reverse settle.
        val coverSnapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val freshController = MorphController(now = { now }, snapshot = { coverSnapshot }, log = {}, info = {})
        var tiltAfterReset = -1f
        runBlocking(TestFrameClock()) {
            val play = launch { freshController.playCoverSettle() }
            while (freshController.coverProgress.value >= 1f) yield()
            tiltAfterReset = freshController.coverSettleTilt.value
            play.cancelAndJoin()
        }
        assertEquals(MorphCurve.MAX_TILT, tiltAfterReset, 0.5f)
        ClosingContinuity.clearForTest()
    }

    @Test fun `cover settle falls back to the dramatized start tilt when no closing was ever recorded`() {
        ClosingContinuity.clearForTest()
        val snapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        var tiltAfterReset = -1f
        runBlocking(TestFrameClock()) {
            val play = launch { controller.playCoverSettle() }
            while (controller.coverProgress.value >= 1f) yield()
            tiltAfterReset = controller.coverSettleTilt.value
            play.cancelAndJoin()
        }
        assertEquals(MorphCurve.coverSettleStartTilt(), tiltAfterReset, 0f)
    }

    @Test fun `cover settle skips its own tilt animation when angle mode already drives the cover`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f,
            hingeAngleDeg = 60f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        controller.noteSnapshot(snapshot)
        assertTrue(controller.angleMode)
        runBlocking(TestFrameClock()) { controller.playCoverSettle() }
        assertEquals(0f, controller.coverSettleTilt.value, 0f)
        assertEquals(1f, controller.coverProgress.value, 0f)
    }

    // ---- MorphController wiring: the animator duration scale actually stretches a real tween ----

    @Test fun `animator duration scale stretches a real tween's on-screen time`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        controller.noteAnimatorDurationScale(0.5f)
        val clock = TestFrameClock(frameMs = 8L)
        var msAtFull = -1L
        runBlocking(clock) {
            val play = launch { controller.playCoverFrost("debug") }
            while (controller.coverFrost.value < 1f) yield()
            msAtFull = clock.nowMs
            play.cancelAndJoin()
        }
        // COVER_FROST_IN_MS (350) at 0.5x plays over MorphCurve.scaledDurationMs(350, 0.5f) = 700 ms.
        assertEquals(MorphCurve.scaledDurationMs(MorphCurve.COVER_FROST_IN_MS, 0.5f).toDouble(), msAtFull.toDouble(), 16.0)
    }

    @Test fun `an invalid animator duration scale is treated as 1x`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, msSinceTransition = Long.MAX_VALUE)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        controller.noteAnimatorDurationScale(Float.NaN)
        val clock = TestFrameClock(frameMs = 8L)
        var msAtFull = -1L
        runBlocking(clock) {
            val play = launch { controller.playEnter() }
            // progress starts at 1 (idle) and is snapped to 0 as the play's first action; wait
            // for the running flag (set even earlier) so the wait below cannot exit immediately.
            while (!controller.running) yield()
            while (controller.progress.value < 1f) yield()
            msAtFull = clock.nowMs
            play.join()
        }
        assertEquals(MorphCurve.DURATION_MS.toDouble(), msAtFull.toDouble(), 16.0)
    }
}
