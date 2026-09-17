package cz.pflanzer.foldduo

/**
 * B43 "Výkon jako feature": pure decision logic for [WidgetSnapshotCache], HostWidgetView's
 * (LauncherScreen.kt) freeze-time bitmap cache. Free of `android.graphics.Bitmap` and of any
 * clock call — every timestamp is a plain `Long` the caller supplies — so this is plain-JUnit
 * testable; [WidgetSnapshotCache] is the Android-facing wrapper that owns one `Bitmap` per widget
 * id and calls into this for the "do I need to redraw" decision.
 *
 * The freeze this backs lasts up to ~1.5 s (MorphController.leftHalfFrozen) and is drawn dozens
 * of times at 120 Hz; the same widget can also be frozen, thawed and re-frozen in quick
 * succession (fold, unfold, fold again within a couple of seconds) without its content or laid-
 * out size having changed at all. [canReuse] is what lets [WidgetSnapshotCache.capture] skip a
 * fresh `view.draw(Canvas(...))` for that second case.
 */
object WidgetSnapshotPolicy {
    /** How long a capture stays reusable after it was taken, per the task's "cached for 5 s". */
    const val CACHE_MS = 5_000L

    /** One cached capture's identity: the size it was taken at, and when. */
    data class Entry(val width: Int, val height: Int, val capturedAtMs: Long)

    /**
     * True when [entry] (the last capture for this widget id, if any) can stand in for a new
     * request of ([width]x[height]) at [nowMs]: same entry, this size (a resize invalidates it —
     * a cached bitmap at the wrong size is worthless and would stretch), captured within
     * [cacheMs] of now (a `>=` boundary counts as expired, matching "for 5 s": the entry is valid
     * for that whole span but not a moment after).
     */
    fun canReuse(entry: Entry?, width: Int, height: Int, nowMs: Long, cacheMs: Long = CACHE_MS): Boolean {
        if (entry == null) return false
        if (entry.width != width || entry.height != height) return false
        if (width <= 0 || height <= 0) return false
        val age = nowMs - entry.capturedAtMs
        return age in 0 until cacheMs
    }
}
