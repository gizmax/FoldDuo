package cz.pflanzer.foldduo.island

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure motion/colour rules for the "Apple Music karta a morph" redesign (17. 9. noc, Tom's device
 * feedback: "udělej lepší animaci, maximalizovanou verzi lépe poskládat, ideálně ve stylu
 * oficiálního widgetu Apple Music"). No Android or Compose types here, mirroring [IslandMotion.kt]:
 * a single morph `progress` (0 = pill, 1 = card) drives every layer — container rect, the shared
 * artwork/icon rect, and three opacity windows — through plain JVM functions the Compose layer
 * ([cz.pflanzer.foldduo.island.RailIsland]) only samples inside `graphicsLayer`/`layout` lambdas.
 */

/** Linear interpolation, unclamped — callers already pass `progress` in `0f..1f`. */
fun islandLerp(a: Float, b: Float, progress: Float): Float = a + (b - a) * progress

/** `0` before [start], `1` after [end], a linear ramp in between — one opacity/motion "window" of the morph. */
fun islandMorphWindow(progress: Float, start: Float, end: Float): Float {
    if (end <= start) return if (progress >= end) 1f else 0f
    return ((progress - start) / (end - start)).coerceIn(0f, 1f)
}

/** A rect in the same dp space [cz.pflanzer.foldduo.island.IslandCardRect] already uses. */
data class MorphRect(val leftDp: Float, val topDp: Float, val widthDp: Float, val heightDp: Float) {
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
}

/** Every edge of [from]..[to] lerped at [progress] — the one container rect the whole morph shares (task spec: "one glass container whose rect interpolates from the pill rect to the card rect"). */
fun islandMorphRect(progress: Float, from: MorphRect, to: MorphRect): MorphRect = MorphRect(
    leftDp = islandLerp(from.leftDp, to.leftDp, progress),
    topDp = islandLerp(from.topDp, to.topDp, progress),
    widthDp = islandLerp(from.widthDp, to.widthDp, progress),
    heightDp = islandLerp(from.heightDp, to.heightDp, progress),
)

/** The shared artwork/icon element's own rect, lerped the same way as the container ([islandMorphRect]) between its pill anchor and its card anchor — shared-element travel, not a crossfade. */
fun islandArtworkRect(progress: Float, from: MorphRect, to: MorphRect): MorphRect = islandMorphRect(progress, from, to)

/** Corner radius from the pill's fully round corner (half its height) to the card's fixed corner, at [progress]. */
fun islandMorphCornerDp(progress: Float, pillHeightDp: Float, cardCornerDp: Float = ISLAND_MORPH_CARD_CORNER_DP): Float =
    islandLerp(pillHeightDp / 2f, cardCornerDp, progress)

/** The expanded card's fixed corner radius (task spec: "corner radius from pill/2 to 28 dp"). */
const val ISLAND_MORPH_CARD_CORNER_DP = 28f

/** Reduce motion collapses the whole morph into a short crossfade (task spec item 1). */
const val ISLAND_MORPH_REDUCED_MOTION_MS = 150

// --- Opacity/motion windows, task spec item 1 -----------------------------------------------

/** The pill's own content (icon/equalizer/marquee title) fades out over the morph's first 30 %. */
fun islandPillContentAlpha(progress: Float): Float = 1f - islandMorphWindow(progress, 0f, 0.3f)

/** The card's title/artist fade in from 40 % of the morph to fully in by 100 %. */
fun islandCardTextAlpha(progress: Float): Float = islandMorphWindow(progress, 0.4f, 1f)

/** The transport controls fade in from 55 % of the morph to fully in by 100 %. */
fun islandControlsAlpha(progress: Float): Float = islandMorphWindow(progress, 0.55f, 1f)

/** The transport controls rise from [riseDp] to `0` as they fade in ([islandControlsAlpha]'s own window). */
fun islandControlsRiseDp(progress: Float, riseDp: Float = 8f): Float = riseDp * (1f - islandControlsAlpha(progress))

// --- Pill equalizer glyph, task spec item 3 -------------------------------------------------

/** Bars in the pill's "now playing" equalizer glyph. */
const val ISLAND_EQUALIZER_BAR_COUNT = 3

/** Full period of one bar's oscillation while playing. */
const val ISLAND_EQUALIZER_PERIOD_MS = 900L

/** Fixed level (no motion) for a paused session's equalizer glyph — "static bars" per the task spec. */
const val ISLAND_EQUALIZER_PAUSED_LEVEL = 0.55f

private val ISLAND_EQUALIZER_BAR_PHASE = doubleArrayOf(0.0, 2.0 * PI / 3.0, 4.0 * PI / 3.0)

/**
 * Bar [index]'s height fraction (`0.25..1`) at [elapsedMs] into the animation: each of the
 * [ISLAND_EQUALIZER_BAR_COUNT] bars runs its own phase-shifted sine wave while [playing] so they
 * never move in lockstep; a paused session holds every bar at [ISLAND_EQUALIZER_PAUSED_LEVEL].
 */
fun islandEqualizerBarLevel(index: Int, elapsedMs: Long, playing: Boolean, periodMs: Long = ISLAND_EQUALIZER_PERIOD_MS): Float {
    if (!playing) return ISLAND_EQUALIZER_PAUSED_LEVEL
    if (periodMs <= 0L) return ISLAND_EQUALIZER_PAUSED_LEVEL
    val phase = ISLAND_EQUALIZER_BAR_PHASE.getOrElse(index) { 0.0 }
    val t = (elapsedMs % periodMs).toDouble() / periodMs.toDouble()
    val wave = sin(t * 2.0 * PI + phase)
    return (0.625 + 0.375 * wave).toFloat().coerceIn(0.25f, 1f)
}

// --- Live playhead extrapolation, task spec item 2 ------------------------------------------

/**
 * The progress bar's live position: [basePositionMs] as reported at [baseAtMs] (both in the same
 * clock — `PlaybackState.lastPositionUpdateTime` is `SystemClock.elapsedRealtime()`, never wall
 * time), advanced by real elapsed time at [speed] while [playing] — the same extrapolation
 * `PlaybackState.getPosition()` itself does, redrawn by the caller at 1 Hz rather than recomputed
 * continuously. Frozen at [basePositionMs] while paused (a stopped clock should not creep from a
 * stale [speed]). Clamped to `[0, durationMs]` once [durationMs] is known (> 0).
 */
fun extrapolatedPositionMs(
    basePositionMs: Long,
    baseAtMs: Long,
    nowMs: Long,
    speed: Float,
    playing: Boolean,
    durationMs: Long = 0L,
): Long {
    val raw = if (!playing) basePositionMs else basePositionMs + ((nowMs - baseAtMs) * speed).toLong()
    val clamped = raw.coerceAtLeast(0L)
    return if (durationMs > 0L) clamped.coerceAtMost(durationMs) else clamped
}

// --- Gradient background / auto text colour, task spec item 2 ------------------------------

/** Darkens an opaque ARGB colour toward black by [amount] (`0..1`); alpha is left untouched. */
fun darkenArgb(argb: Int, amount: Float): Int {
    val a = amount.coerceIn(0f, 1f)
    fun channel(shift: Int): Int = (((argb shr shift) and 0xFF) * (1f - a)).roundToInt().coerceIn(0, 255)
    return (argb and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** [argb]'s own alpha channel replaced by [fraction] (`0..1`) — the gradient's top stop, "the artwork's dominant colour at 85 %". */
fun withAlphaFraction(argb: Int, fraction: Float): Int {
    val a = (fraction.coerceIn(0f, 1f) * 255f).roundToInt()
    return (a shl 24) or (argb and 0x00FFFFFF)
}

/** The card gradient's top stop: [dominantArgb] at 85 % opacity over the glass veil. */
fun mediaCardGradientTopArgb(dominantArgb: Int): Int = withAlphaFraction(dominantArgb, 0.85f)

/** The card gradient's bottom stop: [dominantArgb] darkened 30 %, fully opaque. */
fun mediaCardGradientBottomArgb(dominantArgb: Int): Int = darkenArgb(dominantArgb, 0.30f)

/** WCAG relative luminance of an opaque ARGB colour, `0` (black) to `1` (white); alpha is ignored — mirrors [cz.pflanzer.foldduo.relativeLuminance], kept local so this file stays a self-contained JVM unit. */
fun islandColorLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= .03928) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
    }
    return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
}

/**
 * True when the card should use light (near-white) text/icons over its gradient: the mean
 * luminance of the two gradient stops is at or below the WCAG midpoint — a dark album colour gets
 * light text, a bright one gets dark text (task spec: "text colour auto light/dark by the
 * gradient's luminance").
 */
fun mediaCardUsesLightText(topArgb: Int, bottomArgb: Int): Boolean =
    (islandColorLuminance(topArgb) + islandColorLuminance(bottomArgb)) / 2.0 <= 0.5

// --- "Island transparency" (Colours & glass settings, Tom 2026-09-17: "nastavení transparentnosti
// i těch malých i velkých tabletek. Default 75 %") -------------------------------------------

/** Floor of the "Island transparency" setting: below this the pill/card glass stops reading as a chip over the wallpaper. */
const val ISLAND_OPACITY_MIN = 0.4f

/** Ceiling: 100 % is today's fixed look — every base alpha below is exactly what a 1.0 factor already draws. */
const val ISLAND_OPACITY_MAX = 1.0f

/** Default per the task ("Default 75 %"). */
const val ISLAND_OPACITY_DEFAULT = 0.75f

/** The settings slider's step size ("steps of 5 %"). */
const val ISLAND_OPACITY_STEP = 0.05f

/** Clamp any candidate opacity (a dragged slider value, a restored/foreign pref) into the setting's allowed `0.4..1.0` range. */
fun islandOpacityClamped(opacity: Float): Float = opacity.coerceIn(ISLAND_OPACITY_MIN, ISLAND_OPACITY_MAX)

/** [opacity] rounded to the nearest [ISLAND_OPACITY_STEP] inside the clamped range — the slider's "steps of 5 %". */
fun islandOpacitySnapped(opacity: Float): Float {
    val clamped = islandOpacityClamped(opacity)
    val stepIndex = ((clamped - ISLAND_OPACITY_MIN) / ISLAND_OPACITY_STEP).roundToInt()
    return (ISLAND_OPACITY_MIN + stepIndex * ISLAND_OPACITY_STEP).coerceIn(ISLAND_OPACITY_MIN, ISLAND_OPACITY_MAX)
}

/**
 * The collapsed pill's veil alpha at [opacity]: today's fixed [baseAlpha] (`.55f` in
 * `RailIsland.kt`'s `IslandPill`) scaled by the clamped [opacity] factor — `1.0` reproduces
 * [baseAlpha] exactly (today's look), `0.75` (the default) lets a quarter more of the wallpaper
 * through, `0.4` is the floor. Only the pill's background fill/veil, never its icon, artwork or
 * text — those stay fully opaque regardless of this setting.
 */
fun islandPillVeilAlpha(opacity: Float, baseAlpha: Float): Float = baseAlpha * islandOpacityClamped(opacity)

/** The expanded card's own glass veil alpha (`.92f` base in `RailIsland.kt`'s `IslandExpandedOverlay`) — same rule as [islandPillVeilAlpha]. */
fun islandCardVeilAlpha(opacity: Float, baseAlpha: Float): Float = baseAlpha * islandOpacityClamped(opacity)

/**
 * The expanded media card's album-colour gradient alpha at [opacity]: multiplies whatever the
 * morph's own fade-in window ([islandMorphWindow]) already computed for [morphWindowAlpha], so the
 * gradient still fades in with the card and is additionally scaled by the transparency setting —
 * text, artwork and transport controls are untouched by either factor.
 */
fun islandCardGradientAlpha(opacity: Float, morphWindowAlpha: Float): Float = morphWindowAlpha * islandOpacityClamped(opacity)
