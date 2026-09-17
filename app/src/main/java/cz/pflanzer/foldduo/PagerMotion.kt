package cz.pflanzer.foldduo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * IDEAS B24 "Pružiny všude": iOS-style rubber-band resistance for dragging past the ends of the
 * Home/App Library pager. [rawDeltaPx] is how far past the end the finger has dragged; the result
 * converges on [limitPx] as [rawDeltaPx] grows without bound, instead of tracking the finger 1:1
 * (Android's hard clamp) or letting it run away.
 */
internal fun rubberBandOverscroll(rawDeltaPx: Float, limitPx: Float, factor: Float = 0.35f): Float {
    if (limitPx <= 0f) return rawDeltaPx * factor
    val magnitude = abs(rawDeltaPx)
    val resisted = (magnitude * factor * limitPx) / (limitPx + factor * magnitude)
    return resisted * sign(rawDeltaPx)
}

/**
 * B-spread follow-up "end of the strip": the signed page-unit distance [requestedPosition] sits
 * past the pager's own two true ends — negative past anchor 0, or past the last page
 * ([pageCount] - 1 past an anchor already sitting there — 0 everywhere else, including a mid-pager
 * anchor's own one-page bound ([boundedPagePosition] already clamps that one, it pages normally).
 * Extracted from PageGestures.kt's `onePageGestures` (its `overshootPx` local function computes
 * the same `when` on the requested position before multiplying by a stride) purely so it has a
 * name and a unit test on its own — both the inner workspace's [WorkspaceEdgeOverscroll] and the
 * cover's own overscroll only ever see true ends this way, which is exactly what makes rubber-band
 * resistance ([rubberBandOverscroll]) apply only at the real ends of a page strip, spread's
 * Today-included one included, never at an ordinary one-page anchor bound mid-strip.
 */
internal fun edgeOvershootPositions(requestedPosition: Float, anchor: Int, pageCount: Int): Float {
    val lastPage = (pageCount - 1).toFloat()
    return when {
        anchor == 0 && requestedPosition < 0f -> requestedPosition
        anchor == pageCount - 1 && requestedPosition > lastPage -> requestedPosition - lastPage
        else -> 0f
    }
}

/** The spring the rubber band returns to zero with once the finger lifts. */
private val OverscrollReturnSpring = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)

/**
 * Applies [rubberBandOverscroll] to whatever horizontal scroll the pager itself could not consume
 * (i.e. dragging past page 0 or past the last page — the App Library) and springs back to zero on
 * release. [widthPx] is the resistance limit, evaluated live so it tracks layout/orientation.
 */
internal class PagerRubberBandOverscroll(
    private val scope: CoroutineScope,
    private val widthPx: () -> Float,
) : OverscrollEffect {
    private val overscrollPx = Animatable(0f)
    val offsetPx: Float get() = overscrollPx.value
    override val isInProgress: Boolean get() = overscrollPx.value != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        val consumed = performScroll(delta)
        val remaining = delta.x - consumed.x
        if (remaining != 0f) {
            val limit = widthPx().coerceAtLeast(1f)
            val next = rubberBandOverscroll(overscrollPx.value + remaining, limit)
            scope.launch { overscrollPx.snapTo(next) }
            return Offset(remaining, delta.y - consumed.y)
        }
        return consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        performFling(velocity)
        if (overscrollPx.value != 0f) overscrollPx.animateTo(0f, OverscrollReturnSpring)
    }

    override val effectModifier: Modifier = Modifier.graphicsLayer { translationX = overscrollPx.value }
}

/**
 * B24 follow-up "Rubber-band on the inner workspace": the expanded/inner workspace's own custom
 * drag (PageGestures.kt's `onePageGestures`, used because it drives a [WorkspacePageMotion]
 * instead of a plain stride) has no `OverscrollEffect` pipeline to plug into like the cover
 * pager's [PagerRubberBandOverscroll] — it resolves scroll itself, frame by frame, against an
 * absolute target position. This mirrors that class's feel (same [rubberBandOverscroll] factor,
 * same [OverscrollReturnSpring] release) as a small, self-contained holder instead: the caller
 * feeds it the *raw* pixel amount the drag wants to move past the true first/last page edge each
 * frame (already absolute, since the caller always knows the requested position for this
 * gesture — no incremental accumulation needed), and reads [offsetPx] back as a purely visual
 * `graphicsLayer` translation over the whole workspace, never the real scroll position.
 */
internal class WorkspaceEdgeOverscroll {
    private val overscrollPx = Animatable(0f)
    val offsetPx: Float get() = overscrollPx.value

    /** [rawOvershootPx] is the signed distance requested past the edge this frame; 0 snaps back immediately. */
    suspend fun update(rawOvershootPx: Float, widthPx: Float) {
        overscrollPx.snapTo(if (rawOvershootPx == 0f) 0f else rubberBandOverscroll(rawOvershootPx, widthPx.coerceAtLeast(1f)))
    }

    /** Springs back to 0 once the finger lifts (or the gesture is cancelled). */
    suspend fun release() {
        if (overscrollPx.value != 0f) overscrollPx.animateTo(0f, OverscrollReturnSpring)
    }
}

/**
 * How far the App Library's leading row has "peeked" in from the right (B24 item 1: "past the
 * last home page, the App Library peeks"). Purely a function of the pager's own settle progress
 * toward the library page — no separate overscroll gesture needed, since the library is a real,
 * adjacent page. `0` anywhere else, `1` once the library page is fully current.
 */
internal fun libraryPeekProgress(currentPage: Int, currentPageOffsetFraction: Float, libraryPage: Int): Float = when {
    currentPage == libraryPage - 1 -> currentPageOffsetFraction.coerceIn(0f, 1f)
    currentPage == libraryPage -> (1f + currentPageOffsetFraction.coerceIn(-1f, 0f))
    else -> 0f
}
