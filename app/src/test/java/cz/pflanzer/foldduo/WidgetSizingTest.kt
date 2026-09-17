package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizingTest {
    private val grid = WidgetGridSizing(
        columns = 4,
        rows = 6,
        cellWidthDp = 80f,
        cellHeightDp = 72f,
        horizontalGapDp = 10f,
        verticalGapDp = 18f,
    )

    @Test fun targetCellsProvideThePreferredSpan() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 100f,
            minHeightDp = 100f,
            targetCellWidth = 3,
            targetCellHeight = 2,
        ), grid)!!

        assertEquals(WidgetSpan(3, 2), result.preferred)
        assertEquals(result.preferred, result.minimum)
        assertEquals(result.preferred, result.maximum)
    }

    @Test fun minimumDimensionsIncludeOnlyGapsBetweenSpannedCells() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 151f,
            minHeightDp = 127f,
        ), grid)!!

        assertEquals(WidgetSpan(3, 3), result.preferred)
    }

    @Test fun resizeBoundsUseLauncherCeilingForMaximum() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 54f,
            minResizeWidthDp = 70f,
            maxResizeWidthDp = 229f,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL,
        ), grid)!!

        assertEquals(WidgetSpan(2, 1), result.preferred)
        assertEquals(WidgetSpan(1, 1), result.minimum)
        assertEquals(WidgetSpan(3, 1), result.maximum)
    }

    @Test fun unboundedResizeMaximumUsesTheGridEdge() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 70f,
            minHeightDp = 54f,
            minResizeHeightDp = 54f,
            resizeMode = WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid)!!

        assertEquals(WidgetSpan(1, 6), result.maximum)
    }

    @Test fun defaultAndMaximumSpansHonorContentBoundsWithUnequalRowPitches() {
        val unequalRows = grid.copy(cellHeightDp = 60f, maximumCellHeightDp = 90f, verticalGapDp = 10f)
        val provider = WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 100f,
            minResizeHeightDp = 100f,
            maxResizeHeightDp = 260f,
            resizeMode = WidgetProviderSizing.RESIZE_VERTICAL,
        )
        val result = widgetSpanConstraints(provider, unequalRows)!!

        fun width(span: Int) = span * unequalRows.cellWidthDp - unequalRows.horizontalGapDp
        fun shortestHeight(span: Int) = span * unequalRows.cellHeightDp - unequalRows.verticalGapDp
        fun tallestHeight(span: Int) = span * unequalRows.maximumCellHeightDp - unequalRows.verticalGapDp
        assertTrue(width(result.preferred.width) >= provider.minWidthDp)
        assertTrue(shortestHeight(result.preferred.height) >= provider.minHeightDp)
        assertTrue(tallestHeight(result.maximum.height) <= provider.maxResizeHeightDp)
        assertEquals(WidgetSpan(2, 2), result.preferred)
        assertEquals(3, result.maximum.height)
    }

    @Test fun oversizedProviderUsesWholeGridAndReportsUnfulfilledMinimum() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 311f,
            minHeightDp = 72f,
        ), grid)!!
        assertEquals(WidgetSpan(4, 2), result.preferred)
        assertFalse(result.minimumFitsGrid)
    }

    @Test fun resizeMinimumAboveDefaultIsIgnoredPerProviderContract() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 72f,
            minResizeWidthDp = 231f,
            maxResizeWidthDp = 150f,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL,
        ), grid)!!
        assertEquals(WidgetSpan(2, 2), result.preferred)
        assertEquals(2, result.minimum.width)
    }

    @Test fun api31TargetCellsReplaceLegacyDefaultDimensions() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 151f,
            minHeightDp = 72f,
            targetCellWidth = 1,
            targetCellHeight = 1,
        ), grid)!!

        assertEquals(WidgetSpan(1, 1), result.preferred)
    }

    @Test fun weatherProviderTargetCellsFitDespiteLargerLegacyDimensions() {
        val current = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f, minHeightDp = 110f,
            minResizeWidthDp = 150f, minResizeHeightDp = 100f,
            targetCellWidth = 2, targetCellHeight = 2,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL or WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid)!!
        val timeline = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 250f, minHeightDp = 110f,
            minResizeWidthDp = 240f, minResizeHeightDp = 110f,
            targetCellWidth = 4, targetCellHeight = 2,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL or WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid)!!
        assertEquals(WidgetSpan(2, 2), current.preferred)
        assertEquals(WidgetSpan(4, 2), timeline.preferred)
    }

    @Test fun zeroPaddingBoundaryFitsAtExactProviderWidth() {
        val exact = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 110f,
            minResizeWidthDp = 150f,
            minResizeHeightDp = 110f,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL or WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid.copy(cellWidthDp = 80f, cellHeightDp = 64f))!!
        val justShort = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150.01f,
            minHeightDp = 110f,
        ), grid.copy(cellWidthDp = 80f, cellHeightDp = 64f))!!

        assertEquals(2, exact.preferred.width) // 2 * 80 - 10 = exactly 150dp of real content.
        assertEquals(3, justShort.preferred.width)
    }

    @Test fun anchoredContentSizeUsesExactMixedRowPitches() {
        val mixed = grid.copy(
            cellHeightDp = 60f,
            maximumCellHeightDp = 90f,
            topRowHeightDp = 90f,
            appRowHeightDp = 60f,
            verticalGapDp = 18f,
        )

        assertEquals(WidgetContentSize(150f, 162f), mixed.contentSize(0, 0, 2, 2))
        assertEquals(WidgetContentSize(310f, 102f), mixed.contentSize(0, 2, 4, 2))
        assertEquals(WidgetContentSize(150f, 132f), mixed.contentSize(0, 1, 2, 2))
    }

    // --- B40 size classes ---------------------------------------------------------------

    @Test fun sizeClassMatchesTheFourNamedFootprints() {
        assertEquals(WidgetSizeClass.Small, widgetSizeClassFor(2, 2))
        assertEquals(WidgetSizeClass.Medium, widgetSizeClassFor(4, 2))
        assertEquals(WidgetSizeClass.Large, widgetSizeClassFor(4, 4))
        assertEquals(WidgetSizeClass.ExtraLarge, widgetSizeClassFor(4, 6))
    }

    @Test fun sizeClassGeneralizesToOtherSpans() {
        assertEquals(WidgetSizeClass.Small, widgetSizeClassFor(1, 1))
        assertEquals(WidgetSizeClass.Medium, widgetSizeClassFor(3, 1))
        assertEquals(WidgetSizeClass.Large, widgetSizeClassFor(2, 3))
        assertEquals(WidgetSizeClass.Large, widgetSizeClassFor(4, 3))
        assertEquals(WidgetSizeClass.ExtraLarge, widgetSizeClassFor(2, 5))
        assertEquals(WidgetSizeClass.ExtraLarge, widgetSizeClassFor(4, 6))
    }

    @Test fun sizeClassLabelsAreTheIosNames() {
        assertEquals("Small", widgetSizeClassLabel(WidgetSizeClass.Small))
        assertEquals("Medium", widgetSizeClassLabel(WidgetSizeClass.Medium))
        assertEquals("Large", widgetSizeClassLabel(WidgetSizeClass.Large))
        assertEquals("Extra Large", widgetSizeClassLabel(WidgetSizeClass.ExtraLarge))
    }

    // --- B40 content variant, with hysteresis --------------------------------------------

    @Test fun contentVariantIsCompactBelowTheLowerThreshold() {
        assertEquals(WidgetContentVariant.Compact, widgetContentVariantFor(0f))
        assertEquals(WidgetContentVariant.Compact, widgetContentVariantFor(CONTENT_VARIANT_COMPACT_DP - 1f))
        // Always Compact below the lower threshold, regardless of the variant already showing.
        assertEquals(WidgetContentVariant.Compact, widgetContentVariantFor(CONTENT_VARIANT_COMPACT_DP - 1f, WidgetContentVariant.Regular))
    }

    @Test fun contentVariantIsRegularAtOrAboveTheUpperThreshold() {
        assertEquals(WidgetContentVariant.Regular, widgetContentVariantFor(CONTENT_VARIANT_REGULAR_DP))
        assertEquals(WidgetContentVariant.Regular, widgetContentVariantFor(CONTENT_VARIANT_REGULAR_DP + 500f))
        // Always Regular at/above the upper threshold, regardless of the variant already showing.
        assertEquals(WidgetContentVariant.Regular, widgetContentVariantFor(CONTENT_VARIANT_REGULAR_DP, WidgetContentVariant.Compact))
    }

    @Test fun contentVariantHoldsInsideTheHysteresisBandInsteadOfFlapping() {
        val mid = (CONTENT_VARIANT_COMPACT_DP + CONTENT_VARIANT_REGULAR_DP) / 2f
        assertEquals(WidgetContentVariant.Compact, widgetContentVariantFor(mid, WidgetContentVariant.Compact))
        assertEquals(WidgetContentVariant.Regular, widgetContentVariantFor(mid, WidgetContentVariant.Regular))
        // A whole pass back and forth across the band never flips once a side is held.
        var variant = WidgetContentVariant.Compact
        val heights = listOf(140f, 145f, 150f, 145f, 140f, 135f, 140f)
        heights.forEach { h -> variant = widgetContentVariantFor(h, variant) }
        assertEquals(WidgetContentVariant.Compact, variant)
    }

    @Test fun contentVariantWithNoPriorStateUsesTheBandMidpoint() {
        assertEquals(WidgetContentVariant.Compact, widgetContentVariantFor(CONTENT_VARIANT_COMPACT_DP, null))
        val mid = (CONTENT_VARIANT_COMPACT_DP + CONTENT_VARIANT_REGULAR_DP) / 2f
        assertEquals(WidgetContentVariant.Regular, widgetContentVariantFor(mid, null))
    }

    // --- B40 options-bundle builder (WidgetOptionsSizes) ----------------------------------

    @Test fun optionsSizesBoundEachDimensionAcrossBothPanels() {
        val result = widgetOptionsSizes(listOf(WidgetContentSize(200f, 90f), WidgetContentSize(196f, 96f)))
        assertEquals(196f, result.minWidthDp, 0f)
        assertEquals(200f, result.maxWidthDp, 0f)
        assertEquals(90f, result.minHeightDp, 0f)
        assertEquals(96f, result.maxHeightDp, 0f)
        assertEquals(listOf(WidgetContentSize(200f, 90f), WidgetContentSize(196f, 96f)), result.sizes)
    }

    @Test fun optionsSizesDedupesEqualPanelSizes() {
        val result = widgetOptionsSizes(listOf(WidgetContentSize(150f, 100f), WidgetContentSize(150f, 100f)))
        assertEquals(listOf(WidgetContentSize(150f, 100f)), result.sizes)
        assertEquals(150f, result.minWidthDp, 0f)
        assertEquals(150f, result.maxWidthDp, 0f)
    }

    @Test fun optionsSizesCoerceNonPositiveDimensionsToOneDp() {
        val result = widgetOptionsSizes(listOf(WidgetContentSize(-5f, 0f)))
        assertEquals(1f, result.minWidthDp, 0f)
        assertEquals(1f, result.minHeightDp, 0f)
        assertEquals(WidgetContentSize(1f, 1f), result.sizes.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun optionsSizesRequireAtLeastOneSize() {
        widgetOptionsSizes(emptyList())
    }

    // --- B40 both-panel content sizes, from LayoutModel -----------------------------------

    @Test fun bothPanelContentSizesGiveOnePerPanelForAHomePagePlacement() {
        val result = bothPanelContentSizes(LayoutPreset(), LayoutPreset(), labels = true, column = 0, row = 0, spanX = 2, spanY = 2)
        assertEquals(2, result.sizes.size)
        assertTrue(result.sizes.all { it.widthDp > 0f && it.heightDp > 0f })
    }

    @Test fun leadingPanelContentSizeGivesOnlyTheInnerSize() {
        val result = leadingPanelContentSize(LayoutPreset(), labels = true, column = 0, row = 0, spanX = 4, spanY = 6)
        assertEquals(1, result.sizes.size)
        assertTrue(result.sizes.single().widthDp > 0f && result.sizes.single().heightDp > 0f)
    }
}
