package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test

class LayoutModelTest {
    @Test fun `grid keeps the rail inset on the physical Fold presets`() {
        val p = LayoutPreset(rowGap = 16.263264f, dockWidth = 73.46808f, dockPosition = .503011f)
        for (labels in listOf(true, false)) {
            val g = homeGeometry(475.43f, 696.38f, p, labels, labelHeight = 21.12f)
            assertEquals(railWidthDp(p.dockWidth), g.railWidth, .01f)
            assertEquals(g.homeWidth, HOME_START_DP + g.gridWidth + g.railWidth, .01f)
        }
    }

    @Test fun `railInsetDp shrinks only the grid, never the pane box`() {
        // "Spread jako jedna plocha" (17. 9. noc): HomeGeometry.railInsetDp is the SAME value as
        // railWidth, but it is what actually shrinks gridWidth — homeWidth (the pane box a
        // strip-based pager positions, WorkspacePageMotion.kt's expandedPaneLayout) must stay the
        // rail's own business alone, or Today and Home stop sharing one uniform pane pitch again.
        val seam = FoldSeam(544f, 10f)
        val narrowDock = homeGeometry(1088f, 821f, LayoutPreset(dockWidth = 56f), true, seamXDp = seam.xDp, seamGutterDp = seam.gutterDp)
        val wideDock = homeGeometry(1088f, 821f, LayoutPreset(dockWidth = 84f), true, seamXDp = seam.xDp, seamGutterDp = seam.gutterDp)
        assertEquals(narrowDock.railInsetDp, narrowDock.railWidth, 0f)
        assertTrue(wideDock.railInsetDp > narrowDock.railInsetDp)
        // A wider rail eats into the grid...
        assertTrue(wideDock.gridWidth < narrowDock.gridWidth)
        // ...but never into the pane box itself: both dock widths still get the exact same,
        // uniform (seam-derived) pane width.
        assertEquals(narrowDock.homeWidth, wideDock.homeWidth, 0f)
        assertEquals(534f, narrowDock.homeWidth, 0f)
    }
    @Test fun `dock position presets are inert but still persisted`() {
        val p = LayoutPreset(dockPosition = .43f, dockAlignToGrid = false)
        assertEquals(homeGeometry(475f, 700f, p.copy(dockAlignToGrid = true), true), homeGeometry(475f, 700f, p, true))
        assertEquals(homeGeometry(475f, 700f, p.copy(dockPosition = .7f), true), homeGeometry(475f, 700f, p, true))
        assertEquals(.43f, p.copy(dockAlignToGrid = true).sanitized().dockPosition)
        assertFalse(p.sanitized().dockAlignToGrid)
    }
    @Test fun `hiding labels preserves row rhythm and large text gains room`() {
        val regular = homeGeometry(475f, 700f, LayoutPreset(), true)
        val hidden = homeGeometry(475f, 700f, LayoutPreset(), false)
        val largeText = homeGeometry(475f, 700f, LayoutPreset(), true, labelHeight = 34f)
        assertEquals(regular.rowHeight, hidden.rowHeight)
        assertTrue(largeText.rowHeight >= regular.rowHeight + 14f)
        assertTrue(regular.iconSize / (regular.gridWidth / 4f) in .70f.. .77f)
    }
    @Test fun `new icon default preserves tuned presets and current schema values`() {
        assertEquals(66f, upgradePreset(LayoutPreset(iconSize = 60f), 2, false).iconSize)
        val custom = LayoutPreset(62f, 13f, 72f, .67f)
        assertEquals(custom, upgradePreset(custom, 2, true))
        assertEquals(LayoutPreset(iconSize = 60f), upgradePreset(LayoutPreset(iconSize = 60f), 3, false))
        assertEquals(LayoutPreset(), upgradePreset(LayoutPreset(54f, 12f, 64f), 1, false))
        assertEquals(LayoutPreset(), upgradePreset(LayoutPreset(58f, 12f, 64f), 1, true))
    }
    @Test fun `grid and rail fit at Fold cover inner and short landscape sizes`() {
        val sizes = listOf(475f to 700f, 933f to 650f, 850f to 840f, 360f to 620f, 740f to 280f)
        for ((width, height) in sizes) {
            val p = LayoutPreset()
            val g = homeGeometry(width, height, p, true)
            assertTrue(g.gridWidth + p.dockWidth + 24f <= g.homeWidth)
            assertTrue(g.iconSize + 8f <= g.gridWidth / 4f)
            // 2026-09-17 noc "Mřížka 4x7 a obsah výš": contentTop no longer centres on window
            // height — with no cutout it is always the rail's own top margin.
            assertEquals("$width x $height", RAIL_TOP_MARGIN_DP, g.contentTop, 0f)
        }
    }

    @Test fun `content sits at the rail's clock line, pushed down only by a cutout that reaches lower`() {
        val p = LayoutPreset()
        assertEquals(RAIL_TOP_MARGIN_DP, homeGeometry(475f, 751f, p, true).contentTop, 0f)
        // A cutout bottom below RAIL_TOP_MARGIN_DP - 8 still doesn't move the first row.
        assertEquals(RAIL_TOP_MARGIN_DP, homeGeometry(475f, 751f, p, true, cutoutBottomDp = 2f).contentTop, 0f)
        // The Fold 8 cover camera cutout reaches well past that: contentTop clears its bottom edge
        // plus 8 dp, dropping the old vertically-centred band entirely.
        val withCutout = homeGeometry(475f, 751f, p, true, cutoutBottomDp = COVER_CUTOUT_DP)
        assertEquals(COVER_CUTOUT_DP + 8f, withCutout.contentTop, .001f)
        assertTrue(withCutout.contentTop > RAIL_TOP_MARGIN_DP)
    }

    @Test fun `seven rows plus two widget-height rows fit the measured cover and inner panels with real icons`() {
        // Fold 8, as measured on the device: cover home pane 475 x 751 dp, inner right pane ~466 x 704 dp.
        for ((width, height, cutout) in listOf(Triple(475f, 751f, COVER_CUTOUT_DP), Triple(466f, 704f, 0f))) {
            val g = homeGeometry(width, height, LayoutPreset(), true, cutoutBottomDp = cutout)
            assertTrue("icon size $width x $height", g.iconSize >= 56f)
            val contentHeight = g.widgetHeight + 18f + (GRID_ROWS - 2) * g.rowHeight
            assertTrue("content $width x $height fits: top=${g.contentTop} content=$contentHeight window=$height",
                g.contentTop + contentHeight <= height + 1f)
        }
    }
    @Test fun `expanded pane appears from actual window width`() {
        assertFalse(homeGeometry(475f, 700f, LayoutPreset(), true).expanded)
        assertTrue(homeGeometry(933f, 650f, LayoutPreset(), true).expanded)
        assertTrue(homeGeometry(933f, 650f, LayoutPreset(), true).homeWidth <= 460f)
        assertEquals(FOLD_THRESHOLD_DP, 650f, 0f)
        assertFalse(homeGeometry(FOLD_THRESHOLD_DP - 1f, 700f, LayoutPreset(), true).expanded)
        assertTrue(homeGeometry(FOLD_THRESHOLD_DP, 700f, LayoutPreset(), true).expanded)
    }
    @Test fun `fold seam makes the right pane the home width`() {
        // Fold 8 inner panel: 1088 x 821 dp, FoldingFeature at 1224 px = 544 dp.
        val seamed = homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = 544f, seamGutterDp = 10f)
        assertTrue(seamed.expanded)
        assertEquals(534f, seamed.homeWidth, 0f)
        assertEquals(534f - 68f - 44f, seamed.gridWidth, 0f)
        val defaultGutter = homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = 544f)
        assertEquals(1088f - 544f - FOLD_GUTTER_DP, defaultGutter.homeWidth, 0f)
        // Off-center hinges still follow the feature, not the 56/44 split.
        assertEquals(1088f - 600f - 10f, homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = 600f).homeWidth, 0f)
        // Without a seam the historical split remains; everything but the pane widths matches.
        val fallback = homeGeometry(1088f, 821f, LayoutPreset(), true)
        assertEquals(460f, fallback.homeWidth, 0f)
        assertEquals(fallback.rowHeight, seamed.rowHeight, 0f)
        assertEquals(fallback.contentTop, seamed.contentTop, 0f)
        assertEquals(fallback.railWidth, seamed.railWidth, 0f)
    }
    @Test fun `fold seam is ignored on the cover and when it cannot split the window`() {
        assertEquals(555f, homeGeometry(555f, 876f, LayoutPreset(), true, seamXDp = 277f).homeWidth, 0f)
        val fallback = homeGeometry(1088f, 821f, LayoutPreset(), true).homeWidth
        assertEquals(fallback, homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = 0f).homeWidth, 0f)
        assertEquals(fallback, homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = 1085f).homeWidth, 0f)
        assertEquals(fallback, homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = Float.NaN).homeWidth, 0f)
    }
    @Test fun `reconciliation preserves custom order while handling installs removals duplicates`() {
        assertEquals(listOf("c", "a", "d"), reconcileOrder(listOf("c", "gone", "a", "c"), listOf("a", "c", "d")))
        assertEquals(emptyList<String>(), reconcileOrder(listOf("a"), emptyList()))
    }
    @Test fun `reordering across page boundary does not drop apps`() {
        val apps = (0..31).map { "app$it" }
        val moved = moveApp(apps, "app16", -1)
        assertEquals("app16", moved[15])
        assertEquals("app15", moved[16])
        assertEquals(apps.toSet(), moved.toSet())
        assertEquals("app31", moveApp(apps, "app31", -100).first())
        assertEquals(apps, moveApp(apps, "missing", 1))
    }
    @Test fun `out of range preferences are constrained before layout`() {
        val p = LayoutPreset(999f, -40f, 2f, 12f).sanitized()
        assertEquals(68f, p.iconSize); assertEquals(0f, p.rowGap)
        assertEquals(56f, p.dockWidth); assertEquals(.75f, p.dockPosition)
    }
    @Test fun `new installs and refresh do not pin apps and empty home stays empty`() {
        assertEquals(listOf("c", "a"), reconcilePins(listOf("c", "gone", "a", "c"), listOf("a", "c", "new")))
        assertEquals(emptyList<String>(), reconcilePins(emptyList(), listOf("new")))
    }
    @Test fun `migration uses suggestions for auto sorted legacy home but retains manual first page`() {
        val installed = (0..31).map { "app%02d".format(it) }
        assertEquals(listOf("app20", "app10"), migrateHomePins(installed, installed, listOf("app20", "app10", "gone")))
        val custom = listOf("app31") + installed.dropLast(1)
        assertEquals(custom.take(16), migrateHomePins(custom, installed, listOf("app20")))
    }
    @Test fun `home always has a page independently of the library`() {
        assertEquals(1, homePageCount(0)); assertEquals(1, homePageCount(HOME_CELLS)); assertEquals(2, homePageCount(HOME_CELLS + 1))
    }
}
