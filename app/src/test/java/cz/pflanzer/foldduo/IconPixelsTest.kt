package cz.pflanzer.foldduo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IconPixelsTest {
    private val px = ICON_PX
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF1A73E8.toInt()
    private val inset = IconPixels.ADAPTIVE_SAFE_INSET

    private fun buffer() = IntArray(px * px)
    private fun IntArray.fill(l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t..b) for (x in l..r) this[y * px + x] = color
    }
    private fun IntArray.at(x: Int, y: Int) = this[y * px + x]
    private fun alpha(p: Int) = p ushr 24
    private fun isWhite(p: Int) = alpha(p) >= 200 && ((p shr 16) and 0xFF) >= 240 && ((p shr 8) and 0xFF) >= 240 && (p and 0xFF) >= 240

    /** Calendar-like foreground: white square in the safe zone, blue glyph inside, white text enclosed in the glyph. */
    private fun calendarLike(): IntArray = buffer().apply {
        fill(20, 20, 123, 123, white)
        fill(50, 40, 93, 103, blue)
        fill(60, 60, 83, 70, white)
    }

    @Test fun whiteSquareIsDetectedAsBacking() {
        val backing = IconPixels.detectOpaqueBacking(calendarLike(), px, px, inset)
        assertNotNull(backing)
        assertEquals(white, backing!!.color)
        assertTrue(backing.coverage >= .85f)
    }

    @Test fun nearWhiteNoiseStillCountsAsBacking() {
        val pixels = calendarLike()
        for (i in pixels.indices) if (pixels[i] == white && i % 3 == 0) pixels[i] = 0xFFF4F6F8.toInt()
        assertEquals(white, IconPixels.detectOpaqueBacking(pixels, px, px, inset)!!.color)
    }

    @Test fun keyOutRemovesBackingKeepsGlyphAndEnclosedText() {
        val pixels = calendarLike()
        val removed = IconPixels.keyOutBacking(pixels, px, px, white, IconPixels.BACKING_TOLERANCE, inset)
        assertTrue("removed $removed", removed > 0)
        assertEquals(0, alpha(pixels.at(30, 30)))
        assertEquals(0, alpha(pixels.at(122, 122)))
        assertEquals(blue, pixels.at(55, 45))
        assertTrue("enclosed white text must survive", isWhite(pixels.at(70, 65)))
        assertArrayEquals(intArrayOf(50, 40, 93, 103), IconPixels.glyphBounds(pixels, px, px))
        // 1 px feather: the glyph column next to the cut is half alpha, the next one is intact.
        assertEquals(127, alpha(pixels.at(50, 70)))
        assertEquals(255, alpha(pixels.at(51, 70)))
    }

    @Test fun strippedGlyphFillsSixtyFourPercentAndIsCentred() {
        val result = IconPixels.stripBacking(calendarLike(), px, px, inset)
        assertTrue(result is StrippedIcon.Glyph)
        val glyph = (result as StrippedIcon.Glyph).pixels
        val bounds = IconPixels.glyphBounds(glyph, px, px)!!
        val height = bounds[3] - bounds[1] + 1
        val width = bounds[2] - bounds[0] + 1
        // Glyph was 44×64 px: the longer side spans 64 % of 144 = 92 px, the shorter keeps the aspect (63 px).
        assertEquals(px * IconPixels.GLYPH_FILL, height.toFloat(), 1f)
        assertEquals(44f / 64f * px * IconPixels.GLYPH_FILL, width.toFloat(), 2f)
        assertEquals(px / 2f, (bounds[1] + bounds[3] + 1) / 2f, 1f)
        assertEquals(px / 2f, (bounds[0] + bounds[2] + 1) / 2f, 1f)
        // No backing white outside the glyph, but the enclosed text is still there, scaled up.
        val whites = glyph.indices.filter { isWhite(glyph[it]) }
        assertTrue(whites.size > 300)
        assertTrue(whites.all { val x = it % px; val y = it / px; x in bounds[0]..bounds[2] && y in bounds[1]..bounds[3] })
        assertEquals(white, result.backing.color)
    }

    @Test fun flatIconFallsBackToTint() {
        val result = IconPixels.stripBacking(buffer().apply { fill(0, 0, px - 1, px - 1, blue) }, px, px, inset)
        assertTrue(result is StrippedIcon.Tint)
        assertEquals(blue, (result as StrippedIcon.Tint).backing.color)
    }

    @Test fun tinyGlyphFallsBackToTint() {
        val pixels = buffer().apply { fill(20, 20, 123, 123, white); fill(66, 66, 77, 77, blue) }
        assertTrue(IconPixels.stripBacking(pixels, px, px, inset) is StrippedIcon.Tint)
    }

    @Test fun transparentBackgroundIconIsUntouched() {
        val pixels = buffer()
        for (y in 0 until px) for (x in 0 until px) {
            val dx = x - 71.5f; val dy = y - 71.5f
            if (dx * dx + dy * dy <= 40f * 40f) pixels[y * px + x] = blue
        }
        assertNull(IconPixels.detectOpaqueBacking(pixels, px, px, inset))
        assertTrue(IconPixels.stripBacking(pixels, px, px, inset) is StrippedIcon.Untouched)
        assertNull(IconPixels.detectOpaqueBacking(pixels, px, px, IconPixels.LEGACY_SAFE_INSET))
    }

    @Test fun roundBackingIsCaughtByTheCircleRing() {
        val pixels = buffer()
        for (y in 0 until px) for (x in 0 until px) {
            val dx = x - 71.5f; val dy = y - 71.5f
            if (dx * dx + dy * dy <= 50f * 50f) pixels[y * px + x] = white
        }
        pixels.fill(52, 52, 91, 91, blue)
        assertEquals(white, IconPixels.detectOpaqueBacking(pixels, px, px, inset)!!.color)
        val result = IconPixels.stripBacking(pixels, px, px, inset)
        assertTrue(result is StrippedIcon.Glyph)
        assertTrue((result as StrippedIcon.Glyph).pixels.none(::isWhite))
    }

    @Test fun multiColouredRingIsNotABacking() {
        val pixels = buffer().apply { fill(0, 0, px - 1, px - 1, white) }
        for (i in pixels.indices) if ((i / px) % 2 == 0) pixels[i] = blue
        assertNull(IconPixels.detectOpaqueBacking(pixels, px, px, inset))
    }

    @Test fun centreAndScaleKeepsAspectAndAlpha() {
        val pixels = buffer().apply { fill(10, 10, 29, 49, blue) }
        val out = IconPixels.centreAndScale(pixels, px, px, intArrayOf(10, 10, 29, 49), .5f)
        val bounds = IconPixels.glyphBounds(out, px, px)!!
        assertEquals(72, bounds[3] - bounds[1] + 1)
        assertEquals(36, bounds[2] - bounds[0] + 1)
        assertEquals(blue, out.at(72, 72))
        assertEquals(0, out.at(5, 5))
    }

    private fun IntArray.fillRoundedSquare(l: Int, t: Int, r: Int, b: Int, radius: Float, color: Int) {
        for (y in t..b) for (x in l..r) {
            val cx = x.toFloat().coerceIn(l + radius, r - radius); val cy = y.toFloat().coerceIn(t + radius, b - radius)
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= radius * radius) this[y * px + x] = color
        }
    }

    /** Pin-like glyph: a filled circle of [radius] at ([cx], [cy]) with a smaller white hole, on top of a stem. */
    private fun IntArray.fillPin(cx: Int, cy: Int, radius: Int, color: Int) {
        for (y in cy - radius..cy + radius + radius / 2) for (x in cx - radius..cx + radius) {
            val dx = x - cx; val dy = y - cy
            val inHead = dx * dx + dy * dy <= radius * radius
            val inStem = y > cy && abs(dx) <= (radius * (cy + radius + radius / 2 - y)) / (radius + radius / 2)
            if (inHead || inStem) this[y * px + x] = color
        }
        val hole = radius / 3
        for (y in cy - hole..cy + hole) for (x in cx - hole..cx + hole)
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= hole * hole) this[y * px + x] = white
    }

    private fun assertGlyphOnlyCentred(result: StrippedIcon, glyphColor: Int) {
        assertTrue("expected Glyph, got ${result.javaClass.simpleName}", result is StrippedIcon.Glyph)
        val glyph = (result as StrippedIcon.Glyph).pixels
        assertEquals(white, result.backing.color)
        val bounds = IconPixels.glyphBounds(glyph, px, px)!!
        val longest = maxOf(bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1)
        assertEquals(px * IconPixels.GLYPH_FILL, longest.toFloat(), 1f)
        assertEquals(px / 2f, (bounds[0] + bounds[2] + 1) / 2f, 1f)
        assertEquals(px / 2f, (bounds[1] + bounds[3] + 1) / 2f, 1f)
        // The backing is gone: every white pixel left is inside the glyph's box (its hole), and the glyph is coloured.
        val whites = glyph.indices.filter { isWhite(glyph[it]) }
        assertTrue(whites.all { val x = it % px; val y = it / px; x in bounds[0] + 4..bounds[2] - 4 && y in bounds[1] + 4..bounds[3] - 4 })
        val coloured = glyph.count { alpha(it) >= 200 && (it and 0xFFFFFF) == (glyphColor and 0xFFFFFF) }
        assertTrue("coloured $coloured", coloured > 1500)
    }

    /** A 66 dp solid round glyph (88 px) covers most of the 27 % ring's sides but none of its corners: no backing. */
    @Test fun largeRoundGlyphIsNotMistakenForABacking() {
        val pixels = buffer()
        for (y in 0 until px) for (x in 0 until px) {
            val dx = x - 71.5f; val dy = y - 71.5f
            if (dx * dx + dy * dy <= 44f * 44f) pixels[y * px + x] = blue
        }
        assertNull(IconPixels.detectOpaqueBacking(pixels, px, px, inset))
        assertTrue(IconPixels.stripBacking(pixels, px, px, inset) is StrippedIcon.Untouched)
    }

    /** Maps-like: a 90 px white rounded square (transparent corners) inside the safe zone with a 30 px pin. */
    @Test fun roundedSquareBackingShortOfTheSafeZoneIsStripped() {
        val red = 0xFFEA4335.toInt()
        val pixels = buffer().apply { fillRoundedSquare(27, 27, 116, 116, 16f, white); fillPin(72, 66, 15, red) }
        assertEquals(0, alpha(pixels.at(27, 27)))
        // The safe-zone rectangle ring at 17 % (24 px) sits on transparent pixels; the inscribed circle still fits.
        val backing = IconPixels.detectOpaqueBacking(pixels, px, px, inset)!!
        assertEquals(white, backing.color)
        assertEquals(inset, backing.inset, 1e-6f)
        assertTrue(backing.circle)
        assertGlyphOnlyCentred(IconPixels.stripBacking(pixels, px, px, inset), red)
    }

    /** An 84 px rounded square misses both safe-zone rings; the 22 % rectangle ring (corners included) finds it. */
    @Test fun roundedSquareBelowTheCircleRingIsFoundByTheInnerRing() {
        val red = 0xFFEA4335.toInt()
        val pixels = buffer().apply { fillRoundedSquare(30, 30, 113, 113, 6f, white); fillPin(72, 66, 15, red) }
        val backing = IconPixels.detectOpaqueBacking(pixels, px, px, inset)!!
        assertEquals(.22f, backing.inset, 1e-6f)
        assertTrue(!backing.circle)
        assertGlyphOnlyCentred(IconPixels.stripBacking(pixels, px, px, inset), red)
    }

    /** Maps as dumped from the Fold: a sharp 72 px white square (54 dp of the 108 dp layer) with the pin. */
    @Test fun squareBackingAtHalfTheLayerIsStripped() {
        val red = 0xFFEA4335.toInt()
        val pixels = buffer().apply { fill(36, 36, 107, 107, white); fillPin(72, 66, 15, red) }
        val backing = IconPixels.detectOpaqueBacking(pixels, px, px, inset)!!
        assertEquals(.27f, backing.inset, 1e-6f)
        assertGlyphOnlyCentred(IconPixels.stripBacking(pixels, px, px, inset), red)
    }

    /** Play-like: the whole 144 px layer is white with a 60 px coloured triangle; 88 % of the pixels go. */
    @Test fun fullWhiteLayerWithTriangleKeepsTheTriangle() {
        val green = 0xFF34A853.toInt()
        val pixels = buffer().apply {
            fill(0, 0, px - 1, px - 1, white)
            for (y in 42..101) { val half = (y - 42) * 30 / 60; for (x in 72 - half..72 + half) this[y * px + x] = green }
        }
        val opaqueBefore = pixels.count { alpha(it) >= IconPixels.GLYPH_ALPHA_THRESHOLD }
        val work = pixels.copyOf()
        val removed = IconPixels.keyOutBacking(work, px, px, white, IconPixels.BACKING_TOLERANCE, inset)
        assertTrue("removed $removed of $opaqueBefore", removed > .85f * opaqueBefore)
        val result = IconPixels.stripBacking(pixels, px, px, inset)
        assertTrue("expected Glyph, got ${result.javaClass.simpleName}", result is StrippedIcon.Glyph)
        val glyph = (result as StrippedIcon.Glyph).pixels
        assertTrue(glyph.none(::isWhite))
        val bounds = IconPixels.glyphBounds(glyph, px, px)!!
        assertEquals(px * IconPixels.GLYPH_FILL, (bounds[3] - bounds[1] + 1).toFloat(), 1f)
        assertEquals(px / 2f, (bounds[0] + bounds[2] + 1) / 2f, 1f)
        assertEquals(px / 2f, (bounds[1] + bounds[3] + 1) / 2f, 1f)
    }

    @Test fun backingBiggerThanTheRingIsClearedToTheEdge() {
        // White fills the buffer to its edge, glyph in the middle: nothing white may survive outside the glyph.
        val pixels = buffer().apply { fill(0, 0, px - 1, px - 1, white); fill(50, 40, 93, 103, blue) }
        val work = pixels.copyOf()
        IconPixels.keyOutBacking(work, px, px, white, IconPixels.BACKING_TOLERANCE, inset)
        assertEquals(0, alpha(work.at(0, 0)))
        assertEquals(0, alpha(work.at(143, 71)))
        assertArrayEquals(intArrayOf(50, 40, 93, 103), IconPixels.glyphBounds(work, px, px))
    }

    @Test fun onlyTheFindingRingSeedsTheKeyOut() {
        // Calendar-like glyph whose enclosed white sits exactly where the circle ring's diagonal (39..104) and the
        // 27 % rectangle ring run; the rectangle ring at 17 % found the backing, so neither may seed.
        val pixels = buffer().apply { fill(20, 20, 123, 123, white); fill(30, 30, 113, 113, blue); fill(38, 38, 105, 105, white) }
        val result = IconPixels.stripBacking(pixels, px, px, inset)
        assertTrue(result is StrippedIcon.Glyph)
        assertEquals(inset, (result as StrippedIcon.Glyph).backing.inset, 1e-6f)
        assertTrue(!result.backing.circle)
        assertTrue("enclosed white must survive", result.pixels.count(::isWhite) > 3000)
    }

    @Test fun floodFillOnFullTileIsFast() {
        val pixels = calendarLike()
        val start = System.nanoTime()
        repeat(20) { IconPixels.stripBacking(pixels, px, px, inset) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("20 strips took $ms ms", ms < 2000)
    }
}
