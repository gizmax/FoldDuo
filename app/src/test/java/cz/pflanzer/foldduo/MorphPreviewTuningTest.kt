package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.pose.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B20, "Náhled morphu v nastavení": the "Frost intensity" / "Tilt" sliders' pure mapping onto
 * [FoldConfig] and an already-computed tilt degree (see [MorphPreviewTuning]'s own KDoc for why
 * tilt is scaled directly rather than through [FoldConfig.intensity]).
 */
class MorphPreviewTuningTest {

    @Test fun `factor clamps to the slider range, default is unchanged`() {
        assertEquals(1f, MorphPreviewTuning.DEFAULT_FACTOR)
        assertEquals(0.5f, MorphPreviewTuning.clampFactor(0f))
        assertEquals(0.5f, MorphPreviewTuning.clampFactor(0.5f))
        assertEquals(1.5f, MorphPreviewTuning.clampFactor(1.5f))
        assertEquals(1.5f, MorphPreviewTuning.clampFactor(3f))
        assertEquals(0.8f, MorphPreviewTuning.clampFactor(0.8f), 1e-6f)
    }

    @Test fun `blur spread scales with the frost factor and clamps out-of-range factors`() {
        val base = FoldConfig().blurSpread
        assertEquals(base, MorphPreviewTuning.blurSpread(base, 1f), 1e-6f)
        assertEquals(base * 0.5f, MorphPreviewTuning.blurSpread(base, 0f), 1e-6f) // clamped to 0.5x, not 0
        assertEquals(base * 1.5f, MorphPreviewTuning.blurSpread(base, 10f), 1e-6f) // clamped to 1.5x
        assertEquals(base * 1.25f, MorphPreviewTuning.blurSpread(base, 1.25f), 1e-6f)
    }

    @Test fun `tilt intensity is the clamped factor, unchanged at default`() {
        assertEquals(1f, MorphPreviewTuning.tiltIntensity(1f))
        assertEquals(0.5f, MorphPreviewTuning.tiltIntensity(0.1f))
        assertEquals(1.5f, MorphPreviewTuning.tiltIntensity(9f))
    }

    @Test fun `buildConfig folds both sliders into a fresh FoldConfig, leaving the rest untouched`() {
        val base = FoldConfig()
        val built = MorphPreviewTuning.buildConfig(base, frostFactor = 1.4f, tiltFactor = 0.6f)
        assertEquals(base.blurSpread * 1.4f, built.blurSpread, 1e-6f)
        assertEquals(0.6f, built.intensity, 1e-6f)
        // Untouched fields carry over from base.
        assertEquals(base.darkening, built.darkening, 1e-6f)
        assertEquals(base.eyeDistanceMm, built.eyeDistanceMm, 1e-6f)
        assertEquals(base.movingSide, built.movingSide)
        assertEquals(base.coverFrostFromRight, built.coverFrostFromRight)
    }

    @Test fun `scaleTilt multiplies and clamps to the shader's tilt cap`() {
        assertEquals(30f, MorphPreviewTuning.scaleTilt(30f, 1f), 1e-6f)
        assertEquals(15f, MorphPreviewTuning.scaleTilt(30f, 0.5f), 1e-6f)
        // 45 * 1.5 = 67.5, clamped down to the shader's cap.
        assertEquals(FoldShader.MAX_TILT, MorphPreviewTuning.scaleTilt(45f, 1.5f), 1e-6f)
        assertEquals(0f, MorphPreviewTuning.scaleTilt(0f, 1.5f), 1e-6f)
    }

    @Test fun `preview tilt follows the same curve as a real unfold, scaled by the tilt slider`() {
        val flat = MorphPreviewTuning.previewTilt(FoldShader.FLAT_HINGE)
        assertEquals(0f, flat, 1e-6f)
        val onAtDefault = MorphPreviewTuning.previewTilt(FoldShader.PANEL_ON_HINGE)
        assertEquals(MorphCurve.angleTilt(Panel.Inner, FoldShader.PANEL_ON_HINGE), onAtDefault, 1e-6f)
        val onAtHalfTilt = MorphPreviewTuning.previewTilt(FoldShader.PANEL_ON_HINGE, tiltFactor = 0.5f)
        assertEquals(onAtDefault * 0.5f, onAtHalfTilt, 1e-4f)
        assertTrue(onAtHalfTilt < onAtDefault)
    }
}
