package cz.pflanzer.foldduo.pose

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B48 "Pant jako ovladač": [TentTiltDetector]'s threshold, direction, and 1/s rate limit. */
class TentTiltDetectorTest {
    /** A unit gravity vector's x/z at [angleDeg] from the detector's own reference (atan2(z, x)). */
    private fun xz(angleDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        return (10f * cos(rad).toFloat()) to (10f * sin(rad).toFloat())
    }

    @Test fun `the first sample in Tent only captures rest, no signal`() {
        val d = TentTiltDetector()
        val (gx, gz) = xz(0f)
        assertNull(d.onPose(0L, FoldPose.Tent, gx, gz))
    }

    @Test fun `a small tilt under the threshold reports nothing`() {
        val d = TentTiltDetector()
        val (gx0, gz0) = xz(0f)
        d.onPose(0L, FoldPose.Tent, gx0, gz0)
        val (gx1, gz1) = xz(8f) // under THRESHOLD_DEG (12)
        assertNull(d.onPose(100L, FoldPose.Tent, gx1, gz1))
    }

    @Test fun `tilting past the threshold one way reports FORWARD`() {
        val d = TentTiltDetector()
        val (gx0, gz0) = xz(0f)
        d.onPose(0L, FoldPose.Tent, gx0, gz0)
        val (gx1, gz1) = xz(20f)
        assertEquals(TiltDirection.FORWARD, d.onPose(100L, FoldPose.Tent, gx1, gz1))
    }

    @Test fun `tilting past the threshold the other way reports BACKWARD`() {
        val d = TentTiltDetector()
        val (gx0, gz0) = xz(0f)
        d.onPose(0L, FoldPose.Tent, gx0, gz0)
        val (gx1, gz1) = xz(-20f)
        assertEquals(TiltDirection.BACKWARD, d.onPose(100L, FoldPose.Tent, gx1, gz1))
    }

    @Test fun `a held tilt does not repeat inside the 1s rate limit`() {
        val d = TentTiltDetector()
        val (gx0, gz0) = xz(0f)
        d.onPose(0L, FoldPose.Tent, gx0, gz0)
        val (gx1, gz1) = xz(20f)
        assertEquals(TiltDirection.FORWARD, d.onPose(100L, FoldPose.Tent, gx1, gz1))
        assertNull("still within MIN_INTERVAL_MS of the last fire", d.onPose(500L, FoldPose.Tent, gx1, gz1))
        assertNull(d.onPose(1_000L, FoldPose.Tent, gx1, gz1))
    }

    @Test fun `a held tilt fires again once the rate limit window passes`() {
        val d = TentTiltDetector()
        val (gx0, gz0) = xz(0f)
        d.onPose(0L, FoldPose.Tent, gx0, gz0)
        val (gx1, gz1) = xz(20f)
        assertEquals(TiltDirection.FORWARD, d.onPose(100L, FoldPose.Tent, gx1, gz1))
        assertEquals(TiltDirection.FORWARD, d.onPose(100L + TentTiltDetector.MIN_INTERVAL_MS, FoldPose.Tent, gx1, gz1))
    }

    @Test fun `leaving Tent forgets the rest orientation`() {
        val d = TentTiltDetector()
        val (gx0, gz0) = xz(0f)
        d.onPose(0L, FoldPose.Tent, gx0, gz0)
        val (gx1, gz1) = xz(20f)
        assertEquals(TiltDirection.FORWARD, d.onPose(100L, FoldPose.Tent, gx1, gz1))
        // Leave Tent, then come back at the very same (still tilted) orientation: it becomes the new rest.
        assertNull(d.onPose(200L, FoldPose.Open, gx1, gz1))
        assertNull("re-entering Tent at the current orientation captures a fresh rest", d.onPose(2_000L, FoldPose.Tent, gx1, gz1))
    }

    @Test fun `outside Tent never reports a tilt`() {
        val d = TentTiltDetector()
        val (gx, gz) = xz(45f)
        assertNull(d.onPose(0L, FoldPose.Open, gx, gz))
        assertNull(d.onPose(100L, FoldPose.Stand, gx, gz))
    }
}
