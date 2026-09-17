package cz.pflanzer.foldduo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.notifications.notificationBadge
import kotlinx.coroutines.launch

/** Open/close spring (IDEAS B25): matches the App Library shared-bounds morph (AppLibraryPage.kt). */
private val FolderOpenSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

private fun Rect.toFoldRect() = FoldRect(left, top, width, height)

@Composable
internal fun FolderPanel(
    folder: FolderEntry, apps: Map<String, AppEntry>, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>, onDismiss: () -> Unit,
    onRename: (String) -> Unit, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onMoveOut: (String, DropTarget) -> Unit,
    /** Right inset for the persistent rail; the scrim stays full-bleed, the panel centres beside it. */
    endInset: Dp = 0.dp,
    /**
     * Fold avoidance (IDEAS B7): unfolded, the extent (dp, this box's frame) of the pane holding
     * the tapped folder icon. The panel centres inside it and never straddles the seam; the scrim
     * still covers both panes. `null` keeps the compact, window-centred layout.
     */
    pane: ClosedFloatingPointRange<Float>? = null,
    /**
     * Root-window bounds (px, `LayoutCoordinates.boundsInRoot()`) of the tapped folder icon
     * (IDEAS B25, "Složka jako skleněná karta"): the card grows from and shrinks back into this
     * rect. `null` (icon never measured, e.g. a restored dock slot) falls back to a plain
     * scale-and-fade — see [folderOpenFrameFallback].
     */
    iconBounds: Rect? = null,
    /**
     * Set while the app being dragged belongs to this open folder (LauncherScreen.kt: dragging a
     * child out). Instead of the caller nulling `openFolderId` the instant the drag starts, this
     * plays the same reverse spring as any other close and only then calls [onDismiss] — the card
     * visibly shrinks back into its icon while the drag continues over the home grid.
     */
    closeRequested: Boolean = false,
    /** Live open/close progress (0 = icon, 1 = open), so the real folder icon in the grid behind
     * this card (FolderTile in LauncherScreen.kt) can fade out in step instead of staying visible
     * underneath. */
    onProgress: (Float) -> Unit = {},
) {
    var title by rememberSaveable(folder.id) { mutableStateOf(folder.title) }
    val context = LocalContext.current
    val reduceMotion = remember(context) { systemReduceMotionEnabled(context) }
    val progress = remember(folder.id) { Animatable(if (reduceMotion) 1f else 0f) }
    var closing by remember(folder.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(folder.id, reduceMotion) {
        Haptics.play(context, HapticEvent.FOLDER_OPEN)
        if (!reduceMotion) progress.animateTo(1f, FolderOpenSpring)
    }
    fun requestClose() {
        if (closing) return
        closing = true
        Haptics.play(context, HapticEvent.FOLDER_CLOSE)
        scope.launch {
            if (!reduceMotion) progress.animateTo(0f, FolderOpenSpring)
            onDismiss()
        }
    }
    BackHandler { requestClose() }
    LaunchedEffect(closeRequested) { if (closeRequested) requestClose() }
    DisposableEffect(drag, folder.id) {
        drag.activeSourceScope = folder.id
        onDispose { if (drag.activeSourceScope == folder.id) drag.activeSourceScope = null }
    }
    var cardRootBounds by remember(folder.id) { mutableStateOf<Rect?>(null) }
    val iconRect = iconBounds?.toFoldRect()
    val cardRect = cardRootBounds?.toFoldRect()
    val frame = if (iconRect != null && cardRect != null) folderOpenFrame(progress.value, iconRect, cardRect)
        else folderOpenFrameFallback(progress.value)
    SideEffect { onProgress(progress.value) }
    val dark = LocalDuoPalette.current.dark
    Box(Modifier.fillMaxSize().imePadding().padding(end = endInset).testTag("folder-panel"),
        contentAlignment = Alignment.Center) {
        // Frost + 15 % dim behind the card, confined to the pane holding the folder on the inner
        // display (the other pane stays crisp); full window on the cover, where pane is null.
        val regionModifier = if (pane != null) {
            Modifier.align(Alignment.CenterStart).offset(x = pane.start.dp).width(pane.extentDp.dp).fillMaxHeight()
        } else Modifier.fillMaxSize()
        Box(regionModifier.frostedGlass(corner = 0.dp, tintAlpha = 0f, fallback = Color.Transparent, border = null)
            .alpha(frame.frostAlpha))
        Box(regionModifier.background(Color.Black.copy(alpha = frame.dimAlpha)))
        // Tapping anywhere outside the card (either pane) closes it, reversing into the icon.
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, onClickLabel = "Close folder", onClick = ::requestClose))
        // Inside a pane the panel takes at most the pane width minus a margin on each side (and
        // no more than the usual 90 %); the pane box is positioned from the window's left edge.
        val panelModifier = if (pane != null) {
            val width = minOf(pane.extentDp - 2 * PANE_SURFACE_MARGIN_DP, pane.extentDp * .9f).coerceAtLeast(120f)
            Modifier.align(Alignment.CenterStart).offset(x = (pane.start + (pane.extentDp - width) / 2f).dp).width(width.dp)
        } else Modifier.fillMaxWidth(.9f)
        Box(panelModifier.fillMaxHeight(.82f).heightIn(min = 260.dp, max = 620.dp)
            // Captured before the graphicsLayer transform below: the card's own final, laid-out
            // position, untouched by the morph that visually shrinks it back to the icon.
            .onGloballyPositioned { cardRootBounds = it.boundsInRoot() }
            .graphicsLayer {
                scaleX = frame.scaleX; scaleY = frame.scaleY
                translationX = frame.translateX; translationY = frame.translateY
            }
            .clip(RoundedCornerShape(32.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .testTag("folder-panel-content")
            .frostedGlass(corner = 32.dp, tintAlpha = .55f, fallback = Glass.copy(alpha = .97f),
                veilColor = if (dark) Color.Black else Color.White)) {
            if (iconRect != null) FolderIconPreview(folder, apps,
                Modifier.fillMaxSize().padding(28.dp).alpha(frame.iconPreviewAlpha))
            Column(Modifier.padding(18.dp).alpha(frame.contentAlpha)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, { title = it }, Modifier.weight(1f).testTag("folder-name"),
                        singleLine = true, textStyle = IosMenuRowStyle, label = { Text("Folder name", style = IosSectionLabelStyle) })
                    TextButton(onClick = { if (title.isNotBlank()) onRename(title); requestClose() }) {
                        Text("Done", style = IosTitleStyle)
                    }
                }
                val pages = remember(folder.appIds) { folder.appIds.chunked(FOLDER_APPS_PER_PAGE) }
                    .ifEmpty { listOf(emptyList()) }
                if (pages.size <= 1) {
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                        contentPadding = PaddingValues(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(pages.first(), key = { it }) { appId ->
                            apps[appId]?.let { app -> FolderChild(app, folder.id, drag, page, homeDestinations, dockVacancies,
                                onLaunch = onLaunch, onMoveOut = onMoveOut) }
                        }
                    }
                } else {
                    val pagerState = rememberPagerState(pageCount = { pages.size })
                    HorizontalPager(pagerState, Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) { pageIndex ->
                        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), userScrollEnabled = false,
                            contentPadding = PaddingValues(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(pages[pageIndex], key = { it }) { appId ->
                                apps[appId]?.let { app -> FolderChild(app, folder.id, drag, page, homeDestinations, dockVacancies,
                                    onLaunch = onLaunch, onMoveOut = onMoveOut) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        repeat(pages.size) { index -> FolderPageDot(selected = index == pagerState.currentPage) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPageDot(selected: Boolean) {
    Box(Modifier.size(if (selected) 8.dp else 6.dp).clip(CircleShape)
        .background(Color.White.copy(alpha = if (selected) 1f else .4f)))
}

/** The folder's closed-icon look (up to 4 mini previews), crossfaded out as the card opens. */
@Composable
private fun FolderIconPreview(folder: FolderEntry, apps: Map<String, AppEntry>, modifier: Modifier = Modifier) {
    Box(modifier) {
        folder.appIds.take(4).forEachIndexed { index, id ->
            apps[id]?.let { app ->
                Image(app.icon.asImageBitmap(), null, Modifier.align(when (index) {
                    0 -> Alignment.TopStart; 1 -> Alignment.TopEnd; 2 -> Alignment.BottomStart; else -> Alignment.BottomEnd
                }).fillMaxSize(.42f).padding(6.dp).clip(LocalIconStyle.current.clipShape()))
            }
        }
    }
}

@Composable
private fun FolderChild(
    app: AppEntry, folderId: String, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onMoveOut: (String, DropTarget) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // B23 follow-up: same shared press spring as every other icon surface (Home, dock, App
    // Library, closed folder tile) — apps inside an open folder were the one surface still
    // pressing at full scale with no feedback.
    val interaction = remember { MutableInteractionSource() }
    Surface(Modifier.fillMaxWidth().testTag("folder-child-${app.id}"), color = Color.White.copy(alpha = .34f),
        shape = RoundedCornerShape(18.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().dropRegion(drag, DropTarget.Library(app.id), app.id, page,
                folderId = folderId, scope = folderId).clickable(interactionSource = interaction,
                indication = LocalIndication.current, enabled = app.available) { onLaunch(app, null) }
                .padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[app.packageName] ?: 0
                Image(app.icon.asImageBitmap(), null, Modifier.size(64.dp).iconPressScale(interaction)
                    .clip(LocalIconStyle.current.clipShape())
                    .notificationBadge(badgeCount))
                Text(app.label, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium)
                if (app.isWork || !app.available) Text(if (app.available) app.profileLabel else "${app.profileLabel} unavailable",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { menu = true }, Modifier.align(Alignment.TopEnd).size(36.dp)
                .testTag("folder-options-${app.id}")) { Icon(Icons.Rounded.MoreVert, "Move ${app.label}") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                homeDestinations.distinctBy(::homeCellPage).forEach { destination ->
                    val destinationPage = homeCellPage(destination)
                    val label = if (destinationPage == -1) "Move to the left pane" else "Move to page ${destinationPage + 1}"
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menu = false; onMoveOut(app.id, DropTarget.Home(destination))
                    }, modifier = Modifier.testTag("folder-move-${app.id}-page-$destinationPage"))
                }
                dockVacancies.firstOrNull()?.let { dock ->
                    DropdownMenuItem(text = { Text("Move to dock") }, onClick = {
                        menu = false; onMoveOut(app.id, DropTarget.Dock(dock))
                    }, modifier = Modifier.testTag("folder-move-${app.id}-dock"))
                }
                DropdownMenuItem(text = { Text("Remove shortcut") }, onClick = {
                    menu = false; onMoveOut(app.id, DropTarget.Remove)
                }, modifier = Modifier.testTag("folder-remove-${app.id}"))
            }
        }
    }
}
