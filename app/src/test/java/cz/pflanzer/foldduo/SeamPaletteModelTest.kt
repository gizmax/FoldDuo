package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamPaletteModelTest {

    // --- Seam-gesture detection: start distance from the seam ------------------------------

    @Test fun `eligible right at the seam`() {
        assertTrue(seamPaletteEligibleStart(startXDp = 300f, seamXDp = 300f))
    }

    @Test fun `eligible within the 24 dp zone on either side`() {
        assertTrue(seamPaletteEligibleStart(startXDp = 276f, seamXDp = 300f))
        assertTrue(seamPaletteEligibleStart(startXDp = 324f, seamXDp = 300f))
    }

    @Test fun `not eligible just past the zone`() {
        assertFalse(seamPaletteEligibleStart(startXDp = 275.9f, seamXDp = 300f))
        assertFalse(seamPaletteEligibleStart(startXDp = 324.1f, seamXDp = 300f))
    }

    @Test fun `zone width is configurable`() {
        assertTrue(seamPaletteEligibleStart(startXDp = 310f, seamXDp = 300f, zoneDp = 12f))
        assertFalse(seamPaletteEligibleStart(startXDp = 313f, seamXDp = 300f, zoneDp = 12f))
    }

    // --- Seam-gesture detection: direction -> pane -------------------------------------------

    @Test fun `moving left opens the Left pane`() {
        assertEquals(Pane.Left, seamPaletteTargetPane(deltaXDp = -20f, deltaYDp = 0f))
    }

    @Test fun `moving right opens the Right pane`() {
        assertEquals(Pane.Right, seamPaletteTargetPane(deltaXDp = 20f, deltaYDp = 0f))
    }

    @Test fun `no pane resolves within the direction slop`() {
        assertNull(seamPaletteTargetPane(deltaXDp = 3f, deltaYDp = 0f))
        assertNull(seamPaletteTargetPane(deltaXDp = -3f, deltaYDp = 0f))
    }

    @Test fun `no pane resolves for a more-vertical-than-horizontal drag`() {
        assertNull(seamPaletteTargetPane(deltaXDp = 10f, deltaYDp = 20f))
        assertNull(seamPaletteTargetPane(deltaXDp = -10f, deltaYDp = -20f))
    }

    @Test fun `horizontal-dominant travel past the slop resolves a pane even with some vertical drift`() {
        assertEquals(Pane.Right, seamPaletteTargetPane(deltaXDp = 20f, deltaYDp = 10f))
    }

    // --- Seam-gesture detection: drag progress and open threshold --------------------------

    @Test fun `drag progress follows the finger 1 to 1 up to the palette width`() {
        assertEquals(0f, seamPaletteDragProgress(0f, 200f), 0.0001f)
        assertEquals(0.5f, seamPaletteDragProgress(100f, 200f), 0.0001f)
        assertEquals(1f, seamPaletteDragProgress(200f, 200f), 0.0001f)
    }

    @Test fun `drag progress clamps at 1 past the full width`() {
        assertEquals(1f, seamPaletteDragProgress(400f, 200f), 0.0001f)
    }

    @Test fun `drag progress uses the magnitude, direction-agnostic`() {
        assertEquals(0.5f, seamPaletteDragProgress(-100f, 200f), 0.0001f)
    }

    @Test fun `drag progress is 0 when the palette width is not known yet`() {
        assertEquals(0f, seamPaletteDragProgress(100f, 0f), 0.0001f)
        assertEquals(0f, seamPaletteDragProgress(100f, -50f), 0.0001f)
    }

    @Test fun `commits open at exactly the open fraction, not just short of it`() {
        assertFalse(seamPaletteShouldCommitOpen(SEAM_PALETTE_OPEN_FRACTION - 0.01f))
        assertTrue(seamPaletteShouldCommitOpen(SEAM_PALETTE_OPEN_FRACTION))
        assertTrue(seamPaletteShouldCommitOpen(1f))
    }

    // --- Recent apps ordering fallback -------------------------------------------------------

    @Test fun `recent apps prefer UsageStats recency when it has anything at all`() {
        val usage = listOf("a", "b", "c")
        val history = listOf("x", "y")
        assertEquals(usage, seamPaletteRecentPackages(usage, history))
    }

    @Test fun `recent apps fall back to launch history when UsageStats is empty`() {
        val history = listOf("x", "y", "z")
        assertEquals(history, seamPaletteRecentPackages(emptyList(), history))
    }

    @Test fun `recent apps are capped to the configured count`() {
        val usage = ('a'..'z').map { it.toString() }
        assertEquals(SEAM_PALETTE_RECENT_APPS_COUNT, seamPaletteRecentPackages(usage, emptyList()).size)
        assertEquals(usage.take(SEAM_PALETTE_RECENT_APPS_COUNT), seamPaletteRecentPackages(usage, emptyList()))
    }

    @Test fun `recent apps are de-duplicated without reordering the first occurrence`() {
        assertEquals(listOf("a", "b", "c"), seamPaletteRecentPackages(listOf("a", "b", "a", "c", "b"), emptyList()))
    }

    @Test fun `both sources empty yields an empty row, not a crash`() {
        assertTrue(seamPaletteRecentPackages(emptyList(), emptyList()).isEmpty())
    }

    // --- Clipboard row visibility rule -------------------------------------------------------

    @Test fun `clipboard row is visible for ordinary text`() {
        assertTrue(seamPaletteClipboardVisible("hello"))
    }

    @Test fun `clipboard row is hidden for a null clip`() {
        assertFalse(seamPaletteClipboardVisible(null))
    }

    @Test fun `clipboard row is hidden for a blank or whitespace-only clip`() {
        assertFalse(seamPaletteClipboardVisible(""))
        assertFalse(seamPaletteClipboardVisible("   "))
        assertFalse(seamPaletteClipboardVisible("\n\t"))
    }

    @Test fun `clipboard text is trimmed`() {
        assertEquals("hello", seamPaletteClipboardText("  hello  \n"))
    }
}
