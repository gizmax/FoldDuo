package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.RAIL_GAP_DP
import cz.pflanzer.foldduo.RAIL_ISLAND_ITEM_HEIGHT_DP
import cz.pflanzer.foldduo.island.DigitRollChar
import cz.pflanzer.foldduo.island.ISLAND_BREATHING_AMPLITUDE
import cz.pflanzer.foldduo.island.ISLAND_BREATHING_PERIOD_MS
import cz.pflanzer.foldduo.island.ISLAND_CARD_DEFAULT_HEIGHT_DP
import cz.pflanzer.foldduo.island.ISLAND_CARD_MARGIN_DP
import cz.pflanzer.foldduo.island.ISLAND_CARD_MAX_WIDTH_DP
import cz.pflanzer.foldduo.island.ISLAND_CARD_MEDIA_HEIGHT_COVER_DP
import cz.pflanzer.foldduo.island.ISLAND_CARD_MEDIA_HEIGHT_INNER_DP
import cz.pflanzer.foldduo.island.ISLAND_DISMISS_THRESHOLD_FRACTION
import cz.pflanzer.foldduo.island.ISLAND_DISMISS_VELOCITY_THRESHOLD_PX
import cz.pflanzer.foldduo.island.IslandCardAnchor
import cz.pflanzer.foldduo.island.IslandContentTransition
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.IslandVisualState
import cz.pflanzer.foldduo.island.digitRollDiff
import cz.pflanzer.foldduo.island.islandBreathingEnabled
import cz.pflanzer.foldduo.island.islandBreathingScale
import cz.pflanzer.foldduo.island.islandCardHeightDp
import cz.pflanzer.foldduo.island.islandCardRect
import cz.pflanzer.foldduo.island.islandCardWidthDp
import cz.pflanzer.foldduo.island.islandContentTransition
import cz.pflanzer.foldduo.island.islandExpandTimedOut
import cz.pflanzer.foldduo.island.islandItemDismissible
import cz.pflanzer.foldduo.island.islandPillTopDp
import cz.pflanzer.foldduo.island.islandShouldAutoCollapse
import cz.pflanzer.foldduo.island.islandSwipeDismissed
import cz.pflanzer.foldduo.island.islandVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The rail island's B30 "Ostrůvek s pružinou" motion rules (island/IslandMotion.kt): the shape state machine, the swipe-dismiss threshold, and the chronometer digit-roll split. */
class IslandMotionTest {
    private val timer = IslandItem("timer", kind = IslandKind.TIMER, chronometerBase = 1_000L)
    private val call = IslandItem("call", kind = IslandKind.CALL)

    // --- islandVisualState / auto-collapse / timeout ---

    @Test fun `visual state is empty with no items regardless of an expanded key`() {
        assertEquals(IslandVisualState.EMPTY, islandVisualState(emptyList(), null))
        assertEquals(IslandVisualState.EMPTY, islandVisualState(emptyList(), "stale"))
    }

    @Test fun `visual state is expanded only while the expanded key still names a live item`() {
        assertEquals(IslandVisualState.EXPANDED, islandVisualState(listOf(timer, call), "timer"))
        assertEquals(IslandVisualState.COMPACT, islandVisualState(listOf(timer, call), "gone"))
        assertEquals(IslandVisualState.COMPACT, islandVisualState(listOf(timer, call), null))
    }

    @Test fun `an expanded card auto-collapses once its item vanishes from the feed`() {
        assertTrue(islandShouldAutoCollapse("timer", listOf(call)))
        assertFalse(islandShouldAutoCollapse("timer", listOf(timer, call)))
        assertFalse(islandShouldAutoCollapse(null, emptyList()))
    }

    @Test fun `expand timeout fires once the timeout has elapsed, not before`() {
        assertFalse(islandExpandTimedOut(expandedAtMs = 0L, nowMs = 5_999L, timeoutMs = 6_000L))
        assertTrue(islandExpandTimedOut(expandedAtMs = 0L, nowMs = 6_000L, timeoutMs = 6_000L))
        assertTrue(islandExpandTimedOut(expandedAtMs = 1_000L, nowMs = 20_000L, timeoutMs = 6_000L))
    }

    // --- content transition (crossfade vs. Now Bar cross-slide) ---

    @Test fun `content transition is first for a fresh slot, update for the same key, replace otherwise`() {
        assertEquals(IslandContentTransition.FIRST, islandContentTransition(null, "timer"))
        assertEquals(IslandContentTransition.UPDATE, islandContentTransition("timer", "timer"))
        assertEquals(IslandContentTransition.REPLACE, islandContentTransition("timer", "call"))
    }

    // --- dismiss threshold ---

    @Test fun `calls navigation and media sessions are never swipe-dismissible`() {
        assertFalse(islandItemDismissible(IslandKind.CALL))
        assertFalse(islandItemDismissible(IslandKind.NAVIGATION))
        assertFalse(islandItemDismissible(IslandKind.MEDIA))
        assertTrue(islandItemDismissible(IslandKind.TIMER))
        assertTrue(islandItemDismissible(IslandKind.PROGRESS))
        assertTrue(islandItemDismissible(IslandKind.TRANSPORT))
        assertTrue(islandItemDismissible(IslandKind.WORKOUT))
        assertTrue(islandItemDismissible(IslandKind.OTHER))
    }

    @Test fun `a drag past the threshold fraction of the pill's width dismisses it`() {
        val width = 200f
        assertFalse(islandSwipeDismissed(offsetPx = 79f, widthPx = width, velocityPx = 0f))
        assertTrue(islandSwipeDismissed(offsetPx = 80f, widthPx = width, velocityPx = 0f))
        assertTrue(islandSwipeDismissed(offsetPx = -80f, widthPx = width, velocityPx = 0f))
        assertEquals(0.4f, ISLAND_DISMISS_THRESHOLD_FRACTION)
    }

    @Test fun `a fast flick in the drag direction dismisses before the distance threshold`() {
        val width = 400f
        // Far short of the 160 px distance threshold, but flung at or above the velocity threshold.
        assertTrue(islandSwipeDismissed(offsetPx = 20f, widthPx = width, velocityPx = ISLAND_DISMISS_VELOCITY_THRESHOLD_PX))
        assertTrue(islandSwipeDismissed(offsetPx = -20f, widthPx = width, velocityPx = -ISLAND_DISMISS_VELOCITY_THRESHOLD_PX))
        // Same speed, just under threshold: not dismissed.
        assertFalse(islandSwipeDismissed(offsetPx = 20f, widthPx = width, velocityPx = ISLAND_DISMISS_VELOCITY_THRESHOLD_PX - 1f))
        // A fast flick opposite to a small drag does not count (finger reversed direction).
        assertFalse(islandSwipeDismissed(offsetPx = 20f, widthPx = width, velocityPx = -ISLAND_DISMISS_VELOCITY_THRESHOLD_PX))
        // No velocity and no offset: never dismissed regardless of sign edge cases.
        assertFalse(islandSwipeDismissed(offsetPx = 0f, widthPx = width, velocityPx = 0f))
    }

    @Test fun `a pill with no measured width never counts as dismissed`() {
        assertFalse(islandSwipeDismissed(offsetPx = 1_000f, widthPx = 0f, velocityPx = 10_000f))
        assertFalse(islandSwipeDismissed(offsetPx = 1_000f, widthPx = -10f, velocityPx = 10_000f))
    }

    // --- digit roll ---

    @Test fun `digit roll against a null previous marks every character as freshly grown`() {
        assertEquals(listOf(DigitRollChar(null, '0'), DigitRollChar(null, ':'), DigitRollChar(null, '0'), DigitRollChar(null, '5')),
            digitRollDiff(null, "0:05"))
    }

    @Test fun `digit roll of equal-length strings pairs characters positionally`() {
        assertEquals(listOf(DigitRollChar('0', '0'), DigitRollChar(':', ':'), DigitRollChar('0', '0'), DigitRollChar('5', '6')),
            digitRollDiff("0:05", "0:06"))
        // A colon and any unchanged digit report old == new; the caller can skip animating those.
        val diff = digitRollDiff("1:30", "1:30")
        assertTrue(diff.all { it.old == it.new })
    }

    @Test fun `digit roll aligns from the right so a widening chronometer grows a fresh leading digit`() {
        // "9:59" -> "10:00": the new leading '1' has no predecessor, everything else shifts one right.
        assertEquals(
            listOf(DigitRollChar(null, '1'), DigitRollChar('9', '0'), DigitRollChar(':', ':'),
                DigitRollChar('5', '0'), DigitRollChar('9', '0')),
            digitRollDiff("9:59", "10:00"),
        )
    }

    // --- idle breathing ---

    @Test fun `breathing only runs with an active chronometer and without reduced motion`() {
        assertTrue(islandBreathingEnabled(hasActiveChronometer = true, animatorDurationScale = 1f))
        assertFalse(islandBreathingEnabled(hasActiveChronometer = false, animatorDurationScale = 1f))
        assertFalse(islandBreathingEnabled(hasActiveChronometer = true, animatorDurationScale = 0f))
        assertFalse(islandBreathingEnabled(hasActiveChronometer = false, animatorDurationScale = 0f))
    }

    @Test fun `breathing scale is a 2 percent sine cycle every 4 seconds, starting at 1`() {
        assertEquals(4_000L, ISLAND_BREATHING_PERIOD_MS)
        assertEquals(0.02f, ISLAND_BREATHING_AMPLITUDE)
        assertEquals(1f, islandBreathingScale(0L), 1e-4f)
        assertEquals(1f + ISLAND_BREATHING_AMPLITUDE, islandBreathingScale(ISLAND_BREATHING_PERIOD_MS / 4), 1e-3f)
        assertEquals(1f, islandBreathingScale(ISLAND_BREATHING_PERIOD_MS / 2), 1e-3f)
        assertEquals(1f - ISLAND_BREATHING_AMPLITUDE, islandBreathingScale(ISLAND_BREATHING_PERIOD_MS * 3 / 4), 1e-3f)
        // A full period returns to the same phase; never outside the amplitude envelope.
        assertEquals(islandBreathingScale(0L), islandBreathingScale(ISLAND_BREATHING_PERIOD_MS), 1e-3f)
        (0..40).forEach { i ->
            val scale = islandBreathingScale(i * 137L)
            assertTrue(abs(scale - 1f) <= ISLAND_BREATHING_AMPLITUDE + 1e-4f)
        }
    }

    // --- "Ostrůvek do plochy": pill top, card size, card rect (cover/inner, cap, seam) ---

    @Test fun `pill top advances by one pill's height plus the rail gap per index`() {
        assertEquals(16f, islandPillTopDp(16f, 0), 1e-4f)
        assertEquals(16f + RAIL_ISLAND_ITEM_HEIGHT_DP + RAIL_GAP_DP, islandPillTopDp(16f, 1), 1e-4f)
        assertEquals(16f + 2 * (RAIL_ISLAND_ITEM_HEIGHT_DP + RAIL_GAP_DP), islandPillTopDp(16f, 2), 1e-4f)
    }

    @Test fun `card width is the pane minus two margins, capped`() {
        assertEquals(360f - 2 * ISLAND_CARD_MARGIN_DP, islandCardWidthDp(360f, ISLAND_CARD_MAX_WIDTH_DP), 1e-4f)
        // A pane wide enough that the margin alone would exceed the cap: the cap wins (the inner display).
        assertEquals(ISLAND_CARD_MAX_WIDTH_DP, islandCardWidthDp(1000f, ISLAND_CARD_MAX_WIDTH_DP), 1e-4f)
        // An uncapped call (the cover, task spec: "screen width - 2x16dp"): the margin always wins.
        assertEquals(555f - 2 * ISLAND_CARD_MARGIN_DP, islandCardWidthDp(555f, Float.MAX_VALUE), 1e-4f)
        // Never negative for a pane narrower than the two margins.
        assertEquals(0f, islandCardWidthDp(10f, ISLAND_CARD_MAX_WIDTH_DP), 1e-4f)
    }

    @Test fun `card height is fixed by content, media taller on inner than cover, everything else compact`() {
        assertEquals(160f, ISLAND_CARD_MEDIA_HEIGHT_INNER_DP)
        assertEquals(152f, ISLAND_CARD_MEDIA_HEIGHT_COVER_DP)
        assertEquals(96f, ISLAND_CARD_DEFAULT_HEIGHT_DP)
        assertEquals(ISLAND_CARD_MEDIA_HEIGHT_INNER_DP, islandCardHeightDp(hasMedia = true, isCover = false))
        assertEquals(ISLAND_CARD_MEDIA_HEIGHT_COVER_DP, islandCardHeightDp(hasMedia = true, isCover = true))
        assertEquals(ISLAND_CARD_DEFAULT_HEIGHT_DP, islandCardHeightDp(hasMedia = false, isCover = false))
        assertEquals(ISLAND_CARD_DEFAULT_HEIGHT_DP, islandCardHeightDp(hasMedia = false, isCover = true))
    }

    @Test fun `card rect keeps the pill's top, pins its right edge to the rail, and grows left`() {
        // Inner pane: seam at 100, rail's right edge at 100 + 444 = 544 (INNER_PANE_WIDTH_DP-ish span).
        val inner = IslandCardAnchor(islandTopDp = 40f, pillWidthDp = 80f, paneStartDp = 100f, paneWidthDp = 444f, isCover = false)
        val rect = islandCardRect(inner, pillTopDp = 40f, hasMedia = false)
        assertEquals(40f, rect.topDp, 1e-4f)
        assertEquals(96f, rect.heightDp, 1e-4f)
        assertEquals(96f, rect.bottomDp - rect.topDp, 1e-4f)
        val expectedWidth = islandCardWidthDp(444f, ISLAND_CARD_MAX_WIDTH_DP)
        assertEquals(expectedWidth, rect.widthDp, 1e-4f)
        // Right edge fixed at the rail's own edge (paneStart + paneWidth), regardless of width.
        assertEquals(100f + 444f, rect.rightDp, 1e-4f)
        // Never crosses the seam: left edge stays at or past paneStartDp.
        assertTrue(rect.leftDp >= inner.paneStartDp - 1e-4f)
    }

    @Test fun `card rect on the cover has no width cap, only the screen-edge margins`() {
        val cover = IslandCardAnchor(islandTopDp = 16f, pillWidthDp = 80f, paneStartDp = 0f, paneWidthDp = 555f, isCover = true)
        val rect = islandCardRect(cover, pillTopDp = 16f, hasMedia = true)
        assertEquals(555f - 2 * ISLAND_CARD_MARGIN_DP, rect.widthDp, 1e-4f)
        assertEquals(ISLAND_CARD_MEDIA_HEIGHT_COVER_DP, rect.heightDp, 1e-4f)
        // Right edge pinned to the rail (paneStart + paneWidth); the left margin is what's left over.
        assertEquals(555f, rect.rightDp, 1e-4f)
        assertEquals(2 * ISLAND_CARD_MARGIN_DP, rect.leftDp, 1e-4f)
    }

    @Test fun `card rect on the inner is capped at 420dp even on a very wide pane`() {
        val wideInner = IslandCardAnchor(islandTopDp = 16f, pillWidthDp = 80f, paneStartDp = 0f, paneWidthDp = 1200f, isCover = false)
        val rect = islandCardRect(wideInner, pillTopDp = 16f, hasMedia = true)
        assertEquals(ISLAND_CARD_MAX_WIDTH_DP, rect.widthDp, 1e-4f)
        // Right edge still pinned to the rail, so a capped width leaves a gap before the seam, not after it.
        assertEquals(1200f, rect.rightDp, 1e-4f)
        assertTrue(rect.leftDp > wideInner.paneStartDp)
    }

    @Test fun `card rect never puts the left edge past the seam even for a too-narrow pane`() {
        val cramped = IslandCardAnchor(islandTopDp = 16f, pillWidthDp = 80f, paneStartDp = 500f, paneWidthDp = 10f, isCover = false)
        val rect = islandCardRect(cramped, pillTopDp = 16f, hasMedia = false)
        assertEquals(0f, rect.widthDp, 1e-4f)
        // Zero width collapses left onto the (still seam-safe) right edge, not past the seam.
        assertEquals(510f, rect.leftDp, 1e-4f)
        assertTrue(rect.leftDp >= cramped.paneStartDp)
    }
}
