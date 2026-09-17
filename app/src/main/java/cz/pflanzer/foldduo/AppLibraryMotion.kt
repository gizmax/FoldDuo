package cz.pflanzer.foldduo

/**
 * B31 "App Library" motion: pure scheduling and mapping logic behind the category
 * expand/collapse shared-bounds transition, the A-Z index rail, the entrance stagger and the
 * search-focus tile/list swap. Free of Android and Compose types so it runs as plain JUnit
 * under app/src/test; the Compose glue that reads these lives in AppLibrary.kt and
 * AppLibraryPage.kt.
 */

/**
 * Whether the App Library shows the alphabetical list (search results, or the "Choose home
 * apps" pin sheet) rather than the category tile grid. Search focus alone — before any text is
 * typed — already swaps to the list, matching iOS: tapping the field animates the tiles away.
 */
internal fun isLibraryBrowsing(editing: Boolean, query: String, searchFocused: Boolean): Boolean =
    !editing && query.isBlank() && !searchFocused

/**
 * The open category collapses whenever the App Library page is no longer the settled pager
 * page (swiping away, or the pane switching cover/inner mid-drag), so it never reopens stale.
 * While the page stays current, [open] passes through unchanged.
 */
internal fun libraryOpenAfterCurrencyChange(open: AppCategory?, isCurrent: Boolean): AppCategory? =
    if (isCurrent) open else null

/**
 * A-Z index letter for [label]: the first character, accents folded to their plain Latin
 * letter ("Č" and "Ç" both group under "C"), uppercased. Anything that isn't a foldable Latin
 * letter — digits, punctuation, emoji, CJK — groups under "#", exactly like the iOS App
 * Library's numbers-and-symbols bucket.
 */
internal fun libraryIndexLetter(label: String): String {
    val first = label.trim().firstOrNull() ?: return "#"
    if (!first.isLetter()) return "#"
    val folded = java.text.Normalizer.normalize(first.toString(), java.text.Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
    val base = folded.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (base in 'A'..'Z') base.toString() else "#"
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** 25 ms stagger between one App Library tile's entrance and the next, capped so a long grid settles in well under a second. */
internal const val LIBRARY_ENTRANCE_STAGGER_MS = 25L
internal const val LIBRARY_ENTRANCE_STAGGER_MAX_ITEMS = 16

/** Delay before grid item [index] starts its fade/rise entrance; negative indices start immediately. */
internal fun libraryEntranceDelayMs(index: Int): Long =
    index.coerceIn(0, LIBRARY_ENTRANCE_STAGGER_MAX_ITEMS) * LIBRARY_ENTRANCE_STAGGER_MS
