// Ported from marcoazeem/duo-open (MIT, Copyright (c) 2026 marcoazeem), see docs/upstream/LICENSE-duo-open.
// Adapted for Fold Duo on Galaxy Z Fold 8.
package cz.pflanzer.foldduo.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log

/** One delivered hinge sample: degrees, the sensor's accuracy, its timestamp and every raw value. */
class HingeSample(val deg: Float, val accuracy: Int, val timestampNs: Long, val values: FloatArray)

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Uses the platform `TYPE_HINGE_ANGLE` sensor (non-wake-up preferred), falling
 * back to any vendor sensor whose name/type mentions "hinge". It's an
 * on-change sensor, so registering delivers the current angle immediately and
 * then only fires while the hinge actually moves.
 *
 * Registration is explicit about latency: fastest rate, `maxReportLatencyUs = 0` (no
 * batching, every event is delivered as it happens) and, when given, a [handler] so the
 * events land on that looper instead of the registering thread's. The registration result
 * and the sensor's properties are logged once per [start] (tag `HingeAngleSource`) so a
 * device log shows what the launcher is actually listening to.
 */
class HingeAngleSource(
    context: Context,
    private val handler: Handler? = null,
    private val onSample: (HingeSample) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    val sensor: Sensor? = sensorManager?.let { sm ->
        sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false)
            ?: sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
            ?: sm.getSensorList(Sensor.TYPE_ALL).firstOrNull {
                it.stringType.contains("hinge", ignoreCase = true) ||
                    it.name.contains("hinge", ignoreCase = true)
            }
    }

    var lastAngle: Float = Float.NaN
        private set

    /** Result of the last [SensorManager.registerListener]; null before [start]. */
    var registered: Boolean? = null
        private set

    private var started = false

    fun start() {
        val s = sensor
        if (s == null) {
            Log.w(TAG, "no hinge sensor on this device")
            return
        }
        if (started) return
        started = true
        val ok = sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
        registered = ok
        Log.i(TAG, "registered=$ok name='${s.name}' type=${s.stringType} wakeUp=${s.isWakeUpSensor} " +
            "reportingMode=${s.reportingMode} minDelayUs=${s.minDelay} maxDelayUs=${s.maxDelay} " +
            "fifoMax=${s.fifoMaxEventCount} rate=FASTEST maxReportLatencyUs=0 handler=${handler != null}")
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        Log.i(TAG, "unregistered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        lastAngle = angle
        onSample(HingeSample(angle, event.accuracy, event.timestamp, event.values.copyOf()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.i(TAG, "accuracy changed: $accuracy")
    }

    private companion object {
        const val TAG = "HingeAngleSource"
    }
}
