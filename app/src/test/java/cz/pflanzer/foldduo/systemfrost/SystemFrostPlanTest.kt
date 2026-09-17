package cz.pflanzer.foldduo.systemfrost

import cz.pflanzer.foldduo.MorphCurve
import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemFrostPlanTest {

    // ---- opening, angle mode ----

    @Test fun `opening sequence with a confident angle frosts the cover, swaps to the inner clear, and finishes`() {
        val plan = SystemFrostPlan()
        var t = 0L

        // Hinge step Closed -> Mid on the cover: the opening trigger.
        var actions = plan.onHingeStep(t, Panel.Cover, HingeStep.Closed, launcherForeground = false, keyguardLocked = false)
        assertEquals(emptyList<FrostAction>(), actions) // first sample has no predecessor yet
        assertEquals(FrostPhase.IDLE, plan.phase)

        t += 50
        actions = plan.onHingeStep(t, Panel.Cover, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        assertEquals(listOf(FrostAction.AcquirePoseEngine, FrostAction.RequestScreenshot(OverlayTarget.COVER)), actions)
        assertEquals(FrostPhase.COVER_FROST, plan.phase)

        t += 20
        actions = plan.onScreenshotResult(t, OverlayTarget.COVER, success = true)
        assertEquals(listOf(FrostAction.AttachOverlay(OverlayTarget.COVER, 0f)), actions)

        // Angle mode, paced: a frost-up rises no faster than the rise slew (TiltPacing), so a
        // sample soon after the trigger reads below the raw angle mapping ...
        t += 30
        val slewedTilt = (plan.onTick(t, angleDeg = 30f).single() as FrostAction.UpdateOverlay).tiltDeg
        assertTrue("expected a slew-limited rise, got $slewedTilt", slewedTilt > 0f && slewedTilt < MorphCurve.angleTilt(Panel.Cover, 30f))
        // ... and catches up to track the angle exactly once enough time has passed.
        t += 200
        actions = plan.onTick(t, angleDeg = 30f)
        assertEquals(listOf(FrostAction.UpdateOverlay(OverlayTarget.COVER, MorphCurve.angleTilt(Panel.Cover, 30f))), actions)

        // Panel swaps to inner: cover comes down, the inner clear starts.
        t += 40
        actions = plan.onPanelChanged(t, Panel.Inner)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.COVER, "panel swapped to inner")), actions)
        assertEquals(FrostPhase.INNER_CLEARING, plan.phase)

        // The inner display lights up: request its screenshot, attach fully frosted.
        actions = plan.onDisplayOn(t)
        assertEquals(listOf(FrostAction.RequestScreenshot(OverlayTarget.INNER)), actions)

        t += 15
        val innerAttachedAt = t
        actions = plan.onScreenshotResult(t, OverlayTarget.INNER, success = true)
        assertEquals(listOf(FrostAction.AttachOverlay(OverlayTarget.INNER, MorphCurve.MAX_TILT)), actions)

        // B15: the fresh attach holds fully frosted for at least INNER_ATTACH_HOLD_MS, even with
        // a confident angle already available, so the clear never starts under One UI's fade.
        t += 100
        actions = plan.onTick(t, angleDeg = 130f)
        assertEquals(listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, MorphCurve.MAX_TILT)), actions)

        // Just past the hold: the guaranteed-minimum floor (TiltPacing, armed at the attach) is
        // still well above the raw angle mapping — the clear may not be faster than the timed
        // play it replaces, whatever the angle claims at this exact instant.
        t = innerAttachedAt + SystemFrostPlan.INNER_ATTACH_HOLD_MS + 20
        val pacedTilt = (plan.onTick(t, angleDeg = 130f).single() as FrostAction.UpdateOverlay).tiltDeg
        assertTrue("expected the floor to still dominate, got $pacedTilt", pacedTilt > MorphCurve.angleTilt(Panel.Inner, 130f))

        // Well past the floor's own duration: the clear tracks the angle exactly again.
        t = innerAttachedAt + SystemFrostPlan.INNER_ATTACH_HOLD_MS + MorphCurve.DURATION_MS + 50
        actions = plan.onTick(t, angleDeg = 130f)
        assertEquals(listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, MorphCurve.angleTilt(Panel.Inner, 130f))), actions)

        // Angle reaches Flat: the overlay comes down and the engine is released.
        t += 100
        actions = plan.onTick(t, angleDeg = MorphCurve.ANGLE_FLAT)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.INNER, "flat"), FrostAction.ReleasePoseEngine), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    // ---- opening, NaN angle (timed fallback) ----

    @Test fun `opening with no confident angle ramps the cover on a timer and self-releases without a swap`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, launcherForeground = false, keyguardLocked = false)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        plan.onScreenshotResult(30, OverlayTarget.COVER, success = true)

        // Mid-ramp: the timed schedule follows MorphCurve's own cover-frost fraction.
        val midTilt = (plan.onTick(30 + 100, Float.NaN).single() as FrostAction.UpdateOverlay).tiltDeg
        assertTrue("expected a partial frost, got $midTilt", midTilt > 0f && midTilt < MorphCurve.MAX_TILT)

        // Held fully frosted through the hold window.
        val heldTilt = (plan.onTick(30 + MorphCurve.COVER_FROST_IN_MS + 200L, Float.NaN).single() as FrostAction.UpdateOverlay).tiltDeg
        assertEquals(MorphCurve.MAX_TILT, heldTilt, 0.01f)

        // No swap ever came: past the hold + release window it gives up and releases the engine.
        val releaseAt = 30 + MorphCurve.coverFrostReleaseAtMs + MorphCurve.COVER_FROST_OUT_MS + 50
        val actions = plan.onTick(releaseAt, Float.NaN)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.COVER, "timed out without a swap"), FrostAction.ReleasePoseEngine), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    @Test fun `inner clear with no confident angle holds full frost then removes on the 8 s timeout`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, launcherForeground = false, keyguardLocked = false)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        plan.onScreenshotResult(30, OverlayTarget.COVER, success = true)
        plan.onPanelChanged(200, Panel.Inner)
        plan.onDisplayOn(200)
        plan.onScreenshotResult(220, OverlayTarget.INNER, success = true)

        // No angle: held fully frosted, nothing to clear it early.
        val actions = plan.onTick(220 + 4_000L, Float.NaN)
        assertEquals(listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, MorphCurve.MAX_TILT)), actions)
        assertEquals(FrostPhase.INNER_CLEARING, plan.phase)

        // 8 s after the attach, the safety net removes it.
        val timeoutActions = plan.onTick(220 + SystemFrostPlan.INNER_CLEAR_TIMEOUT_MS + 10, Float.NaN)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.INNER, "timeout"), FrostAction.ReleasePoseEngine), timeoutActions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    // ---- closing ----

    @Test fun `closing holds the inner frost while the hinge is away from Flat and releases once it settles`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Inner, HingeStep.Flat, launcherForeground = false, keyguardLocked = false)
        var actions = plan.onHingeStep(500, Panel.Inner, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        assertEquals(listOf(FrostAction.AcquirePoseEngine, FrostAction.RequestScreenshot(OverlayTarget.INNER)), actions)
        assertEquals(FrostPhase.INNER_FROST_CLOSING, plan.phase)

        plan.onScreenshotResult(520, OverlayTarget.INNER, success = true)

        // Still folding (Mid): the hold does not release yet.
        actions = plan.onHingeStep(600, Panel.Inner, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        assertEquals(emptyList<FrostAction>(), actions)
        assertEquals(FrostPhase.INNER_FROST_CLOSING, plan.phase)

        // Angle-driven tilt still updates while held.
        actions = plan.onTick(650, angleDeg = 120f)
        assertEquals(listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, MorphCurve.closingAngleTilt(120f))), actions)

        // Hinge reads Flat again, but not settled yet.
        actions = plan.onHingeStep(700, Panel.Inner, HingeStep.Flat, launcherForeground = false, keyguardLocked = false)
        assertEquals(emptyList<FrostAction>(), actions)
        assertEquals(FrostPhase.INNER_FROST_CLOSING, plan.phase)

        // Flat for CLOSE_FROST_FLAT_SETTLE_MS: releases.
        actions = plan.onHingeStep(700 + MorphCurve.CLOSE_FROST_FLAT_SETTLE_MS, Panel.Inner, HingeStep.Flat,
            launcherForeground = false, keyguardLocked = false)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.INNER, "hinge back at Flat"), FrostAction.ReleasePoseEngine), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    @Test fun `closing is removed at the panel swap to cover even if the hinge never settles at Flat`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Inner, HingeStep.Flat, launcherForeground = false, keyguardLocked = false)
        plan.onHingeStep(500, Panel.Inner, HingeStep.Closed, launcherForeground = false, keyguardLocked = false)
        plan.onScreenshotResult(520, OverlayTarget.INNER, success = true)

        val actions = plan.onPanelChanged(900, Panel.Cover)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.INNER, "panel swapped to cover"), FrostAction.ReleasePoseEngine), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    // ---- pacing morphu (17. 9.) ----

    @Test fun `the inner clear floor guarantees at least DURATION_MS even when the angle claims it is already nearly flat`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, launcherForeground = false, keyguardLocked = false)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        plan.onScreenshotResult(30, OverlayTarget.COVER, success = true)
        plan.onPanelChanged(200, Panel.Inner)
        plan.onDisplayOn(200)
        val attachedAt = 220L
        plan.onScreenshotResult(attachedAt, OverlayTarget.INNER, success = true)

        // The magnetometer estimate claims the phone is already almost flat right after the swap
        // (the 2026-09-17 device bug: a HingeStepGate clamp/step jump), well before the
        // guaranteed-minimum floor's own delay + duration has elapsed. The displayed tilt must
        // not collapse toward the angle's (nearly clear) mapping with it.
        val tooEarly = attachedAt + SystemFrostPlan.INNER_ATTACH_HOLD_MS + 50
        val tiltTooEarly = (plan.onTick(tooEarly, angleDeg = 165f).single() as FrostAction.UpdateOverlay).tiltDeg
        assertTrue("expected the floor to hold well above the angle mapping, got $tiltTooEarly",
            tiltTooEarly > MorphCurve.angleTilt(Panel.Inner, 165f) + 5f)

        // Once the floor's own duration has actually elapsed, the angle (still short of
        // MorphCurve.ANGLE_FLAT) governs again, exactly.
        val afterFloor = attachedAt + SystemFrostPlan.INNER_ATTACH_HOLD_MS + MorphCurve.DURATION_MS + 50
        val tiltAfter = (plan.onTick(afterFloor, angleDeg = 165f).single() as FrostAction.UpdateOverlay).tiltDeg
        assertEquals(MorphCurve.angleTilt(Panel.Inner, 165f), tiltAfter, 0.05f)
    }

    @Test fun `the closing frost-up never pops faster than the rise slew, even on a hinge-step snap`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Inner, HingeStep.Flat, launcherForeground = false, keyguardLocked = false)
        plan.onHingeStep(500, Panel.Inner, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        val attachedAt = 520L
        plan.onScreenshotResult(attachedAt, OverlayTarget.INNER, success = true)

        // Right after the attach: angle still near flat, a fall from the attach's own MAX_TILT —
        // never limited.
        val afterAttach = attachedAt + 20
        var tiltDeg = (plan.onTick(afterAttach, angleDeg = 170f).single() as FrostAction.UpdateOverlay).tiltDeg
        assertEquals(MorphCurve.closingAngleTilt(170f), tiltDeg, 0.01f)

        // The estimate then snaps hard toward "fully frosted" in one sample (a step glitch): the
        // rise is capped by the slew, not an instant pop to MAX_TILT.
        val snapAt = afterAttach + 20
        tiltDeg = (plan.onTick(snapAt, angleDeg = 90f).single() as FrostAction.UpdateOverlay).tiltDeg
        assertTrue("expected a slew-limited rise, got $tiltDeg", tiltDeg < 10f)
    }

    // ---- gates ----

    @Test fun `our own launcher in the foreground skips the episode entirely`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, launcherForeground = true, keyguardLocked = false)
        val actions = plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, launcherForeground = true, keyguardLocked = false)
        assertEquals(listOf(FrostAction.Skip("launcher foreground")), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    @Test fun `a locked keyguard skips the episode entirely`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Inner, HingeStep.Flat, launcherForeground = false, keyguardLocked = true)
        val actions = plan.onHingeStep(10, Panel.Inner, HingeStep.Mid, launcherForeground = false, keyguardLocked = true)
        assertEquals(listOf(FrostAction.Skip("keyguard locked")), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }

    // ---- screenshot failure ----

    @Test fun `a failed screenshot ends the episode silently, one skip action`() {
        val plan = SystemFrostPlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, launcherForeground = false, keyguardLocked = false)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, launcherForeground = false, keyguardLocked = false)
        val actions = plan.onScreenshotResult(30, OverlayTarget.COVER, success = false)
        assertEquals(listOf(FrostAction.RemoveOverlay(OverlayTarget.COVER, "screenshot failed"), FrostAction.ReleasePoseEngine,
            FrostAction.Skip("screenshot failed for COVER")), actions)
        assertEquals(FrostPhase.IDLE, plan.phase)
    }
}
