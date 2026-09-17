package cz.pflanzer.foldduo.cameraisland

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.BuildConfig
import cz.pflanzer.foldduo.HapticEvent
import cz.pflanzer.foldduo.Haptics
import cz.pflanzer.foldduo.LocalFoldSeam
import cz.pflanzer.foldduo.MotionPrefs
import cz.pflanzer.foldduo.island.ISLAND_EQUALIZER_BAR_COUNT
import cz.pflanzer.foldduo.island.ISLAND_EQUALIZER_PERIOD_MS
import cz.pflanzer.foldduo.island.IslandAction
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.IslandMedia
import cz.pflanzer.foldduo.island.chronometerText
import cz.pflanzer.foldduo.island.extrapolatedPositionMs
import cz.pflanzer.foldduo.island.islandEqualizerBarLevel
import cz.pflanzer.foldduo.island.mediaRemainingLabel
import cz.pflanzer.foldduo.island.mediaTimeLabel
import cz.pflanzer.foldduo.splits
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * B61 "Ostrov kolem kamery" phases 1/3/4: a black pill built around the camera cutout itself
 * (IDEAS.md, distinct from `island/RailIsland.kt`'s pill stack under the status block) — the
 * launcher's own take on iOS's Dynamic Island. [CameraIslandOverlay] is composed once at the
 * root, next to `island.IslandExpandedOverlay`; it reads the live cutout, drives one morph
 * [Animatable] through `CameraIslandGeometry.kt`'s pure rect/state functions, and renders states
 * hidden -> compact -> minimal -> expanded plus a transient "alert" pulse. Phase 2 (Live Updates
 * adapters, Now Bar per-app parsing) is `island/IslandNotificationListener.kt`'s own, developed in
 * parallel — this file only consumes the `IslandItem`s that listener already publishes, the same
 * list `RailIsland` reads.
 *
 * 17. 9. night, iPhone-Dynamic-Island rework: [CameraIslandVisualState.HIDDEN] now renders
 * NOTHING (see [cameraIslandContainerAlpha]) — with nothing live the overlay draws no pill at all,
 * just the bare cutout the system already shows; the pill grows out of [cameraIslandNoneRect] on
 * the first live item and shrinks back into it when the last one leaves. [CameraIslandVisualState.MINIMAL]
 * is now a primary compact-shaped pill plus a detached secondary circle
 * ([cameraIslandMinimalSecondaryRect]), not two slots sharing one pill. The corner radius is tied
 * directly to the live morphed rect's own height ([cameraIslandCornerDp]) instead of a separate
 * from/to-state lerp. The expanded card fills its own pane (`panelWidth - 24dp`) with a 44dp
 * corner and a per-kind layout (media/transport/timer/call/navigation/progress) whose first row
 * avoids [cameraIslandExpandedExclusionZone].
 */

// --- "Live activities: Camera island / Rail" (Today settings, default Camera island) --------

enum class LiveActivitiesSurface { CAMERA_ISLAND, RAIL }

private const val CAMERA_ISLAND_PREFS = "appearance"
private const val CAMERA_ISLAND_SURFACE_KEY = "liveActivitiesSurface"

/**
 * Which surface shows live activities: the camera island (default) or the rail's own pill stack
 * (`island/RailIsland.kt`, today's behaviour). Same live-[State] pattern as
 * `island.IslandStyle`/[MotionPrefs] so the Today settings row and both surfaces — often far
 * apart in the composition — agree within one session without a restart.
 */
object CameraIslandSettings {
    private val state = mutableStateOf(LiveActivitiesSurface.CAMERA_ISLAND)

    val current: State<LiveActivitiesSurface> get() = state

    fun refresh(context: Context) { state.value = surface(context) }

    fun surface(context: Context): LiveActivitiesSurface {
        val raw = context.getSharedPreferences(CAMERA_ISLAND_PREFS, Context.MODE_PRIVATE)
            .getString(CAMERA_ISLAND_SURFACE_KEY, null)
        return raw?.let { name -> runCatching { LiveActivitiesSurface.valueOf(name) }.getOrNull() } ?: LiveActivitiesSurface.CAMERA_ISLAND
    }

    fun setSurface(context: Context, value: LiveActivitiesSurface) {
        context.getSharedPreferences(CAMERA_ISLAND_PREFS, Context.MODE_PRIVATE).edit().putString(CAMERA_ISLAND_SURFACE_KEY, value.name).apply()
        state.value = value
    }
}

/** `true` while the camera island (rather than the rail) is the live-activities surface — the one flag `island/RailIsland.kt` and `LayoutModel.kt`'s `islandSlotHeight` need. */
@Composable
fun cameraIslandIsActiveSurface(): Boolean {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CameraIslandSettings.refresh(context) }
    return CameraIslandSettings.current.value == LiveActivitiesSurface.CAMERA_ISLAND
}

// --- Debug injection (task spec item 6, debug builds only) ----------------------------------

/**
 * Debug-only fake [IslandItem] source so the camera island's compact/minimal/expanded states can
 * be screenshotted without a real Uber ride, timer or call. [CameraIslandOverlay] merges
 * [items] into the live feed only when [BuildConfig.DEBUG]; the debug-only broadcast receiver
 * that drives this (`app/src/debug/.../CameraIslandDebugReceiver.kt`) calls [inject] directly.
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind timer
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind uber --ei count 2
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind media
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind call
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind none
 */
object CameraIslandDebugInjector {
    private val state = mutableStateOf<List<IslandItem>>(emptyList())
    val items: State<List<IslandItem>> get() = state
    /** `--ez expand true`: the overlay opens the first injected item's card once (for screenshots of the expanded layout). */
    private val expandState = mutableStateOf(0)
    val expandRequest: State<Int> get() = expandState
    /** The injected item the expand request targets (the phone may carry a real item ahead of it). */
    @Volatile var expandKey: String? = null
        private set

    fun inject(kind: String?, count: Int, expand: Boolean = false) {
        val now = System.currentTimeMillis()
        val n = count.coerceIn(1, 2)
        val built = when (kind?.trim()?.lowercase()) {
            "timer" -> (1..n).map { fakeTimer(now, it) }
            "uber" -> (1..n).map { fakeUber(now, it) }
            "media" -> (1..n).map { fakeMedia(it) }
            "call" -> (1..n).map { fakeCall(now, it) }
            else -> emptyList()
        }
        state.value = built
        if (expand && built.isNotEmpty()) { expandKey = built.first().key; expandState.value++ }
        Log.i("FoldDuoIsland", "debug inject kind=$kind count=$n expand=$expand -> ${built.map { it.key }}")
    }

    private fun fakeUber(now: Long, index: Int) = IslandItem(
        key = "debug-uber-$index", kind = IslandKind.TRANSPORT, title = "Toyota Corolla", text = "4AB 1234 · Marek",
        keyValue = "4 min", etaMs = now + 4 * 60_000L,
        segments = listOf("Přijíždí", "Na místě", "Cesta", "Cíl"), stageIndex = 0,
        actions = listOf(IslandAction("Zavolat"), IslandAction("Zpráva")),
    )

    /** Duration/position tuned so the media card's progress bar shows 38% (task item 6). */
    private fun fakeMedia(index: Int) = IslandItem(
        key = "debug-media-$index", kind = IslandKind.MEDIA, title = "Blinding Lights", text = "The Weeknd",
        media = IslandMedia(
            playing = true, controller = null,
            positionMs = 84_000L, durationMs = 221_000L, positionUpdatedAtMs = SystemClock.elapsedRealtime(),
        ),
        accentColor = 0xFF7A1F2B.toInt(),
    )

    /** Two actions (task item 6): first = pause/resume, second = cancel (see [CameraIslandTimerExpanded]/[CameraIslandCallExpanded] docs on the mapping order). */
    private fun fakeTimer(now: Long, index: Int) = IslandItem(
        key = "debug-timer-$index", kind = IslandKind.TIMER, title = "Timer",
        chronometerBase = now - 125_000L, countDown = false,
        actions = listOf(IslandAction("Pozastavit"), IslandAction("Zrušit")),
    )

    private fun fakeCall(now: Long, index: Int) = IslandItem(
        key = "debug-call-$index", kind = IslandKind.CALL, title = if (index == 1) "Marek Novák" else "Volající $index",
        chronometerBase = now - 201_000L, actions = listOf(IslandAction("Ztlumit"), IslandAction("Zavěsit")),
    )
}

// --- Motion constants (Compose-side; the pure thresholds/windows live in CameraIslandGeometry.kt) --

/** `spring(dampingRatio 0.72, stiffness 260)` — growing/expanding transitions overshoot slightly, iOS-style (task spec item 4). */
private val CAMERA_ISLAND_EXPAND_SPRING = spring<Float>(dampingRatio = 0.72f, stiffness = 260f)

/** `spring(dampingRatio 0.85, stiffness 320)` — shrinking/collapsing transitions settle faster with less overshoot (task spec item 4). */
private val CAMERA_ISLAND_COLLAPSE_SPRING = spring<Float>(dampingRatio = 0.85f, stiffness = 320f)

private val CAMERA_ISLAND_ARRIVAL_SPRING = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
private val CAMERA_ISLAND_ALERT_SPRING = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
private val CAMERA_ISLAND_FLIGHT_SPRING = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 300f)

private fun <T> cameraIslandSpec(reduceMotion: Boolean, spring: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (reduceMotion) tween(CAMERA_ISLAND_REDUCED_MOTION_MS) else spring

/** Whether [to] is bigger (by area) than [from] — decides [CAMERA_ISLAND_EXPAND_SPRING] vs [CAMERA_ISLAND_COLLAPSE_SPRING] for a transition (task spec item 4: "expand uses ... so the card overshoots slightly ... collapse ..."). */
private fun cameraIslandIsGrowing(from: CameraIslandRect, to: CameraIslandRect): Boolean =
    (to.widthDp.toDouble() * to.heightDp.toDouble()) >= (from.widthDp.toDouble() * from.heightDp.toDouble())

private fun cameraIslandMorphSpec(reduceMotion: Boolean, growing: Boolean): FiniteAnimationSpec<Float> =
    if (reduceMotion) tween(CAMERA_ISLAND_REDUCED_MOTION_MS) else if (growing) CAMERA_ISLAND_EXPAND_SPRING else CAMERA_ISLAND_COLLAPSE_SPRING

/** Expanded content height per item kind: [cameraIslandExpandedHeightForKind] on [item]'s own kind (124/164dp, exact per the 17. 9. night mock — no per-item variation any more). Null falls back to the "below" layout's own 124dp. */
private fun cameraIslandExpandedHeightFor(item: IslandItem?): Float =
    item?.let { cameraIslandExpandedHeightForKind(it.kind) } ?: CAMERA_ISLAND_EXPANDED_MIN_HEIGHT_DP

/**
 * Which leading/trailing slot widths a state's pill uses — MINIMAL's own primary pill is exactly the
 * compact shape (task spec item 2: "primary pill as compact"); HIDDEN/EXPANDED lay themselves out
 * separately. [measuredTrailingWidthDp] is the live-measured, never-wrapping trailing value width
 * (Tom, 17. 9.: "the pill grows to fit it") — null (no text value; a media equalizer/progress ring)
 * falls back to [CAMERA_ISLAND_EQUALIZER_WIDTH_DP].
 */
private fun cameraIslandContentFor(state: CameraIslandVisualState, measuredTrailingWidthDp: Float?): CameraIslandContent = when (state) {
    CameraIslandVisualState.COMPACT, CameraIslandVisualState.MINIMAL -> CameraIslandContent(
        leadingWidthDp = CAMERA_ISLAND_LEADING_ICON_DP,
        trailingWidthDp = measuredTrailingWidthDp ?: CAMERA_ISLAND_EQUALIZER_WIDTH_DP,
    )
    CameraIslandVisualState.HIDDEN, CameraIslandVisualState.EXPANDED -> CameraIslandContent.NONE
}

/** Only the fields that decide whether a state transition should play — deliberately excludes any per-second/ticking field so a running chronometer never restarts the morph. */
private data class CameraIslandTransitionKey(val state: CameraIslandVisualState, val frontKey: String?, val backKey: String?, val hasMedia: Boolean)

private data class CameraIslandSnapshot(val key: CameraIslandTransitionKey, val rect: CameraIslandRect)

/**
 * The camera island's root-level overlay, composed once in `LauncherScreen.kt` next to
 * `island.IslandExpandedOverlay`. Self-contained: reads the live cutout (`View.rootWindowInsets`),
 * the fold seam ([LocalFoldSeam]) and reduce-motion ([MotionPrefs]) itself, so the call site only
 * ever needs [items]. A no-op (renders nothing) while "Live activities" is set to Rail
 * ([CameraIslandSettings]).
 */
@Composable
fun CameraIslandOverlay(items: List<IslandItem>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CameraIslandSettings.refresh(context) }
    val surface by CameraIslandSettings.current
    if (surface != LiveActivitiesSurface.CAMERA_ISLAND) return

    val reduceMotion = MotionPrefs.enabled.value
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Live cutout, task spec: "check its cutout via WindowInsets.displayCutout bounding rects;
    // treat 'no cutout' as a fallback". Read directly off the platform View (minSdk 33 already has
    // DisplayCutout.boundingRects, API 28+) rather than Compose's WindowInsets abstraction, which
    // only exposes edge insets, not the cutout's own position.
    var cutoutRectPx by remember { mutableStateOf<android.graphics.Rect?>(null) }
    DisposableEffect(view) {
        fun refresh() { cutoutRectPx = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull() }
        refresh()
        val listener = View.OnApplyWindowInsetsListener { _, insets -> refresh(); insets }
        view.setOnApplyWindowInsetsListener(listener)
        onDispose { view.setOnApplyWindowInsetsListener(null) }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("camera-island-overlay")) {
        // Pane identity (task spec item 3): a fold seam means this is the inner display, and the
        // island lives on its right (Home) pane, same convention as island/RailIsland.kt's own
        // anchor. This composable is a root-level sibling of the whole screen (fillMaxSize with no
        // extra inset padding of its own), so window px == this Box's own px; no rootOrigin
        // correction is needed the way LauncherScreen.kt's inset-padded BoxWithConstraints does.
        val seam = LocalFoldSeam.current?.takeIf { it.splits(maxWidth.value) }
        val isCover = seam == null
        val paneStartDp = seam?.homeStartDp ?: 0f
        val paneWidthDp = maxWidth.value - paneStartDp

        val cutout = remember(cutoutRectPx, paneStartDp, paneWidthDp, density) {
            val r = cutoutRectPx
            if (r != null && r.width() > 0 && r.height() > 0) {
                CameraCutoutRect(
                    leftDp = r.left / density.density, topDp = r.top / density.density,
                    widthDp = r.width() / density.density, heightDp = r.height() / density.density,
                )
            } else cameraIslandFallbackCutout(paneStartDp, paneWidthDp)
        }

        // Tom's rule (17. 9.): "jen aktuální běžící věci" — the island shows only what is
        // actually running right now (cameraIslandLiveItems in CameraIslandGeometry.kt), never a
        // finished download or a resumable-but-idle media session the rail can afford to keep.
        // Re-evaluated at least once a second (not just when [items] itself changes reference) so
        // a paused session's 30s grace / a finished countdown's 60s grace actually expire close to
        // on time rather than waiting for the next unrelated notification update.
        var liveFilterTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { liveFilterTickMs = System.currentTimeMillis(); delay(1_000L) } }
        val debugItems = if (BuildConfig.DEBUG) CameraIslandDebugInjector.items.value else emptyList()
        val liveItems = remember(items, debugItems, liveFilterTickMs) {
            val wallNowMs = System.currentTimeMillis()
            // cameraIslandLiveItems's own doc: chronometerBase/nowMs are wall-clock throughout this
            // module, but IslandMedia.positionUpdatedAtMs is natively SystemClock.elapsedRealtime() —
            // normalized here, the one Android-clock read in this file, so the pure filter itself
            // never has to reconcile two clocks.
            val elapsedNowMs = SystemClock.elapsedRealtime()
            val normalized = (items + debugItems).map { item ->
                val media = item.media ?: return@map item
                item.copy(media = media.copy(positionUpdatedAtMs = wallNowMs - (elapsedNowMs - media.positionUpdatedAtMs)))
            }
            cameraIslandLiveItems(normalized, wallNowMs)
        }

        // "The card keeps drawing the last item through its own dismiss/collapse animation" —
        // same reasoning as island/RailIsland.kt's IslandExpandedOverlay `lastItem` cache, just
        // keyed so it survives both of a MINIMAL pill's two slots, not only one.
        val cache = remember { mutableStateMapOf<String, IslandItem>() }
        SideEffect { liveItems.forEach { cache[it.key] = it } }

        val itemKeys = remember(liveItems) { liveItems.map { it.key } }
        var expandedKey by remember { mutableStateOf<String?>(null) }
        var expandedAtMs by remember { mutableStateOf(0L) }
        var minimalFrontIndex by remember { mutableStateOf(0) }

        LaunchedEffect(itemKeys, expandedKey) {
            if (cameraIslandShouldAutoCollapse(expandedKey, itemKeys)) expandedKey = null
        }
        if (BuildConfig.DEBUG) {
            val expandRequest = CameraIslandDebugInjector.expandRequest.value
            LaunchedEffect(expandRequest) {
                if (expandRequest > 0) { withFrameNanos { }; withFrameNanos { }
                    val wanted = CameraIslandDebugInjector.expandKey
                    (if (wanted != null && wanted in itemKeys) wanted else itemKeys.firstOrNull())
                        ?.let { expandedKey = it; expandedAtMs = System.currentTimeMillis() } }
            }
        }
        LaunchedEffect(expandedKey) {
            if (expandedKey != null) {
                val openedAt = expandedAtMs
                delay(CAMERA_ISLAND_IDLE_TIMEOUT_MS)
                if (expandedAtMs == openedAt) expandedKey = null
            }
        }

        val visualState = cameraIslandVisualState(itemKeys, expandedKey)
        val frontKey: String? = when (visualState) {
            CameraIslandVisualState.HIDDEN -> null
            CameraIslandVisualState.EXPANDED -> expandedKey
            CameraIslandVisualState.MINIMAL -> itemKeys.getOrNull(minimalFrontIndex.coerceIn(0, 1)) ?: itemKeys.firstOrNull()
            CameraIslandVisualState.COMPACT -> itemKeys.firstOrNull()
        }
        val backKey: String? = if (visualState == CameraIslandVisualState.MINIMAL)
            itemKeys.getOrNull(cameraIslandSwitchedIndex(minimalFrontIndex.coerceIn(0, 1))) else null
        val frontItem = frontKey?.let { cache[it] }
        val backItem = backKey?.let { cache[it] }
        val hasMedia = frontItem?.media != null

        // Tom's 17. 9. correction: the compact/minimal trailing value ("4 min", "12:34", "03:21")
        // must never wrap — measure its actual width and let the pill grow to fit it (Uber
        // "4 min" -> pill ~155dp on the cover) instead of hugging a fixed floor. Only a text value
        // is measured; a media equalizer/progress ring has no text and keeps its own fixed width
        // (cameraIslandContentFor's own null fallback).
        val textMeasurer = rememberTextMeasurer()
        val trailingLabel = frontItem?.let { cameraIslandCompactValueLabel(it, liveFilterTickMs) }
        val measuredTrailingWidthDp = remember(trailingLabel, density) {
            trailingLabel?.let { label ->
                val result = textMeasurer.measure(text = label, style = CameraIslandTrailingValueStyle)
                with(density) { result.size.width.toDp().value }
            }?.let { maxOf(it, CAMERA_ISLAND_TRAILING_SLOT_DP) }
        }

        val expandedContentHeightDp = cameraIslandExpandedHeightFor(frontItem)
        val targetRect = when (visualState) {
            CameraIslandVisualState.EXPANDED -> cameraIslandExpandedRect(cutout, paneStartDp, paneWidthDp, isCover, expandedContentHeightDp)
            CameraIslandVisualState.HIDDEN -> cameraIslandNoneRect(cutout)
            else -> cameraIslandRect(cutout, cameraIslandContentFor(visualState, measuredTrailingWidthDp))
        }

        // One morph Animatable drives every state transition (task spec item 4): on a state/front/
        // back change, whatever was last settled becomes "from" and the new target becomes "to" —
        // deliberately not the live mid-flight rect, so an interrupted transition restarts cleanly
        // rather than needing a second layer of bookkeeping for a rare, human-paced interaction.
        val transitionKey = remember(visualState, frontKey, backKey, hasMedia) { CameraIslandTransitionKey(visualState, frontKey, backKey, hasMedia) }
        var toSnapshot by remember { mutableStateOf(CameraIslandSnapshot(transitionKey, targetRect)) }
        var fromSnapshot by remember { mutableStateOf(toSnapshot) }
        val morph = remember { Animatable(1f) }
        LaunchedEffect(transitionKey, targetRect) {
            if (transitionKey != toSnapshot.key || targetRect != toSnapshot.rect) {
                val previousTo = toSnapshot
                fromSnapshot = previousTo
                toSnapshot = CameraIslandSnapshot(transitionKey, targetRect)
                morph.snapTo(0f)
                val growing = cameraIslandIsGrowing(fromSnapshot.rect, toSnapshot.rect)
                Log.i("FoldDuoIsland", "morph ${fromSnapshot.key.state}->${toSnapshot.key.state} growing=$growing from=${fromSnapshot.rect} to=${toSnapshot.rect}")
                morph.animateTo(1f, cameraIslandMorphSpec(reduceMotion, growing))
            }
        }

        // Pane identity + fold (task spec item 3): a fresh composition (this activity's own
        // relaunch onto its new panel — no cross-process animation is possible) pops in rather
        // than appearing instantly.
        val arrival = remember { Animatable(0f) }
        LaunchedEffect(Unit) { arrival.animateTo(1f, cameraIslandSpec(reduceMotion, CAMERA_ISLAND_ARRIVAL_SPRING)) }

        // Alert pulse (task spec item 4): a signature deliberately excluding the chronometer's own
        // ticking text (title/text/progress only) so a running timer does not "alert" every second.
        val alertSignature = frontItem?.let { "${it.title} ${it.text} ${it.progress} ${it.keyValue}" }
        var previousAlertSignature by remember { mutableStateOf(alertSignature) }
        val alertPop = remember { Animatable(1f) }
        LaunchedEffect(frontKey, alertSignature) {
            if (cameraIslandAlertTriggered(previousAlertSignature, alertSignature) && !reduceMotion) {
                alertPop.snapTo(CAMERA_ISLAND_ALERT_PEAK_SCALE)
                alertPop.animateTo(1f, CAMERA_ISLAND_ALERT_SPRING)
                Haptics.play(context, HapticEvent.ISLAND_EXPAND)
            }
            previousAlertSignature = alertSignature
        }

        // Minimize-into-island (task spec item 4): a key that just appeared flies its own app icon
        // in from the screen centre (no dock/Home-tile bounds are wired into this overlay — see
        // this file's own doc — "if the app is placed" per the task spec is therefore always the
        // "else" branch today) into the compact slot, scaling in from 0.6 (task spec item 4).
        val flights = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
        var previousKeySet by remember { mutableStateOf<Set<String>?>(null) }
        LaunchedEffect(itemKeys) {
            val currentSet = itemKeys.toSet()
            val previous = previousKeySet
            if (previous != null && !reduceMotion) {
                (currentSet - previous).forEach { key ->
                    val anim = Animatable(0f)
                    flights[key] = anim
                    scope.launch {
                        anim.animateTo(1f, CAMERA_ISLAND_FLIGHT_SPRING)
                        flights.remove(key)
                    }
                }
            }
            previousKeySet = currentSet
        }

        fun dismissExpanded() { Haptics.play(context, HapticEvent.ISLAND_DISMISS); expandedKey = null }
        fun openItem(item: IslandItem?) { launchCameraIslandIntent(context, item?.contentIntent); dismissExpanded() }
        fun runAction(action: IslandAction) { launchCameraIslandIntent(context, action.intent); dismissExpanded() }

        // Tap-away scrim: only present (and only intercepts touches) while a card is expanded.
        if (visualState == CameraIslandVisualState.EXPANDED) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { dismissExpanded() } }.testTag("camera-island-scrim"))
        }

        var pillWidthPx by remember { mutableFloatStateOf(0f) }
        var pillHeightPx by remember { mutableFloatStateOf(0f) }
        val dragOffsetX = remember { Animatable(0f) }
        val dragOffsetY = remember { Animatable(0f) }
        val pressed = remember { MutableInteractionSource() }
        val isPressed by pressed.collectIsPressedAsState()

        // Task spec item 4/second correction: with nothing live before AND after this frame there
        // is nothing to draw at all — no black pill, no hairline, just the bare cutout. Any other
        // transition (including growing out of / shrinking into HIDDEN) still renders, faded by
        // cameraIslandContainerAlpha.
        val showContainer = fromSnapshot.key.state != CameraIslandVisualState.HIDDEN || toSnapshot.key.state != CameraIslandVisualState.HIDDEN
        if (showContainer) {
            Box(Modifier
                .layout { measurable, _ ->
                    val r = cameraIslandMorphRect(morph.value, fromSnapshot.rect, toSnapshot.rect)
                    val w = r.widthDp.dp.roundToPx().coerceAtLeast(0)
                    val h = r.heightDp.dp.roundToPx().coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val r = cameraIslandMorphRect(morph.value, fromSnapshot.rect, toSnapshot.rect)
                    translationX = r.leftDp.dp.toPx() + dragOffsetX.value
                    translationY = r.topDp.dp.toPx() + dragOffsetY.value
                    shape = RoundedCornerShape(cameraIslandCornerDp(r.heightDp).dp)
                    clip = true
                    val containerAlpha = cameraIslandContainerAlpha(fromSnapshot.key.state, toSnapshot.key.state, morph.value)
                    val pressScale = if (isPressed) CAMERA_ISLAND_PRESS_SCALE else 1f
                    val scale = arrival.value.coerceAtLeast(0f) * alertPop.value * pressScale
                    scaleX = scale; scaleY = scale
                    alpha = arrival.value.coerceIn(0f, 1f) * containerAlpha
                }
                .drawWithCache {
                    val hairline = 1.dp.toPx()
                    val inset = hairline / 2f
                    onDrawBehind {
                        drawRect(Color.Black)
                        val r = cameraIslandMorphRect(morph.value, fromSnapshot.rect, toSnapshot.rect)
                        val corner = cameraIslandCornerDp(r.heightDp).dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = .10f), Offset(inset, inset), Size(size.width - hairline, size.height - hairline),
                            CornerRadius((corner - inset).coerceAtLeast(0f)), Stroke(hairline))
                    }
                }
                .onSizeChangedPx { w, h -> pillWidthPx = w; pillHeightPx = h }
                .combinedClickable(
                    interactionSource = pressed,
                    indication = null,
                    onClick = {
                        when (visualState) {
                            CameraIslandVisualState.COMPACT, CameraIslandVisualState.MINIMAL -> {
                                expandedKey = frontKey; expandedAtMs = System.currentTimeMillis()
                                Haptics.play(context, HapticEvent.ISLAND_EXPAND)
                            }
                            CameraIslandVisualState.EXPANDED -> openItem(frontItem)
                            CameraIslandVisualState.HIDDEN -> Unit
                        }
                    },
                    onLongClick = {
                        val action = frontItem?.actions?.firstOrNull()
                        if (action != null) runAction(action) else openItem(frontItem)
                    },
                    role = Role.Button,
                )
                .pointerInput(visualState, frontKey, backKey) {
                    if (visualState == CameraIslandVisualState.MINIMAL && backKey != null) {
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = {
                                val velocity = tracker.calculateVelocity().x
                                val switched = cameraIslandSwipeSwitched(dragOffsetX.value, pillWidthPx, velocity)
                                scope.launch {
                                    dragOffsetX.animateTo(0f, cameraIslandSpec(reduceMotion, CAMERA_ISLAND_COLLAPSE_SPRING))
                                    if (switched) minimalFrontIndex = cameraIslandSwitchedIndex(minimalFrontIndex.coerceIn(0, 1))
                                }
                            },
                            onDragCancel = { scope.launch { dragOffsetX.animateTo(0f, cameraIslandSpec(reduceMotion, CAMERA_ISLAND_COLLAPSE_SPRING)) } },
                        ) { change, amount -> change.consume(); tracker.addPosition(change.uptimeMillis, change.position); scope.launch { dragOffsetX.snapTo(dragOffsetX.value + amount) } }
                    } else if (visualState == CameraIslandVisualState.EXPANDED) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = {
                                val velocity = tracker.calculateVelocity().y
                                val collapsed = dragOffsetY.value < 0f && cameraIslandSwipeCollapsed(dragOffsetY.value, pillHeightPx, velocity)
                                scope.launch {
                                    if (collapsed) { dragOffsetY.animateTo(-pillHeightPx, cameraIslandSpec(reduceMotion, CAMERA_ISLAND_COLLAPSE_SPRING)); dismissExpanded(); dragOffsetY.snapTo(0f) }
                                    else dragOffsetY.animateTo(0f, cameraIslandSpec(reduceMotion, CAMERA_ISLAND_COLLAPSE_SPRING))
                                }
                            },
                            onDragCancel = { scope.launch { dragOffsetY.animateTo(0f, cameraIslandSpec(reduceMotion, CAMERA_ISLAND_COLLAPSE_SPRING)) } },
                        ) { change, amount -> if (amount < 0f) change.consume(); tracker.addPosition(change.uptimeMillis, change.position); scope.launch { dragOffsetY.snapTo((dragOffsetY.value + amount).coerceAtMost(0f)) } }
                    }
                }
                .semantics { contentDescription = cameraIslandContentDescription(visualState, frontItem) }
                .testTag("camera-island-pill")) {
                val ticking = frontItem?.chronometerBase != null || backItem?.chronometerBase != null
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(ticking) {
                    while (ticking) { now = System.currentTimeMillis(); delay(1_000L - System.currentTimeMillis() % 1_000L) }
                }
                // Outgoing content, fading + shrinking out over the morph's first 35% (task spec
                // item 4: cameraIslandOutgoingContentAlpha/Scale, mirrored from island/IslandCardMotion.kt).
                if (fromSnapshot.key != toSnapshot.key) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val t = cameraIslandOutgoingContentScale(morph.value)
                        scaleX = t; scaleY = t
                        alpha = cameraIslandOutgoingContentAlpha(morph.value)
                    }) {
                        CameraIslandContentBySlot(fromSnapshot.key.state, cache[fromSnapshot.key.frontKey], now, cutout, fromSnapshot.rect, ::runAction)
                    }
                }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    if (fromSnapshot.key != toSnapshot.key) {
                        val t = cameraIslandIncomingContentScale(morph.value)
                        scaleX = t; scaleY = t
                        alpha = cameraIslandIncomingContentAlpha(morph.value)
                    } else { scaleX = 1f; scaleY = 1f; alpha = 1f }
                }) {
                    CameraIslandContentBySlot(visualState, frontItem, now, cutout, toSnapshot.rect, ::runAction)
                }
            }
        }

        // Minimal's second activity: a detached circle beside the primary pill (task spec item 2),
        // crossfaded the same way as the primary content when it appears/disappears.
        val fromSecondaryRect = fromSnapshot.key.backKey?.let { cameraIslandMinimalSecondaryRect(fromSnapshot.rect) }
        val toSecondaryRect = toSnapshot.key.backKey?.let { cameraIslandMinimalSecondaryRect(toSnapshot.rect) }
        if (fromSnapshot.key != toSnapshot.key) {
            fromSecondaryRect?.let { rect ->
                CameraIslandSecondaryCircle(cache[fromSnapshot.key.backKey], rect, cameraIslandOutgoingContentAlpha(morph.value) * arrival.value) {}
            }
        }
        toSecondaryRect?.let { rect ->
            val alpha = if (fromSnapshot.key != toSnapshot.key) cameraIslandIncomingContentAlpha(morph.value) else 1f
            CameraIslandSecondaryCircle(cache[toSnapshot.key.backKey], rect, alpha * arrival.value) {
                minimalFrontIndex = cameraIslandSwitchedIndex(minimalFrontIndex.coerceIn(0, 1))
                Haptics.play(context, HapticEvent.ISLAND_DISMISS)
            }
        }

        // Flying icon ghosts (task spec item 4).
        val screenCenterPx = Offset(with(density) { maxWidth.toPx() } / 2f, with(density) { maxHeight.toPx() } / 2f)
        val destRect = cameraIslandRect(cutout, CameraIslandContent.COMPACT)
        val destCenterPx = with(density) {
            Offset((destRect.leftDp + CAMERA_ISLAND_LEADING_SLOT_DP / 2f).dp.toPx(), (destRect.topDp + destRect.heightDp / 2f).dp.toPx())
        }
        flights.forEach { (key, anim) ->
            FlightGhost(anim, cache[key]?.appIcon, screenCenterPx, destCenterPx)
        }
    }
}

/** [Modifier.onGloballyPositioned]-free size probe: cheap width/height in px without pulling in a whole `LayoutCoordinates`. */
private fun Modifier.onSizeChangedPx(onSize: (Float, Float) -> Unit): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        onSize(placeable.width.toFloat(), placeable.height.toFloat())
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    })

private fun cameraIslandContentDescription(state: CameraIslandVisualState, item: IslandItem?): String = when (state) {
    CameraIslandVisualState.HIDDEN -> "Camera"
    else -> listOfNotNull(cameraIslandKindLabel(item?.kind), item?.title?.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { "Camera island" }
}

/** The camera island's own compact/minimal trailing-slot label: [IslandItem.keyValue] (an ETA,
 * "Kurýr", …) takes priority over the generic `island/IslandItem.kt#compactIslandLabel` title
 * fallback, which is tuned for the rail's own pill, not this one. */
private fun cameraIslandCompactValueLabel(item: IslandItem, now: Long): String? {
    item.chronometerBase?.let { return chronometerText(it, now, item.countDown) }
    if (item.kind == IslandKind.MEDIA) return null
    item.keyValue?.let { return it }
    item.progress?.let { return "${(it * 100f).toInt().coerceIn(0, 100)}%" }
    return item.title.takeIf { it.isNotBlank() }
}

/** SF-Pro-ish tabular numerals for chronometers/ETAs (task spec item 1: `FontFeatureSettings "tnum"`). */
private val CameraIslandTabularStyle = TextStyle(fontFeatureSettings = "tnum")

/** The compact/minimal trailing value's own text style — shared verbatim between the `TextMeasurer` call that sizes the pill and the `Text` that renders inside it, so the two never disagree (mock: "15 sp semibold tabular"). */
private val CameraIslandTrailingValueStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
private fun CameraIslandContentBySlot(state: CameraIslandVisualState, front: IslandItem?, now: Long, cutout: CameraCutoutRect, rect: CameraIslandRect, onAction: (IslandAction) -> Unit) {
    when (state) {
        CameraIslandVisualState.HIDDEN -> Unit
        CameraIslandVisualState.COMPACT, CameraIslandVisualState.MINIMAL -> {
            // The trailing box's own width is baked into `rect` already (CameraIslandOverlay's
            // TextMeasurer call built it) — recovered here rather than re-measured, so a settled
            // "from"/"to" snapshot mid-transition renders with exactly the width its own rect used.
            val trailingWidthDp = cameraIslandTrailingContentWidthDp(rect, cutout)
            CameraIslandCompactContent(front, now, trailingWidthDp)
        }
        CameraIslandVisualState.EXPANDED -> CameraIslandExpandedContent(front, now, rect, onAction)
    }
}

/** Leading icon 12/24/10 from the pill's own left edge to the cutout, trailing value 10/[trailingWidthDp]/14 from the cutout to the right edge (mock item 1) — the cutout itself is the empty [cameraIslandPillCameraZoneWidthDp] gap between the two slots, [cameraIslandRect]'s own construction guarantees this. [trailingWidthDp] never wraps the value (Tom, 17. 9.): it is exactly the measured width the pill itself grew to fit. */
@Composable
private fun CameraIslandCompactContent(item: IslandItem?, now: Long, trailingWidthDp: Float) {
    item ?: return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = CAMERA_ISLAND_LEADING_INSET_DP.dp).width(CAMERA_ISLAND_LEADING_ICON_DP.dp).fillMaxHeight().align(Alignment.CenterStart), contentAlignment = Alignment.Center) {
            CameraIslandLeadingIcon(item, CAMERA_ISLAND_LEADING_ICON_DP.dp)
        }
        Box(Modifier.padding(end = CAMERA_ISLAND_TRAILING_INSET_DP.dp).width(trailingWidthDp.dp).fillMaxHeight().align(Alignment.CenterEnd), contentAlignment = Alignment.Center) {
            val label = cameraIslandCompactValueLabel(item, now)
            val valueColor = when (item.kind) {
                IslandKind.TIMER -> Color(CAMERA_ISLAND_ORANGE_ARGB)
                IslandKind.CALL -> Color(CAMERA_ISLAND_GREEN_ARGB)
                else -> Color.White
            }
            when {
                label != null -> Text(label, color = valueColor, maxLines = 1, softWrap = false, style = CameraIslandTrailingValueStyle)
                item.kind == IslandKind.MEDIA -> CameraIslandEqualizer(item.media?.playing == true, item.accentColor?.let { Color(it) } ?: Color(CAMERA_ISLAND_MUSIC_ARGB), 20.dp)
                item.progress != null -> CameraIslandProgressRing(item.progress, 18.dp)
                else -> Unit
            }
        }
    }
}

/** Compact/minimal leading icon per kind (task item 1): call = green circle, timer = orange rounded-6dp square with a glyph, media = artwork (rounded 6dp, gradient placeholder without one), transport = white rounded-6dp square with the app icon; anything else keeps the plain glyph. */
@Composable
private fun CameraIslandLeadingIcon(item: IslandItem, size: Dp) {
    when (item.kind) {
        IslandKind.CALL -> Box(Modifier.size(size).clip(CircleShape).background(Color(CAMERA_ISLAND_GREEN_ARGB)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size(size * .58f))
        }
        IslandKind.TIMER -> Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Color(CAMERA_ISLAND_ORANGE_ARGB)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Timer, null, tint = Color.Black, modifier = Modifier.size(size * .58f))
        }
        IslandKind.MEDIA -> CameraIslandArtwork(item, size, corner = 6.dp)
        IslandKind.TRANSPORT -> Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            CameraIslandGlyph(item, size * .7f, fallbackTint = Color.Black)
        }
        else -> CameraIslandGlyph(item, size)
    }
}

/** Minimal's own second activity (task spec item 2): a detached circle drawn as a sibling of the primary pill, in the same window-dp space. */
@Composable
private fun CameraIslandSecondaryCircle(item: IslandItem?, rect: CameraIslandRect, alpha: Float, onClick: () -> Unit) {
    item ?: return
    Box(Modifier
        .layout { measurable, _ ->
            val w = rect.widthDp.dp.roundToPx().coerceAtLeast(0)
            val h = rect.heightDp.dp.roundToPx().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .graphicsLayer {
            translationX = rect.leftDp.dp.toPx()
            translationY = rect.topDp.dp.toPx()
            this.alpha = alpha.coerceIn(0f, 1f)
            shape = CircleShape
            clip = true
        }
        .background(Color.Black)
        .combinedClickable(onClick = onClick, role = Role.Button)
        .testTag("camera-island-secondary"), contentAlignment = Alignment.Center) {
        CameraIslandGlyph(item, 18.dp)
    }
}

/** 3 bars, 4dp wide with 3dp gaps (mock `.eq`), tinted with [tint] (the media accent, or the mock's own `--music` default), animating only while [playing] (`islandEqualizerBarLevel`'s own static level when paused). */
@Composable
private fun CameraIslandEqualizer(playing: Boolean, tint: Color, height: Dp) {
    val transition = rememberInfiniteTransition(label = "camera-island-eq")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ISLAND_EQUALIZER_PERIOD_MS.toInt(), easing = LinearEasing)),
        label = "phase",
    )
    val elapsedMs = (phase * ISLAND_EQUALIZER_PERIOD_MS).toLong()
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(CAMERA_ISLAND_EQUALIZER_BAR_GAP_DP.dp), verticalAlignment = Alignment.Bottom) {
        repeat(ISLAND_EQUALIZER_BAR_COUNT) { index ->
            val level = islandEqualizerBarLevel(index, elapsedMs, playing)
            Box(Modifier.width(CAMERA_ISLAND_EQUALIZER_BAR_WIDTH_DP.dp).fillMaxHeight(level.coerceIn(0.15f, 1f)).background(tint, RoundedCornerShape(2.dp)))
        }
    }
}

// --- Expanded card (17. 9. night mock rework, B61 phase 3) -----------------------------------
//
// Two mock-defined layouts, chosen by kind (see [cameraIslandExpandedHeightForKind]): "below"
// (timer/call/navigation/progress/workout/other — content sits below the cutout, y52..108) and
// "row1" (transport/media — the first row obtées the cutout via the fixed 188/51/rest grid). Both
// are absolutely positioned (`Modifier.offset`) inside one `Box(fillMaxSize())`, mirroring the
// mock's own CSS `position:absolute` rows rather than a sequential Column — the mock's rows sit at
// fixed y-offsets from the card's own top, not stacked one after another.

/** Per-kind expanded layout (task item 2). */
@Composable
private fun CameraIslandExpandedContent(item: IslandItem?, now: Long, cardRect: CameraIslandRect, onAction: (IslandAction) -> Unit) {
    item ?: return
    Box(
        if (item.kind == IslandKind.MEDIA) {
            val tint = item.accentColor?.let { Color(it) } ?: Color(CAMERA_ISLAND_MUSIC_ARGB)
            Modifier.fillMaxSize().drawWithCache {
                val brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = .28f), Color.Black),
                    center = Offset(size.width * .12f, size.height * .2f),
                    radius = maxOf(size.width, size.height) * .9f,
                )
                onDrawBehind { drawRect(brush) }
            }
        } else Modifier.fillMaxSize().background(Color.Black),
    ) {
        when (item.kind) {
            IslandKind.MEDIA -> CameraIslandMediaExpanded(item, now, cardRect)
            IslandKind.TRANSPORT -> CameraIslandTransportExpanded(item, now, cardRect, onAction)
            IslandKind.TIMER -> CameraIslandTimerExpanded(item, now, onAction)
            IslandKind.CALL -> CameraIslandCallExpanded(item, now, onAction)
            IslandKind.NAVIGATION, IslandKind.PROGRESS, IslandKind.WORKOUT, IslandKind.OTHER -> CameraIslandBelowSimpleExpanded(item, now)
        }
    }
}

/** "below" layout (task item 2, timer/call/navigation/progress/generic): one row from y52 to y108, insets 20/16, space-between (mock `.below`) — entirely below the cutout, no exclusion avoidance needed. */
@Composable
private fun CameraIslandBelowRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = CAMERA_ISLAND_BELOW_LEFT_INSET_DP.dp, end = CAMERA_ISLAND_BELOW_RIGHT_INSET_DP.dp)
            .offset(y = CAMERA_ISLAND_BELOW_TOP_DP.dp)
            .height(CAMERA_ISLAND_BELOW_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box { left() }
        Box { right() }
    }
}

/** "row1" layout (task item 2, transport/media): the fixed 188/51/rest grid at y12-68 that obtées the cutout (mock `.row1`). */
@Composable
private fun CameraIslandRow1(cardRect: CameraIslandRect, left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    val rightColWidth = cameraIslandRow1RightColWidthDp(cardRect.widthDp)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = CAMERA_ISLAND_ROW1_INSET_DP.dp)
            .offset(y = CAMERA_ISLAND_ROW1_TOP_DP.dp)
            .height(CAMERA_ISLAND_ROW1_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(CAMERA_ISLAND_ROW1_LEFT_COL_DP.dp), verticalAlignment = Alignment.CenterVertically, content = left)
        Spacer(Modifier.width(CAMERA_ISLAND_ROW1_CAMERA_GAP_DP.dp))
        Row(Modifier.width(rightColWidth.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

/** 52dp circle action button (task item 2 "below" layout: timer cancel/pause, call mute/end). */
@Composable
private fun CameraIslandCircleButton(background: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(CAMERA_ISLAND_CIRCLE_BUTTON_DIAMETER_DP.dp).clip(CircleShape).background(background)
            .combinedClickable(onClick = onClick, role = Role.Button)
            .testTag("camera-island-circle-button"),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CameraIslandMediaExpanded(item: IslandItem, now: Long, cardRect: CameraIslandRect) {
    val media = item.media
    val accent = item.accentColor?.let { Color(it) } ?: Color(CAMERA_ISLAND_MUSIC_ARGB)
    CameraIslandRow1(cardRect,
        left = {
            CameraIslandArtwork(item, CAMERA_ISLAND_MEDIA_ARTWORK_DP.dp, corner = CAMERA_ISLAND_MEDIA_ARTWORK_CORNER_DP.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.text.isNotBlank()) Text(item.text, color = Color.White.copy(alpha = .62f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        right = { CameraIslandEqualizer(media?.playing == true, accent, 20.dp) },
    )
    val position = media?.let { extrapolatedPositionMs(it.positionMs, it.positionUpdatedAtMs, now, it.playbackSpeed, it.playing, it.durationMs) } ?: 0L
    val duration = media?.durationMs ?: 0L
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = CAMERA_ISLAND_MEDIA_BAR_TOP_DP.dp)) {
        if (duration > 0L) LinearProgressIndicator({ position / duration.toFloat() }, Modifier.fillMaxWidth().height(CAMERA_ISLAND_MEDIA_BAR_HEIGHT_DP.dp), color = Color.White, trackColor = Color.White.copy(alpha = .22f))
        else LinearProgressIndicator(Modifier.fillMaxWidth().height(CAMERA_ISLAND_MEDIA_BAR_HEIGHT_DP.dp), color = Color.White.copy(alpha = .4f), trackColor = Color.White.copy(alpha = .15f))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = CAMERA_ISLAND_MEDIA_TIMES_TOP_DP.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mediaTimeLabel(position), color = Color.White.copy(alpha = .55f), fontSize = 12.sp, style = CameraIslandTabularStyle)
        Text(mediaRemainingLabel(position, duration), color = Color.White.copy(alpha = .55f), fontSize = 12.sp, style = CameraIslandTabularStyle)
    }
    val controls = media?.controller?.transportControls
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = CAMERA_ISLAND_MEDIA_CONTROLS_TOP_DP.dp).height(CAMERA_ISLAND_MEDIA_CONTROL_GLYPH_DP.dp)) {
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(CAMERA_ISLAND_MEDIA_CONTROL_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(CAMERA_ISLAND_MEDIA_CONTROL_GLYPH_DP.dp)
                .combinedClickable(onClick = { controls?.skipToPrevious() }, role = Role.Button))
            Icon(if (media?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(CAMERA_ISLAND_MEDIA_CONTROL_GLYPH_DP.dp)
                .combinedClickable(onClick = { if (media?.playing == true) controls?.pause() else controls?.play() }, role = Role.Button))
            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(CAMERA_ISLAND_MEDIA_CONTROL_GLYPH_DP.dp)
                .combinedClickable(onClick = { controls?.skipToNext() }, role = Role.Button))
        }
        Icon(Icons.Rounded.Cast, null, tint = Color.White.copy(alpha = .7f),
            modifier = Modifier.align(Alignment.CenterEnd).size(CAMERA_ISLAND_MEDIA_OUTPUT_GLYPH_DP.dp))
    }
}

@Composable
private fun CameraIslandTransportExpanded(item: IslandItem, now: Long, cardRect: CameraIslandRect, onAction: (IslandAction) -> Unit) {
    CameraIslandRow1(cardRect,
        left = {
            Box(Modifier.size(CAMERA_ISLAND_TRANSPORT_ICON_DP.dp).clip(RoundedCornerShape(CAMERA_ISLAND_TRANSPORT_ICON_CORNER_DP.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                CameraIslandGlyph(item, CAMERA_ISLAND_TRANSPORT_ICON_DP.dp * .6f, fallbackTint = Color.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(item.title.ifBlank { "Kurýr je na cestě" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.text.isNotBlank()) Text(item.text, color = Color.White.copy(alpha = .62f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        right = {
            val value = item.keyValue ?: item.etaMs?.let { chronometerText(it, now, true) }
            val (bigPart, unitPart) = value?.let { cameraIslandSplitEtaValue(it) } ?: ("" to "")
            Column(horizontalAlignment = Alignment.End) {
                if (bigPart.isNotBlank()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        // "4" + "min" on one line, never wrapping (Tom, 17. 9.).
                        Text(bigPart, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02f).em,
                            style = CameraIslandTabularStyle, maxLines = 1, softWrap = false)
                        if (unitPart.isNotBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text(unitPart, color = Color.White.copy(alpha = .62f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
                // The stage caption lives in the stepper below; a second copy under the ETA
                // was clipped by the 56 dp header row (17. 9. screenshot), so it only shows
                // when there is no stepper to carry it.
                val caption = if (item.segments.isEmpty()) item.stageIndex?.let { item.segments.getOrNull(it) } else null
                if (caption != null) Text(caption, color = Color.White.copy(alpha = .55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
    if (item.segments.isNotEmpty()) CameraIslandSegmentStepper(item.segments, item.stageIndex ?: 0, cardRect.widthDp)
    if (item.actions.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CAMERA_ISLAND_ROW1_INSET_DP.dp).offset(y = CAMERA_ISLAND_CHIPS_TOP_DP.dp).height(CAMERA_ISLAND_CHIP_HEIGHT_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(CAMERA_ISLAND_CHIP_GAP_DP.dp),
        ) {
            item.actions.take(2).forEach { action -> CameraIslandActionChip(action, onAction, Modifier.weight(1f)) }
        }
    }
}

/** Transport's stage stepper (task item 2, mock "steps"): track 2dp 22% white from x24 to `cardWidth-24`, dots 12dp (30% white todo, white done, current white ×1.25 with an 18% white halo), labels 11sp 55% (current: white semibold) centred under each dot at y100. */
@Composable
private fun CameraIslandSegmentStepper(segments: List<String>, stageIndex: Int, cardWidthDp: Float) {
    val trackStart = CAMERA_ISLAND_STEPPER_TRACK_INSET_DP
    val trackWidth = (cameraIslandStepperTrackEndDp(cardWidthDp) - trackStart).coerceAtLeast(0f)
    val doneFraction = if (segments.size <= 1) 0f else (stageIndex.toFloat() / (segments.size - 1).toFloat()).coerceIn(0f, 1f)
    val trackCenterY = CAMERA_ISLAND_STEPPER_TOP_DP + 1f
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.offset(x = trackStart.dp, y = CAMERA_ISLAND_STEPPER_TOP_DP.dp).width(trackWidth.dp).height(2.dp).background(Color.White.copy(alpha = .22f)))
        Box(Modifier.offset(x = trackStart.dp, y = CAMERA_ISLAND_STEPPER_TOP_DP.dp).width((trackWidth * doneFraction).dp).height(2.dp).background(Color.White))
        segments.forEachIndexed { index, label ->
            val dotX = cameraIslandStepperDotXDp(index, segments.size, cardWidthDp)
            val done = index < stageIndex
            val current = index == stageIndex
            val dotSize = if (current) CAMERA_ISLAND_STEPPER_DOT_DP * CAMERA_ISLAND_STEPPER_CURRENT_SCALE else CAMERA_ISLAND_STEPPER_DOT_DP
            if (current) {
                val haloSize = dotSize + 2f * CAMERA_ISLAND_STEPPER_HALO_EXTRA_DP
                Box(Modifier.offset(x = (dotX - haloSize / 2f).dp, y = (trackCenterY - haloSize / 2f).dp).size(haloSize.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = .18f)))
            }
            Box(Modifier.offset(x = (dotX - dotSize / 2f).dp, y = (trackCenterY - dotSize / 2f).dp).size(dotSize.dp)
                .clip(CircleShape).background(if (done || current) Color.White else Color.White.copy(alpha = .3f)))
            Text(
                label, color = if (current) Color.White else Color.White.copy(alpha = .55f),
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp).absoluteOffset(x = (dotX - 38f).dp, y = CAMERA_ISLAND_STEPPER_LABEL_TOP_DP.dp),
            )
        }
    }
}

@Composable
private fun CameraIslandTimerExpanded(item: IslandItem, now: Long, onAction: (IslandAction) -> Unit) {
    CameraIslandBelowRow(
        left = {
            Column {
                Text("Časovač", color = Color.White.copy(alpha = .6f), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                item.chronometerBase?.let {
                    // No explicit lineHeight: 46 sp under a 42 sp face clipped the digits' bottom (Tom, 17. 9.).
                    Text(chronometerText(it, now, item.countDown), color = Color(CAMERA_ISLAND_ORANGE_ARGB), fontSize = 42.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.02f).em, style = CameraIslandTabularStyle, maxLines = 1, softWrap = false)
                }
            }
        },
        right = {
            // Task item 2: actions[0] = pause/resume (right, orange), actions[1] = cancel (left,
            // plain) — mock DOM order is cancel-then-pause, but the ACTION mapping is reversed.
            val pauseAction = item.actions.getOrNull(0)
            val cancelAction = item.actions.getOrNull(1)
            Row(horizontalArrangement = Arrangement.spacedBy(CAMERA_ISLAND_CIRCLE_BUTTON_GAP_DP.dp)) {
                cancelAction?.let { action ->
                    CameraIslandCircleButton(background = Color.White.copy(alpha = .14f), onClick = { onAction(action) }) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                pauseAction?.let { action ->
                    CameraIslandCircleButton(background = Color(CAMERA_ISLAND_ORANGE_ARGB).copy(alpha = .32f), onClick = { onAction(action) }) {
                        Icon(Icons.Rounded.Pause, null, tint = Color(CAMERA_ISLAND_ORANGE_ARGB), modifier = Modifier.size(22.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun CameraIslandCallExpanded(item: IslandItem, now: Long, onAction: (IslandAction) -> Unit) {
    CameraIslandBelowRow(
        left = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CameraIslandCallAvatar(item)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row {
                        Text("${item.text.ifBlank { "mobil" }} · ", color = Color.White.copy(alpha = .55f), fontSize = 13.sp)
                        item.chronometerBase?.let { Text(chronometerText(it, now, false), color = Color.White.copy(alpha = .55f), fontSize = 13.sp, style = CameraIslandTabularStyle) }
                    }
                }
            }
        },
        right = {
            // Task item 2: mute = actions[0], end/hang up = actions.last() — the debug call fake
            // always carries exactly two ("Ztlumit", "Zavěsit") so this never collides in practice.
            val muteAction = item.actions.getOrNull(0)
            val endAction = item.actions.lastOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(CAMERA_ISLAND_CIRCLE_BUTTON_GAP_DP.dp)) {
                muteAction?.let { action ->
                    CameraIslandCircleButton(background = Color.White.copy(alpha = .14f), onClick = { onAction(action) }) {
                        Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                endAction?.let { action ->
                    CameraIslandCircleButton(background = Color(CAMERA_ISLAND_RED_ARGB), onClick = { onAction(action) }) {
                        Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        },
    )
}

/** Call's own 44dp avatar (task item 2): [IslandItem.appIcon] if present, else a blue-grey gradient with up to two initials ([cameraIslandInitials]). */
@Composable
private fun CameraIslandCallAvatar(item: IslandItem) {
    val density = LocalDensity.current
    val px = with(density) { CAMERA_ISLAND_CALL_AVATAR_DP.dp.roundToPx() }
    val bitmap = remember(item.appIcon, px) { item.appIcon?.toCameraIslandBitmap(px) }
    Box(
        Modifier.size(CAMERA_ISLAND_CALL_AVATAR_DP.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF5B6B9A), Color(0xFF2D3A63)))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(cameraIslandInitials(item.title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "below" layout for navigation/progress/workout/generic (task item 2: "nothing else" — glyph + title + caption left, value right). */
@Composable
private fun CameraIslandBelowSimpleExpanded(item: IslandItem, now: Long) {
    CameraIslandBelowRow(
        left = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CameraIslandGlyph(item, 28.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    if (item.title.isNotBlank()) Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.text.isNotBlank()) Text(item.text, color = Color.White.copy(alpha = .55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        right = {
            val value = item.keyValue
                ?: item.chronometerBase?.let { chronometerText(it, now, item.countDown) }
                ?: item.progress?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" }
            if (value != null) Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = CameraIslandTabularStyle, maxLines = 1, softWrap = false)
        },
    )
}

/** 36dp tall, 18dp corner, 14% white fill, 15sp text (mock item 2 chips) — transport's own action row; [modifier] lets the caller give it equal-width siblings (`Modifier.weight(1f)`). */
@Composable
private fun CameraIslandActionChip(action: IslandAction, onAction: (IslandAction) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(CAMERA_ISLAND_CHIP_HEIGHT_DP.dp).clip(RoundedCornerShape(CAMERA_ISLAND_CHIP_CORNER_DP.dp)).background(Color.White.copy(alpha = .14f))
            .combinedClickable(onClick = { onAction(action) }, role = Role.Button)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(action.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("camera-island-action"))
    }
}

@Composable
private fun FlightGhost(progress: Animatable<Float, AnimationVector1D>, icon: Drawable?, startPx: Offset, endPx: Offset) {
    val density = LocalDensity.current
    val bitmap = remember(icon) { icon?.toCameraIslandBitmap(with(density) { CAMERA_ISLAND_LEADING_ICON_DP.dp.roundToPx() }) }
    Box(Modifier.size(CAMERA_ISLAND_LEADING_ICON_DP.dp)
        .graphicsLayer {
            val t = progress.value
            translationX = (startPx.x + (endPx.x - startPx.x) * t) - size.width / 2f
            translationY = (startPx.y + (endPx.y - startPx.y) * t) - size.height / 2f
            // Pops in from 0.6x, task spec item 4 ("leading icon scaling in from 0.6").
            val scale = 1.8f + (0.6f - 1.8f) * t
            scaleX = scale; scaleY = scale
            alpha = if (t < .85f) 1f else (1f - (t - .85f) / .15f).coerceIn(0f, 1f)
        }
        .clip(CircleShape)
        .background(Color.White.copy(alpha = .12f))
        .testTag("camera-island-flight"), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Media artwork, or a gradient placeholder tinted by the item's own accent (mock item 1: "media = artwork"). [corner] lets compact (6dp) and expanded (12dp) usages share this one composable. */
@Composable
private fun CameraIslandArtwork(item: IslandItem, size: Dp, corner: Dp = 12.dp) {
    val artwork = item.media?.artwork
    if (artwork != null) {
        val bitmap = remember(artwork) { artwork.asImageBitmap() }
        Image(bitmap, null, Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    } else {
        val accent = item.accentColor?.let { Color(it) } ?: Color(CAMERA_ISLAND_MUSIC_ARGB)
        Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(accent, accent.copy(alpha = .55f)))))
    }
}

/** The app-icon/kind-glyph fallback shared by every leading/trailing icon slot. [fallbackTint] lets a caller drawing this glyph over a light background (e.g. transport's white icon square) darken it instead of the usual white-on-black. */
@Composable
private fun CameraIslandGlyph(item: IslandItem, size: Dp, fallbackTint: Color = Color.White) {
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
    val app = remember(item.key, item.appIcon, px) { item.appIcon?.toCameraIslandBitmap(px) }
    if (app != null) Image(app, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentScale = ContentScale.Crop)
    else Icon(cameraIslandKindIcon(item.kind), null, tint = fallbackTint, modifier = Modifier.size(size))
}

@Composable
private fun CameraIslandProgressRing(progress: Float, size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = this.size.width * .12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(Color.White.copy(alpha = .25f), -90f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        drawArc(Color.White, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = stroke)
    }
}

private fun cameraIslandKindIcon(kind: IslandKind): ImageVector = when (kind) {
    IslandKind.CALL -> Icons.Rounded.Call
    IslandKind.NAVIGATION -> Icons.Rounded.Navigation
    IslandKind.TIMER -> Icons.Rounded.Timer
    IslandKind.MEDIA -> Icons.Rounded.MusicNote
    IslandKind.TRANSPORT -> Icons.Rounded.DirectionsCar
    IslandKind.PROGRESS -> Icons.Rounded.Downloading
    IslandKind.WORKOUT -> Icons.Rounded.FitnessCenter
    IslandKind.OTHER -> Icons.Rounded.Notifications
}

private fun cameraIslandKindLabel(kind: IslandKind?): String? = when (kind) {
    IslandKind.CALL -> "Call"; IslandKind.NAVIGATION -> "Navigation"; IslandKind.TIMER -> "Timer"
    IslandKind.MEDIA -> "Now playing"; IslandKind.TRANSPORT -> "Media"; IslandKind.PROGRESS -> "Progress"
    IslandKind.WORKOUT -> "Workout"; IslandKind.OTHER -> "Live update"; null -> null
}

private fun Drawable.toCameraIslandBitmap(px: Int): ImageBitmap? {
    if (px <= 0) return null
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/** Send a content / action intent from the foreground launcher; mirrors `island/RailIsland.kt`'s own `launchIsland` (kept as a private local copy so the two overlays stay independently editable). */
private fun launchCameraIslandIntent(context: Context, intent: PendingIntent?) {
    intent ?: return
    runCatching {
        @Suppress("DEPRECATION")
        val mode = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            Build.VERSION.SDK_INT >= 34 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> null
        }
        if (mode != null) {
            val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
            intent.send(context, 0, null, null, null, null, options.toBundle())
        } else intent.send()
    }
}
