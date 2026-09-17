package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the B31 App Library motion: tile expand/collapse, the A-Z index, the entrance stagger and the search swap. */
class AppLibraryMotionTest {

    @Test fun `browsing shows tiles until search is focused or has text`() {
        assertTrue(isLibraryBrowsing(editing = false, query = "", searchFocused = false))
        assertFalse(isLibraryBrowsing(editing = false, query = "", searchFocused = true))
        assertFalse(isLibraryBrowsing(editing = false, query = "cal", searchFocused = false))
        // A whitespace-only query is still blank, so it counts as browsing.
        assertTrue(isLibraryBrowsing(editing = false, query = "   ", searchFocused = false))
        // The pin sheet is always the alphabetical list, focused or not.
        assertFalse(isLibraryBrowsing(editing = true, query = "", searchFocused = false))
    }

    @Test fun `open category collapses only when the page stops being current`() {
        assertEquals(AppCategory.Games, libraryOpenAfterCurrencyChange(AppCategory.Games, isCurrent = true))
        assertEquals(null, libraryOpenAfterCurrencyChange(AppCategory.Games, isCurrent = false))
        assertEquals(null, libraryOpenAfterCurrencyChange(null, isCurrent = true))
        assertEquals(null, libraryOpenAfterCurrencyChange(null, isCurrent = false))
    }

    @Test fun `index letter folds accents onto their base Latin letter`() {
        assertEquals("C", libraryIndexLetter("Čokoláda"))
        assertEquals("C", libraryIndexLetter("čokoláda"))
        assertEquals("C", libraryIndexLetter("Chrome"))
        assertEquals("A", libraryIndexLetter("Áčko"))
        assertEquals("O", libraryIndexLetter(" Örsted"))
        assertEquals("S", libraryIndexLetter("Škoda"))
        assertEquals("Z", libraryIndexLetter("Žralok"))
    }

    @Test fun `index letter groups digits and symbols under the hash bucket`() {
        assertEquals("#", libraryIndexLetter("1Password"))
        assertEquals("#", libraryIndexLetter("7-Eleven"))
        assertEquals("#", libraryIndexLetter("!Emergency"))
        assertEquals("#", libraryIndexLetter(""))
        assertEquals("#", libraryIndexLetter("   "))
        assertEquals("#", libraryIndexLetter("速報"))
    }

    @Test fun `index letter is case insensitive and ignores leading whitespace`() {
        assertEquals("A", libraryIndexLetter("  Alza"))
        assertEquals("A", libraryIndexLetter("alza"))
        assertEquals("A", libraryIndexLetter("ALZA"))
    }

    @Test fun `entrance delay steps by 25ms per tile and clamps for long grids`() {
        assertEquals(0L, libraryEntranceDelayMs(0))
        assertEquals(25L, libraryEntranceDelayMs(1))
        assertEquals(250L, libraryEntranceDelayMs(10))
        val capped = LIBRARY_ENTRANCE_STAGGER_MAX_ITEMS * LIBRARY_ENTRANCE_STAGGER_MS
        assertEquals(capped, libraryEntranceDelayMs(LIBRARY_ENTRANCE_STAGGER_MAX_ITEMS))
        assertEquals(capped, libraryEntranceDelayMs(LIBRARY_ENTRANCE_STAGGER_MAX_ITEMS + 50))
        assertEquals(0L, libraryEntranceDelayMs(-3))
    }
}
