package cz.pflanzer.foldduo

import androidx.compose.foundation.pager.PagerState

/**
 * Logical pages are zero based: `0 until homePages` are Home, `homePages` is All apps.
 *
 * B39 "Přehled stránek": a hidden Home page (the overview's checkmark, or a page a mode leaves
 * out — see [effectiveHiddenPages]) still exists logically — its widgets, its App Library entry,
 * every other page number referenced elsewhere in `LauncherScreen.kt` — but the physical
 * `HorizontalPager` never gives it a stop to swipe onto. [visibleHomePages] is the ascending list
 * of logical Home page numbers the pager currently shows; its size (plus one for the trailing "All
 * apps" page, and any temporary page LauncherScreen.kt is mid-drag over) is the `pageCount` passed
 * to `rememberPagerState`. Everything else in this file keeps translating so the rest of
 * LauncherScreen.kt can go on treating `currentPage`/`settledPage` as logical page numbers exactly
 * as before this existed.
 *
 * "Stránkování inneru jako spread" (17. 9. noc, identity audit): a settled physical pager slot `k`
 * is exactly SpreadPager.kt's strip slot `k` — its RIGHT pane shows [visibleHomePages][k], the same
 * page [SpreadPager.spreadRightPage] computes — so "logical page" here and "the current page"
 * everywhere in LauncherScreen.kt (the indicator, `lastHomePage`, move-to-page, Spotlight, the seam
 * palette, PageOverview.onJump) is one single definition: the RIGHT pane's page. [logicalOf]/
 * [physicalOf] are pure translation ([logicalPageOf]/[physicalPageOf] below, unit tested without a
 * `PagerState`) so `currentPage`/`settledPage` and every `animateScrollToPage`/`scrollToPage` call
 * agree on it automatically instead of each call site re-deriving "the current page" its own way.
 */
internal class LauncherPager(
    val state: PagerState,
    private val visibleHomePages: () -> List<Int>,
    private val homePages: () -> Int,
) {
    /** The logical page number the physical pager slot [physical] currently shows. */
    fun logicalOf(physical: Int): Int = logicalPageOf(physical, visibleHomePages(), homePages())
    /** The physical pager slot logical page [logical] sits at; a hidden Home page snaps to its nearest visible neighbor. */
    fun physicalOf(logical: Int): Int = physicalPageOf(logical, visibleHomePages(), homePages())
    val currentPage get() = logicalOf(state.currentPage)
    val settledPage get() = logicalOf(state.settledPage)
    /** The logical page the pager's very first physical stop shows — "the first spread" (iOS Home
     * semantics: a Home press goes here), whatever Home page number that happens to be once hidden
     * pages or a mode leave page 0 out of the strip. */
    val firstPage get() = logicalOf(0)
    /** True while the pager sits on its first physical stop — [firstPage]'s own spread. */
    val isOnFirstPage get() = state.currentPage == 0
    fun requestScrollToPage(page: Int) { trace("request", page); state.requestScrollToPage(physicalOf(page)) }
    suspend fun scrollToPage(page: Int) { trace("scroll", page); state.scrollToPage(physicalOf(page)) }
    suspend fun animateScrollToPage(page: Int) { trace("animate", page); state.animateScrollToPage(physicalOf(page)) }

    /** Diagnostic (17. 9.): every programmatic page jump with its caller, and both the logical
     * page requested and the physical slot it resolves to, for "why did the page change" reports —
     * a mismatch between the two used to be exactly how a spread's page-identity bug showed up. */
    private fun trace(kind: String, page: Int) {
        val caller = Throwable().stackTrace.drop(2).firstOrNull { it.className.startsWith("cz.pflanzer") && !it.className.contains("LauncherPager") }
        android.util.Log.i("FoldDuoPager", "$kind -> logical $page (physical ${physicalOf(page)}) (from ${caller?.className?.substringAfterLast('.')}.${caller?.methodName}:${caller?.lineNumber})")
    }
}

/** Pure translation physical pager slot -> logical (real) Home page number; see [LauncherPager]. */
internal fun logicalPageOf(physical: Int, visible: List<Int>, homePages: Int): Int =
    if (physical < visible.size) visible[physical] else homePages + (physical - visible.size)

/** Pure translation logical (real) Home page number -> physical pager slot; see [LauncherPager]. */
internal fun physicalPageOf(logical: Int, visible: List<Int>, homePages: Int): Int {
    val exact = visible.indexOf(logical)
    if (exact >= 0) return exact
    if (logical >= homePages) return visible.size + (logical - homePages)
    val higher = visible.firstOrNull { it > logical }
    return if (higher != null) visible.indexOf(higher) else (visible.size - 1).coerceAtLeast(0)
}
