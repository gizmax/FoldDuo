package cz.pflanzer.foldduo

import kotlin.math.max

/**
 * "Pacing morphu" (STATUS.md, 17. 9. 2026): the displayed hinge tilt is always the SLOWER of the
 * hand (the angle-mapped tilt, rate-limited on the rise) and a guaranteed minimum timeline (a
 * floor that holds, then eases to 0 no faster than a fixed duration). Pure Kotlin, no Android
 * types, so it is unit-testable on the JVM and shared between the launcher's own morph
 * ([MorphController]) and the system-wide frost over other apps
 * ([cz.pflanzer.foldduo.systemfrost.SystemFrostPlan]).
 *
 * Motivation (device log, 2026-09-17 13:25): the magnetometer estimate is clamped inside the 90
 * step's band ([cz.pflanzer.foldduo.pose.HingeStepGate]) and can sit far from the HAL truth while
 * the phone is held in the hand; at one panel swap the estimate already read 165° (nearly flat,
 * its clamp) while the true hinge angle was 123° (still well frosted), so almost the whole clear
 * collapsed into the smoother's first ~60 ms tick, hidden behind One UI's own ~800 ms screen-on
 * fade — by the time the panel became visible the clear had already finished, reading as one jump
 * instead of a glass wipe. The floor below restores the old fixed-length clear
 * ([MorphCurve.DURATION_MS]) as a guaranteed lower bound on how fast the frost may visibly lift,
 * while a genuinely slow hand still gets to hold it longer: the angle always wins whenever it
 * reports *more* frost than the floor's own schedule.
 */
class TiltPacing {
    private var armedAtMs = Long.MIN_VALUE
    private var releaseDelayMs = 0L
    private var releaseDurationMs = 1
    private var releaseFromTilt = 0f

    /** True while the angle (rather than the floor) was the larger of the two on the last [pace] call. */
    private var angleGoverning = false

    /** True once [armRelease] has run at least once; the floor itself may already have eased all the way to 0 — see [floorAt]. */
    val armed: Boolean get() = armedAtMs != Long.MIN_VALUE

    /**
     * Arms (or re-arms) the release floor: it holds at [fromTilt] for [delayMs], then eases to 0
     * along [MorphCurve.clearEasing] over [durationMs]. Both durations are the caller's own —
     * already B18-scaled ([MorphCurve.scaledDurationMs]) if that correction applies to them.
     */
    fun armRelease(nowMs: Long, delayMs: Long, durationMs: Int, fromTilt: Float) {
        armedAtMs = nowMs
        releaseDelayMs = delayMs.coerceAtLeast(0L)
        releaseDurationMs = durationMs.coerceAtLeast(1)
        releaseFromTilt = fromTilt.coerceAtLeast(0f)
    }

    /**
     * The floor's own value at [nowMs]: [releaseFromTilt] until [armRelease]'s `delayMs` has
     * elapsed, eased down to 0 along [MorphCurve.clearEasing] over its `durationMs`; 0 before the
     * first [armRelease], or once that release has finished.
     */
    fun floorAt(nowMs: Long): Float {
        if (!armed) return 0f
        val elapsed = nowMs - armedAtMs
        if (elapsed < releaseDelayMs) return releaseFromTilt
        val t = (elapsed - releaseDelayMs).toFloat() / releaseDurationMs
        if (t >= 1f) return 0f
        return releaseFromTilt * (1f - MorphCurve.clearEasing.transform(t))
    }

    /**
     * The paced, displayed tilt for one sample: the larger of the rise-limited [angleTiltDeg] and
     * the release floor ([floorAt]). A NaN [angleTiltDeg] (no usable angle this sample) falls back
     * to the floor alone. While the hand holds more frost than the floor's own schedule ("the
     * angle wins"), the moment it drops back below what is currently on screen the floor is
     * re-armed from [prevDisplayed] with the same [durationMs] (defaulting to the duration last
     * passed to [armRelease]) so the final clear still takes at least that long from wherever it
     * starts, instead of finishing out a schedule that already ran most of its course while the
     * hand paused.
     */
    fun pace(
        nowMs: Long,
        prevDisplayed: Float,
        angleTiltDeg: Float,
        dtMs: Long,
        fullRiseMs: Int,
        durationMs: Int = releaseDurationMs,
    ): Float {
        if (angleTiltDeg.isNaN()) {
            angleGoverning = false
            return floorAt(nowMs)
        }
        val risen = riseLimited(prevDisplayed, angleTiltDeg, dtMs, fullRiseMs)
        val floor = floorAt(nowMs)
        if (angleGoverning && risen <= floor) {
            // The hand had been holding the frost above the guaranteed floor and just caught back
            // down to (or past) its schedule: a fresh guaranteed clear starts from here.
            armRelease(nowMs, 0L, durationMs, prevDisplayed)
            angleGoverning = false
            return floorAt(nowMs)
        }
        angleGoverning = risen > floor
        return max(risen, floor)
    }

    companion object {
        /**
         * Default full-[MorphCurve.MAX_TILT]-range rise time for a frost-up (the cover while
         * opening, or the inner left half while closing): [MorphCurve.RISE_FULL_MS]. B18-scale it
         * ([MorphCurve.scaledDurationMs]) before passing it as [fullRiseMs].
         */
        const val DEFAULT_FULL_RISE_MS = 500

        /**
         * [target] approached from [prevDisplayed] at no faster a pace than would cross the whole
         * [MorphCurve.MAX_TILT] range in [fullRiseMs]: a fall (clearing) is never limited, only a
         * rise (frosting up) is — a hinge-step snap must read as a fast frost, not a pop. NaN
         * (no usable angle) holds at [prevDisplayed].
         */
        fun riseLimited(prevDisplayed: Float, target: Float, dtMs: Long, fullRiseMs: Int): Float {
            if (target.isNaN()) return prevDisplayed
            if (target <= prevDisplayed) return target
            val step = MorphCurve.MAX_TILT * dtMs.coerceAtLeast(0L).toFloat() / fullRiseMs.coerceAtLeast(1)
            return (prevDisplayed + step).coerceAtMost(target)
        }
    }
}
