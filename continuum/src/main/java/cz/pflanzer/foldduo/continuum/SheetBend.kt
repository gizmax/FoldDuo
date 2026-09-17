// Fold Duo B37 "Tapeta jako ohýbaný list": original for this project (not ported from duo-open).
package cz.pflanzer.foldduo.continuum

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log

/**
 * Shared glue for res/raw/sheet_bend.agsl: the wallpaper drawn as a sheet of paper bent at the
 * hinge by the real device angle. Mirrors [FoldShader]'s create/shared/setUniforms split — a
 * fresh [RuntimeShader] per persistent-canvas caller (the wallpaper engine draws every frame and
 * keeps its own instance), one process-wide [shared] instance for the Compose launcher's
 * `RenderEffect` layer.
 */
object SheetBend {
    /** Bend uniform cap; matches the AGSL's own clamp. Beyond this the per-half tilt nears 90°. */
    const val MAX_BEND = 60f

    /** Bends whose magnitude is below this draw the plain image (effect visually off). */
    const val FLAT_EPSILON = 0.05f

    /** Default light direction: from the upper right, screen-space, already unit length. */
    val DEFAULT_LIGHT_DIR = floatArrayOf(0.6f, -0.8f)

    private const val TAG = "SheetBend"

    @Volatile
    private var source: String? = null

    @Volatile
    private var sharedShader: RuntimeShader? = null
    private val sharedLock = Any()

    /**
     * A fresh, caller-owned shader: the AGSL program is compiled on every call, so callers that
     * draw every frame should keep the instance (the wallpaper engine owns one this way); the
     * launcher's bend effect uses [shared].
     */
    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.sheet_bend)
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
        hingeX: Float,
        bendDeg: Float,
        eyeDistancePx: Float,
        lightDir: FloatArray = DEFAULT_LIGHT_DIR,
    ) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("hingeX", hingeX)
        shader.setFloatUniform("bendDeg", bendDeg)
        shader.setFloatUniform("eyeDistancePx", eyeDistancePx)
        shader.setFloatUniform("lightDir", lightDir[0], lightDir[1])
    }
}
