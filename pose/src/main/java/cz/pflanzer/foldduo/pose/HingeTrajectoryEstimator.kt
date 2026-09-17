package cz.pflanzer.foldduo.pose

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Persists the median duration of the user's last few opening/closing transitions, so the
 * trajectory model (below) has something better than a flat default once it has seen a few
 * folds. Pure interface: [PoseRepository] can back it with SharedPreferences the same way
 * [HingeRestStore] backs the calibration rests; tests use [InMemoryHingeTrajectoryDurationStore].
 */
interface HingeTrajectoryDurationStore {
    /** Median opening duration (ms) of the last transitions seen, or the default if none yet. */
    fun openingMedianMs(): Long
    /** Median closing duration (ms), or the default. */
    fun closingMedianMs(): Long
    /** Record one completed opening's actual duration (ms). */
    fun recordOpening(durationMs: Long)
    /** Record one completed closing's actual duration (ms). */
    fun recordClosing(durationMs: Long)
}

/** In-process median-of-last-N store (no persistence); good enough for a single session and for tests. */
class InMemoryHingeTrajectoryDurationStore(
    private val defaultOpeningMs: Long = HingeTrajectoryEstimator.DEFAULT_OPENING_DURATION_MS,
    private val defaultClosingMs: Long = HingeTrajectoryEstimator.DEFAULT_CLOSING_DURATION_MS,
    private val historySize: Int = 10,
) : HingeTrajectoryDurationStore {
    private val opening = ArrayDeque<Long>()
    private val closing = ArrayDeque<Long>()

    override fun openingMedianMs(): Long = median(opening) ?: defaultOpeningMs
    override fun closingMedianMs(): Long = median(closing) ?: defaultClosingMs
    override fun recordOpening(durationMs: Long) = push(opening, durationMs)
    override fun recordClosing(durationMs: Long) = push(closing, durationMs)

    private fun push(dq: ArrayDeque<Long>, v: Long) {
        dq.addLast(v)
        while (dq.size > historySize) dq.removeFirst()
    }

    private fun median(dq: ArrayDeque<Long>): Long? {
        if (dq.isEmpty()) return null
        val s = dq.sorted()
        return s[s.size / 2]
    }
}

/**
 * A critically damped second-order follower: chases a (possibly moving) target with no
 * overshoot and no discrete "hold" (unlike [HingeTiltSmoother]'s glitch hold, which freezes the
 * output outright for a while). Standard closed-form update for a critically damped
 * spring-damper (e.g. Ryan Juckett's "Damped Springs"), frame/sample-rate agnostic (the caller
 * passes `dt`). [timeConstantMs] is roughly a third of the desired settle time: a step input is
 * ~78 % converged after `timeConstantMs` * 2, ~95 % after * 3 and ~98 % after * 4, so pass ~40 ms
 * for a "settles in about 120 ms" follower (Morph v3 design doc, "kriticky tlumený follower").
 */
class CriticallyDampedFollower(initial: Float = 0f) {
    var value: Float = initial
        private set
    private var velocity: Float = 0f

    /** Jumps straight to [v] with zero velocity (used only at a hard, authoritative anchor). */
    fun snap(v: Float) {
        value = v
        velocity = 0f
    }

    fun update(dtMs: Long, target: Float, timeConstantMs: Float): Float {
        val dt = dtMs.coerceAtLeast(0L) / 1000f
        if (dt <= 0f) return value
        val tau = timeConstantMs.coerceAtLeast(1f) / 1000f
        val omega = 2f / tau
        val change = value - target
        val temp = (velocity + omega * change) * dt
        val decay = exp(-omega * dt)
        velocity = (velocity - omega * temp) * decay
        value = target + (change + temp) * decay
        return value
    }
}

/**
 * B44 "Fúze úhlu v3 — trajektorie" (docs/research/morph-smoothness.md, STATUS.md): a continuous
 * hinge angle for the 90 band, fused from the quantised step events, the gyroscope, gravity and
 * the existing magnetometer estimate ([HingeAngleEstimator]), instead of the magnetometer alone.
 *
 * Pure Kotlin, main-thread/single-writer only (like the rest of `pose/`). Feed it, in timestamp
 * order:
 *  - [onStep] on every `TYPE_HINGE_ANGLE` sample (0/90/180),
 *  - [onGyroHingeRate] on every gyroscope sample's rate about the hinge axis (rad/s, opening
 *    positive) — the highest-rate input (~100-200 Hz on the Fold 8) and the "tick" that mostly
 *    drives [tick]'s output,
 *  - [onGravity] on every gravity sample (device axes, m/s²),
 *  - [onMagnetAngle] with the plain [HingeAngleEstimator] angle/confidence (before
 *    [HingeStepGate]) whenever a magnetometer sample lands.
 *
 * Model (design doc "Morph v3", points a-d):
 *  1. **Anchors**: a step event is a timestamp of a known angle band. The band centres —
 *     [OPEN_STEP90_ANGLE]/[OPEN_STEP180_ANGLE] opening, [CLOSE_STEP90_ANGLE]/[CLOSE_STEP0_ANGLE]
 *     closing — are the trajectory's `from`/`to`; [phase] tracks which leg is active.
 *  2. **In-band**: while [phase] is [Phase.OPENING]/[Phase.CLOSING], the gyro is integrated from
 *     the anchor, bias-corrected by the mean rate over the still second immediately before the
 *     transition started ([Phase.AT_CLOSED]/[Phase.AT_FLAT]). The integral is only *trusted* when
 *     it is both clearly non-zero ([gyroTrustThresholdRadS]) and gravity is *not* steady — a
 *     steady gravity vector means the half carrying the IMU (and the gravity sensor, which on
 *     this device is fused from the same chip) is the one at rest, so the other half is doing the
 *     folding and the gyro sees nothing of it (measured on `20260915-174608.jsonl`: the IMU half
 *     never moved, gyro swing 2-28° of a 170° fold). This is an approximation of "gravity rotates
 *     about the hinge axis consistent with the gyro": it only checks that gravity is changing at
 *     all, not that the rotation axis matches, so a whole-phone tilt while genuinely mid-fold
 *     could over-trust the gyro. Untrusted, the estimate falls back to the trajectory model below,
 *     corrected by the magnetometer's trend.
 *  3. **Trajectory model**: a minimum-jerk profile (`6u^5-15u^4+10u^3`, `u = t/D`) between the
 *     anchors. `D` comes from the initial gyro speed when one is available soon after the anchor
 *     (`D = 1.875 * Δangle / v0`, the min-jerk peak-velocity relation solved for `D` —
 *     [refineDurationFromPeak]) or otherwise the learned
 *     median of the user's last 10 transitions ([HingeTrajectoryDurationStore], default 900 ms
 *     opening / 700 ms closing). Reaching the terminal step event corrects any remaining gap
 *     through the same critically damped output stage used every sample (below), rather than a
 *     separate one-off snap.
 *  4. **Fuse**: `base` = the gyro integral when trusted; otherwise the magnetometer's own angle
 *     leads directly (clamped to the anchor band), the min-jerk model only standing in when no
 *     magnet reading has arrived at all — a model whose guessed `D` is wrong for seconds (this is
 *     the normal case whenever the gyro is untrusted for a whole transition, e.g. the table
 *     fixture) must never out-vote a magnet sample that is simply *there*. The magnetometer only
 *     becomes a slow *corrector* — clamped to ±[magnetBoundDeg] of `base`, tracked as a
 *     `target - base` residual with an exponential moving average (τ [magnetTauMs]) and added back
 *     to `base` — while the gyro path is the trusted `base`, to absorb its bias/drift without
 *     letting a single noisy magnet sample punch through. Monotonicity is enforced per direction
 *     (opening never decreases, closing never increases). The result is finally smoothed by one shared
 *     [CriticallyDampedFollower] (~50 ms settle, [outputTauMs] — fast enough that a fold peaking
 *     near 400 deg/s does not itself add several degrees of tracking lag) — no glitch holds, matching the
 *     design doc's "výstup přes kriticky tlumený follower... bez glitch hold".
 *
 * At the resting ends ([Phase.AT_CLOSED]/[Phase.AT_FLAT]) the estimate just follows the resting
 * anchor (10°/168°); [PoseRepository] still applies [HingeStepGate] on top for the *published*
 * angle so the ends stay pinned to hard 0/180 exactly as before, except during the motion-released
 * closing window (point e below).
 *
 * Point (e), "closing from first motion", is this class's job too: while [phase] is
 * [Phase.AT_FLAT] the gyro is continuously integrated (bias-corrected the same way) into
 * [flatClosingIntegralDeg]; when [PoseRepository] sees [HingeMotionDetectors.Fired.Closing] fire
 * it calls [releaseClosingFromMotion], which only takes effect if that integral already shows
 * more than [FLAT_CLOSE_RELEASE_DEG] of closing rotation — otherwise the flat gate stays
 * authoritative (a stray fast gyro spike with no real accumulated rotation must not un-pin a
 * still-flat phone). The opening counterpart ("opening from Closed", design doc point e, marked
 * optional/conservative) is intentionally *not* implemented: it would need a reliable "cover panel
 * active" signal this class does not have, and a false release on a closed phone merely being
 * handled would frost the wrong panel; left for a later pass (STATUS.md).
 */
class HingeTrajectoryEstimator(
    private val store: HingeTrajectoryDurationStore = InMemoryHingeTrajectoryDurationStore(),
    private val outputTauMs: Float = DEFAULT_OUTPUT_TAU_MS,
    private val magnetTauMs: Float = DEFAULT_MAGNET_TAU_MS,
    private val magnetBoundDeg: Float = DEFAULT_MAGNET_BOUND_DEG,
    private val gyroTrustThresholdRadS: Float = DEFAULT_GYRO_TRUST_THRESHOLD_RAD_S,
    private val gravitySteadySigma: Float = DEFAULT_GRAVITY_STEADY_SIGMA,
) {
    enum class Phase { AT_CLOSED, OPENING, AT_FLAT, CLOSING }

    class Estimate(val angleDeg: Float, val confidence: Float, val source: String, val timestampNs: Long)

    /** One completed transition, for [PoseRepository]'s per-transition log line. */
    class TransitionSummary(
        val opening: Boolean,
        val actualDurationMs: Long,
        val predictedDurationMs: Long,
        val anchorsSeen: Int,
        val meanAbsGyroRadS: Float,
        val gyroTrustedFraction: Float,
    )

    var phase: Phase = Phase.AT_CLOSED
        private set

    var angleDeg: Float = CLOSE_STEP0_ANGLE
        private set
    var confidence: Float = 1f
        private set
    var source: String = "step"
        private set

    /** Called once per completed opening or closing, with the numbers for the log line. */
    var onTransitionSummary: ((TransitionSummary) -> Unit)? = null

    private val follower = CriticallyDampedFollower(CLOSE_STEP0_ANGLE)
    private var lastTickNs = Long.MIN_VALUE

    // -- active transition state --
    private var anchorNs = Long.MIN_VALUE
    private var fromAngle = CLOSE_STEP0_ANGLE
    private var toAngle = CLOSE_STEP0_ANGLE
    private var predictedDurationMs = DEFAULT_OPENING_DURATION_MS
    /** Largest bias-corrected |gyro rate| seen so far this transition; see [refineDurationFromPeak]. */
    private var v0MaxRadS = 0f
    private var gyroIntegralAngle = 0f
    private var restBiasRadS = 0f
    private var magCorrectorDeg = 0f
    private var lastMagnetAngle = Float.NaN
    private var extreme = 0f
    private var gyroTrustedFlag = false
    /** Consecutive samples where the raw (unfiltered) trust condition held; see [gyroTrustedFlag]'s update. */
    private var trustAboveCount = 0
    private var anchorsSeen = 0
    private var gyroSamplesThisTransition = 0
    private var gyroTrustedSamplesThisTransition = 0
    private var sumAbsGyroThisTransition = 0.0

    // -- rest-bias accumulators (mean gyro rate while resting, consumed at the next anchor) --
    private var restBiasSum = 0.0
    private var restBiasCount = 0
    private var lastGyroNs = Long.MIN_VALUE

    // -- flat-rest closing-motion integral (point e) --
    private var flatCloseIntegralDeg = 0f
    private var flatRestBiasRadS = 0f

    // -- gravity steadiness (EMA of the dt-normalized rate of change, not a raw per-sample delta:
    // a raw delta is silently sample-rate dependent — at 50 Hz the fixture's real motion clears a
    // per-sample threshold easily, but the same motion fed at 200 Hz halves each delta four times
    // over and never would) --
    private var gLastX = 0f
    private var gLastY = 0f
    private var gLastZ = 0f
    private var gLastNs = Long.MIN_VALUE
    private var gHasLast = false
    private var gSigmaEma = 0f

    /** Quantised `hinge_angle` step (0/90/180) with its sensor timestamp. */
    fun onStep(timestampNs: Long, stepDeg: Float) {
        anchorsSeen++
        when {
            stepDeg <= 45f -> {
                if (phase == Phase.CLOSING) completeTransition(timestampNs, opening = false, nextPhase = Phase.AT_CLOSED)
                else { phase = Phase.AT_CLOSED; flatCloseIntegralDeg = 0f }
            }
            stepDeg >= 135f -> {
                if (phase == Phase.OPENING) completeTransition(timestampNs, opening = true, nextPhase = Phase.AT_FLAT)
                else { phase = Phase.AT_FLAT; flatCloseIntegralDeg = 0f }
            }
            else -> when (phase) {
                Phase.AT_CLOSED -> startTransition(timestampNs, opening = true, from = OPEN_STEP90_ANGLE, to = OPEN_STEP180_ANGLE, predicted = store.openingMedianMs())
                Phase.AT_FLAT -> startTransition(timestampNs, opening = false, from = CLOSE_STEP90_ANGLE, to = CLOSE_STEP0_ANGLE, predicted = store.closingMedianMs())
                Phase.OPENING, Phase.CLOSING -> Unit // duplicate/late 90 mid-transition: ignore
            }
        }
    }

    /**
     * Morph v3 (e): releases the flat gate when a closing swing is detected before the step-90
     * event arrives, starting the trajectory from [CLOSING_RELEASE_ANGLE] (~172°, just inside
     * flat). Only takes effect from [Phase.AT_FLAT] and only once [flatClosingIntegralDeg] already
     * shows more than [FLAT_CLOSE_RELEASE_DEG] of accumulated closing rotation; otherwise a no-op
     * (the flat gate stays authoritative — see [PoseRepository]).
     */
    fun releaseClosingFromMotion(nowNs: Long): Boolean {
        if (phase != Phase.AT_FLAT) return false
        if (abs(flatCloseIntegralDeg) < FLAT_CLOSE_RELEASE_DEG) return false
        startTransition(nowNs, opening = false, from = CLOSING_RELEASE_ANGLE, to = CLOSE_STEP0_ANGLE, predicted = store.closingMedianMs())
        source = "motion-release"
        return true
    }

    /** Accumulated closing-direction gyro rotation (deg) since the last flat-gate reset; 0 unless [phase] is [Phase.AT_FLAT]. */
    fun flatClosingIntegralDeg(): Float = flatCloseIntegralDeg

    private fun startTransition(nowNs: Long, opening: Boolean, from: Float, to: Float, predicted: Long) {
        phase = if (opening) Phase.OPENING else Phase.CLOSING
        anchorNs = nowNs
        fromAngle = from
        toAngle = to
        predictedDurationMs = predicted.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        v0MaxRadS = 0f
        gyroIntegralAngle = from
        restBiasRadS = if (restBiasCount > 0) (restBiasSum / restBiasCount).toFloat() else 0f
        restBiasSum = 0.0
        restBiasCount = 0
        magCorrectorDeg = 0f
        extreme = from
        gyroTrustedFlag = false
        trustAboveCount = 0
        anchorsSeen = 1
        gyroSamplesThisTransition = 0
        gyroTrustedSamplesThisTransition = 0
        sumAbsGyroThisTransition = 0.0
        lastGyroNs = nowNs
    }

    private fun completeTransition(nowNs: Long, opening: Boolean, nextPhase: Phase) {
        val actualMs = if (anchorNs == Long.MIN_VALUE) 0L else (nowNs - anchorNs) / 1_000_000L
        if (anchorNs != Long.MIN_VALUE && actualMs in 50..15_000) {
            if (opening) store.recordOpening(actualMs) else store.recordClosing(actualMs)
        }
        val trustedFraction = if (gyroSamplesThisTransition > 0) gyroTrustedSamplesThisTransition.toFloat() / gyroSamplesThisTransition else 0f
        val meanAbsGyro = if (gyroSamplesThisTransition > 0) (sumAbsGyroThisTransition / gyroSamplesThisTransition).toFloat() else 0f
        onTransitionSummary?.invoke(TransitionSummary(opening, actualMs, predictedDurationMs, anchorsSeen, meanAbsGyro, trustedFraction))
        anchorNs = Long.MIN_VALUE
        phase = nextPhase
        flatCloseIntegralDeg = 0f
        // The terminal step is authoritative, but eased to (not snapped): tick() immediately, in the
        // now-resting phase, so the shared follower converges to the resting anchor at its normal
        // pace instead of leaving a stale in-transition value on screen until the next sample.
        tick(nowNs)
    }

    /** Gravity sample (device axes, m/s²); only feeds the steadiness check used by the gyro-trust gate. */
    fun onGravity(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (gHasLast) {
            val dtS = (timestampNs - gLastNs).coerceAtLeast(0L) / 1e9f
            if (dtS > 0f) {
                val rate = (abs(x - gLastX) + abs(y - gLastY) + abs(z - gLastZ)) / dtS
                gSigmaEma += (rate - gSigmaEma) * GRAVITY_EMA_ALPHA
            }
        }
        gLastX = x
        gLastY = y
        gLastZ = z
        gLastNs = timestampNs
        gHasLast = true
    }

    /** The plain [HingeAngleEstimator] angle/confidence (before [HingeStepGate]); the slow magnet corrector. */
    fun onMagnetAngle(timestampNs: Long, angleDeg: Float, confidence: Float): Estimate {
        if (!angleDeg.isNaN()) lastMagnetAngle = angleDeg
        return tick(timestampNs)
    }

    /** Gyro rate about the hinge axis (rad/s, opening positive); the highest-rate input and the main tick. */
    fun onGyroHingeRate(timestampNs: Long, radPerSec: Float): Estimate {
        // Capped: at the real ~100-200 Hz rate dt is a few ms, so anything past MAX_GYRO_DT_NS means
        // a real gap (sensor re-registration, app resume, or simply the first sample fed after a
        // phase change with no gyro in between) — integrating a stale rate over that whole gap would
        // add a bogus multi-degree jump on a single sample instead of just resuming cleanly.
        val dtS = if (lastGyroNs == Long.MIN_VALUE) 0f else (timestampNs - lastGyroNs).coerceIn(0L, MAX_GYRO_DT_NS) / 1e9f
        lastGyroNs = timestampNs
        when (phase) {
            Phase.AT_CLOSED -> { restBiasSum += radPerSec; restBiasCount++ }
            Phase.AT_FLAT -> {
                flatRestBiasRadS += (radPerSec - flatRestBiasRadS) * FLAT_BIAS_EMA_ALPHA
                val corrected = radPerSec - flatRestBiasRadS
                flatCloseIntegralDeg = (flatCloseIntegralDeg + Math.toDegrees(corrected.toDouble()).toFloat() * dtS) * FLAT_INTEGRAL_DECAY
            }
            Phase.OPENING, Phase.CLOSING -> {
                gyroSamplesThisTransition++
                sumAbsGyroThisTransition += abs(radPerSec).toDouble()
                val corrected = radPerSec - restBiasRadS
                refineDurationFromPeak(corrected)
                // A single noisy sample (real accel/gyro jitter, a table nudge) must not flip trust
                // for a whole run of ticks: this fixture's own IMU-half-stationary data has brief
                // spikes (README: "IMU-half share of accel motion 0.08-0.43", not exactly 0), and one
                // false-trusted tick freezes the estimate near the anchor for the rest of a still
                // window. Require [TRUST_CONSECUTIVE_SAMPLES] in a row, the same debounce shape as
                // [OpeningMotionDetector].
                if (abs(corrected) >= gyroTrustThresholdRadS && !gravitySteady()) trustAboveCount++ else trustAboveCount = 0
                gyroTrustedFlag = trustAboveCount >= TRUST_CONSECUTIVE_SAMPLES
                if (gyroTrustedFlag) gyroTrustedSamplesThisTransition++
                gyroIntegralAngle += Math.toDegrees(corrected.toDouble()).toFloat() * dtS
            }
        }
        return tick(timestampNs)
    }

    /**
     * Refines [predictedDurationMs] from the largest bias-corrected |gyro rate| seen so far this
     * transition (`v0` in the design doc's `D ≈ Δangle / (1.875·v0)`, the min-jerk peak-velocity
     * relation). The very first sample above [MIN_V0_RAD_S] is a bad proxy for the *peak* velocity
     * a min-jerk profile starts at zero speed, so the first crossing of a low threshold is tiny and
     * turns this into a huge overestimate of `D`. Tracking the running maximum instead means the
     * estimate starts too slow (safe: the model then just lags, corrected by the magnet/gyro base)
     * and monotonically tightens toward the true peak as the fold passes its midpoint, after which
     * it stays put (the true peak observed) instead of chasing the falling tail.
     */
    private fun refineDurationFromPeak(correctedRadPerSec: Float) {
        val v0 = abs(correctedRadPerSec)
        if (v0 <= v0MaxRadS || v0 < MIN_V0_RAD_S) return
        v0MaxRadS = v0
        val v0Deg = Math.toDegrees(v0.toDouble()).toFloat()
        val span = abs(toAngle - fromAngle)
        // A min-jerk profile's peak velocity is v_peak = 1.875 * span / D (peak at u=0.5, exact for
        // the quintic profile), so inverted for D this is D = 1.875 * span / v_peak, not
        // span / (1.875 * v_peak) — the design doc's "D ≈ Δangle / (1.875·v0)" reads as the inverse
        // of that relation; implemented the physically correct way round (verified against a clean
        // synthetic min-jerk profile: this recovers the true D exactly when v0 is the true peak).
        val dMs = (1.875f * span / v0Deg * 1000f).toLong()
        predictedDurationMs = dMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    private fun gravitySteady(): Boolean = gSigmaEma < gravitySteadySigma

    private fun dtMsSince(nowNs: Long): Long {
        val dt = if (lastTickNs == Long.MIN_VALUE) 0L else (nowNs - lastTickNs) / 1_000_000L
        lastTickNs = nowNs
        return dt.coerceAtLeast(0L)
    }

    private fun tick(nowNs: Long): Estimate {
        val dt = dtMsSince(nowNs)
        if (phase == Phase.AT_CLOSED || phase == Phase.AT_FLAT) {
            val target = if (phase == Phase.AT_CLOSED) CLOSE_STEP0_ANGLE else OPEN_STEP180_ANGLE
            angleDeg = follower.update(dt, target, outputTauMs)
            confidence = 1f
            source = "step"
            return Estimate(angleDeg, confidence, source, nowNs)
        }
        val elapsedMs = if (anchorNs == Long.MIN_VALUE) 0L else (nowNs - anchorNs) / 1_000_000L
        val modelAngle = fromAngle + (toAngle - fromAngle) * minJerk(elapsedMs.toFloat() / predictedDurationMs.toFloat())
        val magAvailable = !lastMagnetAngle.isNaN()
        // Untrusted gyro (b): "fall back to the trajectory model + magnet trend" is implemented as
        // the magnet trend *leading* directly (clamped to the anchor band — it is itself already a
        // sample-rate estimate of the angle, not merely a correction), with the timer-only min-jerk
        // model as the last resort when no magnet reading has arrived yet. A model with a badly
        // guessed duration (this fixture's IMU-half-stationary transitions run 0.3-6 s against a
        // 0.7-0.9 s default/learned prior) must never be allowed to anchor the estimate for seconds
        // while a perfectly good magnet reading is sitting right there.
        val untrustedBase = if (magAvailable) lastMagnetAngle.coerceIn(min(fromAngle, toAngle), max(fromAngle, toAngle)) else modelAngle
        val base = if (gyroTrustedFlag) gyroIntegralAngle else untrustedBase
        var fused = base
        if (gyroTrustedFlag) {
            // The magnet is now only a slow *corrector* on the gyro path (catches gyro bias/drift);
            // bounded against `base` (not the model) so it cannot be smuggled toward a stale model.
            val magTarget = (if (magAvailable) lastMagnetAngle else base).coerceIn(base - magnetBoundDeg, base + magnetBoundDeg)
            magCorrectorDeg += ((magTarget - base) - magCorrectorDeg) * (1f - exp(-dt / magnetTauMs))
            fused = base + magCorrectorDeg
        } else {
            magCorrectorDeg = 0f // reset so it starts clean if/when the gyro becomes trusted again
        }
        // Monotonicity is only enforced on the smooth paths (gyro integral, or the min-jerk model
        // when no magnet is available): a raw magnet sample is noisy enough (~9 deg RMS) that
        // clamping the *whole rest of the transition* to one unlucky low (opening) or high
        // (closing) sample would lock in that sample's error instead of averaging it out — worse
        // than not guarding at all, and worse than today's magnitude-only v2 (which does not).
        if (gyroTrustedFlag || !magAvailable) {
            fused = if (phase == Phase.OPENING) max(fused, extreme) else min(fused, extreme)
        }
        extreme = fused
        angleDeg = follower.update(dt, fused, outputTauMs)
        confidence = if (gyroTrustedFlag) 0.85f else if (magAvailable) 0.6f else 0.4f
        source = if (gyroTrustedFlag) "gyro" else if (magAvailable) "magnet" else "model"
        return Estimate(angleDeg, confidence, source, nowNs)
    }

    companion object {
        // Fold 8 defaults, tuned against HingeCalibration.FOLD8_BX transitions (2026-09-15
        // recording). This class is only fed real angles while HINGE_FUSION_V3 is on AND
        // PoseRepository's magnetometer path is registered, which it is not for a device with
        // no HingeCalibration.forModel table — so these anchors, like HingeStepGate's, have not
        // needed a per-model variant yet. They are named constants rather than DeviceProfile
        // fields (this module cannot depend on :app's DeviceProfile) so a future model would add
        // its own set here and PoseRepository would pick by device model, same pattern as
        // HingeCalibration.forModel.
        const val OPEN_STEP90_ANGLE = 42f
        const val OPEN_STEP180_ANGLE = 168f
        const val CLOSE_STEP90_ANGLE = 140f
        const val CLOSE_STEP0_ANGLE = 10f
        /** Start-from angle for [releaseClosingFromMotion] (Morph v3 e): just inside flat. */
        const val CLOSING_RELEASE_ANGLE = 172f

        const val DEFAULT_OPENING_DURATION_MS = 900L
        const val DEFAULT_CLOSING_DURATION_MS = 700L
        const val MIN_DURATION_MS = 150L
        const val MAX_DURATION_MS = 4000L

        /** Below this the "initial gyro speed" is noise, not a real swing; keep the learned/default D. */
        const val MIN_V0_RAD_S = 0.15f

        /**
         * ~50 ms settle (see [CriticallyDampedFollower]) for the shared output stage: short enough
         * that tracking a fast fold (peak ~400 deg/s on a 0.3 s transition) does not itself cost
         * several degrees of lag, long enough to hide per-sample sensor jitter.
         */
        const val DEFAULT_OUTPUT_TAU_MS = 15f
        const val DEFAULT_MAGNET_TAU_MS = 500f
        const val DEFAULT_MAGNET_BOUND_DEG = 25f
        const val DEFAULT_GYRO_TRUST_THRESHOLD_RAD_S = 0.15f
        /** Consecutive gyro samples the trust condition must hold before [gyroTrustedFlag] flips on. */
        const val TRUST_CONSECUTIVE_SAMPLES = 5
        /**
         * Rate of change of gravity, sum of |Δg| (m/s²) per second, above which gravity counts as
         * "not steady" — dt-normalized so it does not depend on the caller's sampling rate. A
         * genuine fold swings gravity by up to ~2*9.8 m/s² over roughly a second (tens of units);
         * sensor jitter at rest is well under 1.
         */
        const val DEFAULT_GRAVITY_STEADY_SIGMA = 3f
        /** Deg of accumulated flat-rest closing rotation needed before [releaseClosingFromMotion] fires. */
        const val FLAT_CLOSE_RELEASE_DEG = 5f
        /** Cap on a single gyro sample's dt (100 ms): past this, treat it as a gap, not real rotation time. */
        const val MAX_GYRO_DT_NS = 100_000_000L

        private const val GRAVITY_EMA_ALPHA = 0.3f
        private const val FLAT_BIAS_EMA_ALPHA = 0.05f
        /** Slow leak on the flat-rest integral so a long, quiet hold never accumulates spurious drift. */
        private const val FLAT_INTEGRAL_DECAY = 0.999f

        /** Minimum-jerk position fraction (quintic smootherstep): 0 at u<=0, 1 at u>=1, peak rate 1.875 at u=0.5. */
        fun minJerk(uRaw: Float): Float {
            val u = uRaw.coerceIn(0f, 1f)
            return u * u * u * (u * (u * 6f - 15f) + 10f)
        }
    }
}
