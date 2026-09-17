// Ported from marcoazeem/duo-open (MIT, Copyright (c) 2026 marcoazeem), see docs/upstream/LICENSE-duo-open.
// Adapted for Fold Duo on Galaxy Z Fold 8.
package cz.pflanzer.foldduo.continuum

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log

/** Where the hinge sits in a given surface, and how the eye looks at it. */
data class FoldLine(
    /** True when the hinge is a vertical line (splits the x axis). */
    val splitsX: Boolean,
    /** Hinge line position in px along the split axis. */
    val position: Float,
    /** Eye position in px along the split axis. */
    val eyePos: Float = position,
    /** Which side moves (-1 / +1 / 0 = both); null = [FoldConfig.movingSide]. */
    val movingSide: Int? = null,
)

/** Shared glue for res/raw/fold_morph.agsl — used by the launcher, wallpaper and (later) overlay. */
object FoldShader {
    /** Pane tilt cap; beyond this the kernel is mostly black anyway. */
    const val MAX_TILT = 45f

    /** Pane tilts below this draw the plain image (effect visually off). */
    const val FLAT_EPSILON = 0.05f

    // Galaxy Z Fold 8 values (2026-09-15, pose/testdata/README.md): the hinge angle here is the
    // magnetometer estimate (HingeAngleEstimator, ~9 deg RMS), not a hinge sensor, so the
    // mapping is deliberately wide. OnePlus Open originals: 172 / 20 / 6.

    /** Hinge angle treated as fully flat; real hinges rest short of 180° and the estimate rests at 180 ± 2. */
    const val FLAT_HINGE = 172f

    /**
     * Hinge angle at which the inner panel lights up (Samsung swaps the panel behind display 0):
     * OPENED commits at ~90–95° of lid angle on the HAL truth stream. The inner frost is full
     * here and clears continuously to [FLAT_HINGE]; the cover frost is full here too.
     */
    const val PANEL_ON_HINGE = 90f

    /**
     * Below this the cover counts as at rest (closed): the estimate's closed rest reads 0 ± 1°
     * and a 10° turn of the phone in the Earth field moves it ~12° before the compensation
     * has learned that orientation, so the cover frost only starts past this.
     */
    const val CLOSED_HINGE = 15f

    private const val TAG = "FoldShader"
    private const val REFERENCE_PX_PER_MM = 6f

    @Volatile
    private var source: String? = null

    @Volatile
    private var sharedShader: RuntimeShader? = null
    private val sharedLock = Any()

    /**
     * A fresh, caller-owned shader: the AGSL program is compiled on every call (a few ms of
     * SkSL work), so callers that draw every frame should keep the instance. The wallpaper
     * engine owns one this way; the launcher's morph uses [shared].
     */
    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.fold_morph)
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
     * The process-wide shader for the launcher's unfold morph, compiled once and kept.
     * Android relaunches the activity on every panel swap and the morph plays on the new
     * instance's first frame, so the compile must not be paid per activity. Safe to call from
     * any thread (the warm-up compiles it off the main thread); uniforms are only ever set on
     * the main thread, in layer blocks, and every [android.graphics.RenderEffect] snapshots
     * them at creation, so sharing one instance between layers is fine.
     */
    fun shared(context: Context): RuntimeShader? = sharedShader ?: synchronized(sharedLock) {
        sharedShader ?: create(context.applicationContext)?.also { sharedShader = it }
    }

    /** The shared shader if [shared] has already compiled it; never compiles. */
    fun sharedIfReady(): RuntimeShader? = sharedShader

    /**
     * Moving-pane tilt on the inner panel. The physical swing far exceeds what
     * the shader can show (it saturates at [MAX_TILT]), so instead of clamping
     * — which freezes the picture for most of an unfold — the visible range
     * [PANEL_ON_HINGE]..[FLAT_HINGE] is mapped linearly onto 0..[MAX_TILT],
     * so the frost keeps resolving the whole way open. Intensity scales it.
     */
    fun tiltForHinge(hingeDegrees: Float, config: FoldConfig): Float {
        val progress = ((config.flatHingeDeg - hingeDegrees) / (config.flatHingeDeg - config.panelOnHingeDeg)).coerceIn(0f, 1f)
        val full = if (config.movingSide == 0) MAX_TILT * 0.6f else MAX_TILT
        return (progress * full * config.intensity).coerceIn(0f, MAX_TILT)
    }

    /**
     * Tilt on the cover panel, which is live only for the first/last
     * [FoldConfig.panelOnHingeDeg] degrees: flat when closed, fully frosted at the swap.
     */
    fun coverTiltForHinge(hingeDegrees: Float, config: FoldConfig): Float {
        val progress = ((hingeDegrees - config.closedHingeDeg) / (config.panelOnHingeDeg - config.closedHingeDeg)).coerceIn(0f, 1f)
        return (progress * MAX_TILT * config.intensity).coerceIn(0f, MAX_TILT)
    }

    fun tiltFor(hingeDegrees: Float, config: FoldConfig, innerPanel: Boolean): Float =
        if (hingeDegrees.isNaN()) 0f
        else if (innerPanel) tiltForHinge(hingeDegrees, config)
        else coverTiltForHinge(hingeDegrees, config)

    /** Fallback hinge placement (centered) when no FoldingFeature is available. */
    fun centeredFold(width: Float, height: Float, foldSplitsLong: Boolean): FoldLine {
        val splitsX = if (foldSplitsLong) width >= height else width < height
        return FoldLine(splitsX, (if (splitsX) width else height) * 0.5f)
    }

    /**
     * The cover screen as a single pane hinged on one edge, viewed from its
     * center. [FoldConfig.coverFrostFromRight] puts the hinge on the left (the
     * spine side on the OnePlus Open) so the frost is heaviest at the right
     * edge and grows leftward; false mirrors it.
     */
    fun coverFold(width: Float, height: Float, config: FoldConfig): FoldLine =
        if (config.coverFrostFromRight) {
            FoldLine(splitsX = true, position = 0f, eyePos = width * 0.5f, movingSide = 1)
        } else {
            FoldLine(splitsX = true, position = width, eyePos = width * 0.5f, movingSide = -1)
        }

    fun foldFor(innerPanel: Boolean, width: Float, height: Float, config: FoldConfig): FoldLine =
        if (innerPanel) centeredFold(width, height, config.foldSplitsLong) else coverFold(width, height, config)

    fun pxPerMm(context: Context): Float {
        val xdpi = context.resources.displayMetrics.xdpi
        return if (xdpi.isFinite() && xdpi > 0f) xdpi / 25.4f else REFERENCE_PX_PER_MM
    }

    fun setUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        tiltDegrees: Float,
        config: FoldConfig,
        pxPerMm: Float,
        fold: FoldLine,
    ) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("tiltDegrees", tiltDegrees)
        shader.setFloatUniform("eyeDistancePx", config.eyeDistanceMm * pxPerMm)
        shader.setFloatUniform("hingePos", fold.position)
        shader.setFloatUniform("eyeX", fold.eyePos)
        shader.setFloatUniform("axisSwap", if (fold.splitsX) 0f else 1f)
        shader.setFloatUniform("paneSide", (fold.movingSide ?: config.movingSide).toFloat())
        shader.setFloatUniform("blurSpread", config.blurSpread)
        // Blur radius is in device px; renormalize the per-px darkening from the
        // original's ~6 px/mm so dense panels don't crush to black.
        shader.setFloatUniform("darkening", config.darkening * REFERENCE_PX_PER_MM / pxPerMm)
    }
}
