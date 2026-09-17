package cz.pflanzer.foldduo

import kotlin.math.abs

/**
 * Maps the native full-width pager position to the scroll distance shown by the
 * expanded workspace. Home pages advance by one pane while the All apps page
 * still enters and leaves across the full pager width.
 */
internal data class WorkspacePageMotion(
    val homePages: Int,
    val pageWidth: Float,
    val homeStride: Float,
) {
    init {
        require(homePages > 0)
        require(pageWidth.isFinite() && pageWidth > 0f)
        require(homeStride.isFinite() && homeStride > 0f)
    }

    private val lastHome = homePages - 1
    private val lastHomeOffset = lastHome * homeStride

    /** Visual scroll offset for a physical (and possibly fractional) pager position. */
    fun offset(position: Float): Float = when {
        position < 0f -> position * pageWidth
        position <= lastHome -> position * homeStride
        else -> lastHomeOffset + (position - lastHome) * pageWidth
    }

    /** Physical pager position for a visual scroll offset. */
    fun position(offset: Float): Float = when {
        offset < 0f -> offset / pageWidth
        offset <= lastHomeOffset && lastHome > 0 -> offset / homeStride
        else -> lastHome + (offset - lastHomeOffset) / pageWidth
    }

    fun positionAfterVisualDelta(position: Float, delta: Float): Float =
        position(offset(position) + delta)

    fun stride(fromPosition: Int, towardPosition: Int): Float =
        abs(offset(towardPosition.toFloat()) - offset(fromPosition.toFloat()))
}

/**
 * Horizontal placement of the unfolded pair in window dp. The "Today" strip item occupies
 * `[leadingOrigin, leadingOrigin + leadingWidth]`. Home pages are placed at
 * `homeOrigin + page * homeStride`; [homeStride] is that pane's own full box width (with a real
 * hinge, `homeOrigin - leadingOrigin` too — the uniform strip pitch), so a one-stride scroll
 * replaces Home N with Home N+1 in place AND advances Today by the exact same amount, which is
 * what lets "Spread jako jedna plocha" (17. 9. noc) translate the whole Today+Home strip with one
 * `graphicsLayer` instead of keeping two independently paced panes in step by hand.
 */
internal data class ExpandedPaneLayout(
    val leadingOrigin: Float,
    val leadingWidth: Float,
    val homeOrigin: Float,
    val homeStride: Float,
)

/**
 * With a [seam] the Today pane spans the window edge up to the gutter (`[0, seam.leadingEndDp]`)
 * and Home starts right after the gutter (`homeOrigin == seam.homeStartDp`), so both panes share
 * pane-local x = 0 (pane identity, PLAN.md fact 1). Without a seam Today keeps the historical
 * leading-grid bounds: one grid width centered in the left half.
 */
/**
 * Výkon 5 "stránky složené předem" (17. 9. noc): every Home page ExpandedWorkspace must keep
 * composed for the whole life of the strip — literally all of them (B39 caps the page count at a
 * handful, so this is cheap; see LauncherScreen.kt's own doc on the `derivedStateOf` windowing
 * this replaces, which used to tear a page's `HomePagePane`/`HostWidgetView` down and rebuild it
 * mid-swipe). A real function, not an inline range, so "how many pages compose" has one pure,
 * regression-tested answer at the edges (0 pages, 1 page, the usual cap) independent of Compose.
 */
internal fun composedHomePages(visibleHomePages: Int): List<Int> =
    if (visibleHomePages <= 0) emptyList() else (0 until visibleHomePages).toList()

/**
 * Výkon 5: the DRAW-only replacement for the old composition windowing above — a page (or Today)
 * whose fixed strip box `[pageStartPx, pageStartPx + paneWidthPx)` does not intersect the visible
 * window `[scrollPx - marginPx, scrollPx + viewportPx + marginPx)` skips drawing entirely (an
 * `alpha = 0f graphicsLayer`, read inside that layer's own block — see call sites) while staying
 * composed and measured. [marginPx] widens the window by a margin on both sides (the call sites
 * pass one pane width, the same "current pane plus a neighbour" retention the old windowing kept)
 * so a page one swipe away is never mid-fade-in when it must already be visible.
 */
internal fun homePageDrawVisible(pageStartPx: Float, paneWidthPx: Float, scrollPx: Float, viewportPx: Float, marginPx: Float): Boolean =
    pageStartPx + paneWidthPx > scrollPx - marginPx && pageStartPx < scrollPx + viewportPx + marginPx

internal fun expandedPaneLayout(width: Float, homeWidth: Float, gridWidth: Float, seam: FoldSeam?): ExpandedPaneLayout {
    val homeOrigin = width - homeWidth
    val split = seam != null && seam.splits(width)
    // "Spread jako jedna plocha" (17. 9. noc): with a real hinge, the right pane's own pitch is
    // the FULL pane box [homeWidth] the seam already hands it (paneBounds/FoldGeometry's own
    // right-pane extent) — not `gridWidth + 16f`, which only measures the icon grid AFTER
    // HomeGeometry.railInsetDp/HOME_START_DP have already been carved out of it. Deriving the
    // pitch from the narrower grid made the right pane advance by less than the left (Today) pane
    // every page turn — a visibly different pitch on either side of the seam mid-swipe (device
    // report, 17. 9.: "je tam vidět předěl mezi displeji, čára, kde se grafika řeže"). `homeWidth`
    // and the seam-derived `leadingWidth` below are the SAME value whenever the hinge is centred
    // (the Fold 8 is), so Today and every Home page now share one uniform pane pitch; without a
    // seam there is no hinge to align two full-height panes against, so the historical
    // `gridWidth + 16f` pitch (centred in the left half) is untouched.
    val paneWidth = if (split) homeWidth else gridWidth + 16f
    val leadingOrigin = if (split) 0f else (width / 2f - gridWidth) / 2f - 16f
    val leadingWidth = if (split) seam!!.leadingEndDp else paneWidth
    return ExpandedPaneLayout(leadingOrigin, leadingWidth, homeOrigin, paneWidth)
}
