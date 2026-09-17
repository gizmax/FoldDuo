package cz.pflanzer.foldduo.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskGeometryTest {
    @Test fun noVerticalSeamSplitsTopBottomAtMidpoint() {
        val layout = deskLayout(1088f, 821f, verticalSeamXDp = null)
        assertTrue(layout.stacked)
        assertEquals(0f, layout.face.top)
        assertEquals(0f, layout.face.left)
        assertEquals(1088f, layout.face.right)
        assertEquals(1088f, layout.deck.right)
        assertEquals(821f, layout.deck.bottom)
        // Gutter on both sides of the midpoint (410.5), face ends before it, deck starts after.
        assertTrue(layout.face.bottom < 410.5f)
        assertTrue(layout.deck.top > 410.5f)
    }

    @Test fun verticalSeamFallsBackToLeftRight() {
        val layout = deskLayout(1088f, 821f, verticalSeamXDp = 544f)
        assertFalse(layout.stacked)
        assertEquals(0f, layout.face.left)
        assertEquals(821f, layout.face.bottom)
        assertEquals(821f, layout.deck.bottom)
        assertTrue(layout.face.right < 544f)
        assertTrue(layout.deck.left > 544f)
        assertEquals(1088f, layout.deck.right)
    }

    @Test fun verticalSeamOutsideWindowIsIgnored() {
        // A seam that does not actually split this width (e.g. a stale value from a rotation
        // still in flight) must not produce an inverted or degenerate left/right pane.
        val layout = deskLayout(1088f, 821f, verticalSeamXDp = 5000f)
        assertTrue(layout.stacked)
    }

    @Test fun gutterIsSymmetricAroundTheMidline() {
        val layout = deskLayout(1000f, 800f, verticalSeamXDp = null, gutterDp = 20f)
        assertEquals(380f, layout.face.bottom) // 400 - 20
        assertEquals(420f, layout.deck.top) // 400 + 20
    }

    @Test fun tinyHeightNeverGoesNegative() {
        val layout = deskLayout(400f, 10f, verticalSeamXDp = null, gutterDp = 10f)
        assertTrue(layout.face.heightDp >= 0f)
        assertTrue(layout.deck.heightDp >= 0f)
    }

    @Test fun panesTileTheFullWidthWhenStacked() {
        val layout = deskLayout(900f, 700f, verticalSeamXDp = null)
        assertEquals(900f, layout.face.widthDp)
        assertEquals(900f, layout.deck.widthDp)
    }
}
