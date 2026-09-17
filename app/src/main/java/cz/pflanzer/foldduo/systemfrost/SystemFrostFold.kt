package cz.pflanzer.foldduo.systemfrost

import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldLine

/**
 * Pure geometry for the system-wide frost overlay's fold shader (STATUS.md "Mlha nad cizími
 * aplikacemi"): the exact same [FoldLine] the launcher builds for its own layers
 * (`LauncherScreen.kt`'s `coverFold`/`coverFrostLayer` around line 377-386, and
 * `todayFold`/`leftHalfFrost` around line 1417-1427), so [SystemFrost] can feed them straight
 * into [cz.pflanzer.foldduo.continuum.FoldShader.setUniforms] and get the same perspective tilt,
 * not just a blur. No Android types here so it is a plain JVM unit under app/src/test.
 *
 * The overlay window itself already IS the pane — [target] == INNER gets a window cropped to
 * just the inner left half (see `OverlayPlan.bounds(leftHalf = true)`), unlike the launcher
 * where one root layer spans the whole screen and the seam sits at an offset inside it. So here
 * the seam is simply this view's own edge:
 *  - COVER: the whole panel is the moving pane, hinged on its own left edge (x = 0), eye
 *    centered in the panel — same values as the launcher's cover layer (hinge at 0, `movingSide
 *    = 1`, mirroring `FoldConfig.coverFrostFromRight = true`).
 *  - INNER: the seam is this view's own right edge (`widthPx`, since the window's width already
 *    is the left-half width), eye centered in it, left side moving (`movingSide = -1`) — same
 *    values as the launcher's expanded-left-half layer.
 */
object SystemFrostFold {
    /**
     * Same tunables as the launcher's own layers ([FoldConfig]'s defaults); nothing
     * overlay-specific. B20's "Frost intensity" / "Tilt" sliders are folded in on top of this by
     * the caller ([cz.pflanzer.foldduo.systemfrost.SystemFrost.applyTilt], through
     * [cz.pflanzer.foldduo.MorphPreviewTuning.buildConfig]) rather than baked in here, so this
     * stays the fixed 1.0 baseline.
     */
    val baseConfig = FoldConfig()

    /** The [FoldLine] for [target]'s fold shader, given the overlay window's own size in px. */
    fun foldLineFor(target: OverlayTarget, widthPx: Float, heightPx: Float): FoldLine = when (target) {
        OverlayTarget.COVER -> FoldLine(splitsX = true, position = 0f, eyePos = widthPx * 0.5f, movingSide = 1)
        OverlayTarget.INNER -> FoldLine(splitsX = true, position = widthPx, eyePos = widthPx * 0.5f, movingSide = -1)
    }
}
