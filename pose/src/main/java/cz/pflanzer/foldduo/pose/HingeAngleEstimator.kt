package cz.pflanzer.foldduo.pose

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Monotone calibration table hinge angle (deg) -> magnetometer feature (uT).
 *
 * The Fold 8 closure magnets sit in the half without the IMU; as the hinge opens their
 * field at the magnetometer grows monotonically (bx -197 uT closed -> -260 uT open on
 * the 2026-09-15 recording), so one piecewise-linear table inverts to an angle.
 * [angleOf] is the inverse (clamped to the table's range); [valueAt] the forward lookup;
 * [slopeAt] |dV/dA| in uT/deg, which maps sensor noise to angular resolution.
 */
class HingeCalibration(angles: FloatArray, values: FloatArray) {
    val angles: FloatArray
    val values: FloatArray
    val increasing: Boolean
    private val invValues: FloatArray
    private val invAngles: FloatArray

    init {
        require(angles.size == values.size && angles.size >= 2) { "need >= 2 knots" }
        val order = angles.indices.sortedBy { angles[it] }
        this.angles = FloatArray(angles.size) { angles[order[it]] }
        this.values = FloatArray(values.size) { values[order[it]] }
        increasing = this.values.last() >= this.values.first()
        // The inverse needs strictly monotone knots: collapse flat runs to their mid angle.
        val iv = ArrayList<Float>(); val ia = ArrayList<Float>()
        var i = 0
        while (i < this.values.size) {
            var j = i
            while (j + 1 < this.values.size && this.values[j + 1] == this.values[i]) j++
            iv.add(this.values[i]); ia.add((this.angles[i] + this.angles[j]) / 2f)
            i = j + 1
        }
        if (!increasing) { iv.reverse(); ia.reverse() }
        invValues = iv.toFloatArray(); invAngles = ia.toFloatArray()
        for (k in 1 until invValues.size) require(invValues[k] > invValues[k - 1]) { "table is not monotone" }
    }

    val minValue: Float get() = min(values.first(), values.last())
    val maxValue: Float get() = max(values.first(), values.last())

    fun valueAt(angleDeg: Float): Float = interp(angleDeg, angles, values)

    /**
     * This table's shape stretched so that its 0 and 180 knots read [closedValue] and
     * [openValue]: the per-device self-calibration from the two rests (the shape between them
     * stays the default's until a device has its own transitions fitted). Refuses a pair that
     * flips or collapses the table (returns this).
     */
    fun rescaledTo(closedValue: Float, openValue: Float): HingeCalibration {
        val v0 = valueAt(0f); val v180 = valueAt(180f)
        if (!closedValue.isFinite() || !openValue.isFinite() || v180 == v0) return this
        val gain = (openValue - closedValue) / (v180 - v0)
        if (gain <= 0.2f || gain >= 5f) return this
        return HingeCalibration(angles.copyOf(), FloatArray(values.size) { closedValue + (values[it] - v0) * gain })
    }
    fun angleOf(value: Float): Float = interp(value, invValues, invAngles)

    /** |dV/dA| (uT/deg): secant over the 20 deg around [angleDeg], clamped to the table. */
    fun slopeAt(angleDeg: Float): Float {
        val a0 = max(angleDeg - 10f, angles.first()); val a1 = min(angleDeg + 10f, angles.last())
        return if (a1 > a0) abs(valueAt(a1) - valueAt(a0)) / (a1 - a0) else Float.NaN
    }

    companion object {
        /**
         * Fits a table from (angleDeg, feature) pairs: 10-degree bin medians made monotone
         * with pool-adjacent-violators; [closedRest]/[openRest] add the 0/180 knots measured at
         * rest (the truth stream only samples while the hinge moves, so the bins miss them).
         */
        fun fit(pairs: List<Pair<Float, Float>>, closedRest: Float? = null, openRest: Float? = null, binDeg: Float = 10f): HingeCalibration {
            require(pairs.size >= 3) { "need >= 3 pairs" }
            val bins = sortedMapOf<Int, MutableList<Float>>()
            for ((a, v) in pairs) bins.getOrPut((a.coerceIn(0f, 179.999f) / binDeg).toInt()) { mutableListOf() }.add(v)
            val keys = bins.keys.toList()
            val angles = keys.map { it * binDeg + binDeg / 2f }.toMutableList()
            val meds = keys.map { median(bins[it]!!) }
            val weights = keys.map { bins[it]!!.size.toFloat() }
            val increasing = spearman(pairs.map { it.first }, pairs.map { it.second }) > 0
            val values = pav(meds, weights, increasing).toMutableList()
            if (closedRest != null && closedRest.isFinite()) {
                angles.add(0, 0f); values.add(0, if (increasing) min(closedRest, values[0]) else max(closedRest, values[0]))
            }
            if (openRest != null && openRest.isFinite()) {
                angles.add(180f); values.add(if (increasing) max(openRest, values.last()) else min(openRest, values.last()))
            }
            return HingeCalibration(angles.toFloatArray(), values.toFloatArray())
        }

        /** Pool-adjacent-violators isotonic regression (weighted means). */
        fun pav(ys: List<Float>, weights: List<Float>, increasing: Boolean): List<Float> {
            val blocks = ys.indices.map { floatArrayOf(ys[it], weights[it], 1f) }.toMutableList() // mean, weight, count
            var i = 0
            while (i < blocks.size - 1) {
                val a = blocks[i]; val b = blocks[i + 1]
                val bad = if (increasing) a[0] > b[0] else a[0] < b[0]
                if (bad) {
                    val tw = a[1] + b[1]
                    blocks[i] = floatArrayOf((a[0] * a[1] + b[0] * b[1]) / tw, tw, a[2] + b[2])
                    blocks.removeAt(i + 1)
                    i = max(i - 1, 0)
                } else i++
            }
            return blocks.flatMap { blk -> List(blk[2].toInt()) { blk[0] } }
        }

        fun median(xs: List<Float>): Float {
            val s = xs.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
        }

        fun spearman(x: List<Float>, y: List<Float>): Float {
            fun ranks(v: List<Float>): FloatArray {
                val order = v.indices.sortedBy { v[it] }; val r = FloatArray(v.size)
                var i = 0
                while (i < order.size) {
                    var j = i
                    while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
                    for (k in i..j) r[order[k]] = (i + j) / 2f + 1f
                    i = j + 1
                }
                return r
            }
            val rx = ranks(x); val ry = ranks(y); val n = rx.size
            val mx = rx.average(); val my = ry.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in 0 until n) { sxy += (rx[i] - mx) * (ry[i] - my); sxx += (rx[i] - mx) * (rx[i] - mx); syy += (ry[i] - my) * (ry[i] - my) }
            return if (sxx == 0.0 || syy == 0.0) Float.NaN else (sxy / sqrt(sxx * syy)).toFloat()
        }

        internal fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
            if (x <= xs.first()) return ys.first()
            if (x >= xs.last()) return ys.last()
            var i = 1
            while (xs[i] < x) i++
            val x0 = xs[i - 1]; val x1 = xs[i]
            return if (x1 == x0) ys[i - 1] else ys[i - 1] + (ys[i] - ys[i - 1]) * (x - x0) / (x1 - x0)
        }

        /**
         * DEVICE-SPECIFIC default: Galaxy Z Fold 8 SM-F971B, uncalibrated magnetometer x axis
         * (ak0991x_0), fitted on `pose/testdata/20260915-174608.jsonl` against the vendor HAL
         * lid-angle truth (13 fold transitions on a table, tools/hinge_fit.py). The 0 and 180
         * knots are the rest values (-197.4 / -260.4 uT), the rest are 10-degree bin medians.
         * It includes the sensor's hard-iron offset and the Earth field of that table and
         * orientation; a per-device self-calibration from rest anchors (0/180 step + still)
         * replaces the endpoints and, later, the whole table on the device.
         */
        val FOLD8_BX = HingeCalibration(
            floatArrayOf(0f, 5f, 15f, 25f, 35f, 45f, 55f, 65f, 75f, 85f, 95f, 105f, 115f, 125f, 135f, 145f, 155f, 165f, 175f, 180f),
            floatArrayOf(-197.4f, -203.0f, -208.3f, -216.1f, -223.6f, -229.0f, -230.7f, -235.5f, -238.2f, -240.0f, -242.2f, -244.2f,
                -245.2f, -247.0f, -248.0f, -249.8f, -251.9f, -254.5f, -255.7f, -260.4f),
        )

        /** Same recording, feature |B| (uT); slightly worse than [FOLD8_BX] in leave-one-out (9.2 vs 8.6 deg RMS). */
        val FOLD8_MAGNITUDE = HingeCalibration(
            floatArrayOf(0f, 5f, 15f, 25f, 35f, 45f, 55f, 65f, 75f, 85f, 95f, 105f, 115f, 125f, 135f, 145f, 155f, 165f, 175f, 180f),
            floatArrayOf(204.6f, 210.9f, 216.3f, 222.9f, 228.8f, 233.4f, 234.8f, 239.0f, 241.6f, 242.7f, 245.4f, 247.1f, 247.6f, 249.2f,
                249.9f, 252.0f, 253.9f, 256.5f, 258.3f, 262.5f),
        )

        /**
         * Model-keyed lookup for [PoseRepository]: a magnetometer table is per-device (it bakes
         * in that device's closure magnets, hard-iron offset and the Earth field of whichever
         * recording fitted it), so a device this launcher has never been calibrated on — a
         * Galaxy Z Fold 7 (`SM-F966`) included, which has a different magnet layout and no
         * table of its own yet — must not silently reuse the Fold 8's. Returns `null` for any
         * unrecognised or uncalibrated `Build.MODEL`; the caller's contract for that is to leave
         * the continuous-angle estimator off entirely (`hingeAngleDeg` stays NaN, the 0/90/180
         * step sensor keeps driving the morph — PLAN.md fact 4's guaranteed path).
         */
        fun forModel(model: String): HingeCalibration? = when {
            model.startsWith("SM-F971") -> FOLD8_BX // Galaxy Z Fold 8
            else -> null
        }
    }
}

/**
 * Continuous hinge angle from the uncalibrated magnetometer (pure Kotlin, no Android).
 *
 * Inputs: [onMagUncalibrated] samples (TYPE_MAGNETIC_FIELD_UNCALIBRATED x/y/z, uT), the
 * quantized hinge step events ([onHingeStep], 0/90/180 with their sensor timestamps) and,
 * optionally, the gyro rate about the hinge axis on the IMU half ([onGyroHingeRate]).
 *
 * Model: feature (bx or |B|) minus an offset, inverted through a monotone [HingeCalibration].
 * The offset absorbs whatever additive field changed since calibration (Earth field after
 * the phone was turned, hard-iron drift) and is re-anchored at *rests*: a 0 or 180 step
 * followed by >= [restMinNs] of the feature staying within [restSigmaUt] sigma. Anchoring at
 * the step event itself is deliberately not done: on this HAL the step's angle lags the
 * field by tens of ms and, at the steep closed end of the table (0.7 uT/deg), a 6 deg anchor
 * error becomes 4 uT = 20-30 deg at 90 deg (tools/hinge_fit.py, variants anch-*).
 *
 * The gyro, when fed, is integrated between magnetometer samples and the magnetometer pulls
 * the state back with time constant [blendTauNs] (complementary filter). Without gyro
 * samples the output is the magnetometer estimate directly. On the table with the IMU half
 * still the gyro measures ~0 and contributes nothing; hand-held it adds the fold's own
 * motion, which the magnetometer alone cannot separate from the Earth-field change.
 *
 * Leave-one-transition-out on the 2026-09-15 recording (HingeAngleEstimatorTest): 9.3 deg RMS
 * pooled over 202 truth samples, median 8.8 deg per transition, 20 deg on the 0.3 s snap-close
 * (the raw table without any anchoring: 8.6 deg pooled). The Earth field while the phone
 * rotates mid-fold (up to 71 uT for a 90 deg turn = worse than useless) is not this class's
 * business: the caller subtracts it before feeding samples ([EarthFieldCompensator], learned
 * from the rests this class reports through [onRest]; PoseRepository wires the two).
 *
 * A consumer may drive UI from [angleDeg] once [confidence] >= [CONFIDENT] and [anchors] >= 1.
 */
class HingeAngleEstimator(
    calibration: HingeCalibration = HingeCalibration.FOLD8_BX,
    val feature: Feature = Feature.BX,
    private val restMinNs: Long = 1_000_000_000L,
    private val restSettleNs: Long = 300_000_000L,
    private val restSigmaUt: Float = 1f,
    private val blendTauNs: Long = 30_000_000L,
    private val gyroFreshNs: Long = 100_000_000L,
    private val anchorAtRest: Boolean = true,
    /** B19: minimum stillness for a "micro-rest" ([onMicroRest], any angle, Earth-field only). */
    private val microRestMinNs: Long = 400_000_000L,
    /** Minimum spacing between two micro-rest firings, so one long stillness fires once, not at every sample. */
    private val microRestDebounceNs: Long = 1_000_000_000L,
) {
    enum class Feature { BX, MAGNITUDE }

    class Estimate(val angleDeg: Float, val confidence: Float, val timestampNs: Long, val offsetUt: Float)

    /** One confirmed rest: the hinge at [angleDeg] (0 or 180), the medians of the fed x/y/z over the rest window and of the feature. */
    class Rest(val angleDeg: Float, val timestampNs: Long, val x: Float, val y: Float, val z: Float, val feature: Float)

    /**
     * The table in use; replacing it (a per-device rescale from the learned rests) keeps the
     * state and re-anchors the offset against the new table at the last rest, if there was one.
     */
    var calibration: HingeCalibration = calibration
        set(value) {
            field = value
            if (!lastRestAngle.isNaN()) offsetUt = lastRestFeature - value.valueAt(lastRestAngle)
        }

    /** Called after every rest anchor with the rest's medians (self-calibration, Earth-field learning). */
    var onRest: ((Rest) -> Unit)? = null

    /**
     * Called after a "micro-rest": >= [microRestMinNs] of the feature holding steady (same sigma
     * test as [onRest]) at *any* angle, not just after a 0/180 step. [Rest.angleDeg] is this
     * class's own current estimate at that moment (table + offset, whatever they are right now),
     * not a ground truth — good enough to pair two micro-rests at roughly the same physical angle
     * for the Earth-field solve ([EarthFieldCompensator.learnAtMicroRest]), which is the only
     * consumer; never anchors the table offset (B19: pose/testdata/README.md, IDEAS.md B19).
     */
    var onMicroRest: ((Rest) -> Unit)? = null
    private var lastMicroRestNs = Long.MIN_VALUE

    /** The 0/180 step whose rest is awaited; NaN when none. */
    val pendingRestAngle: Float get() = restAngle

    /** Latest angle in degrees (0 closed .. 180 flat); NaN before the first magnetometer sample. */
    var angleDeg: Float = Float.NaN
        private set

    /** 0..1: 0 before any sample; lowered outside the table's range (0.2 at 20 uT beyond) and before the first rest anchor (0.6). */
    var confidence: Float = 0f
        private set

    /** Current additive offset (uT) applied to the feature before the table lookup. */
    var offsetUt: Float = 0f
        private set

    /** Number of rest anchors applied so far. */
    var anchors: Int = 0
        private set

    var lastTimestampNs: Long = Long.MIN_VALUE
        private set

    private var lastAnchorNs = Long.MIN_VALUE
    private var lastRestAngle = Float.NaN
    private var lastRestFeature = 0f
    private var lastGyroNs = Long.MIN_VALUE
    private var pendingGyroDeg = 0f
    private var blended = Float.NaN

    // rest candidate after a 0/180 step
    private var restAngle = Float.NaN
    private var restFromNs = Long.MIN_VALUE

    // ring buffer of recent (t, feature) for the rest window (2.56 s at 100 Hz)
    private val bufT = LongArray(256)
    private val bufV = FloatArray(256)
    private val bufX = FloatArray(256)
    private val bufY = FloatArray(256)
    private val bufZ = FloatArray(256)
    private var bufHead = 0
    private var bufSize = 0

    fun onMagUncalibrated(timestampNs: Long, x: Float, y: Float, z: Float): Estimate {
        val v = when (feature) { Feature.BX -> x; Feature.MAGNITUDE -> sqrt(x * x + y * y + z * z) }
        push(timestampNs, v, x, y, z)
        if (anchorAtRest && !restAngle.isNaN() && timestampNs - restFromNs >= restMinNs) tryAnchor(timestampNs)
        val mag = calibration.angleOf(v - offsetUt)
        val gyroFresh = lastGyroNs != Long.MIN_VALUE && timestampNs - lastGyroNs <= gyroFreshNs
        val out = if (gyroFresh && !blended.isNaN() && lastTimestampNs != Long.MIN_VALUE) {
            val dt = (timestampNs - lastTimestampNs).coerceAtLeast(0L)
            val k = 1f - exp(-dt.toFloat() / blendTauNs.toFloat())
            var cur = blended + pendingGyroDeg
            cur += k * (mag - cur)
            cur.coerceIn(0f, 180f)
        } else mag
        pendingGyroDeg = 0f
        blended = out
        angleDeg = out
        lastTimestampNs = timestampNs
        confidence = confidenceFor(v - offsetUt, timestampNs)
        if (onMicroRest != null) tryMicroRest(timestampNs, out)
        return Estimate(out, confidence, timestampNs, offsetUt)
    }

    /**
     * Fires [onMicroRest] once per stillness window (debounced by [microRestDebounceNs]),
     * independent of [restAngle]/[onHingeStep]: a device turned in the hand can pause anywhere.
     * Reuses [featureSigma]'s ring buffer; does not touch [offsetUt] or [anchors].
     */
    private fun tryMicroRest(nowNs: Long, currentAngleDeg: Float) {
        // lastMicroRestNs starts at Long.MIN_VALUE; `nowNs - MIN_VALUE` overflows (the same pitfall
        // PoseRepository's periodic angle log hit), so the "never fired yet" case is explicit.
        if (lastMicroRestNs != Long.MIN_VALUE && nowNs - lastMicroRestNs < microRestDebounceNs) return
        val sigma = featureSigma(nowNs, microRestMinNs)
        if (sigma.isNaN() || sigma >= restSigmaUt) return
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>(); val vs = ArrayList<Float>()
        for (i in 0 until bufSize) {
            val idx = (bufHead - 1 - i + bufT.size) % bufT.size
            if (nowNs - bufT[idx] > microRestMinNs) break
            xs.add(bufX[idx]); ys.add(bufY[idx]); zs.add(bufZ[idx]); vs.add(bufV[idx])
        }
        if (xs.size < 5) return
        lastMicroRestNs = nowNs
        onMicroRest?.invoke(Rest(currentAngleDeg, nowNs, HingeCalibration.median(xs), HingeCalibration.median(ys),
            HingeCalibration.median(zs), HingeCalibration.median(vs)))
    }

    /** Quantized TYPE_HINGE_ANGLE event (0/90/180) with its sensor timestamp. */
    fun onHingeStep(timestampNs: Long, stepDeg: Float) {
        if (stepDeg <= 45f) { restAngle = 0f; restFromNs = timestampNs + restSettleNs }
        else if (stepDeg >= 135f) { restAngle = 180f; restFromNs = timestampNs + restSettleNs }
        else restAngle = Float.NaN
    }

    /** Gyro rate about the hinge axis on the IMU half (rad/s, sign = opening positive). */
    fun onGyroHingeRate(timestampNs: Long, radPerSec: Float) {
        if (lastGyroNs != Long.MIN_VALUE && timestampNs > lastGyroNs) {
            pendingGyroDeg += Math.toDegrees(radPerSec.toDouble()).toFloat() * (timestampNs - lastGyroNs) / 1e9f
        }
        lastGyroNs = timestampNs
    }

    /** Sigma (uT) of the feature over the last [windowNs]; NaN with fewer than 10 samples. */
    fun featureSigma(nowNs: Long, windowNs: Long = restMinNs): Float {
        var n = 0; var sum = 0.0; var sum2 = 0.0
        for (i in 0 until bufSize) {
            val idx = (bufHead - 1 - i + bufT.size) % bufT.size
            if (nowNs - bufT[idx] > windowNs) break
            n++; sum += bufV[idx]; sum2 += bufV[idx].toDouble() * bufV[idx]
        }
        if (n < 10) return Float.NaN
        val mean = sum / n
        return sqrt(max(sum2 / n - mean * mean, 0.0)).toFloat()
    }

    private fun tryAnchor(nowNs: Long) {
        val sigma = featureSigma(nowNs)
        if (sigma.isNaN() || sigma >= restSigmaUt) return
        val vals = ArrayList<Float>(); val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>()
        for (i in 0 until bufSize) {
            val idx = (bufHead - 1 - i + bufT.size) % bufT.size
            if (nowNs - bufT[idx] > restMinNs) break
            vals.add(bufV[idx]); xs.add(bufX[idx]); ys.add(bufY[idx]); zs.add(bufZ[idx])
        }
        val angle = restAngle
        anchor(angle, HingeCalibration.median(vals), nowNs)
        restAngle = Float.NaN
        onRest?.invoke(Rest(angle, nowNs, HingeCalibration.median(xs), HingeCalibration.median(ys), HingeCalibration.median(zs), lastRestFeature))
    }

    /** Anchor the offset so the table passes through [featureValue] at [angleDeg]; counts as a rest anchor at [nowNs]. */
    fun anchor(angleDeg: Float, featureValue: Float, nowNs: Long) {
        offsetUt = featureValue - calibration.valueAt(angleDeg)
        lastRestAngle = angleDeg
        lastRestFeature = featureValue
        anchors++
        lastAnchorNs = nowNs
    }

    private fun confidenceFor(shifted: Float, nowNs: Long): Float {
        val outside = when {
            shifted < calibration.minValue -> calibration.minValue - shifted
            shifted > calibration.maxValue -> shifted - calibration.maxValue
            else -> 0f
        }
        // Past the table's ends the angle is clamped (beyond closed is closed); the confidence
        // declines over 20 uT: a slam-close bounces bx 6 uT past the closed rest for ~150 ms
        // (2026-09-15 recording) and must not drop the estimate below CONFIDENT (10 uT).
        val range = (1f - outside / 20f).coerceIn(0.2f, 1f)
        val anchor = if (lastAnchorNs == Long.MIN_VALUE) 0.6f else (1f - (nowNs - lastAnchorNs) / 120e9f * 0.4f).coerceIn(0.6f, 1f)
        return range * anchor
    }

    companion object {
        /**
         * Confidence at or above which a consumer may drive UI from [angleDeg]: within 10 uT of
         * the table's range (an excursion of > 20 uT outside it drops to 0.2) and at least anchored once at
         * a rest in this process ([anchors] >= 1; before that the confidence is 0.6 and the
         * offset is the table's own, i.e. another table's Earth field).
         */
        const val CONFIDENT = 0.5f
    }

    private fun push(t: Long, v: Float, x: Float, y: Float, z: Float) {
        bufT[bufHead] = t; bufV[bufHead] = v; bufX[bufHead] = x; bufY[bufHead] = y; bufZ[bufHead] = z
        bufHead = (bufHead + 1) % bufT.size
        if (bufSize < bufT.size) bufSize++
    }
}
