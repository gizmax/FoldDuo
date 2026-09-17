package cz.pflanzer.foldduo.pose

import kotlin.math.abs

/**
 * "The phone is being opened" (or closed) from the gyroscope.
 *
 * The public hinge sensor on the Fold 8 is quantised (0 / 90 / 180) and reports late: on a
 * measured slow open the 90 came 4.4 s before the panel swap and the 180 only 1.8 s after
 * it, with nothing while the phone actually swung open. The gyroscope is not: swinging one
 * half of the book rotates it around the hinge axis (device y), so |ω_y| above
 * [thresholdRadS] for [consecutiveSamples] samples in a row is the motion.
 *
 * The one IMU sits in a single half, though: a swing of the other half is invisible to it
 * (a measured real open produced one 5.9 rad/s spike and then < 2 rad/s until the swap), and
 * turning the closed phone in the hand looks exactly like a swing (handling peaks measured at
 * 1.4–4.5 rad/s per second, 6–7 while flipping it). The defaults are therefore high and the
 * consumer gates the gyro trigger behind a setting; the hinge step stays the reliable signal.
 *
 * Pure and main-thread only; feed it every gyroscope sample while its panel is active
 * ([HingeMotionDetectors]). After a fire it rearms only once the rate has dropped back under
 * the threshold, so one swing produces one event. [seq] counts fires; a consumer that sees it
 * change reacts once.
 */
class OpeningMotionDetector(
    val thresholdRadS: Float = DEFAULT_THRESHOLD_RAD_S,
    val consecutiveSamples: Int = DEFAULT_CONSECUTIVE_SAMPLES,
) {
    /** Number of fires so far. */
    var seq: Int = 0
        private set

    /** The rate around the hinge axis of the last fed sample (rad/s, signed). */
    var lastRateRadS: Float = 0f
        private set

    private var above = 0
    private var armed = true

    /** Feed one gyroscope sample's rotation rate around the hinge axis; true when this sample fires. */
    fun feed(rateAroundHingeRadS: Float): Boolean {
        lastRateRadS = rateAroundHingeRadS
        val fast = abs(rateAroundHingeRadS) >= thresholdRadS
        if (!fast) {
            above = 0
            armed = true
            return false
        }
        above++
        if (!armed || above < consecutiveSamples) return false
        armed = false
        seq++
        return true
    }

    /** Forget any partial run (e.g. the panel changed). */
    fun reset() {
        above = 0
        armed = true
        lastRateRadS = 0f
    }

    companion object {
        /**
         * rad/s around the hinge axis; ~315°/s. Measured on the Fold 8 (2026-09-15): ordinary
         * handling of the closed phone peaked at 1.4–4.5 rad/s per second (6–7 while flipping
         * it over), the fastest real open/close swings at 5.6–8.7 rad/s. Tunable.
         */
        const val DEFAULT_THRESHOLD_RAD_S = 5.5f
        /** At SENSOR_DELAY_GAME (20 ms) three samples = 60 ms of sustained rotation. */
        const val DEFAULT_CONSECUTIVE_SAMPLES = 3
        /** Index of the hinge axis (device y) in a gyroscope event's values. */
        const val HINGE_AXIS = 1
    }
}

/**
 * The two hinge-motion detectors and the panel gate between them: a gyroscope sample feeds the
 * [opening] detector while the cover is active and the [closing] one while the inner panel is,
 * so each sequence counts swings seen on its own panel only. The other detector is reset on
 * every sample so a run never straddles a panel swap; an unknown panel feeds neither.
 */
class HingeMotionDetectors(
    val opening: OpeningMotionDetector = OpeningMotionDetector(),
    val closing: OpeningMotionDetector = OpeningMotionDetector(),
) {
    enum class Fired { None, Opening, Closing }

    /** The rate around the hinge axis of the last fed sample, whichever panel it landed on. */
    var lastRateRadS: Float = 0f
        private set

    fun feed(panel: Panel, rateAroundHingeRadS: Float): Fired {
        lastRateRadS = rateAroundHingeRadS
        return when (panel) {
            Panel.Cover -> { closing.reset(); if (opening.feed(rateAroundHingeRadS)) Fired.Opening else Fired.None }
            Panel.Inner -> { opening.reset(); if (closing.feed(rateAroundHingeRadS)) Fired.Closing else Fired.None }
            Panel.Unknown -> { opening.reset(); closing.reset(); Fired.None }
        }
    }

    fun reset() {
        opening.reset()
        closing.reset()
        lastRateRadS = 0f
    }
}
