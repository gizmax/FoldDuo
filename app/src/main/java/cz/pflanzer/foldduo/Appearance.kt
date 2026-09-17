package cz.pflanzer.foldduo

import android.content.Context
import androidx.compose.runtime.*
import java.time.*
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.pflanzer.foldduo.systemfrost.SystemFrost

enum class AppearanceMode { LIGHT, DARK, SYSTEM, SUNRISE_SUNSET }

/**
 * B48 "Pant jako ovladač": what a recognized hinge squeeze does (see [HingeSqueezeOverlay]/
 * `pose/HingeSqueeze.kt`). [AppLibrary] is the default — a self-contained overlay this app fully
 * owns; [NowBrief] launches Samsung's Now Brief if it resolves, else falls back to Spotlight (its
 * package/component is unverified on a real device, same caveat as every other Samsung-specific
 * integration in this codebase).
 */
enum class HingeSqueezeAction { Off, AppLibrary, NowBrief }
data class AppearanceState(val mode: AppearanceMode = AppearanceMode.LIGHT, val place: String = "",
    val latitude: Double? = null, val longitude: Double? = null, val locationTime: Long = 0,
    val deviceLocation: Boolean = false, val dark: Boolean = false, val fallback: String? = null,
    val locationStatus: String? = null,
    /**
     * "Frost on motion (experimental)": let the gyroscope's hinge-axis rotation trigger the
     * cover frost while opening and the closing frost while folding (UnfoldMorph.kt). Off by
     * default: with one IMU, turning the closed phone in the hand is indistinguishable from
     * opening it (measured 2026-09-15), so only the hinge step triggers the frosts otherwise.
     */
    val motionFrost: Boolean = false,
    /**
     * "Frost over other apps" (Czech: "Mlha i nad aplikacemi"): the same Continuum frost, played
     * by the accessibility service over whatever app is in front (systemfrost/SystemFrost.kt),
     * not only inside this launcher. On by default; needs the accessibility service enabled to
     * do anything (SystemFrost reads this key straight out of `SharedPreferences`, so it takes
     * effect without restarting the service).
     */
    val systemFrost: Boolean = true,
    /**
     * "Inner display from ~35° (experimental)" (STATUS.md/IDEAS.md B14): request the hidden
     * `DeviceStateManager` OPENED state while opening, so the inner panel lights up at the
     * hinge-step-90 trigger (~35-50°) instead of One UI's own ~91° switch
     * (systemfrost/OpenedOverridePlan.kt, systemfrost/DeviceStateOverride.kt). Off by default:
     * whether Samsung's hidden-API policy even allows a third-party app through is unverified on
     * a real device.
     */
    val openedOverride: Boolean = false,
    /**
     * B16, "Haptic at flat": a single `EFFECT_TICK` when the left half's unfold morph reaches
     * Flat (MorphController.settleGeneration in UnfoldMorph.kt), at most once per unfold. On by
     * default.
     */
    val hapticAtFlat: Boolean = true,
    /**
     * B20, "Náhled morphu v nastavení": the "Frost intensity" / "Tilt" sliders' factors
     * (0.5..1.5, 1.0 = unchanged), read by both the launcher's own frost layers
     * (LauncherScreen.kt, through [MorphPreviewTuning.buildConfig]) and the system-wide overlay
     * ([cz.pflanzer.foldduo.systemfrost.SystemFrost], straight out of the same `SharedPreferences`
     * key so a change while the accessibility service is running takes effect without a restart,
     * same pattern as [systemFrost]).
     */
    val morphFrostFactor: Float = MorphPreviewTuning.DEFAULT_FACTOR,
    val morphTiltFactor: Float = MorphPreviewTuning.DEFAULT_FACTOR,
    /** B48 "Pant jako ovladač": "Hinge squeeze" (Continuum settings). App Library by default. */
    val hingeSqueezeAction: HingeSqueezeAction = HingeSqueezeAction.Off) // 17. 9.: off until the detector is tuned on real squeezes

class AppearanceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private fun currentSystemDark() = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    var state by mutableStateOf(load(currentSystemDark())); private set
    init { DuoAppearanceRuntime.dark = state.dark }
    private fun load(systemDark: Boolean): AppearanceState {
        val mode = runCatching { AppearanceMode.valueOf(prefs.getString("mode", "LIGHT")!!) }.getOrDefault(AppearanceMode.LIGHT)
        val lat = runCatching { prefs.getString("lat", null)?.toDoubleOrNull() }.getOrNull()
        val lon = runCatching { prefs.getString("lon", null)?.toDoubleOrNull() }.getOrNull()
        return resolve(AppearanceState(mode, runCatching { prefs.getString("place", "") ?: "" }.getOrDefault(""), lat, lon,
            runCatching { prefs.getLong("locationTime", 0) }.getOrDefault(0),
            runCatching { prefs.getBoolean("deviceLocation", false) }.getOrDefault(false),
            motionFrost = runCatching { prefs.getBoolean("motionFrost", false) }.getOrDefault(false),
            systemFrost = runCatching { prefs.getBoolean(SystemFrost.KEY_ENABLED, true) }.getOrDefault(true),
            openedOverride = runCatching { prefs.getBoolean(SystemFrost.KEY_OPENED_OVERRIDE, false) }.getOrDefault(false),
            hapticAtFlat = runCatching { prefs.getBoolean("hapticAtFlat", true) }.getOrDefault(true),
            morphFrostFactor = runCatching { prefs.getFloat(SystemFrost.KEY_MORPH_FROST_FACTOR, MorphPreviewTuning.DEFAULT_FACTOR) }.getOrDefault(MorphPreviewTuning.DEFAULT_FACTOR),
            morphTiltFactor = runCatching { prefs.getFloat(SystemFrost.KEY_MORPH_TILT_FACTOR, MorphPreviewTuning.DEFAULT_FACTOR) }.getOrDefault(MorphPreviewTuning.DEFAULT_FACTOR),
            hingeSqueezeAction = runCatching { prefs.getString("hingeSqueezeAction", null)?.let { HingeSqueezeAction.valueOf(it) } }
                .getOrNull() ?: HingeSqueezeAction.Off), systemDark)
    }
    fun setMotionFrost(value: Boolean, systemDark: Boolean) { save(state.copy(motionFrost = value), systemDark) }
    fun setSystemFrost(value: Boolean, systemDark: Boolean) { save(state.copy(systemFrost = value), systemDark) }
    fun setOpenedOverride(value: Boolean, systemDark: Boolean) { save(state.copy(openedOverride = value), systemDark) }
    fun setHapticAtFlat(value: Boolean, systemDark: Boolean) { save(state.copy(hapticAtFlat = value), systemDark) }
    fun setMorphFrostFactor(value: Float, systemDark: Boolean) { save(state.copy(morphFrostFactor = MorphPreviewTuning.clampFactor(value)), systemDark) }
    fun setMorphTiltFactor(value: Float, systemDark: Boolean) { save(state.copy(morphTiltFactor = MorphPreviewTuning.clampFactor(value)), systemDark) }
    fun resetMorphPreview(systemDark: Boolean) { save(state.copy(morphFrostFactor = MorphPreviewTuning.DEFAULT_FACTOR, morphTiltFactor = MorphPreviewTuning.DEFAULT_FACTOR), systemDark) }
    fun setHingeSqueezeAction(value: HingeSqueezeAction, systemDark: Boolean) { save(state.copy(hingeSqueezeAction = value), systemDark) }
    fun setMode(mode: AppearanceMode, systemDark: Boolean) { save(state.copy(mode = mode), systemDark) }
    fun setManual(place: String, latitude: Double, longitude: Double, systemDark: Boolean) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        save(state.copy(place = place.trim(), latitude = latitude, longitude = longitude,
            locationTime = System.currentTimeMillis(), deviceLocation = false), systemDark)
    }
    fun setDeviceLocation(latitude: Double, longitude: Double, systemDark: Boolean) = save(state.copy(
        place = "Approximate device location", latitude = latitude, longitude = longitude,
        locationTime = System.currentTimeMillis(), deviceLocation = true, locationStatus = null), systemDark)
    fun locationStatus(message: String?) { state = state.copy(locationStatus = message) }
    fun clearLocation(systemDark: Boolean) = save(state.copy(place = "", latitude = null, longitude = null,
        locationTime = 0, deviceLocation = false), systemDark)
    fun refresh(systemDark: Boolean) { state = resolve(state, systemDark); DuoAppearanceRuntime.dark = state.dark }
    fun reloadFromPreferences(systemDark: Boolean = currentSystemDark()) {
        val transientStatus = state.locationStatus
        state = load(systemDark).copy(locationStatus = transientStatus)
        DuoAppearanceRuntime.dark = state.dark
    }
    private fun save(value: AppearanceState, systemDark: Boolean) {
        prefs.edit().putString("mode", value.mode.name).putString("place", value.place)
            .putString("lat", value.latitude?.toString()).putString("lon", value.longitude?.toString())
            .putLong("locationTime", value.locationTime).putBoolean("deviceLocation", value.deviceLocation)
            .putBoolean("motionFrost", value.motionFrost)
            .putBoolean(SystemFrost.KEY_ENABLED, value.systemFrost)
            .putBoolean(SystemFrost.KEY_OPENED_OVERRIDE, value.openedOverride)
            .putBoolean("hapticAtFlat", value.hapticAtFlat)
            .putFloat(SystemFrost.KEY_MORPH_FROST_FACTOR, value.morphFrostFactor)
            .putFloat(SystemFrost.KEY_MORPH_TILT_FACTOR, value.morphTiltFactor)
            .putString("hingeSqueezeAction", value.hingeSqueezeAction.name).apply()
        state = resolve(value, systemDark)
        DuoAppearanceRuntime.dark = state.dark
    }
    private fun resolve(value: AppearanceState, systemDark: Boolean): AppearanceState = when (value.mode) {
        AppearanceMode.LIGHT -> value.copy(dark = false, fallback = null)
        AppearanceMode.DARK -> value.copy(dark = true, fallback = null)
        AppearanceMode.SYSTEM -> value.copy(dark = systemDark, fallback = null)
        AppearanceMode.SUNRISE_SUNSET -> {
            val lat = value.latitude; val lon = value.longitude
            if (lat == null || lon == null) value.copy(dark = systemDark, fallback = "Using system theme until a location is set")
            else if (value.deviceLocation && System.currentTimeMillis() - value.locationTime > 30L * 24 * 60 * 60 * 1000)
                value.copy(dark = systemDark, fallback = "Using system theme because the device location is stale")
            else runCatching { val now = ZonedDateTime.now(); value.copy(dark = solarSchedule(now.toLocalDate(), lat, lon, now.zone).isDark(now), fallback = null) }
                .getOrElse { value.copy(dark = systemDark, fallback = "Using system theme because this location is unavailable") }
        }
    }
}

object DuoAppearanceRuntime { @Volatile var dark: Boolean = false }

/** B43: `@Immutable` — four `Color`s and a `Boolean`, read via [LocalDuoPalette] across the whole
 * tree (including `paneInkColor()`'s callers on the morph's own panes), so Compose can skip
 * recomposing a reader whose only changed parameter turns out equal to the one it already had. */
@Immutable
data class DuoPalette(val ink: androidx.compose.ui.graphics.Color, val glass: androidx.compose.ui.graphics.Color,
    val backgroundTop: androidx.compose.ui.graphics.Color, val backgroundBottom: androidx.compose.ui.graphics.Color, val dark: Boolean)
val LightDuoPalette = DuoPalette(androidx.compose.ui.graphics.Color(0xFF243A46), androidx.compose.ui.graphics.Color(0xFFE8EFF2),
    androidx.compose.ui.graphics.Color(0xFF41687E), androidx.compose.ui.graphics.Color(0xFFD8CEB6), false)
val DarkDuoPalette = DuoPalette(androidx.compose.ui.graphics.Color(0xFFEAF3F6), androidx.compose.ui.graphics.Color(0xFF263A43),
    androidx.compose.ui.graphics.Color(0xFF132832), androidx.compose.ui.graphics.Color(0xFF463F35), true)
val LocalDuoPalette = staticCompositionLocalOf { LightDuoPalette }

@Composable
fun rememberSavedAppearance(): AppearanceState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(context) { AppearanceStore(context.applicationContext) }
    DisposableEffect(context, store, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) { store.reloadFromPreferences() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }
        var registered = false
        fun register() { if (!registered) {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true; store.reloadFromPreferences()
        } }
        fun unregister() { if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> register()
            Lifecycle.Event.ON_STOP -> unregister()
            else -> Unit
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); unregister() }
    }
    return store.state
}
