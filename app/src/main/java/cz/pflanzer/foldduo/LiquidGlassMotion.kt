package cz.pflanzer.foldduo

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.round

/*
 * Pure arithmetic behind Liquid Glass (IDEAS.md B41): the lens height-field and refraction
 * offset continuum/liquid_glass.agsl computes per pixel, mirrored here so it runs as a JVM test,
 * plus the light direction from gravity (low-passed the same way pose/ParallaxModel.kt low-passes
 * the wallpaper parallax) and the wallpaper sample origin for a panel under the live B27 parallax
 * offset. No Android types.
 */

/** Rest-state refraction offset in px, matching the shader's `REST_OFFSET_PX`. */
const val LIQUID_GLASS_REST_OFFSET_PX = 6f

/**
 * Height field at [edgeDistancePx] (distance inward from the lens edge — 0 right at the
 * boundary, growing toward the centre): 1 at the edge, smoothstepped down to 0 over [edgeBandPx].
 * Depends only on distance to the nearest edge, so it is the same on every side of the shape —
 * symmetric by construction, not by a separate left/right check.
 */
fun liquidGlassLensHeight(edgeDistancePx: Float, edgeBandPx: Float): Float {
    if (edgeBandPx <= 0f) return if (edgeDistancePx <= 0f) 1f else 0f
    val t = (edgeDistancePx / edgeBandPx).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t) // smoothstep(0, band, edgeDistance)
    return 1f - smooth
}

/**
 * Refraction offset in px at [edgeDistancePx] from the lens edge: [restOffsetPx] at rest
 * ([bulge] 0), doubled at full bulge (1) — `restOffsetPx * (1 + bulge)` — zero at the centre
 * ([liquidGlassLensHeight] is 0 there regardless of bulge), maximum right at the edge.
 */
fun liquidGlassOffsetPx(edgeDistancePx: Float, edgeBandPx: Float, restOffsetPx: Float, bulge: Float): Float =
    liquidGlassLensHeight(edgeDistancePx, edgeBandPx) * restOffsetPx * (1f + bulge.coerceIn(0f, 1f))

/**
 * Specular/inner-shadow light direction: a low-passed, normalised 2D vector from gravity
 * ([update]'s `gx`/`gy`, [pose.ParallaxModel]'s device-frame convention). Starts at — and falls
 * back to whenever the smoothed gravity is too small to trust ([MIN_MAGNITUDE], e.g. the phone
 * flat on a table) — a fixed top-left direction, so a device without gravity yet (or with
 * reduced motion, which callers simply never [update]) still gets a plausible highlight.
 */
class LiquidGlassLight {
    private var lowPassX = DEFAULT_X
    private var lowPassY = DEFAULT_Y
    private var lastMs = Long.MIN_VALUE
    private var started = false

    var dirX = DEFAULT_X
        private set
    var dirY = DEFAULT_Y
        private set

    /** One gravity sample at [nowMs] (`gx`/`gy`, m/s^2, device frame). */
    fun update(nowMs: Long, gx: Float, gy: Float) {
        if (!started) {
            started = true
            lowPassX = gx; lowPassY = gy
        } else {
            val dtMs = (nowMs - lastMs).coerceIn(0L, MAX_DT_MS)
            val alpha = 1f - exp(-dtMs / TAU_MS)
            lowPassX += (gx - lowPassX) * alpha
            lowPassY += (gy - lowPassY) * alpha
        }
        lastMs = nowMs
        val len = hypot(lowPassX, lowPassY)
        if (len < MIN_MAGNITUDE) {
            dirX = DEFAULT_X; dirY = DEFAULT_Y
        } else {
            dirX = lowPassX / len; dirY = lowPassY / len
        }
    }

    /** Back to the fixed default direction until the next [update]. */
    fun reset() {
        started = false
        lowPassX = DEFAULT_X; lowPassY = DEFAULT_Y
        dirX = DEFAULT_X; dirY = DEFAULT_Y
    }

    companion object {
        /** Low-pass time constant on the raw gravity sample (faster than the wallpaper parallax's
         * 250 ms — a highlight lagging the tilt reads as "wrong", not "smooth"). */
        const val TAU_MS = 150f
        private const val MAX_DT_MS = 1_000L
        /** Below this many m/s^2 of smoothed gravity, the direction is too noisy to trust. */
        const val MIN_MAGNITUDE = 0.05f
        /** Static top-left default (reduced motion, no gravity yet, or gravity near zero). */
        const val DEFAULT_X = -0.7071f
        const val DEFAULT_Y = -0.7071f

        /**
         * [dirX]/[dirY] snapped to the nearest 1/[BUCKETS], so a caller that only republishes a
         * changed value (Compose state) skips sensor jitter that never visibly moves the
         * highlight — the render-effect cache then keeps hitting while the phone sits still,
         * instead of rebuilding every frame the gravity loop ticks.
         */
        fun quantize(value: Float, buckets: Int = 32): Float =
            if (buckets <= 0) value else round(value * buckets) / buckets
    }
}

/**
 * Where the wallpaper crop for a Liquid Glass panel should be sampled from: the panel's own
 * window position, shifted opposite the live B27 parallax offset (px, screen space) — as the
 * wallpaper layer slides by ([parallaxDxPx], [parallaxDyPx]), the patch of it sitting behind a
 * screen-fixed panel moves the other way in the wallpaper's own coordinate space. Zero offset is
 * the identity ([backdropPlacement]/[backdropCrop]'s own origin).
 */
fun liquidGlassSampleOrigin(cellLeft: Float, cellTop: Float, parallaxDxPx: Float, parallaxDyPx: Float): Pair<Float, Float> =
    (cellLeft - parallaxDxPx) to (cellTop - parallaxDyPx)

/**
 * [backdropCrop] for a [cellWidth]×[cellHeight] panel at window ([cellLeft], [cellTop]), under
 * the live parallax offset ([liquidGlassSampleOrigin]) — the crop a Liquid Glass panel draws as
 * its own background, recomputed only when the panel's layout position or the parallax offset
 * changes (never per frame on a still phone: both are cheap Compose state reads a caller can
 * gate the same way [FrostedBackdrop]'s placement is).
 */
fun liquidGlassCellCrop(
    cellLeft: Float, cellTop: Float, cellWidth: Float, cellHeight: Float,
    parallaxDxPx: Float, parallaxDyPx: Float,
    bgLeft: Float, bgTop: Float, bgWidth: Float, bgHeight: Float,
    backdropWidth: Int, backdropHeight: Int,
): CropRect {
    val (originX, originY) = liquidGlassSampleOrigin(cellLeft, cellTop, parallaxDxPx, parallaxDyPx)
    return backdropCrop(originX, originY, cellWidth, cellHeight, bgLeft, bgTop, bgWidth, bgHeight, backdropWidth, backdropHeight)
}

/*
 * Výkon 3 "kreslení na inneru" (17. 9. noc): one shader layer PER ICON (28 tiles x 2 panes, each
 * its own RuntimeShader RenderEffect graphicsLayer) is what a device trace caught costing
 * sync+draw time during a plain page swipe — every one of those layers needs its own offscreen
 * render-to-texture pass every time it (or anything forcing a redraw of its subtree) draws, even
 * though [LiquidGlassEffectCache] already skipped rebuilding the RenderEffect object itself. A
 * still icon under a still light does not need to re-run the shader every frame; it needs to run
 * it ONCE and reuse the resulting pixels. [LiquidGlassCacheKey] is that "once" decision: two keys
 * that compare equal mean the cached bitmap (built by [Modifier.liquidGlassPanelCached],
 * LiquidGlassBacking.kt, via a detached `GraphicsLayer.toImageBitmap()` snapshot) is still good
 * and a caller should just draw it — no shader, no extra layer, a plain bitmap blit exactly like
 * the icon's own baked `Image`. Cell position and the (currently unused-for-sampling, kept for
 * this key's own future correctness) parallax offset are folded into one bucketed pair via
 * [liquidGlassPxBucket] rather than compared exactly, so the couple of stray px a layout pass can
 * report between otherwise-identical frames never forces a rebuild — only an actual move of at
 * least [LIQUID_GLASS_CACHE_BUCKET_DP] does. The light direction reuses [LiquidGlassLight]'s own
 * 1/32 quantization (already the unit callers hold the direction in), and [wallpaperRevision] is
 * whatever the caller bumps when the underlying photo/crop source itself changes (this app already
 * has one: `LauncherBackgroundCache.revision`).
 */

/** Bucket size (dp-equivalent px, since callers pass already-scaled px) for cache-key
 * quantization of a Liquid Glass cell's position: changes smaller than this never invalidate a
 * cached bitmap, per the task's "quantised parallax offset (>= 2 dp change)". */
const val LIQUID_GLASS_CACHE_BUCKET_DP = 2f

/** How many buckets [LiquidGlassLight.quantize] already snaps a light direction component to — reused
 * here so the cache key's own light bucket matches exactly what a caller reads from that state. */
private const val LIGHT_BUCKETS = 32

/** [valuePx] snapped to the nearest multiple of [bucketPx] (a plain round-to-bucket, not a floor),
 * so a value wobbling by less than half a bucket either way always lands on the same integer. */
fun liquidGlassPxBucket(valuePx: Float, bucketPx: Float): Int =
    if (bucketPx <= 0f) Math.round(valuePx) else Math.round(valuePx / bucketPx)

/**
 * One Liquid Glass cell's cache identity: equal keys mean a previously built bitmap is still
 * valid and can be reused as-is. Every field is already an integer/bucketed value, so plain data
 * class equality *is* the "needs rebuild" decision — a caller just compares the new key against
 * the one its cached bitmap was built for.
 */
data class LiquidGlassCacheKey(
    val widthPx: Int,
    val heightPx: Int,
    val cornerPx: Int,
    val xBucket: Int,
    val yBucket: Int,
    val lightXBucket: Int,
    val lightYBucket: Int,
    val tintArgb: Int,
    val wallpaperRevision: Int,
)

/**
 * Builds [LiquidGlassCacheKey] for a cell at window ([windowX], [windowY]) of [widthPx]x[heightPx]
 * with corner radius [cornerPx], under light direction ([lightX], [lightY]) and [tintArgb], at
 * wallpaper [wallpaperRevision]. [positionBucketPx] is the quantization granularity ([liquidGlassPxBucket]),
 * [LIQUID_GLASS_CACHE_BUCKET_DP] px by default.
 */
fun liquidGlassCacheKeyFor(
    widthPx: Float, heightPx: Float, cornerPx: Float,
    windowX: Float, windowY: Float,
    lightX: Float, lightY: Float,
    tintArgb: Int, wallpaperRevision: Int,
    positionBucketPx: Float = LIQUID_GLASS_CACHE_BUCKET_DP,
): LiquidGlassCacheKey = LiquidGlassCacheKey(
    widthPx = Math.round(widthPx), heightPx = Math.round(heightPx), cornerPx = Math.round(cornerPx),
    xBucket = liquidGlassPxBucket(windowX, positionBucketPx), yBucket = liquidGlassPxBucket(windowY, positionBucketPx),
    lightXBucket = Math.round(lightX * LIGHT_BUCKETS), lightYBucket = Math.round(lightY * LIGHT_BUCKETS),
    tintArgb = tintArgb, wallpaperRevision = wallpaperRevision,
)
