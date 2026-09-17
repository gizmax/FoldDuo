package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test

/** IDEAS B23/B24: launch-zoom return guard, overscroll resistance, and the tuned page-snap release. */
class LaunchAndPagerMotionTest {

    @Test fun `return guard plays once inside the window`() {
        val guard = LaunchReturnGuard(windowMillis = 3_000L)
        guard.noteLaunch(1_000L)
        assertTrue(guard.consumeReturn(1_500L))
        assertFalse(guard.consumeReturn(1_600L))
    }

    @Test fun `return guard never plays without a launch`() {
        assertFalse(LaunchReturnGuard().consumeReturn(1_000L))
    }

    @Test fun `return guard skips a resume outside the window`() {
        val guard = LaunchReturnGuard(windowMillis = 3_000L)
        guard.noteLaunch(1_000L)
        assertFalse(guard.consumeReturn(5_000L))
        // Still consumed the one shot even though it fell outside the window.
        assertFalse(guard.consumeReturn(1_500L))
    }

    @Test fun `reset drops a pending return`() {
        val guard = LaunchReturnGuard()
        guard.noteLaunch(0L)
        guard.reset()
        assertFalse(guard.consumeReturn(100L))
    }

    @Test fun `rubber band resists but never reverses direction`() {
        assertEquals(0f, rubberBandOverscroll(0f, 400f), 0f)
        assertTrue(rubberBandOverscroll(120f, 400f) in 1f..119f)
        assertTrue(rubberBandOverscroll(-120f, 400f) in -119f..-1f)
    }

    @Test fun `rubber band converges on the limit but never exceeds it`() {
        val limit = 400f
        val near = rubberBandOverscroll(2_000f, limit)
        val farther = rubberBandOverscroll(20_000f, limit)
        assertTrue(near < limit)
        assertTrue(farther < limit)
        assertTrue(farther > near)
    }

    @Test fun `a short low-velocity swipe still turns the page`() {
        // Same shape as WorkspaceEditingTest's releasePage cases, tuned to the lower threshold
        // B24 asks for on the cover pager: a small drag with almost no velocity still commits.
        assertEquals(3, releasePage(2.16f, 2, 8, 50f, threshold = 80f, distanceThreshold = 0.12f))
        assertEquals(2, releasePage(2.05f, 2, 8, 50f, threshold = 80f, distanceThreshold = 0.12f))
    }

    @Test fun `edge overshoot is zero away from the pager's own two true ends`() {
        // A mid-pager anchor's own one-page bound is not a true end: boundedPagePosition already
        // clamps it, so it pages normally instead of resisting.
        assertEquals(0f, edgeOvershootPositions(1.5f, anchor = 2, pageCount = 8), 0f)
        assertEquals(0f, edgeOvershootPositions(-0.4f, anchor = 3, pageCount = 8), 0f)
        assertEquals(0f, edgeOvershootPositions(8.4f, anchor = 3, pageCount = 8), 0f)
    }

    @Test fun `edge overshoot fires only past anchor 0 going negative`() {
        assertEquals(-.3f, edgeOvershootPositions(-.3f, anchor = 0, pageCount = 8), 0f)
        assertEquals(0f, edgeOvershootPositions(.3f, anchor = 0, pageCount = 8), 0f)
        // Anchor 0 requesting past the far end (impossible in one gesture, but stays inert).
        assertEquals(0f, edgeOvershootPositions(9f, anchor = 0, pageCount = 8), 0f)
    }

    @Test fun `edge overshoot fires only past the last page going positive`() {
        val lastPage = 7
        assertEquals(.6f, edgeOvershootPositions(7.6f, anchor = lastPage, pageCount = 8), .0001f)
        assertEquals(0f, edgeOvershootPositions(6.6f, anchor = lastPage, pageCount = 8), 0f)
    }
}
