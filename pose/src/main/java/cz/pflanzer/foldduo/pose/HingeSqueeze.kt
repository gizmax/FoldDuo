package cz.pflanzer.foldduo.pose

import kotlin.math.abs

/**
 * What [HingeSqueezeDetector.onGyro] (or [HingeSqueezeDetector.onHingeStep]) returns; `null` most
 * calls, since almost every sample just updates [HingeSqueezeDetector.depthDeg] in place.
 */
sealed class SqueezeSignal {
    /** A squeeze was recognized: the hold begins now. [depthDeg] is the gyro-integrated closing
     * angle at the moment recognition completed (already the same value [HingeSqueezeDetector.depthDeg]
     * reports); [holdMs] is how long the hand sat calm before recognition fired (>= [HingeSqueezeDetector.SETTLE_MS]). */
    data class Recognized(val depthDeg: Float, val holdMs: Long) : SqueezeSignal()
    /** The hold ended by re-flattening (the net integral fell back under [HingeSqueezeDetector.RELEASE_ANGLE_DEG]). */
    object Released : SqueezeSignal()
}

/**
 * B48 "Pant jako ovladač" (IDEAS.md): a light closing squeeze from Flat that the user holds,
 * without the hinge step ever reaching Mid (90°), reads as a deliberate input — the fold as a
 * controller, not just something to open or close. Pure and main-thread only, no Android types,
 * fed one sample at a time (same style as [OpeningMotionDetector]); [PoseRepository] owns the
 * instance and republishes [phase]/[depthDeg] into [PoseSnapshot.squeezeSeq] /
 * [PoseSnapshot.squeezeDepthDeg] / [PoseSnapshot.squeezeHeld].
 *
 * Active only while the panel is Inner and the last hinge step reads Flat; every entry point
 * resets to [Phase.IDLE] the instant either is not true (a real close arriving mid-gesture, panel
 * Cover, or hinge Mid/Closed), and does so *silently* — no [SqueezeSignal.Released] — because that
 * case is a real close under way, not a release of the squeeze gesture.
 *
 * State machine, one gyroscope sample at a time ([onGyro], rate about the hinge axis, rad/s,
 * *opening positive* — the same convention [HingeTrajectoryEstimator.onGyroHingeRate] documents):
 *  - `IDLE`: a candidate closing rotation accumulates in [depthDeg] the moment the rate leaves
 *    [CALM_RATE_RAD_S]; if it reaches [MOTION_ANGLE_DEG] within [MOTION_WINDOW_MS] of that first
 *    non-calm sample, or [HingeMotionDetectors.Fired.Closing] fires this same sample (the task's
 *    "OR", a much coarser but instant alternative), the gesture becomes `MOVING`. A candidate that
 *    fails to reach the threshold in time is dropped (reset to 0) and a later sample can start a
 *    fresh attempt — this is what keeps ordinary handling jitter from ever registering (see
 *    `HingeSqueezeDetectorTest`'s "tilt at rest" case).
 *  - `MOVING`: keeps integrating; once the rate drops back under [CALM_RATE_RAD_S] the motion has
 *    stopped and the gesture becomes `SETTLING`.
 *  - `SETTLING`: keeps integrating (a small residual drift is still the user's finger, not noise);
 *    [SETTLE_MS] of calm in a row (the step never having left Flat in the meantime — enforced by
 *    every entry point's own Flat+Inner gate) recognizes the squeeze, subject to [DEBOUNCE_MS]
 *    against the previous [SqueezeSignal.Recognized]. A debounced attempt is dropped entirely (no
 *    event, no `HELD`) rather than held silently, so a second quick squeeze cannot half-open the
 *    overlay without ever telling a consumer it did.  Motion resuming (rate leaves calm again)
 *    goes back to `MOVING` and restarts the dwell timer.
 *  - `HELD`: still integrating in *both* directions — a fresh opening rotation subtracts from
 *    [depthDeg] the same way closing added to it, which is what lets "re-flattening" simply be
 *    "the integral fell back under [RELEASE_ANGLE_DEG]" without a separate opening-motion test, and
 *    is also what makes [depthDeg] follow the hand while held (the launcher's drag-like overlay).
 *    Crossing under [RELEASE_ANGLE_DEG] resets to `IDLE` and emits [SqueezeSignal.Released].
 */
class HingeSqueezeDetector(
    private val motionAngleDeg: Float = MOTION_ANGLE_DEG,
    private val settleMs: Long = SETTLE_MS,
) {
    enum class Phase { IDLE, MOVING, SETTLING, HELD }

    /** Current phase; consumers only care about `HELD` (maps to [PoseSnapshot.squeezeHeld]). */
    var phase: Phase = Phase.IDLE
        private set

    /** Live net closing rotation (deg) since the gesture started: 0 in [Phase.IDLE], otherwise
     * clamped to `[0, MAX_DEPTH_DEG]` and updated on every [onGyro] call regardless of phase. */
    var depthDeg: Float = 0f
        private set

    private var motionStartMs = -1L
    private var settleStartMs = -1L
    private var lastSampleMs = -1L
    private var lastFireMs = Long.MIN_VALUE / 2

    /**
     * Feed a hinge-step sample: leaving Flat, or the panel leaving Inner, cancels a
     * squeeze-in-progress or a held squeeze with **no** event — "if the step 90 does arrive, it
     * was a real close: cancel silently" (IDEAS.md B48). Safe to call on every hinge sample, not
     * just on a step change.
     */
    fun onHingeStep(panelInner: Boolean, stepDeg: Float): SqueezeSignal? {
        if (panelInner && HingeStep.of(stepDeg) == HingeStep.Flat) return null
        reset()
        return null
    }

    /**
     * Feed one gyroscope sample's rate about the hinge axis (rad/s, opening positive). Call on
     * every sample; a sample outside Flat+Inner is a no-op cancel (see [onHingeStep]) rather than
     * an error, so a caller need not gate this call itself.
     *
     * @param closingMotionFired [HingeMotionDetectors.Fired.Closing] having fired this same
     * sample — the coarse/fast alternative start condition.
     */
    fun onGyro(nowMs: Long, panelInner: Boolean, stepDeg: Float, rateRadS: Float, closingMotionFired: Boolean): SqueezeSignal? {
        if (!panelInner || HingeStep.of(stepDeg) != HingeStep.Flat) {
            reset()
            return null
        }
        val dtMs = if (lastSampleMs < 0) 0L else (nowMs - lastSampleMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        lastSampleMs = nowMs
        // Opening-positive convention: closing rotation is negative, so -rate is "closing progress".
        val deltaDeg = Math.toDegrees((-rateRadS).toDouble()).toFloat() * (dtMs / 1000f)
        val calm = abs(rateRadS) < CALM_RATE_RAD_S

        return when (phase) {
            Phase.IDLE -> onIdleSample(nowMs, calm, deltaDeg, closingMotionFired)
            Phase.MOVING -> {
                depthDeg = (depthDeg + deltaDeg).coerceIn(0f, MAX_DEPTH_DEG)
                if (calm) { phase = Phase.SETTLING; settleStartMs = nowMs }
                null
            }
            Phase.SETTLING -> onSettlingSample(nowMs, calm, deltaDeg)
            Phase.HELD -> {
                depthDeg = (depthDeg + deltaDeg).coerceIn(0f, MAX_DEPTH_DEG)
                if (depthDeg < RELEASE_ANGLE_DEG) { reset(); SqueezeSignal.Released } else null
            }
        }
    }

    private fun onIdleSample(nowMs: Long, calm: Boolean, deltaDeg: Float, closingMotionFired: Boolean): SqueezeSignal? {
        if (closingMotionFired) {
            // The coarse detector already required a fast, sustained swing; treat it as an
            // instant qualification regardless of how little this detector's own integral has
            // accumulated so far this sample.
            depthDeg = (depthDeg + deltaDeg).coerceIn(0f, MAX_DEPTH_DEG).coerceAtLeast(motionAngleDeg)
            phase = Phase.MOVING
            motionStartMs = nowMs
            return null
        }
        if (calm) {
            // Truly at rest: a stale candidate that never reached the threshold is abandoned.
            if (motionStartMs >= 0 && depthDeg < motionAngleDeg) { depthDeg = 0f; motionStartMs = -1L }
            return null
        }
        if (motionStartMs < 0) { motionStartMs = nowMs; depthDeg = 0f }
        depthDeg = (depthDeg + deltaDeg).coerceIn(0f, MAX_DEPTH_DEG)
        when {
            depthDeg >= motionAngleDeg && nowMs - motionStartMs <= MOTION_WINDOW_MS -> phase = Phase.MOVING
            nowMs - motionStartMs > MOTION_WINDOW_MS -> { depthDeg = 0f; motionStartMs = -1L } // too slow: drop and retry fresh
        }
        return null
    }

    private fun onSettlingSample(nowMs: Long, calm: Boolean, deltaDeg: Float): SqueezeSignal? {
        depthDeg = (depthDeg + deltaDeg).coerceIn(0f, MAX_DEPTH_DEG)
        if (!calm) { phase = Phase.MOVING; settleStartMs = -1L; return null }
        if (nowMs - settleStartMs < settleMs) return null
        if (nowMs - lastFireMs < DEBOUNCE_MS) { reset(); return null }
        phase = Phase.HELD
        lastFireMs = nowMs
        return SqueezeSignal.Recognized(depthDeg, holdMs = nowMs - settleStartMs)
    }

    private fun reset() {
        phase = Phase.IDLE
        depthDeg = 0f
        motionStartMs = -1L
        settleStartMs = -1L
        lastSampleMs = -1L
    }

    companion object {
        /** Gentle-start threshold (deg of net closing rotation) — much smaller than a real close. */
        const val MOTION_ANGLE_DEG = 12f // 17. 9.: 6 fired on a phone merely held (log: depth 2.7–9 while typing in Spotlight)
        /** [MOTION_ANGLE_DEG] must accumulate within this long of the first non-calm sample. */
        const val MOTION_WINDOW_MS = 400L
        /** Below this |rate| the hand counts as still. */
        const val CALM_RATE_RAD_S = 0.3f
        /** Calm dwell required, once motion stops, before a squeeze is recognized. */
        const val SETTLE_MS = 400L
        /** [depthDeg] falling back under this releases a held squeeze. */
        const val RELEASE_ANGLE_DEG = 3f
        /** [depthDeg] never reports more than this (IDEAS.md B48: "cap 40°"). */
        const val MAX_DEPTH_DEG = 40f
        /** At most one [SqueezeSignal.Recognized] this often. */
        const val DEBOUNCE_MS = 1_500L
        /** A gap wider than this between gyro samples (sensor re-registration, app resume) is not integrated. */
        const val MAX_SAMPLE_GAP_MS = 500L
    }
}
