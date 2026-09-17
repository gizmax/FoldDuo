package cz.pflanzer.foldduo

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * B36 "Haptický jazyk": one named vocabulary of touch across the launcher, instead of each
 * feature picking its own [android.view.HapticFeedbackConstants] or [android.os.VibrationEffect]
 * ad hoc (the pre-B36 call sites: [performFlatTick] in UnfoldMorph.kt for B16's "Haptic at flat",
 * and `AlphabetIndexRail`'s `CLOCK_TICK` in AppLibrary.kt). Every interaction that used to reach
 * for a raw constant now names one of these instead.
 */
enum class HapticEvent {
    PAGE_SETTLE, ICON_PICKUP, ICON_DROP, FOLDER_OPEN, FOLDER_CLOSE, STACK_FLIP,
    ISLAND_EXPAND, ISLAND_DISMISS, INDEX_TICK, FLAT, STANDBY_ENTER, STANDBY_LEAVE,
    SPOTLIGHT_OPEN, PAGE_ADDED,
    /** 2026-09-17 noc "Mazání stránek s dotazem": a page actually being deleted (edit mode's "×",
     * the page overview's trash, or "Odebrat stránku"), whether it was empty or confirmed. */
    PAGE_REMOVED,
    /** B39 "Přehled stránek": the pinch-out/long-press overview opening and its dismissal. */
    OVERVIEW_OPEN, OVERVIEW_CLOSE,
    /** B42 "Paleta ze švu": the edge-drag panel committing open and closing again. */
    PALETTE_OPEN, PALETTE_CLOSE,
    /** B42 "Dosah na coveru": the pull-down committing into its held-down position. */
    REACHABILITY_SNAP,
    /** B48 "Pant jako ovladač": a hinge squeeze being recognized (firm — confirms the gesture, not decoration on an animation) and released (light — the overlay is simply going away). */
    SQUEEZE_RECOGNIZED, SQUEEZE_RELEASE,
    /** 2026-09-17 evening "Kontextové menu a režim úprav": the icon popover opening (a lighter
     * tap than [ICON_PICKUP], which is now reserved for an actual drag starting) and Home edit
     * ("jiggle") mode being entered/left. */
    ICON_POPOVER, EDIT_MODE_ENTER, EDIT_MODE_EXIT,
}

/** Only [HapticEvent.FLAT] and the StandBy transitions survive "reduce motion" (MotionPrefs.kt):
 * they confirm a physical state change (the phone settling flat, StandBy starting or ending), not
 * decoration on top of an animation that reduce motion has already turned off. */
private val REDUCE_MOTION_SURVIVORS = setOf(HapticEvent.FLAT, HapticEvent.STANDBY_ENTER, HapticEvent.STANDBY_LEAVE)

/** "Haptics" setting in Appearance (end of the list): a global amplitude scale, `OFF` muting
 * everything (including [HapticEvent.FLAT] and StandBy, unlike reduce motion, which spares those
 * two — this is a deliberate "no vibration at all" switch, that one is "no decoration"). */
enum class HapticIntensity(val amplitudeScale: Float) { OFF(0f), LIGHT(0.6f), NORMAL(1.0f), STRONG(1.3f) }

/** One primitive of a `VibrationEffect.Composition`, named independently of the Android SDK
 * constant so [HapticPlanner] stays plain Kotlin and JVM-testable without Robolectric (same
 * reasoning as [ReduceMotionLogic] / `SystemFrostPlan`). */
enum class HapticPrimitive { TICK, CLICK, LOW_TICK, THUD, SPIN, QUICK_RISE, SLOW_RISE, QUICK_FALL }

/** A composition step: [primitive] at [amplitude] (already intensity-scaled and clamped to
 * `[0,1]` by [HapticPlanner]), optionally after [delayMs] once the previous step ends. */
data class HapticStep(val primitive: HapticPrimitive, val amplitude: Float, val delayMs: Int = 0)

/** Fallback for a device without full primitive support: one of `VibrationEffect`'s predefined
 * effects. */
enum class HapticPredefined { TICK, CLICK, HEAVY_CLICK }

/** Last-resort fallback: a [android.view.HapticFeedbackConstants] played through a [View], for a
 * device with no usable [android.os.Vibrator] service at all. */
enum class HapticConstant { CLOCK_TICK, LONG_PRESS, CONTEXT_CLICK, CONFIRM, REJECT }

/** Which of the three vibration APIs this device supports, richest first (checked once per
 * process by [Haptics], logged under `FoldDuoHaptics`). */
enum class HapticTier { PRIMITIVES, PREDEFINED, CONSTANTS }

/** What [Haptics] actually plays for one call: `null` (from [HapticPlanner.plan]) means silent. */
sealed class HapticPlan {
    data class Primitives(val steps: List<HapticStep>) : HapticPlan()
    data class Predefined(val effect: HapticPredefined) : HapticPlan()
    data class Constant(val constant: HapticConstant) : HapticPlan()
}

/** Base plan per event at [HapticIntensity.NORMAL]'s amplitudes, plus its predefined- and
 * constants-tier equivalents. The explicit assignments from IDEAS.md B36 are pageSettle
 * (LOW_TICK .5), iconPickup (QUICK_RISE .6), iconDrop (THUD .8), folderOpen (SLOW_RISE .5 +
 * TICK), stackFlip (SPIN .6), standByEnter (SLOW_RISE .7), standByLeave (QUICK_FALL .5); the rest
 * (folderClose, islandExpand/Dismiss, indexTick, flat, spotlightOpen, pageAdded) are this file's
 * own choices, picked to sit in the same weight class as their nearest sibling (folderClose
 * mirrors folderOpen's rise with a fall; islandDismiss/indexTick are the lightest taps in the
 * vocabulary since they can repeat quickly). */
private data class HapticSpec(val steps: List<HapticStep>, val predefined: HapticPredefined, val constant: HapticConstant)

private val HAPTIC_SPECS: Map<HapticEvent, HapticSpec> = mapOf(
    HapticEvent.PAGE_SETTLE to HapticSpec(
        listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.5f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    HapticEvent.ICON_PICKUP to HapticSpec(
        listOf(HapticStep(HapticPrimitive.QUICK_RISE, 0.6f)), HapticPredefined.TICK, HapticConstant.LONG_PRESS),
    HapticEvent.ICON_DROP to HapticSpec(
        listOf(HapticStep(HapticPrimitive.THUD, 0.8f)), HapticPredefined.HEAVY_CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.FOLDER_OPEN to HapticSpec(
        listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.5f), HapticStep(HapticPrimitive.TICK, 0.4f, delayMs = 30)),
        HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.FOLDER_CLOSE to HapticSpec(
        listOf(HapticStep(HapticPrimitive.QUICK_FALL, 0.5f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    HapticEvent.STACK_FLIP to HapticSpec(
        listOf(HapticStep(HapticPrimitive.SPIN, 0.6f)), HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.ISLAND_EXPAND to HapticSpec(
        listOf(HapticStep(HapticPrimitive.CLICK, 0.5f)), HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.ISLAND_DISMISS to HapticSpec(
        listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.4f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    HapticEvent.INDEX_TICK to HapticSpec(
        listOf(HapticStep(HapticPrimitive.TICK, 0.3f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    HapticEvent.FLAT to HapticSpec(
        listOf(HapticStep(HapticPrimitive.TICK, 0.5f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    HapticEvent.STANDBY_ENTER to HapticSpec(
        listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.7f)), HapticPredefined.HEAVY_CLICK, HapticConstant.CONFIRM),
    HapticEvent.STANDBY_LEAVE to HapticSpec(
        listOf(HapticStep(HapticPrimitive.QUICK_FALL, 0.5f)), HapticPredefined.CLICK, HapticConstant.REJECT),
    HapticEvent.SPOTLIGHT_OPEN to HapticSpec(
        listOf(HapticStep(HapticPrimitive.CLICK, 0.6f)), HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.PAGE_ADDED to HapticSpec(
        listOf(HapticStep(HapticPrimitive.TICK, 0.5f), HapticStep(HapticPrimitive.CLICK, 0.4f, delayMs = 40)),
        HapticPredefined.CLICK, HapticConstant.CONFIRM),
    // Mirrors PAGE_ADDED's rise with a fall instead — same relationship as FOLDER_OPEN/FOLDER_CLOSE.
    HapticEvent.PAGE_REMOVED to HapticSpec(
        listOf(HapticStep(HapticPrimitive.QUICK_FALL, 0.5f), HapticStep(HapticPrimitive.LOW_TICK, 0.4f, delayMs = 30)),
        HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    // B39/B42 follow-up: reuses the closest existing pair's exact steps rather than inventing a
    // new weight class — an overview/palette panel opening and closing is the same shape of
    // interaction as the rail island expanding/dismissing and a folder opening/closing.
    HapticEvent.OVERVIEW_OPEN to HapticSpec(
        listOf(HapticStep(HapticPrimitive.CLICK, 0.5f)), HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.OVERVIEW_CLOSE to HapticSpec(
        listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.4f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    HapticEvent.PALETTE_OPEN to HapticSpec(
        listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.5f), HapticStep(HapticPrimitive.TICK, 0.4f, delayMs = 30)),
        HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.PALETTE_CLOSE to HapticSpec(
        listOf(HapticStep(HapticPrimitive.QUICK_FALL, 0.5f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    // Same weight as pageSettle: a spring committing into a held position, not a state change.
    HapticEvent.REACHABILITY_SNAP to HapticSpec(
        listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.5f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    // B48: recognized is a firm confirmation (same weight class as folderOpen/paletteOpen's
    // rise+tick — the gesture just "clicked into place"), release is the lightest tap in the
    // vocabulary (islandDismiss/indexTick's class — the overlay simply going away, not a state
    // change worth confirming on its own).
    HapticEvent.SQUEEZE_RECOGNIZED to HapticSpec(
        listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.6f), HapticStep(HapticPrimitive.TICK, 0.5f, delayMs = 30)),
        HapticPredefined.HEAVY_CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.SQUEEZE_RELEASE to HapticSpec(
        listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.4f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    // Lighter than iconPickup (a real drag starting): the popover is a menu appearing, not
    // something physically picked up.
    HapticEvent.ICON_POPOVER to HapticSpec(
        listOf(HapticStep(HapticPrimitive.TICK, 0.4f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
    // Same weight class as folderOpen/folderClose: the whole Home surface changing mode.
    HapticEvent.EDIT_MODE_ENTER to HapticSpec(
        listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.5f), HapticStep(HapticPrimitive.TICK, 0.4f, delayMs = 30)),
        HapticPredefined.CLICK, HapticConstant.CONTEXT_CLICK),
    HapticEvent.EDIT_MODE_EXIT to HapticSpec(
        listOf(HapticStep(HapticPrimitive.QUICK_FALL, 0.5f)), HapticPredefined.TICK, HapticConstant.CLOCK_TICK),
)

/**
 * Pure decision: what to play for [event] at [intensity] on a device offering [tier], given
 * whether "reduce motion" is on. `null` means silent — [Haptics] is the only caller that owns a
 * real [android.os.Vibrator] or [View]; this object has no Android import and is JVM-testable.
 */
object HapticPlanner {
    fun plan(event: HapticEvent, intensity: HapticIntensity, tier: HapticTier, reduceMotion: Boolean): HapticPlan? {
        if (reduceMotion && event !in REDUCE_MOTION_SURVIVORS) return null
        if (intensity == HapticIntensity.OFF) return null
        val spec = HAPTIC_SPECS.getValue(event)
        return when (tier) {
            HapticTier.PRIMITIVES -> HapticPlan.Primitives(spec.steps.map {
                it.copy(amplitude = (it.amplitude * intensity.amplitudeScale).coerceIn(0f, 1f))
            })
            HapticTier.PREDEFINED -> HapticPlan.Predefined(spec.predefined)
            HapticTier.CONSTANTS -> HapticPlan.Constant(spec.constant)
        }
    }
}

/** Never more than one haptic per [minIntervalMs] (B36 rate limiting), across every event — a
 * single shared instance in [Haptics], not one per event. [allow] is the only mutable state;
 * pure and JVM-testable given an injected clock. The initial `lastFiredAt` is `MIN_VALUE / 2`,
 * not `MIN_VALUE`, so the very first `nowMs - lastFiredAt` cannot overflow (the same class of bug
 * STATUS.md's hinge-angle gate hit with `now - Long.MIN_VALUE`). */
class HapticRateLimiter(private val minIntervalMs: Long = HAPTIC_MIN_INTERVAL_MS) {
    private var lastFiredAt = Long.MIN_VALUE / 2
    @Synchronized
    fun allow(nowMs: Long): Boolean {
        if (nowMs - lastFiredAt < minIntervalMs) return false
        lastFiredAt = nowMs
        return true
    }
}

const val HAPTIC_MIN_INTERVAL_MS = 60L

// --- Android adapter --------------------------------------------------------------------

/**
 * The Android glue: resolves this device's [HapticTier] once per process (logged under
 * `FoldDuoHaptics`), reads the "Haptics" intensity and reduce-motion signals, rate-limits, and
 * plays whatever [HapticPlanner] returns. Every call is decoration, never required feedback, so
 * failures are swallowed (same convention as [performFlatTick]).
 */
object Haptics {
    private const val TAG = "FoldDuoHaptics"
    private const val PREFS_NAME = "appearance"
    private const val KEY_INTENSITY = "hapticIntensity"

    private val rateLimiter = HapticRateLimiter()
    @Volatile private var cachedTier: HapticTier? = null

    /** The "Haptics" setting (Appearance, end of the list): `OFF`/`LIGHT`/`NORMAL`/`STRONG`, `NORMAL` by default. */
    fun intensity(context: Context): HapticIntensity {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_INTENSITY, null)
        return raw?.let { name -> runCatching { HapticIntensity.valueOf(name) }.getOrNull() } ?: HapticIntensity.NORMAL
    }

    fun setIntensity(context: Context, value: HapticIntensity) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_INTENSITY, value.name).apply()
    }

    private fun vibrator(context: Context) = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()

    private val ALL_PRIMITIVES = intArrayOf(
        VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_THUD,
        VibrationEffect.Composition.PRIMITIVE_SPIN, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
        VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)

    private fun tierFor(context: Context, vibrator: android.os.Vibrator?): HapticTier {
        cachedTier?.let { return it }
        val allPrimitivesSupported = vibrator != null && runCatching { vibrator.areAllPrimitivesSupported(*ALL_PRIMITIVES) }.getOrDefault(false)
        val hasVibrator = vibrator != null && runCatching { vibrator.hasVibrator() }.getOrDefault(false)
        val tier = when {
            allPrimitivesSupported -> HapticTier.PRIMITIVES
            hasVibrator -> HapticTier.PREDEFINED
            else -> HapticTier.CONSTANTS
        }
        cachedTier = tier
        Log.i(TAG, "device haptic tier: $tier")
        return tier
    }

    /** Logs [tierFor] once, at startup ([MainActivity.onCreate]) rather than waiting for the
     * first interaction, so the tier line is always in the log even on a session with no touches. */
    fun logTierOnce(context: Context) {
        if (cachedTier != null) return
        tierFor(context, vibrator(context))
    }

    /** This device's [HapticTier] (resolved once per process, same cache [logTierOnce] warms) —
     * read by the Motion & haptics settings page's status line. */
    fun tier(context: Context): HapticTier = tierFor(context, vibrator(context))

    /**
     * Play [event] now, unless the rate limiter, "Haptics" intensity, or reduce motion silences
     * it (see [HapticPlanner]). [view] is only needed for the [HapticTier.CONSTANTS] fallback
     * (a device with no usable [android.os.Vibrator]); a plain-Activity caller with no composable
     * view handy (StandByActivity) can omit it; on real hardware (minSdk 33 always has a
     * `VibratorManager`) that tier is never reached.
     */
    fun play(context: Context, event: HapticEvent, view: View? = null) {
        if (!rateLimiter.allow(SystemClock.elapsedRealtime())) return
        val vibrator = vibrator(context)
        val tier = tierFor(context, vibrator)
        val plan = HapticPlanner.plan(event, intensity(context), tier, MotionPrefs.readReduceMotion(context)) ?: return
        runCatching {
            when (plan) {
                is HapticPlan.Primitives -> vibrator?.let { v ->
                    val composition = VibrationEffect.startComposition()
                    plan.steps.forEach { composition.addPrimitive(it.primitive.toAndroid(), it.amplitude, it.delayMs) }
                    v.vibrate(composition.compose())
                }
                is HapticPlan.Predefined -> vibrator?.vibrate(VibrationEffect.createPredefined(plan.effect.toAndroid()))
                is HapticPlan.Constant -> view?.performHapticFeedback(plan.constant.toAndroid())
            }
        }
    }
}

private fun HapticPrimitive.toAndroid(): Int = when (this) {
    HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
    HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
    HapticPrimitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    HapticPrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
    HapticPrimitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
    HapticPrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    HapticPrimitive.SLOW_RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
    HapticPrimitive.QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
}

private fun HapticPredefined.toAndroid(): Int = when (this) {
    HapticPredefined.TICK -> VibrationEffect.EFFECT_TICK
    HapticPredefined.CLICK -> VibrationEffect.EFFECT_CLICK
    HapticPredefined.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
}

private fun HapticConstant.toAndroid(): Int = when (this) {
    HapticConstant.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
    HapticConstant.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
    HapticConstant.CONTEXT_CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticConstant.CONFIRM -> HapticFeedbackConstants.CONFIRM
    HapticConstant.REJECT -> HapticFeedbackConstants.REJECT
}

/** One-line wiring for a Compose call site: `val haptics = rememberHapticPlayer()`, then
 * `haptics(HapticEvent.ICON_PICKUP)`. Captures [LocalContext] and [LocalView] once. */
@Composable
fun rememberHapticPlayer(): (HapticEvent) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) { { event: HapticEvent -> Haptics.play(context, event, view) } }
}
