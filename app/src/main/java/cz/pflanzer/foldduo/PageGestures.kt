package cz.pflanzer.foldduo

import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

/** One adjacent page per ordinary pointer gesture, including its release fling. */
internal class PageGestureLimits(private val pager: PagerState) : NestedScrollConnection, PagerSnapDistance {
    var anchor: Int? = null
    var pointerDown = false
    var editing = false
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val start = anchor ?: return Offset.Zero
        if (!pointerDown || editing || source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
        val stride = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
        if (stride <= 0) return Offset.Zero
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val allowed = boundedPagePosition(position - available.x / stride, start, pager.pageCount)
        return Offset(available.x + (allowed - position) * stride, 0f)
    }
    override fun calculateTargetPage(startPage: Int, suggestedTargetPage: Int, velocity: Float,
        pageSize: Int, pageSpacing: Int): Int =
        if (editing) suggestedTargetPage else boundedPagePosition(suggestedTargetPage.toFloat(), anchor ?: startPage, pager.pageCount).toInt()
}

internal fun boundedPagePosition(position: Float, anchor: Int, count: Int): Float =
    position.coerceIn((anchor - 1).coerceAtLeast(0).toFloat(), (anchor + 1).coerceAtMost(count - 1).coerceAtLeast(0).toFloat())

internal fun releasePage(position: Float, anchor: Int, count: Int, velocity: Float, threshold: Float,
    distanceThreshold: Float = .2f): Int {
    val displacement = position - anchor
    val candidate = when {
        velocity < -threshold -> floor(position + .0001f).toInt() + 1
        velocity > threshold -> ceil(position - .0001f).toInt() - 1
        abs(displacement) >= distanceThreshold -> anchor + sign(displacement).toInt()
        else -> anchor
    }
    return boundedPagePosition(candidate.toFloat(), anchor, count).toInt()
}

/** Content-area rule (no rail): the left 70 % opens Notifications, the right 30 % Quick Settings. */
internal fun shadePanelForStart(startX: Float, width: Float): ShadePanel =
    if (startX < width * .7f) ShadePanel.NOTIFICATIONS else ShadePanel.QUICK_SETTINGS

/**
 * Which shade a downward swipe opens (PLAN.md, rail item 5). Over the rail column (the right
 * [railWidth] of the gesture box) the rail's own thirds decide: the top third opens Quick
 * Settings, the lower two thirds Notifications. A swipe starting anywhere else keeps the 70/30
 * horizontal rule of [shadePanelForStart]. A rail of zero width never captures.
 */
internal fun shadePanelFor(startX: Float, startY: Float, width: Float, height: Float, railWidth: Float): ShadePanel {
    val overRail = railWidth > 0f && startX >= width - railWidth
    return when {
        !overRail -> shadePanelForStart(startX, width)
        startY < height / 3f -> ShadePanel.QUICK_SETTINGS
        else -> ShadePanel.NOTIFICATIONS
    }
}

/** Choose an axis before children see the slop-crossing event. A vertical list must not
 * steal a mostly-horizontal swipe simply because one fast sample crossed both thresholds.
 * PagerState still owns scrolling, layout, cancellation, and the settling animation. */
@Composable
internal fun Modifier.onePageGestures(
    pager: PagerState,
    limits: PageGestureLimits,
    motion: WorkspacePageMotion? = null,
    enabled: Boolean = true,
    canStartDownwardSwipe: (Offset) -> Boolean = { true },
    onDownwardSwipe: ((ShadePanel) -> Unit)? = null,
    /**
     * B28 follow-up "Spotlight vs shade": the other half of the same downward drag on empty Home
     * space, arbitrated by [downwardHomeGestureLane]/[downwardHomeGestureTarget] purely from
     * where the drag started ([canStartDownwardSwipe] gates *whether* either can start at all —
     * same eligibility for both, e.g. not over a scrolled dock). `null` disables Spotlight's lane
     * entirely; the drag then behaves exactly as it did before this arbitration existed.
     */
    onSpotlightPullDown: (() -> Unit)? = null,
    /**
     * B42 "Dosah na coveru": the third lane of the same downward drag, [REACHABILITY_BOTTOM_FRACTION]
     * of the page measured up from the bottom. `null` (every caller except the cover pager)
     * disables it entirely, same convention as [onSpotlightPullDown]; [reachabilityBottomFraction]
     * must also be > 0f (LauncherScreen.kt passes it only for the cover, 0f for the expanded
     * workspace) since both are needed for [downwardHomeGestureLane] to ever pick this lane.
     */
    onReachabilityPullDown: (() -> Unit)? = null,
    reachabilityBottomFraction: Float = 0f,
    onLeadingOverscroll: (() -> Unit)? = null,
    // An upward swipe over Home opens the App Library (Android muscle memory). Separate
    // from the downward branch: same axis decision, its own gate, no shade involvement.
    canStartUpwardSwipe: (Offset) -> Boolean = { false },
    onUpwardSwipe: (() -> Unit)? = null,
    /** Width in px of the rail column at the right edge; its vertical thirds pick the shade ([shadePanelFor]). */
    railWidthPx: Float = 0f,
    /**
     * B24 follow-up "Rubber-band on the inner workspace": dragging past the true first/last page
     * (not the one-page-per-gesture [boundedPagePosition] limit, which pages normally) resists
     * instead of hard-clamping, mirroring the cover pager's `PagerRubberBandOverscroll`. `null`
     * (the default, and every caller before the expanded workspace) keeps the old hard clamp.
     */
    edgeOverscroll: WorkspaceEdgeOverscroll? = null,
    overscrollWidthPx: () -> Float = { 0f },
) : Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRailWidth by rememberUpdatedState(railWidthPx)
    val currentCanStartDownwardSwipe by rememberUpdatedState(canStartDownwardSwipe)
    val currentDownwardSwipe by rememberUpdatedState(onDownwardSwipe)
    val currentSpotlightPullDown by rememberUpdatedState(onSpotlightPullDown)
    val currentReachabilityPullDown by rememberUpdatedState(onReachabilityPullDown)
    val currentReachabilityBottomFraction by rememberUpdatedState(reachabilityBottomFraction)
    val currentCanStartUpwardSwipe by rememberUpdatedState(canStartUpwardSwipe)
    val currentUpwardSwipe by rememberUpdatedState(onUpwardSwipe)
    val currentLeadingOverscroll by rememberUpdatedState(onLeadingOverscroll)
    val currentOverscrollWidthPx by rememberUpdatedState(overscrollWidthPx)
    return nestedScroll(limits).pointerInput(pager, limits, motion) {
        coroutineScope {
            var motionJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val gestureEnabled = currentEnabled
                val anchor = pager.currentPage
                val trace = DuoMotionTrace.begin(anchor,
                    pager.currentPage + pager.currentPageOffsetFraction, down.position.x, down.position.y)
                if (!gestureEnabled) {
                    trace?.cancel("disabled", pager.currentPage + pager.currentPageOffsetFraction)
                    return@awaitEachGesture
                }
                limits.anchor = anchor
                limits.pointerDown = true
                val tracker = VelocityTracker().apply { addPointerInputChange(down) }
                var dragPositions: Channel<Float>? = null
                var dragSlopOffset = 0f
                var releaseVelocity = 0f
                var leadingDrag = 0f
                var canceled = false
                var cancelReason: String? = null
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || limits.editing || event.changes.any { it.id != down.id && it.pressed }) {
                            canceled = true
                            cancelReason = when {
                                change == null -> "pointer_missing"
                                change.isConsumed -> "consumed"
                                limits.editing -> "editing"
                                else -> "multiple_pointers"
                            }
                            break
                        }
                        // Match Compose's drag tracking, including Android's batched historical
                        // samples and its handling of the final finger-up event.
                        tracker.addPointerInputChange(change)
                        leadingDrag = maxOf(leadingDrag, change.position.x - down.position.x)
                        val terminalRelease = event.type == PointerEventType.Release && change.changedToUp()
                        if (dragPositions == null && (change.pressed || terminalRelease)) {
                            val distance = change.position - down.position
                            if (maxOf(abs(distance.x), abs(distance.y)) > viewConfiguration.touchSlop) {
                                trace?.slop(distance.x, distance.y,
                                    pager.currentPage + pager.currentPageOffsetFraction)
                                // Spotlight's or Reachability's lane, downward, still short of its
                                // much larger threshold (set inside the block below): unlike every
                                // other outcome this one does not resolve the gesture yet, so it
                                // must skip the horizontal/paging fallback too, not just the break.
                                var pendingLane = false
                                if (abs(distance.y) >= abs(distance.x)) {
                                    // A terminal release can preserve meaningful travel after a
                                    // blocked frame dropped every MOVE. It can page horizontally,
                                    // but must not trigger a vertical system action on finger-up.
                                    // B28 follow-up "Spotlight vs shade" (+ B42 "Dosah na coveru"):
                                    // all three live on this same downward drag, so
                                    // canStartDownwardSwipe (whatever native widget/dock-scroll
                                    // checks it does) gates every lane equally; downwardHomeGestureLane
                                    // (SpotlightModel.kt), decided once from the drag's start point,
                                    // picks which one — never more than one — this particular
                                    // gesture is even allowed to open.
                                    val startYFraction = (down.position.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    val overRail = currentRailWidth > 0f && down.position.x >= size.width - currentRailWidth
                                    val lane = downwardHomeGestureLane(startYFraction, overRail, bottomFraction = currentReachabilityBottomFraction)
                                    val eligible = change.pressed && distance.y > 0f && currentCanStartDownwardSwipe(down.position) &&
                                        when (lane) {
                                            DownwardHomeGestureLane.SHADE -> currentDownwardSwipe != null
                                            DownwardHomeGestureLane.REACHABILITY -> currentReachabilityPullDown != null
                                            DownwardHomeGestureLane.SPOTLIGHT -> currentSpotlightPullDown != null
                                        }
                                    val target = if (eligible) downwardHomeGestureTarget(startYFraction, overRail, distance.x / density, distance.y / density,
                                        bottomFraction = currentReachabilityBottomFraction)
                                        else DownwardHomeGestureTarget.NONE
                                    val openDownward = target == DownwardHomeGestureTarget.SHADE
                                    val openSpotlight = target == DownwardHomeGestureTarget.SPOTLIGHT
                                    val openReachability = target == DownwardHomeGestureTarget.REACHABILITY
                                    val openUpward = !openDownward && !openSpotlight && !openReachability && change.pressed && distance.y < 0f &&
                                        currentUpwardSwipe != null && currentCanStartUpwardSwipe(down.position)
                                    // No decision yet in Spotlight's or Reachability's lane: keep
                                    // going around the loop (unconsumed) instead of falling through
                                    // to "vertical_axis", since more travel is exactly what would
                                    // resolve it — see the flag declared above this whole if.
                                    pendingLane = eligible && target == DownwardHomeGestureTarget.NONE &&
                                        (lane == DownwardHomeGestureLane.SPOTLIGHT || lane == DownwardHomeGestureLane.REACHABILITY)
                                    if (!pendingLane) {
                                        cancelReason = when {
                                            openDownward -> "downward_action"
                                            openSpotlight -> "spotlight_action"
                                            openReachability -> "reachability_action"
                                            openUpward -> "upward_action"
                                            else -> "vertical_axis"
                                        }
                                        if (openDownward) {
                                            change.consume()
                                            currentDownwardSwipe?.invoke(shadePanelFor(down.position.x, down.position.y,
                                                size.width.toFloat(), size.height.toFloat(), currentRailWidth))
                                        } else if (openSpotlight) {
                                            change.consume()
                                            currentSpotlightPullDown?.invoke()
                                        } else if (openReachability) {
                                            change.consume()
                                            currentReachabilityPullDown?.invoke()
                                        } else if (openUpward) {
                                            change.consume()
                                            currentUpwardSwipe?.invoke()
                                        }
                                        break
                                    }
                                }
                                if (!pendingLane) {
                                motionJob?.cancel()
                                // Pointer input can outpace layout on a busy frame. Keep the
                                // latest absolute drag position rather than queueing and replaying
                                // every stale delta after the finger has already moved on.
                                val channel = Channel<Float>(Channel.CONFLATED)
                                dragPositions = channel
                                dragSlopOffset = sign(distance.x) * viewConfiguration.touchSlop
                                motionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                    fun stride() = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).toFloat().coerceAtLeast(1f)
                                    fun position() = pager.currentPage + pager.currentPageOffsetFraction
                                    fun visualOffset() = motion?.offset(position()) ?: position() * stride()
                                    try {
                                        pager.scroll(MutatePriority.UserInput) {
                                            with(pager) { updateTargetPage((anchor - sign(distance.x).toInt()).coerceIn(0, pager.pageCount - 1)) }
                                            fun moveBy(pixels: Float) {
                                                val current = position()
                                                val requested = motion?.positionAfterVisualDelta(current, pixels)
                                                    ?: (current + pixels / stride())
                                                val allowed = boundedPagePosition(requested, anchor, pager.pageCount)
                                                scrollBy((allowed - current) * stride())
                                            }
                                            // B24 follow-up "Rubber-band on the inner workspace": how far past
                                            // the true first/last page (not the one-page anchor bound, which
                                            // pages normally) the requested position would sit if [pixels] were
                                            // applied from the position moveBy would see right now — computed
                                            // before that move, since boundedPagePosition would otherwise have
                                            // already clamped it away to exactly 0.
                                            fun overshootPx(pixels: Float): Float {
                                                val current = position()
                                                val requested = motion?.positionAfterVisualDelta(current, pixels)
                                                    ?: (current + pixels / stride())
                                                return edgeOvershootPositions(requested, anchor, pager.pageCount) * stride()
                                            }
                                            val dragStartVisualOffset = visualOffset()
                                            for (dragPosition in channel) {
                                                if (dragPosition != 0f) with(pager) { updateTargetPage((anchor - sign(dragPosition).toInt()).coerceIn(0, pager.pageCount - 1)) }
                                                val deltaPixels = dragStartVisualOffset - dragPosition - visualOffset()
                                                if (edgeOverscroll != null) edgeOverscroll.update(overshootPx(deltaPixels), currentOverscrollWidthPx())
                                                moveBy(deltaPixels)
                                            }
                                            // The finger has lifted (or the gesture was cancelled): let the
                                            // rubber band spring back to 0 concurrently with the page settle
                                            // below, rather than blocking it or fighting the settle's own
                                            // moveBy calls (which no longer track overshoot once the drag ends).
                                            if (edgeOverscroll != null) launch { edgeOverscroll.release() }
                                            val velocity = if (canceled) 0f else releaseVelocity
                                            // A relaxed thumb swipe should commit even if the finger
                                            // slows before lifting. Cap the distance on the unfolded
                                            // display so it never requires a half-screen hand stretch.
                                            val releaseDirection = when {
                                                position() > anchor -> 1
                                                position() < anchor -> -1
                                                velocity < 0f -> 1
                                                velocity > 0f -> -1
                                                else -> 0
                                            }
                                            val adjacent = (anchor + releaseDirection).coerceIn(0, pager.pageCount - 1)
                                            val releaseStride = motion?.stride(anchor, adjacent)
                                                ?.takeIf { it > 0f } ?: stride()
                                            val target = if (canceled) position().roundToInt() else releasePage(
                                                position(), anchor, pager.pageCount, velocity, 250f * density,
                                                distanceThreshold = minOf(.2f, 72f * density / releaseStride)
                                            )
                                            trace?.release(position(), velocity, target, canceled)
                                            with(pager) { updateTargetPage(target) }
                                            val startVisualOffset = visualOffset()
                                            val targetVisualOffset = motion?.offset(target.toFloat()) ?: target * stride()
                                            val distanceToPage = targetVisualOffset - startVisualOffset
                                            animate(0f, distanceToPage, initialVelocity = (-velocity).coerceIn(-stride() * 5f, stride() * 5f)) { value, _ ->
                                                // Springs can overshoot the one-page bound. Base every
                                                // frame on the pager's actual position so a rejected delta
                                                // cannot become a persistent settling offset.
                                                moveBy(startVisualOffset + value - visualOffset())
                                            }
                                            // The final callback normally lands exactly here; resolve any
                                            // subpixel pager rounding before this scroll mutation completes.
                                            moveBy(targetVisualOffset - visualOffset())
                                        }
                                        trace?.motionCompleted(position(), visualOffset())
                                    } catch (failure: CancellationException) {
                                        trace?.motionCanceled(position(), visualOffset())
                                        throw failure
                                    }
                                    if (!canceled && anchor == 0 && leadingDrag >= 72f * density) {
                                        currentLeadingOverscroll?.invoke()
                                    }
                                }
                                channel.trySend(distance.x - dragSlopOffset)
                                }
                            }
                        } else if (dragPositions != null) {
                            dragPositions?.trySend(change.position.x - down.position.x - dragSlopOffset)
                        }
                        if (dragPositions != null) change.consume()
                        if (!change.pressed) {
                            if (trace != null) trace.terminal(event.type.toString(),
                                change.position.x - down.position.x, change.position.y - down.position.y,
                                change.uptimeMillis, android.os.SystemClock.uptimeMillis(),
                                change.historical.size, change.isConsumed,
                                pager.currentPage + pager.currentPageOffsetFraction)
                            releaseVelocity = tracker.calculateVelocity().x
                            break
                        }
                    }
                } finally {
                    limits.pointerDown = false
                    dragPositions?.close()
                    val finalCancelReason = cancelReason
                    if (finalCancelReason != null) {
                        trace?.cancel(finalCancelReason, pager.currentPage + pager.currentPageOffsetFraction)
                    } else if (dragPositions == null) {
                        trace?.release(pager.currentPage + pager.currentPageOffsetFraction,
                            releaseVelocity, anchor, canceled = false)
                    }
                }
            }
        }
    }
}
