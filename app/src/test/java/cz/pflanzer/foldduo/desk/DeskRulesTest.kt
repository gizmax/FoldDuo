package cz.pflanzer.foldduo.desk

import cz.pflanzer.foldduo.pose.FoldPose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskRulesTest {
    // --- enter: pose hold ---

    @Test fun standHeldPastThresholdEnters() =
        assertTrue(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Desk, DeskRules.HOLD_MS))

    @Test fun standHeldPastThresholdEntersStandByFaceToo() =
        assertTrue(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.StandByFace, DeskRules.HOLD_MS))

    @Test fun standJustShortOfThresholdDoesNotEnter() =
        assertFalse(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Desk, DeskRules.HOLD_MS - 1))

    @Test fun zeroHoldDoesNotEnter() =
        assertFalse(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Desk, 0L))

    @Test fun longerThanThresholdStillEnters() =
        assertTrue(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Desk, DeskRules.HOLD_MS * 10))

    // --- enter: setting variants ---

    @Test fun offNeverEntersRegardlessOfHold() =
        assertFalse(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Off, DeskRules.HOLD_MS * 10))

    // --- enter: pose gating ---

    @Test fun otherPosesNeverEnterEvenHeldLong() {
        for (pose in FoldPose.entries.filter { it != FoldPose.Stand }) {
            assertFalse("$pose should not enter desk mode", DeskRules.shouldEnter(pose, DeskMode.Desk, DeskRules.HOLD_MS * 10))
        }
    }

    // --- exit ---

    @Test fun leavingStandExits() =
        assertTrue(DeskRules.shouldExit(FoldPose.Open, DeskMode.Desk, closeTapped = false))

    @Test fun stayingInStandDoesNotExit() =
        assertFalse(DeskRules.shouldExit(FoldPose.Stand, DeskMode.Desk, closeTapped = false))

    @Test fun tapCloseAlwaysExitsEvenStillStanding() =
        assertTrue(DeskRules.shouldExit(FoldPose.Stand, DeskMode.Desk, closeTapped = true))

    @Test fun turningModeOffWhileShowingExits() =
        assertTrue(DeskRules.shouldExit(FoldPose.Stand, DeskMode.Off, closeTapped = false))

    @Test fun `stand with the hinge flat is a phone in the hand, not a desk`() {
        assertFalse(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Desk, DeskRules.HOLD_MS, hingeMid = false))
        assertTrue(DeskRules.shouldEnter(FoldPose.Stand, DeskMode.Desk, DeskRules.HOLD_MS, hingeMid = true))
        assertTrue(DeskRules.shouldExit(FoldPose.Stand, DeskMode.Desk, closeTapped = false, hingeMid = false))
    }
}
