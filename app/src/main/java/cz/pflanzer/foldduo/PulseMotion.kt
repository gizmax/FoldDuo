package cz.pflanzer.foldduo

/*
 * IDEAS.md B50 "Tapeta dýchá s oznámením": pure Kotlin mirror of continuum/res/raw/edge_pulse.agsl's
 * progress -> spread/opacity maths (SheetBendMotion.kt/LiquidGlassMotion.kt's own pattern), so the
 * curve is covered by a JVM test without an AGSL compiler. Nothing here touches Android; the glue
 * that turns a wall-clock pulse timestamp into a live `progress` and feeds [EdgePulse.setUniforms]
 * lives in DuneWallpaper.kt (Compose `DuneWallpaper()`) and `DuneWallpaperService.DuneEngine`.
 */
object PulseMotion {
    /** Total pulse length end to end (B50 task: "total 900 ms, ease-out"). */
    const val DURATION_MS = 900L

    /** Progress fraction at which the glow is at its widest/brightest, before it starts fading. */
    const val PEAK_PROGRESS = 0.35f

    /** How far inward the band grows at its widest, as a fraction of the surface's height. */
    const val MAX_SPREAD_FRACTION = 0.18f

    /** The shader's opacity cap (B50 task: "max 35% opacity"). */
    const val MAX_OPACITY = 0.35f

    /**
     * [atMs] (the pulse's own timestamp) turned into 0..1 progress at [nowMs]: `>= 1` once the
     * pulse is fully played out, so a caller can treat that as "nothing to draw" instead of
     * evaluating a permanently no-op shader (both call sites do exactly that).
     */
    fun progressAt(atMs: Long, nowMs: Long): Float =
        ((nowMs - atMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)

    /** The ease-out rise shared by [spreadFraction] and [opacity]'s first half: 0 at progress 0, 1 at [PEAK_PROGRESS], `1 - (1-t)^2` in between. */
    private fun riseAt(progress: Float): Float {
        val t = (progress / PEAK_PROGRESS).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    /**
     * How far the band has grown inward at [progress], as a fraction of the surface's height:
     * rises with [riseAt] to [MAX_SPREAD_FRACTION] by [PEAK_PROGRESS], then holds there — the
     * glow stops growing and, past that point, only [opacity] keeps changing (it fades out).
     */
    fun spreadFraction(progress: Float): Float = riseAt(progress.coerceIn(0f, 1f)) * MAX_SPREAD_FRACTION

    /**
     * Opacity envelope at [progress]: rises with [riseAt] to [MAX_OPACITY] by [PEAK_PROGRESS],
     * then eases out (a mirrored `1 - t^2`) to 0 by progress 1.0.
     */
    fun opacity(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val envelope = if (p <= PEAK_PROGRESS) riseAt(p) else {
            val t = ((p - PEAK_PROGRESS) / (1f - PEAK_PROGRESS)).coerceIn(0f, 1f)
            1f - t * t
        }
        return envelope * MAX_OPACITY
    }
}
