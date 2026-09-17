package cz.pflanzer.foldduo.systemfrost

import cz.pflanzer.foldduo.CloseFrostHold
import cz.pflanzer.foldduo.MorphCurve
import cz.pflanzer.foldduo.TiltPacing
import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.Panel

/**
 * Which overlay window a [FrostAction] concerns: the full-cover frost while opening, or the
 * left-half inner frost while opening (clearing) / closing (frosting up).
 */
enum class OverlayTarget { COVER, INNER }

/** Where [SystemFrostPlan] currently is; see the file comment for the transition diagram. */
enum class FrostPhase {
    /** No overlay attached; the only thing running is the cheap hinge-step listener. */
    IDLE,

    /** Opening, cover side: the cover is screenshot + frosted, waiting for the panel swap. */
    COVER_FROST,

    /** Opening, inner side: the swap happened, the inner left half is screenshot + clearing. */
    INNER_CLEARING,

    /** Closing, inner side: the fold started, the inner left half is screenshot + frosting up. */
    INNER_FROST_CLOSING,
}

/** One thing the Android side (SystemFrost.kt) must do in response to a plan event. */
sealed interface FrostAction {
    data class RequestScreenshot(val target: OverlayTarget) : FrostAction
    data class AttachOverlay(val target: OverlayTarget, val tiltDeg: Float) : FrostAction
    data class UpdateOverlay(val target: OverlayTarget, val tiltDeg: Float) : FrostAction
    data class RemoveOverlay(val target: OverlayTarget, val reason: String) : FrostAction
    object AcquirePoseEngine : FrostAction
    object ReleasePoseEngine : FrostAction
    data class Skip(val reason: String) : FrostAction

    // ---- B14: DeviceStateManager OPENED override (see OpenedOverridePlan) ----
    /** Ask [cz.pflanzer.foldduo.systemfrost.DeviceStateOverride] to request OPENED. */
    object RequestOpenedOverride : FrostAction
    /** Cancel that request; [reason] is logged (`hinge back at 0`, `angle >= 100`, `hinge step 180`, `service destroyed`). */
    data class CancelOpenedOverride(val reason: String) : FrostAction

    // ---- B15: continuity bridge over a panel swap (see ContinuityBridgePlan) ----
    /** Show the retained screenshot of the other panel over [target]'s pane, freshly attached. */
    data class ShowBridge(val target: BridgeTarget) : FrostAction
    /** Start the 150 ms fade-out (the new app drew, or the 700 ms cap elapsed). */
    data class FadeOutBridge(val target: BridgeTarget) : FrostAction
    /** The fade finished: tear the bridge window down. */
    data class HideBridge(val target: BridgeTarget) : FrostAction
}

/**
 * Pure state machine for "Mlha nad cizími aplikacemi" (STATUS.md, task from 2026-09-15): the
 * same Continuum frost the launcher plays on its own Home, driven instead by the accessibility
 * service over whatever app is in the foreground. No Android types here (testable as a plain
 * JVM unit); [SystemFrost] is the framework-facing half that owns the sensors, the screenshot
 * calls and the overlay windows and turns their events into calls on this class.
 *
 * Transitions:
 *  - `IDLE` -[hinge step Closed -> non-Closed, panel Cover]-> `COVER_FROST` (opening starts).
 *  - `COVER_FROST` -[panel swaps to Inner]-> `INNER_CLEARING` (opening continues on the inner side).
 *  - `COVER_FROST` -[no swap within the timed hold, angle NaN]-> `IDLE` (false positive, released).
 *  - `INNER_CLEARING` -[angle reaches FLAT_HINGE, or the NaN-fallback timeout]-> `IDLE` (done).
 *  - `IDLE` -[hinge step Flat -> non-Flat, panel Inner]-> `INNER_FROST_CLOSING` (closing starts).
 *  - `INNER_FROST_CLOSING` -[panel swaps to Cover, or the hinge is back at Flat and stays
 *    ([CloseFrostHold]), or the safety-net timeout]-> `IDLE` (closing ends; nothing shown on
 *    the cover afterwards).
 *
 * A new episode is gated at the trigger only (item 5 of the task): the launcher must not be the
 * foreground app and the keyguard must not be locked. Once an episode is running it is never
 * cancelled by those checks changing (there is nothing sensible to fall back to mid-frost); it
 * still ends normally through the transitions above.
 */
class SystemFrostPlan(
    private val innerClearTimeoutMs: Long = INNER_CLEAR_TIMEOUT_MS,
    private val closeHoldFactory: (startedAtMs: Long) -> CloseFrostHold = { CloseFrostHold(it) },
) {
    var phase: FrostPhase = FrostPhase.IDLE
        private set

    private var previousStep: HingeStep? = null
    private var coverTriggerMs = 0L
    private var innerAttachedMs = 0L
    private var closeHold: CloseFrostHold? = null

    // ---- "Pacing morphu" (17. 9. 2026): same TiltPacing as the launcher's own morph
    // (UnfoldMorph.kt), driving the tilt actually sent in UpdateOverlay instead of the raw
    // angle mapping. No B18 duration-scale plumbing here: this tick loop runs on real elapsed
    // time (SystemClock.elapsedRealtime ticks, not a Compose tween), so it is unaffected by the
    // animator duration scale to begin with.

    /** INNER_CLEARING's guaranteed-minimum floor: armed at the inner attach ([onScreenshotResult]) with [INNER_ATTACH_HOLD_MS] as the delay and [MorphCurve.DURATION_MS] as the clear, replacing what used to be a separate hard-coded hold check. */
    private val innerClearPacing = TiltPacing()
    /** Shared rise-slew state for the inner overlay (INNER_CLEARING's [innerClearPacing.pace] and INNER_FROST_CLOSING's plain [TiltPacing.riseLimited]); the two phases never run at once. Reset at every fresh attach. */
    private var lastInnerDisplayed = 0f
    private var lastInnerTickMs = Long.MIN_VALUE
    /** Rise-slew state for the cover overlay (COVER_FROST's angle-driven branch); reset at every episode start. */
    private var lastCoverDisplayed = 0f
    private var lastCoverTickMs = Long.MIN_VALUE

    /**
     * A new hinge-step reading arrived (from the always-on `TYPE_HINGE_ANGLE` listener, mapped
     * through [HingeStep.of]). [panel] is the currently active panel. Detects the opening and
     * closing triggers from [FrostPhase.IDLE]; while [FrostPhase.INNER_FROST_CLOSING] is running,
     * also drives its hinge-based release ([CloseFrostHold]).
     */
    fun onHingeStep(
        nowMs: Long,
        panel: Panel,
        step: HingeStep?,
        launcherForeground: Boolean,
        keyguardLocked: Boolean,
    ): List<FrostAction> {
        val before = previousStep
        previousStep = step
        return when (phase) {
            FrostPhase.IDLE -> {
                val opening = panel == Panel.Cover && before == HingeStep.Closed && step != null && step != HingeStep.Closed
                val closing = panel == Panel.Inner && before == HingeStep.Flat && step != null && step != HingeStep.Flat
                when {
                    opening -> startEpisode(nowMs, FrostPhase.COVER_FROST, OverlayTarget.COVER, launcherForeground, keyguardLocked)
                    closing -> startEpisode(nowMs, FrostPhase.INNER_FROST_CLOSING, OverlayTarget.INNER, launcherForeground, keyguardLocked)
                    else -> emptyList()
                }
            }
            FrostPhase.INNER_FROST_CLOSING -> {
                val hold = closeHold
                val release = hold?.release(panel, step, nowMs)
                if (release != null) endEpisode(OverlayTarget.INNER, release) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun startEpisode(
        nowMs: Long,
        nextPhase: FrostPhase,
        target: OverlayTarget,
        launcherForeground: Boolean,
        keyguardLocked: Boolean,
    ): List<FrostAction> {
        if (launcherForeground) return listOf(FrostAction.Skip("launcher foreground"))
        if (keyguardLocked) return listOf(FrostAction.Skip("keyguard locked"))
        phase = nextPhase
        innerAttachedMs = 0L
        when (nextPhase) {
            FrostPhase.COVER_FROST -> {
                coverTriggerMs = nowMs
                lastCoverDisplayed = 0f
                lastCoverTickMs = nowMs
            }
            FrostPhase.INNER_FROST_CLOSING -> closeHold = closeHoldFactory(nowMs)
            else -> Unit
        }
        return listOf(FrostAction.AcquirePoseEngine, FrostAction.RequestScreenshot(target))
    }

    /** The panel behind display 0 changed (from the Pose Engine snapshot, or a display listener). */
    fun onPanelChanged(nowMs: Long, panel: Panel): List<FrostAction> = when (phase) {
        FrostPhase.COVER_FROST -> if (panel == Panel.Inner) {
            phase = FrostPhase.INNER_CLEARING
            innerAttachedMs = 0L
            listOf(FrostAction.RemoveOverlay(OverlayTarget.COVER, "panel swapped to inner"))
        } else emptyList()
        FrostPhase.INNER_FROST_CLOSING -> if (panel == Panel.Cover) {
            endEpisode(OverlayTarget.INNER, "panel swapped to cover")
        } else emptyList()
        else -> emptyList()
    }

    /** The inner display is confirmed lit (`Display.STATE_ON`); the shot before this is black. */
    fun onDisplayOn(nowMs: Long): List<FrostAction> =
        if (phase == FrostPhase.INNER_CLEARING && innerAttachedMs == 0L) listOf(FrostAction.RequestScreenshot(OverlayTarget.INNER))
        else emptyList()

    /**
     * The screenshot for [target] finished. A failure (secure window, error code) skips silently
     * and ends whatever episode requested it — nothing to show, no overlay stays behind.
     */
    fun onScreenshotResult(nowMs: Long, target: OverlayTarget, success: Boolean): List<FrostAction> {
        if (phase == FrostPhase.IDLE) return emptyList() // stale callback from an episode already ended
        if (!success) return endEpisode(target, "screenshot failed") + listOf(FrostAction.Skip("screenshot failed for $target"))
        return when {
            // The cover attaches sharp (tilt 0) and frosts up over the following ticks, whether
            // driven by the angle or the timed fallback: it mirrors the launcher's own cover
            // frost, which never snaps straight to full.
            target == OverlayTarget.COVER && phase == FrostPhase.COVER_FROST ->
                listOf(FrostAction.AttachOverlay(OverlayTarget.COVER, 0f))
            // The inner left half attaches already fully frosted (item 3): the screenshot is
            // taken the instant the panel lights up, at ~90 deg, where the launcher's own morph
            // is fully frosted too.
            target == OverlayTarget.INNER && (phase == FrostPhase.INNER_CLEARING || phase == FrostPhase.INNER_FROST_CLOSING) -> {
                innerAttachedMs = nowMs
                // "Pacing morphu": reset the shared rise-slew baseline to the attach's own full
                // tilt, and (INNER_CLEARING only) arm the guaranteed-minimum floor — the old
                // INNER_ATTACH_HOLD_MS hard hold is now just the floor's own delay.
                lastInnerDisplayed = MorphCurve.MAX_TILT
                lastInnerTickMs = nowMs
                if (phase == FrostPhase.INNER_CLEARING) {
                    innerClearPacing.armRelease(nowMs, INNER_ATTACH_HOLD_MS, MorphCurve.DURATION_MS, MorphCurve.MAX_TILT)
                }
                listOf(FrostAction.AttachOverlay(OverlayTarget.INNER, MorphCurve.MAX_TILT))
            }
            else -> emptyList()
        }
    }

    /**
     * Periodic poll (~[cz.pflanzer.foldduo.MorphCurve.CLOSE_FROST_HOLD_POLL_MS]) while an episode
     * runs, carrying the latest continuous hinge angle ([angleDeg], NaN when the estimator has
     * none). Angle mode drives the tilt directly through [MorphCurve.angleTilt]; NaN falls back
     * to the timed cover schedule ([MorphCurve.coverFrostFraction]) for the cover, and to a plain
     * timeout for the inner clear (there is no timer that reliably tracks an unknown clear).
     */
    fun onTick(nowMs: Long, angleDeg: Float): List<FrostAction> = when (phase) {
        FrostPhase.COVER_FROST -> {
            if (angleDeg.isNaN()) {
                val elapsed = nowMs - coverTriggerMs
                val fraction = MorphCurve.coverFrostFraction(elapsed)
                val tilt = MorphCurve.coverFrostTilt(fraction)
                if (elapsed > MorphCurve.coverFrostReleaseAtMs + MorphCurve.COVER_FROST_OUT_MS) {
                    endEpisode(OverlayTarget.COVER, "timed out without a swap")
                } else listOf(FrostAction.UpdateOverlay(OverlayTarget.COVER, tilt))
            } else {
                // "Pacing morphu": a plain rise slew (no floor — the cover only ever frosts up
                // under angle mode, it never needs a guaranteed-minimum clear).
                val dtMs = (nowMs - lastCoverTickMs).coerceIn(0L, 1_000L)
                lastCoverTickMs = nowMs
                val paced = TiltPacing.riseLimited(lastCoverDisplayed, MorphCurve.angleTilt(Panel.Cover, angleDeg), dtMs, MorphCurve.RISE_FULL_MS)
                lastCoverDisplayed = paced
                listOf(FrostAction.UpdateOverlay(OverlayTarget.COVER, paced))
            }
        }
        FrostPhase.INNER_CLEARING -> {
            if (innerAttachedMs == 0L) emptyList() // no screenshot attached yet, nothing to update
            else if (angleDeg.isNaN()) {
                // No angle at all: hold fully frosted, same as always — nothing paces a clear
                // with no hand signal to pace against; the timeout below is the only way out.
                if (nowMs - innerAttachedMs >= innerClearTimeoutMs) endEpisode(OverlayTarget.INNER, "timeout")
                else listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, MorphCurve.MAX_TILT))
            } else if (angleDeg >= MorphCurve.ANGLE_FLAT) {
                endEpisode(OverlayTarget.INNER, "flat")
            } else if (nowMs - innerAttachedMs >= innerClearTimeoutMs) {
                endEpisode(OverlayTarget.INNER, "timeout")
            } else {
                // "Pacing morphu": the guaranteed-minimum floor (armed at the attach, above) is
                // what used to be the hard INNER_ATTACH_HOLD_MS gate plus B15's own comment about
                // never clearing under One UI's screen-on fade — same guarantee, now also
                // covering a too-fast or wrong-at-first angle estimate for the whole 750 ms clear.
                val dtMs = (nowMs - lastInnerTickMs).coerceIn(0L, 1_000L)
                lastInnerTickMs = nowMs
                val paced = innerClearPacing.pace(nowMs, lastInnerDisplayed, MorphCurve.angleTilt(Panel.Inner, angleDeg), dtMs, MorphCurve.RISE_FULL_MS)
                lastInnerDisplayed = paced
                listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, paced))
            }
        }
        FrostPhase.INNER_FROST_CLOSING -> {
            if (innerAttachedMs == 0L) emptyList()
            else {
                // "Pacing morphu": a plain rise slew, no floor (the frost-up has no guaranteed
                // minimum to hit — only a snap must never read as a pop). "Zavírání jako Duo"
                // item 2/5: the closing-specific curve (full by 100 deg, well before the swap),
                // matching the launcher's own left-half frost.
                val dtMs = (nowMs - lastInnerTickMs).coerceIn(0L, 1_000L)
                lastInnerTickMs = nowMs
                val target = if (angleDeg.isNaN()) MorphCurve.MAX_TILT else MorphCurve.closingAngleTilt(angleDeg)
                val paced = TiltPacing.riseLimited(lastInnerDisplayed, target, dtMs, MorphCurve.RISE_FULL_MS)
                lastInnerDisplayed = paced
                listOf(FrostAction.UpdateOverlay(OverlayTarget.INNER, paced))
            }
        }
        FrostPhase.IDLE -> emptyList()
    }

    private fun endEpisode(target: OverlayTarget, reason: String): List<FrostAction> {
        phase = FrostPhase.IDLE
        innerAttachedMs = 0L
        closeHold = null
        lastInnerTickMs = Long.MIN_VALUE
        lastCoverTickMs = Long.MIN_VALUE
        return listOf(FrostAction.RemoveOverlay(target, reason), FrostAction.ReleasePoseEngine)
    }

    companion object {
        /** No angle to know when the inner clear finished: give up and remove after this long. */
        const val INNER_CLEAR_TIMEOUT_MS = 8_000L

        /** B15: minimum time the freshly-attached inner shot stays fully frosted — now the delay of [innerClearPacing]'s release floor; see [onScreenshotResult]. */
        const val INNER_ATTACH_HOLD_MS = 300L
    }
}
