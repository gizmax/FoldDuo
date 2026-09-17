package cz.pflanzer.foldduo

import android.app.role.RoleManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import cz.pflanzer.foldduo.pose.PoseRepository
import cz.pflanzer.foldduo.pose.Panel
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.predict.ContinuityChipHost
import cz.pflanzer.foldduo.predict.ForegroundSighting
import cz.pflanzer.foldduo.predict.LastLaunchTracker
import cz.pflanzer.foldduo.predict.PredictionController
import cz.pflanzer.foldduo.predict.launchContinuityApp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: LauncherModel by viewModels()
    private lateinit var widgets: WidgetController
    internal lateinit var backups: BackupController
        private set
    internal lateinit var backgrounds: LauncherBackgroundController
        private set
    private val homeRequests = mutableIntStateOf(0)
    private val searchRequests = mutableIntStateOf(0)
    /** B48 "Pant jako ovladač": bumped when a squeeze recognizes under `HingeSqueezeAction.NowBrief`
     * and Now Brief's launch intent did not resolve — LauncherScreen's `LaunchedEffect` opens
     * Spotlight the same way [searchRequests] does. */
    private val spotlightRequests = mutableIntStateOf(0)
    /** B48 tent tilt: the label [TentTiltToastHost] shows for the last [PoseSnapshot.tentTiltSeq]
     * this activity reacted to; null when there was no active media session to act on. */
    private val tentTiltMessage = mutableStateOf<String?>(null)
    private val tentTiltSeq = mutableIntStateOf(0)
    /** B23 "Otevírání aplikací z ikony": bumped on every onResume so LauncherScreen can decide
     * (its own [LaunchReturnGuard], next to the icon bounds it captured at launch) whether this
     * particular resume should replay the "un-zoom" return animation. */
    private val launchResumes = mutableIntStateOf(0)
    private val defaultHome = mutableStateOf(false)
    private val showFirstRun = mutableStateOf(false)
    private lateinit var setupExperience: SetupExperience
    private lateinit var status: DeviceStatusMonitor
    private lateinit var appearance: AppearanceStore
    private var appearanceLocationGeneration = 0
    private var appearancePermissionGeneration = -1
    private var appearanceLocationCancellation: CancellationSignal? = null
    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { appearance.refresh(systemDark()); checkModeSchedule() }
    }
    // B39 "Režimy plochy": the DND signal. `NotificationManager.currentInterruptionFilter` has no
    // getter that reports the *previous* value, so this activity instance tracks it itself —
    // DndModeSignal.turnedOn only fires the switch on the ALL -> not-ALL edge, never on every
    // broadcast while DND stays on, and never on the way back off (the task only asks for "DND
    // turns on"). Re-read at each onStart so a filter change while stopped is not misread as new.
    private var previousInterruptionFilter = android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    private var dndReceiverRegistered = false
    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = notificationManager().currentInterruptionFilter
            if (DndModeSignal.turnedOn(previousInterruptionFilter, current))
                DndModeSignal.modeFor(model.state.value.homeModes)?.let { model.switchMode(it.id, "dnd") }
            previousInterruptionFilter = current
        }
    }
    private fun notificationManager() = getSystemService(android.app.NotificationManager::class.java)
    /** The scheduled trigger: cheap enough to just recompute on every minute tick (the time receiver already fires that often for the clock widgets). */
    private fun checkModeSchedule() {
        val now = java.time.LocalDateTime.now()
        val scheduled = ModeScheduler.scheduledMode(model.state.value.homeModes, now.dayOfWeek.value, now.hour * 60 + now.minute)
        if (scheduled != null && scheduled.id != model.state.value.activeModeId) model.switchMode(scheduled.id, "scheduled")
    }
    private val locationPermission = activityResultRegistry.register("duo.appearance.location", this,
        ActivityResultContracts.RequestPermission(), permissionResult@{ granted ->
        if (appearancePermissionGeneration != appearanceLocationGeneration || isDestroyed) return@permissionResult
        appearancePermissionGeneration = -1
        if (granted) requestAppearanceLocation()
        else finishAppearanceLocation("Location permission wasn’t granted. Using the system theme until you set a place.")
    })
    private var shadeSetupDialog: android.app.AlertDialog? = null
    private var returningFromShadeSettings = false
    /** Left edge of the vertical FoldingFeature in window px; null off the hinge (cover). */
    private val foldSeamPx = mutableStateOf<Float?>(null)
    private lateinit var pose: PoseRepository
    private lateinit var morph: MorphController
    /** B22: high-refresh-rate request + jank log for the launcher's own window, active only while [morph] runs an episode (see the `LaunchedEffect` in `onCreate`'s `setContent`). */
    private lateinit var frameMetrics: MorphFrameMetricsCollector
    /** B35 "Předpovědi aplikací + kontinuita": records launches, ranks Today suggestions, judges the continuity chip. */
    private lateinit var predictor: PredictionController
    /** Last panel [observeFoldGeometry] saw, to catch the Cover -> Inner edge (not just any snapshot). */
    private var lastPanel: Panel? = null
    /** The continuity chip's current candidate, if any, and when it was shown ([ContinuityChipHost] hides it after [cz.pflanzer.foldduo.predict.CONTINUITY_CHIP_DURATION_MS]). */
    private val continuityChip = mutableStateOf<ForegroundSighting?>(null)
    private var continuityShownAtMs = 0L
    /** Today's ranked Suggestions (recomputed on resume and every 30 min, see `setContent`'s `LaunchedEffect`s below); rendered by `LeadingPane.kt`'s `LeadingTopSlot` (`SuggestionsRow`, below B34's hub, above the grid). */
    private val suggestions = mutableStateOf<List<AppEntry>>(emptyList())
    /** B46 "Dvojice aplikací": shown when a pair's launch sequence finds the shade accessibility
     * service unbound (PairLaunch.kt's `toggleSplitScreen` returned false). */
    private val pairAccessibilityPromptVisible = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // B43 "Výkon jako feature": the process' own start time (zygote fork + classloading,
        // before this Activity even existed), not just this method's — the more honest "cold
        // start" reference the task asks to log under FoldDuoPerf.
        //
        // Výkon 2 (17. 9. noc) bug fix: Process.getStartElapsedRealtime() is the PROCESS's start,
        // which is only a sensible "cold start" reference for the FIRST Activity created in that
        // process. The cover<->inner panel swap recreates this Activity without restarting the
        // process (no configChanges in the manifest — PoseEngine.kt's own doc explains why), and
        // a device log caught exactly this: "cold start -> first frame: 329459 ms" on a relaunch,
        // i.e. this Activity's own creation measured against a process that had been alive for
        // 5+ minutes already. `processHasCreatedActivity` (a plain in-process flag, not
        // `savedInstanceState` — that survives this exact recreation too, but not a fresh launch
        // after the process was already warm for some other reason) tells the two cases apart.
        val isFirstActivityInProcess = !processHasCreatedActivity
        processHasCreatedActivity = true
        val coldStartMs = if (isFirstActivityInProcess) android.os.Process.getStartElapsedRealtime()
            else android.os.SystemClock.elapsedRealtime()
        // Device-agnostic wallpaper framing (DeviceProfile.kt): this Activity is the launcher's
        // own entry point for detecting the live device profile, read by LauncherBackgroundCache
        // and drawDunes instead of an assumed Fold 8 panel size.
        DeviceProfileHolder.initFrom(this)
        setupExperience = SetupExperience(this)
        showFirstRun.value = setupExperience.entryDecision(SetupExperience.hadLauncherState(this)) ==
            SetupEntryDecision.SHOW
        returningFromShadeSettings = savedInstanceState?.getBoolean(SHADE_SETTINGS_PENDING) == true
        val restoreShadeDialog = savedInstanceState?.getBoolean(SHADE_DIALOG_VISIBLE) == true
        appearance = AppearanceStore(this)
        Haptics.logTierOnce(this)
        // B34: seeds the live Compose state ("Notifications on Today" / "Show silent
        // notifications") from SharedPreferences once; both Appearance rows keep it in sync
        // for the rest of this process (see NotificationHubSettings).
        cz.pflanzer.foldduo.notifications.NotificationHubSettings.refresh(this)
        // Redesign after Tom's 2026-09-17 feedback: badges are their own opt-out toggle, on by
        // default, independent of the hub above (see notifications/Badges.kt).
        cz.pflanzer.foldduo.notifications.BadgeSettings.refresh(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        widgets = WidgetController(this, model).also { it.restore(savedInstanceState) }
        backups = BackupController(this, model, widgets) { }.also { it.restore() }
        backgrounds = LauncherBackgroundController(this) { }
        status = DeviceStatusMonitor(this).also { lifecycle.addObserver(it) }
        // App-scoped: Android relaunches this activity on the panel swap, and the new instance
        // must still see that swap in the repository's snapshot (PoseEngine).
        pose = PoseEngine.get(this)
        predictor = PredictionController(this)
        morph = MorphController(snapshot = { pose.snapshot.value }, motionFrost = { appearance.state.motionFrost },
            reduceMotion = { MotionPrefs.enabled.value })
        morph.noteAnimatorDurationScale(readAnimatorDurationScale())
        // B21: refreshed once here (readReduceMotion(this) below covers the very first frame,
        // before ON_START's register() would otherwise run it) and kept live for as long as the
        // activity is started.
        MotionPrefs.attach(this, this)
        frameMetrics = MorphFrameMetricsCollector(window, android.os.Handler(android.os.Looper.getMainLooper()))
        observeFoldGeometry()
        updateDefaultHome()
        if (savedInstanceState == null && intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        intent.removeExtra("duo_destination")
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            val deviceStatus = status.state.collectAsStateWithLifecycle().value
            val islandItems = cz.pflanzer.foldduo.island.IslandNotificationListener.items.collectAsStateWithLifecycle().value
            val hubNotifications = cz.pflanzer.foldduo.notifications.NotificationHub.items.collectAsStateWithLifecycle().value
            // Badges (redesign after Tom's 2026-09-17 feedback): the same listener grant's per-package
            // counts, gated live by the "Badges" setting the same way hubGroups gates on hubEnabled.
            val rawBadgeCounts = cz.pflanzer.foldduo.notifications.NotificationBadges.counts.collectAsStateWithLifecycle().value
            val badgeCounts = if (cz.pflanzer.foldduo.notifications.BadgeSettings.enabled.value) rawBadgeCounts else emptyMap()
            val foldPose = pose.state.collectAsStateWithLifecycle().value
            // B48 "Pant jako ovladač": squeezeDepthDeg/squeezeHeld drive the App Library overlay
            // live (collected here, not via the `observeFoldGeometry` lifecycleScope collectors
            // above, which only react to edges); the recognized/released/tent-tilt *reactions*
            // stay in those collectors so they run exactly once per edge regardless of recomposition.
            //
            // Výkon 2 (17. 9. noc): B44's gyro fusion republishes PoseSnapshot on every gyro
            // sample (~100-200 Hz) whenever the pose engine is running, i.e. almost always while
            // this Activity is in front — not just during a squeeze. Reading `.value` straight
            // off `collectAsStateWithLifecycle()` here made *this* composable (the one that calls
            // LauncherScreen with 30+ parameters) re-run at that rate even when nothing about the
            // squeeze changed, because PoseSnapshot carries a `FloatArray` field whose default
            // `equals()` is reference-based, so consecutive snapshots are essentially never
            // `==` to the StateFlow. `derivedStateOf` re-reads the fast-changing snapshot but
            // only invalidates readers when the *derived* Float/Boolean output actually differs
            // — outside an active squeeze both stay at 0f/false forever, so this scope stops
            // recomposing on hinge-fusion noise entirely; while a squeeze is held it still tracks
            // every sample for the "drag-like" depth follow the overlay needs.
            val squeezeSnapshotState = pose.snapshot.collectAsStateWithLifecycle()
            val squeezeDepthDeg by remember { derivedStateOf { squeezeSnapshotState.value.squeezeDepthDeg } }
            val squeezeHeld by remember { derivedStateOf { squeezeSnapshotState.value.squeezeHeld } }
            val density = LocalDensity.current
            val foldSeam = foldSeamPx.value?.let { px -> FoldSeam(with(density) { px.toDp().value }) }
            // B29 "Barvy z tapety": live wallpaper-derived palette (WallpaperPalette.kt), blended
            // into DuoTheme's Material scheme and exposed via LocalWallpaperPalette for the
            // FrostedBackdrop veil default and the widgets' "Wallpaper" colour appearance.
            val wallpaperPalette = rememberWallpaperPalette()
            // Výkon 2: one shared Liquid Glass light for every panel on screen (LiquidGlassBacking.kt's
            // LocalLiquidGlassLight doc) instead of each icon's own frame loop.
            val liquidGlassLight = rememberLiquidGlassLight()
            // B35: recomputed whenever the app/dock set changes, on every resume (launchResumes
            // bumps in onResume) and, while this screen stays composed, every 30 minutes.
            LaunchedEffect(state.apps, state.dock, launchResumes.intValue) {
                suggestions.value = withContext(Dispatchers.Default) { predictor.suggestions(state.apps, state.dock.filterNotNull().toSet()) }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(30 * 60 * 1000L)
                    suggestions.value = withContext(Dispatchers.Default) { predictor.suggestions(state.apps, state.dock.filterNotNull().toSet()) }
                }
            }
            CompositionLocalProvider(LocalFoldSeam provides foldSeam, LocalFoldPose provides foldPose,
                LocalMorphController provides morph, LocalIconStyle provides state.iconStyle,
                LocalLiquidGlassLight provides liquidGlassLight,
                cz.pflanzer.foldduo.notifications.LocalNotificationBadges provides badgeCounts) {
            DuoTheme(appearance.state.dark, wallpaperPalette) {
            // B35 "kontinuita": the chip overlays the whole screen rather than only Today's
            // canvas — LauncherScreen.kt's own Today composition is mid-edit by this wave's B33
            // (item transforms) and B34 (notification hub), so this box wraps it from the
            // outside instead of adding a parameter to it. It only ever appears right after a
            // cover -> inner swap, when Home is already what is in front, so this reads the same
            // to the user as anchoring it inside the canvas would.
            Box(Modifier.fillMaxSize()) {
                LauncherScreen(state, model, widgets, homeRequests.intValue,
                    onLaunch = { launchApp(it) }, onMakeDefault = ::makeDefault, onAppInfo = ::appInfo,
                    onLaunchPair = ::launchPair,
                    isDefaultHome = defaultHome.value, deviceStatus = deviceStatus, onStatusMode = ::setStatusMode, onWallpaperPreview = ::previewWallpaper,
                    searchRequests = searchRequests.intValue,
                    onLaunchFrom = ::launchApp, onGoogleSearch = ::openGoogleSearch,
                    appearance = appearance.state,
                    onAppearanceMode = { cancelAppearanceLocation(); appearance.setMode(it, systemDark()) },
                    onAppearanceManual = { place, lat, lon -> cancelAppearanceLocation(); appearance.setManual(place, lat, lon, systemDark()) },
                    onAppearanceDeviceLocation = ::useAppearanceLocation,
                    onAppearanceClear = { cancelAppearanceLocation(); appearance.clearLocation(systemDark()) },
                    onAppearanceMotionFrost = { appearance.setMotionFrost(it, systemDark()) },
                    onAppearanceSystemFrost = { appearance.setSystemFrost(it, systemDark()) },
                    onAppearanceOpenedOverride = { appearance.setOpenedOverride(it, systemDark()) },
                    onAppearanceHapticAtFlat = { appearance.setHapticAtFlat(it, systemDark()) },
                    onAppearanceMorphFrostFactor = { appearance.setMorphFrostFactor(it, systemDark()) },
                    onAppearanceMorphTiltFactor = { appearance.setMorphTiltFactor(it, systemDark()) },
                    onAppearanceResetMorphPreview = { appearance.resetMorphPreview(systemDark()) },
                    onAppearanceHingeSqueeze = { appearance.setHingeSqueezeAction(it, systemDark()) },
                    squeezeDepthDeg = squeezeDepthDeg, squeezeHeld = squeezeHeld,
                    spotlightRequests = spotlightRequests.intValue,
                    showFirstRun = showFirstRun.value,
                    onFinishFirstRun = ::finishFirstRun,
                    onShadeSetup = ::showShadeSetup,
                    islandItems = islandItems,
                    launchResumes = launchResumes.intValue,
                    hubNotifications = hubNotifications,
                    suggestions = suggestions.value,
                    onSuggestionBlock = { app ->
                        predictor.block(app.packageName)
                        suggestions.value = suggestions.value.filterNot { it.id == app.id }
                    })
                ContinuityChipHost(
                    sighting = continuityChip.value,
                    shownAtMs = continuityShownAtMs,
                    onTap = { sighting ->
                        launchContinuityApp(this@MainActivity, sighting, leftPaneBounds())
                        continuityChip.value = null
                    },
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
                )
                // B48 tent tilt: a sibling overlay rather than a LauncherScreen.kt addition, same
                // reasoning as ContinuityChipHost above it.
                TentTiltToastHost(
                    message = tentTiltMessage.value, seq = tentTiltSeq.intValue,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                )
                PairAccessibilityPrompt(
                    visible = pairAccessibilityPromptVisible.value,
                    onDismiss = { pairAccessibilityPromptVisible.value = false },
                    onOpenSettings = {
                        pairAccessibilityPromptVisible.value = false
                        SystemShadeAccessibilityService.openAccessibilitySettings(this@MainActivity)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
            }
            // Off the critical path: composes nothing until the first frame is out, then one
            // throwaway frame of the frost shader (UnfoldMorph.kt) so the first real morph
            // does not compile it.
            MorphWarmUp(morph)
            // B22, "120 Hz při morphu": while any morph episode runs on this window (the unfold
            // clear, a hinge/gyro-triggered cover or closing frost, the cover settle), ask for
            // 120 Hz and start the frame-metrics collector; release/log the moment it ends. The
            // three flags are `by mutableStateOf` on `morph`, so this recomposes exactly on the
            // frames where the combined answer flips, not on every progress tick.
            val morphEpisodeActive = morph.running || morph.coverFrostActive || morph.closeFrostActive
            LaunchedEffect(morphEpisodeActive) {
                if (morphEpisodeActive) { HighRefreshRate.request(window); frameMetrics.start() }
                // "Zavírání jako Duo" item 6: attach whichever closing episode just finished, if any.
                else { frameMetrics.stop(morph.takeClosingEpisode()); HighRefreshRate.release(window) }
            }
            // B43: cold start -> first frame, then first frame -> "Home interactive" (apps loaded,
            // LauncherModel.state.loading false), reported via reportFullyDrawn() and logged under
            // FoldDuoPerf so DEBUG_PERF (debug builds) can dump it after the fact.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                val firstFrameMs = android.os.SystemClock.elapsedRealtime()
                val coldStartToFirstFrame = firstFrameMs - coldStartMs
                val basis = if (isFirstActivityInProcess) "process start" else "activity recreate, e.g. panel swap"
                android.util.Log.i(PERF_TAG, "cold start -> first frame: $coldStartToFirstFrame ms ($basis)")
                PerfHistory.noteStartup(coldStartToFirstFrame)
                model.state.first { !it.loading }
                reportFullyDrawn()
                val interactiveMs = android.os.SystemClock.elapsedRealtime() - firstFrameMs
                android.util.Log.i(PERF_TAG, "first frame -> home interactive: $interactiveMs ms")
                PerfHistory.noteInteractive(interactiveMs)
            }
            }
        }
        FoldRenderExperiment.attach(this)
        MorphDebug.attach(this, morph)
        IconStyleDebug.attach(this, model)
        DebugPerf.attach(this)
        // Výkon 2 item 1: debug-only main-thread stall/slow-message watchdog (MainThreadWatchdog.kt).
        MainThreadWatchdog.start()
        if (restoreShadeDialog) window.decorView.post { if (!isFinishing && !isDestroyed) showShadeSetup() }
    }

    /**
     * Fold geometry for the composition: the vertical hinge line from Jetpack WindowManager
     * (state is always FLAT on this firmware, so only orientation matters) and the Pose
     * Engine output. Both run only while the activity is started.
     */
    private fun observeFoldGeometry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    val seam = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
                    val left = seam?.bounds?.left?.toFloat()
                    if (left != foldSeamPx.value) {
                        android.util.Log.d(TAG, "fold seam: ${seam?.bounds ?: "none"}")
                        foldSeamPx.value = left
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pose.state.collect { android.util.Log.d(TAG, "pose: $it") }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Panel edges seen while this instance lives arm the morph (Continuum A).
                pose.snapshot.collect { morph.noteSnapshot(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // B35 "kontinuita": a Cover -> Inner edge is the panel swap the chip cares about;
                // any other transition (including Inner -> Cover) is ignored.
                //
                // Výkon 2 (17. 9. noc): predictor.continuityCandidate() -> lastForegroundApp() runs
                // a synchronous UsageStatsManager.queryEvents() binder call. This collector's
                // dispatcher is Main (lifecycleScope's default), so that query used to run right on
                // the main thread at exactly the moment this fires — the Cover -> Inner edge, i.e.
                // mid panel-swap/mid-morph, which is exactly where the device log showed 43/71/38/32
                // skipped frames. Moved off main; only the two State writes at the end need it back.
                pose.snapshot.map { it.panel }.distinctUntilChanged().collect { panel ->
                    val previous = lastPanel
                    lastPanel = panel
                    if (previous == Panel.Cover && panel == Panel.Inner) {
                        val swapAtMs = System.currentTimeMillis()
                        val candidate = withContext(Dispatchers.Default) { predictor.continuityCandidate(packageName, swapAtMs) }
                        if (candidate != null) { continuityChip.value = candidate; continuityShownAtMs = swapAtMs }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // B48 "Pant jako ovladač": squeezeHeld flips exactly at recognition (false -> true)
                // and at release (true -> false, see pose/HingeSqueeze.kt), so a single collector on
                // it covers both edges the task's haptics/action need. `Off` mutes both edges (the
                // detector itself keeps running either way — this is only where the app reacts).
                var wasHeld = false
                pose.snapshot.map { it.squeezeHeld }.distinctUntilChanged().collect { held ->
                    val action = appearance.state.hingeSqueezeAction
                    if (action != HingeSqueezeAction.Off) {
                        if (held && !wasHeld) {
                            Haptics.play(this@MainActivity, HapticEvent.SQUEEZE_RECOGNIZED)
                            if (action == HingeSqueezeAction.NowBrief && !openNowBrief(this@MainActivity)) spotlightRequests.intValue++
                        } else if (!held && wasHeld) {
                            Haptics.play(this@MainActivity, HapticEvent.SQUEEZE_RELEASE)
                        }
                    }
                    wasHeld = held
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // B48 tent tilt: media transport control, only while something is actually
                // playing (handleTentTilt returns null and shows nothing otherwise).
                pose.snapshot.map { it.tentTiltSeq to it.tentTiltDir }.distinctUntilChanged().collect { (seq, direction) ->
                    if (direction != null) { tentTiltMessage.value = handleTentTilt(direction); tentTiltSeq.intValue = seq }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart(); widgets.host.startListening()
        PoseEngine.acquire(this)
        if (!timeReceiverRegistered) {
            ContextCompat.registerReceiver(this, timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            timeReceiverRegistered = true
        }
        if (!dndReceiverRegistered) {
            previousInterruptionFilter = notificationManager().currentInterruptionFilter
            ContextCompat.registerReceiver(this, dndReceiver,
                IntentFilter(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            dndReceiverRegistered = true
        }
        appearance.refresh(systemDark())
        checkModeSchedule()
    }
    override fun onStop() {
        if (timeReceiverRegistered) { unregisterReceiver(timeReceiver); timeReceiverRegistered = false }
        if (dndReceiverRegistered) { unregisterReceiver(dndReceiver); dndReceiverRegistered = false }
        PoseEngine.release()
        widgets.host.stopListening(); super.onStop()
    }
    override fun onDestroy() {
        shadeSetupDialog?.dismiss()
        cancelAppearanceLocation()
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        // The StandBy pose service is START_STICKY but nothing restarts it after a sideload
        // reinstall or a process crash until a reboot or a settings toggle (17. 9.); Home
        // resuming is the natural place to bring it back.
        if (!cz.pflanzer.foldduo.standby.StandByService.running &&
            cz.pflanzer.foldduo.standby.StandByPrefs(this).enabled) {
            runCatching { cz.pflanzer.foldduo.standby.StandByService.start(this) }
        }
        returningFromShadeSettings = false
        model.refresh(); appearance.refresh(systemDark()); updateDefaultHome()
        // Notification access may have changed in Settings while we were paused.
        cz.pflanzer.foldduo.island.IslandNotificationListener.refreshEnabled(this)
        // B18: the developer setting can change while we are paused (Settings -> Developer
        // options); every timed morph fallback reads this scale (UnfoldMorph.kt), angle mode does not.
        morph.noteAnimatorDurationScale(readAnimatorDurationScale())
        // B23: a resume can be the app we just launched coming back to Home (or Android's own
        // predictive-back-to-home transition landing here); LauncherScreen decides, via the
        // guard, whether this particular resume replays the "un-zoom".
        launchResumes.intValue++
        // B21: same reasoning — the ContentObserver in MotionPrefs.attach() covers a live toggle,
        // this covers the settings screen having been open the whole time we were paused.
        MotionPrefs.refresh(this)
        // B35: pulls in any UsageStats launches this instance itself did not see (another
        // launcher session, or usage access having just been granted); a no-op without that
        // access or once nothing new is left to merge. Off the main thread — UsageStatsManager
        // can be slow the first time.
        lifecycleScope.launch(Dispatchers.Default) { predictor.backfillFromUsageStats(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) }
    }

    /** B18: `Settings.Global.ANIMATOR_DURATION_SCALE`, 1x when unset or unreadable. */
    private fun readAnimatorDurationScale(): Float =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

    internal fun openSystemShade(panel: ShadePanel) {
        when (SystemShadeAccessibilityService.open(this, panel)) {
            ShadeOpenResult.OPENED -> Unit
            ShadeOpenResult.SERVICE_DISABLED -> showShadeSetup()
            ShadeOpenResult.SERVICE_STARTING -> Toast.makeText(this,
                "Shade gestures are starting. Swipe down again.", Toast.LENGTH_SHORT).show()
            ShadeOpenResult.ACTION_REJECTED -> Toast.makeText(this,
                "Android couldn’t open the system panel.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShadeSetup() {
        if (shadeSetupDialog?.isShowing == true) return
        shadeSetupDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Turn on shade gestures")
            .setMessage("Android requires you to enable Duo Launcher shade gestures in Accessibility settings. This service only opens Notifications or Quick Settings; it doesn’t read screen content or watch other apps.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    returningFromShadeSettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: android.content.ActivityNotFoundException) {
                    returningFromShadeSettings = false
                    Toast.makeText(this, "Accessibility settings are unavailable.", Toast.LENGTH_LONG).show()
                }
            }
            .also { dialog -> dialog.setOnDismissListener { shadeSetupDialog = null } }
            .show()
    }

    private fun finishFirstRun() {
        setupExperience.finish()
        showFirstRun.value = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setStatusMode(model.state.value.verticalStatus)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgets.save(outState)
        outState.putBoolean(SHADE_DIALOG_VISIBLE, shadeSetupDialog?.isShowing == true && !returningFromShadeSettings)
        outState.putBoolean(SHADE_SETTINGS_PENDING, returningFromShadeSettings)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        FoldRenderExperiment.onNewIntent(this, intent)
        if (intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        else if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.getStringExtra("duo_destination") == "home") {
            // iOS: coming back from an app lands on the page you left; only a Home press while
            // Home is already in front (resumed) goes to the first page. 17. 9. log: the HOME
            // intent delivered on the return from an app animated the pager away from the page
            // the user was on.
            val alreadyInFront = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (alreadyInFront || intent.getStringExtra("duo_destination") == "home") homeRequests.intValue++
            else android.util.Log.i("FoldDuoPager", "home intent while not in front: keeping the current page")
        }
        intent.removeExtra("duo_destination")
    }

    @Deprecated("Widget configuration uses the platform host request-code API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun launchApp(app: AppEntry, bounds: android.graphics.Rect? = null) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startMainActivity(app.component, user, screenBounds(bounds), launchOptions(bounds))
            model.recordLaunch(app)
            // B35 "kontinuita": own-launch fallback for the chip when usage access isn't granted.
            LastLaunchTracker.record(app.packageName, app.label)
        } catch (_: Exception) { Toast.makeText(this, "${app.label} is unavailable.", Toast.LENGTH_SHORT).show(); model.refresh() }
    }

    /**
     * Best-effort left-pane bounds for [launchContinuityApp]'s `ActivityOptions.setLaunchBounds`:
     * the fold seam already tracked for the frost layers ([foldSeamPx]) when known, else the
     * window's own left half.
     */
    private fun leftPaneBounds(): android.graphics.Rect {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        val width = window.decorView.width; val height = window.decorView.height
        val seam = foldSeamPx.value?.roundToInt() ?: (width / 2)
        return android.graphics.Rect(location[0], location[1], location[0] + seam, location[1] + height)
    }

    /** B46's mirror of [leftPaneBounds], for `second`'s best-effort split-screen launch bounds. */
    private fun rightPaneBounds(): android.graphics.Rect {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        val width = window.decorView.width; val height = window.decorView.height
        val seam = foldSeamPx.value?.roundToInt() ?: (width / 2)
        return android.graphics.Rect(location[0] + seam, location[1], location[0] + width, location[1] + height)
    }

    /**
     * B46 "Dvojice aplikací": launches [pair]'s `first` normally (with the zoom, from [bounds]).
     * On the cover — [pose] never reports [Panel.Inner] there — that's the whole sequence, per the
     * task's pane split (cover launches `first` only). On the inner display: wait for `first` to
     * resume ([PairLaunch.awaitResumed]), toggle split screen via the accessibility service, then
     * launch `second` adjacent into the right pane; if the service isn't bound, show the "turn on
     * the accessibility service" prompt instead of guessing at a retry.
     */
    private fun launchPair(pair: PairEntry, bounds: android.graphics.Rect?) {
        val apps = model.state.value.apps.associateBy(AppEntry::id)
        val first = apps[pair.first]
        if (first == null) { Toast.makeText(this, "This pair is unavailable.", Toast.LENGTH_SHORT).show(); model.refresh(); return }
        launchApp(first, bounds)
        val second = apps[pair.second] ?: return
        if (pose.snapshot.value.panel != Panel.Inner) return
        lifecycleScope.launch {
            PairLaunch.awaitResumed(this@MainActivity, first.packageName)
            if (!PairLaunch.toggleSplitScreen(this@MainActivity)) {
                pairAccessibilityPromptVisible.value = true
                return@launch
            }
            PairLaunch.launchAdjacent(this@MainActivity, second, rightPaneBounds())
        }
    }

    private fun screenBounds(bounds: android.graphics.Rect?): android.graphics.Rect? = bounds?.takeUnless { it.isEmpty }?.let {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        android.graphics.Rect(it).apply { offset(location[0], location[1]) }
    }
    /**
     * B23: the window zooms in from the icon's own bounds (`makeClipRevealAnimation`) instead of
     * the plain cross-fade Android uses by default. Reduced motion (`ANIMATOR_DURATION_SCALE == 0`)
     * skips this entirely — plain launch, no bounds-driven animation at all.
     */
    private fun launchOptions(bounds: android.graphics.Rect?): Bundle? =
        bounds?.takeUnless { it.isEmpty || systemReduceMotionEnabled(this) }?.let {
            android.app.ActivityOptions.makeClipRevealAnimation(window.decorView, it.left, it.top, it.width(), it.height()).toBundle()
        }
    private fun openGoogleSearch(bounds: android.graphics.Rect?): Boolean = try {
        startActivity(googleSearchIntent().apply { sourceBounds = screenBounds(bounds) }, launchOptions(bounds))
        true
    } catch (_: android.content.ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    private fun makeDefault() {
        // Samsung may immediately cancel a role request; its Home settings is reliable.
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            val role = getSystemService(RoleManager::class.java)
            if (role.isRoleAvailable(RoleManager.ROLE_HOME)) startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun updateDefaultHome() {
        defaultHome.value = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun systemDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun useAppearanceLocation() {
        cancelAppearanceLocation()
        appearance.locationStatus("Waiting for approximate device location…")
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            requestAppearanceLocation()
        else {
            appearancePermissionGeneration = appearanceLocationGeneration
            runCatching { locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                .onFailure { finishAppearanceLocation("Location permission couldn’t be requested. Using the system theme.") }
        }
    }

    private fun requestAppearanceLocation() {
        val generation = ++appearanceLocationGeneration
        val manager = getSystemService(LocationManager::class.java)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finishAppearanceLocation("Location permission isn’t available. Using the system theme."); return
        }
        val cached = runCatching { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time <= 15 * 60_000 } }.getOrNull()
        if (cached != null) {
            if (generation == appearanceLocationGeneration) appearance.setDeviceLocation(cached.latitude, cached.longitude, systemDark())
            finishAppearanceLocation(null); return
        }
        val provider = runCatching { when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        } }.getOrNull() ?: run { finishAppearanceLocation("No approximate location provider is available. Using the system theme."); return }
        val cancellation = CancellationSignal()
        appearanceLocationCancellation = cancellation
        window.decorView.postDelayed({
            if (generation == appearanceLocationGeneration && appearanceLocationCancellation === cancellation) {
                cancellation.cancel(); finishAppearanceLocation("Location timed out. Using the system theme until you try again or enter a place.")
            }
        }, 10_000)
        runCatching { manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(this)) { location ->
            if (generation != appearanceLocationGeneration || isDestroyed) return@getCurrentLocation
            if (location != null) appearance.setDeviceLocation(location.latitude, location.longitude, systemDark())
            finishAppearanceLocation(if (location == null) "Location is unavailable. Using the system theme." else null)
        } }.onFailure { finishAppearanceLocation("Location is unavailable. Using the system theme.") }
    }

    private fun cancelAppearanceLocation() {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation?.cancel(); appearanceLocationCancellation = null
        if (::appearance.isInitialized) appearance.locationStatus(null)
    }

    private fun finishAppearanceLocation(message: String?) {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation = null
        appearance.locationStatus(message)
    }

    private fun setStatusMode(vertical: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vertical) controller.hide(WindowInsetsCompat.Type.statusBars()) else controller.show(WindowInsetsCompat.Type.statusBars())
        statusBarWantedHidden = vertical
        // 17. 9. (Tom: "dá se vypnout ten šedivý status při malém swipe down?"): Android reveals a
        // hidden status bar transiently on an edge swipe and offers no way to disable that. The
        // closest thing is to ask for it to be hidden again the moment its reveal animation is
        // prepared, which shortens the grey bar to a blink at best. Experimental; logged so the
        // next logcat shows whether One UI honours it.
        if (!transientBarHookInstalled) {
            transientBarHookInstalled = true
            androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(window.decorView,
                object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                        if (statusBarWantedHidden && animation.typeMask and WindowInsetsCompat.Type.statusBars() != 0) {
                            android.util.Log.i("FoldDuoPerf", "status bar reveal animation: asking to hide again")
                            controller.hide(WindowInsetsCompat.Type.statusBars())
                        }
                    }
                    override fun onProgress(insets: WindowInsetsCompat, running: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
                })
        }
    }
    private var statusBarWantedHidden = false
    private var transientBarHookInstalled = false

    private fun previewWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, DuneWallpaperService::class.java)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "The system wallpaper preview is unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun appInfo(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, user, null, null)
        } catch (_: Exception) {
            Toast.makeText(this, "${app.label} is unavailable.", Toast.LENGTH_SHORT).show()
            model.refresh()
        }
    }

    private companion object {
        const val TAG = "FoldDuo"
        const val PERF_TAG = "FoldDuoPerf"
        const val SHADE_DIALOG_VISIBLE = "duo.shade.dialog_visible"
        const val SHADE_SETTINGS_PENDING = "duo.shade.settings_pending"
        /** Výkon 2: set by the first [MainActivity] created in this process — see [onCreate]'s `coldStartMs`. */
        @Volatile
        private var processHasCreatedActivity = false
    }
}
