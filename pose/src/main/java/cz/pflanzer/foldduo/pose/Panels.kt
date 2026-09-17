// Ported from marcoazeem/duo-open (MIT, Copyright (c) 2026 marcoazeem), see docs/upstream/LICENSE-duo-open.
// Adapted for Fold Duo on Galaxy Z Fold 8.
package cz.pflanzer.foldduo.pose

import android.view.Display
import kotlin.math.max
import kotlin.math.min

/**
 * Identifies the physical panel from the display mode. Samsung swaps the panel
 * behind logical display 0, so `displayId` is not an identity; the physical
 * mode size is. Exact Fold 8 sizes first, aspect-ratio fallback second
 * (book-style inner panels are near-square, covers are tall).
 *
 * The fallback already generalizes past the Fold 8: a Galaxy Z Fold 7 (SM-F966B, cover
 * 1080x2520 21:9, inner 1968x2184 near-square) never hits the exact-size branch but classifies
 * correctly on aspect ratio alone (2520/1080 = 2.33 > 1.45 -> Cover; 2184/1968 = 1.11 < 1.45 ->
 * Inner), same as the cover-is-the-smaller-area rule `DeviceProfile.kt` (`:app`) uses at runtime.
 * [COVER_LONG]/[COVER_SHORT]/[INNER_LONG]/[INNER_SHORT] stay as the Fold 8's own measured
 * numbers — a fast-path match plus the reference values `WallpaperFraming.kt`'s and
 * `DeviceProfile.FOLD8`'s tests build from — not a hardcoded assumption for every device.
 */
object Panels {
    const val COVER_LONG = 1972
    const val COVER_SHORT = 1248
    const val INNER_LONG = 2448
    const val INNER_SHORT = 1848
    private const val INNER_MAX_ASPECT = 1.45f

    fun classify(physicalWidth: Int, physicalHeight: Int): Panel {
        val long = max(physicalWidth, physicalHeight)
        val short = min(physicalWidth, physicalHeight)
        if (long <= 0 || short <= 0) return Panel.Unknown
        if (long == INNER_LONG && short == INNER_SHORT) return Panel.Inner
        if (long == COVER_LONG && short == COVER_SHORT) return Panel.Cover
        return if (long.toFloat() / short < INNER_MAX_ASPECT) Panel.Inner else Panel.Cover
    }
}

fun Display?.panel(): Panel {
    val mode = runCatching { this?.mode }.getOrNull() ?: return Panel.Unknown
    return Panels.classify(mode.physicalWidth, mode.physicalHeight)
}
