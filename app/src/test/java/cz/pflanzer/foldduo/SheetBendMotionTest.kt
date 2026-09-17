package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.pose.HingeStepGate
import cz.pflanzer.foldduo.pose.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B37, "Tapeta jako ohýbaný list": [SheetBendMotion]'s hinge-to-bend mapping and per-panel plan. */
class SheetBendMotionTest {
    @Test fun `NaN hinge angle reads as flat`() {
        assertEquals(0f, SheetBendMotion.bendForHinge(Float.NaN), 0f)
    }

    @Test fun `flat and past-flat angles are dead flat`() {
        assertEquals(0f, SheetBendMotion.bendForHinge(MorphCurve.ANGLE_FLAT), 1e-4f)
        // 180 is what HingeStepGate reports once the step settles at Flat, past ANGLE_FLAT (172).
        assertEquals(0f, SheetBendMotion.bendForHinge(180f), 0f)
    }

    @Test fun `the swap angle bends the full amount`() {
        assertEquals(SheetBendMotion.MAX_BEND_DEG, SheetBendMotion.bendForHinge(MorphCurve.ANGLE_PANEL_ON), 1e-4f)
    }

    @Test fun `bend decreases monotonically from the swap to flat`() {
        var previous = SheetBendMotion.bendForHinge(MorphCurve.ANGLE_PANEL_ON)
        var angle = MorphCurve.ANGLE_PANEL_ON + 1f
        while (angle <= MorphCurve.ANGLE_FLAT) {
            val bend = SheetBendMotion.bendForHinge(angle)
            assertTrue("bend should not increase past $angle° (was $previous, now $bend)", bend <= previous + 1e-4f)
            previous = bend
            angle += 1f
        }
    }

    @Test fun `165 degrees, inside HingeStepGate's mid band, gives a small positive bend`() {
        assertTrue(165f <= HingeStepGate.MAX_MID)
        val bend = SheetBendMotion.bendForHinge(165f)
        assertTrue("expected a small positive bend, got $bend", bend > 0f && bend < 10f)
    }

    @Test fun `20 degrees, HingeStepGate's other mid-band bound, saturates at the max bend`() {
        assertTrue(20f >= HingeStepGate.MIN_MID)
        // Below ANGLE_PANEL_ON (90°) the progress fraction clamps to 1, same as the swap itself.
        assertEquals(SheetBendMotion.MAX_BEND_DEG, SheetBendMotion.bendForHinge(20f), 1e-4f)
    }

    @Test fun `only the inner panel bends`() {
        assertEquals(0f, SheetBendMotion.bendForPanel(Panel.Cover, MorphCurve.ANGLE_PANEL_ON), 0f)
        assertEquals(0f, SheetBendMotion.bendForPanel(Panel.Unknown, MorphCurve.ANGLE_PANEL_ON), 0f)
        assertEquals(SheetBendMotion.bendForHinge(MorphCurve.ANGLE_PANEL_ON),
            SheetBendMotion.bendForPanel(Panel.Inner, MorphCurve.ANGLE_PANEL_ON), 0f)
    }

    @Test fun `the derived plan centres the hinge and never bends the cover`() {
        val inner = SheetBendMotion.planFor(Panel.Inner, widthPx = 2448f, hingeDeg = MorphCurve.ANGLE_PANEL_ON)
        assertEquals(1224f, inner.hingeX, 0f)
        assertEquals(SheetBendMotion.MAX_BEND_DEG, inner.bendDeg, 1e-4f)

        val cover = SheetBendMotion.planFor(Panel.Cover, widthPx = 1248f, hingeDeg = MorphCurve.ANGLE_PANEL_ON)
        assertEquals(0f, cover.bendDeg, 0f)

        val coverAtFlat = SheetBendMotion.planFor(Panel.Cover, widthPx = 1248f, hingeDeg = MorphCurve.ANGLE_FLAT)
        assertEquals(0f, coverAtFlat.bendDeg, 0f)
    }
}
