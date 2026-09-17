package cz.pflanzer.foldduo

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import cz.pflanzer.foldduo.continuum.EdgePulse
import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldLine
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.continuum.SheetBend
import cz.pflanzer.foldduo.notifications.NotificationPulse
import cz.pflanzer.foldduo.notifications.pulsePreferences
import cz.pflanzer.foldduo.notifications.readPulseFromPreferences
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.Panels
import cz.pflanzer.foldduo.pose.ParallaxModel
import cz.pflanzer.foldduo.pose.panel
import cz.pflanzer.foldduo.standby.StandBySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * One wallpaper, pane identity (PLAN.md fact 1, IDEAS.md B2): the picture is framed by
 * WallpaperFraming.kt, so the cover shows the right half of what the inner panel shows and the
 * left pane reveals the rest on unfold. The same drawing serves the Compose launcher
 * background ([DuneWallpaper]) and the live wallpaper ([DuneWallpaperService]).
 */

/**
 * The panel this window is on: the physical panel behind display 0 (pose/Panels.kt, Samsung
 * swaps it under the same display id), else by the window width when the display cannot be read.
 */
@Composable
internal fun currentPanel(): Panel {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        val physical = runCatching { context.display?.panel() }.getOrNull() ?: Panel.Unknown
        if (physical != Panel.Unknown) physical
        else if (configuration.screenWidthDp >= FOLD_THRESHOLD_DP) Panel.Inner else Panel.Cover
    }
}

@Composable
internal fun DuneWallpaper(modifier: Modifier = Modifier.fillMaxSize(), panel: Panel = currentPanel()) {
    trackRecomposition("DuneWallpaper")
    val palette = LocalDuoPalette.current
    val photo = rememberLauncherBackgroundPhoto()
    Canvas(modifier.then(rememberEdgePulse(panel)).then(rememberSheetBend(panel)).then(rememberWallpaperParallax(panel))) {
        drawLauncherBackground(photo, palette.dark, panel)
    }
}

/** Výkon 3 "kreslení na inneru": attach/detach log tag for the Compose wallpaper chain's own
 * bend/pulse `graphicsLayer`s — the same "FoldDuoPerf" tag every other perf log in this app uses. */
private const val WALLPAPER_PERF_TAG = "FoldDuoPerf"

private const val WALLPAPER_DEPTH_PREFS = "appearance"
private const val WALLPAPER_DEPTH_KEY = "wallpaperDepth"
/** Extra scale-down at full morph tilt (IDEAS.md B27: the wallpaper "backs off" while opening/closing). */
private const val MORPH_BACKOFF_SCALE = 0.985f
private const val SHEET_BEND_KEY = "sheetBend"
private const val WALLPAPER_PULSE_KEY = "wallpaperPulses"

/** "Wallpaper depth" (Appearance settings, IDEAS.md B27): on by default, same `appearance` prefs file as the other toggles. */
internal fun wallpaperDepthEnabled(context: Context): Boolean =
    context.getSharedPreferences(WALLPAPER_DEPTH_PREFS, Context.MODE_PRIVATE).getBoolean(WALLPAPER_DEPTH_KEY, true)

internal fun setWallpaperDepthEnabled(context: Context, value: Boolean) {
    context.getSharedPreferences(WALLPAPER_DEPTH_PREFS, Context.MODE_PRIVATE).edit().putBoolean(WALLPAPER_DEPTH_KEY, value).apply()
}

/** "Wallpaper bends at the hinge" (Appearance settings, IDEAS.md B37): on by default, same `appearance` prefs file. */
internal fun sheetBendEnabled(context: Context): Boolean =
    context.getSharedPreferences(WALLPAPER_DEPTH_PREFS, Context.MODE_PRIVATE).getBoolean(SHEET_BEND_KEY, true)

internal fun setSheetBendEnabled(context: Context, value: Boolean) {
    context.getSharedPreferences(WALLPAPER_DEPTH_PREFS, Context.MODE_PRIVATE).edit().putBoolean(SHEET_BEND_KEY, value).apply()
}

/** "Wallpaper pulses with notifications" (Colours & glass settings, IDEAS.md B50): on by default, same `appearance` prefs file as the other toggles. */
internal fun wallpaperPulsesEnabled(context: Context): Boolean =
    context.getSharedPreferences(WALLPAPER_DEPTH_PREFS, Context.MODE_PRIVATE).getBoolean(WALLPAPER_PULSE_KEY, true)

internal fun setWallpaperPulsesEnabled(context: Context, value: Boolean) {
    context.getSharedPreferences(WALLPAPER_DEPTH_PREFS, Context.MODE_PRIVATE).edit().putBoolean(WALLPAPER_PULSE_KEY, value).apply()
}

/** The system's "Remove animations" (Settings > Accessibility, `ANIMATOR_DURATION_SCALE == 0`): parallax off then. */
internal fun systemReduceMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f) == 0f

private fun parallaxMaxOffsetDp(panel: Panel): Float =
    if (panel == Panel.Cover) ParallaxModel.MAX_OFFSET_COVER_DP else ParallaxModel.MAX_OFFSET_INNER_DP

/**
 * Wallpaper depth (IDEAS.md B27): a `graphicsLayer` that translates and slightly over-scales the
 * wallpaper only — icons and widgets are composed outside this modifier — from the low-passed
 * gravity vector ([ParallaxModel]), plus the unfold/close morph's back-off while it runs
 * ([MorphController.angleTilt], read-only here). One collector per composable ([LaunchedEffect])
 * writes a single [Offset] state that the `graphicsLayer` lambda reads, so a moving phone
 * invalidates only this layer, never the rest of the composition. Off (no translation, but still
 * over-scaled to a no-op 1x) when "Wallpaper depth" is off or the system's reduced-motion is on.
 * The [PoseEngine] is only *read* here, never acquired: [MainActivity] already holds it (started)
 * for the unfold morph while the launcher is resumed.
 */
@Composable
private fun rememberWallpaperParallax(panel: Panel): Modifier {
    val context = LocalContext.current
    val morph = LocalMorphController.current
    val maxOffsetDp = parallaxMaxOffsetDp(panel)
    val model = remember(maxOffsetDp) { ParallaxModel(maxOffsetDp) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val widthPx = remember { mutableIntStateOf(0) }
    val reduceMotion = remember(context) { systemReduceMotionEnabled(context) }
    LaunchedEffect(context, model, reduceMotion) {
        if (reduceMotion) { model.reset(); offset.value = Offset.Zero; return@LaunchedEffect }
        val pose = PoseEngine.get(context)
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (wallpaperDepthEnabled(context)) {
                    val g = pose.snapshot.value.gravity
                    model.update(frameNanos / 1_000_000L, g[0], g[1])
                    offset.value = Offset(model.offsetDpX, model.offsetDpY)
                } else if (offset.value != Offset.Zero) {
                    model.reset()
                    offset.value = Offset.Zero
                }
            }
        }
    }
    val density = LocalDensity.current
    return Modifier
        .onSizeChanged { widthPx.intValue = it.width }
        .graphicsLayer {
            val tilt = morph?.angleTilt?.value ?: 0f
            val backOff = 1f - (1f - MORPH_BACKOFF_SCALE) * (tilt / MorphCurve.MAX_TILT).coerceIn(0f, 1f)
            val widthDp = widthPx.intValue / density.density
            val scale = ParallaxModel.overscale(maxOffsetDp, widthDp) * backOff
            scaleX = scale
            scaleY = scale
            translationX = offset.value.x * density.density
            translationY = offset.value.y * density.density
        }
}

/** Výkon 3 "kreslení na inneru" (17. 9. noc): [b] the current bend/pulse-progress input decides
 * whether [rememberSheetBend]/[rememberEdgePulse] need a `graphicsLayer` at all this frame — the
 * flat/idle case (bend under [SheetBend.FLAT_EPSILON]) is by far the common one (the inner panel
 * sits open/flat for most of a session), so most of the time neither wallpaper effect should cost
 * even the extra `RenderNode` a `renderEffect = null` layer still is, not just skip rebuilding the
 * `RenderEffect` object. */
internal fun sheetBendLayerNeeded(bendDeg: Float): Boolean = abs(bendDeg) >= SheetBend.FLAT_EPSILON

/** See [sheetBendLayerNeeded]; the pulse's own "idle" is progress reaching 1 (fully played out). */
internal fun edgePulseLayerNeeded(progress: Float): Boolean = progress < 1f

/**
 * B37, "Tapeta jako ohýbaný list": a `graphicsLayer` that applies [SheetBend]'s AGSL as a
 * `RenderEffect`, wrapping [rememberWallpaperParallax]'s layer so the effect reads the
 * already-parallaxed picture (parallax translation before the bend, per the task). Off (no
 * effect at all) on the cover, with "Wallpaper bends at the hinge" off, or under reduced motion.
 * Reads [PoseEngine] without acquiring it, same as [rememberWallpaperParallax]: [MainActivity]
 * already keeps it running for the unfold morph while the launcher is resumed.
 *
 * Výkon 3: the `graphicsLayer` itself (not just its `renderEffect`) is only attached while
 * [sheetBendLayerNeeded] — a [derivedStateOf] over the live [bend] state, so the ~120 Hz hinge
 * samples keep flowing into the decision (same "raw signal in, rare boolean out" shape as
 * [cz.pflanzer.foldduo.hingeUnfoldLayerNeeded]) without recomposing this composable on every one
 * of them, only the handful of times bend actually crosses the flat threshold. A one-line
 * `FoldDuoPerf` log fires exactly on those crossings (attach/detach), so a logcat capture during a
 * fold/unfold shows the layer's lifetime directly instead of having to infer it from jank alone.
 */
@Composable
private fun rememberSheetBend(panel: Panel): Modifier {
    if (panel != Panel.Inner) return Modifier
    val context = LocalContext.current
    val shader = remember(context) { SheetBend.shared(context) } ?: return Modifier
    val pxPerMm = remember(context) { FoldShader.pxPerMm(context) }
    val eyeDistancePx = remember(pxPerMm) { FoldConfig().eyeDistanceMm * pxPerMm }
    val reduceMotion = remember(context) { systemReduceMotionEnabled(context) }
    val bend = remember { mutableStateOf(0f) }
    LaunchedEffect(context, reduceMotion) {
        if (reduceMotion) { bend.value = 0f; return@LaunchedEffect }
        val pose = PoseEngine.get(context)
        val snap = SheetSnapSpring()
        var previousBend = 0f
        var lastFrameNanos = -1L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dtMs = if (lastFrameNanos < 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000f
                lastFrameNanos = frameNanos
                val angle = pose.snapshot.value.hingeAngleDeg
                val currentBend = if (sheetBendEnabled(context)) SheetBendMotion.bendForHinge(angle) else 0f
                if (MorphCurve.settleAngleEdge(Panel.Inner, previousBend, currentBend, angle)) snap.trigger()
                previousBend = currentBend
                snap.advance(dtMs)
                bend.value = currentBend + snap.overshootDeg
            }
        }
    }
    val needsLayer by remember { derivedStateOf { sheetBendLayerNeeded(bend.value) } }
    LaunchedEffect(needsLayer) {
        Log.i(WALLPAPER_PERF_TAG, "wallpaper effect ${if (needsLayer) "attach" else "detach"}: bend")
    }
    if (!needsLayer) return Modifier
    return Modifier.graphicsLayer {
        val w = size.width
        val h = size.height
        val b = bend.value
        if (!sheetBendLayerNeeded(b) || w <= 1f || h <= 1f) {
            renderEffect = null
            return@graphicsLayer
        }
        SheetBend.setUniforms(shader, w, h, hingeX = w * 0.5f, bendDeg = b, eyeDistancePx = eyeDistancePx)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * B50 "Tapeta dýchá s oznámením": a `graphicsLayer` that chains [EdgePulse]'s AGSL on top of
 * whatever [rememberSheetBend] already produced (this modifier sits outside it in
 * [DuneWallpaper]'s chain, so it reads the already-bent picture), reading
 * [NotificationPulse.pulse] directly — the launcher runs in the same process as
 * [cz.pflanzer.foldduo.island.IslandNotificationListener]. Unlike [rememberSheetBend] this runs on
 * both panels (the task: "na coveru i inneru"). The gates ("Wallpaper pulses with notifications"
 * off, reduced motion, StandBy showing) are re-read every frame while a pulse is playing, not just
 * once when it starts, so flipping one mid-pulse ends the glow on the very next frame instead of
 * waiting for the next notification.
 */
@Composable
private fun rememberEdgePulse(panel: Panel): Modifier {
    val context = LocalContext.current
    val shader = remember(context) { EdgePulse.shared(context) } ?: return Modifier
    val pulse by NotificationPulse.pulse.collectAsState()
    val progress = remember { mutableStateOf(1f) }
    val colorArgb = remember { mutableStateOf(WallpaperPalette.FALLBACK_ACCENT) }
    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        colorArgb.value = p.colorArgb
        while (isActive) {
            val gated = !wallpaperPulsesEnabled(context) || systemReduceMotionEnabled(context) || StandBySession.showing.value
            val current = if (gated) 1f else PulseMotion.progressAt(p.atMs, System.currentTimeMillis())
            progress.value = current
            if (current >= 1f) break
            withFrameNanos { }
        }
    }
    // Výkon 3: same "no layer when idle" treatment as rememberSheetBend above — a pulse plays for
    // under a second and this panel spends the rest of its life at progress == 1 (no active
    // notification), so the common case should carry no layer at all, not a null-effect one.
    val needsLayer by remember { derivedStateOf { edgePulseLayerNeeded(progress.value) } }
    LaunchedEffect(needsLayer) {
        Log.i(WALLPAPER_PERF_TAG, "wallpaper effect ${if (needsLayer) "attach" else "detach"}: pulse")
    }
    if (!needsLayer) return Modifier
    return Modifier.graphicsLayer {
        val w = size.width
        val h = size.height
        val prog = progress.value
        if (!edgePulseLayerNeeded(prog) || w <= 1f || h <= 1f) {
            renderEffect = null
            return@graphicsLayer
        }
        EdgePulse.setUniforms(shader, w, h, colorArgb.value, prog)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * The user's launcher background photo, cached copy first and then the decoded one; null while
 * there is none (the dunes are drawn instead). Every drawing of the background in the
 * composition reads the same state so the copies match pixel for pixel.
 */
@Composable
internal fun rememberLauncherBackgroundPhoto(): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val revision = LauncherBackgroundCache.revision.intValue
    val initial = remember(revision) { LauncherBackgroundCache.bitmap?.takeUnless { it.isRecycled } }
    val photo = produceState(initialValue = initial, key1 = context, key2 = revision) {
        value = withContext(Dispatchers.IO) { loadLauncherBackground(context) }
    }.value
    return remember(photo) { photo?.asImageBitmap() }
}

/**
 * The launcher background on a surface of [panel]: the user photo's pane-identity crop, else the
 * dunes drawn in the inner panel's space and cropped the same way. [Panel.Unknown] is a plain
 * centre crop of this surface. [surface] is the surface the picture is framed for; it defaults
 * to this scope's size, and a caller that draws a window-sized picture into a smaller, offset
 * scope (a copy of the background under one pane) passes the window size and translates.
 */
internal fun DrawScope.drawLauncherBackground(photo: ImageBitmap?, dark: Boolean = false, panel: Panel = Panel.Unknown, surface: Size = size) {
    val destinationWidth = surface.width.toInt().coerceAtLeast(1)
    val destinationHeight = surface.height.toInt().coerceAtLeast(1)
    if (photo == null || photo.width <= 0 || photo.height <= 0) {
        drawDunes(dark, panel, surface)
        return
    }
    val crop = LauncherBackgroundCache.cropFor(photo.width, photo.height, destinationWidth, destinationHeight, panel)
    val sourceLeft = crop.left.roundToInt().coerceIn(0, photo.width - 1)
    val sourceTop = crop.top.roundToInt().coerceIn(0, photo.height - 1)
    val sourceWidth = crop.width.roundToInt().coerceIn(1, photo.width - sourceLeft)
    val sourceHeight = crop.height.roundToInt().coerceIn(1, photo.height - sourceTop)
    drawImage(
        image = photo,
        srcOffset = IntOffset(sourceLeft, sourceTop),
        srcSize = IntSize(sourceWidth, sourceHeight),
        dstSize = IntSize(destinationWidth, destinationHeight),
    )
}

/**
 * The Duo dunes. On the cover the scene is laid out for the inner panel's own measured size
 * ([DeviceProfileHolder], the live device's profile — the Fold 8's is 2448×1848, its home
 * orientation) and this surface shows its right half, so the dune crests continue across the
 * seam when the phone opens; on the inner panel (or an unknown one) the scene fills the surface.
 */
internal fun DrawScope.drawDunes(dark: Boolean = false, panel: Panel = Panel.Unknown, surface: Size = size) {
    val w = surface.width
    val h = surface.height
    if (panel != Panel.Cover || w < 1f || h < 1f) {
        drawDuneScene(w, h, dark)
        return
    }
    val innerSpec = DeviceProfileHolder.current.inner
    val sceneW = innerSpec.widthPx.toFloat()
    val sceneH = innerSpec.heightPx.toFloat()
    val crop = LauncherBackgroundCache.cropFor(sceneW.toInt(), sceneH.toInt(), w.toInt(), h.toInt(), panel)
    withTransform({
        scale(w / crop.width, h / crop.height, Offset.Zero)
        translate(-crop.left, -crop.top)
    }) { drawDuneScene(sceneW, sceneH, dark) }
}

private fun DrawScope.drawDuneScene(w: Float, h: Float, dark: Boolean) {
    drawRect(Brush.verticalGradient(if (dark) listOf(Color(0xFF132832), Color(0xFF263E49), Color(0xFF463F35))
        else listOf(Color(0xFF41687E), Color(0xFF94ADB5), Color(0xFFD8CEB6))), size = Size(w, h))
    fun dune(y: Float, crest: Float, color: Color) {
        val path = Path().apply {
            moveTo(0f, h * y)
            cubicTo(w * .3f, h * (y - crest), w * .6f, h * (y + crest), w, h * (y - crest * .35f))
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(path, color)
    }
    dune(.57f, .17f, if (dark) Color(0xFF5B5040) else Color(0xFFC9B38E))
    dune(.72f, .12f, if (dark) Color(0xFF453D32) else Color(0xFFA49373))
    dune(.85f, .19f, if (dark) Color(0xFF302C26) else Color(0xFF84775F))
    for (n in 0..28) {
        val y = h * (.84f + n * .011f)
        val path = Path().apply {
            moveTo(0f, y)
            cubicTo(w * .35f, y - h * .17f, w * .65f, y + h * .05f, w, y - h * .06f)
        }
        drawPath(path, Color.White.copy(alpha = .045f), style = Stroke(1.3f))
    }
}

/** Opens Android's live wallpaper preview on [DuneWallpaperService]; false when there is none. */
internal fun openLiveWallpaperPreview(context: Context): Boolean = try {
    context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, DuneWallpaperService::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** True when [DuneWallpaperService] is the current live wallpaper. */
internal fun isLiveWallpaperActive(context: Context): Boolean = runCatching {
    WallpaperManager.getInstance(context).wallpaperInfo?.component == ComponentName(context, DuneWallpaperService::class.java)
}.getOrDefault(false)

/**
 * The launcher background as a live wallpaper, under the lock screen and any launcher.
 *
 * Static between events: a frame is drawn on surface changes, visibility, preference and
 * time / configuration changes only. The one animation is Continuum A's frost on unfold: the
 * Fold 8 gives apps no continuous hinge angle (PLAN.md fact 4), so the surface growing from the
 * cover size to the inner size (the panel swap, `onSurfaceChanged`) starts a [MorphCurve]-timed
 * run in which the left half of the picture clears from [FoldShader.MAX_TILT] to flat through
 * the continuum shader, frame by frame from the Choreographer, and idles again at the end.
 * Wallpaper offsets are ignored: pane identity needs the picture fixed, not a parallax.
 */
class DuneWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DuneEngine()

    private companion object {
        const val TAG = "FoldDuoWallpaper"

        /**
         * Which panel the last engine drew on and when it left the screen. Should the system
         * recreate the engine on the swap instead of resizing its surface, the new one still
         * knows it follows the cover and plays the morph when that was within [MorphCurve.ARMED_TTL_MS].
         */
        @Volatile var lastPanel = Panel.Unknown
        @Volatile var lastPanelLeftMs = 0L
    }

    inner class DuneEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, Choreographer.FrameCallback {
        private val painter = CanvasDrawScope()
        private val appearance = AppearanceStore(this@DuneWallpaperService)
        private val appearancePrefs = getSharedPreferences("appearance", MODE_PRIVATE)
        private val backgroundPrefs = launcherBackgroundPreferences(this@DuneWallpaperService)
        private val loader = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var photo: ImageBitmap? = cachedLauncherBackground(this@DuneWallpaperService)?.asImageBitmap()
        private var photoLoad = 0
        private var photoLoading = false
        private var photoFailed = false
        private var visible = false
        private var timeReceiverRegistered = false

        // Framing and the morph.
        private var panel = Panel.Unknown
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        /** The picture recorded once per surface / photo / theme; the frost is a render effect on it. */
        private val scene = RenderNode("fold-duo-wallpaper")
        private var sceneValid = false
        private var shader: RuntimeShader? = null
        private var shaderTried = false
        private val foldConfig = FoldConfig()
        private val pxPerMm = FoldShader.pxPerMm(this@DuneWallpaperService)

        init {
            // Device-agnostic wallpaper framing (WallpaperFraming.kt, LauncherBackground.kt):
            // this engine runs outside the launcher Activity, so it is its own entry point for
            // detecting the live DeviceProfile (drawDunes reads DeviceProfileHolder.current for
            // the inner panel's real size instead of the Fold 8's Panels.INNER_LONG/SHORT constants).
            DeviceProfileHolder.initFrom(this@DuneWallpaperService)
        }
        private var morphing = false
        private var morphStartMs = -1L
        private var morphProgress = 1f
        private var previewPlayed = false
        /** Whether this engine's single [Choreographer.FrameCallback] is queued, driving the morph and/or [sheetSnapSpring]. */
        private var frameLoopActive = false
        private var lastFrameTimeMs = -1L
        /** Once the surface refuses a hardware canvas this engine stays on software (no frost). */
        private var hardwareFailed = false

        // Wallpaper depth (IDEAS.md B27): this engine's own gravity listener, since it runs outside
        // the activity and cannot share MainActivity's PoseEngine. SENSOR_DELAY_UI while visible only.
        private val sensorManager = this@DuneWallpaperService.getSystemService(SensorManager::class.java)
        private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        private var parallaxRegistered = false
        private var parallax = ParallaxModel(ParallaxModel.MAX_OFFSET_INNER_DP)
        private var parallaxOffsetDpX = 0f
        private var parallaxOffsetDpY = 0f
        private val parallaxListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (wallpaperDepthEnabled(this@DuneWallpaperService) && !systemReduceMotionEnabled(this@DuneWallpaperService)) {
                    parallax.update(event.timestamp / 1_000_000L, event.values[0], event.values[1])
                    parallaxOffsetDpX = parallax.offsetDpX
                    parallaxOffsetDpY = parallax.offsetDpY
                } else if (parallaxOffsetDpX != 0f || parallaxOffsetDpY != 0f) {
                    parallax.reset()
                    parallaxOffsetDpX = 0f
                    parallaxOffsetDpY = 0f
                } else return
                if (visible) render(surfaceHolder)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        private fun registerParallax() {
            val sensor = gravitySensor
            if (sensor == null || parallaxRegistered) return
            parallaxRegistered = sensorManager?.registerListener(parallaxListener, sensor, SensorManager.SENSOR_DELAY_UI) == true
        }

        private fun unregisterParallax() {
            if (!parallaxRegistered) return
            sensorManager?.unregisterListener(parallaxListener)
            parallaxRegistered = false
        }

        // B37 "Tapeta jako ohýbaný list": bend from the real hinge angle, inner panel only. Unlike
        // the parallax's own gravity sensor, the hinge angle comes from the shared, app-scoped
        // PoseEngine (magnetometer estimate + step gate); acquired only while this engine is
        // visible, same lifetime as [registerParallax]/[unregisterParallax].
        private var sheetBendShader: RuntimeShader? = null
        private var sheetBendShaderTried = false
        private var poseAcquired = false
        private var poseJob: Job? = null
        private var sheetBendDeg = 0f
        private var previousSheetBendDeg = 0f
        private val sheetSnapSpring = SheetSnapSpring()

        private fun registerSheetBend() {
            if (poseAcquired) return
            poseAcquired = true
            val pose = PoseEngine.acquire(this@DuneWallpaperService)
            poseJob = loader.launch {
                pose.snapshot.collect { snapshot ->
                    val enabled = sheetBendEnabled(this@DuneWallpaperService) &&
                        !systemReduceMotionEnabled(this@DuneWallpaperService) && panel == Panel.Inner
                    val newBend = if (enabled) SheetBendMotion.bendForHinge(snapshot.hingeAngleDeg) else 0f
                    if (MorphCurve.settleAngleEdge(Panel.Inner, previousSheetBendDeg, newBend, snapshot.hingeAngleDeg)) {
                        sheetSnapSpring.trigger()
                        ensureFrameLoop()
                    }
                    previousSheetBendDeg = newBend
                    sheetBendDeg = newBend
                    if (visible) render(surfaceHolder)
                }
            }
        }

        private fun unregisterSheetBend() {
            if (!poseAcquired) return
            poseAcquired = false
            poseJob?.cancel()
            poseJob = null
            PoseEngine.release()
            sheetBendDeg = 0f
            previousSheetBendDeg = 0f
            sheetSnapSpring.reset()
        }

        // B50 "Tapeta dýchá s oznámením": the pulse's colour and timestamp arrive over
        // SharedPreferences (NotificationPulse.kt's `writePulseToPreferences`) rather than a
        // shared StateFlow, since this engine may not share IslandNotificationListener's process
        // in the future even though it does today (see NotificationPulse.kt's header). Observed
        // only while visible, same lifetime as [registerParallax]/[registerSheetBend].
        private var pulseShader: RuntimeShader? = null
        private var pulseShaderTried = false
        private val pulsePrefsFile = pulsePreferences(this@DuneWallpaperService)
        private var pulsePrefsRegistered = false
        private var pulseAtMs = 0L
        private var pulseColorArgb = 0

        private fun registerPulseListener() {
            if (pulsePrefsRegistered) return
            pulsePrefsRegistered = true
            readPulseFromPreferences(this@DuneWallpaperService)?.let { pulseAtMs = it.atMs; pulseColorArgb = it.colorArgb }
            pulsePrefsFile.registerOnSharedPreferenceChangeListener(this)
            // Becoming visible mid-pulse (e.g. the lock screen turns on moments after a
            // notification): pick the animation back up instead of drawing one static frame.
            if (pulseProgress() < 1f) ensureFrameLoop()
        }

        private fun unregisterPulseListener() {
            if (!pulsePrefsRegistered) return
            pulsePrefsRegistered = false
            pulsePrefsFile.unregisterOnSharedPreferenceChangeListener(this)
        }

        /** [PulseMotion.progressAt] against the wall clock; `>= 1` once the last pulse has fully played out (or there never was one). */
        private fun pulseProgress(): Float = if (pulseAtMs <= 0L) 1f else PulseMotion.progressAt(pulseAtMs, System.currentTimeMillis())

        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (visible) { appearance.reloadFromPreferences(systemDark()); invalidateScene(); render(surfaceHolder) }
            }
        }
        private fun registerTimeReceiver() {
            if (timeReceiverRegistered) return
            ContextCompat.registerReceiver(this@DuneWallpaperService, timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            timeReceiverRegistered = true
        }
        private fun unregisterTimeReceiver() {
            if (!timeReceiverRegistered) return
            unregisterReceiver(timeReceiver)
            timeReceiverRegistered = false
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            appearance.reloadFromPreferences(systemDark())
            invalidateScene()
            render(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            val next = Panels.classify(width, height)
            val now = SystemClock.uptimeMillis()
            val followsCover = panel == Panel.Cover ||
                (panel == Panel.Unknown && lastPanel == Panel.Cover && now - lastPanelLeftMs <= MorphCurve.ARMED_TTL_MS)
            val unfolded = next == Panel.Inner && followsCover
            // The chooser's preview plays the morph once so the effect can be seen before setting it.
            val demo = isPreview && !previewPlayed && next == Panel.Inner
            if (next != panel) { parallax = ParallaxModel(parallaxMaxOffsetDp(next)); parallaxOffsetDpX = 0f; parallaxOffsetDpY = 0f }
            panel = next
            lastPanel = next
            invalidateScene()
            appearance.reloadFromPreferences(systemDark())
            if (unfolded || demo) {
                previewPlayed = previewPlayed || demo
                Log.i(TAG, "unfold morph: surface ${width}x$height panel=$panel preview=$isPreview")
                startMorph(holder)
            } else render(holder)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            render(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopAnimations()
            sceneValid = false
            super.onSurfaceDestroyed(holder)
        }

        /** Ignored: the system scroll offset, not IDEAS.md B27's gravity parallax (see [parallaxListener]) — pane identity needs the picture fixed to that. */
        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int) = Unit

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                photoLoad++
                photoLoading = false
                photoFailed = false
                photo = cachedLauncherBackground(this@DuneWallpaperService)?.asImageBitmap()
                appearancePrefs.registerOnSharedPreferenceChangeListener(this)
                backgroundPrefs.registerOnSharedPreferenceChangeListener(this)
                registerTimeReceiver()
                registerParallax()
                registerSheetBend()
                registerPulseListener()
                appearance.reloadFromPreferences(systemDark())
                invalidateScene()
                render(surfaceHolder)
            } else {
                lastPanelLeftMs = SystemClock.uptimeMillis()
                stopAnimations()
                unregisterParallax()
                unregisterSheetBend()
                unregisterPulseListener()
                appearancePrefs.unregisterOnSharedPreferenceChangeListener(this)
                backgroundPrefs.unregisterOnSharedPreferenceChangeListener(this)
                unregisterTimeReceiver()
            }
        }

        override fun onDestroy() {
            lastPanelLeftMs = SystemClock.uptimeMillis()
            stopAnimations()
            unregisterParallax()
            unregisterSheetBend()
            unregisterPulseListener()
            photoLoad++
            loader.cancel()
            unregisterTimeReceiver()
            appearancePrefs.unregisterOnSharedPreferenceChangeListener(this)
            backgroundPrefs.unregisterOnSharedPreferenceChangeListener(this)
            scene.discardDisplayList()
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences === pulsePrefsFile) {
                // Its own, lighter path: a pulse changes only the render effect, never the
                // recorded scene, so this skips invalidateScene()/appearance's reload below.
                readPulseFromPreferences(this@DuneWallpaperService)?.let { next ->
                    if (next.atMs != pulseAtMs) {
                        pulseAtMs = next.atMs
                        pulseColorArgb = next.colorArgb
                        ensureFrameLoop()
                        if (visible) render(surfaceHolder)
                    }
                }
                return
            }
            if (sharedPreferences === backgroundPrefs) {
                photoLoad++
                photo = cachedLauncherBackground(this@DuneWallpaperService)?.asImageBitmap()
                photoLoading = false
                photoFailed = false
            }
            if (sharedPreferences === appearancePrefs && key == WALLPAPER_DEPTH_KEY && !wallpaperDepthEnabled(this@DuneWallpaperService)) {
                parallax.reset(); parallaxOffsetDpX = 0f; parallaxOffsetDpY = 0f
            }
            if (sharedPreferences === appearancePrefs && key == SHEET_BEND_KEY && !sheetBendEnabled(this@DuneWallpaperService)) {
                sheetBendDeg = 0f; previousSheetBendDeg = 0f; sheetSnapSpring.reset()
            }
            invalidateScene()
            if (visible) { appearance.reloadFromPreferences(systemDark()); render(surfaceHolder) }
        }

        private fun systemDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        private fun invalidateScene() { sceneValid = false }

        // --- the morph -------------------------------------------------------------------

        private fun startMorph(holder: SurfaceHolder) {
            if (!shaderTried) {
                shaderTried = true
                shader = FoldShader.create(this@DuneWallpaperService)
            }
            if (shader == null || hardwareFailed) { render(holder); return }
            morphStartMs = -1L
            morphProgress = 0f
            morphing = true
            ensureFrameLoop()
        }

        /**
         * Stops the unfold-frost morph and B37's snap-flat crack together, and unregisters the
         * shared Choreographer callback that drives both — every caller of the old `stopMorph`
         * (surface destroyed, visibility lost, engine destroyed) needs both stopped, since none of
         * those moments should leave an animation ticking against an invisible or gone surface.
         */
        private fun stopAnimations() {
            if (frameLoopActive) Choreographer.getInstance().removeFrameCallback(this)
            frameLoopActive = false
            morphing = false
            morphProgress = 1f
            sheetSnapSpring.reset()
        }

        /** Posts this engine's single [Choreographer.FrameCallback] if it is not already queued. */
        private fun ensureFrameLoop() {
            if (frameLoopActive) return
            frameLoopActive = true
            lastFrameTimeMs = -1L
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            val frameMs = frameTimeNanos / 1_000_000L
            val dtMs = if (lastFrameTimeMs < 0L) 0f else (frameMs - lastFrameTimeMs).toFloat()
            lastFrameTimeMs = frameMs
            if (morphing) {
                if (morphStartMs < 0L) morphStartMs = frameMs
                morphProgress = ((frameMs - morphStartMs).toFloat() / MorphCurve.DURATION_MS).coerceIn(0f, 1f)
                if (morphProgress >= 1f) morphing = false
            }
            if (sheetSnapSpring.isRunning) sheetSnapSpring.advance(dtMs)
            render(surfaceHolder)
            if ((morphing || sheetSnapSpring.isRunning || pulseProgress() < 1f) && visible && surfaceHolder.surface.isValid) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                frameLoopActive = false
                morphProgress = 1f
            }
        }

        /** The frost for this frame: the launcher's curve, [FoldShader.MAX_TILT] -> 0 with the same easing. */
        private fun currentTilt(): Float =
            if (morphing) MorphCurve.tilt(MorphCurve.easing.transform(morphProgress)) else 0f

        // --- drawing ---------------------------------------------------------------------

        private fun render(holder: SurfaceHolder) {
            if (!holder.surface.isValid) return
            if (launcherBackgroundEnabled(this@DuneWallpaperService) && photo == null && !photoFailed) {
                if (photoLoading) return
                photoLoading = true
                val request = ++photoLoad
                loader.launch {
                    val loaded = withContext(Dispatchers.IO) { loadLauncherBackground(this@DuneWallpaperService) }
                    if (request == photoLoad) {
                        photoLoading = false
                        photo = loaded?.asImageBitmap()
                        photoFailed = loaded == null
                        invalidateScene()
                        if (visible) render(holder)
                    }
                }
                return
            }
            if (!launcherBackgroundEnabled(this@DuneWallpaperService)) { photo = null; photoFailed = false }
            val tilt = currentTilt()
            val frost = tilt >= FoldShader.FLAT_EPSILON && panel == Panel.Inner
            if (!hardwareFailed) {
                val canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) {
                    Log.w(TAG, "hardware canvas unavailable, drawing in software: ${e.message}")
                    null
                }
                if (canvas != null) {
                    try { drawFrame(canvas, tilt, frost) } finally { holder.unlockCanvasAndPost(canvas) }
                    return
                }
                hardwareFailed = true
                stopAnimations()
            }
            val canvas = try { holder.lockCanvas() } catch (_: IllegalArgumentException) { null } ?: return
            try { drawScene(canvas, canvas.width, canvas.height) } finally { holder.unlockCanvasAndPost(canvas) }
        }

        /** Hardware path: the recorded picture drawn through the frost (a runtime shader effect) or plain. */
        private fun drawFrame(canvas: android.graphics.Canvas, tilt: Float, frost: Boolean) {
            val w = canvas.width
            val h = canvas.height
            if (!sceneValid || scene.width != w || scene.height != h) {
                scene.setPosition(0, 0, w, h)
                val recording = scene.beginRecording(w, h)
                try { drawScene(recording, w, h) } finally { scene.endRecording() }
                sceneValid = true
            }
            val frostEffect = shader?.takeIf { frost }?.let { s ->
                // Hinge at the middle of the inner panel, the left half moving, eye over its centre.
                FoldShader.setUniforms(s, w.toFloat(), h.toFloat(), tilt, foldConfig, pxPerMm,
                    FoldLine(splitsX = true, position = w * 0.5f, eyePos = w * 0.25f, movingSide = -1))
                RenderEffect.createRuntimeShaderEffect(s, "content")
            }
            val bendEffect = sheetBendEffect(w, h)
            val combined = if (frostEffect != null && bendEffect != null) RenderEffect.createChainEffect(frostEffect, bendEffect)
                else frostEffect ?: bendEffect
            val pulseEffect = pulseEffect(w, h)
            val effect = if (pulseEffect != null && combined != null) RenderEffect.createChainEffect(pulseEffect, combined)
                else pulseEffect ?: combined
            scene.setRenderEffect(effect)
            // Wallpaper depth (IDEAS.md B27): translate + over-scale the recorded picture only, at
            // draw time so the scene above needs no re-recording. Zero offset (depth off, reduced
            // motion, or not yet a sample) still over-scales by the panel's margin, a no-op 1x then.
            val density = resources.displayMetrics.density
            val backOff = 1f - (1f - MORPH_BACKOFF_SCALE) * (tilt / FoldShader.MAX_TILT).coerceIn(0f, 1f)
            val scale = ParallaxModel.overscale(parallaxMaxOffsetDp(panel), w / density) * backOff
            canvas.save()
            canvas.scale(scale, scale, w / 2f, h / 2f)
            canvas.translate(parallaxOffsetDpX * density, parallaxOffsetDpY * density)
            canvas.drawRenderNode(scene)
            canvas.restore()
        }

        /** B37's current bend (base + [sheetSnapSpring] overshoot) as a `RenderEffect`, or null when flat or off the inner panel. */
        private fun sheetBendEffect(w: Int, h: Int): RenderEffect? {
            if (panel != Panel.Inner) return null
            val bend = sheetBendDeg + sheetSnapSpring.overshootDeg
            if (abs(bend) < SheetBend.FLAT_EPSILON) return null
            if (!sheetBendShaderTried) {
                sheetBendShaderTried = true
                sheetBendShader = SheetBend.create(this@DuneWallpaperService)
            }
            val s = sheetBendShader ?: return null
            SheetBend.setUniforms(s, w.toFloat(), h.toFloat(), hingeX = w * 0.5f, bendDeg = bend,
                eyeDistancePx = foldConfig.eyeDistanceMm * pxPerMm)
            return RenderEffect.createRuntimeShaderEffect(s, "content")
        }

        /**
         * B50's edge glow as a `RenderEffect`, or null once it has fully played out, off ("Wallpaper
         * pulses with notifications"), under reduced motion, or while StandBy is showing — checked
         * fresh on every frame this is called from (this engine's own [doFrame]/[render]), same as
         * the launcher's `rememberEdgePulse`. Runs on both panels, unlike [sheetBendEffect].
         */
        private fun pulseEffect(w: Int, h: Int): RenderEffect? {
            val progress = pulseProgress()
            if (progress >= 1f) return null
            if (!wallpaperPulsesEnabled(this@DuneWallpaperService) || systemReduceMotionEnabled(this@DuneWallpaperService) ||
                StandBySession.showing.value) return null
            if (!pulseShaderTried) {
                pulseShaderTried = true
                pulseShader = EdgePulse.create(this@DuneWallpaperService)
            }
            val s = pulseShader ?: return null
            EdgePulse.setUniforms(s, w.toFloat(), h.toFloat(), pulseColorArgb, progress)
            return RenderEffect.createRuntimeShaderEffect(s, "content")
        }

        private fun drawScene(canvas: android.graphics.Canvas, w: Int, h: Int) {
            painter.draw(Density(resources.displayMetrics.density), LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(canvas), Size(w.toFloat(), h.toFloat())) {
                    drawLauncherBackground(photo, appearance.state.dark, panel)
                }
        }
    }
}
