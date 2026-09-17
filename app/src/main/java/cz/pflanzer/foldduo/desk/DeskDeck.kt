package cz.pflanzer.foldduo.desk

import android.media.session.MediaController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.AppEntry
import cz.pflanzer.foldduo.LauncherState
import cz.pflanzer.foldduo.SeamPaletteDndTile
import cz.pflanzer.foldduo.SeamPaletteFlashlightTile
import cz.pflanzer.foldduo.SeamPaletteRecentApps
import cz.pflanzer.foldduo.SeamPaletteToggleTile
import cz.pflanzer.foldduo.frostedGlass
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Wifi
import cz.pflanzer.foldduo.openAutoRotateSettings
import cz.pflanzer.foldduo.openWifiPanel

/**
 * B47 "Stůl": the deck — a scrollable column of glass cards, draggable to reorder (a handle icon,
 * not the whole card body, so a drag never fights the card's own controls) and long-press (on the
 * body, not the handle) to hide; a trailing "+" row restores whatever is hidden. Persists order
 * and hidden set to [DeskPrefs] as they change.
 */
@Composable
internal fun DeskDeck(
    prefs: DeskPrefs,
    state: LauncherState,
    islandItems: List<IslandItem>,
    onLaunchApp: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var order by remember { mutableStateOf(prefs.cardOrder) }
    var hidden by remember { mutableStateOf(prefs.hiddenCards) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val visible = DeskCardOrder.visible(order, hidden)
    val density = LocalDensity.current
    val rowHeightPx = with(density) { DECK_ROW_HEIGHT_DP.dp.toPx() }
    val mediaController: MediaController? = remember(islandItems) {
        islandItems.firstOrNull { it.kind == IslandKind.MEDIA }?.media?.controller
    }

    Column(modifier.testTag("desk-deck")) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(visible, key = { _, card -> card.name }) { index, card ->
                val isDragging = index == draggingIndex
                Box(Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                    .testTag("desk-card-${card.name.lowercase()}")) {
                    DeskCardChrome(
                        onHide = { hidden = DeskCardOrder.hide(hidden, card); prefs.hiddenCards = hidden },
                        onDragDelta = { deltaY ->
                            draggingIndex = index
                            dragOffsetY += deltaY
                            val targetIndex = (index + (dragOffsetY / rowHeightPx).toInt()).coerceIn(0, visible.lastIndex)
                            if (targetIndex != index) {
                                val movedName = visible[index]
                                val newVisibleOrder = DeskCardOrder.move(visible, index, targetIndex)
                                // Re-thread the hidden cards back into their old relative spots so
                                // a reorder among the visible ones never reveals or reshuffles them.
                                order = rebuildFullOrder(order, newVisibleOrder)
                                prefs.cardOrder = order
                                draggingIndex = targetIndex
                                dragOffsetY -= (targetIndex - index) * rowHeightPx
                            }
                        },
                        onDragEnd = { draggingIndex = -1; dragOffsetY = 0f },
                    ) {
                        DeskCardContent(card, prefs, state, mediaController, onLaunchApp)
                    }
                }
            }
        }
        val hiddenList = hidden.toList()
        if (hiddenList.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Hidden:", color = Color.White.copy(alpha = .6f), modifier = Modifier.padding(end = 8.dp))
                hiddenList.forEach { card ->
                    Row(Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = .12f))
                        .clickable(onClick = { hidden = DeskCardOrder.show(hidden, card); prefs.hiddenCards = hidden })
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("desk-restore-${card.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.padding(end = 4.dp))
                        Text(card.name, color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

/** Moves the same card that moved within [newVisibleOrder] to the matching position in the full
 * [fullOrder] (hidden cards included), so hidden cards keep their place relative to what is still
 * visible around them instead of being dropped to the end. */
private fun rebuildFullOrder(fullOrder: List<DeskCard>, newVisibleOrder: List<DeskCard>): List<DeskCard> {
    val visibleSet = newVisibleOrder.toSet()
    val hiddenInPlace = fullOrder.filterNot { it in visibleSet }
    // Simple, deterministic merge: visible cards in their new order, hidden ones re-inserted at
    // their original fractional position among the old full order.
    val result = mutableListOf<DeskCard>()
    var visibleCursor = 0
    for (original in fullOrder) {
        if (original in visibleSet) {
            if (visibleCursor < newVisibleOrder.size) result += newVisibleOrder[visibleCursor]
            visibleCursor++
        } else {
            result += original
        }
    }
    // Any leftover (should not happen, sizes match) is appended defensively.
    while (visibleCursor < newVisibleOrder.size) { result += newVisibleOrder[visibleCursor]; visibleCursor++ }
    return (result + hiddenInPlace).distinct()
}

private const val DECK_ROW_HEIGHT_DP = 96

@Composable
private fun DeskCardChrome(
    onHide: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    content: @Composable () -> Unit,
) {
    val onDragDeltaState = rememberUpdatedState(onDragDelta)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    Row(Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .frostedGlass(corner = 18.dp, tintAlpha = .18f)
        .pointerInput(Unit) { detectTapGestures(onLongPress = { onHide() }) }
        .padding(14.dp)) {
        Box(Modifier.weight(1f)) { content() }
        Icon(Icons.Rounded.DragHandle, "Reorder", tint = Color.White.copy(alpha = .5f),
            modifier = Modifier
                .padding(start = 8.dp)
                .testTag("desk-drag-handle")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { onDragEndState.value(); break }
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            change.consume()
                            onDragDeltaState.value(dy)
                        }
                    }
                })
    }
}

@Composable
private fun DeskCardContent(
    card: DeskCard,
    prefs: DeskPrefs,
    state: LauncherState,
    mediaController: MediaController?,
    onLaunchApp: (AppEntry) -> Unit,
) = when (card) {
    DeskCard.Media -> DeskMediaCard(mediaController)
    DeskCard.Timer -> DeskTimerCard()
    DeskCard.Note -> DeskNoteCard(prefs)
    DeskCard.Calculator -> DeskCalculatorCard()
    DeskCard.Toggles -> DeskTogglesCard()
    DeskCard.Recents -> DeskRecentsCard(state, onLaunchApp)
}

@Composable
private fun DeskTogglesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.testTag("desk-toggles-card")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeamPaletteToggleTile(Icons.Rounded.Wifi, "Wi-Fi", Modifier.weight(1f).testTag("desk-toggle-wifi")) { openWifiPanel(context) }
            SeamPaletteFlashlightTile(context, Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeamPaletteDndTile(context, Modifier.weight(1f))
            SeamPaletteToggleTile(Icons.Rounded.ScreenRotation, "Auto-rotate", Modifier.weight(1f).testTag("desk-toggle-rotate")) { openAutoRotateSettings(context) }
        }
    }
}

@Composable
private fun DeskRecentsCard(state: LauncherState, onLaunchApp: (AppEntry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("desk-recents-card")) {
        Text("Recent", color = Color.White.copy(alpha = .7f))
        Spacer(Modifier.width(8.dp))
        SeamPaletteRecentApps(state, onLaunchApp)
    }
}
