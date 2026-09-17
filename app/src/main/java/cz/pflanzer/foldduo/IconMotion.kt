package cz.pflanzer.foldduo

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * IDEAS B23 "Otevírání aplikací z ikony": every icon surface (Home grid, dock, App Library,
 * folder tile) presses the same way — scale to [ICON_PRESS_SCALE] and spring back with the same
 * iOS-like damping — instead of each surface hand-rolling its own tween. [Modifier.iconPressScale]
 * is that one shared modifier; apply it next to the surface's own `clickable`/`combinedClickable`
 * so both read the same [InteractionSource].
 */
internal const val ICON_PRESS_SCALE = 0.9f

/** `spring(dampingRatio 0.6, stiffness High)`, the shared press-feedback spec (B23 item 1). */
internal val IconPressSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh)

/** IDEAS B24 "Pružiny všude": the picked-up drag ghost grows to this scale with [DragPickupSpring]. */
internal const val DRAG_PICKUP_SCALE = 1.15f
internal val DragPickupSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)

/** The settle-into-cell spring for neighbour reflow and the dropped icon (~4 % overshoot). */
internal val IconSettleSpring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)

@Composable
internal fun Modifier.iconPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = ICON_PRESS_SCALE,
    reduceMotion: Boolean = false,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        animationSpec = if (reduceMotion) snap() else IconPressSpring,
        label = "icon-press-scale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
