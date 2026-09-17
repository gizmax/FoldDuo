package cz.pflanzer.foldduo

/** Jake's reference measures 76px dock artwork against 106px home artwork. */
fun dockIconSize(homeIconSize: Float) = homeIconSize * (76f / 106f)

data class LayoutPreset(
    val iconSize: Float = 66f,
    val rowGap: Float = 8f,
    val dockWidth: Float = 68f,
    /** Where the dock sits in the rail's free span: 0.25 high, 0.5 centred, 0.75 low ([railLayout]). */
    val dockPosition: Float = 0.56f,
    /** Inert since the rail became persistent chrome; kept for persistence. */
    val dockAlignToGrid: Boolean = true,
) {
    fun sanitized() = copy(
        iconSize = iconSize.coerceIn(40f, 68f),
        rowGap = rowGap.coerceIn(0f, 28f),
        dockWidth = dockWidth.coerceIn(56f, 84f),
        dockPosition = dockPosition.coerceIn(0.25f, 0.75f),
    )
}

data class HomeGeometry(
    val expanded: Boolean,
    val homeWidth: Float,
    val gridWidth: Float,
    val iconSize: Float,
    val rowHeight: Float,
    val widgetHeight: Float,
    val contentTop: Float,
    /** Right inset every content surface keeps clear for the rail column ([railWidthDp]). */
    val railWidth: Float,
    /**
     * "Spread jako jedna plocha" (17. 9. noc): the SAME value as [railWidth], exposed under its
     * own name for the one place that must not conflate the two roles — the Home grid's own
     * trailing padding (shrinks [gridWidth]) is never allowed to also shrink the pane BOX a
     * strip-based pager positions ([homeWidth]/`ExpandedPaneLayout.homeStride` in
     * WorkspacePageMotion.kt), or Today and every Home page stop sharing one pitch again.
     */
    val railInsetDp: Float = railWidth,
)

/** Left margin of the Home grid inside its pane (the long-press "options" strip). */
const val HOME_START_DP = 16f

/** Space between the dock and the window edge. */
const val RAIL_EDGE_PADDING_DP = 12f

/** Space between the dock and the content it sits beside. */
const val RAIL_CONTENT_GAP_DP = 16f

/** Vertical gap between the rail blocks (status, island, dock). */
const val RAIL_GAP_DP = 8f
/** Duo keeps the clock right at the top edge of the strip; the grid's contentTop does not apply to the rail. */
const val RAIL_TOP_MARGIN_DP = 12f // 17. 9.: 16 read slightly low (the clock glyphs carry ~4 dp of leading), 12 makes the visible gap match the right edge (Tom)

/** Space between the dock and the bottom safe inset. */
const val RAIL_BOTTOM_MARGIN_DP = 16f // 17. 9.: the search's gap to the bottom edge equals its gap to the right edge (Tom)

/** Island slot height with no live item: the slot collapses and the dock span starts at the status. */
const val RAIL_ISLAND_HEIGHT_DP = 0f

/** One collapsed island pill (island/RailIsland.kt); a second pill stacks below with [RAIL_GAP_DP]. */
const val RAIL_ISLAND_ITEM_HEIGHT_DP = 44f

/** Collapsed pills shown at once; further items fold into a "+N" badge on the last pill. */
const val RAIL_ISLAND_MAX_COLLAPSED = 3 // 17. 9.: Apple Music + YouTube + a timer at once (Tom)

/**
 * Height the collapsed island reserves in the rail for [itemCount] live items: 0, 44, 96 dp
 * (44·n plus the gaps between pills, capped at [RAIL_ISLAND_MAX_COLLAPSED]). The expanded card
 * overlays the dock span instead of reserving more, so the dock never moves on a tap.
 *
 * B61 "Ostrov kolem kamery" rail hand-off: when [cameraIslandActive] is true (the "Live
 * activities" setting is Camera island, `cameraisland/CameraIsland.kt`'s `CameraIslandSettings`),
 * live items show around the camera cutout instead — the rail's own pill stack draws nothing
 * (`island/RailIsland.kt` self-guards), so this slot always collapses to 0 regardless of
 * [itemCount], letting the dock slide up to fill the space the rail pills would have reserved.
 */
fun islandSlotHeight(itemCount: Int, cameraIslandActive: Boolean = false): Float {
    if (cameraIslandActive) return RAIL_ISLAND_HEIGHT_DP
    val shown = itemCount.coerceIn(0, RAIL_ISLAND_MAX_COLLAPSED)
    return if (shown == 0) RAIL_ISLAND_HEIGHT_DP else shown * RAIL_ISLAND_ITEM_HEIGHT_DP + (shown - 1) * RAIL_GAP_DP
}

/** Fold 8 cover camera cutout: 104 px at density 360 (PLAN.md, fact 1). */
const val COVER_CUTOUT_DP = 104f / 2.25f

/** The rail column width: dock plus its edge padding and the gap to content. */
fun railWidthDp(dockWidth: Float): Float = dockWidth + RAIL_EDGE_PADDING_DP + RAIL_CONTENT_GAP_DP

/**
 * Vertical positions of the right rail, in dp from the top of the window area it is laid out in
 * (iPhone Duo Home: status at the top, the dock vertically centred in the strip, search at the
 * very bottom). Status sits below the top inset and below any camera cutout, the island slot
 * directly under it. The search control is anchored to the bottom of the span (its bottom edge
 * on [RAIL_BOTTOM_MARGIN_DP] above the bottom inset, the same baseline as the page-indicator
 * row), and the dock is centred in the span that ends [RAIL_GAP_DP] above the search, biased by
 * [LayoutPreset.dockPosition] (0.5 = centre). The dock keeps four 48 dp+ rows and scrolls rather
 * than shrinking when the window is short.
 */
data class RailLayout(
    val railWidth: Float,
    val statusTop: Float,
    val islandTop: Float,
    val islandHeight: Float,
    val dockTop: Float,
    val dockHeight: Float,
    val dockRowHeight: Float,
    val searchSize: Float,
    /** Top of the bottom-anchored search control: `spanBottom - searchSize`. */
    val searchTop: Float,
) {
    val dockBottom: Float get() = dockTop + dockHeight
    val searchBottom: Float get() = searchTop + searchSize
}

fun railLayout(
    height: Float,
    topInset: Float,
    bottomInset: Float,
    cutoutBottom: Float,
    statusHeight: Float,
    dockWidth: Float,
    dockIconSize: Float,
    topMargin: Float = RAIL_TOP_MARGIN_DP,
    islandHeight: Float = RAIL_ISLAND_HEIGHT_DP,
    dockPosition: Float = 0.5f,
): RailLayout {
    // The rail hugs the right edge and never crosses the centred camera cutout, so the cutout
    // does not push the status column down (17. 9.: it used to sit ~72 dp from the top on the
    // cover, the safe-area padding plus this rule). [cutoutBottom] is kept for callers/tests.
    @Suppress("UNUSED_PARAMETER") val ignoredCutout = cutoutBottom
    val statusTop = topInset + topMargin
    val islandTop = statusTop + statusHeight + if (statusHeight > 0f) RAIL_GAP_DP else 0f
    val spanTop = islandTop + islandHeight + RAIL_GAP_DP
    val spanBottom = height - bottomInset - RAIL_BOTTOM_MARGIN_DP
    val searchSize = maxOf(48f, dockIconSize)
    val desiredRow = maxOf(48f, dockIconSize + 12f)
    // The search control is anchored to the bottom of the span (Duo: level with the page dots);
    // the dock lives in the span that ends RAIL_GAP_DP above it.
    val searchTop = spanBottom - searchSize
    val dockSpanBottom = searchTop - RAIL_GAP_DP
    val available = dockSpanBottom - spanTop
    val dockHeight = minOf(4f * desiredRow + 16f, available).coerceAtLeast(76f)
    val dockRowHeight = ((dockHeight - 16f) / 4f).coerceAtLeast(48f)
    // Centre the dock itself in the free span (0.5), sliding it up or down with dockPosition;
    // never let the dock overlap the search control, and never push it above the island.
    val free = dockSpanBottom - spanTop - dockHeight
    val dockTop = (spanTop + free * dockPosition.coerceIn(0.25f, 0.75f))
        .coerceAtMost(dockSpanBottom - dockHeight)
        .coerceAtLeast(spanTop)
    return RailLayout(railWidthDp(dockWidth), statusTop, islandTop, islandHeight,
        dockTop, dockHeight, dockRowHeight, searchSize, searchTop)
}

/** Advance old defaults without changing individually tuned values. */
fun upgradePreset(preset: LayoutPreset, schema: Int, expanded: Boolean): LayoutPreset = when {
    schema < 2 -> preset.copy(
        iconSize = if (preset.iconSize == if (expanded) 58f else 54f) 66f else preset.iconSize,
        rowGap = if (preset.rowGap == 12f) 8f else preset.rowGap,
        dockWidth = if (preset.dockWidth == 64f) 68f else preset.dockWidth,
    )
    schema == 2 && preset.iconSize == 60f -> preset.copy(iconSize = 66f)
    else -> preset
}

/**
 * [seamXDp] is the vertical hinge line from `FoldingFeature` (window dp). When the window is
 * expanded and the seam is known, Home is the right pane `[seamXDp + seamGutterDp, width]`;
 * without a seam the historical 56/44 split remains the fallback.
 *
 * 2026-09-17 noc "Mřížka 4x7 a obsah výš": Tom on the device — "widgety a ikony dovol dávat i více
 * nahoru a hlavně chybí spodní řada" (an empty band up top, room for one more row at the bottom).
 * [contentTop] no longer centres the grid between the top and the bottom chrome (page dots /
 * "Set as home" pill) — that band is what was wasted; the first row now sits level with the
 * rail's own clock line ([RAIL_TOP_MARGIN_DP]), pushed down only far enough to clear a camera
 * cutout that reaches lower than that — the cover's, centred over the grid's own columns — by
 * [cutoutBottomDp] plus 8 dp of clearance. The row pitch itself is untouched (icon size stays
 * exactly as it was); the room this frees up at the top is what fits [GRID_ROWS]'s new seventh
 * row instead of sitting idle.
 */
fun homeGeometry(width: Float, height: Float, preset: LayoutPreset, labels: Boolean, labelHeight: Float = 20f, seamXDp: Float? = null, seamGutterDp: Float = FOLD_GUTTER_DP, cutoutBottomDp: Float = 0f): HomeGeometry {
    val p = preset.sanitized()
    val expanded = width >= FOLD_THRESHOLD_DP
    val seam = seamXDp?.let { FoldSeam(it, seamGutterDp) }?.takeIf { it.splits(width) }
    val homeWidth = when {
        !expanded -> width
        seam != null -> width - seam.homeStartDp
        else -> minOf(460f, width * 0.56f)
    }
    val railWidth = railWidthDp(p.dockWidth)
    val railInsetDp = railWidth
    val gridWidth = (homeWidth - railInsetDp - HOME_START_DP).coerceAtLeast(192f)
    val icon = minOf(p.iconSize, (gridWidth / 4f - 10f).coerceAtLeast(32f))
    // Keep the same icon rhythm when labels are hidden; allow larger system text to fit.
    val row = maxOf(48f, icon + if (labels) maxOf(20f, labelHeight) else 20f) + p.rowGap
    val widget = minOf(176f, gridWidth / 2f - 5f).coerceAtLeast(88f)
    val contentTop = maxOf(RAIL_TOP_MARGIN_DP, cutoutBottomDp + 8f)
    // The dock no longer follows the widget block: the rail model (railLayout) centres it in the
    // strip, biased by dockPosition. dockAlignToGrid stays persisted but inert.
    return HomeGeometry(expanded, homeWidth, gridWidth, icon, row, widget, contentTop, railWidth, railInsetDp)
}

// --- B40 "Widgety ve velikostních třídách" ----------------------------------------------

/**
 * Pane content size fed to [homeGeometry] for the two panel windows that share one Home page
 * under pane identity (CONTEXT.md, ADB measurement 13. 9. 2026, dp @360; the cover figure is
 * already net of its 104 px camera cutout, [COVER_CUTOUT_DP]). Only the height differs in any
 * meaningful way (830 vs 821 dp) — the widths are within a dp of each other, as pane identity
 * intends. Used to build both panels' `OPTION_APPWIDGET_SIZES` without needing a live
 * simultaneous measurement of a panel the device is not currently showing.
 *
 * These four are the Fold 8's own numbers, kept as their historical (some hand-rounded to a
 * whole dp) literals so today's widget size buckets never drift; [DeviceProfile.FOLD8] computes
 * the same figures unrounded (`DeviceProfileTest` checks the two agree within half a dp) and is
 * what a device this launcher does not have shipped defaults for would pass to
 * [bothPanelContentSizes]/[leadingPanelContentSize] instead.
 */
const val COVER_PANE_WIDTH_DP = 555f
const val COVER_PANE_HEIGHT_DP = 830f
const val INNER_PANE_WIDTH_DP = 544f
const val INNER_PANE_HEIGHT_DP = 821f

/** The Home-grid pitch a Home-page widget slot measures against, mirroring the [WidgetGridSizing] `SharedHomeGrid` (LauncherScreen.kt) builds around [HomeGeometry.widgetHeight]/[HomeGeometry.rowHeight]. */
fun homeWidgetGridSizing(geometry: HomeGeometry): WidgetGridSizing {
    val topPitch = (geometry.widgetHeight + 18f) / 2f
    return WidgetGridSizing(GRID_COLUMNS, GRID_ROWS, geometry.gridWidth / GRID_COLUMNS,
        minOf(topPitch, geometry.rowHeight), maxOf(topPitch, geometry.rowHeight),
        horizontalGapDp = 10f, verticalGapDp = 18f, topRowHeightDp = topPitch, appRowHeightDp = geometry.rowHeight)
}

/**
 * B40: the dp box a Home-page placement ([column]/[row]/[spanX]/[spanY]) gets on each panel,
 * cover and inner, from the very same span — pane identity keeps the placement itself
 * identical, only each panel's own [LayoutPreset] (icon/dock sizing may differ compact vs
 * expanded) and pane geometry change it. Feeds a host widget's `OPTION_APPWIDGET_SIZES` so a
 * responsive RemoteViews can prebuild both layouts instead of only the one it happened to bind
 * at.
 */
fun bothPanelContentSizes(compactPreset: LayoutPreset, expandedPreset: LayoutPreset, labels: Boolean,
    column: Int, row: Int, spanX: Int, spanY: Int,
    coverPaneWidthDp: Float = COVER_PANE_WIDTH_DP, coverPaneHeightDp: Float = COVER_PANE_HEIGHT_DP,
    innerPaneWidthDp: Float = INNER_PANE_WIDTH_DP, innerPaneHeightDp: Float = INNER_PANE_HEIGHT_DP): WidgetOptionsSizes {
    val cover = homeWidgetGridSizing(homeGeometry(coverPaneWidthDp, coverPaneHeightDp, compactPreset, labels))
    val inner = homeWidgetGridSizing(homeGeometry(innerPaneWidthDp, innerPaneHeightDp, expandedPreset, labels))
    return widgetOptionsSizes(listOf(cover.contentSize(column, row, spanX, spanY), inner.contentSize(column, row, spanX, spanY)))
}

/**
 * Same idea for the Today (leading, page -1) column: the cover never shows page -1 (pane
 * identity, LeadingPane.kt), so there is only ever the one, inner-panel size.
 */
fun leadingPanelContentSize(expandedPreset: LayoutPreset, labels: Boolean,
    column: Int, row: Int, spanX: Int, spanY: Int,
    innerPaneWidthDp: Float = INNER_PANE_WIDTH_DP, innerPaneHeightDp: Float = INNER_PANE_HEIGHT_DP): WidgetOptionsSizes {
    val geometry = homeGeometry(innerPaneWidthDp, innerPaneHeightDp, expandedPreset, labels)
    val grid = leadingGridSizing(innerPaneWidthDp, geometry)
    return widgetOptionsSizes(listOf(grid.contentSize(column, row, spanX, spanY)))
}

/** Keep stored order stable across installs, removals and configuration changes. */
fun reconcileOrder(saved: List<String>, installed: List<String>): List<String> {
    val present = installed.toSet()
    return (saved.filter { it in present } + installed).distinct()
}

/** Installing an app must never create a home-screen pin. */
fun reconcilePins(saved: List<String>, installed: List<String>): List<String> {
    val available = installed.toSet()
    return saved.filter { it in available }.distinct()
}

fun migrateHomePins(legacy: List<String>, installed: List<String>, suggested: List<String>): List<String> {
    val surviving = reconcilePins(legacy, installed)
    val oldSet = surviving.toSet()
    val wasReordered = surviving.isNotEmpty() && surviving != installed.filter { it in oldSet }
    return if (wasReordered) surviving.take(16) else reconcilePins(suggested, installed).take(16)
}

fun homePageCount(cellCount: Int) = maxOf(1, (cellCount + HOME_CELLS - 1) / HOME_CELLS)

fun moveApp(order: List<String>, id: String, offset: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0) return order
    val to = (from + offset).coerceIn(0, order.lastIndex)
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
