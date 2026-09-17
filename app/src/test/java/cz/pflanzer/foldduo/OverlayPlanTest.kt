package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlanTest {
    @Test fun `full bounds cover the whole display, anchored top-left`() {
        val b = OverlayPlan.bounds(2448, 1848, leftHalf = false)
        assertEquals(0, b.leftPx)
        assertEquals(0, b.topPx)
        assertEquals(2448, b.widthPx)
        assertEquals(1848, b.heightPx)
    }

    @Test fun `left-half bounds are half the width, full height`() {
        val b = OverlayPlan.bounds(2448, 1848, leftHalf = true)
        assertEquals(0, b.leftPx)
        assertEquals(0, b.topPx)
        assertEquals(1224, b.widthPx)
        assertEquals(1848, b.heightPx)
    }

    @Test fun `blur ramps 0 to max over 400 ms, holds, then ramps back to 0`() {
        val ms = 3000L
        val maxBlur = 40
        assertEquals(0, OverlayPlan.blurRadiusAt(0, ms, maxBlur))
        assertEquals(maxBlur / 2, OverlayPlan.blurRadiusAt(200, ms, maxBlur))
        assertEquals(maxBlur, OverlayPlan.blurRadiusAt(400, ms, maxBlur))
        assertEquals(maxBlur, OverlayPlan.blurRadiusAt(1500, ms, maxBlur))
        assertEquals(maxBlur, OverlayPlan.blurRadiusAt(2600, ms, maxBlur))
        assertEquals(maxBlur / 2, OverlayPlan.blurRadiusAt(2800, ms, maxBlur))
        assertEquals(0, OverlayPlan.blurRadiusAt(3000, ms, maxBlur))
    }

    @Test fun `a short duration shrinks the ramp so it still goes both ways`() {
        val ms = 300L
        assertEquals(150L, OverlayPlan.rampMs(ms))
        assertEquals(0, OverlayPlan.blurRadiusAt(0, ms, 40))
        assertEquals(40, OverlayPlan.blurRadiusAt(150, ms, 40))
        assertEquals(0, OverlayPlan.blurRadiusAt(300, ms, 40))
    }

    @Test fun `out-of-range elapsed time is clamped, never negative or beyond max`() {
        assertEquals(0, OverlayPlan.blurRadiusAt(-100, 3000, 40))
        assertEquals(0, OverlayPlan.blurRadiusAt(5000, 3000, 40))
        assertEquals(0, OverlayPlan.blurRadiusAt(1000, 0, 40))
        assertEquals(0, OverlayPlan.blurRadiusAt(1000, 3000, 0))
    }
}
