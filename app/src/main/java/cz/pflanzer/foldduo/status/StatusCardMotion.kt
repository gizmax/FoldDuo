package cz.pflanzer.foldduo.status

/**
 * Pure motion/geometry rules for the rail's "Status card" (2026-09-17 noc). Tom's device feedback
 * on the first 2x2-grid version ("při rozkliku Wi-Fi jsem si představoval spíše pilulku stejné
 * výšky, co vyjede doleva, a budou tam ty jednotlivé parametry") reshaped this into a sideways
 * glass PILL, not a card: the ring glyph grows LEFT, its height frozen exactly at the ring's own
 * measured height for the whole animation (so the ring stays put as the pill's right-end "cap"
 * the whole time, not just at rest), with the four parameter items sliding in from behind it.
 * No Android/Compose types here (mirrors [cz.pflanzer.foldduo.island.IslandCardMotion]) so the
 * anchor math, the pill rect and the stagger are plain JVM units; `status/StatusCard.kt` is the
 * framework-facing half that turns these into `Animatable`/spring-driven `graphicsLayer` calls.
 */

/**
 * Anchor geometry the root-level status card overlay needs, measured off the rail's own status
 * ring via `LayoutCoordinates.boundsInRoot()` (the same technique the folder-open morph already
 * uses for its icon bounds — [cz.pflanzer.foldduo.FolderOpenMotion] — chosen specifically to
 * sidestep the safe-drawing-inset bug the island card's box-relative `rail.islandTop` hit: a
 * measured root-relative rect needs no manual safe-inset arithmetic). [paneStartDp]/[paneWidthDp]
 * still come from [cz.pflanzer.foldduo.paneBounds] math (there is nothing to measure for "the
 * far edge of the pane"), so those two — unlike the ring rect — must be converted to root
 * coordinates by the caller the same way `island/IslandMotion`'s anchor now is (add the
 * safe-drawing left inset to a box-relative pane start).
 */
data class StatusCardAnchor(
    val ringTopDp: Float,
    val ringLeftDp: Float,
    val ringWidthDp: Float,
    val ringHeightDp: Float,
    val paneStartDp: Float,
    val paneWidthDp: Float,
    val isCover: Boolean,
)

/** Margin the pill keeps from the seam (inner) or the screen edge (cover) at its far (left) end;
 * task spec: "pane width − 32 dp" / "screen − 32 dp" — 16 dp on each of the two margins that sum
 * to that 32. */
const val STATUS_CARD_MARGIN_DP = 16f

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

// --- Pill geometry (ring -> sideways pill, right edge pinned at the ring) --------------------

/** The pill's rect at any point of the morph: [heightDp] is always the ring's own measured height
 * (task spec: "height = the ring glyph's measured height, keep it exactly"), so its corner is
 * always a full stadium — half the (constant) height, never interpolated on its own. */
data class StatusPillRect(val topDp: Float, val leftDp: Float, val widthDp: Float, val heightDp: Float) {
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
    val cornerDp: Float get() = heightDp / 2f
}

/** `min(contentWidthDp, paneWidthDp - 2 * margin)`, never negative — the pill's fully-open width
 * for a given pane, capped so it never reaches the seam (inner) or the screen edge (cover). */
fun statusPillWidthDp(contentWidthDp: Float, paneWidthDp: Float): Float =
    minOf(contentWidthDp, paneWidthDp - 2f * STATUS_CARD_MARGIN_DP).coerceAtLeast(0f)

/**
 * The pill's fully-open target rect (task spec: "grows LEFT", "the ring stays at the pill's right
 * end as its cap", "width by content up to the pane width − 32 dp"). The right edge is fixed at
 * the ring's own right edge — not the pane's right edge — so the ring visually IS the pill's cap;
 * on a rail flush with the pane's edge those coincide anyway. [contentWidthDp] is the width the
 * row of parameter items wants (see [statusPillContentWidthDp]); when the pane is too narrow to
 * fit it even after the margin, the width is truncated further so the left edge never crosses
 * [StatusCardAnchor.paneStartDp] (the seam/screen edge).
 */
fun statusPillRect(anchor: StatusCardAnchor, contentWidthDp: Float): StatusPillRect {
    val ringRightDp = anchor.ringLeftDp + anchor.ringWidthDp
    val desiredWidth = statusPillWidthDp(contentWidthDp, anchor.paneWidthDp)
    val maxAvailableWidth = (ringRightDp - anchor.paneStartDp).coerceAtLeast(0f)
    val width = desiredWidth.coerceAtMost(maxAvailableWidth)
    val left = (ringRightDp - width).coerceAtLeast(anchor.paneStartDp)
    return StatusPillRect(anchor.ringTopDp, left, ringRightDp - left, anchor.ringHeightDp)
}

/**
 * The shell rect mid-morph at [progress] (0 = closed ring, 1 = fully open [pill]): top and height
 * are frozen at the ring's own for the entire animation (not lerped — they are already equal at
 * both ends), and the right edge stays pinned at [pill]'s right edge (== the ring's own right
 * edge) the whole time, so only the left edge travels as the width grows from the ring's own
 * width to the pill's.
 */
fun statusPillShellRect(anchor: StatusCardAnchor, pill: StatusPillRect, progress: Float): StatusPillRect {
    val t = progress.coerceIn(0f, 1f)
    val width = lerp(anchor.ringWidthDp, pill.widthDp, t)
    return StatusPillRect(anchor.ringTopDp, pill.rightDp - width, width, anchor.ringHeightDp)
}

// --- Content width by item (rough, no real text measurement) --------------------------------

/** Hairline separator thickness between items (task spec: "hairline separators between items"). */
const val STATUS_PILL_SEPARATOR_DP = 1f

/** Horizontal gap on each side of a separator between two neighbouring items. */
const val STATUS_PILL_ITEM_SPACING_DP = 8f

/** Leading padding before the first item and trailing gap before the ring/cap. */
const val STATUS_PILL_END_PADDING_DP = 12f

/**
 * Rough width (dp) one compact item (task spec: "44 dp tall... icon 20 dp + one line of text
 * 13 sp") needs for [text] — a per-character estimate, not a real text measurement (the pure
 * geometry layer intentionally has no Android `Paint`/`TextMeasurer` to call, the same trade-off
 * [cz.pflanzer.foldduo.island.IslandCardMotion] makes with its fixed card heights), clamped to a
 * sane range so one long SSID or device name cannot blow out the whole pill — the Compose layer
 * always wraps the row in a horizontal scroll as a backstop regardless of how close this estimate
 * lands (task spec: "if the pill does not fit all four items on a narrow pane, it scrolls
 * horizontally inside").
 */
fun statusPillItemWidthDp(text: String, minDp: Float = 64f, maxDp: Float = 132f): Float {
    val iconAndPadding = 20f + 2f * 12f // 20dp icon + 12dp horizontal padding each side
    val textWidthDp = text.length * 6.2f // ~13sp average glyph advance
    return (iconAndPadding + textWidthDp).coerceIn(minDp, maxDp)
}

/**
 * Total content width (dp) for [itemTexts] laid out left to right with a hairline separator plus
 * spacing between each, and end padding at both ends (task spec: item content drives the pill's
 * width). Empty input needs no pill at all.
 */
fun statusPillContentWidthDp(itemTexts: List<String>): Float {
    if (itemTexts.isEmpty()) return 0f
    val itemsWidth = itemTexts.sumOf { statusPillItemWidthDp(it).toDouble() }.toFloat()
    val gapCount = itemTexts.size - 1
    val gapsWidth = gapCount * (STATUS_PILL_SEPARATOR_DP + 2f * STATUS_PILL_ITEM_SPACING_DP)
    return 2f * STATUS_PILL_END_PADDING_DP + itemsWidth + gapsWidth
}

// --- Item order + stagger (task spec: 40 ms stagger, slide + fade) --------------------------

/** The pill's four parameter items, left to right (task spec order: "Wi-Fi, Mobile, Battery, Bluetooth"). */
enum class StatusPillItem { WIFI, MOBILE, BATTERY, BLUETOOTH }

/** Canonical left-to-right item order — also the order [statusPillContentWidthDp]'s `itemTexts` must follow. */
val STATUS_PILL_ITEM_ORDER = listOf(StatusPillItem.WIFI, StatusPillItem.MOBILE, StatusPillItem.BATTERY, StatusPillItem.BLUETOOTH)

/** Stagger between each item's slide-in start (task spec: 40 ms). */
const val STATUS_PILL_ITEM_STAGGER_MS = 40L

/**
 * [index]'s own local progress (0..[itemCount] - 1) given the overall morph's [globalProgress]
 * (0..1 over [totalDurationMs]): item 0 starts immediately, each later item [staggerMs] behind
 * the previous one, and every item still reaches 1 exactly when [globalProgress] does (a shorter
 * tail duration for later items, not a longer total animation) — generalised over any [itemCount]
 * so it is not tied to the pill's current four items. A [totalDurationMs] too short to fit the
 * full stagger still clamps to a sane (non-negative) local duration instead of dividing by zero
 * or going negative; an [index] outside `0 until itemCount` clamps into range.
 */
fun statusPillItemProgress(
    index: Int,
    itemCount: Int,
    globalProgress: Float,
    totalDurationMs: Long,
    staggerMs: Long = STATUS_PILL_ITEM_STAGGER_MS,
): Float {
    if (totalDurationMs <= 0L || itemCount <= 0) return globalProgress.coerceIn(0f, 1f)
    val clampedIndex = index.coerceIn(0, itemCount - 1)
    val delayMs = clampedIndex * staggerMs
    val elapsedMs = globalProgress.coerceIn(0f, 1f) * totalDurationMs
    val localDurationMs = (totalDurationMs - delayMs).coerceAtLeast(1L)
    return ((elapsedMs - delayMs) / localDurationMs).coerceIn(0f, 1f)
}

/** Convenience overload keyed by [StatusPillItem], using its position in [STATUS_PILL_ITEM_ORDER]. */
fun statusPillItemProgress(
    item: StatusPillItem,
    globalProgress: Float,
    totalDurationMs: Long,
    staggerMs: Long = STATUS_PILL_ITEM_STAGGER_MS,
): Float = statusPillItemProgress(
    STATUS_PILL_ITEM_ORDER.indexOf(item).coerceAtLeast(0), STATUS_PILL_ITEM_ORDER.size,
    globalProgress, totalDurationMs, staggerMs,
)

/**
 * How far (dp) an item still is, at its own [progress] (0..1), from its resting slide position —
 * [travelDp] at the very start (task spec: "slide in from behind the ring's centre"), 0 once
 * settled. Paired with a fade (alpha == [progress]) in the Compose layer.
 */
fun statusPillItemTranslationDp(progress: Float, travelDp: Float): Float = (1f - progress.coerceIn(0f, 1f)) * travelDp

// --- Signal level -> bars ---------------------------------------------------------------------

/** Lit bars (0..4) for a mobile signal level (0..4) on the pill's mobile item. */
fun mobileSignalBars(level: Int?): Int = (level ?: 0).coerceIn(0, 4)
