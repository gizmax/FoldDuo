package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.pose.Panel
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * IDEAS.md B37 "Tapeta jako ohýbaný list": the wallpaper drawn as a sheet of paper bent at the
 * hinge by the real angle (PoseSnapshot.hingeAngleDeg, step-gated by HingeStepGate: exactly
 * 0°/180° at the ends, clamped to 20..165° inside the 90° band). Pure Kotlin, no Android types,
 * mirroring ParallaxModel/MorphCurve: both the Compose `DuneWallpaper()` and the live wallpaper
 * engine feed this the current hinge angle every frame and read the resulting bend in degrees.
 */

/** Where the hinge sits and how far it bends, for [continuum.SheetBend.setUniforms]. */
data class SheetBendPlan(val hingeX: Float, val bendDeg: Float)

object SheetBendMotion {
    /** Bend at the swap ([MorphCurve.ANGLE_PANEL_ON], ~90° hinge); matches [continuum.SheetBend.MAX_BEND]. */
    const val MAX_BEND_DEG = 60f

    /**
     * The sheet's bend at hinge angle [hingeDeg]: 0 at or past [MorphCurve.ANGLE_FLAT] (flat),
     * [MAX_BEND_DEG] at [MorphCurve.ANGLE_PANEL_ON] (the swap), eased with the launcher's own
     * unfold curve ([MorphCurve.clearEasing]) in between — the same progress fraction
     * [cz.pflanzer.foldduo.continuum.FoldShader.tiltForHinge] uses, so the sheet straightens on
     * exactly the schedule the frost does. NaN (no angle yet) reads as flat, same as
     * [MorphCurve.angleTilt].
     */
    fun bendForHinge(hingeDeg: Float): Float {
        if (hingeDeg.isNaN()) return 0f
        val progress = ((MorphCurve.ANGLE_FLAT - hingeDeg) / (MorphCurve.ANGLE_FLAT - MorphCurve.ANGLE_PANEL_ON))
            .coerceIn(0f, 1f)
        return MAX_BEND_DEG * MorphCurve.clearEasing.transform(progress)
    }

    /** Only the inner panel bends; the cover always shows a flat crop (IDEAS.md B37). */
    fun bendForPanel(panel: Panel, hingeDeg: Float): Float =
        if (panel == Panel.Inner) bendForHinge(hingeDeg) else 0f

    /**
     * The full uniform derivation for [continuum.SheetBend.setUniforms]: on the inner panel the
     * hinge sits at the surface's own centre (the whole inner picture is one texture) and bends
     * by [bendForHinge]; the cover is a flat crop of that same picture (pane identity,
     * WallpaperFraming.kt) so it never bends, whatever the hinge angle — its hingeX is reported
     * as the surface's centre too, purely so a caller that ignores [SheetBendPlan.bendDeg] still
     * gets a sane value, never as a signal to bend it.
     */
    fun planFor(panel: Panel, widthPx: Float, hingeDeg: Float): SheetBendPlan =
        SheetBendPlan(hingeX = widthPx * 0.5f, bendDeg = bendForPanel(panel, hingeDeg))
}

/**
 * B37 "Snap flat": when the bend reaches 0 (the hinge settles at Flat) the sheet doesn't merely
 * stop bending, it visibly cracks straight — a closed-form underdamped spring (dampingRatio 0.6)
 * from [START_DEG] back to 0, so it briefly overshoots past 0 before resting, the same read as a
 * real sheet of paper snapping flat. Time-stepped like [cz.pflanzer.foldduo.pose.ParallaxModel]
 * so either the composable's frame loop or the wallpaper engine's Choreographer can drive it.
 * [trigger] is meant to be edge-fired from [MorphCurve.settleAngleEdge] — that function only
 * checks a tilt-like value crossing from positive to (at-or-below) zero against the angle, so
 * [SheetBendMotion.bendForHinge]'s output qualifies as-is, no separate edge detector needed.
 */
class SheetSnapSpring {
    private var elapsedS = 0f
    private var running = false

    /** Current overshoot in degrees, added on top of [SheetBendMotion.bendForHinge]'s own value. */
    var overshootDeg: Float = 0f
        private set

    /** True while the crack is still playing; a hint for callers deciding whether to keep animating. */
    val isRunning: Boolean get() = running

    /** Starts (or restarts) the crack from [START_DEG]. */
    fun trigger() {
        elapsedS = 0f
        running = true
        overshootDeg = START_DEG
    }

    /** Advances the spring by [dtMs]; a no-op once it has settled or before the first [trigger]. */
    fun advance(dtMs: Float) {
        if (!running) return
        elapsedS += dtMs.coerceAtLeast(0f) / 1000f
        if (elapsedS >= SETTLE_S) {
            running = false
            overshootDeg = 0f
            return
        }
        val decay = exp(-DAMPING_RATIO * NATURAL_FREQ * elapsedS)
        overshootDeg = START_DEG * decay *
            (cos(DAMPED_FREQ * elapsedS) + (DAMPING_RATIO * NATURAL_FREQ / DAMPED_FREQ) * sin(DAMPED_FREQ * elapsedS))
    }

    /** Back to at-rest, no overshoot (a panel change, reduced motion turning on, or the wallpaper going invisible). */
    fun reset() {
        running = false
        overshootDeg = 0f
        elapsedS = 0f
    }

    companion object {
        /** A hair past flat before the spring eases back — the visual "crack". */
        const val START_DEG = -2f

        /** Same damping ratio as the task's spec; underdamped, so the value overshoots past 0 once before resting. */
        const val DAMPING_RATIO = 0.6f

        /** Natural frequency in rad/s; tuned for a crack that reads as instant (~200 ms to settle). */
        const val NATURAL_FREQ = 30f
        private val DAMPED_FREQ = NATURAL_FREQ * sqrt(1f - DAMPING_RATIO * DAMPING_RATIO)

        /** Past this the spring is settled (comfortably past 4 time-constants at this damping/frequency). */
        const val SETTLE_S = 0.3f
    }
}
