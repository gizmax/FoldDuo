package cz.pflanzer.foldduo

/*
 * Pure arithmetic behind the frosted glass cards (FrostedBackdrop.kt): the box blur that
 * softens the small backdrop copy of the wallpaper and the mapping from a card's window bounds
 * to the backdrop pixels under it. No Android types, so the JVM tests run it as is.
 */

/** The backdrop is the launcher background at 1/[FROST_DOWNSCALE] of the surface (÷4 render, ÷2 filtered resample). */
const val FROST_DOWNSCALE = 8

/** Passes of the 3-tap box blur over the small backdrop; two passes approximate a Gaussian. */
const val FROST_BLUR_PASSES = 2

/**
 * A separable 3-tap box blur (kernel 1 1 1 / 3, horizontal then vertical) over ARGB [pixels]
 * of [width]×[height], in place, repeated [passes] times. Edges clamp: the missing neighbour
 * repeats the edge pixel, so borders keep their colour instead of darkening. Every channel,
 * alpha included, is averaged the same way, so a constant alpha survives unchanged and a
 * transparent region fades into its surroundings rather than bleeding colour. Returns [pixels].
 */
fun boxBlur3(pixels: IntArray, width: Int, height: Int, passes: Int = 1): IntArray {
    require(width >= 0 && height >= 0 && pixels.size >= width * height) { "Pixel buffer smaller than $width x $height." }
    if (width == 0 || height == 0 || passes <= 0) return pixels
    val scratch = IntArray(width * height)
    repeat(passes) {
        // Horizontal: pixels -> scratch.
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = pixels[row + if (x > 0) x - 1 else 0]
                val centre = pixels[row + x]
                val right = pixels[row + if (x < width - 1) x + 1 else x]
                scratch[row + x] = average3(left, centre, right)
            }
        }
        // Vertical: scratch -> pixels.
        for (y in 0 until height) {
            val above = (if (y > 0) y - 1 else 0) * width
            val row = y * width
            val below = (if (y < height - 1) y + 1 else y) * width
            for (x in 0 until width) pixels[row + x] = average3(scratch[above + x], scratch[row + x], scratch[below + x])
        }
    }
    return pixels
}

/** Per-channel mean of three ARGB values, rounded to nearest. */
private fun average3(a: Int, b: Int, c: Int): Int {
    fun channel(shift: Int): Int = (((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) + ((c ushr shift) and 0xFF) + 1) / 3
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * Where the whole backdrop lands in a card's local space: backdrop pixel (x, y) draws at
 * (x * [scaleX] + [offsetX], y * [scaleY] + [offsetY]). Drawing the full backdrop through this
 * transform (clipped to the card) puts the exact slice of wallpaper under the card, with no
 * rounding to backdrop pixels, so a card sliding across the screen samples smoothly.
 */
data class BackdropPlacement(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float) {
    /** The backdrop x under the card's local [x]. */
    fun sourceX(x: Float): Float = (x - offsetX) / scaleX
    /** The backdrop y under the card's local [y]. */
    fun sourceY(y: Float): Float = (y - offsetY) / scaleY
}

/**
 * Placement of a [backdropWidth]×[backdropHeight] backdrop under a card whose top-left corner
 * is at window ([cardLeft], [cardTop]), where the background it was made from covers the window
 * rectangle at ([bgLeft], [bgTop]) of [bgWidth]×[bgHeight] px. Sizes must be positive.
 */
fun backdropPlacement(
    cardLeft: Float, cardTop: Float,
    bgLeft: Float, bgTop: Float, bgWidth: Float, bgHeight: Float,
    backdropWidth: Int, backdropHeight: Int,
): BackdropPlacement {
    require(bgWidth > 0f && bgHeight > 0f && backdropWidth > 0 && backdropHeight > 0) { "Sizes must be positive." }
    return BackdropPlacement(bgWidth / backdropWidth, bgHeight / backdropHeight, bgLeft - cardLeft, bgTop - cardTop)
}

/**
 * The backdrop pixels under a [cardWidth]×[cardHeight] card at window ([cardLeft], [cardTop]),
 * clamped to the backdrop; a card entirely outside the background gives an empty crop on its
 * nearest edge. The same numbers [backdropPlacement] draws with, in crop form for callers that
 * copy a slice instead.
 */
fun backdropCrop(
    cardLeft: Float, cardTop: Float, cardWidth: Float, cardHeight: Float,
    bgLeft: Float, bgTop: Float, bgWidth: Float, bgHeight: Float,
    backdropWidth: Int, backdropHeight: Int,
): CropRect {
    val placement = backdropPlacement(cardLeft, cardTop, bgLeft, bgTop, bgWidth, bgHeight, backdropWidth, backdropHeight)
    val left = placement.sourceX(0f).coerceIn(0f, backdropWidth.toFloat())
    val top = placement.sourceY(0f).coerceIn(0f, backdropHeight.toFloat())
    val right = placement.sourceX(cardWidth.coerceAtLeast(0f)).coerceIn(left, backdropWidth.toFloat())
    val bottom = placement.sourceY(cardHeight.coerceAtLeast(0f)).coerceIn(top, backdropHeight.toFloat())
    return CropRect(left, top, right - left, bottom - top)
}
