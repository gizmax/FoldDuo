package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BuiltinWidgetsTest {
    private val prague: ZoneId = ZoneId.of("Europe/Prague")
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(prague).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 9, 13)
    private val now = at(today, 10)

    // --- ids --------------------------------------------------------------------------

    @Test fun `built-in ids are the sentinel range -2 to -9 and nothing else`() {
        assertEquals(setOf(-2, -3, -4, -6, -7, -8, -9), BUILTIN_WIDGET_IDS)
        assertEquals(listOf(CLOCK_ANALOG_WIDGET, CALENDAR_WIDGET, BATTERIES_WIDGET, PHOTOS_WIDGET), listOf(-6, -7, -8, -9))
        assertFalse(isBuiltinWidgetId(EMPTY_WIDGET))
        assertFalse(isBuiltinWidgetId(NEEDS_BINDING_WIDGET))
        assertFalse(isBuiltinWidgetId(-10))
        assertFalse(isBuiltinWidgetId(0))
        // Placeable: host ids, built-ins and the restore placeholder; never the empty draft or unknown negatives.
        assertTrue((-9..-2).filter { it != NEEDS_BINDING_WIDGET }.all(::isPlaceableWidgetId))
        assertTrue(isPlaceableWidgetId(NEEDS_BINDING_WIDGET) && isPlaceableWidgetId(0) && isPlaceableWidgetId(41))
        assertFalse(isPlaceableWidgetId(EMPTY_WIDGET))
        assertFalse(isPlaceableWidgetId(-10))
    }

    @Test fun `model places the new built-ins and rejects ids below the range`() {
        val empty = HomeLayout(emptyList(), emptyList())
        listOf(CLOCK_ANALOG_WIDGET, CALENDAR_WIDGET, BATTERIES_WIDGET, PHOTOS_WIDGET).forEach { id ->
            val placed = placeWidget(empty, WidgetPlacement(3, id, -1, 0, 0, GRID_COLUMNS, 2))
            assertEquals(id, placed.placement(3)?.id)
        }
        assertEquals(empty, placeWidget(empty, WidgetPlacement(3, -10, -1, 0, 0, GRID_COLUMNS, 2)))
        assertEquals(empty, placeWidget(empty, WidgetPlacement(3, EMPTY_WIDGET, -1, 0, 0, GRID_COLUMNS, 2)))
        // Photos fills the six leading-pane rows when picked for page -1 (LeadingPaneTest covers the span).
        assertEquals(GRID_ROWS, builtinTodayRows(PHOTOS_WIDGET))
        assertEquals(2, builtinTodayRows(CALENDAR_WIDGET))
        assertEquals(WidgetPlacement(3, EMPTY_WIDGET, -1, 0, 0, GRID_COLUMNS, GRID_ROWS),
            widgetCandidate(empty, 3, homeCellIndex(-1, 0), GRID_COLUMNS, builtinTodayRows(PHOTOS_WIDGET)))
    }

    @Test fun `picker catalogue names and footprints`() {
        assertEquals(listOf("Clock", "Calendar", "Batteries", "Photos"), BUILTIN_WIDGET_CATALOG.take(4).map { it.second })
        assertEquals(BUILTIN_WIDGET_IDS, BUILTIN_WIDGET_CATALOG.map { it.first }.toSet())
        assertEquals(WidgetSpan(4, 2), builtinWidgetSpan(CALENDAR_WIDGET))
        assertEquals(WidgetSpan(4, 2), builtinWidgetSpan(PHOTOS_WIDGET))
        assertEquals(WidgetSpan(2, 2), builtinWidgetSpan(CLOCK_ANALOG_WIDGET))
        assertEquals(WidgetSpan(2, 2), builtinWidgetSpan(BATTERIES_WIDGET))
        assertEquals(WidgetSpan(2, 2), builtinWidgetSpan(CLOCK_WIDGET))
        assertEquals("Batteries", builtinWidgetLabel(BATTERIES_WIDGET))
        assertEquals("Add widget", builtinWidgetLabel(EMPTY_WIDGET))
        assertEquals("Widget panel", builtinWidgetLabel(INFO_WIDGET))
    }

    @Test fun `size class follows the card rectangle`() {
        assertEquals(WidgetSize.SMALL, widgetSizeFor(235f, 176f))      // Home 2 x 2
        assertEquals(WidgetSize.MEDIUM, widgetSizeFor(480f, 176f))     // Home 4 x 2, Today two rows
        assertEquals(WidgetSize.MEDIUM, widgetSizeFor(502f, 80f))      // Today one-row strip
        assertEquals(WidgetSize.LARGE, widgetSizeFor(502f, 376f))      // Today four rows
        assertEquals(WidgetSize.LARGE, widgetSizeFor(502f, 700f))      // Today full pane
        assertEquals(WidgetSize.SMALL, widgetSizeFor(300f, 320f))      // tall but narrow
    }

    // --- clock ------------------------------------------------------------------------

    @Test fun `hands sweep continuously and wrap at twelve`() {
        assertEquals(HandAngles(0f, 0f, 0f), handAngles(0, 0, 0))
        assertEquals(HandAngles(0f, 0f, 0f), handAngles(12, 0, 0))
        val quarterPastThree = handAngles(15, 15, 0)
        assertEquals(97.5f, quarterPastThree.hour, .001f)
        assertEquals(90f, quarterPastThree.minute, .001f)
        assertEquals(0f, quarterPastThree.second, .001f)
        val half = handAngles(6, 30, 30, 500)
        assertEquals(183f, half.second, .001f)
        assertEquals(183.05f, half.minute, .001f)
        assertEquals(195.254f, half.hour, .001f)
    }

    @Test fun `time zone ids become city names`() {
        assertEquals("Prague", timeZoneCity("Europe/Prague"))
        assertEquals("Buenos Aires", timeZoneCity("America/Argentina/Buenos_Aires"))
        assertEquals("UTC", timeZoneCity("UTC"))
        assertEquals("GMT+2", timeZoneCity("GMT+2"))
    }

    // --- calendar ---------------------------------------------------------------------

    private fun event(id: Long, title: String, begin: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(id, title, begin, end, allDay, null)

    @Test fun `upcoming events keep running and later ones today then tomorrow, all-day first`() {
        val tomorrow = today.plusDays(1)
        val allDayToday = event(1, "Holiday", today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        val ended = event(2, "Breakfast", at(today, 8), at(today, 9))
        val running = event(3, "Stand-up", at(today, 9, 45), at(today, 10, 15))
        val later = event(4, "Lunch", at(today, 12), at(today, 13))
        val tomorrowEvent = event(5, "Dentist", at(tomorrow, 9), at(tomorrow, 10))
        val dayAfter = event(6, "Trip", at(today.plusDays(2), 9), at(today.plusDays(2), 10))
        val yesterdayAllDay = event(7, "Old", today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        val all = listOf(dayAfter, tomorrowEvent, later, running, ended, allDayToday, yesterdayAllDay)
        assertEquals(listOf(1L, 3L, 4L), upcomingEvents(all, now, prague).map { it.id })
        assertEquals(listOf(1L, 3L, 4L, 5L), upcomingEvents(all, now, prague, limit = 6).map { it.id })
        assertTrue(upcomingEvents(emptyList(), now, prague).isEmpty())
    }

    @Test fun `event times format for today, tomorrow and all-day in both clock styles`() {
        val tomorrow = today.plusDays(1)
        val lunch = event(4, "Lunch", at(today, 14, 30), at(today, 15))
        assertEquals("14:30", formatEventTime(lunch, now, prague, twentyFourHour = true))
        assertEquals("2:30 PM", formatEventTime(lunch, now, prague, twentyFourHour = false, locale = java.util.Locale.US)
            .replace("\u202F", " "))
        val dentist = event(5, "Dentist", at(tomorrow, 9), at(tomorrow, 10))
        assertEquals("Tomorrow 9:00", formatEventTime(dentist, now, prague, twentyFourHour = true))
        val holiday = event(1, "Holiday", today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        assertEquals("All-day", formatEventTime(holiday, now, prague, twentyFourHour = true))
        val holidayTomorrow = holiday.copy(begin = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        assertEquals("Tomorrow, all-day", formatEventTime(holidayTomorrow, now, prague, twentyFourHour = true))
        assertEquals(270L, minutesUntil(lunch, now))
        assertNull(minutesUntil(holiday, now))
        assertNull(minutesUntil(lunch, at(today, 14, 45)))
    }

    // --- photos -----------------------------------------------------------------------

    @Test fun `photo title is On This Day for earlier years and Recents otherwise`() {
        assertEquals("On This Day", photoTitle(at(today.minusYears(3), 12), now, prague))
        assertEquals("On This Day", photoTitle(at(LocalDate.of(2025, 12, 31), 23, 59), now, prague))
        assertEquals("Recents", photoTitle(at(today.minusDays(5), 12), now, prague))
        assertEquals("Recents", photoTitle(now, now, prague))
    }

    // --- batteries --------------------------------------------------------------------

    @Test fun `ring sweep and colours follow the level and charging state`() {
        assertEquals(0f, ringSweep(0), 0f)
        assertEquals(3f, ringSweep(0) + 3f, 0f)
        assertEquals(3.6f, ringSweep(1), .001f)
        assertEquals(180f, ringSweep(50), .001f)
        assertEquals(360f, ringSweep(100), .001f)
        assertEquals(360f, ringSweep(140), .001f)
        assertEquals(0xFF30D158, ringColorArgb(5, charging = true))
        assertEquals(0xFFFF453A, ringColorArgb(20, charging = false))
        assertEquals(0xFFFFD60A, ringColorArgb(40, charging = false))
        assertEquals(0xFF34C759, ringColorArgb(41, charging = false))
    }

    @Test fun `battery percent rounds and clamps provider values`() {
        assertEquals(73, batteryPercent(73, 100))
        assertEquals(50, batteryPercent(1, 2))
        assertEquals(0, batteryPercent(-1, -1))
        assertEquals(100, batteryPercent(150, 100))
    }

    @Test fun `bluetooth classes map to device kinds`() {
        assertEquals(DeviceKind.HEADPHONES, deviceKindFor(0x0400, 0x0418))
        assertEquals(DeviceKind.WATCH, deviceKindFor(0x0700, 0x0704))
        assertEquals(DeviceKind.OTHER, deviceKindFor(0x0700, 0x0708))
        assertEquals(DeviceKind.OTHER, deviceKindFor(0x0100, 0x010C))
    }
}
