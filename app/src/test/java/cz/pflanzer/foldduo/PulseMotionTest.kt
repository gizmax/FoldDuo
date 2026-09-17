package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B50 "Tapeta dýchá s oznámením": [PulseMotion]'s progress -> spread/opacity curve, the pure mirror of edge_pulse.agsl's own maths. */
class PulseMotionTest {
    // --- progressAt ----------------------------------------------------------------------------

    @Test fun `progress is zero right when the pulse fires`() {
        assertEquals(0f, PulseMotion.progressAt(atMs = 1_000L, nowMs = 1_000L), 0f)
    }

    @Test fun `progress is one once the full duration has elapsed`() {
        assertEquals(1f, PulseMotion.progressAt(atMs = 0L, nowMs = PulseMotion.DURATION_MS), 0f)
    }

    @Test fun `progress past the duration clamps at one, never overshoots`() {
        assertEquals(1f, PulseMotion.progressAt(atMs = 0L, nowMs = PulseMotion.DURATION_MS * 10), 0f)
    }

    @Test fun `progress is proportional partway through`() {
        val half = PulseMotion.progressAt(atMs = 0L, nowMs = PulseMotion.DURATION_MS / 2)
        assertEquals(0.5f, half, 0.01f)
    }

    @Test fun `a pulse timestamp in the future clamps to zero, not negative`() {
        assertEquals(0f, PulseMotion.progressAt(atMs = 10_000L, nowMs = 0L), 0f)
    }

    // --- spreadFraction --------------------------------------------------------------------------

    @Test fun `spread starts at zero`() {
        assertEquals(0f, PulseMotion.spreadFraction(0f), 0f)
    }

    @Test fun `spread reaches its max fraction exactly at the peak progress`() {
        assertEquals(PulseMotion.MAX_SPREAD_FRACTION, PulseMotion.spreadFraction(PulseMotion.PEAK_PROGRESS), 1e-4f)
    }

    @Test fun `spread holds at the max fraction after the peak, it never recedes`() {
        assertEquals(PulseMotion.MAX_SPREAD_FRACTION, PulseMotion.spreadFraction(0.6f), 1e-4f)
        assertEquals(PulseMotion.MAX_SPREAD_FRACTION, PulseMotion.spreadFraction(1f), 1e-4f)
    }

    @Test fun `spread rises monotonically up to the peak`() {
        var previous = 0f
        var p = 0f
        while (p <= PulseMotion.PEAK_PROGRESS) {
            val spread = PulseMotion.spreadFraction(p)
            assertTrue("spread should not decrease at $p (was $previous, now $spread)", spread >= previous - 1e-6f)
            previous = spread
            p += 0.01f
        }
    }

    @Test fun `spread never exceeds the max fraction`() {
        var p = 0f
        while (p <= 1f) {
            assertTrue(PulseMotion.spreadFraction(p) <= PulseMotion.MAX_SPREAD_FRACTION + 1e-6f)
            p += 0.05f
        }
    }

    // --- opacity ---------------------------------------------------------------------------------

    @Test fun `opacity starts at zero`() {
        assertEquals(0f, PulseMotion.opacity(0f), 0f)
    }

    @Test fun `opacity peaks at the max opacity exactly at the peak progress`() {
        assertEquals(PulseMotion.MAX_OPACITY, PulseMotion.opacity(PulseMotion.PEAK_PROGRESS), 1e-4f)
    }

    @Test fun `opacity fades back to zero by progress one`() {
        assertEquals(0f, PulseMotion.opacity(1f), 1e-4f)
    }

    @Test fun `opacity never exceeds the max opacity`() {
        var p = 0f
        while (p <= 1f) {
            assertTrue(PulseMotion.opacity(p) <= PulseMotion.MAX_OPACITY + 1e-6f)
            p += 0.02f
        }
    }

    @Test fun `opacity rises before the peak and falls after it`() {
        val beforePeak = PulseMotion.opacity(PulseMotion.PEAK_PROGRESS - 0.1f)
        val atPeak = PulseMotion.opacity(PulseMotion.PEAK_PROGRESS)
        val afterPeak = PulseMotion.opacity(PulseMotion.PEAK_PROGRESS + 0.2f)
        assertTrue(beforePeak < atPeak)
        assertTrue(afterPeak < atPeak)
    }

    @Test fun `out-of-range progress is clamped the same as in range`() {
        assertEquals(PulseMotion.opacity(0f), PulseMotion.opacity(-1f), 0f)
        assertEquals(PulseMotion.opacity(1f), PulseMotion.opacity(2f), 0f)
        assertEquals(PulseMotion.spreadFraction(0f), PulseMotion.spreadFraction(-1f), 0f)
        assertEquals(PulseMotion.spreadFraction(1f), PulseMotion.spreadFraction(2f), 0f)
    }
}
