package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test

class StackRotationTest {
    private val calendar = StackMember(CALENDAR_WIDGET, StackCategory.CALENDAR)
    private val photos = StackMember(PHOTOS_WIDGET, StackCategory.PHOTOS, usageScore = 3)
    private val weather = StackMember(100, StackCategory.WEATHER, usageScore = 9)
    private val clock = StackMember(CLOCK_WIDGET, StackCategory.CLOCK, usageScore = 1)
    private val members = listOf(calendar, photos, weather, clock)

    // --- rule table: periodFor ----------------------------------------------------------------

    @Test fun `periodFor covers every hour exactly once, per the B26 rule table`() {
        val expected = mapOf(
            0 to DayPeriod.NIGHT, 1 to DayPeriod.NIGHT, 2 to DayPeriod.NIGHT, 3 to DayPeriod.NIGHT,
            4 to DayPeriod.NIGHT, 5 to DayPeriod.NIGHT,
            6 to DayPeriod.MORNING, 7 to DayPeriod.MORNING, 8 to DayPeriod.MORNING, 9 to DayPeriod.MORNING,
            10 to DayPeriod.MIDDAY, 11 to DayPeriod.MIDDAY, 12 to DayPeriod.MIDDAY, 13 to DayPeriod.MIDDAY,
            14 to DayPeriod.MIDDAY, 15 to DayPeriod.MIDDAY, 16 to DayPeriod.MIDDAY,
            17 to DayPeriod.EVENING, 18 to DayPeriod.EVENING, 19 to DayPeriod.EVENING, 20 to DayPeriod.EVENING,
            21 to DayPeriod.EVENING,
            22 to DayPeriod.NIGHT, 23 to DayPeriod.NIGHT,
        )
        expected.forEach { (hour, period) -> assertEquals("hour $hour", period, StackRotation.periodFor(hour)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `periodFor rejects an out-of-range hour`() { StackRotation.periodFor(24) }

    @Test fun `categoryForBuiltin classifies every built-in, host ids default to OTHER`() {
        assertEquals(StackCategory.CALENDAR, StackRotation.categoryForBuiltin(CALENDAR_WIDGET))
        assertEquals(StackCategory.PHOTOS, StackRotation.categoryForBuiltin(PHOTOS_WIDGET))
        assertEquals(StackCategory.CLOCK, StackRotation.categoryForBuiltin(CLOCK_WIDGET))
        assertEquals(StackCategory.CLOCK, StackRotation.categoryForBuiltin(CLOCK_ANALOG_WIDGET))
        assertEquals(StackCategory.OTHER, StackRotation.categoryForBuiltin(BATTERIES_WIDGET))
        assertEquals(StackCategory.OTHER, StackRotation.categoryForBuiltin(42))
    }

    // --- preferredIndex ------------------------------------------------------------------------

    @Test fun `morning prefers the first Calendar member`() {
        assertEquals(0, StackRotation.preferredIndex(members, hour = 8, usageAvailable = false))
    }

    @Test fun `evening prefers the first Weather or Photos member`() {
        assertEquals(1, StackRotation.preferredIndex(members, hour = 18, usageAvailable = false))
        assertEquals(1, StackRotation.preferredIndex(listOf(calendar, weather), hour = 18, usageAvailable = false))
    }

    @Test fun `night prefers the first Clock member`() {
        assertEquals(3, StackRotation.preferredIndex(members, hour = 23, usageAvailable = true))
    }

    @Test fun `midday prefers the most-used member only while usage stats are available`() {
        assertEquals(2, StackRotation.preferredIndex(members, hour = 12, usageAvailable = true)) // weather, score 9
        assertNull(StackRotation.preferredIndex(members, hour = 12, usageAvailable = false))
    }

    @Test fun `no matching category or an empty stack returns null, not a guess`() {
        assertNull(StackRotation.preferredIndex(listOf(photos, weather), hour = 8, usageAvailable = false)) // no calendar
        assertNull(StackRotation.preferredIndex(emptyList(), hour = 8, usageAvailable = true))
    }

    // --- pin -----------------------------------------------------------------------------------

    @Test fun `a pin holds for 30 minutes and expires exactly on the boundary`() {
        val pin = StackPin(PHOTOS_WIDGET, pinnedAtMillis = 1_000L)
        assertTrue(StackRotation.isPinned(pin, now = 1_000L))
        assertTrue(StackRotation.isPinned(pin, now = 1_000L + STACK_PIN_DURATION_MS - 1))
        assertFalse(StackRotation.isPinned(pin, now = 1_000L + STACK_PIN_DURATION_MS))
        assertFalse(StackRotation.isPinned(null, now = 1_000L))
        assertFalse(StackRotation.isPinned(pin, now = 999L)) // a clock that moved backward never reports pinned
    }

    // --- resolveIndex: combines pin, smart rotate and the rule table ---------------------------

    @Test fun `a live pin overrides the time-of-day rule`() {
        val pin = StackPin(clock.id, pinnedAtMillis = 0L)
        val index = StackRotation.resolveIndex(members, hour = 8, currentIndex = 1, usageAvailable = false,
            smartRotate = true, pin = pin, now = STACK_PIN_DURATION_MS - 1)
        assertEquals(3, index) // clock's index, even though hour 8 would prefer calendar
    }

    @Test fun `an expired pin falls through to the rule`() {
        val pin = StackPin(clock.id, pinnedAtMillis = 0L)
        val index = StackRotation.resolveIndex(members, hour = 8, currentIndex = 1, usageAvailable = false,
            smartRotate = true, pin = pin, now = STACK_PIN_DURATION_MS)
        assertEquals(0, index) // calendar, morning rule
    }

    @Test fun `smart rotate off keeps the current member even when the rule would change it`() {
        val index = StackRotation.resolveIndex(members, hour = 23, currentIndex = 1, usageAvailable = false,
            smartRotate = false, pin = null, now = 0L)
        assertEquals(1, index)
    }

    @Test fun `no rule match keeps the current member`() {
        val index = StackRotation.resolveIndex(listOf(photos, weather), hour = 8, currentIndex = 1, usageAvailable = false,
            smartRotate = true, pin = null, now = 0L)
        assertEquals(1, index)
    }

    @Test fun `resolveIndex on an empty stack returns currentIndex unchanged`() {
        assertEquals(4, StackRotation.resolveIndex(emptyList(), hour = 8, currentIndex = 4, usageAvailable = true,
            smartRotate = true, pin = null, now = 0L))
    }

    // --- visibility gating ----------------------------------------------------------------------

    @Test fun `rotation may run only while hidden or exactly on the resume that reveals the page`() {
        assertFalse(stackRotationMayRun(pageVisible = true, justBecameVisible = false))
        assertTrue(stackRotationMayRun(pageVisible = true, justBecameVisible = true))
        assertTrue(stackRotationMayRun(pageVisible = false, justBecameVisible = false))
        assertTrue(stackRotationMayRun(pageVisible = false, justBecameVisible = true))
    }
}
