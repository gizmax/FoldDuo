package cz.pflanzer.foldduo.standby

import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.Panel

/*
 * Pure decisions behind StandBy (PLAN.md, Fáze 4): when the pose service starts the activity,
 * when the activity leaves, how the glow alarm ramps and how the face order is stored. No
 * Android types, so all of it runs as JVM unit tests (StandByRulesTest).
 */

/** The faces the pager can show, in their default order. Weather has no data source yet. */
enum class StandByFace { Clock, Calendar, Photos }

/**
 * What is in front of the user, as far as the launcher can tell without usage-stats access:
 * our own [cz.pflanzer.foldduo.MainActivity] resumed, nothing (keyguard showing or the display
 * off), or presumably another app (our Home not resumed while the screen is on and unlocked).
 */
enum class Foreground { Launcher, Nothing, Other }

data class StandByInputs(
    val pose: FoldPose,
    /** Snapshot stillness (ms without a gravity change above the engine's delta). */
    val stillMs: Long,
    /** Logical display 0 is on. Required for Closed (we never wake a closed phone on a table). */
    val coverOn: Boolean,
    val foreground: Foreground,
    /** `Settings.canDrawOverlays`: the Android 10+ exemption for starting an activity from a service. */
    val overlayGranted: Boolean,
    /** Setting "Also when closed on a table". Tent is always on while the service runs. */
    val closedOnTable: Boolean,
    /** StandByActivity is already resumed. */
    val showing: Boolean,
    /** Elapsed ms since the user dismissed StandBy by gesture; `Long.MAX_VALUE` if never. */
    val msSinceGestureExit: Long = Long.MAX_VALUE,
    /**
     * The physical panel behind display 0. Tent is cover-only (17. 9. device report: StandBy
     * came up on the inner panel while the phone was being closed in the hand).
     */
    val panel: Panel = Panel.Cover,
    /** Last gyroscope rotation rate around the hinge axis, rad/s; a moving hinge is not a tent on a table. */
    val hingeRateRadS: Float = 0f,
    /** Elapsed ms since the gyroscope last saw a closing motion; `Long.MAX_VALUE` if never. */
    val msSinceClosingMotion: Long = Long.MAX_VALUE,
)

object StandByRules {
    /** Closed on a table must rest this long before the cover turns into StandBy. */
    const val CLOSED_STILL_MS = 4_000L
    /** Never relaunch this soon after a user gesture ended StandBy. */
    const val GESTURE_EXIT_DEBOUNCE_MS = 10_000L
    /** After a gesture exit, relaunch also needs movement since the exit, unless this long has passed. */
    const val RELAUNCH_ANYWAY_MS = 60_000L
    /** Closed: a phone that moved this recently is being picked up, so StandBy leaves. */
    const val PICKUP_STILL_MS = 800L
    /**
     * Tent must rest this long on top of the classifier's own 400 ms: a phone being closed in
     * the hand pauses at tent-like angles for shorter than a phone stood on a table.
     */
    const val TENT_STILL_MS = 1_500L
    /** Tent: the hinge may not be turning faster than this (rad/s) — a closing phone is not a tent. */
    const val TENT_MAX_HINGE_RATE_RAD_S = 0.2f
    /** Tent: never this soon after the gyroscope saw a closing motion. */
    const val TENT_AFTER_CLOSING_MS = 3_000L

    /**
     * Start decision. Tent is cover-only and must stand still: the cover panel behind display 0,
     * [TENT_STILL_MS] of rest, a hinge that is not turning and no closing motion within
     * [TENT_AFTER_CLOSING_MS] — so a phone being closed in the hand never becomes a tent, and
     * the inner panel never shows StandBy. Closed needs the setting, the cover on and
     * [CLOSED_STILL_MS] of rest. Both need a
     * place to start over: our Home resumed (an app in the foreground may start activities), or
     * nothing in front and the overlay permission (the background-start exemption). Another app
     * in front, e.g. a video watched in Tent, is never covered.
     */
    fun shouldStart(i: StandByInputs): Boolean {
        if (i.showing) return false
        if (!relaunchAllowed(i.msSinceGestureExit, i.stillMs)) return false
        val canStart = when (i.foreground) {
            Foreground.Launcher -> true
            Foreground.Nothing -> i.overlayGranted
            Foreground.Other -> false
        }
        if (!canStart) return false
        return when (i.pose) {
            FoldPose.Tent -> tentResting(i)
            FoldPose.Closed -> i.closedOnTable && i.coverOn && i.stillMs >= CLOSED_STILL_MS
            else -> false
        }
    }

    /** The Tent half of [shouldStart]: on the cover, resting, hinge still, no recent closing motion. */
    fun tentResting(i: StandByInputs): Boolean =
        i.panel == Panel.Cover && i.stillMs >= TENT_STILL_MS &&
            kotlin.math.abs(i.hingeRateRadS) <= TENT_MAX_HINGE_RATE_RAD_S &&
            i.msSinceClosingMotion >= TENT_AFTER_CLOSING_MS

    /**
     * A gesture exit means "not now": StandBy comes back once the phone has moved since (put
     * down again) and at least [GESTURE_EXIT_DEBOUNCE_MS] passed, or after [RELAUNCH_ANYWAY_MS].
     */
    fun relaunchAllowed(msSinceGestureExit: Long, stillMs: Long): Boolean =
        msSinceGestureExit >= RELAUNCH_ANYWAY_MS ||
            (msSinceGestureExit >= GESTURE_EXIT_DEBOUNCE_MS && stillMs < msSinceGestureExit)

    /**
     * Exit decision for a showing StandBy: the inner panel took over, the pose left {Tent, Closed}
     * (Open, Stand, Flip or the InMotion window of a hinge step), or a Closed phone is being
     * picked up. A preview opened from settings ignores the pose altogether.
     */
    fun shouldExit(pose: FoldPose, panel: Panel, stillMs: Long, preview: Boolean = false): Boolean {
        if (preview) return false
        if (panel == Panel.Inner) return true
        return when (pose) {
            FoldPose.Tent -> false
            FoldPose.Closed -> stillMs < PICKUP_STILL_MS
            else -> true
        }
    }
}

/**
 * Why a showing [StandByActivity] finished, purely to give the
 * `FoldDuoStandBy` log line a stable reason and to drive [countsAsGestureExit] — added after a
 * device report (17. 9. night) of the cover going dark: logcat showed `onStop` firing (not a
 * pose exit, not a tap), i.e. something else briefly took the top-resumed window and our old
 * `onStop` treated *any* such hide as "the user left", penalising the relaunch with up to
 * [StandByRules.RELAUNCH_ANYWAY_MS] of blackout. A keyguard re-assertion while StandBy has not
 * moved (still resting in Tent/Closed) is not a user dismissal — it should relaunch as soon as
 * pose says so again, not sit through that debounce.
 */
enum class StandByExitReason { PoseOrPanel, GestureDoubleTap, GestureSwipeUp, GestureBack, HiddenByKeyguard, HiddenOther }

/** A hidden exit only debounces the relaunch (see [StandBySession.noteGestureExit]) when it was not the keyguard. */
val StandByExitReason.countsAsGestureExit: Boolean get() = this != StandByExitReason.HiddenByKeyguard

/** [StandByExitReason] for [StandByActivity.onStop]'s natural "hidden" path. */
fun hiddenExitReason(keyguardLocked: Boolean): StandByExitReason =
    if (keyguardLocked) StandByExitReason.HiddenByKeyguard else StandByExitReason.HiddenOther

// --- Glow alarm -------------------------------------------------------------------------

object GlowAlarm {
    /** The ramp starts this long before the next alarm clock. */
    const val WINDOW_MS = 5 * 60_000L
    /** After the alarm time the glow stays until the alarm app updates the next alarm, at most this long. */
    const val HOLD_MS = 10 * 60_000L
    const val BLACK_ARGB = 0xFF000000.toInt()
    /** Warm amber, the sunrise-lamp end of the ramp. */
    const val WARM_ARGB = 0xFFD98A3E.toInt()

    /**
     * 0 outside the window, rising to 1 at the alarm time (ease-in, so the first minutes stay
     * near black), 1 while the alarm is due, 0 again once it is [HOLD_MS] stale or unknown.
     */
    fun progress(nowMs: Long, alarmMs: Long?, windowMs: Long = WINDOW_MS): Float {
        if (alarmMs == null) return 0f
        if (nowMs >= alarmMs) return if (nowMs - alarmMs < HOLD_MS) 1f else 0f
        val start = alarmMs - windowMs
        if (nowMs <= start) return 0f
        val linear = (nowMs - start).toFloat() / windowMs
        return (linear * linear).coerceIn(0f, 1f)
    }

    /** Face background for [progress]: black to [WARM_ARGB], per-channel linear. */
    fun argb(progress: Float): Int = lerpArgb(BLACK_ARGB, WARM_ARGB, progress.coerceIn(0f, 1f))

    private fun lerpArgb(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t + .5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

// --- Face order persistence -------------------------------------------------------------

object FaceOrder {
    val DEFAULT: List<StandByFace> = StandByFace.entries

    /** "Clock,Photos" — the faces shown, in order; a face left out is hidden. */
    fun encode(faces: List<StandByFace>): String = faces.joinToString(",") { it.name }

    /** Tolerant inverse of [encode]: unknown names dropped, duplicates collapsed, empty -> [DEFAULT]. */
    fun decode(text: String?): List<StandByFace> {
        if (text == null) return DEFAULT
        val faces = text.split(',').mapNotNull { name ->
            StandByFace.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }.distinct()
        return faces.ifEmpty { DEFAULT }
    }

    fun moveUp(faces: List<StandByFace>, face: StandByFace): List<StandByFace> {
        val index = faces.indexOf(face)
        if (index <= 0) return faces
        return faces.toMutableList().also { it[index] = it[index - 1]; it[index - 1] = face }
    }

    /** Show or hide a face; the last shown face cannot be hidden. */
    fun toggle(faces: List<StandByFace>, face: StandByFace, shown: Boolean): List<StandByFace> = when {
        shown && face !in faces -> faces + face
        !shown && faces.size > 1 -> faces - face
        else -> faces
    }
}
