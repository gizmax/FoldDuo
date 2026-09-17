package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.continuum.SheetBend
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Výkon 3 "kreslení na inneru" (17. 9. noc): [sheetBendLayerNeeded]/[edgePulseLayerNeeded] are the
 * "does the Compose wallpaper chain need a `graphicsLayer` at all this frame" decisions behind
 * [rememberSheetBend]/[rememberEdgePulse] (DuneWallpaper.kt) — the pure half of "when bend == 0,
 * pulse == 0 the wallpaper layer must have no RenderEffect attached, and no per-frame
 * invalidation" the task asks to verify. The Compose glue (attach/detach `FoldDuoPerf` log,
 * `derivedStateOf`) needs a real composition to exercise and is not covered here, same split as
 * every other graphicsLayer-only piece of this app (HingeUnfoldTest.kt's own note applies).
 */
class DuneWallpaperGateTest {

    // ---- sheet bend ----

    @Test fun `no layer needed at exactly zero bend`() {
        assertTrue(!sheetBendLayerNeeded(0f))
    }

    @Test fun `no layer needed just under the flat epsilon, either sign`() {
        val justUnder = SheetBend.FLAT_EPSILON * 0.5f
        assertTrue(!sheetBendLayerNeeded(justUnder))
        assertTrue(!sheetBendLayerNeeded(-justUnder))
    }

    @Test fun `a layer is needed at or beyond the flat epsilon, either sign`() {
        assertTrue(sheetBendLayerNeeded(SheetBend.FLAT_EPSILON))
        assertTrue(sheetBendLayerNeeded(-SheetBend.FLAT_EPSILON))
        assertTrue(sheetBendLayerNeeded(10f))
        assertTrue(sheetBendLayerNeeded(-10f))
    }

    // ---- edge pulse ----

    @Test fun `no layer needed once pulse progress reaches 1 (played out)`() {
        assertTrue(!edgePulseLayerNeeded(1f))
        assertTrue(!edgePulseLayerNeeded(1.5f))
    }

    @Test fun `a layer is needed anywhere short of progress 1`() {
        assertTrue(edgePulseLayerNeeded(0f))
        assertTrue(edgePulseLayerNeeded(0.99f))
    }
}
