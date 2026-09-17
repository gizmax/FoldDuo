package cz.pflanzer.foldduo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B36 rate limiting: [HapticRateLimiter] never lets two haptics through less than
 * [HAPTIC_MIN_INTERVAL_MS] apart, measured from the last *allowed* call (not the last attempt).
 */
class HapticRateLimiterTest {

    @Test fun `the very first call is always allowed`() {
        assertTrue(HapticRateLimiter().allow(0L))
    }

    @Test fun `a call inside the window right after the first is rejected`() {
        val limiter = HapticRateLimiter()
        limiter.allow(1_000L)
        assertFalse(limiter.allow(1_000L + HAPTIC_MIN_INTERVAL_MS - 1))
    }

    @Test fun `a call exactly at the window boundary is allowed`() {
        val limiter = HapticRateLimiter()
        limiter.allow(1_000L)
        assertTrue(limiter.allow(1_000L + HAPTIC_MIN_INTERVAL_MS))
    }

    @Test fun `a rejected call does not reset the window`() {
        val limiter = HapticRateLimiter()
        limiter.allow(0L)
        assertFalse(limiter.allow(30L))
        // Still measured from t=0, not t=30: 59 < 60 since the last *allowed* call.
        assertFalse(limiter.allow(59L))
        assertTrue(limiter.allow(60L))
    }

    @Test fun `a custom interval is honoured`() {
        val limiter = HapticRateLimiter(minIntervalMs = 200L)
        limiter.allow(0L)
        assertFalse(limiter.allow(199L))
        assertTrue(limiter.allow(200L))
    }

    @Test fun `the very first call at a very negative time does not overflow and is still allowed`() {
        // Regression for the class of bug STATUS.md's hinge-angle gate hit (`now - Long.MIN_VALUE`
        // overflowing): the limiter's initial lastFiredAt is MIN_VALUE / 2, so even a caller
        // passing a clock value far in the past on the first call must not throw or misbehave.
        assertTrue(HapticRateLimiter().allow(Long.MIN_VALUE / 4))
    }
}
