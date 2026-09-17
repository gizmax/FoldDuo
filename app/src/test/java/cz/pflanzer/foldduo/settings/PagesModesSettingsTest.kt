package cz.pflanzer.foldduo.settings

import cz.pflanzer.foldduo.ModeSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure formatting for the Pages & modes page's schedule summary line — no Android types. */
class PagesModesSettingsTest {
    @Test fun `no schedule reads as manual-only`() {
        assertEquals("No schedule — switch manually from the page overview", formatModeSchedule(null))
    }

    @Test fun `every weekday collapses to Every day`() {
        val schedule = ModeSchedule((1..7).toSet(), 9 * 60, 17 * 60)
        assertEquals("Every day, 09:00–17:00", formatModeSchedule(schedule))
    }

    @Test fun `a partial weekday set lists short day names in order`() {
        val schedule = ModeSchedule(setOf(6, 7, 1), 22 * 60, 6 * 60)
        assertEquals("Mon, Sat, Sun, 22:00–06:00", formatModeSchedule(schedule))
    }
}
