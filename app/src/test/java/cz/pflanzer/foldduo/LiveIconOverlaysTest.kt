package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class LiveIconOverlaysTest {
    // --- package -> overlay mapping --------------------------------------------------------

    @Test fun mapsKnownClockPackagesToClockKind() {
        assertEquals(LiveIconKind.Clock, liveIconKindFor("com.sec.android.app.clockpackage"))
        assertEquals(LiveIconKind.Clock, liveIconKindFor("com.google.android.deskclock"))
    }

    @Test fun mapsKnownCalendarPackagesToCalendarKind() {
        assertEquals(LiveIconKind.Calendar, liveIconKindFor("com.samsung.android.calendar"))
        assertEquals(LiveIconKind.Calendar, liveIconKindFor("com.google.android.calendar"))
    }

    @Test fun mapsKnownBatteryAndMapsPackages() {
        assertEquals(LiveIconKind.Battery, liveIconKindFor("com.samsung.android.lool"))
        assertEquals(LiveIconKind.Maps, liveIconKindFor("com.google.android.apps.maps"))
    }

    @Test fun unknownPackageHasNoOverlay() {
        assertNull(liveIconKindFor("com.example.notepad"))
        assertNull(liveIconKindFor(""))
        // Weather has no overlay: the launcher has no weather data source of its own (see the task).
        assertNull(liveIconKindFor("com.sec.android.daemonapp"))
    }

    // --- calendar overlay gating -------------------------------------------------------------

    @Test fun calendarOverlaySkippedForUntouchedSystemArtwork() {
        assertFalse(showsCalendarOverlay(IconStyle(pack = null, effect = IconEffect.None)))
    }

    @Test fun calendarOverlayShownWhenPackOrEffectReplacedTheArtwork() {
        assertTrue(showsCalendarOverlay(IconStyle(pack = "com.example.pack", effect = IconEffect.None)))
        assertTrue(showsCalendarOverlay(IconStyle(pack = null, effect = IconEffect.Glass)))
        assertTrue(showsCalendarOverlay(IconStyle(pack = null, effect = IconEffect.ClearGlass)))
    }

    // --- clock hand angles ---------------------------------------------------------------

    @Test fun clockOverlayAnglesMatchHandAnglesWhenMoving() {
        assertEquals(handAngles(10, 24, 36), clockOverlayAngles(10, 24, 36, reduceMotion = false))
    }

    @Test fun clockOverlayAnglesPinTheSecondHandUnderReduceMotion() {
        val angles = clockOverlayAngles(10, 24, 36, reduceMotion = true)
        assertEquals(handAngles(10, 24, 0), angles)
        assertEquals(0f, angles.second, 0f)
    }

    // --- battery ring fraction -------------------------------------------------------------

    @Test fun batteryOverlayFractionMapsZeroToOneHundredOntoZeroToOne() {
        assertEquals(0f, batteryOverlayFraction(0), 0f)
        assertEquals(.5f, batteryOverlayFraction(50), 1e-3f)
        assertEquals(1f, batteryOverlayFraction(100), 0f)
    }

    @Test fun batteryOverlayFractionClampsOutOfRangeLevels() {
        assertEquals(0f, batteryOverlayFraction(-5), 0f)
        assertEquals(1f, batteryOverlayFraction(140), 0f)
    }

    // --- calendar label formatting per locale -----------------------------------------------

    @Test fun calendarOverlayWeekdayIsUppercaseInEnglish() {
        // 2026-09-21 is a Monday.
        assertEquals("MON", calendarOverlayWeekday(LocalDate.of(2026, 9, 21), Locale.US))
    }

    @Test fun calendarOverlayWeekdayFollowsTheGivenLocale() {
        val monday = LocalDate.of(2026, 9, 21)
        val czech = calendarOverlayWeekday(monday, Locale("cs", "CZ"))
        assertTrue("expected the Czech short form, got '$czech'", czech.isNotBlank())
        assertEquals(czech.uppercase(Locale("cs", "CZ")), czech)
        // Different locales are free to disagree, but each must format consistently with itself.
        assertEquals(calendarOverlayWeekday(monday, Locale.US), calendarOverlayWeekday(monday, Locale.US))
    }

    @Test fun calendarOverlayWeekdayChangesWithTheDate() {
        val monday = calendarOverlayWeekday(LocalDate.of(2026, 9, 21), Locale.US)
        val tuesday = calendarOverlayWeekday(LocalDate.of(2026, 9, 22), Locale.US)
        assertEquals("MON", monday)
        assertEquals("TUE", tuesday)
    }
}
