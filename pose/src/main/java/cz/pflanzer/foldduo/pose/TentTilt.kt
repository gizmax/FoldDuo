package cz.pflanzer.foldduo.pose

import kotlin.math.abs
import kotlin.math.atan2

/** Which way the phone tilted about the hinge axis, relative to the orientation it rested in
 * when Tent was entered. Sign convention is unverified on a real device (IDEAS.md B48 tent-tilt
 * is untested hardware, same caveat as the rest of this file's callers): [FORWARD] is defined as
 * the cover half tipping away from whoever is looking at it (gz growing relative to rest). */
enum class TiltDirection { FORWARD, BACKWARD }

/**
 * B48 "Pant jako ovladač": while [FoldPose.Tent] holds, tilting the whole propped-up phone about
 * the hinge axis is a second input (media transport control, IDEAS.md B48) independent of the
 * squeeze gesture above. Pure and main-thread only, no Android types; [PoseRepository] feeds it
 * from the same gravity samples [PoseClassifier] already uses and republishes into
 * [PoseSnapshot.tentTiltSeq] / [PoseSnapshot.tentTiltDir].
 *
 * The hinge axis is device y (same axis [OpeningMotionDetector] reads off the gyroscope), so a
 * rotation about it moves gravity within the device's x/z plane; [rest] captures that plane's
 * angle the moment Tent is (re-)entered and every later sample is compared against it, wrapped to
 * `[-180, 180]` so the rest orientation never has to be exactly 0. Crossing more than
 * [THRESHOLD_DEG] away from rest fires [onPose], rate-limited to at most one call per
 * [MIN_INTERVAL_MS] regardless of how far past the threshold the tilt goes — a held tilt does not
 * repeat every sample, only every [MIN_INTERVAL_MS] while still past the threshold.
 */
class TentTiltDetector {
    private var restAngleDeg: Float? = null
    private var lastFireMs = Long.MIN_VALUE / 2

    /** Feed the current pose and gravity's x/z components; call on every pose evaluation (leaving
     * [FoldPose.Tent] forgets the rest orientation, so re-entering captures a fresh one). Returns
     * a direction only on the samples that actually cross [THRESHOLD_DEG] past rate-limiting. */
    fun onPose(nowMs: Long, pose: FoldPose, gx: Float, gz: Float): TiltDirection? {
        if (pose != FoldPose.Tent) { restAngleDeg = null; return null }
        val angle = Math.toDegrees(atan2(gz.toDouble(), gx.toDouble())).toFloat()
        val rest = restAngleDeg
        if (rest == null) { restAngleDeg = angle; return null }
        val delta = wrapDeg(angle - rest)
        if (abs(delta) < THRESHOLD_DEG) return null
        if (nowMs - lastFireMs < MIN_INTERVAL_MS) return null
        lastFireMs = nowMs
        return if (delta > 0) TiltDirection.FORWARD else TiltDirection.BACKWARD
    }

    companion object {
        const val THRESHOLD_DEG = 12f
        const val MIN_INTERVAL_MS = 1_000L
        private fun wrapDeg(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
