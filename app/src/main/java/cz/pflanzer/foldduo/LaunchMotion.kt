package cz.pflanzer.foldduo

/** The app and window-space bounds a launch-zoom overlay renders while its icon is "in flight". */
internal data class LaunchIconVisual(val app: AppEntry, val boundsInWindow: android.graphics.Rect)

/**
 * IDEAS B23 "Otevírání aplikací z ikony": the system zoom (`ActivityOptions.makeClipRevealAnimation`,
 * see `MainActivity.launchOptions`) is mirrored in Compose — the tapped icon grows and fades while
 * the rest of the page shrinks 4 % and dims, and on the way back the page un-zooms and the icon
 * pops back to size. These are the pure, Android-free pieces of that: durations/scales, and the
 * guard that decides whether an `onResume` should replay the "return" half.
 */
internal const val ICON_LAUNCH_ZOOM_DURATION_MS = 250
internal const val ICON_LAUNCH_ZOOM_TARGET_SCALE = 1.6f
internal const val PAGE_LAUNCH_ZOOM_SCALE = 0.96f
internal const val PAGE_LAUNCH_DIM_ALPHA = 0.82f

/**
 * Guards the "un-zoom" return animation (B23 item 3): it must play at most once per launch, and
 * only if the resume plausibly belongs to that launch (inside [windowMillis]). Without this guard
 * a resume that arrives long after the tap (phone left on another app for a while, then Home
 * pressed) would replay a stale zoom, and a resume that follows Android's own predictive-back
 * transition would double-animate on top of it.
 */
internal class LaunchReturnGuard(private val windowMillis: Long = 3_000L) {
    private var launchedAtMillis: Long? = null
    private var played = false

    /** Call when an app is launched from an icon. */
    fun noteLaunch(nowMillis: Long) {
        launchedAtMillis = nowMillis
        played = false
    }

    /**
     * Call on every resume. Returns true at most once per [noteLaunch] — and only the first time,
     * within [windowMillis] of it — so a later, unrelated resume never replays it.
     */
    fun consumeReturn(nowMillis: Long): Boolean {
        val launchedAt = launchedAtMillis ?: return false
        if (played) return false
        played = true
        return nowMillis - launchedAt in 0..windowMillis
    }

    /** Drops any pending return without playing it (e.g. reduced motion just turned on). */
    fun reset() {
        launchedAtMillis = null
        played = true
    }
}
