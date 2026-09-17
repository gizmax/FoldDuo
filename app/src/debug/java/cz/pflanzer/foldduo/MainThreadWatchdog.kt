package cz.pflanzer.foldduo

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Printer
import android.view.Choreographer

/**
 * Výkon 2 "hlavní vlákno" (17. 9. noc), item 1: a small, always-on main-thread stall detector for
 * debug builds, so the *next* device run of this app tells us WHERE the main thread was stuck
 * instead of just THAT it skipped frames (`Choreographer: Skipped N frames!` names no location).
 *
 * Two independent signals, both logged under `FoldDuoPerf`:
 *  - A heartbeat runnable is posted to the main [Looper] every [HEARTBEAT_INTERVAL_MS] from a
 *    dedicated background [HandlerThread]. The same background thread polls whether that runnable
 *    has actually run; once it is more than [STALL_THRESHOLD_MS] late, the watchdog snapshots the
 *    main thread's OWN stack trace (`Looper.getMainLooper().thread.stackTrace`, read from off that
 *    thread — safe, `Thread.getStackTrace()` is designed for exactly this) *while it is still
 *    stuck*, not after it recovers (a stack captured post-recovery would just show the heartbeat's
 *    own idle dispatch, not whatever blocked it). Logged as `main thread stalled N ms at: …`.
 *  - `Looper.getMainLooper().setMessageLogging` gets a [Printer] that times every dispatched
 *    message the ordinary way Android's own low-level profiling does (`adb shell setprop
 *    log.tag.looper VERBOSE` does the same thing system-wide); a message plus its callback/target
 *    that took more than [SLOW_MESSAGE_THRESHOLD_MS] is logged with that target description, which
 *    is usually enough on its own to name the offending `Runnable`/`Handler` without a stack trace.
 *
 * Started once from [MainActivity.onCreate] (debug only — see `app/src/release`'s no-op) and kept
 * running for the rest of the process, including across the cover<->inner panel swap's Activity
 * recreation, since [MainActivity] itself is what may be stalling.
 */
internal object MainThreadWatchdog {
    private const val TAG = "FoldDuoPerf"
    private const val HEARTBEAT_INTERVAL_MS = 100L
    private const val STALL_THRESHOLD_MS = 250L
    private const val POLL_INTERVAL_MS = 50L
    private const val SLOW_MESSAGE_THRESHOLD_MS = 32L
    private const val MAX_STACK_FRAMES = 25
    /** Výkon 3 "kreslení na inneru" (17. 9. noc), item 5: how many of the recent Choreographer
     * frame gaps a stall log names — enough to see whether a stall was one huge frame or a run of
     * several merely-bad ones, without dumping the whole ring buffer. */
    private const val TOP_SLOW_FRAMES = 8
    /** Ring buffer size for [recentFrameGapsMs] — a couple of seconds' worth even at 120 Hz, so a
     * stall log can always look back past its own detection latency ([STALL_THRESHOLD_MS] +
     * [POLL_INTERVAL_MS]) to frames that led up to it, not just the ones after. */
    private const val FRAME_RING_SIZE = 240

    @Volatile private var started = false
    @Volatile private var heartbeatSeq = 0L
    @Volatile private var respondedSeq = 0L
    @Volatile private var postedAtMs = 0L

    private lateinit var mainThread: Thread
    private lateinit var mainHandler: Handler
    private lateinit var workerHandler: Handler

    /** Always-on Choreographer frame-gap ring buffer: [doFrame] writes it on the main thread,
     * [logStall] reads it from the background poll thread ([pollHeartbeat] runs on
     * [workerHandler]) — [frameLock] is the only thing making that safe, since `ArrayDeque` itself
     * is not. Independent of any episode's [PagerSwipeFrameMetricsCollector]/
     * [MorphFrameMetricsCollector]: those start/stop around a known episode, this one just always
     * remembers "what were the last ~2 s of frames like" so a stall detected off-thread can name
     * them regardless of what triggered it. */
    private val frameLock = Any()
    private val recentFrameGapsMs = ArrayDeque<Double>(FRAME_RING_SIZE)
    private var lastFrameNanos = -1L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos >= 0L) {
                val gapMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                synchronized(frameLock) {
                    recentFrameGapsMs.addLast(gapMs)
                    while (recentFrameGapsMs.size > FRAME_RING_SIZE) recentFrameGapsMs.removeFirst()
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Snapshot of the recent frame gaps, safe to call from any thread. */
    private fun recentFrameGapsSnapshot(): List<Double> = synchronized(frameLock) { recentFrameGapsMs.toList() }

    fun start() {
        if (started) return
        started = true
        mainThread = Looper.getMainLooper().thread
        mainHandler = Handler(Looper.getMainLooper())
        workerHandler = Handler(HandlerThread("MainThreadWatchdog").apply { start() }.looper)
        Looper.getMainLooper().setMessageLogging(SlowMessagePrinter())
        Choreographer.getInstance().postFrameCallback(frameCallback)
        Log.i(TAG, "watchdog: started (heartbeat ${HEARTBEAT_INTERVAL_MS}ms, stall > ${STALL_THRESHOLD_MS}ms, " +
            "slow message > ${SLOW_MESSAGE_THRESHOLD_MS}ms)")
        postHeartbeat()
    }

    private fun postHeartbeat() {
        val seq = ++heartbeatSeq
        postedAtMs = SystemClock.elapsedRealtime()
        mainHandler.post { respondedSeq = seq }
        workerHandler.postDelayed({ pollHeartbeat(seq) }, HEARTBEAT_INTERVAL_MS)
    }

    private fun pollHeartbeat(seq: Long) {
        if (respondedSeq >= seq) { postHeartbeat(); return }
        val lateBy = SystemClock.elapsedRealtime() - postedAtMs
        if (lateBy < STALL_THRESHOLD_MS) {
            workerHandler.postDelayed({ pollHeartbeat(seq) }, POLL_INTERVAL_MS)
            return
        }
        logStall(lateBy)
        // Keep polling (without logging again) until the stuck heartbeat finally runs, so the
        // next one is only queued once the main thread is actually free again.
        awaitRecovery(seq)
    }

    private fun awaitRecovery(seq: Long) {
        if (respondedSeq >= seq) { postHeartbeat(); return }
        workerHandler.postDelayed({ awaitRecovery(seq) }, POLL_INTERVAL_MS)
    }

    /** Top [MAX_STACK_FRAMES] frames of the main thread, trimmed to our own code plus one frame of context. */
    private fun logStall(lateByMs: Long) {
        val frames = runCatching { mainThread.stackTrace }.getOrNull() ?: return
        val relevant = ArrayList<String>(MAX_STACK_FRAMES)
        for (frame in frames.take(MAX_STACK_FRAMES)) {
            val ours = frame.className.startsWith("cz.pflanzer")
            relevant += frame.toString()
            if (!ours) break // the first non-app ("framework") frame is kept for context, then we stop
        }
        val slowest = topSlowestFrames(recentFrameGapsSnapshot(), TOP_SLOW_FRAMES)
        val framesText = if (slowest.isEmpty()) "none recorded yet"
            else slowest.joinToString(", ") { "%.1fms".format(java.util.Locale.ROOT, it) }
        Log.w(TAG, "main thread stalled ${lateByMs}ms at: " + relevant.joinToString(" <- ") +
            " | slowest recent frames: $framesText")
    }

    /**
     * Android's own dispatcher calls [println] twice per message on the looper it is attached to:
     * once with a string starting `">>>>> Dispatching to "` right before running it, once starting
     * `"<<<<< Finished to "` right after — see `Looper.loop()`. Timing between the two, on the
     * SAME thread that runs them (the printer is invoked synchronously from `loop()`), needs no
     * extra synchronization.
     */
    private class SlowMessagePrinter : Printer {
        private var dispatchStartMs = 0L
        private var target = ""

        override fun println(line: String) {
            when {
                line.startsWith(">>>>> Dispatching") -> { dispatchStartMs = SystemClock.elapsedRealtime(); target = line }
                line.startsWith("<<<<< Finished") -> {
                    val durationMs = SystemClock.elapsedRealtime() - dispatchStartMs
                    if (durationMs > SLOW_MESSAGE_THRESHOLD_MS) Log.w(TAG, "slow main thread message (${durationMs}ms): $target")
                }
            }
        }
    }
}

/**
 * Výkon 3 "kreslení na inneru" (17. 9. noc), item 5: the [count] largest values in [durationsMs],
 * descending — [MainThreadWatchdog.logStall]'s own "which recent frames were actually slow"
 * half, extracted as a pure top-level function (no `Choreographer`/threading involved) so it has
 * a plain-JUnit test. Stable for ties (Kotlin's `sortedDescending` is a stable sort), total (never
 * throws): an empty or shorter-than-[count] list just returns everything it has, largest first.
 */
internal fun topSlowestFrames(durationsMs: List<Double>, count: Int): List<Double> =
    if (count <= 0) emptyList() else durationsMs.sortedDescending().take(count)
