package cz.pflanzer.foldduo.systemfrost

/** Which pane the B15 continuity bridge covers: the inner's right half after an opening swap
 *  (pane identity: the cover maps onto it, see [BridgeFraming]), or the whole cover after a
 *  closing swap. */
enum class BridgeTarget { INNER_RIGHT, COVER }

/**
 * Pure state machine for B15 "Most kontinuity při swapu" (STATUS.md, IDEAS.md): right after a
 * panel swap, the newly-foregrounded app is invisible/relayouting for a beat. This bridges it by
 * holding the *other* panel's last screenshot over the fresh panel's matching pane until the app
 * draws its first frame there (an accessibility content/windows-changed event for its package) or
 * [CAP_MS] elapses, whichever first, then fades it out over [FADE_MS].
 *
 * Deliberately independent of [SystemFrostPlan]'s own phase/timing: a closing episode releases
 * the Pose Engine (stopping [SystemFrostPlan.onTick]'s poll) at the exact moment the closing
 * bridge should start, and the bridge's whole life (<= [CAP_MS] + [FADE_MS] = 850 ms) is far
 * shorter than an inner clear can run anyway. [SystemFrost] drives this with its own
 * Handler-based timers, not the shared tick loop.
 */
class ContinuityBridgePlan(
    private val capMs: Long = CAP_MS,
    private val fadeMs: Long = FADE_MS,
) {
    private enum class Stage { SHOWING, FADING }
    private data class Bridge(val target: BridgeTarget, val stage: Stage)

    private var bridge: Bridge? = null

    /** The panel just swapped to [target]'s panel. Skipped when our own launcher is in front:
     *  it has pane identity of its own and needs no bridge. */
    fun onSwap(target: BridgeTarget, launcherForeground: Boolean): List<FrostAction> {
        if (launcherForeground) return emptyList()
        bridge = Bridge(target, Stage.SHOWING)
        return listOf(FrostAction.ShowBridge(target))
    }

    /** The foreground app (a non-launcher package) drew: a `TYPE_WINDOW_CONTENT_CHANGED` or
     *  `TYPE_WINDOWS_CHANGED` accessibility event arrived while the bridge is still showing. */
    fun onForegroundAppDrew(): List<FrostAction> {
        val b = bridge ?: return emptyList()
        if (b.stage != Stage.SHOWING) return emptyList()
        bridge = b.copy(stage = Stage.FADING)
        return listOf(FrostAction.FadeOutBridge(b.target))
    }

    /** [capMs] elapsed since [onSwap] with no draw event yet. */
    fun onCapElapsed(target: BridgeTarget): List<FrostAction> {
        val b = bridge ?: return emptyList()
        if (b.target != target || b.stage != Stage.SHOWING) return emptyList()
        bridge = b.copy(stage = Stage.FADING)
        return listOf(FrostAction.FadeOutBridge(target))
    }

    /** [fadeMs] elapsed since the fade started: the bridge window comes down. */
    fun onFadeElapsed(target: BridgeTarget): List<FrostAction> {
        val b = bridge ?: return emptyList()
        if (b.target != target || b.stage != Stage.FADING) return emptyList()
        bridge = null
        return listOf(FrostAction.HideBridge(target))
    }

    companion object {
        const val CAP_MS = 700L
        const val FADE_MS = 150L
    }
}
