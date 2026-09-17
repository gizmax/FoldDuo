package cz.pflanzer.foldduo.standby

/*
 * StandBy v2 night mode (iOS Night Mode on the Tent cover): dim to ~25% and tint red when the
 * ambient light is low for a while, or during the user's sleep hours. Pure Kotlin, no Android
 * types (the light-sensor sampling and the sleep-hours clock live in the UI glue), so the
 * decision table runs as plain JUnit (StandByNightModeTest).
 *
 * Tom's ask (17. 9. night, device report): a MANUAL "Night mode: Auto / Always / Off" setting
 * plus a quick per-session toggle (StandByStacksUi.kt's moon glyph), on top of the original
 * Auto-only lux/sleep-hours rule. The two stay conceptually separate: [isAmbientNight] is the
 * original ambient rule (only under [NightMode.Auto]) that swaps to the minimal alarm-only
 * `NightModeFace`; [isForcedRed] is the new whole-screen red theme, on permanently under
 * [NightMode.Always] or while the session override is on, regardless of light or the clock.
 */

/** Whether a minute-of-day falls in an (inclusive start, exclusive end) window, wrapping past midnight. */
object SleepWindow {
    /**
     * [startMinute]/[endMinute] are minutes since local midnight, `[0, 1440)`. A window where
     * start > end wraps past midnight (23:00-06:00 = 23:00..23:59 plus 00:00..05:59); a
     * zero-length window (`start == end`) never matches, rather than reading as "all day".
     */
    fun contains(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) nowMinute in startMinute until endMinute
        else nowMinute >= startMinute || nowMinute < endMinute
    }
}

/** The StandBy page's "Night mode" setting: automatic, always on, or never. */
enum class NightMode { Auto, Always, Off }

data class NightModeInputs(
    val mode: NightMode,
    /** How long `TYPE_LIGHT` has continuously read below [NightModeRules.LUX_THRESHOLD]. */
    val lowLightMs: Long,
    val sleepHoursEnabled: Boolean,
    val nowMinuteOfDay: Int,
    val sleepStartMinute: Int,
    val sleepEndMinute: Int,
)

object NightModeRules {
    const val LUX_THRESHOLD = 10f
    const val LOW_LIGHT_HOLD_MS = 5_000L
    /** Perceived brightness once night mode kicks in (a black veil at `1 - DIM_FRACTION` alpha). */
    const val DIM_FRACTION = 0.25f
    /** A tap wakes the dimmed face back to normal for this long. */
    const val WAKE_WINDOW_MS = 10_000L
    const val DEFAULT_SLEEP_START_MINUTE = 23 * 60
    const val DEFAULT_SLEEP_END_MINUTE = 6 * 60

    /**
     * The original ambient rule: low light for [LOW_LIGHT_HOLD_MS] straight, or inside the
     * sleep-hours window — only while the setting is [NightMode.Auto]. `Always`/`Off` never go
     * through here (see [isForcedRed] for `Always`); a session override is handled separately by
     * the caller, since forcing the theme on should show the full red UI, not swap to the
     * minimal ambient face (StandByStacksUi.kt's `StandByV2Screen`).
     */
    fun isAmbientNight(i: NightModeInputs): Boolean {
        if (i.mode != NightMode.Auto) return false
        if (i.lowLightMs >= LOW_LIGHT_HOLD_MS) return true
        return i.sleepHoursEnabled && SleepWindow.contains(i.nowMinuteOfDay, i.sleepStartMinute, i.sleepEndMinute)
    }

    /** Whole-screen red theme forced on: the "Always" setting, or the session's quick toggle. */
    fun isForcedRed(mode: NightMode, sessionForced: Boolean): Boolean = mode == NightMode.Always || sessionForced

    /** True while a tap-triggered wake ([wokenAtMs]) is still within [WAKE_WINDOW_MS] of [nowMs]. */
    fun isAwake(nowMs: Long, wokenAtMs: Long): Boolean = nowMs - wokenAtMs < WAKE_WINDOW_MS
}

/**
 * Colours for the forced-red theme (StandByStacksUi.kt's whole-screen tint, StandByActivity's
 * brightness floor is separate). Text/icon ink swaps to these flat ARGB values outright; photos,
 * album art and the live Weather widget instead get a translucent [PRIMARY_ARGB] wash drawn over
 * them at [ARTWORK_TINT_ALPHA] (a plain src-over blend, i.e. linear interpolation toward
 * [PRIMARY_ARGB] — [mixTowardRed] is that same blend kept pure and testable, since the visual
 * layer draws it at composite time instead of precomputing pixels).
 */
object NightPalette {
    /** ~iOS system red, the night face's existing colour (StandByStacksUi.kt's old `NIGHT_RED`). */
    const val PRIMARY_ARGB = 0xFFFF3B30.toInt()
    /** Secondary/dimmer red text, per Tom's spec. */
    const val SECONDARY_ARGB = 0xFFB0261E.toInt()
    /** How strongly artwork (photos, album art, the Weather host view) reads as red. */
    const val ARTWORK_TINT_ALPHA = 0.55f

    /** [argb] linearly interpolated toward [tint] by [strength] (0 = unchanged, 1 = [tint]); alpha untouched. */
    fun mixTowardRed(argb: Int, strength: Float = ARTWORK_TINT_ALPHA, tint: Int = PRIMARY_ARGB): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF; val g = (argb ushr 8) and 0xFF; val b = argb and 0xFF
        val tr = (tint ushr 16) and 0xFF; val tg = (tint ushr 8) and 0xFF; val tb = tint and 0xFF
        fun mix(c: Int, t: Int) = (c + (t - c) * strength).toInt().coerceIn(0, 255)
        return (a shl 24) or (mix(r, tr) shl 16) or (mix(g, tg) shl 8) or mix(b, tb)
    }
}

/** Reduces a stream of lux samples to "how long has it been continuously dark", without keeping history. */
object LowLightAccumulator {
    /** Resets to 0 the instant [luxNow] is at or above [threshold]; otherwise grows by [deltaMs]. */
    fun update(currentLowMs: Long, luxNow: Float, deltaMs: Long, threshold: Float = NightModeRules.LUX_THRESHOLD): Long =
        if (luxNow >= threshold) 0L else (currentLowMs + deltaMs).coerceAtLeast(0L)
}
