package cz.pflanzer.foldduo

import android.os.Handler
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/**
 * B22, "120 Hz při morphu": while a morph episode runs on the launcher's own window, ask for the
 * high refresh rate; release it once idle. `Window.attributes.preferredRefreshRate` (not
 * `Surface.setFrameRate`, which needs a `Surface` this Compose activity doesn't hand out directly)
 * is the documented way to hint a whole activity window's refresh rate on API 30+ (this app's
 * minSdk is 33), and it composes with One UI's own refresh-rate policy rather than fighting it —
 * worst case (a firmware that ignores the hint, or a battery saver override) the morph just plays
 * at whatever rate the system already chose, same as before this existed.
 */
object HighRefreshRate {
    private const val TAG = "FoldDuoMorph"

    /** Ask the window for 120 Hz; a no-op (logged, swallowed) if the window is gone or the call throws. */
    fun request(window: Window) = apply(window, 120f)

    /** Hand the refresh rate back to the system's own policy. */
    fun release(window: Window) = apply(window, 0f)

    private fun apply(window: Window, hz: Float) {
        runCatching {
            val attrs = window.attributes
            if (attrs.preferredRefreshRate == hz) return
            attrs.preferredRefreshRate = hz
            window.attributes = attrs
        }.onFailure { Log.w(TAG, "preferredRefreshRate=$hz failed: ${it.message}") }
    }
}

/**
 * B22's jank measurement for the launcher's own window: a [Window.OnFrameMetricsAvailableListener]
 * active only for the length of one episode ([start]/[stop], driven by [MainActivity]'s
 * `running || coverFrostActive || closeFrostActive`), aggregated through [FrameStats] and logged
 * as one line under [TAG] when the episode ends. Per-frame [FrameMetrics.TOTAL_DURATION] (the
 * full app+sync+present time HWUI tracks) is what the task asks to log, in ms.
 */
class MorphFrameMetricsCollector(private val window: Window, private val handler: Handler) {
    private val durationsMs = mutableListOf<Double>()
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    fun start() {
        if (listener != null) return
        durationsMs.clear()
        val l = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            durationsMs += frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        listener = l
        runCatching { window.addOnFrameMetricsAvailableListener(l, handler) }
            .onFailure { Log.w(TAG, "addOnFrameMetricsAvailableListener failed: ${it.message}"); listener = null }
    }

    /**
     * @param closing "Zavírání jako Duo" item 6: the closing episode this frame-stats episode
     * overlapped, if any ([MorphController.takeClosingEpisode]) — attached to the [FrameStats]
     * so one log line and one [PerfHistory] entry carry both.
     */
    fun stop(closing: ClosingEpisodeStats? = null) {
        val l = listener ?: return
        listener = null
        runCatching { window.removeOnFrameMetricsAvailableListener(l) }
        if (durationsMs.isNotEmpty()) {
            val stats = FrameStats.from(durationsMs).copy(closing = closing)
            Log.i(TAG, "episode: ${stats.logLine()}")
            PerfHistory.record("morph", stats, android.os.SystemClock.elapsedRealtime())
        } else if (closing != null) {
            // No frames observed (a very short/instant episode) but a closing episode still
            // completed: log it on its own so item 6's "one line per closing episode" holds even
            // then (MorphController.logClosingEpisode already logged under FoldDuoMorph too, but
            // this keeps DEBUG_PERF's dump complete).
            Log.i(TAG, "closing episode (no frames observed): ${closing.logLine()}")
        }
    }

    private companion object { const val TAG = "FoldDuoMorph" }
}

/**
 * Výkon 3 "kreslení na inneru" (17. 9. noc), item 5: the same [FrameMetrics]-based episode
 * collector as [MorphFrameMetricsCollector], for the inner spread's own pager drags instead of a
 * morph — `start()`/`stop()` bracket one swipe (drag begin to settle), driven by
 * `nativePager.isScrollInProgress` (LauncherScreen.kt), and the result is logged as a "swipe
 * episode" under `FoldDuoPerf` (not `FoldDuoMorph` — this is the launcher's OWN perf log, not the
 * unfold morph's) plus recorded into [PerfHistory] under the `"swipe"` label so `DEBUG_PERF` and
 * the Diagnostics "dump perf" button pick it up the same way morph/systemfrost episodes already
 * do. Kept as its own small class rather than a parameter on [MorphFrameMetricsCollector] so a
 * failed or slow swipe collector can never be confused with (or accidentally suppress) the morph
 * one — the two run fully independently, including possibly at once (a swipe mid-fold).
 */
class PagerSwipeFrameMetricsCollector(private val window: Window, private val handler: Handler) {
    private val durationsMs = mutableListOf<Double>()
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    fun start() {
        if (listener != null) return
        durationsMs.clear()
        val l = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            durationsMs += frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        listener = l
        runCatching { window.addOnFrameMetricsAvailableListener(l, handler) }
            .onFailure { Log.w(TAG, "swipe: addOnFrameMetricsAvailableListener failed: ${it.message}"); listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { window.removeOnFrameMetricsAvailableListener(l) }
        if (durationsMs.isNotEmpty()) {
            val stats = FrameStats.from(durationsMs)
            Log.i(TAG, "swipe episode: ${stats.logLine()}")
            PerfHistory.record("swipe", stats, android.os.SystemClock.elapsedRealtime())
        }
    }

    private companion object { const val TAG = "FoldDuoPerf" }
}
