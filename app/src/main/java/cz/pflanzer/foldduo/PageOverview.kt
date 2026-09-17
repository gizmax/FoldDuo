package cz.pflanzer.foldduo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * B39 "Přehled stránek a režimy plochy": pinch-out (two-finger scale below [PINCH_OUT_THRESHOLD])
 * anywhere on a Home page opens the overview. A distinct gesture recognizer from the single-pointer
 * ones in `PageGestures.kt`/`onePageGestures` (per B42's seam gesture, kept on its own detector so
 * neither steals the other's pointer) — it only ever looks at two-or-more-pointer frames, and a
 * one-pointer swipe never reaches [calculateZoom].
 */
private const val PINCH_OUT_THRESHOLD = 0.8f

@Composable
internal fun Modifier.pinchOutToOverview(enabled: Boolean, onTrigger: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(enabled) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scale = 1f
            var triggered = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                    scale *= event.calculateZoom()
                    if (!triggered && scale < PINCH_OUT_THRESHOLD) {
                        triggered = true
                        event.changes.forEach { it.consume() }
                        onTrigger()
                    }
                }
            } while (event.changes.any { it.pressed } && !triggered)
        }
    }
}

/** The overview's own pinch-in-to-close, symmetric with [pinchOutToOverview] (scale growing back past 1/[PINCH_OUT_THRESHOLD]). */
@Composable
private fun Modifier.pinchInToClose(onTrigger: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var scale = 1f
        var triggered = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                scale *= event.calculateZoom()
                if (!triggered && scale < PINCH_OUT_THRESHOLD) { triggered = true; event.changes.forEach { it.consume() }; onTrigger() }
            }
        } while (event.changes.any { it.pressed } && !triggered)
    }
}

private const val THUMB_SCALE = 0.4f

/** One widget's footprint on the page, in grid cells — [PageThumbnailContent] draws it as a single rounded rect rather than one icon per covered cell. */
private data class PageOverviewWidgetBox(val row: Int, val column: Int, val spanX: Int, val spanY: Int)

/**
 * Everything the overview needs from a Home page to draw its thumbnail, computed once per open.
 * B39 follow-up: a real (if simplified) render — [icons] reuses each app's own cached bitmap
 * (`AppEntry.icon`, already rasterised by `IconRenderer` at load time, the same one every other
 * tile in the launcher blits directly) rather than composing the live `SharedHomeGrid`, and
 * [widgets] is just each placement's footprint for a plain rounded-rect frame, not its content.
 */
private data class PageOverviewEntry(
    val page: Int, val isToday: Boolean, val hidden: Boolean,
    /** Row-major (width [GRID_COLUMNS]) — an app icon's bitmap, or null for an empty cell, a folder cell ([folderCells]) or one a widget covers ([widgets], excluded here so it is never drawn twice). */
    val icons: List<Bitmap?>,
    val folderCells: List<Boolean>,
    val widgets: List<PageOverviewWidgetBox>,
) {
    /** Mirrors the old dot-grid's emptiness check: nothing placed at all (Today is never "empty" — it always has at least its virtual default). */
    val isEmptyPage: Boolean get() = icons.all { it == null } && folderCells.none { it } && widgets.isEmpty()
}

private fun buildEntries(state: LauncherState, expanded: Boolean): List<PageOverviewEntry> {
    val appsById = state.apps.associateBy { it.id }
    fun entryFor(page: Int, isToday: Boolean, hidden: Boolean): PageOverviewEntry {
        val widgetsOnPage = state.widgetPlacements.filter { it.page == page }
        val covered = widgetsOnPage.flatMap { it.coveredIndices() }.toSet()
        val icons = (0 until HOME_CELLS).map { local ->
            val index = homeCellIndex(page, local)
            if (index in covered) null else appsById[state.layout.slotAt(index)]?.icon
        }
        val folderCells = (0 until HOME_CELLS).map { local ->
            val index = homeCellIndex(page, local)
            index !in covered && state.folders.any { it.id == state.layout.slotAt(index) }
        }
        return PageOverviewEntry(page, isToday, hidden, icons, folderCells,
            widgetsOnPage.map { PageOverviewWidgetBox(it.row, it.column, it.spanX, it.spanY) })
    }
    val today = if (expanded) listOf(entryFor(-1, true, false)) else emptyList()
    val homes = (0 until state.homePages).map { page -> entryFor(page, false, page in state.hiddenPages) }
    return today + homes
}

/**
 * The iOS-style page editor: mode chips on top, Home page thumbnails below (a horizontal row on
 * the cover, a two-row grid on the inner — pane identity keeps this composable itself confined to
 * the caller's right pane there, same as the rest of Home). [onDismiss] fires on Back, tapping the
 * scrim, or a pinch-in; [reduceMotion] drops the spring open/close for a plain 150 ms fade.
 */
@Composable
internal fun PageOverviewOverlay(
    visible: Boolean,
    state: LauncherState,
    expanded: Boolean,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onSetHidden: (Int, Boolean) -> Unit,
    onAddPage: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onSwitchMode: (String) -> Unit,
    onCreateMode: (String) -> Unit,
    onRenameMode: (String, String) -> Unit,
    onDeleteMode: (String) -> Unit,
    onToggleModePage: (String, Int, Boolean) -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val fade = if (reduceMotion) tween<Float>(150) else tween<Float>(220)
    AnimatedVisibility(visible = visible, enter = fadeIn(fade), exit = fadeOut(fade), modifier = Modifier.testTag("page-overview")) {
        val scale by animateFloatAsState(1f, animationSpec = if (reduceMotion) tween(150) else spring(dampingRatio = .8f), label = "overview-scale")
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
            .clickable(onClick = onDismiss)
            .pinchInToClose(onDismiss)
            .graphicsLayer { scaleX = scale; scaleY = scale }) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(if (expanded) .92f else .96f)
                .clip(RoundedCornerShape(28.dp)).background(Glass.copy(alpha = .34f))
                .border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(28.dp))
                // Absorb taps so they do not fall through to the dismiss scrim behind.
                .clickable(onClick = {}, enabled = false)
                .padding(16.dp)) {
                ModeChipRow(state, onSwitchMode, onCreateMode, onRenameMode, onDeleteMode)
                Spacer(Modifier.height(14.dp))
                val entries = remember(state.layout, state.apps, state.homePages, state.hiddenPages, expanded) { buildEntries(state, expanded) }
                val activeMode = state.homeModes.firstOrNull { it.id == state.activeModeId }
                val modeEditing = activeMode != null && activeMode.id != ALL_MODE_ID
                if (expanded) {
                    LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(260.dp).testTag("page-overview-grid"),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(entries, key = { it.page }) { entry ->
                            PageThumbnailCell(entry, state, expanded, modeEditing, activeMode, onJump, onReorder, onSetHidden, onRemovePage, onToggleModePage)
                        }
                        item(key = "add-page") { AddPageTile(expanded, onAddPage) }
                    }
                } else {
                    LazyRow(Modifier.fillMaxWidth().height(140.dp).testTag("page-overview-row"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(entries, key = { it.page }) { entry ->
                            PageThumbnailCell(entry, state, expanded, modeEditing, activeMode, onJump, onReorder, onSetHidden, onRemovePage, onToggleModePage)
                        }
                        item(key = "add-page") { AddPageTile(expanded, onAddPage) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChipRow(state: LauncherState, onSwitchMode: (String) -> Unit, onCreateMode: (String) -> Unit,
    onRenameMode: (String, String) -> Unit, onDeleteMode: (String) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    LazyRow(Modifier.fillMaxWidth().testTag("mode-chip-row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.homeModes, key = { it.id }) { mode ->
            if (renamingId == mode.id) {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    modifier = Modifier.width(140.dp).testTag("mode-rename-field-${mode.id}"),
                    keyboardOptions = KeyboardOptions.Default,
                    keyboardActions = KeyboardActions(onDone = {
                        onRenameMode(mode.id, renameText.trim().ifBlank { mode.name }); renamingId = null
                    }))
            } else {
                FilterChip(selected = mode.id == state.activeModeId, onClick = { onSwitchMode(mode.id) },
                    modifier = Modifier.testTag("mode-chip-${mode.id}").semantics { contentDescription = "Režim: ${mode.name}" },
                    label = { Text(mode.name) },
                    trailingIcon = if (mode.id != ALL_MODE_ID) ({
                        Row {
                            Icon(Icons.Rounded.Edit, "Rename ${mode.name}", modifier = Modifier.size(16.dp)
                                .clickable { renameText = mode.name; renamingId = mode.id }.testTag("mode-rename-${mode.id}"))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Close, "Delete ${mode.name}", modifier = Modifier.size(16.dp)
                                .clickable { onDeleteMode(mode.id) }.testTag("mode-delete-${mode.id}"))
                        }
                    }) else null)
            }
        }
        item("new-mode") {
            if (creating) {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                    modifier = Modifier.width(140.dp).testTag("mode-new-field"),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) onCreateMode(newName.trim()); newName = ""; creating = false
                    }))
            } else {
                AssistChip(onClick = { creating = true }, label = { Text("+ Režim") }, modifier = Modifier.testTag("mode-add"))
            }
        }
    }
}

@Composable
private fun AddPageTile(expanded: Boolean, onAddPage: () -> Unit) {
    val width = if (expanded) 90.dp else 70.dp
    Box(Modifier.width(width).height(width * 1.5f).clip(RoundedCornerShape(14.dp))
        .background(Glass.copy(alpha = .2f)).border(1.dp, Color.White.copy(alpha = .3f), RoundedCornerShape(14.dp))
        .clickable(onClick = onAddPage).testTag("page-overview-add"), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Add, "Přidat stránku", tint = Color.White)
    }
}

/**
 * One thumbnail. Long-press-drag reorders (Today and the "+" tile are excluded by the caller
 * never wiring [onReorder] for them); the checkmark hides/shows. The trash (2026-09-17 noc
 * "Mazání stránek s dotazem") removes an empty page outright and force-deletes a non-empty one
 * through the same "Smazat stránku?" confirmation as everywhere else — [onRemovePage] itself
 * decides which (see `requestDeletePage` in LauncherScreen.kt), this cell just always shows it.
 * It shares its corner with the mode-membership toggle: while editing a custom mode's page set
 * that toggle takes over instead, so a page can't be both deleted and marked a member in the same
 * tap target — turning off mode editing brings the trash back.
 */
@Composable
private fun PageThumbnailCell(
    entry: PageOverviewEntry,
    state: LauncherState,
    expanded: Boolean,
    modeEditing: Boolean,
    activeMode: HomeMode?,
    onJump: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onSetHidden: (Int, Boolean) -> Unit,
    onRemovePage: (Int) -> Unit,
    onToggleModePage: (String, Int, Boolean) -> Unit,
) {
    val width = if (expanded) 90.dp else 70.dp
    val height = width * 1.5f
    val density = LocalDensity.current
    val widthPx = with(density) { (width + 14.dp).toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val isEmpty = !entry.isToday && entry.isEmptyPage
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier
            .graphicsLayer { translationX = if (dragging) dragOffset else 0f }
            .width(width).height(height).clip(RoundedCornerShape(14.dp))
            .background(if (entry.hidden) Color.Black.copy(alpha = .35f) else Glass.copy(alpha = .28f))
            .border(1.dp, Color.White.copy(alpha = if (entry.hidden) .15f else .35f), RoundedCornerShape(14.dp))
            .alpha(if (entry.hidden) .5f else 1f)
            .clickable { onJump(entry.page) }
            .then(if (entry.isToday) Modifier else Modifier.pointerInput(entry.page) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true; dragOffset = 0f },
                    onDrag = { change, amount ->
                        change.consume(); dragOffset += amount.x
                        val steps = (dragOffset / widthPx).roundToInt()
                        if (steps != 0) {
                            val target = (entry.page + steps).coerceIn(0, state.homePages - 1)
                            if (target != entry.page) { onReorder(entry.page, target); dragOffset -= steps * widthPx }
                        }
                    },
                    onDragEnd = { dragging = false; dragOffset = 0f },
                    onDragCancel = { dragging = false; dragOffset = 0f },
                )
            })
            .testTag(if (entry.isToday) "page-overview-thumb-today" else "page-overview-thumb-${entry.page}"),
        ) {
            PageThumbnailContent(entry, Modifier.matchParentSize().padding(8.dp))
            if (!entry.isToday) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .45f))
                    .clickable { onSetHidden(entry.page, !entry.hidden) }
                    .testTag("page-overview-hide-${entry.page}"), contentAlignment = Alignment.Center) {
                    if (!entry.hidden) Icon(Icons.Rounded.Check, "Hide page ${entry.page + 1}", tint = Color.White, modifier = Modifier.size(14.dp))
                }
                if (isEmpty || !modeEditing) Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .45f))
                    .clickable { onRemovePage(entry.page) }
                    .testTag("page-overview-trash-${entry.page}"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Delete, if (isEmpty) "Remove empty page ${entry.page + 1}" else "Delete page ${entry.page + 1}",
                        tint = Color.White, modifier = Modifier.size(13.dp))
                } else if (activeMode != null) {
                    val member = entry.page in activeMode.visiblePages
                    Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).clip(CircleShape)
                        .background(if (member) Color.White.copy(alpha = .85f) else Color.Black.copy(alpha = .45f))
                        .clickable { onToggleModePage(activeMode.id, entry.page, !member) }
                        .testTag("page-overview-mode-member-${entry.page}"), contentAlignment = Alignment.Center) {
                        if (member) Icon(Icons.Rounded.Check, "In ${activeMode.name}", tint = Color.Black, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(if (entry.isToday) "Today" else "${entry.page + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center)
    }
}

/**
 * B39 follow-up: a real (if cheap) scaled-down render of [entry] instead of the old dot grid —
 * each app icon's own cached bitmap ([AppEntry.icon], already rasterised once by [IconRenderer])
 * blitted at thumbnail size, a plain tinted square standing in for a folder, and one rounded rect
 * per widget's footprint (never its live content — that would mean composing the real
 * `SharedHomeGrid`/host widget views just for a thumbnail, which the task calls out of scope).
 * Positioned by simple arithmetic against [BoxWithConstraints], the same shape of layout
 * `SharedHomeGrid` itself uses, rather than a `LazyVerticalGrid` — cheaper for a fixed 4xGRID_ROWS
 * cell count with no scrolling.
 */
@Composable
private fun PageThumbnailContent(entry: PageOverviewEntry, modifier: Modifier = Modifier) {
    val iconStyle = LocalIconStyle.current
    BoxWithConstraints(modifier) {
        val cellWidth = maxWidth / GRID_COLUMNS
        val cellHeight = maxHeight / GRID_ROWS
        val iconSize = minOf(cellWidth, cellHeight) * 0.8f
        val iconInsetX = (cellWidth - iconSize) / 2
        val iconInsetY = (cellHeight - iconSize) / 2
        // Widget frames first, so they sit under nothing (their own covered cells never also get
        // an icon or folder square — buildEntries already excludes them there).
        entry.widgets.forEach { box ->
            Box(Modifier
                .offset(x = cellWidth * box.column, y = cellHeight * box.row)
                .size(cellWidth * box.spanX, cellHeight * box.spanY)
                .padding(1.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = .22f))
                .border(.5.dp, Color.White.copy(alpha = .4f), RoundedCornerShape(3.dp)))
        }
        entry.icons.forEachIndexed { local, icon ->
            if (icon != null) Image(icon.asImageBitmap(), null,
                Modifier.offset(x = cellWidth * (local % GRID_COLUMNS) + iconInsetX, y = cellHeight * (local / GRID_COLUMNS) + iconInsetY)
                    .size(iconSize).clip(iconStyle.clipShape()))
        }
        entry.folderCells.forEachIndexed { local, isFolder ->
            if (isFolder) Box(Modifier
                .offset(x = cellWidth * (local % GRID_COLUMNS) + iconInsetX, y = cellHeight * (local / GRID_COLUMNS) + iconInsetY)
                .size(iconSize).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .5f)))
        }
    }
}
