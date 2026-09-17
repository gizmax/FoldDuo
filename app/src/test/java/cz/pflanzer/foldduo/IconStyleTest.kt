package cz.pflanzer.foldduo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class IconStyleTest {
    private fun points(outline: FloatArray) = outline.toList().chunked(2).map { it[0] to it[1] }

    @Test fun everyShapeIsClosedAndInsideBounds() {
        IconShape.entries.forEach { shape ->
            val outline = iconShapeOutline(shape, 144f)
            assertEquals(0, outline.size % 2)
            assertTrue("$shape has too few points", outline.size / 2 > 16)
            assertEquals("$shape not closed", outline[0], outline[outline.size - 2], 1e-3f)
            assertEquals("$shape not closed", outline[1], outline[outline.size - 1], 1e-3f)
            assertTrue("$shape leaves bounds", outline.all { it in 0f..144f })
        }
    }

    @Test fun squircleTouchesEdgeMidpointsAndBulgesPastRoundedSquare() {
        val pts = points(iconShapeOutline(IconShape.Squircle, 100f))
        assertTrue(pts.any { it.first > 99.9f && kotlin.math.abs(it.second - 50f) < .5f })
        assertTrue(pts.any { it.second < .1f && kotlin.math.abs(it.first - 50f) < .5f })
        // Diagonal extent: the n=5 superellipse corner sits further out than the 34/144 rounded square's.
        val squircleCorner = pts.maxOf { minOf(it.first, it.second) }
        val roundedCorner = points(iconShapeOutline(IconShape.RoundedSquare, 100f)).maxOf { minOf(it.first, it.second) }
        assertTrue(squircleCorner > roundedCorner)
    }

    @Test fun circlePointsAreOnTheRadius() {
        points(iconShapeOutline(IconShape.Circle, 144f)).forEach { (x, y) ->
            assertEquals(72f, hypot(x - 72f, y - 72f), .01f)
        }
    }

    @Test fun teardropHasOneTightCorner() {
        val pts = points(iconShapeOutline(IconShape.Teardrop, 100f))
        // Bottom-right reaches almost to (100,100); top-left stays on a radius-50 arc.
        assertTrue(pts.any { it.first > 95f && it.second > 95f })
        assertFalse(pts.any { it.first < 10f && it.second < 10f })
    }

    @Test fun roundedSquareMatchesHistoricalRadius() {
        val pts = points(iconShapeOutline(IconShape.RoundedSquare, 144f))
        assertTrue(pts.any { it.first == 0f && it.second == 34f })
        assertTrue(pts.any { it.first == 34f && it.second == 0f })
    }

    @Test fun defaultsKeepTodaysLook() {
        val style = JSONObject("{}").iconStyle()
        assertEquals(IconStyle(IconShape.RoundedSquare, IconEffect.None, null), style)
        assertTrue(style.clipsToShape)
    }

    @Test fun styleRoundTripsThroughJson() {
        val style = IconStyle(IconShape.Squircle, IconEffect.ClearGlass, "com.example.pack")
        assertEquals(style, JSONObject(JSONObject().putIconStyle(style).toString()).iconStyle())
        val none = IconStyle(IconShape.Circle, IconEffect.Glass, null)
        assertNull(JSONObject(JSONObject().putIconStyle(none).toString()).iconStyle().pack)
        assertEquals(IconShape.RoundedSquare, JSONObject("""{"iconShape":"Blob","iconEffect":"Sparkle"}""").iconStyle().shape)
    }

    @Test fun packArtworkIsNotClippedUnlessGlass() {
        assertFalse(IconStyle(pack = "p").clipsToShape)
        assertTrue(IconStyle(pack = "p", effect = IconEffect.Glass).clipsToShape)
    }

    @Test fun glassTileColorMixesFifteenPercentTowardDominant() {
        val tile = glassTileColor(0xFF000000.toInt(), .22f)
        assertEquals(56, tile ushr 24)
        assertEquals(216, (tile shr 16) and 0xFF)
        val white = glassTileColor(0xFFFFFFFF.toInt(), .10f)
        assertEquals(0x19FFFFFF, white)
    }

    @Test fun dominantColorIgnoresTransparentPixels() {
        assertEquals(0xFFFF0000.toInt(), dominantColor(intArrayOf(0xFFFF0000.toInt(), 0x0000FF00)))
        assertEquals(0xFF808080.toInt(), dominantColor(intArrayOf(0, 0)))
    }

    // --- B41 Liquid Glass --------------------------------------------------------------

    @Test fun liquidGlassDefaultsOnButOnlyAppliesToClearGlass() {
        assertTrue(IconStyle().liquidGlass)
        assertFalse(IconStyle(effect = IconEffect.None).usesLiquidGlass)
        assertFalse(IconStyle(effect = IconEffect.Glass).usesLiquidGlass)
        assertTrue(IconStyle(effect = IconEffect.ClearGlass).usesLiquidGlass)
        assertFalse(IconStyle(effect = IconEffect.ClearGlass, liquidGlass = false).usesLiquidGlass)
    }

    @Test fun liquidGlassFlagRoundTripsThroughJson() {
        val off = IconStyle(IconShape.Squircle, IconEffect.ClearGlass, liquidGlass = false)
        assertEquals(off, JSONObject(JSONObject().putIconStyle(off).toString()).iconStyle())
        // Missing key (older saved state) defaults to on.
        assertTrue(JSONObject("""{"iconEffect":"ClearGlass"}""").iconStyle().liquidGlass)
    }

    // --- B49 Live icons ------------------------------------------------------------------

    @Test fun liveIconsDefaultsOn() {
        assertTrue(IconStyle().liveIcons)
    }

    @Test fun liveIconsFlagRoundTripsThroughJson() {
        val off = IconStyle(liveIcons = false)
        assertEquals(off, JSONObject(JSONObject().putIconStyle(off).toString()).iconStyle())
        // Missing key (older saved state) defaults to on.
        assertTrue(JSONObject("""{"iconEffect":"None"}""").iconStyle().liveIcons)
    }
}
