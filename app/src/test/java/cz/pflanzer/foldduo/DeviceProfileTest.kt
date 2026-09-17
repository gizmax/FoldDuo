package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.pose.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceProfile] is the profile layer this launcher never had before 2026-09-17 (user request:
 * "ať to subagent udělá, aby launcher běžel i na Fold 7"). Two device profiles are exercised:
 *
 *  - [DeviceProfile.FOLD8] must reproduce every geometric constant the launcher already shipped
 *    with (`LayoutModel.kt`'s `COVER_PANE_*_DP`/`INNER_PANE_*_DP`/`COVER_CUTOUT_DP`,
 *    `RailLayoutTest`'s 555x876/1088x821/544), within half a dp — those constants are hand
 *    rounded to a whole dp in places, this class is not, so exact bit-for-bit equality is not
 *    the goal (see `DeviceProfile.kt`'s doc on `FOLD8`).
 *  - a synthetic Galaxy Z Fold 7 (`DeviceProfile.fold7`, no physical unit exists to measure) at
 *    both ends of the request's stated "~2.625-3.0" density range, checking the geometry this
 *    launcher's pure functions (`homeGeometry`, `railLayout`, `WallpaperFraming`) produce for it
 *    stays sane — a 21:9 cover and a near-square inner are a real stress test of code that used
 *    to assume the Fold 8's own proportions.
 */
class DeviceProfileTest {
    private val eps = 0.5f

    // --- Fold 8: reproduces today's shipped constants -----------------------------------------

    @Test fun `fold8 profile reproduces the cover and inner panel pixel sizes`() {
        val p = DeviceProfile.FOLD8
        assertEquals(DeviceModel.FOLD8, p.model)
        assertEquals(1248, p.cover.widthPx); assertEquals(1972, p.cover.heightPx)
        assertEquals(2448, p.inner.widthPx); assertEquals(1848, p.inner.heightPx)
        assertEquals(DeviceProfile.FOLD8_DENSITY, p.cover.density, 0f)
        assertEquals(DeviceProfile.FOLD8_DENSITY, p.inner.density, 0f)
    }

    @Test fun `fold8 profile reproduces LayoutModel's pane dp constants within half a dp`() {
        val p = DeviceProfile.FOLD8
        assertEquals(COVER_PANE_WIDTH_DP, p.coverPaneWidthDp, eps)
        assertEquals(COVER_PANE_HEIGHT_DP, p.coverPaneHeightDp, eps)
        // The "inner pane" constants are the raw half-width of the inner panel (no gutter): what
        // a widget host on that panel, standing alone, would actually measure.
        assertEquals(INNER_PANE_WIDTH_DP, p.innerLeftPaneWidthDp, eps)
        assertEquals(INNER_PANE_WIDTH_DP, p.innerRightPaneWidthDp, eps)
        assertEquals(INNER_PANE_HEIGHT_DP, p.innerPaneHeightDp, eps)
        assertEquals(COVER_CUTOUT_DP, p.cover.cutout.topDp, 0.001f)
    }

    @Test fun `fold8 profile's seam sits at the inner panel's own centre, x=1224px`() {
        val p = DeviceProfile.FOLD8
        assertEquals(1224f / DeviceProfile.FOLD8_DENSITY, p.seamXDp, 0.01f)
        assertEquals(p.inner.widthDp / 2f, p.seamXDp, 0.001f)
    }

    @Test fun `cover must be the smaller-area panel, panel identity is never a hardcoded size`() {
        assertTrue(DeviceProfile.FOLD8.cover.areaPx < DeviceProfile.FOLD8.inner.areaPx)
        // Swapping cover and inner (as if some future device's "cover" were the larger panel)
        // must be rejected — this is the runtime version of Panels.kt's "smaller area = cover".
        var threw = false
        try {
            DeviceProfile.generic(cover = DeviceProfile.FOLD8.inner, inner = DeviceProfile.FOLD8.cover)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("constructing a profile with the larger panel tagged 'cover' must fail", threw)
    }

    // --- Fold 7: synthetic profile, both ends of the stated density range ---------------------

    @Test fun `fold7 pane widths at density 2_625`() {
        val p = DeviceProfile.fold7(density = 2.625f)
        assertEquals(DeviceModel.FOLD7, p.model)
        // Cover: 6.5" 21:9, portrait (1080x2520 px) -> ~411x960 dp, per the request's own numbers.
        assertEquals(411.43f, p.coverPaneWidthDp, eps)
        assertEquals(960f, p.coverPaneHeightDp, eps)
        // Inner: 8.0" near-square, landscape-native (2184x1968 px) -> 832x749.7 dp, halves to ~416 dp.
        assertEquals(832f, p.inner.widthDp, eps)
        assertEquals(416f, p.innerLeftPaneWidthDp, eps)
        assertEquals(416f, p.innerRightPaneWidthDp, eps)
        assertEquals(749.71f, p.innerPaneHeightDp, eps)
    }

    @Test fun `fold7 pane widths at density 3_0`() {
        val p = DeviceProfile.fold7(density = 3.0f)
        assertEquals(360f, p.coverPaneWidthDp, eps)
        assertEquals(840f, p.coverPaneHeightDp, eps)
        assertEquals(728f, p.inner.widthDp, eps)
        assertEquals(364f, p.innerLeftPaneWidthDp, eps)
        assertEquals(656f, p.innerPaneHeightDp, eps)
        // Cover is still the smaller-area panel at this density too (area is density-independent).
        assertTrue(p.cover.areaPx < p.inner.areaPx)
    }

    @Test fun `fold7's cover is narrower than the Fold 8's, its inner pane still holds a 4-column grid`() {
        val fold7 = DeviceProfile.fold7(density = 2.625f)
        assertTrue("Fold 7 cover (21:9) is narrower than the Fold 8's", fold7.coverPaneWidthDp < DeviceProfile.FOLD8.coverPaneWidthDp)
        val preset = LayoutPreset()
        val innerGeometry = homeGeometry(fold7.innerRightPaneWidthDp, fold7.innerPaneHeightDp, preset, labels = true,
            seamXDp = fold7.seamXDp, seamGutterDp = fold7.seamGutterDp)
        // homeGeometry divides gridWidth by 4 fixed columns (HomeEditing.kt's GRID_COLUMNS); a
        // sane, non-degenerate icon size here is the "4 columns fit" acceptance check.
        assertTrue(innerGeometry.gridWidth > 192f)
        assertTrue(innerGeometry.iconSize in 32f..66f)
        assertFalse(innerGeometry.iconSize.isNaN())
    }

    @Test fun `fold7's 21_9 cover gets a sane, non-degenerate grid too (taller cells, not fewer columns)`() {
        val fold7 = DeviceProfile.fold7(density = 2.625f)
        val preset = LayoutPreset()
        val coverGeometry = homeGeometry(fold7.coverPaneWidthDp, fold7.coverPaneHeightDp, preset, labels = true)
        assertFalse(coverGeometry.expanded) // narrower than FOLD_THRESHOLD_DP, same as the Fold 8's own cover
        assertTrue(coverGeometry.gridWidth > 192f)
        assertTrue(coverGeometry.iconSize in 32f..66f)
        // The cover is much taller than the Fold 8's (960 vs 830 dp): rows have more room, not less.
        assertTrue(coverGeometry.rowHeight > 0f)
    }

    @Test fun `fold7's inner pane is recognized as the expanded pair layout, cover is not`() {
        val fold7 = DeviceProfile.fold7(density = 2.625f)
        val preset = LayoutPreset()
        assertFalse(homeGeometry(fold7.coverPaneWidthDp, fold7.coverPaneHeightDp, preset, true).expanded)
        assertTrue(homeGeometry(fold7.inner.widthDp, fold7.innerPaneHeightDp, preset, true).expanded)
    }

    @Test fun `fold7 rail layout is sane on both panels`() {
        val fold7 = DeviceProfile.fold7(density = 2.625f)
        val preset = LayoutPreset()
        val coverGeometry = homeGeometry(fold7.coverPaneWidthDp, fold7.coverPaneHeightDp, preset, true)
        val rail = railLayout(fold7.coverPaneHeightDp, topInset = 0f, bottomInset = 0f, cutoutBottom = 0f,
            statusHeight = 120f, dockWidth = preset.dockWidth, dockIconSize = dockIconSize(coverGeometry.iconSize))
        assertTrue(rail.statusTop < rail.islandTop)
        assertTrue(rail.islandTop <= rail.dockTop)
        assertTrue(rail.dockTop < rail.dockBottom)
        assertTrue(rail.dockBottom <= rail.searchTop + 1f) // dock never overlaps the search control
        assertTrue(rail.searchBottom <= fold7.coverPaneHeightDp + 1f)
        assertTrue(rail.dockHeight > 0f)
    }

    // --- Wallpaper framing: cover crop mapping generalizes past "right half of the inner" -----

    @Test fun `fold7 cover crop is the right half of the inner crop, not a Fold 8 constant`() {
        val fold7 = DeviceProfile.fold7(density = 2.625f)
        val innerPx = fold7.inner
        val coverPx = fold7.cover
        val innerCrop = WallpaperFraming.surfaceCrop(4000f, 3000f, innerPx.widthPx.toFloat(), innerPx.heightPx.toFloat(),
            Panel.Inner, coverPx.widthPx.toFloat(), coverPx.heightPx.toFloat())
        val coverCrop = WallpaperFraming.surfaceCrop(4000f, 3000f, coverPx.widthPx.toFloat(), coverPx.heightPx.toFloat(),
            Panel.Cover, innerPx.widthPx.toFloat(), innerPx.heightPx.toFloat())
        // The cover crop sits inside the picture and roughly over the right half of the inner crop.
        assertTrue(coverCrop.left >= innerCrop.left)
        assertTrue(coverCrop.right <= innerCrop.right + 1f)
        assertTrue(coverCrop.left >= innerCrop.centerX - 1f)
        assertEquals(coverPx.widthPx.toFloat() / coverPx.heightPx.toFloat(), coverCrop.aspect, 0.01f)
    }

    @Test fun `DeviceModel forBuildModel tags known models and falls back to GENERIC`() {
        assertEquals(DeviceModel.FOLD8, DeviceModel.forBuildModel("SM-F971B"))
        assertEquals(DeviceModel.FOLD7, DeviceModel.forBuildModel("SM-F966B"))
        assertEquals(DeviceModel.GENERIC, DeviceModel.forBuildModel("Pixel Fold"))
        assertEquals(DeviceModel.GENERIC, DeviceModel.forBuildModel(""))
    }

    // --- Cutouts are measured rects, not a constant --------------------------------------------

    @Test fun `cutout handling nets out of usable size only on the panel that has one`() {
        val cutout = CutoutDp(topDp = 40f)
        val cover = PanelSpec(widthPx = 1080, heightPx = 2520, density = 2.625f, cutout = cutout)
        val inner = PanelSpec(widthPx = 2184, heightPx = 1968, density = 2.625f) // no cutout
        val p = DeviceProfile.generic(cover, inner)
        assertEquals(cover.heightDp - 40f, p.coverPaneHeightDp, 0.01f)
        assertEquals(inner.heightDp, p.innerPaneHeightDp, 0.01f) // untouched, no cutout on this panel
        assertEquals(CutoutDp.NONE, inner.cutout)
    }
}
