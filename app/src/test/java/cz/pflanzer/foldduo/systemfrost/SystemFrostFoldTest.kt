package cz.pflanzer.foldduo.systemfrost

import cz.pflanzer.foldduo.continuum.FoldLine
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemFrostFoldTest {

    @Test fun `cover fold line hinges the whole panel on its own left edge`() {
        val line = SystemFrostFold.foldLineFor(OverlayTarget.COVER, widthPx = 1248f, heightPx = 1972f)
        // Same shape as LauncherScreen's coverFold: hinge at x=0, eye centered in the full
        // width, the whole panel is the moving side (+1) — matches FoldConfig.coverFrostFromRight.
        assertEquals(FoldLine(splitsX = true, position = 0f, eyePos = 624f, movingSide = 1), line)
    }

    @Test fun `inner fold line seams at the overlay's own right edge, left side moving`() {
        val line = SystemFrostFold.foldLineFor(OverlayTarget.INNER, widthPx = 1224f, heightPx = 1848f)
        // The overlay window is already cropped to the left half, so the seam is this view's
        // own right edge (widthPx) — matches LauncherScreen's todayFold (position = leftHalfWidth,
        // eye centered in that half, movingSide = -1).
        assertEquals(FoldLine(splitsX = true, position = 1224f, eyePos = 612f, movingSide = -1), line)
    }

    @Test fun `fold lines scale with whatever size the overlay window actually is`() {
        val small = SystemFrostFold.foldLineFor(OverlayTarget.INNER, widthPx = 600f, heightPx = 900f)
        assertEquals(600f, small.position, 0f)
        assertEquals(300f, small.eyePos, 0f)
    }

    @Test fun `cover and inner never mix up which side is hinged`() {
        val cover = SystemFrostFold.foldLineFor(OverlayTarget.COVER, widthPx = 1248f, heightPx = 1972f)
        val inner = SystemFrostFold.foldLineFor(OverlayTarget.INNER, widthPx = 1248f, heightPx = 1972f)
        assertEquals(1, cover.movingSide)
        assertEquals(-1, inner.movingSide)
        assertEquals(0f, cover.position, 0f)
        assertEquals(1248f, inner.position, 0f)
    }
}
