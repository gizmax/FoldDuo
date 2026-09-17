package cz.pflanzer.foldduo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/*
 * Home edit ("jiggle") mode UI (2026-09-17 evening): wiggle, the "-" remove badge, and the top
 * bar ("+" widget/wallpaper/customize menu, "Done" pill). HomeEditMode.kt has the pure state and
 * wiggle maths this reads; LauncherScreen.kt wires `editModeActive` into HomeDragState (`editMode`
 * / `editModeClockMs` / `onEditRemove`, mirrored the same way B25 mirrored `openFolderId`) so
 * every call site here only needs the [HomeDragState] it already carries.
 */

/** Drives [HomeDragState.editModeClockMs] once, at the root, while edit mode is active. Call this
 * exactly once (LauncherScreen.kt's root composable), not per item. */
@Composable
internal fun EditModeWiggleClock(drag: HomeDragState, active: Boolean) {
    LaunchedEffect(active) {
        if (!active) { drag.editModeClockMs = 0L; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (isActive) withFrameMillis { drag.editModeClockMs = it - start }
    }
}

/** The wiggle itself: a small `rotationZ`, per-item phase via [wigglePhaseOffsetMs]; a no-op
 * outside edit mode or under reduce motion. [index] only needs to be stable per item, not
 * globally unique — a home cell index, a dock slot, or a widget slot all work. */
@Composable
internal fun Modifier.homeEditWiggle(drag: HomeDragState, index: Int): Modifier {
    val reduceMotion = MotionPrefs.enabled.value
    if (!drag.editMode || reduceMotion) return this
    val rotation = wiggleRotationDeg(drag.editModeClockMs, index)
    return this.graphicsLayer { rotationZ = rotation }
}

/** "-" remove badge (iOS style, 2026-09-17 night): a 22 dp grey circle with a white minus,
 * top-left of whatever it is placed over (the caller aligns it). */
@Composable
internal fun EditRemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onRemove,
        modifier = modifier.size(22.dp).testTag("edit-remove-badge"),
        shape = CircleShape,
        color = IosGray,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Remove, "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Top bar shown only in edit mode (iOS style, 2026-09-17 night): a 36 dp circular glass "+"
 * button (top-left, opens [onAddMenu]) and a glass "Done" pill (top-right, [onDone], 17 sp
 * semibold). Positioned above the safe-area inset like the rest of the rail
 * (`statusBarsPadding()`), same convention as [cz.pflanzer.foldduo.island.RailIsland].
 */
@Composable
internal fun EditModeTopBar(onAddMenu: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalDuoPalette.current.dark
    val veil = if (dark) Color.Black else Color.White
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape)
            // "Matné sklo pro všechny pilulky" (17. 9. noc): shared primitive, opacity-reactive.
            .glassPill(corner = 18.dp, tint = veil, baseVeilAlpha = .78f)
            .clickable(onClick = onAddMenu).testTag("edit-mode-add"),
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Add to Home", tint = Ink) }
        Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .glassPill(corner = 18.dp, tint = veil, baseVeilAlpha = .78f)
            .clickable(onClick = onDone).testTag("edit-mode-done"),
            contentAlignment = Alignment.Center) {
            Text("Done", color = Ink, style = IosTitleStyle, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

/**
 * The "+" button's menu: what used to be the long-press-on-wallpaper bottom sheet
 * ([EmptySpaceActionSheet]) reused verbatim (same rows, same test tags) but as a small anchored
 * iOS-style glass card (same corner/hairline/veil language as [IconContextPopover]) instead of a
 * full modal sheet — Tom's 2026-09-17 evening follow-up: long-pressing empty wallpaper now enters
 * edit mode directly, and these three actions live here instead.
 */
@Composable
internal fun EditModeAddMenu(onWidgets: () -> Unit, onWallpaper: () -> Unit, onCustomize: () -> Unit, onDismiss: () -> Unit) {
    val dark = LocalDuoPalette.current.dark
    Box(Modifier.fillMaxSize().testTag("edit-mode-add-menu-scrim")
        .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }) {
        Box(
            modifier = Modifier.statusBarsPadding().offset(x = 16.dp, y = 56.dp)
                .widthIn(min = 220.dp, max = 280.dp)
                .clip(RoundedCornerShape(IosMenuCorner))
                .frostedGlass(corner = IosMenuCorner, tintAlpha = .78f,
                    fallback = Glass.copy(alpha = .96f),
                    veilColor = if (dark) Color.Black else Color.White,
                    border = IosHairlineColor)
                .pointerInput(Unit) { detectTapGestures { /* absorb */ } }
                .testTag("edit-mode-add-menu"),
        ) {
            Column {
                EmptySpaceActionSheet(onWidgets = { onWidgets(); onDismiss() },
                    onWallpaper = { onWallpaper(); onDismiss() },
                    onCustomize = { onCustomize(); onDismiss() }, onClose = onDismiss)
            }
        }
    }
}
