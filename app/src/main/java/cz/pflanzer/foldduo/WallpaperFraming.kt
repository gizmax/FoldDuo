package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.pose.Panel

/*
 * Pane-identity wallpaper framing (PLAN.md fact 1, IDEAS.md B2). One picture for both panels:
 * the inner panel shows a centre crop of it, and the cover shows the right half of that same
 * crop, so what is on the cover is exactly what the right pane shows after unfolding and the
 * left pane reveals the rest. Pure arithmetic; no Android types so the JVM tests can run it.
 */

/** An axis-aligned crop in source pixels. */
data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val aspect: Float get() = width / height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

object WallpaperFraming {
    /**
     * The cover crop may reach beyond the right half by this fraction of the half's width (its
     * aspect is a touch wider than half the inner in dp: 555×830 vs 544×821). Past this the crop
     * is fitted by width instead and loses a little height.
     */
    const val COVER_OVERSCAN_MAX = 0.02f

    /** Centre crop of an [imageW]×[imageH] picture to the inner panel's aspect. */
    fun innerCrop(imageW: Float, imageH: Float, innerW: Float, innerH: Float): CropRect {
        require(imageW > 0f && imageH > 0f && innerW > 0f && innerH > 0f) { "Sizes must be positive." }
        val target = innerW / innerH
        val w: Float
        val h: Float
        if (imageW / imageH > target) { h = imageH; w = h * target } else { w = imageW; h = w / target }
        return CropRect((imageW - w) / 2f, (imageH - h) / 2f, w, h)
    }

    /**
     * The right half of [inner], centre-fitted to the cover aspect: by height while the width
     * that needs stays within [COVER_OVERSCAN_MAX] of the half's width (overscan spills evenly
     * onto both sides), else by width. Shifted, never resized, to stay inside the image.
     */
    fun coverCrop(inner: CropRect, coverW: Float, coverH: Float, imageW: Float, imageH: Float): CropRect {
        require(coverW > 0f && coverH > 0f) { "Sizes must be positive." }
        val halfW = inner.width / 2f
        val halfLeft = inner.left + halfW
        val target = coverW / coverH
        val byHeightW = inner.height * target
        val w: Float
        val h: Float
        if (byHeightW <= halfW * (1f + COVER_OVERSCAN_MAX)) { w = byHeightW; h = inner.height }
        else { w = halfW; h = halfW / target }
        val left = (halfLeft + (halfW - w) / 2f).coerceIn(0f, (imageW - w).coerceAtLeast(0f))
        val top = (inner.top + (inner.height - h) / 2f).coerceIn(0f, (imageH - h).coerceAtLeast(0f))
        return CropRect(left, top, w, h)
    }

    /**
     * The crop of an [imageW]×[imageH] picture shown on [panel]. [innerW]×[innerH] and
     * [coverW]×[coverH] are the two surfaces in px; [Panel.Unknown] gets the inner crop.
     */
    fun wallpaperCrop(
        imageW: Float, imageH: Float,
        innerW: Float, innerH: Float,
        coverW: Float, coverH: Float,
        panel: Panel,
    ): CropRect {
        val inner = innerCrop(imageW, imageH, innerW, innerH)
        return if (panel == Panel.Cover) coverCrop(inner, coverW, coverH, imageW, imageH) else inner
    }

    /**
     * [wallpaperCrop] for a surface of [surfaceW]×[surfaceH] on [panel], the other panel's own
     * measured size given as [otherPanelWidthPx]×[otherPanelHeightPx] (its natural-orientation
     * physical size, e.g. a `DeviceProfile`'s `inner`/`cover` — never assumed: this used to
     * default to the Fold 8's `Panels.INNER_LONG`/`INNER_SHORT`/`COVER_SHORT`/`COVER_LONG`
     * constants, which made every wallpaper crop on a different device wrong). When [panel] is
     * cover, [surfaceW]×[surfaceH] is the cover's own size and [otherPanelWidthPx]/[otherPanelHeightPx]
     * is the inner's; otherwise [surfaceW]×[surfaceH] is the inner's own size and
     * [otherPanelWidthPx]/[otherPanelHeightPx] is the cover's.
     */
    fun surfaceCrop(
        imageW: Float, imageH: Float,
        surfaceW: Float, surfaceH: Float,
        panel: Panel,
        otherPanelWidthPx: Float, otherPanelHeightPx: Float,
    ): CropRect =
        when (panel) {
            Panel.Cover -> wallpaperCrop(imageW, imageH, otherPanelWidthPx, otherPanelHeightPx, surfaceW, surfaceH, panel)
            else -> wallpaperCrop(imageW, imageH, surfaceW, surfaceH, otherPanelWidthPx, otherPanelHeightPx, panel)
        }
}
