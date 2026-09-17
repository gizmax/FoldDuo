package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_ALERT_PEAK_SCALE
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_BELOW_HEIGHT_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_BELOW_TOP_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_COLLAPSE_THRESHOLD_FRACTION
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_COLLAPSE_VELOCITY_THRESHOLD_PX
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_CUTOUT_GAP_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_EXCLUSION_MARGIN_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_EXPANDED_MAX_WIDTH_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_HEIGHT_MARGIN_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_HIT_SLOP_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_IDLE_TIMEOUT_MS
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_LEADING_ICON_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_LEADING_INSET_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_LEADING_SLOT_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_MEDIA_PAUSE_GRACE_MS
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_MINIMAL_SECONDARY_DIAMETER_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_MINIMAL_SECONDARY_GAP_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_MIN_HEIGHT_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_MORPH_CARD_CORNER_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_NONE_MARGIN_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_PRESS_SCALE
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_ROW1_CAMERA_GAP_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_ROW1_INSET_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_ROW1_LEFT_COL_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_SIDE_MARGIN_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_STEPPER_TRACK_INSET_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_SWITCH_THRESHOLD_FRACTION
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_SWITCH_VELOCITY_THRESHOLD_PX
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_TIMER_FINISHED_GRACE_MS
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_TRAILING_INSET_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_TRAILING_SLOT_DP
import cz.pflanzer.foldduo.cameraisland.CAMERA_ISLAND_TRAILING_VALUE_DP
import cz.pflanzer.foldduo.cameraisland.CameraCutoutRect
import cz.pflanzer.foldduo.cameraisland.CameraIslandContent
import cz.pflanzer.foldduo.cameraisland.CameraIslandRect
import cz.pflanzer.foldduo.cameraisland.CameraIslandVisualState
import cz.pflanzer.foldduo.cameraisland.cameraIslandAlertTriggered
import cz.pflanzer.foldduo.cameraisland.cameraIslandAppearAlpha
import cz.pflanzer.foldduo.cameraisland.cameraIslandContainerAlpha
import cz.pflanzer.foldduo.cameraisland.cameraIslandCornerDp
import cz.pflanzer.foldduo.cameraisland.cameraIslandDisappearAlpha
import cz.pflanzer.foldduo.cameraisland.cameraIslandExpandedExclusionZone
import cz.pflanzer.foldduo.cameraisland.cameraIslandExpandedHeightForKind
import cz.pflanzer.foldduo.cameraisland.cameraIslandExpandedRect
import cz.pflanzer.foldduo.cameraisland.cameraIslandFallbackCutout
import cz.pflanzer.foldduo.cameraisland.cameraIslandHeightProgress
import cz.pflanzer.foldduo.cameraisland.cameraIslandIdleTimedOut
import cz.pflanzer.foldduo.cameraisland.cameraIslandIncomingContentAlpha
import cz.pflanzer.foldduo.cameraisland.cameraIslandIncomingContentScale
import cz.pflanzer.foldduo.cameraisland.cameraIslandInitials
import cz.pflanzer.foldduo.cameraisland.cameraIslandLiveItems
import cz.pflanzer.foldduo.cameraisland.cameraIslandMinimalSecondaryRect
import cz.pflanzer.foldduo.cameraisland.cameraIslandMorphRect
import cz.pflanzer.foldduo.cameraisland.cameraIslandNoneRect
import cz.pflanzer.foldduo.cameraisland.cameraIslandOutgoingContentAlpha
import cz.pflanzer.foldduo.cameraisland.cameraIslandOutgoingContentScale
import cz.pflanzer.foldduo.cameraisland.cameraIslandPillCameraZoneWidthDp
import cz.pflanzer.foldduo.cameraisland.cameraIslandRect
import cz.pflanzer.foldduo.cameraisland.cameraIslandRow1RightColWidthDp
import cz.pflanzer.foldduo.cameraisland.cameraIslandShouldAutoCollapse
import cz.pflanzer.foldduo.cameraisland.cameraIslandSplitEtaValue
import cz.pflanzer.foldduo.cameraisland.cameraIslandStepperDotXDp
import cz.pflanzer.foldduo.cameraisland.cameraIslandStepperTrackEndDp
import cz.pflanzer.foldduo.cameraisland.cameraIslandSwipeCollapsed
import cz.pflanzer.foldduo.cameraisland.cameraIslandSwipeSwitched
import cz.pflanzer.foldduo.cameraisland.cameraIslandSwitchedIndex
import cz.pflanzer.foldduo.cameraisland.cameraIslandTrailingContentWidthDp
import cz.pflanzer.foldduo.cameraisland.cameraIslandVisualState
import cz.pflanzer.foldduo.cameraisland.cameraIslandWidthProgress
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.IslandMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B61 "Ostrov kolem kamery": pure geometry/state-machine coverage for `cameraisland/CameraIslandGeometry.kt`. */
class CameraIslandGeometryTest {

    // --- Fold 8 cover cutout fixture: 104 px at density 2.25, centred bounding rect 589-659 px. ---
    private val fold8CoverCutout = CameraCutoutRect(leftDp = 589f / 2.25f, topDp = 0f, widthDp = 70f / 2.25f, heightDp = 104f / 2.25f)

    // --- cameraIslandRect (populated compact/minimal pill; the "no content" shape is a pure
    // geometry utility only — CameraIsland.kt never renders it for HIDDEN any more) -------------

    @Test fun `populated pill hugs the cutout with 6dp height margin`() {
        // Cutout tall enough (40dp) that 40+6=46 sits above the 36dp floor — this test is about
        // the +6dp margin rule (mock: "výška 46 = výřez 40 + 6"), not the floor (see below).
        val cutout = CameraCutoutRect(leftDp = 100f, topDp = 10f, widthDp = 40f, heightDp = 40f)
        val rect = cameraIslandRect(cutout)
        assertEquals(6f, CAMERA_ISLAND_HEIGHT_MARGIN_DP, 0f)
        assertEquals(40f + CAMERA_ISLAND_HEIGHT_MARGIN_DP, rect.heightDp, 1e-4f)
        assertEquals(46f, rect.heightDp, 1e-4f)
        assertEquals(40f + 2f * CAMERA_ISLAND_SIDE_MARGIN_DP, rect.widthDp, 1e-4f)
        // Centred on the cutout both ways.
        assertEquals(cutout.centerXDp, rect.centerXDp, 1e-4f)
        assertEquals(cutout.topDp - CAMERA_ISLAND_HEIGHT_MARGIN_DP / 2f, rect.topDp, 1e-4f)
    }

    @Test fun `pill height floors at 36dp even for a very short cutout`() {
        val tiny = CameraCutoutRect(leftDp = 0f, topDp = 0f, widthDp = 10f, heightDp = 2f)
        assertEquals(CAMERA_ISLAND_MIN_HEIGHT_DP, cameraIslandRect(tiny).heightDp, 1e-4f)
    }

    @Test fun `real Fold 8 cover cutout produces the task's own numbers with no content`() {
        val rect = cameraIslandRect(fold8CoverCutout)
        assertEquals(fold8CoverCutout.widthDp + 2f * CAMERA_ISLAND_SIDE_MARGIN_DP, rect.widthDp, 1e-3f)
        assertEquals(fold8CoverCutout.heightDp + CAMERA_ISLAND_HEIGHT_MARGIN_DP, rect.heightDp, 1e-3f)
    }

    @Test fun `compact content grows the pill along its own leading-inset-icon-gap and gap-value-trailing-inset grid`() {
        val cutout = CameraCutoutRect(leftDp = 200f, topDp = 0f, widthDp = 30f, heightDp = 20f)
        val rect = cameraIslandRect(cutout, CameraIslandContent.COMPACT)
        // 12dp inset + 24dp icon (the leading slot IS the icon, no extra floor) + 10dp gap on the left.
        assertEquals(12f + CAMERA_ISLAND_LEADING_SLOT_DP + 10f, cutout.leftDp - rect.leftDp, 1e-4f)
        assertEquals(46f, cutout.leftDp - rect.leftDp, 1e-4f)
        // 10dp gap + the (fallback, unmeasured) value slot + 14dp inset on the right.
        assertEquals(10f + CAMERA_ISLAND_TRAILING_VALUE_DP + 14f, rect.rightDp - cutout.rightDp, 1e-4f)
        // The cutout itself never moves relative to the window regardless of pill growth.
        assertEquals(200f, cutout.leftDp, 0f)
    }

    // --- Tom's 17. 9. correction: the trailing value never wraps, the pill grows to fit it -----

    @Test fun `compact pill width grows to fit the measured trailing value width, never wrapping`() {
        val cutout = CameraCutoutRect(leftDp = 100f, topDp = 0f, widthDp = 27f, heightDp = 40f)
        val measuredValueWidthDp = 44f
        val content = CameraIslandContent(leadingWidthDp = CAMERA_ISLAND_LEADING_ICON_DP, trailingWidthDp = measuredValueWidthDp)
        val rect = cameraIslandRect(cutout, content)
        // Pill width = leading slot (12 inset + 24 icon + 10 gap) + camera zone (the cutout itself)
        // + measured value width + paddings (10 gap + 14 inset) — Tom's 17. 9. correction's own
        // breakdown, for a 44dp-wide value: 46 + 27 + 44 + 24 = 141.
        val leadingSlot = CAMERA_ISLAND_LEADING_INSET_DP + CAMERA_ISLAND_LEADING_ICON_DP + CAMERA_ISLAND_CUTOUT_GAP_DP
        val cameraZone = cutout.widthDp
        val paddings = CAMERA_ISLAND_CUTOUT_GAP_DP + CAMERA_ISLAND_TRAILING_INSET_DP
        val expectedWidth = leadingSlot + cameraZone + measuredValueWidthDp + paddings
        assertEquals(141f, expectedWidth, 1e-3f)
        assertEquals(expectedWidth, rect.widthDp, 1e-3f)
        // A wider value grows the pill further — it is never clamped down to force a wrap.
        val wider = cameraIslandRect(cutout, content.copy(trailingWidthDp = 90f))
        assertTrue(wider.widthDp > rect.widthDp)
    }

    @Test fun `trailing content width recovers exactly the measured value width baked into a rect`() {
        val cutout = CameraCutoutRect(leftDp = 50f, topDp = 0f, widthDp = 27f, heightDp = 40f)
        val content = CameraIslandContent(leadingWidthDp = CAMERA_ISLAND_LEADING_ICON_DP, trailingWidthDp = 44f)
        val rect = cameraIslandRect(cutout, content)
        assertEquals(44f, cameraIslandTrailingContentWidthDp(rect, cutout), 1e-3f)
    }

    @Test fun `the pill's own camera zone is the cutout width plus 2x8dp`() {
        val cutout = CameraCutoutRect(leftDp = 0f, topDp = 0f, widthDp = 27f, heightDp = 40f)
        assertEquals(43f, cameraIslandPillCameraZoneWidthDp(cutout), 1e-4f)
        assertEquals(CAMERA_ISLAND_EXCLUSION_MARGIN_DP, 8f, 0f)
    }

    @Test fun `a wider trailing slot than the 32dp floor grows only that side`() {
        val cutout = CameraCutoutRect(leftDp = 0f, topDp = 0f, widthDp = 30f, heightDp = 20f)
        val wide = cameraIslandRect(cutout, CameraIslandContent(leadingWidthDp = CAMERA_ISLAND_LEADING_SLOT_DP, trailingWidthDp = 80f))
        val narrow = cameraIslandRect(cutout, CameraIslandContent.COMPACT)
        assertEquals(narrow.leftDp, wide.leftDp, 1e-4f)
        assertTrue(wide.rightDp > narrow.rightDp)
    }

    // --- minimal state: primary pill is compact-shaped, second activity is a detached circle ---

    @Test fun `compact content's leading slot is exactly the 24dp icon, minimal reuses the same shape`() {
        // Mock item 1: "levý slot 12 + 24 + 10" — the leading slot IS the icon, no wider floor.
        // Minimal's own primary pill reuses this exact shape ("primary pill as compact"), so there
        // is no separate minimal-content constant to drift out of sync with it.
        assertEquals(CAMERA_ISLAND_LEADING_SLOT_DP, CameraIslandContent.COMPACT.leadingWidthDp, 0f)
        assertEquals(24f, CAMERA_ISLAND_LEADING_SLOT_DP, 0f)
        assertEquals(CAMERA_ISLAND_LEADING_ICON_DP, CAMERA_ISLAND_LEADING_SLOT_DP, 0f)
        assertTrue(CAMERA_ISLAND_TRAILING_VALUE_DP >= CAMERA_ISLAND_TRAILING_SLOT_DP)
    }

    @Test fun `minimal's secondary activity is a 36dp circle detached 8dp to the right of the primary pill`() {
        val cutout = CameraCutoutRect(leftDp = 100f, topDp = 0f, widthDp = 30f, heightDp = 20f)
        val primary = cameraIslandRect(cutout, CameraIslandContent.COMPACT)
        val secondary = cameraIslandMinimalSecondaryRect(primary)
        assertEquals(CAMERA_ISLAND_MINIMAL_SECONDARY_DIAMETER_DP, secondary.widthDp, 1e-4f)
        assertEquals(CAMERA_ISLAND_MINIMAL_SECONDARY_DIAMETER_DP, secondary.heightDp, 1e-4f)
        assertEquals(primary.rightDp + CAMERA_ISLAND_MINIMAL_SECONDARY_GAP_DP, secondary.leftDp, 1e-4f)
        assertEquals(primary.centerYDp, secondary.centerYDp, 1e-4f)
    }

    // --- fallback cutout (no real cutout on this panel) ---------------------------------------

    @Test fun `fallback cutout centres in the given pane and yields a floor-height pill with no content`() {
        val fallback = cameraIslandFallbackCutout(paneStartDp = 500f, paneWidthDp = 400f)
        assertEquals(500f + 200f, fallback.centerXDp, 1e-4f)
        assertEquals(0f, fallback.widthDp, 0f)
        assertEquals(0f, fallback.heightDp, 0f)
        val rect = cameraIslandRect(fallback)
        assertEquals(CAMERA_ISLAND_MIN_HEIGHT_DP, rect.heightDp, 1e-4f)
        assertEquals(2f * CAMERA_ISLAND_SIDE_MARGIN_DP, rect.widthDp, 1e-4f)
    }

    @Test fun `fallback on the cover (paneStart 0) centres in the whole screen width`() {
        val fallback = cameraIslandFallbackCutout(paneStartDp = 0f, paneWidthDp = 555f)
        assertEquals(277.5f, fallback.centerXDp, 1e-3f)
    }

    // --- "nothing live": the seed/sink rect the pill grows out of / shrinks into ---------------

    @Test fun `none rect is the cutout expanded by 4dp on every side`() {
        val cutout = CameraCutoutRect(leftDp = 100f, topDp = 5f, widthDp = 27f, heightDp = 40f)
        val rect = cameraIslandNoneRect(cutout)
        assertEquals(cutout.leftDp - CAMERA_ISLAND_NONE_MARGIN_DP, rect.leftDp, 1e-4f)
        assertEquals(cutout.topDp - CAMERA_ISLAND_NONE_MARGIN_DP, rect.topDp, 1e-4f)
        assertEquals(cutout.widthDp + 2f * CAMERA_ISLAND_NONE_MARGIN_DP, rect.widthDp, 1e-4f)
        assertEquals(cutout.heightDp + 2f * CAMERA_ISLAND_NONE_MARGIN_DP, rect.heightDp, 1e-4f)
        assertEquals(4f, CAMERA_ISLAND_NONE_MARGIN_DP, 0f)
    }

    @Test fun `container alpha is zero throughout a transition that starts and ends hidden`() {
        assertEquals(0f, cameraIslandContainerAlpha(CameraIslandVisualState.HIDDEN, CameraIslandVisualState.HIDDEN, 0f), 0f)
        assertEquals(0f, cameraIslandContainerAlpha(CameraIslandVisualState.HIDDEN, CameraIslandVisualState.HIDDEN, 0.5f), 0f)
        assertEquals(0f, cameraIslandContainerAlpha(CameraIslandVisualState.HIDDEN, CameraIslandVisualState.HIDDEN, 1f), 0f)
    }

    @Test fun `appearing out of hidden fades in over the first 30pct of the morph`() {
        assertEquals(0f, cameraIslandAppearAlpha(0f), 1e-4f)
        assertEquals(0.5f, cameraIslandAppearAlpha(0.15f), 1e-4f)
        assertEquals(1f, cameraIslandAppearAlpha(0.3f), 1e-4f)
        assertEquals(1f, cameraIslandAppearAlpha(1f), 1e-4f)
        assertEquals(cameraIslandAppearAlpha(0.2f), cameraIslandContainerAlpha(CameraIslandVisualState.HIDDEN, CameraIslandVisualState.COMPACT, 0.2f), 1e-4f)
    }

    @Test fun `disappearing into hidden fades out over the last 30pct of the morph`() {
        assertEquals(1f, cameraIslandDisappearAlpha(0f), 1e-4f)
        assertEquals(1f, cameraIslandDisappearAlpha(0.7f), 1e-4f)
        assertEquals(0.5f, cameraIslandDisappearAlpha(0.85f), 1e-4f)
        assertEquals(0f, cameraIslandDisappearAlpha(1f), 1e-4f)
        assertEquals(cameraIslandDisappearAlpha(0.9f), cameraIslandContainerAlpha(CameraIslandVisualState.COMPACT, CameraIslandVisualState.HIDDEN, 0.9f), 1e-4f)
    }

    @Test fun `container alpha is fully opaque for any transition that neither starts nor ends hidden`() {
        assertEquals(1f, cameraIslandContainerAlpha(CameraIslandVisualState.COMPACT, CameraIslandVisualState.MINIMAL, 0f), 0f)
        assertEquals(1f, cameraIslandContainerAlpha(CameraIslandVisualState.MINIMAL, CameraIslandVisualState.EXPANDED, 0.5f), 0f)
        assertEquals(1f, cameraIslandContainerAlpha(CameraIslandVisualState.EXPANDED, CameraIslandVisualState.COMPACT, 1f), 0f)
    }

    // --- expanded rect: fills its own pane minus a 12dp edge margin, never crosses the seam ---

    @Test fun `expanded width is the pane width minus 2x12dp on either pane`() {
        val cutout = CameraCutoutRect(leftDp = 270f, topDp = 0f, widthDp = 30f, heightDp = 20f)
        val cover = cameraIslandExpandedRect(cutout, paneStartDp = 0f, paneWidthDp = 475f, isCover = true, contentHeightDp = 130f)
        assertEquals(475f - 2f * CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP, cover.widthDp, 1e-4f)
        // A wide pane caps the card at CAMERA_ISLAND_EXPANDED_MAX_WIDTH_DP, centred on the camera.
        val inner = cameraIslandExpandedRect(cutout, paneStartDp = 0f, paneWidthDp = 700f, isCover = false, contentHeightDp = 130f)
        assertEquals(CAMERA_ISLAND_EXPANDED_MAX_WIDTH_DP, inner.widthDp, 1e-4f)
        assertEquals(cutout.centerXDp - CAMERA_ISLAND_EXPANDED_MAX_WIDTH_DP / 2f, inner.leftDp, 1e-4f)
    }

    @Test fun `expanded height is clamped into the 100-168dp band`() {
        val cutout = CameraCutoutRect(leftDp = 100f, topDp = 5f, widthDp = 30f, heightDp = 20f)
        val tooShort = cameraIslandExpandedRect(cutout, 0f, 500f, isCover = false, contentHeightDp = 10f)
        assertEquals(CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP, tooShort.heightDp, 1e-4f)
        assertEquals(100f, CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP, 0f)
        val tooTall = cameraIslandExpandedRect(cutout, 0f, 500f, isCover = false, contentHeightDp = 999f)
        assertEquals(CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP, tooTall.heightDp, 1e-4f)
        assertEquals(168f, CAMERA_ISLAND_EXPANDED_MAX_HEIGHT_DP, 0f)
        // Grows downward from the cutout's own top.
        assertEquals(5f, tooShort.topDp, 0f)
    }

    // --- per-kind expanded height: 156dp ("row1": transport/media), 100dp ("below": the rest) --

    @Test fun `expanded height for kind is 168dp for transport, 156dp for media, 100dp for everything else`() {
        assertEquals(168f, cameraIslandExpandedHeightForKind(IslandKind.TRANSPORT), 0f)
        assertEquals(156f, cameraIslandExpandedHeightForKind(IslandKind.MEDIA), 0f)
        assertEquals(100f, cameraIslandExpandedHeightForKind(IslandKind.TIMER), 0f)
        assertEquals(100f, cameraIslandExpandedHeightForKind(IslandKind.CALL), 0f)
        assertEquals(100f, cameraIslandExpandedHeightForKind(IslandKind.NAVIGATION), 0f)
        assertEquals(100f, cameraIslandExpandedHeightForKind(IslandKind.PROGRESS), 0f)
        assertEquals(100f, cameraIslandExpandedHeightForKind(IslandKind.WORKOUT), 0f)
        assertEquals(100f, cameraIslandExpandedHeightForKind(IslandKind.OTHER), 0f)
    }

    @Test fun `expanded card never crosses the seam on a narrow inner pane`() {
        // A right pane starting at 400dp, only 120dp wide.
        val cutout = CameraCutoutRect(leftDp = 410f, topDp = 0f, widthDp = 20f, heightDp = 20f)
        val rect = cameraIslandExpandedRect(cutout, paneStartDp = 400f, paneWidthDp = 120f, isCover = false, contentHeightDp = 120f)
        assertEquals(400f + CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP, rect.leftDp, 1e-4f)
        assertTrue(rect.rightDp <= 400f + 120f + 1e-4f)
        assertEquals(120f - 2f * CAMERA_ISLAND_EXPANDED_EDGE_MARGIN_DP, rect.widthDp, 1e-4f)
    }

    // --- expanded exclusion zone: nothing drawn where the cutout physically is ------------------

    @Test fun `exclusion zone is cutout width plus 16dp, cutout-height tall, centred on the cutout`() {
        val cutout = CameraCutoutRect(leftDp = 270f, topDp = 0f, widthDp = 27f, heightDp = 40f)
        val card = cameraIslandExpandedRect(cutout, paneStartDp = 0f, paneWidthDp = 475f, isCover = true, contentHeightDp = 130f)
        val zone = cameraIslandExpandedExclusionZone(cutout, card)
        assertEquals(27f + 16f, zone.widthDp, 1e-4f)
        assertEquals(40f, zone.heightDp, 1e-4f)
        assertEquals(0f, zone.topDp, 0f)
        // Centred on the cutout, expressed relative to the card's own left edge.
        assertEquals(cutout.centerXDp - card.leftDp, zone.leftDp + zone.widthDp / 2f, 1e-3f)
    }

    // --- morph rect: width-first, height-follow -------------------------------------------------

    @Test fun `width and height sub-progress windows`() {
        assertEquals(0f, cameraIslandWidthProgress(0f), 1e-4f)
        assertEquals(1f, cameraIslandWidthProgress(0.55f), 1e-4f)
        assertEquals(1f, cameraIslandWidthProgress(1f), 1e-4f)
        assertEquals(0f, cameraIslandHeightProgress(0.25f), 1e-4f)
        assertEquals(0f, cameraIslandHeightProgress(0f), 1e-4f)
        assertEquals(1f, cameraIslandHeightProgress(1f), 1e-4f)
    }

    @Test fun `morph rect lerps every edge between the two states, reproducing endpoints exactly`() {
        val from = CameraIslandRect(0f, 0f, 100f, 40f)
        val to = CameraIslandRect(50f, 10f, 320f, 120f)
        assertEquals(from, cameraIslandMorphRect(0f, from, to))
        assertEquals(to, cameraIslandMorphRect(1f, from, to))
    }

    @Test fun `morph rect grows width well before height at the halfway point`() {
        val from = CameraIslandRect(0f, 0f, 100f, 40f)
        val to = CameraIslandRect(50f, 10f, 320f, 120f)
        val mid = cameraIslandMorphRect(0.5f, from, to)
        // Width's own window (0..0.55) is 0.5/0.55 = 0.909 done; height's (0.25..1) is only 0.333 done.
        val widthT = cameraIslandWidthProgress(0.5f)
        val heightT = cameraIslandHeightProgress(0.5f)
        assertEquals(100f + (320f - 100f) * widthT, mid.widthDp, 1e-3f)
        assertEquals(40f + (120f - 40f) * heightT, mid.heightDp, 1e-3f)
        assertTrue(widthT > heightT)
    }

    // --- corner radius tied to the live rect height ---------------------------------------------

    @Test fun `corner radius is half the height while short, capped at the fixed 40dp card corner once tall`() {
        assertEquals(44f, CAMERA_ISLAND_MORPH_CARD_CORNER_DP, 0f)
        assertEquals(18f, cameraIslandCornerDp(36f), 1e-4f)
        assertEquals(23f, cameraIslandCornerDp(46f), 1e-4f)
        assertEquals(CAMERA_ISLAND_MORPH_CARD_CORNER_DP, cameraIslandCornerDp(100f), 1e-4f)
        assertEquals(CAMERA_ISLAND_MORPH_CARD_CORNER_DP, cameraIslandCornerDp(156f), 1e-4f)
        // The exact crossover: height/2 == 40dp at height 80dp.
        assertEquals(40f, cameraIslandCornerDp(80f), 1e-4f)
    }

    // --- morph content crossfade windows: outgoing 0-35%, incoming 55-100% ---------------------

    @Test fun `content crossfade fades out over the first 35pct and in over the last 45pct, with a gap between`() {
        assertEquals(1f, cameraIslandOutgoingContentAlpha(0f), 1e-4f)
        assertEquals(0f, cameraIslandOutgoingContentAlpha(0.35f), 1e-4f)
        assertEquals(0f, cameraIslandOutgoingContentAlpha(0.5f), 1e-4f)
        assertEquals(0f, cameraIslandIncomingContentAlpha(0f), 1e-4f)
        assertEquals(0f, cameraIslandIncomingContentAlpha(0.55f), 1e-4f)
        assertEquals(1f, cameraIslandIncomingContentAlpha(1f), 1e-4f)
        assertEquals(0.5f, cameraIslandIncomingContentAlpha(0.775f), 1e-4f)
    }

    @Test fun `content crossfade pairs its alpha window with a matching blur-like scale window`() {
        assertEquals(1f, cameraIslandOutgoingContentScale(0f), 1e-4f)
        assertEquals(0.9f, cameraIslandOutgoingContentScale(0.35f), 1e-4f)
        assertEquals(0.94f, cameraIslandIncomingContentScale(0.55f), 1e-4f)
        assertEquals(1f, cameraIslandIncomingContentScale(1f), 1e-4f)
    }

    // --- touch: hit slop + press scale ----------------------------------------------------------

    @Test fun `hit slop and press scale match the task's iOS feel numbers`() {
        assertEquals(12f, CAMERA_ISLAND_HIT_SLOP_DP, 0f)
        assertEquals(0.97f, CAMERA_ISLAND_PRESS_SCALE, 0f)
    }

    // --- state machine: hidden -> compact -> minimal -> expanded ------------------------------

    @Test fun `visual state is hidden with no items regardless of a stale expanded key`() {
        assertEquals(CameraIslandVisualState.HIDDEN, cameraIslandVisualState(emptyList(), null))
        assertEquals(CameraIslandVisualState.HIDDEN, cameraIslandVisualState(emptyList(), "gone"))
    }

    @Test fun `visual state is compact with exactly one item`() {
        assertEquals(CameraIslandVisualState.COMPACT, cameraIslandVisualState(listOf("a"), null))
    }

    @Test fun `visual state is minimal with two or more items and nothing expanded`() {
        assertEquals(CameraIslandVisualState.MINIMAL, cameraIslandVisualState(listOf("a", "b"), null))
        assertEquals(CameraIslandVisualState.MINIMAL, cameraIslandVisualState(listOf("a", "b", "c"), null))
    }

    @Test fun `visual state is expanded only while the expanded key still names a live item`() {
        assertEquals(CameraIslandVisualState.EXPANDED, cameraIslandVisualState(listOf("a", "b"), "a"))
        // A stale key from an item that just disappeared falls back to compact/minimal, not expanded.
        assertEquals(CameraIslandVisualState.MINIMAL, cameraIslandVisualState(listOf("a", "b"), "gone"))
        assertEquals(CameraIslandVisualState.COMPACT, cameraIslandVisualState(listOf("a"), "gone"))
    }

    @Test fun `auto collapse fires only when the expanded key has actually vanished`() {
        assertTrue(cameraIslandShouldAutoCollapse("a", listOf("b")))
        assertFalse(cameraIslandShouldAutoCollapse("a", listOf("a", "b")))
        assertFalse(cameraIslandShouldAutoCollapse(null, listOf("a")))
    }

    @Test fun `idle timeout is exactly 8s and inclusive at the boundary`() {
        assertEquals(8_000L, CAMERA_ISLAND_IDLE_TIMEOUT_MS)
        assertFalse(cameraIslandIdleTimedOut(expandedAtMs = 1_000L, nowMs = 1_000L + CAMERA_ISLAND_IDLE_TIMEOUT_MS - 1L, CAMERA_ISLAND_IDLE_TIMEOUT_MS))
        assertTrue(cameraIslandIdleTimedOut(expandedAtMs = 1_000L, nowMs = 1_000L + CAMERA_ISLAND_IDLE_TIMEOUT_MS, CAMERA_ISLAND_IDLE_TIMEOUT_MS))
    }

    // --- gestures: swipe left/right switches minimal's two activities, swipe up collapses -----

    @Test fun `switching activities needs 35pct distance or an 800px flick`() {
        assertFalse(cameraIslandSwipeSwitched(offsetPx = 30f, widthPx = 100f, velocityPx = 0f))
        assertTrue(cameraIslandSwipeSwitched(offsetPx = 36f, widthPx = 100f, velocityPx = 0f))
        assertTrue(cameraIslandSwipeSwitched(offsetPx = 10f, widthPx = 100f, velocityPx = CAMERA_ISLAND_SWITCH_VELOCITY_THRESHOLD_PX))
        // A flick opposite the drag direction does not count.
        assertFalse(cameraIslandSwipeSwitched(offsetPx = 10f, widthPx = 100f, velocityPx = -CAMERA_ISLAND_SWITCH_VELOCITY_THRESHOLD_PX))
        assertEquals(0.35f, CAMERA_ISLAND_SWITCH_THRESHOLD_FRACTION, 0f)
    }

    @Test fun `switched index always flips between the two minimal slots`() {
        assertEquals(1, cameraIslandSwitchedIndex(0))
        assertEquals(0, cameraIslandSwitchedIndex(1))
    }

    @Test fun `collapsing the expanded card needs 30pct distance or a 900px flick, upward only`() {
        assertFalse(cameraIslandSwipeCollapsed(offsetPx = -20f, heightPx = 120f, velocityPx = 0f))
        assertTrue(cameraIslandSwipeCollapsed(offsetPx = -40f, heightPx = 120f, velocityPx = 0f))
        assertTrue(cameraIslandSwipeCollapsed(offsetPx = -5f, heightPx = 120f, velocityPx = -CAMERA_ISLAND_COLLAPSE_VELOCITY_THRESHOLD_PX))
        assertEquals(0.3f, CAMERA_ISLAND_COLLAPSE_THRESHOLD_FRACTION, 0f)
    }

    // --- alert pulse ---------------------------------------------------------------------------

    @Test fun `alert triggers only on an actual value change, never on first appearance or a no-op update`() {
        assertFalse(cameraIslandAlertTriggered(null, "4 min"))
        assertFalse(cameraIslandAlertTriggered("4 min", "4 min"))
        assertTrue(cameraIslandAlertTriggered("4 min", "3 min"))
        assertFalse(cameraIslandAlertTriggered("4 min", null))
        assertEquals(1.06f, CAMERA_ISLAND_ALERT_PEAK_SCALE, 0f)
    }

    // --- cameraIslandLiveItems: "jen aktuální běžící věci" (Tom, 17. 9.) ---------------------

    private fun item(kind: IslandKind, key: String = kind.name) = IslandItem(key = key, kind = kind)

    @Test fun `call, navigation and workout are always kept`() {
        val items = listOf(item(IslandKind.CALL), item(IslandKind.NAVIGATION), item(IslandKind.WORKOUT))
        assertEquals(items, cameraIslandLiveItems(items, nowMs = 0L))
    }

    @Test fun `other is never kept`() {
        assertTrue(cameraIslandLiveItems(listOf(item(IslandKind.OTHER)), nowMs = 0L).isEmpty())
    }

    @Test fun `transport is kept only once a live-activity adapter recognised an eta, stage or key value`() {
        val bare = item(IslandKind.TRANSPORT)
        assertTrue(cameraIslandLiveItems(listOf(bare), nowMs = 0L).isEmpty())
        assertEquals(1, cameraIslandLiveItems(listOf(bare.copy(etaMs = 12_000L)), nowMs = 0L).size)
        assertEquals(1, cameraIslandLiveItems(listOf(bare.copy(stageIndex = 1)), nowMs = 0L).size)
        assertEquals(1, cameraIslandLiveItems(listOf(bare.copy(keyValue = "4 min")), nowMs = 0L).size)
    }

    @Test fun `timer needs a running chronometer, a finished countdown lingers 60s then drops`() {
        assertTrue(cameraIslandLiveItems(listOf(item(IslandKind.TIMER)), nowMs = 0L).isEmpty())
        // Count-up (stopwatch): always kept regardless of how "old" the base is.
        val stopwatch = item(IslandKind.TIMER).copy(chronometerBase = -1_000_000L, countDown = false)
        assertEquals(1, cameraIslandLiveItems(listOf(stopwatch), nowMs = 0L).size)
        // Countdown not yet at zero.
        val counting = item(IslandKind.TIMER).copy(chronometerBase = 5_000L, countDown = true)
        assertEquals(1, cameraIslandLiveItems(listOf(counting), nowMs = 0L).size)
        // Countdown that just hit zero, and just inside the 60s grace.
        val justFinished = item(IslandKind.TIMER).copy(chronometerBase = 0L, countDown = true)
        assertEquals(1, cameraIslandLiveItems(listOf(justFinished), nowMs = CAMERA_ISLAND_TIMER_FINISHED_GRACE_MS).size)
        // Past the grace window: dropped.
        assertTrue(cameraIslandLiveItems(listOf(justFinished), nowMs = CAMERA_ISLAND_TIMER_FINISHED_GRACE_MS + 1L).isEmpty())
    }

    @Test fun `progress is kept only while indeterminate or below 100pct`() {
        assertEquals(1, cameraIslandLiveItems(listOf(item(IslandKind.PROGRESS).copy(progressIndeterminate = true)), nowMs = 0L).size)
        assertEquals(1, cameraIslandLiveItems(listOf(item(IslandKind.PROGRESS).copy(progress = 0.5f)), nowMs = 0L).size)
        assertTrue(cameraIslandLiveItems(listOf(item(IslandKind.PROGRESS).copy(progress = 1f)), nowMs = 0L).isEmpty())
        assertTrue(cameraIslandLiveItems(listOf(item(IslandKind.PROGRESS)), nowMs = 0L).isEmpty())
    }

    @Test fun `media is kept while playing, or for 30s of grace after it stops`() {
        assertTrue(cameraIslandLiveItems(listOf(item(IslandKind.MEDIA)), nowMs = 0L).isEmpty())
        val playing = item(IslandKind.MEDIA).copy(media = IslandMedia(playing = true, positionUpdatedAtMs = 0L))
        assertEquals(1, cameraIslandLiveItems(listOf(playing), nowMs = 1_000_000L).size)
        val paused = item(IslandKind.MEDIA).copy(media = IslandMedia(playing = false, positionUpdatedAtMs = 0L))
        assertEquals(1, cameraIslandLiveItems(listOf(paused), nowMs = CAMERA_ISLAND_MEDIA_PAUSE_GRACE_MS).size)
        assertTrue(cameraIslandLiveItems(listOf(paused), nowMs = CAMERA_ISLAND_MEDIA_PAUSE_GRACE_MS + 1L).isEmpty())
        // "Stopped" is the same false-playing shape as paused (IslandNotificationListener's own
        // mediaItem() maps both PAUSED and STOPPED to playing=false) — governed by the identical
        // 30s rule, independent of the rail's separate 10-minute resumable-session TTL.
        val longStopped = item(IslandKind.MEDIA).copy(media = IslandMedia(playing = false, positionUpdatedAtMs = 0L))
        assertTrue(cameraIslandLiveItems(listOf(longStopped), nowMs = 9 * 60_000L).isEmpty())
    }

    @Test fun `an empty live filter result is exactly what makes the visual state hidden`() {
        val items = listOf(item(IslandKind.OTHER), item(IslandKind.PROGRESS).copy(progress = 1f))
        val live = cameraIslandLiveItems(items, nowMs = 0L)
        assertTrue(live.isEmpty())
        assertEquals(CameraIslandVisualState.HIDDEN, cameraIslandVisualState(live.map { it.key }, expandedKey = null))
    }

    // --- expanded card "row1" layout: fixed 188/51/rest grid (17. 9. night mock) ----------------

    @Test fun `row1's right column is whatever is left after the insets, the fixed left column and the fixed camera gap`() {
        assertEquals(188f, CAMERA_ISLAND_ROW1_LEFT_COL_DP, 0f)
        assertEquals(51f, CAMERA_ISLAND_ROW1_CAMERA_GAP_DP, 0f)
        // The mock's own 451dp card: 451 - 2x16 - 188 - 51 = 180.
        assertEquals(180f, cameraIslandRow1RightColWidthDp(451f), 1e-3f)
    }

    @Test fun `row1's right column never goes negative on a pane narrower than the mock's own reference`() {
        assertEquals(0f, cameraIslandRow1RightColWidthDp(100f), 0f)
    }

    // --- expanded card stepper geometry: track 24..cardWidth-24, dots evenly spaced -------------

    @Test fun `stepper track spans 24dp to cardWidth-24dp, matching the mock's own 451dp card`() {
        assertEquals(24f, CAMERA_ISLAND_STEPPER_TRACK_INSET_DP, 0f)
        assertEquals(427f, cameraIslandStepperTrackEndDp(451f), 1e-3f)
    }

    @Test fun `stepper dots are evenly spaced across the track, matching the mock's own 0-33-66-100pct`() {
        val cardWidth = 451f
        val start = CAMERA_ISLAND_STEPPER_TRACK_INSET_DP
        val end = cameraIslandStepperTrackEndDp(cardWidth)
        val width = end - start
        assertEquals(start, cameraIslandStepperDotXDp(0, 4, cardWidth), 1e-3f)
        assertEquals(start + width / 3f, cameraIslandStepperDotXDp(1, 4, cardWidth), 1e-3f)
        assertEquals(start + width * 2f / 3f, cameraIslandStepperDotXDp(2, 4, cardWidth), 1e-3f)
        assertEquals(end, cameraIslandStepperDotXDp(3, 4, cardWidth), 1e-3f)
    }

    @Test fun `a single stepper dot sits at the track's own start rather than dividing by zero`() {
        assertEquals(CAMERA_ISLAND_STEPPER_TRACK_INSET_DP, cameraIslandStepperDotXDp(0, 1, 451f), 0f)
    }

    // --- transport's big ETA: "4" + "min" never wraps -------------------------------------------

    @Test fun `splitting an eta value separates the leading digit run from its unit`() {
        assertEquals("4" to "min", cameraIslandSplitEtaValue("4 min"))
        assertEquals("12" to "min", cameraIslandSplitEtaValue("12 min"))
        assertEquals("4" to "", cameraIslandSplitEtaValue("4"))
        // No leading digit run: the whole string is the "big" part, no unit to split off.
        assertEquals("Kurýr" to "", cameraIslandSplitEtaValue("Kurýr"))
    }

    // --- call avatar initials fallback -----------------------------------------------------------

    @Test fun `initials take one letter from each of the first two words, uppercased`() {
        assertEquals("MN", cameraIslandInitials("Marek Novák"))
        assertEquals("JS", cameraIslandInitials("john smith"))
        assertEquals("MA", cameraIslandInitials("Marek"))
        assertEquals("", cameraIslandInitials("   "))
        assertEquals("", cameraIslandInitials(""))
    }
}
