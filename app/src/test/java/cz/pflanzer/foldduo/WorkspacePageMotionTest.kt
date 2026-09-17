package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePageMotionTest {
    private val motion = WorkspacePageMotion(
        homePages = 3,
        pageWidth = 1000f,
        homeStride = 500f,
    )

    @Test fun `homes and library have the intended endpoints`() {
        assertEquals(0f, motion.offset(0f), 0f)
        assertEquals(500f, motion.offset(1f), 0f)
        assertEquals(1000f, motion.offset(2f), 0f)
        assertEquals(2000f, motion.offset(3f), 0f)

        assertEquals(0f, motion.position(0f), 0f)
        assertEquals(1f, motion.position(500f), 0f)
        assertEquals(2f, motion.position(1000f), 0f)
        assertEquals(3f, motion.position(2000f), 0f)
    }

    @Test fun `leading overscroll keeps the full pager width`() {
        assertEquals(-1000f, motion.offset(-1f), 0f)
        assertEquals(-1f, motion.position(-1000f), 0f)
    }

    @Test fun `mapping and inverse stay continuous through every boundary`() {
        for (step in -1000..3000) {
            val position = step / 1000f
            assertEquals(position, motion.position(motion.offset(position)), .00001f)
        }

        val epsilon = .0001f
        for (boundary in listOf(0f, 2f)) {
            assertTrue(motion.offset(boundary) - motion.offset(boundary - epsilon) < .11f)
            assertTrue(motion.offset(boundary + epsilon) - motion.offset(boundary) < .11f)
        }
    }

    @Test fun `one home page keeps both outer transitions full width`() {
        val single = WorkspacePageMotion(1, 1000f, 500f)
        for (position in listOf(-1.5f, -1f, -.75f, 0f, .75f, 1f, 1.5f)) {
            assertEquals(position * 1000f, single.offset(position), 0f)
            assertEquals(position, single.position(single.offset(position)), 0f)
        }
    }

    @Test fun `half width home panes preserve one to one visual input`() {
        val startingPositions = listOf(0f, .5f, 1.75f, 2.25f, 2.75f)
        val deltas = listOf(-120f, -10f, 0f, 35f, 140f)
        for (start in startingPositions) for (delta in deltas) {
            val moved = motion.positionAfterVisualDelta(start, delta)
            // Two Float conversions can accumulate a tiny subpixel rounding error.
            assertEquals(delta, motion.offset(moved) - motion.offset(start), .001f)
        }
        assertEquals(500f, motion.stride(0, 1), 0f)
        assertEquals(500f, motion.stride(1, 2), 0f)
        assertEquals(1000f, motion.stride(2, 3), 0f)
    }

    @Test fun `seam places the leading pane at the edge and home after the gutter`() {
        // Fold 8 inner: width 1088, seam 544, gutter 10 -> right pane 534 wide.
        val seam = FoldSeam(544f, 10f)
        val geometry = homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = seam.xDp, seamGutterDp = seam.gutterDp)
        val panes = expandedPaneLayout(1088f, geometry.homeWidth, geometry.gridWidth, seam)
        assertEquals(0f, panes.leadingOrigin, 0f)
        // The Today pane spans [0, seam - gutter]; Home starts after the gutter.
        assertEquals(seam.leadingEndDp, panes.leadingWidth, 0f)
        assertEquals(534f, panes.leadingWidth, 0f)
        assertEquals(554f, panes.homeOrigin, 0f)
        assertEquals(seam.homeStartDp, panes.homeOrigin, 0f)
        // "Spread jako jedna plocha" (17. 9. noc): the right pane's own pitch is its FULL pane box
        // (geometry.homeWidth) — the same value the seam already hands the left (Today) pane, not
        // the narrower icon grid (geometry.gridWidth + 16f) after the rail's own trailing inset
        // has been carved out of it. Today and Home now share one uniform pitch.
        val pageWidth = 1088f - 68f - 28f
        assertEquals(geometry.homeWidth, panes.homeStride, 0f)
        assertEquals(panes.leadingWidth, panes.homeStride, 0f)
        assertTrue(panes.leadingOrigin + panes.leadingWidth <= panes.homeOrigin)
        // One stride puts Home 2 exactly where Home 1 was; Today does not move for Home paging.
        val motion = WorkspacePageMotion(3, pageWidth, panes.homeStride)
        assertEquals(0f, panes.homeStride - motion.offset(1f), 0f)
        assertEquals(panes.homeStride, motion.stride(0, 1), 0f)
        assertEquals(panes.homeStride, motion.stride(1, 2), 0f)
        assertEquals(motion.pageWidth, motion.stride(2, 3), 0f)
    }

    @Test fun `without a hinge the pitch still falls back to the historical grid-based width`() {
        // No seam to align two full-height panes against: the right pane keeps its narrower,
        // historical pitch (gridWidth + 16f), not the (here undefined) uniform strip pitch.
        val geometry = homeGeometry(1088f, 821f, LayoutPreset(), true)
        val panes = expandedPaneLayout(1088f, geometry.homeWidth, geometry.gridWidth, null)
        assertEquals(geometry.gridWidth + 16f, panes.homeStride, 0f)
    }

    @Test fun `viewport offset for a right-pane home page k lands exactly k strides in`() {
        val seam = FoldSeam(544f, 10f)
        val geometry = homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = seam.xDp, seamGutterDp = seam.gutterDp)
        val panes = expandedPaneLayout(1088f, geometry.homeWidth, geometry.gridWidth, seam)
        val motion = WorkspacePageMotion(4, 992f, panes.homeStride)
        for (k in 0..3) {
            // ExpandedWorkspace places Home page k's fixed strip box at `homeOrigin + k * stride`;
            // the strip's shared translationX is `-motion.offset(p)`, so at p == k the page sits
            // exactly at the pane's own origin (visually aligned with Today's, uniform pitch).
            val boxOriginAtRest = panes.homeOrigin + k * panes.homeStride - motion.offset(k.toFloat())
            assertEquals(panes.homeOrigin, boxOriginAtRest, .001f)
        }
    }

    @Test fun `without a seam the leading grid stays centered in the left half`() {
        val geometry = homeGeometry(1088f, 821f, LayoutPreset(), true)
        val panes = expandedPaneLayout(1088f, geometry.homeWidth, geometry.gridWidth, null)
        assertEquals(1088f - 460f, panes.homeOrigin, 0f)
        assertEquals((1088f / 2f - geometry.gridWidth) / 2f - 16f, panes.leadingOrigin, 0f)
        assertEquals(geometry.gridWidth + 16f, panes.leadingWidth, 0f)
        assertEquals(geometry.gridWidth + 16f, panes.homeStride, 0f)
        // A seam that cannot split the window behaves like no seam.
        assertEquals(panes, expandedPaneLayout(1088f, geometry.homeWidth, geometry.gridWidth, FoldSeam(1085f)))
    }

    @Test fun `composedHomePages keeps every page, never windows to a viewport`() {
        assertEquals(emptyList<Int>(), composedHomePages(0))
        assertEquals(listOf(0), composedHomePages(1))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), composedHomePages(6))
        // Never negative even for a defensive caller.
        assertEquals(emptyList<Int>(), composedHomePages(-1))
    }

    @Test fun `homePageDrawVisible matches the intersecting-plus-margin window`() {
        // Page 2 of stride 500, viewport 1000, margin one pane width: the visible scroll range is
        // the open interval (pageStart - viewport - margin, pageStart + paneWidth + margin) =
        // (1000 - 1000 - 500, 1000 + 500 + 500) = (-500, 2000).
        val pageStart = 1000f // page 2 * stride 500
        val paneWidth = 500f
        val viewport = 1000f
        val margin = paneWidth
        fun visible(scroll: Float) = homePageDrawVisible(pageStart, paneWidth, scrollPx = scroll, viewportPx = viewport, marginPx = margin)
        // Fully inside the viewport.
        assertTrue(visible(1000f))
        // Just inside either open edge of the window.
        assertTrue(visible(1999f))
        assertTrue(visible(-499f))
        // Exactly on (exclusive) or past either edge: not drawn.
        assertTrue(!visible(2000f))
        assertTrue(!visible(2001f))
        assertTrue(!visible(-500f))
        assertTrue(!visible(-501f))
        // Zero margin falls back to plain intersection.
        assertTrue(homePageDrawVisible(0f, 500f, scrollPx = 400f, viewportPx = 1000f, marginPx = 0f))
        assertTrue(!homePageDrawVisible(0f, 500f, scrollPx = 500f, viewportPx = 1000f, marginPx = 0f))
    }
}
