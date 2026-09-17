package cz.pflanzer.foldduo

import kotlin.math.sin

/**
 * Home "edit mode" (iOS "jiggle mode", 2026-09-17 evening): entered from the icon popover's
 * "Edit Home Screen", or a long-press on empty wallpaper. Pure state/maths here; the Compose
 * glue (wiggle modifier, "-" remove badge, top bar) lives in HomeEditModeUI.kt, and
 * LauncherScreen.kt owns the `editModeActive` boolean itself (mirrored onto HomeDragState the
 * same way B25 mirrored `openFolderId`, so every icon/folder/widget wrapper can read it without
 * a new parameter threaded through SharedHomeGrid/HomePagePane).
 */
internal enum class EditModeEvent { ENTER, DONE, BACK, TAP_WALLPAPER }

internal object HomeEditMode {
    /** Every exit event behaves the same (leave edit mode); kept as named events, rather than a
     * single "exit" boolean toggle, so a future exit path (e.g. a new gesture) is one more `when`
     * arm instead of a call site guessing which boolean to flip. */
    fun reduce(active: Boolean, event: EditModeEvent): Boolean = when (event) {
        EditModeEvent.ENTER -> true
        EditModeEvent.DONE, EditModeEvent.BACK, EditModeEvent.TAP_WALLPAPER -> false
    }
}

/** iOS jiggle: about 1.5 degrees either way, roughly a 0.9 s period. */
internal const val WIGGLE_AMPLITUDE_DEG = 1.5f
internal const val WIGGLE_PERIOD_MS = 900L

/** A per-item phase offset so a whole grid doesn't wiggle in lockstep — 137 has no common factor
 * with 900, so consecutive indices spread across the period instead of repeating in a short
 * visible cycle. */
internal fun wigglePhaseOffsetMs(index: Int): Long =
    ((index.toLong() * 137L) % WIGGLE_PERIOD_MS + WIGGLE_PERIOD_MS) % WIGGLE_PERIOD_MS

/** Rotation (degrees) for item [index] at [timeMs] since edit mode was entered. Reduced motion
 * (or edit mode being off) is the caller's job — this is just the wave. */
internal fun wiggleRotationDeg(timeMs: Long, index: Int): Float {
    val periodPos = ((timeMs + wigglePhaseOffsetMs(index)) % WIGGLE_PERIOD_MS + WIGGLE_PERIOD_MS) % WIGGLE_PERIOD_MS
    val phase = periodPos.toDouble() / WIGGLE_PERIOD_MS.toDouble()
    return (WIGGLE_AMPLITUDE_DEG * sin(phase * 2.0 * Math.PI)).toFloat()
}

/**
 * B39's "+" page dot ([HomePages]/`pagingTempPage` in LauncherScreen.kt) already appears while a
 * drag, a pending widget placement or the Home layout editor's own page is in progress; edit mode
 * joins that same list of reasons instead of getting its own separate visibility flag.
 */
internal fun homePagingTempPageActive(
    dragActive: Boolean, widgetDragging: Boolean, pendingNewPage: Boolean,
    homePagesEditing: Boolean, editMode: Boolean,
): Boolean = dragActive || widgetDragging || pendingNewPage || homePagesEditing || editMode
