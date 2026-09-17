package cz.pflanzer.foldduo.pose

/**
 * The quantized `hinge_angle` step is authoritative at the ends; the magnetometer estimate only
 * interpolates inside the 90 step's band.
 *
 * Why: with the phone open and resting at 180 the Earth field alone swings raw bx by ~40 uT
 * when the phone is tilted in the hand (2026-09-15 21:50 log: est 85–180° while the step sat at
 * 180), which is more than half of the table's range, so the left half frosted on a mere tilt.
 * Until the Earth-field compensation is trustworthy, "step says 180" means flat and "step says
 * 0" means closed, whatever the magnet reads. Inside the 90 band the estimate is clamped to
 * [MIN_MID, MAX_MID] so an Earth-field excursion can never fake a full close or a full open.
 *
 * "Zavírání jako Duo" (17. 9. noc): the one exception to the hard 180 pin is [closingOnsetDeg]
 * ([ClosingOnsetDetector]'s bridged estimate) — while it is not NaN and the step still reads Flat
 * (or has not reported yet), it is returned instead of the pin, so a confirmed closing motion can
 * bridge the angle from ~172° down toward the Mid band's own clamp *before* the raw HAL step
 * actually transitions off Flat (which it does at ~145°). [PoseRepository] passes NaN whenever
 * the detector is not active, which reduces this to exactly the old behaviour.
 */
object HingeStepGate {
    /** Fold 8 defaults, fitted against [HingeCalibration.FOLD8_BX]'s clamp band. A device with
     * no magnetometer table for its model never reaches [apply] with a non-NaN [estimateDeg]
     * ([PoseRepository] skips the whole estimator for it), so these two never need a per-model
     * override in practice yet; [apply] still takes them as parameters for when one does. */
    const val MIN_MID = 20f
    const val MAX_MID = 165f

    /**
     * @param stepDeg the last `hinge_angle` step (0/90/180) or NaN before any.
     * @param closingOnsetDeg [ClosingOnsetDetector.angleDeg]'s live bridge, or NaN while it is
     * not active (the default, and the whole of the old behaviour).
     */
    fun apply(stepDeg: Float, estimateDeg: Float, minMid: Float = MIN_MID, maxMid: Float = MAX_MID,
        closingOnsetDeg: Float = Float.NaN): Float {
        if (!closingOnsetDeg.isNaN() && (stepDeg.isNaN() || stepDeg >= 135f)) {
            return closingOnsetDeg.coerceIn(minMid, 180f)
        }
        return when {
            estimateDeg.isNaN() -> estimateDeg
            stepDeg.isNaN() -> estimateDeg
            stepDeg <= 45f -> 0f
            stepDeg >= 135f -> 180f
            else -> estimateDeg.coerceIn(minMid, maxMid)
        }
    }
}
