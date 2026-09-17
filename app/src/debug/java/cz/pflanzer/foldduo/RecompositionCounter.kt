package cz.pflanzer.foldduo

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Výkon 4 "recompositions při swipe" (17. 9. noc): a debug-only per-tag recomposition counter for
 * the composables on the expanded workspace's swipe path — see every [trackRecomposition] call
 * site (`ExpandedWorkspace`, `HomePagePane`, `SharedHomeGrid`, `AppTile`, `MovableWidget`,
 * `HostWidgetView`, `LeadingTopSlot`, the page indicator, `StatusRail`, `RailIsland`,
 * `DuneWallpaper`). A `SideEffect` runs once after every composition that actually *commits*
 * (the first one included, then again each time Compose re-runs that composable's body) — so
 * incrementing a tag's counter from inside one is exactly "how many times did this composable
 * recompose", not how many times it was merely invoked as part of a larger skipped subtree.
 *
 * [startEpisode]/[endEpisode] bracket one swipe the same way [PagerSwipeFrameMetricsCollector]
 * does (LauncherScreen.kt's own `nativePager.isScrollInProgress` `LaunchedEffect` drives both, so
 * a swipe's frame-timing log and its recomposition log always cover the exact same window) and log
 * the top tags under `FoldDuoPerf` — a device run says exactly WHAT churned during a janky swipe,
 * not just that some frame was slow. The release source set supplies a no-op with the same API.
 */
internal object RecompositionCounter {
    private const val TAG = "FoldDuoPerf"
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Bumps [tag]'s counter by one recomposition. Safe from the main thread only (Compose's own
     * threading contract for composition), like every other counter here. */
    fun increment(tag: String) {
        counts.getOrPut(tag) { AtomicInteger(0) }.incrementAndGet()
    }

    /** Zeroes every counter — call once a swipe episode starts, so its log covers only that swipe. */
    fun startEpisode() {
        counts.clear()
    }

    /** Logs the top tags (by recomposition count) since the last [startEpisode]; silent if nothing
     * incremented at all (a swipe that recomposed nothing is exactly the goal, not a bug). */
    fun endEpisode() {
        val snapshot = counts.mapValues { it.value.get() }
        if (snapshot.isEmpty()) return
        Log.i(TAG, "recompositions during swipe: ${formatRecompositionLog(snapshot)}")
    }
}

/**
 * Marks the calling composable as tracked under [tag]: a plain `SideEffect` that bumps
 * [RecompositionCounter]'s counter for [tag] every time the calling composable's body actually
 * re-executes. Called at the very top of a composable's body (before any early return that would
 * skip building its usual content), so an early-return branch still counts as one recomposition of
 * this composable, matching what the Compose runtime itself considers "this scope ran again".
 */
@Composable
internal fun trackRecomposition(tag: String) {
    SideEffect { RecompositionCounter.increment(tag) }
}

/**
 * Pure aggregation behind [RecompositionCounter.endEpisode]: the [limit] highest counts,
 * descending — extracted so it has a plain-JUnit test independent of `Log`/Android, same pattern
 * as MainThreadWatchdog.kt's `topSlowestFrames`. Ties keep [counts]' own iteration order (Kotlin's
 * `sortedByDescending` is a stable sort). Total: never throws, an empty map returns an empty list.
 */
internal fun topRecompositionCounts(counts: Map<String, Int>, limit: Int = 10): List<Pair<String, Int>> =
    counts.entries.sortedByDescending { it.value }.take(limit.coerceAtLeast(0)).map { it.key to it.value }

/** The exact "tag=count, tag=count, …" log body [RecompositionCounter.endEpisode] writes, as a
 * pure function so its formatting/ordering has its own test. */
internal fun formatRecompositionLog(counts: Map<String, Int>, limit: Int = 10): String =
    topRecompositionCounts(counts, limit).joinToString(", ") { "${it.first}=${it.second}" }
