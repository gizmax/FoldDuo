package cz.pflanzer.foldduo.pose

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** Everything the classifier saw, for debug UI and for consumers that need more than the pose. */
data class PoseSnapshot(
    val pose: FoldPose = FoldPose.Closed,
    val panel: Panel = Panel.Unknown,
    val hingeDeg: Float = Float.NaN,
    val gravity: FloatArray = floatArrayOf(0f, 0f, 0f),
    val msSinceTransition: Long = Long.MAX_VALUE,
    val stillMs: Long = 0L,
    /**
     * Number of "opening motion" events the gyroscope has produced while the cover was active
     * ([OpeningMotionDetector]); a consumer that sees it change knows the phone is being opened.
     */
    val openingMotionSeq: Int = 0,
    /** Rotation rate around the hinge axis of the last gyroscope sample, rad/s (0 without one). */
    val hingeRateRadS: Float = 0f,
    /** The closing counterpart of [openingMotionSeq]: fast hinge-axis rotation seen while the inner panel was active. */
    val closingMotionSeq: Int = 0,
    /**
     * Continuous hinge angle in degrees (0 closed .. 180 flat) from the magnetometer estimator
     * ([HingeAngleEstimator]); NaN until the estimate is confident ([hingeAngleConfidence] >=
     * [HingeAngleEstimator.CONFIDENT] and anchored at a rest in this process) or while the
     * magnetometer is not running. Updated at the magnetometer's rate (~50 Hz) while valid.
     */
    val hingeAngleDeg: Float = Float.NaN,
    /** The estimator's confidence 0..1 (0 without a magnetometer). */
    val hingeAngleConfidence: Float = 0f,
    /** `mag` while [hingeAngleDeg] is valid, `step` when consumers must fall back to [hingeDeg]'s 0/90/180. */
    val hingeAngleSource: String = "step",
    /**
     * B48 "Pant jako ovladač": bumps once per [HingeSqueezeDetector.Phase.HELD] recognized (a
     * consumer that sees it change reacts once, same convention as [openingMotionSeq]).
     */
    val squeezeSeq: Int = 0,
    /** Live depth (deg, 0..[HingeSqueezeDetector.MAX_DEPTH_DEG]) while [squeezeHeld]; 0 otherwise. */
    val squeezeDepthDeg: Float = 0f,
    /** True from the sample that bumped [squeezeSeq] until the squeeze is released or cancelled. */
    val squeezeHeld: Boolean = false,
    /** B48: bumps once per recognized [TentTiltDetector] tilt while [FoldPose.Tent] holds. */
    val tentTiltSeq: Int = 0,
    /** The direction of the tilt that last bumped [tentTiltSeq]; null before the first one. */
    val tentTiltDir: TiltDirection? = null,
)

/**
 * Live Pose Engine for the Fold 8. Main thread only; call [start] from a
 * foreground component and [stop] when it goes away.
 *
 * Inputs (PLAN.md fact 4: no continuous hinge angle for apps):
 *  - physical panel behind display 0 (DisplayManager, mode size),
 *  - last hinge step from TYPE_HINGE_ANGLE (0 / 90 / 180 on this firmware),
 *  - TYPE_GRAVITY,
 *  - TYPE_GYROSCOPE while a panel is known: rotation around the hinge axis is the "being
 *    opened" (cover active) or "being closed" (inner active) signal the quantised hinge
 *    sensor cannot give in time (Continuum A, cover frost and closing frost). It runs at
 *    SENSOR_DELAY_GAME only between [start] and [stop], i.e. while the launcher is in front
 *    (PoseEngine ref-counts the activity's onStart/onStop), so it costs nothing in the background.
 *  - time since the last panel swap or hinge step (InMotion window),
 *  - TYPE_MAGNETIC_FIELD_UNCALIBRATED at SENSOR_DELAY_GAME (~50 Hz) into [HingeAngleEstimator]:
 *    the continuous hinge angle (PLAN.md fact 4, supplement of 2026-09-15) published as
 *    [PoseSnapshot.hingeAngleDeg] once confident, with the 0/180 rests learned on this device
 *    ([HingeRestStore]) as the table's endpoints,
 *  - TYPE_GAME_ROTATION_VECTOR (no magnetometer inside) for the Earth-field compensation
 *    ([EarthFieldCompensator]): learned from the rests, subtracted from every magnetometer
 *    sample before the estimator sees it. Skipped, and logged, when the sensor is missing.
 *
 * Logging: `transition …` / `POSE=…` at D/I; on debuggable builds (or with
 * `setprop log.tag.PoseRepository VERBOSE`) every hinge sample and every fast gyro sample
 * are logged at V, so a device log shows exactly what the sensors delivered and when.
 */
class PoseRepository(
    context: Context,
    private val thresholds: PoseThresholds = PoseThresholds(),
    private val motion: HingeMotionDetectors = HingeMotionDetectors(),
    /**
     * `Build.MODEL` (e.g. "SM-F971B"), used only to look up a magnetometer calibration table
     * ([HingeCalibration.forModel]) — nothing else in this class is model-specific. A device
     * with no table (a Galaxy Z Fold 7, or any future device this launcher has not been
     * calibrated on) never registers the magnetometer/rotation-vector sensors: [PoseSnapshot.hingeAngleDeg]
     * stays NaN and [PoseSnapshot.hingeAngleSource] stays `"step"` for the whole session, which
     * is the documented, guaranteed fallback (PLAN.md fact 4) — the morph runs on the quantized
     * 0/90/180 hinge step and the pacing floor, not the (nonexistent) continuous angle.
     */
    deviceModel: String = android.os.Build.MODEL,
) : SensorEventListener, DisplayManager.DisplayListener {
    private val app = context.applicationContext
    private val displayManager = app.getSystemService(DisplayManager::class.java)
    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val classifier = PoseClassifier(thresholds)
    private val hingeSource = HingeAngleSource(app, handler) { onHinge(it) }
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rests = HingeRestStore(app)
    /** This device's magnetometer table, or `null` when [deviceModel] has none ([HingeCalibration.forModel]) — [startMag] never registers a sensor when this is null. */
    private val hingeCalibration: HingeCalibration? = HingeCalibration.forModel(deviceModel)
    /** The hinge-angle estimator; its table is the device's once both rests are known. */
    // The table keeps the fixture's shape and span (FOLD8_BX); only its offset is anchored at
    // rests. Rescaling the endpoints to this device's last closed/open rest (2026-09-15) let the
    // Earth field of whichever orientation the rest happened in stretch the whole table
    // (closed drifted -197 -> -154 uT, 17. 9. log: estimate 165 while the HAL read 123).
    // Constructed with the Fold 8's table even off-Fold-8: harmless, since startMag() below
    // never feeds it a sample when hingeCalibration is null.
    val estimator = HingeAngleEstimator(hingeCalibration ?: HingeCalibration.FOLD8_BX).also {
        it.onRest = { r -> onRest(r) }
        it.onMicroRest = { r -> onMicroRest(r) }
    }
    private val angleGate = HingeAngleGate()
    val earthField = EarthFieldCompensator()
    /**
     * B44 "Fúze úhlu v3": trajectory model + gyro/gravity fused inside the 90 band, instead of
     * the magnetometer alone. Always constructed (cheap) but only fed/consulted while
     * [HINGE_FUSION_V3] is true, so the v2 path (raw [estimator] + [HingeStepGate]) stays
     * selectable for an A/B without removing either.
     */
    val trajectoryEstimator = HingeTrajectoryEstimator().also {
        it.onTransitionSummary = { s -> onTrajectoryTransition(s) }
    }
    private var trajectoryValid = false
    /** B48 "Pant jako ovladač": squeeze/tent-tilt are independent of [HINGE_FUSION_V3] — both are
     * fed straight from the same hinge-step and gyro samples this class already sees, never from
     * [trajectoryEstimator] or [estimator]. */
    private val squeeze = HingeSqueezeDetector()
    /**
     * "Zavírání jako Duo" (17. 9. noc): bridges [hingeAngleDeg] from ~172° down while the raw
     * step still reads Flat, fed the same hinge-step and gyro samples as [squeeze] (v2 path
     * only — [HINGE_FUSION_V3] has its own, independent release-from-motion in
     * [trajectoryEstimator]). See [gatedAngle].
     */
    private val closingOnset = ClosingOnsetDetector()
    /** `"motion"` / `"integral"` while [closingOnset] is bridging [hingeAngleDeg]; null otherwise (the ordinary [hingeAngleSourceTag] applies). */
    private var closingOnsetSourceLabel: String? = null
    private val tentTilt = TentTiltDetector()
    private var squeezeSeq = 0
    private var squeezeDepthDeg = 0f
    private var squeezeHeld = false
    private var tentTiltSeq = 0
    private var tentTiltDir: TiltDirection? = null
    private val verbose = Log.isLoggable(TAG, Log.VERBOSE) ||
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _state = MutableStateFlow(FoldPose.Closed)
    val state: StateFlow<FoldPose> = _state.asStateFlow()
    private val _snapshot = MutableStateFlow(PoseSnapshot())
    val snapshot: StateFlow<PoseSnapshot> = _snapshot.asStateFlow()

    private var panel = Panel.Unknown
    private var hingeDeg = Float.NaN
    private var gravity = floatArrayOf(0f, 0f, 0f)
    private var lastTransitionMs = Long.MIN_VALUE
    private var lastMotionMs = 0L
    private var started = false
    private var gyroRegistered = false
    private var hingeSamples = 0
    private var magSamples = 0
    private var magRegistered = false
    private var rotationRegistered = false
    /** Latest game rotation vector `[x, y, z, w]`; null before the first sample (no compensation). */
    private var rotation: FloatArray? = null
    private var hingeAngleDeg = Float.NaN
    private var hingeAngleConfidence = 0f
    private var lastAngleLogMs = 0L // not MIN_VALUE: `now - MIN_VALUE` overflows and the periodic log never fires
    private var lastLoggedAngle = Float.NaN
    private val exitMotion = Runnable { evaluate() }

    /** The gyroscope has its own listener so it can be (un)registered with the panel. */
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onGyro(event)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Magnetometer + game rotation vector, registered between [start] and [stop]. */
    private val magListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> onMag(event)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> onRotation(event)
            else -> Unit
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.i(TAG, "mag accuracy changed: ${sensor?.name} -> $accuracy")
        }
    }

    fun start() {
        if (started) return
        started = true
        panel = displayManager.getDisplay(Display.DEFAULT_DISPLAY).panel()
        displayManager.registerDisplayListener(this, handler)
        hingeSource.start()
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0, handler) }
        updateGyro()
        startMag()
        lastMotionMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "start panel=$panel hinge=${hingeSource.sensor?.name ?: "none"} gyro=${gyroSensor?.name ?: "none"} " +
            "mag=${magSensor?.name ?: "none"} rotation=${rotationSensor?.name ?: "none"} verbose=$verbose")
        evaluate()
    }

    /**
     * The magnetometer (SENSOR_DELAY_GAME = 20 ms; FASTEST needs HIGH_SAMPLING_RATE_SENSORS on
     * Android 12+ and the estimator was fitted at 100 Hz but needs no more than ~50) and the game
     * rotation vector, both on the main handler. The estimate is invalid until the first rest
     * anchor of this run, so the table's stale offset never reaches a consumer.
     */
    private fun startMag() {
        if (hingeCalibration == null) {
            Log.i(TAG, "hinge angle: no magnetometer table for this device model, estimator off (step sensor only)")
            return
        }
        val mag = magSensor
        if (mag == null) {
            Log.w(TAG, "hinge angle: no TYPE_MAGNETIC_FIELD_UNCALIBRATED, estimator off")
            return
        }
        magRegistered = sensorManager.registerListener(magListener, mag, SensorManager.SENSOR_DELAY_GAME, 0, handler)
        val rot = rotationSensor
        rotationRegistered = rot != null && sensorManager.registerListener(magListener, rot, SensorManager.SENSOR_DELAY_GAME, 0, handler)
        if (rot == null) Log.w(TAG, "hinge angle: no TYPE_GAME_ROTATION_VECTOR, Earth-field compensation skipped")
        val c = rests.closed; val o = rests.open
        Log.i(TAG, "hinge angle: mag registered=$magRegistered name='${mag.name}' minDelayUs=${mag.minDelay} rate=GAME " +
            "rotation registered=$rotationRegistered table=${if (c != null && o != null) "device" else "default"} " +
            "rests closed=${c?.feature?.let { "%.1f".format(it) } ?: "-"} open=${o?.feature?.let { "%.1f".format(it) } ?: "-"} uT " +
            "table0=%.1f table180=%.1f".format(estimator.calibration.valueAt(0f), estimator.calibration.valueAt(180f)))
    }

    fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(this)
        hingeSource.stop()
        sensorManager.unregisterListener(this)
        if (gyroRegistered) { sensorManager.unregisterListener(gyroListener); gyroRegistered = false }
        if (magRegistered || rotationRegistered) { sensorManager.unregisterListener(magListener); magRegistered = false; rotationRegistered = false }
        motion.reset()
        handler.removeCallbacks(exitMotion)
        rotation = null
        hingeAngleDeg = Float.NaN; angleGate.reset()
        trajectoryValid = false
        hingeAngleConfidence = 0f
        // B48: no more samples arrive until the next start(), so a held squeeze (or a candidate
        // mid-gesture) is dropped without its usual Released signal — same as [squeezeSeq] and
        // [tentTiltSeq] themselves, left as they were: monotonic counters, not reset on stop/start.
        squeezeDepthDeg = 0f; squeezeHeld = false
        Log.i(TAG, "stop (mag samples=$magSamples, anchors=${estimator.anchors}, earth |E|=%.1f uT from ${earthField.observations} rests)".format(earthField.magnitude))
    }

    // ---- inputs ----

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val now = displayManager.getDisplay(displayId).panel()
        if (now != Panel.Unknown && now != panel) {
            panel = now
            markTransition("panel=$now")
            motion.reset()
            updateGyro()
        }
        evaluate()
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    /**
     * The gyroscope listens while the engine runs and a panel is known: on the cover for the
     * opening motion, on the inner panel for the closing one ([HingeMotionDetectors]).
     */
    private fun updateGyro() {
        val sensor = gyroSensor ?: return
        val want = started && panel != Panel.Unknown
        if (want == gyroRegistered) return
        if (want) {
            motion.reset()
            val ok = sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME, 0, handler)
            gyroRegistered = ok
            Log.i(TAG, "gyro registered=$ok name='${sensor.name}' rate=GAME panel=$panel " +
                "threshold=${motion.opening.thresholdRadS} rad/s x${motion.opening.consecutiveSamples}")
        } else {
            sensorManager.unregisterListener(gyroListener)
            gyroRegistered = false
            motion.reset()
            Log.i(TAG, "gyro unregistered (panel=$panel)")
        }
    }

    private fun onHinge(sample: HingeSample) {
        hingeSamples++
        if (verbose) {
            Log.v(TAG, "hinge sample deg=${sample.deg} acc=${sample.accuracy} ts=${sample.timestampNs} " +
                "values=${sample.values.joinToString(",")} n=$hingeSamples panel=$panel " +
                "lagMs=${(SystemClock.elapsedRealtimeNanos() - sample.timestampNs) / 1_000_000}")
        }
        val deg = sample.deg
        val before = HingeStep.of(hingeDeg)
        hingeDeg = deg
        estimator.onHingeStep(sample.timestampNs, deg)
        if (HINGE_FUSION_V3) trajectoryEstimator.onStep(sample.timestampNs, deg)
        // B48: a step sample leaving Flat (or the panel not being Inner) cancels a squeeze in
        // progress *silently* (IDEAS.md B48: "a real close"); no signal to react to here.
        squeeze.onHingeStep(panel == Panel.Inner, deg)
        // "Zavírání jako Duo": the real step transition arriving means the bridge's job is done;
        // the ordinary step-based path (HingeStepGate's own pin/interpolation) takes over.
        closingOnset.onHingeStep(panel == Panel.Inner, deg)
        // The first sample after registration is the current angle, not motion.
        if (before != null && HingeStep.of(deg) != before) markTransition("hinge=${deg.toInt()}")
        evaluate()
    }

    private fun onGyro(event: SensorEvent) {
        val rate = event.values.getOrNull(OpeningMotionDetector.HINGE_AXIS) ?: return
        if (magRegistered) estimator.onGyroHingeRate(event.timestamp, rate)
        if (HINGE_FUSION_V3 && magRegistered) trajectoryEstimator.onGyroHingeRate(event.timestamp, rate)
        val fired = motion.feed(panel, rate)
        onSqueezeGyro(event.timestamp, rate, fired == HingeMotionDetectors.Fired.Closing)
        onClosingOnsetGyro(event.timestamp, rate, fired == HingeMotionDetectors.Fired.Closing)
        if (verbose && abs(rate) >= GYRO_LOG_FLOOR_RAD_S) {
            Log.v(TAG, "gyro sample hingeRate=%.2f rad/s |w|=%.2f panel=%s seq=%d closeSeq=%d".format(rate,
                Math.sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]).toDouble()), panel, motion.opening.seq, motion.closing.seq))
        }
        val hinge = if (hingeDeg.isNaN()) "-" else hingeDeg.toInt().toString()
        when (fired) {
            HingeMotionDetectors.Fired.Opening ->
                Log.i(TAG, "opening motion: hingeRate=%.2f rad/s seq=%d panel=%s hinge=%s".format(rate, motion.opening.seq, panel, hinge))
            HingeMotionDetectors.Fired.Closing -> {
                Log.i(TAG, "closing motion: hingeRate=%.2f rad/s seq=%d panel=%s hinge=%s".format(rate, motion.closing.seq, panel, hinge))
                if (HINGE_FUSION_V3 && trajectoryEstimator.releaseClosingFromMotion(event.timestamp)) {
                    Log.i(TAG, "hinge fusion v3: closing released from motion, flatIntegral=%.1f deg, starting at %.0f".format(
                        trajectoryEstimator.flatClosingIntegralDeg(), HingeTrajectoryEstimator.CLOSING_RELEASE_ANGLE))
                }
            }
            HingeMotionDetectors.Fired.None -> {
                if (HINGE_FUSION_V3) publishFusionAngle() else publishClosingOnsetIfActive()
                evaluate()
                return
            }
        }
        if (HINGE_FUSION_V3) publishFusionAngle() else publishClosingOnsetIfActive()
        evaluate()
    }

    /**
     * "Zavírání jako Duo": feeds [closingOnset] from the same gyro sample [onGyro] just fed
     * [motion]/[squeeze], and republishes [hingeAngleDeg] immediately on the sample that confirms
     * a fresh onset (rather than waiting for the next magnetometer sample) so the bridge is
     * visible as early as the gyro itself allows. Every other sample is republished through
     * [publishClosingOnsetIfActive] right after this call in [onGyro] regardless, so a live
     * [depthDeg] update (while already active) still reaches [hingeAngleDeg] promptly.
     */
    private fun onClosingOnsetGyro(timestampNs: Long, rate: Float, closingMotionFired: Boolean) {
        val nowMs = timestampNs / 1_000_000L
        val justActive = closingOnset.onGyro(nowMs, panel == Panel.Inner, hingeDeg, rate, closingMotionFired)
        if (justActive) {
            Log.i(TAG, "closing onset: source=${closingOnset.source} depth=%.1f angle=%.1f panel=%s".format(
                closingOnset.depthDeg, closingOnset.angleDeg(), panel))
        }
    }

    /**
     * "Zavírání jako Duo": the v2 counterpart of [publishFusionAngle] — while [closingOnset] is
     * bridging the angle, [gatedAngle] returns its estimate regardless of the magnetometer's own
     * confidence (a real, gyro-measured rotation needs no magnetometer corroboration); otherwise
     * this is a no-op and the ordinary [onMag]-driven value stands (a gyro sample alone, with no
     * bridge active, has nothing new to publish).
     */
    private fun publishClosingOnsetIfActive() {
        if (!closingOnset.active) {
            closingOnsetSourceLabel = null
            return
        }
        closingOnsetSourceLabel = when (closingOnset.source) {
            ClosingOnsetDetector.Source.MOTION -> "motion"
            ClosingOnsetDetector.Source.INTEGRAL -> "integral"
            ClosingOnsetDetector.Source.NONE -> null
        }
        hingeAngleDeg = gatedAngle(Float.NaN)
        publish()
    }

    /**
     * The angle [HingeStepGate] publishes for this instant: the closing-onset bridge while
     * [closingOnset] is active (bypassing the magnetometer/step pin at Flat, see
     * [HingeStepGate.apply]'s `closingOnsetDeg`), otherwise the ordinary v2 gate over
     * [magEstimateDeg].
     */
    private fun gatedAngle(magEstimateDeg: Float): Float =
        HingeStepGate.apply(hingeDeg, magEstimateDeg, closingOnsetDeg = if (closingOnset.active) closingOnset.angleDeg() else Float.NaN)

    /**
     * B48 "Pant jako ovladač": feeds [squeeze] from the same gyro sample [onGyro] just fed
     * [motion], converting the sensor's boot-elapsed nanosecond timestamp to milliseconds (the
     * detector's own dt/debounce math is relative, so any monotonic clock works). Reacting to the
     * [SqueezeSignal] here (rather than leaving it to [evaluate]) keeps the log line next to the
     * other per-sample gyro logging above/below it in this file.
     */
    private fun onSqueezeGyro(timestampNs: Long, rate: Float, closingMotionFired: Boolean) {
        val nowMs = timestampNs / 1_000_000L
        when (val signal = squeeze.onGyro(nowMs, panel == Panel.Inner, hingeDeg, rate, closingMotionFired)) {
            is SqueezeSignal.Recognized -> {
                squeezeSeq++; squeezeHeld = true; squeezeDepthDeg = signal.depthDeg
                Log.i(TAG, "squeeze: recognized depth=%.1f holdMs=%d seq=%d".format(signal.depthDeg, signal.holdMs, squeezeSeq))
            }
            SqueezeSignal.Released -> {
                squeezeHeld = false; squeezeDepthDeg = 0f
                Log.i(TAG, "squeeze: released")
            }
            null -> if (squeezeHeld) squeezeDepthDeg = squeeze.depthDeg // live depth while held (drag-like follow)
        }
    }

    // ---- hinge angle (magnetometer) ----

    private fun onRotation(event: SensorEvent) {
        val q = rotation ?: FloatArray(4).also { rotation = it }
        q[0] = event.values[0]; q[1] = event.values[1]; q[2] = event.values[2]
        q[3] = if (event.values.size > 3) event.values[3] else sqrt((1f - q[0] * q[0] - q[1] * q[1] - q[2] * q[2]).coerceAtLeast(0f))
    }

    /**
     * `earthField.deviceEarth(q)` gated on [EarthFieldCompensator.quality]: below `GOOD` (thin
     * orientation coverage, an out-of-range or badly-fit solve — B19) the solve is not trusted,
     * so nothing is subtracted and the caller sees v1 behaviour (table absorbs whatever Earth
     * field is baked into the current rest, same as before the trajectory fit).
     */
    private fun activeEarthDevice(q: FloatArray): FloatArray =
        if (earthField.quality == EarthFieldCompensator.Quality.GOOD) earthField.deviceEarth(q) else floatArrayOf(0f, 0f, 0f)

    private fun onMag(event: SensorEvent) {
        magSamples++
        val raw = floatArrayOf(event.values[0], event.values[1], event.values[2])
        val q = rotation
        val b = if (q == null) raw.copyOf(3) else {
            val e = activeEarthDevice(q)
            floatArrayOf(raw[0] - e[0], raw[1] - e[1], raw[2] - e[2])
        }
        val est = estimator.onMagUncalibrated(event.timestamp, b[0], b[1], b[2])
        // At the step sensor's ends the angle is pinned (HingeStepGate: 0 / 180) whatever the
        // magnetometer's confidence says, so the gate must not flap the angle in and out there:
        // 17. 9. log — conf 0.19 at rest on 180 turned angle mode off mid-unfold and let the
        // timed fallback replay the whole morph a second time.
        val gateValid = angleGate.update(event.timestamp, est.confidence, estimator.anchors >= 1)
        val stepPinned = !hingeDeg.isNaN() && (hingeDeg <= 45f || hingeDeg >= 135f)
        val valid = estimator.anchors >= 1 && (stepPinned || gateValid)
        trajectoryValid = valid
        if (HINGE_FUSION_V3) trajectoryEstimator.onMagnetAngle(event.timestamp, est.angleDeg, est.confidence)
        // "Zavírání jako Duo": gatedAngle bridges from closingOnset regardless of `valid` (a
        // gyro-measured onset needs no magnetometer corroboration) — it falls back to the
        // ordinary v2 gate over the magnetometer estimate exactly when closingOnset is not active.
        val angle = gatedAngle(if (valid) est.angleDeg else Float.NaN)
        val wasValid = !hingeAngleDeg.isNaN()
        hingeAngleConfidence = est.confidence
        val now = SystemClock.elapsedRealtime()
        if (valid != wasValid) {
            Log.i(TAG, "hinge angle: ${if (valid) "valid" else "invalid"} est=%.1f conf=%.2f anchors=%d offset=%.1f uT step=%s".format(
                est.angleDeg, est.confidence, estimator.anchors, est.offsetUt, hingeStepText()))
            lastLoggedAngle = Float.NaN
        }
        if (valid && now - lastAngleLogMs >= ANGLE_LOG_MIN_MS && (lastLoggedAngle.isNaN() || abs(angle - lastLoggedAngle) >= ANGLE_LOG_MIN_DEG)) {
            lastAngleLogMs = now; lastLoggedAngle = angle
            Log.i(TAG, "hinge angle: est=%.1f conf=%.2f src=mag step=%s bx=%.1f raw=%.1f offset=%.1f panel=%s".format(
                angle, est.confidence, hingeStepText(), b[0], raw[0], est.offsetUt, panel))
            if (HINGE_FUSION_V3) {
                Log.v(TAG, "hinge fusion v3: angle=%.1f conf=%.2f src=%s phase=%s".format(
                    trajectoryEstimator.angleDeg, trajectoryEstimator.confidence, trajectoryEstimator.source, trajectoryEstimator.phase))
            }
        }
        if (verbose && magSamples % 50 == 0) {
            Log.v(TAG, "mag sample n=$magSamples raw=%.1f/%.1f/%.1f comp=%.1f/%.1f/%.1f est=%.1f conf=%.2f pendingRest=%s".format(
                raw[0], raw[1], raw[2], b[0], b[1], b[2], est.angleDeg, est.confidence, estimator.pendingRestAngle))
        }
        if (HINGE_FUSION_V3) {
            publishFusionAngle()
        } else {
            closingOnsetSourceLabel = when {
                !closingOnset.active -> null
                closingOnset.source == ClosingOnsetDetector.Source.MOTION -> "motion"
                else -> "integral"
            }
            hingeAngleDeg = angle
            publish()
        }
    }

    /**
     * B44: recomputes [hingeAngleDeg]/[hingeAngleSource] from [trajectoryEstimator] and
     * republishes. Called after every gyro and magnetometer sample while [HINGE_FUSION_V3] is on,
     * so the fused angle updates at (roughly) the gyro's rate, not just the magnetometer's.
     * [HingeStepGate] still pins the ends to hard 0/180, except while a motion-released closing
     * (Morph v3 e) is under way and the step sensor still reads Flat — that window is exactly the
     * point of the release, so the gate would otherwise hide it.
     */
    private fun publishFusionAngle() {
        val motionReleased = trajectoryEstimator.phase == HingeTrajectoryEstimator.Phase.CLOSING && HingeStep.of(hingeDeg) == HingeStep.Flat
        val v3Angle = trajectoryEstimator.angleDeg
        hingeAngleDeg = when {
            !trajectoryValid -> Float.NaN
            motionReleased -> v3Angle
            else -> HingeStepGate.apply(hingeDeg, v3Angle)
        }
        publish()
    }

    /** B44 per-transition summary line: duration, predicted D, anchors seen, mean |gyro|, source mix. */
    private fun onTrajectoryTransition(s: HingeTrajectoryEstimator.TransitionSummary) {
        Log.i(TAG, "hinge fusion v3: transition %s actual=%dms predicted=%dms anchors=%d meanAbsGyro=%.2f rad/s gyroTrusted=%.0f%%".format(
            if (s.opening) "open" else "close", s.actualDurationMs, s.predictedDurationMs, s.anchorsSeen,
            s.meanAbsGyroRadS, s.gyroTrustedFraction * 100f))
    }

    /**
     * The estimator confirmed a rest (0/180 step, >= 1 s still): record this device's rest
     * value (the table's endpoint), rescale the table once both are known, and hand the raw
     * field + attitude to the Earth-field solver, re-anchoring against the new compensation.
     */
    private fun onRest(rest: HingeAngleEstimator.Rest) {
        val q = rotation
        rests.record(rest.angleDeg, HingeRestStore.Rest(rest.x, rest.y, rest.z, rest.feature, System.currentTimeMillis()))
        // rests are recorded for the log and future fits only; the table is not rescaled (see estimator init)
        var earth = "skipped (no rotation vector)"
        if (q != null) {
            // The raw rest vector = compensated medians + what was subtracted (constant over a still rest).
            val e = activeEarthDevice(q)
            earthField.learnAtRest(rest.timestampNs, rest.angleDeg, q, floatArrayOf(rest.x + e[0], rest.y + e[1], rest.z + e[2]))
            // The compensation (and possibly its quality gate) changed under the estimator: re-anchor the offset on the new one.
            val eNew = activeEarthDevice(q)
            estimator.anchor(rest.angleDeg, rest.feature + e[0] - eNew[0], rest.timestampNs)
            earth = "E_world=%.1f/%.1f/%.1f |E|=%.1f uT rests=%d pairs=%d diversity=%.3f residual=%.1f uT quality=%s".format(
                earthField.earthWorld[0], earthField.earthWorld[1], earthField.earthWorld[2], earthField.magnitude,
                earthField.observations, earthField.pairs, earthField.diversity, earthField.residualUt, earthField.quality)
        }
        Log.i(TAG, "hinge rest: step=%.0f bx=%.1f (x/y/z %.1f/%.1f/%.1f) anchors=%d offset=%.1f uT table=%s [0=%.1f 180=%.1f] earth: %s".format(
            rest.angleDeg, rest.feature, rest.x, rest.y, rest.z, estimator.anchors, estimator.offsetUt,
            if (rests.closed != null && rests.open != null) "device" else "default",
            estimator.calibration.valueAt(0f), estimator.calibration.valueAt(180f), earth))
    }

    /**
     * A still moment mid-transition, any angle (B19 anchor diversity): Earth-field-only, never
     * touches the calibration table or [estimator]'s offset. Silently skipped without an
     * attitude (same as [onRest]).
     */
    private fun onMicroRest(rest: HingeAngleEstimator.Rest) {
        val q = rotation ?: return
        val e = activeEarthDevice(q)
        earthField.learnAtMicroRest(rest.timestampNs, rest.angleDeg, q, floatArrayOf(rest.x + e[0], rest.y + e[1], rest.z + e[2]))
        if (verbose) {
            Log.v(TAG, "hinge micro-rest: angle=%.1f bx=%.1f rests=%d pairs=%d diversity=%.3f quality=%s".format(
                rest.angleDeg, rest.feature, earthField.observations, earthField.pairs, earthField.diversity, earthField.quality))
        }
    }

    private fun hingeStepText() = if (hingeDeg.isNaN()) "-" else hingeDeg.toInt().toString()

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GRAVITY) return
        val g = event.values
        if (HINGE_FUSION_V3) trajectoryEstimator.onGravity(event.timestamp, g[0], g[1], g[2])
        val delta = abs(g[0] - gravity[0]) + abs(g[1] - gravity[1]) + abs(g[2] - gravity[2])
        if (delta > STILL_DELTA) {
            lastMotionMs = SystemClock.elapsedRealtime()
            handler.removeCallbacks(exitMotion)
            handler.postDelayed(exitMotion, thresholds.standStillMsMin + 10)
        }
        gravity = floatArrayOf(g[0], g[1], g[2])
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun markTransition(why: String) {
        lastTransitionMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "transition $why")
        handler.removeCallbacks(exitMotion)
        handler.postDelayed(exitMotion, thresholds.inMotionWindowMs + 10)
    }

    // ---- output ----

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val since = if (lastTransitionMs == Long.MIN_VALUE) Long.MAX_VALUE else now - lastTransitionMs
        val stillMs = now - lastMotionMs
        val pose = classifier.classify(panel, hingeDeg, gravity[0], gravity[1], gravity[2], since, stillMs, _state.value)
        // B48 "Pant jako ovladač": tent tilt only needs the pose (Tent gate) and gravity's x/z
        // (the plane the hinge-axis rotation moves within, see TentTilt.kt) — both already
        // computed for the classifier above, so no extra sensor plumbing is needed here.
        tentTilt.onPose(now, pose, gravity[0], gravity[2])?.let { direction ->
            tentTiltSeq++; tentTiltDir = direction
            Log.i(TAG, "tent tilt: $direction seq=$tentTiltSeq")
        }
        val snap = PoseSnapshot(pose, panel, hingeDeg, gravity, since, stillMs,
            openingMotionSeq = motion.opening.seq, hingeRateRadS = motion.lastRateRadS, closingMotionSeq = motion.closing.seq,
            hingeAngleDeg = hingeAngleDeg, hingeAngleConfidence = hingeAngleConfidence,
            hingeAngleSource = hingeAngleSourceTag(),
            squeezeSeq = squeezeSeq, squeezeDepthDeg = squeezeDepthDeg, squeezeHeld = squeezeHeld,
            tentTiltSeq = tentTiltSeq, tentTiltDir = tentTiltDir)
        _snapshot.value = snap
        if (_state.value != pose) {
            _state.value = pose
            Log.i(TAG, "POSE=$pose panel=$panel hinge=${if (hingeDeg.isNaN()) "-" else hingeDeg.toInt()} " +
                "g=%.1f/%.1f/%.1f since=%s".format(gravity[0], gravity[1], gravity[2], if (since == Long.MAX_VALUE) "-" else "${since}ms"))
        }
    }

    /** A magnetometer or (v3) gyro sample: republish the snapshot with the new angle (the pose itself is unchanged). */
    private fun publish() {
        val s = _snapshot.value
        _snapshot.value = s.copy(hingeAngleDeg = hingeAngleDeg, hingeAngleConfidence = hingeAngleConfidence,
            hingeAngleSource = hingeAngleSourceTag())
    }

    /**
     * `"onset-motion"` / `"onset-integral"` while [ClosingOnsetDetector] is bridging the angle
     * ("Zavírání jako Duo", 17. 9. noc — [MorphController] reads this to log the onset source),
     * `"fusion"` under B44 (v3), `"mag"` under the ordinary v2 path, `"step"` while no angle is
     * valid at all.
     */
    private fun hingeAngleSourceTag(): String = when {
        hingeAngleDeg.isNaN() -> "step"
        closingOnsetSourceLabel != null -> "onset-$closingOnsetSourceLabel"
        HINGE_FUSION_V3 -> "fusion"
        else -> "mag"
    }

    companion object {
        private const val TAG = "PoseRepository"
        /**
         * B44 "Fúze úhlu v3": true routes [PoseSnapshot.hingeAngleDeg] through
         * [HingeTrajectoryEstimator] (trajectory model + gyro/gravity + magnet slow corrector)
         * instead of the raw magnetometer estimate; false keeps the v2 behaviour (this class's own
         * [estimator] + [HingeStepGate]) for an A/B. Compile-time only (no runtime toggle): flip and
         * rebuild to compare.
         */
        const val HINGE_FUSION_V3 = false // 17. 9.: off until validated on an in-hand recording (fixture on the table: v3 27° vs v2 24°; synthetic in-hand 3.95°)
        /** `hinge angle:` lines at most this often ... */
        private const val ANGLE_LOG_MIN_MS = 250L
        /** ... and only when the estimate moved this much since the last line. */
        private const val ANGLE_LOG_MIN_DEG = 2f
        /** Sum of |Δg| over the three axes above which the device counts as moving. */
        private const val STILL_DELTA = 0.35f
        /** Verbose gyro samples below this rate are not logged (the phone at rest is ~0.01 rad/s). */
        private const val GYRO_LOG_FLOOR_RAD_S = 0.5f
    }
}
