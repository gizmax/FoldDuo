package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for WallpaperPalette.kt (B29 "Barvy z tapety"): [WallpaperPalette.of]'s
 * luminance-vs-hints decision, its accent/fallback selection, the derived veil/ink/fill colours,
 * and [paneIsDark]'s per-pane override — all ARGB Int, no Android imports, so these run as plain
 * JVM unit tests like BuiltinWidgetsTest/HostWidgetSkinTest.
 */
class WallpaperPaletteTest {
    private val darkBlue = 0xFF16324A.toInt()   // relativeLuminance well under .5
    private val brightYellow = 0xFFF5E36B.toInt() // relativeLuminance well over .5

    // --- accent / fallback selection ------------------------------------------------------

    @Test fun `primary colour wins over the bitmap fallback`() {
        val palette = WallpaperPalette.of(primary = darkBlue, supportsDarkText = false, supportsDarkTheme = true,
            fallbackArgb = brightYellow)
        assertEquals(darkBlue, palette.accent)
    }

    @Test fun `bitmap fallback is used only when the system reports no primary colour`() {
        val palette = WallpaperPalette.of(primary = null, supportsDarkText = false, supportsDarkTheme = true,
            fallbackArgb = brightYellow)
        assertEquals(brightYellow, palette.accent)
    }

    @Test fun `with neither a primary colour nor a fallback, the fixed accent is used`() {
        val palette = WallpaperPalette.of(primary = null, supportsDarkText = false, supportsDarkTheme = false)
        assertEquals(WallpaperPalette.FALLBACK_ACCENT, palette.accent)
    }

    @Test fun `Default is the fixed dark accent with no photo or hints`() {
        assertEquals(WallpaperPalette.FALLBACK_ACCENT, WallpaperPalette.Default.accent)
        assertTrue(WallpaperPalette.Default.dark)
    }

    // --- colour-hints vs luminance ---------------------------------------------------------

    @Test fun `a dark-text hint alone (no dark-theme hint) reads bright regardless of the accent's own luminance`() {
        // The accent is dark, but the system explicitly says dark text suits this wallpaper.
        val palette = WallpaperPalette.of(darkBlue, supportsDarkText = true, supportsDarkTheme = false)
        assertFalse(palette.dark)
    }

    @Test fun `a dark-theme hint alone (no dark-text hint) reads dark regardless of the accent's own luminance`() {
        val palette = WallpaperPalette.of(brightYellow, supportsDarkText = false, supportsDarkTheme = true)
        assertTrue(palette.dark)
    }

    @Test fun `both hints set, or neither, falls back to the accent's own luminance`() {
        assertFalse(WallpaperPalette.of(brightYellow, supportsDarkText = true, supportsDarkTheme = true).dark)
        assertTrue(WallpaperPalette.of(darkBlue, supportsDarkText = true, supportsDarkTheme = true).dark)
        assertFalse(WallpaperPalette.of(brightYellow, supportsDarkText = false, supportsDarkTheme = false).dark)
        assertTrue(WallpaperPalette.of(darkBlue, supportsDarkText = false, supportsDarkTheme = false).dark)
    }

    // --- derived veil / ink / fill ----------------------------------------------------------

    @Test fun `a dark wallpaper gets a white veil and white rail ink`() {
        val palette = WallpaperPalette.of(darkBlue, supportsDarkText = false, supportsDarkTheme = false)
        assertTrue(palette.dark)
        assertEquals(0xFFFFFFFF.toInt(), palette.veilArgb)
        assertEquals(0xFFFFFFFF.toInt(), palette.railInkArgb)
    }

    @Test fun `a bright wallpaper gets a black veil and dark rail ink`() {
        val palette = WallpaperPalette.of(brightYellow, supportsDarkText = false, supportsDarkTheme = false)
        assertFalse(palette.dark)
        assertEquals(0xFF000000.toInt(), palette.veilArgb)
        assertNotEquals(0xFFFFFFFF.toInt(), palette.railInkArgb)
        assertTrue(relativeLuminance(palette.railInkArgb) < .5)
    }

    @Test fun `island fill is a real blend, not the bare accent or a fixed neutral`() {
        val palette = WallpaperPalette.of(brightYellow, supportsDarkText = false, supportsDarkTheme = false)
        assertNotEquals(brightYellow, palette.islandFillArgb)
        assertEquals(0xFF, (palette.islandFillArgb ushr 24) and 0xFF) // stays opaque
    }

    @Test fun `mixArgb interpolates each channel linearly and clamps to 0 or 1`() {
        val a = 0xFF102030.toInt(); val b = 0xFFF0E0D0.toInt()
        assertEquals(a, WallpaperPalette.mixArgb(a, b, 0f))
        assertEquals(b, WallpaperPalette.mixArgb(a, b, 1f))
        val half = WallpaperPalette.mixArgb(a, b, .5f)
        assertEquals(((0x10 + 0xF0) / 2), (half shr 16) and 0xFF)
        assertEquals(((0x20 + 0xE0) / 2), (half shr 8) and 0xFF)
        assertEquals(((0x30 + 0xD0) / 2), half and 0xFF)
    }

    // --- per-pane luminance ------------------------------------------------------------------

    @Test fun `paneIsDark reads the sampled crop colour when one is given`() {
        assertTrue(paneIsDark(darkBlue, fallback = false))
        assertFalse(paneIsDark(brightYellow, fallback = true))
    }

    @Test fun `paneIsDark falls back to the global palette's dark flag without a sample`() {
        assertTrue(paneIsDark(null, fallback = true))
        assertFalse(paneIsDark(null, fallback = false))
    }
}
