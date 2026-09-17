package cz.pflanzer.foldduo.standby

import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandByRulesTest {
    private fun inputs(pose: FoldPose, stillMs: Long = 5_000, coverOn: Boolean = true,
        foreground: Foreground = Foreground.Launcher, overlay: Boolean = false, closedOnTable: Boolean = true,
        showing: Boolean = false, sinceExit: Long = Long.MAX_VALUE, panel: Panel = Panel.Cover,
        hingeRate: Float = 0f, sinceClosing: Long = Long.MAX_VALUE) =
        StandByInputs(pose, stillMs, coverOn, foreground, overlay, closedOnTable, showing, sinceExit,
            panel = panel, hingeRateRadS = hingeRate, msSinceClosingMotion = sinceClosing)

    // --- start: decision table ---

    @Test fun tentStartsOverTheLauncher() = assertTrue(StandByRules.shouldStart(inputs(FoldPose.Tent)))

    @Test fun tentStartsOverNothingOnlyWithOverlayPermission() {
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Tent, foreground = Foreground.Nothing, overlay = true)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, foreground = Foreground.Nothing, overlay = false)))
    }

    @Test fun tentNeverCoversAnotherApp() =
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, foreground = Foreground.Other, overlay = true)))

    @Test fun tentIgnoresCoverAndClosedSetting() =
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Tent, coverOn = false, closedOnTable = false)))

    // 17. 9. device report: StandBy came up on the inner panel while the phone was being closed
    // in the hand. Tent is a phone stood on a table, seen from outside: cover panel, resting,
    // hinge still, nothing closing just now.
    @Test fun tentNeverStartsOnTheInnerPanel() {
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, panel = Panel.Inner)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, panel = Panel.Unknown)))
    }

    @Test fun tentNeedsItsOwnRest() {
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, stillMs = StandByRules.TENT_STILL_MS - 1)))
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Tent, stillMs = StandByRules.TENT_STILL_MS)))
    }

    @Test fun tentNeedsAStillHinge() {
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, hingeRate = 0.6f)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, hingeRate = -0.6f)))
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Tent, hingeRate = 0.1f)))
    }

    @Test fun tentWaitsAfterAClosingMotion() {
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, sinceClosing = 1_000)))
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Tent, sinceClosing = StandByRules.TENT_AFTER_CLOSING_MS)))
    }

    @Test fun closedOnTableStartsAfterFourSecondsStill() {
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Closed, stillMs = 4_000)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Closed, stillMs = 3_999)))
    }

    @Test fun closedNeedsCoverOnAndTheSetting() {
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Closed, coverOn = false)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Closed, closedOnTable = false)))
    }

    @Test fun closedOverKeyguardNeedsOverlay() {
        assertTrue(StandByRules.shouldStart(inputs(FoldPose.Closed, foreground = Foreground.Nothing, overlay = true)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Closed, foreground = Foreground.Nothing, overlay = false)))
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Closed, foreground = Foreground.Other, overlay = true)))
    }

    @Test fun otherPosesNeverStart() {
        for (pose in listOf(FoldPose.Open, FoldPose.Stand, FoldPose.Flip, FoldPose.InMotion))
            assertFalse(pose.name, StandByRules.shouldStart(inputs(pose, overlay = true)))
    }

    @Test fun alreadyShowingNeverStartsAgain() =
        assertFalse(StandByRules.shouldStart(inputs(FoldPose.Tent, showing = true)))

    @Test fun gestureExitDebounce() {
        assertFalse("5 s after exit", StandByRules.shouldStart(inputs(FoldPose.Tent, sinceExit = 5_000, stillMs = 1_000)))
        assertFalse("15 s after exit, not moved since", StandByRules.shouldStart(inputs(FoldPose.Tent, sinceExit = 15_000, stillMs = 20_000)))
        assertTrue("15 s after exit, moved since", StandByRules.shouldStart(inputs(FoldPose.Tent, sinceExit = 15_000, stillMs = 5_000)))
        assertTrue("61 s after exit, never moved", StandByRules.shouldStart(inputs(FoldPose.Tent, sinceExit = 61_000, stillMs = 90_000)))
        assertTrue("closed, moved after the exit and rested 4 s",
            StandByRules.shouldStart(inputs(FoldPose.Closed, sinceExit = 30_000, stillMs = 4_500)))
    }

    // --- exit ---

    @Test fun innerPanelEndsStandBy() = assertTrue(StandByRules.shouldExit(FoldPose.Tent, Panel.Inner, 10_000))

    @Test fun tentStaysWhileTent() = assertFalse(StandByRules.shouldExit(FoldPose.Tent, Panel.Cover, 0))

    @Test fun closedStaysUntilPickedUp() {
        assertFalse(StandByRules.shouldExit(FoldPose.Closed, Panel.Cover, 5_000))
        assertTrue(StandByRules.shouldExit(FoldPose.Closed, Panel.Cover, 500))
    }

    @Test fun leavingTentOrClosedEnds() {
        for (pose in listOf(FoldPose.Open, FoldPose.Stand, FoldPose.Flip, FoldPose.InMotion))
            assertTrue(pose.name, StandByRules.shouldExit(pose, Panel.Cover, 10_000))
    }

    @Test fun previewIgnoresPoseAndPanel() {
        assertFalse(StandByRules.shouldExit(FoldPose.Open, Panel.Inner, 0, preview = true))
        assertFalse(StandByRules.shouldExit(FoldPose.InMotion, Panel.Cover, 0, preview = true))
    }
}

class GlowAlarmTest {
    private val alarm = 1_000_000_000L

    @Test fun noAlarmIsBlack() {
        assertEquals(0f, GlowAlarm.progress(alarm, null), 0f)
        assertEquals(GlowAlarm.BLACK_ARGB, GlowAlarm.argb(GlowAlarm.progress(alarm, null)))
    }

    @Test fun darkBeforeTheWindow() {
        assertEquals(0f, GlowAlarm.progress(alarm - GlowAlarm.WINDOW_MS - 1, alarm), 0f)
        assertEquals(0f, GlowAlarm.progress(alarm - GlowAlarm.WINDOW_MS, alarm), 0f)
    }

    @Test fun rampIsMonotonicAndEasesIn() {
        var last = -1f
        for (step in 0..20) {
            val now = alarm - GlowAlarm.WINDOW_MS + GlowAlarm.WINDOW_MS * step / 20
            val p = GlowAlarm.progress(now, alarm)
            assertTrue("step $step: $p >= $last", p >= last)
            last = p
        }
        assertEquals(.25f, GlowAlarm.progress(alarm - GlowAlarm.WINDOW_MS / 2, alarm), 1e-6f)
        assertEquals(1f, GlowAlarm.progress(alarm, alarm), 0f)
    }

    @Test fun holdsAfterTheAlarmThenReleases() {
        assertEquals(1f, GlowAlarm.progress(alarm + 60_000, alarm), 0f)
        assertEquals(0f, GlowAlarm.progress(alarm + GlowAlarm.HOLD_MS, alarm), 0f)
    }

    @Test fun colorEndsAreExactAndChannelsRise() {
        assertEquals(GlowAlarm.BLACK_ARGB, GlowAlarm.argb(0f))
        assertEquals(GlowAlarm.WARM_ARGB, GlowAlarm.argb(1f))
        val mid = GlowAlarm.argb(.5f)
        assertEquals(0xFF, mid ushr 24)
        assertEquals(0x6D, (mid shr 16) and 0xFF)
        assertEquals(0x45, (mid shr 8) and 0xFF)
        assertEquals(0x1F, mid and 0xFF)
        assertEquals(GlowAlarm.WARM_ARGB, GlowAlarm.argb(7f))
        assertEquals(GlowAlarm.BLACK_ARGB, GlowAlarm.argb(-1f))
    }
}

class FaceOrderTest {
    @Test fun roundTrip() {
        val order = listOf(StandByFace.Photos, StandByFace.Clock)
        assertEquals("Photos,Clock", FaceOrder.encode(order))
        assertEquals(order, FaceOrder.decode(FaceOrder.encode(order)))
    }

    @Test fun missingOrEmptyIsDefault() {
        assertEquals(FaceOrder.DEFAULT, FaceOrder.decode(null))
        assertEquals(FaceOrder.DEFAULT, FaceOrder.decode(""))
        assertEquals(FaceOrder.DEFAULT, FaceOrder.decode("Weather, ,Music"))
    }

    @Test fun tolerantDecode() {
        assertEquals(listOf(StandByFace.Calendar, StandByFace.Clock),
            FaceOrder.decode(" calendar ,Clock,Nope,Calendar"))
    }

    @Test fun moveUpAndToggle() {
        val all = FaceOrder.DEFAULT
        assertEquals(listOf(StandByFace.Calendar, StandByFace.Clock, StandByFace.Photos), FaceOrder.moveUp(all, StandByFace.Calendar))
        assertEquals(all, FaceOrder.moveUp(all, StandByFace.Clock))
        assertEquals(listOf(StandByFace.Clock, StandByFace.Photos), FaceOrder.toggle(all, StandByFace.Calendar, shown = false))
        assertEquals(listOf(StandByFace.Clock), FaceOrder.toggle(listOf(StandByFace.Clock), StandByFace.Clock, shown = false))
        assertEquals(listOf(StandByFace.Clock, StandByFace.Photos), FaceOrder.toggle(listOf(StandByFace.Clock), StandByFace.Photos, shown = true))
    }

    // --- StandByExitReason (17. 9. night device report: onStop() finishing StandBy on its own) ---

    @Test fun hiddenExitReasonIsKeyguardWhenLocked() {
        assertEquals(StandByExitReason.HiddenByKeyguard, hiddenExitReason(keyguardLocked = true))
        assertEquals(StandByExitReason.HiddenOther, hiddenExitReason(keyguardLocked = false))
    }

    @Test fun onlyTheKeyguardHiddenReasonSkipsTheGestureDebounce() {
        // A keyguard re-assertion while StandBy hasn't moved is not a user dismissal — it should
        // relaunch as soon as pose says so again, not sit through StandByRules.RELAUNCH_ANYWAY_MS.
        assertFalse(StandByExitReason.HiddenByKeyguard.countsAsGestureExit)
        assertTrue(StandByExitReason.HiddenOther.countsAsGestureExit)
        assertTrue(StandByExitReason.PoseOrPanel.countsAsGestureExit)
        assertTrue(StandByExitReason.GestureDoubleTap.countsAsGestureExit)
        assertTrue(StandByExitReason.GestureSwipeUp.countsAsGestureExit)
        assertTrue(StandByExitReason.GestureBack.countsAsGestureExit)
    }
}
