package cz.pflanzer.foldduo.pose

import kotlin.math.abs

/**
 * "Zavírání jako Duo" (STATUS.md, 17. 9. noc): an early, gyro-anchored estimate of how far the
 * hinge has closed while the raw `hinge_angle` step still reads Flat (180) — today the closing
 * frost only starts once that step actually leaves Flat, which HAL reports at ~145° of physical
 * angle (docs/research/morph-smoothness.md; STATUS.md's "Úhel pantu v2" / [HingeStepGate]), well
 * later than the ~170° a continuous, Duo-style close should start frosting from.
 *
 * Two ways in, mirroring [HingeSqueezeDetector]'s own IDLE/MOVING onset (tuned tighter here since
 * this drives a visible frost automatically, not a deliberate held gesture):
 *  - [HingeMotionDetectors.Fired.Closing] firing this same sample (the existing 5.5 rad/s
 *    sustained-swing detector, [OpeningMotionDetector]) is an instant qualification, regardless of
 *    how little this detector's own integral has accumulated so far.
 *  - or the net closing rotation ([depthDeg]) reaches [MOTION_ANGLE_DEG] within [MOTION_WINDOW_MS]
 *    of the first non-calm sample — at which point the bridged angle ([angleDeg]) is exactly
 *    180 - [MOTION_ANGLE_DEG] = 170°, matching the task's "begins at ~170°" target precisely.
 *
 * Deliberately NOT triggered by a mere tilt of the phone in the hand: a slow reorientation while
 * flat is either `calm` (|rate| < [CALM_RATE_RAD_S]) from the start, in which case nothing ever
 * accumulates, or it briefly exceeds calm but a candidate that fails to reach [MOTION_ANGLE_DEG]
 * within [MOTION_WINDOW_MS] is dropped and a later sample starts a fresh attempt — the same guard
 * that keeps ordinary handling jitter from firing [HingeSqueezeDetector] (its own
 * `MOTION_ANGLE_DEG` was raised from 6° to 12° for exactly this reason, 17. 9.: "6 fired on a
 * phone merely held"). Once acquired ([active]), the estimate keeps integrating in both
 * directions (a hand that eases back open subtracts from [depthDeg] the same way closing added to
 * it) and cancels back to 0 either when the raw hinge step actually reports something other than
 * Flat (a real close arrived — the ordinary step-based path in `UnfoldMorph.kt` /
 * [HingeStepGate] takes over from here) or when the phone has sat calm for [CANCEL_CALM_MS] with
 * the step still at Flat (a false start that never continued into a real close, not a genuine
 * close the hand is merely resting mid-way through).
 *
 * Pure and main-thread only, no Android types, fed one sample at a time — same shape as
 * [HingeSqueezeDetector] and [OpeningMotionDetector] — so it is unit-testable on the JVM and
 * owned by [PoseRepository] alongside them.
 */
class ClosingOnsetDetector {
    enum class Source { NONE, MOTION, INTEGRAL }

    /** Net closing rotation (deg) accumulated since the current run started; 0 while [Source.NONE]. */
    var depthDeg: Float = 0f
        private set

    /** Which condition confirmed the current run; [Source.NONE] while no onset is bridging the angle. */
    var source: Source = Source.NONE
        private set

    /** True while [angleDeg] is bridging a real, continuous estimate instead of NaN. */
    val active: Boolean get() = source != Source.NONE

    private var runStartMs = -1L
    private var calmSinceMs = -1L
    private var lastSampleMs = -1L

    /**
     * Feed a hinge-step sample: the raw step reporting anything other than Flat means the real
     * transition has arrived — this detector's job (bridging *before* that arrives) is over;
     * reset silently, the ordinary step-based closing path takes over. Safe to call on every
     * hinge sample, not just on a step change (mirrors [HingeSqueezeDetector.onHingeStep]).
     */
    fun onHingeStep(panelInner: Boolean, stepDeg: Float) {
        if (panelInner && HingeStep.of(stepDeg) == HingeStep.Flat) return
        reset()
    }

    /**
     * Feed one gyroscope sample's rate about the hinge axis (rad/s, opening positive — the same
     * convention [HingeSqueezeDetector.onGyro] documents). A sample outside Flat+Inner is a no-op
     * cancel rather than an error, so a caller need not gate this call itself.
     *
     * @param closingMotionFired [HingeMotionDetectors.Fired.Closing] having fired this same
     * sample: the coarse/fast spike, an instant qualification.
     * @return true exactly on the sample where [active] turns on (a fresh onset just confirmed);
     * false otherwise, including every sample where it stays on, stays off, or turns off.
     */
    fun onGyro(nowMs: Long, panelInner: Boolean, stepDeg: Float, rateRadS: Float, closingMotionFired: Boolean): Boolean {
        val wasActive = active
        if (!panelInner || HingeStep.of(stepDeg) != HingeStep.Flat) {
            reset()
            return false
        }
        val dtMs = if (lastSampleMs < 0) 0L else (nowMs - lastSampleMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        lastSampleMs = nowMs
        // Opening-positive convention: closing rotation is negative, so -rate is closing progress.
        val deltaDeg = Math.toDegrees((-rateRadS).toDouble()).toFloat() * (dtMs / 1000f)
        val calm = abs(rateRadS) < CALM_RATE_RAD_S

        if (closingMotionFired) {
            depthDeg = (depthDeg + deltaDeg).coerceAtLeast(MOTION_ANGLE_DEG).coerceAtMost(MAX_DEPTH_DEG)
            source = Source.MOTION
            calmSinceMs = -1L
            return !wasActive && active
        }
        if (calm) {
            if (!active) {
                // A stale, unconfirmed candidate never reached the threshold: abandon it, a later
                // sample can start a fresh attempt.
                if (runStartMs >= 0) { depthDeg = 0f; runStartMs = -1L }
                return false
            }
            // A confirmed run gone calm: cancel after CANCEL_CALM_MS of stillness with the step
            // still at Flat — a real close keeps moving and never reaches this.
            if (calmSinceMs < 0) calmSinceMs = nowMs
            else if (nowMs - calmSinceMs >= CANCEL_CALM_MS) reset()
            return false
        }
        calmSinceMs = -1L
        if (runStartMs < 0) { runStartMs = nowMs; if (!active) depthDeg = 0f }
        depthDeg = (depthDeg + deltaDeg).coerceIn(0f, MAX_DEPTH_DEG)
        if (!active) {
            if (depthDeg >= MOTION_ANGLE_DEG && nowMs - runStartMs <= MOTION_WINDOW_MS) {
                source = Source.INTEGRAL
            } else if (nowMs - runStartMs > MOTION_WINDOW_MS) {
                depthDeg = 0f
                runStartMs = -1L // too slow to qualify: drop and retry fresh
            }
        }
        return !wasActive && active
    }

    /** The bridged closing angle estimate: 180 minus [depthDeg] while [active], NaN otherwise. */
    fun angleDeg(): Float = if (active) (180f - depthDeg) else Float.NaN

    fun reset() {
        depthDeg = 0f
        source = Source.NONE
        runStartMs = -1L
        calmSinceMs = -1L
        lastSampleMs = -1L
    }

    companion object {
        /** Depth (deg) that confirms an onset via the integral path; also the floor a spike qualification jumps to. 180 - this = ~170°, the task's onset target. */
        const val MOTION_ANGLE_DEG = 10f
        /** [MOTION_ANGLE_DEG] must accumulate within this long of the first non-calm sample. */
        const val MOTION_WINDOW_MS = 600L
        /** A confirmed run sitting calm this long (step still Flat, no real transition arrived) cancels back to 0: a false start, not a paused real close. */
        const val CANCEL_CALM_MS = 700L
        /** Below this |rate| the hand counts as still. */
        const val CALM_RATE_RAD_S = 0.3f
        /** [depthDeg] never reports more than this. */
        const val MAX_DEPTH_DEG = 40f
        /** A gap wider than this between gyro samples (sensor re-registration, app resume) is not integrated. */
        const val MAX_SAMPLE_GAP_MS = 500L
    }
}
