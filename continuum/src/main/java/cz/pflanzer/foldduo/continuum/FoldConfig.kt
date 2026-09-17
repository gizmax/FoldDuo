// Ported from marcoazeem/duo-open (MIT, Copyright (c) 2026 marcoazeem), see docs/upstream/LICENSE-duo-open.
// Adapted for Fold Duo on Galaxy Z Fold 8.
package cz.pflanzer.foldduo.continuum

/**
 * Tunables for the frosted-glass morph. Defaults are duo-open's, tuned on a
 * OnePlus Open; the hinge constants live in [FoldShader] and MUST be replaced
 * by values measured on the Fold 8 (PLAN.md, Fáze 0 step 5).
 */
data class FoldConfig(
    val intensity: Float = 1f,
    val blurSpread: Float = 0.12f,
    val darkening: Float = 0.015f,
    val eyeDistanceMm: Float = 450f,
    /** True when the hinge splits the long axis of the window. */
    val foldSplitsLong: Boolean = false,
    /** -1 = left/top pane moves, +1 = right/bottom, 0 = both. Fold Duo: Today (left) moves. */
    val movingSide: Int = -1,
    val coverFrostFromRight: Boolean = true,
    /**
     * Hinge-angle constants for [FoldShader.tiltForHinge]/[FoldShader.coverTiltForHinge], as
     * fields rather than the object's own top-level constants: a device with a different
     * magnetometer-estimate behaviour (a Galaxy Z Fold 7, whose hinge signature has no
     * calibrated table yet — `pose/HingeAngleEstimator.kt`'s `HingeCalibration.forModel`) would
     * pass its own here instead of [FoldShader.FLAT_HINGE]/[FoldShader.PANEL_ON_HINGE]/[FoldShader.CLOSED_HINGE].
     * Defaults are the Fold 8's own (2026-09-15, `pose/testdata/README.md`); every other reader
     * of those object constants (`UnfoldMorph.kt`, `systemfrost/`) is unaffected — this config
     * is only consulted by the two tilt functions above.
     */
    val flatHingeDeg: Float = FoldShader.FLAT_HINGE,
    val panelOnHingeDeg: Float = FoldShader.PANEL_ON_HINGE,
    val closedHingeDeg: Float = FoldShader.CLOSED_HINGE,
)
