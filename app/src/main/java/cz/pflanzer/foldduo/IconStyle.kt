package cz.pflanzer.foldduo

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Launcher-side icon styling. Samsung Good Lock / Theme Park shapes and glass icons apply only
 * to One UI Home, so Duo renders them itself (see [IconRenderer]).
 */
enum class IconShape(val title: String) {
    Squircle("Squircle"), RoundedSquare("Rounded"), Circle("Circle"), Teardrop("Teardrop"), Square("Square")
}

enum class IconEffect(val title: String) { None("None"), Glass("Glass"), ClearGlass("Clear") }

/** Defaults keep the pre-style look: rounded square, no effect, system icons. */
data class IconStyle(
    val shape: IconShape = IconShape.RoundedSquare,
    val effect: IconEffect = IconEffect.None,
    val pack: String? = null,
    /** B41 "Liquid Glass": on by default, but only ever drawn for [IconEffect.ClearGlass] — see [usesLiquidGlass]. */
    val liquidGlass: Boolean = true,
    /**
     * B49 "Živé ikony": composable overlays (real clock hands, today's date, a battery ring, a
     * compass needle) drawn over a handful of known packages' baked icons — see
     * `LiveIconOverlays.kt`. On by default; this is the one master switch ("Live icons" in
     * Colours & glass), independent of [shape]/[effect]/[pack].
     */
    val liveIcons: Boolean = true,
) {
    /** Covered icon-pack artwork keeps its own outline, so Compose must not clip it to [shape]. */
    val clipsToShape: Boolean get() = pack == null || effect != IconEffect.None

    /** True when icon/dock/folder backings should draw the Liquid Glass wallpaper refraction. */
    val usesLiquidGlass: Boolean get() = effect == IconEffect.ClearGlass && liquidGlass
}

internal fun JSONObject.putIconStyle(style: IconStyle): JSONObject = put("iconShape", style.shape.name)
    .put("iconEffect", style.effect.name).put("iconPack", style.pack ?: JSONObject.NULL)
    .put("iconLiquidGlass", style.liquidGlass).put("iconLiveIcons", style.liveIcons)

internal fun JSONObject.iconStyle(): IconStyle = IconStyle(
    shape = IconShape.entries.firstOrNull { it.name == optString("iconShape") } ?: IconShape.RoundedSquare,
    effect = IconEffect.entries.firstOrNull { it.name == optString("iconEffect") } ?: IconEffect.None,
    pack = if (isNull("iconPack")) null else optString("iconPack").takeIf { it.isNotBlank() },
    liquidGlass = optBoolean("iconLiquidGlass", true),
    liveIcons = optBoolean("iconLiveIcons", true),
)

/** Radius of the historical rounded-square mask (34 px on a 144 px icon). */
internal const val ROUNDED_SQUARE_RADIUS = 34f / 144f
/** iOS-like superellipse exponent. */
internal const val SQUIRCLE_EXPONENT = 5.0

/**
 * Closed outline of [shape] inside a [size]×[size] square as x,y pairs; the last point repeats
 * the first. Pure so both the bitmap rasteriser and the Compose clip share one definition.
 */
internal fun iconShapeOutline(shape: IconShape, size: Float = 1f, samplesPerQuarter: Int = 24): FloatArray = when (shape) {
    IconShape.Squircle -> superellipseOutline(size, SQUIRCLE_EXPONENT, samplesPerQuarter * 4)
    IconShape.RoundedSquare -> roundedRectOutline(size, FloatArray(4) { ROUNDED_SQUARE_RADIUS * size }, samplesPerQuarter)
    IconShape.Circle -> roundedRectOutline(size, FloatArray(4) { size / 2f }, samplesPerQuarter)
    // Pixel-style teardrop: three round corners, a small radius at the bottom right.
    IconShape.Teardrop -> roundedRectOutline(size, floatArrayOf(size / 2f, size / 2f, size * .12f, size / 2f), samplesPerQuarter)
    IconShape.Square -> roundedRectOutline(size, FloatArray(4) { size * .06f }, samplesPerQuarter)
}

internal fun superellipseOutline(size: Float, exponent: Double, samples: Int): FloatArray {
    val half = size / 2.0
    val out = FloatArray((samples + 1) * 2)
    for (i in 0..samples) {
        val t = 2 * PI * (i % samples) / samples
        val c = cos(t); val s = sin(t)
        out[i * 2] = (half + half * sign(c) * abs(c).pow(2 / exponent)).toFloat().coerceIn(0f, size)
        out[i * 2 + 1] = (half + half * sign(s) * abs(s).pow(2 / exponent)).toFloat().coerceIn(0f, size)
    }
    return out
}

/** Corner radii in order top-left, top-right, bottom-right, bottom-left. */
internal fun roundedRectOutline(size: Float, radii: FloatArray, samplesPerCorner: Int): FloatArray {
    val r = FloatArray(4) { radii[it].coerceIn(0f, size / 2f) }
    // Corner centres and the start angle of each quarter arc, walking clockwise (y down).
    val centres = arrayOf(floatArrayOf(r[0], r[0]), floatArrayOf(size - r[1], r[1]),
        floatArrayOf(size - r[2], size - r[2]), floatArrayOf(r[3], size - r[3]))
    val starts = doubleArrayOf(PI, 1.5 * PI, 0.0, 0.5 * PI)
    val points = ArrayList<Float>((samplesPerCorner + 1) * 8 + 2)
    for (corner in 0 until 4) for (step in 0..samplesPerCorner) {
        val angle = starts[corner] + (PI / 2) * step / samplesPerCorner
        points += (centres[corner][0] + r[corner] * cos(angle)).toFloat().coerceIn(0f, size)
        points += (centres[corner][1] + r[corner] * sin(angle)).toFloat().coerceIn(0f, size)
    }
    points += points[0]; points += points[1]
    return points.toFloatArray()
}

/** Glass tile colour: white at [alpha], mixed [mix] toward the icon's [dominant] colour. ARGB ints, no Android APIs. */
internal fun glassTileColor(dominant: Int, alpha: Float, mix: Float = .15f): Int {
    fun channel(shift: Int): Int {
        val d = (dominant shr shift) and 0xFF
        return (255 + (d - 255) * mix).toInt().coerceIn(0, 255)
    }
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Alpha-weighted average colour of ARGB pixels; opaque mid-grey when fully transparent. */
internal fun dominantColor(pixels: IntArray): Int {
    var r = 0L; var g = 0L; var b = 0L; var weight = 0L
    for (p in pixels) {
        val a = (p ushr 24).toLong()
        if (a == 0L) continue
        r += ((p shr 16) and 0xFF) * a; g += ((p shr 8) and 0xFF) * a; b += (p and 0xFF) * a; weight += a
    }
    if (weight == 0L) return 0xFF808080.toInt()
    return (0xFF shl 24) or ((r / weight).toInt() shl 16) or ((g / weight).toInt() shl 8) or (b / weight).toInt()
}
