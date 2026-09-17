package cz.pflanzer.foldduo

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.continuum.ShaderRegistry
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.HingeTiltSmoother
import cz.pflanzer.foldduo.pose.OpeningMotionDetector
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Continuum A (PLAN.md, Fáze 3): the two-phase unfold morph on Home, the iPhone Duo one, and
 * its inverse while folding.
 *
 * Phase 1, cover frost: while the phone is being opened the cover Home (wallpaper included)
 * frosts progressively, hinged on its left edge. The Fold 8 gives apps no continuous hinge
 * angle (fact 4) and its quantised hinge sensor reports late, so the trigger is a hinge step
 * off Closed or, behind the "frost on motion" setting, the gyroscope's opening motion
 * (pose/OpeningMotion.kt); the frost is held until the panel swap switches the cover off, or
 * released after [MorphCurve.COVER_FROST_HOLD_MS] when no swap follows (a false positive costs
 * a brief haze).
 *
 * Phase 2, inner clear: the swap starts a fixed-length animation in which the whole left half
 * of the inner panel (its wallpaper, the leading pane's widgets and icons) starts fully frosted
 * under one frost layer and clears like glass: [MorphCurve.clearEasing] holds the frost through
 * the first part and resolves it late, nothing fades, and the pane only drifts by
 * [MorphCurve.SLIDE_FRACTION] of its width (a slide read as "the widget shoots in" on the
 * device; everything must move with the frost, not under it); the right pane (icons, dock,
 * rail) stands still and sharp (fact 1, pane identity). The mapping from progress to drift /
 * tilt lives in [MorphCurve] as pure functions so it can be unit-tested and tuned in one place.
 *
 * Phase 0, closing frost: the inverse of phase 2 while the open phone is being folded. The
 * trigger is the hinge step leaving Flat or (behind the same setting) the gyroscope's closing
 * motion on the inner panel; the left half frosts up in place (no slide, no fade) over
 * [MorphCurve.CLOSE_FROST_IN_MS] and is held for the swap to the cover (which switches the inner
 * panel off and resets it). A hinge trigger holds by state: as long as the inner panel is up and
 * the hinge step is off Flat (a slow fold was traced at 3 s from the Mid step to the swap, and
 * the step flickers Mid <-> Flat while the phone is held around 135°), released only once the
 * step is back at Flat for [MorphCurve.CLOSE_FROST_FLAT_SETTLE_MS] or after
 * [MorphCurve.CLOSE_FROST_HOLD_MAX_MS] as a safety net ([CloseFrostHold]); a gyro trigger has
 * no hinge state to follow and holds [MorphCurve.CLOSE_FROST_HOLD_MS]. The right pane stays
 * sharp; the cover then settles as before.
 *
 * Angle mode (2026-09-15, the Duo behaviour proper): while the Pose Engine publishes a confident
 * continuous hinge angle ([PoseSnapshot.hingeAngleDeg], the magnetometer estimator of
 * pose/HingeAngleEstimator.kt) the frost follows the hand instead of a timer. On the inner panel
 * the left-half tilt is [FoldShader.tiltForHinge] of the angle (full frost at
 * [FoldShader.PANEL_ON_HINGE] = 90°, where the panel lights up, clear at [FoldShader.FLAT_HINGE]
 * = 172°) both ways: opening clears it, closing frosts it, no play needed. On the cover it is
 * [FoldShader.coverTiltForHinge] (sharp below [FoldShader.CLOSED_HINGE] = 15°, full at 90°). The
 * value is smoothed ([HingeTiltSmoother], τ 60 ms, a > 40° jump held 200 ms) and written into
 * [MorphController.angleTilt], a float state read only in the layer blocks, so the ~50 Hz
 * samples re-render the layers and recompose nothing. While angle mode is on the timed plays
 * of the same layers are not started (the swap's unfold clear, the hinge/gyro frosts); when the
 * angle is NaN (not yet anchored at a rest, out of the table's range, no magnetometer) the
 * timed morphs above run exactly as before. The layers take the larger of the two, so a debug
 * replay still shows while angle mode holds the half sharp.
 *
 * Host app widgets on the left half (AppWidgetHostViews in AndroidViews) are not composited
 * through the frosted layer, so while either morph drives the half
 * ([MorphController.leftHalfFrozen]) WidgetSlot shows a bitmap snapshot of each in place of the
 * live view (LauncherScreen.kt). The flag is derived from the animation values themselves
 * (progress back at 1, closing frost back at 0), not only from the plays' running flags, so a
 * snapshot can never be dropped while the half is still frosted.
 *
 * Note on timing: the tweens here follow the system animator duration scale (Compose's
 * MotionDurationScale), plain delays do not. On a phone set to 0.5× (the Fold 8 per the setup
 * notes) [MorphCurve.DURATION_MS] plays in half the time while [MorphCurve.SCREEN_ON_SETTLE_MS]
 * and the holds are real time; a log line `thawed host widget` ~330 ms after the 600 ms morph's
 * start was that, not an early thaw.
 */

/** Everything tunable about the morph, in one place. */
object MorphCurve {
    /** Length of the inner clear (phase 2). Duo is ~0.4 s; 750 so the glass reads as clearing, not as content arriving. */
    const val DURATION_MS = 750

    /**
     * Today starts this fraction of its own width left of its resting place: a barely
     * perceptible drift. A real slide (0.5) read on the device as the widgets shooting in after
     * the frost instead of the glass clearing over them.
     */
    const val SLIDE_FRACTION = 0.04f

    /** Frost at progress 0: the shader's cap, fully frosted. */
    const val MAX_TILT = FoldShader.MAX_TILT

    // Angle mode (see the file comment): the hinge angle -> tilt mapping is FoldShader's with the Fold 8 constants.
    /** The angle at which the inner panel lights up; the inner frost is full here. */
    const val ANGLE_PANEL_ON = FoldShader.PANEL_ON_HINGE
    /** The angle at which the inner frost is fully clear. */
    const val ANGLE_FLAT = FoldShader.FLAT_HINGE
    /** Below this the cover frost is off in angle mode. */
    const val ANGLE_COVER_CLOSED = FoldShader.CLOSED_HINGE
    /** Time constant of the tilt smoothing in angle mode. */
    const val ANGLE_TAU_MS = HingeTiltSmoother.DEFAULT_TAU_MS
    /** An angle jump above this between samples is an Earth-field glitch: the tilt holds for [ANGLE_GLITCH_HOLD_MS]. */
    const val ANGLE_GLITCH_DEG = HingeTiltSmoother.DEFAULT_GLITCH_DEG
    const val ANGLE_GLITCH_HOLD_MS = HingeTiltSmoother.DEFAULT_HOLD_MS
    /** The debug preview (`DEBUG_MORPH --ef angle 120`) holds its fake angle this long. */
    const val ANGLE_DEBUG_HOLD_MS = 3_000L
    /** B33/"Pacing morphu": full-[MAX_TILT]-range rise time for a frost-up ([TiltPacing.riseLimited]'s `fullRiseMs`), B18-scaled before use. */
    const val RISE_FULL_MS = TiltPacing.DEFAULT_FULL_RISE_MS
    private val angleConfig = FoldConfig()

    /**
     * Angle mode's tilt for [panel] at hinge [angleDeg]: the inner left half through
     * [FoldShader.tiltForHinge] (45° at [ANGLE_PANEL_ON], 0 at [ANGLE_FLAT], linear between,
     * clamped), the cover through [FoldShader.coverTiltForHinge] (0 at [ANGLE_COVER_CLOSED], 45°
     * at [ANGLE_PANEL_ON]); 0 for an unknown panel or a NaN angle.
     */
    fun angleTilt(panel: Panel, angleDeg: Float): Float = when {
        angleDeg.isNaN() -> 0f
        panel == Panel.Inner -> FoldShader.tiltForHinge(angleDeg, angleConfig)
        panel == Panel.Cover -> FoldShader.coverTiltForHinge(angleDeg, angleConfig)
        else -> 0f
    }

    // ---- "Zavírání jako Duo" (17. 9. noc): a distinct, asymmetric closing curve ----

    /**
     * Item 2, "continuity to the swap": the inner left half's closing tilt reaches full well
     * before the panel actually swaps off (~86-91°, [FoldShader.PANEL_ON_HINGE]), not right at
     * it, so the last few degrees before the swap are already fully frosted (Apple's own closing
     * feel) instead of the frost still resolving when the screen goes dark. Deliberately not the
     * same endpoint [ANGLE_PANEL_ON] the opening curve uses.
     */
    const val CLOSING_PANEL_ON_HINGE = 100f

    /**
     * Ease-in for the closing tilt: slow to leave [ANGLE_FLAT] (a hinge barely moving reads as
     * barely fogging), fast through the approach to [CLOSING_PANEL_ON_HINGE] — the mirror
     * intent of [clearEasing] (which is slow to *leave* fully frosted while clearing); together
     * the two read as a matched pair of curves, not a straight reversal of one.
     */
    val closingTiltEasing = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    /**
     * The inner left half's tilt while CLOSING only (paired with [MorphController.closingDirectionActive]
     * — never applied while opening, which keeps using [angleTilt]/[FoldShader.tiltForHinge]):
     * [ANGLE_FLAT] .. [CLOSING_PANEL_ON_HINGE] maps onto 0 .. [MAX_TILT] through
     * [closingTiltEasing], saturating at [MAX_TILT] for any angle at or below
     * [CLOSING_PANEL_ON_HINGE] (including the whole way down to the eventual swap). NaN is 0,
     * same convention as [angleTilt].
     */
    fun closingAngleTilt(angleDeg: Float): Float {
        if (angleDeg.isNaN()) return 0f
        val progress = ((ANGLE_FLAT - angleDeg) / (ANGLE_FLAT - CLOSING_PANEL_ON_HINGE)).coerceIn(0f, 1f)
        return closingTiltEasing.transform(progress) * MAX_TILT
    }

    /**
     * The cover layer's tilt: the timed cover frost, angle mode, or the B17 reverse cover settle
     * ([settleTilt]), whichever is larger. [settleTilt] defaults to 0 for callers (and tests)
     * that predate it.
     */
    fun coverTilt(coverFrost: Float, angleTilt: Float, settleTilt: Float = 0f): Float =
        max(max(coverFrostTilt(coverFrost), angleTilt.coerceIn(0f, MAX_TILT)), settleTilt.coerceIn(0f, MAX_TILT))

    /**
     * The clear's easing over time: slow out of the fully frosted start, fast through the
     * middle, soft into rest, so the frost holds early and the glass clears late. Pinned by
     * [CLEAR_HOLD_FRACTION] / [CLEAR_HOLD_TILT]: for the first 30 % of the animation the tilt
     * stays at or above 35°.
     */
    val clearEasing = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)
    const val CLEAR_HOLD_FRACTION = 0.3f
    const val CLEAR_HOLD_TILT = 35f

    /** The cover Home settles from this alpha after a fold so the switch does not pop. */
    const val COVER_SETTLE_FROM = 0.85f
    /** B17: length of the reverse cover settle (alpha and tilt together); a Compose tween, so B18 scales it. */
    const val COVER_SETTLE_MS = 300

    /**
     * How long after a panel swap the first expanded (or compact) layout still counts as the
     * result of that swap. Covers the activity relaunch Android performs on the swap.
     */
    const val TRIGGER_WINDOW_MS = 800L
    /** An armed request older than this is dropped instead of replayed. */
    const val ARMED_TTL_MS = 1_500L
    /**
     * One UI plays its own screen-on fade on the inner panel after a swap (measured on the Fold 8:
     * `LayeredScreenOnOffAnimator WAKE_UP delay=240 dur=600`, i.e. ~840 ms after OPENED). A morph
     * that runs underneath it is invisible, so the frosted pane holds until the panel is lit.
     */
    const val SCREEN_ON_SETTLE_MS = 900L

    // Phase 1, the cover frost (see the file comment).
    /** Tilt of the fully frosted cover, hinged on its left edge. */
    const val COVER_FROST_TILT = FoldShader.MAX_TILT
    /** The cover frosts 0 -> [COVER_FROST_TILT] over this long once the opening is detected. */
    const val COVER_FROST_IN_MS = 350
    /**
     * Frosted cover held this long for the panel swap; then it was a false positive. Short: a
     * measured session of handling the closed phone kept the cover hazed for seconds at a time.
     */
    const val COVER_FROST_HOLD_MS = 1_200L
    /** Release of a false positive back to sharp. */
    const val COVER_FROST_OUT_MS = 300
    /**
     * Gyro trigger: |rate around the hinge axis| at least this, for this many consecutive
     * samples. Handling the closed phone was measured at 1.4–4.5 rad/s per second (6–7 while
     * flipping it), the fastest real swings at 5.6–8.7; a real open can also stay under 2 rad/s
     * when the other half swings, so the gyro trigger is opt-in ([AppearanceState.motionFrost]).
     */
    const val GYRO_OPEN_RATE_RAD_S = OpeningMotionDetector.DEFAULT_THRESHOLD_RAD_S
    const val GYRO_OPEN_SAMPLES = OpeningMotionDetector.DEFAULT_CONSECUTIVE_SAMPLES

    // Phase 0, the closing frost (see the file comment).
    /** Tilt of the fully frosted, slid-out left half. */
    const val CLOSE_FROST_TILT = FoldShader.MAX_TILT
    /** The left half frosts 0 -> [CLOSE_FROST_TILT] and slides out over this long once the fold is detected. */
    const val CLOSE_FROST_IN_MS = 350
    /** A gyro-triggered frosted left half is held this long for the swap to the cover; then it was a false positive. */
    const val CLOSE_FROST_HOLD_MS = 1_200L
    /** Release of a false positive back to sharp and in place. */
    const val CLOSE_FROST_OUT_MS = 300
    /** The debug replay (`DEBUG_MORPH --ez close true`) holds this long instead, to be looked at. */
    const val CLOSE_FROST_DEBUG_HOLD_MS = 1_000L
    /**
     * A hinge-triggered frost holds by state instead ([CloseFrostHold]): while the hinge step
     * stays off Flat on the inner panel. This is its safety net: a phone left half-open longer
     * than this releases anyway.
     */
    const val CLOSE_FROST_HOLD_MAX_MS = 8_000L
    /** The hinge step must read Flat again for this long before the hinge hold releases: it flickers Mid <-> Flat around 135°. */
    const val CLOSE_FROST_FLAT_SETTLE_MS = 400L
    /** How often the hinge hold re-reads the pose. */
    const val CLOSE_FROST_HOLD_POLL_MS = 50L

    val easing = FastOutSlowInEasing

    /** Horizontal offset of the Today pane in px for [paneWidthPx] at [progress] (0..1): starts [SLIDE_FRACTION] left, ends at 0. */
    fun translationX(progress: Float, paneWidthPx: Float): Float =
        -(1f - progress.coerceIn(0f, 1f)) * paneWidthPx * SLIDE_FRACTION

    /** Pane tilt in degrees for the frost shader: [MAX_TILT] at 0, flat (0) at 1. */
    fun tilt(progress: Float): Float = (1f - progress.coerceIn(0f, 1f)) * MAX_TILT

    /** The tilt at [timeFraction] (0..1) of the clear as it plays: [tilt] of the eased progress. */
    fun clearTilt(timeFraction: Float): Float = tilt(clearEasing.transform(timeFraction.coerceIn(0f, 1f)))

    /** Alpha of the cover Home during the settle, [COVER_SETTLE_FROM] -> 1. */
    fun coverAlpha(progress: Float): Float =
        COVER_SETTLE_FROM + (1f - COVER_SETTLE_FROM) * progress.coerceIn(0f, 1f)

    /** Tilt of the morph's first animated frame (progress 0): what the idle layer pre-builds its effect for. */
    val startTilt: Float get() = tilt(0f)

    /** Cover tilt for the frost fraction (0 = sharp, 1 = held fully frosted). */
    fun coverFrostTilt(fraction: Float): Float = fraction.coerceIn(0f, 1f) * COVER_FROST_TILT

    /**
     * The cover frost schedule without a swap, as a fraction over time since the trigger:
     * rises over [COVER_FROST_IN_MS], holds for [COVER_FROST_HOLD_MS], falls over
     * [COVER_FROST_OUT_MS]. Linear in time here (the controller eases the same keyframes);
     * this is the reference the tests and any other renderer follow.
     */
    fun coverFrostFraction(msSinceTrigger: Long): Float =
        frostFraction(msSinceTrigger, COVER_FROST_IN_MS, COVER_FROST_HOLD_MS, COVER_FROST_OUT_MS)

    /** When, relative to the trigger, an unswapped cover frost starts to release. */
    val coverFrostReleaseAtMs: Long get() = COVER_FROST_IN_MS + COVER_FROST_HOLD_MS

    // ---- phase 0 ----

    /**
     * Left-half tilt for the closing fraction (0 = sharp, at rest; 1 = held fully frosted). Same
     * curve as the unfold in reverse; the half frosts in place (no slide, no fade).
     */
    fun closeFrostTilt(fraction: Float): Float = fraction.coerceIn(0f, 1f) * CLOSE_FROST_TILT

    /** The gyro-triggered closing frost schedule without a swap; see [coverFrostFraction]. A hinge trigger holds by state ([CloseFrostHold]). */
    fun closeFrostFraction(msSinceTrigger: Long): Float =
        frostFraction(msSinceTrigger, CLOSE_FROST_IN_MS, CLOSE_FROST_HOLD_MS, CLOSE_FROST_OUT_MS)

    /** When, relative to the trigger, an unswapped gyro-triggered closing frost starts to release. */
    val closeFrostReleaseAtMs: Long get() = CLOSE_FROST_IN_MS + CLOSE_FROST_HOLD_MS

    /**
     * How long the risen closing frost holds for [trigger] before it releases without a swap:
     * null for `hinge`, which holds by state ([CloseFrostHold]) instead of a timer; the fixed
     * [CLOSE_FROST_DEBUG_HOLD_MS] for the `debug` replay (the hinge is Flat, a state hold would
     * release at once); [CLOSE_FROST_HOLD_MS] for `gyro`.
     */
    fun closeFrostTimedHoldMs(trigger: String): Long? = when (trigger) {
        "hinge" -> null
        "debug" -> CLOSE_FROST_DEBUG_HOLD_MS
        else -> CLOSE_FROST_HOLD_MS
    }

    /**
     * What the expanded left-half frost layer draws for the two morphs together: the unfold
     * clear ([progress], 1 at rest) and the closing frost ([closeFrost], 0 at rest). They never
     * run at once ([MorphController]), so whichever is off its rest value wins: the larger tilt.
     * The pane's drift is the unfold's alone ([translationX]); the closing frost moves nothing.
     */
    fun leftHalfTilt(progress: Float, closeFrost: Float, angleTilt: Float = 0f): Float =
        max(max(tilt(progress), closeFrostTilt(closeFrost)), angleTilt.coerceIn(0f, MAX_TILT))

    /** True while the left half is off its rest state on any driver: [progress] short of 1, [closeFrost] or [angleTilt] above 0. */
    fun leftHalfMoving(progress: Float, closeFrost: Float, angleTilt: Float = 0f): Boolean = progress < 1f || closeFrost > 0f || angleTilt > 0f

    // ---- B16, "life after cleaning": the settle once the left half reaches Flat ----

    /** Stagger between successive Today items settling, in reading order (see LeadingPane.kt's `leadingReadingRank`). */
    const val SETTLE_STAGGER_MS = 40L
    /** Icons/widgets start a touch smaller and dimmer, then spring to their resting scale/alpha. */
    const val SETTLE_START_SCALE = 0.96f
    const val SETTLE_START_ALPHA = 0.85f
    /** Settle spring tuning: brisk, only slightly underdamped ([androidx.compose.animation.core.spring]). */
    const val SETTLE_DAMPING_RATIO = 0.7f
    /** Copy of [androidx.compose.animation.core.Spring.StiffnessMediumLow], kept a plain constant so this file stays testable on the JVM without pulling in the animation-core object graph. */
    const val SETTLE_STIFFNESS = 400f
    /** Angle mode's settle only fires when the angle itself is genuinely near [ANGLE_FLAT], not merely because angle mode stopped driving (a dropped read or low confidence also snaps the tilt to 0). */
    const val SETTLE_FLAT_MARGIN_DEG = 5f

    /** Delay before the item at [index] (0-based, reading order) starts its settle spring. */
    fun settleDelayMs(index: Int): Long = index.coerceAtLeast(0) * SETTLE_STAGGER_MS

    /**
     * True exactly when this angle-mode sample marks the inner left half reaching Flat for B16's
     * settle-once trigger: the tilt was above 0 and is back at 0 because the angle itself is at
     * or past [ANGLE_FLAT] within [SETTLE_FLAT_MARGIN_DEG] — not because angle mode merely
     * stopped driving (out of range, lost anchor, low confidence), which also eases the tilt to 0
     * through the same smoother but is not a real unfold completion.
     */
    fun settleAngleEdge(panel: Panel, previousTilt: Float, tilt: Float, angleDeg: Float): Boolean =
        panel == Panel.Inner && previousTilt > 0f && tilt <= 0f && !angleDeg.isNaN() && angleDeg >= ANGLE_FLAT - SETTLE_FLAT_MARGIN_DEG

    // ---- B16: the specular sweep over the frost as the left half finishes clearing ----

    /** The sweep runs while the shader tilt (not the hinge angle) crosses this window down to 0. */
    const val SWEEP_START_TILT = 20f
    /** Width of the light band, as a fraction of the pane's width. */
    const val SWEEP_WIDTH_FRACTION = 0.18f
    /** Peak alpha of the band (white). */
    const val SWEEP_ALPHA = 0.12f

    /** 0 while [tilt] is at or above [SWEEP_START_TILT] (not due yet), 1 once it reaches 0 (swept all the way through); monotonic and clamped outside the window. */
    fun sweepProgress(tilt: Float): Float = ((SWEEP_START_TILT - tilt) / SWEEP_START_TILT).coerceIn(0f, 1f)

    /**
     * The band's center, as a fraction of the pane's width, at [progress]: starts just past the
     * hinge/seam edge (fraction 1, the pane's right side — the left half's own hinge) and ends just
     * past the outward edge (fraction 0, the left side), each overshot by half the band's own
     * width so it fully enters and fully leaves instead of popping in or clipping out.
     */
    fun sweepCenterFraction(progress: Float): Float {
        val half = SWEEP_WIDTH_FRACTION / 2f
        return (1f + half) - (1f + SWEEP_WIDTH_FRACTION) * progress.coerceIn(0f, 1f)
    }

    /**
     * Triangular feather of the band at [xFraction] (a fraction of the pane's width) for a band
     * centered at [centerFraction]: 1 at the center, 0 at or beyond half the band's width away.
     */
    fun sweepIntensity(xFraction: Float, centerFraction: Float): Float {
        val half = SWEEP_WIDTH_FRACTION / 2f
        return (1f - abs(xFraction - centerFraction) / half).coerceIn(0f, 1f)
    }

    // ---- B17, "closing as a mirror": the reverse cover settle ----

    /** Hinge angle the cover's reverse settle appears to start from (dramatized: One UI has already swapped the panel by the time this plays, so there is no real angle to read from a cold cover). */
    const val COVER_SETTLE_START_ANGLE = 40f

    /** [COVER_SETTLE_START_ANGLE] through the cover mapping: the reverse settle's starting tilt. */
    fun coverSettleStartTilt(config: FoldConfig = FoldConfig()): Float = FoldShader.coverTiltForHinge(COVER_SETTLE_START_ANGLE, config)

    /**
     * Tilt of the reverse cover settle at [timeFraction] (0..1 of the play): eases from
     * [startTilt] to sharp along [clearEasing] — the same curve the unfold's own clear uses, so
     * the two morphs read as mirrors of each other.
     */
    fun coverSettleTiltAt(timeFraction: Float, startTilt: Float = coverSettleStartTilt()): Float =
        startTilt * (1f - clearEasing.transform(timeFraction.coerceIn(0f, 1f)))

    // ---- B17: closing seam darken on the right pane ----

    /** Cap of the closing darken gradient at the seam: 12% black at full tilt. */
    const val CLOSING_DARKEN_MAX_ALPHA = 0.12f
    /** Schmitt-trigger threshold for [MorphController]'s closing/opening direction latch: a confirmed move of at least this many degrees flips it, smaller drift (sensor-rate noise, or the phone held still mid-fold) does not. */
    const val CLOSING_DIRECTION_HYSTERESIS_DEG = 4f

    /** Alpha of the closing darken gradient for [tilt] (0..[MAX_TILT]): linear, capped at [CLOSING_DARKEN_MAX_ALPHA]. */
    fun closingDarkenAlpha(tilt: Float): Float = (tilt.coerceIn(0f, MAX_TILT) / MAX_TILT) * CLOSING_DARKEN_MAX_ALPHA

    // ---- B18: the morph's own clock, corrected for the system animator duration scale ----

    /**
     * [baseMs] as it actually plays under the system's animator duration scale
     * (`Settings.Global.ANIMATOR_DURATION_SCALE`): Compose's `MotionDurationScale` would apply
     * this automatically to a tween running in the right dispatcher context, but the plays here
     * pass their duration explicitly (so the debug replay and the tests can reason about it
     * directly), so the correction is done by hand instead — a 0.5x setting halves every tween
     * unless the length fed to it is doubled back first. 0 (animations off) is treated as 1 (a
     * play still needs *some* length to run out); never returns under 1 ms.
     */
    fun scaledDurationMs(baseMs: Int, animatorDurationScale: Float): Int {
        val scale = if (!animatorDurationScale.isFinite() || animatorDurationScale <= 0f) 1f else animatorDurationScale
        return (baseMs / scale).roundToInt().coerceAtLeast(1)
    }

    // ---- B21: reduce motion ----

    /** Length of the whole-launcher-morph's crossfade while `MotionPrefs.enabled` is on: fixed, not B18-scaled (the point of reduce motion is a short, predictable transition regardless of the animator duration scale, which is usually 0 in that state anyway). */
    const val REDUCE_MOTION_CROSSFADE_MS = 200

    private fun frostFraction(msSinceTrigger: Long, inMs: Int, holdMs: Long, outMs: Int): Float {
        if (msSinceTrigger <= 0L) return 0f
        val rise = inMs.toLong()
        val release = rise + holdMs
        val end = release + outMs
        return when {
            msSinceTrigger < rise -> msSinceTrigger.toFloat() / inMs
            msSinceTrigger < release -> 1f
            msSinceTrigger < end -> 1f - (msSinceTrigger - release).toFloat() / outMs
            else -> 0f
        }
    }
}

/**
 * Which trigger, if any, a new Pose Engine snapshot supplies for the cover frost: only while
 * the cover is active, and only on an edge: the hinge step leaving Closed (the first sample
 * after registration has no predecessor and never counts), or, with [motionFrost] on, the
 * gyroscope's opening-motion sequence advancing (a first snapshot, [previousMotionSeq] < 0,
 * never counts either).
 */
fun coverFrostTrigger(panel: Panel, previousStep: HingeStep?, step: HingeStep?, previousMotionSeq: Int, motionSeq: Int,
    motionFrost: Boolean): String? {
    if (panel != Panel.Cover) return null
    if (previousStep == HingeStep.Closed && step != null && step != HingeStep.Closed) return "hinge"
    if (motionFrost && previousMotionSeq >= 0 && motionSeq != previousMotionSeq) return "gyro"
    return null
}

/**
 * The closing counterpart: only while the inner panel is active, on the hinge step leaving
 * Flat (Mid on a normal fold, measured 1.2 s before the swap to the cover; straight to Closed
 * on a fast one) or, with [motionFrost] on, the closing-motion sequence advancing.
 */
fun closeFrostTrigger(panel: Panel, previousStep: HingeStep?, step: HingeStep?, previousMotionSeq: Int, motionSeq: Int,
    motionFrost: Boolean): String? {
    if (panel != Panel.Inner) return null
    if (previousStep == HingeStep.Flat && step != null && step != HingeStep.Flat) return "hinge"
    if (motionFrost && previousMotionSeq >= 0 && motionSeq != previousMotionSeq) return "gyro"
    return null
}

/**
 * True when the latest Pose Engine snapshot says the inner panel just took over: the pose is
 * still [FoldPose.InMotion] (500 ms after a swap) or the swap is younger than
 * [MorphCurve.TRIGGER_WINDOW_MS]. Either way the panel must be [Panel.Inner]: a hinge step
 * alone while closed must not arm the morph.
 */
fun unfoldJustHappened(pose: FoldPose, panel: Panel, msSinceTransition: Long): Boolean =
    panel == Panel.Inner && (pose == FoldPose.InMotion || msSinceTransition < MorphCurve.TRIGGER_WINDOW_MS)

/** The closing counterpart: the cover just took over. */
fun foldJustHappened(panel: Panel, msSinceTransition: Long): Boolean =
    panel == Panel.Cover && msSinceTransition < MorphCurve.TRIGGER_WINDOW_MS

/**
 * The state-based hold of a hinge-triggered closing frost (phase 0), pure so it can be tested:
 * feed the pose the controller last saw and the clock; the frost holds while the answer is
 * null. It releases once the hinge step has read Flat on the inner panel for [flatSettleMs]
 * without interruption (a shorter Flat is the step flickering while the phone is held around
 * 135°), or when [maxMs] have passed since [startedAtMs] whatever the pose. The swap to the
 * cover never gets here: it cancels the play. Any panel other than Inner holds too (no swap
 * seen yet, or none coming), bounded by the same net.
 */
class CloseFrostHold(
    private val startedAtMs: Long,
    private val maxMs: Long = MorphCurve.CLOSE_FROST_HOLD_MAX_MS,
    private val flatSettleMs: Long = MorphCurve.CLOSE_FROST_FLAT_SETTLE_MS,
) {
    private var flatSinceMs = -1L

    /** Null while the frost holds; otherwise why it releases (`hinge back at Flat`, `timeout`). */
    fun release(panel: Panel, step: HingeStep?, nowMs: Long): String? {
        if (nowMs - startedAtMs >= maxMs) return "timeout"
        val flat = panel == Panel.Inner && step == HingeStep.Flat
        if (!flat) { flatSinceMs = -1L; return null }
        if (flatSinceMs < 0L) flatSinceMs = nowMs
        return if (nowMs - flatSinceMs >= flatSettleMs) "hinge back at Flat" else null
    }
}

/**
 * B16's settle-once-per-unfold state: [fire] returns true exactly the first time it is called,
 * false every time after. Pure and trivial by design so [MorphController] can drive it from
 * either edge that reaches Flat (the timed play completing, or [MorphCurve.settleAngleEdge] in
 * angle mode) without either path needing to know whether the other already fired.
 */
class SettleOnceGate {
    private var didFire = false

    /** True only on the first call; every later call returns false. */
    fun fire(): Boolean {
        if (didFire) return false
        didFire = true
        return true
    }

    /** Whether [fire] has already returned true once. */
    val hasFired: Boolean get() = didFire
}

/**
 * True while the expanded left half is driven by a morph and so may be frosted on the next
 * frame: from the unfold morph's start (its first frame, at progress 0, is fully frosted) until
 * its [progress] is back at 1, and from a closing frost being taken until it is released or
 * cancelled and [closeFrost] is back at 0. Host app widgets on the half show a snapshot of
 * themselves while this is on. The flags cover the frame before the first animated value is
 * written; the values cover whatever ends a play (cancellation included) before the half is
 * sharp and in place again.
 */
fun leftHalfFrozen(running: Boolean, closeFrostActive: Boolean, progress: Float = 1f, closeFrost: Float = 0f, angleTilt: Float = 0f): Boolean =
    running || closeFrostActive || MorphCurve.leftHalfMoving(progress, closeFrost, angleTilt)

/**
 * Drives the morph. `progress` is 1 when idle (Today at rest, no frost); a play snaps it to 0
 * and animates back to 1 over [MorphCurve.DURATION_MS]. Read `progress.value` inside a
 * `graphicsLayer` block so only the layer re-renders per frame.
 *
 * [snapshot] is the live Pose Engine snapshot; [requests] is bumped by [requestEnter] (a
 * panel swap seen while the activity already exists, or the debug replay) and keyed by the
 * layout's trigger effect. [motionFrost] is the "frost on motion" setting: the gyro triggers
 * of both frosts are honoured only while it is on.
 */
class MorphController(
    private val snapshot: () -> PoseSnapshot,
    /** Injectable for JVM tests (android.os.SystemClock is not mocked there). */
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Debug-level log sink of the synchronous paths; injectable for JVM tests (android.util.Log is not mocked there). */
    private val log: (String) -> Unit = { Log.d(TAG, it) },
    private val motionFrost: () -> Boolean = { false },
    /** Info-level log sink of the plays; injectable for the same reason. */
    private val info: (String) -> Unit = { Log.i(TAG, it) },
    /**
     * B21, "Reduce motion" ([MotionPrefs.enabled] in production): while true, angle mode never
     * drives a tilt (see [noteAngle]), a hinge/gyro cover or closing frost is never armed
     * ([requestCoverFrost], [requestCloseFrost] — the launcher never shows the shader's tilt/blur
     * at all), [playEnter] plays a plain [MorphCurve.REDUCE_MOTION_CROSSFADE_MS] crossfade instead
     * of the timed clear, and [playCoverSettle] resolves instantly. Callers still see [progress] /
     * [coverProgress] move (so an alpha-only crossfade in the layers has something to read), just
     * never a nonzero tilt.
     */
    private val reduceMotion: () -> Boolean = { false },
) {
    val progress = Animatable(1f)
    val coverProgress = Animatable(1f)
    /** Phase 1: 0 = sharp cover, 1 = held fully frosted. Read in the cover layer's block. */
    val coverFrost = Animatable(0f)
    /** B17: the reverse cover settle's own tilt, 0 = sharp. Read in the cover layer's block, combined with [coverFrost] and [angleTilt] through [MorphCurve.coverTilt]. */
    val coverSettleTilt = Animatable(0f)
    /**
     * B32 (standby/StandByMorph.kt): 0 = sharp, 1 = held fully frosted while StandBy owns the
     * screen. [requestStandByReturn] snaps it to 1 and clears it over 300 ms once StandBy exits,
     * so the launcher's return reads as one continuous morph instead of the activity popping
     * away. Read in whichever panel's own layer is active; not combined into [coverTilt] /
     * [leftHalfTilt] (StandBy's frost is a separate, flat overlay — see `standby/StandByMorphOverlay.kt`'s
     * `Modifier.standByFrost`, not the hinge-tilted shader).
     */
    val standByReturnFrost = Animatable(0f)
    /** Phase 0: 0 = the left half sharp and at rest, 1 = held fully frosted and slid out. Read in the left-half layers' blocks. */
    val closeFrost = Animatable(0f)
    var running by mutableStateOf(false)
        private set
    /** True from a cover-frost trigger until it is released or the layout leaves the cover. */
    var coverFrostActive by mutableStateOf(false)
        private set
    /** Bumped for every accepted cover-frost trigger; the compact layout plays when it changes. */
    var coverFrostRequests by mutableIntStateOf(0)
        private set
    /** True from the expanded layout taking a closing-frost trigger until it is released, consumed by the swap or cancelled. */
    var closeFrostActive by mutableStateOf(false)
        private set
    /** Bumped for every accepted closing-frost trigger; the expanded layout plays when it changes. */
    var closeFrostRequests by mutableIntStateOf(0)
        private set
    /**
     * See [leftHalfFrozen]: on from before the first frosted frame of either morph until the
     * half is sharp and in place again. All inputs are snapshot state, so a composable reading
     * this recomposes in the frame that first draws the frost, not one later; derived, so the
     * per-frame progress writes reach readers only when the answer flips.
     */
    private val leftHalfFrozenState = derivedStateOf {
        leftHalfFrozen(running, closeFrostActive, progress.value, closeFrost.value, angleTiltState.floatValue)
    }
    val leftHalfFrozen: Boolean get() = leftHalfFrozenState.value
    /** Host widgets currently shown as snapshots by WidgetSlot, for the logs. */
    private val frozenHostWidgets = mutableSetOf<Int>()

    /** WidgetSlot took a snapshot of host widget [id] ([widthPx] x [heightPx]). */
    fun noteHostWidgetFrozen(id: Int, widthPx: Int, heightPx: Int) {
        frozenHostWidgets += id
        log("froze host widget $id (${widthPx}x${heightPx} px), ${frozenHostWidgets.size} frozen")
    }

    /** WidgetSlot dropped the snapshot of host widget [id] and shows the live view again. */
    fun noteHostWidgetThawed(id: Int) {
        if (frozenHostWidgets.remove(id)) log("thawed host widget $id, ${frozenHostWidgets.size} frozen")
    }
    // ---- angle mode ----
    private val angleTiltState = mutableFloatStateOf(0f)
    /**
     * Angle mode's smoothed tilt (0 = sharp), written on every Pose Engine snapshot (~50 Hz
     * while the magnetometer runs). Read it in a `graphicsLayer` / `foldEffect` block only.
     */
    val angleTilt: State<Float> get() = angleTiltState
    private val angleSmoother = HingeTiltSmoother(tauMs = MorphCurve.ANGLE_TAU_MS, glitchDeg = MorphCurve.ANGLE_GLITCH_DEG,
        holdMs = MorphCurve.ANGLE_GLITCH_HOLD_MS, maxTilt = MorphCurve.MAX_TILT)
    /**
     * "Pacing morphu" (2026-09-17): the inner left half's displayed tilt is the slower of the
     * hand ([angleSmoother]'s output) and this floor, armed at the Cover -> Inner swap
     * ([noteSnapshot]) so the clear is never faster than [MorphCurve.DURATION_MS] once revealed,
     * whatever the angle estimate claims in the meantime. See [TiltPacing]'s own doc for why.
     */
    private val innerTiltPacing = TiltPacing()
    /** Time of the last [noteAngle] sample, for [TiltPacing]'s rise slew `dtMs`; [Long.MIN_VALUE] before the first. */
    private var lastAngleSampleMs = Long.MIN_VALUE
    /** True while the frost is driven by the hinge angle: a valid angle on the inner or cover panel. */
    var angleMode: Boolean = false
        private set
    /** The last angle fed to the smoother (NaN off), for the logs and the replay tests. */
    var lastAngleDeg: Float = Float.NaN
        private set
    private var debugAngleDeg = Float.NaN
    private var debugAngleUntilMs = Long.MIN_VALUE
    private var coverFrostTriggerName: String? = null
    private var coverFrostArmedAtMs = 0L
    private var closeFrostTriggerName: String? = null
    private var closeFrostArmedAtMs = 0L
    private var lastHingeStep: HingeStep? = null
    private var lastMotionSeq = -1
    private var lastClosingSeq = -1
    /**
     * True once the frost shader is compiled and its GPU program has drawn a frame in this
     * process ([MorphWarmUp]), so the next morph's first frame pays neither. Stays false when
     * the shader failed to compile (there is nothing to warm; the morph then runs without frost).
     */
    var warmedUp by mutableStateOf(false)
        private set

    fun noteWarmedUp() { warmedUp = true }
    /** Bumped for every explicit enter request; the layout replays when it changes. */
    var requests by mutableIntStateOf(0)
        private set
    private var lastPanel = Panel.Unknown
    private var pendingEnter = false
    private var pendingCover = false

    // ---- B16: settle once per unfold ----
    private val settleGate = SettleOnceGate()
    /** Bumped exactly once per unfold, the frame the left half reaches Flat (the timed play ending, or [MorphCurve.settleAngleEdge] in angle mode): drives the Today items' staggered settle spring and the "haptic at flat" tick. */
    var settleGeneration by mutableIntStateOf(0)
        private set
    /** The angle-mode tilt last seen on the inner panel, to detect [MorphCurve.settleAngleEdge]. */
    private var lastInnerAngleTilt = 0f

    private fun fireSettle() { if (settleGate.fire()) settleGeneration++ }

    // ---- B17: closing-only tilt for the right pane's seam darken ----
    /** True while the hinge angle's confirmed direction is closing (decreasing); a Schmitt trigger against [MorphCurve.CLOSING_DIRECTION_HYSTERESIS_DEG] so holding the phone still mid-fold (near-zero per-sample deltas) does not read as "opening again". */
    private var closingDirectionActive = false
    private var directionAnchorDeg = Float.NaN
    private val closingAngleTiltState = mutableFloatStateOf(0f)
    /** B17: the left half's tilt attributable to CLOSING only (not opening) — the state/gyro-held closing frost, or angle mode while the hinge angle is decreasing on the inner panel. Read from the right pane's seam-darken draw block. */
    val closingTilt: Float get() = max(MorphCurve.closeFrostTilt(closeFrost.value), closingAngleTiltState.floatValue)
    /**
     * "Zavírání jako Duo" item 2, "never releasing early if the estimate wobbles": the highest
     * paced tilt seen so far in the current closing run, held even if a later sample's angle
     * (mapped through [MorphCurve.closingAngleTilt]) would read as slightly less — a transient
     * wobble in the estimate must not read as the frost receding. Reset to 0 the moment
     * [closingDirectionActive] drops (a real re-open, or the half leaves Inner), so a fresh close
     * starts its own ratchet from 0.
     */
    private var closingPeakTilt = 0f
    /** "Zavírání jako Duo" item 6: one measurement per closing episode, logged under [TAG]. */
    private val closingEpisode = ClosingEpisodeTracker(now)
    private var lastClosingEpisodeStats: ClosingEpisodeStats? = null

    /** `motion`/`integral` while [cz.pflanzer.foldduo.pose.ClosingOnsetDetector] is bridging the angle (its source is encoded in [cz.pflanzer.foldduo.pose.PoseSnapshot.hingeAngleSource] as `onset-motion`/`onset-integral`), `step` otherwise (the plain hinge-step transition owns the onset, same as before this feature). */
    private fun onsetSourceLabel(hingeAngleSource: String): String = when {
        hingeAngleSource.startsWith("onset-motion") -> "motion"
        hingeAngleSource.startsWith("onset-integral") -> "integral"
        else -> "step"
    }

    private fun logClosingEpisode(stats: ClosingEpisodeStats) {
        lastClosingEpisodeStats = stats
        info("closing episode: ${stats.logLine()}")
    }

    /** The last completed closing episode's stats, consumed once (for [cz.pflanzer.foldduo.HighRefreshRate]'s `MorphFrameMetricsCollector.stop`); null if none is pending. */
    fun takeClosingEpisode(): ClosingEpisodeStats? {
        val s = lastClosingEpisodeStats
        lastClosingEpisodeStats = null
        return s
    }

    // ---- B18: the morph's own clock ----
    private var animatorDurationScale = 1f
    /** Read once per resume (`Settings.Global.ANIMATOR_DURATION_SCALE`); every timed fallback below scales its Compose tween by it (see [MorphCurve.scaledDurationMs]). */
    fun noteAnimatorDurationScale(scale: Float) { animatorDurationScale = if (scale.isFinite() && scale > 0f) scale else 1f }

    /**
     * Feed every snapshot; a Cover -> Inner edge arms the enter morph, Inner -> Cover the cover
     * settle (and drops an armed closing frost: the inner panel is off), on the cover a hinge
     * step off Closed or a new opening-motion event arms the cover frost ([coverFrostTrigger]),
     * on the inner panel a hinge step off Flat or a new closing-motion event arms the closing
     * frost ([closeFrostTrigger]).
     */
    fun noteSnapshot(s: PoseSnapshot) {
        val before = lastPanel
        if (s.panel != Panel.Unknown) lastPanel = s.panel
        if (before == Panel.Cover && s.panel == Panel.Inner) {
            // "Pacing morphu": arm the guaranteed-minimum floor on every real swap, from full
            // frost, whatever the angle estimate says at this exact instant (see TiltPacing.kt).
            if (!reduceMotion()) {
                innerTiltPacing.armRelease(now(), MorphCurve.SCREEN_ON_SETTLE_MS,
                    MorphCurve.scaledDurationMs(MorphCurve.DURATION_MS, animatorDurationScale), MorphCurve.MAX_TILT)
            }
            requestEnter()
        }
        if (before == Panel.Inner && s.panel == Panel.Cover) {
            if (closeFrostTriggerName != null) log("close frost: armed trigger=$closeFrostTriggerName dropped (swapped to the cover)")
            closeFrostTriggerName = null
            closingEpisode.end(swapped = true)?.let { logClosingEpisode(it) }
            requestCoverSettle()
        }
        noteAngle(s)
        val step = HingeStep.of(s.hingeDeg)
        val motion = motionFrost()
        val cover = coverFrostTrigger(s.panel, lastHingeStep, step, lastMotionSeq, s.openingMotionSeq, motion)
        val close = closeFrostTrigger(s.panel, lastHingeStep, step, lastClosingSeq, s.closingMotionSeq, motion)
        lastHingeStep = step
        lastMotionSeq = s.openingMotionSeq
        lastClosingSeq = s.closingMotionSeq
        if (cover != null) requestCoverFrost(cover)
        if (close != null) requestCloseFrost(close)
    }

    /**
     * Angle mode's per-sample step: the snapshot's hinge angle (or the debug preview's) mapped
     * through [MorphCurve.angleTilt] for its panel, smoothed, written to [angleTilt]. Mode edges
     * are logged once.
     */
    private fun noteAngle(s: PoseSnapshot) {
        if (reduceMotion()) {
            if (angleMode) { angleMode = false; info("angle mode off (reduce motion) ${describeSnapshot()}") }
            lastAngleDeg = Float.NaN
            closingDirectionActive = false
            directionAnchorDeg = Float.NaN
            closingPeakTilt = 0f
            lastAngleSampleMs = Long.MIN_VALUE
            if (closingAngleTiltState.floatValue != 0f) closingAngleTiltState.floatValue = 0f
            if (angleTiltState.floatValue != 0f) angleTiltState.floatValue = 0f
            lastInnerAngleTilt = 0f
            return
        }
        val nowMs = now()
        val angle = if (nowMs < debugAngleUntilMs) debugAngleDeg else s.hingeAngleDeg
        val on = !angle.isNaN() && (s.panel == Panel.Inner || s.panel == Panel.Cover)
        if (on != angleMode) {
            angleMode = on
            info("angle mode ${if (on) "on" else "off"} (conf=%.2f angle=%s src=%s panel=%s%s)".format(Locale.ROOT, s.hingeAngleConfidence,
                if (angle.isNaN()) "-" else "%.1f".format(Locale.ROOT, angle), s.hingeAngleSource, s.panel,
                if (nowMs < debugAngleUntilMs) " debug" else ""))
        }
        lastAngleDeg = if (on) angle else Float.NaN

        // "Zavírání jako Duo": the closing-direction latch is updated from the raw angle BEFORE
        // shaping the tilt (moved ahead of B17's original post-tilt position), so a confirmed
        // close picks the closing-specific curve ([MorphCurve.closingAngleTilt]) for THIS sample,
        // not one sample late. A Schmitt trigger against the anchor (not the previous sample) so
        // consecutive sensor-rate samples — whose deltas are far smaller than a real fold — and
        // holding the phone still mid-fold both keep the last confirmed direction instead of
        // flickering back to "opening".
        val wasClosing = closingDirectionActive
        if (s.panel == Panel.Inner) {
            if (angle.isNaN()) {
                closingDirectionActive = false; directionAnchorDeg = Float.NaN
            } else if (directionAnchorDeg.isNaN()) {
                directionAnchorDeg = angle
            } else {
                val delta = angle - directionAnchorDeg
                if (delta <= -MorphCurve.CLOSING_DIRECTION_HYSTERESIS_DEG) { closingDirectionActive = true; directionAnchorDeg = angle }
                else if (delta >= MorphCurve.CLOSING_DIRECTION_HYSTERESIS_DEG) { closingDirectionActive = false; directionAnchorDeg = angle }
            }
        }
        val closingNow = s.panel == Panel.Inner && closingDirectionActive
        // Item 6: a fresh close starts an episode; direction flipping back to opening (or the
        // angle dropping out) without ever reaching a swap ends it as cancelled.
        if (closingNow && !wasClosing) closingEpisode.start(onsetSourceLabel(s.hingeAngleSource), angle)
        else if (!closingNow && wasClosing) closingEpisode.end(swapped = false)?.let { logClosingEpisode(it) }

        val shapedTilt = when {
            !on -> 0f
            closingNow -> MorphCurve.closingAngleTilt(angle)
            else -> MorphCurve.angleTilt(s.panel, angle)
        }
        val handTilt = angleSmoother.update(nowMs, lastAngleDeg, shapedTilt)
        // "Pacing morphu": the displayed tilt is never faster than the hand *or* the guaranteed
        // minimum timeline (TiltPacing.kt) — the inner left half's release floor (armed on the
        // Cover -> Inner swap, noteSnapshot) for the clear, a plain rise slew for a frost-up on
        // either panel (the cover while opening, the left half while closing).
        val dtMs = if (lastAngleSampleMs == Long.MIN_VALUE) 0L else (nowMs - lastAngleSampleMs).coerceIn(0L, 1_000L)
        lastAngleSampleMs = nowMs
        val prevDisplayed = angleTiltState.floatValue
        val fullRiseMs = MorphCurve.scaledDurationMs(MorphCurve.RISE_FULL_MS, animatorDurationScale)
        // handTilt already eases to 0 on its own (HingeTiltSmoother) when the angle is off/NaN, so
        // it is never itself NaN here; TiltPacing's own NaN handling is for a caller with no such
        // smoother in between (SystemFrostPlan's raw angle sample).
        val paced = when (s.panel) {
            Panel.Inner -> innerTiltPacing.pace(nowMs, prevDisplayed, handTilt, dtMs, fullRiseMs)
            Panel.Cover -> TiltPacing.riseLimited(prevDisplayed, handTilt, dtMs, fullRiseMs)
            else -> handTilt
        }
        // Item 2, "never releasing early if the estimate wobbles": while closing, the displayed
        // tilt only ever rises (or holds) — see closingPeakTilt's own doc.
        val tilt = if (closingNow) {
            closingPeakTilt = max(closingPeakTilt, paced)
            closingPeakTilt
        } else {
            if (s.panel == Panel.Inner) closingPeakTilt = 0f
            paced
        }
        if (s.panel == Panel.Inner) {
            // B16: the settle fires the moment the angle-driven tilt reaches Flat for real.
            if (MorphCurve.settleAngleEdge(s.panel, lastInnerAngleTilt, tilt, angle)) fireSettle()
            lastInnerAngleTilt = tilt
            // B17: the closing darken only counts the tilt while closingDirectionActive.
            closingAngleTiltState.floatValue = if (closingDirectionActive) tilt else 0f
            if (closingNow) {
                ClosingContinuity.note(tilt)
                closingEpisode.noteTilt(tilt)
            }
        }
        if (tilt != angleTiltState.floatValue) angleTiltState.floatValue = tilt
    }

    /** True when the current snapshot's angle drives [panel]'s frost (angle mode, or the debug preview). */
    private fun angleDrives(panel: Panel): Boolean {
        val s = snapshot()
        val angle = if (now() < debugAngleUntilMs) debugAngleDeg else s.hingeAngleDeg
        return !angle.isNaN() && s.panel == panel
    }

    /** Debug preview: drive angle mode with [angleDeg] for [holdMs], then hand back to the sensor. */
    fun debugAngle(angleDeg: Float, holdMs: Long = MorphCurve.ANGLE_DEBUG_HOLD_MS) {
        debugAngleDeg = angleDeg.coerceIn(0f, 180f)
        debugAngleUntilMs = now() + holdMs
        info("debug angle: ${debugAngleDeg}° for ${holdMs}ms -> tilt ${MorphCurve.angleTilt(snapshot().panel, debugAngleDeg)}° ${describeSnapshot()}")
        noteAngle(snapshot())
    }

    /** For logs: the snapshot fields the triggers look at. */
    fun describeSnapshot(): String = snapshot().let {
        "panel=${it.panel} since=${if (it.msSinceTransition == Long.MAX_VALUE) "-" else "${it.msSinceTransition}ms"} " +
            "hinge=${if (it.hingeDeg.isNaN()) "-" else it.hingeDeg.toInt().toString()} motionSeq=${it.openingMotionSeq} " +
            "closeSeq=${it.closingMotionSeq} angle=${if (it.hingeAngleDeg.isNaN()) "-" else "%.0f".format(Locale.ROOT, it.hingeAngleDeg)} " +
            "angleTilt=%.1f".format(Locale.ROOT, angleTiltState.floatValue)
    }

    /**
     * Arm the cover frost (phase 1) for [trigger] (`hinge`, `gyro`, `debug`). One at a time: a
     * trigger while a frost is armed, rising, held or releasing is logged and dropped.
     */
    fun requestCoverFrost(trigger: String) {
        if (reduceMotion()) {
            log("cover frost: trigger=$trigger ignored (reduce motion) ${describeSnapshot()}")
            return
        }
        if (coverFrostActive || coverFrostTriggerName != null) {
            log("cover frost: trigger=$trigger ignored (already active) ${describeSnapshot()}")
            return
        }
        if (trigger != "debug" && angleDrives(Panel.Cover)) {
            log("cover frost: trigger=$trigger ignored (angle mode drives the cover) ${describeSnapshot()}")
            return
        }
        coverFrostTriggerName = trigger
        coverFrostArmedAtMs = now()
        coverFrostRequests++
    }

    /** The compact layout is up and [coverFrostRequests] changed: the armed trigger, once, if fresh. */
    fun takeCoverFrost(): String? {
        val trigger = coverFrostTriggerName ?: return null
        coverFrostTriggerName = null
        return if (now() - coverFrostArmedAtMs <= MorphCurve.ARMED_TTL_MS) trigger else null
    }

    /**
     * Phase 1: frost the cover over [MorphCurve.COVER_FROST_IN_MS], hold it for the swap, and
     * when none comes within [MorphCurve.COVER_FROST_HOLD_MS] release it over
     * [MorphCurve.COVER_FROST_OUT_MS]. The swap relaunches the activity (or flips the layout),
     * which cancels this and resets the frost; the cover is off by then anyway.
     */
    suspend fun playCoverFrost(trigger: String) {
        coverFrostActive = true
        val startedAt = now()
        info("cover frost: trigger=$trigger ${describeSnapshot()}")
        try {
            coverFrost.snapTo(0f)
            coverFrost.animateTo(1f, tween(MorphCurve.scaledDurationMs(MorphCurve.COVER_FROST_IN_MS, animatorDurationScale), easing = MorphCurve.easing))
            delay(MorphCurve.COVER_FROST_HOLD_MS)
            info("cover frost: released (no swap) after ${now() - startedAt}ms ${describeSnapshot()}")
            coverFrost.animateTo(0f, tween(MorphCurve.scaledDurationMs(MorphCurve.COVER_FROST_OUT_MS, animatorDurationScale), easing = MorphCurve.easing))
        } finally {
            coverFrostActive = false
            withContext(NonCancellable) { coverFrost.snapTo(0f) }
        }
    }

    // ---- phase 0 ----

    /** An armed (not yet taken) closing frost that is still fresh. */
    private fun closeFrostArmed(): Boolean =
        closeFrostTriggerName != null && now() - closeFrostArmedAtMs <= MorphCurve.ARMED_TTL_MS

    /** True while a closing frost is armed or playing: the unfold morph must not start. */
    val closeFrostBusy: Boolean get() = closeFrostActive || closeFrostArmed()

    /**
     * Arm the closing frost (phase 0) for [trigger] (`hinge`, `gyro`, `debug`). One at a time,
     * and never while the unfold morph is running or armed: a trigger then is logged and dropped.
     */
    fun requestCloseFrost(trigger: String) {
        if (reduceMotion()) {
            log("close frost: trigger=$trigger ignored (reduce motion) ${describeSnapshot()}")
            return
        }
        if (closeFrostActive || closeFrostTriggerName != null) {
            log("close frost: trigger=$trigger ignored (already active) ${describeSnapshot()}")
            return
        }
        if (running || (pendingEnter && armedFresh())) {
            log("close frost: trigger=$trigger ignored (unfold morph ${if (running) "running" else "armed"}) ${describeSnapshot()}")
            return
        }
        if (trigger != "debug" && angleDrives(Panel.Inner)) {
            log("close frost: trigger=$trigger ignored (angle mode drives the left half) ${describeSnapshot()}")
            return
        }
        closeFrostTriggerName = trigger
        closeFrostArmedAtMs = now()
        closeFrostRequests++
    }

    /**
     * The expanded layout is up and [closeFrostRequests] changed: the armed trigger, once, if
     * fresh. Taking it marks the frost active at once, so nothing else starts in the gap before
     * [playCloseFrost] runs (which always follows).
     */
    fun takeCloseFrost(): String? {
        val trigger = closeFrostTriggerName ?: return null
        closeFrostTriggerName = null
        if (now() - closeFrostArmedAtMs > MorphCurve.ARMED_TTL_MS) {
            log("close frost: trigger=$trigger dropped (stale) ${describeSnapshot()}")
            return null
        }
        closeFrostActive = true
        return trigger
    }

    /**
     * Phase 0: frost the left half and slide it out over [MorphCurve.CLOSE_FROST_IN_MS], hold it
     * for the swap to the cover, and when none comes release it over
     * [MorphCurve.CLOSE_FROST_OUT_MS]. A hinge trigger holds by state ([CloseFrostHold]: until
     * the hinge step is back at Flat, [MorphCurve.CLOSE_FROST_HOLD_MAX_MS] at most), the others
     * for [MorphCurve.closeFrostTimedHoldMs]. The swap switches the inner panel off and
     * relaunches the activity (or flips the layout), which cancels this; the frost is reset to 0
     * at once.
     */
    suspend fun playCloseFrost(trigger: String) {
        closeFrostActive = true
        val startedAt = now()
        var released = false
        // Item 6: this path only ever starts a "step" episode — angle mode driving the left half
        // takes this trigger before it is even requested (requestCloseFrost's angleDrives check),
        // so whenever this plays, there was no continuous angle (and so no onset bridge) to notice
        // the close any earlier. A closing episode already running (from a later angle-mode read
        // that started after this hinge/gyro trigger fired) is left alone (start() is a no-op).
        closingEpisode.start("step", Float.NaN)
        info("close frost: trigger=$trigger ${describeSnapshot()}")
        try {
            closeFrost.snapTo(0f)
            closeFrost.animateTo(1f, tween(MorphCurve.scaledDurationMs(MorphCurve.CLOSE_FROST_IN_MS, animatorDurationScale), easing = MorphCurve.easing))
            ClosingContinuity.note(MorphCurve.closeFrostTilt(closeFrost.value))
            closingEpisode.noteTilt(MorphCurve.closeFrostTilt(closeFrost.value))
            val timedHold = MorphCurve.closeFrostTimedHoldMs(trigger)
            val reason = if (timedHold != null) { delay(timedHold); "no swap" } else holdWhileHingeOffFlat(startedAt)
            released = true
            info("close frost: released ($reason) after ${now() - startedAt}ms ${describeSnapshot()}")
            closeFrost.animateTo(0f, tween(MorphCurve.scaledDurationMs(MorphCurve.CLOSE_FROST_OUT_MS, animatorDurationScale), easing = MorphCurve.easing))
        } finally {
            closeFrostActive = false
            val swapped = lastPanel == Panel.Cover || snapshot().panel == Panel.Cover
            if (!released) {
                info("close frost: ${if (swapped) "consumed by swap" else "cancelled (layout gone)"} after ${now() - startedAt}ms ${describeSnapshot()}")
            }
            closingEpisode.end(swapped)?.let { logClosingEpisode(it) }
            withContext(NonCancellable) { closeFrost.snapTo(0f) }
        }
    }

    /** The hinge hold: re-reads the pose every [MorphCurve.CLOSE_FROST_HOLD_POLL_MS] until [CloseFrostHold] releases; the reason. */
    private suspend fun holdWhileHingeOffFlat(startedAt: Long): String {
        val hold = CloseFrostHold(startedAt)
        while (true) {
            hold.release(lastPanel, lastHingeStep, now())?.let { return it }
            ClosingContinuity.note(MorphCurve.closeFrostTilt(closeFrost.value))
            delay(MorphCurve.CLOSE_FROST_HOLD_POLL_MS)
        }
    }

    /**
     * Arm the enter morph. [durationMs] is only for the debug replay (a stretched morph can
     * be screenshotted); a real swap always plays at [MorphCurve.DURATION_MS].
     */
    fun requestEnter(durationMs: Int = MorphCurve.DURATION_MS) {
        pendingEnter = true
        armedAtMs = now()
        nextDurationMs = durationMs.coerceIn(1, 10_000)
        requests++
        // The activity is recreated on the panel swap, so this controller may never have seen the
        // cover (noteSnapshot's Cover -> Inner arming misses): arm the guaranteed-minimum floor
        // here too, from full frost. 17. 9. log: floor unarmed -> the left half snapped sharp at
        // +160 ms, then the timed fallback re-frosted it 1.2 s later (a double morph).
        if (durationMs == MorphCurve.DURATION_MS && !reduceMotion() && !innerTiltPacing.armed) {
            innerTiltPacing.armRelease(now(), MorphCurve.SCREEN_ON_SETTLE_MS,
                MorphCurve.scaledDurationMs(MorphCurve.DURATION_MS, animatorDurationScale), MorphCurve.MAX_TILT)
        }
    }
    fun requestCoverSettle() { pendingCover = true; armedAtMs = now(); requests++ }
    /** True while the inner left half's guaranteed-minimum clear (TiltPacing) is still releasing. */
    val innerPacingFloorActive: Boolean get() = innerTiltPacing.floorAt(now()) > 0f
    private var nextDurationMs = MorphCurve.DURATION_MS
    private var armedAtMs = 0L

    /**
     * A swap seen while another app was in front must not replay when the launcher comes back
     * seconds later; an armed request is only honoured for [ARMED_TTL_MS].
     */
    private fun armedFresh(): Boolean = now() - armedAtMs <= MorphCurve.ARMED_TTL_MS

    /**
     * The expanded layout is (first) up: play if a swap just happened or was requested. Never
     * while a closing frost is armed or playing (the two morphs drive the same layers).
     */
    fun shouldEnter(pose: FoldPose): Boolean {
        val s = snapshot()
        if (pendingEnter && !armedFresh()) pendingEnter = false
        val armed = pendingEnter || unfoldJustHappened(pose, s.panel, s.msSinceTransition)
        pendingEnter = false
        pendingCover = false
        if (armed && closeFrostBusy) {
            log("unfold morph: ignored (close frost ${if (closeFrostActive) "active" else "armed"}) ${describeSnapshot()}")
            return false
        }
        // A real swap with a valid angle: the hand drives the clear (angle mode), no timed play.
        // A debug replay (requestEnter with a duration) always plays, on top of angle mode.
        // The pacing floor IS the guaranteed timed clear: while it is still releasing, a second
        // timed play would re-frost a half that is already clearing (angle mode may have dropped
        // out mid-episode when the magnetometer's confidence dipped).
        if (armed && nextDurationMs == MorphCurve.DURATION_MS && (angleDrives(Panel.Inner) || innerTiltPacing.floorAt(now()) > 0f)) {
            info("unfold morph: angle mode + timed floor drives the clear, timed play skipped ${describeSnapshot()}")
            return false
        }
        return armed
    }

    /** The compact layout is (first) up: settle if the cover just took over. */
    fun shouldSettleCover(): Boolean {
        val s = snapshot()
        if (pendingCover && !armedFresh()) pendingCover = false
        val armed = pendingCover || foldJustHappened(s.panel, s.msSinceTransition)
        pendingCover = false
        pendingEnter = false
        return armed
    }

    suspend fun playEnter() {
        val baseDuration = nextDurationMs
        val replay = baseDuration != MorphCurve.DURATION_MS
        nextDurationMs = MorphCurve.DURATION_MS
        if (!replay && reduceMotion()) { playEnterReduced(); return }
        // The debug replay's duration is an explicit override (a stretched morph for a
        // screenshot); B18's correction only applies to the real, fixed-length play.
        val duration = if (replay) baseDuration else MorphCurve.scaledDurationMs(baseDuration, animatorDurationScale)
        running = true
        try {
            progress.snapTo(0f)
            // Real swaps: hold the frosted pane until One UI's screen-on fade is over, then resolve.
            var wait = 0L
            if (!replay) {
                val since = snapshot().msSinceTransition
                wait = if (since == Long.MAX_VALUE) 0L else (MorphCurve.SCREEN_ON_SETTLE_MS - since).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
            }
            info("unfold morph: start after wait=${wait}ms, duration=${duration}ms replay=$replay ${describeSnapshot()}")
            progress.animateTo(1f, tween(duration, easing = MorphCurve.clearEasing))
            // B16: the timed play reaching Flat is one of the two settle triggers (the other is
            // the angle-mode edge in noteAngle); a cancelled play (below) never reaches here.
            fireSettle()
        } finally {
            running = false
            // Cancelled (the layout went away, or a replay restarts it): the layer is reset to
            // rest, so nothing stays half frosted and the host widgets thaw with it.
            if (progress.value < 1f) withContext(NonCancellable) { progress.snapTo(1f) }
        }
    }

    /**
     * B21: the reduced-motion play. No wait for One UI's screen-on fade (reduce motion wants a
     * short, predictable transition, not a frost held for a fade it isn't showing anyway), no
     * easing curve that reads as glass clearing (linear, like a plain crossfade); the layers
     * suppress the shader tilt/blur entirely while [reduceMotion] is on ([noteAngle],
     * [requestCoverFrost], [requestCloseFrost] above), so [progress] here only drives an
     * alpha-style crossfade in the composables that read it, not a frost.
     */
    private suspend fun playEnterReduced() {
        running = true
        try {
            progress.snapTo(0f)
            info("unfold morph: reduce motion crossfade, duration=${MorphCurve.REDUCE_MOTION_CROSSFADE_MS}ms ${describeSnapshot()}")
            progress.animateTo(1f, tween(MorphCurve.REDUCE_MOTION_CROSSFADE_MS, easing = LinearEasing))
            fireSettle()
        } finally {
            running = false
            if (progress.value < 1f) withContext(NonCancellable) { progress.snapTo(1f) }
        }
    }

    /**
     * B17: the cover's reverse settle. When angle mode already drives the cover's tilt (a real,
     * confident hinge angle read on the cover), the hand is doing this already and only the
     * alpha polish plays; otherwise the cover starts frosted and tilted at
     * [MorphCurve.coverSettleStartTilt] and straightens to sharp along [MorphCurve.clearEasing]
     * over [MorphCurve.COVER_SETTLE_MS] (B18-scaled), mirroring the unfold's own clear.
     */
    suspend fun playCoverSettle() {
        if (reduceMotion()) {
            coverProgress.snapTo(1f)
            coverSettleTilt.snapTo(0f)
            info("cover settle: instant (reduce motion) ${describeSnapshot()}")
            return
        }
        coverProgress.snapTo(0f)
        val angleValid = angleDrives(Panel.Cover)
        // Item 4, "continuity": start from whatever frost level the closing inner half ended at
        // (full, by item 2's design) rather than the old dramatized guess — see
        // ClosingContinuity's own doc for why this needs a process-wide stash.
        coverSettleTilt.snapTo(if (angleValid) 0f else ClosingContinuity.takeForCoverSettle(MorphCurve.coverSettleStartTilt()))
        val duration = MorphCurve.scaledDurationMs(MorphCurve.COVER_SETTLE_MS, animatorDurationScale)
        try {
            coroutineScope {
                launch { coverProgress.animateTo(1f, tween(duration, easing = LinearEasing)) }
                if (!angleValid) launch { coverSettleTilt.animateTo(0f, tween(duration, easing = MorphCurve.clearEasing)) }
            }
        } finally {
            withContext(NonCancellable) { coverSettleTilt.snapTo(0f) }
        }
    }

    // ---- B32: the return trip from StandBy ----

    private var standByReturnArmed = false
    /** Bumped when [requestStandByReturn] arms a play; the layout picks it up the same way as the other plays ([takeCloseFrost] etc). */
    var standByReturnRequests by mutableIntStateOf(0)
        private set

    /**
     * StandBy just exited (standby/StandByMorphOverlay.kt's `rememberLauncherStandByFrost`, on
     * [cz.pflanzer.foldduo.standby.StandBySession.showing]'s true -> false edge): arm a clear on
     * whichever panel is active.
     */
    fun requestStandByReturn() { standByReturnArmed = true; standByReturnRequests++ }

    /** The layout is up and [standByReturnRequests] changed: take the armed play, once. */
    fun takeStandByReturn(): Boolean {
        if (!standByReturnArmed) return false
        standByReturnArmed = false
        return true
    }

    /** Snap [standByReturnFrost] to fully frosted and clear it over 300 ms (B18-scaled). */
    suspend fun playStandByReturn() {
        try {
            standByReturnFrost.snapTo(1f)
            standByReturnFrost.animateTo(0f, tween(MorphCurve.scaledDurationMs(300, animatorDurationScale), easing = MorphCurve.clearEasing))
        } finally {
            withContext(NonCancellable) { standByReturnFrost.snapTo(0f) }
        }
    }

    private companion object {
        const val TAG = "FoldDuoMorph"
    }
}

val LocalMorphController = staticCompositionLocalOf<MorphController?> { null }

/**
 * B16's haptic tick, played once per unfold when [MorphController.settleGeneration] changes and
 * the "Haptic at flat" setting is on (AppearanceSettings.kt): a single `EFFECT_TICK` via
 * `VibratorManager` (needs `android.permission.VIBRATE`). A missing service or an unsupported
 * predefined effect is swallowed — this is decoration, not required feedback.
 */
fun performFlatTick(context: android.content.Context) {
    Haptics.play(context, HapticEvent.FLAT)
}

/** One target pane size (dp) to warm the frost's RenderEffect/layer path for, B43 step 2. */
private data class WarmPane(val label: String, val widthDp: Float, val heightDp: Float)

/**
 * The two sizes the frost actually runs at (CONTEXT.md, measured on this Fold 8 13. 9. 2026,
 * dp @360): the cover pane and the inner panel's left half (the frost's real canvas — see
 * ExpandedWorkspace's `leftHalfWidth` in LauncherScreen.kt, not the whole inner panel). A GPU
 * runtime-effect program itself compiles once regardless of the destination size, but the first
 * draw at a *new* destination size can still pay for a new backing-store allocation on the render
 * thread; warming both up front means neither pane's first real morph frame is the first frame at
 * that size.
 */
private val WARM_PANES = listOf(
    WarmPane("cover", 555f, 876f),
    WarmPane("inner-left-half", 544f, 821f),
)

/**
 * Warm-up of the morph's render path, once per process (Fáze 3 measurement: the first ~100 ms
 * of a morph hitched on the RenderEffect/layer setup and, once per process, the AGSL compile;
 * the panel swap relaunches the activity, so a per-activity warm-up would land on the morph
 * itself). [MorphWarmUp] composes nothing until the first frame has gone out, then compiles
 * every `res/raw` shader this app ships ([ShaderRegistry], discovered via `R.raw` reflection so a
 * shader another agent adds — B37's sheet-bend shader, say — is warmed here without touching this
 * file) off the main thread and draws one throwaway frame of a 2×2 dp box per shader per
 * [WARM_PANES] size, so HWUI compiles the GPU program and initialises the RenderEffect/layer path
 * at both real destination sizes while nothing is animating. `fold_morph` gets its real uniforms
 * (via [FoldShader.setUniforms], at the morph's start tilt); a shader whose uniform contract this
 * file doesn't know (anything found by the registry other than `fold_morph`) is still compiled
 * and still drawn, just with its default (zero) uniforms — that already exercises the compile and
 * the first-draw allocation, the two costs this warm-up exists to move off the morph's own first
 * frame. The boxes sit at the window origin (under the status bar) with a 2 % white fill; that is
 * invisible in practice.
 */
object MorphWarmUp {
    private const val TAG = "FoldDuoMorph"
    private const val FOLD_MORPH_NAME = "fold_morph"

    /** Set once the throwaway frames have been drawn (or no shader compiled) in this process. */
    @Volatile
    var doneInProcess = false
        private set

    @Composable
    operator fun invoke(morph: MorphController) {
        val context = LocalContext.current
        var draw by remember { mutableStateOf(false) }
        LaunchedEffect(morph) {
            if (doneInProcess) {
                if (FoldShader.sharedIfReady() != null) morph.noteWarmedUp()
                Log.i(TAG, "warm-up: already done in this process (shaders=${ShaderRegistry.compiledIfReady().keys})")
                return@LaunchedEffect
            }
            withFrameNanos { } // the first real frame goes out untouched
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val compiled = withContext(Dispatchers.Default) { ShaderRegistry.compileAll(context) }
            compiled.forEach { Log.i(TAG, "warm-up: shader '${it.name}' compiled in ${it.compileMs} ms") }
            if (compiled.none { it.name == FOLD_MORPH_NAME }) {
                doneInProcess = true
                Log.w(TAG, "warm-up: fold_morph shader unavailable, morph plays without frost")
                return@LaunchedEffect
            }
            draw = true
            withFrameNanos { } // the frame that composes and draws the boxes
            withFrameNanos { } // their display lists have reached the render thread
            draw = false
            doneInProcess = true
            morph.noteWarmedUp()
            Log.i(TAG, "warm-up: ${compiled.size} shader(s) compiled, throwaway frames drawn at " +
                "${WARM_PANES.joinToString { it.label }}, ${android.os.SystemClock.elapsedRealtime() - startedAt} ms after the first frame")
        }
        if (draw) {
            val density = LocalDensity.current
            val pxPerMm = remember(context) { FoldShader.pxPerMm(context) }
            val config = remember { FoldConfig() }
            val shaders = remember { ShaderRegistry.compiledIfReady().entries.toList() }
            Column {
                shaders.forEach { (name, shader) ->
                    WARM_PANES.forEach { pane ->
                        key(name, pane.label) {
                            val wPx = with(density) { pane.widthDp.dp.toPx() }
                            val hPx = with(density) { pane.heightDp.dp.toPx() }
                            Box(Modifier.size(2.dp).graphicsLayer {
                                runCatching {
                                    if (name == FOLD_MORPH_NAME) {
                                        val line = FoldShader.centeredFold(wPx, hPx, config.foldSplitsLong)
                                        FoldShader.setUniforms(shader, wPx, hPx, MorphCurve.startTilt, config, pxPerMm, line)
                                    }
                                    renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
                                        .asComposeRenderEffect()
                                    clip = true
                                }.onFailure { Log.w(TAG, "warm-up: shader '$name' at ${pane.label} failed: ${it.message}") }
                            }.background(Color.White.copy(alpha = 0.02f)))
                        }
                    }
                }
            }
        }
    }
}
