// Fold Duo B50 "Tapeta dýchá s oznámením": original for this project (not ported from duo-open).
package cz.pflanzer.foldduo.continuum

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log

/**
 * Shared glue for res/raw/edge_pulse.agsl: a soft additive glow entering from a screen edge,
 * synced with a notification's derived colour. Mirrors [SheetBend]'s create/shared/setUniforms
 * split — a fresh [RuntimeShader] per persistent-canvas caller (the wallpaper engine keeps its own
 * instance), one process-wide [shared] instance for the Compose launcher's `RenderEffect` layer.
 */
object EdgePulse {
    /** Edge codes for the AGSL's `edge` uniform; B50 only ever drives [EDGE_TOP]. */
    const val EDGE_TOP = 0f
    const val EDGE_BOTTOM = 1f
    const val EDGE_LEFT = 2f
    const val EDGE_RIGHT = 3f

    /** Matches the AGSL's own `MAX_OPACITY`; a progress at or past 1 draws nothing (see [setUniforms] callers). */
    const val MAX_OPACITY = 0.35f

    private const val TAG = "EdgePulse"

    @Volatile
    private var source: String? = null

    @Volatile
    private var sharedShader: RuntimeShader? = null
    private val sharedLock = Any()

    /**
     * A fresh, caller-owned shader: the AGSL program is compiled on every call, so callers that
     * draw every frame should keep the instance (the wallpaper engine owns one this way); the
     * launcher's pulse effect uses [shared].
     */
    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.edge_pulse)
            .bufferedReader().use { it.readText() }
            .also { source = it }
        return try {
            RuntimeShader(src)
        } catch (e: Exception) {
            Log.e(TAG, "AGSL compile failed: ${e.message}", e)
            null
        }
    }

    /** The process-wide shader for the launcher's wallpaper layer, compiled once and kept. */
    fun shared(context: Context): RuntimeShader? = sharedShader ?: synchronized(sharedLock) {
        sharedShader ?: create(context.applicationContext)?.also { sharedShader = it }
    }

    /** The shared shader if [shared] has already compiled it; never compiles. */
    fun sharedIfReady(): RuntimeShader? = sharedShader

    fun setUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        colorArgb: Int,
        progress: Float,
        edge: Float = EDGE_TOP,
    ) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("colour",
            ((colorArgb shr 16) and 0xFF) / 255f,
            ((colorArgb shr 8) and 0xFF) / 255f,
            (colorArgb and 0xFF) / 255f)
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("edge", edge)
    }
}
