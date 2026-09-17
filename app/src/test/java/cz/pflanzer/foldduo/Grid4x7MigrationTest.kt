package cz.pflanzer.foldduo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 2026-09-17 noc "Mřížka 4x7 a obsah výš": GRID_ROWS 6 -> 7, HOME_CELLS 24 -> 28. Every earlier
 * saved-state schema (< 13, LauncherModel.kt) and layout-backup version (< 5, LayoutBackup.kt)
 * wrote 24-cell pages; both migrate through the same pure helpers tested directly here
 * ([migrateSchema12HomeCells]/[migrateSchema12LeadingCells]), widening each page so rows 0-5 keep
 * their exact slot and the new row 6 starts empty. A folder/pair tile is just an id string sitting
 * in one of these slots — a purely positional widen carries it along with no remapping of its own.
 */
class Grid4x7MigrationTest {

    @Test fun `migrateSchema12HomeCells is a no-op on empty input`() {
        assertEquals(emptyList<String?>(), migrateSchema12HomeCells(emptyList()))
    }

    @Test fun `migrateSchema12HomeCells keeps rows 0-5 in place and leaves the new row 6 empty`() {
        // One legacy (24-cell) page: "a" at local 0 (row 0 col 0), "z" at local 23 (row 5 col 3).
        val onePage = List<String?>(LEGACY_HOME_CELLS) { if (it == 0) "a" else if (it == 23) "z" else null }
        val migrated = migrateSchema12HomeCells(onePage)
        assertEquals(HOME_CELLS, migrated.size)
        assertEquals("a", migrated[0])
        assertEquals("z", migrated[23])
        // Every other cell of rows 0-5 (unset originally) stays unset, and the 4 new row-6 cells
        // at the end of the page are empty too.
        assertTrue((migrated.indices - setOf(0, 23)).all { migrated[it] == null })
    }

    @Test fun `migrateSchema12HomeCells widens every page independently, ids ride along untouched`() {
        val folderId = newFolderId()
        val pairId = newPairId()
        // Two legacy pages back to back: folder tile at page0 local5, pair tile at page1 local10.
        val legacy = MutableList<String?>(2 * LEGACY_HOME_CELLS) { null }
        legacy[5] = folderId
        legacy[LEGACY_HOME_CELLS + 10] = pairId
        val migrated = migrateSchema12HomeCells(legacy)
        // Same page, same local row/column as before - only the page's own pitch grew from 24 to 28.
        assertEquals(folderId, migrated[homeCellIndex(0, 5)])
        assertEquals(pairId, migrated[homeCellIndex(1, 10)])
        assertEquals(0, homeCellPage(5)); assertEquals(1, homeCellPage(LEGACY_HOME_CELLS + 10))
        // Nothing else leaked into either page's new row 6.
        assertTrue((24 until 28).all { migrated[homeCellIndex(0, it)] == null })
        assertTrue((24 until 28).all { migrated[homeCellIndex(1, it)] == null })
    }

    @Test fun `migrateSchema12HomeCells pads a short trailing page out to a full one before widening`() {
        // A normalized array commonly has its trailing nulls trimmed - fewer than 24 entries here.
        val trimmed = listOf<String?>("only")
        val migrated = migrateSchema12HomeCells(trimmed)
        assertEquals(HOME_CELLS, migrated.size)
        assertEquals("only", migrated[0])
        assertTrue(migrated.drop(1).all { it == null })
    }

    @Test fun `migrateSchema12LeadingCells pads the fixed leading array from 24 to 28`() {
        val legacy = List<String?>(LEGACY_HOME_CELLS) { if (it == 0) "hub" else if (it == 23) "tail" else null }
        val migrated = migrateSchema12LeadingCells(legacy)
        assertEquals(HOME_CELLS, migrated.size)
        assertEquals("hub", migrated[0])
        assertEquals("tail", migrated[23])
        assertTrue((24 until HOME_CELLS).all { migrated[it] == null })
    }

    @Test fun `widget placements need no remapping - page row column are independent of the cell count`() {
        // Documents the invariant the migration relies on: a WidgetPlacement never derives its
        // geometry from HOME_CELLS, so bumping GRID_ROWS/HOME_CELLS leaves an existing (valid)
        // placement's row/column/page exactly as saved.
        val widget = WidgetPlacement(0, CLOCK_WIDGET, 1, 0, 3, 2, 2)
        assertTrue(widget.row + widget.spanY <= GRID_ROWS)
        assertEquals(3, widget.row); assertEquals(1, widget.page)
    }

    // --- Backup round-trip: a version-4 (24-cell) export decodes as the current (28-cell) shape. ---
    // App-id positions can't be asserted end to end here (resolving an id to "available" needs a
    // real AppEntry, which needs a Bitmap the JVM test path can't construct - the same constraint
    // every other LayoutBackup test in this module works around by sticking to widgets/structure),
    // but everything that doesn't need app resolution - the leading array's width, widget geometry,
    // and the version gate itself - is fully exercised.

    private fun legacyRootFrom(state: LauncherState): JSONObject {
        val root = JSONObject(encodeLayoutBackup(state, emptyList(), "scope-a"))
        root.put("version", 4)
        return root
    }

    @Test fun `a version 4 backup's leading array widens from 24 to 28 cells on decode`() {
        val root = legacyRootFrom(LauncherState(loading = false))
        root.put("homeSlots", JSONArray())
        val legacyLeading = JSONArray().apply { repeat(LEGACY_HOME_CELLS) { put(JSONObject.NULL) } }
        root.put("leadingSlots", legacyLeading)
        val preview = decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a")
        assertEquals(HOME_CELLS, preview.layout.leadingSlots.size)
    }

    @Test fun `a version 4 backup rejects a leading array of the current (28) length`() {
        val root = legacyRootFrom(LauncherState(loading = false))
        root.put("homeSlots", JSONArray())
        // version 4 must still be exactly the LEGACY length - the current 28-length shape belongs
        // to version 5 only, so this must fail loudly rather than silently read garbage rows.
        val currentLengthLeading = JSONArray().apply { repeat(HOME_CELLS) { put(JSONObject.NULL) } }
        root.put("leadingSlots", currentLengthLeading)
        assertTrue(runCatching { decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a") }.isFailure)
    }

    @Test fun `a version 4 backup's builtin widget geometry decodes unchanged, rows 0-5 only`() {
        val root = legacyRootFrom(LauncherState(loading = false))
        root.put("homeSlots", JSONArray())
        root.put("leadingSlots", JSONArray().apply { repeat(LEGACY_HOME_CELLS) { put(JSONObject.NULL) } })
        root.put("widgets", JSONArray().put(JSONObject()
            .put("slot", 0).put("page", 1).put("column", 0).put("row", 3).put("spanX", 2).put("spanY", 2)
            .put("builtinId", CLOCK_ANALOG_WIDGET).put("appearance", "Auto")))
        val preview = decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a")
        val placement = preview.layout.placement(0)
        assertEquals(CLOCK_ANALOG_WIDGET, placement?.id)
        assertEquals(1, placement?.page); assertEquals(3, placement?.row); assertEquals(0, placement?.column)
    }

    @Test fun `the current version 5 backup keeps the 28-cell leading length round trip`() {
        val raw = encodeLayoutBackup(LauncherState(loading = false), emptyList(), "scope-a")
        assertEquals(5, JSONObject(raw).getInt("version"))
        val preview = decodeLayoutBackup(raw, emptyList(), emptyList(), "scope-a")
        assertEquals(HOME_CELLS, preview.layout.leadingSlots.size)
    }
}
