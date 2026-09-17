package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test

class HomeEditingTest {
    private val layout = HomeLayout(listOf("a", "b", "c"), listOf("d", "e", null, "a"))
    @Test fun `existing home app inserts forward and leaves the dock`() {
        val next = dropApp(layout, "a", DropTarget.Home(2))
        assertEquals(listOf("b", "c", "a"), next.slots)
        assertEquals(listOf("d", "e", null, null), next.dock)
        assertEquals(next, dropApp(next, "a", DropTarget.Home(2)))
    }

    @Test fun `existing home app inserts backward and rotates only intervening cells`() {
        val before = HomeLayout(listOf("a", "b", "c", "d", "e"), emptyList())
        val next = dropApp(before, "e", DropTarget.Home(1))
        assertEquals(listOf("a", "e", "b", "c", "d"), next.slots)
    }

    @Test fun `existing home app moved to an empty target leaves its source hole`() {
        val before = HomeLayout(listOf("a", "b", null, "c", "d"), emptyList())
        val next = dropApp(before, "a", DropTarget.Home(2))
        assertEquals(listOf(null, "b", "a", "c", "d"), next.slots)
    }

    @Test fun `existing move across rows preserves sparse cells within and outside its range`() {
        val before = HomeLayout(listOf("a", null, "b", "c", "d", null, "e", "f", "g", "h"), emptyList())
        val next = dropApp(before, "a", DropTarget.Home(8))
        assertEquals(listOf(null, "b", "c", "d", null, "e", "f", "g", "a", "h"), next.slots)
        assertEquals(before.slots.filterNotNull().toSet(), next.slots.filterNotNull().toSet())
    }
    @Test fun `move to new page retains empty cells and exact destination`() {
        val target = HOME_CELLS + 2
        val next = dropApp(layout, "a", DropTarget.Home(target))
        assertNull(next.slots[0])
        assertEquals("b", next.slots[1])
        assertEquals("a", next.slots[target])
        assertEquals(2, homePageCount(next.slots.size))
        assertEquals(layout.slots.filterNotNull().toSet(), next.slots.filterNotNull().toSet())
        assertNull(next.dock[3])
    }
    @Test fun `dock reorders existing shortcuts without ejecting one`() {
        val reordered = dropApp(HomeLayout(listOf("home", "d"), listOf("d", "e", "f", "a")), "d", DropTarget.Dock(2))
        assertEquals(listOf("e", "f", "d", "a"), reordered.dock)
        assertEquals(listOf("home"), reordered.slots)

        val movedToGap = dropApp(layout, "d", DropTarget.Dock(2))
        assertEquals(listOf(null, "e", "d", "a"), movedToGap.dock)
        assertEquals(layout.slots, movedToGap.slots)

        val movedBackward = dropApp(HomeLayout(listOf("home"), listOf("d", "e", "f", "a")), "a", DropTarget.Dock(1))
        assertEquals(listOf("d", "a", "e", "f"), movedBackward.dock)
        assertEquals(listOf("home"), movedBackward.slots)
    }
    @Test fun `moving dock shortcut onto home inserts it and clears dock`() {
        val next = dropApp(layout, "d", DropTarget.Home(1))
        assertEquals(listOf("a", "d", "b", "c"), next.slots)
        assertEquals(listOf(null, "e", null, "a"), next.dock)
    }

    @Test fun `new home app shifts only to the first vacancy`() {
        val before = HomeLayout(listOf("a", "b", null, "c", null, "d"), emptyList())
        val next = dropApp(before, "new", DropTarget.Home(0))
        assertEquals(listOf("new", "a", "b", "c", null, "d"), next.slots)
    }

    @Test fun `new home app wraps through rows and overflows a full page`() {
        val fullPage = (0 until 16).map(Int::toString)
        val next = dropApp(HomeLayout(fullPage, emptyList()), "new", DropTarget.Home(14))
        assertEquals(17, next.slots.size)
        assertEquals("new", next.slots[14])
        assertEquals("14", next.slots[15])
        assertEquals("15", next.slots[16])
        assertEquals(1, next.pageCount)
        assertEquals((fullPage + "new").toSet(), next.slots.filterNotNull().toSet())
    }

    @Test fun `empty home target accepts a new shortcut without shifting sparse cells`() {
        val before = HomeLayout(listOf("a", null, "b", null, "c"), emptyList())
        assertEquals(listOf("a", "new", "b", null, "c"), dropApp(before, "new", DropTarget.Home(1)).slots)
    }

    @Test fun `new dock shortcut prefers a later vacancy`() {
        val before = HomeLayout(listOf("home"), listOf("a", "b", "c", null, "d"))
        assertTrue(canPlaceInDock(before, "new"))
        val next = dropApp(before, "new", DropTarget.Dock(1))
        assertEquals(listOf("a", "new", "b", "c", "d"), next.dock)
        assertEquals(before.slots, next.slots)
        assertEquals(shortcuts(before) + "new", shortcuts(next))
    }

    @Test fun `new dock shortcut uses an earlier vacancy when no later one exists`() {
        val before = HomeLayout(listOf("new"), listOf("a", null, "b", "c", "d"))
        val next = dropApp(before, "new", DropTarget.Dock(3))
        assertEquals(listOf("a", "b", "c", "new", "d"), next.dock)
        assertEquals(emptyList<String?>(), next.slots)
        assertEquals(shortcuts(before), shortcuts(next))
    }

    @Test fun `full dock rejects a home newcomer without changing either surface`() {
        val before = HomeLayout(listOf("left", "new", "right"), listOf("a", "b", "c", "d"))
        val next = dropApp(before, "new", DropTarget.Dock(1))
        assertFalse(canPlaceInDock(before, "new"))
        assertSame(before, next)
        assertEquals(shortcuts(before), shortcuts(next))
    }

    @Test fun `home to empty dock moves without leaving a duplicate`() {
        val before = HomeLayout(listOf("a", "moving"), listOf("x", null, "y"))
        val next = dropApp(before, "moving", DropTarget.Dock(1))
        assertEquals(listOf("a"), next.slots)
        assertEquals(listOf("x", "moving", "y"), next.dock)
        assertEquals(1, (next.slots + next.dock).count { it == "moving" })
        assertEquals(shortcuts(before), shortcuts(next))
    }

    @Test fun `full dock rejects a library newcomer and keeps every shortcut`() {
        val before = HomeLayout(listOf("home"), listOf("a", "b", "c", "d"))
        val next = dropApp(before, "new", DropTarget.Dock(0))
        assertSame(before, next)
        assertEquals(shortcuts(before), shortcuts(next))
    }

    @Test fun `preview is pure and committing it again is idempotent`() {
        val before = HomeLayout(listOf("left", "new", "right"), listOf("a", null, "c", "d"))
        val preview = dropApp(before, "new", DropTarget.Dock(1))
        assertEquals(listOf("left", "new", "right"), before.slots)
        assertEquals(listOf("a", null, "c", "d"), before.dock)
        assertEquals(preview, dropApp(preview, "new", DropTarget.Dock(1)))
    }

    @Test fun `moving a dock shortcut home opens space for a new dock app`() {
        val before = HomeLayout(listOf("home"), listOf("a", "b", "c", "d"))
        val cleared = dropApp(before, "b", DropTarget.Home(1))
        assertEquals(listOf("home", "b"), cleared.slots)
        assertEquals(listOf("a", null, "c", "d"), cleared.dock)
        assertTrue(canPlaceInDock(cleared, "new"))
        val added = dropApp(cleared, "new", DropTarget.Dock(1))
        assertEquals(listOf("a", "new", "c", "d"), added.dock)
        assertEquals(cleared.slots, added.slots)
        assertEquals(shortcuts(before) + "new", shortcuts(added))
    }

    @Test fun `editing one shortcut preserves unrelated legacy duplicates`() {
        val before = HomeLayout(listOf("legacy", "legacy", "new"), listOf("a", null, "c", "d"))
        val next = dropApp(before, "new", DropTarget.Dock(1))
        assertEquals(listOf("legacy", "legacy"), next.slots)
        assertEquals(listOf("a", "new", "c", "d"), next.dock)
    }
    @Test fun `removal refresh and normalization keep intentional gaps`() {
        assertEquals(listOf(null, "b", "c"), pinHomeApp(layout.slots, "a", false))
        assertEquals(listOf("d", "b", "c"), pinHomeApp(listOf(null, "b", "c"), "d", true))
        assertEquals(listOf("a", null, "c"), reconcileHomeSlots(layout.slots, setOf("a", "c", "new")))
        assertEquals(listOf("a", null, null, "b"), normalizeHomeSlots(listOf("a", "a", null, "b", null)))
        assertEquals(emptyList<String?>(), normalizeHomeSlots(listOf(null, null)))
    }
    @Test fun `invalid drop leaves layout unchanged`() {
        assertEquals(layout, dropApp(layout, "a", DropTarget.Home(-HOME_CELLS - 1)))
        assertEquals(layout, dropApp(layout, "a", DropTarget.Home(HOME_CELLS * 2)))
        assertEquals(layout, dropApp(layout, "a", DropTarget.Dock(4)))
        assertEquals(layout, dropApp(layout, " ", DropTarget.Dock(0)))
        assertEquals(layout, dropApp(layout, "a", DropTarget.Library("a")))
        assertFalse(canPlaceInDock(layout.copy(dock = listOf("a", "b")), ""))
        assertTrue(canPlaceInDock(layout.copy(dock = listOf("a", "b")), "a"))
    }

    private fun shortcuts(layout: HomeLayout) = (layout.slots + layout.dock).filterNotNull().toSet()

    // --- Explicit page count: add/remove pages, migration, and move-to-new-page. ---

    @Test fun `a layout without an explicit page count keeps its derived page count`() {
        assertEquals(1, layout.explicitPageCount)
        assertEquals(1, layout.pageCount)
        val spanningTwoPages = HomeLayout((0 until 30).map(Int::toString), emptyList())
        assertEquals(1, spanningTwoPages.explicitPageCount)
        assertEquals(2, spanningTwoPages.pageCount)
    }

    @Test fun `adding a page grows the count and never shrinks below derived content`() {
        val one = addHomePage(layout)
        assertEquals(2, one.explicitPageCount)
        assertEquals(2, one.pageCount)
        assertEquals(3, addHomePage(one).pageCount)
        assertEquals(layout.slots, one.slots)
    }

    @Test fun `moving a widget onto the new page grows the page count exactly like an app drop`() {
        val placement = WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2)
        val before = HomeLayout(emptyList(), emptyList(), listOf(placement))
        assertEquals(1, before.pageCount)
        val moved = moveWidget(before, placement.slot, homeCellIndex(before.pageCount, 0))
        assertEquals(2, moved.pageCount)
        assertEquals(1, moved.placement(0)?.page)
    }

    @Test fun `removing a page is refused when it holds an icon, a widget, is the last page, or is out of range`() {
        val withIcon = HomeLayout(List(HOME_CELLS) { null } + listOf("b"), emptyList())
        assertEquals(2, withIcon.pageCount)
        assertEquals(withIcon, removeHomePage(withIcon, 1))
        val withWidget = HomeLayout(listOf("a"), emptyList(), listOf(WidgetPlacement(0, CLOCK_WIDGET, 1, 0, 0, 2, 2)))
        assertEquals(2, withWidget.pageCount)
        assertEquals(withWidget, removeHomePage(withWidget, 1))
        assertEquals(layout, removeHomePage(layout, 0))
        assertEquals(layout, removeHomePage(layout, 1))
        assertEquals(layout, removeHomePage(layout, -1))
    }

    @Test fun `removing an empty trailing page drops the count and keeps earlier pages`() {
        val threePages = addHomePage(addHomePage(HomeLayout(listOf("a"), emptyList())))
        assertEquals(3, threePages.pageCount)
        val two = removeHomePage(threePages, 2)
        assertEquals(2, two.pageCount)
        assertEquals(listOf("a"), two.slots)
    }

    @Test fun `removing an empty middle page shifts later slots and widgets down by one page`() {
        val slots = listOf("a") + List(HOME_CELLS - 1) { null } + List(HOME_CELLS) { null } + listOf("b")
        val threePages = HomeLayout(slots, emptyList())
        assertEquals(3, threePages.pageCount)
        val result = removeHomePage(threePages, 1)
        assertEquals(2, result.pageCount)
        assertEquals("a", result.slotAt(0))
        assertEquals("b", result.slotAt(HOME_CELLS))
        val withWidgets = HomeLayout(listOf("a"), emptyList(),
            listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2), WidgetPlacement(1, DATE_WIDGET, 2, 0, 0, 2, 2)))
        assertEquals(3, withWidgets.pageCount)
        val shifted = removeHomePage(withWidgets, 1)
        assertEquals(1, shifted.placement(1)?.page)
        assertEquals(0, shifted.placement(0)?.page)
    }

    @Test fun `trimming drops trailing empty pages but keeps a middle empty one`() {
        val base = HomeLayout(listOf("a") + List(HOME_CELLS - 1) { null } + List(HOME_CELLS) { null }, emptyList())
        assertEquals(2, base.pageCount)
        val threePages = addHomePage(base)
        assertEquals(3, threePages.pageCount)
        val trimmed = trimTrailingEmptyPages(threePages)
        assertEquals(1, trimmed.pageCount)
        assertEquals(listOf("a"), trimmed.slots)

        val keepsMiddle = HomeLayout(listOf("a") + List(HOME_CELLS - 1) { null } + List(HOME_CELLS) { null } + listOf("b"), emptyList())
        assertEquals(keepsMiddle, trimTrailingEmptyPages(keepsMiddle))
    }

    // --- B39 "Přehled stránek": reorder, hide/show, and the pager's visible-index mapping. ---

    private fun threePageLayout() = HomeLayout(
        listOf("a") + List(HOME_CELLS - 1) { null } + List(HOME_CELLS) { null } + listOf("c"), emptyList(),
        listOf(WidgetPlacement(0, CLOCK_WIDGET, 1, 0, 0, 2, 2, stackMembers = listOf(CLOCK_WIDGET, DATE_WIDGET))))

    @Test fun `reordering pages moves slots widgets and stacks together, and out-of-range or equal indices are a no-op`() {
        val before = threePageLayout()
        assertEquals(3, before.pageCount)
        val moved = reorderHomePages(before, 0, 2)
        // Page 0's "a" now sits on the last page; the widget (with its stack) that lived on
        // page 1 shifts down to page 0 since everything after the removed slot moves up one.
        assertEquals("a", moved.slotAt(homeCellIndex(2, 0)))
        assertNull(moved.slotAt(homeCellIndex(0, 0)))
        assertEquals("c", moved.slotAt(homeCellIndex(1, 0)))
        val widget = moved.placement(0)!!
        assertEquals(0, widget.page)
        assertEquals(listOf(CLOCK_WIDGET, DATE_WIDGET), widget.stackMembers)
        assertEquals(before, reorderHomePages(before, 1, 1))
        assertEquals(before, reorderHomePages(before, -1, 1))
        assertEquals(before, reorderHomePages(before, 0, 3))
    }

    @Test fun `reordering carries a page's hidden flag with its content`() {
        val hidden = threePageLayout().copy(hiddenPages = setOf(1))
        val moved = reorderHomePages(hidden, 1, 2)
        assertEquals(setOf(2), moved.hiddenPages)
    }

    @Test fun `hiding a page is refused for the leading canvas, out of range, or the last visible page`() {
        val layout = threePageLayout()
        assertEquals(layout, setPageHidden(layout, -1, true))
        assertEquals(layout, setPageHidden(layout, 3, true))
        val allHidden = setPageHidden(setPageHidden(layout, 0, true), 1, true)
        assertEquals(setOf(0, 1), allHidden.hiddenPages)
        // The third and last visible page cannot be hidden too.
        assertEquals(allHidden, setPageHidden(allHidden, 2, true))
        assertEquals(setOf(1), setPageHidden(allHidden, 0, false).hiddenPages)
    }

    @Test fun `visible index mapping skips hidden pages both ways and never hides every page`() {
        assertEquals(listOf(0, 1, 2, 3), visiblePageIndices(4, emptySet()))
        assertEquals(listOf(0, 2), visiblePageIndices(4, setOf(1, 3)))
        // Every page hidden is a safety fallback: show them all rather than nothing.
        assertEquals(listOf(0, 1, 2), visiblePageIndices(3, setOf(0, 1, 2)))
        assertEquals(2, realPageForVisibleIndex(4, setOf(1, 3), 1))
        assertEquals(0, realPageForVisibleIndex(4, setOf(1, 3), 0))
        // Clamped, never throws, for an index past the visible count.
        assertEquals(2, realPageForVisibleIndex(4, setOf(1, 3), 5))
        assertEquals(1, visibleIndexForRealPage(4, setOf(1, 3), 2))
        assertNull(visibleIndexForRealPage(4, setOf(1, 3), 1))
        assertNull(visibleIndexForRealPage(4, setOf(1, 3), 9))
    }

    // --- 2026-09-17 noc "Mazání stránek s dotazem": force-deleting a non-empty page. ---

    /** Four pages: page 0 has a plain app, page 1 (the one force-deleted below) has a folder tile,
     * a pair tile and a widget carrying a Smart Stack, page 2 is empty but hidden, page 3 has a
     * plain app and is also hidden. */
    private fun forceDeleteFixture(): Triple<HomeLayout, String, String> {
        val folderId = newFolderId()
        val pairId = newPairId()
        val slots = MutableList<String?>(4 * HOME_CELLS) { null }
        slots[homeCellIndex(0, 0)] = "a"
        slots[homeCellIndex(1, 0)] = folderId
        slots[homeCellIndex(1, 1)] = pairId
        slots[homeCellIndex(3, 0)] = "z"
        val layout = HomeLayout(slots, emptyList(),
            widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, 1, 2, 0, 2, 2, stackMembers = listOf(CLOCK_WIDGET, DATE_WIDGET))),
            folders = listOf(FolderEntry(folderId, "Folder", listOf("f1", "f2"))),
            pairs = listOf(PairEntry(pairId, "p1", "p2")),
            hiddenPages = setOf(2, 3))
        return Triple(layout, folderId, pairId)
    }

    @Test fun `force-deleting a non-empty page dissolves folders and pairs, drops widgets, shifts later pages, and remaps hidden pages`() {
        val (layout, folderId, pairId) = forceDeleteFixture()
        assertEquals(4, layout.pageCount)
        // Without force it is refused exactly like before, content untouched.
        assertEquals(layout, removeHomePage(layout, 1))
        val deleted = removeHomePage(layout, 1, force = true)
        assertEquals(3, deleted.pageCount)
        assertEquals("a", deleted.slotAt(homeCellIndex(0, 0)))
        // Former page 2 (empty) is now page 1; former page 3 ("z") is now page 2.
        assertTrue(deleted.slotsForPage(1).all { it == null })
        assertEquals("z", deleted.slotAt(homeCellIndex(2, 0)))
        // The folder/pair tiles are gone from Home and their entries dissolved outright — their
        // member apps were never Home slots of their own, so nothing needs relocating for them.
        assertNull(deleted.indexOfShortcut(folderId))
        assertNull(deleted.indexOfShortcut(pairId))
        assertTrue(deleted.folders.none { it.id == folderId })
        assertTrue(deleted.pairs.none { it.id == pairId })
        // The widget (and the Smart Stack riding on it) on the deleted page is dropped outright.
        assertNull(deleted.placement(0))
        // Hidden pages 2 and 3 (both after the deleted page 1) shift down to 1 and 2.
        assertEquals(setOf(1, 2), deleted.hiddenPages)
    }

    @Test fun `force deleting the only remaining page is refused, same minimum as the non-force path`() {
        val single = HomeLayout(listOf("a"), emptyList())
        assertEquals(1, single.pageCount)
        assertEquals(single, removeHomePage(single, 0, force = true))
    }

    @Test fun `delete-page counts include every folder and pair member, and gate the confirmation`() {
        val (layout, _, _) = forceDeleteFixture()
        assertEquals(4 to 1, homeDeletePageCounts(layout, 1))
        assertTrue(homeDeletePageNeedsConfirm(layout, 1))
        // Page 2 is genuinely empty: no confirmation needed, and both counts are zero.
        assertEquals(0 to 0, homeDeletePageCounts(layout, 2))
        assertFalse(homeDeletePageNeedsConfirm(layout, 2))
    }

    @Test fun `delete-page message reports both counts literally`() {
        assertEquals("Stránka obsahuje 4 aplikací / 1 widgetů. Aplikace zůstanou v App Library.",
            homeDeletePageMessage(4, 1))
        assertEquals("Stránka obsahuje 0 aplikací / 0 widgetů. Aplikace zůstanou v App Library.",
            homeDeletePageMessage(0, 0))
    }
}
