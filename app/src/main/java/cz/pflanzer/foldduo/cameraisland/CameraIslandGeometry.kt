package cz.pflanzer.foldduo.cameraisland

import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.islandLerp
import cz.pflanzer.foldduo.island.islandMorphWindow
import cz.pflanzer.foldduo.island.islandSwipeDismissed

/**
 * Pure geometry and state-machine rules for IDEAS.md B61 "Ostrov kolem kamery" (the Dynamic
 * Island-style pill built around the camera cutout, as opposed to `island/RailIsland.kt`'s pill
 * stack under the status block). No Android or Compose types are ever CALLED here, mirroring
 * `island/IslandMotion.kt` + `island/IslandCardMotion.kt`: rects, morph windows, the
 * hidden/compact/minimal/expanded state machine and [cameraIslandLiveItems]'s own filter are
 * plain JVM units reading only [IslandItem]'s plain-typed fields (`String`/`Long`/`Float`/
 * `Boolean`/`Int`) — the same convention `island/IslandItem.kt`'s own top-level functions
 * (`rankIslandItems`, `compactIslandLabel`) already use despite [IslandItem] itself declaring
 * nullable Android fields (`Drawable`, `PendingIntent`, …) it never touches.
 * `cameraisland/CameraIsland.kt` is the Compose half that reads a live cutout, drives one
 * `Animatable<Float>` progress through these functions, and turns the result into
 * `graphicsLayer`/`layout` draws.
 *
 * 17. 9. night, iPhone-Dynamic-Island rework (Tom's device verdict: "looks weird; make it a
 * little wider; on tap it should expand more like the iPhone Dynamic Island"): this file's own
 * numbers were retuned against the iPhone 15/16 Pro proportions translated to Fold 8 cover dp
 * (cutout 27dp wide, ~39.6dp tall) — a fixed 12dp/32dp/10dp leading-slot grid and a matching
 * 10dp/32dp/14dp trailing-slot grid so content never crowds the cutout, an expanded card that is
 * `panelWidth - 2x12dp` wide with a 44dp corner instead of a capped 320/360dp card, and a
 * width-first/height-follow morph (see [cameraIslandWidthProgress]/[cameraIslandHeightProgress])
 * instead of one uniform lerp.
 *
 * Second correction, same night ("jinak bude jen malá kamera"): with nothing live the island
 * draws NOTHING — no black pill, no hairline, just the bare cutout the system already shows.
 * [CameraIslandVisualState.HIDDEN] is a true "no container" state now, not a small idle pill; the
 * pill only exists while [cameraIslandLiveItems] is non-empty, growing out of
 * [cameraIslandNoneRect] (the cutout plus a 4dp margin) on the first live item and shrinking back
 * into it when the last one leaves (see [cameraIslandContainerAlpha]). [CameraIslandContent.NONE]
 * and [cameraIslandRect]'s own "no content" branch (still `cutout + 2x[CAMERA_ISLAND_SIDE_MARGIN_DP]`)
 * are kept as plain, tested geometry utilities — nothing in `CameraIsland.kt` renders that shape
 * for [CameraIslandVisualState.HIDDEN] any more, but the floor they define is still what keeps a
 * compact/minimal pill with a small slot on one side from looking lopsided.
 */

// --- Rect model --------------------------------------------------------------------------

/**
 * A display cutout's bounding box, in dp, in its own panel's window coordinates (the same space
 * `View.getRootWindowInsets()?.displayCutout?.boundingRects` reports, once converted px -> dp) —
 * unlike `DeviceProfile.kt`'s [cz.pflanzer.foldduo.CutoutDp] (edge insets only), this carries the
 * cutout's actual position so the pill can be centred on it.
 */
data class CameraCutoutRect(val leftDp: Float, val topDp: Float, val widthDp: Float, val heightDp: Float) {
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
    val centerXDp: Float get() = leftDp + widthDp / 2f
    val centerYDp: Float get() = topDp + heightDp / 2f

    companion object {
        /** A zero-size cutout at the origin — never used directly as a real cutout, only as the
         * seed [cameraIslandFallbackCutout] builds a synthetic anchor from. */
        val NONE = CameraCutoutRect(0f, 0f, 0f, 0f)
    }
}

/** The camera island's own rect, in the same window-dp space as [CameraCutoutRect]. */
data class CameraIslandRect(val leftDp: Float, val topDp: Float, val widthDp: Float, val heightDp: Float) {
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
    val centerXDp: Float get() = leftDp + widthDp / 2f
    val centerYDp: Float get() = topDp + heightDp / 2f
}

// --- Constants -----------------------------------------------------------------------------

/**
 * Pill height = cutout height + this margin (17. 9. night pixel-precise mock,
 * `docs/design/camera-island-mock.html`: "výška 46 (výřez 40 + 6)" — 40dp cutout + 6dp margin =
 * 46dp pill, superseding the earlier iPhone-proportion +10dp rule).
 */
const val CAMERA_ISLAND_HEIGHT_MARGIN_DP = 6f

/** Pill height never shrinks below this, even for a very short/missing cutout (task spec item 1). */
const val CAMERA_ISLAND_MIN_HEIGHT_DP = 36f

/** Idle pill's (nothing live, [CameraIslandContent.NONE]) margin either side of the cutout — "a
 * little wider" than the old 14dp per Tom's device verdict, matching the iPhone idle island's own
 * generosity (task spec item 1: "idle width = cutout width + 2x24dp"). */
const val CAMERA_ISLAND_SIDE_MARGIN_DP = 24f

/** Compact/minimal leading slot's own icon/artwork size (task spec item 1). */
const val CAMERA_ISLAND_LEADING_ICON_DP = 24f

/**
 * Compact/minimal leading slot's own width: exactly the icon, no extra floor (mock: "levý slot 12
 * + 24 + 10 před kamerou" — 12dp inset, 24dp icon, 10dp gap, nothing wider). Kept as its own
 * constant (equal to [CAMERA_ISLAND_LEADING_ICON_DP]) rather than folded away so [CameraIslandContent.COMPACT]
 * and [cameraIslandRect]'s `maxOf` floor keep reading the way every other slot width does.
 */
const val CAMERA_ISLAND_LEADING_SLOT_DP = 24f

/** Leading slot's inset from the pill's own left edge (task spec item 1: "12dp from the left edge"). */
const val CAMERA_ISLAND_LEADING_INSET_DP = 12f

/** Gap kept between a content slot and the cutout on either side (task spec item 1: "ending >= 10dp before the cutout" / "10dp gap" after it). */
const val CAMERA_ISLAND_CUTOUT_GAP_DP = 10f

/** Trailing slot's inset from the pill's own right edge (task spec item 1: "14dp right padding"). */
const val CAMERA_ISLAND_TRAILING_INSET_DP = 14f

/** Trailing slot's minimum width, same floor as the leading slot (task spec item 1: "each at least 32dp"). */
const val CAMERA_ISLAND_TRAILING_SLOT_DP = 32f

/**
 * Compact/minimal's own FALLBACK trailing "key value" slot width, used only when there is no text
 * value to measure (a media equalizer, a progress ring) — see [CAMERA_ISLAND_EQUALIZER_WIDTH_DP]
 * for that case specifically. A real text value ("12:34", "4 min", "03:21") never uses this
 * constant: Tom's 17. 9. correction is that the trailing value must NEVER wrap, so
 * `CameraIsland.kt`'s compact content measures the actual label with a `TextMeasurer` and feeds
 * that measured dp width in as [CameraIslandContent.trailingWidthDp] instead — the pill grows to
 * fit it (mock: "hodnota se nikdy nezalamuje, šířka pilulky roste podle ní (Uber „4 min" = 155)").
 */
const val CAMERA_ISLAND_TRAILING_VALUE_DP = 56f

/** Fixed trailing width for a media pill's 3-bar equalizer (mock `.eq`: 3 bars × 4dp + 2 gaps × 3dp = 18dp) — the one trailing "value" that is never measured as text. */
const val CAMERA_ISLAND_EQUALIZER_WIDTH_DP = 18f

/** Minimal state's second activity: a small circle detached to the right of the primary pill (task spec item 2), not a second slot inside it. */
const val CAMERA_ISLAND_MINIMAL_SECONDARY_DIAMETER_DP = 36f

/** Gap between the primary pill and the detached secondary circle (task spec item 2: "detached 8dp to the right"). */
const val CAMERA_ISLAND_MINIMAL_SECONDARY_GAP_DP = 8f

/** Expanded card's edge clearance from its own pane (task spec item 3: "width = panel width - 2x12dp"). */
const val CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP = 12f

/**
 * Expanded card's height floor — the mock's own "below"-layout height (timer/call/navigation/
 * progress/generic: 124dp). [cameraIslandExpandedHeightForKind] never returns anything below this,
 * so the `coerceIn` in [cameraIslandExpandedRect] is an identity clamp for every real kind, kept
 * only as a defensive floor.
 */
const val CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP = 100f
/** Media card: row 1 + bar + times + controls (17. 9. screenshot: 156 fits). */
const val CAMERA_ISLAND_EXPANDED_MEDIA_HEIGHT_DP = 156f

/** Expanded card's height ceiling — the mock's own "row1"-layout height (transport/media: 164dp). */
const val CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP = 168f

/** Fallback pill's distance from the top of its pane when the panel has no cutout at all (task spec item 1). */
const val CAMERA_ISLAND_FALLBACK_TOP_MARGIN_DP = 12f

/** The expanded card's fixed corner radius (17. 9. night pixel-precise mock: "roh 40", superseding the earlier 44dp iPhone-proportion rule). */
const val CAMERA_ISLAND_MORPH_CARD_CORNER_DP = 44f
/**
 * The expanded card never grows past this (dp): on the cover at the phone's real 2.25 density the
 * panel is ~555 dp wide and a panel-minus-24 card read as a black banner with an empty middle
 * (17. 9. screenshots). iPhone's island is ~89 % of a 393 pt screen; 480 keeps that feel on the
 * cover while the inner pane (~530 dp) still gets its own margins.
 */
const val CAMERA_ISLAND_EXPANDED_MAX_WIDTH_DP = 480f

/** Which of the two mock-defined expanded heights a kind uses: 164dp ("row1" layout: transport, media — row 1 obtéká kameru) or 124dp ("below" layout: timer, call, navigation, progress, workout/generic — content sits entirely below the cutout). Pure on [IslandKind] alone, unlike the old per-item height that varied with whether actions/segments were present. */
fun cameraIslandExpandedHeightForKind(kind: IslandKind): Float = when (kind) {
    IslandKind.TRANSPORT -> CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP
    IslandKind.MEDIA -> CAMERA_ISLAND_EXPANDED_MEDIA_HEIGHT_DP
    else -> CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP
}

/** Reduce motion collapses the whole morph into a short crossfade (task spec item 4). */
const val CAMERA_ISLAND_REDUCED_MOTION_MS = 150

/** Margin kept either side of the cutout inside the expanded card's exclusion zone (task spec item 3: "27+2x8dp wide") — also the compact pill's own camera-zone margin (mock item 1: "camera zone 43 dp wide (cutout + 2×8)"). */
const val CAMERA_ISLAND_EXCLUSION_MARGIN_DP = 8f

/** The compact/minimal pill's own always-empty camera zone width — cutout width + 2×[CAMERA_ISLAND_EXCLUSION_MARGIN_DP] (mock item 1: "43 dp" for the mock's 27dp-wide cutout). */
fun cameraIslandPillCameraZoneWidthDp(cutout: CameraCutoutRect): Float = cutout.widthDp + 2f * CAMERA_ISLAND_EXCLUSION_MARGIN_DP

/**
 * A state's leading/trailing slot widths (each already clamped to its own floor by
 * [cameraIslandRect] — [leadingWidthDp]/[trailingWidthDp] below the floor still yield a pill that
 * never looks lopsided). [extraHeightDp] lets a state grow the pill taller than the bare cutout
 * clearance (unused today — kept so a future state does not need a new rect function).
 */
data class CameraIslandContent(val leadingWidthDp: Float = 0f, val trailingWidthDp: Float = 0f, val extraHeightDp: Float = 0f) {
    companion object {
        val NONE = CameraIslandContent()
        /** [CAMERA_ISLAND_LEADING_SLOT_DP] left of the cutout, a same-floor trailing value slot
         * right of it — the compact state, and the minimal state's own primary pill (task spec
         * item 1/2: "Minimal ... primary pill as compact"). */
        val COMPACT = CameraIslandContent(leadingWidthDp = CAMERA_ISLAND_LEADING_SLOT_DP, trailingWidthDp = CAMERA_ISLAND_TRAILING_VALUE_DP)
    }
}

/**
 * The pill's rect for [cutout] and [content]. Height is `max(cutout height + 10dp, 36dp)` (task
 * spec item 1). With [CameraIslandContent.NONE] (idle/hidden) the pill hugs the cutout
 * symmetrically by [CAMERA_ISLAND_SIDE_MARGIN_DP] either side — the task's own "idle width =
 * cutout + 2x24dp". With real content, each side is pushed out along its own
 * inset/slot/cutout-gap grid ([CAMERA_ISLAND_LEADING_INSET_DP]+slot+[CAMERA_ISLAND_CUTOUT_GAP_DP]
 * on the left, [CAMERA_ISLAND_CUTOUT_GAP_DP]+slot+[CAMERA_ISLAND_TRAILING_INSET_DP] on the right),
 * each independently maxed against the bare idle margin so a pill with only one side occupied
 * never grows lopsided past what it needs, and the cutout itself never moves.
 */
fun cameraIslandRect(cutout: CameraCutoutRect, content: CameraIslandContent = CameraIslandContent.NONE): CameraIslandRect {
    val height = maxOf(cutout.heightDp + CAMERA_ISLAND_HEIGHT_MARGIN_DP + content.extraHeightDp, CAMERA_ISLAND_MIN_HEIGHT_DP)
    val leadingMargin = if (content.leadingWidthDp <= 0f) CAMERA_ISLAND_SIDE_MARGIN_DP else maxOf(
        CAMERA_ISLAND_SIDE_MARGIN_DP,
        CAMERA_ISLAND_LEADING_INSET_DP + maxOf(content.leadingWidthDp, CAMERA_ISLAND_LEADING_SLOT_DP) + CAMERA_ISLAND_CUTOUT_GAP_DP,
    )
    val trailingMargin = if (content.trailingWidthDp <= 0f) CAMERA_ISLAND_SIDE_MARGIN_DP else maxOf(
        CAMERA_ISLAND_SIDE_MARGIN_DP,
        CAMERA_ISLAND_CUTOUT_GAP_DP + maxOf(content.trailingWidthDp, CAMERA_ISLAND_TRAILING_SLOT_DP) + CAMERA_ISLAND_TRAILING_INSET_DP,
    )
    val left = cutout.leftDp - leadingMargin
    val right = cutout.rightDp + trailingMargin
    return CameraIslandRect(left, cutout.centerYDp - height / 2f, right - left, height)
}

/**
 * The trailing "value" content width baked into an already-built pill/minimal [rect] (the inverse
 * of the `content.trailingWidthDp` [cameraIslandRect] used to build it): `rect.right - cutout.right
 * - gap - inset`. Tom's 17. 9. correction ("the trailing value must never wrap; the pill grows to
 * fit it") means the value's measured width is baked into the rect the moment it is built
 * (`CameraIsland.kt`'s `TextMeasurer` call) — rendering that same value later (including a settled
 * "from"/"to" [CameraIslandRect] snapshot mid-transition) recovers its own box width straight from
 * the rect instead of re-measuring or threading a separate width through the snapshot machinery.
 * Never negative; a [rect] with no real trailing content (e.g. a [CameraIslandVisualState.HIDDEN]
 * rect never built through the content branch) simply floors at 0.
 */
fun cameraIslandTrailingContentWidthDp(rect: CameraIslandRect, cutout: CameraCutoutRect): Float =
    (rect.rightDp - cutout.rightDp - CAMERA_ISLAND_CUTOUT_GAP_DP - CAMERA_ISLAND_TRAILING_INSET_DP).coerceAtLeast(0f)

/**
 * Splits a compact "value" string like "4 min" into its leading digit run and the rest ("4", "min")
 * for the expanded transport card's big-number-plus-unit display (mock: `<div class="big">4<small>min</small></div>`,
 * task item 2: "'4' + 'min' on one line, no wrapping"). Falls back to `(value, "")` when [value]
 * does not start with a digit run (nothing to split off, the whole string is shown as the "big" part).
 */
fun cameraIslandSplitEtaValue(value: String): Pair<String, String> {
    val match = Regex("""^(\d+)\s*(.*)$""").find(value.trim()) ?: return value.trim() to ""
    val (number, rest) = match.destructured
    return number to rest.trim()
}

/**
 * Up to two initials from a display name ("Marek Novák" -> "MN"), for the call card's own avatar
 * fallback when there is no [IslandItem.appIcon]-style image to show. Blank/single-word input
 * yields at most the first one or two letters of that single token; fully blank input yields "".
 */
fun cameraIslandInitials(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * The minimal state's second activity (task spec item 2): a [CAMERA_ISLAND_MINIMAL_SECONDARY_DIAMETER_DP]
 * circle detached [CAMERA_ISLAND_MINIMAL_SECONDARY_GAP_DP] to the right of the primary pill
 * [primaryRect], vertically centred on the same centre line.
 */
fun cameraIslandMinimalSecondaryRect(primaryRect: CameraIslandRect): CameraIslandRect {
    val diameter = CAMERA_ISLAND_MINIMAL_SECONDARY_DIAMETER_DP
    return CameraIslandRect(
        leftDp = primaryRect.rightDp + CAMERA_ISLAND_MINIMAL_SECONDARY_GAP_DP,
        topDp = primaryRect.centerYDp - diameter / 2f,
        widthDp = diameter,
        heightDp = diameter,
    )
}

/**
 * The synthetic cutout a panel with no real camera cutout gets (task spec item 1: "fallback rect
 * ... top-centre of the right pane on the inner, tall pill"): zero-size, centred
 * horizontally in `[paneStartDp, paneStartDp + paneWidthDp)`, [topMarginDp] from the top of that
 * pane. Feeding this into [cameraIslandRect] with [CameraIslandContent.NONE] yields exactly the
 * "tall pill" the task describes (a zero-height cutout still floors at
 * [CAMERA_ISLAND_MIN_HEIGHT_DP]); feeding it into a state's own content grows the same fallback
 * pill exactly the way a real cutout's pill grows, with no special-casing needed anywhere else in
 * this file.
 */
fun cameraIslandFallbackCutout(paneStartDp: Float, paneWidthDp: Float, topMarginDp: Float = CAMERA_ISLAND_FALLBACK_TOP_MARGIN_DP): CameraCutoutRect {
    val centerX = paneStartDp + paneWidthDp / 2f
    return CameraCutoutRect(leftDp = centerX, topDp = topMarginDp, widthDp = 0f, heightDp = 0f)
}

/**
 * The expanded card's rect (task spec item 3): width is always `paneWidthDp -
 * 2x[CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP]` — never a fixed cap, so the card fills its own panel
 * on the cover and is clamped to its own pane on the inner ("never over the seam", enforced simply
 * by [paneWidthDp] already excluding the other pane — there is nothing left to clamp against once
 * width is derived from the pane's own width). Height is [contentHeightDp] clamped into
 * `[CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP, CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP]`; top is the
 * cutout's own top (task spec item 3: "the top edge stays at y = 0" — the card still hugs the
 * cutout). [isCover] is accepted for call-site symmetry with the rest of this file's pane-aware
 * functions even though both panes share one formula today.
 */
fun cameraIslandExpandedRect(
    cutout: CameraCutoutRect,
    paneStartDp: Float,
    paneWidthDp: Float,
    isCover: Boolean,
    contentHeightDp: Float,
): CameraIslandRect {
    val height = contentHeightDp.coerceIn(CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP, CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP)
    val maxWidth = (paneWidthDp - 2f * CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP).coerceAtLeast(0f)
    val width = minOf(maxWidth, CAMERA_ISLAND_EXPANDED_MAX_WIDTH_DP)
    // Centred on the camera, then kept inside the pane's own margins.
    val minLeft = paneStartDp + CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP
    val maxLeft = (paneStartDp + paneWidthDp - CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP - width).coerceAtLeast(minLeft)
    val left = (cutout.centerXDp - width / 2f).coerceIn(minLeft, maxLeft)
    return CameraIslandRect(left, cutout.topDp, width, height)
}

/**
 * The exclusion zone the expanded card's first row must leave empty at the cutout's own position
 * (task spec item 3: "nothing is drawn in a 27+2x8dp wide, cutout-height tall exclusion zone at
 * the cutout position"), expressed in the card's OWN local coordinates ([cardRect]'s left/top
 * subtracted out) so a caller laying out that first row can subtract it directly from its
 * `Modifier`/`Row` maths without re-deriving window space.
 */
fun cameraIslandExpandedExclusionZone(cutout: CameraCutoutRect, cardRect: CameraIslandRect): CameraIslandRect {
    val width = cutout.widthDp + 2f * CAMERA_ISLAND_EXCLUSION_MARGIN_DP
    return CameraIslandRect(
        leftDp = cutout.centerXDp - width / 2f - cardRect.leftDp,
        topDp = 0f,
        widthDp = width,
        heightDp = cutout.heightDp,
    )
}

// --- Expanded card per-kind layout geometry (17. 9. night mock rework) ----------------------
//
// Tom's device verdict on the previous expanded card ("looks terrible, the timer layout
// especially") replaces the old "avoid the exclusion zone in row 1" universal layout with two
// mock-defined layouts:
//  - "below": content sits entirely below the cutout (y 52..108) — timer, call, navigation,
//    progress, generic/workout. No exclusion zone needed; the cutout is simply above the content.
//  - "row1": content's first row obtées the cutout, needing the old exclusion-avoidance shape —
//    transport, media. Unlike the old [cameraIslandExpandedExclusionZone] (which centred a zone on
//    the LIVE cutout position), the mock fixes this row's own 3-column grid as constants
//    (188dp / 51dp / rest) calibrated to the mock's own fixed panel/cutout geometry, so it is
//    reproduced literally rather than re-derived from a possibly different real cutout position.

/** "below" layout (task item 2, timer/call/navigation/progress/generic): left/right inset and the y-band the content row occupies, both in the card's own local coordinates (top of card = y 0). */
const val CAMERA_ISLAND_BELOW_LEFT_INSET_DP = 20f
const val CAMERA_ISLAND_BELOW_RIGHT_INSET_DP = 16f
/** "below" content row is vertically centred in the 100 dp card (iPhone centres it too; the 17. 9. screenshot showed a 52 dp empty band above it). */
const val CAMERA_ISLAND_BELOW_TOP_DP = 14f
const val CAMERA_ISLAND_BELOW_HEIGHT_DP = 72f

/** "below" layout's two 52dp circle action buttons (timer cancel/pause, call mute/end): diameter and gap between them. */
const val CAMERA_ISLAND_CIRCLE_BUTTON_DIAMETER_DP = 52f
const val CAMERA_ISLAND_CIRCLE_BUTTON_GAP_DP = 12f

/** Call's own avatar circle diameter (mock: "avatar 44 dp kruh"). */
const val CAMERA_ISLAND_CALL_AVATAR_DP = 44f

/** "row1" layout (task item 2, transport/media): left/right inset and the fixed 3-column grid — left column, the empty gap over the cutout, and the rest (right column, right-aligned content). */
const val CAMERA_ISLAND_ROW1_INSET_DP = 16f
const val CAMERA_ISLAND_ROW1_LEFT_COL_DP = 188f
const val CAMERA_ISLAND_ROW1_CAMERA_GAP_DP = 51f
const val CAMERA_ISLAND_ROW1_TOP_DP = 12f
const val CAMERA_ISLAND_ROW1_HEIGHT_DP = 56f

/** Row 1's right column width for a card of [cardWidthDp]: whatever is left after the insets, the fixed left column and the fixed camera gap (task item 2: "grid [188 dp | 51 dp camera gap | rest]"). Never negative even for a pane narrower than the mock's own 451dp reference. */
fun cameraIslandRow1RightColWidthDp(cardWidthDp: Float): Float =
    (cardWidthDp - 2f * CAMERA_ISLAND_ROW1_INSET_DP - CAMERA_ISLAND_ROW1_LEFT_COL_DP - CAMERA_ISLAND_ROW1_CAMERA_GAP_DP).coerceAtLeast(0f)

/** Media/transport artwork/app-icon size and corner in "row1"'s left column (mock: obal/app icon 56dp / 40dp). */
const val CAMERA_ISLAND_MEDIA_ARTWORK_DP = 56f
const val CAMERA_ISLAND_MEDIA_ARTWORK_CORNER_DP = 12f
const val CAMERA_ISLAND_TRANSPORT_ICON_DP = 40f
const val CAMERA_ISLAND_TRANSPORT_ICON_CORNER_DP = 10f

/** Transport's stepper (task item 2, "row2"): the track's own y and the two dp margins its ends sit at (mock: "24..427" for a 451dp card, i.e. inset 24dp both sides), the dot size, the current dot's own scale-up and halo, and the label row's y. */
const val CAMERA_ISLAND_STEPPER_TOP_DP = 84f
const val CAMERA_ISLAND_STEPPER_TRACK_INSET_DP = 24f
const val CAMERA_ISLAND_STEPPER_DOT_DP = 12f
const val CAMERA_ISLAND_STEPPER_CURRENT_SCALE = 1.25f
const val CAMERA_ISLAND_STEPPER_HALO_EXTRA_DP = 5f
const val CAMERA_ISLAND_STEPPER_LABEL_TOP_DP = 100f // labels 100–114; chips start 8 dp under them (the 17. 9. screenshot had them overlapping)

/** The stepper track's right end for a card of [cardWidthDp] (mock: 451dp card -> x 24..427). */
fun cameraIslandStepperTrackEndDp(cardWidthDp: Float): Float = cardWidthDp - CAMERA_ISLAND_STEPPER_TRACK_INSET_DP

/**
 * The x position (card-local dp) of stepper dot [index] of [count] evenly spaced dots between the
 * track's two ends (mock: 4 dots at 0%/33.3%/66.6%/100% of the track). [count] <= 1 places the
 * single dot at the track's own start, matching a 0-length "evenly spaced" degenerate case rather
 * than dividing by zero.
 */
fun cameraIslandStepperDotXDp(index: Int, count: Int, cardWidthDp: Float): Float {
    val start = CAMERA_ISLAND_STEPPER_TRACK_INSET_DP
    val end = cameraIslandStepperTrackEndDp(cardWidthDp)
    if (count <= 1) return start
    val fraction = index.toFloat() / (count - 1).toFloat()
    return islandLerp(start, end, fraction.coerceIn(0f, 1f))
}

/** Transport's action chips row (task item 2, "row3"): y, height, corner and the gap between the (at most two) equal-width chips. */
const val CAMERA_ISLAND_CHIPS_TOP_DP = 122f
const val CAMERA_ISLAND_CHIP_HEIGHT_DP = 36f
const val CAMERA_ISLAND_CHIP_CORNER_DP = 18f
const val CAMERA_ISLAND_CHIP_GAP_DP = 8f

/** Media's progress bar / elapsed-remaining labels row (task item 2, media "row2"). */
const val CAMERA_ISLAND_MEDIA_BAR_TOP_DP = 78f
const val CAMERA_ISLAND_MEDIA_BAR_HEIGHT_DP = 4f
const val CAMERA_ISLAND_MEDIA_TIMES_TOP_DP = 88f

/** Media's transport-control row (task item 2, media "row3"): glyph size, the gap between the three centred controls, and the AirPlay-style output glyph at the row's right edge. */
const val CAMERA_ISLAND_MEDIA_CONTROLS_TOP_DP = 110f
const val CAMERA_ISLAND_MEDIA_CONTROL_GLYPH_DP = 28f
const val CAMERA_ISLAND_MEDIA_CONTROL_GAP_DP = 44f
const val CAMERA_ISLAND_MEDIA_OUTPUT_GLYPH_DP = 22f

/** Equalizer bar geometry, shared by the compact pill's trailing slot and the media card's row1 (mock `.eq`: 4dp-wide bars, 3dp gap). */
const val CAMERA_ISLAND_EQUALIZER_BAR_WIDTH_DP = 4f
const val CAMERA_ISLAND_EQUALIZER_BAR_GAP_DP = 3f

/** Plain ARGB ints (not Compose `Color`, keeping this file's no-Android/Compose-types convention) for the mock's three semantic accents: timer/ETA orange, call green, hang-up red. */
const val CAMERA_ISLAND_ORANGE_ARGB = 0xFFFF9F0A.toInt()
const val CAMERA_ISLAND_GREEN_ARGB = 0xFF30D158.toInt()
const val CAMERA_ISLAND_RED_ARGB = 0xFFFF453A.toInt()

/** Fallback equalizer/artwork tint when a media item carries no [cz.pflanzer.foldduo.island.IslandItem.accentColor] (mock `--music`). */
const val CAMERA_ISLAND_MUSIC_ARGB = 0xFFFC3C44.toInt()

/** Every edge of [from]..[to] lerped uniformly at [progress] — used for position-only maths (e.g. drag offsets); the pill/card rect itself goes through [cameraIslandMorphRect] instead, which is width-first/height-follow, not uniform. */
fun cameraIslandLerpRectUniform(progress: Float, from: CameraIslandRect, to: CameraIslandRect): CameraIslandRect = CameraIslandRect(
    leftDp = islandLerp(from.leftDp, to.leftDp, progress),
    topDp = islandLerp(from.topDp, to.topDp, progress),
    widthDp = islandLerp(from.widthDp, to.widthDp, progress),
    heightDp = islandLerp(from.heightDp, to.heightDp, progress),
)

// --- Width-first / height-follow morph, task spec item 4 ------------------------------------

/** Fraction of the morph ([0, this]) that drives the rect's width (task spec item 4: "width grows FIRST, progress 0-0.55 drives width"). */
const val CAMERA_ISLAND_WIDTH_WINDOW_END = 0.55f

/** Fraction of the morph ([this, 1]) that drives the rect's height (task spec item 4: "height follows over 0.25-1.0"). */
const val CAMERA_ISLAND_HEIGHT_WINDOW_START = 0.25f

/** The width-only sub-progress of the whole morph [progress] (task spec item 4). */
fun cameraIslandWidthProgress(progress: Float): Float = islandMorphWindow(progress, 0f, CAMERA_ISLAND_WIDTH_WINDOW_END)

/** The height-only sub-progress of the whole morph [progress] (task spec item 4). */
fun cameraIslandHeightProgress(progress: Float): Float = islandMorphWindow(progress, CAMERA_ISLAND_HEIGHT_WINDOW_START, 1f)

/**
 * The one rect the whole morph shares (task spec item 4): width lerps on
 * [cameraIslandWidthProgress]'s own window, height on [cameraIslandHeightProgress]'s — width
 * finishes growing well before height catches up, the same "grows sideways, then down" motion the
 * iPhone's own Dynamic Island plays. Left/top are derived by lerping each rect's OWN centre on the
 * matching window rather than lerping the edges directly, so the shape stays centred on the cutout
 * throughout (and still reproduces [from]/[to] exactly at `progress` 0/1, since each window is 0
 * at 0 and 1 at 1).
 */
fun cameraIslandMorphRect(progress: Float, from: CameraIslandRect, to: CameraIslandRect): CameraIslandRect {
    val widthT = cameraIslandWidthProgress(progress)
    val heightT = cameraIslandHeightProgress(progress)
    val width = islandLerp(from.widthDp, to.widthDp, widthT)
    val height = islandLerp(from.heightDp, to.heightDp, heightT)
    val centerX = islandLerp(from.centerXDp, to.centerXDp, widthT)
    val centerY = islandLerp(from.centerYDp, to.centerYDp, heightT)
    return CameraIslandRect(centerX - width / 2f, centerY - height / 2f, width, height)
}

/** Corner radius tied to the CURRENT (already-morphed) rect height (task spec item 4: "the corner
 * radius tied to min(height/2, 40dp)") — a pill (height well under 80dp) is always fully round,
 * an expanded card (height above 80dp) is always the fixed [cardCornerDp], and anything in
 * between (there is none today, but this stays correct if a state ever sits in that band) eases
 * smoothly, with no separate from/to-state bookkeeping needed to get either direction right. */
fun cameraIslandCornerDp(heightDp: Float, cardCornerDp: Float = CAMERA_ISLAND_MORPH_CARD_CORNER_DP): Float =
    minOf(heightDp / 2f, cardCornerDp)

/** @deprecated kept only so any stray caller/test using the old pill-height/progress lerp keeps compiling; prefer [cameraIslandCornerDp] on the live morphed rect height. */
fun cameraIslandMorphCornerDp(progress: Float, pillHeightDp: Float, cardCornerDp: Float = CAMERA_ISLAND_MORPH_CARD_CORNER_DP): Float =
    islandLerp(pillHeightDp / 2f, cardCornerDp, progress)

// --- Morph content crossfade, task spec item 4: "outgoing 1->0 over 0-35%, incoming 0->1 over 55-100%" --

/** The content being morphed AWAY FROM: opaque until the morph starts, fully faded by 35% (task spec item 4). */
fun cameraIslandOutgoingContentAlpha(progress: Float): Float = 1f - islandMorphWindow(progress, 0f, 0.35f)

/** The content being morphed TO: stays invisible until 55% of the morph, then fades in to fully opaque (task spec item 4). */
fun cameraIslandIncomingContentAlpha(progress: Float): Float = islandMorphWindow(progress, 0.55f, 1f)

/** Outgoing content's own "blur-like" shrink, 1 -> 0.9 over the same window as [cameraIslandOutgoingContentAlpha] (task spec item 4). */
fun cameraIslandOutgoingContentScale(progress: Float): Float = islandLerp(1f, 0.9f, islandMorphWindow(progress, 0f, 0.35f))

/** Incoming content's own "blur-like" grow, 0.94 -> 1 over the same window as [cameraIslandIncomingContentAlpha] (task spec item 4). */
fun cameraIslandIncomingContentScale(progress: Float): Float = islandLerp(0.94f, 1f, islandMorphWindow(progress, 0.55f, 1f))

// --- Nothing live: the island draws NOTHING, not a small idle pill --------------------------

/** [cameraIslandNoneRect]'s margin either side of the bare cutout — the seed/sink rect the pill grows out of and shrinks back into as the live list becomes non-empty/empty (Tom, 17. 9.: "jinak bude jen malá kamera"). */
const val CAMERA_ISLAND_NONE_MARGIN_DP = 4f

/** The cutout itself, expanded by [CAMERA_ISLAND_NONE_MARGIN_DP] on every side: what [CameraIslandVisualState.HIDDEN] "is", geometrically, for the one animated frame where the pill is growing out of it or shrinking back into it. Never drawn at full opacity — see [cameraIslandContainerAlpha]. */
fun cameraIslandNoneRect(cutout: CameraCutoutRect): CameraIslandRect = CameraIslandRect(
    leftDp = cutout.leftDp - CAMERA_ISLAND_NONE_MARGIN_DP,
    topDp = cutout.topDp - CAMERA_ISLAND_NONE_MARGIN_DP,
    widthDp = cutout.widthDp + 2f * CAMERA_ISLAND_NONE_MARGIN_DP,
    heightDp = cutout.heightDp + 2f * CAMERA_ISLAND_NONE_MARGIN_DP,
)

/** Container alpha while growing OUT of nothing (task: "alpha 0->1 over the first 30%"). */
fun cameraIslandAppearAlpha(progress: Float): Float = islandMorphWindow(progress, 0f, 0.3f)

/** Container alpha while shrinking back INTO nothing: the mirror of [cameraIslandAppearAlpha], fading out over the last 30% as the rect shrinks down onto [cameraIslandNoneRect]. */
fun cameraIslandDisappearAlpha(progress: Float): Float = 1f - islandMorphWindow(progress, 0.7f, 1f)

/**
 * The pill/card container's own alpha for a transition from [fromState] to [toState] at
 * [progress]: `0` throughout a transition that starts and ends at
 * [CameraIslandVisualState.HIDDEN] (nothing to draw — the steady "just the bare cutout" state),
 * [cameraIslandAppearAlpha] growing OUT of hidden, [cameraIslandDisappearAlpha] shrinking INTO it,
 * and fully opaque for any other transition (compact/minimal/expanded content crossfades handle
 * their own alpha separately — this is only the black container itself).
 */
fun cameraIslandContainerAlpha(fromState: CameraIslandVisualState, toState: CameraIslandVisualState, progress: Float): Float = when {
    fromState == CameraIslandVisualState.HIDDEN && toState == CameraIslandVisualState.HIDDEN -> 0f
    fromState == CameraIslandVisualState.HIDDEN -> cameraIslandAppearAlpha(progress)
    toState == CameraIslandVisualState.HIDDEN -> cameraIslandDisappearAlpha(progress)
    else -> 1f
}

// --- State machine ---------------------------------------------------------------------------

/** Which shape the camera island currently holds (task spec item 2's four "always-on" states; ALERT is a transient pulse layered on top, not a distinct shape — see [cameraIslandAlertTriggered]). */
enum class CameraIslandVisualState { HIDDEN, COMPACT, MINIMAL, EXPANDED }

/**
 * The island's shape for [itemKeys] and an [expandedKey] request: [CameraIslandVisualState.HIDDEN]
 * with nothing live (a stale [expandedKey] from an item that just disappeared does not hold the
 * card open — same rule as `island/IslandMotion.kt`'s `islandVisualState`), EXPANDED only while
 * [expandedKey] names an item still present, MINIMAL with two or more live items (task spec item
 * 2: "two activities: one each side"), COMPACT with exactly one.
 */
fun cameraIslandVisualState(itemKeys: List<String>, expandedKey: String?): CameraIslandVisualState = when {
    itemKeys.isEmpty() -> CameraIslandVisualState.HIDDEN
    expandedKey != null && expandedKey in itemKeys -> CameraIslandVisualState.EXPANDED
    itemKeys.size >= 2 -> CameraIslandVisualState.MINIMAL
    else -> CameraIslandVisualState.COMPACT
}

/** An expanded card whose item vanished from the feed must fold back on its own. */
fun cameraIslandShouldAutoCollapse(expandedKey: String?, itemKeys: List<String>): Boolean =
    expandedKey != null && expandedKey !in itemKeys

/** How long an expanded card stays open, untouched, before it folds back (task spec item 5: "8s idle collapse stays"). */
const val CAMERA_ISLAND_IDLE_TIMEOUT_MS = 8_000L

/** True once an expanded card has been open for [timeoutMs] without a touch resetting it. */
fun cameraIslandIdleTimedOut(expandedAtMs: Long, nowMs: Long, timeoutMs: Long = CAMERA_ISLAND_IDLE_TIMEOUT_MS): Boolean =
    nowMs - expandedAtMs >= timeoutMs

// --- Gestures: reuses island/IslandMotion.kt's own swipe-fraction-or-flick rule -------------

/** Fraction of the minimal pill's width a left/right drag must cross to switch activities. */
const val CAMERA_ISLAND_SWITCH_THRESHOLD_FRACTION = 0.35f

/** A flick at or above this speed (px/s) switches activities early, same idea as the rail island's dismiss flick. */
const val CAMERA_ISLAND_SWITCH_VELOCITY_THRESHOLD_PX = 800f

/** Whether a horizontal drag on the minimal pill should switch which of its two activities is in front (task spec: "swipe left/right -> switch activities"). */
fun cameraIslandSwipeSwitched(offsetPx: Float, widthPx: Float, velocityPx: Float): Boolean =
    islandSwipeDismissed(offsetPx, widthPx, velocityPx, CAMERA_ISLAND_SWITCH_THRESHOLD_FRACTION, CAMERA_ISLAND_SWITCH_VELOCITY_THRESHOLD_PX)

/** [CameraIslandVisualState.MINIMAL] only ever holds two slots (front/back); a switch just flips which index is front. */
fun cameraIslandSwitchedIndex(currentIndex: Int): Int = if (currentIndex == 0) 1 else 0

/** Fraction of the expanded card's height an upward drag must cross to collapse it. */
const val CAMERA_ISLAND_COLLAPSE_THRESHOLD_FRACTION = 0.3f

/** A flick at or above this speed (px/s) collapses the card early. */
const val CAMERA_ISLAND_COLLAPSE_VELOCITY_THRESHOLD_PX = 900f

/** Whether a vertical drag on the expanded card should collapse it (task spec: "swipe up -> collapse"). */
fun cameraIslandSwipeCollapsed(offsetPx: Float, heightPx: Float, velocityPx: Float): Boolean =
    islandSwipeDismissed(offsetPx, heightPx, velocityPx, CAMERA_ISLAND_COLLAPSE_THRESHOLD_FRACTION, CAMERA_ISLAND_COLLAPSE_VELOCITY_THRESHOLD_PX)

/** Extra touch area either side of the pill's own bounds (task spec item 5: "whole pill is the tap target with a 12dp hit-slop"). */
const val CAMERA_ISLAND_HIT_SLOP_DP = 12f

/** Press-state scale while the pill/card is held down (task spec item 5: "press state scales 0.97 while pressed"). */
const val CAMERA_ISLAND_PRESS_SCALE = 0.97f

// --- Alert pulse, task spec item 4: "scale 1.0 -> 1.06 -> 1.0" ------------------

/** Peak scale of the alert pulse the pill plays on a value change. */
const val CAMERA_ISLAND_ALERT_PEAK_SCALE = 1.06f

/**
 * True when the item's own displayed value actually changed ([previousLabel] to [currentLabel])
 * rather than merely appearing for the first time (`null` -> anything, no pulse — nothing to
 * compare a freshly-shown value against) or staying the same (a re-composition with identical
 * text is not an update worth an alert).
 */
fun cameraIslandAlertTriggered(previousLabel: String?, currentLabel: String?): Boolean =
    previousLabel != null && currentLabel != null && previousLabel != currentLabel

// --- "Only things running right now" (Tom, 17. 9.) ------------------------------------------

/** A paused (or genuinely stopped — [cz.pflanzer.foldduo.island.IslandMedia.playing] is `false` for both) media session still counts as live for this long since its last known position update. */
const val CAMERA_ISLAND_MEDIA_PAUSE_GRACE_MS = 30_000L

/** A countdown timer that already reached zero still counts as live for this long past that point. */
const val CAMERA_ISLAND_TIMER_FINISHED_GRACE_MS = 60_000L

/**
 * Tom's rule (17. 9.): "jen aktuální běžící věci, například dojezd taxíku, běžící timer" — the
 * camera island shows ONLY things that are actually running right now, unlike the rail (or a
 * notification shade), which can afford to linger on something recently finished. Filters
 * [items] (already ranked/deduped/enriched upstream by `island/IslandNotificationListener.kt` and
 * `island/LiveActivityAdapters.kt`) down to what the camera island is ever allowed to render;
 * every rect/content function in this file, and `CameraIsland.kt`'s own overlay, only ever see the
 * result of this filter, never the raw list. An empty result collapses the pill to
 * [CameraIslandVisualState.HIDDEN] — the bare black pill around the cutout — through the exact
 * same [cameraIslandVisualState] rule an empty [items] list already produced before this filter
 * existed.
 *
 * - [IslandKind.CALL]/[IslandKind.NAVIGATION]/[IslandKind.WORKOUT]: always kept — these kinds only
 *   ever exist upstream while genuinely ongoing, nothing further to check here.
 * - [IslandKind.TRANSPORT]: kept only once a live-activity adapter actually recognised it as a
 *   ride/delivery in progress ([IslandItem.etaMs], [IslandItem.stageIndex] or [IslandItem.keyValue]
 *   set) — a transport-shaped notification with none of the three is not identifiably "running"
 *   on this surface.
 * - [IslandKind.TIMER]: kept only with an actual running chronometer ([IslandItem.chronometerBase]
 *   `!= null`); a countdown ([IslandItem.countDown]) that already reached zero (`nowMs` at or past
 *   the base) stays for [CAMERA_ISLAND_TIMER_FINISHED_GRACE_MS] more, then drops. A count-up
 *   chronometer (a stopwatch) has no "finished" state and is always kept.
 * - [IslandKind.PROGRESS]: kept only while it is still moving — indeterminate, or a determinate
 *   value below `1f`.
 * - [IslandKind.MEDIA]: kept while its session is playing, or for
 *   [CAMERA_ISLAND_MEDIA_PAUSE_GRACE_MS] after its last known position update once it is not — the
 *   rail's own separate 10-minute resumable-session TTL (`island/IslandNotificationListener.kt`'s
 *   `STOPPED_SESSION_TTL_MS`) never extends this window for the island. [nowMs] and
 *   [IslandMedia.positionUpdatedAtMs][cz.pflanzer.foldduo.island.IslandMedia.positionUpdatedAtMs]
 *   must already share one clock by the time they reach this function — the field is natively
 *   `SystemClock.elapsedRealtime()`-based while every other timestamp here (`chronometerBase`,
 *   [nowMs] itself, by this file's own convention) is wall-clock, so `CameraIsland.kt`'s Android-
 *   side caller normalizes it to wall-clock before calling, keeping this function itself free of
 *   any clock read.
 * - [IslandKind.OTHER] and anything else not covered above (a finished/valueless [IslandKind.PROGRESS],
 *   a [IslandKind.TRANSPORT] with no recognised ETA/stage/value, a titled-but-not-running
 *   [IslandKind.TIMER]): dropped.
 */
fun cameraIslandLiveItems(items: List<IslandItem>, nowMs: Long): List<IslandItem> = items.filter { cameraIslandItemIsLive(it, nowMs) }

private fun cameraIslandItemIsLive(item: IslandItem, nowMs: Long): Boolean = when (item.kind) {
    IslandKind.CALL, IslandKind.NAVIGATION, IslandKind.WORKOUT -> true
    IslandKind.TRANSPORT -> item.etaMs != null || item.stageIndex != null || item.keyValue != null
    IslandKind.TIMER -> {
        val base = item.chronometerBase
        base != null && (!item.countDown || nowMs - base <= CAMERA_ISLAND_TIMER_FINISHED_GRACE_MS)
    }
    IslandKind.PROGRESS -> item.progressIndeterminate || (item.progress != null && item.progress < 1f)
    IslandKind.MEDIA -> {
        val media = item.media
        media != null && (media.playing || nowMs - media.positionUpdatedAtMs <= CAMERA_ISLAND_MEDIA_PAUSE_GRACE_MS)
    }
    IslandKind.OTHER -> false
}
