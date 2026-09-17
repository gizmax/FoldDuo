package cz.pflanzer.foldduo.systemfrost

import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.Panel

/**
 * Pure state machine for B14 "Inner od ~35°" (STATUS.md, IDEAS.md): request the hidden
 * `DeviceStateManager` OPENED state ([DeviceStateOverride]) as soon as the phone starts opening
 * from the cover, so the inner panel (and with it [SystemFrostPlan]'s own angle-mode tilt
 * mapping, which already saturates to full frost below [cz.pflanzer.foldduo.continuum.FoldShader.PANEL_ON_HINGE]
 * and clears 90→172 exactly as it does today — see the file comment on [SystemFrost.applyTilt])
 * lights up at ~35–50° instead of waiting for One UI's own ~91° `HALF_OPENED` switch.
 *
 * Independent of [SystemFrostPlan]: this fires whether or not our own launcher is in the
 * foreground (unlike the frost episodes, which skip over the launcher because it already has
 * pane identity — there is no such reason to skip the early inner here), gated only on the
 * keyguard and the setting. [SystemFrost] is the framework-facing half: it calls [request]
 * outcome back through [onRequestFailed] once it knows whether the reflection call itself threw
 * (hidden API blocked / SecurityException), which disables all further attempts for the process.
 */
class OpenedOverridePlan {
    private enum class State { IDLE, REQUESTED }

    private var state = State.IDLE
    private var previousStep: HingeStep? = null
    private var disabled = false

    /** True once a request has failed; no further requests are made for the life of the process. */
    val isDisabledForProcess: Boolean get() = disabled

    /**
     * A new hinge-step reading arrived, on the always-on cheap listener (same one
     * [SystemFrostPlan.onHingeStep] reads). Requests OPENED on the Closed -> non-Closed step on
     * the cover (mirrors the opening trigger [SystemFrostPlan] uses for its own cover frost);
     * cancels on rule (a) the hinge falling back to Closed, or the step-180 half of rule (b).
     */
    fun onHingeStep(
        nowMs: Long,
        panel: Panel,
        step: HingeStep?,
        keyguardLocked: Boolean,
        settingOn: Boolean,
    ): List<FrostAction> {
        val before = previousStep
        previousStep = step
        if (disabled) return emptyList()
        return when (state) {
            State.IDLE -> {
                val trigger = settingOn && !keyguardLocked && panel == Panel.Cover &&
                    before == HingeStep.Closed && step != null && step != HingeStep.Closed
                if (trigger) {
                    state = State.REQUESTED
                    listOf(FrostAction.RequestOpenedOverride)
                } else emptyList()
            }
            State.REQUESTED -> when (step) {
                HingeStep.Closed -> cancel("hinge back at 0")
                HingeStep.Flat -> cancel("hinge step 180")
                else -> emptyList()
            }
        }
    }

    /**
     * Periodic continuous-angle tick, when one happens to be available (the Pose Engine's
     * magnetometer estimator, running only while a frost episode also has it acquired — see
     * SystemFrost.kt). The other half of rule (b); rule (d), the 10 s safety timeout, is driven
     * by [SystemFrost] on its own Handler instead, since it must fire even when no tick loop is
     * running at all (e.g. the frost episode was skipped because the launcher is foreground).
     */
    fun onAngleTick(angleDeg: Float): List<FrostAction> {
        if (state != State.REQUESTED) return emptyList()
        return if (!angleDeg.isNaN() && angleDeg >= ANGLE_CANCEL_DEG) cancel("angle >= $ANGLE_CANCEL_DEG") else emptyList()
    }

    /** Rule (c) and rule (d): cancel from outside the hinge-step stream (service destroyed, or the 10 s timer fired). */
    fun cancelExternally(reason: String): List<FrostAction> = if (state == State.REQUESTED) cancel(reason) else emptyList()

    /** The reflection call itself threw. No cancel action: nothing was ever granted to undo. */
    fun onRequestFailed(): List<FrostAction> {
        disabled = true
        state = State.IDLE
        return emptyList()
    }

    private fun cancel(reason: String): List<FrostAction> {
        state = State.IDLE
        return listOf(FrostAction.CancelOpenedOverride(reason))
    }

    companion object {
        /** Rule (b): the continuous angle at or above which the base state is HALF_OPENED/OPENED anyway. */
        const val ANGLE_CANCEL_DEG = 100f
    }
}
