package cz.pflanzer.foldduo

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.pose.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Frosted glass without a per-frame blur. A RenderEffect on a card's layer would blur the card's
 * own content, not the wallpaper under it, and a live backdrop blur costs a full-screen pass
 * every frame. Instead one pre-blurred copy of the launcher background is kept per panel crop:
 * the background as DuneWallpaper draws it (photo or dunes), rendered at a quarter of the surface,
 * resampled to an eighth and box-blurred (FrostPixels.kt). A Glass card then draws the slice of
 * that copy lying under its own window bounds, a white veil, a hairline and a top highlight.
 * The upscale from 1/8 is the draw's bilinear filter, which adds to the softness.
 *
 * Cost: the backdrop is surface/8 squared, 306 x 231 px on the inner panel (~280 KB) and
 * 156 x 246 on the cover (~150 KB); a 1.1 MB quarter-size bitmap lives only during the build.
 * It is rebuilt off the main thread when the photo changes (LauncherBackgroundCache revision),
 * the panel or the day/night palette switches, or the surface is resized; never per frame.
 */

/**
 * The blurred background and the window rectangle the background covers, so a card at any
 * window position can find the backdrop pixels under it. Provided by LauncherScreen through
 * [LocalFrostedBackdrop]; null while there is none (first frame, a preview without one).
 */
@Immutable
class FrostedBackdrop(val image: ImageBitmap, val originX: Float, val originY: Float, val width: Float, val height: Float)

val LocalFrostedBackdrop = compositionLocalOf<FrostedBackdrop?> { null }

/** Last two backdrops built (one per panel, typically), so a recreated activity reuses them. */
internal object FrostedBackdropCache {
    private data class Key(val revision: Int, val panel: Panel, val dark: Boolean, val width: Int, val height: Int)
    private val entries = object : LinkedHashMap<Key, ImageBitmap>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>?) = size > 2
    }

    @Synchronized fun get(revision: Int, panel: Panel, dark: Boolean, width: Int, height: Int): ImageBitmap? =
        entries[Key(revision, panel, dark, width, height)]

    @Synchronized fun put(revision: Int, panel: Panel, dark: Boolean, width: Int, height: Int, image: ImageBitmap) {
        entries[Key(revision, panel, dark, width, height)] = image
    }
}

/**
 * The blurred copy of the launcher background for a [surfaceWidth]×[surfaceHeight] surface of
 * [panel]: [photo] cropped with pane identity like the live background, or the dunes (dark or
 * day) when there is none. Pure CPU work, safe off the main thread. Null for a surface too small
 * to matter or when the graphics stack refuses (the caller falls back to the plain glass fill).
 */
internal fun buildFrostedBackdrop(photo: Bitmap?, dark: Boolean, panel: Panel, surfaceWidth: Int, surfaceHeight: Int): Bitmap? {
    if (surfaceWidth < FROST_DOWNSCALE || surfaceHeight < FROST_DOWNSCALE) return null
    val quarterW = surfaceWidth / 4
    val quarterH = surfaceHeight / 4
    val quarter = Bitmap.createBitmap(quarterW, quarterH, Bitmap.Config.ARGB_8888)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(quarter)),
        Size(quarterW.toFloat(), quarterH.toFloat())) {
        drawLauncherBackground(photo?.takeUnless { it.isRecycled }?.asImageBitmap(), dark, panel)
    }
    val width = surfaceWidth / FROST_DOWNSCALE
    val height = surfaceHeight / FROST_DOWNSCALE
    val eighth = Bitmap.createScaledBitmap(quarter, width, height, true)
    if (eighth !== quarter) quarter.recycle()
    val pixels = IntArray(width * height)
    eighth.getPixels(pixels, 0, width, 0, 0, width, height)
    eighth.recycle()
    boxBlur3(pixels, width, height, FROST_BLUR_PASSES)
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * The frosted backdrop for this window's background, [surfaceWidth]×[surfaceHeight] px on
 * [panel], built off the main thread and keyed on the background revision, the panel, the
 * palette and the size. The previous image stays in place while a new one is built, so a
 * rotation or photo change never drops the cards to the flat fill in between. Uses the cached
 * photo only: while the launcher is still decoding it the dunes are blurred instead, and the
 * revision bump that publishes the photo rebuilds this.
 */
@Composable
internal fun rememberFrostedBackdrop(surfaceWidth: Int, surfaceHeight: Int, panel: Panel = currentPanel()): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val dark = LocalDuoPalette.current.dark
    val revision = LauncherBackgroundCache.revision.intValue
    var image by remember { mutableStateOf(FrostedBackdropCache.get(revision, panel, dark, surfaceWidth, surfaceHeight)) }
    LaunchedEffect(revision, panel, dark, surfaceWidth, surfaceHeight) {
        FrostedBackdropCache.get(revision, panel, dark, surfaceWidth, surfaceHeight)?.let { image = it; return@LaunchedEffect }
        if (surfaceWidth < FROST_DOWNSCALE || surfaceHeight < FROST_DOWNSCALE) { image = null; return@LaunchedEffect }
        val built = withContext(Dispatchers.Default) {
            runCatching { buildFrostedBackdrop(cachedLauncherBackground(context), dark, panel, surfaceWidth, surfaceHeight) }
                .getOrNull()?.asImageBitmap()
        }
        if (built != null) FrostedBackdropCache.put(revision, panel, dark, surfaceWidth, surfaceHeight, built)
        image = built
    }
    return image
}

/**
 * Frosted glass behind this composable: clipped to a [corner] rounded rectangle, the slice of
 * [LocalFrostedBackdrop] under the composable's window bounds, a white veil of [tintAlpha], a
 * top highlight (white 18 % fading out over the top 40 %) and a 1 dp [border] hairline.
 * Without a backdrop the card is the flat [fallback] fill plus the hairline, today's glass.
 *
 * Bounds: the window position is taken from onGloballyPositioned (a state, so a page scroll
 * redraws the slice) and re-read from the live LayoutCoordinates at each draw, which includes
 * graphicsLayer transforms. The Today pane slides in through a layer translation during the
 * unfold morph, which moves nothing in layout, so the draw also reads the morph progress: every
 * morph frame redraws the card with the wallpaper slice under its translated position, and the
 * glass stays fixed to the wallpaper while the pane moves over it. Nothing is allocated per
 * draw beyond the draw itself; the clip path and brushes are cached with the size.
 *
 * [veilColor] is the flat wash over the blurred slice: white by default, but B29 "Barvy z tapety"
 * defaults it to [LocalWallpaperPalette]'s derived veil instead — white on a dark wallpaper,
 * black on a bright one — so Glass stays legible either way; a host widget whose own background
 * sampled dark passes an explicit black here regardless (HostWidgetFrame.kt, HostFrameSpec.darkGlass).
 */
@Composable
fun Modifier.frostedGlass(corner: Dp, tintAlpha: Float = .22f, fallback: Color = Color.White.copy(alpha = .2f),
    border: Color? = Color.White.copy(alpha = .25f), veilColor: Color = Color(LocalWallpaperPalette.current.veilArgb),
    backdrop: FrostedBackdrop? = LocalFrostedBackdrop.current): Modifier {
    val morph = LocalMorphController.current
    val windowX = remember { mutableFloatStateOf(0f) }
    val windowY = remember { mutableFloatStateOf(0f) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { coords ->
            coordinates[0] = coords
            val position = coords.positionInWindow()
            windowX.floatValue = position.x
            windowY.floatValue = position.y
        }
        .drawWithCache {
            val radius = corner.toPx()
            val path = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius))) }
            val veil = veilColor.copy(alpha = tintAlpha.coerceIn(0f, 1f))
            val highlight = Brush.verticalGradient(0f to Color.White.copy(alpha = .18f), .4f to Color.Transparent,
                startY = 0f, endY = size.height.coerceAtLeast(1f))
            val hairline = 1.dp.toPx()
            val inset = hairline / 2f
            onDrawBehind {
                val image = backdrop?.image
                if (image == null || backdrop.width <= 0f || backdrop.height <= 0f) {
                    drawPath(path, fallback)
                } else {
                    // State reads: layout moves and morph frames both invalidate this draw.
                    var x = windowX.floatValue
                    var y = windowY.floatValue
                    morph?.progress?.value
                    coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.let { x = it.x; y = it.y }
                    val placement = backdropPlacement(x, y, backdrop.originX, backdrop.originY, backdrop.width, backdrop.height,
                        image.width, image.height)
                    clipPath(path) {
                        withTransform({
                            translate(placement.offsetX, placement.offsetY)
                            scale(placement.scaleX, placement.scaleY, Offset.Zero)
                        }) { drawImage(image) }
                        drawRect(veil)
                        drawRect(highlight)
                    }
                }
                if (border != null) drawRoundRect(border, Offset(inset, inset), Size(size.width - hairline, size.height - hairline),
                    CornerRadius((radius - inset).coerceAtLeast(0f)), Stroke(hairline))
            }
        }
}
