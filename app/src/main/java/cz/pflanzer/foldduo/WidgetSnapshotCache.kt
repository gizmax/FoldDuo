package cz.pflanzer.foldduo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import kotlinx.coroutines.delay

/**
 * B43 "Výkon jako feature": the process-wide cache backing HostWidgetView's frozen-bitmap path
 * (LauncherScreen.kt). While a morph drives the left half ([cz.pflanzer.foldduo.MorphController]
 * .leftHalfFrozen), each host app widget on it draws a bitmap snapshot in place of its live
 * `AndroidView` (which does not composite through the frost's `graphicsLayer`/`RenderEffect`).
 * This cache makes that snapshot cheap:
 *  - [capture] draws at most once per freeze per size: repeat calls for the same id/size within
 *    [WidgetSnapshotPolicy.CACHE_MS] (a close→open cycle) return the bitmap already held instead
 *    of drawing the host view again.
 *  - the returned bitmap is copied to [Bitmap.Config.HARDWARE]: GPU-resident, so compositing it
 *    into the frost's layer on every one of the dozens of 120 Hz frames a freeze lasts does not
 *    re-upload a software ARGB_8888 buffer each time (the software copy used only to draw into is
 *    recycled immediately after the hardware copy is made).
 *  - [scheduleEviction] recycles the bitmap [WidgetSnapshotPolicy.CACHE_MS] after a thaw, unless
 *    another freeze refreshed the entry first — "recycled after thaw", but with the grace window
 *    that makes the cache actually useful for a quick close→open. [evictNow] recycles immediately
 *    (widget removed, or its host view rebound to a different provider).
 */
object WidgetSnapshotCache {
    private const val TAG = "FoldDuoMorph"

    private data class Cached(val bitmap: Bitmap, val entry: WidgetSnapshotPolicy.Entry)

    private val lock = Any()
    private val byId = HashMap<Int, Cached>()

    /**
     * A hardware-backed snapshot of [view] at its current laid-out size, reusing the cached one
     * for this [id] when [WidgetSnapshotPolicy.canReuse] says it still applies. Null when there is
     * no usable cached entry and [view] cannot be drawn fresh (no layout yet, or the draw/copy
     * failed).
     */
    fun capture(id: Int, view: View, width: Int = view.width, height: Int = view.height,
        nowMs: Long = SystemClock.elapsedRealtime()): Bitmap? {
        synchronized(lock) {
            val cached = byId[id]
            if (WidgetSnapshotPolicy.canReuse(cached?.entry, width, height, nowMs)) return cached!!.bitmap
            val fresh = drawHardwareBitmap(view, width, height) ?: return null
            if (cached != null && cached.bitmap !== fresh) cached.bitmap.recycle()
            byId[id] = Cached(fresh, WidgetSnapshotPolicy.Entry(width, height, nowMs))
            return fresh
        }
    }

    /**
     * Call once a freeze for [id] ends: the cached bitmap is recycled in [delayMs] unless
     * [capture] refreshes the entry first (a close→open within the window) — [Cached.entry]
     * changing means a different freeze already made this call's eviction stale, so it is a
     * no-op rather than a double-recycle or an eviction of the wrong bitmap.
     */
    suspend fun scheduleEviction(id: Int, delayMs: Long = WidgetSnapshotPolicy.CACHE_MS) {
        val capturedAt = synchronized(lock) { byId[id]?.entry?.capturedAtMs } ?: return
        delay(delayMs)
        synchronized(lock) {
            val current = byId[id] ?: return
            if (current.entry.capturedAtMs == capturedAt) { current.bitmap.recycle(); byId.remove(id) }
        }
    }

    /** Drops and recycles [id]'s cached bitmap right away, skipping the grace window. */
    fun evictNow(id: Int) {
        synchronized(lock) { byId.remove(id) }?.bitmap?.recycle()
    }

    /**
     * Výkon 5 "stránky složené předem": [id]'s cached bitmap, if any, without capturing a fresh
     * one and without [WidgetSnapshotPolicy]'s age/size reuse check — used by HostWidgetView's
     * deferred-bind path (LauncherScreen.kt), which has no [View] yet to draw from on the one
     * frame before its `AndroidView` binds. Any bitmap this id has ever produced beats a blank
     * cell for that single frame, however stale; null only the very first time [id] is ever
     * hosted (nothing has been captured for it yet).
     */
    fun peek(id: Int): Bitmap? = synchronized(lock) { byId[id]?.bitmap }

    private fun drawHardwareBitmap(view: View, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || !view.isLaidOut) return null
        val software = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "host widget snapshot draw failed: $e"); return null
        }
        return try {
            software.copy(Bitmap.Config.HARDWARE, false).also { software.recycle() }
        } catch (e: Exception) {
            Log.w(TAG, "host widget snapshot hardware copy failed, using software bitmap: $e")
            software
        }
    }
}
