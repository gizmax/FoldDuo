package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IDEAS.md B33, "Rozložení z pantu": [unfoldProgress] is the pure mapping from the shared
 * left-half tilt to a per-cell unfold progress, staggered by distance from the seam (the leading
 * pane's own right edge — the leading canvas is the LEFT pane, FoldGeometry.kt). Rendering
 * ([Modifier.hingeUnfold], HingeUnfold.kt) is graphicsLayer-only and not exercised here (no
 * Android/Compose UI types on the JVM test path), same split as the rest of the morph's pure
 * logic (MorphPolishTest.kt etc).
 */
class HingeUnfoldTest {

    private val columns = GRID_COLUMNS // 4

    // ---- basic range mapping ----

    @Test fun `fully frosted tilt is progress 0 at the seam column, flat tilt is progress 1 everywhere`() {
        // The seam column (nearest the hinge) has no lag, so it alone reads the raw mapping.
        assertEquals(0f, unfoldProgress(MorphCurve.MAX_TILT, column = columns - 1, row = 0, columns), 1e-4f)
        for (column in 0 until columns) for (row in 0 until 6) {
            assertEquals(1f, unfoldProgress(0f, column, row, columns), 1e-4f)
        }
    }

    @Test fun `progress is always clamped to 0 and 1, never NaN, for extreme or out-of-range tilts`() {
        for (tilt in listOf(-100f, -1f, 0f, 20f, 45f, 90f, 1000f)) {
            for (column in -2..columns + 1) for (row in -2..7) {
                val p = unfoldProgress(tilt, column, row, columns)
                assertTrue("tilt=$tilt column=$column row=$row was $p", !p.isNaN() && p in 0f..1f)
            }
        }
    }

    @Test fun `zero or negative columns never divides by zero`() {
        val p = unfoldProgress(20f, column = 0, row = 0, columns = 0)
        assertTrue(!p.isNaN() && p in 0f..1f)
    }

    // ---- stagger by distance from the seam ----

    @Test fun `the column nearest the seam leads every other column at the same row and tilt`() {
        val row = 0
        val midTilt = MorphCurve.MAX_TILT / 2f
        val seamColumn = columns - 1
        val seamProgress = unfoldProgress(midTilt, seamColumn, row, columns)
        for (column in 0 until seamColumn) {
            val other = unfoldProgress(midTilt, column, row, columns)
            assertTrue("column=$column ($other) should lag the seam column ($seamProgress)", other <= seamProgress)
        }
    }

    @Test fun `progress strictly decreases (or holds at the clamp) as distance from the seam grows`() {
        val row = 0
        val midTilt = MorphCurve.MAX_TILT * 0.4f
        var previous = Float.POSITIVE_INFINITY
        for (column in columns - 1 downTo 0) { // walking away from the seam
            val p = unfoldProgress(midTilt, column, row, columns)
            assertTrue("column=$column progress $p should not exceed previous $previous", p <= previous + 1e-6f)
            previous = p
        }
    }

    @Test fun `each column further from the seam lags by 8 percent of the progress range, net of the row's own bias`() {
        val row = 0 // even row: the ±2% row bias is -0.02 here (see the diagonal-sweep tests below)
        val rowBias = -0.02f
        val tilt = MorphCurve.MAX_TILT * 0.6f // mid-flight, away from the 0/1 clamps
        val base = ((MorphCurve.MAX_TILT - tilt) / MorphCurve.MAX_TILT)
        val seam = unfoldProgress(tilt, columns - 1, row, columns)
        val oneAway = unfoldProgress(tilt, columns - 2, row, columns)
        // seam has no column lag; the row bias alone is negative, so it clamps to zero: the
        // seam column's progress is exactly the base mapping.
        assertEquals(base, seam, 1e-4f)
        // one column away lags by 8% of the range, plus this row's own -2%: progress is base
        // re-scaled by (base-lag)/(1-lag).
        val lag = (0.08f + rowBias).coerceIn(0f, 1f)
        assertEquals(((base - lag) / (1f - lag)).coerceIn(0f, 1f), oneAway, 1e-4f)
    }

    // ---- diagonal row bias ----

    @Test fun `adjacent rows in the same column are not perfectly in sync mid-flight`() {
        val column = 1
        val tilt = MorphCurve.MAX_TILT * 0.5f
        val row0 = unfoldProgress(tilt, column, row = 0, columns)
        val row1 = unfoldProgress(tilt, column, row = 1, columns)
        assertTrue("row 0 ($row0) and row 1 ($row1) should differ mid-flight", row0 != row1)
    }

    @Test fun `row bias never flips the overall column ordering`() {
        // Even with the row bias, a whole row further from the seam still never leads a nearer one.
        val tilt = MorphCurve.MAX_TILT * 0.5f
        val nearSeam = unfoldProgress(tilt, columns - 1, row = 5, columns) // far row, near column
        val farFromSeam = unfoldProgress(tilt, 0, row = 0, columns) // near row, far column
        assertTrue(nearSeam >= farFromSeam)
    }

    // ---- monotonic in tilt (both directions of travel share one function) ----

    @Test fun `progress is monotonically non-increasing in tilt, for every cell`() {
        for (column in 0 until columns) for (row in 0 until 6) {
            var previous = 1f
            var t = 0f
            while (t <= MorphCurve.MAX_TILT + 0.01f) {
                val p = unfoldProgress(t, column, row, columns)
                assertTrue("column=$column row=$row tilt=$t: $p should not exceed previous $previous",
                    p <= previous + 1e-6f)
                previous = p
                t += 3f
            }
        }
    }

    // ---- reverse mapping: closing feeds a rising tilt back through the same function ----

    @Test fun `closing (rising tilt) retraces the opening curve exactly, cell by cell`() {
        val column = 2
        val row = 3
        val openingTilts = listOf(45f, 30f, 15f, 0f)
        val closingTilts = openingTilts.reversed() // 0 -> 45, same values, opposite order in time
        val openingProgress = openingTilts.map { unfoldProgress(it, column, row, columns) }
        val closingProgress = closingTilts.map { unfoldProgress(it, column, row, columns) }
        // Closing at tilt X gives back exactly the progress opening had at tilt X: one shared function.
        assertEquals(openingProgress, closingProgress.reversed())
    }

    // ---- "Zavírání jako Duo" (17. 9. noc), item 3: closingUnfoldProgress gives the seam the
    // LEAD instead of the lag, so the actual wiring (Modifier.hingeUnfold) folds paper toward the
    // hinge instead of literally replaying the opening curve backward (which would have the far
    // edge finish folding first — the old "closing folds the seam column back to 0 last" test
    // documented exactly that undesired ordering; this replaces it). ----

    @Test fun `closingUnfoldProgress mirrors unfoldProgress with the column reflected`() {
        for (tilt in listOf(0f, 5f, 22.5f, 40f, MorphCurve.MAX_TILT)) {
            for (column in 0 until columns) for (row in 0..5) {
                assertEquals(unfoldProgress(tilt, columns - 1 - column, row, columns),
                    closingUnfoldProgress(tilt, column, row, columns), 1e-6f)
            }
        }
    }

    @Test fun `closing folds the seam column first, ahead of the far column, at the same row and tilt`() {
        val row = 0
        val midTilt = MorphCurve.MAX_TILT * 0.5f
        val seamColumn = columns - 1
        val seamProgress = closingUnfoldProgress(midTilt, seamColumn, row, columns)
        for (column in 0 until seamColumn) {
            val other = closingUnfoldProgress(midTilt, column, row, columns)
            assertTrue("seam ($seamProgress) should already be further folded than column=$column ($other)",
                seamProgress <= other)
        }
    }

    @Test fun `closing seam column reaches fully folded before the tilt (and so the frost) reaches MAX_TILT`() {
        val row = 0
        val seamColumn = columns - 1
        // Partway through closing, short of full frost, the seam item is already flat toward it.
        val midClosingTilt = MorphCurve.MAX_TILT * (8f / 9f) // 40 of 45: base 0.111, well under the seam's own 0.22 lag-as-lead
        val seamProgress = closingUnfoldProgress(midClosingTilt, seamColumn, row, columns)
        assertEquals(0f, seamProgress, 1e-4f)
    }

    @Test fun `closingUnfoldProgress stays in range and never crashes for extreme inputs`() {
        for (tilt in listOf(-100f, -1f, 0f, 45f, 1000f)) {
            for (column in -2..columns + 1) for (row in -2..7) {
                val p = closingUnfoldProgress(tilt, column, row, columns)
                assertTrue(!p.isNaN() && p in 0f..1f)
            }
        }
        assertTrue(!closingUnfoldProgress(10f, 0, 0, 0).isNaN())
    }

    // ---- rendering constants (rest-state sanity, the graphicsLayer math itself needs Android UI) ----

    @Test fun `rest-state constants are within the ranges the task specifies`() {
        assertEquals(-70f, UNFOLD_ROTATION_START_DEG, 0f)
        assertEquals(0.4f, UNFOLD_ALPHA_START, 0f)
        assertEquals(12f, UNFOLD_TRANSLATION_START_DP, 0f)
    }

    // ---- "Oprava naklánění na Today" (17. 9. noc): LeadingPane.kt's top slot (hub, suggestions)
    // now shares this same function — it takes the grid's own row 0 / row 1 (column 0, since a
    // full-width card has no per-column stagger of its own) so it unfolds as part of the same
    // diagonal wave as the grid rows below it, instead of sitting still while they tilt. ----

    @Test fun `top slot row 0 (hub) leads row 1 (suggestions) at column 0, mid-flight`() {
        val midTilt = MorphCurve.MAX_TILT * 0.5f
        val hubProgress = unfoldProgress(midTilt, column = 0, row = 0, columns)
        val suggestionsProgress = unfoldProgress(midTilt, column = 0, row = 1, columns)
        assertTrue("hub row 0 ($hubProgress) should lead suggestions row 1 ($suggestionsProgress)",
            hubProgress > suggestionsProgress)
    }

    @Test fun `top slot rows never invert the seam-column lead even at the extremes`() {
        for (tilt in listOf(0f, 1f, MorphCurve.MAX_TILT - 1f, MorphCurve.MAX_TILT)) {
            val hubProgress = unfoldProgress(tilt, column = 0, row = 0, columns)
            val suggestionsProgress = unfoldProgress(tilt, column = 0, row = 1, columns)
            assertTrue("tilt=$tilt: hub ($hubProgress) should never lag suggestions ($suggestionsProgress)",
                hubProgress >= suggestionsProgress)
        }
    }

    // ---- composition-free check of the graphicsLayer formula itself (HingeUnfold.kt's
    // Modifier.hingeUnfold body), mirrored here in plain arithmetic over the same public
    // constants and the pure unfoldProgress, since the real graphicsLayer block needs a Density
    // and a DrawScope that only exist under Compose UI test infrastructure. ----

    @Test fun `the unfold transform is non-identity for any tilt short of fully at rest`() {
        val row = 0
        val column = 0
        for (tilt in listOf(1f, 10f, 22.5f, 40f, MorphCurve.MAX_TILT)) {
            val progress = unfoldProgress(tilt, column, row, columns)
            val rotationY = UNFOLD_ROTATION_START_DEG * (1f - progress)
            val translationXDp = (1f - progress) * UNFOLD_TRANSLATION_START_DP
            val alpha = UNFOLD_ALPHA_START + (1f - UNFOLD_ALPHA_START) * progress
            if (progress < 1f) {
                assertTrue("tilt=$tilt progress=$progress: rotationY should be non-zero", rotationY != 0f)
                assertTrue("tilt=$tilt progress=$progress: translationX should be non-zero", translationXDp != 0f)
                assertTrue("tilt=$tilt progress=$progress: alpha should be below 1", alpha < 1f)
            }
        }
    }

    @Test fun `the unfold transform is exactly identity at full rest (progress 1)`() {
        // The seam column at tilt 0 is the one cell guaranteed to hit progress == 1 exactly.
        val progress = unfoldProgress(0f, column = columns - 1, row = 0, columns)
        assertEquals(1f, progress, 0f)
        val rotationY = UNFOLD_ROTATION_START_DEG * (1f - progress)
        val translationXDp = (1f - progress) * UNFOLD_TRANSLATION_START_DP
        val alpha = UNFOLD_ALPHA_START + (1f - UNFOLD_ALPHA_START) * progress
        assertEquals(0f, rotationY, 0f)
        assertEquals(0f, translationXDp, 0f)
        assertEquals(1f, alpha, 0f)
    }

    // ---- Výkon 3 "kreslení na inneru": the hinge-unfold graphicsLayer gate ----

    @Test fun `no layer is needed at exactly zero tilt`() {
        assertTrue(!hingeUnfoldLayerNeeded(0f))
    }

    @Test fun `any positive tilt needs a layer, however small`() {
        assertTrue(hingeUnfoldLayerNeeded(0.01f))
        assertTrue(hingeUnfoldLayerNeeded(1f))
        assertTrue(hingeUnfoldLayerNeeded(MorphCurve.MAX_TILT))
        assertTrue(hingeUnfoldLayerNeeded(1000f))
    }

    @Test fun `negative tilt (should not occur, but must not crash) is treated as idle`() {
        assertTrue(!hingeUnfoldLayerNeeded(-1f))
    }

    @Test fun `layer-needed and unfoldProgress agree at the rest boundary, for every cell`() {
        // hingeUnfoldLayerNeeded(0f) being false must mean every cell is already fully at rest —
        // never a layer skipped while some cell still had rotating/fading left to do.
        for (column in 0 until columns) for (row in 0 until 6) {
            assertEquals(1f, unfoldProgress(0f, column, row, columns), 1e-6f)
        }
        assertTrue(!hingeUnfoldLayerNeeded(0f))
    }
}
