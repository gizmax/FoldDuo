package cz.pflanzer.foldduo.predict

/*
 * Pure geometry behind the Suggestions strip (`PredictionUi.kt`'s `SuggestionsRow`, wired through
 * `LeadingPane.kt`'s `LeadingTopSlot`). After a 2026-09-17 night device report ("v Suggestions
 * jsou popisky špatně vycentrované a oříznuté, ikony divné") the strip stopped drawing its own
 * icon/label composables and started reusing the Home grid's own `AppTile` (LauncherScreen.kt),
 * at the grid's own cell width and icon size. Kept free of Android/Compose types (like
 * `TiltPacing.kt`) so it is plain-JVM testable without Robolectric.
 */

/** Always four: one slot per ranked suggestion, unfilled slots render blank but keep their layout space. */
const val SUGGESTIONS_TILE_COUNT = 4

/** `MaterialTheme.typography.labelLarge`'s line height at this size — the "Návrhy"/"Suggestions" caption. */
const val SUGGESTIONS_CAPTION_HEIGHT_DP = 20f

/** The gap `SuggestionsRow` puts between the caption and the tile row (`padding(top = 6.dp)`). */
const val SUGGESTIONS_CAPTION_GAP_DP = 6f

/** `AppTile`'s label: `fontSize = 11.sp, lineHeight = 14.sp`. */
const val SUGGESTIONS_LABEL_HEIGHT_DP = 14f

/** `AppTile`'s own `Modifier.padding(top = 4.dp)` between the icon box and its label. */
const val SUGGESTIONS_LABEL_GAP_DP = 4f

/** The glass strip's vertical inset (`frostedGlass` panel's `padding(vertical = 10.dp)`), once per edge. */
const val SUGGESTIONS_STRIP_PADDING_DP = 10f

/** One `AppTile`'s own intrinsic height at [iconSizeDp]: the icon box plus its label — nothing above it ever coerces this shorter, so it is never clipped. */
fun suggestionTileHeightDp(iconSizeDp: Float): Float =
    iconSizeDp + SUGGESTIONS_LABEL_GAP_DP + SUGGESTIONS_LABEL_HEIGHT_DP

/**
 * Total height the glass strip needs at [iconSizeDp] to show its caption and one full row of
 * tiles without clipping anything: both vertical insets, the caption line, the gap under it, then
 * the tile itself. `SuggestionsRow` never applies a `height()`/`clipToBounds()` smaller than this
 * — the strip wraps its content, so this is what it actually measures to, not a cap on it.
 */
fun suggestionsRowHeightDp(iconSizeDp: Float): Float =
    2 * SUGGESTIONS_STRIP_PADDING_DP + SUGGESTIONS_CAPTION_HEIGHT_DP + SUGGESTIONS_CAPTION_GAP_DP +
        suggestionTileHeightDp(iconSizeDp)

/**
 * Width of each of the [SUGGESTIONS_TILE_COUNT] tiles when the strip is handed the grid's own
 * [gridWidthDp] — the same division `SharedHomeGrid` uses for its own cells (`maxWidth / 4`), so
 * a suggestion tile is exactly as wide as a Home grid cell, edge to edge with no leftover slack
 * for `Arrangement.SpaceEvenly` to have to squeeze or overflow.
 */
fun suggestionCellWidthDp(gridWidthDp: Float): Float = gridWidthDp / SUGGESTIONS_TILE_COUNT
