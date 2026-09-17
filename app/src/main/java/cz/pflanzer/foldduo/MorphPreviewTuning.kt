package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.pose.Panel

/**
 * B20, "Náhled morphu v nastavení": pure mapping from the Appearance sliders to the frost shader's
 * own tunables, so a live thumbnail and the real frost layers (LauncherScreen.kt's cover/left-half
 * layers, systemfrost/SystemFrost.kt's overlay) can share one calculation with no Android types —
 * plain JVM unit under app/src/test.
 *
 * Two sliders, both 0.5..1.5 (1.0 = today's behaviour, unchanged):
 *  - "Frost intensity" scales [FoldConfig.blurSpread] (how far the shader's blur kernel spreads);
 *    [buildConfig] folds this into the [FoldConfig] callers already build for [FoldShader.setUniforms].
 *  - "Tilt" scales the already-mapped tilt degrees ([scaleTilt]): [FoldConfig.intensity] is only
 *    consulted *inside* [FoldShader.tiltForHinge]/[FoldShader.coverTiltForHinge] while computing a
 *    tilt from a hinge angle, but callers here (the launcher's cover/left-half layers, the debug
 *    thumbnail, the system-wide overlay) hand [FoldShader.setUniforms] an already-computed degree
 *    value (from [MorphCurve]'s pure functions or a fixed hinge-angle mapping the overlay shares
 *    with the launcher) that never revisits [FoldConfig.intensity], so the slider multiplies that
 *    number directly instead.
 */
object MorphPreviewTuning {
    /** Slider range for both "Frost intensity" and "Tilt"; 1.0 sits in the middle, unchanged. */
    const val MIN_FACTOR = 0.5f
    const val MAX_FACTOR = 1.5f
    const val DEFAULT_FACTOR = 1f

    /** Clamp a slider value to the supported range. */
    fun clampFactor(value: Float): Float = value.coerceIn(MIN_FACTOR, MAX_FACTOR)

    /** [baseBlurSpread] ([FoldConfig.blurSpread]'s default) scaled by the "Frost intensity" slider. */
    fun blurSpread(baseBlurSpread: Float, frostFactor: Float): Float = baseBlurSpread * clampFactor(frostFactor)

    /** The "Tilt" slider as [FoldConfig.intensity], clamped. */
    fun tiltIntensity(tiltFactor: Float): Float = clampFactor(tiltFactor)

    /**
     * [base] with both sliders folded in: [FoldConfig.blurSpread] scaled by [frostFactor],
     * [FoldConfig.intensity] replaced by [tiltFactor] (a fresh 1.0 base config has intensity 1.0
     * to begin with, so replacing it is the same as multiplying).
     */
    fun buildConfig(base: FoldConfig, frostFactor: Float, tiltFactor: Float): FoldConfig =
        base.copy(intensity = tiltIntensity(tiltFactor), blurSpread = blurSpread(base.blurSpread, frostFactor))

    /** An already-computed tilt in degrees, scaled by the "Tilt" slider and re-clamped to the shader's cap. */
    fun scaleTilt(tiltDeg: Float, tiltFactor: Float): Float = (tiltDeg * clampFactor(tiltFactor)).coerceIn(0f, FoldShader.MAX_TILT)

    /** The preview thumbnail's tilt for the angle slider (0..180°), inner-panel mapping (same curve as a real unfold), with the "Tilt" slider folded in. */
    fun previewTilt(angleDeg: Float, tiltFactor: Float = DEFAULT_FACTOR): Float = scaleTilt(MorphCurve.angleTilt(Panel.Inner, angleDeg), tiltFactor)
}
