package cz.pflanzer.foldduo

import android.content.Context
import android.os.Handler
import android.os.Looper
import cz.pflanzer.foldduo.pose.PoseRepository

/**
 * App-scoped [PoseRepository], ref-counted by the activities that show it.
 *
 * Android relaunches [MainActivity] on the panel swap (no `configChanges` in the manifest),
 * so a per-activity repository would be born after the swap and never see it. One process-wide
 * instance survives the relaunch; the stop after the last release is deferred by
 * [STOP_GRACE_MS] so the old instance's `onStop` -> new instance's `onStart` never restarts the
 * engine, and the new instance reads `snapshot.msSinceTransition` of the swap that recreated it.
 */
internal object PoseEngine {
    private const val STOP_GRACE_MS = 2_000L
    private val handler = Handler(Looper.getMainLooper())
    private var repository: PoseRepository? = null
    private var refs = 0
    private val stopLater = Runnable { if (refs == 0) repository?.stop() }

    /** The shared repository, created on first use; not started. */
    fun get(context: Context): PoseRepository =
        repository ?: PoseRepository(context.applicationContext).also { repository = it }

    /** Start (or keep running) the engine for one foreground component. */
    fun acquire(context: Context): PoseRepository {
        refs++
        handler.removeCallbacks(stopLater)
        return get(context).also { it.start() }
    }

    /** Release one foreground component; the engine stops [STOP_GRACE_MS] after the last one. */
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) handler.postDelayed(stopLater, STOP_GRACE_MS)
    }
}
