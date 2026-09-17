package cz.pflanzer.foldduo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairEditingTest {
    private val pairId = "pair:123e4567-e89b-12d3-a456-426614174000"
    private val folderId = "folder:123e4567-e89b-12d3-a456-426614174001"

    // --- pure decision: hold duration -> Folder vs. Pair ------------------------------------

    @Test fun quickReleaseCreatesAFolder() {
        assertEquals(AppOnAppDropOutcome.FOLDER, appOnAppDropOutcome(0))
        assertEquals(AppOnAppDropOutcome.FOLDER, appOnAppDropOutcome(PAIR_HOLD_THRESHOLD_MS - 1))
    }

    @Test fun holdingPastTheThresholdCreatesAPair() {
        assertEquals(AppOnAppDropOutcome.PAIR, appOnAppDropOutcome(PAIR_HOLD_THRESHOLD_MS))
        assertEquals(AppOnAppDropOutcome.PAIR, appOnAppDropOutcome(PAIR_HOLD_THRESHOLD_MS + 500))
    }

    @Test fun customThresholdIsRespected() {
        assertEquals(AppOnAppDropOutcome.PAIR, appOnAppDropOutcome(100, thresholdMs = 100))
        assertEquals(AppOnAppDropOutcome.FOLDER, appOnAppDropOutcome(99, thresholdMs = 100))
    }

    @Test fun isPlainAppShortcutRejectsContainerIdsAndNull() {
        assertTrue(isPlainAppShortcut("com.example/.Main"))
        assertFalse(isPlainAppShortcut(null))
        assertFalse(isPlainAppShortcut(folderId))
        assertFalse(isPlainAppShortcut(pairId))
    }

    // --- creation -----------------------------------------------------------------------------

    @Test fun createPairUsesTargetCellWithoutMovingUnrelatedApps() {
        val before = HomeLayout(listOf("a", "other", "b"), listOf(null, "dock", null, null))
        val next = createPair(before, PairEntry(pairId, "a", "b"), 0)
        assertEquals(listOf(pairId, "other"), next.slots)
        assertEquals(PairEntry(pairId, "a", "b"), next.pair(pairId))
        assertEquals(before.dock, next.dock)
    }

    @Test fun createPairRejectsWidgetCollisionSameAppTwiceAndFolderMembership() {
        val widget = WidgetPlacement(7, 100, 0, 0, 0, 2, 2)
        val before = HomeLayout(listOf("a", "b"), emptyList(), listOf(widget))
        assertSame(before, createPair(before, PairEntry(pairId, "a", "b"), 0))
        assertSame(before, createPair(before, PairEntry(pairId, "a", "a"), 2))
        val folder = FolderEntry(folderId, "Group", listOf("a", "c"))
        val withFolder = HomeLayout(listOf(folderId, "b"), emptyList(), folders = listOf(folder))
        assertSame(withFolder, createPair(withFolder, PairEntry(pairId, "a", "b"), 1))
    }

    @Test fun createPairRejectsAnAppAlreadyInAnotherPair() {
        val existing = PairEntry("pair:00000000-0000-0000-0000-000000000000", "a", "b")
        val before = HomeLayout(listOf(existing.id, "c"), emptyList(), pairs = listOf(existing))
        assertSame(before, createPair(before, PairEntry(pairId, "a", "c"), 1))
    }

    // --- swap / split / remove ------------------------------------------------------------------

    @Test fun swapSidesExchangesFirstAndSecond() {
        val before = HomeLayout(listOf(pairId), emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = swapPairSides(before, pairId)
        assertEquals(PairEntry(pairId, "b", "a"), next.pair(pairId))
        assertSame(before, swapPairSides(before, "pair:nope"))
    }

    @Test fun splitPutsFirstBackAtTheSameCellAndSecondInTheNearestFreeCell() {
        val before = HomeLayout(listOf(pairId, "other"), emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = splitPair(before, pairId)
        assertEquals("a", next.slots[0])
        assertTrue(next.pairs.isEmpty())
        assertTrue("b" in next.slots)
        assertEquals(2, next.slots.count { it == "a" || it == "b" })
    }

    @Test fun splitOnAFullPageDropsTheSecondApp() {
        val fullPage = List(HOME_CELLS - 1) { "app$it" } + listOf(pairId)
        val before = HomeLayout(fullPage, emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = splitPair(before, pairId)
        assertEquals("a", next.slots.last())
        assertTrue(next.pairs.isEmpty())
        assertFalse("b" in next.slots)
    }

    @Test fun splitOnTheLeadingCanvasStaysOnTheLeadingCanvas() {
        val leading = List(HOME_CELLS) { if (it == 0) pairId else null }
        val before = HomeLayout(emptyList(), emptyList(), leadingSlots = leading, pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = splitPair(before, pairId)
        assertEquals("a", next.leadingSlots[0])
        assertTrue("b" in next.leadingSlots)
        assertTrue(next.slots.none { it == "b" })
    }

    @Test fun removeDropsBothMembersFromHomeEntirely() {
        val before = HomeLayout(listOf(pairId, "other"), listOf("d0", null, null, null), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = removePair(before, pairId)
        // Same "leaves a gap, does not compact" rule dropApp/removeAppFromFolder use elsewhere —
        // the pair's own cell just goes empty; "other" does not shift to fill it.
        assertEquals(listOf(null, "other"), next.slots)
        assertTrue(next.pairs.isEmpty())
        assertFalse("a" in next.slots); assertFalse("b" in next.slots)
    }

    // --- reconciliation on uninstall --------------------------------------------------------

    @Test fun reconcilingAfterOneMemberUninstalledPromotesTheSurvivor() {
        val before = HomeLayout(listOf(pairId, "other"), emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = reconcilePairs(before, setOf("a"))
        assertEquals(listOf("b", "other"), next.slots)
        assertTrue(next.pairs.isEmpty())
    }

    @Test fun reconcilingAfterBothMembersUninstalledJustDropsTheCell() {
        val before = HomeLayout(listOf(pairId, "other"), emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = reconcilePairs(before, setOf("a", "b"))
        // "a" is processed first, promoting "b" into the cell — then the final pass removes "b"
        // too, since it is also being uninstalled (same simultaneous-removal fix as folders').
        assertEquals(listOf(null, "other"), next.slots)
        assertTrue(next.pairs.isEmpty())
    }

    @Test fun reconcilingAnUnrelatedRemovalLeavesThePairAlone() {
        // "nonexistent" isn't a pair member or a plain shortcut anywhere in this layout — the same
        // "already removed elsewhere" case LauncherModel.refresh() always feeds reconcilePairs
        // (plain shortcuts are stripped from homeSlots/dock before it ever runs).
        val before = HomeLayout(listOf(pairId, "other"), emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        val next = reconcilePairs(before, setOf("nonexistent"))
        assertEquals(before, next)
    }

    // --- dropApp / dock interaction with pairs ------------------------------------------------

    @Test fun pairMembersCannotBeDraggedOutIndividuallyByDropApp() {
        val before = HomeLayout(listOf(pairId), emptyList(), pairs = listOf(PairEntry(pairId, "a", "b")))
        assertSame(before, dropApp(before, "a", DropTarget.Home(3)))
    }

    @Test fun pairIdItselfCannotBePlacedInTheDockAndNeitherCanItsMembers() {
        val before = HomeLayout(listOf(pairId), listOf(null, null, null, null), pairs = listOf(PairEntry(pairId, "a", "b")))
        assertFalse(canPlaceInDock(before, pairId))
        assertFalse(canPlaceInDock(before, "a"))
        assertFalse(canPlaceInDock(before, "b"))
    }

    // --- migration/backup round-trip ---------------------------------------------------------
    // No AppEntry can be constructed in a plain JVM test (it holds a real android.graphics.Bitmap,
    // and resolving an "apps" metadata entry at all runs it through android.content.ComponentName,
    // which throws under the unmocked Android stub jar this module's unit tests run against) — the
    // same reason every *existing* LayoutBackup test in WidgetAppearanceTest.kt sticks to widget-only
    // states. These stay within that same constraint: JSON shape from a real encode, backward
    // compatibility with a payload from before pairs existed, and decode's structural validation
    // (duplicate/self/orphan pair references), none of which ever reach app-metadata resolution.

    @Test fun encodedBackupWritesThePairsArray() {
        val pair = PairEntry(pairId, "com.example.a/.Main", "com.example.b/.Main")
        val state = LauncherState(pairs = listOf(pair), loading = false)
        val root = JSONObject(encodeLayoutBackup(state, emptyList(), "scope-a"))
        assertEquals(LAYOUT_BACKUP_VERSION, root.getInt("version"))
        val pairsJson = root.getJSONArray("pairs")
        assertEquals(1, pairsJson.length())
        assertEquals(pair.id, pairsJson.getJSONObject(0).getString("id"))
        assertEquals(pair.first, pairsJson.getJSONObject(0).getString("first"))
        assertEquals(pair.second, pairsJson.getJSONObject(0).getString("second"))
    }

    @Test fun aBackupWrittenBeforePairsExistedStillDecodesWithNoPairs() {
        val root = JSONObject(encodeLayoutBackup(LauncherState(loading = false), emptyList(), "scope-a"))
        assertTrue(root.has("pairs"))
        root.remove("pairs")
        val preview = decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a")
        assertTrue(preview.layout.pairs.isEmpty())
        assertEquals(0, preview.pairCount)
    }

    @Test fun decodeRejectsDuplicatePairIdsAndSelfPairedEntries() {
        fun withPairs(pairsJson: org.json.JSONArray): String {
            val root = JSONObject(encodeLayoutBackup(LauncherState(loading = false), emptyList(), "scope-a"))
            root.put("pairs", pairsJson)
            return root.toString()
        }
        val duplicate = withPairs(org.json.JSONArray()
            .put(JSONObject().put("id", pairId).put("first", "a").put("second", "b"))
            .put(JSONObject().put("id", pairId).put("first", "c").put("second", "d")))
        assertTrue(runCatching { decodeLayoutBackup(duplicate, emptyList(), emptyList(), "scope-a") }.isFailure)
        val selfPaired = withPairs(org.json.JSONArray()
            .put(JSONObject().put("id", pairId).put("first", "a").put("second", "a")))
        assertTrue(runCatching { decodeLayoutBackup(selfPaired, emptyList(), emptyList(), "scope-a") }.isFailure)
    }

    @Test fun decodeRejectsAPairCellReferenceThatWasNeverDeclared() {
        val root = JSONObject(encodeLayoutBackup(LauncherState(loading = false), emptyList(), "scope-a"))
        root.put("homeSlots", org.json.JSONArray().put(pairId)) // "pairs" stays empty
        assertTrue(runCatching { decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a") }.isFailure)
    }
}
