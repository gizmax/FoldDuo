package cz.pflanzer.foldduo

import kotlin.math.ceil

/**
 * Pure motion rules for the glass-card folder open/close animation (IDEAS.md B25, "Složka jako
 * skleněná karta"). No Android or Compose types here, so the transform math, the fade windows
 * and the paging are plain JVM units; FolderPanel.kt is the framework-facing half that turns a
 * [folderOpenFrame] into a `graphicsLayer` scale/translation on the card's own `Surface` (already
 * laid out at its final pane position by Compose — nothing here touches layout, only the draw)
 * and alpha on the icon-preview / grid layers.
 *
 * Units are whatever the caller measured [FoldRect]s in: `LayoutCoordinates.boundsInRoot()`
 * reports pixels, and every quantity below is a ratio or a plain difference between two rects
 * measured the same way, so pixels in gives pixels out with no density conversion needed.
 */

/** An axis-aligned rectangle: a tapped folder icon's bounds, or a card's laid-out bounds. */
data class FoldRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/** The card's visual state at one [progress] value of the open/close morph (0 = icon, 1 = open). */
data class FolderOpenFrame(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float,
    /** Alpha of the folder's own closed-icon preview, drawn under the card's real content. */
    val iconPreviewAlpha: Float,
    /** Alpha of the card's title and app grid. */
    val contentAlpha: Float,
    /** Alpha of the frosted-glass backdrop dropped behind the card over the rest of the page. */
    val frostAlpha: Float,
    /** Alpha of the flat dim laid over the rest of the page, capped at [FOLDER_MAX_DIM]. */
    val dimAlpha: Float,
)

/** The icon preview crossfades out over the first 35 % of the motion. */
const val FOLDER_ICON_FADE_END = 0.35f

/** The card's title and grid fade in over the last 40 % of the motion (from here to progress 1). */
const val FOLDER_CONTENT_FADE_START = 0.6f

/** The home page behind the card dims by at most 15 %. */
const val FOLDER_MAX_DIM = 0.15f

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

/**
 * The frame at [progress] (clamped to 0..1) for a card growing from [icon] to its own laid-out
 * [card] rect. `scaleX`/`scaleY` shrink the card — already measured at its final size — down to
 * the icon's footprint at progress 0; `translateX`/`translateY` are added *after* scaling (screen
 * pixels, matching `graphicsLayer`'s transform order: scale around the layer's centre, then
 * translate) so the shrunk card's centre lands exactly on the icon's centre. At progress 1 both
 * scales are 1 and both translations are 0: the card sits exactly where Compose laid it out, no
 * different from a folder panel that never had an icon to grow from.
 *
 * A [card] with no measured size yet (width or height 0, e.g. the very first frame before its
 * own layout pass) falls back to no scale/translate at all rather than dividing by zero.
 */
fun folderOpenFrame(progress: Float, icon: FoldRect, card: FoldRect): FolderOpenFrame {
    val p = progress.coerceIn(0f, 1f)
    val scaleX = if (card.width > 0f) lerp(icon.width / card.width, 1f, p) else 1f
    val scaleY = if (card.height > 0f) lerp(icon.height / card.height, 1f, p) else 1f
    val translateX = if (card.width > 0f) lerp(icon.centerX - card.centerX, 0f, p) else 0f
    val translateY = if (card.height > 0f) lerp(icon.centerY - card.centerY, 0f, p) else 0f
    return FolderOpenFrame(scaleX, scaleY, translateX, translateY,
        iconPreviewAlpha = iconPreviewAlpha(p), contentAlpha = contentAlpha(p),
        frostAlpha = p, dimAlpha = p * FOLDER_MAX_DIM)
}

/**
 * The frame when no icon rect is known at all (e.g. the tapped tile was never measured): a plain
 * scale-and-fade from 92 % centred on the card's own position, with the same content fade-in
 * window as the real morph so the two paths read the same once the grid is visible.
 */
fun folderOpenFrameFallback(progress: Float): FolderOpenFrame {
    val p = progress.coerceIn(0f, 1f)
    val scale = lerp(.92f, 1f, p)
    return FolderOpenFrame(scale, scale, 0f, 0f, iconPreviewAlpha = 0f, contentAlpha = contentAlpha(p),
        frostAlpha = p, dimAlpha = p * FOLDER_MAX_DIM)
}

private fun iconPreviewAlpha(progress: Float): Float = (1f - progress / FOLDER_ICON_FADE_END).coerceIn(0f, 1f)

private fun contentAlpha(progress: Float): Float =
    ((progress - FOLDER_CONTENT_FADE_START) / (1f - FOLDER_CONTENT_FADE_START)).coerceIn(0f, 1f)

/** Apps per page of the 3-column grid before the card switches to a paged, dot-indicated layout. */
const val FOLDER_APPS_PER_PAGE = 9

/** Number of pages a folder with [appCount] apps needs at [perPage] apps per page (never 0). */
fun folderPageCount(appCount: Int, perPage: Int = FOLDER_APPS_PER_PAGE): Int =
    if (appCount <= 0) 1 else ceil(appCount.toFloat() / perPage).toInt()

/**
 * The horizontal extent (same units as [icon], typically dp) the open card is confined to on the
 * inner display: the pane holding the tapped icon, never crossing [seam] — or the whole
 * [windowWidth] minus [railWidth] on the cover, where there is no seam. Reuses `paneFor`/
 * `paneBounds` (FoldGeometry.kt) applied to the icon's own horizontal span instead of a drop
 * region, so a folder can be placed correctly from just the tapped rect and the seam, without a
 * Home-cell index.
 */
fun folderCardPaneBounds(icon: FoldRect, seam: FoldSeam?, windowWidth: Float, railWidth: Float = 0f): ClosedFloatingPointRange<Float> {
    if (seam == null) return 0f..(windowWidth - railWidth).coerceAtLeast(0f)
    val pane = paneFor(icon.left..icon.right, seam)
    return paneBounds(pane, seam, windowWidth, railWidth)
}
