@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package cz.pflanzer.foldduo

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.os.UserManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.graphicsLayer
import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldEffectCache
import cz.pflanzer.foldduo.continuum.FoldLine
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.island.IslandCardAnchor
import cz.pflanzer.foldduo.island.IslandExpandedOverlay
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.RAIL_ISLAND_RIGHT_PADDING_DP
import cz.pflanzer.foldduo.island.RailIsland
import cz.pflanzer.foldduo.status.StatusCardAnchor
import cz.pflanzer.foldduo.status.StatusCardOverlay
import cz.pflanzer.foldduo.notifications.HubGroup
import cz.pflanzer.foldduo.notifications.HubNotification
import cz.pflanzer.foldduo.notifications.HubPillEvent
import cz.pflanzer.foldduo.notifications.HubPillState
import cz.pflanzer.foldduo.notifications.HUB_PILL_AUTO_COLLAPSE_MS
import cz.pflanzer.foldduo.notifications.NotificationHub
import cz.pflanzer.foldduo.notifications.NotificationHubBadge
import cz.pflanzer.foldduo.notifications.NotificationHubSettings
import cz.pflanzer.foldduo.notifications.groupHubNotifications
import cz.pflanzer.foldduo.notifications.notificationBadge
import cz.pflanzer.foldduo.notifications.reduceHubPill
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.pflanzer.foldduo.predict.firstFreeHomeSlotIndex
import cz.pflanzer.foldduo.continuum.foldEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.desk.DeskOverlay
import cz.pflanzer.foldduo.standby.rememberLauncherStandByFrost
import cz.pflanzer.foldduo.standby.standByFrost
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal val Ink: Color
    @Composable get() = LocalDuoPalette.current.ink
internal val Glass: Color
    @Composable get() = LocalDuoPalette.current.glass

private fun findFreeWidgetIndex(layout: HomeLayout, page: Int, spanX: Int, spanY: Int): Int? {
    val blocked = layout.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
    for (row in 0..GRID_ROWS - spanY) for (column in 0..GRID_COLUMNS - spanX) {
        val cells = buildList {
            repeat(spanY) { y -> repeat(spanX) { x -> add(homeCellIndex(page, (row + y) * GRID_COLUMNS + column + x)) } }
        }
        if (cells.none { it in blocked || layout.slotAt(it) != null }) return cells.first()
    }
    return null
}

/**
 * B29 "Barvy z tapety": [wallpaperPalette] (default [WallpaperPalette.Default], today's fixed
 * look) blends into the Material scheme two ways — its base is Material You's own
 * `dynamicLightColorScheme`/`dynamicDarkColorScheme` (always available: minSdk 33 is well past
 * API 31) rather than the old fixed scheme below, and [WallpaperPalette.accent] is then lerped
 * into `primary`/`secondary`/`tertiary` so a wallpaper the system's own dynamic engine reads flatly
 * still shows through. [MainActivity] provides [wallpaperPalette] from [rememberWallpaperPalette]
 * and also exposes it via [LocalWallpaperPalette] for [Modifier.frostedGlass]'s veil default and
 * the "Wallpaper" widget appearance.
 */
@Composable
fun DuoTheme(dark: Boolean = false, wallpaperPalette: WallpaperPalette = WallpaperPalette.Default, content: @Composable () -> Unit) {
    val palette = if (dark) DarkDuoPalette else LightDuoPalette
    val context = LocalContext.current
    val dynamicBase = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val accent = Color(wallpaperPalette.accent)
    val scheme = dynamicBase.copy(
        primary = lerp(dynamicBase.primary, accent, .5f),
        secondary = lerp(dynamicBase.secondary, accent, .3f),
        tertiary = lerp(dynamicBase.tertiary, accent, .3f),
        onSurface = palette.ink,
        onSecondaryContainer = palette.ink,
    )
    CompositionLocalProvider(LocalDuoPalette provides palette, LocalWallpaperPalette provides wallpaperPalette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}


@Composable
fun LauncherScreen(
    state: LauncherState, model: LauncherModel, widgets: WidgetController, homeRequests: Int,
    onLaunch: (AppEntry) -> Unit, onMakeDefault: () -> Unit, onAppInfo: (AppEntry) -> Unit,
    isDefaultHome: Boolean, deviceStatus: DeviceStatus, onStatusMode: (Boolean) -> Unit, onWallpaperPreview: () -> Unit,
    searchRequests: Int = 0,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onGoogleSearch: (android.graphics.Rect?) -> Boolean = { false },
    appearance: AppearanceState = AppearanceState(),
    onAppearanceMode: (AppearanceMode) -> Unit = {},
    onAppearanceManual: (String, Double, Double) -> Unit = { _, _, _ -> },
    onAppearanceDeviceLocation: () -> Unit = {},
    onAppearanceClear: () -> Unit = {},
    onAppearanceMotionFrost: (Boolean) -> Unit = {},
    onAppearanceSystemFrost: (Boolean) -> Unit = {},
    onAppearanceOpenedOverride: (Boolean) -> Unit = {},
    onAppearanceHapticAtFlat: (Boolean) -> Unit = {},
    onAppearanceMorphFrostFactor: (Float) -> Unit = {},
    onAppearanceMorphTiltFactor: (Float) -> Unit = {},
    onAppearanceResetMorphPreview: () -> Unit = {},
    onAppearanceHingeSqueeze: (HingeSqueezeAction) -> Unit = {},
    showFirstRun: Boolean = false,
    onFinishFirstRun: () -> Unit = {},
    onShadeSetup: () -> Unit = {},
    /** Ranked Live Updates for the rail island (island/IslandNotificationListener.kt); empty without the listener grant. */
    islandItems: List<IslandItem> = emptyList(),
    /** B23: bumped by MainActivity.onResume; drives the launch-return "un-zoom" (see [LaunchReturnGuard]). */
    launchResumes: Int = 0,
    /** B34: everything the notification-listener grant currently sees as hub-eligible (not ongoing/media/silent); empty without the grant. */
    hubNotifications: List<HubNotification> = emptyList(),
    /** B35: today's ranked Suggestions (MainActivity's `predictor.suggestions`); empty when the setting is off or nothing is ranked yet. */
    suggestions: List<AppEntry> = emptyList(),
    /** "Nezobrazovat": MainActivity owns the block list (`PredictionController.block`), since [suggestions] itself is already recomputed there. */
    onSuggestionBlock: (AppEntry) -> Unit = {},
    /** B48 "Pant jako ovladač": live squeeze state from `PoseRepository`'s `PoseSnapshot`
     * (`squeezeSeq`/`squeezeDepthDeg`/`squeezeHeld`), collected by MainActivity. [squeezeHeld]
     * (gated on [AppearanceState.hingeSqueezeAction] == AppLibrary) drives [HingeSqueezeOverlay]. */
    squeezeDepthDeg: Float = 0f,
    squeezeHeld: Boolean = false,
    /** B48: bumped by MainActivity when a squeeze recognizes under `HingeSqueezeAction.NowBrief`
     * and Now Brief's launch intent did not resolve — the same "external trigger" shape as
     * [searchRequests], opening Spotlight instead. */
    spotlightRequests: Int = 0,
    /** B46 "Dvojice aplikací": MainActivity's `launchPair` — the zoom for `first` happens here like
     * any other icon launch; the split-screen sequence for `second` is MainActivity's job. */
    onLaunchPair: (PairEntry, android.graphics.Rect?) -> Unit = { _, _ -> },
) {
    // Declared first so every local function/lambda defined further down this (very long)
    // composable — openSpotlight, rotateStacksIfDue, the shared cycleStack callback — can close
    // over it without each needing its own `LocalContext.current`.
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf("") }
    var dockSlot by rememberSaveable { mutableIntStateOf(0) }
    var widgetSlot by rememberSaveable { mutableIntStateOf(0) }
    var widgetTargetIndex by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    var widgetExactTarget by rememberSaveable { mutableStateOf(false) }
    var widgetPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var widgetProfileSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    var widgetSession by remember { mutableStateOf<WidgetPickerSession?>(null) }
    var widgetPlacementMessage by remember { mutableStateOf<String?>(null) }
    // Edit ("jiggle") mode + its "+" add menu (2026-09-17 evening redesign): a long-press on empty
    // wallpaper used to open EmptySpaceActionSheet (emptyCellIndex) directly; it now enters edit
    // mode instead, and that sheet's three actions move into editAddMenuOpen's compact menu.
    var editModeActive by rememberSaveable { mutableStateOf(false) }
    var editAddMenuOpen by rememberSaveable { mutableStateOf(false) }
    // "Mazání stránek s dotazem" (2026-09-17 noc): the page the "×" next to the page indicator,
    // the page overview's trash, or "Odebrat stránku" is about to force-delete once its
    // confirmation ("Smazat stránku?") is answered — null while no confirmation is up.
    var deletePageConfirm by rememberSaveable { mutableStateOf<Int?>(null) }
    // Every one of those three entry points funnels through here: an already-empty page just
    // disappears with a haptic, same as any other edit-mode removal; one holding something opens
    // the confirmation instead of deleting outright.
    val requestDeletePage: (Int) -> Unit = { page ->
        if (homeDeletePageNeedsConfirm(state.layout, page)) deletePageConfirm = page
        else { widgets.removeHomePage(page); Haptics.play(context, HapticEvent.PAGE_REMOVED) }
    }
    var resizeSlot by remember { mutableStateOf<Int?>(null) }
    var resizeWidth by rememberSaveable { mutableIntStateOf(1) }
    var resizeHeight by rememberSaveable { mutableIntStateOf(1) }
    var resizeConstraints by remember { mutableStateOf<WidgetSpanConstraints?>(null) }
    var resizePitchY by remember { mutableFloatStateOf(1f) }
    var resizeTopPitch by remember { mutableFloatStateOf(1f) }
    var resizeAppPitch by remember { mutableFloatStateOf(1f) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var appMoveMenu by rememberSaveable { mutableStateOf(false) }
    var customizationPage by rememberSaveable { mutableStateOf(CustomizationPage.OVERVIEW) }
    LaunchedEffect(selectedId) { if (selectedId == null) appMoveMenu = false }
    LaunchedEffect(sheet) { if (sheet.isEmpty()) customizationPage = CustomizationPage.OVERVIEW }
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var createFolderFirstId by rememberSaveable { mutableStateOf<String?>(null) }
    // B46 "Dvojice aplikací": long-press on a pair tile opens this menu (Swap sides/Split/Remove),
    // exactly mirroring openFolderId/createFolderFirstId's plain rememberSaveable state above.
    var pairMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    var lastHomePage by rememberSaveable { mutableIntStateOf(0) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var pinQuery by rememberSaveable { mutableStateOf("") }
    // B28 Spotlight: opened by a pull-down on Home, arbitrated against the shade in the shared
    // recognizer's onePageGestures(onSpotlightPullDown = openSpotlight) call below, or the
    // rail's search button (openSpotlight, wired at its CircleControl call site).
    var spotlightOpen by rememberSaveable { mutableStateOf(false) }
    val openSpotlight = { spotlightOpen = true; Haptics.play(context, HapticEvent.SPOTLIGHT_OPEN) }
    LaunchedEffect(spotlightRequests) { if (spotlightRequests > 0) openSpotlight() }
    // Spotlight v2 (17. 9. evening): live open/close progress, read by the page root below
    // (graphicsLayer scale/dim of the Home content while Spotlight is up) — SpotlightOverlay
    // itself owns the animation and just writes into this on every frame.
    val spotlightMotion = rememberSpotlightMotionState()
    // B48 "Pant jako ovladač": squeezeHeld comes straight from PoseRepository via MainActivity;
    // this just lets a tap inside the overlay (or Back) close it early without the hand actually
    // having to re-flatten — the physical release still resets [squeezeHeld] to false on its own.
    var squeezeDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(squeezeHeld) { if (squeezeHeld) squeezeDismissed = false }
    // B42 "Paleta ze švu": declared here (not inside the BoxWithConstraints below) so the overlay
    // call, which — like SpotlightOverlay above it — lives outside that inner scope, can read
    // them; the gesture modifier that writes them sits inside that scope but closes over these
    // same `remember`s like any other outer state this composable already threads inward.
    var seamDragPane by remember { mutableStateOf<Pane?>(null) }
    var seamDragProgress by remember { mutableFloatStateOf(0f) }
    var seamPaletteOpenPane by remember { mutableStateOf<Pane?>(null) }
    val launcherActivity = androidx.activity.compose.LocalActivity.current as MainActivity
    val launcherRootView = LocalView.current.rootView
    val appsById = remember(state.apps) { state.apps.associateBy { it.id } }
    val drag = remember { HomeDragState() }
    // B25 follow-up: mirrored onto drag so FolderTile (already threaded HomeDragState for its
    // drop region) can fade the real grid icon out while its glass card is open, without adding
    // openFolderId/progress as separate parameters through SharedHomeGrid/HomePagePane.
    SideEffect { drag.openFolderId = openFolderId }
    // Home edit ("jiggle") mode (2026-09-17 evening): mirrored onto drag the same way, so every
    // icon/folder/widget/dock wrapper (all already threaded HomeDragState) can wiggle, show the
    // "-" remove badge and drag immediately without a new parameter down each of those call sites.
    SideEffect { drag.editMode = editModeActive }
    SideEffect { drag.onEditRemove = { target ->
        model.removePlacement(target); model.trimTrailingEmptyPages()
        Haptics.play(launcherRootView.context, HapticEvent.ICON_DROP)
    } }
    SideEffect { drag.onEditModeExit = { editModeActive = false; model.trimTrailingEmptyPages() } }
    EditModeWiggleClock(drag, editModeActive)
    val homePages = state.homePages
    val pendingNewPage = widgets.pendingPlacement?.page == homePages
    // "Edit mode" for the trailing "+" page: dragging an icon/widget, mid widget placement, the
    // customization sheet's Home layout page (its own "Přidat stránku" button lives there too), or
    // Home edit ("jiggle") mode itself (homePagingTempPageActive, HomeEditMode.kt).
    val homePagesEditing = sheet == "settings" && customizationPage == CustomizationPage.HOME
    val pagingTempPage = homePagingTempPageActive(drag.active, widgetSession != null, pendingNewPage, homePagesEditing, editModeActive)
    // B39 "Přehled stránek": hidden pages are only skipped while browsing normally — a drag, a
    // pending widget placement, or the Home layout editor's own temporary "+" page all need every
    // logical page to still get a physical pager stop, so hiding is suspended for their duration.
    val homeIndices = (0 until homePages).toList()
    val visibleHomeIndices = if (pagingTempPage) homeIndices
        else homeIndices.filter { it !in state.effectiveHiddenHomePages }.ifEmpty { homeIndices }
    var pageOverviewOpen by rememberSaveable { mutableStateOf(false) }
    val visibleHomePages = visibleHomeIndices.size + if (pagingTempPage) 1 else 0
    var expandedWorkspace by remember { mutableStateOf(false) }
    // B26 Smart stack follow-up: mirrors ExpandedWorkspace's own `showToday` (the Today/leading
    // canvas only exists at all in expanded/unfolded mode — the cover never composes page -1).
    // Defaults true (assume visible) so an unlucky first frame before ExpandedWorkspace reports
    // in cannot rotate a stack the user might already be looking at.
    var todayVisible by remember { mutableStateOf(true) }
    val stackPageVisible = expandedWorkspace && todayVisible
    // Compact hub pill (redesign after Tom's 2026-09-17 feedback): collapsed (the one-row summary)
    // by default, a tap expands it into the old cards; leaving Today, resuming the app, or a 20 s
    // timeout all collapse it again — reduceHubPill is the one pure rule behind every trigger here.
    var hubPillState by remember { mutableStateOf(HubPillState.COLLAPSED) }
    val hubExpanded = hubPillState == HubPillState.EXPANDED
    fun dispatchHubPill(event: HubPillEvent) { hubPillState = reduceHubPill(hubPillState, event) }
    LaunchedEffect(todayVisible) { if (!todayVisible) dispatchHubPill(HubPillEvent.LEAVE_TODAY) }
    LaunchedEffect(hubPillState) {
        if (hubPillState == HubPillState.EXPANDED) { delay(HUB_PILL_AUTO_COLLAPSE_MS); dispatchHubPill(HubPillEvent.TIMEOUT) }
    }
    LifecycleResumeEffect(Unit) { dispatchHubPill(HubPillEvent.RESUME); onPauseOrDispose { } }
    // B26 Smart stack follow-up: recompute every stack's shown member exactly when
    // StackRotation.stackRotationMayRun says it is safe to — never while Today is on screen,
    // only the instant it stops being visible or the instant it becomes visible again (so the
    // member is already right before the reveal, and StackFlipContainer's own flip plays as it
    // recomposes with a changed activeId), plus on every activity resume unless Today is
    // currently visible (a resume can land straight back on an already-open Today).
    fun rotateStacksIfDue(justBecameVisible: Boolean) {
        if (!stackRotationMayRun(stackPageVisible, justBecameVisible)) return
        val hour = java.time.LocalDateTime.now().hour
        val now = System.currentTimeMillis()
        // Same signal Spotlight.kt's own recency falls back to: real UsageStats when the special
        // grant is present, else "has this launcher ever recorded a launch at all" as a coarse
        // stand-in — either makes MIDDAY's highest-usageScore rule eligible to run instead of
        // always deferring to the current member.
        val usageAvailable = hasUsageAccess(context) || state.recentLaunches.isNotEmpty()
        state.widgetPlacements.filter { it.isStack }.forEach { placement ->
            // Host (non-builtin) widgets have no provider-label classifier wired yet (STATUS.md
            // B26's own open item) — categoryForBuiltin already falls back to OTHER for them,
            // same as an unclassified member always has, so this never mis-sorts, only misses
            // out on the morning/evening/night rules for a purely third-party stack.
            val members = placement.stackMembers.map { id -> StackMember(id, StackRotation.categoryForBuiltin(id)) }
            // The manual pin (StackPin, a 30-minute hold after a swipe) is persisted in its own
            // prefs file (StackPinStore.kt) keyed by slot, so it survives the process death a
            // panel swap causes; StackRotation.isPinned already treats a stale one as inactive.
            val pin = StackPinStore.get(context, placement.slot)
            val nextIndex = StackRotation.resolveIndex(members, hour, placement.activeStackIndex(),
                usageAvailable = usageAvailable, smartRotate = placement.smartRotate, pin = pin, now = now)
            members.getOrNull(nextIndex)?.id?.let { nextId ->
                if (nextId != placement.id) model.showStackMember(placement.slot, nextId)
            }
        }
    }
    var previousStackPageVisible by remember { mutableStateOf(stackPageVisible) }
    LaunchedEffect(stackPageVisible) {
        rotateStacksIfDue(justBecameVisible = stackPageVisible && !previousStackPageVisible)
        previousStackPageVisible = stackPageVisible
    }
    LaunchedEffect(launchResumes) { rotateStacksIfDue(justBecameVisible = false) }
    // Fold avoidance (IDEAS B7) for sheets and dialogs composed outside the layout box below.
    var foldAvoidance by remember { mutableStateOf<FoldAvoidance?>(null) }
    val pageCount = visibleHomePages + 1
    val nativePager = rememberPagerState(initialPage = savedPage.coerceIn(0, pageCount - 1), pageCount = { pageCount })
    val currentVisibleHomeIndices = rememberUpdatedState(visibleHomeIndices)
    val currentHomePages = rememberUpdatedState(homePages)
    val pager = remember(nativePager) { LauncherPager(nativePager, { currentVisibleHomeIndices.value }, { currentHomePages.value }) }
    // Výkon 3 "kreslení na inneru" (17. 9. noc), item 5: brackets one pager drag (begin -> settle)
    // with a FrameMetrics episode, logged as "swipe episode ..." under FoldDuoPerf — see
    // PagerSwipeFrameMetricsCollector's own doc (HighRefreshRate.kt). `isScrollInProgress` covers
    // both an active drag and its settle fling/spring, which is exactly the window a swipe's own
    // jank (recomposition, extra layers) would show up in.
    val swipeFrameMetrics = remember(launcherActivity) {
        PagerSwipeFrameMetricsCollector(launcherActivity.window, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    LaunchedEffect(nativePager.isScrollInProgress) {
        // Výkon 4 "recompositions při swipe": the same episode window as the frame-timing
        // collector above, so a swipe's "frames=N p95=Nms" line and its "recompositions during
        // swipe: tag=count" line always describe the exact same gesture.
        if (nativePager.isScrollInProgress) { swipeFrameMetrics.start(); RecompositionCounter.startEpisode() }
        else { swipeFrameMetrics.stop(); RecompositionCounter.endEpisode() }
    }
    fun leaveTemporaryWidgetPage() {
        val persistedPages = model.state.value.homePages
        if (pager.currentPage >= persistedPages)
            pager.requestScrollToPage((persistedPages - 1).coerceAtLeast(0))
    }
    var priorPendingPlacement by remember { mutableStateOf<WidgetPlacement?>(null) }
    LaunchedEffect(widgets.pendingPlacement, state.layout) {
        val pending = widgets.pendingPlacement
        if (pending != null) priorPendingPlacement = pending
        else priorPendingPlacement?.let { prior ->
            if (model.placement(prior.slot) == null && prior.page >= homePages) leaveTemporaryWidgetPage()
            priorPendingPlacement = null
        }
    }
    val pageGestures = remember(nativePager) { PageGestureLimits(nativePager) }
    SideEffect { pageGestures.editing = drag.active || widgetSession != null || resizeSlot != null }
    // B24 "Pružiny všude": the cover pager snaps with an underdamped spring instead of the
    // default critically-damped one, and a lower positional threshold means a short, almost
    // stationary swipe still turns the page rather than snapping back.
    val pageFling = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
        nativePager, pagerSnapDistance = pageGestures,
        snapAnimationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        snapPositionalThreshold = 0.15f,
    )
    val scope = rememberCoroutineScope()
    val reduceMotion = remember(context) { systemReduceMotionEnabled(context) }
    val pagerWidthPxState = remember { mutableFloatStateOf(0f) }
    val pagerOverscroll = remember(scope) { PagerRubberBandOverscroll(scope) { pagerWidthPxState.floatValue } }
    // B24 follow-up "Rubber-band on the inner workspace": the expanded workspace's custom drag
    // (onePageGestures) has no OverscrollEffect pipeline to plug pagerOverscroll into, so it gets
    // its own holder with the same feel — same width limit (pagerWidthPxState) as the cover.
    val workspaceEdgeOverscroll = remember { WorkspaceEdgeOverscroll() }
    // B23 "Otevírání aplikací z ikony": every launch that started from an icon (bounds != null)
    // captures those bounds and plays a forward zoom (icon grows and fades, the page shrinks 4 %
    // and dims); the matching resume plays it in reverse exactly once (see launchResumes below).
    val launchReturnGuard = remember { LaunchReturnGuard() }
    var pendingLaunchIcon by remember { mutableStateOf<LaunchIconVisual?>(null) }
    val pageZoomScale = remember { Animatable(1f) }
    val pageZoomDim = remember { Animatable(1f) }
    val iconZoomScale = remember { Animatable(1f) }
    val iconZoomAlpha = remember { Animatable(1f) }
    fun launchZoom(app: AppEntry, bounds: android.graphics.Rect?) {
        if (reduceMotion) return
        launchReturnGuard.noteLaunch(android.os.SystemClock.elapsedRealtime())
        if (bounds != null && !bounds.isEmpty) {
            pendingLaunchIcon = LaunchIconVisual(app, android.graphics.Rect(bounds))
            scope.launch {
                iconZoomScale.snapTo(1f); iconZoomAlpha.snapTo(1f)
                launch { iconZoomScale.animateTo(ICON_LAUNCH_ZOOM_TARGET_SCALE, tween(ICON_LAUNCH_ZOOM_DURATION_MS)) }
                launch { iconZoomAlpha.animateTo(0f, tween(ICON_LAUNCH_ZOOM_DURATION_MS)) }
            }
        }
        scope.launch {
            pageZoomScale.snapTo(1f); pageZoomDim.snapTo(1f)
            launch { pageZoomScale.animateTo(PAGE_LAUNCH_ZOOM_SCALE, tween(ICON_LAUNCH_ZOOM_DURATION_MS)) }
            launch { pageZoomDim.animateTo(PAGE_LAUNCH_DIM_ALPHA, tween(ICON_LAUNCH_ZOOM_DURATION_MS)) }
        }
    }
    val zoomLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, bounds -> launchZoom(app, bounds); onLaunchFrom(app, bounds) }
    val zoomLaunch: (AppEntry) -> Unit = { app -> zoomLaunchFrom(app, null) }
    // B46 "Dvojice aplikací": the same icon-zoom feedback as a plain app launch, played on the
    // pair's `first` app (the one that actually launches with the zoom); the split-screen sequence
    // itself is MainActivity's onLaunchPair.
    val zoomLaunchPair: (PairEntry, android.graphics.Rect?) -> Unit = { pair, bounds ->
        appsById[pair.first]?.let { launchZoom(it, bounds) }
        onLaunchPair(pair, bounds)
    }
    // B26 Smart stack follow-up: a manual swipe (StackFlipContainer's onSwipe, threaded down as
    // onCycleStack) commits the flip and then pins it in StackPinStore — commitLayout updates the
    // model's StateFlow synchronously, so `model.placement(slot)` right after already sees the
    // member the swipe just landed on. Shared by both the expanded workspace's Today canvas and
    // the plain cover pager below (a stack is an ordinary widget on either).
    val cycleStack: (Int, Boolean) -> Unit = { slot, forward ->
        model.cycleStackMember(slot, forward)
        model.placement(slot)?.let { StackPinStore.set(context, slot, StackPin(it.id, System.currentTimeMillis())) }
    }
    LaunchedEffect(launchResumes) {
        val shouldReplay = !reduceMotion && launchReturnGuard.consumeReturn(android.os.SystemClock.elapsedRealtime())
        if (shouldReplay) {
            launch { pageZoomScale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
            launch { pageZoomDim.animateTo(1f, tween(200)) }
            if (pendingLaunchIcon != null) {
                iconZoomAlpha.snapTo(0f); iconZoomScale.snapTo(1.3f)
                launch { iconZoomAlpha.animateTo(1f, tween(150)) }
                launch { iconZoomScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
            }
        } else {
            pageZoomScale.snapTo(1f); pageZoomDim.snapTo(1f)
            iconZoomAlpha.snapTo(1f); iconZoomScale.snapTo(1f)
        }
        pendingLaunchIcon = null
    }
    var previousHomePages by remember { mutableIntStateOf(homePages) }
    var previousEditRevision by remember { mutableIntStateOf(state.editRevision) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(pager, homePages) {
        snapshotFlow { pager.settledPage to drag.active }.distinctUntilChanged().collect { (page, moving) ->
            if (!moving) { savedPage = page; if (page in 0 until homePages) lastHomePage = page }
        }
    }
    LaunchedEffect(homePages, state.editRevision) {
        if (homePages != previousHomePages && !drag.active) {
            // Pin edits in the library keep the library selected; a completed drop stays on home.
            if (state.editRevision == previousEditRevision) {
                if (pager.currentPage == previousHomePages) pager.scrollToPage(homePages)
                else if (pager.currentPage >= pageCount) pager.scrollToPage(homePages - 1)
            } else if (pager.currentPage >= homePages) pager.scrollToPage(homePages - 1)
        }
        previousHomePages = homePages
        previousEditRevision = state.editRevision
    }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage != homePages) focus.clearFocus() }
    LaunchedEffect(state.verticalStatus) { onStatusMode(state.verticalStatus) }
    // MainActivity's onNewIntent only bumps homeRequests for a Home press while already in front
    // (or the internal duo_destination=="home" signal) — a Home intent delivered on the return
    // from an app never reaches here at all, so this effect keeps the iOS-like "in front" rule
    // alone: the first press goes to the first spread (pager.firstPage, whatever Home page that
    // happens to be once hidden pages are in play — never a stale lastHomePage/currentPage
    // fallback); already there (Today included), a second press dismisses whatever is open and
    // stays put — it never jumps on to the App Library.
    LaunchedEffect(homeRequests) { if (homeRequests > 0) {
        drag.clear(); widgetSession = null; resizeSlot = null; sheet = ""; widgetPackage = null
        widgetExactTarget = false; widgetPlacementMessage = null; selectedId = null; appMoveMenu = false
        openFolderId = null; createFolderFirstId = null; pairMenuId = null; editModeActive = false; editAddMenuOpen = false
        focus.clearFocus(); keyboard?.hide()
        if (!pager.isOnFirstPage) pager.animateScrollToPage(pager.firstPage)
    } }
    LaunchedEffect(searchRequests) { if (searchRequests > 0) { drag.clear(); widgetSession = null; resizeSlot = null; sheet = ""; widgetPackage = null; widgetExactTarget = false; selectedId = null
        if (!state.googleSearch || !onGoogleSearch(null)) pager.animateScrollToPage(homePages)
    } }
    val widgetPickerBack = {
        if (widgetSession != null) {
            leaveTemporaryWidgetPage(); widgetSession = null; widgetPlacementMessage = null
        } else {
            sheet = ""; widgetPackage = null; widgetExactTarget = false; widgetPlacementMessage = null
        }
    }
    BackHandler(enabled = sheet == "widgets") { widgetPickerBack() }
    BackHandler(enabled = sheet.isEmpty()) { if (resizeSlot != null) resizeSlot = null else if (drag.active) {
        val destination = if (drag.source?.target is DropTarget.Library) homePages else drag.originPage.coerceAtMost(homePages - 1)
        drag.clear(); scope.launch { pager.scrollToPage(destination) }
    } else if (selectedId != null) selectedId = null else { focus.clearFocus(); scope.launch { pager.animateScrollToPage(0) } } }
    val openLibrary = { scope.launch { pager.animateScrollToPage(homePages) }; Unit }

    // Unfolded, one Home page is visible in the right pane and its left neighbor is always beside
    // it: both take drops, so drags move icons and widgets between the panes. In spread mode that
    // neighbor is whatever settled strip slot the native pager sits on (Today only at slot 0, a
    // real Home page's own predecessor from there on); "right pane only" keeps Today as the left
    // neighbor unconditionally, exactly as it already did before spread paging existed.
    val dragWindowPage = pager.currentPage
    val eligibleDragPages = remember(expandedWorkspace, dragWindowPage, state.innerPagingSpread, visibleHomeIndices) {
        when {
            !expandedWorkspace -> setOf(dragWindowPage)
            !state.innerPagingSpread -> setOf(-1, dragWindowPage)
            else -> spreadEligiblePages(visibleHomeIndices, pager.state.currentPage)
        }
    }
    val rawTarget = if (drag.active) drag.destination(drag.pointer, eligibleDragPages)?.target else null
    val target = if (rawTarget is DropTarget.Home && drag.source?.target is DropTarget.Widget) {
        val slot = (drag.source!!.target as DropTarget.Widget).index
        model.placement(slot)?.let {
            DropTarget.Home(adjustedWidgetDropIndex(rawTarget.index, it, drag.source!!.bounds, drag.origin))
        } ?: rawTarget
    } else rawTarget
    val blockedDock = drag.moved && target is DropTarget.Dock &&
        if (drag.source?.folderId != null) state.dock.none { it == null }
        else drag.source?.appId?.let { !canPlaceInDock(state.layout, it) } == true
    val insertionTarget = target.takeIf { drag.moved && !blockedDock }
    // B46 "Dvojice aplikací": HomeDragState.pairHoverTarget/pairHoverStartMs are a pure stopwatch
    // reset whenever the live drop target changes; dragNowMs ticks while dragging so pairDropArmed
    // below recomposes as the dwell time itself grows, not only when the target changes.
    LaunchedEffect(insertionTarget) { drag.notePairHover(insertionTarget, System.currentTimeMillis()) }
    var dragNowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(drag.active) {
        while (drag.active) { dragNowMs = System.currentTimeMillis(); delay(50) }
    }
    val pairDropArmed = insertionTarget is DropTarget.Home && insertionTarget == drag.pairHoverTarget &&
        drag.source?.appId != null && drag.source?.folderId == null &&
        isPlainAppShortcut(state.layout.slotAt(insertionTarget.index)) &&
        state.layout.slotAt(insertionTarget.index) != drag.source?.appId &&
        appOnAppDropOutcome(dragNowMs - drag.pairHoverStartMs) == AppOnAppDropOutcome.PAIR
    val widgetRawTarget = widgetSession?.let { session -> drag.regions.values.firstOrNull {
        it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(session.pointer)
    }?.target as? DropTarget.Home }
    val widgetDraft = widgetSession?.let { session -> session.candidate ?: widgetRawTarget?.let { cell ->
        widgetCandidate(state.layout, session.slot, session.targetIndex ?: cell.index, session.span.width, session.span.height)
    } ?: session.targetIndex?.let { widgetCandidate(state.layout, session.slot, it, session.span.width, session.span.height) } }
    val dropHomePage = if (pager.currentPage >= visibleHomePages)
        lastHomePage.coerceIn(0, homePages - 1) else pager.currentPage.coerceIn(0, homePages)
    val previewLayout = remember(state.layout, drag.source, insertionTarget, drag.moved) {
        val id = drag.source?.appId
        when {
            id != null && insertionTarget is DropTarget.Home -> dropApp(state.layout, id, insertionTarget)
            id != null && insertionTarget is DropTarget.Dock -> dropApp(state.layout, id, insertionTarget)
            drag.source?.target is DropTarget.Widget && insertionTarget is DropTarget.Home ->
                moveWidget(state.layout, (drag.source!!.target as DropTarget.Widget).index, insertionTarget.index)
            else -> state.layout
        }
    }
    val edgeWidth = with(LocalDensity.current) { 30.dp.toPx() }
    val edgePointer = widgetSession?.takeIf { it.dragging }?.pointer ?: drag.pointer
    val edgeActive = (drag.active && drag.moved) || widgetSession?.dragging == true
    val edge = if (!edgeActive) 0 else dragEdgeDirection(edgePointer, drag.rootBounds, edgeWidth)
    LaunchedEffect(edgeActive, edge) {
        if (edge != 0) while (drag.active || widgetSession?.dragging == true) {
            delay(650)
            val next = (pager.currentPage + edge).coerceIn(0, homePages)
            if ((!drag.active && widgetSession?.dragging != true) || next == pager.currentPage) break
            // Do not key this effect on currentPage: it changes halfway through the
            // animation and would cancel the turn before the inner grid is visible.
            // Once the hold commits a turn, finish its animation while the finger moves
            // into the incoming page. Leaving the edge cancels only the next hold timer.
            scope.launch { pager.animateScrollToPage(next) }.join()
        }
    }
    fun finishDrag(cancelled: Boolean) {
        val source = drag.source ?: return
        val moved = drag.moved
        val rawDestination = if (moved && !cancelled) drag.destination(drag.pointer, eligibleDragPages)?.target else null
        val destination = if (rawDestination is DropTarget.Home && source.target is DropTarget.Widget) {
            model.placement(source.target.index)?.let {
                DropTarget.Home(adjustedWidgetDropIndex(rawDestination.index, it, source.bounds, drag.origin))
            }
                ?: rawDestination
        } else rawDestination
        // B46 "Dvojice aplikací": dropping a plain app onto another plain app (never a folder or
        // pair cell — those already claim a higher-priority DropTarget.Folder region, or simply
        // aren't a "plain" occupant) creates a Folder on a quick release, a Pair once the drag
        // dwelled on it for appOnAppDropOutcome's threshold — see LauncherScreen.kt's root
        // pairDropArmed (same dwell, used only for the live "about to become a pair" visual).
        val appOnAppOccupant = (destination as? DropTarget.Home)?.let { model.state.value.layout.slotAt(it.index) }
        val appOnAppTarget = destination is DropTarget.Home && source.folderId == null && source.appId != null &&
            isPlainAppShortcut(appOnAppOccupant) && appOnAppOccupant != source.appId
        val changed = when {
            appOnAppTarget -> {
                val homeTarget = destination as DropTarget.Home
                val dwellMs = if (drag.pairHoverTarget == destination) System.currentTimeMillis() - drag.pairHoverStartMs else 0L
                when (appOnAppDropOutcome(dwellMs)) {
                    AppOnAppDropOutcome.PAIR -> model.createPair(appOnAppOccupant!!, source.appId!!, homeTarget.index) != null
                    AppOnAppDropOutcome.FOLDER -> model.createFolder(appOnAppOccupant!!, source.appId!!, homeTarget.index) != null
                }
            }
            source.folderId != null && destination is DropTarget.Folder ->
                model.addAppToFolder(destination.id, source.appId ?: "")
            source.folderId != null && destination != null && source.appId != null ->
                model.removeAppFromFolder(source.folderId, source.appId, destination)
            destination == DropTarget.Remove -> model.removePlacement(source.target)
            destination is DropTarget.Home && source.target is DropTarget.Widget -> {
                // B26 Smart stack: dropping a widget squarely onto another of the same span
                // stacks them instead of the rejected same-cell move. rawDestination (before the
                // top-left alignment above) is the cell actually under the finger at release, so
                // it always lands inside whichever widget the pointer was dropped on.
                val sourceSlot = source.target.index
                val stackTarget = (rawDestination as? DropTarget.Home)
                    ?.let { widgetPlacementAt(model.state.value.layout, it.index, sourceSlot) }
                val sourcePlacement = model.placement(sourceSlot)
                if (stackTarget != null && sourcePlacement != null && canStackTogether(stackTarget, sourcePlacement))
                    model.stackWidgets(stackTarget.slot, sourceSlot)
                else model.moveWidgetTo(sourceSlot, destination.index)
            }
            destination != null && source.appId != null -> model.applyDrop(source.appId, destination)
            else -> false
        }
        // iOS behaviour: a page left empty by this move disappears if it is trailing (middle
        // empty pages stay). Safe to run unconditionally on the destination page used below: a
        // trimmed page is always trailing, so it can only be at or after whatever just changed.
        if (changed) model.trimTrailingEmptyPages()
        if (moved && changed) Haptics.play(launcherRootView.context, HapticEvent.ICON_DROP)
        val returnToLibrary = source.target is DropTarget.Library && source.folderId == null && !changed
        val destinationHomePage = (destination as? DropTarget.Home)?.index?.let(::homeCellPage)
        val page = when (destination) {
            is DropTarget.Home -> destinationHomePage!!.coerceAtLeast(0)
            is DropTarget.Dock -> dropHomePage
            is DropTarget.Widget -> 0
            else -> if (source.target is DropTarget.Library) pager.currentPage else drag.originPage
        }
        scope.launch {
            // Let a new home page compose before removing the temporary drop page.
            withFrameNanos { }
            drag.clear()
            withFrameNanos { }
            pager.scrollToPage(if (returnToLibrary) model.state.value.homePages else page.coerceIn(0, model.state.value.homePages - 1))
            // Edit mode's immediateDrag (HomeDrag.kt) makes even a plain tap-release go through
            // this same finishDrag path (start() already fired on touch down); the old "release
            // without moving opens the menu" behaviour must not fire while editing — a released,
            // unmoved icon there should just sit back down.
            if (!moved && !cancelled && !editModeActive) {
                if (source.target is DropTarget.Dock) { dockSlot = source.target.index; sheet = "dock" }
                else if (source.target is DropTarget.Widget) { widgetSlot = source.target.index; sheet = "widgetActions" }
                else if (source.appId?.let(::isFolderId) == true) openFolderId = source.appId
                else if (source.folderId == null) selectedId = source.appId
            }
        }
    }

    // Window rectangle of this root, the space the background is drawn in: the frosted cards
    // (FrostedBackdrop.kt) map their window bounds through it to the blurred backdrop.
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    // "Ostrůvek do plochy" (17. 9. večer): key of the island item expanded into its card; hoisted
    // up here (rather than inside the BoxWithConstraints below) so the root-level
    // IslandExpandedOverlay — a sibling of that box, not a child of the rail — can read it too.
    // islandAnchor is the matching geometry hand-off, published by a SideEffect once the rail's
    // own layout (rail.islandTop, the seam, the pane) is known further down.
    var islandExpanded by remember { mutableStateOf<String?>(null) }
    var islandAnchor by remember { mutableStateOf(IslandCardAnchor(0f, 0f, 0f, 0f, isCover = true)) }
    // "Stavová karta z railu" (17. 9. noc): same hoisting as the island card above, one Box
    // earlier — statusRingBounds is measured directly off the ring (StatusRail's onRingBounds,
    // root-relative px), statusCardAnchor is published once the pane/seam is known further down.
    var statusCardOpen by remember { mutableStateOf(false) }
    var statusRingBounds by remember { mutableStateOf<Rect?>(null) }
    var statusCardAnchor by remember { mutableStateOf(StatusCardAnchor(0f, 0f, 0f, 0f, 0f, 0f, isCover = true)) }
    // Continuum A phase 1 (UnfoldMorph.kt): on the cover the whole Home, wallpaper included, is
    // one layer hinged on its left edge that frosts while the phone is being opened. Idle it
    // costs nothing (no render effect); the effect for the fully frosted cover is pre-built.
    val rootPanel = currentPanel()
    val coverMorph = LocalMorphController.current
    val coverContext = LocalContext.current
    val coverShader = remember(coverContext) { FoldShader.shared(coverContext) }
    val coverPxPerMm = remember(coverContext) { FoldShader.pxPerMm(coverContext) }
    // B20: "Frost intensity" / "Tilt" (Appearance.kt's morphFrostFactor/morphTiltFactor).
    val coverFoldConfig = remember(appearance.morphFrostFactor) { FoldConfig(blurSpread = MorphPreviewTuning.blurSpread(FoldConfig().blurSpread, appearance.morphFrostFactor)) }
    val coverFoldCache = remember { FoldEffectCache() }
    val coverFold = remember(rootSize.width) {
        FoldLine(splitsX = true, position = 0f, eyePos = rootSize.width * 0.5f, movingSide = 1)
    }
    // B21: reduce motion turns the frost layer off entirely (no tilt, no blur) rather than just
    // forcing its tilt to 0 — MorphController already forces angleTilt/coverFrost/closeFrost to 0
    // or unarmed while reduce motion is on (UnfoldMorph.kt), this only removes the render effect.
    val reduceMotionCover = MotionPrefs.enabled.value
    val coverFrostLayer = if (rootPanel != Panel.Cover || coverMorph == null || coverShader == null || reduceMotionCover) Modifier else
        Modifier.foldEffect(coverShader, tilt = { MorphPreviewTuning.scaleTilt(MorphCurve.coverTilt(coverMorph.coverFrost.value, coverMorph.angleTilt.value, coverMorph.coverSettleTilt.value), appearance.morphTiltFactor) },
            config = coverFoldConfig, pxPerMm = coverPxPerMm, fold = coverFold, cache = coverFoldCache,
            primeTilt = MorphCurve.COVER_FROST_TILT)
    // B32 (Tent -> StandBy morph, standby/StandByMorphOverlay.kt): the launcher's own pre-entry
    // frost (hinge-angle-driven while it is confident, timed fallback otherwise) and the return
    // clear once StandBy exits (MorphController.requestStandByReturn), both flat overlays with
    // no hinge tilt (contrast [coverFrostLayer] above), so one bridge covers whichever panel is
    // active. The only integration point this feature needs in LauncherScreen.kt.
    val standByFrostValue = rememberLauncherStandByFrost(coverMorph)
    val standByFrostLayer = Modifier.standByFrost(standByFrostValue)
    Box(Modifier.fillMaxSize().testTag("launcher-root")
        .onGloballyPositioned { rootOrigin = it.positionInWindow(); rootSize = it.size }.then(coverFrostLayer).then(standByFrostLayer).homeDragInput(drag,
        enabled = sheet.isEmpty() && !showFirstRun && selectedId == null && resizeSlot == null,
        page = pager.currentPage, eligiblePages = eligibleDragPages,
        // iPhone-style icon popover (2026-09-17 evening, fixes the long-press regression): a
        // movable app icon (Home cell or dock — anything dropRegion registered with a plain
        // appId, which excludes folders since their id never appears in appsById) opens the
        // popover on long-press instead of lifting straight into a drag. Home edit ("jiggle")
        // mode overrides this entirely: everything drags immediately, the way a jiggling icon
        // does on iOS.
        popoverEligible = { region -> region.appId != null && appsById.containsKey(region.appId) },
        onPopover = { Haptics.play(launcherRootView.context, HapticEvent.ICON_POPOVER) },
        immediateDrag = { editModeActive },
        onStart = {
            focus.clearFocus(); keyboard?.hide(); Haptics.play(launcherRootView.context, HapticEvent.ICON_PICKUP)
            // B25 follow-up: dragging a child out no longer nulls openFolderId instantly — the
            // FolderPanel below sees closeRequested flip true and plays its reverse spring, then
            // dismisses itself (setting openFolderId = null) once the card has shrunk back down.
            if (drag.source?.target is DropTarget.Library) scope.launch {
                withFrameNanos { }
                pager.scrollToPage(lastHomePage.coerceIn(0, homePages - 1))
            }
        },
        onFinish = { cancelled -> finishDrag(cancelled) })) {
        DuneWallpaper()
        val frostImage = rememberFrostedBackdrop(rootSize.width, rootSize.height)
        val frost = remember(frostImage, rootOrigin, rootSize) {
            frostImage?.let { FrostedBackdrop(it, rootOrigin.x, rootOrigin.y, rootSize.width.toFloat(), rootSize.height.toFloat()) }
        }
        CompositionLocalProvider(LocalFrostedBackdrop provides frost) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            // Spotlight v2 (17. 9. evening): the page root scales to 0.96 and dims 20 % while
            // Spotlight is open, read-only off spotlightMotion.progress (SpotlightOverlay writes
            // it from its own frost animation) — a plain graphicsLayer hook, nothing here reaches
            // into Spotlight.kt itself.
            .graphicsLayer {
                val p = spotlightMotion.progress
                val scale = 1f - 0.04f * p
                scaleX = scale; scaleY = scale
                alpha = 1f - 0.2f * p
            }) {
            val wide = maxWidth.value >= FOLD_THRESHOLD_DP
            val preset = if (wide) state.expanded else state.compact
            val density = LocalDensity.current
            // Výkon 4 "recompositions při swipe": pager.currentPage only flips at a page-boundary
            // crossing (Compose Foundation's own PagerState.currentPage is itself a derivedStateOf
            // over the continuous scroll offset), but reading it as a plain `val` here still
            // subscribes this whole BoxWithConstraints content scope — everything from the pager
            // down through ExpandedWorkspace/HomePagePane/SharedHomeGrid — to recompose on every
            // crossing. A derivedStateOf narrows that to "only when the boolean itself changes",
            // same idiom as ExpandedWorkspace's own showToday/showLibrary below.
            val inLibrary by remember(visibleHomePages) { derivedStateOf { pager.currentPage == visibleHomePages } }
            var statusHeight by remember { mutableFloatStateOf(0f) }
            // islandExpanded is now hoisted above this box (root scope) — see its declaration there.
            // B34: package of the hub stack currently fanned open; null when every stack is collapsed.
            var expandedHubGroup by remember { mutableStateOf<String?>(null) }
            // "Notifications on Today" (Appearance settings, default on): off empties the canvas
            // slot and the cover badge without touching what the listener publishes.
            val hubGroups = remember(hubNotifications, NotificationHubSettings.hubEnabled.value) {
                if (NotificationHubSettings.hubEnabled.value) groupHubNotifications(hubNotifications) else emptyList()
            }
            // FoldingFeature bounds are window coordinates; this box starts after the safe-drawing inset.
            val safeLeftDp = with(density) { WindowInsets.safeDrawing.getLeft(this, LocalLayoutDirection.current).toDp().value }
            val foldSeam = LocalFoldSeam.current?.let { it.copy(xDp = it.xDp - safeLeftDp) }?.takeIf { it.splits(maxWidth.value) }
            // This box already sits inside the safe-drawing insets; only a camera cutout that
            // reaches below them (cover, status bar hidden) still needs clearing — now by the
            // grid's own first row too (17. 9. noc "Mřížka 4x7 a obsah výš"), not just the rail.
            val safeTopDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp().value }
            val cutoutBottomDp = (with(density) { WindowInsets.displayCutout.getTop(this).toDp().value } - safeTopDp).coerceAtLeast(0f)
            val geometry = homeGeometry(maxWidth.value, maxHeight.value, preset, state.labels,
                labelHeight = with(density) { 14.sp.toDp().value } + 6f,
                seamXDp = foldSeam?.xDp, seamGutterDp = foldSeam?.gutterDp ?: FOLD_GUTTER_DP,
                cutoutBottomDp = cutoutBottomDp)
            // topInset = -safeTopDp: the rail is clear of the cutout, so its status column may sit
            // above this box's safe-area padding, [RAIL_TOP_MARGIN_DP] from the screen's top edge.
            val rail = railLayout(maxHeight.value, topInset = -safeTopDp, bottomInset = 0f, cutoutBottom = cutoutBottomDp,
                statusHeight = if (state.verticalStatus) statusHeight else 0f, dockWidth = preset.dockWidth,
                dockIconSize = dockIconSize(geometry.iconSize), topMargin = RAIL_TOP_MARGIN_DP,
                // The collapsed island reserves 0 / 44 / 96 dp; its expanded card overlays the dock span.
                // B61: 0 outright while the camera island (not the rail) is the live-activities surface.
                islandHeight = islandSlotHeight(islandItems.size, cz.pflanzer.foldduo.cameraisland.cameraIslandIsActiveSurface()),
                dockPosition = preset.dockPosition)
            // "Ostrůvek do plochy": hand the rail's own island geometry to the root-level
            // IslandExpandedOverlay (a sibling of this whole box, so its card is free to grow past
            // the rail column's width) — same pane/seam math FoldAvoidance/paneBounds use below.
            SideEffect {
                val paneStartDp = foldSeam?.homeStartDp ?: 0f
                // The overlay lives at the root Box, OUTSIDE this box's safe-drawing padding, so the
                // rail's box-relative offsets shift by the insets (17. 9.: the card collapsed into
                // the Wi-Fi glyph, one safe-area inset above its own pill).
                islandAnchor = IslandCardAnchor(
                    islandTopDp = rail.islandTop + safeTopDp,
                    pillWidthDp = geometry.railWidth - 16f,
                    paneStartDp = paneStartDp + safeLeftDp,
                    // The same right edge the pills themselves render at (RailIsland's own call
                    // site below pads `end = RAIL_ISLAND_RIGHT_PADDING_DP.dp`), so the card's
                    // right edge lines up with the pill it grew from, not the bare window edge.
                    paneWidthDp = maxWidth.value - paneStartDp - RAIL_ISLAND_RIGHT_PADDING_DP,
                    isCover = foldSeam == null,
                )
            }
            // Status card (17. 9. noc): the ring's own rect comes from a measured root-relative
            // Rect (statusRingBounds, StatusRail's onRingBounds) rather than rail geometry math —
            // deliberately, so it needs none of the safe-drawing-inset correction the island
            // anchor above does (a measured rect already includes it). Only the pane/seam bound
            // — nothing to measure — reuses that same box-relative-to-root fix: [foldSeam] is
            // already shifted by -safeLeftDp for this BoxWithConstraints, so paneStartDp adds it
            // back for the root-level overlay, exactly like paneStartDp above.
            SideEffect {
                val ring = statusRingBounds
                if (ring != null) {
                    val ringTopDp = with(density) { ring.top.toDp().value }
                    val ringLeftDp = with(density) { ring.left.toDp().value }
                    val ringWidthDp = with(density) { ring.width.toDp().value }
                    val ringHeightDp = with(density) { ring.height.toDp().value }
                    val statusPaneStartDp = (foldSeam?.homeStartDp ?: 0f) + safeLeftDp
                    statusCardAnchor = StatusCardAnchor(
                        ringTopDp = ringTopDp, ringLeftDp = ringLeftDp,
                        ringWidthDp = ringWidthDp, ringHeightDp = ringHeightDp,
                        paneStartDp = statusPaneStartDp,
                        paneWidthDp = maxWidth.value - (foldSeam?.homeStartDp ?: 0f) - RAIL_EDGE_PADDING_DP,
                        isCover = foldSeam == null,
                    )
                }
            }
            SideEffect {
                resizePitchY = with(density) { minOf((geometry.widgetHeight + 18f) / 2f, geometry.rowHeight).dp.toPx() }
                resizeTopPitch = with(density) { ((geometry.widgetHeight + 18f) / 2f).dp.toPx() }
                resizeAppPitch = with(density) { geometry.rowHeight.dp.toPx() }
            }
            LaunchedEffect(geometry.gridWidth, geometry.widgetHeight, geometry.rowHeight) { resizeSlot = null }
            SideEffect { expandedWorkspace = geometry.expanded }
            // Continuum A: the panel swap relaunches this activity, so the first expanded layout
            // after a swap (or a swap / debug replay seen while it lives) plays the unfold morph;
            // the first compact layout after a fold settles the cover Home (UnfoldMorph.kt).
            val morph = LocalMorphController.current
            val foldPose = LocalFoldPose.current
            // B16: a single haptic tick the moment the left half reaches Flat (settleGeneration
            // bumps at most once per unfold, in MorphController.fireSettle), gated by the
            // "Haptic at flat" setting.
            val hapticContext = LocalContext.current
            LaunchedEffect(morph, morph?.settleGeneration, appearance.hapticAtFlat) {
                if (morph == null || morph.settleGeneration == 0 || !appearance.hapticAtFlat) return@LaunchedEffect
                performFlatTick(hapticContext)
            }
            // B36: pageSettle only on a real change (never the first composition of this effect).
            var lastSettledPage by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(pager.settledPage) {
                val settled = pager.settledPage
                if (lastSettledPage != null && lastSettledPage != settled) Haptics.play(hapticContext, HapticEvent.PAGE_SETTLE)
                lastSettledPage = settled
            }
            // B36 follow-up: B39's page overview open/close, same "never on the first
            // composition" guard as pageSettle above (nullable Boolean, not a plain default-false
            // var, so mounting already-open from a config change cannot fire a spurious close).
            var lastPageOverviewOpen by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(pageOverviewOpen) {
                if (lastPageOverviewOpen != null && lastPageOverviewOpen != pageOverviewOpen)
                    Haptics.play(hapticContext, if (pageOverviewOpen) HapticEvent.OVERVIEW_OPEN else HapticEvent.OVERVIEW_CLOSE)
                lastPageOverviewOpen = pageOverviewOpen
            }
            // B36 follow-up: B42's seam palette open/close (seamPaletteOpenPane holds which pane
            // it opened on; only the open/closed edge matters here).
            var lastSeamPaletteOpen by remember { mutableStateOf<Boolean?>(null) }
            val seamPaletteOpenNow = seamPaletteOpenPane != null
            LaunchedEffect(seamPaletteOpenNow) {
                if (lastSeamPaletteOpen != null && lastSeamPaletteOpen != seamPaletteOpenNow)
                    Haptics.play(hapticContext, if (seamPaletteOpenNow) HapticEvent.PALETTE_OPEN else HapticEvent.PALETTE_CLOSE)
                lastSeamPaletteOpen = seamPaletteOpenNow
            }
            LaunchedEffect(geometry.expanded, morph, morph?.requests) {
                if (morph == null) return@LaunchedEffect
                if (geometry.expanded) {
                    if (morph.shouldEnter(foldPose)) {
                        android.util.Log.i("FoldDuoMorph", "unfold morph: pose=$foldPose ${morph.describeSnapshot()}")
                        morph.playEnter()
                    }
                } else if (morph.shouldSettleCover()) {
                    android.util.Log.i("FoldDuoMorph", "cover settle: ${morph.describeSnapshot()}")
                    morph.playCoverSettle()
                }
            }
            // Phase 1: a cover-frost trigger (hinge step, gyro opening motion, debug replay) while
            // the cover layout is up. Leaving the cover layout cancels and resets it.
            LaunchedEffect(geometry.expanded, morph, morph?.coverFrostRequests) {
                if (morph == null || geometry.expanded) return@LaunchedEffect
                val trigger = morph.takeCoverFrost() ?: return@LaunchedEffect
                morph.playCoverFrost(trigger)
            }
            // Phase 0: a closing-frost trigger (hinge step off Flat, gyro closing motion, debug
            // replay) while the expanded layout is up. The swap to the cover flips the layout
            // (or relaunches the activity), which cancels the play and resets the frost to 0.
            LaunchedEffect(geometry.expanded, morph, morph?.closeFrostRequests) {
                if (morph == null || !geometry.expanded) return@LaunchedEffect
                val trigger = morph.takeCloseFrost() ?: return@LaunchedEffect
                morph.playCloseFrost(trigger)
            }
            LaunchedEffect(geometry.expanded) {
                if (!geometry.expanded) {
                    val sessionTargetsLeading = widgetSession?.let { session ->
                        session.candidate?.page == -1 || session.targetIndex?.let(::homeCellPage) == -1
                    } == true
                    val savedTargetLeading = widgetTargetIndex != Int.MIN_VALUE && homeCellPage(widgetTargetIndex) == -1
                    if (sessionTargetsLeading || savedTargetLeading) {
                        widgetSession = null
                        widgetTargetIndex = Int.MIN_VALUE
                        widgetExactTarget = false
                        widgetPackage = null
                        widgetProfileSerial = null
                        widgetPlacementMessage = null
                        sheet = ""
                    }
                    val dragTouchesLeading = drag.source?.page == -1 ||
                        ((target as? DropTarget.Home)?.index?.let(::homeCellPage) == -1)
                    if (dragTouchesLeading) {
                        drag.clear()
                    }
                }
            }
            val contentHeight = maxHeight
            val panes = expandedPaneLayout(maxWidth.value, geometry.homeWidth, geometry.gridWidth, foldSeam)
            val pagerWidth = maxWidth - geometry.railWidth.dp
            val pagerWidthPx = with(density) { pagerWidth.toPx() }
            SideEffect { pagerWidthPxState.floatValue = pagerWidthPx }
            val homeStride = panes.homeStride.dp
            val bottomSpace = if (isDefaultHome) 44.dp else 88.dp
            val workspaceMotion = if (geometry.expanded) remember(visibleHomePages, pagerWidth, homeStride, density) {
                WorkspacePageMotion(visibleHomePages, with(density) { pagerWidth.toPx() }, with(density) { homeStride.toPx() })
            } else null
            val dockScroll = rememberScrollState()
            // Widgets on the leading canvas (page -1) are sized against its wider cells, not the Home grid.
            val leadingSizing = remember(panes.leadingWidth, geometry) { leadingGridSizing(panes.leadingWidth, geometry) }
            var gestureOriginInRoot by remember { mutableStateOf(Offset.Zero) }
            var gestureOriginInWindow by remember { mutableStateOf(Offset.Zero) }
            // Fold avoidance (IDEAS B7): while unfolded with a seam, interactive surfaces (folder
            // panel, action sheets, dialogs) open inside one pane. Scrollable content ignores it.
            val windowInfo = LocalWindowInfo.current
            val avoidance = foldSeam?.takeIf { geometry.expanded }?.let { seam ->
                FoldAvoidance(seam, maxWidth.value, geometry.railWidth, safeLeftDp,
                    windowWidthDp = with(density) { windowInfo.containerSize.width.toDp().value },
                    contentOriginRootX = gestureOriginInRoot.x, density = density.density)
            }
            SideEffect { foldAvoidance = avoidance }
            /** Pane of the Home cell [index] from its drop region, else from its page. */
            fun paneOfCell(index: Int?) = avoidance?.let { av ->
                av.paneOf(index?.let { drag.regions[DropTarget.Home(it)]?.bounds }, index?.let(::homeCellPage))
            }
            // Výkon 4 "recompositions při swipe": same reasoning as `inLibrary` above — this combines
            // a dozen separate States (several of which change far more often than a page swipe:
            // drag.active toggles constantly during any icon drag, widgetSession/resizeSlot/sheet
            // during widget setup) directly as a plain `val`, so touching ANY one of them used to
            // recompose this entire BoxWithConstraints content scope even when the *value* of
            // pagerInputEnabled itself did not change. A derivedStateOf narrows that to "only when
            // the gate's own boolean flips" — still re-evaluated on every underlying change, but
            // only actually invalidating downstream readers (onePageGestures' `enabled`) then.
            val pagerInputEnabled by remember(visibleHomePages) {
                derivedStateOf {
                    pager.currentPage in 0..visibleHomePages && !drag.active &&
                        widgetSession == null && resizeSlot == null && sheet.isEmpty() && !showFirstRun && selectedId == null &&
                        openFolderId == null && !editModeActive && createFolderFirstId == null &&
                        launcherActivity.backups.preview == null && !launcherActivity.backups.pickerPending &&
                        !launcherActivity.backgrounds.pickerPending && widgets.setupStatus == null &&
                        widgets.reconfigureWidgetId == null
                }
            }
            // B42 "Paleta ze švu": seamDragPane/seamDragProgress/seamPaletteOpenPane (declared at
            // the top of this composable, same as spotlightOpen) hold the live drag state while
            // the finger is still down (before commit) and the committed-open pane once it is.
            // seamPaletteGesture (SeamPalette.kt) sits ahead of onePageGestures in the modifier
            // chain below and claims the gesture by consumption alone — no other coordination
            // needed.
            // B42 "Dosah na coveru": the offset a downward pull from the cover's bottom lane
            // applies to the cover pager content below; disengaged (and reset) the moment the
            // layout becomes the expanded/inner workspace, where this lane does not exist.
            val reachability = rememberReachabilityController()
            LaunchedEffect(geometry.expanded) { if (geometry.expanded) reachability.dismiss(true) }
            // The page recognizer owns the whole content area on both displays: unfolded it
            // spans the leading pane, the gutter and the Home pane, so a horizontal swipe that
            // starts over the left canvas pages the right pane exactly like one over its icons.
            // Only the rendering is clipped to the right pane (ExpandedWorkspace), not the input.
            Box(Modifier.fillMaxSize().onGloballyPositioned {
                gestureOriginInRoot = it.boundsInRoot().topLeft
                gestureOriginInWindow = it.boundsInWindow().topLeft
            }.seamPaletteGesture(
                seam = foldSeam,
                enabled = geometry.expanded && pagerInputEnabled,
                reduceMotion = reduceMotion,
                paneWidthDp = { p -> foldSeam?.let { paneBounds(p, it, maxWidth.value, geometry.railWidth).extentDp } ?: 0f },
                onDrag = { p, progress -> seamDragPane = p; seamDragProgress = progress },
                onRelease = { p, commit ->
                    seamDragPane = null; seamDragProgress = 0f
                    if (commit) seamPaletteOpenPane = p
                },
            ).onePageGestures(
                nativePager,
                pageGestures,
                motion = workspaceMotion,
                enabled = pagerInputEnabled,
                // B24 follow-up: only the expanded/inner workspace uses this custom gesture with
                // a WorkspacePageMotion at all (the cover falls to the plain HorizontalPager
                // branch below, which already has pagerOverscroll); gate on that same condition.
                edgeOverscroll = if (geometry.expanded) workspaceEdgeOverscroll else null,
                overscrollWidthPx = { pagerWidthPxState.floatValue },
                // Positive IDs are provider-owned Android views. Leave their vertical
                // stream untouched so scrollable widgets retain native gesture handling.
                // A dock that is already scrolled also gets first use of a downward drag.
                canStartDownwardSwipe = { point ->
                    if (pager.currentPage !in 0 until visibleHomePages) false else {
                        val region = drag.hit(point + gestureOriginInRoot, eligibleDragPages)
                        val rootOnScreen = IntArray(2).also(launcherRootView::getLocationOnScreen)
                        val screenPoint = point + gestureOriginInWindow +
                            Offset(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat())
                        !(region?.target is DropTarget.Dock && dockScroll.value > 0) &&
                            !nativeWidgetConsumesVerticalGesture(launcherRootView, screenPoint)
                    }
                },
                onDownwardSwipe = launcherActivity::openSystemShade,
                // B28 follow-up "Spotlight vs shade": the shared recognizer now arbitrates the
                // two by where the drag started (SpotlightModel.kt's downwardHomeGestureLane),
                // so this is the single place Spotlight's pull-down opens from — the separate,
                // additive gesture HomePagePane/Spotlight.kt used before is gone.
                onSpotlightPullDown = openSpotlight,
                // B42 "Dosah na coveru": the third downward lane, cover-only (0f/null on the
                // expanded workspace disables it entirely — SpotlightModel.kt's
                // downwardHomeGestureLane never picks REACHABILITY when bottomFraction is 0f).
                reachabilityBottomFraction = if (geometry.expanded) 0f else REACHABILITY_BOTTOM_FRACTION,
                onReachabilityPullDown = if (geometry.expanded) null else {
                    {
                        reachability.engage(with(density) { (maxHeight * REACHABILITY_PULL_FRACTION).toPx() }, reduceMotion)
                        Haptics.play(hapticContext, HapticEvent.REACHABILITY_SNAP)
                    }
                },
                // Upward over a Home page (not the rail/dock column, not a native widget that
                // owns vertical input) opens the App Library.
                canStartUpwardSwipe = { point ->
                    if (pager.currentPage !in 0 until visibleHomePages || point.x > pagerWidthPx) false else {
                        val rootOnScreen = IntArray(2).also(launcherRootView::getLocationOnScreen)
                        val screenPoint = point + gestureOriginInWindow +
                            Offset(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat())
                        !nativeWidgetConsumesVerticalGesture(launcherRootView, screenPoint)
                    }
                },
                onUpwardSwipe = openLibrary,
                // Over the rail column the rail's thirds pick the shade (top third Quick
                // Settings, below it Notifications); elsewhere the 70/30 rule stays.
                railWidthPx = with(density) { geometry.railWidth.dp.toPx() },
            )
            // B39 "Přehled stránek": pinch-out opens the page overview. Its own two-pointer-only
            // gesture detector (PageOverview.kt), never fed through onePageGestures above, so
            // neither steals the other's pointer (same separation B42's seam gesture keeps).
            .pinchOutToOverview(pagerInputEnabled) { pageOverviewOpen = true }) {
            val pagerModifier = Modifier.fillMaxHeight().width(pagerWidth).testTag("app-pager")
                .semantics { stateDescription = if (pager.currentPage == visibleHomePages) "All apps" else "Home page ${pager.currentPage + 1} of $visibleHomePages" }
                // B23: the rest of the page shrinks 4 % and dims while an icon launch is zooming in.
                .graphicsLayer { scaleX = pageZoomScale.value; scaleY = pageZoomScale.value; alpha = pageZoomDim.value }
            if (geometry.expanded) {
                // B24 follow-up: the rubber band is a purely visual overlay on top of whatever
                // ExpandedWorkspace already renders for the real (always in-bounds) pager
                // position — never the scroll position itself, same split as the cover's
                // PagerRubberBandOverscroll.effectModifier.
                Box(pagerModifier.graphicsLayer { translationX = workspaceEdgeOverscroll.offsetPx }) {
                    // PagerState remains the source of truth for snapping, accessibility
                    // state, and programmatic page requests.
                    HorizontalPager(nativePager, Modifier.fillMaxSize(), userScrollEnabled = false,
                        key = { if (it == visibleHomePages) "library" else "home-$it" }) { }
                    ExpandedWorkspace(
                        nativePager = nativePager, motion = workspaceMotion!!,
                        visibleHomePages = visibleHomePages, logicalPage = pager::logicalOf,
                        spreadPaging = state.innerPagingSpread, panes = panes,
                        contentHeight = contentHeight, bottomSpace = bottomSpace, geometry = geometry,
                        state = state, previewSlots = previewLayout.slots, previewLeadingSlots = previewLayout.leadingSlots,
                        previewWidgetPlacements = previewLayout.widgetPlacements, appsById = appsById,
                        widgets = widgets, drag = drag, target = target, insertionTarget = insertionTarget,
                        libraryQuery = libraryQuery, onLibraryQuery = { libraryQuery = it },
                        onLaunch = zoomLaunch, onLaunchFrom = zoomLaunchFrom, onPinned = model::setPinned,
                        onTurnOnWork = { model.turnOnWork(it) },
                        onActions = { selectedId = it.id }, onWidget = { widgetSlot = it; sheet = "widgetActions" },
                        onFolder = { openFolderId = it },
                        onLaunchPair = zoomLaunchPair, onPairActions = { pairMenuId = it }, pairDropArmed = pairDropArmed,
                        onEmptyWidget = { editModeActive = true; Haptics.play(launcherRootView.context, HapticEvent.EDIT_MODE_ENTER) },
                        onRefresh = model::refresh,
                        onCycleStack = cycleStack,
                        backgroundOrigin = rootOrigin, backgroundSize = rootSize,
                        appearance = appearance,
                        onTodayVisibilityChanged = { todayVisible = it },
                        hubGroups = hubGroups, hubExpanded = hubExpanded,
                        onHubToggle = { dispatchHubPill(HubPillEvent.TAP) },
                        expandedHubGroup = expandedHubGroup,
                        onHubExpandGroup = { expandedHubGroup = it },
                        onHubDismiss = { NotificationHub.dismiss(it) },
                        onHubClearGroup = { group -> group.notifications.forEach { NotificationHub.dismiss(it.key) } },
                        suggestions = suggestions, onSuggestionLaunch = zoomLaunch, onSuggestionBlock = onSuggestionBlock,
                        onSuggestionAddToHome = { app -> model.applyDrop(app.id, DropTarget.Home(firstFreeHomeSlotIndex(state.homeSlots))) },
                    )
                }
            } else {
                HorizontalPager(nativePager, pagerModifier.graphicsLayer {
                        alpha = morph?.let { MorphCurve.coverAlpha(it.coverProgress.value) } ?: 1f
                        // B42 "Dosah na coveru": the whole Home content offsets with the pull; the
                        // rail (StatusRail/dock/search/island, drawn as separate siblings further
                        // down this same Box, outside pagerModifier entirely) never moves.
                        translationY = reachability.offset.value
                    }.reachabilityDismissOnTap(reachability, reduceMotion),
                    // Keep adjacent Home panes attached so ordinary back-and-forth paging does
                    // not synchronously inflate provider RemoteViews inside the gesture frame.
                    beyondViewportPageCount = 1,
                    userScrollEnabled = !drag.active && resizeSlot == null, flingBehavior = pageFling,
                    // B24: iOS-style rubber band past page 0 (before the first Home page) and past
                    // the App Library, instead of Android's stretch/glow overscroll.
                    overscrollEffect = pagerOverscroll,
                    key = { if (it == visibleHomePages) "library" else "home-$it" }) { physicalPage ->
                    // B39 "pager mapping": translate the pager's physical slot back to the real
                    // (logical) Home page number a hidden page can leave a gap in front of.
                    val page = pager.logicalOf(physicalPage)
                    if (physicalPage == visibleHomePages) {
                        // B24 follow-up "App Library peek": how far the pager itself has settled
                        // onto this page, past the last Home page — read live so it recomposes
                        // through the drag/fling, not just once the page snaps.
                        val libraryPeek = libraryPeekProgress(nativePager.currentPage, nativePager.currentPageOffsetFraction, visibleHomePages)
                        AppLibrary(state, libraryQuery, { libraryQuery = it }, zoomLaunch, model::setPinned,
                            onActions = { selectedId = it.id }, modifier = Modifier.fillMaxSize().testTag("library-page"), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = bottomSpace),
                            drag = drag, page = visibleHomePages, onLaunchFrom = zoomLaunchFrom, onTurnOnWork = { model.turnOnWork(it) },
                            expanded = geometry.expanded, isCurrent = inLibrary, peekProgress = libraryPeek)
                    } else {
                        Row(Modifier.fillMaxSize().testTag("home-surface")) {
                            HomePagePane(page, state, previewLayout.slots, previewLayout.leadingSlots, previewLayout.widgetPlacements, appsById, geometry, contentHeight,
                                bottomSpace, widgets, drag, target, insertionTarget, showLargeWidget = false,
                                onLaunch = zoomLaunchFrom, onActions = { selectedId = it.id },
                                onWidget = { widgetSlot = it; sheet = "widgetActions" },
                                onFolder = { openFolderId = it },
                                onLaunchPair = zoomLaunchPair, onPairActions = { pairMenuId = it }, pairDropArmed = pairDropArmed,
                                onEmptyWidget = { editModeActive = true; Haptics.play(launcherRootView.context, HapticEvent.EDIT_MODE_ENTER) },
                                onRefresh = model::refresh,
                                onCycleStack = cycleStack)
                        }
                    }
                }
            }
            // Page indicator: on the cover it is centred over the whole pager; when expanded with
            // a known seam it is centred under the right (Home) pane only, like the Duo dots that
            // sit under the app grid rather than under the whole window.
            val indicatorModifier = if (geometry.expanded && foldSeam != null) {
                Modifier.align(Alignment.BottomStart).offset(x = panes.homeOrigin.dp).width(panes.homeStride.dp).padding(bottom = 6.dp)
            } else Modifier.align(Alignment.BottomStart).width(pagerWidth).padding(start = 16.dp, bottom = 6.dp)
            Column(indicatorModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                trackRecomposition("PageIndicator")
                if (!isDefaultHome) FilledTonalButton(onClick = { sheet = ""; onMakeDefault() }, Modifier.heightIn(min = 48.dp).testTag("home-setup")) {
                    Icon(Icons.Rounded.Home, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Set as home app")
                }
                Row(Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { pageOverviewOpen = true }) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    // "Stránkování inneru jako spread": a distinct Today icon at the start of the
                    // indicator, same shape as the trailing "All apps" link below — Today only
                    // exists on the inner workspace, so this never shows on the cover.
                    if (geometry.expanded) {
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage(0) } },
                            Modifier.size(32.dp).testTag("today-page-link")) {
                            Icon(Icons.Rounded.Today, "Today", tint = Color.White.copy(alpha = if (todayVisible) 1f else .6f),
                                modifier = Modifier.size(17.dp))
                        }
                    }
                    // B39: index is the visible slot; realPage is the logical Home page it shows
                    // (the trailing "+" during editing keeps its old identity, index == homePages).
                    if (visibleHomePages <= 6) repeat(visibleHomePages) { index ->
                        val realPage = if (index < visibleHomeIndices.size) visibleHomeIndices[index] else homePages
                        Box(Modifier.size(28.dp).clip(CircleShape).clickable {
                                // Tapping the trailing "+" (or dropping onto it) commits a real page.
                                if (realPage == homePages) { model.addHomePage(); scope.launch { pager.animateScrollToPage(realPage) } }
                                else scope.launch { pager.animateScrollToPage(realPage) }
                            }
                            .testTag(if (realPage == homePages) "add-home-page-dot" else "home-page-dot-$realPage")
                            .semantics { contentDescription = if (realPage == homePages) "Přidat stránku" else "Home page ${realPage + 1}" }, contentAlignment = Alignment.Center) {
                            if (realPage == homePages) Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            else {
                                // B24: the active dot springs wider and brighter instead of snapping.
                                val current = realPage == pager.currentPage
                                val dotSize by animateDpAsState(if (current) 6.dp else 4.dp,
                                    animationSpec = if (reduceMotion) snap() else spring(dampingRatio = .7f, stiffness = Spring.StiffnessMedium), label = "page-dot-size")
                                val dotAlpha by animateFloatAsState(if (current) 1f else .4f,
                                    animationSpec = if (reduceMotion) snap() else spring(dampingRatio = .7f, stiffness = Spring.StiffnessMedium), label = "page-dot-alpha")
                                Box(Modifier.size(dotSize).background(Color.White.copy(alpha = dotAlpha), CircleShape))
                            }
                        }
                    } else Text("${minOf(pager.currentPage + 1, homePages)} / $homePages", color = Color.White, fontSize = 12.sp)
                    IconButton(onClick = openLibrary, Modifier.size(32.dp).testTag("library-page-link")) {
                        Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "All apps page", tint = Color.White.copy(alpha = if (pager.currentPage == homePages) 1f else .6f), modifier = Modifier.size(17.dp))
                    }
                    // "Mazání stránek s dotazem" (2026-09-17 noc): edit mode's own delete button
                    // for whichever Home page is current — never for Today (pager.currentPage
                    // never is -1, only 0 until homePages here) or the "+"/library slots, and
                    // never when it is the only page left.
                    if (editModeActive && homePages > 1 && pager.currentPage in 0 until homePages) {
                        Spacer(Modifier.width(6.dp))
                        Surface(onClick = { requestDeletePage(pager.currentPage) },
                            modifier = Modifier.size(24.dp).testTag("edit-mode-delete-page"),
                            shape = CircleShape, color = Glass.copy(alpha = .92f)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "Smazat stránku", tint = Ink, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
            // B23: the launched icon itself, growing and fading in place over the shrinking page
            // (forward), or popping back to size over the un-zooming page (return).
            pendingLaunchIcon?.let { icon ->
                val left = icon.boundsInWindow.left - gestureOriginInWindow.x
                val top = icon.boundsInWindow.top - gestureOriginInWindow.y
                Box(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { icon.boundsInWindow.width().toDp() }, with(density) { icon.boundsInWindow.height().toDp() })
                    .graphicsLayer { scaleX = iconZoomScale.value; scaleY = iconZoomScale.value; alpha = iconZoomAlpha.value }
                    .testTag("launch-zoom-icon")) {
                    Image(icon.app.icon.asImageBitmap(), null, Modifier.fillMaxSize().clip(LocalIconStyle.current.clipShape()))
                }
            }
            if (sheet.isNotEmpty() && sheet != "widgets") {
                val activeCustomizationPage = if (sheet == "settings:wallpaper") CustomizationPage.WALLPAPER else customizationPage
                // The widget options sheet is an action sheet: it opens inside the pane of its
                // widget. The customization, dock and pin sheets are scrollable content and span both.
                val widgetActionsPane = if (sheet == "widgetActions") avoidance?.let { av ->
                    av.paneOf(drag.regions[DropTarget.Widget(widgetSlot)]?.bounds, model.placement(widgetSlot)?.page)
                } else null
                ModalBottomSheet(onDismissRequest = {
                    customizationPage = CustomizationPage.OVERVIEW
                    sheet = ""; widgetPackage = null; widgetExactTarget = false
                }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
                    modifier = widgetActionsPane?.let { avoidance?.sheetModifier(it) } ?: Modifier,
                    containerColor = MaterialTheme.colorScheme.surface) {
                    ModalDialogBackHandler {
                        if ((sheet == "settings" || sheet == "settings:wallpaper") &&
                            activeCustomizationPage != CustomizationPage.OVERVIEW) {
                            customizationPage = CustomizationPage.OVERVIEW
                            sheet = "settings"
                        } else {
                            customizationPage = CustomizationPage.OVERVIEW
                            sheet = ""; widgetPackage = null; widgetExactTarget = false
                        }
                    }
                    when (sheet) {
                        "dock" -> AppPicker(state.apps, dockSlot,
                            onSelect = {
                                if (canPlaceInDock(state.layout, it.id)) {
                                    model.applyDrop(it.id, DropTarget.Dock(dockSlot)); sheet = ""
                                }
                            },
                            onClear = { model.removePlacement(DropTarget.Dock(dockSlot)) },
                            onLongClick = { selectedId = it.id; sheet = "" },
                            canSelect = { canPlaceInDock(state.layout, it.id) },
                            blockedHint = if (state.dock.none { it == null }) "Dock full • Move an app out first" else null)
                        "pins" -> Column(Modifier.fillMaxHeight(.9f).imePadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { sheet = "" }) { Text("Done") }
                            }
                            AppLibrary(state, pinQuery, { pinQuery = it }, zoomLaunch, model::setPinned,
                                onActions = { selectedId = it.id; sheet = "" }, editing = true, modifier = Modifier.weight(1f).fillMaxWidth(),
                                onTurnOnWork = { model.turnOnWork(it) })
                        }
                        "settings", "settings:wallpaper" -> CustomizationSheet(state, wide, model, isDefaultHome,
                            page = activeCustomizationPage, onPage = { customizationPage = it; sheet = "settings" },
                            onMakeDefault = { sheet = ""; onMakeDefault() },
                            onClose = { customizationPage = CustomizationPage.OVERVIEW; sheet = "" }, onEditPins = { sheet = "pins" },
                            onWidget = { widgetSlot = it; widgetPackage = null; widgetProfileSerial = null; widgetExactTarget = false; sheet = "widgets" },
                            onAddWidget = { page -> widgetSlot = model.nextWidgetSlot(); widgetTargetIndex = page * HOME_CELLS; widgetPackage = null; widgetProfileSerial = null; widgetExactTarget = false; sheet = "widgets" },
                            onRemoveWidget = widgets::remove,
                            onDeletePage = requestDeletePage,
                            onExportLayout = { sheet = ""; launcherActivity.backups.startExport() },
                            onImportLayout = { sheet = ""; launcherActivity.backups.startImport() },
                            appearance = appearance, onAppearanceMode = onAppearanceMode,
                            onAppearanceManual = onAppearanceManual, onAppearanceDeviceLocation = onAppearanceDeviceLocation,
                            onAppearanceClear = onAppearanceClear,
                            onAppearanceMotionFrost = onAppearanceMotionFrost,
                            onAppearanceSystemFrost = onAppearanceSystemFrost,
                            onAppearanceOpenedOverride = onAppearanceOpenedOverride,
                            onAppearanceHapticAtFlat = onAppearanceHapticAtFlat,
                            onAppearanceMorphFrostFactor = onAppearanceMorphFrostFactor,
                            onAppearanceMorphTiltFactor = onAppearanceMorphTiltFactor,
                            onAppearanceResetMorphPreview = onAppearanceResetMorphPreview,
                            onAppearanceHingeSqueeze = onAppearanceHingeSqueeze,
                            onShadeSetup = { sheet = ""; onShadeSetup() },
                            backgrounds = launcherActivity.backgrounds,
                            onWallpaperPreview = { sheet = ""; onWallpaperPreview() }, homePage = pager.currentPage.coerceIn(0, homePages - 1),
                            onOpenPageOverview = { sheet = ""; pageOverviewOpen = true })
                        "widgetActions" -> model.placement(widgetSlot)?.let { placement ->
                            val topPitch = (geometry.widgetHeight + 18f) / 2f
                            val gridSizing = WidgetGridSizing(GRID_COLUMNS, GRID_ROWS, geometry.gridWidth / GRID_COLUMNS,
                                minOf(topPitch, geometry.rowHeight), maxOf(topPitch, geometry.rowHeight), 10f, 18f,
                                topRowHeightDp = topPitch, appRowHeightDp = geometry.rowHeight)
                            val constraints = widgets.manager.getAppWidgetInfo(placement.id)?.let {
                                widgets.sizing(it, if (placement.page == -1) leadingSizing else gridSizing)
                            }
                            WidgetActions(placement, constraints,
                                canConfigure = widgets.canReconfigure(placement.id),
                                onConfigure = { widgets.reconfigure(placement.id); sheet = "" },
                                isValid = { x, y -> (x == placement.spanX && y == placement.spanY) || resizeWidget(state.layout, widgetSlot, x, y) != state.layout },
                                onResize = { x, y -> model.resizeWidget(widgetSlot, x, y) },
                                onStartResize = { x, y ->
                                    resizeSlot = widgetSlot; resizeWidth = x; resizeHeight = y
                                    resizeConstraints = constraints; sheet = ""
                                },
                                // Same placement path as a drag drop; page -1 is the left pane.
                                onMoveToPage = { page ->
                                    (0 until HOME_CELLS).firstOrNull { local ->
                                        widgetCandidate(state.layout, placement.slot, homeCellIndex(page, local),
                                            placement.spanX, placement.spanY) != null
                                    }?.let { model.moveWidgetTo(placement.slot, homeCellIndex(page, it)) } == true
                                }, homePages = homePages, expanded = geometry.expanded,
                                onReplace = {
                                    widgetPackage = null
                                    widgetProfileSerial = widgets.manager.getAppWidgetInfo(placement.id)?.profile?.let {
                                        launcherActivity.getSystemService(UserManager::class.java).getSerialNumberForUser(it)
                                    }?.takeIf { it >= 0 }
                                    widgetExactTarget = false; sheet = "widgets"
                                },
                                onRemove = { widgets.remove(widgetSlot); sheet = "" },
                                onClose = { sheet = "" },
                                onAppearance = if (supportsWidgetAppearance(placement.id)) { appearance, tint ->
                                    model.setWidgetAppearance(placement.slot, appearance, tint)
                                } else null,
                                wallpaperTint = if (supportsWidgetAppearance(placement.id)) rememberWallpaperTint() else null,
                                onEditStack = { sheet = "stackEdit" }.takeIf { placement.isStack })
                        }
                        "stackEdit" -> model.placement(widgetSlot)?.let { placement ->
                            StackEditSheet(placement, labelFor = { widgetLabel(it, widgets) },
                                onReorder = { from, to -> model.reorderStackMember(widgetSlot, from, to) },
                                onRemoveMember = { memberId ->
                                    model.removeStackMember(widgetSlot, memberId)
                                    if (model.placement(widgetSlot)?.isStack != true) sheet = "widgetActions"
                                },
                                onToggleSmartRotate = { model.setStackSmartRotate(widgetSlot, it) },
                                onClose = { sheet = "widgetActions" })
                        }
                    }
                }
            }
            if (showFirstRun) {
                ModalBottomSheet(
                    onDismissRequest = onFinishFirstRun,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("first-run-setup"),
                ) {
                    FirstRunSetupSheet(
                        isDefaultHome = isDefaultHome,
                        onMakeDefault = onMakeDefault,
                        onAddWidget = {
                            onFinishFirstRun()
                            widgetSlot = model.nextWidgetSlot()
                            widgetTargetIndex = pager.currentPage.coerceIn(0, homePages - 1) * HOME_CELLS
                            widgetPackage = null
                            widgetProfileSerial = null
                            widgetExactTarget = false
                            sheet = "widgets"
                        },
                        onExplore = onFinishFirstRun,
                        onSkip = onFinishFirstRun,
                    )
                }
            }
            if (sheet == "widgets") {
                val catalogProfiles = remember(state.profiles) { state.profiles.filter { it.isPersonal || it.isWork } }
                val selectedProfile = catalogProfiles.firstOrNull { it.userSerial == widgetProfileSerial }
                    ?: catalogProfiles.firstOrNull { it.isPersonal } ?: AppProfile(0, "Personal", true, false, false, true, true)
                val userManager = remember(launcherActivity) { launcherActivity.getSystemService(UserManager::class.java) }
                val providers = remember(widgetPackage, selectedProfile, sheet, state.apps) {
                    val user = userManager.getUserForSerialNumber(selectedProfile.userSerial)
                    if (user == null || !selectedProfile.available || !selectedProfile.unlocked || selectedProfile.quiet) emptyList()
                    else runCatching { widgetPackage?.let { widgets.providersForPackage(it, user) }
                        ?: widgets.providers(user) }.getOrDefault(emptyList()).filter { provider ->
                        provider.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 &&
                            provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
                    }
                }
                val catalog by produceState<List<WidgetCatalogEntry>?>(null, providers, selectedProfile.userSerial, sheet) {
                    value = withContext(Dispatchers.IO) { widgetCatalog(launcherActivity, providers, selectedProfile) }
                }
                val topPitch = (geometry.widgetHeight + 18f) / 2f
                val pickerSizing = remember(geometry) { WidgetGridSizing(GRID_COLUMNS, GRID_ROWS,
                    geometry.gridWidth / GRID_COLUMNS, minOf(topPitch, geometry.rowHeight),
                    maxOf(topPitch, geometry.rowHeight), 10f, 18f,
                    topRowHeightDp = topPitch, appRowHeightDp = geometry.rowHeight) }
                val footprint: (AppWidgetProviderInfo) -> WidgetSpan? = { provider ->
                    widgets.sizing(provider, pickerSizing)?.takeIf { it.minimumFitsGrid }?.preferred
                }
                // Page -1 (the leading canvas) places through the same session as Home pages; a
                // draft there publishes the leading cell size to its provider.
                fun sizingFor(page: Int) = if (page == -1) leadingSizing else pickerSizing
                // Reveal the page a placement session targets. The leading canvas is always
                // beside the current Home page, so it needs no scroll unless All apps is open.
                fun revealPage(page: Int) { scope.launch {
                    if (page >= 0) pager.scrollToPage(page.coerceIn(0, homePages))
                    else if (pager.currentPage >= visibleHomePages) pager.scrollToPage(lastHomePage.coerceIn(0, homePages - 1))
                } }
                VisualWidgetPicker(catalog, catalogProfiles.ifEmpty { listOf(selectedProfile) }, selectedProfile,
                    endInset = geometry.railWidth.dp,
                    onSelectProfile = { widgetProfileSerial = it.userSerial; widgetPlacementMessage = null },
                    onTurnOnWork = { model.turnOnWork(it) }, hiddenForDrag = widgetSession != null,
                    footprint = footprint,
                    onBack = widgetPickerBack,
                    onTap = { provider ->
                        footprint(provider)?.let { preferredSpan ->
                            val existing = model.placement(widgetSlot)
                            val constraints = widgets.sizing(provider, pickerSizing)
                            val span = existing?.let { placement ->
                                WidgetSpan(placement.spanX, placement.spanY).takeIf {
                                    constraints != null && it.width in constraints.minimum.width..constraints.maximum.width &&
                                        it.height in constraints.minimum.height..constraints.maximum.height
                                }
                            } ?: preferredSpan
                            val special = existing?.takeIf { it.row + it.spanY > GRID_ROWS }
                            if (special != null) {
                                widgetSession = WidgetPickerSession(provider, widgetSlot,
                                    WidgetSpan(special.spanX, special.spanY), Offset.Zero,
                                    dragging = false, candidate = special)
                                widgetPlacementMessage = null
                                scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                                return@let
                            }
                            val requestedIndex = existing?.let { homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column) }
                                ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                            val requestedPage = homeCellPage(requestedIndex).coerceIn(if (expandedWorkspace) -1 else 0, homePages)
                            val availablePages = (if (expandedWorkspace) -1 else 0)..homePages
                            val autoPages = (listOf(requestedPage) + availablePages.filter { it != requestedPage })
                            val freeIndex = if (existing != null || widgetExactTarget) requestedIndex.takeIf {
                                widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                            } else autoPages.asSequence().flatMap { page ->
                                (0 until HOME_CELLS).asSequence().map { homeCellIndex(page, it) }
                            }.firstOrNull { widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null }
                            val targetIndex = freeIndex ?: requestedIndex
                            widgetSession = WidgetPickerSession(provider, widgetSlot, span, Offset.Zero,
                                dragging = false, targetIndex = targetIndex)
                            widgetPlacementMessage = if (freeIndex == null) NO_ROOM_FOR_SIZE_MESSAGE else null
                            revealPage(homeCellPage(targetIndex))
                        }
                    },
                    onBuiltin = builtin@{ builtinId ->
                        val existing = model.placement(widgetSlot)
                        val special = existing?.takeIf { it.row + it.spanY > GRID_ROWS }
                        val requested = existing?.let {
                            homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column)
                        } ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                        val requestedPage = homeCellPage(requested).coerceIn(if (expandedWorkspace) -1 else 0, homePages)
                        // A built-in picked for the leading canvas takes its pane footprint (Photos: the whole pane).
                        val span = existing?.let { WidgetSpan(it.spanX, it.spanY) }
                            ?: if (requestedPage == -1) leadingBuiltinSpan(builtinId) else builtinWidgetSpan(builtinId)
                        if (special != null) {
                            widgetSession = WidgetPickerSession(null, widgetSlot, span, Offset.Zero,
                                dragging = false, candidate = special, builtinId = builtinId)
                            widgetPlacementMessage = null
                            scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                            return@builtin
                        }
                        val availablePages = (if (expandedWorkspace) -1 else 0)..homePages
                        val candidates = if (model.placement(widgetSlot) != null || widgetExactTarget) sequenceOf(requested)
                            else (listOf(requestedPage) + availablePages.filter { it != requestedPage }).asSequence()
                                .flatMap { page -> (0 until HOME_CELLS).asSequence().map { homeCellIndex(page, it) } }
                        val free = candidates.firstOrNull {
                            widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                        }
                        widgetSession = WidgetPickerSession(null, widgetSlot, span, Offset.Zero,
                            dragging = false, targetIndex = free ?: requested, builtinId = builtinId)
                        widgetPlacementMessage = if (free == null)
                            "There isn’t room for this card. Choose another page or move an item first." else null
                        revealPage(homeCellPage(free ?: requested))
                    },
                    onDragStart = { provider, point ->
                        footprint(provider)?.let { span ->
                            widgetSession = WidgetPickerSession(provider, widgetSlot, span, point, dragging = true)
                            widgetPlacementMessage = null
                            scope.launch { pager.scrollToPage(lastHomePage.coerceIn(0, homePages - 1)) }
                        }
                    },
                    onDrag = { point -> widgetSession = widgetSession?.copy(pointer = point) },
                    onDrop = {
                        val session = widgetSession
                        if (session != null && widgetDraft != null) {
                            session.provider?.let { widgets.add(widgetDraft, it, sizingFor(widgetDraft.page)) }
                                ?: session.builtinId?.let { widgets.setBuiltin(widgetDraft.copy(id = it)) }
                            widgetSession = null; sheet = ""; widgetPackage = null
                        } else {
                            leaveTemporaryWidgetPage(); widgetSession = null
                            widgetPlacementMessage = "There isn’t room there. Try another space or page."
                        }
                    },
                    onCancelDrag = {
                        if (widgetSession != null) {
                            leaveTemporaryWidgetPage(); widgetSession = null
                        }
                    })
                widgetSession?.let { session ->
                    val placementDensity = LocalDensity.current
                    val sessionEntry = session.provider?.let { selected -> catalog?.firstOrNull {
                        it.provider.provider == selected.provider && it.provider.profile == selected.profile } }
                    // Legacy overflow replacements are locked to their existing view
                    // bounds and may begin below the canonical seven-row grid. They have
                    // no Home-cell address; specialAnchor below is their visual anchor.
                    val candidateIndex = widgetDraft?.takeIf { session.candidate == null }
                        ?.let { homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column) }
                    val visualIndex = candidateIndex ?: widgetRawTarget?.index ?: session.targetIndex
                    val specialAnchor = session.candidate?.let { drag.regions[DropTarget.Widget(session.slot)]?.bounds }
                    val anchor = specialAnchor ?: visualIndex?.let { drag.regions[DropTarget.Home(it)]?.bounds }
                    Box(Modifier.fillMaxSize().testTag("widget-placement-mode")
                        .then(if (!session.dragging && session.candidate == null) Modifier.pointerInput(session.slot, session.span) {
                            detectTapGestures { local ->
                                val point = local + drag.rootOrigin
                                val cell = drag.regions.values.firstOrNull {
                                    it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(point)
                                }?.target as? DropTarget.Home
                                cell?.let { widgetSession = session.copy(pointer = point, targetIndex = it.index) }
                            }
                        } else Modifier)) {
                        // The picker stays full width (scrollable content); its action row sits
                        // inside the pane of the target cell, or under the pointer while dragging.
                        val toolbarPane = avoidance?.let { av ->
                            val targetPage = session.candidate?.page ?: visualIndex?.let(::homeCellPage)
                            if (anchor == null && session.dragging) av.paneAt(session.pointer.x) else av.paneOf(anchor, targetPage)
                        }
                        val toolbarModifier = if (toolbarPane != null) Modifier.align(Alignment.TopStart)
                            .offset(x = toolbarPane.start.dp).width(toolbarPane.extentDp.dp).statusBarsPadding().padding(top = 8.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                        else Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, end = geometry.railWidth.dp)
                        Row(toolbarModifier.background(Glass.copy(alpha = .97f), RoundedCornerShape(22.dp))
                            .testTag("widget-placement-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = widgetPickerBack) { Text("Back to widgets") }
                            if (session.candidate != null) Text("Replace here", color = Ink,
                                modifier = Modifier.testTag("widget-replacement-locked"))
                            val targetPage = homeCellPage(session.targetIndex ?: 0)
                            if (!session.dragging && session.candidate == null) IconButton(
                                enabled = targetPage > if (expandedWorkspace) -1 else 0, onClick = {
                                val local = homeCellLocal(session.targetIndex ?: 0)
                                val page = targetPage - 1
                                widgetSession = session.copy(targetIndex = homeCellIndex(page, local))
                                scope.launch { pager.animateScrollToPage(page.coerceAtLeast(0)) }
                            }) { Icon(Icons.Rounded.ChevronLeft, "Previous home page") }
                            Text("${session.span.width} × ${session.span.height}", color = Ink)
                            if (!session.dragging && session.candidate == null) IconButton(enabled = targetPage < homePages, onClick = {
                                val local = homeCellLocal(session.targetIndex ?: 0)
                                val page = (targetPage + 1).coerceAtMost(homePages)
                                widgetSession = session.copy(targetIndex = homeCellIndex(page, local))
                                scope.launch { pager.animateScrollToPage(page.coerceAtLeast(0)) }
                            }) { Icon(Icons.Rounded.ChevronRight, "Next home page") }
                            if (!session.dragging) TextButton(enabled = widgetDraft != null, onClick = {
                                widgetDraft?.let { draft ->
                                    val contentSize = specialAnchor?.let { bounds -> with(placementDensity) {
                                        WidgetContentSize(bounds.width.toDp().value, bounds.height.toDp().value)
                                    } }
                                    session.provider?.let { widgets.add(draft, it, sizingFor(draft.page), contentSize) }
                                        ?: session.builtinId?.let { widgets.setBuiltin(draft.copy(id = it)) }
                                    widgetSession = null; sheet = ""; widgetPackage = null
                                }
                            }, modifier = Modifier.testTag("widget-placement-apply")) { Text("Place") }
                            TextButton(onClick = { leaveTemporaryWidgetPage(); widgetSession = null; sheet = ""; widgetPackage = null },
                                modifier = Modifier.testTag("widget-placement-cancel")) { Text("Cancel") }
                        }
                        if (anchor != null) {
                            val density = LocalDensity.current
                            // The anchored cell's own width: leading cells are wider than Home cells.
                            val cellWidthPx = anchor.width
                            fun pickerRowTop(row: Int): Float = if (row <= 2) row * with(density) { topPitch.dp.toPx() }
                                else with(density) { (geometry.widgetHeight + 18f + (row - 2) * geometry.rowHeight).dp.toPx() }
                            val candidateRow = homeCellLocal(visualIndex ?: 0) / GRID_COLUMNS
                            val previewWidth = specialAnchor?.let { with(density) { it.width.toDp() } }
                                ?: with(density) { (cellWidthPx * session.span.width - 10.dp.toPx()).toDp() }
                            val previewHeight = specialAnchor?.let { with(density) { it.height.toDp() } }
                                ?: with(density) { (pickerRowTop(candidateRow + session.span.height) -
                                    pickerRowTop(candidateRow) - 18.dp.toPx()).coerceAtLeast(48.dp.toPx()).toDp() }
                            val previewX = if (specialAnchor != null) anchor.left
                                else anchor.left + with(density) { 5.dp.toPx() }
                            Surface(Modifier.offset { IntOffset(previewX.roundToInt(), anchor.top.roundToInt()) }
                                .size(previewWidth, previewHeight).testTag("widget-placement-preview")
                                .semantics { stateDescription = if (widgetDraft != null) "Ready to place" else "No room here" },
                                color = if (widgetDraft != null) Glass.copy(alpha = .82f) else Color(0xFFE7B6B6).copy(alpha = .9f),
                                shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(3.dp,
                                    if (widgetDraft != null) Color.White else Color(0xFFFF6B6B))) {
                                Box(Modifier.fillMaxSize()) {
                                    if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    else Column(Modifier.align(Alignment.Center).padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(session.provider?.loadLabel(launcherActivity.packageManager)?.toString()
                                            ?: builtinWidgetLabel(session.builtinId ?: INFO_WIDGET), color = Ink,
                                            textAlign = TextAlign.Center)
                                        Text("${session.span.width} × ${session.span.height}", color = Ink)
                                    }
                                    if (widgetDraft == null) Box(Modifier.matchParentSize()
                                        .background(Color(0xFFB83B3B).copy(alpha = .34f)), contentAlignment = Alignment.Center) {
                                        Text("No room here", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        } else if (session.dragging) {
                            Surface(Modifier.offset { IntOffset((session.pointer.x - 90.dp.toPx()).roundToInt(),
                                (session.pointer.y - 60.dp.toPx()).roundToInt()) }.size(180.dp, 120.dp)
                                .testTag("widget-placement-preview").semantics { stateDescription = "No room here" },
                                color = Color(0xFFE7B6B6).copy(alpha = .9f), shape = RoundedCornerShape(24.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    Box(Modifier.matchParentSize().background(Color(0xFFB83B3B).copy(alpha = .34f)),
                                        contentAlignment = Alignment.Center) { Text("No room here", color = Color.White) }
                                }
                            }
                        }
                    }
                }
                widgetPlacementMessage?.let { message ->
                    Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).padding(end = geometry.railWidth.dp),
                        color = Glass, shape = RoundedCornerShape(18.dp)) { Text(message, Modifier.padding(16.dp), color = Ink) }
                }
            }
            openFolderId?.let { id ->
                state.folders.firstOrNull { it.id == id }?.let { folder ->
                    val blocked = state.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
                    // Unfolded, the leading canvas (page -1) is a destination like any Home page.
                    val destinationPages = (if (expandedWorkspace) listOf(-1) else emptyList()) + (0 until homePages)
                    val homeDestinations = destinationPages.mapNotNull { destinationPage ->
                        (0 until HOME_CELLS).map { homeCellIndex(destinationPage, it) }
                            .firstOrNull { it !in blocked && state.layout.slotAt(it) == null }
                    }
                    FolderPanel(folder, appsById, drag, pager.currentPage, homeDestinations,
                        dockVacancies = state.dock.indices.filter { state.dock[it] == null },
                        onDismiss = { openFolderId = null }, onRename = { model.renameFolder(id, it) },
                        onLaunch = zoomLaunchFrom, endInset = geometry.railWidth.dp,
                        // Opens in the pane of the tapped folder icon (its drop region); dock folders go right.
                        pane = paneOfCell(state.layout.indexOfShortcut(folder.id)),
                        // Glass-card morph (IDEAS B25): the icon's own drop region already tracks
                        // its root bounds for drag/pane purposes; reused here as the open/close anchor.
                        iconBounds = drag.regions[DropTarget.Folder(folder.id)]?.bounds,
                        // B25 follow-up: a drag picked up from inside this folder closes it the
                        // same way Back/tap-outside/Done do — reverse spring, then onDismiss.
                        closeRequested = drag.active && drag.source?.folderId == folder.id,
                        onProgress = { drag.openFolderCardProgress = it },
                        onMoveOut = { appId, destination ->
                            if (model.removeAppFromFolder(id, appId, destination)) openFolderId = model.folder(id)?.id
                        })
                } ?: LaunchedEffect(id) { openFolderId = null }
            }
            // Persistent chrome (iPhone Duo): the rail draws above every content surface (Home
            // pages, App Library, folder panels, the widget picker) and below sheets and dialogs.
            // It stays inside the page-gesture box so swipes over it still page and open the shade.
            if (state.verticalStatus) StatusRail(deviceStatus,
                Modifier.align(Alignment.TopEnd).padding(end = RAIL_EDGE_PADDING_DP.dp).offset(y = rail.statusTop.dp)
                    .width(preset.dockWidth.dp).onSizeChanged {
                        statusHeight = with(density) { it.height.toDp().value }
                    },
                compact = contentHeight < 500.dp, iconSize = dockIconSize(geometry.iconSize).dp,
                onOpenStatusCard = { statusCardOpen = true; Haptics.play(hapticContext, HapticEvent.ISLAND_EXPAND) },
                cardOpen = statusCardOpen,
                onRingBounds = { statusRingBounds = it })
            // The island (rail.islandTop) is drawn after the dock and search below so its
            // expanded card overlays them; see the end of this box.
            // B41 Liquid Glass: the dock's own slab of glass refracts the wallpaper behind it
            // (same shader as the icons/folders) instead of the flat tint, gated the same way.
            val dockCorner = RoundedCornerShape(30.dp)
            Box(Modifier.align(Alignment.TopEnd).padding(end = RAIL_EDGE_PADDING_DP.dp).offset(y = rail.dockTop.dp)
                .width(preset.dockWidth.dp).height(rail.dockHeight.dp).graphicsLayer {
                    // Composite the stationary dock independently of the shared pager layer.
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }
                .clip(dockCorner)
                // Výkon 3: the dock never bulges, so it always qualifies for the cached bitmap
                // path instead of a live per-frame RenderEffect layer.
                .then(if (state.iconStyle.usesLiquidGlass) Modifier.liquidGlassPanelCached(dockCorner, 30.dp)
                    else Modifier.background(Glass.copy(alpha = .32f)))
                .border(1.dp, Color.White.copy(alpha = .3f), dockCorner)
                .testTag("dock")) {
                Column(Modifier.padding(vertical = 8.dp).verticalScroll(dockScroll)) {
                    DockAppColumn(state.dock, previewLayout.dock, appsById, rail.dockRowHeight,
                        dockIconSize(geometry.iconSize), drag, insertionTarget,
                        onLaunch = zoomLaunchFrom, onChoose = { dockSlot = it; sheet = "dock" })
                }
            }
            // Search is anchored to the bottom of the rail (rail.searchTop, level with the page
            // dots); a drag hides it and shows the removal target above the dock instead.
            if (!inLibrary && !drag.active && sheet.isEmpty()) {
                val controlSize = rail.searchSize.dp
                val searchBounds = remember { android.graphics.Rect() }
                Box(Modifier.align(Alignment.TopEnd).padding(end = RAIL_EDGE_PADDING_DP.dp)
                    .offset(y = rail.searchTop.dp).height(controlSize)
                    .width(preset.dockWidth.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.onGloballyPositioned { searchBounds.set(it.boundsInWindow().toAndroidBounds()) }) {
                        CircleControl(Icons.Rounded.Search, if (state.googleSearch) "Search Google" else "Search apps", "search", controlSize) {
                            if (!state.googleSearch || !onGoogleSearch(searchBounds)) openSpotlight()
                        }
                    }
                }
            }
            if (drag.moved && drag.source?.target !is DropTarget.Library &&
                drag.source?.appId?.let(::isFolderId) != true) Surface(
                // Removal lives in the rail directly above the dock while the search control
                // below it is hidden. A centered target overlaps the expanded workspace's first cell.
                Modifier.align(Alignment.TopEnd).padding(end = RAIL_EDGE_PADDING_DP.dp)
                    .offset(y = (rail.dockTop - RAIL_GAP_DP - 64f).dp)
                    .width(preset.dockWidth.dp).height(64.dp)
                    .dropRegion(drag, DropTarget.Remove).testTag("remove-drop-target"),
                color = if (target == DropTarget.Remove) Color(0xFFB33B3B) else Glass.copy(alpha = .96f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(vertical = 6.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Text("Remove", fontSize = 11.sp, maxLines = 1)
                }
            }
            // Rail island (PLAN.md rail item 3, iPhone Duo's vertical Dynamic Island): collapsed
            // pills directly under the status block; a tap expands one into a card grown into the
            // page ("Ostrůvek do plochy", rendered by the root-level IslandExpandedOverlay next to
            // SpotlightOverlay, not here). A tap anywhere else collapses it (the scrim sits under
            // the island, above everything else); so does a 6 s timeout or a swipe up on the card.
            if (islandExpanded != null) Box(Modifier.fillMaxSize().testTag("island-scrim")
                .pointerInput(Unit) { detectTapGestures { islandExpanded = null } })
            if (islandItems.isNotEmpty() && !drag.active) RailIsland(islandItems, islandExpanded, { islandExpanded = it },
                Modifier.align(Alignment.TopEnd).padding(end = RAIL_ISLAND_RIGHT_PADDING_DP.dp).offset(y = rail.islandTop.dp)
                    .width((geometry.railWidth - 16f).dp))
            // B34 on the cover: the Today canvas (page -1) never shows there (pane identity), so
            // an unread count is all the cover gets — a small badge in the rail beside the island,
            // instead of the glass cards the inner panel shows.
            if (!geometry.expanded && hubGroups.isNotEmpty()) NotificationHubBadge(
                count = hubGroups.sumOf { it.notifications.size },
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset(x = -(geometry.railWidth - RAIL_EDGE_PADDING_DP - 4f).dp, y = (rail.statusTop - 2f).dp))
        }
        if (drag.active) {
            if (drag.moved) {
                if (pager.currentPage > 0) Box(Modifier.align(Alignment.CenterStart).width(6.dp).height(112.dp)
                    .background(Color.White.copy(alpha = if (edge < 0) .9f else .3f), RoundedCornerShape(6.dp)).testTag("drag-edge-left"))
                if (pager.currentPage < homePages) Box(Modifier.align(Alignment.CenterEnd).width(6.dp).height(112.dp)
                    .background(Color.White.copy(alpha = if (edge > 0) .9f else .3f), RoundedCornerShape(6.dp)).testTag("drag-edge-right"))
            }
            appsById[drag.source?.appId]?.let { app ->
                val size = 66.dp
                val px = with(LocalDensity.current) { size.toPx() }
                // B24: the picked-up icon springs up to 1.15x instead of appearing at full size.
                val pickupScale by animateFloatAsState(if (drag.active) DRAG_PICKUP_SCALE else 1f,
                    animationSpec = if (reduceMotion) snap() else DragPickupSpring, label = "drag-pickup-scale")
                // B41 Liquid Glass: the picked-up icon's glass "pools" while dragging (bulge
                // 0->1, +4dp corner radius), dropping releases it; never under reduced motion.
                val bulge by animateFloatAsState(if (!reduceMotion && drag.active) 1f else 0f,
                    animationSpec = if (reduceMotion) snap() else DragPickupSpring, label = "drag-glass-bulge")
                val dragIconStyle = LocalIconStyle.current
                Box(Modifier
                    .offset { IntOffset((drag.pointer.x - drag.rootOrigin.x - px / 2).roundToInt(), (drag.pointer.y - drag.rootOrigin.y - px * .65f).roundToInt()) }
                    .size(size).graphicsLayer { scaleX = pickupScale; scaleY = pickupScale }
                    .shadow(16.dp, dragIconStyle.clipShape()).testTag("drag-ghost")) {
                    if (dragIconStyle.usesLiquidGlass) Box(Modifier.matchParentSize().liquidGlassPanel(
                        dragIconStyle.clipShape(), (size.value * ROUNDED_SQUARE_RADIUS).dp + 4.dp * bulge, bulge = { bulge }))
                    Image(app.icon.asImageBitmap(), "Moving ${app.label}", Modifier.fillMaxSize().clip(dragIconStyle.clipShape()))
                }
            }
            drag.source?.appId?.let { state.layout.folder(it) }?.let { folder ->
                Surface(Modifier.offset { IntOffset((drag.pointer.x - drag.rootOrigin.x - 42.dp.toPx()).roundToInt(),
                    (drag.pointer.y - drag.rootOrigin.y - 52.dp.toPx()).roundToInt()) }.size(84.dp)
                    .shadow(16.dp, RoundedCornerShape(20.dp)).testTag("folder-drag-ghost"),
                    color = Glass.copy(alpha = .96f), shape = RoundedCornerShape(20.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(folder.title, color = Ink, textAlign = TextAlign.Center) }
                }
            }
            drag.source?.widgetId?.let { id ->
                val width = 144.dp; val height = 108.dp
                val x = with(LocalDensity.current) { width.toPx() }
                val y = with(LocalDensity.current) { height.toPx() }
                Surface(Modifier.offset { IntOffset((drag.pointer.x - x / 2).roundToInt(), (drag.pointer.y - y * .65f).roundToInt()) }
                    .size(width, height).shadow(16.dp, RoundedCornerShape(24.dp)).testTag("drag-ghost"),
                    color = Glass.copy(alpha = .95f), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Widgets, null, tint = Ink)
                        Spacer(Modifier.height(8.dp))
                        Text(remember(id, widgets) { widgetLabel(id, widgets) }, color = Ink, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
            if (blockedDock) Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding()
                    .padding(top = 10.dp, start = 20.dp, end = geometry.railWidth.dp + 8.dp),
                color = Glass.copy(alpha = .96f), shape = RoundedCornerShape(18.dp)
            ) {
                Text("Dock full • Move an app out first",
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Ink, fontSize = 13.sp)
            }
        }
        resizeSlot?.let { slot ->
            val placement = model.placement(slot)
            val bounds = drag.regions[DropTarget.Widget(slot)]?.bounds
            if (placement != null && bounds != null) {
                val minW = resizeConstraints?.minimum?.width ?: 2
                val minH = resizeConstraints?.minimum?.height ?: 2
                val maxW = minOf(GRID_COLUMNS - placement.column, resizeConstraints?.maximum?.width ?: GRID_COLUMNS)
                val maxH = minOf(GRID_ROWS - placement.row, resizeConstraints?.maximum?.height ?: GRID_ROWS)
                val feasible = placement.page >= -1 && placement.row in 0 until GRID_ROWS &&
                    !(placement.id >= 0 && resizeConstraints == null) && minW <= maxW && minH <= maxH
                val candidate = resizeWidget(state.layout, slot, resizeWidth, resizeHeight)
                val valid = feasible && ((resizeWidth == placement.spanX && resizeHeight == placement.spanY) || candidate != state.layout)
                val density = LocalDensity.current
                // Column pitch of the widget's own grid (leading cells are wider than Home cells).
                val resizePitchX = (bounds.width + with(density) { 10.dp.toPx() }) / placement.spanX
                val widthPx = (bounds.width + (resizeWidth - placement.spanX) * resizePitchX).coerceAtLeast(resizePitchX)
                fun resizeRowTop(row: Int) = if (row <= 2) row * resizeTopPitch else 2 * resizeTopPitch + (row - 2) * resizeAppPitch
                val heightPx = (resizeRowTop(placement.row + resizeHeight) - resizeRowTop(placement.row) -
                    with(density) { 18.dp.toPx() }).coerceAtLeast(resizePitchY)
                Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .border(3.dp, if (valid) Color.White else Color(0xFFFF6B6B), RoundedCornerShape(24.dp))
                    .testTag("widget-resize-preview-$slot")) {
                    Box(Modifier.align(Alignment.BottomEnd).offset(12.dp, 12.dp).size(44.dp)
                        .background(if (valid) Color.White else Color(0xFFFF6B6B), CircleShape)
                        .testTag("widget-resize-handle-$slot")
                        .pointerInput(slot, resizeConstraints) {
                            var dx = 0f; var dy = 0f; var startWidth = resizeWidth; var startHeight = resizeHeight
                            detectDragGestures(onDragStart = {
                                dx = 0f; dy = 0f; startWidth = resizeWidth; startHeight = resizeHeight
                            }, onDrag = { change, amount ->
                                change.consume(); dx += amount.x; dy += amount.y
                                if (feasible && resizeConstraints?.canResizeHorizontally != false)
                                    resizeWidth = (startWidth + (dx / resizePitchX).roundToInt()).coerceIn(minW, maxW)
                                if (feasible && resizeConstraints?.canResizeVertically != false)
                                    resizeHeight = (startHeight + (dy / resizePitchY).roundToInt()).coerceIn(minH, maxH)
                            })
                        }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.OpenInFull, "Drag to resize widget", tint = Ink, modifier = Modifier.size(22.dp))
                    }
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        .background(Glass.copy(alpha = .96f), RoundedCornerShape(20.dp))) {
                        TextButton(onClick = { resizeSlot = null }) { Text("Cancel") }
                        TextButton(enabled = valid, onClick = {
                            model.resizeWidget(slot, resizeWidth, resizeHeight); resizeSlot = null
                        }) { Text("Apply") }
                    }
                    if (!feasible) Text("Move this widget into the seven-row grid before resizing.",
                        color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .65f)).padding(8.dp))
                }
            }
        }
        appsById[selectedId]?.let { app ->
            val pinned = state.layout.indexOfShortcut(app.id) != null
            val packageName = app.packageName
            // B49 "Quick look": the app's first home-screen widget provider, for the preview
            // section below — the same lookup `hasWidgets` used, kept around instead of thrown away.
            val previewProviders = remember(packageName, app.user) {
                if (packageName.isEmpty()) emptyList() else runCatching {
                    widgets.providersForPackage(packageName, app.user).filter {
                        it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                    }
                }.getOrDefault(emptyList())
            }
            val hasWidgets = previewProviders.isNotEmpty()
            val previewProvider = previewProviders.firstOrNull()
            val previewEntry by produceState<WidgetCatalogEntry?>(null, previewProvider, app.userSerial) {
                value = previewProvider?.let { provider ->
                    withContext(Dispatchers.IO) {
                        widgetCatalog(launcherActivity, listOf(provider),
                            AppProfile(app.userSerial, app.profileLabel, !app.isWork, app.isWork, quiet = false, unlocked = true, available = true))
                            .firstOrNull()
                    }
                }
            }
            val previewSpan = remember(previewProvider, leadingSizing) {
                previewProvider?.let { widgets.sizing(it, leadingSizing)?.preferred } ?: WidgetSpan(2, 2)
            }
            // Fold avoidance: the sheet opens inside the pane of the pressed icon (Home cell or
            // library row); dock icons and anything unplaced default to the right pane.
            val appSheetModifier = foldAvoidance?.let { av ->
                val index = state.layout.indexOfShortcut(app.id)
                av.sheetModifier(av.paneOf(index?.let { drag.regions[DropTarget.Home(it)]?.bounds }
                    ?: drag.regions[DropTarget.Library(app.id)]?.bounds, index?.let(::homeCellPage)))
            } ?: Modifier
            ModalBottomSheet(onDismissRequest = { appMoveMenu = false; selectedId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
                modifier = appSheetModifier) {
                LauncherAppActionSheet(app, pinned, homePages, appMoveMenu, { appMoveMenu = it },
                    onAddOrRemove = { model.setPinned(app.id, !pinned); model.trimTrailingEmptyPages(); selectedId = null },
                    onMoveFirst = { model.move(app.id, -maxOf(HOME_CELLS, state.homeSlots.size)); selectedId = null },
                    onMoveEarlier = { model.move(app.id, -1); selectedId = null },
                    onMoveLater = { model.move(app.id, 1); selectedId = null },
                    onMovePage = { page -> model.applyDrop(app.id, DropTarget.Home(homeCellIndex(page, 0))); selectedId = null },
                    onInfo = { onAppInfo(app); selectedId = null },
                    onWidgets = if (hasWidgets) {{
                        val page = lastHomePage.coerceIn(0, homePages - 1)
                        widgetTargetIndex = homeCellIndex(page, 0); widgetExactTarget = false
                        widgetSlot = model.nextWidgetSlot(); widgetPackage = packageName
                        widgetProfileSerial = app.userSerial; selectedId = null; sheet = "widgets"
                    }} else null,
                    widgetPreview = previewEntry, widgetPreviewSpan = previewSpan,
                    // "Add to Today" pre-targets the leading (Today) pane's first free cell and
                    // opens the same picker/bind flow as "Widgets" above (no temporary binding) —
                    // filtered to this package, it's a one-tap confirm when there's one provider.
                    onAddToToday = if (previewProvider != null && expandedWorkspace) {{
                        widgetTargetIndex = homeCellIndex(-1, 0); widgetExactTarget = false
                        widgetSlot = model.nextWidgetSlot(); widgetPackage = packageName
                        widgetProfileSerial = app.userSerial; selectedId = null; sheet = "widgets"
                    }} else null,
                    onCreateFolder = { createFolderFirstId = app.id; selectedId = null },
                    onClose = { appMoveMenu = false; selectedId = null })
            }
        }
        // iPhone-style icon popover (2026-09-17 evening): anchored right next to the pressed
        // icon, in the same root coordinate space the drag ghost above already uses (region
        // bounds are root-relative; drag.rootOrigin converts to this Box's own local space).
        drag.iconPopover?.let { region ->
            appsById[region.appId]?.let { app ->
                val placed = state.layout.indexOfShortcut(app.id) != null
                // iOS style (2026-09-17 night): keep the card inside the icon's own pane, same
                // fold-avoidance FoldAvoidance already gives every sheet/dialog — converted from
                // its content-frame dp range into this popover's root-minus-rootOrigin px space.
                val paneRangeX = foldAvoidance?.let { av ->
                    val pane = av.paneOf(region.bounds)
                    val lo = av.contentOriginRootX + pane.start * av.density - drag.rootOrigin.x
                    val hi = av.contentOriginRootX + pane.endInclusive * av.density - drag.rootOrigin.x
                    lo..hi
                }
                IconContextPopover(
                    app = app,
                    anchor = region.bounds.translate(-drag.rootOrigin.x, -drag.rootOrigin.y),
                    screenSize = Size(rootSize.width.toFloat(), rootSize.height.toFloat()),
                    placed = placed,
                    isDefaultHome = isDefaultHome,
                    onDismiss = { drag.iconPopover = null },
                    onEditHome = { editModeActive = true },
                    onMovePage = { selectedId = app.id; appMoveMenu = true; drag.iconPopover = null },
                    onAppInfo = { onAppInfo(app) },
                    onToggleHome = { model.setPinned(app.id, !placed); model.trimTrailingEmptyPages() },
                    onMore = { selectedId = app.id; drag.iconPopover = null },
                    paneRangeX = paneRangeX,
                )
            }
        }
        // Home edit ("jiggle") mode top bar + its "+" menu (2026-09-17 evening): the "+" menu
        // reuses EmptySpaceActionSheet's three actions — this replaces the old long-press-on-
        // wallpaper bottom sheet, which used to open EmptySpaceActionSheet directly.
        if (editModeActive) {
            EditModeTopBar(onAddMenu = { editAddMenuOpen = true },
                onDone = { editModeActive = false; editAddMenuOpen = false; model.trimTrailingEmptyPages() },
                modifier = Modifier.align(Alignment.TopCenter))
            BackHandler { editModeActive = false; editAddMenuOpen = false; model.trimTrailingEmptyPages() }
        }
        if (editAddMenuOpen) {
            EditModeAddMenu(
                onWidgets = {
                    widgetTargetIndex = firstFreeHomeSlotIndex(state.homeSlots); widgetExactTarget = true
                    widgetSlot = model.nextWidgetSlot(); widgetPackage = null; widgetProfileSerial = null
                    sheet = "widgets"
                },
                onWallpaper = { sheet = "settings:wallpaper" },
                onCustomize = { sheet = "settings" },
                onDismiss = { editAddMenuOpen = false },
            )
        }
        // Dialogs centre in the right pane (rail side, where the thumb is) unless anchored to an item.
        val dialogInPane = foldAvoidance?.let { it.dialogModifier(it.bounds(Pane.Right)) } ?: Modifier
        createFolderFirstId?.let { firstId ->
            val first = appsById[firstId]
            val folderDialogModifier = foldAvoidance?.let { av ->
                val index = state.layout.indexOfShortcut(firstId)
                av.dialogModifier(av.paneOf(index?.let { drag.regions[DropTarget.Home(it)]?.bounds }, index?.let(::homeCellPage)))
            } ?: Modifier
            AlertDialog(onDismissRequest = { createFolderFirstId = null }, modifier = folderDialogModifier,
                title = { Text("Create folder with ${first?.label ?: "app"}") },
                text = { LazyColumn(Modifier.heightIn(max = 420.dp).testTag("folder-app-picker")) {
                    items(state.apps.filter { it.id != firstId && it.available }, key = { it.id }) { second ->
                        TextButton(onClick = {
                            // A folder forms where its first app lives, the leading canvas included while unfolded.
                            val preferredPage = state.layout.indexOfShortcut(firstId)?.let(::homeCellPage)
                                ?.takeIf { it >= 0 || expandedWorkspace } ?: lastHomePage.coerceIn(0, homePages - 1)
                            val blocked = state.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
                            val targetIndex = (0 until HOME_CELLS).map { homeCellIndex(preferredPage, it) }
                                .firstOrNull { it !in blocked && state.layout.slotAt(it) in listOf(null, firstId, second.id) }
                            if (targetIndex != null) model.createFolder(firstId, second.id, targetIndex)
                            createFolderFirstId = null
                        }, modifier = Modifier.fillMaxWidth().testTag("folder-app-${second.id}")) {
                            Text(second.label, Modifier.fillMaxWidth())
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { createFolderFirstId = null }) { Text("Cancel") } })
        }
        pairMenuId?.let { id ->
            model.pair(id)?.let { pair ->
                val pairSheetModifier = foldAvoidance?.let { av ->
                    val index = state.layout.indexOfShortcut(id)
                    av.sheetModifier(av.paneOf(index?.let { drag.regions[DropTarget.Home(it)]?.bounds }, index?.let(::homeCellPage)))
                } ?: Modifier
                ModalBottomSheet(onDismissRequest = { pairMenuId = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
                    modifier = pairSheetModifier) {
                    PairActionSheet(pair, appsById,
                        onSwap = { model.swapPairSides(id); pairMenuId = null },
                        onSplit = { model.splitPair(id); pairMenuId = null },
                        onRemove = { model.removePair(id); pairMenuId = null },
                        onClose = { pairMenuId = null })
                }
            } ?: LaunchedEffect(id) { pairMenuId = null }
        }
        launcherActivity.backups.preview?.let { preview ->
            LayoutRestorePreview(preview, onRestore = {
                launcherActivity.backups.applyImport(); sheet = ""
            }, onCancel = launcherActivity.backups::cancelImport, modifier = dialogInPane)
        }
        if (launcherActivity.backups.pickerPending) AlertDialog(onDismissRequest = {}, modifier = dialogInPane,
            title = { Text("Layout document") },
            text = { Text("The system document picker is still open. Return to it to finish, or cancel this operation.") },
            confirmButton = { TextButton(onClick = { launcherActivity.backups.resumePendingPicker() },
                modifier = Modifier.testTag("backup-picker-resume")) { Text("Resume") } },
            dismissButton = { TextButton(onClick = launcherActivity.backups::cancelImport,
                modifier = Modifier.testTag("backup-picker-cancel")) { Text("Cancel") } })
        if (launcherActivity.backgrounds.pickerPending && !launcherActivity.backgrounds.loading) AlertDialog(
            onDismissRequest = {}, modifier = dialogInPane, title = { Text("Background photo") },
            text = { Text("The photo picker was interrupted. Resume choosing a photo, or cancel and keep the current background.") },
            confirmButton = { TextButton(onClick = launcherActivity.backgrounds::choosePhoto,
                modifier = Modifier.testTag("background-picker-resume")) { Text("Resume") } },
            dismissButton = { TextButton(onClick = launcherActivity.backgrounds::cancelPendingSelection,
                modifier = Modifier.testTag("background-picker-cancel")) { Text("Cancel") } })
        (launcherActivity.backups.errorMessage ?: launcherActivity.backups.successMessage)?.let { message ->
            AlertDialog(onDismissRequest = launcherActivity.backups::clearMessage, modifier = dialogInPane,
                title = { Text(if (launcherActivity.backups.errorMessage != null) "Layout backup problem" else "Layout backup") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = launcherActivity.backups::clearMessage) { Text("OK") } })
        }
        widgets.failureMessage?.let { message ->
            AlertDialog(onDismissRequest = widgets::clearFailure, modifier = dialogInPane, title = { Text("Widget not added") },
                text = { Text(message, Modifier.testTag("widget-bind-error")) },
                confirmButton = { TextButton(onClick = widgets::clearFailure) { Text("OK") } })
        }
        if (widgets.pendingPlacement != null && widgets.setupStatus != null) {
            AlertDialog(onDismissRequest = {}, modifier = dialogInPane, title = { Text("Finish widget setup") },
                text = { Text("The widget is waiting at its chosen spot. Finish setup to add it, or cancel to remove the placeholder.") },
                confirmButton = { Button(onClick = widgets::finishPendingSetup,
                    modifier = Modifier.semantics { contentDescription = "Continue widget setup" }) { Text("Finish setup") } },
                dismissButton = { TextButton(onClick = { leaveTemporaryWidgetPage(); widgets.cancelPendingSetup() },
                    modifier = Modifier.semantics { contentDescription = "Cancel widget setup" }) { Text("Cancel") } })
        }
        widgets.reconfigureWidgetId?.let {
            AlertDialog(onDismissRequest = {}, modifier = dialogInPane, title = { Text("Widget settings") },
                text = { Text("Widget settings were interrupted. Resume configuration, or cancel and keep the widget unchanged.") },
                confirmButton = { Button(onClick = widgets::finishPendingReconfigure,
                    modifier = Modifier.testTag("widget-reconfigure-resume")) { Text("Resume") } },
                dismissButton = { TextButton(onClick = widgets::cancelPendingReconfigure,
                    modifier = Modifier.testTag("widget-reconfigure-cancel")) { Text("Cancel") } })
        }
        // "Mazání stránek s dotazem" (2026-09-17 noc): shared by edit mode's "×", the page
        // overview's trash, and Home layout settings' "Odebrat stránku" (all three call
        // requestDeletePage). Plain AlertDialog + dialogInPane, same fold-avoidance convention as
        // every other confirmation above, rather than a bespoke card.
        deletePageConfirm?.let { page ->
            val (appCount, widgetCount) = homeDeletePageCounts(state.layout, page)
            // iOS alert style (2026-09-17 night): centred narrow card, 17 sp semibold title,
            // 13 sp message, destructive action in iOS system red.
            AlertDialog(onDismissRequest = { deletePageConfirm = null },
                modifier = dialogInPane.widthIn(max = IosAlertWidth).testTag("delete-page-confirm"),
                shape = RoundedCornerShape(IosAlertCorner),
                title = { Text("Smazat stránku?", style = IosTitleStyle) },
                text = { Text(homeDeletePageMessage(appCount, widgetCount), style = IosSectionLabelStyle) },
                confirmButton = { TextButton(onClick = {
                    widgets.removeHomePage(page); deletePageConfirm = null
                    Haptics.play(launcherRootView.context, HapticEvent.PAGE_REMOVED)
                }, modifier = Modifier.testTag("delete-page-confirm-yes")) { Text("Smazat", style = IosMenuRowStyle, color = IosDestructive) } },
                dismissButton = { TextButton(onClick = { deletePageConfirm = null },
                    modifier = Modifier.testTag("delete-page-confirm-cancel")) { Text("Zrušit", style = IosMenuRowStyle) } })
        }
        }
        // "Ostrůvek do plochy" (17. 9. večer): the expanded island card itself, a single overlay
        // call at this root Box — above every page (it draws after the whole BoxWithConstraints
        // above, which holds Home/App Library/folders/the rail) and below Spotlight/sheets. Free
        // of the rail column's width here, unlike RailIsland's own collapsed pills above.
        IslandExpandedOverlay(
            items = islandItems, expandedKey = islandExpanded, anchor = islandAnchor,
            onDismiss = { islandExpanded = null },
        )
        // B61 "Ostrov kolem kamery": the black pill built around the camera cutout itself, a
        // single self-contained overlay call (reads the live cutout/fold seam/reduce-motion on
        // its own) — a no-op while "Live activities" (Today settings) is set to Rail.
        cz.pflanzer.foldduo.cameraisland.CameraIslandOverlay(items = islandItems)
        // "Stavová karta z railu" (17. 9. noc): same root-level-sibling pattern as the island
        // card above — free of the rail column's width, grows from the measured ring rect.
        StatusCardOverlay(
            anchor = statusCardAnchor, open = statusCardOpen, ink = paneInkColor(),
            onDismiss = { statusCardOpen = false },
        )
        // B28 Spotlight: last so it draws over everything else in this pane, still inside the
        // LocalFrostedBackdrop scope above so its frost reads the real wallpaper backdrop.
        // "Spotlight přes oba pane" (17. 9. noc): the left pane's app preview reuses B49's own
        // quick-look lookup (same `providersForPackage`/`widgetCatalog` calls as
        // `LauncherAppActionSheet`'s `previewProviders`/`previewEntry` above) instead of
        // duplicating WidgetController/AppProfile plumbing inside Spotlight.kt — remembered so its
        // identity is stable across recompositions (Spotlight.kt's own `produceState` is keyed on
        // this lambda plus the highlighted app's id). Root-Box scope here, past where the Home
        // BoxWithConstraints (and its `leadingSizing`) already closed, so unlike B49's sheet this
        // renders the widget at a plain 2×2 default span rather than the provider's own preferred
        // size — a preview-only simplification, not a binding, so it never actually places one.
        val spotlightWidgetPreviewFor = remember<suspend (AppEntry) -> SpotlightWidgetPreview?>(widgets, launcherActivity) {
            { app ->
                withContext(Dispatchers.IO) {
                    if (app.packageName.isEmpty()) null else runCatching {
                        val provider = widgets.providersForPackage(app.packageName, app.user).firstOrNull {
                            it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                        } ?: return@runCatching null
                        val entry = widgetCatalog(launcherActivity, listOf(provider),
                            AppProfile(app.userSerial, app.profileLabel, !app.isWork, app.isWork,
                                quiet = false, unlocked = true, available = true)).firstOrNull() ?: return@runCatching null
                        SpotlightWidgetPreview(entry, WidgetSpan(2, 2))
                    }.getOrNull()
                }
            }
        }
        SpotlightOverlay(
            visible = spotlightOpen, state = state, onDismiss = { spotlightOpen = false },
            onLaunch = onLaunchFrom,
            onLaunchPair = onLaunchPair,
            onOpenWallpaper = { sheet = ""; onWallpaperPreview() },
            onOpenAppearance = { sheet = "settings"; customizationPage = CustomizationPage.OVERVIEW },
            onOpenWidgets = {
                val page = lastHomePage.coerceIn(0, homePages - 1)
                widgetTargetIndex = homeCellIndex(page, 0); widgetExactTarget = false
                widgetSlot = model.nextWidgetSlot(); widgetPackage = null; widgetProfileSerial = null
                sheet = "widgets"
            },
            onOpenEditHome = { spotlightOpen = false; editModeActive = true },
            suggestions = suggestions,
            motion = spotlightMotion,
            isDefaultHome = isDefaultHome,
            // "Spotlight přes oba pane": the left pane's app preview reuses B49's own quick-look
            // lookup (LauncherAppActionSheet above, `previewProviders`/`previewEntry`) rather than
            // duplicating WidgetController/AppProfile plumbing inside Spotlight.kt itself.
            widgetPreviewFor = spotlightWidgetPreviewFor,
        )
        // B42 "Paleta ze švu": inner display only (SeamPaletteOverlay no-ops without a split
        // seam); same layering rule as Spotlight above it.
        SeamPaletteOverlay(
            openPane = seamPaletteOpenPane, dragPane = seamDragPane, dragProgress = seamDragProgress,
            reduceMotion = reduceMotion, state = state,
            onDismiss = { seamPaletteOpenPane = null },
            onLaunchAdjacent = { app -> if (launchAppAdjacent(context, app)) model.recordLaunch(app) },
        )
        // B47 "Stůl": Fold 8 has no Flex mode, this replaces it — Stand pose (pose/PoseClassifier.kt)
        // held 1.2 s morphs into a StandBy-like face + control deck. Self-contained like Spotlight
        // and the seam palette above it (reads LocalFoldPose/LocalFoldSeam itself); a no-op while
        // the "Desk when standing" setting is Off or the pose is not Stand.
        DeskOverlay(state = state, islandItems = islandItems, onLaunchApp = { app -> onLaunch(app) })
        // B39 "Přehled stránek a režimy plochy": last of all, same reasoning as Spotlight above.
        PageOverviewOverlay(
            visible = pageOverviewOpen, state = state, expanded = expandedWorkspace,
            reduceMotion = MotionPrefs.enabled.value,
            onDismiss = { pageOverviewOpen = false },
            onJump = { page -> scope.launch { pager.animateScrollToPage(page) }; pageOverviewOpen = false },
            onReorder = { from, to -> model.reorderHomePages(from, to) },
            onSetHidden = { page, hidden -> model.setPageHidden(page, hidden) },
            onAddPage = { model.addHomePage() },
            onRemovePage = requestDeletePage,
            onSwitchMode = { id -> model.switchMode(id, "manual") },
            onCreateMode = { name -> model.createMode(name) },
            onRenameMode = { id, name -> model.renameMode(id, name) },
            onDeleteMode = { id -> model.deleteMode(id) },
            onToggleModePage = { id, page, member ->
                val mode = state.homeModes.firstOrNull { it.id == id }
                val pages = if (member) (mode?.visiblePages.orEmpty() + page) else (mode?.visiblePages.orEmpty() - page)
                model.setModeVisiblePages(id, pages.toSet())
            },
        )
        // B48 "Pant jako ovladač": last of all, same layering reasoning as Spotlight above —
        // opens only when the setting picks App Library (the NowBrief/Off cases never set
        // squeezeHeld's App Library gate, handled entirely in MainActivity's own effect).
        HingeSqueezeOverlay(
            visible = squeezeHeld && !squeezeDismissed && !spotlightOpen && appearance.hingeSqueezeAction == HingeSqueezeAction.AppLibrary,
            depthDeg = squeezeDepthDeg, reduceMotion = MotionPrefs.enabled.value, state = state,
            onLaunch = zoomLaunchFrom, onPin = model::setPinned, onTurnOnWork = { model.turnOnWork(it) },
            onDismiss = { squeezeDismissed = true },
        )
        }
    }
}

@Composable
private fun ExpandedWorkspace(
    nativePager: androidx.compose.foundation.pager.PagerState,
    motion: WorkspacePageMotion,
    visibleHomePages: Int,
    /** B39 "pager mapping": physical stride slot -> real (logical) Home page number; see [LauncherPager.logicalOf]. Identity when nothing is hidden. */
    logicalPage: (Int) -> Int = { it },
    /**
     * "Stránkování inneru jako spread" (Home layout setting): true (default) pages Today together
     * with Home as one continuous two-pane strip; false keeps the previous "right pane only"
     * behaviour byte-for-byte (Today never moves except into the App Library transition below).
     */
    spreadPaging: Boolean = true,
    panes: ExpandedPaneLayout,
    contentHeight: Dp,
    bottomSpace: Dp,
    geometry: HomeGeometry,
    state: LauncherState,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    libraryQuery: String,
    onLibraryQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit,
    onPinned: (String, Boolean) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    /** B46 "Dvojice aplikací": mirrors onLaunch/onLaunchFrom and onFolder above for pair tiles. */
    onLaunchPair: (PairEntry, android.graphics.Rect?) -> Unit = { _, _ -> },
    onPairActions: (String) -> Unit = {},
    pairDropArmed: Boolean = false,
    onEmptyWidget: (Int) -> Unit,
    onRefresh: () -> Unit,
    /** B26 Smart stack: flips the stack at this slot forward/back (manual swipe). */
    onCycleStack: (Int, Boolean) -> Unit = { _, _ -> },
    /** Window rectangle the launcher background is drawn in (the root); the morph copies it under the left half. */
    backgroundOrigin: Offset = Offset.Zero,
    backgroundSize: IntSize = IntSize.Zero,
    appearance: AppearanceState = AppearanceState(),
    /** B26 Smart stack follow-up: reports this pane's own `showToday` up to LauncherScreen, the
     * only place that knows about stacks/LauncherModel — the Today canvas only exists here. */
    onTodayVisibilityChanged: (Boolean) -> Unit = {},
    /** B34: hub groups for the leading canvas's top slot; empty when the setting is off or nothing is posted. */
    hubGroups: List<HubGroup> = emptyList(),
    /** Compact hub pill (B45): false shows the one-row summary, true the old cards; LauncherScreen.kt owns the state. */
    hubExpanded: Boolean = false,
    onHubToggle: () -> Unit = {},
    expandedHubGroup: String? = null,
    onHubExpandGroup: (String?) -> Unit = {},
    onHubOpen: (HubNotification) -> Unit = {},
    onHubDismiss: (String) -> Unit = {},
    onHubClearGroup: (HubGroup) -> Unit = {},
    /** B35: today's ranked Suggestions for the leading canvas's top slot; empty when the setting is off or nothing is ranked yet. */
    suggestions: List<AppEntry> = emptyList(),
    onSuggestionLaunch: (AppEntry) -> Unit = {},
    onSuggestionBlock: (AppEntry) -> Unit = {},
    onSuggestionAddToHome: (AppEntry) -> Unit = {},
) {
    trackRecomposition("ExpandedWorkspace")
    val density = LocalDensity.current
    val viewportWidth = motion.pageWidth
    val stride = motion.homeStride
    val homeOrigin = with(density) { panes.homeOrigin.dp.toPx() }
    val homePaneWidth = with(density) { panes.homeStride.dp.toPx() }
    val todayX = with(density) { panes.leadingOrigin.dp.toPx() }
    val todayWidth = with(density) { panes.leadingWidth.dp.toPx() }
    val leadingGridWidth = leadingGridWidth(panes.leadingWidth)
    val lastHomeOffset = motion.offset((visibleHomePages - 1).toFloat())
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun scroll() = motion.offset(nativePager.currentPage + nativePager.currentPageOffsetFraction)
    // Home pages page inside the right pane: page N sits at `N * stride` pane-local (right-pane
    // only) or at absolute `homeOrigin + N * stride` (spread — see the strip container below);
    // either way every page lives at one fixed strip position for its entire lifetime on screen.
    // Výkon 5 "stránky složené předem": this USED to be a scroll-driven `derivedStateOf` windowed
    // to the intersecting page plus a ±1 retention margin, so a page entering/leaving that window
    // during a swipe tore down and rebuilt its whole `HomePagePane` subtree — device evidence
    // (17. 9. noc, after Výkon 4) showed exactly that shape: one swipe episode with a single
    // ~150 ms frame carrying `HomePagePane=1, SharedHomeGrid=1, AppTile=8, HostWidgetView=1`, i.e.
    // one page's first-ever composition landing on the settle. There are at most a handful of
    // Home pages (B39's own page-count cap), so composing all of them for the whole life of the
    // strip is cheap; [homePageDrawVisible] below (read only inside each page's own `graphicsLayer`
    // block, never here) is the draw-only replacement for the old windowing.
    val allHomePages = remember(visibleHomePages) { composedHomePages(visibleHomePages) }
    fun todayShift() = (scroll() - lastHomeOffset).coerceAtLeast(0f)
    // "Spread jako jedna plocha" (17. 9. noc): Today's own fixed strip box spans
    // `[todayX, todayX + todayWidth]`; it is worth composing only while the shared strip
    // translation (`scroll()`, the same value the whole strip's graphicsLayer reads) has not yet
    // pushed that box fully offscreen. "Right pane only" keeps the old todayShift()-based rule
    // unchanged (Today only ever moves for the App Library's own full-width transition there).
    val showToday by remember(nativePager, motion, todayX, todayWidth, lastHomeOffset, spreadPaging) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (spreadPaging) todayWidth > 0f && todayX + todayWidth - scroll() > 0f
            else todayWidth > 0f && todayX - todayShift() + todayWidth > 0f
        }
    }
    // B26 Smart stack follow-up: [showToday] above is a live, continuous scroll signal — it
    // flips mid-drag, before the pager has actually settled anywhere. Reporting that directly
    // would let a stack rotate (and visibly flip) while a drag is still moving Today back into
    // view. This settles-only twin uses `settledPage` instead of the live offset, so it only
    // changes once the pager (or a drag/fling) actually comes to rest on a new page.
    val showTodaySettled by remember(nativePager, motion, todayX, todayWidth, lastHomeOffset, spreadPaging) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (spreadPaging) nativePager.settledPage == 0
            else {
                val settledShift = (motion.offset(nativePager.settledPage.toFloat()) - lastHomeOffset).coerceAtLeast(0f)
                todayWidth > 0f && todayX - settledShift + todayWidth > 0f
            }
        }
    }
    SideEffect { onTodayVisibilityChanged(showTodaySettled) }
    val libraryPhysicalPage = visibleHomePages
    val showLibrary by remember(nativePager, libraryPhysicalPage) {
        derivedStateOf(structuralEqualityPolicy()) {
            nativePager.currentPage + nativePager.currentPageOffsetFraction >= libraryPhysicalPage - 1.25f
        }
    }

    // Continuum A phase 2 (and phase 0, its inverse while folding): the morph frosts the whole
    // left half as one piece of glass. The two never run at once; the layer blocks combine them
    // through MorphCurve.leftHalf* so a closing frost drives the same layers, and angle mode
    // (the real hinge angle, MorphController.angleTilt) drives them through the same max.
    // The frost (continuum's fold shader, hinge at the pane's right edge = the seam)
    // is the outer layer over `[0, seam - gutter]` × full height; inside it sit a copy of the
    // launcher background under that half (the same pixels as the base wallpaper, so at rest it
    // is invisible and during the morph the wallpaper frosts with the pane) and the pane itself
    // (widgets, icons) on an inner layer that only drifts by MorphCurve.SLIDE_FRACTION during
    // the unfold: nothing slides or fades separately from the frost, else the content reads as
    // arriving after the glass clears (device feedback). Both read the progress in their layer
    // blocks, so a running morph re-renders the layers and recomposes nothing. Without a
    // compiled shader, no frost. The shader is the process-wide one (compiled once, warmed by
    // MorphWarmUp); the layer keeps its RenderEffect across frames with the same uniforms and,
    // while idle, pre-builds the start-of-morph effect so the first animated frame only sets it.
    val morph = LocalMorphController.current
    val context = LocalContext.current
    val foldShader = remember(context) { FoldShader.shared(context) }
    val pxPerMm = remember(context) { FoldShader.pxPerMm(context) }
    // B20: "Frost intensity" / "Tilt" (Appearance.kt's morphFrostFactor/morphTiltFactor).
    val foldConfig = remember(appearance.morphFrostFactor) { FoldConfig(blurSpread = MorphPreviewTuning.blurSpread(FoldConfig().blurSpread, appearance.morphFrostFactor)) }
    val foldCache = remember { FoldEffectCache() }
    val leftHalfWidth = todayX + todayWidth
    val todayFold = remember(todayX, todayWidth) {
        FoldLine(splitsX = true, position = leftHalfWidth, eyePos = todayX + todayWidth * 0.5f, movingSide = -1)
    }
    // B21: same reasoning as the cover layer above — reduce motion drops the frost layer (and,
    // below, the specular sweep that rides on the same tilt) entirely instead of just zeroing it.
    val reduceMotionLeftHalf = MotionPrefs.enabled.value
    val leftHalfFrost = if (morph == null || foldShader == null || reduceMotionLeftHalf) Modifier else Modifier.foldEffect(foldShader,
        tilt = { MorphPreviewTuning.scaleTilt(MorphCurve.leftHalfTilt(morph.progress.value, morph.closeFrost.value, morph.angleTilt.value), appearance.morphTiltFactor) },
        config = foldConfig, pxPerMm = pxPerMm, fold = todayFold, cache = foldCache, primeTilt = MorphCurve.startTilt)
    // B21: the reduced-motion crossfade (UnfoldMorph.kt's MorphController.playEnterReduced): a
    // plain alpha fade in place of the frost/tilt, driven by the same `progress` the timed play
    // already animates (0 = just triggered, 1 = at rest), so no separate Animatable is needed.
    // B43: `graphicsLayer { }` reads `progress` in the layer block (like every other driver of
    // this half), not `Modifier.alpha(Float)`, which reads it right here in the composable body
    // and would recompose this whole composable on every tick instead of only re-drawing a layer.
    val leftHalfCrossfade = if (morph == null || !reduceMotionLeftHalf) Modifier else Modifier.graphicsLayer {
        alpha = morph.progress.value.coerceIn(0f, 1f)
    }
    // B16, the specular sweep: a soft light band crossing the frost from the hinge side (the
    // pane's right edge, its own seam) outward as the shader tilt runs 20 -> 0. Drawn on top of
    // the already-composited frost (no second shader pass): a 3-stop horizontal gradient
    // (transparent, MorphCurve.SWEEP_ALPHA, transparent) is exactly the triangular feather
    // MorphCurve.sweepIntensity describes, so the two agree without sampling it per pixel.
    val leftHalfSweep = if (morph == null || reduceMotionLeftHalf) Modifier else Modifier.drawWithContent {
        drawContent()
        val tilt = MorphCurve.leftHalfTilt(morph.progress.value, morph.closeFrost.value, morph.angleTilt.value)
        val t = MorphCurve.sweepProgress(tilt)
        if (t > 0f && t < 1f && size.width > 0f) {
            val bandWidthPx = size.width * MorphCurve.SWEEP_WIDTH_FRACTION
            val centerPx = size.width * MorphCurve.sweepCenterFraction(t)
            val left = centerPx - bandWidthPx / 2f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White.copy(alpha = 0f),
                    0.5f to Color.White.copy(alpha = MorphCurve.SWEEP_ALPHA),
                    1f to Color.White.copy(alpha = 0f),
                    startX = left, endX = left + bandWidthPx,
                ),
                topLeft = Offset(left, 0f),
                size = Size(bandWidthPx, size.height),
            )
        }
    }
    val todaySlide = if (morph == null) Modifier else Modifier.graphicsLayer {
        translationX = MorphCurve.translationX(morph.progress.value, todayWidth)
    }
    // B17, closing seam darken: a cheap vertical gradient on the RIGHT pane (not frosted — the
    // frost stays confined to the left half, fact 1) that darkens toward the seam as the phone
    // folds, scaled by MorphController.closingTilt (the closing-only driver: never the opening
    // clear). Plain Canvas draw, no shader.
    val closingSeamDarkenWidthPx = with(density) { 48.dp.toPx() }
    val closingSeamDarken = if (morph == null) Modifier else Modifier.drawWithContent {
        drawContent()
        val darkenAlpha = MorphCurve.closingDarkenAlpha(morph.closingTilt)
        if (darkenAlpha > 0f && size.width > 0f) {
            val bandWidth = closingSeamDarkenWidthPx.coerceAtMost(size.width)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = darkenAlpha),
                    1f to Color.Black.copy(alpha = 0f),
                    startX = 0f, endX = bandWidth,
                ),
                topLeft = Offset.Zero,
                size = Size(bandWidth, size.height),
            )
        }
    }
    val backgroundPhoto = rememberLauncherBackgroundPhoto()
    val backgroundDark = LocalDuoPalette.current.dark

    // The leading canvas content (topSlot + HomePagePane) is identical in both branches below;
    // only its wrapping/offset differs (fixed strip position vs. the old todayShift()-driven one).
    @Composable
    fun BoxScope.TodayContent() {
        if (morph != null && backgroundSize.width > 0 && backgroundSize.height > 0) {
            var copyOrigin by remember { mutableStateOf(Offset.Zero) }
            Canvas(Modifier.matchParentSize().onGloballyPositioned { copyOrigin = it.positionInWindow() }) {
                val dx = backgroundOrigin.x - copyOrigin.x
                val dy = backgroundOrigin.y - copyOrigin.y
                translate(dx, dy) {
                    drawLauncherBackground(backgroundPhoto, backgroundDark, Panel.Inner,
                        surface = Size(backgroundSize.width.toFloat(), backgroundSize.height.toFloat()))
                }
            }
        }
        Box(Modifier.offset { IntOffset(todayX.roundToInt(), 0) }
            .width(panes.leadingWidth.dp).height((contentHeight - bottomSpace).coerceAtLeast(0.dp))
            .testTag("expanded-leading-home")
            .then(todaySlide)) {
            HomePagePane(
                -1, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                widgets, drag, target, insertionTarget, showLargeWidget = false,
                onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                onFolder = onFolder,
                onLaunchPair = onLaunchPair, onPairActions = onPairActions, pairDropArmed = pairDropArmed,
                onEmptyWidget = onEmptyWidget,
                onRefresh = onRefresh,
                onCycleStack = onCycleStack,
                gridWidth = leadingGridWidth,
                topSlot = {
                    // "Oprava naklánění na Today" (17. 9. noc): thread the same `morph`
                    // every grid item already reads (LocalMorphController.current, above)
                    // into the top slot, so the hub/suggestions unfold with the rest of
                    // Today instead of sitting still while the grid below them tilts.
                    LeadingTopSlot(hubGroups, hubExpanded, onHubToggle, expandedHubGroup, onHubExpandGroup,
                        onHubOpen, onHubDismiss, onHubClearGroup,
                        suggestions, onSuggestionLaunch, onSuggestionBlock, onSuggestionAddToHome,
                        morph = morph,
                        // Same tile geometry SharedHomeGrid uses for this pane's own icons
                        // (leadingGridWidth / GRID_COLUMNS, geometry.iconSize) — see AppTile reuse.
                        cellWidthDp = leadingGridWidth / GRID_COLUMNS, iconSize = geometry.iconSize, labels = state.labels)
                },
            )
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().testTag("expanded-workspace")) {
        if (spreadPaging) {
            // "Spread jako jedna plocha" (17. 9. noc): Today and every Home page sit at ONE fixed
            // strip position each — Today at `todayX`, Home page k at `homeOrigin + k * stride`
            // (WorkspacePageMotion.kt's expandedPaneLayout now derives the right pane's own pitch
            // from its full pane box, not its narrower icon grid, so this is the SAME pitch Today
            // uses) — inside a single container. One graphicsLayer translationX pans the whole
            // strip together, so a page crossing the seam is the very same node moving, never a
            // hand-off between two differently-paced boxes (device report this fixes, 17. 9.: "je
            // tam vidět předěl mezi displeji, čára, kde se grafika řeže"). `scroll()` is read only
            // inside the graphicsLayer block, like every other continuous driver in this function,
            // so a scroll frame re-draws this one layer without recomposing ExpandedWorkspace —
            // every child box below has a FIXED offset that never changes mid-drag.
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = -scroll() }) {
                // Výkon 5 "stránky složené předem": the leading canvas used to be wrapped in
                // `if (showToday)` — a live, continuous scroll-derived `State` read right here in
                // the composable body, so it recomposed (tore down/rebuilt) ExpandedWorkspace's
                // whole Today subtree, HostWidgetView included, every time the strip scrolled past
                // its edge. It is now unconditional (composed for the whole life of the strip,
                // like every Home page below) and the same visibility test only gates DRAWING,
                // read inside this Box's own `graphicsLayer` block so a scroll frame re-draws
                // without recomposing anything above it.
                key("expanded-leading-home") {
                    stateHolder.SaveableStateProvider("expanded-home--1") {
                        Box(Modifier
                            .width(with(density) { leftHalfWidth.toDp() }).fillMaxHeight()
                            .testTag("expanded-left-half")
                            .graphicsLayer { alpha = if (todayWidth > 0f && todayX + todayWidth - scroll() > 0f) 1f else 0f }
                            .then(leftHalfFrost).then(leftHalfSweep).then(leftHalfCrossfade)) {
                            TodayContent()
                        }
                    }
                }

                // Every Home page lives at its own fixed strip position for its entire lifetime on
                // screen — never reparented between a "left" and a "right" wrapper, so a page's own
                // AppWidgetHostView is never at risk of two simultaneous owners. Uniform gridWidth
                // (geometry.gridWidth, HomePagePane's own default) regardless of which viewport
                // slot the page currently sits in — no reflow crossing the seam; only the rail's
                // own trailing inset (HomeGeometry.railInsetDp) is ever reserved inside the grid,
                // whether or not this page happens to be under the rail's physical column right now.
                // Výkon 5: every page in [allHomePages] composes for the whole life of the strip
                // (see its own doc above) — [homePageDrawVisible] below is the draw-only gate that
                // replaces the old scroll-windowed composition, read inside this page's own
                // `graphicsLayer` block so scrolling a page off Retention range only stops it from
                // drawing, never tears its `HomePagePane`/`HostWidgetView` down.
                allHomePages.forEach { page ->
                    val logical = logicalPage(page)
                    key("expanded-home-$page") {
                        stateHolder.SaveableStateProvider("expanded-home-$page") {
                            Box(Modifier.offset { IntOffset((homeOrigin + page * stride).roundToInt(), 0) }
                                .width(panes.homeStride.dp).fillMaxHeight()
                                .graphicsLayer {
                                    alpha = if (homePageDrawVisible(homeOrigin + page * stride, homePaneWidth, scroll(), viewportWidth, homePaneWidth)) 1f else 0f
                                }) {
                                HomePagePane(
                                    logical, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                                    widgets, drag, target, insertionTarget, showLargeWidget = logical > 0,
                                    onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                                    onFolder = onFolder,
                                    onLaunchPair = onLaunchPair, onPairActions = onPairActions, pairDropArmed = pairDropArmed,
                                    onEmptyWidget = onEmptyWidget,
                                    onRefresh = onRefresh,
                                    onCycleStack = onCycleStack,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // "Right pane only": Today never moves except into the App Library transition below;
            // Home pages page inside their own clipped right-pane box. Kept byte-for-byte the same
            // shape as before Spread paging existed — only the shared pane pitch (`stride`) it and
            // the strip above both read from `panes`/`motion` is now the corrected, wider one.
            if (showToday) {
                key("expanded-leading-home") {
                    stateHolder.SaveableStateProvider("expanded-home--1") {
                        Box(Modifier.offset { IntOffset((-todayShift()).roundToInt(), 0) }
                            .width(with(density) { leftHalfWidth.toDp() }).fillMaxHeight()
                            .testTag("expanded-left-half")
                            .then(leftHalfFrost).then(leftHalfSweep).then(leftHalfCrossfade)) {
                            TodayContent()
                        }
                    }
                }
            }

            Box(Modifier.offset { IntOffset(homeOrigin.roundToInt(), 0) }.width(panes.homeStride.dp)
                .fillMaxHeight().clipToBounds().testTag("expanded-home-pane")) {
                // Výkon 5: same "compose every page, draw-gate only" shape as the spread branch
                // above — the parent's own `clipToBounds()` already keeps an offscreen page from
                // painting outside this box, [homePageDrawVisible] additionally skips it drawing
                // (and any AndroidView it hosts) at all while it sits well outside the pane.
                allHomePages.forEach { page ->
                    // B39: `page` here is the physical stride slot (spacing/positioning only — the
                    // pane's own X offset below); its content must key off the real Home page number.
                    val logical = logicalPage(page)
                    key("expanded-home-$page") {
                        stateHolder.SaveableStateProvider("expanded-home-$page") {
                            Box(Modifier.offset { IntOffset((page * stride - scroll()).roundToInt(), 0) }
                                .width(panes.homeStride.dp).fillMaxHeight()
                                .graphicsLayer {
                                    alpha = if (homePageDrawVisible(page * stride, homePaneWidth, scroll(), homePaneWidth, homePaneWidth)) 1f else 0f
                                }) {
                                HomePagePane(
                                    logical, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                                    widgets, drag, target, insertionTarget, showLargeWidget = logical > 0,
                                    onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                                    onFolder = onFolder,
                                    onLaunchPair = onLaunchPair, onPairActions = onPairActions, pairDropArmed = pairDropArmed,
                                    onEmptyWidget = onEmptyWidget,
                                    onRefresh = onRefresh,
                                    onCycleStack = onCycleStack,
                                )
                            }
                        }
                    }
                }
            }
        }

        // B17 closing seam darken: fixed at the right pane's own box regardless of which page
        // happens to be scrolled there right now — a physical-fold effect, orthogonal to
        // page-scroll, so it lives outside the strip's own translated container.
        Box(Modifier.offset { IntOffset(homeOrigin.roundToInt(), 0) }.width(panes.homeStride.dp)
            .fillMaxHeight().then(closingSeamDarken))

        if (showLibrary) {
            key("library-pane") {
                Box(Modifier.offset { IntOffset(((visibleHomePages - 1) * stride + viewportWidth - scroll()).roundToInt(), 0) }
                    .fillMaxSize()) {
                    AppLibrary(state, libraryQuery, onLibraryQuery, onLaunch, onPinned,
                        onActions = onActions,
                        modifier = Modifier.fillMaxSize().testTag("library-page"),
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = bottomSpace),
                        drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom, onTurnOnWork = onTurnOnWork,
                        expanded = true, isCurrent = nativePager.currentPage == libraryPhysicalPage,
                        // B31 pane identity: the alphabetical list (search/pin) stays right-pane
                        // width even though the category grid intentionally spans both panes.
                        paneWidth = geometry.gridWidth.dp)
                }
            }
        }
    }
}

@Composable
private fun HomePagePane(
    page: Int,
    state: LauncherState,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    contentHeight: Dp,
    bottomSpace: Dp,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    showLargeWidget: Boolean,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    /** B46 "Dvojice aplikací": mirrors onLaunch/onFolder above for pair tiles. */
    onLaunchPair: (PairEntry, android.graphics.Rect?) -> Unit = { _, _ -> },
    onPairActions: (String) -> Unit = {},
    pairDropArmed: Boolean = false,
    onEmptyWidget: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    onCycleStack: (Int, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    /** Grid width inside the pane; the leading canvas (page -1) is wider than the Home grid. */
    gridWidth: Float = geometry.gridWidth,
    /**
     * Extension point above the grid, page -1 only (LeadingPane.kt): B34's notification hub first,
     * then B35's suggestions row, then the grid below — each caller only ever adds to the end of
     * that order, never reaches into [SharedHomeGrid] itself.
     */
    topSlot: (@Composable () -> Unit)? = null,
) {
    trackRecomposition("HomePagePane")
    val homeScroll = rememberScrollState()
    var paneBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val pageStart = homeCellIndex(page, 0)
    val backgroundTarget = (pageStart until pageStart + HOME_CELLS).firstOrNull { index ->
        state.layout.slotAt(index) == null && state.widgetPlacements.none { index in it.coveredIndices() }
    } ?: pageStart
    val verticalEdge = with(LocalDensity.current) { 42.dp.toPx() }
    LaunchedEffect(drag.active, page, paneBounds) {
        while (drag.active) {
            val pointer = drag.pointer
            val amount = when {
                !paneBounds.contains(pointer) -> 0f
                pointer.y < paneBounds.top + verticalEdge && homeScroll.canScrollBackward -> -18f
                pointer.y > paneBounds.bottom - verticalEdge && homeScroll.canScrollForward -> 18f
                else -> 0f
            }
            if (amount != 0f) homeScroll.scrollBy(amount)
            delay(16)
        }
    }
    Box(modifier.testTag("home-page-$page")
        .semantics {
            onLongClick("Home options") {
                if (!drag.active) onEmptyWidget(backgroundTarget)
                !drag.active
            }
        }
        .onGloballyPositioned { paneBounds = it.boundsInRoot() }
        .width((gridWidth + HOME_START_DP).dp)
        .height((contentHeight - bottomSpace).coerceAtLeast(0.dp))) {
        Box(Modifier.width(HOME_START_DP.dp).fillMaxHeight().testTag("home-options-margin-$page")
            .pointerInput(backgroundTarget, drag.active, drag.editMode) {
                detectTapGestures(
                    onTap = { if (drag.editMode) drag.onEditModeExit() },
                    onLongPress = { if (!drag.active) onEmptyWidget(backgroundTarget) },
                )
            })
        Column(Modifier.offset(x = HOME_START_DP.dp).width(gridWidth.dp).fillMaxHeight()
            .verticalScroll(homeScroll).padding(top = geometry.contentTop.dp, bottom = 8.dp)) {
            topSlot?.invoke()
            SharedHomeGrid(page, state.homeSlots, state.leadingSlots, previewSlots, previewLeadingSlots, previewWidgetPlacements,
                appsById, geometry, state.labels, widgets, drag, target,
                folders = state.folders, onLaunch = onLaunch, onActions = onActions, onWidget = onWidget,
                onFolder = onFolder, onEmptyWidget = onEmptyWidget, onCycleStack = onCycleStack,
                pairs = state.pairs, onLaunchPair = onLaunchPair, onPairActions = onPairActions, pairDropArmed = pairDropArmed,
                compactPreset = state.compact, expandedPreset = state.expanded)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            if (state.error != null) Text(state.error, color = Color.White,
                modifier = Modifier.clickable(onClick = onRefresh).padding(12.dp))
        }
    }
}

@Composable
private fun CircleControl(icon: ImageVector, label: String, tag: String, visualSize: Dp, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(visualSize.coerceAtLeast(48.dp)).testTag(tag)) {
        Box(Modifier.size(visualSize).testTag("$tag-visual").background(Glass.copy(alpha = .22f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * B16's settle spring for one Today item: idle (before the first Flat of this unfold, or an item
 * off [settleIndexByKey]) it is `1f to 1f`, invisible. On [MorphController.settleGeneration]
 * changing it snaps to [MorphCurve.SETTLE_START_SCALE]/[MorphCurve.SETTLE_START_ALPHA], waits
 * [MorphCurve.settleDelayMs] for its reading-order [settleIndex], then springs a single
 * `Animatable` to 1; scale and alpha share it (linear interpolation) so one spring drives both,
 * and a slightly underdamped overshoot on the way in reads as an alpha over 1 (harmless — alpha
 * clamps visually).
 */
@Composable
private fun rememberSettleAnim(morph: MorphController?, settleIndex: Int?): Pair<Float, Float> {
    if (morph == null || settleIndex == null) return 1f to 1f
    val t = remember(morph) { Animatable(1f) }
    LaunchedEffect(morph, morph.settleGeneration, settleIndex) {
        if (morph.settleGeneration == 0) return@LaunchedEffect
        t.snapTo(0f)
        delay(MorphCurve.settleDelayMs(settleIndex))
        t.animateTo(1f, spring(dampingRatio = MorphCurve.SETTLE_DAMPING_RATIO, stiffness = MorphCurve.SETTLE_STIFFNESS))
    }
    val scale = MorphCurve.SETTLE_START_SCALE + (1f - MorphCurve.SETTLE_START_SCALE) * t.value
    val alpha = (MorphCurve.SETTLE_START_ALPHA + (1f - MorphCurve.SETTLE_START_ALPHA) * t.value).coerceIn(0f, 1f)
    return scale to alpha
}

@Composable
private fun SharedHomeGrid(
    page: Int,
    savedSlots: List<String?>,
    savedLeadingSlots: List<String?>,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    widgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    labels: Boolean,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    folders: List<FolderEntry>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
    onCycleStack: (Int, Boolean) -> Unit = { _, _ -> },
    /** B40: the compact (cover) and expanded (inner) [LayoutPreset]s, used to size a host widget's [WidgetOptionsSizes] on both panels regardless of which one is showing right now. */
    compactPreset: LayoutPreset? = null,
    expandedPreset: LayoutPreset? = null,
    /** B46 "Dvojice aplikací": rendered alongside [folders] — see PairTile.kt. */
    pairs: List<PairEntry> = emptyList(),
    onLaunchPair: (PairEntry, android.graphics.Rect?) -> Unit = { _, _ -> },
    onPairActions: (String) -> Unit = {},
    /** True when [target] itself is the cell a hold-to-pair drag has dwelled on long enough for
     * [appOnAppDropOutcome] to turn the release into a Pair — see LauncherScreen.kt's root. */
    pairDropArmed: Boolean = false,
) {
    trackRecomposition("SharedHomeGrid")
    val rowHeight = geometry.rowHeight
    val iconSize = geometry.iconSize
    val morph = LocalMorphController.current
    val pageStart = homeCellIndex(page, 0)
    val pageRange = pageStart until pageStart + HOME_CELLS
    fun savedAt(index: Int) = if (page == -1) savedLeadingSlots.getOrNull(homeCellLocal(index)) else savedSlots.getOrNull(index)
    fun previewAt(index: Int) = if (page == -1) previewLeadingSlots.getOrNull(homeCellLocal(index)) else previewSlots.getOrNull(index)
    fun savedIndexOf(id: String) = if (page == -1) savedLeadingSlots.indexOf(id).takeIf { it >= 0 }?.let { homeCellIndex(-1, it) }
        else savedSlots.indexOf(id).takeIf { it >= 0 }
    fun previewIndexOf(id: String) = if (page == -1) previewLeadingSlots.indexOf(id).takeIf { it >= 0 }?.let { homeCellIndex(-1, it) }
        else previewSlots.indexOf(id).takeIf { it >= 0 }
    val draggedId = drag.source?.appId
    val homeTarget = (target as? DropTarget.Home)?.index
    val source = drag.source?.target as? DropTarget.Home
    val draggedPreviewIndex = draggedId?.let(::previewIndexOf) ?: -1
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        homeTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Dock -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val pending = widgets.pendingPlacement?.takeIf { it.page == page }
    val pendingIsReplacement = pending != null && widgetPlacements.any { it.slot == pending.slot }
    val pageWidgets = widgetPlacements.filter { it.page == page } + listOfNotNull(pending?.takeUnless { pendingIsReplacement })
    // The leading canvas shows its virtual default (one large Photos card) while nothing is
    // placed on it; the default is never persisted and registers no drag region.
    val leadingDefaults = if (page == -1 && leadingPaneEmpty(savedLeadingSlots + previewLeadingSlots, widgetPlacements, pending))
        DEFAULT_LEADING_PLACEMENTS else emptyList()
    // B16: Today's (page -1 only) settle-once spring plays items in reading order, so gaps
    // between occupied cells never stretch the ~40 ms stagger out; every kind of item shares one
    // rank space, keyed so an icon and a widget never collide.
    val settleIndexByKey: Map<String, Int> = if (page != -1) emptyMap() else buildList {
        (savedLeadingSlots + previewLeadingSlots).filterNotNull().distinct().forEach { id ->
            val savedIndex = savedIndexOf(id) ?: -1
            val previewIndex = previewIndexOf(id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val local = renderIndex - pageStart
            add("app:$id" to leadingReadingRank(local / GRID_COLUMNS, local % GRID_COLUMNS))
        }
        folders.forEach { folder ->
            val savedIndex = savedIndexOf(folder.id) ?: -1
            val previewIndex = previewIndexOf(folder.id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val local = renderIndex - pageStart
            add("folder:${folder.id}" to leadingReadingRank(local / GRID_COLUMNS, local % GRID_COLUMNS))
        }
        pairs.forEach { pair ->
            val savedIndex = savedIndexOf(pair.id) ?: -1
            val previewIndex = previewIndexOf(pair.id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val local = renderIndex - pageStart
            add("pair:${pair.id}" to leadingReadingRank(local / GRID_COLUMNS, local % GRID_COLUMNS))
        }
        pageWidgets.forEach { p -> add("widget:${p.slot}" to leadingReadingRank(p.row, p.column)) }
        leadingDefaults.forEach { p -> add("default:${p.id}" to leadingReadingRank(p.row, p.column)) }
    }.sortedBy { it.second }.mapIndexed { index, pair -> pair.first to index }.toMap()
    val renderedRows = maxOf(GRID_ROWS, pageWidgets.maxOfOrNull { it.row + it.spanY } ?: GRID_ROWS)
    val topPitch = (geometry.widgetHeight + 18f) / 2f
    fun rowTop(row: Int) = if (row <= 2) row * topPitch else geometry.widgetHeight + 18f + (row - 2) * rowHeight
    BoxWithConstraints(Modifier.fillMaxWidth().height(rowTop(renderedRows).dp)) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / 4
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val rowHeightPx = with(density) { rowHeight.dp.toPx() }

        repeat(HOME_CELLS) { localIndex ->
            val globalIndex = pageStart + localIndex
            val cell = DropTarget.Home(globalIndex)
            val savedId = savedAt(globalIndex)
            val savedApp = appsById[savedId]
            val savedFolder = folders.firstOrNull { it.id == savedId }
            val savedPair = pairs.firstOrNull { it.id == savedId }
            val previewId = previewAt(globalIndex)
            val highlighted = drag.active && target == cell
            // B46 "Dvojice aplikací": a drag dwelling on this cell past the hold threshold will
            // create a Pair instead of a Folder on release — see appOnAppDropOutcome/finishDrag.
            val pairArmed = highlighted && pairDropArmed
            val gap = hiddenIndex == globalIndex
            val row = localIndex / GRID_COLUMNS
            val cellHeight = rowTop(row + 1) - rowTop(row)
            Box(Modifier.offset(x = cellWidth * (localIndex % GRID_COLUMNS), y = rowTop(row).dp)
                .width(cellWidth).height(cellHeight.dp).testTag("home-cell-$globalIndex")
                .dropRegion(drag, cell, savedApp?.id ?: savedFolder?.id ?: savedPair?.id, page)
                .combinedClickable(
                    onClick = { if (drag.editMode) drag.onEditModeExit() else savedFolder?.let { onFolder(it.id) } },
                    onLongClick = { if (savedId == null && !drag.active) onEmptyWidget(globalIndex) })
                .background(if (highlighted) Glass.copy(alpha = .25f) else Color.Transparent, RoundedCornerShape(16.dp))
                .border(if (highlighted) 2.dp else 0.dp,
                    if (highlighted) Color.White.copy(alpha = .8f) else Color.Transparent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.TopCenter) {
                if (drag.active && drag.source?.appId != null && (gap || previewId == null)) Box(
                    Modifier.size(iconSize.dp).testTag(if (gap) "drag-gap-home-$globalIndex" else "empty-home-slot-$globalIndex")
                        .background(Glass.copy(alpha = if (gap) .16f else .08f), RoundedCornerShape(18.dp))
                        .border(if (gap) 2.dp else 1.dp, Color.White.copy(alpha = if (gap) .55f else .3f), RoundedCornerShape(18.dp)))
                if (pairArmed) PairFormingIndicator(Modifier.align(Alignment.BottomEnd)
                    .padding(4.dp).testTag("pair-forming-$globalIndex"))
            }
        }

        val ids = (if (page == -1) savedLeadingSlots + previewLeadingSlots
            else savedSlots.slicePage(pageRange) + previewSlots.slicePage(pageRange)).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedIndexOf(id) ?: -1
            val previewIndex = previewIndexOf(id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val localIndex = renderIndex - pageStart
                val row = localIndex / GRID_COLUMNS
                val column = localIndex % GRID_COLUMNS
                // B24: neighbours shuffle and the dropped icon settles with a bouncy spring
                // (~4 % overshoot) instead of the critically-damped default.
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset((column * cellWidthPx).roundToInt(), with(density) { rowTop(row).dp.toPx() }.roundToInt()),
                    animationSpec = IconSettleSpring,
                    label = "home insertion $id",
                )
                val visible = previewIndex in pageRange && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (dimDragged && id == draggedId) .28f else 1f,
                    label = "home insertion visibility $id",
                )
                val (settleScale, settleAlpha) = rememberSettleAnim(morph, settleIndexByKey["app:$id"])
                // B33: Today's items (page -1 only) unfold from the hinge as the left half
                // clears/frosts; see HingeUnfold.kt.
                Box(Modifier.offset { animatedOffset }.width(cellWidth).height(rowHeight.dp)
                    .graphicsLayer { scaleX = settleScale; scaleY = settleScale }
                    .alpha(opacity * settleAlpha)
                    .hingeUnfold(if (page == -1) morph else null, row, column)
                    .homeEditWiggle(drag, renderIndex)
                    .testTag("home-app-$id"), contentAlignment = Alignment.TopCenter) {
                    if (visible) AppTile(app, iconSize, labels,
                        onClick = { onLaunch(app, it) }, onLongClick = { onActions(app) })
                    if (visible && drag.editMode) EditRemoveBadge(
                        onRemove = { drag.onEditRemove(DropTarget.Home(renderIndex)) },
                        modifier = Modifier.align(Alignment.TopStart))
                }
            }
        }
        folders.forEach { folder ->
            val savedIndex = savedIndexOf(folder.id) ?: -1
            val previewIndex = previewIndexOf(folder.id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val localIndex = renderIndex - pageStart
            val row = localIndex / GRID_COLUMNS
            val column = localIndex % GRID_COLUMNS
            val x = cellWidth * column
            val y = rowTop(row).dp
            key(folder.id) {
                val (settleScale, settleAlpha) = rememberSettleAnim(morph, settleIndexByKey["folder:${folder.id}"])
                Box(Modifier.offset(x = x, y = y).width(cellWidth).height(rowHeight.dp)
                    .homeEditWiggle(drag, renderIndex)) {
                    FolderTile(folder, appsById, iconSize, labels, drag, page,
                        Modifier.fillMaxSize()
                            .graphicsLayer { scaleX = settleScale; scaleY = settleScale; alpha = settleAlpha }
                            .hingeUnfold(if (page == -1) morph else null, row, column)
                            .testTag("home-folder-${folder.id}"), onClick = { onFolder(folder.id) })
                    if (drag.editMode) EditRemoveBadge(
                        onRemove = { drag.onEditRemove(DropTarget.Home(renderIndex)) },
                        modifier = Modifier.align(Alignment.TopStart))
                }
            }
        }
        pairs.forEach { pair ->
            val savedIndex = savedIndexOf(pair.id) ?: -1
            val previewIndex = previewIndexOf(pair.id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val localIndex = renderIndex - pageStart
            val row = localIndex / GRID_COLUMNS
            val column = localIndex % GRID_COLUMNS
            val x = cellWidth * column
            val y = rowTop(row).dp
            key(pair.id) {
                val (settleScale, settleAlpha) = rememberSettleAnim(morph, settleIndexByKey["pair:${pair.id}"])
                PairTile(pair, appsById, iconSize, labels,
                    Modifier.offset(x = x, y = y).width(cellWidth).height(rowHeight.dp)
                        .graphicsLayer { scaleX = settleScale; scaleY = settleScale; alpha = settleAlpha }
                        .hingeUnfold(if (page == -1) morph else null, row, column)
                        .testTag("home-pair-${pair.id}"),
                    onClick = { bounds -> onLaunchPair(pair, bounds) }, onLongClick = { onPairActions(pair.id) })
            }
        }
        pageWidgets.forEach { placement ->
            key("widget-${placement.slot}") {
                val x = cellWidth * placement.column + 5.dp
                val width = (cellWidth * placement.spanX - 10.dp).coerceAtLeast(1.dp)
                val y = rowTop(placement.row)
                val height = (rowTop(placement.row + placement.spanY) - y - 18f).coerceAtLeast(48f)
                val (settleScale, settleAlpha) = rememberSettleAnim(morph, settleIndexByKey["widget:${placement.slot}"])
                val settleModifier = Modifier.graphicsLayer { scaleX = settleScale; scaleY = settleScale; alpha = settleAlpha }
                    .hingeUnfold(if (page == -1) morph else null, placement.row, placement.column)
                Box(Modifier.offset(x = x, y = y.dp).width(width).height(height.dp)
                    .homeEditWiggle(drag, placement.slot)) {
                    if (placement == pending) Surface(Modifier.fillMaxSize().then(settleModifier)
                        .testTag("widget-pending-${placement.slot}").semantics(mergeDescendants = true) {
                            contentDescription = "Pending ${widgets.pendingProvider?.shortClassName ?: "widget"}"
                        }, color = Glass.copy(alpha = .72f),
                        shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(8.dp)); Text("Finish widget setup", color = Ink)
                        }
                    } else {
                        // B40: both panels' dp box for this placement, from the SAME span (pane
                        // identity) — cover+inner on a Home page, inner-only on Today (page -1,
                        // which the cover never shows). Only host widgets (id >= 0) need it.
                        val panelSizes = if (placement.id < 0) null else remember(placement.id, placement.column,
                            placement.row, placement.spanX, placement.spanY, page, labels, compactPreset, expandedPreset) {
                            val expanded = expandedPreset ?: LayoutPreset()
                            if (page == -1) leadingPanelContentSize(expanded, labels,
                                placement.column, placement.row, placement.spanX, placement.spanY)
                            else bothPanelContentSizes(compactPreset ?: expanded, expanded, labels,
                                placement.column, placement.row, placement.spanX, placement.spanY)
                        }
                        MovableWidget(placement.id, placement.slot, widgets, drag, target,
                            Modifier.fillMaxSize().then(settleModifier), page = page,
                            appearance = placement.appearance, tint = placement.tint,
                            stackMembers = placement.stackMembers,
                            onCycleStack = { forward -> onCycleStack(placement.slot, forward) },
                            panelSizes = panelSizes) { onWidget(placement.slot) }
                    }
                    if (drag.editMode && placement != pending) EditRemoveBadge(
                        onRemove = { drag.onEditRemove(DropTarget.Widget(placement.slot)) },
                        modifier = Modifier.align(Alignment.TopStart))
                }
            }
        }
        leadingDefaults.forEach { placement ->
            key("leading-default-${placement.id}") {
                val x = cellWidth * placement.column + 5.dp
                val width = (cellWidth * placement.spanX - 10.dp).coerceAtLeast(1.dp)
                val y = rowTop(placement.row)
                val height = (rowTop(placement.row + placement.spanY) - y - 18f).coerceAtLeast(48f)
                // Fades while something is being dragged so the cells it covers show their targets.
                val opacity by animateFloatAsState(if (drag.active && drag.moved) .3f else 1f, label = "leading default")
                val (settleScale, settleAlpha) = rememberSettleAnim(morph, settleIndexByKey["default:${placement.id}"])
                LeadingDefaultWidget(placement.id, onOptions = { onEmptyWidget(pageStart) },
                    Modifier.offset(x = x, y = y.dp).width(width).height(height.dp)
                        .graphicsLayer { scaleX = settleScale; scaleY = settleScale }
                        .alpha(opacity * settleAlpha)
                        .hingeUnfold(if (page == -1) morph else null, placement.row, placement.column))
            }
        }
    }
}

@Composable
private fun DockAppColumn(
    savedDock: List<String?>,
    previewDock: List<String?>,
    appsById: Map<String, AppEntry>,
    rowHeight: Float,
    iconSize: Float,
    drag: HomeDragState,
    target: DropTarget?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onChoose: (Int) -> Unit,
) {
    val draggedId = drag.source?.appId
    val dockTarget = (target as? DropTarget.Dock)?.index
    val source = drag.source?.target as? DropTarget.Dock
    val draggedPreviewIndex = previewDock.indexOf(draggedId)
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        dockTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Home -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val launchBounds = remember(savedDock.size) { List(savedDock.size) { android.graphics.Rect() } }
    val interactions = remember(savedDock.size) { List(savedDock.size) { MutableInteractionSource() } }
    // B23: the shared press spring, same feel as Home/App Library/folder icons.
    val slotScales = savedDock.indices.map { index ->
        val pressed by interactions[index].collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) ICON_PRESS_SCALE else 1f, animationSpec = IconPressSpring, label = "dock press $index")
        scale
    }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.dp.toPx() }
    Box(Modifier.fillMaxWidth().height((rowHeight * savedDock.size).dp)) {
        savedDock.indices.forEach { index ->
            val cell = DropTarget.Dock(index)
            val savedApp = appsById[savedDock[index]]
            val previewId = previewDock.getOrNull(index)
            val highlighted = drag.active && target == cell
            val gap = hiddenIndex == index
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                .background(if (highlighted) Color.White.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                when {
                    gap -> Box(Modifier.size(iconSize.dp).testTag("drag-gap-dock-$index")
                        .background(Glass.copy(alpha = .16f), RoundedCornerShape(14.dp))
                        .border(2.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(14.dp)))
                    previewId == null -> Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                .testTag("dock-slot-$index").dropRegion(drag, cell, savedApp?.id)
                .semantics(mergeDescendants = true) { contentDescription = savedApp?.label ?: "Choose dock app ${index + 1}" }
                .combinedClickable(interactionSource = interactions[index], indication = LocalIndication.current, role = Role.Button, onClick = {
                    if (savedApp != null) onLaunch(savedApp, launchBounds[index]) else onChoose(index)
                }, onLongClick = null)
                .semantics { onLongClick("Choose dock app") { onChoose(index); true } })
        }

        val ids = (savedDock + previewDock).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedDock.indexOf(id)
            val previewIndex = previewDock.indexOf(id)
            val renderIndex = previewIndex.takeIf { it >= 0 } ?: savedIndex.takeIf { it >= 0 } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset(0, (renderIndex * rowHeightPx).roundToInt()), animationSpec = IconSettleSpring, label = "dock insertion $id")
                val visible = previewIndex >= 0 && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (!visible) 0f else if (dimDragged && id == draggedId) .28f else 1f,
                    label = "dock insertion visibility $id",
                )
                Box(Modifier.offset { animatedOffset }.fillMaxWidth().height(rowHeight.dp).alpha(opacity)
                    .homeEditWiggle(drag, savedIndex)
                    .testTag("dock-app-$id"), contentAlignment = Alignment.Center) {
                    val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[app.packageName] ?: 0
                    Box(Modifier.size(iconSize.dp).notificationBadge(badgeCount)) {
                        Image(app.icon.asImageBitmap(), null, Modifier.fillMaxSize().testTag("dock-icon-$id")
                            .onGloballyPositioned { if (savedIndex >= 0) launchBounds[savedIndex].set(it.boundsInWindow().toAndroidBounds()) }
                            .graphicsLayer { scaleX = slotScales[renderIndex]; scaleY = slotScales[renderIndex] }
                            .clip(LocalIconStyle.current.clipShape()))
                        // B49: live overlay over the dock icon, same rule as Home/folders.
                        LiveIconOverlay(app.packageName, LocalIconStyle.current.clipShape(), Modifier.matchParentSize())
                    }
                    if (drag.editMode && savedIndex >= 0) EditRemoveBadge(
                        onRemove = { drag.onEditRemove(DropTarget.Dock(savedIndex)) },
                        modifier = Modifier.align(Alignment.TopStart))
                }
            }
        }
    }
}

private fun <T> List<T>.slicePage(range: IntRange): List<T> =
    if (isEmpty() || range.first >= size) emptyList() else subList(range.first, minOf(range.last + 1, size))

@Composable
private fun FolderTile(folder: FolderEntry, apps: Map<String, AppEntry>, size: Float, labels: Boolean,
    drag: HomeDragState, page: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // B23: same shared press spring as every other icon surface.
    val interaction = remember { MutableInteractionSource() }
    // B25 follow-up: while this folder's glass card is open (or shrinking back into place), the
    // real tile underneath fades out in step with the card's own progress instead of staying
    // visible the whole time — the frost/dim behind the card no longer has to hide it alone.
    val cardAlpha = if (drag.openFolderId == folder.id) 1f - drag.openFolderCardProgress else 1f
    // B41 Liquid Glass: folder tiles pick up the same wallpaper-refracting backing as Clear-glass
    // icons/dock instead of the flat Glass fill, gated on the same style flag.
    val folderCorner = RoundedCornerShape((size * .24f).dp)
    val folderIconStyle = LocalIconStyle.current
    // Badges (redesign after Tom's 2026-09-17 feedback): a closed folder shows one badge for the
    // whole stack, the sum of every member app's own count.
    val badgeCounts = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current
    val folderBadge = remember(folder.appIds, apps, badgeCounts) {
        cz.pflanzer.foldduo.notifications.folderBadgeCount(folder.appIds.mapNotNull { apps[it]?.packageName }, badgeCounts)
    }
    Column(modifier.alpha(cardAlpha).clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).semantics(mergeDescendants = true) {
        contentDescription = "Folder ${folder.title}, ${folder.appIds.size} apps"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(folderCorner).iconPressScale(interaction)
            // Výkon 3: closed folder tiles never bulge either — cached bitmap, not a live layer.
            .then(if (folderIconStyle.usesLiquidGlass) Modifier.liquidGlassPanelCached(folderCorner, (size * .24f).dp)
                else Modifier.background(Glass.copy(alpha = .72f)))
            .border(1.dp, Color.White.copy(alpha = .55f), folderCorner)
            .dropRegion(drag, DropTarget.Folder(folder.id), page = page, folderId = folder.id)
            .notificationBadge(folderBadge)
            .testTag("folder-drop-${folder.id}")) {
            folder.appIds.take(4).forEachIndexed { index, id ->
                apps[id]?.let { app ->
                    Box(Modifier.align(when (index) {
                        0 -> Alignment.TopStart; 1 -> Alignment.TopEnd; 2 -> Alignment.BottomStart; else -> Alignment.BottomEnd
                    }).padding(5.dp).size((size * .38f).dp)) {
                        Image(app.icon.asImageBitmap(), null, Modifier.fillMaxSize().clip(LocalIconStyle.current.clipShape()))
                        // B49: live overlay on a folder's mini member icons too.
                        LiveIconOverlay(app.packageName, LocalIconStyle.current.clipShape(), Modifier.matchParentSize())
                    }
                }
            }
        }
        if (labels) Text(folder.title, color = Color.White, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The Home grid's own icon tile: the [IconRenderer] bitmap at [size], badge/live-icon overlays,
 * Liquid Glass backing, press scale, and a centred single-line label. `internal` (not `private`)
 * so `predict/PredictionUi.kt`'s `SuggestionsRow` can render the exact same tile instead of its
 * own hand-rolled icon+label column — see STATUS.md "Návrhy jako dlaždice mřížky".
 */
@Composable
internal fun AppTile(app: AppEntry, size: Float, labels: Boolean, modifier: Modifier = Modifier, onClick: (android.graphics.Rect) -> Unit, onLongClick: () -> Unit) {
    trackRecomposition("AppTile")
    val interaction = remember { MutableInteractionSource() }
    val iconSize by animateDpAsState(size.dp, label = "icon size")
    val bounds = remember { android.graphics.Rect() }
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = app.label }
        .clickable(interactionSource = interaction, indication = LocalIndication.current,
            role = Role.Button, onClick = { onClick(bounds) })
        .semantics { onLongClick("App options") { onLongClick(); true } }.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // B23: shared press spring instead of a hand-rolled tween (see IconMotion.kt).
        val iconStyle = LocalIconStyle.current
        val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[app.packageName] ?: 0
        Box(Modifier.size(iconSize).notificationBadge(badgeCount)) {
            // B41 Liquid Glass: a wallpaper-refracting panel behind the (mostly transparent)
            // Clear-glass bitmap; static bulge here, the drag ghost below animates its own.
            // Výkon 3 "kreslení na inneru": a resting Home tile's glass never bulges, so it is
            // built once into a bitmap (LiquidGlassBacking.kt) instead of carrying its own
            // per-frame RenderEffect layer — the fix for the swipe-time jank a device trace
            // caught in ~28 icons x 2 panes each doing their own offscreen shader pass.
            if (iconStyle.usesLiquidGlass) Box(Modifier.matchParentSize()
                .liquidGlassPanelCached(iconStyle.clipShape(), (size * ROUNDED_SQUARE_RADIUS).dp))
            Image(app.icon.asImageBitmap(), null, Modifier.fillMaxSize().onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
                .iconPressScale(interaction).clip(iconStyle.clipShape()))
            // B49: real clock hands / today's date / a battery ring / a compass needle over a
            // handful of known packages' baked icons — never baked into the shared bitmap itself.
            LiveIconOverlay(app.packageName, iconStyle.clipShape(), Modifier.matchParentSize())
        }
        if (labels) Text(app.label, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 3f)), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        color = Glass.copy(alpha = .24f), shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .18f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween, content = content)
    }
}

@Composable
private fun currentTime(): LocalDateTime {
    val time by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(1000) } }
    return time
}

/**
 * The built-in digital clock (`CLOCK_WIDGET`), kept for saved layouts and restyled as the iOS
 * card ([DigitalClockWidget]). On the Home grid it is a 2x2 tile with a small time; [withDate]
 * is the full-width Today variant: large time with the weekday and date underneath.
 */
@Composable
internal fun ClockCard(onClick: () -> Unit, withDate: Boolean = false,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DigitalClockWidget(widgetSizeFor(maxWidth.value, maxHeight.value), onClick, withDate, appearance = appearance, tint = tint)
    }
}

@Composable
internal fun DateCard(onClick: () -> Unit) {
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Text(date.format(DateTimeFormatter.ofPattern("EEEE")), color = Color.White, fontSize = 12.sp, maxLines = 1)
        Text(date.dayOfMonth.toString(), color = Color.White, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 42.sp)
        Text(date.format(DateTimeFormatter.ofPattern("MMMM")), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
    }
}

@Composable
internal fun ExpandedCard(onClick: () -> Unit) {
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Column {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE")), color = Color.White, fontSize = 22.sp)
            Text(date.format(DateTimeFormatter.ofPattern("MMMM d")), color = Color.White.copy(alpha = .8f), fontSize = 16.sp)
        }
        Column {
            Icon(Icons.Rounded.Widgets, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text("A little more room.", color = Color.White, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))
            Text("Add a calendar, photos, or another widget.", color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onClick) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add widget") }
        }
    }
}

@Composable
internal fun WidgetSlot(id: Int, slot: Int, controller: WidgetController, modifier: Modifier, onAdd: () -> Unit,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null,
    /** A host widget shows a snapshot of itself instead of its live view while this is on ([HostWidgetView]). */
    frozen: Boolean = false,
    /** B40: the dp box(es) this placement gets, cover and inner (or just inner on the Today column) — forwarded to the host view's `updateAppWidgetSize`. Null for builtins/the picker preview, which have no placement to size against. */
    panelSizes: WidgetOptionsSizes? = null, fallback: @Composable () -> Unit) {
    var restoreMessage by remember(slot) { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(24.dp)).testTag("widget-slot-$slot")) {
        val displayedContentSize = WidgetContentSize(maxWidth.value, maxHeight.value)
        if (id == NEEDS_BINDING_WIDGET) {
            val restore = controller.restoreDescriptor(slot)
            Surface(Modifier.fillMaxSize().testTag("widget-restore-$slot"), color = Glass.copy(alpha = .88f),
                shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = .7f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(restore?.title ?: "Saved widget", color = Ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(restore?.profileLabel ?: "Unavailable profile", color = Ink.copy(alpha = .72f),
                        style = MaterialTheme.typography.bodySmall)
                    restoreMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    Row {
                        TextButton(onClick = {
                            if (!controller.rebindRestoredWidget(slot, contentSize = displayedContentSize))
                                restoreMessage = "That provider or profile isn’t available. Choose a replacement."
                        },
                            modifier = Modifier.testTag("widget-restore-reconnect-$slot")) { Text("Reconnect") }
                        TextButton(onClick = onAdd, modifier = Modifier.testTag("widget-restore-replace-$slot")) { Text("Replace") }
                    }
                }
            }
            return@BoxWithConstraints
        }
        val info = remember(id) { if (id >= 0) controller.manager.getAppWidgetInfo(id) else null }
        if (info == null) fallback()
        else {
            key(id) {
                // HostWidgetSkin (ZeroPaddingWidgetHostView) samples the provider's own card
                // background before stripping it; the sample decides Glass's dark variant here.
                var sampledDark by remember(id) { mutableStateOf<Boolean?>(null) }
                HostWidgetFrame(appearance, tint, sampledDark = sampledDark) {
                    HostWidgetView(id, info, controller, frozen, appearance = appearance,
                        panelSizes = panelSizes, onDarknessSampled = { sampledDark = it })
                }
            }
        }
    }
}

/**
 * The live AppWidgetHostView of host widget [id] and, while [frozen], a bitmap snapshot of it
 * drawn in its place (same size, inside the same [HostWidgetFrame] clip) with the live view
 * INVISIBLE. The expanded left half is a graphicsLayer with a RenderEffect (the frost); an
 * AndroidView inside it is not composited through such a layer and drops out of the frosted
 * frames, so the snapshot stands in for the ≤ 1.5 s a morph drives the half
 * (MorphController.leftHalfFrozen, on until the half is sharp and in place again). The capture
 * runs in composition, in the frame that first draws the frost (the controller flips the flag
 * together with the morph's first state write), so no frame shows the gap; a view that has no
 * layout yet (the unfold morph right after the relaunch, under One UI's screen-on fade) is
 * retried once per frame until it has one. The thaw hands over with a frame of overlap: the
 * live view is VISIBLE again while the last snapshot is still drawn over it, so the view has
 * drawn once before the snapshot goes and nothing blinks between the two. The snapshot is not
 * interactive.
 *
 * [appearance] drives [HostWidgetSkinHostView.applySkin] on every recomposition the AndroidView
 * `update` block runs for, so choosing Glass/a colour from the long-press menu re-skins the live
 * view immediately rather than waiting for the provider's next RemoteViews push; [onDarknessSampled]
 * forwards the provider's own (now-stripped) card colour up to [HostWidgetFrame]. [panelSizes]
 * (B40) is forwarded the same way to [HostWidgetSkinHostView.applyPanelSizes] every recomposition
 * — including the one a panel swap triggers (the placement's `HomeGeometry` changes) and the
 * first one after bind — so the provider always has both panels' `OPTION_APPWIDGET_SIZES`.
 *
 * B43: the actual bitmap comes from [WidgetSnapshotCache], not a fresh draw every freeze — see
 * its doc for the hardware-backing and close→open reuse this buys.
 */
@Composable
private fun HostWidgetView(id: Int, info: AppWidgetProviderInfo, controller: WidgetController, frozen: Boolean,
    appearance: WidgetAppearance = WidgetAppearance.Auto, panelSizes: WidgetOptionsSizes? = null,
    onDarknessSampled: (Boolean) -> Unit = {}) {
    trackRecomposition("HostWidgetView")
    val morph = LocalMorphController.current
    val hostView = remember(id) { arrayOfNulls<AppWidgetHostView>(1) }
    // Výkon 5 "stránky složené předem": the live `AndroidView` (its factory is
    // `controller.host.createView`, the ~100 ms provider inflate/bind) never mounts on the same
    // frame this id first (re)enters composition — that frame is now either the strip's one-time
    // initial composition or a layout edit's replacement (every page composes once and stays
    // composed, ExpandedWorkspace), never a swipe settle, but deferring it one more frame keeps it
    // off that frame too on general principle, the same `withFrameNanos` idiom the freeze/thaw
    // retry below already uses. `framePosted` flips once per `id` and stays flipped; the actual
    // decision is `hostWidgetShouldBindLive` (HostWidgetBindPolicy.kt) so it has its own JVM test.
    var framePosted by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { withFrameNanos { }; framePosted = true }
    val bindLive = hostWidgetShouldBindLive(framePosted)
    val snapshot = remember(id, frozen) {
        if (!frozen) null else hostView[0]?.let { WidgetSnapshotCache.capture(id, it) }?.also { morph?.noteHostWidgetFrozen(id, it.width, it.height) }
    }
    var lateSnapshot by remember(id, frozen) { mutableStateOf<Bitmap?>(null) }
    // The snapshot of the freeze that just ended, kept drawn for the hand-over frame.
    var lingering by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id, frozen, snapshot) {
        if (!frozen) {
            if (lingering != null) { withFrameNanos { }; lingering = null }
            morph?.noteHostWidgetThawed(id)
            WidgetSnapshotCache.scheduleEviction(id)
            return@LaunchedEffect
        }
        if (snapshot != null) return@LaunchedEffect
        while (lateSnapshot == null) {
            withFrameNanos { }
            lateSnapshot = hostView[0]?.let { WidgetSnapshotCache.capture(id, it) }?.also { morph?.noteHostWidgetFrozen(id, it.width, it.height) }
        }
    }
    DisposableEffect(id) { onDispose { morph?.noteHostWidgetThawed(id); WidgetSnapshotCache.evictNow(id) } }
    val bitmap = snapshot ?: lateSnapshot
    SideEffect { if (bitmap != null && lingering !== bitmap) lingering = bitmap }
    // Before the live view has ever bound there is no [View] yet for WidgetSnapshotCache.capture
    // to draw from — peek() at whatever this id's own cache already holds instead (an earlier
    // bind's bitmap; null the very first time this id is ever hosted, which draws nothing this
    // one frame rather than a stale, unrelated widget's pixels).
    val preBindSnapshot = if (bindLive) null else WidgetSnapshotCache.peek(id)
    val shown = hostWidgetOverlay(snapshot, lateSnapshot, preBindSnapshot, lingering)
    val image = remember(shown) { shown?.asImageBitmap() }
    if (bindLive) {
        AndroidView(factory = { context -> controller.host.createView(context, id, info).also { hostView[0] = it } },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.visibility = if (frozen && image != null) View.INVISIBLE else View.VISIBLE
                (view as? HostWidgetSkinHostView)?.applySkin(appearance, onDarknessSampled)
                (view as? HostWidgetSkinHostView)?.applyPanelSizes(panelSizes)
            })
    }
    if (image != null) Image(image, null, Modifier.fillMaxSize().testTag("widget-snapshot-$id"), contentScale = ContentScale.FillBounds)
}

private fun widgetLabel(id: Int, controller: WidgetController) =
    if (id < 0) builtinWidgetLabel(id) else controller.label(id)

@Composable
private fun MovableWidget(id: Int, slot: Int, controller: WidgetController, drag: HomeDragState,
    target: DropTarget?, modifier: Modifier, page: Int,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null,
    /** B26 Smart stack: the placement's full membership (empty or one id for a plain widget). */
    stackMembers: List<Int> = emptyList(),
    /** Manual vertical swipe on a stack; ignored for a plain widget. */
    onCycleStack: (forward: Boolean) -> Unit = {},
    /** B40: the placement's dp box(es), forwarded to [WidgetSlot]. */
    panelSizes: WidgetOptionsSizes? = null,
    onAdd: () -> Unit) {
    trackRecomposition("MovableWidget")
    val cell = DropTarget.Widget(slot)
    // Only the leading pane (page -1) sits inside the expanded left-half frost layers; Home-page
    // host widgets are never frosted and stay live (HostWidgetView).
    val morph = LocalMorphController.current
    val dropModifier = modifier.dropRegion(drag, cell, page = page, widgetId = id)
        .alpha(if (drag.source?.target == cell) .3f else 1f)
        .border(if (drag.active && target == cell) 2.dp else 0.dp,
            if (drag.active && target == cell) Color.White else Color.Transparent, RoundedCornerShape(24.dp))
        .semantics { onLongClick("Move or replace widget") { onAdd(); true } }
    val renderMember = @Composable { shownId: Int ->
        val frozen = shownId >= 0 && page == -1 && morph?.leftHalfFrozen == true
        WidgetSlot(shownId, slot, controller, Modifier.fillMaxSize(), onAdd, appearance = appearance, tint = tint,
            frozen = frozen, panelSizes = panelSizes) {
            when (shownId) {
                CLOCK_ANALOG_WIDGET, CALENDAR_WIDGET, BATTERIES_WIDGET, PHOTOS_WIDGET ->
                    BuiltinAppleWidget(shownId, onAdd, appearance = appearance, tint = tint)
                CLOCK_WIDGET -> ClockCard(onAdd, appearance = appearance, tint = tint)
                DATE_WIDGET -> DateCard(onAdd)
                INFO_WIDGET -> if (slot % 3 == 2) ExpandedCard(onAdd) else GlassCard(onClick = onAdd) {
                    Icon(Icons.Rounded.Widgets, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text("Your widgets", color = Color.White, fontSize = 15.sp, maxLines = 1)
                    Text("Tap to choose", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                }
                else -> Surface(Modifier.fillMaxSize().clickable(onClick = onAdd), color = Glass.copy(alpha = .18f),
                    shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .25f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White)
                        Text(if (shownId >= 0) "Widget unavailable" else "Add widget", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if (stackMembers.size >= 2) {
        StackFlipContainer(activeId = id, memberIds = stackMembers, onSwipe = onCycleStack,
            modifier = dropModifier.testTag("widget-stack-$slot"), content = renderMember)
    } else {
        Box(dropModifier) { renderMember(id) }
    }
}

@Composable
private fun AppPicker(apps: List<AppEntry>, dockSlot: Int?, onSelect: (AppEntry) -> Unit, onClear: () -> Unit,
    onLongClick: (AppEntry) -> Unit, canSelect: (AppEntry) -> Boolean = { true }, blockedHint: String? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 20.dp).imePadding()) {
        Text(if (dockSlot == null) "Your apps" else "Dock position ${dockSlot + 1}", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("search-field"),
            placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }, shape = RoundedCornerShape(20.dp))
        if (dockSlot != null) TextButton(onClick = onClear) { Text("Leave this position empty") }
        if (blockedHint != null) Text(blockedHint, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp).testTag("dock-full-guidance"))
        LazyColumn(Modifier.weight(1f)) {
            if (filtered.isEmpty()) item { Text("No apps found", Modifier.padding(vertical = 24.dp)) }
            items(filtered, key = { it.id }) { app ->
                val enabled = canSelect(app)
                Row(Modifier.fillMaxWidth().testTag("picker-app-${app.id}")
                    .combinedClickable(enabled = enabled, onClick = { onSelect(app) }, onLongClick = { onLongClick(app) })
                    .alpha(if (enabled) 1f else .45f)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon.asImageBitmap(), null, Modifier.size(44.dp).clip(LocalIconStyle.current.clipShape()))
                    Text(app.label, Modifier.padding(start = 16.dp).weight(1f), maxLines = 2)
                    if (dockSlot != null && enabled) Icon(Icons.Rounded.Add, "Choose ${app.label}")
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(state: LauncherState, initiallyWide: Boolean, model: LauncherModel, isDefaultHome: Boolean,
    onMakeDefault: () -> Unit, onClose: () -> Unit, onEditPins: () -> Unit, onWidget: (Int) -> Unit,
    onAddWidget: (Int) -> Unit, onRemoveWidget: (Int) -> Unit, onWallpaperPreview: () -> Unit,
    onExportLayout: () -> Unit, onImportLayout: () -> Unit,
    appearance: AppearanceState, onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit, onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit,
    backgrounds: LauncherBackgroundController,
    homePage: Int = 0, onAppearanceMotionFrost: (Boolean) -> Unit = {},
    onAppearanceSystemFrost: (Boolean) -> Unit = {},
    onAppearanceHapticAtFlat: (Boolean) -> Unit = {}) {
    var wide by rememberSaveable { mutableStateOf(initiallyWide) }
    val p = if (wide) state.expanded else state.compact
    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Make it yours", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close customization") }
        }
        Button(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth().testTag("default-home-settings")) {
            Text(if (isDefaultHome) "Change home app" else "Set as home app")
        }
        TextButton(onClick = onEditPins, modifier = Modifier.fillMaxWidth()) { Text("Choose home apps") }
        if (state.canUndoEdit) TextButton(onClick = { model.undoEdit(); onClose() }, modifier = Modifier.fillMaxWidth()) {
            Text("Undo last layout change")
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!wide, { wide = false }, label = { Text("Cover / compact") })
            FilterChip(wide, { wide = true }, label = { Text("Inner / expanded") })
        }
        SettingSlider("App icon size", "${p.iconSize.toInt()} dp", p.iconSize, 40f..68f) { model.setPreset(wide, p.copy(iconSize = it)) }
        SettingSlider("Space between rows", "${p.rowGap.toInt()} dp", p.rowGap, 0f..28f) { model.setPreset(wide, p.copy(rowGap = it)) }
        SettingSlider("Dock width", "${p.dockWidth.toInt()} dp", p.dockWidth, 56f..84f) { model.setPreset(wide, p.copy(dockWidth = it)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show app names", Modifier.weight(1f)); Switch(state.labels, model::setLabels, Modifier.testTag("label-switch"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Status at upper right", Modifier.weight(1f)); Switch(state.verticalStatus, model::setVerticalStatus, Modifier.testTag("status-switch"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Search button opens Google", Modifier.weight(1f))
            Switch(state.googleSearch, model::setGoogleSearch, Modifier.testTag("google-search-switch"))
        }
        Text("Opens Google’s search screen. All apps keeps local app search.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { model.setPreset(wide, LayoutPreset()) }) { Text("Reset this layout") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        TextButton(onClick = onWallpaperPreview, modifier = Modifier.fillMaxWidth().testTag("wallpaper-preview")) {
            Icon(Icons.Rounded.Wallpaper, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Apply matching wallpaper")
        }
        Text("Preview the current launcher background in Android’s wallpaper picker, then choose where to apply it.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Launcher background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = backgrounds::choosePhoto, enabled = !backgrounds.loading,
            modifier = Modifier.fillMaxWidth().testTag("background-choose")) { Text("Choose background photo") }
        if (backgrounds.photoSelected) OutlinedButton(onClick = backgrounds::reset,
            modifier = Modifier.fillMaxWidth().testTag("background-reset")) { Text("Reset to Duo dunes") }
        if (backgrounds.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("background-loading"))
        (backgrounds.errorMessage ?: backgrounds.successMessage)?.let { message ->
            TextButton(onClick = backgrounds::clearMessage, Modifier.fillMaxWidth().testTag("background-message")) { Text(message) }
        }
        Text("The selected photo stays on this device and is not included in layout backups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        AppearanceSettings(appearance, onAppearanceMode, onAppearanceManual, onAppearanceDeviceLocation, onAppearanceClear)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Layout backup", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportLayout, modifier = Modifier.weight(1f).testTag("layout-export")) { Text("Save") }
            OutlinedButton(onClick = onImportLayout, modifier = Modifier.weight(1f).testTag("layout-import")) { Text("Restore") }
        }
        Text("Restore always shows a review before changing Home.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Widgets · Page ${homePage + 1}", style = MaterialTheme.typography.titleMedium)
        state.widgetPlacements.filter { it.page == homePage }.forEach { placement ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${placement.spanX} × ${placement.spanY} widget · row ${placement.row + 1}", Modifier.weight(1f))
                IconButton(onClick = { onRemoveWidget(placement.slot) },
                    modifier = Modifier.semantics { contentDescription = "Remove widget" }) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                }
                TextButton(onClick = { onWidget(placement.slot) }) { Text("Replace") }
            }
        }
        if (wide) state.widgetPlacements.filter { it.page == -1 }.forEach { placement ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Unfolded-only page", Modifier.weight(1f))
                IconButton(onClick = { onRemoveWidget(placement.slot) },
                    modifier = Modifier.semantics { contentDescription = "Remove widget from Unfolded-only page" }) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                }
                TextButton(onClick = { onWidget(placement.slot) }) { Text("Replace") }
            }
        }
        TextButton(onClick = { onAddWidget(homePage) }, Modifier.fillMaxWidth()) { Text("Add widget to this page") }
        Text("Hold and drag an app to move it. Pause at the screen edge to turn pages. Release without moving for options.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun SettingSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row { Text(label, Modifier.weight(1f)); Text(valueLabel, color = MaterialTheme.colorScheme.secondary) }
        Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }
}

/**
 * "Appearance" block of the widget options sheet: five chips (Auto / Light / Dark / Glass /
 * Tinted) and, for Tinted, a row of colour swatches — the eight iOS presets plus "Match
 * wallpaper". Every tap applies at once; the local copies keep the sheet responsive even though
 * the placement it was opened with is not observed.
 */
@Composable
private fun WidgetAppearanceOptions(placement: WidgetPlacement, wallpaperTint: Int?, onAppearance: (WidgetAppearance, Int?) -> Unit) {
    var appearance by remember(placement.slot) { mutableStateOf(placement.appearance) }
    var tint by remember(placement.slot) { mutableStateOf(placement.tint) }
    val activeTint = tint ?: WIDGET_TINT_PRESETS.first().first
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WidgetAppearance.entries.forEach { option ->
            FilterChip(selected = appearance == option, onClick = { appearance = option; onAppearance(option, tint) },
                label = { Text(option.name) }, modifier = Modifier.testTag("widget-appearance-${option.name.lowercase()}"))
        }
    }
    if (appearance == WidgetAppearance.Tinted) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        WIDGET_TINT_PRESETS.forEachIndexed { index, (argb, name) ->
            TintSwatch(argb, name, selected = activeTint == argb, modifier = Modifier.testTag("widget-tint-$index")) {
                tint = argb; onAppearance(appearance, argb)
            }
        }
        if (wallpaperTint != null) TintSwatch(wallpaperTint, "Match wallpaper", selected = activeTint == wallpaperTint,
            modifier = Modifier.testTag("widget-tint-wallpaper"), icon = Icons.Rounded.Wallpaper) {
            tint = wallpaperTint; onAppearance(appearance, wallpaperTint)
        }
    }
}

@Composable
private fun TintSwatch(argb: Int, name: String, selected: Boolean, modifier: Modifier = Modifier,
    icon: ImageVector? = null, onClick: () -> Unit) {
    val ink = if (prefersDarkInk(argb)) Color.Black else Color.White
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick, role = Role.RadioButton)
        .semantics { contentDescription = name; stateDescription = if (selected) "Selected" else "Not selected" }
        .padding(2.dp).border(if (selected) 2.dp else 0.dp,
            if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
        .padding(4.dp).background(Color(argb), CircleShape), contentAlignment = Alignment.Center) {
        when {
            icon != null -> Icon(icon, null, Modifier.size(16.dp), tint = ink)
            selected -> Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = ink)
        }
    }
}

@Composable
private fun WidgetActions(
    placement: WidgetPlacement,
    constraints: WidgetSpanConstraints?,
    canConfigure: Boolean,
    onConfigure: () -> Unit,
    isValid: (Int, Int) -> Boolean,
    onResize: (Int, Int) -> Unit,
    onStartResize: (Int, Int) -> Unit,
    onMoveToPage: (Int) -> Boolean,
    homePages: Int,
    /** Unfolded: the left pane (page -1) is offered as a move target too. */
    expanded: Boolean = false,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    /** Today widgets always span the pane; only their height is adjustable. */
    fixedWidth: Boolean = false,
    /** iOS cards and host widgets: applies an appearance (and the tint it uses) to this placement at once. */
    onAppearance: ((WidgetAppearance, Int?) -> Unit)? = null,
    /** ARGB colour behind the "Match wallpaper" swatch; null hides the swatch. */
    wallpaperTint: Int? = null,
    /** B26 Smart stack: opens the member/reorder/smart-rotate sheet; null (a plain widget) hides the row. */
    onEditStack: (() -> Unit)? = null,
) {
    val sheetMaxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * .88f).toDp()
    }
    var width by remember(placement.slot, placement.spanX) { mutableIntStateOf(placement.spanX) }
    var height by remember(placement.slot, placement.spanY) { mutableIntStateOf(placement.spanY) }
    val minWidth = constraints?.minimum?.width ?: 2
    val minHeight = constraints?.minimum?.height ?: 2
    val maxWidth = minOf(GRID_COLUMNS - placement.column, constraints?.maximum?.width ?: GRID_COLUMNS)
    val maxHeight = minOf(GRID_ROWS - placement.row, constraints?.maximum?.height ?: GRID_ROWS)
    val feasible = placement.page >= -1 && placement.row in 0 until GRID_ROWS &&
        !(placement.id >= 0 && constraints == null) && minWidth <= maxWidth && minHeight <= maxHeight
    val valid = feasible && isValid(width, height)
    Column(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Widget options", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close widget options") }
        }
        if (canConfigure) ActionRow(Icons.Rounded.Settings, "Widget settings", onConfigure,
            Modifier.testTag("widget-settings-${placement.slot}"))
        if (onEditStack != null) ActionRow(Icons.Rounded.Layers, "Edit stack", onEditStack,
            Modifier.testTag("widget-edit-stack-${placement.slot}"))
        if (onAppearance != null) WidgetAppearanceOptions(placement, wallpaperTint, onAppearance)
        Text("Resize", style = MaterialTheme.typography.titleMedium)
        if (!fixedWidth) Button(enabled = feasible, onClick = { onStartResize(width, height) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resize on Home") }
        if (!feasible) Text("Move this widget into the seven-row grid before resizing.", color = MaterialTheme.colorScheme.error)
        // Target that had no room on the last attempt; cleared once the widget lands elsewhere.
        var blockedMove by remember(placement.slot, placement.page) { mutableStateOf<WidgetMoveTarget?>(null) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            widgetMoveTargets(placement.page, homePages, expanded).forEach { target ->
                TextButton(onClick = { blockedMove = target.takeUnless { onMoveToPage(target.page) } },
                    modifier = Modifier.testTag("widget-move-${placement.slot}-page-${target.page}")) { Text(target.label) }
            }
        }
        if (blockedMove != null) Text(NO_ROOM_FOR_SIZE_MESSAGE, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("widget-move-no-room"))
        if (!fixedWidth) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width", Modifier.weight(1f))
            IconButton(enabled = constraints?.canResizeHorizontally != false,
                onClick = { if (feasible) width = (width - 1).coerceAtLeast(minWidth) }) {
                Icon(Icons.Rounded.Remove, "Decrease widget width")
            }
            Text("$width columns", Modifier.width(88.dp), textAlign = TextAlign.Center)
            IconButton(enabled = constraints?.canResizeHorizontally != false,
                onClick = { if (feasible) width = (width + 1).coerceAtMost(maxWidth) }) {
                Icon(Icons.Rounded.Add, "Increase widget width")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Height", Modifier.weight(1f))
            IconButton(enabled = constraints?.canResizeVertically != false,
                onClick = { if (feasible) height = (height - 1).coerceAtLeast(minHeight) }) {
                Icon(Icons.Rounded.Remove, "Decrease widget height")
            }
            Text("$height rows", Modifier.width(88.dp), textAlign = TextAlign.Center)
            IconButton(enabled = constraints?.canResizeVertically != false,
                onClick = { if (feasible) height = (height + 1).coerceAtMost(maxHeight) }) {
                Icon(Icons.Rounded.Add, "Increase widget height")
            }
        }
        Text("Sizes that overlap another item are ignored.", style = MaterialTheme.typography.bodySmall)
        if (!valid) Text("That size overlaps another item or extends beyond the page.", color = MaterialTheme.colorScheme.error)
        Button(enabled = valid, onClick = { onResize(width, height); onClose() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Apply size") }
        ActionRow(Icons.Rounded.FindReplace, "Replace", onReplace)
        HorizontalDivider()
        ActionRow(Icons.Rounded.DeleteOutline, "Remove", onRemove, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
    }
}
