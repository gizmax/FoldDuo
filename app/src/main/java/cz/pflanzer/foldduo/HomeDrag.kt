package cz.pflanzer.foldduo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal data class DragRegion(val target: DropTarget, val bounds: Rect, val appId: String?, val page: Int?,
    val widgetId: Int? = null, val folderId: String? = null, val scope: String? = null) {
    val movable get() = appId != null || (widgetId != null && widgetId != EMPTY_WIDGET)
}

@Stable
internal class HomeDragState {
    val regions = mutableStateMapOf<DropTarget, DragRegion>()
    private val regionOwners = mutableMapOf<DropTarget, Any>()
    var source by mutableStateOf<DragRegion?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var origin by mutableStateOf(Offset.Zero)
    var rootBounds by mutableStateOf(Rect.Zero)
    val rootOrigin get() = rootBounds.topLeft
    var originPage = 0
    var moved by mutableStateOf(false)
    var activeSourceScope by mutableStateOf<String?>(null)
    /**
     * Mirrors LauncherScreen's `openFolderId` (B25 follow-up): shared here, rather than threaded
     * through SharedHomeGrid/HomePagePane as separate parameters, purely so `FolderTile` — which
     * already receives [HomeDragState] for its drop region — can read it alongside
     * [openFolderCardProgress] without new call-site plumbing.
     */
    var openFolderId by mutableStateOf<String?>(null)
    /** Live [0,1] open/close progress of the currently open folder's glass card (FolderPanel.kt). */
    var openFolderCardProgress by mutableFloatStateOf(0f)
    /**
     * B46 "Dvojice aplikací": when this drag's live drop target ([insertionTarget] in
     * LauncherScreen.kt's root) last *changed*, and to what — purely a stopwatch. The layout-aware
     * decision of whether that dwell time is actually "app dragged onto another app" lives in the
     * root composable (it alone has `state.layout` and `appsById`); [appOnAppDropOutcome] in
     * PairEditing.kt turns the resulting duration into Folder vs. Pair.
     */
    var pairHoverTarget by mutableStateOf<DropTarget?>(null)
        private set
    var pairHoverStartMs by mutableLongStateOf(0L)
        private set
    fun notePairHover(target: DropTarget?, atMs: Long) {
        if (target != pairHoverTarget) { pairHoverTarget = target; pairHoverStartMs = atMs }
    }

    /**
     * iPhone-style icon popover (2026-09-17 evening, see IconGesture.kt): the region a long-press
     * on a popover-eligible icon is anchored to, from the moment it appears until it is dismissed
     * or converted into a real drag. Kept separate from [source]/[active] so the drag-ghost/dim
     * visuals gated on those never appear for a hold that only ever opened the popover.
     */
    var iconPopover by mutableStateOf<DragRegion?>(null)
    /**
     * Home edit ("jiggle") mode, mirrored here the same way [openFolderId] mirrors LauncherScreen
     * state — every icon/folder/widget/dock wrapper already threads [HomeDragState] through, so
     * this avoids a new parameter down every one of those call sites (SharedHomeGrid,
     * HomePagePane, ExpandedWorkspace, DockAppColumn).
     */
    var editMode by mutableStateOf(false)
    /** Milliseconds since edit mode was entered, driven once at the root (LauncherScreen.kt); the
     * wiggle (HomeEditMode.kt's wiggleRotationDeg) reads this instead of each item running its
     * own clock. */
    var editModeClockMs by mutableLongStateOf(0L)
    /** Removes whatever [DropTarget] the edit mode's "-" badge was tapped on; set once at the
     * root, where [cz.pflanzer.foldduo.LauncherModel] is in scope. */
    var onEditRemove: (DropTarget) -> Unit = {}
    /** Tapping empty wallpaper while editing exits edit mode (Back and the "Done" pill also call
     * this at the root directly; this is only for the empty-cell tap, which has no other route
     * back to LauncherScreen's own state). */
    var onEditModeExit: () -> Unit = {}
    val active get() = source != null
    fun hit(point: Offset, pages: Set<Int>) = regions.values
        .filter { (it.page == null || it.page in pages) && it.bounds.contains(point) &&
            (source != null || it.target !is DropTarget.Folder) && it.scope == activeSourceScope }
        .maxByOrNull(::dragRegionPriority)
    fun destination(point: Offset, pages: Set<Int>): DragRegion? {
        regions[DropTarget.Remove]?.takeIf { source?.target !is DropTarget.Library && it.bounds.contains(point) }?.let { return it }
        return regions.values.filter {
            (it.page == null || it.page in pages) && it.bounds.contains(point) && when (it.target) {
                // A moving widget is rendered at its preview footprint and registers the
                // same Widget target again. Never let that preview shadow the Home cells
                // beneath it. Widgets always move through the cell grid.
                is DropTarget.Widget -> false
                is DropTarget.Home -> source?.appId != null || source?.target is DropTarget.Widget
                is DropTarget.Dock -> source?.appId != null
                is DropTarget.Folder -> source?.appId != null && source?.target !is DropTarget.Folder
                DropTarget.Remove -> source?.target !is DropTarget.Library
                is DropTarget.Library -> false
            }
        }.maxByOrNull(::dragRegionPriority)
    }
    fun clear() { source = null; moved = false }

    fun register(owner: Any, region: DragRegion) {
        regionOwners[region.target] = owner
        regions[region.target] = region
    }

    fun update(owner: Any, target: DropTarget, appId: String?, page: Int?, widgetId: Int?, folderId: String?, scope: String?) {
        regions[target]?.takeIf { regionOwners[target] === owner }?.let {
            if (it.appId != appId || it.widgetId != widgetId || it.page != page || it.folderId != folderId || it.scope != scope) {
                regions[target] = it.copy(appId = appId, widgetId = widgetId, page = page, folderId = folderId, scope = scope)
            }
        }
    }

    fun unregister(owner: Any, target: DropTarget) {
        if (regionOwners[target] === owner) {
            regionOwners.remove(target)
            regions.remove(target)
        }
    }
}

/** Page turning follows the window edges, including the space beside the fixed dock. */
internal fun dragEdgeDirection(point: Offset, window: Rect, edgeWidth: Float): Int = when {
    window.isEmpty || !window.contains(point) -> 0
    point.x < window.left + edgeWidth -> -1
    point.x > window.right - edgeWidth -> 1
    else -> 0
}

@Composable
internal fun Modifier.dropRegion(drag: HomeDragState, target: DropTarget, appId: String? = null, page: Int? = null,
    widgetId: Int? = null, folderId: String? = null, scope: String? = null): Modifier {
    val owner = remember { Any() }
    DisposableEffect(drag, target, owner) { onDispose { drag.unregister(owner, target) } }
    SideEffect { drag.update(owner, target, appId, page, widgetId, folderId, scope) }
    return onGloballyPositioned { coordinates ->
        drag.register(owner, DragRegion(target, coordinates.boundsInRoot(), appId, page, widgetId, folderId, scope))
    }
}

internal fun dragRegionPriority(region: DragRegion): Int = when (region.target) {
    is DropTarget.Folder -> 3
    is DropTarget.Widget -> 2
    is DropTarget.Library -> if (region.scope != null) 2 else 0
    else -> 1
}

/** Keeps the grabbed point over the pointer while resolving the widget's top-left cell. */
internal fun adjustedWidgetDropIndex(
    rawIndex: Int,
    placement: WidgetPlacement,
    sourceBounds: Rect,
    grabPoint: Offset,
): Int {
    val columnOffset = (((grabPoint.x - sourceBounds.left) / sourceBounds.width.coerceAtLeast(1f)) * placement.spanX)
        .toInt().coerceIn(0, placement.spanX - 1)
    val rowOffset = (((grabPoint.y - sourceBounds.top) / sourceBounds.height.coerceAtLeast(1f)) * placement.spanY)
        .toInt().coerceIn(0, placement.spanY - 1)
    val page = homeCellPage(rawIndex)
    val rawLocal = homeCellLocal(rawIndex)
    val column = (rawLocal % GRID_COLUMNS - columnOffset).coerceIn(0, GRID_COLUMNS - placement.spanX)
    val row = (rawLocal / GRID_COLUMNS - rowOffset)
        .coerceIn(0, GRID_ROWS - placement.spanY.coerceAtMost(GRID_ROWS))
    return homeCellIndex(page, row * GRID_COLUMNS + column)
}

/**
 * A native collection widget consumes its own MOVE events even while the finger has only
 * jittered. Compose's stock long-press helper treats that consumption as cancellation, so a
 * Calendar-style widget could scroll but could no longer be picked up. The root observes the
 * Initial pass and arbitrates intent from geometry instead: jitter keeps waiting, while a real
 * move, release/cancel, or second pointer yields the stream to the provider.
 */
private suspend fun AwaitPointerEventScope.awaitWidgetLongPressOrCancellation(
    down: PointerInputChange,
): PointerInputChange? {
    var current = down
    var canceled = false
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (!canceled) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            canceled = change == null || !change.pressed ||
                event.changes.any { it.id != down.id && it.pressed } ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            if (!canceled) current = change!!
        }
    }
    return current.takeUnless { canceled }
}

/**
 * The root owns the pointer so paging cannot dispose the active gesture.
 *
 * iPhone-style icon popover (2026-09-17 evening, IconGesture.kt): a long-press that hits a
 * [popoverEligible] region no longer lifts straight into a drag. It opens the popover ([onPopover])
 * and keeps watching the same pointer — only once it moves past touch slop *while the popover is
 * still open* does this convert into an ordinary drag (from that point on, identical to a region
 * [popoverEligible] never returned true for: [onStart] fires, [HomeDragState.source] is set, the
 * existing ghost/dim/haptics take over). Releasing before any such movement leaves the popover up;
 * [onFinish] is never called for that gesture; the popover is dismissed independently (tap
 * outside, Back, or picking an action) rather than by lifting the finger. [immediateDrag] is the
 * opposite carve-out, for Home edit ("jiggle") mode: a region it accepts skips the long-press
 * wait entirely, exactly like the widget long-press carve-out below already does for scrollable
 * widget content, so touching an icon while editing drags it immediately.
 */
@Composable
internal fun Modifier.homeDragInput(
    drag: HomeDragState, enabled: Boolean, page: Int, eligiblePages: Set<Int> = setOf(page),
    onStart: () -> Unit, onFinish: (Boolean) -> Unit,
    popoverEligible: (DragRegion) -> Boolean = { false },
    onPopover: (DragRegion) -> Unit = {},
    immediateDrag: (DragRegion) -> Boolean = { false },
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPage by rememberUpdatedState(page)
    val currentEligiblePages by rememberUpdatedState(eligiblePages)
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val eligibleForPopover by rememberUpdatedState(popoverEligible)
    val popover by rememberUpdatedState(onPopover)
    val eligibleForImmediateDrag by rememberUpdatedState(immediateDrag)
    return onGloballyPositioned { drag.rootBounds = it.boundsInRoot() }.pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEnabled) return@awaitEachGesture
            val point = down.position + drag.rootOrigin
            val region = drag.hit(point, currentEligiblePages)?.takeIf { it.movable } ?: return@awaitEachGesture
            val skipLongPress = eligibleForImmediateDrag(region)
            if (!skipLongPress) {
                if (region.target is DropTarget.Widget && (region.widgetId ?: EMPTY_WIDGET) >= 0) {
                    awaitWidgetLongPressOrCancellation(down)
                } else {
                    awaitLongPressOrCancellation(down.id)
                } ?: return@awaitEachGesture
            }
            val showPopover = !skipLongPress && eligibleForPopover(region)
            if (showPopover) {
                drag.iconPopover = region
                popover(region)
            } else {
                drag.source = region
                drag.pointer = point
                drag.origin = point
                drag.originPage = currentPage
                drag.moved = false
                start()
            }
            try {
                // Only meaningful while showPopover: true once the hold has converted into a
                // real drag (or immediately, for anything that never went through the popover).
                var converted = !showPopover
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.any { it.id != down.id && it.pressed }) {
                        event.changes.forEach { it.consume() }
                        if (converted) finish(true) else drag.iconPopover = null
                        break
                    }
                    change.consume()
                    val currentPoint = change.position + drag.rootOrigin
                    if (!converted) {
                        if ((currentPoint - point).getDistance() > viewConfiguration.touchSlop) {
                            // The popover is still up and the finger just moved past slop:
                            // dismiss it and start a real drag from here, exactly as if this
                            // region had never been popover-eligible.
                            converted = true
                            drag.iconPopover = null
                            drag.source = region
                            drag.pointer = currentPoint
                            drag.origin = point
                            drag.originPage = currentPage
                            drag.moved = true
                            start()
                        } else if (!change.pressed) {
                            // Released while still just holding: the popover stays open.
                            break
                        } else {
                            continue
                        }
                    }
                    drag.pointer = currentPoint
                    if ((drag.pointer - drag.origin).getDistance() > viewConfiguration.touchSlop) drag.moved = true
                    if (!change.pressed) {
                        finish(false)
                        break
                    }
                    if (!drag.active) break
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                drag.clear()
                drag.iconPopover = null
                throw cancel
            }
        }
    }
}
