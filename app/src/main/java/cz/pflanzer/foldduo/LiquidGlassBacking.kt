package cz.pflanzer.foldduo

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import cz.pflanzer.foldduo.continuum.LiquidGlass
import kotlinx.coroutines.isActive

/*
 * Compose glue for Liquid Glass (IDEAS.md B41): the wallpaper-crop backing and the AGSL panel
 * that refracts it. LiquidGlassMotion.kt has the pure maths; continuum/LiquidGlass.kt has the
 * shader plumbing (mirrors FoldShader). This file only exists in the app module because it needs
 * both — Compose's graphicsLayer/RenderEffect and [FrostedBackdrop], the wallpaper slice source,
 * which the continuum module (a plain Android library, no wallpaper/pose knowledge) cannot see.
 */

/**
 * Live light direction for Liquid Glass panels: gravity, low-passed and quantized
 * ([LiquidGlassLight.quantize]) so a still phone republishes nothing and every panel's
 * [Modifier.liquidGlassPanel] keeps hitting its [LiquidGlassEffectCache] instead of rebuilding a
 * [RenderEffect] every frame the gravity loop ticks. Reduced motion never starts the loop, so the
 * direction stays at [LiquidGlassLight.DEFAULT_X]/[LiquidGlassLight.DEFAULT_Y] (top-left) — a
 * fixed light, as B41 asks. The [PoseEngine] is only *read* here, like [rememberWallpaperParallax]:
 * [MainActivity] already holds it acquired for the unfold morph while the launcher is resumed.
 */
@Composable
fun rememberLiquidGlassLight(): State<Offset> {
    val context = LocalContext.current
    val reduceMotion = remember(context) { systemReduceMotionEnabled(context) }
    val light = remember { LiquidGlassLight() }
    val direction = remember { mutableStateOf(Offset(LiquidGlassLight.DEFAULT_X, LiquidGlassLight.DEFAULT_Y)) }
    LaunchedEffect(context, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        val pose = PoseEngine.get(context)
        while (isActive) {
            withFrameNanos { frameNanos ->
                val g = pose.snapshot.value.gravity
                light.update(frameNanos / 1_000_000L, g[0], g[1])
                val quantized = Offset(LiquidGlassLight.quantize(light.dirX), LiquidGlassLight.quantize(light.dirY))
                if (quantized != direction.value) direction.value = quantized
            }
        }
    }
    return direction
}

/**
 * Výkon 2 (17. 9. noc): [Modifier.liquidGlassPanel] used to call [rememberLiquidGlassLight] itself,
 * so every Home/dock/folder tile with Liquid Glass on started its OWN `withFrameNanos` loop —
 * B41 is wired into `AppTile`, `FolderTile` and the dock (`LauncherScreen.kt`), App Library and
 * Spotlight rows, so a normal Home page's worth of icons meant a few dozen independent per-frame
 * gravity reads/low-pass updates for what is, functionally, one shared light direction. `MainActivity`
 * now calls [rememberLiquidGlassLight] exactly once and provides the resulting `State<Offset>`
 * here; every panel reads the same stable `State` object and defers `.value` to its own
 * `graphicsLayer` block ([LiquidGlassEffectCache] still gates the actual [RenderEffect] rebuild),
 * so one loop drives every panel instead of one loop per panel.
 */
val LocalLiquidGlassLight = staticCompositionLocalOf<State<Offset>> {
    mutableStateOf(Offset(LiquidGlassLight.DEFAULT_X, LiquidGlassLight.DEFAULT_Y))
}

/**
 * Per-panel cache of the last [RenderEffect] [Modifier.liquidGlassPanel] built, mirroring
 * [cz.pflanzer.foldduo.continuum.FoldEffectCache]: a new one is only built when the size, corner
 * radius, light direction, bulge or tint actually changed, so a still icon under a still light
 * reuses the same instance frame after frame — one per panel, main thread only.
 */
private class LiquidGlassEffectCache {
    private var width = -1f
    private var height = -1f
    private var corner = -1f
    private var lightX = Float.NaN
    private var lightY = Float.NaN
    private var bulge = Float.NaN
    private var tint: Color = Color.Unspecified
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    fun effectFor(
        shader: RuntimeShader, width: Float, height: Float, cornerPx: Float,
        lightX: Float, lightY: Float, bulge: Float, tint: Color,
    ): androidx.compose.ui.graphics.RenderEffect {
        effect?.takeIf {
            this.width == width && this.height == height && this.corner == cornerPx &&
                this.lightX == lightX && this.lightY == lightY && this.bulge == bulge && this.tint == tint
        }?.let { return it }
        LiquidGlass.setUniforms(shader, width, height, cornerPx, lightX, lightY, bulge,
            floatArrayOf(tint.red, tint.green, tint.blue, tint.alpha))
        val built = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        this.width = width; this.height = height; this.corner = cornerPx
        this.lightX = lightX; this.lightY = lightY; this.bulge = bulge; this.tint = tint
        effect = built
        return built
    }
}

/**
 * A Liquid Glass panel: the wallpaper slice under this element ([LocalFrostedBackdrop], the same
 * source [Modifier.frostedGlass] uses — Fold Duo has no cross-window blur, so this is "the
 * wallpaper behind the element"), refracted by `continuum/liquid_glass.agsl` into a lens with a
 * specular rim and inner shadow toward [rememberLiquidGlassLight], plus a fixed 6% white veil.
 * Clipped to [shape]; [cornerRadius] only feeds the shader's own edge falloff, an approximation
 * for shapes other than a rounded rect (Squircle/Teardrop/Circle still clip correctly in Compose,
 * they just get a slightly generic-feeling rim). [bulge] (0..1) is read inside the layer block —
 * a caller animating it (icon drag pickup) invalidates only this layer, never composition.
 *
 * Cost: the shader runs on this element's own small [android.graphics.RenderNode], not the
 * screen — an icon-sized crop, not a full-window pass — and [LiquidGlassEffectCache] skips
 * rebuilding the [RenderEffect] whenever nothing it depends on changed, so a still icon under a
 * still light costs one draw with no shader recompilation.
 */
@Composable
fun Modifier.liquidGlassPanel(
    shape: Shape,
    cornerRadius: Dp,
    bulge: () -> Float = { 0f },
    tint: Color = Color.Transparent,
    fallback: Color = Color.White.copy(alpha = .14f),
    backdrop: FrostedBackdrop? = LocalFrostedBackdrop.current,
): Modifier {
    val context = LocalContext.current.applicationContext
    val shader = remember { LiquidGlass.shared(context) }
    val cache = remember { LiquidGlassEffectCache() }
    // Výkon 2: shared across every panel — see LocalLiquidGlassLight's doc above.
    val light = LocalLiquidGlassLight.current
    val density = LocalDensity.current
    val cornerPx = with(density) { cornerRadius.toPx() }
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
        .graphicsLayer {
            val w = size.width
            val h = size.height
            renderEffect = if (shader == null || w <= 1f || h <= 1f) null else {
                val dir = light.value
                cache.effectFor(shader, w, h, cornerPx.coerceAtMost(minOf(w, h) / 2f), dir.x, dir.y, bulge().coerceIn(0f, 1f), tint)
            }
            clip = true
            this.shape = shape
        }
        .drawWithCache {
            val image = backdrop?.image
            onDrawBehind {
                if (image == null || backdrop.width <= 0f || backdrop.height <= 0f) {
                    drawRect(fallback)
                } else {
                    var x = windowX.floatValue
                    var y = windowY.floatValue
                    coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.let { x = it.x; y = it.y }
                    val placement = backdropPlacement(x, y, backdrop.originX, backdrop.originY,
                        backdrop.width, backdrop.height, image.width, image.height)
                    withTransform({
                        translate(placement.offsetX, placement.offsetY)
                        scale(placement.scaleX, placement.scaleY, Offset.Zero)
                    }) { drawImage(image) }
                }
            }
        }
}

/**
 * Výkon 3 "kreslení na inneru" (17. 9. noc): the rest-state Liquid Glass panel for `AppTile`,
 * `FolderTile` and the dock (LauncherScreen.kt) — every place [Modifier.liquidGlassPanel] used to
 * be attached with a fixed `bulge = 0f`, so it never needed a *live* [RenderEffect] layer, just
 * the same pixels the shader would have produced, redrawn as a plain bitmap until something in
 * [LiquidGlassCacheKey] actually changes. A device trace caught 100-300 ms frames during a plain
 * page swipe with the stall inside `nSyncAndDrawFrame` — sync+draw of the render tree — which is
 * exactly the cost of ~28 icons x 2 panes each carrying their OWN offscreen shader pass every time
 * their subtree draws, even though [LiquidGlassEffectCache] already stopped rebuilding the
 * `RenderEffect` object itself. Building the same pixels ONCE into an [ImageBitmap] (via a
 * *detached* `GraphicsLayer` — [LocalGraphicsContext], never added to the visible tree, so it
 * costs nothing when idle) and drawing that bitmap plainly the rest of the time turns almost every
 * frame of a swipe into the same kind of blit the icon's own baked `Image` already does.
 *
 * Chosen over "one glass pass per page" (draw a page-wide backing layer with a mask of every
 * occupied cell's shape): that option needs `continuum/liquid_glass.agsl` rewritten to walk an
 * array of cell rects per pixel (a multi-rect signed-distance loop) to keep each icon's own lens
 * silhouette, instead of one big rounded rect for the whole page — real AGSL surgery with no
 * device here to see whether the refraction still reads as "one lens per icon" afterward. The
 * cache route reuses this app's own established pattern instead ([WidgetSnapshotCache],
 * B43 "Výkon jako feature": capture a bitmap, keep it until the thing it depends on moves) and
 * fails safe: a build that never completes (shader missing, layer capture throws) just keeps
 * showing [fallback] — never a broken or stale-looking icon.
 *
 * The cache key folds in cell position ([LiquidGlassCacheKey.xBucket]/[yBucket], bucketed to
 * [LIQUID_GLASS_CACHE_BUCKET_DP]) even though this panel does not (yet) shift its sample by the
 * live wallpaper parallax offset the way [liquidGlassSampleOrigin] could — neither did the old
 * live [Modifier.liquidGlassPanel] path, so this is not a visual regression, just an honestly
 * named key: a couple of stray px between layout passes never rebuilds, an actual reposition
 * (grid reflow, rotation) does. [wallpaperRevision] reuses `LauncherBackgroundCache.revision`
 * directly — the same signal [FrostedBackdrop] itself rebuilds on — so a new wallpaper invalidates
 * every cell's cache without this file needing its own change counter.
 *
 * Never used for the drag ghost (LauncherScreen.kt's `drag-ghost` Box): that one instance needs a
 * *live* bulge (0..1, animated) and is never more than one at a time, so [Modifier.liquidGlassPanel]
 * (the original, per-frame `RenderEffect` layer) stays exactly as it was for it.
 */
@Composable
fun Modifier.liquidGlassPanelCached(
    shape: Shape,
    cornerRadius: Dp,
    tint: Color = Color.Transparent,
    fallback: Color = Color.White.copy(alpha = .14f),
    backdrop: FrostedBackdrop? = LocalFrostedBackdrop.current,
): Modifier {
    val context = LocalContext.current.applicationContext
    val shader = remember { LiquidGlass.shared(context) }
    val light = LocalLiquidGlassLight.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val cornerPx = with(density) { cornerRadius.toPx() }
    val graphicsContext = LocalGraphicsContext.current
    val layer = remember(graphicsContext) { graphicsContext.createGraphicsLayer() }
    DisposableEffect(graphicsContext, layer) { onDispose { graphicsContext.releaseGraphicsLayer(layer) } }
    val windowX = remember { mutableFloatStateOf(0f) }
    val windowY = remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val wallpaperRevision = LauncherBackgroundCache.revision.intValue

    // Null while there is nothing sensible to cache yet (no shader, no wallpaper crop, no
    // layout size) — the draw side falls back to a flat fill in exactly that case, same as the
    // live `Modifier.liquidGlassPanel` always has.
    val key = if (shader == null || backdrop?.image == null || backdrop.width <= 0f || backdrop.height <= 0f ||
        sizePx.width <= 1 || sizePx.height <= 1) null
        else liquidGlassCacheKeyFor(
            widthPx = sizePx.width.toFloat(), heightPx = sizePx.height.toFloat(), cornerPx = cornerPx,
            windowX = windowX.floatValue, windowY = windowY.floatValue,
            lightX = light.value.x, lightY = light.value.y,
            tintArgb = tint.toArgb(), wallpaperRevision = wallpaperRevision,
        )

    // Výkon 3: this is the ONLY place the actual shader ever runs for a cached panel — once per
    // distinct [key], not once per frame. LaunchedEffect's own key comparison (data class
    // equality) is exactly [LiquidGlassCacheKey]'s "needs rebuild" decision; a repeat of the same
    // key (still light, still position, same wallpaper) never re-enters this block at all.
    LaunchedEffect(key) {
        val s = shader ?: return@LaunchedEffect
        val bg = backdrop ?: return@LaunchedEffect
        key ?: return@LaunchedEffect
        val image = bg.image
        val x = windowX.floatValue
        val y = windowY.floatValue
        layer.record(density, layoutDirection, sizePx) {
            val placement = backdropPlacement(x, y, bg.originX, bg.originY, bg.width, bg.height, image.width, image.height)
            withTransform({
                translate(placement.offsetX, placement.offsetY)
                scale(placement.scaleX, placement.scaleY, Offset.Zero)
            }) { drawImage(image) }
        }
        LiquidGlass.setUniforms(s, sizePx.width.toFloat(), sizePx.height.toFloat(),
            cornerPx.coerceAtMost(minOf(sizePx.width, sizePx.height) / 2f),
            light.value.x, light.value.y, 0f, floatArrayOf(tint.red, tint.green, tint.blue, tint.alpha))
        layer.renderEffect = RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
        // A failed capture (shader/layer trouble) just leaves the previous bitmap (or the flat
        // fallback, on the very first attempt) in place instead of showing a broken frame.
        val snapshot = runCatching { layer.toImageBitmap() }.getOrNull()
        if (snapshot != null) bitmap = snapshot
    }

    return this
        .onGloballyPositioned { coords ->
            val position = coords.positionInWindow()
            windowX.floatValue = position.x
            windowY.floatValue = position.y
            sizePx = IntSize(coords.size.width, coords.size.height)
        }
        .drawBehind {
            val current = bitmap
            if (current == null || key == null) drawRect(fallback) else drawImage(current)
        }
}
