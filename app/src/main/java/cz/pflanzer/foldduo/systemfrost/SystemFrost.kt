package cz.pflanzer.foldduo.systemfrost

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import androidx.core.content.ContextCompat
import cz.pflanzer.foldduo.MorphPreviewTuning
import cz.pflanzer.foldduo.MotionPrefs
import cz.pflanzer.foldduo.OverlayPlan
import cz.pflanzer.foldduo.PoseEngine
import cz.pflanzer.foldduo.continuum.FoldLine
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.pose.HingeAngleSource
import cz.pflanzer.foldduo.pose.HingeStep
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseRepository
import cz.pflanzer.foldduo.pose.panel

/**
 * Production "Mlha nad cizími aplikacemi" (STATUS.md, 2026-09-15): frosts whatever app is in the
 * foreground the same way [cz.pflanzer.foldduo.UnfoldMorph.MorphController] frosts the launcher's
 * own Home, driven by the pure [SystemFrostPlan]. Also carries B14 (the OPENED device-state
 * override, [OpenedOverridePlan]) and B15 (the panel-swap continuity bridge,
 * [ContinuityBridgePlan]) — same accessibility service, same screenshots, so they share this
 * class instead of three independent windows into the same sensors. Lives inside
 * [cz.pflanzer.foldduo.SystemShadeAccessibilityService] — [start] from `onServiceConnected`,
 * [stop] from `onDestroy`, [onAccessibilityEvent] from `onAccessibilityEvent`.
 *
 * Idle cost (both settings on, nothing happening): one `TYPE_HINGE_ANGLE` listener
 * ([HingeAngleSource], on-change, cheap) and a `SharedPreferences` listener. Every other sensor,
 * the [PoseEngine] (which brings up gravity/gyro/magnetometer) and the poll loop only run while
 * an episode (cover frost, inner clear, inner closing frost) is in progress; the accessibility
 * event stream ([onAccessibilityEvent]) is only turned on while a continuity bridge is showing.
 */
class SystemFrost(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val displayManager = service.getSystemService(DisplayManager::class.java)
    private val keyguardManager = service.getSystemService(KeyguardManager::class.java)
    private val prefs: SharedPreferences = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val plan = SystemFrostPlan()
    private val openedOverride = OpenedOverridePlan()
    private val bridgePlan = ContinuityBridgePlan()

    private var frostSettingOn = false
    private var openedOverrideSettingOn = false
    /** B20: "Frost intensity" / "Tilt" sliders, re-read whenever their prefs key changes (see [prefsListener]). */
    private var morphFrostFactor = MorphPreviewTuning.DEFAULT_FACTOR
    private var morphTiltFactor = MorphPreviewTuning.DEFAULT_FACTOR
    /** B21: true while the system wants animations off; see [reduceMotionObserver]. Gates every dispatch in [onHingeSample], and any running episode is torn down the moment it turns on. */
    private var reduceMotionOn = false
    private var reduceMotionObserver: ContentObserver? = null
    /** B22: overlay-window frame sampling ([ChoreographerFrameSampler]) for the duration of one episode (the pose engine's own acquire/release window). */
    private val frameSampler = ChoreographerFrameSampler()
    private var hingeSource: HingeAngleSource? = null
    private var repository: PoseRepository? = null
    private var lastKnownPanel: Panel = Panel.Unknown
    private var displayWasOn = false
    private var tickRunnable: Runnable? = null
    private val overlays = mutableMapOf<OverlayTarget, OverlayEntry>()
    private val pendingBitmaps = mutableMapOf<OverlayTarget, Bitmap>()

    /** The last bitmap successfully attached for each frost target, kept alive a little past the
     * overlay's own removal so the B15 bridge on the *other* panel can reuse it (COVER's shot
     * bridges the inner's right half on opening; INNER's shot bridges the cover on closing). */
    private val lastBitmaps = mutableMapOf<OverlayTarget, Bitmap>()
    private val bridges = mutableMapOf<BridgeTarget, ImageView>()
    private val bridgeTimers = mutableMapOf<BridgeTarget, Runnable>()
    private var openedOverrideTimeoutRunnable: Runnable? = null

    /** One attached overlay window: its view, and the fold geometry fixed at attach time (the
     * window never resizes mid-episode, so the [FoldLine] and px size are computed once). */
    private class OverlayEntry(val view: ImageView, val foldLine: FoldLine, val widthPx: Float, val heightPx: Float)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_ENABLED || key == KEY_OPENED_OVERRIDE) applySettings()
        if (key == null || key == KEY_MORPH_FROST_FACTOR || key == KEY_MORPH_TILT_FACTOR) readMorphFactors()
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        readMorphFactors()
        armReduceMotionWatch()
        applySettings()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        disarmHinge()
        disarmReduceMotionWatch()
        teardownEpisode("service stopped")
        dispatch(openedOverride.cancelExternally("service destroyed"))
        BridgeTarget.entries.forEach { hideBridge(it) }
    }

    private fun readMorphFactors() {
        morphFrostFactor = prefs.getFloat(KEY_MORPH_FROST_FACTOR, MorphPreviewTuning.DEFAULT_FACTOR)
        morphTiltFactor = prefs.getFloat(KEY_MORPH_TILT_FACTOR, MorphPreviewTuning.DEFAULT_FACTOR)
    }

    /** B21: the same three settings [MotionPrefs] reads, watched here too (a plain service has no `State`/lifecycle to hang [MotionPrefs.attach] off, so this keeps its own tiny observer). */
    private fun armReduceMotionWatch() {
        reduceMotionOn = MotionPrefs.readReduceMotion(service)
        if (reduceMotionObserver != null) return
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = MotionPrefs.readReduceMotion(service)
                if (now == reduceMotionOn) return
                reduceMotionOn = now
                if (now) {
                    Log.i(TAG, "skipped: reduce motion")
                    teardownEpisode("reduce motion turned on")
                }
            }
        }
        reduceMotionObserver = observer
        val resolver = service.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        runCatching { resolver.registerContentObserver(Settings.Secure.getUriFor(MotionPrefs.KEY_ACCESSIBILITY_REDUCE_MOTION), false, observer) }
    }

    private fun disarmReduceMotionWatch() {
        reduceMotionObserver?.let { service.contentResolver.unregisterContentObserver(it) }
        reduceMotionObserver = null
    }

    /** Route from [cz.pflanzer.foldduo.SystemShadeAccessibilityService.onAccessibilityEvent]: only
     * meaningful while a B15 bridge is showing (see [setAccessibilityEventsEnabled]), and only for
     * a package that is not our own launcher (that case never shows a bridge in the first place). */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName) return
        dispatch(bridgePlan.onForegroundAppDrew())
    }

    private fun applySettings() {
        val frostOn = prefs.getBoolean(KEY_ENABLED, true)
        val overrideOn = prefs.getBoolean(KEY_OPENED_OVERRIDE, false)
        val frostChanged = frostOn != frostSettingOn
        val overrideChanged = overrideOn != openedOverrideSettingOn
        if (!frostChanged && !overrideChanged) return
        frostSettingOn = frostOn
        openedOverrideSettingOn = overrideOn
        if (frostOn || overrideOn) armHinge() else disarmHinge()
        if (frostChanged && !frostOn) teardownEpisode("setting turned off")
        if (overrideChanged && !overrideOn) dispatch(openedOverride.cancelExternally("setting turned off"))
        Log.i(TAG, "setting changed: frost=$frostOn openedOverride=$overrideOn")
    }

    // ---- idle: the cheap hinge listener only ----

    private fun armHinge() {
        if (hingeSource != null) return
        val source = HingeAngleSource(service, handler) { sample -> onHingeSample(sample.deg) }
        hingeSource = source
        source.start()
        Log.i(TAG, "armed")
    }

    private fun disarmHinge() {
        hingeSource?.stop()
        hingeSource = null
    }

    private fun onHingeSample(deg: Float) {
        if (reduceMotionOn) { Log.i(TAG, "skipped: reduce motion"); return }
        val step = HingeStep.of(deg) ?: return
        val display = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
        val panel = display.panel()
        val now = SystemClock.elapsedRealtime()
        // This always-on listener and the episode poll loop both learn the panel from
        // independent sources (a raw DisplayManager read here vs. the Pose Engine snapshot in
        // poll()) and can each see a swap first depending on timing. notePanelChange() is the
        // single place that updates lastKnownPanel and tells the plan, so whichever one notices
        // first wins and the other one's later read of the same new panel is a no-op — neither
        // silently "eats" the transition without the plan hearing about it (that race is what
        // dropped the panel-swap-to-cover on closing: see STATUS.md "Mlha nad cizími aplikacemi").
        notePanelChange(panel, now)
        val launcherForeground = isLauncherForeground()
        val keyguardLocked = runCatching { keyguardManager?.isKeyguardLocked }.getOrNull() ?: false
        dispatch(plan.onHingeStep(now, panel, step, launcherForeground, keyguardLocked))
        dispatch(openedOverride.onHingeStep(now, panel, step, keyguardLocked, openedOverrideSettingOn))
    }

    /** The only place [lastKnownPanel] is written; see the race note in [onHingeSample]. */
    private fun notePanelChange(panel: Panel, nowMs: Long) {
        if (panel == Panel.Unknown || panel == lastKnownPanel) return
        val previous = lastKnownPanel
        lastKnownPanel = panel
        dispatch(plan.onPanelChanged(nowMs, panel))
        // B15: a bridge only makes sense on an actual Cover<->Inner swap (not the first-ever
        // read from Unknown), and never over our own launcher — it already has pane identity.
        when {
            previous == Panel.Cover && panel == Panel.Inner ->
                dispatch(bridgePlan.onSwap(BridgeTarget.INNER_RIGHT, isLauncherForeground()))
            previous == Panel.Inner && panel == Panel.Cover ->
                dispatch(bridgePlan.onSwap(BridgeTarget.COVER, isLauncherForeground()))
        }
    }

    private fun isLauncherForeground(): Boolean {
        val pkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        return pkg == service.packageName
    }

    // ---- action dispatch ----

    private fun dispatch(actions: List<FrostAction>) {
        for (action in actions) when (action) {
            FrostAction.AcquirePoseEngine -> onAcquire()
            FrostAction.ReleasePoseEngine -> onReleaseEngine()
            is FrostAction.RequestScreenshot -> requestScreenshot(action.target)
            is FrostAction.AttachOverlay -> attachOverlay(action.target, action.tiltDeg)
            is FrostAction.UpdateOverlay -> updateOverlay(action.target, action.tiltDeg)
            is FrostAction.RemoveOverlay -> removeOverlay(action.target, action.reason)
            is FrostAction.Skip -> Log.i(TAG, "skipped: ${action.reason}")
            FrostAction.RequestOpenedOverride -> requestOpenedOverride()
            is FrostAction.CancelOpenedOverride -> cancelOpenedOverride(action.reason)
            is FrostAction.ShowBridge -> showBridge(action.target)
            is FrostAction.FadeOutBridge -> fadeOutBridge(action.target)
            is FrostAction.HideBridge -> hideBridge(action.target)
        }
    }

    // ---- pose engine + poll loop (episode only) ----

    private fun onAcquire() {
        if (repository != null) return
        repository = PoseEngine.acquire(service)
        displayWasOn = isDisplayOn()
        startTicking()
        frameSampler.start()
        Log.i(TAG, "PoseEngine acquired")
    }

    private fun onReleaseEngine() {
        stopTicking()
        frameSampler.stop()
        if (repository != null) {
            PoseEngine.release()
            repository = null
            Log.i(TAG, "PoseEngine released")
        }
    }

    private fun startTicking() {
        if (tickRunnable != null) return
        lateinit var runnable: Runnable
        runnable = Runnable {
            poll()
            if (tickRunnable === runnable) handler.postDelayed(runnable, TICK_MS)
        }
        tickRunnable = runnable
        handler.postDelayed(runnable, TICK_MS)
    }

    private fun stopTicking() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun poll() {
        val repo = repository ?: return
        val now = SystemClock.elapsedRealtime()
        val snapshot = repo.snapshot.value
        notePanelChange(snapshot.panel, now)
        val displayOnNow = isDisplayOn()
        if (displayOnNow && !displayWasOn) dispatch(plan.onDisplayOn(now))
        displayWasOn = displayOnNow
        dispatch(plan.onTick(now, snapshot.hingeAngleDeg))
        // B14: opportunistic — only fires while a frost episode also has the engine acquired
        // (rule (b)'s angle half); the hinge-step half of rule (b) and the Handler-based rule
        // (d) timeout cover the rest regardless of whether this tick loop is even running.
        dispatch(openedOverride.onAngleTick(snapshot.hingeAngleDeg))
    }

    private fun isDisplayOn(): Boolean =
        runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON }.getOrDefault(false)

    // ---- screenshot ----

    private fun requestScreenshot(target: OverlayTarget) {
        val requestedAt = SystemClock.uptimeMillis()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(service),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val latencyMs = SystemClock.uptimeMillis() - requestedAt
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to wrap screenshot for $target", e)
                        null
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    if (bitmap != null) {
                        Log.i(TAG, "screenshot $target captured in ${latencyMs}ms")
                        pendingBitmaps[target] = bitmap
                    } else {
                        Log.i(TAG, "skipped: screenshot $target wrap failed")
                    }
                    dispatch(plan.onScreenshotResult(SystemClock.elapsedRealtime(), target, success = bitmap != null))
                }

                override fun onFailure(errorCode: Int) {
                    val latencyMs = SystemClock.uptimeMillis() - requestedAt
                    Log.i(TAG, "skipped: screenshot $target failed after ${latencyMs}ms errorCode=$errorCode")
                    dispatch(plan.onScreenshotResult(SystemClock.elapsedRealtime(), target, success = false))
                }
            },
        )
    }

    // ---- overlay windows (shares OverlayPlan's geometry with the B13 debug spike) ----

    private fun attachOverlay(target: OverlayTarget, tiltDeg: Float) {
        val bitmap = pendingBitmaps.remove(target)
        if (bitmap == null) {
            Log.i(TAG, "skipped: no screenshot bitmap for $target")
            return
        }
        val wm = windowManager
        if (wm == null) {
            bitmap.recycle()
            Log.e(TAG, "no WindowManager on the accessibility service, cannot attach $target")
            return
        }
        val displayBounds = wm.currentWindowMetrics.bounds
        val bounds = OverlayPlan.bounds(displayBounds.width(), displayBounds.height(), leftHalf = target == OverlayTarget.INNER)
        val widthPx = bounds.widthPx.toFloat()
        val heightPx = bounds.heightPx.toFloat()
        val imageView = ImageView(service).apply {
            scaleType = ImageView.ScaleType.MATRIX
            imageMatrix = Matrix()
            setImageBitmap(bitmap)
        }
        val params = WindowManager.LayoutParams(
            bounds.widthPx,
            bounds.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.leftPx
            y = bounds.topPx
            preferredRefreshRate = 120f // B22: the episode is short-lived; the window disappears with it, so nothing needs to release this back to 0.
        }
        try {
            wm.addView(imageView, params)
            overlays[target]?.let { old -> runCatching { wm.removeView(old.view) } }
            val foldLine = SystemFrostFold.foldLineFor(target, widthPx, heightPx)
            overlays[target] = OverlayEntry(imageView, foldLine, widthPx, heightPx)
            lastBitmaps[target] = bitmap
            applyTilt(target, tiltDeg)
            Log.i(TAG, "attached $target bounds=$bounds tilt=$tiltDeg")
        } catch (e: Exception) {
            Log.e(TAG, "failed to attach $target overlay", e)
        }
    }

    private fun updateOverlay(target: OverlayTarget, tiltDeg: Float) {
        if (overlays[target] == null) return
        applyTilt(target, tiltDeg)
    }

    /**
     * Same shader the launcher plays on its own Home ([FoldShader.shared]), same uniforms
     * ([FoldShader.setUniforms] with [SystemFrostFold.config] and this overlay's own
     * [OverlayEntry.foldLine]) — so the frost over other apps skews with the same perspective
     * as `foldEffect` on Home, not just a blur. A `RuntimeShader`'s uniforms are read when its
     * `RenderEffect` is *created*, not live, so every tilt change here creates and re-applies a
     * fresh [RenderEffect] via [View.setRenderEffect] (mirrors [cz.pflanzer.foldduo.continuum.FoldEffectCache]'s
     * own comment on this). Below [FoldShader.FLAT_EPSILON] the effect is cleared instead of
     * built with an ~identity shader, matching `Modifier.foldEffect`'s own idle path.
     *
     * B14 note: this is also why the inner OPENED override (35-90°) needs no change here. Angle
     * mode's [cz.pflanzer.foldduo.MorphCurve.angleTilt] clamps [FoldShader.tiltForHinge]'s
     * progress to [0,1], so any angle at or below [FoldShader.PANEL_ON_HINGE] (90°) already
     * saturates to full tilt — the same "fully frosted until 90°, clears 90->172" curve this
     * plays today at the natural ~91° swap plays unchanged when the swap happens at ~35-50°.
     */
    private fun applyTilt(target: OverlayTarget, tiltDeg: Float) {
        val entry = overlays[target] ?: return
        if (tiltDeg.isNaN() || tiltDeg < FoldShader.FLAT_EPSILON) {
            entry.view.setRenderEffect(null)
            return
        }
        val shader = FoldShader.shared(service)
        if (shader == null) {
            entry.view.setRenderEffect(null)
            return
        }
        val pxPerMm = FoldShader.pxPerMm(service)
        // B20: "Frost intensity" scales blurSpread (read by setUniforms below); "Tilt" scales the
        // already-mapped degree value directly (setUniforms never re-reads config.intensity).
        val config = MorphPreviewTuning.buildConfig(SystemFrostFold.baseConfig, morphFrostFactor, morphTiltFactor)
        val scaledTilt = MorphPreviewTuning.scaleTilt(tiltDeg, morphTiltFactor)
        FoldShader.setUniforms(
            shader, entry.widthPx, entry.heightPx, scaledTilt,
            config, pxPerMm, entry.foldLine,
        )
        entry.view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }

    private fun removeOverlay(target: OverlayTarget, reason: String) {
        pendingBitmaps.remove(target)?.let { runCatching { it.recycle() } }
        val entry = overlays.remove(target) ?: return
        val wm = windowManager
        runCatching { wm?.removeView(entry.view) }
        Log.i(TAG, "removed $target overlay ($reason)")
    }

    private fun teardownEpisode(reason: String) {
        OverlayTarget.entries.forEach { removeOverlay(it, reason) }
        onReleaseEngine()
    }

    // ---- B14: OPENED device-state override ----

    private fun requestOpenedOverride() {
        val outcome = DeviceStateOverride.request(service)
        if (outcome.contains("FAILED")) {
            Log.i(TAG, "opened override: failed ${outcome.substringAfter("FAILED ", outcome)}")
            dispatch(openedOverride.onRequestFailed())
        } else {
            Log.i(TAG, "opened override: requested")
            cancelOverrideTimeout()
            val timeout = Runnable { dispatch(openedOverride.cancelExternally("10s safety timeout")) }
            openedOverrideTimeoutRunnable = timeout
            handler.postDelayed(timeout, OPENED_OVERRIDE_TIMEOUT_MS)
        }
    }

    private fun cancelOpenedOverride(reason: String) {
        cancelOverrideTimeout()
        DeviceStateOverride.cancel(service)
        Log.i(TAG, "opened override: cancelled ($reason)")
    }

    private fun cancelOverrideTimeout() {
        openedOverrideTimeoutRunnable?.let { handler.removeCallbacks(it) }
        openedOverrideTimeoutRunnable = null
    }

    // ---- B15: continuity bridge ----

    private fun showBridge(target: BridgeTarget) {
        val wm = windowManager
        if (wm == null) {
            Log.i(TAG, "skipped: no WindowManager, cannot show bridge $target")
            return
        }
        val sourceTarget = when (target) { BridgeTarget.INNER_RIGHT -> OverlayTarget.COVER; BridgeTarget.COVER -> OverlayTarget.INNER }
        val bitmap = lastBitmaps[sourceTarget]
        if (bitmap == null) {
            Log.i(TAG, "skipped: no retained screenshot for bridge $target")
            return
        }
        val displayBounds = wm.currentWindowMetrics.bounds
        val bounds = when (target) {
            BridgeTarget.COVER -> OverlayPlan.bounds(displayBounds.width(), displayBounds.height(), leftHalf = false)
            BridgeTarget.INNER_RIGHT -> {
                val half = displayBounds.width() / 2
                OverlayPlan.Bounds(half, 0, displayBounds.width() - half, displayBounds.height())
            }
        }
        // The source crop: the whole cover bitmap bridges the inner's right half; only the right
        // half of the (full-width) inner bitmap bridges the cover, since target == INNER's own
        // overlay bitmap already IS the full inner shot (only clipped to the left half by its
        // view's own bounds, see attachOverlay).
        val srcX = if (target == BridgeTarget.COVER) bitmap.width / 2 else 0
        val srcW = bitmap.width - srcX
        val srcH = bitmap.height
        val fit = BridgeFraming.scaleToFill(srcW.toFloat(), srcH.toFloat(), bounds.widthPx.toFloat(), bounds.heightPx.toFloat())
        val matrix = Matrix().apply {
            postTranslate(-srcX.toFloat(), 0f)
            postScale(fit.scale, fit.scale)
            postTranslate(fit.dx, fit.dy)
        }
        val imageView = ImageView(service).apply {
            scaleType = ImageView.ScaleType.MATRIX
            imageMatrix = matrix
            setImageBitmap(bitmap)
        }
        val params = WindowManager.LayoutParams(
            bounds.widthPx,
            bounds.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.leftPx
            y = bounds.topPx
            preferredRefreshRate = 120f
        }
        try {
            wm.addView(imageView, params)
            bridges[target]?.let { old -> runCatching { wm.removeView(old) } }
            bridges[target] = imageView
            setAccessibilityEventsEnabled(true)
            scheduleBridgeTimer(target, ContinuityBridgePlan.CAP_MS) { dispatch(bridgePlan.onCapElapsed(target)) }
            Log.i(TAG, "bridge shown $target bounds=$bounds source=$sourceTarget")
        } catch (e: Exception) {
            Log.e(TAG, "failed to show bridge $target", e)
        }
    }

    private fun fadeOutBridge(target: BridgeTarget) {
        val view = bridges[target] ?: return
        view.animate().alpha(0f).setDuration(ContinuityBridgePlan.FADE_MS).start()
        scheduleBridgeTimer(target, ContinuityBridgePlan.FADE_MS) { dispatch(bridgePlan.onFadeElapsed(target)) }
        Log.i(TAG, "bridge fading $target")
    }

    private fun hideBridge(target: BridgeTarget) {
        bridgeTimers.remove(target)?.let { handler.removeCallbacks(it) }
        val view = bridges.remove(target) ?: return
        runCatching { windowManager?.removeView(view) }
        if (bridges.isEmpty()) setAccessibilityEventsEnabled(false)
        Log.i(TAG, "bridge hidden $target")
    }

    private fun scheduleBridgeTimer(target: BridgeTarget, delayMs: Long, action: () -> Unit) {
        bridgeTimers.remove(target)?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { bridgeTimers.remove(target); action() }
        bridgeTimers[target] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** Widens (or narrows back to idle) the events this service receives — see the file comment
     * and `system_shade_accessibility_service.xml`: only on while a B15 bridge is showing. */
    private fun setAccessibilityEventsEnabled(enabled: Boolean) {
        val info = runCatching { service.serviceInfo }.getOrNull() ?: return
        info.eventTypes = if (enabled) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0
        runCatching { service.serviceInfo = info }
    }

    companion object {
        private const val TAG = "FoldDuoSystemFrost"
        private const val TICK_MS = 50L

        /** Rule (d): the OPENED override cancels itself if nothing else did within this long. */
        private const val OPENED_OVERRIDE_TIMEOUT_MS = 10_000L

        /** Same `SharedPreferences` file as [cz.pflanzer.foldduo.AppearanceStore]. */
        const val PREFS_NAME = "appearance"

        /** "Frost over other apps" (Czech: "Mlha i nad aplikacemi"); default on. */
        const val KEY_ENABLED = "systemFrost"

        /** "Inner display from ~35° (experimental)" (B14); default off. */
        const val KEY_OPENED_OVERRIDE = "innerFrom35"

        /** B20 "Frost intensity" / "Tilt" sliders (Appearance.kt's `morphFrostFactor` / `morphTiltFactor`); read straight out of prefs here too, same reasoning as [KEY_ENABLED]. */
        const val KEY_MORPH_FROST_FACTOR = "morphFrostFactor"
        const val KEY_MORPH_TILT_FACTOR = "morphTiltFactor"
    }
}
