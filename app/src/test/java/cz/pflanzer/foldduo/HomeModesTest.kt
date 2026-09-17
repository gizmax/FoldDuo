package cz.pflanzer.foldduo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeModesTest {
    // --- migration: a layout saved before B39 has neither hiddenPages nor modes. ---

    @Test fun `a layout without hiddenPages defaults to none hidden`() {
        assertEquals(emptySet<Int>(), HomeLayout(listOf("a"), emptyList()).hiddenPages)
    }

    @Test fun `an empty or absent modes array migrates to just the built-in Vše mode`() {
        assertEquals(listOf(HomeMode(ALL_MODE_ID, "Vše")), JSONArray().toHomeModes())
        assertEquals(listOf(HomeMode(ALL_MODE_ID, "Vše")), defaultHomeModes())
    }

    @Test fun `a saved modes array missing the built-in mode gets it prepended`() {
        val saved = JSONArray().put(HomeMode("work", "Práce", visiblePages = setOf(0, 1)).toJson())
        val loaded = saved.toHomeModes()
        assertEquals(listOf(ALL_MODE_ID, "work"), loaded.map { it.id })
    }

    @Test fun `a mode round-trips through json including its schedule`() {
        val mode = HomeMode("evening", "Večer", icon = "moon", visiblePages = setOf(0, 2),
            schedule = ModeSchedule(setOf(1, 2, 3, 4, 5), 22 * 60, 6 * 60), dndTrigger = true)
        val restored = JSONObject(mode.toJson().toString()).toHomeMode()
        assertEquals(mode, restored)
    }

    @Test fun `a mode without a schedule round-trips with schedule null`() {
        val mode = HomeMode("home", "Doma")
        assertNull(JSONObject(mode.toJson().toString()).toHomeMode().schedule)
    }

    // --- effectiveHiddenPages: manual hides plus the active mode's complement, clamped to range. ---

    @Test fun `Vše never hides a page regardless of stale visiblePages`() {
        val modes = listOf(HomeMode(ALL_MODE_ID, "Vše", visiblePages = setOf(0)))
        assertEquals(emptySet<Int>(), effectiveHiddenPages(4, emptySet(), modes, ALL_MODE_ID))
    }

    @Test fun `a custom mode hides every page it does not list, on top of manual hides`() {
        val modes = listOf(HomeMode(ALL_MODE_ID, "Vše"), HomeMode("work", "Práce", visiblePages = setOf(0, 2)))
        assertEquals(setOf(1, 3), effectiveHiddenPages(4, emptySet(), modes, "work"))
        assertEquals(setOf(1, 2, 3), effectiveHiddenPages(4, setOf(2), modes, "work"))
    }

    @Test fun `effectiveHiddenPages clamps to the current page count so a stale mode cannot hide a removed page`() {
        val modes = listOf(HomeMode(ALL_MODE_ID, "Vše"), HomeMode("work", "Práce", visiblePages = setOf(0, 5)))
        assertEquals(setOf(1, 2), effectiveHiddenPages(3, emptySet(), modes, "work"))
    }

    @Test fun `an unknown active mode id behaves like Vše`() {
        val modes = listOf(HomeMode(ALL_MODE_ID, "Vše"), HomeMode("work", "Práce", visiblePages = setOf(0)))
        assertEquals(emptySet<Int>(), effectiveHiddenPages(3, emptySet(), modes, "gone"))
    }

    // --- ModeScheduler: weekday + time windows, including overnight wrap. ---

    @Test fun `a same-day window is active only on its weekdays within its minutes`() {
        val schedule = ModeSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60) // Mon-Fri 09:00-17:00
        assertTrue(ModeScheduler.isActive(schedule, 3, 9 * 60))
        assertTrue(ModeScheduler.isActive(schedule, 3, 16 * 60 + 59))
        assertFalse(ModeScheduler.isActive(schedule, 3, 17 * 60)) // end is exclusive
        assertFalse(ModeScheduler.isActive(schedule, 3, 8 * 60 + 59))
        assertFalse(ModeScheduler.isActive(schedule, 6, 10 * 60)) // Saturday
    }

    @Test fun `an overnight window wraps past midnight onto the next listed weekday`() {
        val schedule = ModeSchedule(setOf(1), 22 * 60, 6 * 60) // Monday 22:00 -> Tuesday 06:00
        assertTrue(ModeScheduler.isActive(schedule, 1, 23 * 60)) // Monday night
        assertTrue(ModeScheduler.isActive(schedule, 2, 0)) // just past midnight, into Tuesday
        assertTrue(ModeScheduler.isActive(schedule, 2, 5 * 60 + 59))
        assertFalse(ModeScheduler.isActive(schedule, 2, 6 * 60)) // end is exclusive
        assertFalse(ModeScheduler.isActive(schedule, 3, 23 * 60)) // neither Wednesday nor Tuesday (its "yesterday") is a listed start day
    }

    @Test fun `an overnight window wraps Sunday into Monday correctly`() {
        val schedule = ModeSchedule(setOf(7), 23 * 60, 60) // Sunday 23:00 -> Monday 01:00
        assertTrue(ModeScheduler.isActive(schedule, 1, 30)) // Monday just after midnight
        assertFalse(ModeScheduler.isActive(schedule, 1, 61))
    }

    @Test fun `scheduledMode picks the first matching non-Vše mode and null when none matches`() {
        val work = HomeMode("work", "Práce", schedule = ModeSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60))
        val evening = HomeMode("evening", "Večer", schedule = ModeSchedule(ALL_WEEKDAYS, 22 * 60, 6 * 60))
        val modes = listOf(HomeMode(ALL_MODE_ID, "Vše"), work, evening)
        assertEquals("work", ModeScheduler.scheduledMode(modes, 3, 10 * 60)?.id)
        assertEquals("evening", ModeScheduler.scheduledMode(modes, 3, 23 * 60)?.id)
        assertNull(ModeScheduler.scheduledMode(modes, 3, 19 * 60))
        assertNull(ModeScheduler.scheduledMode(listOf(HomeMode(ALL_MODE_ID, "Vše")), 3, 10 * 60))
    }

    // --- DndModeSignal: the ALL -> not-ALL edge, and picking the flagged mode. ---

    @Test fun `turnedOn fires only on the ALL to not-ALL transition`() {
        val all = DndModeSignal.INTERRUPTION_FILTER_ALL
        assertTrue(DndModeSignal.turnedOn(all, 2))
        assertFalse(DndModeSignal.turnedOn(2, all)) // turning off
        assertFalse(DndModeSignal.turnedOn(2, 3)) // already on, changed filter
        assertFalse(DndModeSignal.turnedOn(all, all)) // no-op repeat
    }

    @Test fun `modeFor picks the first dnd-flagged mode or null`() {
        val modes = listOf(HomeMode(ALL_MODE_ID, "Vše"), HomeMode("work", "Práce"), HomeMode("focus", "Focus", dndTrigger = true))
        assertEquals("focus", DndModeSignal.modeFor(modes)?.id)
        assertNull(DndModeSignal.modeFor(listOf(HomeMode(ALL_MODE_ID, "Vše"), HomeMode("work", "Práce"))))
    }

    // --- 2026-09-17 noc "Mazání stránek s dotazem": remapping mode page sets after a page delete. ---

    @Test fun `remapModesAfterPageRemoval drops the deleted page and shifts later ones down`() {
        val modes = listOf(
            HomeMode(ALL_MODE_ID, "Vše", visiblePages = setOf(0, 1, 2, 3)),
            HomeMode("work", "Práce", visiblePages = setOf(1, 2)),
            HomeMode("empty", "Nic", visiblePages = emptySet()),
        )
        val remapped = remapModesAfterPageRemoval(modes, 1)
        // ALL_MODE_ID's stored set is never actually read, but remapping it too is harmless.
        assertEquals(setOf(0, 1, 2), remapped.first { it.id == ALL_MODE_ID }.visiblePages)
        // Page 1 itself drops out; page 2 (kept the folder) shifts down to 1.
        assertEquals(setOf(1), remapped.first { it.id == "work" }.visiblePages)
        assertEquals(emptySet<Int>(), remapped.first { it.id == "empty" }.visiblePages)
    }

    @Test fun `remapModesAfterPageRemoval leaves pages before the deleted one untouched`() {
        val modes = listOf(HomeMode("early", "Early", visiblePages = setOf(0)))
        assertEquals(setOf(0), remapModesAfterPageRemoval(modes, 2).first().visiblePages)
    }
}
