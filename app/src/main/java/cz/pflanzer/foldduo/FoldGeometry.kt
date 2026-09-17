package cz.pflanzer.foldduo

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import cz.pflanzer.foldduo.pose.FoldPose

/**
 * Window width (dp) from which the launcher shows the unfolded pair layout.
 * Fold 8 cover is 555 dp wide, the inner panel 1088 dp (PLAN.md, fact 1).
 */
const val FOLD_THRESHOLD_DP = 650f

/** Reserved folding region on each side of the hinge line, even when the crease is ~0 px. */
const val FOLD_GUTTER_DP = 10f

/**
 * Vertical hinge line reported by `FoldingFeature`, in dp from the left edge of the
 * window. The leading pane occupies `[0, xDp - gutterDp]`, Home pages start at
 * `xDp + gutterDp`. `null` means no folding feature: the cover, or a window that
 * does not span the hinge.
 */
data class FoldSeam(val xDp: Float, val gutterDp: Float = FOLD_GUTTER_DP) {
    val leadingEndDp: Float get() = xDp - gutterDp
    val homeStartDp: Float get() = xDp + gutterDp
}

/** True when the seam splits [width] into two usable panes with the gutter fitting on both sides. */
fun FoldSeam.splits(width: Float): Boolean =
    xDp.isFinite() && gutterDp >= 0f && leadingEndDp > 0f && homeStartDp < width

val LocalFoldSeam = compositionLocalOf<FoldSeam?> { null }

/** Latest Pose Engine output. Nothing consumes it visually yet (Phase 2, step 1). */
val LocalFoldPose = staticCompositionLocalOf { FoldPose.Closed }

/** The two usable panes of the open inner display, either side of the hinge line. */
enum class Pane { Left, Right }

/**
 * Pane holding a point at [anchorX] dp. The hinge line itself counts as the Right pane, the
 * rail side where the thumb already is, so anything without a clearer anchor lands there.
 */
fun paneFor(anchorX: Float, seam: FoldSeam): Pane = if (anchorX < seam.xDp) Pane.Left else Pane.Right

/**
 * Pane holding more of the horizontal extent [anchor] (dp). An anchor that straddles the seam
 * goes where the larger part lies; an even split, an extent inside the gutter, or an inverted
 * range all default to Right.
 */
fun paneFor(anchor: ClosedFloatingPointRange<Float>, seam: FoldSeam): Pane {
    val inLeft = (minOf(anchor.endInclusive, seam.leadingEndDp) - anchor.start).coerceAtLeast(0f)
    val inRight = (anchor.endInclusive - maxOf(anchor.start, seam.homeStartDp)).coerceAtLeast(0f)
    return if (inLeft > inRight) Pane.Left else Pane.Right
}

/**
 * Pane for an interactive surface anchored to a launcher item: its on-screen [anchor] extent
 * when known, otherwise the [page] it lives on (the leading canvas, page -1, is the Left pane;
 * Home pages, the dock and the library are on the Right). Nothing known defaults to Right.
 */
fun paneForAnchor(anchor: ClosedFloatingPointRange<Float>?, page: Int?, seam: FoldSeam): Pane = when {
    anchor != null -> paneFor(anchor, seam)
    page == -1 -> Pane.Left
    else -> Pane.Right
}

/**
 * Horizontal extent of [pane] in window dp: the Left pane runs from the window edge to the
 * gutter, the Right pane from the gutter to the rail column ([railWidth] dp at the window's
 * end). Interactive surfaces (folder panel, sheets, dialogs) stay inside this range so they
 * never straddle the fold; scrollable content is free to cross it.
 */
fun paneBounds(pane: Pane, seam: FoldSeam, windowWidth: Float, railWidth: Float): ClosedFloatingPointRange<Float> = when (pane) {
    Pane.Left -> 0f..seam.leadingEndDp.coerceAtLeast(0f)
    Pane.Right -> seam.homeStartDp..maxOf(seam.homeStartDp, windowWidth - railWidth)
}

/** Width of a pane extent from [paneBounds], dp. */
val ClosedFloatingPointRange<Float>.extentDp: Float get() = endInclusive - start
