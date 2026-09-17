package cz.pflanzer.foldduo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetStackTest {
    private fun layoutOf(vararg placements: WidgetPlacement) = HomeLayout(emptyList(), emptyList(), placements.toList())

    // --- creation ---------------------------------------------------------------------------

    @Test fun `dropping a widget onto a same-span widget creates a stack showing the dropped one`() {
        val target = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 4, 2)
        val dragged = WidgetPlacement(1, PHOTOS_WIDGET, 0, 0, 2, 4, 2)
        val next = stackWidgets(layoutOf(target, dragged), targetSlot = 0, draggedSlot = 1)
        assertEquals(1, next.widgetPlacements.size)
        val stack = next.placement(0)!!
        assertTrue(stack.isStack)
        assertEquals(PHOTOS_WIDGET, stack.id)
        assertEquals(listOf(CALENDAR_WIDGET, PHOTOS_WIDGET), stack.stackMembers)
        assertNull(next.placement(1))
    }

    @Test fun `stacking rejects different spans and the same slot`() {
        val a = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 4, 2)
        val b = WidgetPlacement(1, PHOTOS_WIDGET, 0, 0, 2, 2, 2)
        val layout = layoutOf(a, b)
        assertEquals(layout, stackWidgets(layout, 0, 1))
        assertEquals(layout, stackWidgets(layout, 0, 0))
        assertEquals(layout, stackWidgets(layout, 0, 99))
    }

    @Test fun `dropping onto an existing stack appends the new member and shows it`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2, stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        val dragged = WidgetPlacement(1, CLOCK_WIDGET, 0, 2, 0, 2, 2)
        val next = stackWidgets(layoutOf(stack, dragged), 0, 1).placement(0)!!
        assertEquals(listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET), next.stackMembers)
        assertEquals(CLOCK_WIDGET, next.id)
    }

    @Test fun `dropping a whole stack onto a widget merges every member`() {
        val target = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2)
        val draggedStack = WidgetPlacement(1, PHOTOS_WIDGET, 0, 2, 0, 2, 2,
            stackMembers = listOf(CLOCK_WIDGET, PHOTOS_WIDGET))
        val next = stackWidgets(layoutOf(target, draggedStack), 0, 1).placement(0)!!
        assertEquals(listOf(CALENDAR_WIDGET, CLOCK_WIDGET, PHOTOS_WIDGET), next.stackMembers)
        assertEquals(PHOTOS_WIDGET, next.id)
    }

    @Test fun `dropping a widget already a member removes the dragged slot without duplicating it`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2, stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        val dragged = WidgetPlacement(1, PHOTOS_WIDGET, 0, 2, 0, 2, 2)
        val next = stackWidgets(layoutOf(stack, dragged), 0, 1)
        assertEquals(listOf(CALENDAR_WIDGET, PHOTOS_WIDGET), next.placement(0)!!.stackMembers)
        assertNull(next.placement(1))
    }

    // --- dissolution --------------------------------------------------------------------------

    @Test fun `removing down to one member dissolves the stack`() {
        val stack = WidgetPlacement(0, PHOTOS_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        val next = removeStackMember(layoutOf(stack), 0, PHOTOS_WIDGET).placement(0)!!
        assertFalse(next.isStack)
        assertEquals(emptyList<Int>(), next.stackMembers)
        assertEquals(CALENDAR_WIDGET, next.id)
    }

    @Test fun `removing the shown member falls back to the first remaining one`() {
        val stack = WidgetPlacement(0, PHOTOS_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET))
        val next = removeStackMember(layoutOf(stack), 0, PHOTOS_WIDGET).placement(0)!!
        assertTrue(next.isStack)
        assertEquals(listOf(CALENDAR_WIDGET, CLOCK_WIDGET), next.stackMembers)
        assertEquals(CALENDAR_WIDGET, next.id)
    }

    @Test fun `removing a non-member or on a non-stack is a no-op`() {
        val stack = WidgetPlacement(0, PHOTOS_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        val layout = layoutOf(stack)
        assertEquals(layout, removeStackMember(layout, 0, CLOCK_WIDGET))
        val plain = WidgetPlacement(1, CLOCK_WIDGET, 0, 2, 0, 2, 2)
        val plainLayout = layoutOf(plain)
        assertEquals(plainLayout, removeStackMember(plainLayout, 1, CLOCK_WIDGET))
    }

    // --- member cycling & direct selection -----------------------------------------------------

    @Test fun `cycling forward and backward wraps around the membership`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET))
        val layout = layoutOf(stack)
        val forward = cycleStackMember(layout, 0, forward = true).placement(0)!!
        assertEquals(PHOTOS_WIDGET, forward.id)
        val wrapped = cycleStackMember(layoutOf(forward), 0, forward = true).let {
            cycleStackMember(it, 0, forward = true)
        }.placement(0)!!
        assertEquals(CALENDAR_WIDGET, wrapped.id)
        val backward = cycleStackMember(layout, 0, forward = false).placement(0)!!
        assertEquals(CLOCK_WIDGET, backward.id)
    }

    @Test fun `cycling a plain widget is a no-op`() {
        val plain = WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2)
        val layout = layoutOf(plain)
        assertEquals(layout, cycleStackMember(layout, 0, forward = true))
    }

    @Test fun `showStackMember jumps directly and ignores non-members`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET))
        val layout = layoutOf(stack)
        assertEquals(CLOCK_WIDGET, showStackMember(layout, 0, CLOCK_WIDGET).placement(0)!!.id)
        assertEquals(layout, showStackMember(layout, 0, BATTERIES_WIDGET))
    }

    // --- reordering & smart rotate ---------------------------------------------------------

    @Test fun `reordering moves a member to a new index`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET))
        val next = reorderStackMember(layoutOf(stack), 0, from = 0, to = 2).placement(0)!!
        assertEquals(listOf(PHOTOS_WIDGET, CLOCK_WIDGET, CALENDAR_WIDGET), next.stackMembers)
    }

    @Test fun `reordering out of range or on a non-stack is a no-op`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        val layout = layoutOf(stack)
        assertEquals(layout, reorderStackMember(layout, 0, 0, 5))
        val plain = layoutOf(WidgetPlacement(1, CLOCK_WIDGET, 0, 2, 0, 2, 2))
        assertEquals(plain, reorderStackMember(plain, 1, 0, 0))
    }

    @Test fun `smart rotate toggles independently of membership`() {
        val stack = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        val next = setStackSmartRotate(layoutOf(stack), 0, false).placement(0)!!
        assertFalse(next.smartRotate)
        assertEquals(listOf(CALENDAR_WIDGET, PHOTOS_WIDGET), next.stackMembers)
    }

    // --- helpers -----------------------------------------------------------------------------

    @Test fun `isStack, stackedIds and activeStackIndex agree on plain and stacked placements`() {
        val plain = WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2)
        assertFalse(plain.isStack)
        assertEquals(listOf(CLOCK_WIDGET), plain.stackedIds())
        assertEquals(0, plain.activeStackIndex())
        val stack = plain.copy(id = PHOTOS_WIDGET, stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET))
        assertTrue(stack.isStack)
        assertEquals(listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET), stack.stackedIds())
        assertEquals(1, stack.activeStackIndex())
    }

    @Test fun `withSanitizedStack repairs an inconsistent shown id and dissolves a lone member`() {
        val orphanShown = WidgetPlacement(0, BATTERIES_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        assertEquals(CALENDAR_WIDGET, orphanShown.withSanitizedStack().id)
        val lone = WidgetPlacement(0, PHOTOS_WIDGET, 0, 0, 0, 2, 2, stackMembers = listOf(CALENDAR_WIDGET))
        val sanitized = lone.withSanitizedStack()
        assertFalse(sanitized.isStack)
        assertEquals(CALENDAR_WIDGET, sanitized.id)
        val consistent = WidgetPlacement(0, PHOTOS_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET))
        assertEquals(consistent, consistent.withSanitizedStack())
    }

    @Test fun `widgetPlacementAt finds the widget covering a cell, never the excluded slot`() {
        val a = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 2, 2)
        val b = WidgetPlacement(1, PHOTOS_WIDGET, 0, 2, 0, 2, 2)
        val layout = layoutOf(a, b)
        assertEquals(0, widgetPlacementAt(layout, homeCellIndex(0, 1), excludeSlot = 1)?.slot)
        assertNull(widgetPlacementAt(layout, homeCellIndex(0, 1), excludeSlot = 0))
        assertNull(widgetPlacementAt(layout, homeCellIndex(0, 9), excludeSlot = 5))
    }

    @Test fun `canStackTogether requires a different slot and an identical span`() {
        val a = WidgetPlacement(0, CALENDAR_WIDGET, 0, 0, 0, 4, 2)
        val sameSpan = WidgetPlacement(1, PHOTOS_WIDGET, 0, 0, 2, 4, 2)
        val differentSpan = WidgetPlacement(2, PHOTOS_WIDGET, 0, 0, 2, 2, 2)
        assertTrue(canStackTogether(a, sameSpan))
        assertFalse(canStackTogether(a, differentSpan))
        assertFalse(canStackTogether(a, a))
    }

    // --- JSON round trip + migration (LayoutBackup.kt's shared extension functions) -----------

    @Test fun `stack fields JSON round-trip and old JSON without them migrates to no stack`() {
        val placement = WidgetPlacement(0, PHOTOS_WIDGET, 0, 0, 0, 2, 2,
            stackMembers = listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET), smartRotate = false)
        val json = JSONObject().putWidgetStack(placement)
        assertEquals(listOf(CALENDAR_WIDGET, PHOTOS_WIDGET, CLOCK_WIDGET), json.widgetStackMembers())
        assertFalse(json.widgetSmartRotate())

        val plain = WidgetPlacement(1, CLOCK_WIDGET, 0, 2, 0, 2, 2)
        val plainJson = JSONObject().putWidgetStack(plain)
        assertFalse(plainJson.has("stack"))
        assertTrue(plainJson.widgetStackMembers().isEmpty())
        assertTrue(plainJson.widgetSmartRotate())

        // A layout saved before B26 existed has neither key at all.
        val legacy = JSONObject()
        assertEquals(emptyList<Int>(), legacy.widgetStackMembers())
        assertTrue(legacy.widgetSmartRotate())
    }
}
