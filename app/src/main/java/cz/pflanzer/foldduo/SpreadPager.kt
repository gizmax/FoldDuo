package cz.pflanzer.foldduo

/**
 * "Stránkování inneru jako spread" (17. 9. večer, Tomova zpětná vazba na B24 item 4's inner
 * rubber-band + the pre-existing "only the right pane pages" design): the inner workspace's
 * continuous two-pane strip `[Today, P0, P1, …, P(n-1)]`. The viewport shows two adjacent strip
 * pages at once; swiping right-to-left advances the viewport by exactly one, replacing
 * `[Today|P0]` with `[P0|P1]`, then `[P1|P2]`, and so on — left-to-right goes back. `n` is
 * LauncherScreen.kt's own `visibleHomePages` (B39 hidden pages and the temporary edit-mode "+"
 * page are already excluded/included there exactly like they are or are not a physical pager
 * stop, so the strip inherits that for free — nothing here needs to know about hiding). The
 * continuous drag position `p` is the same `nativePager.currentPage + currentPageOffsetFraction`
 * `ExpandedWorkspace` already reads for the right pane; every function below is pure so the strip
 * math can be unit tested without a `PagerState` or any other Compose runtime type.
 *
 * A settled (integer) strip slot `k` (0 until n, the same domain the native pager already uses
 * for Home paging) shows [spreadLeftPage] on the left and [spreadRightPage] on the right; "the
 * current page" everywhere else in LauncherScreen.kt (the indicator, move-to-page, widget-add,
 * Spotlight, suggestions/hub on Today, PageOverview) stays exactly what it already is, the RIGHT
 * page — spread only adds a first-class left neighbor beside it, never redefines it.
 *
 * "Spread jako jedna plocha" (17. 9. noc): ExpandedWorkspace no longer hands a page between a
 * left-pane wrapper and a right-pane wrapper as it crosses the seam (the old `spreadLeftOwner` /
 * `spreadRightCandidates` / `spreadItemOffset` this file used to carry — a device report caught
 * the hand-off's two independently-paced panes as a visible cut line, "je tam vidět předěl mezi
 * displeji"). Today and every Home page now live at ONE fixed strip position each — Today at the
 * left pane's own origin, Home page k at `homeOrigin + k * homeStride` (WorkspacePageMotion.kt's
 * `ExpandedPaneLayout`, now a uniform pitch — see its own doc) — inside a single container
 * translated by one `graphicsLayer { translationX = -scroll() }`. A page is composed exactly
 * once, in exactly one parent, for its entire lifetime on screen; there is nothing left here to
 * hand off, and a home page's own `AppWidgetHostView` is never at risk of two simultaneous owners
 * because it never has more than one anyway.
 */
internal const val TODAY_STRIP_PAGE = -1

/** The page shown on the LEFT at settled strip slot [slot] — [TODAY_STRIP_PAGE] at slot 0. */
internal fun spreadLeftPage(visiblePages: List<Int>, slot: Int): Int =
    if (slot <= 0) TODAY_STRIP_PAGE else visiblePages.getOrElse(slot - 1) { TODAY_STRIP_PAGE }

/** The page shown on the RIGHT at settled strip slot [slot] — the same page the cover shows
 * (pane identity), and what the rest of LauncherScreen.kt already calls "the current page". */
internal fun spreadRightPage(visiblePages: List<Int>, slot: Int): Int {
    if (visiblePages.isEmpty()) return TODAY_STRIP_PAGE
    return visiblePages[slot.coerceIn(0, visiblePages.size - 1)]
}

/** Both drop-target pages at the settled strip slot [slot] at once (the left slot's page is a
 * first-class drop target too, exactly like the right/"current" one). Empty only when
 * [visiblePages] itself is empty (no Home page exists yet). */
internal fun spreadEligiblePages(visiblePages: List<Int>, slot: Int): Set<Int> {
    if (visiblePages.isEmpty()) return emptySet()
    val bounded = slot.coerceIn(0, visiblePages.size - 1)
    return setOf(spreadLeftPage(visiblePages, bounded), spreadRightPage(visiblePages, bounded))
}

/**
 * The strip slot (0 until [visiblePages].size) whose RIGHT page is [logicalPage] — where an
 * unfold (a cover -> inner panel swap) should place the viewport so pane identity holds: the
 * cover's own page lands on the right, its predecessor (or Today, for page 0) on the left. Falls
 * back to slot 0 for a page the strip does not currently show (e.g. hidden by a mode switched on
 * mid-fold) instead of throwing.
 */
internal fun spreadSlotForPage(visiblePages: List<Int>, logicalPage: Int): Int =
    visiblePages.indexOf(logicalPage).let { if (it >= 0) it else 0 }
