package cz.pflanzer.foldduo.systemfrost

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuityBridgePlanTest {

    @Test fun `a swap while another app is in front shows the bridge`() {
        val plan = ContinuityBridgePlan()
        val actions = plan.onSwap(BridgeTarget.INNER_RIGHT, launcherForeground = false)
        assertEquals(listOf(FrostAction.ShowBridge(BridgeTarget.INNER_RIGHT)), actions)
    }

    @Test fun `never shown when our own launcher is the foreground app`() {
        val plan = ContinuityBridgePlan()
        val opening = plan.onSwap(BridgeTarget.INNER_RIGHT, launcherForeground = true)
        assertEquals(emptyList<FrostAction>(), opening)
        val closing = plan.onSwap(BridgeTarget.COVER, launcherForeground = true)
        assertEquals(emptyList<FrostAction>(), closing)
    }

    @Test fun `a draw event while showing fades it out`() {
        val plan = ContinuityBridgePlan()
        plan.onSwap(BridgeTarget.INNER_RIGHT, launcherForeground = false)
        val actions = plan.onForegroundAppDrew()
        assertEquals(listOf(FrostAction.FadeOutBridge(BridgeTarget.INNER_RIGHT)), actions)
    }

    @Test fun `the fade completing hides the bridge`() {
        val plan = ContinuityBridgePlan()
        plan.onSwap(BridgeTarget.INNER_RIGHT, launcherForeground = false)
        plan.onForegroundAppDrew()
        val actions = plan.onFadeElapsed(BridgeTarget.INNER_RIGHT)
        assertEquals(listOf(FrostAction.HideBridge(BridgeTarget.INNER_RIGHT)), actions)
    }

    @Test fun `the cap elapsing without a draw event fades it out the same way`() {
        val plan = ContinuityBridgePlan()
        plan.onSwap(BridgeTarget.COVER, launcherForeground = false)
        val actions = plan.onCapElapsed(BridgeTarget.COVER)
        assertEquals(listOf(FrostAction.FadeOutBridge(BridgeTarget.COVER)), actions)
    }

    @Test fun `a draw event after the cap already started fading is a no-op`() {
        val plan = ContinuityBridgePlan()
        plan.onSwap(BridgeTarget.COVER, launcherForeground = false)
        plan.onCapElapsed(BridgeTarget.COVER)
        val actions = plan.onForegroundAppDrew()
        assertEquals(emptyList<FrostAction>(), actions)
    }

    @Test fun `a cap for the wrong target (a fresh swap already replaced it) is a no-op`() {
        val plan = ContinuityBridgePlan()
        plan.onSwap(BridgeTarget.INNER_RIGHT, launcherForeground = false)
        // The phone closed again before the opening bridge's cap fired: a new bridge for COVER
        // replaces the old state entirely.
        plan.onSwap(BridgeTarget.COVER, launcherForeground = false)
        val staleCap = plan.onCapElapsed(BridgeTarget.INNER_RIGHT)
        assertEquals(emptyList<FrostAction>(), staleCap)
        // The still-live COVER bridge is unaffected.
        val actions = plan.onCapElapsed(BridgeTarget.COVER)
        assertEquals(listOf(FrostAction.FadeOutBridge(BridgeTarget.COVER)), actions)
    }

    @Test fun `no bridge ever shown means every later event is a no-op`() {
        val plan = ContinuityBridgePlan()
        assertEquals(emptyList<FrostAction>(), plan.onForegroundAppDrew())
        assertEquals(emptyList<FrostAction>(), plan.onCapElapsed(BridgeTarget.INNER_RIGHT))
        assertEquals(emptyList<FrostAction>(), plan.onFadeElapsed(BridgeTarget.INNER_RIGHT))
    }

    @Test fun `a fade-elapsed callback for a still-showing (not yet fading) bridge is a no-op`() {
        val plan = ContinuityBridgePlan()
        plan.onSwap(BridgeTarget.INNER_RIGHT, launcherForeground = false)
        // Wrong stage: nothing started the fade yet.
        assertEquals(emptyList<FrostAction>(), plan.onFadeElapsed(BridgeTarget.INNER_RIGHT))
    }
}
