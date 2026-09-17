package cz.pflanzer.foldduo.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * IDEAS.md B50 "Tapeta dýchá s oznámením": when a new eligible notification arrives (the same
 * eligibility [isHubEligible] already defines for the hub and for Badges.kt — never ongoing/media,
 * silent only with the setting on), island/IslandNotificationListener.kt derives that app's accent
 * colour and publishes a [Pulse] here — colour and timestamp only, never the notification's text or
 * its package name, so nothing about *what* arrived leaves the listener, only *that* something did
 * and in *what colour*. Two publish paths reach the two places the wallpaper is drawn:
 * [NotificationPulse.publish] (an in-process `StateFlow`) for the launcher's own Compose
 * `DuneWallpaper()`, and [writePulseToPreferences] (a tiny `SharedPreferences` write) for
 * `DuneWallpaperService`'s engine, which registers an `OnSharedPreferenceChangeListener` on it —
 * the engine runs in this app's default process today (neither service declares `android:process`
 * in AndroidManifest.xml) so the `StateFlow` would reach it too, but the prefs path is what keeps
 * working if that ever changes. [RailIsland] and [Modifier.notificationBadge] also read
 * [NotificationPulse.pulse] directly, to pop in the same rhythm as the wallpaper glow.
 */

/**
 * One pulse: an app's accent colour and when it fired. [strength] scales the shader's peak
 * opacity — always `1f` today (a single notification), kept for a future "how many arrived at
 * once" burst emphasis.
 */
data class Pulse(val colorArgb: Int, val atMs: Long, val strength: Float = 1f)

/**
 * [android.app.Notification.COLOR_DEFAULT]'s value, restated as a plain `Int` (not the Android
 * constant) so this file has no Android import and stays runnable from `app/src/test`: a
 * notification that never set its own accent colour reports this.
 */
const val PULSE_COLOR_UNSET = 0

/** Two pulses closer together than this coalesce: only the first of a burst is emitted. */
const val PULSE_MIN_INTERVAL_MS = 1_500L

/**
 * The colour a pulse uses (B50 task's fallback order): the notification's own accent colour if
 * the sender set one ([notificationColorArgb] not [PULSE_COLOR_UNSET]), else the app icon's
 * dominant colour (already reduced to an ARGB `Int` by the caller —
 * [cz.pflanzer.foldduo.dominantColorArgb] over the icon's own pixels — null when there was no icon
 * or sampling failed), else the wallpaper's derived accent.
 */
fun choosePulseColorArgb(notificationColorArgb: Int, iconDominantArgb: Int?, paletteAccentArgb: Int): Int =
    if (notificationColorArgb != PULSE_COLOR_UNSET) notificationColorArgb else iconDominantArgb ?: paletteAccentArgb

/**
 * Whether a new eligible notification at [nowMs] should actually emit a pulse: true before the
 * first one ([lastEmittedAtMs] null), otherwise only once [PULSE_MIN_INTERVAL_MS] has passed since
 * the last one — a burst of notifications inside that window coalesces onto the first pulse; later
 * arrivals in the same window are simply dropped, not queued or merged into a brighter one.
 */
fun shouldEmitPulse(lastEmittedAtMs: Long?, nowMs: Long): Boolean =
    lastEmittedAtMs == null || nowMs - lastEmittedAtMs >= PULSE_MIN_INTERVAL_MS

/** Live pulses: [cz.pflanzer.foldduo.island.IslandNotificationListener] is the only publisher. */
object NotificationPulse {
    private val pulseState = MutableStateFlow<Pulse?>(null)

    /** The most recent pulse since process start, or null before the first one has ever fired. */
    val pulse: StateFlow<Pulse?> = pulseState.asStateFlow()

    internal fun publish(pulse: Pulse) { pulseState.value = pulse }
}

private const val PULSE_PREFS = "notification_pulse"
private const val KEY_PULSE_COLOR = "pulse.color"
private const val KEY_PULSE_AT = "pulse.at"

internal fun pulsePreferences(context: Context) = context.getSharedPreferences(PULSE_PREFS, Context.MODE_PRIVATE)

/** Writes [pulse] for `DuneWallpaperService`'s engine to pick up via `OnSharedPreferenceChangeListener`; never a package name or any notification text, only the two fields [Pulse] itself carries. */
internal fun writePulseToPreferences(context: Context, pulse: Pulse) {
    pulsePreferences(context).edit()
        .putInt(KEY_PULSE_COLOR, pulse.colorArgb)
        .putLong(KEY_PULSE_AT, pulse.atMs)
        .apply()
}

/** The last [Pulse] [writePulseToPreferences] wrote, or null before the first one. */
internal fun readPulseFromPreferences(context: Context): Pulse? {
    val prefs = pulsePreferences(context)
    if (!prefs.contains(KEY_PULSE_AT)) return null
    return Pulse(prefs.getInt(KEY_PULSE_COLOR, PULSE_COLOR_UNSET), prefs.getLong(KEY_PULSE_AT, 0L))
}
