package cz.pflanzer.foldduo.predict

/*
 * B35 "Předpovědi aplikací + kontinuita": pure scoring logic behind the Today "Suggestions" row.
 * Free of Android and Compose types (same split as SpotlightModel.kt/Spotlight.kt) so it runs as
 * plain JUnit under app/src/test; the Android glue (SharedPreferences persistence, UsageStats and
 * ConnectivityManager reads) lives in LaunchEventStore.kt and PredictionGlue.kt, the Compose UI in
 * PredictionUi.kt.
 */

/** Coarse network signal at the moment of a launch: Wi‑Fi, mobile data, or unknown/offline. No SSID is ever read (that needs location on Android 13+); just the transport, which needs no extra permission beyond the already-declared ACCESS_NETWORK_STATE. */
enum class NetworkContext { WIFI, MOBILE, UNKNOWN }

/** One recorded launch, reduced to the features [predictTopApps] scores against. */
data class LaunchEvent(
    val packageName: String,
    val timestampMs: Long,
    /** Two-hour bucket of the hour of day the launch happened in, 0..11 ([hourBucket]). */
    val hourBucket: Int,
    val weekend: Boolean,
    val network: NetworkContext,
    /** The Appearance settings' manual place name at launch time, if one was set; null otherwise. */
    val place: String?,
)

/** The context a prediction is made in "right now" — the same features as [LaunchEvent], without a package or timestamp. */
data class PredictionContext(val hourBucket: Int, val weekend: Boolean, val network: NetworkContext, val place: String?)

/** Two-hour bucket (0..11) for [hourOfDay] (0..23), e.g. 13:00 -> bucket 6. */
fun hourBucket(hourOfDay: Int): Int = hourOfDay.coerceIn(0, 23) / 2

/** Half-life of a launch's contribution to the frequency score: an event exactly this old counts half as much as one from right now. */
const val PREDICTOR_HALF_LIFE_MS: Long = 7L * 24 * 60 * 60 * 1000

/** Laplace smoothing constant shared by every conditional probability below — a value seen once is never fully confident, one never seen is never zero. */
private const val ALPHA = 1.0

// Score = Σ weight · P(app | feature), each feature's conditional probability Laplace-smoothed
// and weighted by decayed recency. Hour-of-day carries the most weight (the strongest habitual
// signal at a fixed time of day), place next (a strong but rarer signal, only present once
// Appearance has a manual place set), then weekday/weekend and network, with plain frequency as
// the always-available baseline.
internal const val WEIGHT_FREQUENCY = 1.0
internal const val WEIGHT_HOUR = 1.5
internal const val WEIGHT_WEEKEND = 0.75
internal const val WEIGHT_NETWORK = 0.75
internal const val WEIGHT_PLACE = 1.25

/** Exponential-decay weight of an event from [eventMs], as seen at [nowMs]; halves every [halfLifeMs]. */
internal fun decayWeight(eventMs: Long, nowMs: Long, halfLifeMs: Long = PREDICTOR_HALF_LIFE_MS): Double {
    if (halfLifeMs <= 0) return 1.0
    val ageMs = (nowMs - eventMs).coerceAtLeast(0)
    return Math.pow(0.5, ageMs.toDouble() / halfLifeMs.toDouble())
}

/**
 * Laplace-smoothed P(app | feature = value): decayed weight of (app, value) over decayed weight
 * of (any app, value), biased towards uniform over [appCount] candidate apps by [ALPHA].
 */
private fun conditional(appWeight: Double, valueWeight: Double, appCount: Int): Double =
    (appWeight + ALPHA) / (valueWeight + ALPHA * appCount.coerceAtLeast(1))

/**
 * Top [topN] package names to suggest, best first: [events] (recency-weighted, half-life
 * [halfLifeMs], evaluated at [nowMs]) scored against [context] as `Σ weight · P(app | feature)`
 * over overall frequency, hour-of-day bucket, weekday/weekend and coarse network — plus place
 * when [context].place is set — each Laplace-smoothed. [excludePackages] (already in the dock)
 * and [blocked] ("Nezobrazovat") never appear in the result, and neither does a package with no
 * events at all. Ties break alphabetically so the order is deterministic.
 */
fun predictTopApps(
    events: List<LaunchEvent>,
    nowMs: Long,
    context: PredictionContext,
    excludePackages: Set<String> = emptySet(),
    blocked: Set<String> = emptySet(),
    topN: Int = 4,
    halfLifeMs: Long = PREDICTOR_HALF_LIFE_MS,
): List<String> {
    val candidates = events.map { it.packageName }.toSet() - excludePackages - blocked
    if (candidates.isEmpty()) return emptyList()
    val appCount = candidates.size
    val weighted = events.map { it to decayWeight(it.timestampMs, nowMs, halfLifeMs) }
    fun totalWeight(predicate: (LaunchEvent) -> Boolean): Double = weighted.filter { (event, _) -> predicate(event) }.sumOf { it.second }

    val totalAll = weighted.sumOf { it.second }
    val totalHour = totalWeight { it.hourBucket == context.hourBucket }
    val totalWeekend = totalWeight { it.weekend == context.weekend }
    val totalNetwork = totalWeight { it.network == context.network }
    val totalPlace = context.place?.let { place -> totalWeight { it.place == place } }

    val scores = candidates.associateWith { pkg ->
        fun appWeight(predicate: (LaunchEvent) -> Boolean): Double =
            weighted.filter { (event, _) -> event.packageName == pkg && predicate(event) }.sumOf { it.second }
        var score = WEIGHT_FREQUENCY * conditional(appWeight { true }, totalAll, appCount)
        score += WEIGHT_HOUR * conditional(appWeight { it.hourBucket == context.hourBucket }, totalHour, appCount)
        score += WEIGHT_WEEKEND * conditional(appWeight { it.weekend == context.weekend }, totalWeekend, appCount)
        score += WEIGHT_NETWORK * conditional(appWeight { it.network == context.network }, totalNetwork, appCount)
        if (context.place != null && totalPlace != null) {
            score += WEIGHT_PLACE * conditional(appWeight { it.place == context.place }, totalPlace, appCount)
        }
        score
    }
    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .take(topN.coerceAtLeast(0))
        .map { it.key }
}

/**
 * First free Home slot index for "Add to Home" from a suggestion long-press: the first null in
 * [homeSlots] (same flat, cross-page indexing [cz.pflanzer.foldduo.LauncherModel.move] uses), or
 * one past the end when every existing slot is taken (a fresh page, same convention `dropApp`
 * already relies on elsewhere in the grid).
 */
fun firstFreeHomeSlotIndex(homeSlots: List<String?>): Int =
    homeSlots.indexOfFirst { it == null }.let { if (it >= 0) it else homeSlots.size }
