package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadPagerTest {
    @Test fun `left and right page at each settled slot, no hidden pages`() {
        val visible = listOf(0, 1, 2, 3)
        assertEquals(TODAY_STRIP_PAGE, spreadLeftPage(visible, 0))
        assertEquals(0, spreadRightPage(visible, 0))
        assertEquals(0, spreadLeftPage(visible, 1))
        assertEquals(1, spreadRightPage(visible, 1))
        assertEquals(2, spreadLeftPage(visible, 3))
        assertEquals(3, spreadRightPage(visible, 3))
    }

    @Test fun `hidden pages are skipped in the strip`() {
        // Logical pages 0,1,2,3 with page 1 hidden -> the strip only ever stops on 0, 2, 3.
        val visible = listOf(0, 2, 3)
        assertEquals(TODAY_STRIP_PAGE, spreadLeftPage(visible, 0))
        assertEquals(0, spreadRightPage(visible, 0))
        // Slot 1's right page is logical 2 (1 skipped); its left page is the predecessor visible
        // page, 0 - never the hidden page 1.
        assertEquals(0, spreadLeftPage(visible, 1))
        assertEquals(2, spreadRightPage(visible, 1))
        assertEquals(2, spreadLeftPage(visible, 2))
        assertEquals(3, spreadRightPage(visible, 2))
    }

    @Test fun `the trailing edit-mode plus page is just another strip stop`() {
        // LauncherScreen.kt's pagingTempPage appends one extra visible index (== homePages) while
        // editing; the strip needs no special case for it, it is simply the last entry.
        val homePages = 2
        val visibleWhileEditing = listOf(0, 1, homePages)
        assertEquals(1, spreadLeftPage(visibleWhileEditing, 2))
        assertEquals(homePages, spreadRightPage(visibleWhileEditing, 2))
    }

    @Test fun `eligible pages are the settled slot's own left and right page`() {
        val visible = listOf(0, 2, 3)
        assertEquals(setOf(TODAY_STRIP_PAGE, 0), spreadEligiblePages(visible, 0))
        assertEquals(setOf(0, 2), spreadEligiblePages(visible, 1))
        assertEquals(setOf(2, 3), spreadEligiblePages(visible, 2))
        assertEquals(emptySet<Int>(), spreadEligiblePages(emptyList(), 0))
    }

    @Test fun `viewport placement after a cover to inner swap lands the cover page on the right`() {
        val visible = listOf(0, 1, 2, 3)
        // Page 0: predecessor is Today.
        val slot0 = spreadSlotForPage(visible, 0)
        assertEquals(0, slot0)
        assertEquals(TODAY_STRIP_PAGE, spreadLeftPage(visible, slot0))
        assertEquals(0, spreadRightPage(visible, slot0))
        // Some page k > 0: predecessor is page k - 1.
        val slotK = spreadSlotForPage(visible, 2)
        assertEquals(2, slotK)
        assertEquals(1, spreadLeftPage(visible, slotK))
        assertEquals(2, spreadRightPage(visible, slotK))
    }

    @Test fun `viewport placement skips a hidden predecessor too`() {
        val visible = listOf(0, 2, 3) // logical page 1 hidden
        val slot = spreadSlotForPage(visible, 2)
        assertEquals(1, slot)
        assertEquals(0, spreadLeftPage(visible, slot))
        assertEquals(2, spreadRightPage(visible, slot))
    }

    @Test fun `viewport placement falls back to slot 0 for a page the strip does not show`() {
        assertEquals(0, spreadSlotForPage(listOf(0, 2, 3), 1))
    }
}
