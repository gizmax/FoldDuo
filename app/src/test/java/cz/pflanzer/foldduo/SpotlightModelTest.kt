package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotlightModelTest {

    // --- Pull-down / swipe-up gesture threshold ---------------------------------------------

    @Test fun `pull down needs the full threshold`() {
        assertFalse(spotlightPullDownTriggered(0f, 47.9f))
        assertTrue(spotlightPullDownTriggered(0f, 48f))
        assertTrue(spotlightPullDownTriggered(0f, 90f))
    }

    @Test fun `pull down only fires downward`() {
        assertFalse(spotlightPullDownTriggered(0f, -60f))
    }

    @Test fun `pull down needs vertical dominance over horizontal drift`() {
        assertFalse(spotlightPullDownTriggered(60f, 50f))
        assertTrue(spotlightPullDownTriggered(20f, 50f))
    }

    // --- Close-gesture decision (17. 9. night: a swipe UP slides Spotlight back up) ---

    @Test fun `results dismiss is an upward swipe past the shared threshold`() {
        assertFalse(spotlightResultsDismissTriggered(0f, -47.9f))
        assertTrue(spotlightResultsDismissTriggered(0f, -48f))
        assertTrue(spotlightResultsDismissTriggered(0f, -90f))
    }

    @Test fun `results dismiss never fires downward`() {
        assertFalse(spotlightResultsDismissTriggered(0f, 60f))
    }

    @Test fun `results dismiss needs vertical dominance over horizontal drift`() {
        assertFalse(spotlightResultsDismissTriggered(60f, -50f))
        assertTrue(spotlightResultsDismissTriggered(20f, -50f))
    }

    @Test fun `a mostly horizontal drag never triggers either gesture`() {
        assertFalse(spotlightPullDownTriggered(100f, 60f))
        assertFalse(spotlightResultsDismissTriggered(100f, 60f))
    }

    // --- Shade vs Spotlight arbitration (B28 follow-up) -----------------------------------------

    @Test fun `lane is shade near the top regardless of the rail`() {
        assertEquals(DownwardHomeGestureLane.SHADE, downwardHomeGestureLane(startYFraction = 0f, overRail = false))
        assertEquals(DownwardHomeGestureLane.SHADE, downwardHomeGestureLane(startYFraction = 0.19f, overRail = false))
    }

    @Test fun `lane is spotlight below the top fraction`() {
        assertEquals(DownwardHomeGestureLane.SPOTLIGHT, downwardHomeGestureLane(startYFraction = 0.2f, overRail = false))
        assertEquals(DownwardHomeGestureLane.SPOTLIGHT, downwardHomeGestureLane(startYFraction = 1f, overRail = false))
    }

    @Test fun `the status rail is always the shade lane, even low on the page`() {
        assertEquals(DownwardHomeGestureLane.SHADE, downwardHomeGestureLane(startYFraction = 0.9f, overRail = true))
    }

    @Test fun `shade fires near its ordinary small threshold`() {
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0f, false, 0f, 7.9f))
        assertEquals(DownwardHomeGestureTarget.SHADE, downwardHomeGestureTarget(0f, false, 0f, 8f))
    }

    @Test fun `spotlight needs its own much larger threshold, not the shade's`() {
        // Started low on the page (Spotlight's lane): the shade's small threshold does nothing.
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0.5f, false, 0f, 8f))
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0.5f, false, 0f, 47.9f))
        assertEquals(DownwardHomeGestureTarget.SPOTLIGHT, downwardHomeGestureTarget(0.5f, false, 0f, 48f))
    }

    @Test fun `a drag starting low never opens the shade, however far it travels`() {
        assertEquals(DownwardHomeGestureTarget.SPOTLIGHT, downwardHomeGestureTarget(0.5f, false, 0f, 200f))
    }

    @Test fun `a drag starting near the top never opens spotlight, however far it travels`() {
        assertEquals(DownwardHomeGestureTarget.SHADE, downwardHomeGestureTarget(0.1f, false, 0f, 200f))
    }

    @Test fun `neither fires for an upward or mostly horizontal drag in either lane`() {
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0f, false, 0f, -60f))
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0.5f, false, 0f, -60f))
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0f, false, 100f, 60f))
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(0.5f, false, 100f, 60f))
    }

    // --- Reachability, the third lane (B42 "Dosah na coveru") -------------------------------

    @Test fun `reachability lane is disabled by default, same as every caller before B42`() {
        // bottomFraction defaults to 0f: a start at the very bottom of the page still falls to
        // Spotlight's lane exactly as it did before this lane existed.
        assertEquals(DownwardHomeGestureLane.SPOTLIGHT, downwardHomeGestureLane(startYFraction = 1f, overRail = false))
        assertEquals(DownwardHomeGestureLane.SPOTLIGHT, downwardHomeGestureLane(startYFraction = 0.88f, overRail = false))
    }

    @Test fun `reachability lane is the bottom fraction of the page when enabled`() {
        val bottom = REACHABILITY_BOTTOM_FRACTION
        assertEquals(DownwardHomeGestureLane.SPOTLIGHT, downwardHomeGestureLane(startYFraction = 1f - bottom - 0.01f, overRail = false, bottomFraction = bottom))
        assertEquals(DownwardHomeGestureLane.REACHABILITY, downwardHomeGestureLane(startYFraction = 1f - bottom, overRail = false, bottomFraction = bottom))
        assertEquals(DownwardHomeGestureLane.REACHABILITY, downwardHomeGestureLane(startYFraction = 1f, overRail = false, bottomFraction = bottom))
    }

    @Test fun `the shade keeps its claim on a short page where top and bottom bands would overlap`() {
        // A tiny page where 20% top + 12% bottom would otherwise both claim this point: the
        // shade (checked first in downwardHomeGestureLane) wins, never Reachability.
        assertEquals(DownwardHomeGestureLane.SHADE, downwardHomeGestureLane(startYFraction = 0.15f, overRail = false, bottomFraction = 0.9f))
    }

    @Test fun `the status rail still wins the shade lane even with reachability enabled`() {
        assertEquals(DownwardHomeGestureLane.SHADE, downwardHomeGestureLane(startYFraction = 0.95f, overRail = true, bottomFraction = REACHABILITY_BOTTOM_FRACTION))
    }

    @Test fun `reachability needs its own threshold, not spotlight's or the shade's`() {
        val bottom = REACHABILITY_BOTTOM_FRACTION
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(1f, false, 0f, 15.9f, bottomFraction = bottom))
        assertEquals(DownwardHomeGestureTarget.REACHABILITY, downwardHomeGestureTarget(1f, false, 0f, 16f, bottomFraction = bottom))
    }

    @Test fun `a drag starting in the bottom lane never opens spotlight or the shade, however far it travels`() {
        assertEquals(DownwardHomeGestureTarget.REACHABILITY, downwardHomeGestureTarget(1f, false, 0f, 200f, bottomFraction = REACHABILITY_BOTTOM_FRACTION))
    }

    @Test fun `reachability lane still needs vertical dominance and downward direction`() {
        val bottom = REACHABILITY_BOTTOM_FRACTION
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(1f, false, 0f, -60f, bottomFraction = bottom))
        assertEquals(DownwardHomeGestureTarget.NONE, downwardHomeGestureTarget(1f, false, 100f, 60f, bottomFraction = bottom))
    }

    // --- Query matching ------------------------------------------------------------------------

    @Test fun `exact match beats prefix beats subsequence`() {
        assertEquals(SpotlightMatchKind.EXACT, spotlightMatch("kalendar", "Kalendar"))
        assertEquals(SpotlightMatchKind.PREFIX, spotlightMatch("kal", "Kalendar"))
        assertEquals(SpotlightMatchKind.SUBSEQUENCE, spotlightMatch("kdr", "Kalendar"))
        assertEquals(SpotlightMatchKind.NONE, spotlightMatch("xyz", "Kalendar"))
    }

    @Test fun `accent folding lets Kalendar match Kalendar with diacritics`() {
        assertEquals(SpotlightMatchKind.EXACT, spotlightMatch("kalendar", "Kalendář"))
        assertEquals(SpotlightMatchKind.PREFIX, spotlightMatch("kalend", "Kalendář"))
        assertEquals(SpotlightMatchKind.EXACT, spotlightMatch("kalendar", "kalendar"))
    }

    @Test fun `blank query never matches`() {
        assertEquals(SpotlightMatchKind.NONE, spotlightMatch("", "Kalendar"))
        assertEquals(SpotlightMatchKind.NONE, spotlightMatch("   ", "Kalendar"))
    }

    @Test fun `subsequence requires in-order characters, not just presence`() {
        assertTrue(spotlightIsSubsequence("cal", "camera log"))
        assertFalse(spotlightIsSubsequence("lac", "camera log"))
    }

    // --- App ranking -----------------------------------------------------------------------

    @Test fun `ranking puts prefix ahead of subsequence regardless of recency`() {
        val candidates = listOf(
            SpotlightAppCandidate("subseq.app", "Subsequence Match"), // "sm" is a subsequence, not a prefix
            SpotlightAppCandidate("prefix.app", "Smart Notes"), // "sm" is a prefix
        )
        // subseq.app is more recent (index 0) than prefix.app (index 1), yet prefix still wins.
        val ranked = rankSpotlightApps("sm", candidates, recencyIds = listOf("subseq.app", "prefix.app"))
        assertEquals(listOf("prefix.app", "subseq.app"), ranked.map { it.id })
    }

    @Test fun `ranking ties within the same match kind break on recency, newest first`() {
        val candidates = listOf(
            SpotlightAppCandidate("old.app", "Photos"),
            SpotlightAppCandidate("new.app", "Photo Editor"),
        )
        // Both are prefix matches for "photo"; new.app was launched more recently.
        val ranked = rankSpotlightApps("photo", candidates, recencyIds = listOf("new.app", "old.app"))
        assertEquals(listOf("new.app", "old.app"), ranked.map { it.id })
    }

    @Test fun `ranking falls back to alphabetical when neither match kind nor recency distinguish`() {
        val candidates = listOf(SpotlightAppCandidate("b.app", "Bravo"), SpotlightAppCandidate("a.app", "Alpha"))
        val ranked = rankSpotlightApps("a", candidates, recencyIds = emptyList())
        assertEquals(listOf("a.app", "b.app"), ranked.map { it.id })
    }

    @Test fun `ranking with accent folding matches diacritics both ways`() {
        val candidates = listOf(SpotlightAppCandidate("kalendar.app", "Kalendář"))
        assertEquals(listOf("kalendar.app"), rankSpotlightApps("kalendar", candidates, emptyList()).map { it.id })
    }

    @Test fun `ranking drops non-matching apps entirely`() {
        val candidates = listOf(SpotlightAppCandidate("a.app", "Alpha"), SpotlightAppCandidate("b.app", "Bravo"))
        assertEquals(emptyList<String>(), rankSpotlightApps("zzz", candidates, emptyList()).map { it.id })
    }

    @Test fun `default order uses recency when available, else alphabetical`() {
        val candidates = listOf(SpotlightAppCandidate("b.app", "Bravo"), SpotlightAppCandidate("a.app", "Alpha"))
        assertEquals(listOf("b.app", "a.app"), defaultAppOrder(candidates, listOf("b.app", "a.app"), recencyAvailable = true).map { it.id })
        assertEquals(listOf("a.app", "b.app"), defaultAppOrder(candidates, listOf("b.app", "a.app"), recencyAvailable = false).map { it.id })
    }

    // --- Settings shortcuts ------------------------------------------------------------------

    @Test fun `curated settings shortcuts are all unique and non-blank`() {
        assertTrue(spotlightSettingsShortcutsAreSane())
    }

    @Test fun `sanity check catches a duplicated action`() {
        val duplicated = listOf(
            SpotlightSettingsShortcut("android.settings.WIFI_SETTINGS", "Wi-Fi", "Wi-Fi"),
            SpotlightSettingsShortcut("android.settings.WIFI_SETTINGS", "Wireless", "Bezdrát"),
        )
        assertFalse(spotlightSettingsShortcutsAreSane(duplicated))
    }

    @Test fun `sanity check catches a duplicated label`() {
        val duplicated = listOf(
            SpotlightSettingsShortcut("android.settings.WIFI_SETTINGS", "Network", "Sit"),
            SpotlightSettingsShortcut("android.settings.SOUND_SETTINGS", "Network", "Zvuk"),
        )
        assertFalse(spotlightSettingsShortcutsAreSane(duplicated))
    }

    @Test fun `the curated table has around twenty entries`() {
        assertTrue(SPOTLIGHT_SETTINGS_SHORTCUTS.size in 15..25)
    }

    // --- Row entrance stagger ----------------------------------------------------------------

    @Test fun `row stagger is twenty ms per row and caps out`() {
        assertEquals(0L, spotlightRowDelayMs(0))
        assertEquals(20L, spotlightRowDelayMs(1))
        assertEquals(40L, spotlightRowDelayMs(2))
        val capped = spotlightRowDelayMs(SPOTLIGHT_ROW_STAGGER_MAX_ITEMS)
        assertEquals(capped, spotlightRowDelayMs(SPOTLIGHT_ROW_STAGGER_MAX_ITEMS + 5))
    }

    @Test fun `negative index starts immediately`() {
        assertEquals(0L, spotlightRowDelayMs(-3))
    }

    // --- B46 "Dvojice aplikací": pairs in Spotlight -------------------------------------------

    @Test fun `pair title switches Dvojice vs Pair by locale`() {
        assertEquals("Dvojice: Mapy + Kalendář", spotlightPairTitle("Mapy", "Kalendář", czech = true))
        assertEquals("Pair: Maps + Calendar", spotlightPairTitle("Maps", "Calendar", czech = false))
    }

    @Test fun `pair ranking matches either member label`() {
        val pairs = listOf(
            SpotlightPairCandidate("pair:1", "Maps", "Calendar"),
            SpotlightPairCandidate("pair:2", "Camera", "Photos"),
        )
        assertEquals(listOf("pair:1"), rankSpotlightPairs("cal", pairs).map { it.id })
        assertEquals(listOf("pair:2"), rankSpotlightPairs("photo", pairs).map { it.id })
        assertTrue(rankSpotlightPairs("zzz", pairs).isEmpty())
    }

    @Test fun `empty query never ranks any pair`() {
        val pairs = listOf(SpotlightPairCandidate("pair:1", "Maps", "Calendar"))
        assertTrue(rankSpotlightPairs("", pairs).isEmpty())
        assertTrue(rankSpotlightPairs("   ", pairs).isEmpty())
    }

    @Test fun `exact match outranks a fuzzy subsequence match`() {
        val pairs = listOf(
            SpotlightPairCandidate("pair:fuzzy", "xMapsx", "Other"),
            SpotlightPairCandidate("pair:exact", "Maps", "Other"),
        )
        assertEquals("pair:exact", rankSpotlightPairs("Maps", pairs).first().id)
    }

    // --- Spotlight v2 "Bold Spotlight": Top Hit selection --------------------------------------

    @Test fun `top hit is the highest match kind regardless of section`() {
        val candidates = listOf(
            SpotlightCandidate(SpotlightSectionKind.SETTINGS, "settings:wifi", SpotlightMatchKind.EXACT),
            SpotlightCandidate(SpotlightSectionKind.APPS, "app:x", SpotlightMatchKind.PREFIX),
        )
        assertEquals("settings:wifi", pickSpotlightTopHit(candidates)?.id)
    }

    @Test fun `top hit ties break by section order — apps before contacts before settings before actions`() {
        val candidates = listOf(
            SpotlightCandidate(SpotlightSectionKind.ACTIONS, "action:a", SpotlightMatchKind.EXACT),
            SpotlightCandidate(SpotlightSectionKind.CONTACTS, "contact:1", SpotlightMatchKind.EXACT),
            SpotlightCandidate(SpotlightSectionKind.APPS, "app:x", SpotlightMatchKind.EXACT),
            SpotlightCandidate(SpotlightSectionKind.SETTINGS, "settings:wifi", SpotlightMatchKind.EXACT),
        )
        assertEquals("app:x", pickSpotlightTopHit(candidates)?.id)
    }

    @Test fun `top hit is null when nothing matched, including an empty list`() {
        assertEquals(null, pickSpotlightTopHit(emptyList()))
        assertEquals(null, pickSpotlightTopHit(listOf(SpotlightCandidate(SpotlightSectionKind.APPS, "app:x", SpotlightMatchKind.NONE))))
    }

    // --- Spotlight v2: section grouping --------------------------------------------------------

    @Test fun `sections group by kind, in declared order, dropping empty sections`() {
        val candidates = listOf(
            SpotlightCandidate(SpotlightSectionKind.ACTIONS, "action:a", SpotlightMatchKind.PREFIX),
            SpotlightCandidate(SpotlightSectionKind.APPS, "app:x", SpotlightMatchKind.EXACT),
            SpotlightCandidate(SpotlightSectionKind.APPS, "app:y", SpotlightMatchKind.SUBSEQUENCE),
        )
        val grouped = groupSpotlightSections(candidates)
        assertEquals(listOf(SpotlightSectionKind.APPS, SpotlightSectionKind.ACTIONS), grouped.map { it.first })
        assertEquals(listOf("app:x", "app:y"), grouped.first { it.first == SpotlightSectionKind.APPS }.second)
    }

    @Test fun `sections drop non-matches and never emit an empty group`() {
        val candidates = listOf(SpotlightCandidate(SpotlightSectionKind.APPS, "app:x", SpotlightMatchKind.NONE))
        assertTrue(groupSpotlightSections(candidates).isEmpty())
        assertTrue(groupSpotlightSections(emptyList()).isEmpty())
    }

    // --- Spotlight v2: empty-state content order -----------------------------------------------

    @Test fun `empty state shows suggestions above the field, recent and shortcuts below it`() {
        assertEquals(
            listOf(SpotlightEmptyStateBlock.SUGGESTIONS, SpotlightEmptyStateBlock.FIELD,
                SpotlightEmptyStateBlock.RECENT, SpotlightEmptyStateBlock.SHORTCUTS),
            SPOTLIGHT_EMPTY_STATE_ORDER,
        )
    }

    // --- Spotlight v2: keyboard-aware bottom content padding -----------------------------------

    @Test fun `bottom padding never goes below the minimum`() {
        assertEquals(24f, spotlightBottomContentPaddingDp(imeHeightDp = 0f, navigationBarDp = 0f))
        assertEquals(24f, spotlightBottomContentPaddingDp(imeHeightDp = 10f, navigationBarDp = 5f))
    }

    @Test fun `bottom padding grows to whichever obstruction is taller`() {
        assertEquals(300f, spotlightBottomContentPaddingDp(imeHeightDp = 300f, navigationBarDp = 48f))
        assertEquals(48f, spotlightBottomContentPaddingDp(imeHeightDp = 0f, navigationBarDp = 48f))
    }

    // --- Spotlight přes oba pane (17. 9. noc): layout decision ---------------------------------

    @Test fun `two columns only with a seam, never on the cover`() {
        assertFalse(spotlightUsesTwoColumns(hasSeam = false, widthDp = 2000f))
        assertFalse(spotlightUsesTwoColumns(hasSeam = false, widthDp = FOLD_THRESHOLD_DP))
    }

    @Test fun `two columns needs the fold-wide threshold too, not just a seam`() {
        assertFalse(spotlightUsesTwoColumns(hasSeam = true, widthDp = FOLD_THRESHOLD_DP - 1f))
        assertTrue(spotlightUsesTwoColumns(hasSeam = true, widthDp = FOLD_THRESHOLD_DP))
        assertTrue(spotlightUsesTwoColumns(hasSeam = true, widthDp = 2000f))
    }

    // --- Left pane content by state -------------------------------------------------------------

    @Test fun `left pane shows suggestions when blank, preview once a query lands`() {
        assertEquals(SpotlightLeftPaneContent.SUGGESTIONS, spotlightLeftPaneContent(queryBlank = true))
        assertEquals(SpotlightLeftPaneContent.PREVIEW, spotlightLeftPaneContent(queryBlank = false))
    }

    // --- Highlighted-result selection -------------------------------------------------------------

    @Test fun `highlight defaults to the top hit`() {
        assertEquals("app:x", spotlightEffectiveHighlight(storedKey = null, topHitKey = "app:x", availableKeys = listOf("app:x", "web-search")))
    }

    @Test fun `a tapped highlight sticks while it is still available`() {
        assertEquals("contact:1", spotlightEffectiveHighlight(storedKey = "contact:1", topHitKey = "app:x", availableKeys = listOf("app:x", "contact:1")))
    }

    @Test fun `highlight resets to the top hit once the tapped key falls out of the results`() {
        assertEquals("app:x", spotlightEffectiveHighlight(storedKey = "contact:1", topHitKey = "app:x", availableKeys = listOf("app:x", "web-search")))
    }

    @Test fun `with no top hit at all, highlight falls back to the first available result — e g just Web`() {
        assertEquals("web-search", spotlightEffectiveHighlight(storedKey = null, topHitKey = null, availableKeys = listOf("web-search")))
    }

    @Test fun `nothing available means nothing highlighted`() {
        assertEquals(null, spotlightEffectiveHighlight(storedKey = "app:x", topHitKey = "app:x", availableKeys = emptyList()))
    }

    // --- Hardware-keyboard up down navigation -----------------------------------------------------

    @Test fun `arrow down moves to the next key, arrow up to the previous`() {
        val keys = listOf("app:a", "app:b", "contact:1")
        assertEquals("app:b", spotlightMoveHighlight("app:a", keys, delta = 1))
        assertEquals("app:a", spotlightMoveHighlight("app:b", keys, delta = -1))
    }

    @Test fun `arrow navigation clamps at either end instead of wrapping`() {
        val keys = listOf("app:a", "app:b", "contact:1")
        assertEquals("app:a", spotlightMoveHighlight("app:a", keys, delta = -1))
        assertEquals("contact:1", spotlightMoveHighlight("contact:1", keys, delta = 1))
    }

    @Test fun `arrow navigation with nothing highlighted yet starts from the first key`() {
        assertEquals("app:a", spotlightMoveHighlight(null, listOf("app:a", "app:b"), delta = 1))
    }

    @Test fun `arrow navigation over an empty list highlights nothing`() {
        assertEquals(null, spotlightMoveHighlight("app:a", emptyList(), delta = 1))
    }

    // --- Suggestions grid count --------------------------------------------------------------------

    @Test fun `suggestions grid caps at eight even with more ranked apps`() {
        assertEquals(8, spotlightSuggestionsGridCount(20))
        assertEquals(8, spotlightSuggestionsGridCount(8))
    }

    @Test fun `suggestions grid shows fewer than eight when that is all there is`() {
        assertEquals(3, spotlightSuggestionsGridCount(3))
        assertEquals(0, spotlightSuggestionsGridCount(0))
    }

    // --- Contact avatar initials --------------------------------------------------------------

    @Test fun `initials take the first letter of up to two words`() {
        assertEquals("TP", spotlightInitials("Tomáš Pflanzer"))
        assertEquals("C", spotlightInitials("Cher"))
        assertEquals("JR", spotlightInitials("John Ronald Doe"))
    }

    @Test fun `initials fall back to a question mark for a blank name`() {
        assertEquals("?", spotlightInitials(""))
        assertEquals("?", spotlightInitials("   "))
    }
}
