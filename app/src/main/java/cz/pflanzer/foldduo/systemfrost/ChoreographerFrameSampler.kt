package cz.pflanzer.foldduo.systemfrost

import android.util.Log
import android.view.Choreographer
import cz.pflanzer.foldduo.FrameStats
import cz.pflanzer.foldduo.PerfHistory

/**
 * B22's jank measurement for [SystemFrost]'s overlay window: a `TYPE_ACCESSIBILITY_OVERLAY`
 * window belongs to the accessibility service, not an [android.app.Activity], so there is no
 * [android.view.Window] to hang a `FrameMetrics` listener off (that needs the window's own
 * `Window.addOnFrameMetricsAvailableListener`, an Activity/Dialog API). [Choreographer] is
 * process-wide instead: sampling the gap between successive `doFrame` callbacks while an episode
 * runs approximates each frame's total time closely enough for the same [FrameStats] aggregation
 * the launcher's own `MorphFrameMetricsCollector` (HighRefreshRate.kt) uses, at the cost of also
 * counting frames drawn by whatever app is in front (unavoidable: this Choreographer is the one
 * process-wide clock, and the overlay itself has no per-frame callback of its own).
 */
class ChoreographerFrameSampler {
    private var running = false
    private var lastFrameNs = 0L
    private val gapsMs = mutableListOf<Double>()

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNs != 0L) gapsMs += (frameTimeNanos - lastFrameNs) / 1_000_000.0
            lastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        gapsMs.clear()
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        if (gapsMs.isNotEmpty()) {
            val stats = FrameStats.from(gapsMs)
            Log.i(TAG, "episode: ${stats.logLine()}")
            PerfHistory.record("systemfrost", stats, android.os.SystemClock.elapsedRealtime())
        }
    }

    private companion object { const val TAG = "FoldDuoSystemFrost" }
}
