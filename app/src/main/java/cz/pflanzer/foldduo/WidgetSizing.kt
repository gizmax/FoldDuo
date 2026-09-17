package cz.pflanzer.foldduo

import kotlin.math.ceil

data class WidgetSpan(val width: Int, val height: Int)

data class WidgetGridSizing(
    val columns: Int,
    val rows: Int,
    /** Distance between adjacent grid lines. A widget's content is span * pitch - gap. */
    val cellWidthDp: Float,
    /** Conservative pitch when the UI has rows of different heights. */
    val cellHeightDp: Float,
    /** Largest row pitch, used to keep a provider maximum valid across mixed-height rows. */
    val maximumCellHeightDp: Float = cellHeightDp,
    val horizontalGapDp: Float = 0f,
    val verticalGapDp: Float = 0f,
    /** Exact pitches used to publish the content rectangle for an anchored placement. */
    val topRowHeightDp: Float = cellHeightDp,
    val appRowHeightDp: Float = cellHeightDp,
)

data class WidgetContentSize(val widthDp: Float, val heightDp: Float)

fun WidgetGridSizing.contentSize(column: Int, row: Int, spanX: Int, spanY: Int): WidgetContentSize {
    require(column >= 0 && row >= 0 && spanX > 0 && spanY > 0)
    fun rowTop(value: Int) = if (value <= 2) value * topRowHeightDp
        else 2 * topRowHeightDp + (value - 2) * appRowHeightDp
    return WidgetContentSize(
        (spanX * cellWidthDp - horizontalGapDp).coerceAtLeast(1f),
        (rowTop(row + spanY) - rowTop(row) - verticalGapDp).coerceAtLeast(1f),
    )
}

// --- B40 "Widgety ve velikostních třídách" ----------------------------------------------

/**
 * iOS-style size class of a placement, from its span alone (the span is identical on both
 * panels under pane identity — LayoutModel.kt's [bothPanelContentSizes] is what differs per
 * panel). Named after the four footprints B40 calls out: 2 x 2 Small, 4 x 2 Medium, 4 x 4
 * Large, 4 x GRID_ROWS ExtraLarge (4 x 6 before the 2026-09-17 "Mřížka 4x7" change, 4 x 7 since —
 * the `spanY >= 5` threshold already covers either). The thresholds are width/height bands rather
 * than exact spans, so a grid other than today's 4 x GRID_ROWS (or an in-between resize) still
 * lands on one class.
 */
enum class WidgetSizeClass { Small, Medium, Large, ExtraLarge }

fun widgetSizeClassFor(spanX: Int, spanY: Int): WidgetSizeClass = when {
    spanX <= 2 && spanY <= 2 -> WidgetSizeClass.Small
    spanY <= 2 -> WidgetSizeClass.Medium
    spanY >= 5 -> WidgetSizeClass.ExtraLarge
    else -> WidgetSizeClass.Large
}

/** Picker label for [WidgetSizeClass]. */
fun widgetSizeClassLabel(sizeClass: WidgetSizeClass): String = when (sizeClass) {
    WidgetSizeClass.Small -> "Small"
    WidgetSizeClass.Medium -> "Medium"
    WidgetSizeClass.Large -> "Large"
    WidgetSizeClass.ExtraLarge -> "Extra Large"
}

/**
 * Content density of a built-in (Apple) widget, independent of [WidgetSizeClass]: the same
 * span can land in a shorter box on one panel than the other (cover vs inner, B40), so Compact
 * trims rows/detail (Calendar: 2 events instead of 4, Clock: time only, Photos: no title) while
 * Regular shows the full content. Chosen by measured dp height, never by which panel it is —
 * so a future grid or a mid-resize drag still picks the right variant.
 */
enum class WidgetContentVariant { Compact, Regular }

/** Below this height a widget is always Compact. */
const val CONTENT_VARIANT_COMPACT_DP = 120f

/** At or above this height a widget is always Regular. */
const val CONTENT_VARIANT_REGULAR_DP = 160f

/**
 * [heightDp] to a [WidgetContentVariant] with hysteresis: below [CONTENT_VARIANT_COMPACT_DP] is
 * always Compact and at/above [CONTENT_VARIANT_REGULAR_DP] always Regular; in the 40 dp band
 * between the two, [current] (the variant already showing) is kept, so a resize animation or a
 * panel-swap wobble hovering near the boundary does not flap every frame. With no prior variant
 * (first composition) the band's midpoint decides.
 */
fun widgetContentVariantFor(heightDp: Float, current: WidgetContentVariant? = null): WidgetContentVariant = when {
    heightDp < CONTENT_VARIANT_COMPACT_DP -> WidgetContentVariant.Compact
    heightDp >= CONTENT_VARIANT_REGULAR_DP -> WidgetContentVariant.Regular
    current != null -> current
    heightDp < (CONTENT_VARIANT_COMPACT_DP + CONTENT_VARIANT_REGULAR_DP) / 2f -> WidgetContentVariant.Compact
    else -> WidgetContentVariant.Regular
}

/**
 * dp bounds and the distinct sizes a host widget may be asked to fill, e.g. one per panel under
 * pane identity — the pure shape of the `OPTION_APPWIDGET_*` bundle
 * ([multiSizeOptionsBundle][ZeroPaddingWidgetHost.kt], kept out of this file so it stays free of
 * Android imports and unit-testable on the JVM). [sizes] is deduplicated so a placement whose two
 * panels happen to measure the same keeps a one-element `OPTION_APPWIDGET_SIZES`.
 */
data class WidgetOptionsSizes(
    val minWidthDp: Float,
    val minHeightDp: Float,
    val maxWidthDp: Float,
    val maxHeightDp: Float,
    val sizes: List<WidgetContentSize>,
)

fun widgetOptionsSizes(sizes: List<WidgetContentSize>): WidgetOptionsSizes {
    require(sizes.isNotEmpty()) { "at least one size is required" }
    val safe = sizes.map { WidgetContentSize(it.widthDp.coerceAtLeast(1f), it.heightDp.coerceAtLeast(1f)) }.distinct()
    return WidgetOptionsSizes(
        minWidthDp = safe.minOf { it.widthDp },
        minHeightDp = safe.minOf { it.heightDp },
        maxWidthDp = safe.maxOf { it.widthDp },
        maxHeightDp = safe.maxOf { it.heightDp },
        sizes = safe,
    )
}

data class WidgetProviderSizing(
    val minWidthDp: Float,
    val minHeightDp: Float,
    val minResizeWidthDp: Float = 0f,
    val minResizeHeightDp: Float = 0f,
    val maxResizeWidthDp: Float = 0f,
    val maxResizeHeightDp: Float = 0f,
    val targetCellWidth: Int = 0,
    val targetCellHeight: Int = 0,
    val horizontalPaddingDp: Float = 0f,
    val verticalPaddingDp: Float = 0f,
    val resizeMode: Int = RESIZE_NONE,
) {
    val canResizeHorizontally get() = resizeMode and RESIZE_HORIZONTAL != 0
    val canResizeVertically get() = resizeMode and RESIZE_VERTICAL != 0

    companion object {
        const val RESIZE_NONE = 0
        const val RESIZE_HORIZONTAL = 1
        const val RESIZE_VERTICAL = 2
    }
}

data class WidgetSpanConstraints(
    val preferred: WidgetSpan,
    val minimum: WidgetSpan,
    val maximum: WidgetSpan,
    val canResizeHorizontally: Boolean,
    val canResizeVertically: Boolean,
    val minimumFitsGrid: Boolean = true,
)

/** Derives bounded spans; minimumFitsGrid reports when provider minima exceed the grid. */
fun widgetSpanConstraints(provider: WidgetProviderSizing, grid: WidgetGridSizing): WidgetSpanConstraints? {
    require(grid.columns > 0 && grid.rows > 0)
    require(grid.cellWidthDp > 0f && grid.cellHeightDp > 0f && grid.maximumCellHeightDp > 0f)
    require(grid.horizontalGapDp >= 0f && grid.verticalGapDp >= 0f)

    require(provider.horizontalPaddingDp >= 0f && provider.verticalPaddingDp >= 0f)
    fun widthSpan(size: Float) = spanForSize(size + provider.horizontalPaddingDp, grid.cellWidthDp, grid.horizontalGapDp)
    fun heightSpan(size: Float, pitch: Float = grid.cellHeightDp) =
        spanForSize(size + provider.verticalPaddingDp, pitch, grid.verticalGapDp)
    val legacyWidth = widthSpan(provider.minWidthDp)
    val legacyHeight = heightSpan(provider.minHeightDp)

    val resizeMinWidth = widthSpan(provider.minResizeWidthDp)
    val resizeMinHeight = heightSpan(provider.minResizeHeightDp)
    val declaredMaxWidth = widthSpan(provider.maxResizeWidthDp)
    val declaredMaxHeight = heightSpan(provider.maxResizeHeightDp, grid.maximumCellHeightDp)
    val maxWidth = (if (provider.maxResizeWidthDp > 0f) declaredMaxWidth else grid.columns)
        .coerceAtLeast(resizeMinWidth)
    val maxHeight = (if (provider.maxResizeHeightDp > 0f) declaredMaxHeight else grid.rows)
        .coerceAtLeast(resizeMinHeight)
    val targetPairValid = provider.targetCellWidth in resizeMinWidth..maxWidth &&
        provider.targetCellHeight in resizeMinHeight..maxHeight
    val preferredWidth = if (targetPairValid) provider.targetCellWidth else legacyWidth
    val preferredHeight = if (targetPairValid) provider.targetCellHeight else legacyHeight
    val minWidth = minOf(preferredWidth, resizeMinWidth)
    val minHeight = minOf(preferredHeight, resizeMinHeight)
    val effectiveMinWidth = if (provider.canResizeHorizontally) minWidth else preferredWidth
    val effectiveMinHeight = if (provider.canResizeVertically) minHeight else preferredHeight
    val effectiveMaxWidth = if (provider.canResizeHorizontally) maxWidth else preferredWidth
    val effectiveMaxHeight = if (provider.canResizeVertically) maxHeight else preferredHeight
    val boundedMinWidth = effectiveMinWidth.coerceIn(1, grid.columns)
    val boundedMinHeight = effectiveMinHeight.coerceIn(1, grid.rows)
    val boundedMaxWidth = effectiveMaxWidth.coerceIn(boundedMinWidth, grid.columns)
    val boundedMaxHeight = effectiveMaxHeight.coerceIn(boundedMinHeight, grid.rows)

    return WidgetSpanConstraints(
        preferred = WidgetSpan(
            preferredWidth.coerceIn(boundedMinWidth, boundedMaxWidth),
            preferredHeight.coerceIn(boundedMinHeight, boundedMaxHeight),
        ),
        minimum = WidgetSpan(boundedMinWidth, boundedMinHeight),
        maximum = WidgetSpan(boundedMaxWidth, boundedMaxHeight),
        canResizeHorizontally = provider.canResizeHorizontally,
        canResizeVertically = provider.canResizeVertically,
        minimumFitsGrid = effectiveMinWidth <= grid.columns && effectiveMinHeight <= grid.rows,
    )
}

private fun spanForSize(sizeDp: Float, cellDp: Float, gapDp: Float): Int =
    ceil(((sizeDp.coerceAtLeast(0f) + gapDp) / cellDp).toDouble()).toInt().coerceAtLeast(1)
