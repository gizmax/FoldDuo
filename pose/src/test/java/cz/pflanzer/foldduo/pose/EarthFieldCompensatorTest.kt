package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/** Synthetic rotations through the quaternion math and the pairwise Earth-field solve. */
class EarthFieldCompensatorTest {
    private val earth = floatArrayOf(20f, 0f, -44f) // ~48 uT, Central-European dip, world frame
    private val magnetClosed = floatArrayOf(-197.4f, -30f, -40f)
    private val magnetOpen = floatArrayOf(-260.4f, -28f, -38f)
    private fun deg(d: Float) = (d * PI / 180.0).toFloat()

    /** What the sensor reads for the magnets' field [m] at attitude [q]. */
    private fun sample(q: FloatArray, m: FloatArray): FloatArray {
        val e = Quaternion.rotateInverse(Quaternion.rotationMatrix(q), earth)
        return floatArrayOf(m[0] + e[0], m[1] + e[1], m[2] + e[2])
    }

    @Test fun quaternion_rotationMatrixIsOrthonormalAndMatchesAxisAngle() {
        val q = Quaternion.fromAxisAngle(0f, 0f, 1f, deg(90f))
        val r = Quaternion.rotationMatrix(q)
        // 90 deg about z: device x -> world y
        val v = Quaternion.rotate(r, floatArrayOf(1f, 0f, 0f))
        assertEquals(0f, v[0], 1e-5f); assertEquals(1f, v[1], 1e-5f); assertEquals(0f, v[2], 1e-5f)
        // inverse undoes it
        val back = Quaternion.rotateInverse(r, v)
        assertEquals(1f, back[0], 1e-5f); assertEquals(0f, back[1], 1e-5f); assertEquals(0f, back[2], 1e-5f)
        // a 3-element rotation vector (w implied) gives the same matrix
        val r3 = Quaternion.rotationMatrix(floatArrayOf(q[0], q[1], q[2]))
        for (i in 0 until 9) assertEquals(r[i], r3[i], 1e-5f)
        // orthonormal rows
        for (i in 0 until 3) {
            var n = 0f; for (k in 0 until 3) n += r[i * 3 + k] * r[i * 3 + k]
            assertEquals(1f, n, 1e-5f)
        }
        // identity for the zero rotation
        val id = Quaternion.rotationMatrix(floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(1f, id[0], 0f); assertEquals(1f, id[4], 0f); assertEquals(1f, id[8], 0f); assertEquals(0f, id[1], 0f)
    }

    @Test fun oneOrientation_learnsNothingAndCompensationIsTheIdentity() {
        val c = EarthFieldCompensator()
        val q = Quaternion.fromAxisAngle(1f, 0f, 0f, deg(10f))
        val b = sample(q, magnetClosed)
        c.learnAtRest(1_000_000_000L, 0f, q, b)
        c.learnAtRest(3_000_000_000L, 180f, q, sample(q, magnetOpen))
        c.learnAtRest(5_000_000_000L, 0f, q, b)
        assertEquals(3, c.observations)
        assertEquals(1, c.pairs) // the two closed rests
        assertFalse(c.resolved)
        assertEquals(0f, c.magnitude, 1e-3f)
        val out = c.compensate(q, b)
        assertEquals(b[0], out[0], 1e-5f); assertEquals(b[1], out[1], 1e-5f); assertEquals(b[2], out[2], 1e-5f)
        // no attitude yet: identity too
        val none = c.compensate(null, b)
        assertEquals(b[0], none[0], 0f)
    }

    @Test fun rotatedRests_recoverTheEarthFieldAndCompensateMidFoldSamples() {
        val c = EarthFieldCompensator()
        // Closed on the table, then closed held tilted 30 deg about y and 25 deg about x, open at two more attitudes.
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        val q2 = Quaternion.fromAxisAngle(0f, 1f, 0f, deg(30f))
        val q3 = Quaternion.fromAxisAngle(1f, 0f, 0f, deg(25f))
        val q4 = Quaternion.fromAxisAngle(0.6f, 0f, 0.8f, deg(40f))
        c.learnAtRest(1_000_000_000L, 0f, q1, sample(q1, magnetClosed))
        c.learnAtRest(2_000_000_000L, 0f, q2, sample(q2, magnetClosed))
        c.learnAtRest(3_000_000_000L, 0f, q3, sample(q3, magnetClosed))
        c.learnAtRest(4_000_000_000L, 180f, q1, sample(q1, magnetOpen))
        c.learnAtRest(5_000_000_000L, 180f, q4, sample(q4, magnetOpen))
        assertEquals(4, c.pairs) // 3 closed pairs + 1 open pair
        assertTrue("diversity ${c.diversity}", c.resolved)
        for (i in 0 until 3) assertEquals("E[$i]", earth[i], c.earthWorld[i], 1.5f)
        assertEquals(sqrt(20f * 20f + 44f * 44f), c.magnitude, 1.5f)
        // A mid-fold sample at a new attitude (magnets at ~90 deg) comes back Earth-free.
        val q5 = Quaternion.fromAxisAngle(0f, 0.7f, 0.7f, deg(-50f))
        val m90 = floatArrayOf(-241f, -29f, -39f)
        val out = c.compensate(q5, sample(q5, m90))
        for (i in 0 until 3) assertEquals("comp[$i]", m90[i], out[i], 1.5f)
        // Uncompensated the same sample is tens of uT off on x (the estimator's feature).
        val raw = sample(q5, m90)
        assertTrue("raw bx error ${raw[0] - m90[0]}", kotlin.math.abs(raw[0] - m90[0]) > 5f)
    }

    @Test fun rotationAboutOneAxisOnly_leavesThatComponentAtZeroButCompensatesTheRest() {
        val c = EarthFieldCompensator()
        // Every rest differs by a yaw about world z only: E_z is unobservable (and harmless).
        val qs = listOf(0f, 40f, 80f, 120f).map { Quaternion.fromAxisAngle(0f, 0f, 1f, deg(it)) }
        for ((i, q) in qs.withIndex()) c.learnAtRest((i + 1) * 1_000_000_000L, 0f, q, sample(q, magnetClosed))
        assertTrue(c.resolved)
        assertEquals(earth[0], c.earthWorld[0], 1.5f)
        assertEquals(earth[1], c.earthWorld[1], 1.5f)
        assertEquals(0f, c.earthWorld[2], 1.5f)
        // Any further yaw is compensated exactly on x/y; z keeps the constant the table absorbs.
        val q = Quaternion.fromAxisAngle(0f, 0f, 1f, deg(200f))
        val out = c.compensate(q, sample(q, magnetClosed))
        assertEquals(magnetClosed[0], out[0], 1.5f)
        assertEquals(magnetClosed[1], out[1], 1.5f)
        // resolved (the data determined something), but quality is not GOOD: only 2 of 3 axes are
        // observable here, so the resolved magnitude (~20 uT) is far below a real Earth field and
        // a caller gating on `quality == GOOD` correctly declines to trust it for this partial fit.
        assertEquals(EarthFieldCompensator.Quality.OUT_OF_RANGE, c.quality)
    }

    @Test fun oldRestsAgeOutAndTheWindowIsBounded() {
        val c = EarthFieldCompensator(maxObservations = 3, maxAgeNs = 10_000_000_000L)
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        for (i in 0 until 5) c.learnAtRest(i * 1_000_000_000L, 0f, q, sample(q, magnetClosed))
        assertEquals(3, c.observations)
        c.learnAtRest(30_000_000_000L, 0f, q, sample(q, magnetClosed))
        assertEquals(1, c.observations)
        c.reset()
        assertEquals(0, c.observations); assertEquals(0f, c.magnitude, 0f)
    }

    @Test fun defaultRingIsTwelveObservations() {
        val c = EarthFieldCompensator() // B19: N=12 (was 8)
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        for (i in 0 until 15) c.learnAtRest(i * 1_000_000_000L, 0f, q, sample(q, magnetClosed))
        assertEquals(12, c.observations)
    }

    @Test fun qualityStartsAtNoneAndReachesGoodOnlyWithDiverseWellFitRests() {
        val c = EarthFieldCompensator()
        assertEquals(EarthFieldCompensator.Quality.NONE, c.quality)
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        c.learnAtRest(1_000_000_000L, 0f, q, sample(q, magnetClosed))
        assertEquals(EarthFieldCompensator.Quality.NONE, c.quality) // no pairs yet
        val q2 = Quaternion.fromAxisAngle(1f, 0f, 0f, deg(20f))
        c.learnAtRest(2_000_000_000L, 0f, q2, sample(q2, magnetClosed))
        assertEquals(EarthFieldCompensator.Quality.LOW_DIVERSITY, c.quality) // one small-angle pair
        val q3 = Quaternion.fromAxisAngle(0f, 1f, 0f, deg(70f))
        c.learnAtRest(3_000_000_000L, 0f, q3, sample(q3, magnetClosed))
        val q4 = Quaternion.fromAxisAngle(0.6f, 0f, 0.8f, deg(-55f))
        c.learnAtRest(4_000_000_000L, 180f, q, sample(q, magnetOpen))
        c.learnAtRest(5_000_000_000L, 180f, q4, sample(q4, magnetOpen))
        assertEquals(EarthFieldCompensator.Quality.GOOD, c.quality)
        assertTrue("residual ${c.residualUt}", c.residualUt < 0.5f) // exact synthetic data
    }

    @Test fun inconsistentObservationsAreFlaggedHighResidual() {
        val c = EarthFieldCompensator()
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        val q2 = Quaternion.fromAxisAngle(0f, 1f, 0f, deg(60f))
        val q3 = Quaternion.fromAxisAngle(1f, 0f, 0f, deg(75f))
        val q4 = Quaternion.fromAxisAngle(0.6f, 0f, 0.8f, deg(-80f))
        c.learnAtRest(1_000_000_000L, 0f, q1, sample(q1, magnetClosed))
        c.learnAtRest(2_000_000_000L, 0f, q2, sample(q2, magnetClosed))
        // this one is not consistent with a single constant world field: an outlier rest (say, a
        // fridge magnet nearby), not the attitude-driven Earth term the model assumes.
        val bad = sample(q3, magnetClosed).also { it[0] += 30f; it[1] -= 20f }
        c.learnAtRest(3_000_000_000L, 0f, q3, bad)
        c.learnAtRest(4_000_000_000L, 180f, q1, sample(q1, magnetOpen))
        c.learnAtRest(5_000_000_000L, 180f, q4, sample(q4, magnetOpen))
        assertTrue("residual ${c.residualUt}", c.residualUt > EarthFieldCompensator.MAX_RESIDUAL_UT)
        assertEquals(EarthFieldCompensator.Quality.HIGH_RESIDUAL, c.quality)
    }

    @Test fun microRestsAtMatchingAnglesPairForTheEarthSolveOnly() {
        val c = EarthFieldCompensator()
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        val q2 = Quaternion.fromAxisAngle(0f, 1f, 0f, deg(65f))
        val magnet90 = floatArrayOf(-241f, -30f, -30f) // roughly halfway between magnetClosed/magnetOpen
        // Two micro-rests near 90 deg (a phone paused mid-fold), no 0/180 rest between them.
        c.learnAtMicroRest(1_000_000_000L, 91f, q1, sample(q1, magnet90))
        c.learnAtMicroRest(2_000_000_000L, 89f, q2, sample(q2, magnet90))
        assertEquals(2, c.observations)
        assertEquals(1, c.pairs)
        assertTrue("diversity ${c.diversity}", c.diversity > 0f)
        // A micro-rest far from 90 does not pair with them (outside angleToleranceDeg).
        val q3 = Quaternion.fromAxisAngle(1f, 0f, 0f, deg(50f))
        c.learnAtMicroRest(3_000_000_000L, 20f, q3, sample(q3, magnetClosed))
        assertEquals(1, c.pairs) // still just the one 90-ish pair
    }
}
