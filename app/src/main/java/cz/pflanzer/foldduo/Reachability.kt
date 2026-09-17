package cz.pflanzer.foldduo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * B42 "Dosah na coveru": iOS-style Reachability for the cover pager. A downward drag starting in
 * the bottom [REACHABILITY_BOTTOM_FRACTION] of the cover screen (SpotlightModel.kt's
 * `downwardHomeGestureLane`/`Target`, wired into PageGestures.kt's shared recognizer) pulls the
 * whole Home content down by [REACHABILITY_PULL_FRACTION] of the screen height with a spring;
 * a tap while pulled down, or [REACHABILITY_IDLE_MS] of no interaction, springs it back. The
 * rail (dock/status/search/island — all drawn as separate siblings in LauncherScreen.kt, never
 * inside the pager content this offset applies to) never moves, matching the task's "rail stays
 * put". Reduce motion snaps instead of springing, same convention as [MotionPrefs].
 */

/** How far down the cover pulls, as a fraction of the screen height. */
internal const val REACHABILITY_PULL_FRACTION = 0.40f

/** Idle time (no tap, no new pull) before Reachability springs back on its own. */
internal const val REACHABILITY_IDLE_MS = 5_000L

internal class ReachabilityController(private val scope: CoroutineScope) {
    /** Live vertical offset in px, applied to the cover pager content's own `graphicsLayer`. */
    val offset = Animatable(0f)

    /** True from the moment a pull commits until it has fully sprung back — drives the tap-to-dismiss catcher. */
    var engaged by mutableStateOf(false)
        private set

    private var idleJob: Job? = null
    private var animateJob: Job? = null

    /** Commits the pull: springs (or snaps, reduced motion) to [targetPx] and arms the idle timer. */
    fun engage(targetPx: Float, reduceMotion: Boolean) {
        engaged = true
        idleJob?.cancel()
        idleJob = scope.launch { delay(REACHABILITY_IDLE_MS); dismiss(reduceMotion) }
        animateJob?.cancel()
        animateJob = scope.launch {
            if (reduceMotion) offset.snapTo(targetPx)
            else offset.animateTo(targetPx, spring(dampingRatio = .82f, stiffness = 380f))
        }
    }

    /** A tap, a mode switch to the expanded workspace, or the idle timer springs back to 0. */
    fun dismiss(reduceMotion: Boolean) {
        if (!engaged && offset.value == 0f) return
        engaged = false
        idleJob?.cancel(); idleJob = null
        animateJob?.cancel()
        animateJob = scope.launch {
            if (reduceMotion) offset.snapTo(0f)
            else offset.animateTo(0f, spring(dampingRatio = .82f, stiffness = 380f))
        }
    }
}

@Composable
internal fun rememberReachabilityController(): ReachabilityController {
    val scope = rememberCoroutineScope()
    return remember(scope) { ReachabilityController(scope) }
}

/**
 * Any tap while [controller] is engaged springs the content back — the "until a tap" half of
 * B42's Reachability. Deliberately does not consume the change: an icon under the pulled-down
 * content still launches normally, exactly like iOS Reachability's own tap-through.
 */
internal fun Modifier.reachabilityDismissOnTap(controller: ReachabilityController, reduceMotion: Boolean): Modifier {
    return this.then(Modifier.pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (maxOf(abs(delta.x), abs(delta.y)) > viewConfiguration.touchSlop) moved = true
                if (!change.pressed) {
                    if (!moved && !change.isConsumed && controller.engaged) controller.dismiss(reduceMotion)
                    break
                }
            }
        }
    })
}
