package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * B19: the real fixture (`pose/testdata/20260915-174608.jsonl`) has no `TYPE_GAME_ROTATION_VECTOR`
 * samples — the phone sat flat on a table, only the magnet half moved, so there is no orientation
 * change for [EarthFieldCompensator] to exploit (its resolved `quality` stays `LOW_DIVERSITY` on
 * that recording, same as before this change: [HingeAngleEstimatorTest]'s leave-one-transition-out
 * RMS is unaffected). This test builds the scenario the fixture can't: a phone turned in the hand
 * *while* folding, with a known synthetic `E_world`, and checks (1) the compensator recovers it
 * within 10% from rests at diverse attitudes and (2) applying it to mid-fold samples cuts the
 * angle error a naive (uncompensated) inversion of the same samples would have, on a fold whose
 * rests were *not* used to learn `E_world` (a held-out cycle, LOO in spirit).
 */
class EarthFieldTrajectoryTest {
    private val cal = HingeCalibration.FOLD8_BX
    private val earth = floatArrayOf(20f, 0f, -44f) // ~48.4 uT, Central-European dip, world frame
    private fun deg(d: Float) = (d * PI / 180.0).toFloat()

    /** The closure magnets' field in device coordinates at [angleDeg] (bx from the real table; by/bz a synthetic linear ramp — small but nonzero, as measured on the fixture: pose/testdata/README.md). */
    private fun magnetAt(angleDeg: Float): FloatArray {
        val t = (angleDeg / 180f).coerceIn(0f, 1f)
        return floatArrayOf(cal.valueAt(angleDeg), -38f + 15f * t, -38f + 16f * t)
    }

    private fun quatMul(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    /** What the magnetometer reads for the magnets' field at [angleDeg] and attitude [q]. */
    private fun rawField(angleDeg: Float, q: FloatArray): FloatArray {
        val m = magnetAt(angleDeg)
        val e = Quaternion.rotateInverse(Quaternion.rotationMatrix(q), earth)
        return floatArrayOf(m[0] + e[0], m[1] + e[1], m[2] + e[2])
    }

    private class Sample(val angleDeg: Float, val q: FloatArray)

    /**
     * One synthetic fold: angle sweeps [fromDeg] -> [toDeg] linearly over [steps] samples while
     * the device rotates away from [base] by up to [turnDeg] about [axis] (a hand turning the
     * phone mid-fold, not just at the two rests).
     */
    private fun fold(fromDeg: Float, toDeg: Float, base: FloatArray, axis: FloatArray, turnDeg: Float, steps: Int = 30): List<Sample> =
        (0..steps).map { i ->
            val f = i.toFloat() / steps
            val turn = Quaternion.fromAxisAngle(axis[0], axis[1], axis[2], deg(turnDeg * f))
            Sample(fromDeg + (toDeg - fromDeg) * f, quatMul(turn, base))
        }

    private fun rms(errs: List<Float>) = sqrt(errs.sumOf { (it * it).toDouble() } / errs.size).toFloat()

    @Test fun trajectoryFit_recoversEarthWithinTenPercent_andCutsAngleRmsOnAHeldOutFold() {
        val c = EarthFieldCompensator()
        // Four fold cycles, each picked up at a different attitude and turned differently while
        // folding (a hand is not a table). Cycles 0-2 train E_world (their rests only, six
        // well-conditioned pairs like `rotatedRests_...` in EarthFieldCompensatorTest); cycle 3
        // is held out entirely and used only to score the angle RMS reduction.
        val bases = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            Quaternion.fromAxisAngle(1f, 0f, 0f, deg(15f)),
            Quaternion.fromAxisAngle(0f, 1f, 0f, deg(-20f)),
            Quaternion.fromAxisAngle(0f, 0f, 1f, deg(25f)),
        )
        val axes = listOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0.3f), floatArrayOf(0.6f, 0f, 0.8f), floatArrayOf(0f, 0.7f, 0.7f))
        val turns = listOf(40f, -55f, 65f, -50f)
        val folds = bases.indices.map { i -> fold(0f, 180f, bases[i], axes[i], turns[i]) }

        var t = 0L
        for (i in 0 until 3) { // train on cycles 0-2 only
            val f = folds[i]
            c.learnAtRest(t, 0f, f.first().q, rawField(f.first().angleDeg, f.first().q)); t += 1_000_000_000L
            c.learnAtRest(t, 180f, f.last().q, rawField(f.last().angleDeg, f.last().q)); t += 1_000_000_000L
        }
        assertEquals(6, c.pairs)
        assertTrue("diversity ${c.diversity}", c.resolved)
        assertEquals(EarthFieldCompensator.Quality.GOOD, c.quality)

        // (1) E_world within 10% of the true vector/magnitude.
        val trueMag = sqrt(earth[0] * earth[0] + earth[1] * earth[1] + earth[2] * earth[2])
        assertTrue("magnitude ${c.magnitude} vs true $trueMag", kotlin.math.abs(c.magnitude - trueMag) / trueMag < 0.10f)
        for (i in 0 until 3) {
            val tol = kotlin.math.abs(earth[i]).coerceAtLeast(4f) * 0.10f + 0.5f
            assertEquals("E[$i]", earth[i], c.earthWorld[i], tol)
        }

        // (2) angle RMS on the held-out cycle (2): naive (raw bx through the table) vs compensated.
        val held = folds[3].filter { it.angleDeg in 10f..170f } // skip the very ends (that's the rest anchor's job)
        val naiveErrs = held.map { s -> cal.angleOf(rawField(s.angleDeg, s.q)[0]) - s.angleDeg }
        val compErrs = held.map { s ->
            val comp = c.compensate(s.q, rawField(s.angleDeg, s.q))
            cal.angleOf(comp[0]) - s.angleDeg
        }
        val naiveRms = rms(naiveErrs); val compRms = rms(compErrs)
        println("EarthFieldTrajectoryTest: held-out fold angle RMS naive=%.1f deg compensated=%.1f deg (E resolved %.1f/%.1f/%.1f uT, true %.1f/%.1f/%.1f, |E| %.1f vs %.1f)".format(
            naiveRms, compRms, c.earthWorld[0], c.earthWorld[1], c.earthWorld[2], earth[0], earth[1], earth[2], c.magnitude, trueMag))
        assertTrue("naive RMS $naiveRms should be large (uncompensated mid-fold turn)", naiveRms > 10f)
        assertTrue("compensated RMS $compRms should be much smaller than naive $naiveRms", compRms < naiveRms * 0.3f)
        assertTrue("compensated RMS $compRms should be small in absolute terms", compRms < 6f)
    }

    @Test fun microRestsDuringATransitionImproveDiversityWithoutTouchingTheTable() {
        // A phone paused mid-fold at ~90 deg, twice, at different attitudes: two micro-rests that
        // never anchor the table (HingeAngleEstimator's offset is not this class's business) but
        // do add an orientation-diverse pair for the Earth solve.
        val c = EarthFieldCompensator()
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        val q2 = Quaternion.fromAxisAngle(0f, 1f, 0f, deg(50f))
        c.learnAtMicroRest(1_000_000_000L, 91f, q1, rawField(91f, q1))
        c.learnAtMicroRest(2_000_000_000L, 89f, q2, rawField(89f, q2))
        assertEquals(1, c.pairs)
        assertTrue("diversity from micro-rests alone ${c.diversity}", c.diversity > 0f)
        // Diversity from micro-rests alone is usually thinner than a full rest pair (two rotation
        // axes at least), but it must not be worse than nothing, and must not corrupt a later
        // resolved solve once real rests arrive.
        val q3 = Quaternion.fromAxisAngle(1f, 0f, 0f, deg(70f))
        val q4 = Quaternion.fromAxisAngle(0.6f, 0f, 0.8f, deg(-60f))
        c.learnAtRest(3_000_000_000L, 0f, q1, rawField(0f, q1))
        c.learnAtRest(4_000_000_000L, 0f, q3, rawField(0f, q3))
        c.learnAtRest(5_000_000_000L, 180f, q1, rawField(180f, q1))
        c.learnAtRest(6_000_000_000L, 180f, q4, rawField(180f, q4))
        assertEquals(EarthFieldCompensator.Quality.GOOD, c.quality)
    }
}
