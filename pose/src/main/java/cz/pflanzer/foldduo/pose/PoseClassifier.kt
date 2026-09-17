package cz.pflanzer.foldduo.pose

import kotlin.math.abs

/**
 * Thresholds calibrated on `pose/testdata/` (Galaxy Z Fold 8, 2026-09-13).
 * See `pose/testdata/README.md` for the measured gravity signatures.
 */
data class PoseThresholds(
    /**
     * Tent: the hinge lies horizontally on the table, so gravity has almost no
     * component along the hinge axis (device y): |gy| ≈ 0.1–0.2 measured.
     * A 90° tent puts only ~6.9 m/s² on x, so x alone is not a safe test.
     */
    val tentGyMax: Float = 3.5f,
    /** ...and the cover half is clearly not lying flat (|gx| ≈ 6.9 at 90°, 9.2 at ~60°). */
    val tentGxMin: Float = 4f,
    /** Tent must be still for this long; a phone being opened passes through the same angles. */
    val tentStillMsMin: Long = 400L,
    /**
     * Flip: closed with the cover facing the table. Lying on the camera plateau the
     * phone tilts, so gz is only −4…−6 (−6.8 flat). Anything clearly face-down counts.
     */
    val flipGzMax: Float = -3.5f,
    /** Stand: open, held/propped upright like a book (gy ≈ 7.9 measured; casual reading in hand is ~6). */
    val standGyMin: Float = 7.5f,
    /** Stand must be still, otherwise it is just an open phone in a hand. */
    val standStillMsMin: Long = 800L,
    /** After a panel swap or hinge step the morph window is InMotion. */
    val inMotionWindowMs: Long = 500L,
    /** Hysteresis: once in Tent/Stand, gravity may drift this much before the pose is dropped. */
    val holdSlack: Float = 1.5f,
)

/** Which discrete hinge step the public sensor last reported (0 / 90 / 180), or null. */
enum class HingeStep { Closed, Mid, Flat;
    companion object {
        fun of(deg: Float): HingeStep? = when {
            deg.isNaN() -> null
            deg < 45f -> Closed
            deg < 135f -> Mid
            else -> Flat
        }
    }
}

/**
 * Pure rule classifier over discrete inputs. The Fold 8 gives no continuous
 * hinge angle to third-party apps (PLAN.md fact 4), so the inputs are the
 * active physical panel, the last hinge step, gravity and time since the
 * last transition. No Android dependencies; tested against fixtures.
 */
class PoseClassifier(private val t: PoseThresholds = PoseThresholds()) {

    fun classify(
        panel: Panel,
        hingeDeg: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        msSinceTransition: Long,
        stillMs: Long = Long.MAX_VALUE,
        current: FoldPose? = null,
    ): FoldPose {
        if (msSinceTransition in 0 until t.inMotionWindowMs) return FoldPose.InMotion
        val step = HingeStep.of(hingeDeg)
        return when (panel) {
            Panel.Inner -> {
                val enter = gy >= t.standGyMin && stillMs >= t.standStillMsMin
                val hold = current == FoldPose.Stand && gy >= t.standGyMin - t.holdSlack
                if (enter || hold) FoldPose.Stand else FoldPose.Open
            }
            Panel.Cover, Panel.Unknown -> when (step) {
                HingeStep.Mid -> {
                    val shape = abs(gy) <= t.tentGyMax && abs(gx) >= t.tentGxMin
                    val hold = current == FoldPose.Tent &&
                        abs(gy) <= t.tentGyMax + t.holdSlack && abs(gx) >= t.tentGxMin - t.holdSlack
                    if ((shape && stillMs >= t.tentStillMsMin) || hold) FoldPose.Tent
                    else FoldPose.InMotion
                }
                HingeStep.Flat -> FoldPose.Open // swap not seen yet; treat as opening
                HingeStep.Closed, null -> if (gz <= t.flipGzMax) FoldPose.Flip else FoldPose.Closed
            }
        }
    }

    fun classify(sample: PoseSample, msSinceTransition: Long): FoldPose = classify(
        sample.panel, sample.hingeDeg,
        sample.gravity.getOrElse(0) { 0f }, sample.gravity.getOrElse(1) { 0f }, sample.gravity.getOrElse(2) { 0f },
        msSinceTransition, sample.stillMs,
    )
}
