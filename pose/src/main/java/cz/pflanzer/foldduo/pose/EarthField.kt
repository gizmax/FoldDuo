package cz.pflanzer.foldduo.pose

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Unit-quaternion helpers on Android's rotation-vector layout `[x, y, z, w]` (pure Kotlin). */
object Quaternion {
    /**
     * Row-major 3x3 rotation matrix of the unit quaternion [q] (`[x, y, z, w]`; a 3-element
     * vector gets `w` from the unit norm). Maps device coordinates to world coordinates the way
     * `SensorManager.getRotationMatrixFromVector` does: `world = R * device`.
     */
    fun rotationMatrix(q: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]
        val w = if (q.size > 3) q[3] else sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-6f } ?: return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        return floatArrayOf(
            1f - 2f * (qy * qy + qz * qz), 2f * (qx * qy - qz * qw), 2f * (qx * qz + qy * qw),
            2f * (qx * qy + qz * qw), 1f - 2f * (qx * qx + qz * qz), 2f * (qy * qz - qx * qw),
            2f * (qx * qz - qy * qw), 2f * (qy * qz + qx * qw), 1f - 2f * (qx * qx + qy * qy),
        )
    }

    /** Quaternion `[x, y, z, w]` of a rotation by [angleRad] about the unit axis ([ax], [ay], [az]). */
    fun fromAxisAngle(ax: Float, ay: Float, az: Float, angleRad: Float): FloatArray {
        val n = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 0f } ?: return floatArrayOf(0f, 0f, 0f, 1f)
        val s = kotlin.math.sin(angleRad / 2f) / n
        return floatArrayOf(ax * s, ay * s, az * s, kotlin.math.cos(angleRad / 2f))
    }

    /** `R * v` (device -> world). */
    fun rotate(r: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
        r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
        r[6] * v[0] + r[7] * v[1] + r[8] * v[2],
    )

    /** `Rᵀ * v` (world -> device). */
    fun rotateInverse(r: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        r[0] * v[0] + r[3] * v[1] + r[6] * v[2],
        r[1] * v[0] + r[4] * v[1] + r[7] * v[2],
        r[2] * v[0] + r[5] * v[1] + r[8] * v[2],
    )
}

/**
 * Earth-field compensation for the magnetometer hinge estimate (pure Kotlin), v2.
 *
 * Model: `B_device(t) = M(angle) + R(q_t)ᵀ · E_world`, with `M` the closure magnets' field in
 * device coordinates (what [HingeCalibration] tabulates) and `E_world` the Earth field, constant
 * in the world frame of `TYPE_GAME_ROTATION_VECTOR` (no magnetometer inside it, so the 250 uT
 * magnet cannot corrupt the attitude). `bx` fed to the estimator is `B − R(q)ᵀ · E_world`.
 *
 * `E_world` is learned from *observations*: a hinge angle held still for some minimum time gives
 * one `(angleDeg, q, B)` triple, via [learnAtRest] (0/180 anchors, contributes to the table's
 * offset too, `HingeAngleEstimator`'s business) or [learnAtMicroRest] (any angle, mid-transition,
 * >= ~400 ms still, Earth-field-only — never touches the calibration table). Two observations at
 * (nearly) the same angle but different orientations give `B_i − B_j = (R_iᵀ − R_jᵀ) · E_world`,
 * linear in `E_world` ([angleToleranceDeg] decides "nearly the same": 0/180 rests always match
 * each other exactly, so this is a pure generalisation, not a behaviour change for rest-only
 * data). The solver takes every such pair over the kept observations ([maxObservations] most
 * recent, up to [MAX_OBSERVATIONS_CAP], younger than [maxAgeNs] because the game rotation
 * vector's yaw drifts ~1 deg/min), weights each pair by how much the two attitudes actually
 * differ (a pair 90 deg apart is worth much more than one a few degrees apart — see [solve]),
 * and solves the 3x3 normal equations with a small ridge [lambda]. With one orientation only
 * (every rest on the same table) the pairs carry no information, the ridge shrinks `E_world` to
 * zero and the compensation is the identity: the table then absorbs the Earth field of that
 * orientation as before. Components of `E_world` along the axis the rests were rotated about are
 * unobservable, but those components do not change `B_device` under that rotation either, so
 * leaving them at zero costs nothing.
 *
 * The literal plan in pose/testdata/README.md (`E = R(q)·(B − M_rest)` from one rest) needs an
 * Earth-free `M_rest`, which one orientation cannot give: with `M_rest` learned at the first
 * rest the formula collapses to the estimator's own rest offset. Hence the pairwise solve.
 * Expected residual: |E|·(attitude error) as before, ~2–12 deg; re-learned at every rest.
 *
 * Sanity ([quality]): a solve is only as good as its data. Real Czechia Earth field is ~48 uT
 * (horizontal ~20, vertical ~44); a resolved magnitude outside [MIN_TRUSTED_UT]..[MAX_TRUSTED_UT]
 * or a pairwise fit [residualUt] above [MAX_RESIDUAL_UT] means the orientation coverage was too
 * thin or too noisy to trust (this is exactly the 2026-09-15 field problem: |E| = 18–22 uT from
 * 6 same-orientation-ish rests, an under-determined minimum-norm solution masquerading as a fit).
 * [quality] is purely advisory here — [compensate]/[deviceEarth] still apply whatever
 * [earthWorld] holds, unit-tested against known-partial solutions ([EarthFieldCompensatorTest]
 * `rotationAboutOneAxisOnly...`, where a real, correct fit only ever recovers 2 of 3 axes and a
 * magnitude check would wrongly veto it) — callers that want the "fall back to v1 behaviour"
 * gate (PoseRepository) check `quality == GOOD` themselves before calling [compensate].
 */
class EarthFieldCompensator(
    maxObservations: Int = 12,
    private val maxAgeNs: Long = 5L * 60L * 1_000_000_000L,
    private val lambda: Float = 0.02f,
    private val angleToleranceDeg: Float = 2f,
) {
    // Pairing is O(n^2); a caller-supplied ring larger than the cap is clamped so it stays cheap.
    private val maxObservations: Int = min(maxObservations, MAX_OBSERVATIONS_CAP)

    class Observation(val timestampNs: Long, val stepDeg: Float, val rotation: FloatArray, val field: FloatArray)

    /** Sanity classification of the current [earthWorld] solve; see the class doc. */
    enum class Quality { NONE, LOW_DIVERSITY, OUT_OF_RANGE, HIGH_RESIDUAL, GOOD }

    private val obs = ArrayList<Observation>()

    /** Solved Earth field in world coordinates (uT); zero until orientations differ between observations. */
    val earthWorld = FloatArray(3)

    /** |[earthWorld]| in uT. */
    val magnitude: Float get() = sqrt(earthWorld[0] * earthWorld[0] + earthWorld[1] * earthWorld[1] + earthWorld[2] * earthWorld[2])

    /** Number of rests/micro-rests the current solution is built on ([maxObservations] ring, newest last). */
    val observations: Int get() = obs.size

    /** Number of same-angle (within [angleToleranceDeg]) observation pairs in the last solve. */
    var pairs: Int = 0
        private set

    /**
     * Orientation diversity of the last solve: the middle diagonal entry of the (pair-weighted)
     * normal matrix (before the ridge), ~`2 − 2cos(Δφ)` for one pair rotated by Δφ about another
     * axis. One rotation axis observes two components of `E_world` (the third is the harmless
     * one along the axis), so the middle entry, not the smallest, says whether the data
     * determined anything; below [MIN_DIVERSITY] the solution is the ridge's zero.
     */
    var diversity: Float = 0f
        private set

    /**
     * RMS residual (uT) of `B_i − B_j` against `(R_iᵀ − R_jᵀ) · earthWorld` over the pairs used in
     * the last solve; NaN with no pairs. High values mean the observations are not well explained
     * by a single constant world-frame field (noisy rests, stale/drifted attitude, or a table
     * value that moved between the two observations) — see [quality].
     */
    var residualUt: Float = Float.NaN
        private set

    /** True when the observations spanned enough orientation for [earthWorld] to come from the data. */
    val resolved: Boolean get() = diversity >= MIN_DIVERSITY

    /** Sanity-gated read of the fit: see the class doc and [Quality]. */
    val quality: Quality
        get() = when {
            obs.isEmpty() || pairs == 0 -> Quality.NONE
            !resolved -> Quality.LOW_DIVERSITY
            magnitude < MIN_TRUSTED_UT || magnitude > MAX_TRUSTED_UT -> Quality.OUT_OF_RANGE
            !residualUt.isNaN() && residualUt > MAX_RESIDUAL_UT -> Quality.HIGH_RESIDUAL
            else -> Quality.GOOD
        }

    /**
     * One rest: hinge at [stepDeg] (0 or 180), attitude [q] (`[x, y, z, w]`), median raw field
     * [field] (uT). Contributes to [earthWorld] like every observation; the caller (the
     * estimator's per-device table offset) is unaffected by this class. Re-solves.
     */
    fun learnAtRest(timestampNs: Long, stepDeg: Float, q: FloatArray, field: FloatArray) = learn(timestampNs, stepDeg, q, field)

    /**
     * A "micro-rest": the hinge held still for a few hundred ms at *any* angle mid-transition
     * (not a 0/180 step). Earth-field-only, same as [learnAtRest] otherwise — this class never
     * touches the calibration table, so there is nothing to guard against here; the guard lives
     * in the caller, which must not treat this as a table-offset anchor. Re-solves.
     */
    fun learnAtMicroRest(timestampNs: Long, angleDeg: Float, q: FloatArray, field: FloatArray) = learn(timestampNs, angleDeg, q, field)

    private fun learn(timestampNs: Long, stepDeg: Float, q: FloatArray, field: FloatArray) {
        obs.removeAll { timestampNs - it.timestampNs > maxAgeNs || it.timestampNs > timestampNs }
        obs.add(Observation(timestampNs, stepDeg, Quaternion.rotationMatrix(q), field.copyOf(3)))
        while (obs.size > maxObservations) obs.removeAt(0)
        solve()
    }

    /** Forget every observation (a new calibration table, say). */
    fun reset() {
        obs.clear(); pairs = 0; diversity = 0f; residualUt = Float.NaN
        earthWorld.fill(0f)
    }

    /** The Earth field in device coordinates for attitude [q]: what to subtract from a raw sample. */
    fun deviceEarth(q: FloatArray): FloatArray = Quaternion.rotateInverse(Quaternion.rotationMatrix(q), earthWorld)

    /** [field] minus the Earth field at attitude [q]; the identity while nothing is learned. */
    fun compensate(q: FloatArray?, field: FloatArray): FloatArray {
        if (q == null || (earthWorld[0] == 0f && earthWorld[1] == 0f && earthWorld[2] == 0f)) return field.copyOf(3)
        val e = deviceEarth(q)
        return floatArrayOf(field[0] - e[0], field[1] - e[1], field[2] - e[2])
    }

    /**
     * Pairwise least squares, weighted by orientation diversity: a pair of attitudes 90 deg apart
     * pins `E_world` far better than two within a few degrees of each other (yaw drift, hand
     * jitter at "the same" rest), so it must count for more. Weight `w = (1 − cosΔφ) / 2` (0 at
     * identical attitude, 1 at 180 deg apart) — the same quantity [diversity] reports overall,
     * now applied per pair instead of once for the whole batch.
     */
    private fun solve() {
        val n = FloatArray(9); val g = FloatArray(3)
        pairs = 0
        var sumW = 0f
        val ams = ArrayList<FloatArray>(); val ds = ArrayList<FloatArray>()
        for (i in obs.indices) for (j in i + 1 until obs.size) {
            val a = obs[i]; val b = obs[j]
            if (abs(a.stepDeg - b.stepDeg) > angleToleranceDeg) continue
            pairs++
            // A = R_iᵀ − R_jᵀ (row-major), d = B_i − B_j
            val am = FloatArray(9) { k -> a.rotation[(k % 3) * 3 + k / 3] - b.rotation[(k % 3) * 3 + k / 3] }
            val d = floatArrayOf(a.field[0] - b.field[0], a.field[1] - b.field[1], a.field[2] - b.field[2])
            var dot = 0f
            for (k in 0 until 9) dot += a.rotation[k] * b.rotation[k]
            val cosPhi = ((dot - 1f) / 2f).coerceIn(-1f, 1f)
            val w = ((1f - cosPhi) / 2f).coerceIn(0.05f, 1f)
            sumW += w
            ams.add(am); ds.add(d)
            for (r in 0 until 3) for (c in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += am[k * 3 + r] * am[k * 3 + c]
                n[r * 3 + c] += w * s
            }
            for (r in 0 until 3) { var s = 0f; for (k in 0 until 3) s += am[k * 3 + r] * d[k]; g[r] += w * s }
        }
        diversity = floatArrayOf(n[0], n[4], n[8]).sorted()[1]
        // Scale the ridge by the mean pair weight, so down-weighting low-diversity pairs (all of
        // them, say, when every rest is within a few degrees of the others) does not also shrink
        // the *resolved* directions towards zero relative to an unweighted solve: the ridge must
        // stay a fixed fraction of the data's own scale, not of a scale the weights just shrank.
        val ridge = lambda * (if (pairs > 0) sumW / pairs else 1f)
        n[0] += ridge; n[4] += ridge; n[8] += ridge
        val det = n[0] * (n[4] * n[8] - n[5] * n[7]) - n[1] * (n[3] * n[8] - n[5] * n[6]) + n[2] * (n[3] * n[7] - n[4] * n[6])
        if (abs(det) < 1e-12f || pairs == 0) { earthWorld.fill(0f); residualUt = Float.NaN; return }
        val inv = floatArrayOf(
            (n[4] * n[8] - n[5] * n[7]) / det, (n[2] * n[7] - n[1] * n[8]) / det, (n[1] * n[5] - n[2] * n[4]) / det,
            (n[5] * n[6] - n[3] * n[8]) / det, (n[0] * n[8] - n[2] * n[6]) / det, (n[2] * n[3] - n[0] * n[5]) / det,
            (n[3] * n[7] - n[4] * n[6]) / det, (n[1] * n[6] - n[0] * n[7]) / det, (n[0] * n[4] - n[1] * n[3]) / det,
        )
        val e = Quaternion.rotate(inv, g)
        earthWorld[0] = e[0]; earthWorld[1] = e[1]; earthWorld[2] = e[2]
        // Unweighted RMS residual of every pair against the solved field (diagnostic only).
        var sq = 0.0; var count = 0
        for (k in ams.indices) {
            val am = ams[k]; val d = ds[k]
            for (r in 0 until 3) {
                var pred = 0f
                for (c in 0 until 3) pred += am[r * 3 + c] * earthWorld[c]
                val res = d[r] - pred
                sq += res.toDouble() * res; count++
            }
        }
        residualUt = if (count > 0) sqrt(sq / count).toFloat() else Float.NaN
    }

    companion object {
        /** `2 − 2cos(15°)` ≈ 0.068: one pair of rests 15 deg apart about an axis clear of this one. */
        const val MIN_DIVERSITY = 0.06f

        /** Real Czechia Earth field is ~48 uT; below this the solve is likely an under-determined minimum-norm artefact. */
        const val MIN_TRUSTED_UT = 30f

        /** Above this the solve is likely corrupted (magnetic interference near the rests, bad attitude). */
        const val MAX_TRUSTED_UT = 70f

        /** RMS pair residual (uT) above which the fit is not explained by a single constant world field. */
        const val MAX_RESIDUAL_UT = 10f

        /** Hard cap on [maxObservations]; a caller-supplied larger ring is clamped to keep the O(n^2) pairing cheap. */
        const val MAX_OBSERVATIONS_CAP = 24
    }
}
