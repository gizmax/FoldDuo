package cz.pflanzer.foldduo.predict

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Logcat tag for the continuity chip's best-effort adjacent-launch outcome (B35 task note). */
private const val TAG = "FoldDuoPredict"

/** Same app-op check Spotlight.kt's `hasUsageAccess` uses; duplicated here so this package stays independent of Spotlight.kt (both read the special "usage access" grant, never the network). */
internal fun hasUsageAccess(context: Context): Boolean = runCatching {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** Wi‑Fi vs mobile vs unknown, from the active network's transport — the coarse "place" signal B35 asks for; no SSID (that needs location on Android 13+) and no network traffic of our own. */
fun currentNetworkContext(context: Context): NetworkContext = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkContext.UNKNOWN
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkContext.UNKNOWN
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkContext.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkContext.MOBILE
        else -> NetworkContext.UNKNOWN
    }
}.getOrDefault(NetworkContext.UNKNOWN)

/** The Appearance settings' manual place name, read straight out of its own prefs file (same self-contained pattern as [PredictionSettings]) — null when no place is set (Appearance not in "Sunrise / sunset", or cleared). */
fun manualPlace(context: Context): String? {
    val place = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("place", null)
    return place?.trim()?.takeIf { it.isNotEmpty() }
}

/** [hourBucket]/weekend/network/place "right now" ([nowMs]), for [predictTopApps] and [buildLaunchEvent]. */
fun currentPredictionContext(context: Context, nowMs: Long = System.currentTimeMillis()): PredictionContext {
    val zoned = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
    val weekend = zoned.dayOfWeek == DayOfWeek.SATURDAY || zoned.dayOfWeek == DayOfWeek.SUNDAY
    return PredictionContext(hourBucket(zoned.hour), weekend, currentNetworkContext(context), manualPlace(context))
}

/** [LaunchEvent] for a launch happening right now (or at [atMs]), for [LaunchEventStore.record]. */
fun buildLaunchEvent(context: Context, packageName: String, atMs: Long = System.currentTimeMillis()): LaunchEvent {
    val ctx = currentPredictionContext(context, atMs)
    return LaunchEvent(packageName, atMs, ctx.hourBucket, ctx.weekend, ctx.network, ctx.place)
}

/**
 * UsageStats-derived launch events between [sinceMs] and [untilMs] (MOVE_TO_FOREGROUND only, our
 * own package excluded), for [LaunchEventStore.backfill]. Empty without usage access. Historical
 * network/place are unknowable in hindsight, so those events are scored on time-of-day and
 * weekday only ([predictTopApps] simply never matches [NetworkContext.UNKNOWN]/null place against
 * a live [PredictionContext] that has a real value).
 */
fun usageStatsLaunchEvents(context: Context, sinceMs: Long, untilMs: Long = System.currentTimeMillis()): List<LaunchEvent> {
    if (!hasUsageAccess(context)) return emptyList()
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
    val events = runCatching { usm.queryEvents(sinceMs, untilMs) }.getOrNull() ?: return emptyList()
    val result = mutableListOf<LaunchEvent>()
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != context.packageName) {
            val zoned = Instant.ofEpochMilli(event.timeStamp).atZone(ZoneId.systemDefault())
            val weekend = zoned.dayOfWeek == DayOfWeek.SATURDAY || zoned.dayOfWeek == DayOfWeek.SUNDAY
            result += LaunchEvent(event.packageName, event.timeStamp, hourBucket(zoned.hour), weekend, NetworkContext.UNKNOWN, null)
        }
    }
    return result
}

/** The app in the foreground within the last [withinMs] (up to [nowMs]), other than [ownPackage] — the real signal behind the continuity chip; null without usage access or if nothing else ran. */
fun lastForegroundApp(
    context: Context,
    ownPackage: String,
    withinMs: Long = CONTINUITY_FOREGROUND_WINDOW_MS,
    nowMs: Long = System.currentTimeMillis(),
): ForegroundSighting? {
    if (!hasUsageAccess(context)) return null
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
    val events = runCatching { usm.queryEvents(nowMs - withinMs, nowMs) }.getOrNull() ?: return null
    var last: ForegroundSighting? = null
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != ownPackage) {
            last = ForegroundSighting(event.packageName, event.packageName, event.timeStamp)
        }
    }
    return last
}

/**
 * Own-launch fallback for the continuity chip when usage access is not granted: the launcher's
 * own last [cz.pflanzer.foldduo.MainActivity] launch. Process-lifetime only, not persisted — a
 * fresh process has no continuity to offer until it launches something itself, which is fine
 * since the chip only ever matters for a launch that just happened.
 */
object LastLaunchTracker {
    @Volatile private var last: ForegroundSighting? = null
    fun record(packageName: String, label: String, atMs: Long = System.currentTimeMillis()) {
        last = ForegroundSighting(packageName, label, atMs)
    }
    fun last(): ForegroundSighting? = last
    /** Test-only reset so unit/instrumentation runs don't leak state across cases. */
    internal fun clear() { last = null }
}

/** The continuity sighting to judge against the swap: real UsageStats data when granted, else [LastLaunchTracker]'s own-launch memory. */
fun continuitySighting(
    context: Context,
    ownPackage: String,
    withinMs: Long = CONTINUITY_FOREGROUND_WINDOW_MS,
    nowMs: Long = System.currentTimeMillis(),
): ForegroundSighting? = lastForegroundApp(context, ownPackage, withinMs, nowMs) ?: LastLaunchTracker.last()

/**
 * Launches [sighting]'s package for the continuity chip: `FLAG_ACTIVITY_NEW_TASK` +
 * `FLAG_ACTIVITY_LAUNCH_ADJACENT` with [leftPaneBounds] as a best-effort split-screen hint into
 * the left pane. Android gives no callback for whether the adjacent request was actually honored
 * (outside an existing multi-window session it silently opens full screen instead) — this logs
 * the request made under tag [TAG] so a logcat capture on-device shows what actually happened,
 * rather than the app guessing.
 */
fun launchContinuityApp(context: Context, sighting: ForegroundSighting, leftPaneBounds: Rect?): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(sighting.packageName)
    if (intent == null) {
        Log.d(TAG, "continuity: no launch intent for ${sighting.packageName}")
        return false
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    val options = leftPaneBounds?.let { bounds -> ActivityOptions.makeBasic().apply { setLaunchBounds(bounds) } }
    return try {
        context.startActivity(intent, options?.toBundle())
        Log.d(TAG, "continuity: requested ${sighting.packageName} adjacent, bounds=$leftPaneBounds (system may still open it full-screen)")
        true
    } catch (e: Exception) {
        Log.d(TAG, "continuity: launch failed for ${sighting.packageName}: ${e.message}")
        false
    }
}
