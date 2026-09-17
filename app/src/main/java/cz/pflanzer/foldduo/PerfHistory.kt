package cz.pflanzer.foldduo

/**
 * B43 "Výkon jako feature": a small process-wide ring buffer of recent frame-stats episodes plus
 * the last cold-start timing, so the debug-only `cz.pflanzer.foldduo.DEBUG_PERF` broadcast
 * (app/src/debug/.../DebugPerf.kt) can dump both to logcat on request, after the fact — without
 * either frame-stats collector ([MorphFrameMetricsCollector] in HighRefreshRate.kt, or
 * systemfrost's `ChoreographerFrameSampler`) knowing about the broadcast, and without the
 * broadcast needing to be armed before the episode it is asked about already happened. Kept free
 * of any Android call except the default clock parameter (never reached when a caller supplies
 * its own, same convention as [MorphController]'s `now`), so it stays plain-JUnit testable.
 */
object PerfHistory {
    /** One completed frame-stats episode: which collector it came from, its aggregate, and when it ended. */
    data class Episode(val label: String, val stats: FrameStats, val atElapsedMs: Long)

    /** Cold-start timing, logged once per process by [cz.pflanzer.foldduo.MainActivity]. */
    data class Startup(val coldStartToFirstFrameMs: Long, val firstFrameToInteractiveMs: Long?)

    private const val MAX_EPISODES = 20
    private val lock = Any()
    private val episodesList = ArrayDeque<Episode>(MAX_EPISODES)

    @Volatile
    var startup: Startup? = null
        private set

    /** Appends one episode, evicting the oldest past [MAX_EPISODES]. A zero-frame episode (nothing observed) is not worth keeping. */
    fun record(label: String, stats: FrameStats, atElapsedMs: Long) {
        if (stats.frames == 0) return
        synchronized(lock) {
            episodesList.addLast(Episode(label, stats, atElapsedMs))
            while (episodesList.size > MAX_EPISODES) episodesList.removeFirst()
        }
    }

    /** [MainActivity] calls this once it has the cold-start number; [interactiveMs] may follow later via [noteInteractive]. */
    fun noteStartup(coldStartToFirstFrameMs: Long) {
        startup = Startup(coldStartToFirstFrameMs, startup?.firstFrameToInteractiveMs)
    }

    /** [MainActivity] calls this once `reportFullyDrawn()` fires, filling in the second half of [startup]. */
    fun noteInteractive(firstFrameToInteractiveMs: Long) {
        startup = startup?.copy(firstFrameToInteractiveMs = firstFrameToInteractiveMs)
            ?: Startup(coldStartToFirstFrameMs = -1, firstFrameToInteractiveMs = firstFrameToInteractiveMs)
    }

    /** Snapshot of the last (at most) [n] episodes, oldest first. */
    fun episodes(n: Int = MAX_EPISODES): List<Episode> = synchronized(lock) { episodesList.toList() }.takeLast(n)

    /** Test-only: drop everything recorded so far. */
    internal fun clearForTest() {
        synchronized(lock) { episodesList.clear() }
        startup = null
    }

    /**
     * The debug `DEBUG_PERF` broadcast's own body, shared with the Diagnostics settings page's
     * "Dump perf to logcat" button (release builds have no broadcast receiver — `app/src/debug`
     * is excluded from that source set — so the button calls straight in here instead). Only ever
     * exercised from a running app, never from [PerfHistoryTest], so the plain [android.util.Log]
     * call is safe despite this object otherwise staying Android-free for JVM testability.
     */
    fun logSummary(n: Int = MAX_EPISODES) {
        val tag = "FoldDuoPerf"
        android.util.Log.i(tag, "startup: coldStart->firstFrame=${startup?.coldStartToFirstFrameMs?.let { "${it}ms" } ?: "unknown"} " +
            "firstFrame->interactive=${startup?.firstFrameToInteractiveMs?.let { "${it}ms" } ?: "unknown"}")
        val snapshot = episodes(n)
        if (snapshot.isEmpty()) android.util.Log.i(tag, "no frame-stats episodes recorded yet")
        else snapshot.forEach { e -> android.util.Log.i(tag, "episode[${e.label}] t+${e.atElapsedMs}ms: ${e.stats.logLine()}") }
    }
}
