package cz.pflanzer.foldduo.systemfrost

/**
 * Pure geometry for the B15 continuity bridge (STATUS.md "Most kontinuity při swapu"): maps a
 * screenshot of one panel onto the other panel's matching pane. Same "pane identity" fill rule
 * the rest of the launcher already uses (`paneBounds` in FoldGeometry.kt, the cover/inner
 * framing in WallpaperFraming.kt) — centre-crop scale-to-fill, not a stretch, so a picture keeps
 * its own aspect: the cover (1248x1972) and the inner's half (1224x1848) are close but not
 * identical (0.633 vs 0.662), so filling edge to edge crops a sliver off the taller side rather
 * than distorting the image. No Android types here so it is a plain JVM unit.
 */
object BridgeFraming {
    /** [scale] to apply to the source, then translate by ([dx], [dy]) to center it over the destination. */
    data class Fit(val scale: Float, val dx: Float, val dy: Float)

    /**
     * Scale [srcW]x[srcH] to fill [dstW]x[dstH] edge-to-edge, centered: the larger of the two
     * axis ratios wins, so the source overflows (and gets cropped by the destination's own
     * bounds) on the other axis rather than leaving a gap.
     */
    fun scaleToFill(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Fit {
        require(srcW > 0f && srcH > 0f && dstW > 0f && dstH > 0f) { "Sizes must be positive." }
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val dx = (dstW - srcW * scale) / 2f
        val dy = (dstH - srcH * scale) / 2f
        return Fit(scale, dx, dy)
    }
}
