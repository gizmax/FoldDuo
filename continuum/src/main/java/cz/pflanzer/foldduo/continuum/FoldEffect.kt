// Ported from marcoazeem/duo-open (MIT, Copyright (c) 2026 marcoazeem), see docs/upstream/LICENSE-duo-open.
// Adapted for Fold Duo on Galaxy Z Fold 8.
package cz.pflanzer.foldduo.continuum

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Per-layer cache of the last [RenderEffect] built by [foldEffect]. A `RenderEffect` snapshots
 * the shader uniforms when it is created, so a new tilt needs a new effect; but identical
 * uniforms (the first animated frame after a snap to the start, or a primed start frame)
 * reuse the previous one, and handing the layer the same instance again skips the native
 * `RenderNode.setRenderEffect`. One instance per layer; main thread only.
 */
class FoldEffectCache {
    private var width = -1f
    private var height = -1f
    private var tilt = Float.NaN
    private var line: FoldLine? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    /** True when the cached effect already matches these inputs. */
    fun matches(width: Float, height: Float, tilt: Float, line: FoldLine): Boolean =
        effect != null && this.width == width && this.height == height && this.tilt == tilt && this.line == line

    /** The effect for these inputs, building (and caching) it only when they changed. */
    fun effectFor(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        tilt: Float,
        config: FoldConfig,
        pxPerMm: Float,
        line: FoldLine,
    ): androidx.compose.ui.graphics.RenderEffect {
        effect?.takeIf { matches(width, height, tilt, line) }?.let { return it }
        FoldShader.setUniforms(shader, width, height, tilt, config, pxPerMm, line)
        val built = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        this.width = width; this.height = height; this.tilt = tilt; this.line = line
        effect = built
        return built
    }
}

/**
 * Applies the two-pane Duo fold to this layout subtree. [tilt] is read inside
 * the layer block, so a moving hinge only re-runs the layer, not composition.
 *
 * @param fold Hinge placement in layer px; null centers it using
 *   [FoldConfig.foldSplitsLong].
 * @param cache Reuses the effect across frames with identical uniforms (see [FoldEffectCache]).
 * @param primeTilt When the layer is at rest (effect off) and this is set, the effect for this
 *   tilt is built ahead of time at the layer's current size, so the first animated frame of a
 *   morph that starts at [primeTilt] finds it ready instead of allocating it on the hitching
 *   frame. Needs [cache].
 */
fun Modifier.foldEffect(
    shader: RuntimeShader,
    tilt: () -> Float,
    config: FoldConfig,
    pxPerMm: Float,
    fold: FoldLine?,
    cache: FoldEffectCache? = null,
    primeTilt: Float? = null,
): Modifier = graphicsLayer {
    val t = tilt()
    val w = size.width
    val h = size.height
    if (t < FoldShader.FLAT_EPSILON || w <= 1f || h <= 1f) {
        renderEffect = null
        if (cache != null && primeTilt != null && w > 1f && h > 1f) {
            val line = fold ?: FoldShader.centeredFold(w, h, config.foldSplitsLong)
            if (!cache.matches(w, h, primeTilt, line)) cache.effectFor(shader, w, h, primeTilt, config, pxPerMm, line)
        }
        return@graphicsLayer
    }
    val line = fold ?: FoldShader.centeredFold(w, h, config.foldSplitsLong)
    renderEffect = if (cache != null) cache.effectFor(shader, w, h, t, config, pxPerMm, line) else {
        FoldShader.setUniforms(shader, w, h, t, config, pxPerMm, line)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
    clip = true
}
