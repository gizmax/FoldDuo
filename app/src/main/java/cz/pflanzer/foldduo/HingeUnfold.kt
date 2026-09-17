package cz.pflanzer.foldduo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.max

/*
 * IDEAS.md B33, "Rozložení z pantu": while the left half clears on opening (or frosts up while
 * closing), Today's items (icons, folders, widgets — LeadingPane.kt / LauncherScreen.kt's
 * `SharedHomeGrid`, page -1 only) do not just fade in under the frost, they physically unfold
 * from the hinge, like a panel swinging flat out of the seam.
 *
 * The seam is the leading pane's own right edge (the leading pane is the LEFT pane of the open
 * inner display — FoldGeometry.kt, `LocalFoldSeam`/`paneForAnchor`), so the column nearest it is
 * the rightmost one. [unfoldProgress] maps the shared left-half tilt (0..45°, the same scale as
 * `MorphCurve.MAX_TILT` / `FoldShader.MAX_TILT`: 45 = fully frosted/folded, 0 = flat/clear, in
 * either direction) to a per-cell 0..1 progress, staggered by distance-from-seam so the sweep
 * starts at the hinge, with a small per-row bias (paired with `LeadingPane.leadingReadingRank`'s
 * own reading order) so it reads as a diagonal wave, not flat vertical bands. [Modifier.hingeUnfold]
 * turns that into a `rotationY` + `translationX` + `alpha` graphicsLayer, pivoting on the item's
 * own edge nearest the seam.
 *
 * "Zavírání jako Duo" (17. 9. noc), item 3: closing does NOT just replay [unfoldProgress] with a
 * rising tilt. Doing so would have the column *farthest* from the seam finish folding first (its
 * steeper lag-derived slope reaches 0 while `base` is still well short of 0) and the seam column
 * finish last, alongside the frost — the reverse of how a hinge actually folds paper: the part
 * nearest the fold moves first, the far edge follows and settles last, together with the frost
 * reaching full right at the swap (item 2). [closingUnfoldProgress] gives the seam column the
 * LEAD instead of the lag — see its own doc — so the fold reads as starting at the hinge and
 * traveling outward, arriving there a bit AHEAD of the frost.
 *

 * [tilt] must be read live inside the `graphicsLayer` block (not hoisted into a `val` at
 * composition scope), so the ~50 Hz angle samples invalidate only the one layer that changed,
 * never a recomposition of the grid (task B33, point 5). `MorphController.angleTilt` is already
 * a `State<Float>`; `closingTilt` is a plain getter but reads two `State`s internally
 * (`closeFrost.value`, an internal `mutableFloatStateOf`), so reading it from inside the layer
 * block subscribes the layer the same way — no change to UnfoldMorph.kt was needed.
 */

/** Each column further from the seam starts its unfold this fraction of the progress range later. */
private const val COLUMN_LAG_FRACTION = 0.08f

/** Alternate rows lead/lag by this fraction so the sweep reads diagonally, not as flat column bands. */
private const val ROW_BIAS_FRACTION = 0.02f

/** A lag at or beyond this would make a column's own progress unable to reach 1 (or 0) at all; every lag is clamped short of it. */
private const val MAX_LAG_FRACTION = 0.9f

/** Rest-state constants for [Modifier.hingeUnfold] (also used directly by its tests). */
const val UNFOLD_ROTATION_START_DEG = -70f
const val UNFOLD_ALPHA_START = 0.4f
const val UNFOLD_TRANSLATION_START_DP = 12f

/**
 * Unfold progress (0 = folded flat toward the seam, 1 = at rest, flat) of the grid cell at
 * ([row], [column]) of [columns] columns, for the shared left-half [tilt] in degrees (0..
 * [MorphCurve.MAX_TILT], either direction of travel).
 *
 * The base progress is [tilt] linearly re-mapped 45 -> 0 to 0 -> 1 clamped. The column nearest
 * the seam ([columns] - 1, the leading pane's own right edge) has no lag; each column short of it
 * lags by [COLUMN_LAG_FRACTION] of the range, and alternating rows bias by ±[ROW_BIAS_FRACTION]
 * on top of that (so two cells in the same column but different rows are not perfectly in sync
 * either, reading as a diagonal sweep across the grid). The result is re-scaled so every cell,
 * whatever its lag, still spans the full 0..1 range instead of stalling below 1 or above 0.
 *
 * Pure and total: never NaN, always in 0f..1f, monotonic in [tilt] for any fixed cell.
 */
fun unfoldProgress(tilt: Float, column: Int, row: Int, columns: Int): Float {
    val cols = columns.coerceAtLeast(1)
    val base = ((MorphCurve.MAX_TILT - tilt) / MorphCurve.MAX_TILT).coerceIn(0f, 1f)
    val distanceFromSeam = (cols - 1 - column).coerceIn(0, cols - 1)
    val rowBias = if (row % 2 == 0) -ROW_BIAS_FRACTION else ROW_BIAS_FRACTION
    val lag = (distanceFromSeam * COLUMN_LAG_FRACTION + rowBias).coerceIn(0f, MAX_LAG_FRACTION)
    return ((base - lag) / (1f - lag)).coerceIn(0f, 1f)
}

/**
 * "Zavírání jako Duo" item 3, the closing counterpart of [unfoldProgress]: the seam column
 * ([columns] - 1) leads instead of lagging, so it folds first and the far edge (column 0)
 * finishes last, together with the frost (which is always full by the time [tilt] reaches
 * [MorphCurve.MAX_TILT] — item 2). Exactly [unfoldProgress] with the column mirrored
 * (`columns - 1 - column`): the seam's distance-from-itself is 0 either way, so mirroring the
 * column index turns "distance from seam" into "distance from the far edge", which is what a
 * LEAD (biggest for the seam, none for the far edge) needs. Same range/monotonicity guarantees as
 * [unfoldProgress] (it delegates to it entirely).
 */
fun closingUnfoldProgress(tilt: Float, column: Int, row: Int, columns: Int): Float {
    val cols = columns.coerceAtLeast(1)
    return unfoldProgress(tilt, cols - 1 - column.coerceIn(0, cols - 1), row, cols)
}

/** True while a hinge-unfold `graphicsLayer` is actually needed: [tilt] > 0 means at least one
 * cell is still short of [unfoldProgress] == 1 (folded any amount at all toward the seam), so the
 * layer's rotation/translation/alpha do something. At exactly 0 every cell is already at rest
 * (`alpha` 1, `rotationY` 0, `translationX` 0 — see [unfoldProgress]'s own doc: `base` is 1
 * regardless of a cell's lag when `tilt` is 0), so the layer would be a no-op wrapper: pure
 * overhead (its own `RenderNode`, synced every frame right along with every other layer) for zero
 * visual effect. Extracted so both the gate below and its test share the exact boundary. */
internal fun hingeUnfoldLayerNeeded(tilt: Float): Boolean = tilt > 0f

/**
 * Physically unfolds a Today item out of the hinge: `rotationY` from [UNFOLD_ROTATION_START_DEG]
 * (folded toward the seam) to 0 (flat), pivoting on the item's own right edge (the edge nearest
 * the seam — the leading pane is the left pane, FoldGeometry.kt), a matching `translationX` from
 * [UNFOLD_TRANSLATION_START_DP] toward the seam back to 0, and `alpha` from [UNFOLD_ALPHA_START]
 * to 1, all driven by [unfoldProgress] of the live [morph] tilt (the larger of `angleTilt` —
 * opening — and `closingTilt` — closing; the two never move at once).
 *
 * Under [MotionPrefs.enabled] (reduce motion) the rotation and translation are dropped entirely —
 * a plain fade is all that plays. A null [morph] (previews, tests without one wired up) is a
 * no-op.
 *
 * Everything the *animation* needs is read inside the `graphicsLayer` block itself, so only this
 * layer redraws per angle sample — see the file comment. Whether the layer exists AT ALL is a
 * separate, much rarer decision ([hingeUnfoldLayerNeeded], via [idle] below): most of a Home
 * page's life the hinge sits flat (`tilt == 0`, every cell already at rest), so every cell on
 * every page would otherwise carry a permanently no-op `graphicsLayer` — one more `RenderNode` for
 * "Výkon 3"'s sync+draw cost to walk on every frame of an unrelated page swipe. [remember]ing a
 * [derivedStateOf] here means the ~50 Hz tilt samples still flow straight into [idle]'s
 * calculation (a normal state read), but a *reader* of `idle.value` — this function's own `if`
 * below — only recomposes the two times per unfold/close that it actually flips, not once per
 * sample: exactly the same "raw high-rate signal in, rare boolean out" shape as B43's own
 * `squeezeDepthDeg`/`squeezeHeld` fix (MainActivity.kt, Výkon 2).
 */
@Composable
fun Modifier.hingeUnfold(morph: MorphController?, row: Int, column: Int, columns: Int = GRID_COLUMNS): Modifier {
    if (morph == null) return this
    val idle by remember(morph) { derivedStateOf { !hingeUnfoldLayerNeeded(max(morph.angleTilt.value, morph.closingTilt)) } }
    if (idle) return this
    return this.graphicsLayer {
        val closing = morph.closingTilt
        val tilt = max(morph.angleTilt.value, closing)
        // "Zavírání jako Duo" item 3: closing and opening are never both nonzero at once (the
        // controller's own invariant, UnfoldMorph.kt), so `closing > 0f` unambiguously picks the
        // seam-leads mapping; opening keeps unfoldProgress's own seam-first-to-unfold ordering.
        val progress = if (closing > 0f) closingUnfoldProgress(tilt, column, row, columns) else unfoldProgress(tilt, column, row, columns)
        alpha = UNFOLD_ALPHA_START + (1f - UNFOLD_ALPHA_START) * progress
        if (MotionPrefs.enabled.value) {
            rotationY = 0f
            translationX = 0f
        } else {
            cameraDistance = 12f * density
            transformOrigin = TransformOrigin(1f, 0.5f)
            rotationY = UNFOLD_ROTATION_START_DEG * (1f - progress)
            translationX = (1f - progress) * UNFOLD_TRANSLATION_START_DP.dp.toPx()
        }
    }
}
