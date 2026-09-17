package cz.pflanzer.foldduo.desk

import cz.pflanzer.foldduo.FOLD_GUTTER_DP
import cz.pflanzer.foldduo.FoldSeam
import cz.pflanzer.foldduo.splits

/**
 * B47 "Stůl": pure geometry for the desk-mode split. Stand (pose/PoseClassifier.kt) is the open
 * phone propped up like a laptop, which turns the inner display landscape — the same physical
 * hinge Jetpack WindowManager reports as `FoldingFeature.Orientation.VERTICAL` in portrait
 * (splitting width, [FoldSeam]/`LocalFoldSeam`) becomes `HORIZONTAL` (splitting height) once the
 * window itself rotates to landscape. MainActivity's own `foldSeamPx` only ever tracks the
 * VERTICAL case (Continuum's pane-identity split), so it goes `null` the moment the window
 * rotates for Stand — that null is this file's *signal* that the seam is the usual horizontal
 * one, not a reason to give up. [deskLayout] therefore assumes a horizontal seam at the window's
 * vertical midpoint (the Fold 8's hinge is equidistant top/bottom) whenever no vertical
 * [FoldSeam] is reported, and only falls back to an actual left/right split when one is (the
 * phone stayed portrait — a genuinely rotated Stand, e.g. propped up sideways).
 */

/** One half of the desk split, in window dp. */
data class DeskPane(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val widthDp: Float get() = (right - left).coerceAtLeast(0f)
    val heightDp: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * [face] is the read-only StandBy-like pane, [deck] the interactive control grid. `stacked` is
 * true for the normal top/bottom split (face above deck, hinge horizontal); false for the
 * left/right fallback (face left, deck right — the pane order Left/Right already uses
 * everywhere else in FoldGeometry.kt).
 */
data class DeskLayout(val face: DeskPane, val deck: DeskPane, val stacked: Boolean)

/**
 * [verticalSeamXDp] is `LocalFoldSeam`'s own `xDp`, or null when no VERTICAL FoldingFeature is
 * currently reported (the expected case in Stand, once the window has rotated landscape).
 * [width]/[height] are the window's content area in dp; [gutterDp] is the fold-avoidance gutter
 * on both sides of whichever line is used ([cz.pflanzer.foldduo.FOLD_GUTTER_DP] by default, same
 * as every other pane split in the launcher).
 */
fun deskLayout(width: Float, height: Float, verticalSeamXDp: Float?, gutterDp: Float = FOLD_GUTTER_DP): DeskLayout {
    val verticalSeam = verticalSeamXDp?.let { FoldSeam(it, gutterDp) }?.takeIf { it.splits(width) }
    if (verticalSeam != null) {
        return DeskLayout(
            face = DeskPane(0f, 0f, verticalSeam.leadingEndDp.coerceAtLeast(0f), height),
            deck = DeskPane(verticalSeam.homeStartDp.coerceAtMost(width), 0f, width, height),
            stacked = false,
        )
    }
    val midY = height / 2f
    val faceBottom = (midY - gutterDp).coerceAtLeast(0f)
    val deckTop = (midY + gutterDp).coerceAtMost(height)
    return DeskLayout(
        face = DeskPane(0f, 0f, width, faceBottom),
        deck = DeskPane(0f, deckTop, width, height),
        stacked = true,
    )
}
