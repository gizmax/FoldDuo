package cz.pflanzer.foldduo.island

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * B61 phase 2 ("Ostrov kolem kamery", IDEAS.md): live-activity adapters that turn an ongoing
 * notification into the extra [IslandItem] fields the camera island's expanded card wants — an
 * ETA, a stage list, the compact pill's trailing value, whether a picture slot is worth reserving.
 * Everything below [notificationFacts] is plain Kotlin over [NotificationFacts], so it is testable
 * on the JVM without a `Notification`/`StatusBarNotification` in sight; [notificationFacts] is the
 * single place that reaches into Android types, mirroring how [IslandNotificationListener] already
 * keeps the heavy per-notification mapping off the pure classifier ([islandKindFor] etc. in
 * IslandItem.kt).
 */

/** One notification action, reduced to what an adapter could ever need from it. */
data class FactAction(val title: String, val index: Int)

/**
 * Everything an adapter can read off a posted notification, with no Android types. Built by
 * [notificationFacts] in one pass; adapters only ever see this.
 */
data class NotificationFacts(
    val packageName: String,
    val channel: String? = null,
    val category: String? = null,
    val title: String = "",
    val text: String = "",
    val subText: String? = null,
    val extras: Map<String, Any?> = emptyMap(),
    val actions: List<FactAction> = emptyList(),
    val progress: Int? = null,
    val progressMax: Int? = null,
    val progressIndeterminate: Boolean = false,
    val chronometerBase: Long? = null,
    val whenTime: Long = 0L,
    val ongoing: Boolean = false,
    /** `FLAG_PROMOTED_ONGOING` (Android 16 Live Updates) or the `EXTRA_REQUEST_PROMOTED_ONGOING` extra. */
    val promoted: Boolean = false,
    val smallIconRes: Int? = null,
    val largeIconPresent: Boolean = false,
    val style: String? = null,
)

/** The `android.requestPromotedOngoing` boolean extra a Live Update also carries, repeated by name for the same reason [FLAG_PROMOTED_ONGOING] is repeated by value (compileSdk 36 has no stable public constant for it yet). */
const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
/** `ProgressStyle.Segment`/`.Point` labels, when the platform (or an OEM shim) exposes them as a plain extra rather than only inside the parcelled style object. Best-effort: see docs/research/live-activities.md. */
internal const val EXTRA_PROGRESS_SEGMENT_LABELS = "android.progressSegmentLabels"
internal const val EXTRA_PROGRESS_SEGMENTS = "android.progressSegments"
internal const val EXTRA_PROGRESS_SEGMENT_INDEX = "android.progressCurrentSegment"

/** Builds [NotificationFacts] from a real [StatusBarNotification] — the one Android-touching mapping in this file. */
fun notificationFacts(sbn: StatusBarNotification): NotificationFacts {
    val notification = sbn.notification
    val extras = notification.extras
    val extrasMap = extras.keySet().associateWith { key -> runCatching { extras.get(key) }.getOrNull() }
    val actions = notification.actions.orEmpty().mapIndexed { index, action ->
        FactAction(action.title?.toString().orEmpty(), index)
    }
    val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0).takeIf { it > 0 }
    val showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
    val promotedFlag = notification.flags and FLAG_PROMOTED_ONGOING != 0
    return NotificationFacts(
        packageName = sbn.packageName,
        channel = runCatching { notification.channelId }.getOrNull(),
        category = notification.category,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        extras = extrasMap,
        actions = actions,
        progress = extras.getInt(Notification.EXTRA_PROGRESS, -1).takeIf { it >= 0 },
        progressMax = progressMax,
        progressIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
        chronometerBase = notification.`when`.takeIf { showsChronometer },
        whenTime = notification.`when`,
        ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        promoted = promotedFlag || extras.containsKey(EXTRA_REQUEST_PROMOTED_ONGOING),
        smallIconRes = runCatching { notification.smallIcon?.resId }.getOrNull(),
        largeIconPresent = runCatching { notification.getLargeIcon() }.getOrNull() != null,
        style = runCatching { extras.getString(Notification.EXTRA_TEMPLATE) }.getOrNull(),
    )
}

// --- Known packages (task list: Uber, Bolt, Rohlík, Wolt, Mapy.cz, Google Maps nav, Foodora) ---

const val PKG_UBER = "com.ubercab"
const val PKG_BOLT = "ee.mtakso.client"
const val PKG_ROHLIK = "cz.rohlik.app"
const val PKG_WOLT = "com.wolt.android"
const val PKG_MAPY = "cz.seznam.mapy"
const val PKG_GOOGLE_MAPS = "com.google.android.apps.maps"
const val PKG_FOODORA = "com.global.foodpanda.android"

private val NAVIGATION_PACKAGES = setOf(PKG_MAPY, PKG_GOOGLE_MAPS)
private val RIDE_DELIVERY_PACKAGES = setOf(PKG_UBER, PKG_BOLT, PKG_ROHLIK, PKG_WOLT, PKG_FOODORA)

/**
 * The island kind a recognised ride/delivery/navigation app's own ongoing notification always
 * qualifies as, even without a `android.category`, chronometer or progress bar — these apps ship
 * their own foreground-service notification, so (like [samsungOngoingKind]'s Now Bar activities)
 * the package name alone is enough to know it belongs in the island. Feeds [islandKindFor]'s
 * `appOngoingKind` parameter; unknown packages return null so the existing category/shape rules
 * decide, unchanged.
 */
fun appOngoingKindFor(packageName: String): IslandKind? = when (packageName) {
    in NAVIGATION_PACKAGES -> IslandKind.NAVIGATION
    in RIDE_DELIVERY_PACKAGES -> IslandKind.TRANSPORT
    else -> null
}

/** Enrichment an adapter derived for one notification; kind/qualification stays [appOngoingKindFor]'s job. */
data class LiveActivityMatch(
    val etaMs: Long? = null,
    val segments: List<String> = emptyList(),
    val stageIndex: Int? = null,
    val keyValue: String? = null,
    val pictureKey: String? = null,
)

// --- ETA parsing: "4 min", "12–15 min", "za 8 min", "arriving in 3 min", "12:34" ---

private val ETA_RANGE_MINUTES = Regex("""(\d{1,3})\s*[–—-]\s*(\d{1,3})\s*min""", RegexOption.IGNORE_CASE)
private val ETA_SINGLE_MINUTES = Regex("""(?:za\s+)?(\d{1,3})\s*min""", RegexOption.IGNORE_CASE)
private val CLOCK_TIME_TRAILING = Regex("""(\d{1,2}[:.]\d{2}\s*(?:[APap]\.?[Mm]?\.?)?)\s*$""")

/** `(etaMs, displayText)` parsed out of free text; null/null when nothing matched. [now] anchors a relative "X min"/"za X min" reading; an absolute "12:34" is resolved against today ([samsungOngoingEndTime]). */
internal fun parseEtaAndKeyValue(text: String, now: Long): Pair<Long?, String?> {
    ETA_RANGE_MINUTES.find(text)?.let { match ->
        val lo = match.groupValues[1].toInt()
        val hi = match.groupValues[2].toInt()
        return (now + hi * 60_000L) to "$lo–$hi min"
    }
    ETA_SINGLE_MINUTES.find(text)?.let { match ->
        val minutes = match.groupValues[1].toIntOrNull()
        if (minutes != null) return (now + minutes * 60_000L) to "$minutes min"
    }
    CLOCK_TIME_TRAILING.find(text.trim())?.let { match ->
        samsungOngoingEndTime(text, now)?.let { end -> return end to match.value.trim() }
    }
    return null to null
}

// --- Stage words (Czech + English), checked in priority order; first hit wins. ---

private val DELIVERY_STAGE_WORDS: List<Pair<List<String>, String>> = listOf(
    listOf("doručeno", "delivered", "dorazila objednávka") to "Doručeno",
    listOf("na cestě", "kurýr", "courier", "on the way", "on its way") to "Kurýr",
    listOf("připravuje", "preparing", "being prepared") to "Připravuje se",
    listOf("objednáno", "objednávka přijata", "order placed", "order confirmed", "order received") to "Objednáno",
)
/** In task order: "Objednáno · Připravuje se · Kurýr · Doručeno". */
val DELIVERY_SEGMENTS: List<String> = listOf("Objednáno", "Připravuje se", "Kurýr", "Doručeno")

private val RIDE_STAGE_WORDS: List<Pair<List<String>, String>> = listOf(
    listOf("arrived", "je tady", "is here", "dojel řidič") to "Přijel",
    listOf("hledá", "looking for a driver", "finding you a driver", "requested") to "Hledá se řidič",
    listOf("řidič", "driver", "dorazí", "away", "na cestě", "kurýr", "courier") to "Řidič jede",
    listOf("jízda", "trip started", "en route", "in progress") to "Jízda",
)
val RIDE_SEGMENTS: List<String> = listOf("Hledá se řidič", "Řidič jede", "Přijel", "Jízda")

private fun matchStage(text: String, stageWords: List<Pair<List<String>, String>>): String? {
    val lower = text.lowercase()
    return stageWords.firstOrNull { (words, _) -> words.any { it in lower } }?.second
}

private fun combinedText(facts: NotificationFacts): String =
    listOfNotNull(facts.title, facts.text, facts.subText).filter { it.isNotBlank() }.joinToString(" · ")

private fun pictureKeyFor(facts: NotificationFacts): String? = "large_icon".takeIf { facts.largeIconPresent }

// --- Adapter (d): per-app parsers ---

private fun deliveryMatch(facts: NotificationFacts, now: Long): LiveActivityMatch? {
    val text = combinedText(facts)
    if (text.isBlank()) return null
    val (etaMs, etaLabel) = parseEtaAndKeyValue(text, now)
    val stage = matchStage(text, DELIVERY_STAGE_WORDS)
    if (etaMs == null && stage == null) return null
    return LiveActivityMatch(
        etaMs = etaMs,
        segments = DELIVERY_SEGMENTS,
        stageIndex = stage?.let(DELIVERY_SEGMENTS::indexOf)?.takeIf { it >= 0 },
        keyValue = etaLabel ?: stage,
        pictureKey = pictureKeyFor(facts),
    )
}

private fun rideMatch(facts: NotificationFacts, now: Long): LiveActivityMatch? {
    val text = combinedText(facts)
    if (text.isBlank()) return null
    val (etaMs, etaLabel) = parseEtaAndKeyValue(text, now)
    val stage = matchStage(text, RIDE_STAGE_WORDS)
    if (etaMs == null && stage == null) return null
    return LiveActivityMatch(
        etaMs = etaMs,
        segments = RIDE_SEGMENTS,
        stageIndex = stage?.let(RIDE_SEGMENTS::indexOf)?.takeIf { it >= 0 },
        keyValue = etaLabel ?: stage,
        pictureKey = pictureKeyFor(facts),
    )
}

private fun navigationMatch(facts: NotificationFacts, now: Long): LiveActivityMatch? {
    val text = combinedText(facts)
    if (text.isBlank()) return null
    val (etaMs, etaLabel) = parseEtaAndKeyValue(text, now)
    if (etaMs == null) return null
    return LiveActivityMatch(etaMs = etaMs, keyValue = etaLabel, pictureKey = pictureKeyFor(facts))
}

private val PACKAGE_ADAPTERS: Map<String, (NotificationFacts, Long) -> LiveActivityMatch?> = mapOf(
    PKG_UBER to ::rideMatch,
    PKG_BOLT to ::rideMatch,
    PKG_ROHLIK to ::deliveryMatch,
    PKG_WOLT to ::deliveryMatch,
    PKG_FOODORA to ::deliveryMatch,
    PKG_MAPY to ::navigationMatch,
    PKG_GOOGLE_MAPS to ::navigationMatch,
)

// --- Adapter (a): Android 16 Live Updates ProgressStyle segments/points, best-effort ---

private fun liveUpdateSegments(extras: Map<String, Any?>): List<String> {
    val source = (extras[EXTRA_PROGRESS_SEGMENT_LABELS] as? List<*>) ?: (extras[EXTRA_PROGRESS_SEGMENTS] as? List<*>)
    return source.orEmpty().mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
}

private fun liveUpdateStageIndex(extras: Map<String, Any?>): Int? = when (val raw = extras[EXTRA_PROGRESS_SEGMENT_INDEX]) {
    is Int -> raw
    is Number -> raw.toInt()
    else -> null
}

// --- Adapter (c): generic ongoing progress bar (delivery/upload/download without a known package) ---

private fun genericProgressMatch(facts: NotificationFacts): LiveActivityMatch? {
    if (!facts.ongoing) return null
    if (facts.progressIndeterminate) return LiveActivityMatch(pictureKey = pictureKeyFor(facts))
    val max = facts.progressMax
    val current = facts.progress
    if (max != null && max > 0 && current != null) {
        val pct = (current * 100 / max).coerceIn(0, 100)
        return LiveActivityMatch(keyValue = "$pct%", pictureKey = pictureKeyFor(facts))
    }
    return null
}

/**
 * The single entry point [IslandNotificationListener] calls, in priority order: a recognised
 * app's own parser (d) always wins when it matches — it already knows that app's full stage
 * pipeline better than a generic extra ever could; otherwise a promoted Live Update's own
 * ProgressStyle segments (a); otherwise a bare progress bar (c). Null means "no live-activity
 * enrichment" — the item still shows via [islandKindFor]'s existing rules, just without
 * ETA/segments/keyValue/pictureKey.
 */
fun matchLiveActivity(facts: NotificationFacts, now: Long): LiveActivityMatch? {
    PACKAGE_ADAPTERS[facts.packageName]?.invoke(facts, now)?.let { return it }
    if (facts.promoted) {
        val liveSegments = liveUpdateSegments(facts.extras)
        if (liveSegments.isNotEmpty()) {
            return LiveActivityMatch(
                segments = liveSegments,
                stageIndex = liveUpdateStageIndex(facts.extras),
                pictureKey = pictureKeyFor(facts),
            )
        }
    }
    return genericProgressMatch(facts)
}

// --- Priority ordering: call > navigation > ride/delivery ETA < 2 min (alert) > timer > media > other ---

/** A ride/delivery ETA at or under this is "arriving" — it jumps ahead of a running timer. */
const val LIVE_ACTIVITY_ALERT_WINDOW_MS = 2 * 60_000L

/** [item]'s bucket in the B61 live-activity order; ties within a bucket are broken by [rankIslandItemsWithLiveActivities] (newest first, key last). */
fun liveActivityBucket(item: IslandItem, now: Long): Int = when {
    item.kind == IslandKind.CALL -> 0
    item.kind == IslandKind.NAVIGATION -> 1
    item.kind == IslandKind.TRANSPORT && item.etaMs != null && item.etaMs - now <= LIVE_ACTIVITY_ALERT_WINDOW_MS -> 2
    item.kind == IslandKind.TIMER -> 3
    item.kind == IslandKind.MEDIA -> 4
    else -> 5
}

/** Stable live-activity-aware order: by [liveActivityBucket], newest first within a bucket, key as the final tiebreak (same shape as [rankIslandItems]). */
fun rankIslandItemsWithLiveActivities(items: List<IslandItem>, now: Long): List<IslandItem> =
    items.sortedWith(compareBy<IslandItem> { liveActivityBucket(it, now) }.thenByDescending { it.postedAt }.thenBy { it.key })
