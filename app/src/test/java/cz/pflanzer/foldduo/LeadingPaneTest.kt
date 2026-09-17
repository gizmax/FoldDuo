package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.notifications.HUB_MAX_ROWS
import cz.pflanzer.foldduo.notifications.hubRowsReserved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadingPaneTest {
    // Fold 8 inner panel with the seam: leading pane 534 dp wide, Home grid geometry from the right pane.
    private val geometry = homeGeometry(1088f, 821f, LayoutPreset(), true, seamXDp = 544f, seamGutterDp = 10f)
    private val panes = expandedPaneLayout(1088f, geometry.homeWidth, geometry.gridWidth, FoldSeam(544f, 10f))

    @Test fun `leading grid fills the pane between the options margin and the gutter`() {
        assertEquals(534f, panes.leadingWidth, 0f)
        val width = leadingGridWidth(panes.leadingWidth)
        assertEquals(534f - HOME_START_DP - LEADING_END_DP, width, .001f)
        // Wider than the Home grid, which gives up the rail column; both keep four columns.
        assertTrue(width > geometry.gridWidth)
        assertEquals(geometry.homeWidth - geometry.railWidth - HOME_START_DP, geometry.gridWidth, .001f)
        // A full-span widget is the grid minus the cell gap, like a 4-wide widget on Home.
        val sizing = leadingGridSizing(panes.leadingWidth, geometry)
        assertEquals(width / GRID_COLUMNS, sizing.cellWidthDp, .001f)
        assertEquals(width - 10f, sizing.contentSize(0, 0, GRID_COLUMNS, GRID_ROWS).widthDp, .001f)
        // Narrow windows never collapse the grid below the Home minimum.
        assertEquals(192f, leadingGridWidth(100f), 0f)
    }

    @Test fun `leading sizing keeps the home row pitches`() {
        val sizing = leadingGridSizing(panes.leadingWidth, geometry)
        val topPitch = (geometry.widgetHeight + 18f) / 2f
        assertEquals(topPitch, sizing.topRowHeightDp, .001f)
        assertEquals(geometry.rowHeight, sizing.appRowHeightDp, .001f)
        assertEquals(minOf(topPitch, geometry.rowHeight), sizing.cellHeightDp, .001f)
        assertEquals(maxOf(topPitch, geometry.rowHeight), sizing.maximumCellHeightDp, .001f)
        // Rows 0-1 are exactly the Home widget height; the whole pane is the GRID_ROWS-row grid height.
        assertEquals(geometry.widgetHeight, sizing.contentSize(0, 0, GRID_COLUMNS, 2).heightDp, .001f)
        assertEquals(2 * topPitch + (GRID_ROWS - 2) * geometry.rowHeight - 18f,
            sizing.contentSize(0, 0, GRID_COLUMNS, GRID_ROWS).heightDp, .001f)
    }

    @Test fun `empty leading pane shows one virtual photos card that fills it`() {
        val home = listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2))
        val blank = List<String?>(HOME_CELLS) { null }
        assertTrue(leadingPaneEmpty(blank, home))
        val content = leadingContent(blank, home)
        assertEquals(listOf(PHOTOS_WIDGET), content.map { it.id })
        val photos = content.single()
        assertEquals(WidgetPlacement(-1, PHOTOS_WIDGET, -1, 0, 0, GRID_COLUMNS, GRID_ROWS), photos)
        assertTrue(photos.isLeadingDefault())
        // Virtual defaults never enter a layout: the model rejects negative slots.
        val empty = HomeLayout(emptyList(), emptyList())
        assertTrue(DEFAULT_LEADING_PLACEMENTS.all { placeWidget(empty, it) == empty })
        assertFalse(WidgetPlacement(3, PHOTOS_WIDGET, -1, 0, 0, GRID_COLUMNS, GRID_ROWS).isLeadingDefault())
    }

    @Test fun `anything placed on the leading pane replaces the default`() {
        val blank = List<String?>(HOME_CELLS) { null }
        // A widget on page -1.
        val widget = listOf(WidgetPlacement(7, 41, -1, 0, 0, 4, 2))
        assertFalse(leadingPaneEmpty(blank, widget))
        assertEquals(listOf(7), leadingContent(blank, widget).map { it.slot })
        // An icon on page -1 (leadingSlots), even with no widgets.
        val icon = List<String?>(HOME_CELLS) { if (it == 5) "app" else null }
        assertFalse(leadingPaneEmpty(icon, emptyList()))
        assertTrue(leadingContent(icon, emptyList()).isEmpty())
        // A folder on page -1 counts like an icon; blank strings do not.
        assertFalse(leadingPaneEmpty(List(HOME_CELLS) { if (it == 0) "folder:x" else null }, emptyList()))
        assertTrue(leadingPaneEmpty(List(HOME_CELLS) { "" }, emptyList()))
        // A widget still being set up on page -1 already hides the default; one on Home does not.
        assertFalse(leadingPaneEmpty(blank, emptyList(), pending = WidgetPlacement(3, 9, -1, 0, 0, 2, 2)))
        assertTrue(leadingPaneEmpty(blank, emptyList(), pending = WidgetPlacement(3, 9, 0, 0, 0, 2, 2)))
        // Home widgets alone leave the pane empty.
        assertTrue(leadingPaneEmpty(blank, listOf(WidgetPlacement(1, 9, 0, 0, 0, 2, 2), WidgetPlacement(2, 9, 1, 0, 6, 4, 4))))
    }

    @Test fun `built-ins picked for the leading pane take their pane footprint`() {
        // Photos replaces the default it stands for: the whole 4 x 6 pane.
        assertEquals(WidgetSpan(GRID_COLUMNS, GRID_ROWS), leadingBuiltinSpan(PHOTOS_WIDGET))
        assertNotEquals(builtinWidgetSpan(PHOTOS_WIDGET), leadingBuiltinSpan(PHOTOS_WIDGET))
        // Everything else keeps its Home footprint (builtinTodayRows is never taller than it).
        for (id in listOf(CLOCK_ANALOG_WIDGET, CALENDAR_WIDGET, BATTERIES_WIDGET, CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET)) {
            assertEquals("widget $id", builtinWidgetSpan(id), leadingBuiltinSpan(id))
        }
        // The Photos footprint is placeable on an empty leading pane through the ordinary model path.
        val empty = HomeLayout(emptyList(), emptyList())
        val span = leadingBuiltinSpan(PHOTOS_WIDGET)
        val draft = widgetCandidate(empty, 3, homeCellIndex(-1, 0), span.width, span.height)
        assertEquals(WidgetPlacement(3, EMPTY_WIDGET, -1, 0, 0, GRID_COLUMNS, GRID_ROWS), draft)
        assertEquals(PHOTOS_WIDGET, placeWidget(empty, draft!!.copy(id = PHOTOS_WIDGET)).placement(3)?.id)
    }

    @Test fun `leading pane accepts icons and widgets anywhere like a home page`() {
        val blank = HomeLayout(listOf("home"), listOf(null, null, null, null))
        // An icon dropped from Home into the middle of the leading grid.
        val moved = dropApp(blank, "home", DropTarget.Home(homeCellIndex(-1, 13)))
        assertEquals("home", moved.slotAt(homeCellIndex(-1, 13)))
        assertTrue(moved.slots.isEmpty())
        assertFalse(leadingPaneEmpty(moved.leadingSlots, moved.widgetPlacements))
        // A widget beside it and a widget moved back to Home page 0.
        val widget = widgetCandidate(moved, 4, homeCellIndex(-1, 2), 2, 2)!!.copy(id = 26)
        val withWidget = placeWidget(moved, widget)
        assertEquals(widget, withWidget.placement(4))
        val back = moveWidget(withWidget, 4, homeCellIndex(0, 0))
        assertEquals(0, back.placement(4)?.page)
        // The pane refuses what Home refuses: a widget over the icon.
        assertEquals(withWidget, moveWidget(withWidget, 4, homeCellIndex(-1, 12)))
    }

    @Test fun `B35 top slots reserve rows without ever collapsing the grid to nothing`() {
        assertEquals(GRID_ROWS, leadingAvailableRows(0))
        assertEquals(GRID_ROWS - 1, leadingAvailableRows(SUGGESTIONS_ROW_RESERVED_ROWS))
        assertEquals(GRID_ROWS - 2, leadingAvailableRows(2))
        assertEquals(1, leadingAvailableRows(GRID_ROWS + 5))
    }

    @Test fun `B34-B35 stacking reserves hub rows plus the suggestions row when both show, 0 when neither`() {
        // Neither the hub nor Suggestions is showing: nothing reserved, the full grid is available.
        assertEquals(0, leadingTopSlotRows(hubGroupCount = 0, suggestionsShowing = false))
        assertEquals(GRID_ROWS, leadingAvailableRows(leadingTopSlotRows(0, false)))
        // Suggestions alone: exactly its own reserved row.
        assertEquals(SUGGESTIONS_ROW_RESERVED_ROWS, leadingTopSlotRows(hubGroupCount = 0, suggestionsShowing = true))
        // The hub alone: exactly hubRowsReserved's own count, capped the same way.
        assertEquals(hubRowsReserved(2), leadingTopSlotRows(hubGroupCount = 2, suggestionsShowing = false))
        assertEquals(HUB_MAX_ROWS, leadingTopSlotRows(hubGroupCount = 50, suggestionsShowing = false))
        // Both showing: hub rows plus the suggestions row, and the grid gives up exactly that many.
        val bothRows = hubRowsReserved(2) + SUGGESTIONS_ROW_RESERVED_ROWS
        assertEquals(bothRows, leadingTopSlotRows(hubGroupCount = 2, suggestionsShowing = true))
        assertEquals(GRID_ROWS - bothRows, leadingAvailableRows(leadingTopSlotRows(2, true)))
    }

    @Test fun `the collapsed compact pill reserves one row regardless of group count`() {
        assertEquals(1, leadingTopSlotRows(hubGroupCount = 1, suggestionsShowing = false, hubExpanded = false))
        assertEquals(1, leadingTopSlotRows(hubGroupCount = 50, suggestionsShowing = false, hubExpanded = false))
        // Still nothing with no groups, pill or not.
        assertEquals(0, leadingTopSlotRows(hubGroupCount = 0, suggestionsShowing = false, hubExpanded = false))
        // Suggestions still add their own row on top of the collapsed pill's one.
        assertEquals(1 + SUGGESTIONS_ROW_RESERVED_ROWS,
            leadingTopSlotRows(hubGroupCount = 3, suggestionsShowing = true, hubExpanded = false))
    }

    @Test fun `expanding the pill reserves up to HUB_MAX_ROWS, same as the default`() {
        assertEquals(leadingTopSlotRows(hubGroupCount = 2, suggestionsShowing = false),
            leadingTopSlotRows(hubGroupCount = 2, suggestionsShowing = false, hubExpanded = true))
        assertEquals(HUB_MAX_ROWS, leadingTopSlotRows(hubGroupCount = 50, suggestionsShowing = false, hubExpanded = true))
    }
}
