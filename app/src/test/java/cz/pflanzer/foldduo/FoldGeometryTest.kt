package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Test

class FoldGeometryTest {
    /** Fold 8 inner panel: 1088 dp wide, hinge at 544 dp, 10 dp gutter on each side. */
    private val seam = FoldSeam(xDp = 544f)
    private val width = 1088f
    private val rail = 90f

    @Test fun `a point left of the hinge is the left pane, on or right of it the right pane`() {
        assertEquals(Pane.Left, paneFor(100f, seam))
        assertEquals(Pane.Left, paneFor(543.9f, seam))
        assertEquals(Pane.Right, paneFor(544f, seam))
        assertEquals(Pane.Right, paneFor(600f, seam))
    }

    @Test fun `an extent goes to the pane holding more of it`() {
        assertEquals(Pane.Left, paneFor(400f..480f, seam))
        assertEquals(Pane.Right, paneFor(600f..680f, seam))
        // Straddling the seam: 34 dp inside the left pane, 6 dp inside the right pane.
        assertEquals(Pane.Left, paneFor(500f..560f, seam))
        // 4 dp left, 46 dp right.
        assertEquals(Pane.Right, paneFor(530f..600f, seam))
    }

    @Test fun `an extent centred on the seam, inside the gutter or inverted defaults right`() {
        assertEquals(Pane.Right, paneFor(524f..564f, seam))
        assertEquals(Pane.Right, paneFor(539f..549f, seam))
        assertEquals(Pane.Right, paneFor(560f..500f, seam))
    }

    @Test fun `an anchored surface prefers its extent, then its page, then the right pane`() {
        assertEquals(Pane.Left, paneForAnchor(100f..150f, page = 0, seam = seam))
        assertEquals(Pane.Right, paneForAnchor(700f..750f, page = -1, seam = seam))
        assertEquals(Pane.Left, paneForAnchor(null, page = -1, seam = seam))
        assertEquals(Pane.Right, paneForAnchor(null, page = 0, seam = seam))
        assertEquals(Pane.Right, paneForAnchor(null, page = null, seam = seam))
    }

    @Test fun `pane bounds run edge to gutter and gutter to rail`() {
        assertEquals(0f..534f, paneBounds(Pane.Left, seam, width, rail))
        assertEquals(554f..998f, paneBounds(Pane.Right, seam, width, rail))
        assertEquals(534f, paneBounds(Pane.Left, seam, width, rail).extentDp, 0f)
        assertEquals(444f, paneBounds(Pane.Right, seam, width, rail).extentDp, 0f)
        assertEquals(554f..1088f, paneBounds(Pane.Right, seam, width, 0f))
    }

    @Test fun `pane bounds never invert`() {
        assertEquals(0f..0f, paneBounds(Pane.Left, FoldSeam(xDp = 4f), width, rail))
        assertEquals(554f..554f, paneBounds(Pane.Right, seam, windowWidth = 600f, railWidth = rail))
    }

    @Test fun `widget move targets include the left pane only while unfolded`() {
        assertEquals(listOf(WidgetMoveTarget(1, "Move to page 2"), WidgetMoveTarget(2, "Move to page 3"),
            WidgetMoveTarget(3, "New page")),
            widgetMoveTargets(currentPage = 0, homePages = 3, expanded = false))
        assertEquals(listOf(WidgetMoveTarget(-1, "Move to the left pane"), WidgetMoveTarget(0, "Move to page 1"),
            WidgetMoveTarget(2, "Move to page 3"), WidgetMoveTarget(3, "New page")),
            widgetMoveTargets(currentPage = 1, homePages = 3, expanded = true))
    }

    @Test fun `widget move targets from the left pane offer the Home pages only`() {
        assertEquals(listOf(WidgetMoveTarget(0, "Move to Home 1"), WidgetMoveTarget(1, "Move to Home 2"),
            WidgetMoveTarget(2, "New page")),
            widgetMoveTargets(currentPage = -1, homePages = 2, expanded = true))
        assertEquals(listOf(WidgetMoveTarget(1, "New page")), widgetMoveTargets(currentPage = 0, homePages = 1, expanded = false))
        assertEquals(listOf(WidgetMoveTarget(-1, "Move to the left pane"), WidgetMoveTarget(1, "New page")),
            widgetMoveTargets(currentPage = 0, homePages = 1, expanded = true))
    }

    @Test fun `widget move targets omit New page when already on it`() {
        assertEquals(listOf(WidgetMoveTarget(0, "Move to page 1")),
            widgetMoveTargets(currentPage = 1, homePages = 1, expanded = false))
    }
}
