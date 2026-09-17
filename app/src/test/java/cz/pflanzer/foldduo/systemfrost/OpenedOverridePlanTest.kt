package cz.pflanzer.foldduo.systemfrost

import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenedOverridePlanTest {

    @Test fun `hinge step Closed to Mid on the cover requests OPENED when the setting is on`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        val actions = plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        assertEquals(listOf(FrostAction.RequestOpenedOverride), actions)
    }

    @Test fun `nothing happens with the setting off`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = false)
        val actions = plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = false)
        assertEquals(emptyList<FrostAction>(), actions)
    }

    @Test fun `nothing happens with the keyguard locked`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = true, settingOn = true)
        val actions = plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = true, settingOn = true)
        assertEquals(emptyList<FrostAction>(), actions)
    }

    @Test fun `not gated on the launcher being foreground, unlike the frost episodes`() {
        // OpenedOverridePlan.onHingeStep takes no launcherForeground parameter at all: the early
        // inner is wanted even when our own launcher is what is being opened onto.
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        val actions = plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        assertEquals(listOf(FrostAction.RequestOpenedOverride), actions)
    }

    // ---- rule (a): hinge back at 0 ----

    @Test fun `rule a - hinge falling back to Closed cancels`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        val actions = plan.onHingeStep(20, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        assertEquals(listOf(FrostAction.CancelOpenedOverride("hinge back at 0")), actions)
    }

    // ---- rule (b): continuous angle >= 100, or hinge step 180 ----

    @Test fun `rule b - hinge step Flat cancels`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        val actions = plan.onHingeStep(500, Panel.Inner, HingeStep.Flat, keyguardLocked = false, settingOn = true)
        assertEquals(listOf(FrostAction.CancelOpenedOverride("hinge step 180")), actions)
    }

    @Test fun `rule b - continuous angle at or above 100 cancels`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        assertEquals(emptyList<FrostAction>(), plan.onAngleTick(80f))
        val actions = plan.onAngleTick(100f)
        assertEquals(listOf(FrostAction.CancelOpenedOverride("angle >= ${OpenedOverridePlan.ANGLE_CANCEL_DEG}")), actions)
    }

    @Test fun `an angle tick before any request is a no-op`() {
        val plan = OpenedOverridePlan()
        assertEquals(emptyList<FrostAction>(), plan.onAngleTick(150f))
    }

    // ---- rule (c) and (d): external cancellation ----

    @Test fun `cancelExternally cancels an active request and is a no-op otherwise`() {
        val plan = OpenedOverridePlan()
        assertEquals(emptyList<FrostAction>(), plan.cancelExternally("service destroyed"))
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        val actions = plan.cancelExternally("10s safety timeout")
        assertEquals(listOf(FrostAction.CancelOpenedOverride("10s safety timeout")), actions)
        // Already idle: a second cancel is a no-op.
        assertEquals(emptyList<FrostAction>(), plan.cancelExternally("10s safety timeout"))
    }

    // ---- failure disables further attempts for the process ----

    @Test fun `a failed request disables all further attempts`() {
        val plan = OpenedOverridePlan()
        assertFalse(plan.isDisabledForProcess)
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        plan.onRequestFailed()
        assertTrue(plan.isDisabledForProcess)

        // A fresh opening trigger no longer requests anything.
        plan.onHingeStep(100, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        val actions = plan.onHingeStep(110, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        assertEquals(emptyList<FrostAction>(), actions)
    }

    @Test fun `a new trigger after a settled cancel requests again`() {
        val plan = OpenedOverridePlan()
        plan.onHingeStep(0, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(10, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        plan.onHingeStep(20, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true) // rule (a)

        plan.onHingeStep(200, Panel.Cover, HingeStep.Closed, keyguardLocked = false, settingOn = true)
        val actions = plan.onHingeStep(210, Panel.Cover, HingeStep.Mid, keyguardLocked = false, settingOn = true)
        assertEquals(listOf(FrostAction.RequestOpenedOverride), actions)
    }
}
