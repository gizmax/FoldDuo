package cz.pflanzer.foldduo.predict

import android.content.Context
import cz.pflanzer.foldduo.AppEntry

/**
 * B35: the one object [cz.pflanzer.foldduo.MainActivity] holds to record launches, rank
 * suggestions and manage the block list — everything else in this package is either pure logic
 * ([predictTopApps], [continuityEligible]) or thin Android glue this wires together.
 */
class PredictionController(context: Context) {
    private val appContext = context.applicationContext
    private val eventStore = LaunchEventStore(appContext)
    private val blockList = PredictionBlockList(appContext)

    /** Call from the launch path ([cz.pflanzer.foldduo.MainActivity.launchApp]) right after the app actually launches. */
    fun recordLaunch(packageName: String, atMs: Long = System.currentTimeMillis()) {
        eventStore.record(buildLaunchEvent(appContext, packageName, atMs))
    }

    /** Merge in UsageStats history once usage access is granted (call on resume; a no-op otherwise or once nothing new is left to add). */
    fun backfillFromUsageStats(sinceMs: Long) {
        eventStore.backfill(usageStatsLaunchEvents(appContext, sinceMs))
    }

    /**
     * Top [topN] [AppEntry] to show on Today, best first — empty when "Suggestions on Today" is
     * off, apps whose [AppEntry.id] is in [dockIds] (i.e. already pinned to the dock) and the
     * block list are excluded, and a package with no matching installed [AppEntry] (uninstalled
     * since it was launched) is silently dropped rather than crashing.
     */
    fun suggestions(allApps: List<AppEntry>, dockIds: Set<String>, nowMs: Long = System.currentTimeMillis(), topN: Int = 4): List<AppEntry> {
        if (!PredictionSettings.suggestionsEnabled(appContext)) return emptyList()
        val events = eventStore.load()
        if (events.isEmpty()) return emptyList()
        val available = allApps.filter { it.available }
        val dockPackages = available.filter { it.id in dockIds }.mapTo(mutableSetOf()) { it.packageName }
        val context = currentPredictionContext(appContext, nowMs)
        val ranked = predictTopApps(events, nowMs, context, excludePackages = dockPackages, blocked = blockList.blocked(), topN = topN)
        val byPackage = available.associateBy { it.packageName }
        return ranked.mapNotNull { byPackage[it] }
    }

    /** "Nezobrazovat": never suggest this package again. */
    fun block(packageName: String) = blockList.block(packageName)

    /** The continuity sighting to offer at a cover→inner swap, or null when the chip is off or there is nothing eligible. */
    fun continuityCandidate(ownPackage: String, swapAtMs: Long = System.currentTimeMillis()): ForegroundSighting? {
        if (!PredictionSettings.continuityEnabled(appContext)) return null
        val sighting = continuitySighting(appContext, ownPackage, nowMs = swapAtMs)
        return sighting.takeIf { continuityEligible(it, ownPackage, swapAtMs) }
    }
}
