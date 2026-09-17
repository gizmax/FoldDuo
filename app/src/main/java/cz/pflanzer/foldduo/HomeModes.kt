package cz.pflanzer.foldduo

import org.json.JSONArray
import org.json.JSONObject

/** The built-in mode every layout starts with: every Home page visible, not renameable or deletable. */
const val ALL_MODE_ID = "all"
private const val ALL_MODE_NAME = "Vše"

/**
 * B39 "Režimy plochy": a named set of visible Home pages plus how it switches on automatically.
 * [visiblePages] is ignored for [ALL_MODE_ID] — that mode always shows every page regardless of
 * what is stored, so a page added later is visible under "Vše" without touching this list.
 * [schedule] (nullable) is the automatic weekday+time window a [ModeScheduler] reads; [dndTrigger]
 * marks the (at most conceptually one, [DndModeSignal] takes the first) mode Do Not Disturb should
 * switch to.
 */
data class HomeMode(
    val id: String,
    val name: String,
    val icon: String = "apps",
    val visiblePages: Set<Int> = emptySet(),
    val schedule: ModeSchedule? = null,
    val dndTrigger: Boolean = false,
)

/** ISO weekday numbers, Monday = 1 .. Sunday = 7, matching [java.time.DayOfWeek.getValue]. */
val ALL_WEEKDAYS = (1..7).toSet()

/**
 * A recurring automatic window: active on [weekdays] between [startMinute] and [endMinute]
 * (minutes since midnight, `0..1439`). `endMinute <= startMinute` is an overnight window that
 * wraps past midnight (e.g. 22:00-6:00) — see [ModeScheduler.isActive].
 */
data class ModeSchedule(val weekdays: Set<Int>, val startMinute: Int, val endMinute: Int)

/**
 * Picks which [HomeMode] should be active from the clock alone, a pure function so the actual
 * tick (a periodic check in [MainActivity], not part of this file) has nothing left to decide.
 */
object ModeScheduler {
    /** Whether [schedule] covers [weekdayIso] (1=Monday..7=Sunday) at [minuteOfDay] (0..1439), wrapping past midnight when the window is overnight. */
    fun isActive(schedule: ModeSchedule, weekdayIso: Int, minuteOfDay: Int): Boolean {
        val overnight = schedule.endMinute <= schedule.startMinute
        return if (!overnight) {
            weekdayIso in schedule.weekdays && minuteOfDay in schedule.startMinute until schedule.endMinute
        } else {
            // The window spans midnight: minuteOfDay >= start belongs to the day the window
            // *starts* on, minuteOfDay < end belongs to the day it *ends* on (the day after).
            val startedToday = weekdayIso in schedule.weekdays && minuteOfDay >= schedule.startMinute
            val previousWeekday = if (weekdayIso == 1) 7 else weekdayIso - 1
            val continuingFromYesterday = previousWeekday in schedule.weekdays && minuteOfDay < schedule.endMinute
            startedToday || continuingFromYesterday
        }
    }

    /**
     * The first (list order breaks ties) non-"Vše" mode whose schedule covers this moment, or
     * `null` when none does — callers keep whatever mode is already active in that case, they
     * never fall back to "Vše" on their own.
     */
    fun scheduledMode(modes: List<HomeMode>, weekdayIso: Int, minuteOfDay: Int): HomeMode? =
        modes.firstOrNull { it.id != ALL_MODE_ID && it.schedule != null && isActive(it.schedule, weekdayIso, minuteOfDay) }
}

/**
 * The Samsung Do Not Disturb signal (`NotificationManager.currentInterruptionFilter` via
 * `ACTION_INTERRUPTION_FILTER_CHANGED`, wired in [MainActivity]): pure decisions only, so both the
 * "did DND just turn on" edge and "which mode" pieces are independently testable.
 */
object DndModeSignal {
    /** `NotificationManager.INTERRUPTION_FILTER_ALL`; DND (or any other filter) is anything else. */
    const val INTERRUPTION_FILTER_ALL = 1

    /** True exactly on the ALL -> not-ALL transition (DND, alarms-only, etc. turning on), never on the reverse or a no-op repeat. */
    fun turnedOn(previousFilter: Int, currentFilter: Int) =
        previousFilter == INTERRUPTION_FILTER_ALL && currentFilter != INTERRUPTION_FILTER_ALL

    /** The first mode flagged "při DND", or `null` if none is — callers only switch when this is non-null. */
    fun modeFor(modes: List<HomeMode>): HomeMode? = modes.firstOrNull { it.dndTrigger }
}

/**
 * What the pager/indicator should treat as hidden right now: the user's manual per-page toggle
 * ([HomeLayout.hiddenPages]) plus, unless the active mode is "Vše", every page that mode leaves
 * out. Both sources are clamped to `0 until pageCount` so a stale page number (a mode saved before
 * a page was removed) can never hide something that no longer exists.
 */
fun effectiveHiddenPages(pageCount: Int, manualHidden: Set<Int>, modes: List<HomeMode>, activeModeId: String): Set<Int> {
    val mode = modes.firstOrNull { it.id == activeModeId }
    val modeHidden = if (mode == null || mode.id == ALL_MODE_ID) emptySet()
        else (0 until pageCount).filterTo(mutableSetOf()) { it !in mode.visiblePages }
    return (manualHidden + modeHidden).filterTo(mutableSetOf()) { it in 0 until pageCount }
}

internal fun defaultHomeModes(): List<HomeMode> = listOf(HomeMode(ALL_MODE_ID, ALL_MODE_NAME))

/**
 * 2026-09-17 noc "Mazání stránek s dotazem": remaps every mode's [HomeMode.visiblePages] the same
 * way [removeHomePage] shifts a layout's own slots and widgets when page [page] is deleted — that
 * page number drops out of every mode's set, and every later page number shifts down by one so a
 * mode built for "pages 2 and 3" still means the same physical pages once page 1 is gone.
 * [ALL_MODE_ID]'s stored set is ignored everywhere it is read ([effectiveHiddenPages]), so remapping
 * it here too is harmless rather than load-bearing.
 */
fun remapModesAfterPageRemoval(modes: List<HomeMode>, page: Int): List<HomeMode> = modes.map { mode ->
    mode.copy(visiblePages = mode.visiblePages.mapNotNullTo(mutableSetOf()) { p ->
        when {
            p == page -> null
            p > page -> p - 1
            else -> p
        }
    })
}

// --- JSON (LauncherModel's persist()/load(), same style as LayoutBackup.kt's widget helpers). ---

internal fun JSONObject.toHomeMode(): HomeMode {
    val pages = optJSONArray("visiblePages") ?: JSONArray()
    val scheduleObject = optJSONObject("schedule")
    val schedule = scheduleObject?.let {
        val weekdays = it.optJSONArray("weekdays") ?: JSONArray()
        ModeSchedule(List(weekdays.length()) { i -> weekdays.optInt(i) }.toSet(),
            it.optInt("start", 0), it.optInt("end", 0))
    }
    return HomeMode(getString("id"), getString("name"), optString("icon", "apps"),
        List(pages.length()) { pages.optInt(it) }.toSet(), schedule, optBoolean("dnd", false))
}

internal fun HomeMode.toJson(): JSONObject {
    val json = JSONObject().put("id", id).put("name", name).put("icon", icon)
        .put("visiblePages", JSONArray(visiblePages.sorted())).put("dnd", dndTrigger)
    schedule?.let { s -> json.put("schedule", JSONObject().put("weekdays", JSONArray(s.weekdays.sorted()))
        .put("start", s.startMinute).put("end", s.endMinute)) }
    return json
}

internal fun JSONArray.toHomeModes(): List<HomeMode> {
    val loaded = List(length()) { getJSONObject(it).toHomeMode() }
    return loaded.ifEmpty { defaultHomeModes() }.let { modes ->
        if (modes.any { it.id == ALL_MODE_ID }) modes else listOf(HomeMode(ALL_MODE_ID, ALL_MODE_NAME)) + modes
    }
}

internal fun List<HomeMode>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it.toJson()) } }
