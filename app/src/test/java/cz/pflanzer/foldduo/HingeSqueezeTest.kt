package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B48 "Pant jako ovladač": [squeezeOverlayProgress]'s depth (deg) -> overlay reveal (0..1) mapping
 * in isolation — IDEAS.md's "depth 6-20 deg = peek 30%, > 20 deg = full", continuous in between so
 * the overlay can follow the hand while held.
 */
class HingeSqueezeTest {
    @Test fun `below the peek start reports fully hidden`() {
        assertEquals(0f, squeezeOverlayProgress(0f), 0f)
        assertEquals(0f, squeezeOverlayProgress(3f), 0f)
        assertEquals(0f, squeezeOverlayProgress(SQUEEZE_PEEK_START_DEG), 0f) // 6 deg itself: still hidden
    }

    @Test fun `just past the peek start reports about the peek fraction`() {
        val progress = squeezeOverlayProgress(SQUEEZE_PEEK_START_DEG + 0.01f)
        assertTrue("expected close to $SQUEEZE_PEEK_PROGRESS, was $progress", progress in 0.29f..0.31f)
    }

    @Test fun `progress rises continuously between peek start and full`() {
        val mid = squeezeOverlayProgress((SQUEEZE_PEEK_START_DEG + SQUEEZE_FULL_DEG) / 2f)
        assertEquals(SQUEEZE_PEEK_PROGRESS + (1f - SQUEEZE_PEEK_PROGRESS) / 2f, mid, 0.01f)
        val lower = squeezeOverlayProgress(SQUEEZE_PEEK_START_DEG + 1f)
        val higher = squeezeOverlayProgress(SQUEEZE_PEEK_START_DEG + 5f)
        assertTrue(higher > lower)
    }

    @Test fun `at and past full depth reports fully open`() {
        assertEquals(1f, squeezeOverlayProgress(SQUEEZE_FULL_DEG), 0f)
        assertEquals(1f, squeezeOverlayProgress(SQUEEZE_FULL_DEG + 10f), 0f)
        assertEquals(1f, squeezeOverlayProgress(HingeSqueezeMaxDepthForTest), 0f)
    }

    /** Mirrors `pose.HingeSqueezeDetector.MAX_DEPTH_DEG` without a `:pose` test dependency — the
     * overlay must clamp to fully open at (and beyond) the detector's own cap, never past it. */
    private val HingeSqueezeMaxDepthForTest = 40f
}
