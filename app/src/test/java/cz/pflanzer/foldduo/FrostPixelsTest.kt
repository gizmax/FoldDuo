package cz.pflanzer.foldduo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostPixelsTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun alpha(p: Int) = (p ushr 24) and 0xFF
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private val white = argb(255, 255, 255, 255)
    private val black = argb(255, 0, 0, 0)

    // --- box blur -------------------------------------------------------------------

    @Test fun `a flat picture is unchanged and blurred in place`() {
        val pixels = IntArray(5 * 4) { argb(255, 40, 120, 200) }
        val expected = pixels.copyOf()
        val result = boxBlur3(pixels, 5, 4, passes = 2)
        assertSame(pixels, result)
        assertArrayEquals(expected, result)
    }

    @Test fun `edges clamp so a border keeps its colour instead of darkening`() {
        val pixels = IntArray(6 * 6) { white }
        boxBlur3(pixels, 6, 6)
        assertTrue(pixels.all { it == white })
        // A single black pixel in the corner: clamping repeats it, so the corner stays darker than
        // an interior pixel would with the same neighbour.
        val corner = IntArray(4 * 4) { white }.also { it[0] = black }
        boxBlur3(corner, 4, 4)
        val centre = IntArray(5 * 5) { white }.also { it[2 * 5 + 2] = black }
        boxBlur3(centre, 5, 5)
        assertTrue(red(corner[0]) < red(centre[2 * 5 + 2]))
        // Horizontal pass: (0 + 0 + 255 + 1) / 3 = 85, then vertical: (85 + 85 + 255 + 1) / 3 = 142.
        assertEquals(142, red(corner[0]))
    }

    @Test fun `a step blurs into three columns and total light is conserved away from the edges`() {
        val width = 8
        val pixels = IntArray(width * 3) { i -> if (i % width < 4) black else white }
        boxBlur3(pixels, width, 3)
        val row = (0 until width).map { red(pixels[width + it]) }
        assertEquals(listOf(0, 0, 0, 85, 170, 255, 255, 255), row)
        assertEquals(row, (0 until width).map { red(pixels[it]) })      // rows are alike: vertical clamp
    }

    @Test fun `alpha is averaged like the colour channels, so constant alpha is preserved`() {
        val pixels = IntArray(4 * 4) { i -> argb(200, (i * 16) and 0xFF, 0, 255 - ((i * 16) and 0xFF)) }
        boxBlur3(pixels, 4, 4, passes = 2)
        assertTrue(pixels.all { alpha(it) == 200 })
        assertTrue(pixels.all { green(it) == 0 })
        // A hole of alpha 0 fades into its neighbours rather than staying a hard edge.
        val hole = IntArray(3 * 3) { argb(255, 100, 100, 100) }.also { it[4] = argb(0, 100, 100, 100) }
        boxBlur3(hole, 3, 3)
        assertTrue(alpha(hole[4]) in 1..254)
        assertTrue(alpha(hole[0]) in 1..254)
        assertTrue(hole.all { red(it) == 100 && blue(it) == 100 })
    }

    @Test fun `two passes spread further than one`() {
        fun blurred(passes: Int, at: Int): Int {
            val pixels = IntArray(9 * 1) { white }.also { it[4] = black }
            boxBlur3(pixels, 9, 1, passes)
            return red(pixels[at])
        }
        assertEquals(0, blurred(0, 4))                 // no pass: untouched
        assertEquals(170, blurred(1, 4))               // one pass: 3 px wide
        assertEquals(255, blurred(1, 2))
        assertTrue(blurred(2, 2) < 255)                // two passes: 5 px wide
        assertEquals(255, blurred(2, 1))
    }

    @Test fun `degenerate sizes and short buffers`() {
        assertArrayEquals(IntArray(0), boxBlur3(IntArray(0), 0, 0))
        val one = intArrayOf(argb(255, 9, 8, 7))
        assertEquals(argb(255, 9, 8, 7), boxBlur3(one, 1, 1, passes = 3)[0])
        assertThrows(IllegalArgumentException::class.java) { boxBlur3(IntArray(3), 2, 2) }
    }

    // --- bounds -> backdrop mapping -------------------------------------------------

    @Test fun `placement maps backdrop pixels to window space through the card origin`() {
        // A 2448 x 1848 window drawn full-bleed at (0, 0), backdrop at 1/8: 306 x 231.
        val placement = backdropPlacement(cardLeft = 800f, cardTop = 400f, bgLeft = 0f, bgTop = 0f,
            bgWidth = 2448f, bgHeight = 1848f, backdropWidth = 306, backdropHeight = 231)
        assertEquals(8f, placement.scaleX, 1e-4f)
        assertEquals(8f, placement.scaleY, 1e-4f)
        assertEquals(-800f, placement.offsetX, 1e-4f)
        assertEquals(-400f, placement.offsetY, 1e-4f)
        // The card's own top-left corner samples backdrop pixel (100, 50).
        assertEquals(100f, placement.sourceX(0f), 1e-4f)
        assertEquals(50f, placement.sourceY(0f), 1e-4f)
        // Backdrop pixel (100, 50) drawn through the transform lands at the card's origin.
        assertEquals(0f, 100f * placement.scaleX + placement.offsetX, 1e-4f)
        assertEquals(0f, 50f * placement.scaleY + placement.offsetY, 1e-4f)
    }

    @Test fun `a background offset from the window origin shifts the mapping`() {
        // A status-bar-high content root at y = 120 (not edge-to-edge): the card at window y = 120 is at the top of the backdrop.
        val placement = backdropPlacement(cardLeft = 16f, cardTop = 120f, bgLeft = 0f, bgTop = 120f,
            bgWidth = 1248f, bgHeight = 1972f, backdropWidth = 156, backdropHeight = 246)
        assertEquals(0f, placement.sourceY(0f), 1e-4f)
        assertEquals(2f, placement.sourceX(0f), 1e-4f)
        assertEquals(1248f / 156f, placement.scaleX, 1e-4f)
    }

    @Test fun `the Today pane mid-morph samples the wallpaper under its translated position`() {
        // The pane is 1088 dp wide; at morph progress 0 it drifts SLIDE_FRACTION (4 %) of its
        // width to the left, so a card 500 px in from the pane's left edge stays on the backdrop
        // and samples the wallpaper under its drifted position.
        val paneWidth = 1224f
        val restLeft = 500f
        val driftedLeft = restLeft + MorphCurve.translationX(0f, paneWidth)
        assertTrue(driftedLeft > 0f && driftedLeft < restLeft)
        val rest = backdropCrop(restLeft, 300f, 500f, 250f, 0f, 0f, 2448f, 1848f, 306, 231)
        val drifted = backdropCrop(driftedLeft, 300f, 500f, 250f, 0f, 0f, 2448f, 1848f, 306, 231)
        assertEquals(62.5f, rest.left, 1e-3f)
        assertEquals(62.5f, rest.width, 1e-3f)
        assertEquals(driftedLeft / 8f, drifted.left, 1e-3f)
        assertEquals(rest.width, drifted.width, 1e-3f)
        // A pane pushed past the left edge (a wider slide, or a drag): the crop clamps to the
        // backdrop and narrows, the glass shows the wallpaper edge.
        val movedLeft = restLeft - paneWidth * 0.5f
        assertTrue(movedLeft < 0f && movedLeft + 500f > 0f)
        val moved = backdropCrop(movedLeft, 300f, 500f, 250f, 0f, 0f, 2448f, 1848f, 306, 231)
        assertEquals(0f, moved.left, 1e-3f)
        assertTrue(moved.width < rest.width)
        assertEquals((movedLeft + 500f) / 8f, moved.right, 1e-3f)
        // Fully home (progress 1) equals rest.
        assertEquals(rest, backdropCrop(restLeft + MorphCurve.translationX(1f, paneWidth), 300f, 500f, 250f, 0f, 0f, 2448f, 1848f, 306, 231))
    }

    @Test fun `crop clamps to the backdrop and never inverts`() {
        val outside = backdropCrop(-900f, -900f, 100f, 100f, 0f, 0f, 800f, 600f, 100, 75)
        assertEquals(CropRect(0f, 0f, 0f, 0f), outside)
        val beyond = backdropCrop(790f, 590f, 100f, 100f, 0f, 0f, 800f, 600f, 100, 75)
        assertEquals(98.75f, beyond.left, 1e-3f)
        assertEquals(100f, beyond.right, 1e-3f)
        assertEquals(75f, beyond.bottom, 1e-3f)
        val negative = backdropCrop(10f, 10f, -5f, -5f, 0f, 0f, 800f, 600f, 100, 75)
        assertEquals(0f, negative.width, 1e-3f)
        assertEquals(0f, negative.height, 1e-3f)
        assertThrows(IllegalArgumentException::class.java) { backdropPlacement(0f, 0f, 0f, 0f, 0f, 600f, 100, 75) }
        assertThrows(IllegalArgumentException::class.java) { backdropPlacement(0f, 0f, 0f, 0f, 800f, 600f, 0, 75) }
    }

    @Test fun `downscale and pass constants are the documented ones`() {
        assertEquals(8, FROST_DOWNSCALE)
        assertEquals(2, FROST_BLUR_PASSES)
        assertTrue(widgetPalette(WidgetAppearance.Glass, null, systemDark = false).frosted)
        assertTrue(WidgetAppearance.entries.filter { it != WidgetAppearance.Glass }
            .none { widgetPalette(it, null, systemDark = true).frosted || widgetPalette(it, null, systemDark = false).frosted })
    }
}
