package cz.pflanzer.foldduo.pose

import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooths a sensor-rate tilt target for the frost layers (pure Kotlin, main thread only).
 *
 * Fed once per hinge-angle sample ([update]) with the mapped tilt (or NaN while the angle is
 * unusable): exponential smoothing with time constant [tauMs] hides the ~50–100 Hz steps and
 * the estimator's ~9 deg error; a sample whose *angle* jumps by more than [glitchDeg] from the
 * previous one (an Earth-field glitch when the phone is turned mid-fold) is ignored and the
 * previous target held for [holdMs], after which the next sample is taken as is (a real jump
 * is still there). NaN targets ease the tilt back to 0.
 */
class HingeTiltSmoother(
    val tauMs: Float = DEFAULT_TAU_MS,
    val glitchDeg: Float = DEFAULT_GLITCH_DEG,
    val holdMs: Long = DEFAULT_HOLD_MS,
    private val maxTilt: Float = 45f,
) {
    /** The smoothed tilt, what the layer draws. */
    var tilt: Float = 0f
        private set

    /** The target the smoother is easing toward. */
    var target: Float = 0f
        private set

    /** The last accepted angle in degrees; NaN before any. */
    var lastAngleDeg: Float = Float.NaN
        private set

    /** Number of samples dropped by the glitch hold so far. */
    var glitches: Int = 0
        private set

    private var lastMs = Long.MIN_VALUE
    private var holdUntilMs = Long.MIN_VALUE

    /**
     * One sample at [nowMs]: the raw [angleDeg] (NaN = unusable) and its mapped [tiltDeg]
     * (ignored when the angle is NaN). Returns the smoothed tilt.
     */
    fun update(nowMs: Long, angleDeg: Float, tiltDeg: Float): Float {
        if (angleDeg.isNaN()) {
            target = 0f
            lastAngleDeg = Float.NaN
            holdUntilMs = Long.MIN_VALUE
        } else if (nowMs < holdUntilMs) {
            glitches++
        } else if (!lastAngleDeg.isNaN() && holdUntilMs == Long.MIN_VALUE && abs(angleDeg - lastAngleDeg) > glitchDeg) {
            holdUntilMs = nowMs + holdMs
            glitches++
        } else {
            holdUntilMs = Long.MIN_VALUE
            lastAngleDeg = angleDeg
            target = tiltDeg.coerceIn(0f, maxTilt)
        }
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceIn(0L, 1_000L)
        lastMs = nowMs
        tilt += (target - tilt) * (1f - exp(-dt / tauMs))
        if (abs(target - tilt) < 0.02f) tilt = target
        return tilt
    }

    /** Jumps straight to [tiltDeg] (the fallback hand-over, or a reset). */
    fun snap(tiltDeg: Float) {
        target = tiltDeg.coerceIn(0f, maxTilt); tilt = target
        lastAngleDeg = Float.NaN; holdUntilMs = Long.MIN_VALUE; lastMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_TAU_MS = 60f
        const val DEFAULT_GLITCH_DEG = 40f
        const val DEFAULT_HOLD_MS = 200L
    }
}
