package cz.pflanzer.foldduo.pose

import kotlin.math.atan2

/**
 * Pure math behind [HeadingSource] (B49 "Živé ikony", the Maps compass overlay), factored out of
 * the sensor listener so it is covered by plain JVM unit tests — this project's tests run without
 * Robolectric, so `android.hardware.SensorManager` itself cannot be exercised here.
 */
internal object HeadingMath {
    /**
     * Azimuth in degrees `[0, 360)` from a game rotation vector quaternion (x, y, z, w),
     * replicating `SensorManager.getRotationMatrixFromVector` followed by `getOrientation`'s
     * `values[0]` (`atan2(R[1], R[4])`) without calling into the Android framework. The game
     * rotation vector has no magnetometer inside it, so 0° is whatever the sensor's own arbitrary
     * reference happened to be, not true/magnetic north — the task calls for this sensor
     * specifically (PoseRepository already owns the magnetometer for the hinge estimate).
     */
    fun azimuthDeg(x: Float, y: Float, z: Float, w: Float): Float {
        val r1 = 2f * x * y - 2f * z * w
        val r4 = 1f - 2f * x * x - 2f * z * z
        val deg = Math.toDegrees(atan2(r1.toDouble(), r4.toDouble())).toFloat()
        return wrapDeg(deg)
    }

    /** Shortest signed difference `to - from`, wrapped to `(-180, 180]`, for a circular quantity in degrees. */
    fun angleDiffDeg(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * One exponential low-pass step on a circular heading: moves [current] toward [target] by
     * [alpha] (0..1) of the shortest angular path between them, so it never spins the long way
     * around through the 0°/360° seam.
     */
    fun lowPassHeadingDeg(current: Float, target: Float, alpha: Float): Float =
        wrapDeg(current + alpha.coerceIn(0f, 1f) * angleDiffDeg(current, target))

    private fun wrapDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}
