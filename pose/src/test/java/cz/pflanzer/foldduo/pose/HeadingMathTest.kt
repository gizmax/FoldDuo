package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class HeadingMathTest {
    /** Identity quaternion (x=y=z=0, w=1): no rotation from the reference frame, azimuth 0. */
    @Test fun identityQuaternionIsZeroAzimuth() {
        assertEquals(0f, HeadingMath.azimuthDeg(0f, 0f, 0f, 1f), 1e-3f)
    }

    /**
     * A -90 degree rotation about the device's Z axis (turning the phone clockwise, as seen from
     * above — i.e. turning right) reads as a +90 degree azimuth, matching a compass.
     */
    @Test fun turningRightIncreasesAzimuth() {
        val half = sqrt(2f) / 2f
        // Quaternion for -90 deg about Z: (0, 0, sin(-45deg), cos(-45deg)).
        val azimuth = HeadingMath.azimuthDeg(0f, 0f, -half, half)
        assertEquals(90f, azimuth, .5f)
    }

    @Test fun azimuthAlwaysWrapsIntoZeroToThreeSixty() {
        for (z in -10..10) {
            val angle = HeadingMath.azimuthDeg(0f, 0f, z / 10f, 1f - kotlin.math.abs(z / 10f))
            assertEquals(angle, angle.coerceIn(0f, 360f - 1e-3f), 1e-3f)
        }
    }

    // --- angleDiffDeg --------------------------------------------------------------------

    @Test fun angleDiffTakesTheShortestPath() {
        assertEquals(10f, HeadingMath.angleDiffDeg(350f, 0f), 1e-3f)
        assertEquals(-10f, HeadingMath.angleDiffDeg(0f, 350f), 1e-3f)
        assertEquals(20f, HeadingMath.angleDiffDeg(10f, 30f), 1e-3f)
        assertEquals(180f, HeadingMath.angleDiffDeg(0f, 180f), 1e-3f)
    }

    @Test fun angleDiffOfEqualAnglesIsZero() {
        assertEquals(0f, HeadingMath.angleDiffDeg(123.4f, 123.4f), 1e-3f)
    }

    // --- lowPassHeadingDeg (wrap-around) --------------------------------------------------

    @Test fun lowPassMovesTowardTargetByAlpha() {
        assertEquals(105f, HeadingMath.lowPassHeadingDeg(100f, 110f, .5f), 1e-3f)
        assertEquals(100f, HeadingMath.lowPassHeadingDeg(100f, 110f, 0f), 1e-3f)
        assertEquals(110f, HeadingMath.lowPassHeadingDeg(100f, 110f, 1f), 1e-3f)
    }

    @Test fun lowPassWrapsAcrossTheZeroSeamTheShortWay() {
        // 350 -> 10 is a 20 degree gap through 0/360, not a 340 degree one the long way.
        val result = HeadingMath.lowPassHeadingDeg(350f, 10f, .5f)
        assertEquals(0f, result, 1e-3f)
    }

    @Test fun lowPassResultStaysInZeroToThreeSixty() {
        val result = HeadingMath.lowPassHeadingDeg(5f, 355f, .8f)
        assertEquals(357f, result, 1f)
        assertEquals(result, result.coerceIn(0f, 360f - 1e-3f), 1e-3f)
    }

    @Test fun lowPassAlphaIsClamped() {
        assertEquals(110f, HeadingMath.lowPassHeadingDeg(100f, 110f, 5f), 1e-3f)
        assertEquals(100f, HeadingMath.lowPassHeadingDeg(100f, 110f, -5f), 1e-3f)
    }
}
