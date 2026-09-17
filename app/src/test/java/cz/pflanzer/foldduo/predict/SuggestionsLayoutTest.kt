package cz.pflanzer.foldduo.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsLayoutTest {
    @Test fun `tile height grows exactly with icon size, never smaller than icon plus label`() {
        assertEquals(66f + 4f + 14f, suggestionTileHeightDp(66f), 0f)
        assertEquals(40f + 4f + 14f, suggestionTileHeightDp(40f), 0f)
        // A bigger icon setting (App icon size slider, 40..68 dp) only ever grows the tile.
        assertTrue(suggestionTileHeightDp(68f) > suggestionTileHeightDp(40f))
    }

    @Test fun `strip height is both paddings plus the caption plus one full tile, nothing coerced smaller`() {
        val iconSize = 56f
        val expected = 2 * SUGGESTIONS_STRIP_PADDING_DP + SUGGESTIONS_CAPTION_HEIGHT_DP +
            SUGGESTIONS_CAPTION_GAP_DP + suggestionTileHeightDp(iconSize)
        assertEquals(expected, suggestionsRowHeightDp(iconSize), 0f)
        // Regression guard for the 2026-09-17 clipping report: the strip's height always has room
        // for the full tile height, it never just reserves the caption or a fixed icon size.
        assertTrue(suggestionsRowHeightDp(iconSize) >= suggestionTileHeightDp(iconSize))
        // Across the whole "App icon size" slider range the strip only ever grows, never shrinks
        // (so a taller icon setting can never end up clipped against a shorter cached strip).
        var previous = suggestionsRowHeightDp(40f)
        for (size in 41..68) {
            val next = suggestionsRowHeightDp(size.toFloat())
            assertTrue("size $size should not shrink the strip", next >= previous)
            previous = next
        }
    }

    @Test fun `four tiles exactly fill the grid's own width, nothing left for SpaceEvenly to overflow`() {
        val gridWidth = 534f - 60f // leading pane minus its own margins, from LeadingPaneTest
        val cellWidth = suggestionCellWidthDp(gridWidth)
        assertEquals(gridWidth / 4f, cellWidth, 0f)
        assertEquals(SUGGESTIONS_TILE_COUNT, 4)
        assertEquals(gridWidth, cellWidth * SUGGESTIONS_TILE_COUNT, .001f)
    }

    @Test fun `a narrower pane still divides evenly into four, never producing a zero or negative cell`() {
        for (gridWidth in listOf(192f, 300f, 458f, 534f, 720f)) {
            val cellWidth = suggestionCellWidthDp(gridWidth)
            assertTrue(cellWidth > 0f)
            assertEquals(gridWidth, cellWidth * SUGGESTIONS_TILE_COUNT, .001f)
        }
    }
}
