package cz.pflanzer.foldduo

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseSnapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfoldMorphTest {
    // Today pane on the Fold 8 inner panel: 534 dp at density 360 = 1201.5 px.
    private val paneWidth = 1201.5f

    @Test fun `Today only drifts 4 percent of its width under the frost, no slide`() {
        assertEquals(0.04f, MorphCurve.SLIDE_FRACTION, 0f)
        assertEquals(-paneWidth * 0.04f, MorphCurve.translationX(0f, paneWidth), 1e-3f)
        assertEquals(-paneWidth * 0.02f, MorphCurve.translationX(0.5f, paneWidth), 1e-3f)
        assertEquals(0f, MorphCurve.translationX(1f, paneWidth), 0f)
        // Out-of-range progress is clamped, never overshoots past rest or beyond the start.
        assertEquals(0f, MorphCurve.translationX(1.5f, paneWidth), 0f)
        assertEquals(-paneWidth * 0.04f, MorphCurve.translationX(-1f, paneWidth), 1e-3f)
        // Under 50 px on the Fold 8 pane: the content moves with the glass, not through it.
        assertTrue(-MorphCurve.translationX(0f, paneWidth) < 50f)
    }

    @Test fun `inner clear lasts 750 ms and holds until One UI's screen-on fade is over`() {
        assertEquals(750, MorphCurve.DURATION_MS)
        assertEquals(900L, MorphCurve.SCREEN_ON_SETTLE_MS)
        // The hold must end before an armed request expires, else a real swap never plays.
        assertTrue(MorphCurve.SCREEN_ON_SETTLE_MS < MorphCurve.ARMED_TTL_MS)
    }

    @Test fun `clear easing holds the frost early and clears late`() {
        assertEquals(0.3f, MorphCurve.CLEAR_HOLD_FRACTION, 0f)
        assertEquals(35f, MorphCurve.CLEAR_HOLD_TILT, 0f)
        // Ends: fully frosted at the first frame, flat at the last.
        assertEquals(FoldShader.MAX_TILT, MorphCurve.clearTilt(0f), 0f)
        assertTrue(MorphCurve.clearTilt(1f) < FoldShader.FLAT_EPSILON)
        // The first 30 % of the animation stays at or above 35°: glass, not content arriving.
        for (i in 0..30) assertTrue("tilt at ${i}%", MorphCurve.clearTilt(i / 100f) >= MorphCurve.CLEAR_HOLD_TILT)
        // ... is still visibly frosted at the midpoint (13° of 45°), and settles softly from 75 % on.
        assertTrue(MorphCurve.clearTilt(0.5f) > FoldShader.MAX_TILT * 0.25f)
        assertTrue(MorphCurve.clearTilt(0.75f) < FoldShader.MAX_TILT * 0.05f)
        // Holds longer than the plain fast-out-slow-in the frost phases use.
        assertTrue(MorphCurve.clearTilt(0.3f) > MorphCurve.tilt(MorphCurve.easing.transform(0.3f)))
        // Monotonic over time, clamped outside 0..1.
        var previous = Float.MAX_VALUE
        for (i in 0..100) {
            val t = MorphCurve.clearTilt(i / 100f)
            assertTrue("tilt must not increase at $i", t <= previous)
            previous = t
        }
        assertEquals(MorphCurve.clearTilt(0f), MorphCurve.clearTilt(-1f), 0f)
        assertEquals(MorphCurve.clearTilt(1f), MorphCurve.clearTilt(2f), 0f)
    }

    // ---- phase 1: the cover frost ----

    @Test fun `cover frost tilt maps the held fraction onto the shader cap`() {
        assertEquals(0f, MorphCurve.coverFrostTilt(0f), 0f)
        assertEquals(FoldShader.MAX_TILT * 0.5f, MorphCurve.coverFrostTilt(0.5f), 1e-6f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.coverFrostTilt(1f), 0f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.COVER_FROST_TILT, 0f)
        // Clamped, and off below the flat epsilon at rest.
        assertEquals(FoldShader.MAX_TILT, MorphCurve.coverFrostTilt(2f), 0f)
        assertTrue(MorphCurve.coverFrostTilt(-1f) < FoldShader.FLAT_EPSILON)
    }

    @Test fun `cover frost rises over 350 ms, holds 1_2 s and releases over 300 ms without a swap`() {
        assertEquals(350, MorphCurve.COVER_FROST_IN_MS)
        assertEquals(1_200L, MorphCurve.COVER_FROST_HOLD_MS)
        assertEquals(300, MorphCurve.COVER_FROST_OUT_MS)
        assertEquals(1_550L, MorphCurve.coverFrostReleaseAtMs)
        assertEquals(0f, MorphCurve.coverFrostFraction(0L), 0f)
        assertEquals(0.5f, MorphCurve.coverFrostFraction(175L), 1e-6f)
        assertEquals(1f, MorphCurve.coverFrostFraction(350L), 0f)
        assertEquals(1f, MorphCurve.coverFrostFraction(1_000L), 0f)
        assertEquals(1f, MorphCurve.coverFrostFraction(1_549L), 0f)
        assertEquals(0.5f, MorphCurve.coverFrostFraction(1_550L + 150L), 1e-6f)
        assertEquals(0f, MorphCurve.coverFrostFraction(1_850L), 0f)
        assertEquals(0f, MorphCurve.coverFrostFraction(10_000L), 0f)
        assertEquals(0f, MorphCurve.coverFrostFraction(-5L), 0f)
        // A false positive (handling the closed phone) costs at most rise + hold + release.
        assertTrue(MorphCurve.coverFrostReleaseAtMs + MorphCurve.COVER_FROST_OUT_MS <= 2_000L)
    }

    @Test fun `gyro trigger constants are the detector defaults, measured above handling`() {
        assertEquals(5.5f, MorphCurve.GYRO_OPEN_RATE_RAD_S, 0f)
        assertEquals(3, MorphCurve.GYRO_OPEN_SAMPLES)
        // Per-second handling peaks measured on the Fold 8 stay under the threshold; the fastest real swings do not.
        assertTrue(4.5f < MorphCurve.GYRO_OPEN_RATE_RAD_S)
        assertTrue(5.63f >= MorphCurve.GYRO_OPEN_RATE_RAD_S)
    }

    @Test fun `frost on motion is off by default and gates only the gyro triggers`() {
        assertFalse(AppearanceState().motionFrost)
        // Off: the hinge edges still trigger both frosts, the gyro sequences never do.
        assertEquals("hinge", coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Mid, 0, 0, motionFrost = false))
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Closed, 3, 4, motionFrost = false))
        assertEquals("hinge", closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Mid, 0, 0, motionFrost = false))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Flat, 3, 4, motionFrost = false))
        // On: the gyro sequences advancing count.
        assertEquals("gyro", coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Closed, 3, 4, motionFrost = true))
        assertEquals("gyro", closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Flat, 3, 4, motionFrost = true))
        // The controller reads the setting live.
        var motion = false
        val snapshot = PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, motionFrost = { motion })
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, openingMotionSeq = 0))
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, openingMotionSeq = 1))
        assertEquals(0, controller.coverFrostRequests)
        motion = true
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, openingMotionSeq = 2))
        assertEquals(1, controller.coverFrostRequests)
    }

    @Test fun `cover frost triggers only on the cover, on a hinge edge off Closed or a new gyro event`() {
        // Hinge: Closed -> Mid (or Flat) on the cover.
        assertEquals("hinge", coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Mid, 0, 0, true))
        assertEquals("hinge", coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Flat, 0, 0, true))
        // The first sample after registration has no predecessor: not motion.
        assertNull(coverFrostTrigger(Panel.Cover, null, HingeStep.Mid, 0, 0, true))
        // Still Mid (tent), or back to Closed: nothing.
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Mid, HingeStep.Mid, 0, 0, true))
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Mid, HingeStep.Closed, 0, 0, true))
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Closed, 0, 0, true))
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Closed, null, 0, 0, true))
        // Gyro: the sequence advancing, but never from the first snapshot (-1).
        assertEquals("gyro", coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Closed, 3, 4, true))
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Closed, -1, 4, true))
        assertNull(coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Closed, 4, 4, true))
        // The hinge edge wins when both arrive in one snapshot.
        assertEquals("hinge", coverFrostTrigger(Panel.Cover, HingeStep.Closed, HingeStep.Mid, 3, 4, true))
        // Never on the inner panel or an unknown one: closing rotates just as fast.
        assertNull(coverFrostTrigger(Panel.Inner, HingeStep.Closed, HingeStep.Mid, 3, 4, true))
        assertNull(coverFrostTrigger(Panel.Unknown, HingeStep.Closed, HingeStep.Mid, 3, 4, true))
    }

    // ---- phase 0: the closing frost ----

    @Test fun `closing frost triggers only on the inner panel, on a hinge edge off Flat or a new closing gyro event`() {
        // Hinge: Flat -> Mid on a normal fold (measured 1.2 s before the swap), Flat -> Closed on a fast one.
        assertEquals("hinge", closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Mid, 0, 0, true))
        assertEquals("hinge", closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Closed, 0, 0, true))
        // Registration, still Flat, or opening further (Mid -> Flat) are not a fold.
        assertNull(closeFrostTrigger(Panel.Inner, null, HingeStep.Mid, 0, 0, true))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Flat, 0, 0, true))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Mid, HingeStep.Flat, 0, 0, true))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Mid, HingeStep.Closed, 0, 0, true))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Flat, null, 0, 0, true))
        // Gyro: the closing sequence advancing, never from the first snapshot.
        assertEquals("gyro", closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Flat, 3, 4, true))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Flat, -1, 4, true))
        assertNull(closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Flat, 4, 4, true))
        assertEquals("hinge", closeFrostTrigger(Panel.Inner, HingeStep.Flat, HingeStep.Mid, 3, 4, true))
        // Never on the cover (that is the opening) or an unknown panel.
        assertNull(closeFrostTrigger(Panel.Cover, HingeStep.Flat, HingeStep.Mid, 3, 4, true))
        assertNull(closeFrostTrigger(Panel.Unknown, HingeStep.Flat, HingeStep.Mid, 3, 4, true))
        // The two triggers never fire for the same snapshot: each needs its own panel.
        for (panel in Panel.entries) for (prev in listOf(null) + HingeStep.entries) for (step in listOf(null) + HingeStep.entries) {
            val both = coverFrostTrigger(panel, prev, step, 0, 1, true) != null && closeFrostTrigger(panel, prev, step, 0, 1, true) != null
            assertFalse("$panel $prev -> $step", both)
        }
    }

    @Test fun `closing frost curve is the unfold in reverse and a gyro trigger holds 1_2 s without a swap`() {
        assertEquals(350, MorphCurve.CLOSE_FROST_IN_MS)
        assertEquals(1_200L, MorphCurve.CLOSE_FROST_HOLD_MS)
        assertEquals(300, MorphCurve.CLOSE_FROST_OUT_MS)
        assertEquals(1_000L, MorphCurve.CLOSE_FROST_DEBUG_HOLD_MS)
        assertEquals(1_550L, MorphCurve.closeFrostReleaseAtMs)
        // Only the gyro and the debug replay hold by time; a hinge trigger follows the hinge (below).
        assertEquals(1_200L, MorphCurve.closeFrostTimedHoldMs("gyro"))
        assertEquals(1_000L, MorphCurve.closeFrostTimedHoldMs("debug"))
        assertNull(MorphCurve.closeFrostTimedHoldMs("hinge"))
        // Tilt: sharp at rest, the shader cap when held; the same cap the unfold starts from.
        assertEquals(0f, MorphCurve.closeFrostTilt(0f), 0f)
        assertEquals(FoldShader.MAX_TILT * 0.5f, MorphCurve.closeFrostTilt(0.5f), 1e-6f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.closeFrostTilt(1f), 0f)
        assertEquals(MorphCurve.startTilt, MorphCurve.closeFrostTilt(1f), 0f)
        assertTrue(MorphCurve.closeFrostTilt(0f) < FoldShader.FLAT_EPSILON)
        // Schedule: rise, hold, release, off.
        assertEquals(0f, MorphCurve.closeFrostFraction(0L), 0f)
        assertEquals(0.5f, MorphCurve.closeFrostFraction(175L), 1e-6f)
        assertEquals(1f, MorphCurve.closeFrostFraction(350L), 0f)
        assertEquals(1f, MorphCurve.closeFrostFraction(1_549L), 0f)
        assertEquals(0.5f, MorphCurve.closeFrostFraction(1_700L), 1e-6f)
        assertEquals(0f, MorphCurve.closeFrostFraction(1_850L), 0f)
        assertEquals(0f, MorphCurve.closeFrostFraction(-1L), 0f)
    }

    @Test fun `hinge-triggered closing frost holds while the hinge is off Flat and releases on Flat or the safety net`() {
        assertEquals(8_000L, MorphCurve.CLOSE_FROST_HOLD_MAX_MS)
        assertEquals(400L, MorphCurve.CLOSE_FROST_FLAT_SETTLE_MS)
        assertTrue(MorphCurve.CLOSE_FROST_HOLD_POLL_MS < MorphCurve.CLOSE_FROST_FLAT_SETTLE_MS)
        // A slow fold (traced: Mid at +68 ms, the swap 3 s later): no timer releases it.
        val slow = CloseFrostHold(startedAtMs = 1_000L)
        for (t in 1_000L..4_000L step 50L) assertNull("t=$t", slow.release(Panel.Inner, HingeStep.Mid, t))
        // The step flickers Mid <-> Flat while the phone is held around 135°: a short Flat holds on.
        assertNull(slow.release(Panel.Inner, HingeStep.Flat, 4_050L))
        assertNull(slow.release(Panel.Inner, HingeStep.Flat, 4_400L))
        assertNull(slow.release(Panel.Inner, HingeStep.Mid, 4_450L))
        assertNull(slow.release(Panel.Inner, HingeStep.Flat, 4_500L))
        assertNull(slow.release(Panel.Inner, HingeStep.Flat, 4_850L))
        // Flat for the settle time: the fold was abandoned, release.
        assertEquals("hinge back at Flat", slow.release(Panel.Inner, HingeStep.Flat, 4_900L))
        // Straight to Closed on a fast fold: hold for the swap.
        val fast = CloseFrostHold(startedAtMs = 0L)
        assertNull(fast.release(Panel.Inner, HingeStep.Closed, 500L))
        assertNull(fast.release(Panel.Inner, null, 600L))
        // Flat on any other panel is not a resting open phone: hold, the net bounds it.
        assertNull(fast.release(Panel.Cover, HingeStep.Flat, 700L))
        assertNull(fast.release(Panel.Cover, HingeStep.Flat, 1_500L))
        // The safety net: half-open for 8 s releases whatever the hinge says.
        assertNull(fast.release(Panel.Inner, HingeStep.Mid, 7_999L))
        assertEquals("timeout", fast.release(Panel.Inner, HingeStep.Mid, 8_000L))
        // The gyro hold is the old timed one, and the debug replay's is shorter still.
        assertEquals(MorphCurve.CLOSE_FROST_HOLD_MS, MorphCurve.closeFrostTimedHoldMs("gyro"))
        assertTrue(MorphCurve.CLOSE_FROST_DEBUG_HOLD_MS < MorphCurve.CLOSE_FROST_HOLD_MS)
        assertTrue(MorphCurve.CLOSE_FROST_HOLD_MS < MorphCurve.CLOSE_FROST_HOLD_MAX_MS)
    }

    @Test fun `left half is frozen for host widgets while either morph drives it`() {
        assertFalse(leftHalfFrozen(running = false, closeFrostActive = false))
        assertTrue(leftHalfFrozen(running = true, closeFrostActive = false))
        assertTrue(leftHalfFrozen(running = false, closeFrostActive = true))
        assertTrue(leftHalfFrozen(running = true, closeFrostActive = true))
        // The animation values keep it on past the flags: until the clear is at 1 and the
        // closing frost back at 0, whatever ended the play.
        assertTrue(leftHalfFrozen(running = false, closeFrostActive = false, progress = 0f))
        assertTrue(leftHalfFrozen(running = false, closeFrostActive = false, progress = 0.999f))
        assertTrue(leftHalfFrozen(running = false, closeFrostActive = false, closeFrost = 0.001f))
        assertFalse(leftHalfFrozen(running = false, closeFrostActive = false, progress = 1f, closeFrost = 0f))
        assertFalse(MorphCurve.leftHalfMoving(1f, 0f))
        assertTrue(MorphCurve.leftHalfMoving(0.5f, 0f))
        assertTrue(MorphCurve.leftHalfMoving(1f, 1f))
        // On the controller: taking a closing frost freezes the half at once (before the rise
        // starts), an armed but untaken one does not; the unfold flag is `running` (playEnter).
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f)
        val logs = mutableListOf<String>()
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = { logs += it })
        assertFalse(controller.leftHalfFrozen)
        controller.requestCloseFrost("hinge")
        assertFalse(controller.leftHalfFrozen)
        assertEquals("hinge", controller.takeCloseFrost())
        assertTrue(controller.leftHalfFrozen)
        // The host-widget bookkeeping logs a running count; a thaw of an unknown widget is silent.
        controller.noteHostWidgetFrozen(12, 1180, 420)
        controller.noteHostWidgetFrozen(13, 560, 420)
        assertEquals("froze host widget 12 (1180x420 px), 1 frozen", logs[logs.size - 2])
        assertEquals("froze host widget 13 (560x420 px), 2 frozen", logs.last())
        controller.noteHostWidgetThawed(12)
        assertEquals("thawed host widget 12, 1 frozen", logs.last())
        val count = logs.size
        controller.noteHostWidgetThawed(12)
        assertEquals(count, logs.size)
    }

    @Test fun `left-half frost layer combines the unfold clear and the closing frost`() {
        // Both at rest: sharp.
        assertTrue(MorphCurve.leftHalfTilt(1f, 0f) < FoldShader.FLAT_EPSILON)
        // Only the unfold running: exactly the unfold curve.
        for (i in 0..10) assertEquals(MorphCurve.tilt(i / 10f), MorphCurve.leftHalfTilt(i / 10f, 0f), 0f)
        // Only the closing frost running: exactly the closing curve.
        for (i in 0..10) assertEquals(MorphCurve.closeFrostTilt(i / 10f), MorphCurve.leftHalfTilt(1f, i / 10f), 0f)
        // Held closing frost = the unfold's first frame: the primed effect is reused.
        assertEquals(MorphCurve.startTilt, MorphCurve.leftHalfTilt(1f, 1f), 0f)
    }

    @Test fun `controller derives closing frost triggers from inner snapshots and drops them on the swap`() {
        var clock = 0L
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f)
        val controller = MorphController(now = { clock }, snapshot = { snapshot }, log = {}, motionFrost = { true })
        assertNull(controller.takeCloseFrost())
        assertEquals(0f, controller.closeFrost.value, 0f)
        // Registration on the inner panel: no edge.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 180f, closingMotionSeq = 0))
        assertEquals(0, controller.closeFrostRequests)
        // Hinge steps off Flat: trigger, taken once, and taking marks the frost active.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 90f, closingMotionSeq = 0))
        assertEquals(1, controller.closeFrostRequests)
        assertFalse(controller.closeFrostActive)
        assertTrue(controller.closeFrostBusy)
        // A second trigger while armed is dropped, not queued.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 90f, closingMotionSeq = 1))
        assertEquals(1, controller.closeFrostRequests)
        assertEquals("hinge", controller.takeCloseFrost())
        assertTrue(controller.closeFrostActive)
        assertNull(controller.takeCloseFrost())
        // The closing gyro sequence advancing (back at Flat, nothing armed): trigger.
        val fresh = MorphController(now = { clock }, snapshot = { snapshot }, log = {}, motionFrost = { true })
        fresh.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 180f, closingMotionSeq = 1))
        fresh.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 180f, closingMotionSeq = 2))
        assertEquals(1, fresh.closeFrostRequests)
        // The swap to the cover drops the armed trigger: the inner panel is off. The settle is armed instead.
        fresh.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, closingMotionSeq = 2, msSinceTransition = 10))
        assertNull(fresh.takeCloseFrost())
        assertFalse(fresh.closeFrostBusy)
        assertTrue(fresh.shouldSettleCover())
        // The opening sequence and hinge edges on the cover never arm the closing frost.
        fresh.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 90f, openingMotionSeq = 5, closingMotionSeq = 2))
        assertEquals(1, fresh.closeFrostRequests)
        // A stale request (the layout came up late) is dropped.
        val late = MorphController(now = { clock }, snapshot = { snapshot }, log = {})
        late.requestCloseFrost("debug")
        clock += MorphCurve.ARMED_TTL_MS + 1
        assertNull(late.takeCloseFrost())
        assertFalse(late.closeFrostBusy)
    }

    @Test fun `closing frost and the unfold morph exclude each other`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f, msSinceTransition = Long.MAX_VALUE)
        val logs = mutableListOf<String>()
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = { logs += it })
        // An armed unfold (a fresh swap or the debug replay) drops a closing trigger.
        controller.requestEnter()
        controller.requestCloseFrost("hinge")
        assertEquals(0, controller.closeFrostRequests)
        assertTrue(logs.last().startsWith("close frost: trigger=hinge ignored (unfold morph armed)"))
        // 17. 9.: a real swap arms the pacing floor, which IS the guaranteed clear — no timed play.
        assertFalse(controller.shouldEnter(FoldPose.Open))
        assertTrue(controller.innerPacingFloorActive)
        // An armed or active closing frost stops the unfold from playing.
        controller.requestCloseFrost("hinge")
        assertEquals(1, controller.closeFrostRequests)
        controller.requestEnter()
        assertFalse(controller.shouldEnter(FoldPose.Open))
        assertTrue(logs.last().startsWith("unfold morph: ignored (close frost armed)"))
        assertEquals("hinge", controller.takeCloseFrost())
        assertTrue(controller.closeFrostActive)
        controller.requestEnter()
        assertFalse(controller.shouldEnter(FoldPose.Open))
        assertTrue(logs.last().startsWith("unfold morph: ignored (close frost active)"))
        // ... and a second closing trigger while it plays is dropped too.
        controller.requestCloseFrost("gyro")
        assertEquals(1, controller.closeFrostRequests)
        assertTrue(logs.last().startsWith("close frost: trigger=gyro ignored (already active)"))
        // Neither side touched the cover frost.
        assertEquals(0, controller.coverFrostRequests)
        assertFalse(controller.running)
    }

    @Test fun `controller arms one cover frost per trigger and drops repeats while it is armed`() {
        var clock = 0L
        val snapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val controller = MorphController(now = { clock }, snapshot = { snapshot }, log = {})
        assertNull(controller.takeCoverFrost())
        assertEquals(0, controller.coverFrostRequests)
        assertEquals(0f, controller.coverFrost.value, 0f)
        controller.requestCoverFrost("gyro")
        assertEquals(1, controller.coverFrostRequests)
        // A second trigger while the first is armed is dropped, not queued.
        controller.requestCoverFrost("hinge")
        assertEquals(1, controller.coverFrostRequests)
        assertEquals("gyro", controller.takeCoverFrost())
        // Consumed once.
        assertNull(controller.takeCoverFrost())
        // A stale request (the layout came up late) is dropped.
        controller.requestCoverFrost("hinge")
        clock += MorphCurve.ARMED_TTL_MS + 1
        assertNull(controller.takeCoverFrost())
        // A cover-frost request never arms the inner morph or the settle.
        assertFalse(controller.running)
        assertFalse(controller.coverFrostActive)
        assertEquals(0, controller.requests)
    }

    @Test fun `controller derives cover frost triggers from snapshots`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, motionFrost = { true })
        // First snapshot: registration, no edge.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, openingMotionSeq = 0))
        assertEquals(0, controller.coverFrostRequests)
        // Hinge steps off Closed: trigger.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 90f, openingMotionSeq = 0))
        assertEquals(1, controller.coverFrostRequests)
        assertEquals("hinge", controller.takeCoverFrost())
        // Gyro fires: trigger.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, openingMotionSeq = 0))
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, hingeDeg = 0f, openingMotionSeq = 1))
        assertEquals(2, controller.coverFrostRequests)
        assertEquals("gyro", controller.takeCoverFrost())
        // The same events on the inner panel: nothing, and the inner morph is untouched.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 0f, openingMotionSeq = 1))
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Inner, hingeDeg = 90f, openingMotionSeq = 2))
        assertEquals(2, controller.coverFrostRequests)
        // Closed -> Mid on the inner panel is not a fold either: no closing frost.
        assertEquals(0, controller.closeFrostRequests)
    }

    @Test fun `tilt resolves from the shader cap to flat and is monotonic`() {
        assertEquals(FoldShader.MAX_TILT, MorphCurve.tilt(0f), 0f)
        assertEquals(FoldShader.MAX_TILT * 0.5f, MorphCurve.tilt(0.5f), 1e-6f)
        assertEquals(0f, MorphCurve.tilt(1f), 0f)
        // At rest the tilt is below the shader's flat epsilon, so foldEffect drops the render effect.
        assertTrue(MorphCurve.tilt(1f) < FoldShader.FLAT_EPSILON)
        var previous = Float.MAX_VALUE
        for (i in 0..20) {
            val t = MorphCurve.tilt(i / 20f)
            assertTrue("tilt must not increase at $i", t <= previous)
            previous = t
        }
    }

    @Test fun `progress 0 is the primed start frame and progress 1 the rest state`() {
        // The idle layer pre-builds its RenderEffect for startTilt; the first animated frame
        // (progress 0) must ask for exactly that tilt so the cache hits.
        assertEquals(MorphCurve.startTilt, MorphCurve.tilt(0f), 0f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.startTilt, 0f)
        assertTrue(MorphCurve.startTilt >= FoldShader.FLAT_EPSILON)
        // Rest: no translation, tilt under the epsilon that switches the effect off.
        assertEquals(0f, MorphCurve.translationX(1f, paneWidth), 0f)
        assertTrue(MorphCurve.tilt(1f) < FoldShader.FLAT_EPSILON)
        assertEquals(1f, MorphCurve.coverAlpha(1f), 0f)
    }

    @Test fun `warm-up flag starts false and latches`() {
        val controller = MorphController(now = { 0L }, snapshot = { PoseSnapshot() })
        assertFalse(controller.warmedUp)
        controller.noteWarmedUp()
        assertTrue(controller.warmedUp)
        // A warm-up does not arm or play anything.
        assertEquals(0, controller.requests)
        assertFalse(controller.running)
        assertEquals(1f, controller.progress.value, 0f)
    }

    @Test fun `cover settle eases from 0_85 to full alpha`() {
        assertEquals(0.85f, MorphCurve.coverAlpha(0f), 1e-6f)
        assertEquals(0.925f, MorphCurve.coverAlpha(0.5f), 1e-6f)
        assertEquals(1f, MorphCurve.coverAlpha(1f), 0f)
    }

    @Test fun `unfold trigger needs the inner panel and a fresh swap or InMotion`() {
        assertTrue(unfoldJustHappened(FoldPose.InMotion, Panel.Inner, 600))
        assertTrue(unfoldJustHappened(FoldPose.Open, Panel.Inner, 700))
        assertFalse(unfoldJustHappened(FoldPose.Open, Panel.Inner, 800))
        assertFalse(unfoldJustHappened(FoldPose.Open, Panel.Inner, Long.MAX_VALUE))
        // A hinge step while still on the cover is InMotion too, but must not arm the morph.
        assertFalse(unfoldJustHappened(FoldPose.InMotion, Panel.Cover, 100))
        assertFalse(unfoldJustHappened(FoldPose.InMotion, Panel.Unknown, 100))
        assertTrue(foldJustHappened(Panel.Cover, 100))
        assertFalse(foldJustHappened(Panel.Cover, 800))
        assertFalse(foldJustHappened(Panel.Inner, 100))
    }

    @Test fun `controller arms on a cover to inner edge and consumes the request once`() {
        var snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, msSinceTransition = Long.MAX_VALUE)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot })
        // A stable open Home: nothing to play.
        assertFalse(controller.shouldEnter(FoldPose.Open))
        assertEquals(0, controller.requests)
        // The activity lives through the swap: Cover -> Inner edge arms one enter.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover))
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Inner, pose = FoldPose.InMotion, msSinceTransition = 10))
        assertEquals(1, controller.requests)
        // 17. 9.: the swap arms the pacing floor, which drives the clear itself — no timed play,
        // and the request is consumed by that decision.
        assertFalse(controller.shouldEnter(FoldPose.Open))
        assertTrue(controller.innerPacingFloorActive)
        assertFalse(controller.shouldEnter(FoldPose.Open))
        // The relaunched activity has no edge, only a fresh snapshot: still arms.
        snapshot = PoseSnapshot(pose = FoldPose.InMotion, panel = Panel.Inner, msSinceTransition = 350)
        assertTrue(MorphController(now = { 0L }, snapshot = { snapshot }).shouldEnter(FoldPose.InMotion))
        // Inner -> Cover edge arms the cover settle, not the enter morph.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover, msSinceTransition = 10))
        assertEquals(2, controller.requests)
        assertTrue(controller.shouldSettleCover())
        assertFalse(controller.shouldSettleCover())
        // Unknown panels never count as an edge.
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Unknown))
        controller.noteSnapshot(PoseSnapshot(panel = Panel.Cover))
        assertEquals(2, controller.requests)
    }

    // ---- the plays, on a JVM frame clock ----

    /**
     * A frame clock for the JVM: every withFrameNanos is one frame [frameMs] later. After the
     * frame's animation write the global snapshot is advanced (as a frame on the device does)
     * and [sample] runs, so a test sees what a composable reading the controller would.
     */
    private class FrameClock(private val frameMs: Long = 8L, private val sample: (nowMs: Long) -> Unit = {}) : MonotonicFrameClock {
        var nowMs = 0L
            private set

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield() // a frame boundary: other coroutines (the test body) run between frames
            nowMs += frameMs
            val result = onFrame(nowMs * 1_000_000L)
            Snapshot.sendApplyNotifications()
            sample(nowMs)
            return result
        }
    }

    @Test fun `host widgets stay frozen for the whole unfold clear, until progress is 1`() {
        // A relaunched activity's first expanded layout, past the screen-on settle: no wait.
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, msSinceTransition = Long.MAX_VALUE)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        assertFalse(controller.leftHalfFrozen)
        val frames = mutableListOf<Triple<Long, Float, Boolean>>()
        val clock = FrameClock(frameMs = 8L) { frames += Triple(it, controller.progress.value, controller.leftHalfFrozen) }
        runBlocking(clock) { controller.playEnter() }
        // Every animated frame, from the fully frosted first one to the one that lands at 1,
        // keeps the snapshots up; the flag drops only once the play is over.
        assertTrue("frames: ${frames.size}", frames.size >= MorphCurve.DURATION_MS / 8)
        assertEquals(0f, frames.first().second, 0f)
        frames.forEach { (ms, p, frozen) -> assertTrue("frame at ${ms}ms progress=$p must be frozen", frozen) }
        assertEquals(1f, frames.last().second, 0f)
        assertTrue(frames.last().first - frames.first().first >= MorphCurve.DURATION_MS)
        // The curve holds the frost early: at 30 % of the play the tilt is still >= 35°.
        val at30 = frames.first { (ms, _, _) -> ms - frames.first().first >= MorphCurve.DURATION_MS * MorphCurve.CLEAR_HOLD_FRACTION }
        assertTrue("tilt at 30 %: ${MorphCurve.tilt(at30.second)}", MorphCurve.tilt(at30.second) >= MorphCurve.CLEAR_HOLD_TILT)
        assertFalse(controller.running)
        assertEquals(1f, controller.progress.value, 0f)
        assertFalse(controller.leftHalfFrozen)
    }

    @Test fun `unfold clear cancelled mid-way leaves the half sharp, not frozen`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, msSinceTransition = Long.MAX_VALUE)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        runBlocking(FrameClock()) {
            val play = launch { controller.playEnter() }
            // Idle progress is 1; the play snaps it to 0 and climbs frame by frame.
            while (!controller.running) yield()
            assertEquals(0f, controller.progress.value, 0f)
            assertTrue(controller.leftHalfFrozen)
            while (controller.progress.value < 0.5f) yield()
            assertTrue(controller.progress.value < 1f)
            assertTrue(controller.running)
            assertTrue(controller.leftHalfFrozen)
            // The layout going away (the swap) cancels the play: the layer is reset to rest.
            play.cancelAndJoin()
        }
        assertFalse(controller.running)
        assertEquals(1f, controller.progress.value, 0f)
        assertFalse(controller.leftHalfFrozen)
    }

    @Test fun `host widgets stay frozen through a closing frost until the swap resets it`() {
        val snapshot = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f)
        val controller = MorphController(now = { 0L }, snapshot = { snapshot }, log = {}, info = {})
        val frames = mutableListOf<Pair<Float, Boolean>>()
        val clock = FrameClock { frames += controller.closeFrost.value to controller.leftHalfFrozen }
        controller.requestCloseFrost("hinge")
        assertEquals("hinge", controller.takeCloseFrost())
        assertTrue(controller.leftHalfFrozen)
        runBlocking(clock) {
            val play = launch { controller.playCloseFrost("hinge") }
            // The rise plays out frame by frame, then the hinge hold polls with the frost held at 1.
            while (controller.closeFrost.value < 1f) yield()
            assertTrue(frames.size >= MorphCurve.CLOSE_FROST_IN_MS / 8)
            frames.forEach { (c, frozen) -> assertTrue("closeFrost=$c must be frozen", frozen) }
            assertTrue(controller.closeFrostActive)
            assertTrue(controller.leftHalfFrozen)
            // The swap to the cover cancels the play: the frost resets to 0 and only then the half thaws.
            play.cancelAndJoin()
        }
        assertEquals(0f, controller.closeFrost.value, 0f)
        assertFalse(controller.closeFrostActive)
        assertFalse(controller.leftHalfFrozen)
    }
}
