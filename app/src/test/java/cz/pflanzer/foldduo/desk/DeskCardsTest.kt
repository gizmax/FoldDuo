package cz.pflanzer.foldduo.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskCardsTest {
    @Test fun defaultOrderIsEveryCard() = assertEquals(DeskCard.entries, DeskCardOrder.DEFAULT)

    @Test fun encodeDecodeRoundTrips() {
        val order = listOf(DeskCard.Timer, DeskCard.Media, DeskCard.Note, DeskCard.Calculator, DeskCard.Toggles, DeskCard.Recents)
        assertEquals(order, DeskCardOrder.decode(DeskCardOrder.encode(order)))
    }

    @Test fun decodeNullOrBlankIsDefault() {
        assertEquals(DeskCardOrder.DEFAULT, DeskCardOrder.decode(null))
        assertEquals(DeskCardOrder.DEFAULT, DeskCardOrder.decode(""))
        assertEquals(DeskCardOrder.DEFAULT, DeskCardOrder.decode("   "))
    }

    @Test fun decodeDropsUnknownNamesAndDuplicates() {
        val decoded = DeskCardOrder.decode("Timer,Bogus,Timer,Media")
        assertEquals(listOf(DeskCard.Timer, DeskCard.Media), decoded.take(2))
        assertEquals(1, decoded.count { it == DeskCard.Timer })
    }

    @Test fun decodeAppendsCardsMissingFromSavedText() {
        // A card added after the user already saved an order (schema evolution) must still show up.
        val decoded = DeskCardOrder.decode("Note,Media")
        assertTrue(DeskCard.Timer in decoded)
        assertTrue(DeskCard.Calculator in decoded)
        assertEquals(listOf(DeskCard.Note, DeskCard.Media), decoded.take(2))
    }

    @Test fun moveReordersWithoutLosingItems() {
        val order = listOf(DeskCard.Media, DeskCard.Timer, DeskCard.Note)
        val moved = DeskCardOrder.move(order, from = 0, to = 2)
        assertEquals(listOf(DeskCard.Timer, DeskCard.Note, DeskCard.Media), moved)
        assertEquals(order.toSet(), moved.toSet())
    }

    @Test fun moveSameIndexIsNoOp() {
        val order = listOf(DeskCard.Media, DeskCard.Timer, DeskCard.Note)
        assertEquals(order, DeskCardOrder.move(order, 1, 1))
    }

    @Test fun moveClampsOutOfRangeIndices() {
        val order = listOf(DeskCard.Media, DeskCard.Timer, DeskCard.Note)
        val moved = DeskCardOrder.move(order, from = -5, to = 99)
        assertEquals(listOf(DeskCard.Timer, DeskCard.Note, DeskCard.Media), moved)
    }

    @Test fun hideAndShowRoundTrip() {
        var hidden = emptySet<DeskCard>()
        hidden = DeskCardOrder.hide(hidden, DeskCard.Recents)
        assertTrue(DeskCard.Recents in hidden)
        hidden = DeskCardOrder.show(hidden, DeskCard.Recents)
        assertFalse(DeskCard.Recents in hidden)
    }

    @Test fun hiddenEncodeDecodeRoundTrips() {
        val hidden = setOf(DeskCard.Recents, DeskCard.Toggles)
        assertEquals(hidden, DeskCardOrder.decodeHidden(DeskCardOrder.encodeHidden(hidden)))
    }

    @Test fun visibleFiltersHiddenButKeepsOrder() {
        val order = listOf(DeskCard.Media, DeskCard.Timer, DeskCard.Note, DeskCard.Calculator)
        val visible = DeskCardOrder.visible(order, setOf(DeskCard.Timer))
        assertEquals(listOf(DeskCard.Media, DeskCard.Note, DeskCard.Calculator), visible)
    }
}
