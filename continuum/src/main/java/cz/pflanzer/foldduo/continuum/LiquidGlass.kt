// IDEAS.md B41 "Liquid Glass ikony a dock".
package cz.pflanzer.foldduo.continuum

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log

/**
 * Shared glue for res/raw/liquid_glass.agsl, mirroring [FoldShader]'s plumbing pattern: a
 * process-wide compiled [RuntimeShader], uniform setters, and nothing else — the wallpaper crop,
 * the per-cell [android.graphics.RenderEffect] cache and the light/bulge inputs live in the app
 * module (LiquidGlassBacking.kt), which has the Compose types and [cz.pflanzer.foldduo.FrostedBackdrop]
 * this shader's `content` samples. See LiquidGlassMotion.kt for a pure-Kotlin mirror of the
 * height-field/offset maths the AGSL performs, exercised by the JVM tests.
 */
object LiquidGlass {
    /** Refraction offset at rest (0 bulge), matching the shader's `REST_OFFSET_PX` constant. */
    const val REST_OFFSET_PX = 6f

    /** No extra tint: the shader's own fixed 6% white veil is enough. */
    val NO_TINT: FloatArray = floatArrayOf(1f, 1f, 1f, 0f)

    private const val TAG = "LiquidGlass"

    @Volatile
    private var source: String? = null

    @Volatile
    private var sharedShader: RuntimeShader? = null
    private val sharedLock = Any()

    /** A fresh, caller-owned shader (compiles the AGSL program); see [FoldShader.create]'s note on cost. */
    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.liquid_glass)
            .bufferedReader().use { it.readText() }
            .also { source = it }
        return try {
            RuntimeShader(src)
        } catch (e: Exception) {
            Log.e(TAG, "AGSL compile failed: ${e.message}", e)
            null
        }
    }

    /**
     * The process-wide shader for every Liquid Glass panel (icons, dock, folders): compiled once
     * and kept, like [FoldShader.shared]. B43 "Výkon jako feature" precompiles this at startup
     * through this same entry point.
     */
    fun shared(context: Context): RuntimeShader? = sharedShader ?: synchronized(sharedLock) {
        sharedShader ?: create(context.applicationContext)?.also { sharedShader = it }
    }

    /** The shared shader if [shared] has already compiled it; never compiles. */
    fun sharedIfReady(): RuntimeShader? = sharedShader

    fun setUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        cornerRadiusPx: Float,
        lightDirX: Float,
        lightDirY: Float,
        bulge: Float,
        tint: FloatArray = NO_TINT,
    ) {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("cornerRadius", cornerRadiusPx.coerceAtLeast(0f))
        shader.setFloatUniform("lightDir", lightDirX, lightDirY)
        shader.setFloatUniform("bulge", bulge.coerceIn(0f, 1f))
        shader.setFloatUniform("tint", tint.getOrElse(0) { 1f }, tint.getOrElse(1) { 1f },
            tint.getOrElse(2) { 1f }, tint.getOrElse(3) { 0f })
    }
}
