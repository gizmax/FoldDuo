package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParallaxModelTest {
    /** A model already primed with a "held normally" baseline (gravity 0,0) at t=0. */
    private fun primedModel(maxOffsetDp: Float = ParallaxModel.MAX_OFFSET_COVER_DP): ParallaxModel {
        val model = ParallaxModel(maxOffsetDp)
        model.update(0L, 0f, 0f)
        return model
    }

    /** Feeds a constant (gx, gy) in small steps, like real sensor/frame samples, from [fromMs]. */
    private fun ParallaxModel.tilt(gx: Float, gy: Float, fromMs: Long, totalMs: Long, stepMs: Long = 50L) {
        var t = fromMs
        while (t <= fromMs + totalMs) {
            update(t, gx, gy)
            t += stepMs
        }
    }

    @Test fun deadZoneAbsorbsSmallTilt() {
        val model = primedModel()
        // Under the +/-0.3 m/s^2 dead zone throughout, so it never matters that the rest point lags.
        model.tilt(0.2f, -0.25f, fromMs = 50L, totalMs = 5_000L)
        assertEquals(0f, model.offsetDpX, 1e-4f)
        assertEquals(0f, model.offsetDpY, 1e-4f)
    }

    @Test fun signTiltLeftMovesWallpaperRight() {
        val model = primedModel()
        // Short hold: gravity has been low-passed toward -2 (tau 250 ms) but the rest point (tau
        // 3 s) has barely moved off its baseline, so this reads as a fresh leftward roll.
        model.tilt(-2f, 0f, fromMs = 50L, totalMs = 400L)
        assertTrue("expected a positive x offset, got ${model.offsetDpX}", model.offsetDpX > 0f)
    }

    @Test fun signTiltRightMovesWallpaperLeft() {
        val model = primedModel()
        model.tilt(2f, 0f, fromMs = 50L, totalMs = 400L)
        assertTrue("expected a negative x offset, got ${model.offsetDpX}", model.offsetDpX < 0f)
    }

    @Test fun signAppliesTheSameWayToY() {
        val model = primedModel(ParallaxModel.MAX_OFFSET_INNER_DP)
        model.tilt(0f, -2f, fromMs = 50L, totalMs = 400L)
        assertTrue("expected a positive y offset, got ${model.offsetDpY}", model.offsetDpY > 0f)
    }

    @Test fun clampsToMaxOffset() {
        val model = primedModel()
        // Far beyond saturation; the low-pass fully catches up well before the rest point does.
        model.tilt(-100f, 100f, fromMs = 50L, totalMs = 800L)
        assertEquals(ParallaxModel.MAX_OFFSET_COVER_DP, model.offsetDpX, 1e-3f)
        assertEquals(-ParallaxModel.MAX_OFFSET_COVER_DP, model.offsetDpY, 1e-3f)
    }

    @Test fun restPointRecentresAndOffsetDecaysBack() {
        val model = primedModel()
        model.tilt(-2f, 0f, fromMs = 50L, totalMs = 500L)
        val earlyOffset = model.offsetDpX
        assertTrue("expected a non-zero offset shortly after tilting", earlyOffset > 0f)
        // Held for many multiples of the 3 s recentre time constant: the rest point should have
        // caught up to the held gravity, so the offset decays back toward the (new) centre.
        model.tilt(-2f, 0f, fromMs = 550L, totalMs = 30_000L, stepMs = 200L)
        val lateOffset = model.offsetDpX
        assertTrue("expected the offset to decay from $earlyOffset, got $lateOffset", abs(lateOffset) < abs(earlyOffset) / 4f)
    }

    @Test fun resetForgetsTheRestPoint() {
        val model = primedModel()
        model.tilt(-2f, -2f, fromMs = 50L, totalMs = 400L)
        assertTrue(model.offsetDpX != 0f)
        model.reset()
        assertEquals(0f, model.offsetDpX, 1e-6f)
        assertEquals(0f, model.offsetDpY, 1e-6f)
        // The next sample becomes the new rest point immediately, so it reads as centred.
        model.update(1_000L, -2f, -2f)
        assertEquals(0f, model.offsetDpX, 1e-6f)
    }

    @Test fun overscaleMatchesOnePlusTwiceMaxOverWidth() {
        assertEquals(1f + 16f / 400f, ParallaxModel.overscale(8f, 400f), 1e-5f)
        assertEquals(1f + 24f / 600f, ParallaxModel.overscale(12f, 600f), 1e-5f)
    }

    @Test fun overscaleIsNoOpForNonPositiveWidth() {
        assertEquals(1f, ParallaxModel.overscale(8f, 0f), 1e-6f)
        assertEquals(1f, ParallaxModel.overscale(8f, -5f), 1e-6f)
    }
}
