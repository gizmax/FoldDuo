package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test

/**
 * The right rail as persistent chrome (PLAN.md, Phase 2 step 5a): status at the top below the
 * insets and the cover cutout, a fixed island slot under it, the search control anchored to the
 * bottom of the strip (level with the page-indicator row), the dock vertically centred in the
 * free span between the island and the search (iPhone Duo Home).
 * Window sizes are the measured Fold 8 panels: cover 555 x 876 dp (cutout 104 px = 46 dp),
 * inner 1088 x 821 dp with the hinge at 544 dp.
 */
class RailLayoutTest {
    private val preset = LayoutPreset()
    private val cover = homeGeometry(555f, 876f, preset, true)
    private val inner = homeGeometry(1088f, 821f, preset, true, seamXDp = 544f, seamGutterDp = 10f)
    private val statusHeight = 120f

    private fun rail(geometry: HomeGeometry, height: Float, topInset: Float, bottomInset: Float, cutoutBottom: Float,
        status: Float = statusHeight, position: Float = .5f) = railLayout(height, topInset, bottomInset, cutoutBottom, status,
        preset.dockWidth, dockIconSize(geometry.iconSize), topMargin = geometry.contentTop, dockPosition = position)

    private fun RailLayout.spanTop() = islandTop + islandHeight + RAIL_GAP_DP
    /** The dock span ends a gap above the bottom-anchored search control. */
    private fun RailLayout.dockSpanBottom() = searchTop - RAIL_GAP_DP
    private fun RailLayout.dockCentre() = dockTop + dockHeight / 2f

    @Test fun `rail width is the dock plus its paddings and is the single right inset`() {
        assertEquals(68f + 12f + 16f, railWidthDp(68f), 0f)
        assertEquals(railWidthDp(preset.dockWidth), cover.railWidth, 0f)
        assertEquals(railWidthDp(preset.dockWidth), inner.railWidth, 0f)
        // Home grid: start margin + grid + rail fill the pane exactly on both panels.
        assertEquals(cover.homeWidth, HOME_START_DP + cover.gridWidth + cover.railWidth, .01f)
        assertEquals(inner.homeWidth, HOME_START_DP + inner.gridWidth + inner.railWidth, .01f)
        assertTrue(preset.dockWidth + RAIL_EDGE_PADDING_DP < railWidthDp(preset.dockWidth))
        // Pane identity: the same rail width on cover and inner (same dock preset).
        assertEquals(cover.railWidth, rail(cover, 876f, 46f, 0f, 46f).railWidth, 0f)
        assertEquals(inner.railWidth, rail(inner, 821f, 0f, 0f, 0f).railWidth, 0f)
    }

    @Test fun `cover status clears the top inset and camera cutout and the dock is centred in the free span`() {
        val r = rail(cover, 876f, topInset = 46f, bottomInset = 0f, cutoutBottom = 46f)
        assertEquals(46f + cover.contentTop, r.statusTop, .01f)
        assertTrue("status must sit below the 104 px cutout", r.statusTop >= COVER_CUTOUT_DP)
        assertEquals(r.statusTop + statusHeight + RAIL_GAP_DP, r.islandTop, .01f)
        assertEquals(RAIL_ISLAND_HEIGHT_DP, r.islandHeight, 0f)
        val spanBottom = 876f - RAIL_BOTTOM_MARGIN_DP
        // Search is anchored to the bottom of the span, level with the page-indicator row.
        assertEquals(maxOf(48f, dockIconSize(cover.iconSize)), r.searchSize, .01f)
        assertEquals(spanBottom - r.searchSize, r.searchTop, .01f)
        assertEquals(spanBottom, r.searchBottom, .01f)
        // The dock is centred in what remains above the search.
        assertEquals(spanBottom - r.searchSize - RAIL_GAP_DP, r.dockSpanBottom(), .01f)
        assertEquals((r.spanTop() + r.dockSpanBottom()) / 2f, r.dockCentre(), .01f)
        assertEquals(4f * r.dockRowHeight + 16f, r.dockHeight, .01f)
        assertTrue(r.dockRowHeight >= 48f)
        assertTrue(r.dockTop >= r.spanTop())
        assertTrue(r.dockBottom + RAIL_GAP_DP <= r.searchTop + .01f)
        assertEquals(cover.railWidth, r.railWidth, 0f)
    }

    @Test fun `the centred cutout never pushes the rail's status down (17 9)`() {
        // The rail hugs the right edge and is clear of the camera cutout, so the status column
        // sits RAIL_TOP_MARGIN_DP from the top whether or not a cutout is reported.
        val plain = railLayout(876f, 0f, 0f, 0f, 0f, preset.dockWidth, dockIconSize(cover.iconSize), topMargin = 16f)
        val cut = railLayout(876f, 0f, 0f, COVER_CUTOUT_DP, 0f, preset.dockWidth, dockIconSize(cover.iconSize), topMargin = 16f)
        assertEquals(16f, cut.statusTop, .01f)
        assertEquals(plain.statusTop, cut.statusTop, .01f)
        // No status block: the island slot starts right where the status would.
        assertEquals(plain.statusTop, plain.islandTop, 0f)
        // A negative top inset (the caller lifts the rail above its safe-area padding) moves the
        // whole column up by the same amount.
        val lifted = railLayout(876f, -10f, 0f, COVER_CUTOUT_DP, 0f, preset.dockWidth, dockIconSize(cover.iconSize), topMargin = 16f)
        assertEquals(6f, lifted.statusTop, .01f)
    }

    @Test fun `inner rail has the same blocks in the same order with the dock centred in its span`() {
        val r = rail(inner, 821f, topInset = 0f, bottomInset = 0f, cutoutBottom = 0f)
        assertEquals(inner.contentTop, r.statusTop, 0f)
        assertEquals(821f - RAIL_BOTTOM_MARGIN_DP, r.searchBottom, .01f)
        assertEquals((r.spanTop() + r.dockSpanBottom()) / 2f, r.dockCentre(), .01f)
        val c = rail(cover, 876f, 46f, 0f, 46f)
        // Same dock preset, same dock size on both panels; only the window height moves it.
        assertEquals(c.dockHeight, r.dockHeight, .01f)
        assertEquals(c.dockRowHeight, r.dockRowHeight, .01f)
        assertEquals(c.searchSize, r.searchSize, .01f)
        val withNav = rail(inner, 821f, topInset = 0f, bottomInset = 24f, cutoutBottom = 0f)
        // A bottom inset lifts the search by the inset and the centred dock by half of it.
        assertEquals(821f - 24f - RAIL_BOTTOM_MARGIN_DP, withNav.searchBottom, .01f)
        assertEquals(r.searchTop - 24f, withNav.searchTop, .01f)
        assertEquals((r.spanTop() + withNav.dockSpanBottom()) / 2f, withNav.dockCentre(), .01f)
        assertEquals(r.dockTop - 12f, withNav.dockTop, .01f)
        assertEquals(r.dockHeight, withNav.dockHeight, .01f)
    }

    @Test fun `dockPosition slides the dock up or down inside the free span`() {
        for ((geometry, height, top, cut) in listOf(Quad(cover, 876f, 46f, 46f), Quad(inner, 821f, 0f, 0f))) {
            val mid = rail(geometry, height, top, 0f, cut, position = .5f)
            val up = rail(geometry, height, top, 0f, cut, position = .25f)
            val down = rail(geometry, height, top, 0f, cut, position = .75f)
            val spanBottom = height - RAIL_BOTTOM_MARGIN_DP
            val free = mid.dockSpanBottom() - mid.spanTop() - mid.dockHeight
            assertEquals(mid.spanTop() + free * .25f, up.dockTop, .01f)
            assertEquals(mid.spanTop() + free * .5f, mid.dockTop, .01f)
            assertEquals(mid.spanTop() + free * .75f, down.dockTop, .01f)
            assertTrue(up.dockTop < mid.dockTop && mid.dockTop < down.dockTop)
            assertTrue(up.dockTop >= up.spanTop())
            // The search stays bottom-anchored whatever the dock position; at 0.75 the dock
            // must still clear it.
            assertEquals(spanBottom, up.searchBottom, .01f); assertEquals(spanBottom, down.searchBottom, .01f)
            assertTrue(down.dockBottom + RAIL_GAP_DP <= down.searchTop + .01f)
            assertEquals(mid.dockHeight, up.dockHeight, 0f); assertEquals(mid.dockHeight, down.dockHeight, 0f)
            // Out-of-range values clamp to the preset range.
            assertEquals(up.dockTop, rail(geometry, height, top, 0f, cut, position = 0f).dockTop, 0f)
            assertEquals(down.dockTop, rail(geometry, height, top, 0f, cut, position = 1f).dockTop, 0f)
        }
    }

    @Test fun `dock height follows the dock icon size not the widget block`() {
        val wide = homeGeometry(555f, 876f, preset.copy(iconSize = 68f), true)
        val small = homeGeometry(555f, 876f, preset.copy(iconSize = 40f), true)
        val big = rail(wide, 876f, 46f, 0f, 46f)
        val little = rail(small, 876f, 46f, 0f, 46f)
        assertEquals(4f * maxOf(48f, dockIconSize(68f) + 12f) + 16f, big.dockHeight, .01f)
        assertEquals(4f * 48f + 16f, little.dockHeight, .01f)
        // Both docks are centred in their own span; the span only differs by the search size
        // (48.75 dp for the 68 dp preset vs the 48 dp minimum), so the centres sit within 1 dp.
        assertEquals((big.spanTop() + big.dockSpanBottom()) / 2f, big.dockCentre(), .01f)
        assertEquals((little.spanTop() + little.dockSpanBottom()) / 2f, little.dockCentre(), .01f)
        assertEquals(big.dockCentre(), little.dockCentre(), (big.searchSize - little.searchSize) / 2f + .01f)
        assertTrue(big.dockTop < little.dockTop)
    }

    @Test fun `short windows keep four 48 dp dock rows and scroll instead of overlapping the status`() {
        for (height in listOf(310f, 330f, 375f, 420f, 280f)) {
            val r = railLayout(height, 0f, 0f, 0f, 80f, preset.dockWidth, dockIconSize(66f), topMargin = 16f)
            assertTrue("$height", r.dockRowHeight >= 48f)
            assertTrue("$height", r.dockHeight >= 76f)
            assertTrue("$height", r.dockHeight <= 4f * maxOf(48f, dockIconSize(66f) + 12f) + 16f + .01f)
            assertTrue("$height", r.dockTop >= r.spanTop() - .01f)
            // The search keeps its bottom anchor even when the dock has to clamp at its minimum.
            assertEquals("$height", height - RAIL_BOTTOM_MARGIN_DP, r.searchBottom, .01f)
            if (r.dockHeight > 76f) assertTrue("$height", r.dockBottom + RAIL_GAP_DP <= r.searchTop + .01f)
        }
    }

    @Test fun `island slot is reserved directly below the status for every status height`() {
        for (status in listOf(0f, 60f, 120f, 180f)) {
            val r = railLayout(876f, 46f, 0f, 46f, status, preset.dockWidth, dockIconSize(66f), topMargin = 72f)
            val gap = if (status > 0f) RAIL_GAP_DP else 0f
            assertEquals(r.statusTop + status + gap, r.islandTop, .01f)
            assertEquals(0f, r.islandHeight, 0f)
            val withIsland = railLayout(876f, 46f, 0f, 46f, status, preset.dockWidth, dockIconSize(66f), topMargin = 72f, islandHeight = 90f)
            assertEquals(r.islandTop, withIsland.islandTop, 0f)
            assertEquals(90f, withIsland.islandHeight, 0f)
            // A taller island shrinks the span from the top, so the centred dock moves down by half.
            assertEquals(45f, withIsland.dockTop - r.dockTop, .01f)
        }
    }

    @Test fun `collapsed island reserves 0, 44 or 96 dp and folds further items into the last pill`() {
        assertEquals(0f, islandSlotHeight(0), 0f)
        assertEquals(44f, islandSlotHeight(1), 0f)
        assertEquals(44f + RAIL_GAP_DP + 44f, islandSlotHeight(2), 0f)
        assertEquals(96f, islandSlotHeight(2), 0f)
        // A third and any further item become a "+N" badge, not a third pill.
        assertEquals(44f * 3 + RAIL_GAP_DP * 2, islandSlotHeight(3), 0f)
        assertEquals(islandSlotHeight(3), islandSlotHeight(9), 0f)
        assertEquals(0f, islandSlotHeight(-1), 0f)
    }

    @Test fun `island slot collapses to 0 outright when the camera island is the active surface`() {
        // B61 rail hand-off: regardless of item count, the rail reserves nothing once live
        // activities are drawn around the camera cutout instead.
        assertEquals(0f, islandSlotHeight(0, cameraIslandActive = true), 0f)
        assertEquals(0f, islandSlotHeight(1, cameraIslandActive = true), 0f)
        assertEquals(0f, islandSlotHeight(3, cameraIslandActive = true), 0f)
        // Default parameter (existing call sites) is unaffected.
        assertEquals(44f, islandSlotHeight(1), 0f)
    }

    @Test fun `dynamic island height moves the centred dock down by half and leaves the search anchored`() {
        for ((geometry, height, top, cut) in listOf(Quad(cover, 876f, 46f, 46f), Quad(inner, 821f, 0f, 0f))) {
            val heights = listOf(0, 1, 2).map { count ->
                railLayout(height, top, 0f, cut, statusHeight, preset.dockWidth, dockIconSize(geometry.iconSize),
                    topMargin = geometry.contentTop, islandHeight = islandSlotHeight(count))
            }
            val (empty, one, two) = heights
            assertEquals(0f, empty.islandHeight, 0f); assertEquals(44f, one.islandHeight, 0f); assertEquals(96f, two.islandHeight, 0f)
            // Same island top whatever the item count: directly below the status block.
            assertEquals(empty.islandTop, one.islandTop, 0f); assertEquals(empty.islandTop, two.islandTop, 0f)
            // The dock span starts below the island; the dock stays centred in what remains.
            assertEquals(one.islandTop + 44f + RAIL_GAP_DP, one.spanTop(), .01f)
            assertEquals(two.islandTop + 96f + RAIL_GAP_DP, two.spanTop(), .01f)
            heights.forEach { assertEquals((it.spanTop() + it.dockSpanBottom()) / 2f, it.dockCentre(), .01f) }
            assertEquals(22f, one.dockTop - empty.dockTop, .01f)
            assertEquals(48f, two.dockTop - empty.dockTop, .01f)
            assertTrue(two.dockTop >= two.spanTop())
            // The dock keeps its size and the search its bottom anchor.
            heights.forEach { assertEquals(empty.dockHeight, it.dockHeight, 0f); assertEquals(empty.searchTop, it.searchTop, 0f) }
            // The expanded card (overlaying the dock span) has this much room before the dock.
            assertTrue(two.dockTop - two.islandTop - RAIL_GAP_DP > 96f)
        }
    }

    @Test fun `search is bottom-aligned with the page indicator row on both panels`() {
        val c = rail(cover, 876f, topInset = 46f, bottomInset = 0f, cutoutBottom = 46f)
        val i = rail(inner, 821f, topInset = 0f, bottomInset = 0f, cutoutBottom = 0f)
        // Bottom edge RAIL_BOTTOM_MARGIN_DP above the bottom inset: the page dots' baseline.
        assertEquals(876f - RAIL_BOTTOM_MARGIN_DP, c.searchBottom, .01f)
        assertEquals(821f - RAIL_BOTTOM_MARGIN_DP, i.searchBottom, .01f)
        assertEquals(c.searchSize, i.searchSize, 0f)
        // Same offset from the window bottom on both panels; not glued to the dock any more.
        assertEquals(876f - c.searchTop, 821f - i.searchTop, .01f)
        assertTrue(c.searchTop - c.dockBottom > RAIL_GAP_DP)
        assertTrue(i.searchTop - i.dockBottom > RAIL_GAP_DP)
        // Changing the dock position leaves the search where it is.
        assertEquals(c.searchTop, rail(cover, 876f, 46f, 0f, 46f, position = .75f).searchTop, 0f)
        assertEquals(i.searchTop, rail(inner, 821f, 0f, 0f, 0f, position = .25f).searchTop, 0f)
    }

    private data class Quad(val geometry: HomeGeometry, val height: Float, val topInset: Float, val cutout: Float)
}
