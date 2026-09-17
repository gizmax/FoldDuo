package cz.pflanzer.foldduo

import android.view.Gravity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/** Side margin an avoiding surface keeps from both edges of its pane, dp. */
const val PANE_SURFACE_MARGIN_DP = 16f

/**
 * Fold avoidance (IDEAS B7, Apple's "Designing for iPhone Duo"): only interactive surfaces
 * avoid the hinge. The folder panel, action sheets and dialogs open inside the pane of the item
 * they belong to and never straddle the seam; the grid, App Library and the left canvas are
 * scrollable content and keep crossing it, as does every scrim.
 *
 * Built once per layout pass from the seam already translated into the launcher content frame
 * (the box inside the safe-drawing insets). [contentLeftDp] and [windowWidthDp] map that frame
 * back to window coordinates for surfaces that live in their own window (sheets, dialogs);
 * [contentOriginRootX] and [density] map drop-region bounds (root px) into the frame.
 */
@Immutable
internal data class FoldAvoidance(
    val seam: FoldSeam,
    val contentWidthDp: Float,
    val railWidthDp: Float,
    val contentLeftDp: Float,
    val windowWidthDp: Float,
    val contentOriginRootX: Float,
    val density: Float,
) {
    /** Extent (content-frame dp) of [pane]. */
    fun bounds(pane: Pane): ClosedFloatingPointRange<Float> = paneBounds(pane, seam, contentWidthDp, railWidthDp)

    /** Pane of an item from its drop-region [rootBounds] (root px), else from its [page]; see [paneForAnchor]. */
    fun paneOf(rootBounds: Rect?, page: Int? = null): ClosedFloatingPointRange<Float> {
        val anchor = rootBounds?.takeIf { it.width > 0f || it.height > 0f }?.let {
            val left = (it.left - contentOriginRootX) / density
            val right = (it.right - contentOriginRootX) / density
            minOf(left, right)..maxOf(left, right)
        }
        return bounds(paneForAnchor(anchor, page, seam))
    }

    /** Pane under a pointer at [rootX] (root px). */
    fun paneAt(rootX: Float): ClosedFloatingPointRange<Float> =
        bounds(paneFor((rootX - contentOriginRootX) / density, seam))

    /**
     * Side-anchors a Material 3 `ModalBottomSheet` to [pane]. The sheet's own chain is
     * `modifier.align(TopCenter).widthIn(max).fillMaxWidth()`, so outer padding is the whole
     * trick: the sheet fills the pane, its drag handle and shape follow, and the scrim (a
     * sibling of the sheet inside the dialog window) still covers the whole window.
     */
    fun sheetModifier(pane: ClosedFloatingPointRange<Float>): Modifier = Modifier.padding(
        start = (contentLeftDp + pane.start).coerceAtLeast(0f).dp,
        end = (windowWidthDp - contentLeftDp - pane.endInclusive).coerceAtLeast(0f).dp,
    )

    /**
     * Centres an `AlertDialog` in [pane]. Dialogs sit in a wrap-content window centred on the
     * display, so the window itself is shifted (`WindowManager.LayoutParams.x` is an offset
     * from the centre gravity) and the dialog is capped so it fits the pane with a margin.
     */
    fun dialogModifier(pane: ClosedFloatingPointRange<Float>): Modifier {
        val shiftDp = contentLeftDp + (pane.start + pane.endInclusive) / 2f - windowWidthDp / 2f
        return Modifier.dialogWindowShift(shiftDp)
            .widthIn(max = (pane.extentDp - 2 * PANE_SURFACE_MARGIN_DP).coerceAtLeast(PANE_SURFACE_MARGIN_DP).dp)
    }
}

/** Shifts the dialog window that hosts this composition [shiftDp] to the right of the display centre. */
private fun Modifier.dialogWindowShift(shiftDp: Float): Modifier = composed {
    val view = LocalView.current
    val shiftPx = with(LocalDensity.current) { shiftDp.dp.roundToPx() }
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val attributes = window.attributes
        if (attributes.x != shiftPx || attributes.gravity != Gravity.CENTER) {
            attributes.gravity = Gravity.CENTER
            attributes.x = shiftPx
            window.attributes = attributes
        }
    }
    Modifier
}
