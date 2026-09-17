package cz.pflanzer.foldduo.predict

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Hard cap on persisted launch events (B35 spec); oldest events are dropped first. */
const val LAUNCH_EVENT_CAP = 2000

/** Pure: [existing] (oldest first) with [event] appended, trimmed to at most [cap] by dropping the oldest overflow. */
fun appendLaunchEvent(existing: List<LaunchEvent>, event: LaunchEvent, cap: Int = LAUNCH_EVENT_CAP): List<LaunchEvent> {
    val updated = existing + event
    return if (updated.size > cap) updated.takeLast(cap) else updated
}

/**
 * Pure: [existing] merged with [additions] (e.g. a UsageStats backfill), de-duplicated by
 * (package, timestamp) — the same event seen both from our own launch path and UsageStats counts
 * once — sorted oldest first, capped at [cap].
 */
fun mergeLaunchEvents(existing: List<LaunchEvent>, additions: List<LaunchEvent>, cap: Int = LAUNCH_EVENT_CAP): List<LaunchEvent> {
    val merged = (existing + additions).distinctBy { it.packageName to it.timestampMs }.sortedBy { it.timestampMs }
    return if (merged.size > cap) merged.takeLast(cap) else merged
}

private fun serialize(events: List<LaunchEvent>): String {
    val array = JSONArray()
    events.forEach { event ->
        array.put(
            JSONObject()
                .put("pkg", event.packageName)
                .put("t", event.timestampMs)
                .put("hb", event.hourBucket)
                .put("we", event.weekend)
                .put("net", event.network.name)
                .put("place", event.place ?: JSONObject.NULL)
        )
    }
    return array.toString()
}

private fun deserialize(raw: String): List<LaunchEvent> = runCatching {
    val array = JSONArray(raw)
    List(array.length()) { index ->
        val item = array.getJSONObject(index)
        LaunchEvent(
            packageName = item.getString("pkg"),
            timestampMs = item.getLong("t"),
            hourBucket = item.getInt("hb"),
            weekend = item.getBoolean("we"),
            network = runCatching { NetworkContext.valueOf(item.getString("net")) }.getOrDefault(NetworkContext.UNKNOWN),
            place = item.optString("place", "").ifBlank { null },
        )
    }
}.getOrDefault(emptyList())

/** SharedPreferences-backed store for [LaunchEvent]s, capped at [LAUNCH_EVENT_CAP] via [appendLaunchEvent]/[mergeLaunchEvents]. */
class LaunchEventStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun load(): List<LaunchEvent> = deserialize(prefs.getString(KEY_EVENTS, null) ?: "[]")
    fun record(event: LaunchEvent) = save(appendLaunchEvent(load(), event))
    /** UsageStats backfill (PredictionGlue.kt's [usageStatsLaunchEvents]) merged in, deduplicated. */
    fun backfill(events: List<LaunchEvent>) { if (events.isNotEmpty()) save(mergeLaunchEvents(load(), events)) }
    private fun save(events: List<LaunchEvent>) { prefs.edit().putString(KEY_EVENTS, serialize(events)).apply() }
    companion object {
        internal const val PREFS = "predict_events"
        private const val KEY_EVENTS = "events"
    }
}

/** "Nezobrazovat" block list: packages the Suggestions row must never offer again. */
class PredictionBlockList(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(LaunchEventStore.PREFS, Context.MODE_PRIVATE)
    fun blocked(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty()
    fun block(packageName: String) { prefs.edit().putStringSet(KEY_BLOCKED, blocked() + packageName).apply() }
    fun unblock(packageName: String) { prefs.edit().putStringSet(KEY_BLOCKED, blocked() - packageName).apply() }
    companion object { private const val KEY_BLOCKED = "blocked" }
}

/**
 * "Suggestions on Today" / "Continuity chip" toggles (Appearance settings, both default on) —
 * self-contained the same way [cz.pflanzer.foldduo.colorsFromWallpaperEnabled] is: its own prefs
 * file, read/written directly by the settings row, so this package stays free of
 * [cz.pflanzer.foldduo.AppearanceStore]/[cz.pflanzer.foldduo.AppearanceState] plumbing.
 */
object PredictionSettings {
    private const val PREFS = "predict_settings"
    private const val KEY_SUGGESTIONS = "suggestions_on_today"
    private const val KEY_CONTINUITY = "continuity_chip"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun suggestionsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SUGGESTIONS, true)
    fun setSuggestionsEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_SUGGESTIONS, value).apply() }
    fun continuityEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CONTINUITY, true)
    fun setContinuityEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_CONTINUITY, value).apply() }
}
