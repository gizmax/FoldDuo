package cz.pflanzer.foldduo

import kotlin.math.abs

/**
 * B42 "Paleta ze švu": pure logic behind the glass quick-settings palette that opens from the
 * hinge seam — gesture eligibility/direction/progress, the recent-apps fallback rule and the
 * clipboard row's visibility rule. Free of Android and Compose types so it runs as plain JUnit
 * under app/src/test; the Compose glue (the gesture modifier, the overlay, the toggle plumbing)
 * lives in SeamPalette.kt. Reuses [Pane] from FoldGeometry.kt (the same Left/Right identity fold
 * avoidance and Spotlight already key off).
 */

/** How close (dp) to the hinge seam a drag must *start* to be eligible for the palette at all. */
internal const val SEAM_PALETTE_START_ZONE_DP = 24f

/** Fraction of the palette's own width a drag must cross before release commits it open. */
internal const val SEAM_PALETTE_OPEN_FRACTION = 0.4f

/** The palette's width as a fraction of its pane's full width. */
internal const val SEAM_PALETTE_WIDTH_FRACTION = 0.6f

/** How many of the most recently used apps the palette's row shows. */
internal const val SEAM_PALETTE_RECENT_APPS_COUNT = 6

/** Minimum horizontal travel (dp), over any vertical drift, before a direction is read at all — keeps a near-vertical drag (or a tap) from resolving a pane. */
internal const val SEAM_PALETTE_DIRECTION_SLOP_DP = 4f

/** True when a drag starting at [startXDp] is inside the seam's [zoneDp]-wide hot zone. */
internal fun seamPaletteEligibleStart(startXDp: Float, seamXDp: Float, zoneDp: Float = SEAM_PALETTE_START_ZONE_DP): Boolean =
    abs(startXDp - seamXDp) <= zoneDp

/**
 * Which pane an eligible seam drag reveals: moving left (negative [deltaXDp]) opens the Left
 * pane (the palette slides out to meet the finger from the seam, i.e. the pane's own trailing
 * edge), moving right opens the Right pane. `null` when the drag has not yet moved far enough
 * past [slopDp], or is more vertical than horizontal (not a palette gesture at all — the shared
 * page recognizer is free to treat it as a normal page swipe or vertical gesture instead).
 */
internal fun seamPaletteTargetPane(deltaXDp: Float, deltaYDp: Float, slopDp: Float = SEAM_PALETTE_DIRECTION_SLOP_DP): Pane? = when {
    abs(deltaXDp) <= slopDp || abs(deltaXDp) <= abs(deltaYDp) -> null
    deltaXDp < 0f -> Pane.Left
    else -> Pane.Right
}

/**
 * How much of the palette's own width is exposed, 0..1, following [deltaXDp] 1:1 with the finger
 * once a pane direction is resolved (0 at the seam, 1 once the finger has travelled a full
 * [paletteWidthDp]). A non-positive [paletteWidthDp] (pane geometry not known yet) never opens.
 */
internal fun seamPaletteDragProgress(deltaXDp: Float, paletteWidthDp: Float): Float =
    if (paletteWidthDp <= 0f) 0f else (abs(deltaXDp) / paletteWidthDp).coerceIn(0f, 1f)

/** Past [openFraction] the palette commits open on release; short of it, it springs back closed. */
internal fun seamPaletteShouldCommitOpen(progress: Float, openFraction: Float = SEAM_PALETTE_OPEN_FRACTION): Boolean =
    progress >= openFraction

// --- Recent apps row ---------------------------------------------------------------------

/**
 * The palette's "recent apps" row, newest first, capped to [limit]: [usageStatsPackages] (real
 * UsageStats recency, when the "usage access" grant is present) when non-empty, else the
 * launcher's own [launchHistoryPackages] (`LauncherState.recentLaunches`). Both are already
 * newest-first package-name lists; this only picks a source and trims/dedupes it — no ranking of
 * its own, unlike Spotlight's query-driven [rankSpotlightApps].
 */
internal fun seamPaletteRecentPackages(
    usageStatsPackages: List<String>,
    launchHistoryPackages: List<String>,
    limit: Int = SEAM_PALETTE_RECENT_APPS_COUNT,
): List<String> = usageStatsPackages.ifEmpty { launchHistoryPackages }.distinct().take(limit)

// --- Clipboard row -------------------------------------------------------------------------

/** The clip text the row would show, trimmed; blank/whitespace-only clips count as "nothing". */
internal fun seamPaletteClipboardText(clip: CharSequence?): String? = clip?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/** The clipboard row only ever shows for non-blank text — never for empty clips or non-text ones (Compose glue passes `null` for those upstream). */
internal fun seamPaletteClipboardVisible(clip: CharSequence?): Boolean = seamPaletteClipboardText(clip) != null
