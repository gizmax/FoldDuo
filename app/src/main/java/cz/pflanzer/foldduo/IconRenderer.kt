package cz.pflanzer.foldduo

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

internal const val ICON_PX = 144

private val unitOutlines = IconShape.entries.associateWith { iconShapeOutline(it, 1f) }

internal fun iconShapePath(shape: IconShape, size: Float = ICON_PX.toFloat()): Path = Path().apply {
    val o = unitOutlines.getValue(shape)
    moveTo(o[0] * size, o[1] * size)
    var i = 2
    while (i < o.size) { lineTo(o[i] * size, o[i + 1] * size); i += 2 }
    close()
}

private val composeShapes: Map<IconShape, Shape> = IconShape.entries.associateWith { shape ->
    GenericShape { size, _ ->
        val o = unitOutlines.getValue(shape)
        moveTo(o[0] * size.width, o[1] * size.height)
        var i = 2
        while (i < o.size) { lineTo(o[i] * size.width, o[i + 1] * size.height); i += 2 }
        close()
    }
}

internal fun iconComposeShape(shape: IconShape): Shape = composeShapes.getValue(shape)

/** The icon style Home is currently rendering; composables use it for clips and the drag ghost. */
internal val LocalIconStyle = staticCompositionLocalOf { IconStyle() }

/** Clip for an app icon bitmap: the chosen outline, or none for untreated icon-pack artwork. */
internal fun IconStyle.clipShape(): Shape = if (clipsToShape) iconComposeShape(shape) else RectangleShape

/**
 * Rasterises one app icon into a cached 144 px bitmap with alpha (never flattened onto black).
 * All effects are baked here, so drawing on Home is a plain bitmap blit.
 */
internal object IconRenderer {
    private const val LEGACY_SCALE = .80f
    private const val GLASS_SCALE = .72f
    /** How far the tile leans toward a backing colour that stayed on the foreground (fallback path). */
    private const val BACKING_TINT_MIX = .25f
    private val NEUTRAL_BACKING = 0xFFF4F4F6.toInt()

    fun render(system: Drawable, style: IconStyle, pack: LoadedIconPack?, component: ComponentName?): Bitmap {
        val packIcon = if (pack != null && component != null) runCatching { pack.iconFor(component) }.getOrNull() else null
        if (style.effect != IconEffect.None) return glass(packIcon ?: system, style)
        if (packIcon != null) return if (packIcon is AdaptiveIconDrawable) shaped(packIcon, style.shape)
            else packIcon.draw(ICON_PX)
        if (pack != null && component != null && pack.filter.hasTreatment)
            runCatching { return packTreatment(system, style, pack, component) }
        return shaped(system, style.shape)
    }

    private fun Drawable.draw(size: Int, inset: Float = 0f): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { drawInto(Canvas(it), size, inset) }

    private fun Drawable.drawInto(canvas: Canvas, size: Int, inset: Float) {
        val pad = (size * inset).toInt()
        setBounds(pad, pad, size - pad, size - pad)
        draw(canvas)
    }

    /** Adaptive icons get the shape; legacy icons sit 80 % on a shape-filled neutral backing. */
    fun shaped(drawable: Drawable, shape: IconShape): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(iconShapePath(shape))
        if (drawable is AdaptiveIconDrawable) {
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.background?.draw(canvas)
            drawable.foreground?.draw(canvas)
        } else {
            canvas.drawColor(NEUTRAL_BACKING)
            drawable.drawInto(canvas, ICON_PX, (1f - LEGACY_SCALE) / 2f)
        }
        return bitmap
    }

    /** ADW/Nova fallback for apps the pack doesn't cover: iconback, scaled icon erased by iconmask, iconupon. */
    private fun packTreatment(system: Drawable, style: IconStyle, pack: LoadedIconPack, component: ComponentName): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        pack.backFor(component)?.drawInto(canvas, ICON_PX, 0f)
        val layer = canvas.saveLayer(null, null)
        val icon = if (pack.filter.iconMask != null) system.draw(ICON_PX) else shaped(system, style.shape)
        val inset = (1f - pack.filter.scale.coerceIn(.1f, 1f)) / 2f * ICON_PX
        canvas.drawBitmap(icon, null, android.graphics.RectF(inset, inset, ICON_PX - inset, ICON_PX - inset),
            Paint(Paint.FILTER_BITMAP_FLAG))
        pack.mask()?.let { mask ->
            canvas.drawBitmap(mask.draw(ICON_PX), 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        canvas.restoreToCount(layer)
        pack.upon()?.drawInto(canvas, ICON_PX, 0f)
        return bitmap
    }

    /**
     * Glass / Clear glass: the glyph only on a translucent tinted tile with rim light and specular highlight.
     * Foregrounds (and legacy icons) that carry their own opaque backing — Calendar's white square, Play's white
     * tile — have it keyed out (see [IconPixels]) so only the glyph sits on the glass, as iOS and One UI do.
     */
    fun glass(source: Drawable, style: IconStyle): Bitmap {
        val clear = style.effect == IconEffect.ClearGlass
        val size = ICON_PX.toFloat()
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = iconShapePath(style.shape)
        canvas.clipPath(path)

        val adaptive = source as? AdaptiveIconDrawable
        // Clear glass prefers the monochrome layer; backing removal only applies when there is none.
        val monochrome = if (clear) adaptive?.monochrome else null
        val stripped = if (monochrome == null) stripBacking(source, adaptive) else StrippedIcon.Untouched
        val tintSource = adaptive?.background ?: source
        val dominant = runCatching {
            val sample = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
            if (adaptive != null) { adaptive.setBounds(0, 0, 12, 12); tintSource.draw(Canvas(sample)) }
            else source.drawInto(Canvas(sample), 12, 0f)
            IntArray(144).also { sample.getPixels(it, 0, 12, 0, 0, 12, 12) }.let(::dominantColor)
        }.getOrDefault(0xFF808080.toInt())
        val tileAlpha = if (clear) .10f else .22f
        // A backing that could not be stripped tints the tile instead of showing as an opaque square.
        canvas.drawColor(if (stripped is StrippedIcon.Tint) glassTileColor(stripped.backing.color, tileAlpha, BACKING_TINT_MIX)
            else glassTileColor(dominant, tileAlpha))

        // Specular highlight: a soft white ellipse in the top-left.
        canvas.drawRect(0f, 0f, size, size, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(size * .30f, size * .20f, size * .55f,
                intArrayOf(0x2EFFFFFF, 0x14FFFFFF, 0x00FFFFFF), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(1f, .62f, size * .30f, size * .20f) })
            }
        })

        // Stripped glyph at ~64 % (already centred in its buffer), otherwise the foreground at ~72 %.
        val inset = (1f - GLASS_SCALE) / 2f
        val glyphPaint = if (!clear) null else Paint().apply {
            alpha = 230
            colorFilter = if (monochrome != null) PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
                else ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    .2126f * .45f, .7152f * .45f, .0722f * .45f, 0f, 140f,
                    .2126f * .45f, .7152f * .45f, .0722f * .45f, 0f, 140f,
                    .2126f * .45f, .7152f * .45f, .0722f * .45f, 0f, 140f,
                    0f, 0f, 0f, 1f, 0f)))
        }
        val layer = canvas.saveLayer(null, glyphPaint)
        when {
            stripped is StrippedIcon.Glyph ->
                canvas.drawBitmap(Bitmap.createBitmap(stripped.pixels, ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888), 0f, 0f, null)
            adaptive != null -> {
                val pad = (size * inset).toInt()
                drawLayer(canvas, adaptive, monochrome ?: adaptive.foreground, pad, ICON_PX - 2 * pad)
            }
            else -> source.drawInto(canvas, ICON_PX, inset)
        }
        canvas.restoreToCount(layer)

        // 1.5 px inner rim: a 3 px stroke on the clipped outline, white 45 % at the top to 10 % at the bottom.
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 3f
            shader = LinearGradient(0f, 0f, 0f, size, 0x73FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP)
        })
        return bitmap
    }

    /**
     * Rasterises what the glass tile would show — an adaptive icon's whole 108 dp foreground layer, or the whole
     * legacy / pack drawable — into a 144 px buffer and asks [IconPixels] to strip any opaque backing from it.
     * Any failure (odd drawables, huge bitmaps) leaves the icon untouched.
     */
    private fun stripBacking(source: Drawable, adaptive: AdaptiveIconDrawable?): StrippedIcon = runCatching {
        val pixels = layerPixels(source, adaptive) ?: return StrippedIcon.Untouched
        IconPixels.stripBacking(pixels, ICON_PX, ICON_PX, safeInset(adaptive))
    }.getOrDefault(StrippedIcon.Untouched)

    internal fun safeInset(adaptive: AdaptiveIconDrawable?): Float =
        if (adaptive != null) IconPixels.ADAPTIVE_SAFE_INSET else IconPixels.LEGACY_SAFE_INSET

    /**
     * Draws one layer of [adaptive] as if the icon occupied a [size] square at ([offset], [offset]): the layer
     * itself then spans 108/72 of that square, centred on it. [AdaptiveIconDrawable] composites its layers into
     * its own bitmap and only blits that at its bounds' origin, so it gives the layers bounds relative to (0, 0)
     * whatever its own left/top are — drawing a layer straight onto a canvas with the icon bounds set to a
     * non-zero origin lands it [offset] too far up and left (Maps' pin in the tile's corner). Hence origin bounds
     * plus a canvas translation.
     */
    private fun drawLayer(canvas: Canvas, adaptive: AdaptiveIconDrawable, layer: Drawable?, offset: Int, size: Int) {
        if (layer == null) return
        adaptive.setBounds(0, 0, size, size)
        val save = canvas.save()
        canvas.translate(offset.toFloat(), offset.toFloat())
        layer.draw(canvas)
        canvas.restoreToCount(save)
        adaptive.setBounds(0, 0, ICON_PX, ICON_PX)
    }

    /**
     * The 144 px ARGB buffer [stripBacking] works on: an adaptive icon's full 108 dp foreground layer (drawn with
     * bounds of two thirds, as layers render at 108/72 of the icon bounds), or the whole legacy / pack drawable.
     * Null for an adaptive icon without a foreground.
     */
    internal fun layerPixels(source: Drawable, adaptive: AdaptiveIconDrawable?): IntArray? {
        val foreground = adaptive?.foreground
        if (adaptive != null && foreground == null) return null
        val layer = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layer)
        if (adaptive != null) drawLayer(canvas, adaptive, foreground!!, ICON_PX / 6, ICON_PX - 2 * (ICON_PX / 6))
        else source.drawInto(canvas, ICON_PX, 0f)
        val pixels = IntArray(ICON_PX * ICON_PX).also { layer.getPixels(it, 0, ICON_PX, 0, 0, ICON_PX, ICON_PX) }
        layer.recycle()
        return pixels
    }
}
