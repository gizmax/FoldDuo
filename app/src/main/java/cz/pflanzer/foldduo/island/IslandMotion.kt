package cz.pflanzer.foldduo.island

import cz.pflanzer.foldduo.RAIL_GAP_DP
import cz.pflanzer.foldduo.RAIL_ISLAND_ITEM_HEIGHT_DP
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

/**
 * Pure motion rules for the rail island's Dynamic-Island-like animation (IDEAS.md B30, "Ostrůvek
 * s pružinou"). No Android or Compose types here so the state machine, the dismiss threshold and
 * the digit-roll splitting are plain JVM units; [cz.pflanzer.foldduo.island.RailIsland] is the
 * framework-facing half that turns these decisions into `animateDpAsState`/`Animatable` springs,
 * `AnimatedContent` transitions and gesture handling.
 */

/** Which shape the island currently holds: nothing, a stack of pills, or the long-press card. */
enum class IslandVisualState { EMPTY, COMPACT, EXPANDED }

/**
 * The island's shape for a given item set and expansion request: [IslandVisualState.EMPTY] with
 * no live items regardless of [expandedKey] (a stale key from an item that just disappeared is
 * not enough to hold the card open), [IslandVisualState.EXPANDED] only while [expandedKey] names
 * an item still present in [items], [IslandVisualState.COMPACT] otherwise.
 */
fun islandVisualState(items: List<IslandItem>, expandedKey: String?): IslandVisualState = when {
    items.isEmpty() -> IslandVisualState.EMPTY
    expandedKey != null && items.any { it.key == expandedKey } -> IslandVisualState.EXPANDED
    else -> IslandVisualState.COMPACT
}

/**
 * True once an expanded card has been open for [timeoutMs] without a touch resetting it; the
 * caller restarts its own timer on every open (see `ISLAND_EXPANDED_TIMEOUT_MS` in RailIsland.kt).
 */
fun islandExpandTimedOut(expandedAtMs: Long, nowMs: Long, timeoutMs: Long): Boolean =
    nowMs - expandedAtMs >= timeoutMs

/**
 * An expanded card whose item vanished from the feed (notification cleared, media session ended)
 * must fold back on its own rather than linger on the last known content.
 */
fun islandShouldAutoCollapse(expandedKey: String?, items: List<IslandItem>): Boolean =
    expandedKey != null && items.none { it.key == expandedKey }

/**
 * Whether the whole island's front slot content should be treated as "the same item still
 * updating" ([IslandContentTransition.UPDATE], number-roll / fade territory) or "a different
 * activity took over the slot" ([IslandContentTransition.REPLACE], the vertical cross-slide from
 * B30 item 3 — old headline up, new one in from below). [IslandContentTransition.FIRST] is the
 * initial appearance, with no "old" content to animate away from.
 */
enum class IslandContentTransition { FIRST, UPDATE, REPLACE }

fun islandContentTransition(previousKey: String?, currentKey: String): IslandContentTransition = when {
    previousKey == null -> IslandContentTransition.FIRST
    previousKey == currentKey -> IslandContentTransition.UPDATE
    else -> IslandContentTransition.REPLACE
}

/**
 * Calls and turn-by-turn navigation stay pinned (an active call or route should not vanish from
 * a stray swipe); everything else — timers, progress, workouts, plain Live Updates, and any
 * transport notification that is not backed by a live media session — can be dismissed. A media
 * session has no notification to cancel, so it is excluded too (swiping it away would do nothing
 * and the pill would just reappear on the next publish).
 */
fun islandItemDismissible(kind: IslandKind): Boolean =
    kind != IslandKind.CALL && kind != IslandKind.NAVIGATION && kind != IslandKind.MEDIA

/** Fraction of the pill's width a drag must cross to count as a dismiss, absent a fast flick. */
const val ISLAND_DISMISS_THRESHOLD_FRACTION = 0.4f

/** A flick at or above this speed (px/s), in the direction already dragged, dismisses early. */
const val ISLAND_DISMISS_VELOCITY_THRESHOLD_PX = 900f

/**
 * Whether a horizontal drag on a pill should dismiss it: either it has already crossed
 * [thresholdFraction] of [widthPx], or it was flung at [velocityThreshold] or more in the same
 * direction it was already moving (a fast short flick before crossing the distance still
 * counts; a fling in the opposite direction of a small drag does not). A pill with no measured
 * width (not yet laid out) never counts as dismissed.
 */
fun islandSwipeDismissed(
    offsetPx: Float,
    widthPx: Float,
    velocityPx: Float,
    thresholdFraction: Float = ISLAND_DISMISS_THRESHOLD_FRACTION,
    velocityThreshold: Float = ISLAND_DISMISS_VELOCITY_THRESHOLD_PX,
): Boolean {
    if (widthPx <= 0f) return false
    if (abs(offsetPx) >= widthPx * thresholdFraction) return true
    return abs(velocityPx) >= velocityThreshold && offsetPx != 0f && sign(offsetPx) == sign(velocityPx)
}

/** One character of a chronometer/label transition: the digit sliding out (null = freshly grown) and the one sliding in. */
data class DigitRollChar(val old: Char?, val new: Char)

/**
 * Splits a chronometer string change into per-character roll transitions (B30 item 3, "number
 * roll for chronometers"). Aligned from the right so a width change ("9:59" -> "10:00") grows a
 * fresh leading digit instead of misaligning every character that follows it; a character whose
 * old and new value are equal needs no visual roll (the caller can skip animating it).
 */
fun digitRollDiff(previous: String?, current: String): List<DigitRollChar> {
    if (previous == null) return current.map { DigitRollChar(null, it) }
    val pad = current.length - previous.length
    return current.mapIndexed { index, ch -> DigitRollChar(previous.getOrNull(index - pad), ch) }
}

/** Idle breathing period (B30 item 4): one 2 % scale cycle every 4 s while a chronometer runs. */
const val ISLAND_BREATHING_PERIOD_MS = 4_000L

/** Peak scale deviation of the idle breathing animation. */
const val ISLAND_BREATHING_AMPLITUDE = 0.02f

/**
 * Breathing runs only while something is actively counting (an idle progress pill should not
 * pulse) and only when the system has not asked for reduced motion
 * ([android.provider.Settings.Global.ANIMATOR_DURATION_SCALE] `== 0`, read by the caller; passed
 * in here as a plain float so this stays a JVM unit).
 */
fun islandBreathingEnabled(hasActiveChronometer: Boolean, animatorDurationScale: Float): Boolean =
    hasActiveChronometer && animatorDurationScale > 0f

/**
 * The breathing scale factor at [elapsedMs] into the cycle: oscillates between
 * `1 - amplitude` and `1 + amplitude` with period [periodMs], starting and passing through `1`
 * at `elapsedMs == 0` (a fresh chronometer never starts mid-breath).
 */
fun islandBreathingScale(elapsedMs: Long, periodMs: Long = ISLAND_BREATHING_PERIOD_MS, amplitude: Float = ISLAND_BREATHING_AMPLITUDE): Float {
    if (periodMs <= 0L) return 1f
    val phase = (elapsedMs % periodMs).toDouble() / periodMs.toDouble()
    return (1.0 + amplitude * sin(phase * 2.0 * Math.PI)).toFloat()
}

// --- "Ostrůvek do plochy" (17. 9. večer): the pill grows into the page ---------------------

/**
 * Anchor geometry the root-level expanded overlay ([cz.pflanzer.foldduo.island.RailIsland]'s
 * `IslandExpandedOverlay`) needs, hoisted out of `LauncherScreen.kt`'s own rail layout math
 * ([cz.pflanzer.foldduo.railLayout]) so the card can render as a sibling of the whole rail column
 * instead of nested inside its width-constrained box (the reason a media card's text used to wrap
 * badly — Tom's feedback, 17. 9.). [islandTopDp] is the topmost pill's y, in the same window-dp
 * space as [islandCardRect]'s `pillTopDp`; [pillWidthDp] is the pill's own measured width, the
 * card's morph-from size; [paneStartDp]/[paneWidthDp] bound the pane the card must stay inside —
 * the seam to the window's right edge on the inner display, or the whole screen on the cover
 * ([isCover] then also lifts the [ISLAND_CARD_MAX_WIDTH_DP] cap).
 */
data class IslandCardAnchor(
    val islandTopDp: Float,
    val pillWidthDp: Float,
    val paneStartDp: Float,
    val paneWidthDp: Float,
    val isCover: Boolean,
)

/** Top of the pill at [index] (0-based, rail order) among the collapsed stack, sharing [RailIsland]'s own vertical rhythm. */
fun islandPillTopDp(islandTopDp: Float, index: Int): Float =
    islandTopDp + index * (RAIL_ISLAND_ITEM_HEIGHT_DP + RAIL_GAP_DP)

/** Margin the expanded card keeps from the seam (inner) or the screen edge (cover) on the side opposite the rail anchor. */
const val ISLAND_CARD_MARGIN_DP = 16f

/** Width cap on the inner display only — a media card should not stretch across a wide inner pane. */
const val ISLAND_CARD_MAX_WIDTH_DP = 420f

/**
 * Card heights by content (Apple Music widget redesign, 17. 9. noc): a media session needs room
 * for the Now-Playing-style layout (96 dp artwork + progress bar + transport row) — 160 dp on the
 * inner display, 152 dp on the cover (its card is already full screen width, so it needs less
 * height to read as a widget); everything else is still one compact block.
 */
const val ISLAND_CARD_MEDIA_HEIGHT_INNER_DP = 160f
const val ISLAND_CARD_MEDIA_HEIGHT_COVER_DP = 152f
const val ISLAND_CARD_DEFAULT_HEIGHT_DP = 96f

/** `min(paneWidthDp - 2 * margin, capDp)`, never negative — the card's width for a given pane. */
fun islandCardWidthDp(paneWidthDp: Float, capDp: Float): Float =
    minOf(paneWidthDp - 2f * ISLAND_CARD_MARGIN_DP, capDp).coerceAtLeast(0f)

/** [ISLAND_CARD_MEDIA_HEIGHT_INNER_DP]/[ISLAND_CARD_MEDIA_HEIGHT_COVER_DP] for a live media session (by pane), [ISLAND_CARD_DEFAULT_HEIGHT_DP] for every other kind. */
fun islandCardHeightDp(hasMedia: Boolean, isCover: Boolean): Float = when {
    hasMedia && isCover -> ISLAND_CARD_MEDIA_HEIGHT_COVER_DP
    hasMedia -> ISLAND_CARD_MEDIA_HEIGHT_INNER_DP
    else -> ISLAND_CARD_DEFAULT_HEIGHT_DP
}

/** The expanded card's target rect: same top as the pill it grew from, its own right edge fixed at the rail's. */
data class IslandCardRect(val topDp: Float, val leftDp: Float, val widthDp: Float, val heightDp: Float) {
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
}

/**
 * Builds the expanded card's rect (task spec: "anchored to the pill's position — same top, right
 * edge = the rail's right edge — but extends LEFT over the home page"). The width already keeps
 * [ISLAND_CARD_MARGIN_DP] clear of [IslandCardAnchor.paneStartDp] by construction (`paneWidthDp -
 * 2 * margin`, so a right edge fixed at `paneStartDp + paneWidthDp` never puts the left edge past
 * the seam); the extra `coerceAtLeast` is defensive for a pane narrower than two margins.
 */
fun islandCardRect(anchor: IslandCardAnchor, pillTopDp: Float, hasMedia: Boolean): IslandCardRect {
    val cap = if (anchor.isCover) Float.MAX_VALUE else ISLAND_CARD_MAX_WIDTH_DP
    val width = islandCardWidthDp(anchor.paneWidthDp, cap)
    val railRightDp = anchor.paneStartDp + anchor.paneWidthDp
    val left = (railRightDp - width).coerceAtLeast(anchor.paneStartDp)
    return IslandCardRect(pillTopDp, left, width, islandCardHeightDp(hasMedia, anchor.isCover))
}
