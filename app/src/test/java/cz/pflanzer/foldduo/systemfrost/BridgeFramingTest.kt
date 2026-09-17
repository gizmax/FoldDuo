package cz.pflanzer.foldduo.systemfrost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeFramingTest {

    @Test fun `equal aspect scales to fit exactly with no offset`() {
        val fit = BridgeFraming.scaleToFill(1000f, 1000f, 500f, 500f)
        assertEquals(0.5f, fit.scale, 1e-4f)
        assertEquals(0f, fit.dx, 1e-4f)
        assertEquals(0f, fit.dy, 1e-4f)
    }

    @Test fun `the cover onto the inner's right half scales by width and crops height`() {
        // Fold 8 sizes: cover 1248x1972 px, inner right half 1224x1848 px (Panels.kt / CONTEXT.md).
        val fit = BridgeFraming.scaleToFill(1248f, 1972f, 1224f, 1848f)
        // width/1248 = 0.9808, height/1972 = 0.9371 — the larger (width) ratio wins.
        assertEquals(1224f / 1248f, fit.scale, 1e-4f)
        assertEquals(0f, fit.dx, 1e-4f) // width matches exactly: no horizontal crop
        assertTrue("expected the taller cover to overflow the pane's height and crop, got dy=${fit.dy}", fit.dy < 0f)
    }

    @Test fun `the inner's right half onto the cover scales by height and crops width`() {
        val fit = BridgeFraming.scaleToFill(1224f, 1848f, 1248f, 1972f)
        assertEquals(1972f / 1848f, fit.scale, 1e-4f)
        assertEquals(0f, fit.dy, 1e-4f)
        assertTrue("expected the wider pane's fill to overflow the cover's width and crop, got dx=${fit.dx}", fit.dx < 0f)
    }

    @Test fun `result always centers the source over the destination`() {
        val fit = BridgeFraming.scaleToFill(200f, 100f, 100f, 100f)
        // scale = max(100/200, 100/100) = 1; scaled source is 200x100, destination 100x100:
        // overflow of 100 on the width, split evenly.
        assertEquals(1f, fit.scale, 1e-4f)
        assertEquals(-50f, fit.dx, 1e-4f)
        assertEquals(0f, fit.dy, 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive size`() {
        BridgeFraming.scaleToFill(0f, 100f, 100f, 100f)
    }
}
