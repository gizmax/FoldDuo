package cz.pflanzer.foldduo.continuum

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure, Android-free sanity checks for [EdgePulse]'s tunables (the AGSL itself and the Android
 * glue that compiles/sets it need an instrumented environment, so they are not covered here; see
 * app/src/test's PulseMotionTest for the shared progress -> spread/opacity maths).
 */
class EdgePulseTest {
    @Test fun `the max opacity matches the app module's pulse motion cap`() {
        // Kept in sync by hand with PulseMotion.MAX_OPACITY (app module; continuum has no
        // dependency on it) — this guards against the two drifting apart unnoticed.
        assertEquals(0.35f, EdgePulse.MAX_OPACITY, 0f)
    }

    @Test fun `the edge codes are four distinct, ordered values`() {
        val edges = listOf(EdgePulse.EDGE_TOP, EdgePulse.EDGE_BOTTOM, EdgePulse.EDGE_LEFT, EdgePulse.EDGE_RIGHT)
        assertEquals(edges.distinct(), edges)
        assertEquals(listOf(0f, 1f, 2f, 3f), edges)
    }
}
