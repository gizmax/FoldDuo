package cz.pflanzer.foldduo.standby

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * B32 "Tent -> StandBy morph" (IDEAS.md, STATUS.md "Tent -> StandBy morph B32"): entering and
 * leaving StandBy is a frost morph, not a cut, mirroring UnfoldMorph.kt's own language (a layer
 * frosts, then clears to reveal what is underneath) but scoped to StandBy's own activity swap
 * instead of the panel swap. Pure logic here (no Android types) runs as JVM unit tests, like
 * StandByRules.kt; [StandByMorphController] carries the two Animatables a Compose side reads in a
 * `graphicsLayer` / `drawWithContent` block (StandByMorphOverlay.kt), same convention as
 * MorphController's own progress/frost Animatables.
 *
 * Two independent instances play this: the launcher's own pre-entry frost (LauncherScreen.kt,
 * via the bridge in StandByMorphOverlay.kt) when it is in front and about to hand off to
 * StandByActivity, and StandByActivity's own enter/leave (StandByActivity.kt). They hand off
 * through [StandByActivity.EXTRA_START_FROSTED]: when the launcher already frosted its panel,
 * the activity starts already opaque and only plays the clear, instead of frosting twice.
 *
 * Entering: [angleFrost] follows the hinge angle's physical approach into Tent (120 deg, still
 * clearly open, down to 90 deg, the tent hinge step) while the magnetometer is confident, so the
 * frost tracks the fold itself; [timeFrost] is the fallback ramp over [ENTER_FROST_MS] when it is
 * not (no magnetometer, out of range, not yet anchored). Leaving: the face (or the launcher, on
 * the way back) frosts up over [LEAVE_FROST_MS] / [LEAVE_CLEAR_MS] and the activity finishes
 * behind it.
 *
 * Reduce motion (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`, [reduceMotion]) collapses every
 * phase above to a flat [REDUCED_MOTION_MS] crossfade.
 */

/**
 * Enter/leave phases of one side of the transition. A thin state machine so a stray event (a
 * second exit while already leaving, or a leave requested mid-entrance because the pose left Tent
 * before the reveal spring settled) is handled once and does not restart or double-play.
 */
enum class StandByMorphPhase { Idle, Entering, Showing, Leaving }

/**
 * [requestEnter] only succeeds from [StandByMorphPhase.Idle]; [requestLeave] succeeds from
 * [StandByMorphPhase.Entering] (leaving mid-entrance is normal, see the class doc) or
 * [StandByMorphPhase.Showing], never from [StandByMorphPhase.Idle] (nothing to leave) or a second
 * time from [StandByMorphPhase.Leaving]. The `*Complete` calls only advance the phase they belong
 * to, so a stale completion (e.g. a cancelled play's `finally`) cannot clobber a newer phase.
 */
class StandByMorphMachine {
    var phase: StandByMorphPhase = StandByMorphPhase.Idle
        private set

    fun requestEnter(): Boolean {
        if (phase != StandByMorphPhase.Idle) return false
        phase = StandByMorphPhase.Entering
        return true
    }

    fun enterComplete(): Boolean {
        if (phase != StandByMorphPhase.Entering) return false
        phase = StandByMorphPhase.Showing
        return true
    }

    fun requestLeave(): Boolean {
        if (phase != StandByMorphPhase.Entering && phase != StandByMorphPhase.Showing) return false
        phase = StandByMorphPhase.Leaving
        return true
    }

    fun leaveComplete(): Boolean {
        if (phase != StandByMorphPhase.Leaving) return false
        phase = StandByMorphPhase.Idle
        return true
    }

    /** A fresh instance (a new StandByActivity, or the launcher's bridge after a full round trip): back to Idle regardless of the current phase. */
    fun reset() { phase = StandByMorphPhase.Idle }
}

object StandByMorph {
    /** Length of the enter frost (launcher pre-entry ramp, or StandByActivity's own when it was not pre-frosted). */
    const val ENTER_FROST_MS = 350
    const val ENTER_REVEAL_SCALE_FROM = 0.92f
    const val ENTER_REVEAL_SCALE_TO = 1f
    /** Spring damping of the face's fade + scale reveal out of the frost. */
    const val ENTER_REVEAL_SPRING_DAMPING = 0.8f
    /** Length of the leaving face's own frost-up, before the activity finishes. */
    const val LEAVE_FROST_MS = 250
    /** Length of the launcher's own clear once StandBy has gone (MorphController.requestStandByReturn). */
    const val LEAVE_CLEAR_MS = 300
    /** Reduce motion collapses every phase above to this flat crossfade. */
    const val REDUCED_MOTION_MS = 150

    /** Hinge angle at which the Tent-entry frost is still 0: clearly open, well short of the tent step. */
    const val ANGLE_APPROACH_START_DEG = 120f
    /** Hinge angle at which the Tent-entry frost reaches 1: the tent hinge step itself (PoseClassifier's HingeStep.Mid / Tent shape territory). */
    const val ANGLE_APPROACH_END_DEG = 90f

    val easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * Frost amount (0 sharp .. 1 fully frosted) for the physical approach into Tent: 0 at or
     * above [ANGLE_APPROACH_START_DEG] (still clearly open), 1 at or below
     * [ANGLE_APPROACH_END_DEG] (the tent hinge step, or tighter), linear and monotonic between.
     * Null for a NaN angle (no confident magnetometer read), so the caller falls back to
     * [timeFrost] instead of reading this as "sharp".
     */
    /** How long the launcher stays armed for an entrance after StandByService's "show" decision before giving up. */
    const val ARM_TIMEOUT_MS = 4_000L

    /**
     * The launcher-side angle frost: only while [armed] (StandBy is about to show) and only on
     * the cover; otherwise 0 — a resting closed phone reads as angle 0 and must stay sharp.
     */
    fun launcherAngleFrost(armed: Boolean, onCover: Boolean, angleDeg: Float): Float =
        if (armed && onCover) angleFrost(angleDeg) ?: 0f else 0f

    fun angleFrost(angleDeg: Float): Float? {
        if (angleDeg.isNaN()) return null
        if (angleDeg >= ANGLE_APPROACH_START_DEG) return 0f
        if (angleDeg <= ANGLE_APPROACH_END_DEG) return 1f
        return (ANGLE_APPROACH_START_DEG - angleDeg) / (ANGLE_APPROACH_START_DEG - ANGLE_APPROACH_END_DEG)
    }

    /** Time-based fallback ramp: linear 0..1 over [durationMs] since the trigger; 1 at once for a non-positive duration. */
    fun timeFrost(elapsedMs: Long, durationMs: Int): Float =
        if (durationMs <= 0) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * The enter frost's progress at [elapsedMs]: [angleFrost] of [angleDeg] when it is confident,
     * [timeFrost] over [durationMs] otherwise. The single function the launcher's bridge calls
     * per Pose Engine sample so the two sources never fight over which one is "driving".
     */
    fun enterFrostProgress(elapsedMs: Long, angleDeg: Float, durationMs: Int = ENTER_FROST_MS): Float =
        angleFrost(angleDeg) ?: timeFrost(elapsedMs, durationMs)

    /** True when the system says to skip motion (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`). */
    fun reduceMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

    fun enterDurationMs(reduceMotion: Boolean): Int = if (reduceMotion) REDUCED_MOTION_MS else ENTER_FROST_MS
    fun leaveFrostDurationMs(reduceMotion: Boolean): Int = if (reduceMotion) REDUCED_MOTION_MS else LEAVE_FROST_MS
    fun leaveClearDurationMs(reduceMotion: Boolean): Int = if (reduceMotion) REDUCED_MOTION_MS else LEAVE_CLEAR_MS

    /** Uniform frost look (StandByMorphOverlay.kt's `Modifier.standByFrost`): blur cap in px and the black veil's peak alpha at frost = 1. */
    const val FROST_MAX_BLUR_PX = 48f
    const val FROST_MAX_TINT_ALPHA = 0.85f
}

/**
 * Drives one side of the transition (the launcher's own pre-entry frost, or StandByActivity's
 * enter/leave): [frost] is 0 sharp .. 1 fully frosted, [reveal] is the face's own fade/scale
 * progress (0 hidden/small .. 1 shown/full size). Both are plain Animatables, read inside a
 * `graphicsLayer` block only (UnfoldMorph.kt's own convention), so the animation's per-frame
 * writes reach the draw phase without recomposing anything.
 */
class StandByMorphController {
    val machine = StandByMorphMachine()
    val frost = Animatable(0f)
    val reveal = Animatable(0f)

    /**
     * [startFrosted] (StandByActivity.EXTRA_START_FROSTED): the launcher already played its own
     * pre-entry frost, so [frost] snaps to 1 instead of ramping there again. Either way, once
     * frosted, [frost] clears to 0 while [reveal] springs 0 -> 1 together (the face fades and
     * scales in out of the frost as the frost clears behind it). A no-op from any phase other
     * than [StandByMorphPhase.Idle] (see [StandByMorphMachine]).
     */
    suspend fun playEnter(reduceMotion: Boolean, startFrosted: Boolean) {
        if (!machine.requestEnter()) return
        try {
            val duration = StandByMorph.enterDurationMs(reduceMotion)
            if (startFrosted) frost.snapTo(1f) else {
                frost.snapTo(0f)
                frost.animateTo(1f, tween(duration, easing = StandByMorph.easing))
            }
            reveal.snapTo(0f)
            coroutineScope {
                launch { frost.animateTo(0f, tween(duration, easing = StandByMorph.easing)) }
                launch {
                    if (reduceMotion) reveal.animateTo(1f, tween(duration, easing = StandByMorph.easing))
                    else reveal.animateTo(1f, spring(dampingRatio = StandByMorph.ENTER_REVEAL_SPRING_DAMPING))
                }
            }
        } finally {
            machine.enterComplete()
        }
    }

    /**
     * The face frosts up over [StandByMorph.leaveFrostDurationMs] while fading out, then the
     * caller finishes the activity (or, for the launcher's return trip, this instance's [frost]
     * simply stays at 1 until [MorphController.requestStandByReturn] clears it). A no-op unless
     * [StandByMorphPhase.Entering] or [StandByMorphPhase.Showing] (see [StandByMorphMachine]).
     */
    suspend fun playLeave(reduceMotion: Boolean) {
        if (!machine.requestLeave()) return
        try {
            val duration = StandByMorph.leaveFrostDurationMs(reduceMotion)
            coroutineScope {
                launch { reveal.animateTo(0f, tween(duration, easing = StandByMorph.easing)) }
                launch { frost.animateTo(1f, tween(duration, easing = StandByMorph.easing)) }
            }
        } finally {
            machine.leaveComplete()
        }
    }
}
