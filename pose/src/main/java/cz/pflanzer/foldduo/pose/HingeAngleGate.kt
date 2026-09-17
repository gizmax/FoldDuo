package cz.pflanzer.foldduo.pose

/**
 * Turns the estimator's confidence into a stable "the angle is usable" flag.
 *
 * Enters at confidence >= [enter] immediately (once anchored); leaves only after the
 * confidence has stayed below [exit] for [exitDebounceNs]. On the device the confidence hovers
 * around 0.5 while the closed phone is carried (the Earth field walks bx ~10 uT past the
 * closed rest: 2026-09-15 log, six on/off flips in 20 s at conf 0.49–0.51), and every flip
 * restarts the frost from zero on the inner panel; the estimate past the table's end is a
 * clamped 0/180 and is fine to keep showing for a while.
 */
class HingeAngleGate(
    val enter: Float = HingeAngleEstimator.CONFIDENT,
    val exit: Float = EXIT,
    val exitDebounceNs: Long = EXIT_DEBOUNCE_NS,
) {
    var valid: Boolean = false
        private set

    private var lowSinceNs = Long.MIN_VALUE

    /** One sample: returns the (possibly unchanged) validity. */
    fun update(nowNs: Long, confidence: Float, anchored: Boolean): Boolean {
        if (!anchored) { valid = false; lowSinceNs = Long.MIN_VALUE; return false }
        if (!valid) {
            if (confidence >= enter) { valid = true; lowSinceNs = Long.MIN_VALUE }
        } else if (confidence >= exit) {
            lowSinceNs = Long.MIN_VALUE
        } else {
            if (lowSinceNs == Long.MIN_VALUE) lowSinceNs = nowNs
            if (nowNs - lowSinceNs >= exitDebounceNs) { valid = false; lowSinceNs = Long.MIN_VALUE }
        }
        return valid
    }

    fun reset() { valid = false; lowSinceNs = Long.MIN_VALUE }

    companion object {
        /** Below this for [EXIT_DEBOUNCE_NS] the angle is dropped: ~12 uT past the table's end (see [HingeAngleEstimator.CONFIDENT]). */
        const val EXIT = 0.4f
        const val EXIT_DEBOUNCE_NS = 300_000_000L
    }
}
