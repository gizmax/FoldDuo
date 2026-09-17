package cz.pflanzer.foldduo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

/** [popoverAnchorOffset]/[popoverOpensBelow]/[popoverAlignsLeft] (placement) and
 * [iconPopoverGroups] (row grouping/order) — the pure parts of IconPopover.kt's iOS restyle
 * (2026-09-17 night), so menu placement and section order are JVM-testable without measuring a
 * real popover. */
class IconPopoverTest {
    private val screen = Size(400f, 800f)
    private val popover = Size(250f, 200f)
    private val margin = 12f

    @Test fun `opens below the icon when there is room`() {
        val icon = Rect(20f, 100f, 90f, 170f)
        assertTrue(popoverOpensBelow(icon, popover.height, screen.height, margin))
        val offset = popoverAnchorOffset(icon, popover, screen, margin)
        assertEquals(icon.bottom + margin, offset.y, 0.01f)
    }

    @Test fun `flips above the icon when below runs off the bottom`() {
        val icon = Rect(20f, 650f, 90f, 720f)
        assertFalse(popoverOpensBelow(icon, popover.height, screen.height, margin))
        val offset = popoverAnchorOffset(icon, popover, screen, margin)
        assertEquals(icon.top - margin - popover.height, offset.y, 0.01f)
    }

    @Test fun `stays left-aligned with the icon when there is room`() {
        val icon = Rect(20f, 100f, 90f, 170f)
        assertTrue(popoverAlignsLeft(icon, popover.width, screen.width, margin))
        val offset = popoverAnchorOffset(icon, popover, screen, margin)
        assertEquals(icon.left, offset.x, 0.01f)
    }

    @Test fun `flips to the icon's trailing edge when left-aligned would run off the screen`() {
        val icon = Rect(300f, 100f, 370f, 170f)
        assertFalse(popoverAlignsLeft(icon, popover.width, screen.width, margin))
        val offset = popoverAnchorOffset(icon, popover, screen, margin)
        assertEquals(icon.right - popover.width, offset.x, 0.01f)
    }

    @Test fun `x clamps to the full screen when no pane range is given`() {
        val icon = Rect(-50f, 100f, 10f, 170f)
        val offset = popoverAnchorOffset(icon, popover, screen, margin)
        assertTrue(offset.x >= margin)
        assertTrue(offset.x + popover.width <= screen.width - margin + 0.01f)
    }

    @Test fun `x clamps inside the given pane instead of the whole screen`() {
        // A pane comfortably wider than the card (280 dp vs. 250 dp): without a pane range the
        // card could stray past the pane's own edge into whatever sits beyond it; with one it
        // stays clamped to the pane's own extent instead of the full 400-wide screen.
        val icon = Rect(210f, 100f, 280f, 170f)
        val paneRangeX = 60f..340f
        val offset = popoverAnchorOffset(icon, popover, screen, margin, paneRangeX)
        assertTrue("x=${offset.x} should stay right of the pane start", offset.x >= paneRangeX.start + margin - 0.01f)
        assertTrue("x=${offset.x} + width should stay inside the pane", offset.x + popover.width <= paneRangeX.endInclusive - margin + 0.01f)
    }

    @Test fun `pane clamp never pushes the card fully out of a very narrow pane`() {
        val icon = Rect(210f, 100f, 280f, 170f)
        val narrowPane = 200f..260f
        val offset = popoverAnchorOffset(icon, popover, screen, margin, narrowPane)
        assertTrue(offset.x >= narrowPane.start)
    }

    @Test fun `row grouping, placed app groups Remove from Home on its own trailing red group`() {
        val groups = iconPopoverGroups(placed = true)
        assertEquals(3, groups.size)
        assertEquals(listOf(PopoverRowId.SHARE, PopoverRowId.MOVE_PAGE, PopoverRowId.EDIT_HOME, PopoverRowId.APP_INFO), groups[0])
        assertEquals(listOf(PopoverRowId.TOGGLE_HOME), groups[1])
        assertEquals(listOf(PopoverRowId.MORE), groups[2])
    }

    @Test fun `row grouping, unplaced app folds Add to Home into the middle group`() {
        val groups = iconPopoverGroups(placed = false)
        assertEquals(2, groups.size)
        assertEquals(
            listOf(PopoverRowId.SHARE, PopoverRowId.MOVE_PAGE, PopoverRowId.EDIT_HOME, PopoverRowId.APP_INFO, PopoverRowId.TOGGLE_HOME),
            groups[0],
        )
        assertEquals(listOf(PopoverRowId.MORE), groups[1])
    }

    @Test fun `More is always the last row overall`() {
        assertEquals(PopoverRowId.MORE, iconPopoverGroups(true).last().last())
        assertEquals(PopoverRowId.MORE, iconPopoverGroups(false).last().last())
    }
}
