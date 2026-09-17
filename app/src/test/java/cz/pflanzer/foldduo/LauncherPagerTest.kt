package cz.pflanzer.foldduo

import androidx.compose.foundation.pager.PagerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Stránkování inneru jako spread" (17. 9. noc, identity audit): [LauncherPager]'s logical page is
 * defined as one single thing everywhere — the RIGHT pane's page, exactly [spreadRightPage]'s own
 * page at the same settled slot (see the class doc). These tests pin that contract down with the
 * pure [logicalPageOf]/[physicalPageOf] functions (no `PagerState`/Compose runtime needed) plus a
 * few through a real (unattached) `PagerState`, standing in for the device report where a home
 * intent's `animate -> page 0` mismatched the spread the user was actually looking at.
 */
class LauncherPagerTest {
    private val n3 = listOf(0, 1, 2) // three Home pages, nothing hidden

    @Test fun `currentPage after settling on each spread equals the right pane's page`() {
        for (slot in n3.indices) {
            assertEquals("slot $slot", spreadRightPage(n3, slot), logicalPageOf(slot, n3, homePages = 3))
        }
    }

    @Test fun `animateScrollToPage(currentPage) is a no-op`() {
        // The round trip physical -> logical -> physical must return the same physical slot for
        // every slot the pager can actually settle on, else re-requesting "the current page" would
        // itself move the pager (exactly the bug: a stale/mismatched translation animating away
        // from the spread the user is already looking at).
        for (physical in 0..n3.size) { // + the trailing "All apps" stop
            val logical = logicalPageOf(physical, n3, homePages = 3)
            assertEquals("physical $physical", physical, physicalPageOf(logical, n3, homePages = 3))
        }
    }

    @Test fun `hidden pages skip correctly`() {
        // Logical page 1 hidden: the strip only ever stops on 0, 2, 3 — physical slot 1's right
        // page is logical 2, never the hidden page.
        val visible = listOf(0, 2, 3)
        assertEquals(0, logicalPageOf(0, visible, homePages = 4))
        assertEquals(2, logicalPageOf(1, visible, homePages = 4))
        assertEquals(3, logicalPageOf(2, visible, homePages = 4))
        // The reverse: a hidden page snaps to its nearest later visible neighbor.
        assertEquals(1, physicalPageOf(1, visible, homePages = 4)) // page 1 hidden -> snaps to page 2's slot
        assertEquals(0, physicalPageOf(0, visible, homePages = 4))
        assertEquals(2, physicalPageOf(3, visible, homePages = 4))
        // Round trip still holds for every physical slot the strip actually has.
        for (physical in visible.indices) {
            assertEquals(physical, physicalPageOf(logicalPageOf(physical, visible, homePages = 4), visible, homePages = 4))
        }
    }

    @Test fun `the trailing edit-mode plus page maps to homePages`() {
        // LauncherScreen.kt's pagingTempPage suspends hiding, so `visible` already spans every
        // real Home page; the "+" page is simply the next physical stop past it.
        val visible = listOf(0, 1, 2)
        assertEquals(3, logicalPageOf(3, visible, homePages = 3))
        assertEquals(3, physicalPageOf(3, visible, homePages = 3))
    }

    @Test fun `a real PagerState delegates the same way`() {
        val pager = LauncherPager(PagerState(currentPage = 1, pageCount = { 4 }), { n3 }, { 3 })
        assertEquals(1, pager.currentPage) // spreadRightPage(n3, 1) == 1
        assertEquals(1, pager.settledPage)
        assertEquals(1, pager.physicalOf(pager.currentPage))
        assertTrue(!pager.isOnFirstPage)
        assertEquals(0, pager.firstPage)
    }

    @Test fun `first page is the pager's very first physical stop even with page 0 hidden`() {
        val visible = listOf(1, 2) // page 0 hidden by a mode
        val pager = LauncherPager(PagerState(currentPage = 0, pageCount = { 3 }), { visible }, { 3 })
        assertTrue(pager.isOnFirstPage)
        assertEquals(1, pager.firstPage) // the first visible page, not literal page 0
        assertEquals(1, pager.currentPage)
    }
}
