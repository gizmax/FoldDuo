package cz.pflanzer.foldduo.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Compass heading for a consumer that just needs "which way is this pointing" — the Maps live
 * icon overlay (B49 "Živé ikony + náhled"), which wants a needle without owning a magnetometer.
 * [PoseRepository] already registers `TYPE_MAGNETIC_FIELD_UNCALIBRATED` and
 * `TYPE_GAME_ROTATION_VECTOR` for the hinge-angle estimator (see its class doc), so this is a
 * second, independent listener on `TYPE_GAME_ROTATION_VECTOR` only — the platform supports
 * multiple listeners on one sensor, and this one never touches the magnetometer, so neither
 * engine depends on the other running.
 *
 * [heading] updates at [UPDATE_INTERVAL_MS] (5 Hz, the task's needle rate) and is smoothed with
 * [HeadingMath.lowPassHeadingDeg] so small sensor jitter doesn't visibly shiver the needle. Main
 * thread only; call [start] from a foreground component and [stop] when it goes away, the same
 * contract as [PoseRepository].
 */
class HeadingSource(context: Context, private val handler: Handler = Handler(Looper.getMainLooper())) : SensorEventListener {
    private val app = context.applicationContext
    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    /** Degrees clockwise from the game rotation vector's own reference axis; null before the first sample or while stopped. */
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    private var registered = false
    private var lastUpdateMs = 0L

    /** No-op without a game rotation vector sensor, or if already started. */
    fun start() {
        val sensor = rotationSensor ?: return
        if (registered) return
        registered = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, 0, handler) == true
        lastUpdateMs = 0L
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        _heading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val w = if (v.size > 3) v[3] else sqrt((1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).coerceAtLeast(0f))
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs < UPDATE_INTERVAL_MS) return
        lastUpdateMs = now
        val target = HeadingMath.azimuthDeg(v[0], v[1], v[2], w)
        val current = _heading.value
        _heading.value = if (current == null) target else HeadingMath.lowPassHeadingDeg(current, target, LOW_PASS_ALPHA)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** 5 Hz — the needle rate the task asks for. */
        private const val UPDATE_INTERVAL_MS = 200L
        /** Exponential low-pass factor per accepted (already-throttled) sample. */
        private const val LOW_PASS_ALPHA = 0.35f
    }
}
