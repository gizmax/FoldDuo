package cz.pflanzer.foldduo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import cz.pflanzer.foldduo.pose.PoseRepository
import cz.pflanzer.foldduo.pose.PoseSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only Phase 0 recorder for the Pose Engine fixtures (see PLAN.md, Fáze 0).
 *
 * Writes one JSON object per line to
 * `/sdcard/Android/data/cz.pflanzer.foldduo/files/pose/<stamp>.jsonl`:
 *   hinge, gravity, folding (FoldingFeature), display (DisplayManager), window,
 *   label (manual), lifecycle, meta.
 * High-rate spike channels (SENSOR_DELAY_FASTEST, own HandlerThread, on while recording
 * unless `--ez sensors false`): `{type:"mag_u"|"mag"|"gyro"|"accel", sensorNs, wallMs, v:[…]}`
 * (mag_u carries all 6 values: field xyz + hard-iron bias xyz). The `meta` row logs
 * `elapsedNs`/`elapsedWallMs`/`elapsedToWallOffsetMs` so vendor HAL logcat lines
 * (`sensors-hal: hinge_angle ts=<ns>` – CLOCK_BOOTTIME) align with `sensorNs`; see
 * tools/hinge_truth.py + tools/hinge_fit.py (one-shot: tools/hinge_spike.sh).
 *
 * System device state (CLOSED/TENT/OPENED) is NOT app-readable (DeviceStateManager is
 * @SystemApi even on SDK 37); record it from the host with tools/pose_state_poll.sh in
 * parallel and merge on wallMs.
 *
 * Launch: `adb shell am start -n cz.pflanzer.foldduo/.PoseProbeActivity`
 * ADB control (singleTop, handled in onNewIntent too):
 *   `--ez record true`  start recording      `--ez stop true`  stop recording
 *   `--es label Table`  write a manual label  `--ez sensors true|false` high-rate channels
 * Pull:   `adb pull /sdcard/Android/data/cz.pflanzer.foldduo/files/pose pose/testdata/`
 *
 * The recorder lives in a process-level singleton so it survives the activity being
 * recreated when Samsung swaps the panel under logical display 0.
 */
class PoseProbeActivity : ComponentActivity(), SensorEventListener, DisplayManager.DisplayListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var displayManager: DisplayManager
    private var hinge: Sensor? = null
    private var gravity: Sensor? = null
    private var magU: Sensor? = null
    private var mag: Sensor? = null
    private var gyro: Sensor? = null
    private var accel: Sensor? = null
    private var highRateThread: HandlerThread? = null
    private var highRateHandler: Handler? = null
    private var highRateRegistered = false
    @Volatile private var lastMagNorm = Float.NaN
    @Volatile private var lastGyroNorm = Float.NaN
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var layoutJob: Job? = null
    private var poseJob: Job? = null
    private lateinit var poseRepository: PoseRepository
    private var poseSnapshot = PoseSnapshot()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var bigView: TextView
    private lateinit var detailView: TextView
    private lateinit var recordButton: Button

    private var lastHinge = Float.NaN
    private var lastGravity = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    private var lastGravityLogNs = 0L
    private var lastFolding = "none"
    private var lastLabel = "-"

    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 100L) }
    }

    /** Enabled only while [PoseRecorder.isRecording]; when disabled the default Back (finish) runs. */
    private val backWhileRecording = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            PoseRecorder.log("lifecycle", JSONObject().put("callback", "backIgnoredWhileRecording"))
            Toast.makeText(this@PoseProbeActivity, "Recording — use STOP", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        sensorManager = getSystemService(SensorManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        hinge = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        magU = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        highRateThread = HandlerThread("pose-probe-sensors").also { it.start(); highRateHandler = Handler(it.looper) }
        poseRepository = PoseRepository(this)
        buildContent()
        // Fold gestures near the edge fire the system Back gesture and finished the probe mid-recording
        // (`wm_finish_activity … app-request` in the 2026-09-15 logcat). While recording, Back is swallowed.
        onBackPressedDispatcher.addCallback(this, backWhileRecording)
        PoseRecorder.log("lifecycle", JSONObject().put("callback", "onCreate").put("restored", savedInstanceState != null))
        logMeta()
        logWindow("onCreate")
        logDisplays("onCreate")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        intent ?: return
        val wantSensors = intent.getBooleanExtra("sensors", true)
        if (intent.getBooleanExtra("record", false) && !PoseRecorder.isRecording) {
            PoseRecorder.start(this)
            PoseRecorder.highRate = wantSensors
            logMeta(); logWindow("recordStart"); logDisplays("recordStart")
            setHighRate(wantSensors)
            Log.i("PoseProbe", "record started via intent (sensors=$wantSensors)")
        } else if (intent.hasExtra("sensors") && PoseRecorder.isRecording) {
            PoseRecorder.highRate = wantSensors
            setHighRate(wantSensors)
            logMeta()
            Log.i("PoseProbe", "high-rate sensors via intent: $wantSensors")
        }
        intent.getStringExtra("label")?.let { label ->
            lastLabel = label
            PoseRecorder.log("label", JSONObject().put("label", label))
            Log.i("PoseProbe", "label via intent: $label")
        }
        if (intent.getBooleanExtra("stop", false) && PoseRecorder.isRecording) {
            setHighRate(false)
            PoseRecorder.stop()
            Log.i("PoseProbe", "record stopped via intent")
        }
        render()
    }

    /** Registers/unregisters the FASTEST-rate spike channels on their own thread. */
    private fun setHighRate(on: Boolean) {
        if (on == highRateRegistered) return
        if (on) {
            val h = highRateHandler ?: return
            for (s in listOfNotNull(magU, mag, gyro, accel)) {
                // FASTEST (0 us) needs HIGH_SAMPLING_RATE_SENSORS (declared in the debug manifest);
                // if the platform still refuses, fall back to 200 Hz rather than crash the probe.
                try {
                    sensorManager.registerListener(highRateListener, s, SensorManager.SENSOR_DELAY_FASTEST, 0, h)
                } catch (e: SecurityException) {
                    Log.w("PoseProbe", "FASTEST refused for ${s.name}: ${e.message}; using 5000 us")
                    PoseRecorder.log("accuracy", JSONObject().put("sensor", s.name).put("fallbackUs", 5000).put("reason", e.message))
                    sensorManager.registerListener(highRateListener, s, 5000, 0, h)
                }
            }
            highRateRegistered = true
        } else {
            sensorManager.unregisterListener(highRateListener)
            highRateRegistered = false
        }
        PoseRecorder.log("lifecycle", JSONObject().put("callback", if (on) "highRateOn" else "highRateOff"))
    }

    private val highRateListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (name, n) = when (event.sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "mag_u" to 6
                Sensor.TYPE_MAGNETIC_FIELD -> "mag" to 3
                Sensor.TYPE_GYROSCOPE -> "gyro" to 3
                Sensor.TYPE_ACCELEROMETER -> "accel" to 3
                else -> return
            }
            val v = JSONArray()
            val count = minOf(n, event.values.size)
            for (i in 0 until count) v.put(event.values[i].toDouble())
            when (name) {
                "mag_u" -> lastMagNorm = norm3(event.values)
                "gyro" -> lastGyroNorm = norm3(event.values)
            }
            PoseRecorder.log(name, JSONObject().put("sensorNs", event.timestamp).put("v", v))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            PoseRecorder.log("accuracy", JSONObject().put("sensor", sensor.name).put("acc", accuracy))
        }
    }

    private fun norm3(v: FloatArray): Float =
        if (v.size < 3) Float.NaN else kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(this, handler)
        hinge?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0, handler) }
        gravity?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0, handler) }
        if (PoseRecorder.isRecording && PoseRecorder.highRate) setHighRate(true)
        layoutJob = scope.launch {
            WindowInfoTracker.getOrCreate(this@PoseProbeActivity)
                .windowLayoutInfo(this@PoseProbeActivity)
                .collect { info -> onLayoutInfo(info) }
        }
        poseRepository.start()
        poseJob = scope.launch {
            poseRepository.snapshot.collect { snap ->
                if (snap.pose != poseSnapshot.pose) PoseRecorder.log("pose", JSONObject().put("pose", snap.pose.name))
                poseSnapshot = snap
                render()
            }
        }
        PoseRecorder.log("lifecycle", JSONObject().put("callback", "onStart"))
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        PoseRecorder.log("lifecycle", JSONObject().put("callback", "onResume"))
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        PoseRecorder.log("lifecycle", JSONObject().put("callback", "onPause"))
        super.onPause()
    }

    override fun onStop() {
        sensorManager.unregisterListener(this)
        setHighRate(false)
        displayManager.unregisterDisplayListener(this)
        layoutJob?.cancel()
        layoutJob = null
        poseJob?.cancel()
        poseJob = null
        poseRepository.stop()
        PoseRecorder.log("lifecycle", JSONObject().put("callback", "onStop"))
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        highRateThread?.quitSafely()
        highRateThread = null
        highRateHandler = null
        PoseRecorder.log("lifecycle", JSONObject().put("callback", "onDestroy").put("changingConfigurations", isChangingConfigurations))
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        logWindow("configChanged")
    }

    // ---- sensors ----

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HINGE_ANGLE -> {
                lastHinge = event.values[0]
                PoseRecorder.log(
                    "hinge",
                    JSONObject().put("deg", lastHinge.toDouble()).put("acc", event.accuracy).put("sensorNs", event.timestamp),
                )
            }
            Sensor.TYPE_GRAVITY -> {
                lastGravity = event.values.copyOf(3)
                if (event.timestamp - lastGravityLogNs >= GRAVITY_LOG_INTERVAL_NS) {
                    lastGravityLogNs = event.timestamp
                    PoseRecorder.log(
                        "gravity",
                        JSONObject().put("x", lastGravity[0].toDouble()).put("y", lastGravity[1].toDouble())
                            .put("z", lastGravity[2].toDouble()).put("sensorNs", event.timestamp),
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        PoseRecorder.log("accuracy", JSONObject().put("sensor", sensor.name).put("acc", accuracy))
    }

    // ---- folding feature ----

    private fun onLayoutInfo(info: WindowLayoutInfo) {
        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        lastFolding = if (fold == null) "none" else
            "${fold.state} ${fold.orientation} ${fold.bounds.toShortString()} sep=${fold.isSeparating} occ=${fold.occlusionType}"
        PoseRecorder.log(
            "folding",
            JSONObject()
                .put("present", fold != null)
                .put("state", fold?.state?.toString())
                .put("orientation", fold?.orientation?.toString())
                .put("bounds", fold?.bounds?.toShortString())
                .put("separating", fold?.isSeparating)
                .put("occlusion", fold?.occlusionType?.toString()),
        )
        render()
    }

    // ---- displays / window ----

    override fun onDisplayAdded(displayId: Int) = logDisplays("added:$displayId")
    override fun onDisplayRemoved(displayId: Int) = logDisplays("removed:$displayId")
    override fun onDisplayChanged(displayId: Int) { logDisplays("changed:$displayId"); logWindow("displayChanged:$displayId") }

    private fun logDisplays(reason: String) {
        for (display in displayManager.displays.sortedBy { it.displayId }) {
            val mode = display.mode
            PoseRecorder.log(
                "display",
                JSONObject()
                    .put("reason", reason)
                    .put("id", display.displayId)
                    .put("name", display.name)
                    .put("state", displayState(display.state))
                    .put("physical", "${mode.physicalWidth}x${mode.physicalHeight}")
                    .put("panel", panelFor(mode.physicalWidth, mode.physicalHeight))
                    .put("rotation", display.rotation)
                    .put("presentation", display.flags and Display.FLAG_PRESENTATION != 0),
            )
        }
    }

    private fun logWindow(reason: String) {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val config = resources.configuration
        val display = window.decorView.display
        PoseRecorder.log(
            "window",
            JSONObject()
                .put("reason", reason)
                .put("bounds", bounds.toShortString())
                .put("dp", "${config.screenWidthDp}x${config.screenHeightDp}")
                .put("densityDpi", resources.displayMetrics.densityDpi)
                .put("displayId", display?.displayId)
                .put("panel", display?.mode?.let { panelFor(it.physicalWidth, it.physicalHeight) }),
        )
    }

    private fun panelFor(w: Int, h: Int): String {
        val long = maxOf(w, h); val short = minOf(w, h)
        return when {
            long == 2448 && short == 1848 -> "inner"
            long == 1972 && short == 1248 -> "cover"
            else -> "unknown"
        }
    }

    private fun logMeta() {
        val h = hinge; val g = gravity
        // Same-instant pair so host tools can map HAL logcat `ts=<ns>` (CLOCK_BOOTTIME) to wallMs:
        // wallMs ≈ sensorNs / 1e6 + elapsedToWallOffsetMs.
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val wallMs = System.currentTimeMillis()
        PoseRecorder.log(
            "meta",
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("build", Build.DISPLAY)
                .put("hingeSensor", h?.let { "${it.name} vendor=${it.vendor} minDelayUs=${it.minDelay} res=${it.resolution} range=${it.maximumRange} wake=${it.isWakeUpSensor}" })
                .put("gravitySensor", g?.let { "${it.name} vendor=${it.vendor}" })
                .put("magUSensor", describe(magU))
                .put("magSensor", describe(mag))
                .put("gyroSensor", describe(gyro))
                .put("accelSensor", describe(accel))
                .put("highRate", PoseRecorder.highRate)
                .put("elapsedNs", elapsedNs)
                .put("elapsedWallMs", wallMs)
                .put("elapsedToWallOffsetMs", wallMs - elapsedNs / 1_000_000.0)
                .put("deviceState", "not app-readable; see tools/pose_state_poll.sh"),
        )
    }

    private fun describe(s: Sensor?): String? =
        s?.let { "${it.name} vendor=${it.vendor} minDelayUs=${it.minDelay} res=${it.resolution} range=${it.maximumRange}" }

    // ---- UI ----

    private fun buildContent() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        bigView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 44f; typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, dp(8))
        }
        detailView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        recordButton = Button(this).apply {
            setOnClickListener {
                if (PoseRecorder.isRecording) {
                    setHighRate(false)
                    PoseRecorder.stop()
                } else {
                    PoseRecorder.start(this@PoseProbeActivity)
                    PoseRecorder.highRate = true
                    logMeta(); logWindow("recordStart"); logDisplays("recordStart")
                    setHighRate(true)
                }
                render()
            }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        LABELS.forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label
                setOnClickListener {
                    lastLabel = label
                    PoseRecorder.log("label", JSONObject().put("label", label))
                    render()
                }
            }
            (if (index < 4) labels else labels2).addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff10202a.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(32))
            addView(bigView)
            addView(recordButton)
            addView(labels)
            addView(labels2)
            addView(detailView)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun render() {
        if (!::detailView.isInitialized) return
        backWhileRecording.isEnabled = PoseRecorder.isRecording
        val hingeText = if (lastHinge.isFinite()) String.format(Locale.US, "%6.1f°", lastHinge) else "   —°"
        bigView.text = "$hingeText  ${poseSnapshot.pose}"
        recordButton.text = if (PoseRecorder.isRecording) "■ STOP  (${PoseRecorder.lineCount} lines)" else "● REC"
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val config = resources.configuration
        val display = window.decorView.display
        detailView.text = buildString {
            appendLine("FOLD DUO POSE PROBE  •  debug only")
            appendLine("file: ${PoseRecorder.currentFile?.name ?: "(not recording)"}")
            appendLine("label: $lastLabel")
            appendLine("pose: ${poseSnapshot.pose}  panel=${poseSnapshot.panel}  since=${poseSnapshot.msSinceTransition.takeIf { it != Long.MAX_VALUE }?.let { "${it}ms" } ?: "-"}  still=${poseSnapshot.stillMs}ms")
            appendLine()
            appendLine("gravity: " + lastGravity.joinToString(" ") { String.format(Locale.US, "%5.2f", it) })
            appendLine(String.format(Locale.US, "high-rate: %s  |B|u=%6.1f uT  |w|=%5.2f rad/s", if (highRateRegistered) "on" else "off", lastMagNorm, lastGyroNorm))
            appendLine("folding: $lastFolding")
            appendLine("window: ${bounds.toShortString()}  ${config.screenWidthDp}x${config.screenHeightDp}dp  display=${display?.displayId} ${display?.mode?.let { panelFor(it.physicalWidth, it.physicalHeight) }}")
            appendLine()
            for (d in displayManager.displays.sortedBy { it.displayId }) {
                val m = d.mode
                appendLine("display ${d.displayId}: ${displayState(d.state)} ${m.physicalWidth}x${m.physicalHeight} ${panelFor(m.physicalWidth, m.physicalHeight)} rot=${d.rotation}")
            }
            appendLine()
            appendLine("hinge sensor: ${hinge?.name ?: "none"}  gravity: ${gravity?.name ?: "none"}")
            appendLine("system device state: via adb tools/pose_state_poll.sh")
        }
    }

    private fun displayState(state: Int) = when (state) {
        Display.STATE_OFF -> "OFF"; Display.STATE_ON -> "ON"; Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"; Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "UNKNOWN($state)"
    }

    companion object {
        private const val GRAVITY_LOG_INTERVAL_NS = 50_000_000L // 20 Hz
        private val LABELS = listOf("Closed", "Open", "Tent", "Table", "InMotion", "Flip", "Stand", "Mark")
    }
}

/** Process-level JSONL writer so a recording survives activity recreation. */
object PoseRecorder {
    private const val TAG = "PoseProbe"
    private var writer: FileWriter? = null
    var currentFile: File? = null
        private set
    var lineCount = 0L
        private set
    /** Whether the FASTEST-rate spike channels (mag_u/mag/gyro/accel) should be on while recording. */
    @Volatile var highRate = true
    val isRecording: Boolean get() = writer != null

    @Synchronized
    fun start(context: Context) {
        if (writer != null) return
        val dir = File(context.getExternalFilesDir(null), "pose").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$stamp.jsonl")
        writer = FileWriter(file, true)
        currentFile = file
        lineCount = 0
        Log.i(TAG, "recording to $file")
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        Log.i(TAG, "stopped after $lineCount lines: $currentFile")
    }

    @Synchronized
    fun log(type: String, fields: JSONObject) {
        val w = writer ?: return
        fields.put("type", type)
        fields.put("tMs", SystemClock.elapsedRealtime())
        fields.put("wallMs", System.currentTimeMillis())
        runCatching {
            w.write(fields.toString())
            w.write("\n")
            lineCount++
            if (lineCount % 200 == 0L) w.flush()
        }.onFailure { Log.w(TAG, "write failed: $it") }
    }
}
