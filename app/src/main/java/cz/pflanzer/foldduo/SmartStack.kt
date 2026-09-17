package cz.pflanzer.foldduo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * B26 "Smart stack na Today": the flip between stacked members, the fading page-dot column, and
 * the "Edit stack" sheet. WidgetStack.kt has the pure editing rules this calls into (via the
 * callbacks below, wired to LauncherModel in LauncherScreen.kt) and StackRotation.kt the
 * time-of-day auto-rotation; nothing here decides *which* member should show, only how the
 * transition looks and how a person edits the membership by hand.
 */

private const val STACK_DOT_FADE_DELAY_MS = 1500L
private const val STACK_SWIPE_THRESHOLD_DP = 40f
private const val STACK_ROW_HEIGHT_DP = 56f

/**
 * Wraps a stacked widget's current member ([content]) with the vertical-swipe flip and the
 * fading page dots. [activeId] is the member the placement says to show right now (Smart Stack's
 * rotation or a manual pick already resolved it); this container only owns the *transition* to
 * it — a 3D card flip (rotationX about the horizontal centre, spring dampingRatio 0.8) with the
 * content swapped at the quarter-turn where the card is edge-on and invisible either way.
 */
@Composable
internal fun StackFlipContainer(
    activeId: Int,
    memberIds: List<Int>,
    onSwipe: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    var displayedId by remember(memberIds) { mutableStateOf(activeId) }
    val rotation = remember { Animatable(0f) }
    var dotsVisible by remember { mutableStateOf(false) }
    val flipSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    val context = LocalContext.current
    LaunchedEffect(activeId) {
        if (activeId != displayedId) {
            Haptics.play(context, HapticEvent.STACK_FLIP)
            dotsVisible = true
            rotation.snapTo(0f)
            rotation.animateTo(90f, flipSpring)
            displayedId = activeId
            rotation.snapTo(-90f)
            rotation.animateTo(0f, flipSpring)
        }
    }
    LaunchedEffect(dotsVisible) {
        if (dotsVisible) { delay(STACK_DOT_FADE_DELAY_MS); dotsVisible = false }
    }
    val density = LocalDensity.current
    Box(modifier.pointerInputVerticalSwipe(memberIds.size >= 2, onDragStart = { dotsVisible = true }) { forward -> onSwipe(forward) }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            rotationX = rotation.value
            cameraDistance = 32f * density.density
        }) { content(displayedId) }
        if (memberIds.size >= 2) StackPageDots(memberIds.size, memberIds.indexOf(displayedId).coerceAtLeast(0),
            visible = dotsVisible, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
    }
}

/** Vertical drag past [STACK_SWIPE_THRESHOLD_DP] calls back with the swipe direction; smaller moves are ignored. */
@Composable
private fun Modifier.pointerInputVerticalSwipe(enabled: Boolean, onDragStart: () -> Unit, onSwipe: (Boolean) -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f; onDragStart() },
            onVerticalDrag = { change, amount -> total += amount; change.consume() },
            onDragEnd = {
                val thresholdPx = STACK_SWIPE_THRESHOLD_DP * density
                if (total <= -thresholdPx) onSwipe(true) else if (total >= thresholdPx) onSwipe(false)
            },
        )
    }
}

/** A drag handle's own vertical drag, reported as raw per-move pixel deltas (the caller does the reorder math). */
@Composable
private fun Modifier.pointerInputVerticalDragBy(onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { onDragStart() },
            onVerticalDrag = { change, amount -> onDrag(amount); change.consume() },
            onDragEnd = onDragEnd,
        )
    }

@Composable
private fun StackPageDots(count: Int, activeIndex: Int, visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "stack-dots-alpha")
    if (alpha <= 0f) return
    Column(modifier.alpha(alpha).testTag("stack-dots"), verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(count) { index ->
            Box(Modifier.size(if (index == activeIndex) 6.dp else 4.dp).background(
                Color.White.copy(alpha = if (index == activeIndex) .95f else .5f), CircleShape))
        }
    }
}

/**
 * "Edit stack": Smart rotate toggle, then every member with a drag handle (reorder, by index —
 * dragging past a neighbour's row swaps it in immediately) and a remove button. [onRemoveMember]
 * dissolves the stack itself once only one member is left (WidgetStack.kt's removeStackMember);
 * the caller then closes this sheet back to the ordinary widget options.
 */
@Composable
internal fun StackEditSheet(
    placement: WidgetPlacement,
    labelFor: (Int) -> String,
    onReorder: (from: Int, to: Int) -> Unit,
    onRemoveMember: (Int) -> Unit,
    onToggleSmartRotate: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val members = placement.stackMembers
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Edit stack", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose, modifier = Modifier.testTag("stack-edit-close")) { Icon(Icons.Rounded.Close, "Close") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Smart rotate", Modifier.weight(1f))
            Switch(checked = placement.smartRotate, onCheckedChange = onToggleSmartRotate,
                modifier = Modifier.testTag("stack-smart-rotate"))
        }
        HorizontalDivider()
        val density = LocalDensity.current
        val rowHeightPx = with(density) { STACK_ROW_HEIGHT_DP.dp.toPx() }
        var dragIndex by remember { mutableIntStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        members.forEachIndexed { index, id ->
            key(id) {
                val elevationOffset = if (dragIndex == index) dragOffset else 0f
                Row(Modifier.fillMaxWidth().heightIn(min = STACK_ROW_HEIGHT_DP.dp)
                    .graphicsLayer { translationY = elevationOffset }
                    .testTag("stack-member-$id"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DragHandle, "Reorder ${labelFor(id)}", modifier = Modifier
                        .testTag("stack-reorder-$id")
                        .pointerInputVerticalDragBy(
                            onDragStart = { dragIndex = index; dragOffset = 0f },
                            onDrag = { amount ->
                                dragOffset += amount
                                val steps = (dragOffset / rowHeightPx).roundToInt()
                                if (steps != 0) {
                                    val target = (index + steps).coerceIn(0, members.lastIndex)
                                    if (target != index) { onReorder(index, target); dragOffset -= steps * rowHeightPx }
                                }
                            },
                            onDragEnd = { dragIndex = -1; dragOffset = 0f },
                        ))
                    Text(labelFor(id), Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { onRemoveMember(id) }, modifier = Modifier.testTag("stack-remove-$id")) {
                        Icon(Icons.Rounded.Close, "Remove ${labelFor(id)} from stack")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
