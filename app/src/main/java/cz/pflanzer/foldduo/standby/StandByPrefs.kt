package cz.pflanzer.foldduo.standby

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings of StandBy, one SharedPreferences file ("standby"). */
class StandByPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("standby", Context.MODE_PRIVATE)

    /** Master toggle: the pose service runs and Tent opens StandBy. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** "Also when closed on a table" (Closed + 4 s still + cover on). */
    var closedOnTable: Boolean
        get() = prefs.getBoolean(KEY_CLOSED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOSED, value).apply()

    var faces: List<StandByFace>
        get() = FaceOrder.decode(prefs.getString(KEY_FACES, null))
        set(value) = prefs.edit().putString(KEY_FACES, FaceOrder.encode(value)).apply()

    // --- StandBy v2 (Tent cover, iPhone-style two stacks + three views) ---

    /** Left "clock" stack: which cards, and in what order. */
    var clockCards: List<ClockCard>
        get() = ClockCardOrder.decode(prefs.getString(KEY_CLOCK_CARDS, null))
        set(value) = prefs.edit().putString(KEY_CLOCK_CARDS, ClockCardOrder.encode(value)).apply()

    /** Right "info" stack: which cards, and in what order (before the Weather availability rule). */
    var infoCards: List<InfoCard>
        get() = InfoCardOrder.decode(prefs.getString(KEY_INFO_CARDS, null))
        set(value) = prefs.edit().putString(KEY_INFO_CARDS, InfoCardOrder.encode(value)).apply()

    /** Two IANA zone ids for the World clock card; null/invalid entries are skipped at render. */
    var worldClockZoneA: String?
        get() = prefs.getString(KEY_WORLD_A, DEFAULT_WORLD_A)
        set(value) = prefs.edit().putString(KEY_WORLD_A, value).apply()

    var worldClockZoneB: String?
        get() = prefs.getString(KEY_WORLD_B, DEFAULT_WORLD_B)
        set(value) = prefs.edit().putString(KEY_WORLD_B, value).apply()

    /**
     * "Night mode: Auto / Always / Off" (17. 9. night ask). `Auto` is the original dim + red
     * ambient face on low light or sleep hours; `Always` forces the whole-screen red theme on
     * permanently; `Off` disables both (the session's quick toggle in StandByStacksUi.kt can
     * still force it on top of `Off`, since that is an explicit in-the-moment user action).
     * Falls back to the pre-existing boolean pref (`night_mode_enabled`) for anyone upgrading
     * with a saved setting, defaulting unset to `Auto`.
     */
    var nightMode: NightMode
        get() = prefs.getString(KEY_NIGHT_MODE, null)?.let { name -> NightMode.entries.firstOrNull { it.name == name } }
            ?: if (prefs.getBoolean(KEY_NIGHT_ENABLED, true)) NightMode.Auto else NightMode.Off
        set(value) = prefs.edit().putString(KEY_NIGHT_MODE, value.name).apply()

    var sleepHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SLEEP_ENABLED, value).apply()

    /** Minutes since local midnight, `[0, 1440)`. Default 23:00-06:00. */
    var sleepStartMinute: Int
        get() = prefs.getInt(KEY_SLEEP_START, NightModeRules.DEFAULT_SLEEP_START_MINUTE)
        set(value) = prefs.edit().putInt(KEY_SLEEP_START, value.coerceIn(0, 1439)).apply()

    var sleepEndMinute: Int
        get() = prefs.getInt(KEY_SLEEP_END, NightModeRules.DEFAULT_SLEEP_END_MINUTE)
        set(value) = prefs.edit().putInt(KEY_SLEEP_END, value.coerceIn(0, 1439)).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_CLOSED = "closed_on_table"
        const val KEY_FACES = "faces"
        const val KEY_CLOCK_CARDS = "clock_cards"
        const val KEY_INFO_CARDS = "info_cards"
        const val KEY_WORLD_A = "world_clock_a"
        const val KEY_WORLD_B = "world_clock_b"
        const val KEY_NIGHT_ENABLED = "night_mode_enabled"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_SLEEP_ENABLED = "sleep_hours_enabled"
        const val KEY_SLEEP_START = "sleep_start_minute"
        const val KEY_SLEEP_END = "sleep_end_minute"
        /** London and Tokyo: sensible defaults so the card shows something useful unconfigured. */
        const val DEFAULT_WORLD_A = "Europe/London"
        const val DEFAULT_WORLD_B = "Asia/Tokyo"
    }
}

/**
 * Process-wide bridge between [StandByActivity] and [StandByService]: whether a StandBy
 * activity is alive (started and not yet destroyed) and when the user last dismissed it by
 * gesture (elapsed realtime), for the relaunch debounce.
 */
internal object StandBySession {
    val showing = MutableStateFlow(false)
    @Volatile var lastGestureExitMs = Long.MIN_VALUE

    fun noteGestureExit() { lastGestureExitMs = SystemClock.elapsedRealtime() }

    fun msSinceGestureExit(now: Long = SystemClock.elapsedRealtime()): Long =
        if (lastGestureExitMs == Long.MIN_VALUE) Long.MAX_VALUE else now - lastGestureExitMs

    /**
     * B32 (StandByMorph.kt): bumped by [StandByService] right before it hands off to
     * [StandByActivity] while the launcher is in front and the hinge angle is not confident, so
     * the launcher's own bridge (StandByMorphOverlay.kt's `rememberLauncherStandByFrost`) plays
     * the timed frost-in fallback instead of the (unavailable) angle-driven one.
     */
    private val _enterFrostRequests = MutableStateFlow(0)
    val enterFrostRequests: StateFlow<Int> = _enterFrostRequests.asStateFlow()

    fun requestEnterFrost() { _enterFrostRequests.value++ }
}
