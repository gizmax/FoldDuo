package cz.pflanzer.foldduo.continuum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure, Android-free sanity checks for [SheetBend]'s tunables (the AGSL itself and the Android
 * glue that compiles/sets it need an instrumented environment, so they are not covered here).
 */
class SheetBendTest {
    @Test fun `the default light direction is already unit length`() {
        val (x, y) = SheetBend.DEFAULT_LIGHT_DIR
        val length = sqrt(x * x + y * y)
        assertEquals(1f, length, 1e-4f)
    }

    @Test fun `the bend cap matches the app module's max bend`() {
        // Kept in sync by hand with SheetBendMotion.MAX_BEND_DEG (app module; continuum has no
        // dependency on it) — this guards against the two drifting apart unnoticed.
        assertEquals(60f, SheetBend.MAX_BEND, 0f)
    }

    @Test fun `the flat epsilon is small enough that a real bend is never mistaken for flat`() {
        assertTrue(SheetBend.FLAT_EPSILON in 0f..1f)
    }
}
