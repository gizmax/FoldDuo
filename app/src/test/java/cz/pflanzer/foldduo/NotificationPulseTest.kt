package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.notifications.PULSE_COLOR_UNSET
import cz.pflanzer.foldduo.notifications.PULSE_MIN_INTERVAL_MS
import cz.pflanzer.foldduo.notifications.choosePulseColorArgb
import cz.pflanzer.foldduo.notifications.shouldEmitPulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B50 "Tapeta dýchá s oznámením": [shouldEmitPulse]'s rate limiting/coalescing and [choosePulseColorArgb]'s fallback order. */
class NotificationPulseTest {
    // --- shouldEmitPulse -------------------------------------------------------------------------

    @Test fun `the first pulse always fires`() {
        assertTrue(shouldEmitPulse(lastEmittedAtMs = null, nowMs = 0L))
        assertTrue(shouldEmitPulse(lastEmittedAtMs = null, nowMs = 123_456L))
    }

    @Test fun `a second pulse inside the minimum interval coalesces (does not fire)`() {
        assertFalse(shouldEmitPulse(lastEmittedAtMs = 1_000L, nowMs = 1_000L + PULSE_MIN_INTERVAL_MS - 1))
        assertFalse(shouldEmitPulse(lastEmittedAtMs = 1_000L, nowMs = 1_050L))
    }

    @Test fun `a pulse exactly at the minimum interval fires`() {
        assertTrue(shouldEmitPulse(lastEmittedAtMs = 1_000L, nowMs = 1_000L + PULSE_MIN_INTERVAL_MS))
    }

    @Test fun `a pulse well past the minimum interval fires`() {
        assertTrue(shouldEmitPulse(lastEmittedAtMs = 1_000L, nowMs = 1_000L + PULSE_MIN_INTERVAL_MS * 10))
    }

    @Test fun `a burst of arrivals only ever pulses on the first one`() {
        var lastEmitted: Long? = null
        var emitted = 0
        val arrivals = listOf(0L, 100L, 300L, 900L, 1_400L) // all inside one 1.5 s window
        for (at in arrivals) {
            if (shouldEmitPulse(lastEmitted, at)) { lastEmitted = at; emitted++ }
        }
        assertEquals(1, emitted)
        assertEquals(0L, lastEmitted)
    }

    @Test fun `two bursts a window apart each pulse once`() {
        var lastEmitted: Long? = null
        var emitted = 0
        val arrivals = listOf(0L, 200L, PULSE_MIN_INTERVAL_MS + 50L, PULSE_MIN_INTERVAL_MS + 300L)
        for (at in arrivals) {
            if (shouldEmitPulse(lastEmitted, at)) { lastEmitted = at; emitted++ }
        }
        assertEquals(2, emitted)
    }

    // --- choosePulseColorArgb --------------------------------------------------------------------

    private val NOTIFICATION_COLOR = 0xFFAA2233.toInt()
    private val ICON_COLOR = 0xFF33AA55.toInt()
    private val PALETTE_COLOR = 0xFF5566AA.toInt()

    @Test fun `the notification's own colour wins when it set one`() {
        assertEquals(NOTIFICATION_COLOR, choosePulseColorArgb(NOTIFICATION_COLOR, ICON_COLOR, PALETTE_COLOR))
    }

    @Test fun `the icon's dominant colour is used when the notification never set one`() {
        assertEquals(ICON_COLOR, choosePulseColorArgb(PULSE_COLOR_UNSET, ICON_COLOR, PALETTE_COLOR))
    }

    @Test fun `the wallpaper palette accent is the last resort`() {
        assertEquals(PALETTE_COLOR, choosePulseColorArgb(PULSE_COLOR_UNSET, null, PALETTE_COLOR))
    }

    @Test fun `an unset notification colour is exactly PULSE_COLOR_UNSET, zero`() {
        assertEquals(0, PULSE_COLOR_UNSET)
    }
}
