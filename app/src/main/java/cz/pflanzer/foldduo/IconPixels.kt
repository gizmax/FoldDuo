package cz.pflanzer.foldduo

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An opaque backing found under an icon's glyph: its colour, the share of the sampled ring it covered, the inset
 * (share of the buffer side) of the ring that found it and whether that was the inscribed circle ring.
 */
internal data class Backing(val color: Int, val coverage: Float, val inset: Float = IconPixels.ADAPTIVE_SAFE_INSET,
    val circle: Boolean = false)

/** Outcome of [IconPixels.stripBacking]. */
internal sealed class StrippedIcon {
    /** No opaque backing under the safe-zone ring: draw the source as it is. */
    object Untouched : StrippedIcon()
    /** Backing keyed out and the glyph re-centred; [pixels] is a w×h ARGB buffer. */
    class Glyph(val pixels: IntArray, val backing: Backing) : StrippedIcon()
    /** A backing was found but too little glyph remained: draw the source whole and tint the tile with [backing]. */
    class Tint(val backing: Backing) : StrippedIcon()
}

/**
 * Per-pixel work for the glass icon styles on plain ARGB `IntArray` buffers (no Android types), so it
 * runs in JVM tests. Pixels are non-premultiplied ARGB as `Bitmap.getPixels` returns them.
 */
internal object IconPixels {
    /** Adaptive foreground layers: the 66 dp safe zone of the 108 dp layer, so 17 % in from each edge. */
    const val ADAPTIVE_SAFE_INSET = .17f
    /** Legacy icons have no safe zone; sample just inside their edge. */
    const val LEGACY_SAFE_INSET = .08f
    /**
     * Backings that stop short of the safe zone — Maps' white 54 dp square sits at 25 % of the layer — are looked
     * for with the rectangle ring at these deeper insets too. Rectangle only, and its four corners must be backing
     * as well: a solid glyph would then have to be a 66 dp-plus square with square corners, which the safe zone
     * does not allow, whereas a 66 dp round glyph covers 90 % of the 27 % ring's sides but never its corners.
     */
    val INNER_RING_INSETS = floatArrayOf(.22f, .27f)
    /** Euclidean RGB distance a pixel may sit from the ring median and still count as backing. */
    const val BACKING_TOLERANCE = 28
    /** Share of the ring that must be opaque backing colour. */
    const val RING_BACKING_SHARE = .85f
    /** The stripped glyph is scaled so its longer side spans this share of the tile. */
    const val GLYPH_FILL = .64f
    /** Below this share of the tile the glyph is too small to show alone. */
    const val MIN_GLYPH_SHARE = .25f
    /**
     * At least this share of the tile must be opaque pixels that are not the backing colour, or the icon was a flat
     * colour with anti-aliasing noise. Counted on pixels, not on how much was removed: Play's full white layer
     * loses 88 % of its pixels to the key and its triangle is still a valid glyph.
     */
    const val MIN_COLOURED_SHARE = .01f
    const val GLYPH_ALPHA_THRESHOLD = 24
    private const val OPAQUE_ALPHA = 240

    /**
     * Detects, keys out and re-centres in one go: [StrippedIcon.Untouched] when no backing sits under any ring,
     * [StrippedIcon.Tint] when less than [MIN_COLOURED_SHARE] of the tile is not backing colour or removing the
     * backing left a glyph under [MIN_GLYPH_SHARE] of the tile, otherwise a [StrippedIcon.Glyph] filling
     * [GLYPH_FILL] of the tile.
     */
    fun stripBacking(pixels: IntArray, w: Int, h: Int, safeInset: Float): StrippedIcon {
        val backing = detectOpaqueBacking(pixels, w, h, safeInset) ?: return StrippedIcon.Untouched
        val tol2 = BACKING_TOLERANCE * BACKING_TOLERANCE
        var coloured = 0
        for (p in pixels) if ((p ushr 24) >= GLYPH_ALPHA_THRESHOLD && rgbDistanceSquared(p, backing.color) > tol2) coloured++
        if (coloured < MIN_COLOURED_SHARE * w * h) return StrippedIcon.Tint(backing)
        val work = pixels.copyOf()
        keyOutBacking(work, w, h, backing.color, BACKING_TOLERANCE, backing.inset, backing.circle)
        val bounds = glyphBounds(work, w, h) ?: return StrippedIcon.Tint(backing)
        val longest = max(bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1)
        if (longest < MIN_GLYPH_SHARE * min(w, h)) return StrippedIcon.Tint(backing)
        return StrippedIcon.Glyph(centreAndScale(work, w, h, bounds, GLYPH_FILL), backing)
    }

    /**
     * Samples a two-pixel ring just inside the safe zone — first the inset rectangle, then the inscribed circle
     * (round backings miss the rectangle's corners), then the rectangle at each of [INNER_RING_INSETS] for backings
     * that stop short of the safe zone (their corners must be backing too) — and returns the ring's median colour
     * when at least [RING_BACKING_SHARE] of the ring is opaque and within [BACKING_TOLERANCE] of that median.
     */
    fun detectOpaqueBacking(pixels: IntArray, w: Int, h: Int, safeInset: Float): Backing? {
        val tol2 = BACKING_TOLERANCE * BACKING_TOLERANCE
        val rings = listOf(Triple(safeInset, false, rectRing(w, h, safeInset)), Triple(safeInset, true, circleRing(w, h, safeInset))) +
            INNER_RING_INSETS.filter { it > safeInset }.map { Triple(it, false, rectRing(w, h, it)) }
        for ((inset, circle, ring) in rings) {
            if (ring.isEmpty()) continue
            val opaque = ring.filter { (pixels[it] ushr 24) >= OPAQUE_ALPHA }
            if (opaque.size < RING_BACKING_SHARE * ring.size) continue
            val median = medianColor(opaque.map { pixels[it] })
            val matching = opaque.count { rgbDistanceSquared(pixels[it], median) <= tol2 }
            val share = matching.toFloat() / ring.size
            if (share < RING_BACKING_SHARE) continue
            if (inset > safeInset && rectCorners(w, h, inset).any {
                    (pixels[it] ushr 24) < OPAQUE_ALPHA || rgbDistanceSquared(pixels[it], median) > tol2 }) continue
            return Backing(median or (0xFF shl 24), share, inset, circle)
        }
        return null
    }

    /**
     * Flood-fills (4-connected) from the buffer border and the rectangle ring at [ringInset] — the ring that found
     * the backing — plus the circle ring there when [circleRing] found it, through pixels within [tolerance] of
     * [color] (and through already transparent ones), clearing them, so the whole backing goes whether it is larger
     * or smaller than the ring while enclosed same-coloured glyph parts survive. Only the finding ring seeds: the
     * circle ring or a deeper rectangle could land on glyph detail in the backing colour (Calendar's white digits
     * inside its blue square). Pixels left next to a cleared opaque pixel are feathered to half alpha. Returns how
     * many pixels above [GLYPH_ALPHA_THRESHOLD] were cleared.
     */
    fun keyOutBacking(pixels: IntArray, w: Int, h: Int, color: Int, tolerance: Int, ringInset: Float, circleRing: Boolean = false): Int {
        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        val tol2 = tolerance * tolerance
        fun matches(p: Int): Boolean = (p ushr 24) < GLYPH_ALPHA_THRESHOLD || rgbDistanceSquared(p, color) <= tol2
        fun push(j: Int) {
            if (visited[j] || !matches(pixels[j])) return
            visited[j] = true; stack[top++] = j
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        for (seed in rectRing(w, h, ringInset)) push(seed)
        if (circleRing) for (seed in circleRing(w, h, ringInset)) push(seed)
        val cut = BooleanArray(w * h)
        var removed = 0
        while (top > 0) {
            val i = stack[--top]
            if ((pixels[i] ushr 24) >= GLYPH_ALPHA_THRESHOLD) { removed++; cut[i] = true }
            pixels[i] = 0
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        // 1 px feather along the cut edge.
        for (i in pixels.indices) {
            val a = pixels[i] ushr 24
            if (a == 0 || cut[i]) continue
            val x = i % w; val y = i / w
            val edge = (x > 0 && cut[i - 1]) || (x < w - 1 && cut[i + 1]) || (y > 0 && cut[i - w]) || (y < h - 1 && cut[i + w])
            if (edge) pixels[i] = ((a / 2) shl 24) or (pixels[i] and 0xFFFFFF)
        }
        return removed
    }

    /** Inclusive [left, top, right, bottom] of pixels whose alpha is at least [alphaThreshold]; null when none. */
    fun glyphBounds(pixels: IntArray, w: Int, h: Int, alphaThreshold: Int = GLYPH_ALPHA_THRESHOLD): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (i in pixels.indices) {
            if ((pixels[i] ushr 24) < alphaThreshold) continue
            val x = i % w; val y = i / w
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r, b)
    }

    /**
     * Returns a new w×h buffer with the [bounds] region of [pixels] scaled (aspect kept, bilinear on premultiplied
     * colour) so its longer side spans [fill] of the shorter tile side, centred in the tile.
     */
    fun centreAndScale(pixels: IntArray, w: Int, h: Int, bounds: IntArray, fill: Float = GLYPH_FILL): IntArray {
        val out = IntArray(w * h)
        val bw = bounds[2] - bounds[0] + 1; val bh = bounds[3] - bounds[1] + 1
        val scale = fill * min(w, h) / max(bw, bh)
        val dw = (bw * scale).roundToInt().coerceIn(1, w); val dh = (bh * scale).roundToInt().coerceIn(1, h)
        val dl = (w - dw) / 2; val dt = (h - dh) / 2
        for (dy in 0 until dh) {
            val sy = bounds[1] + (dy + .5f) / scale - .5f
            for (dx in 0 until dw) {
                val sx = bounds[0] + (dx + .5f) / scale - .5f
                out[(dt + dy) * w + dl + dx] = sampleBilinear(pixels, w, h, sx, sy)
            }
        }
        return out
    }

    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, sx: Float, sy: Float): Int {
        val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
        val fx = sx - x0; val fy = sy - y0
        var a = 0f; var r = 0f; var g = 0f; var b = 0f
        fun tap(x: Int, y: Int, weight: Float) {
            if (weight <= 0f || x < 0 || y < 0 || x >= w || y >= h) return
            val p = pixels[y * w + x]
            val pa = (p ushr 24) * weight
            if (pa == 0f) return
            a += pa; r += ((p shr 16) and 0xFF) * pa; g += ((p shr 8) and 0xFF) * pa; b += (p and 0xFF) * pa
        }
        tap(x0, y0, (1 - fx) * (1 - fy)); tap(x0 + 1, y0, fx * (1 - fy))
        tap(x0, y0 + 1, (1 - fx) * fy); tap(x0 + 1, y0 + 1, fx * fy)
        if (a < .5f) return 0
        return (a.roundToInt().coerceIn(0, 255) shl 24) or ((r / a).roundToInt().coerceIn(0, 255) shl 16) or
            ((g / a).roundToInt().coerceIn(0, 255) shl 8) or (b / a).roundToInt().coerceIn(0, 255)
    }

    /** Indices of a 2 px band along the inside edge of the inset rectangle. */
    internal fun rectRing(w: Int, h: Int, inset: Float): IntArray {
        val l = (w * inset).roundToInt(); val t = (h * inset).roundToInt()
        val r = w - 1 - l; val b = h - 1 - t
        if (r - l < 4 || b - t < 4) return IntArray(0)
        val out = LinkedHashSet<Int>()
        for (d in 0..1) {
            for (x in l + d..r - d) { out += (t + d) * w + x; out += (b - d) * w + x }
            for (y in t + d..b - d) { out += y * w + l + d; out += y * w + r - d }
        }
        return out.toIntArray()
    }

    /** The four corner pixels of the inset rectangle (its outer band). */
    internal fun rectCorners(w: Int, h: Int, inset: Float): IntArray {
        val l = (w * inset).roundToInt(); val t = (h * inset).roundToInt()
        val r = w - 1 - l; val b = h - 1 - t
        return intArrayOf(t * w + l, t * w + r, b * w + l, b * w + r)
    }

    /** Indices of a 2 px band along the inside of the circle inscribed in the inset rectangle. */
    internal fun circleRing(w: Int, h: Int, inset: Float): IntArray {
        val l = (w * inset).roundToInt(); val t = (h * inset).roundToInt()
        val radius = min(w - 1 - 2 * l, h - 1 - 2 * t) / 2f - 1.5f
        if (radius < 3f) return IntArray(0)
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val out = LinkedHashSet<Int>()
        for (d in 0..1) {
            val rr = radius - d
            val n = ceil(2 * PI * rr).toInt()
            for (i in 0 until n) {
                val angle = 2 * PI * i / n
                val x = (cx + rr * cos(angle)).roundToInt().coerceIn(0, w - 1)
                val y = (cy + rr * sin(angle)).roundToInt().coerceIn(0, h - 1)
                out += y * w + x
            }
        }
        return out.toIntArray()
    }

    private fun medianColor(colors: List<Int>): Int {
        fun median(shift: Int): Int = colors.map { (it shr shift) and 0xFF }.sorted()[colors.size / 2]
        return (median(16) shl 16) or (median(8) shl 8) or median(0)
    }

    private fun rgbDistanceSquared(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }
}
