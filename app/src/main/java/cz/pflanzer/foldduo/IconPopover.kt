package cz.pflanzer.foldduo

import android.content.Intent
import android.content.pm.LauncherApps
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * iPhone-style icon context popover (2026-09-17 evening, IDEAS item 1 of the "long-press
 * regression" fix; restyled 2026-09-17 night to actually look like iOS 17/18's `UIMenu` — see
 * IosStyle.kt for the font finding and the shared type/shape tokens): a compact glass card
 * anchored right next to the pressed icon, instead of the full-width LauncherAppActionSheet. Only
 * what fits comfortably lives here — shortcuts, Share, "Move to page...", "Edit Home Screen",
 * "App info", "Add/Remove from Home" — anything else ("Widgets", "Create folder") reopens the
 * existing sheet via "More...". IconGesture.kt/HomeDrag.kt's `homeDragInput` decide *when* this
 * shows; this file is only the anchored card itself (plus the pressed icon's own "lifted" preview
 * and the page dim behind it), rendered as a plain overlay inside LauncherScreen's own root Box
 * (never a separate Dialog window) so it shares the launcher's coordinate space and stays in
 * whichever pane the icon is in for free.
 */

/** One row: an app's own static/dynamic shortcut, best-effort ([queryAppShortcuts]). */
internal data class AppShortcutUi(val id: String, val label: String)

/**
 * Static + dynamic shortcuts for [app], via [LauncherApps.getShortcuts] — only callable at all
 * while this app is the default Home (`ACCESS_SHORTCUTS` is otherwise denied), so [isDefaultHome]
 * short-circuits to an empty list rather than letting the call throw. Every other failure
 * (profile not unlocked, provider gone, permission revoked mid-session) is swallowed the same
 * way: shortcuts are a nice-to-have in the popover, never a reason to crash it.
 */
internal fun queryAppShortcuts(
    context: android.content.Context, app: AppEntry, isDefaultHome: Boolean, limit: Int = 4,
): List<AppShortcutUi> {
    if (!isDefaultHome || app.packageName.isEmpty()) return emptyList()
    return runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val query = LauncherApps.ShortcutQuery().setPackage(app.packageName).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
        val shortcuts = launcherApps.getShortcuts(query, app.user) ?: emptyList()
        shortcuts.filter { it.isEnabled }.take(limit).map { info ->
            AppShortcutUi(info.id, (info.shortLabel ?: info.longLabel ?: info.id).toString())
        }
    }.getOrDefault(emptyList())
}

/** Launches one of [queryAppShortcuts]'s results; swallows failures the same way the query does. */
internal fun launchAppShortcut(context: android.content.Context, app: AppEntry, shortcutId: String) {
    runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        launcherApps.startShortcut(app.packageName, shortcutId, null, null, app.user)
    }
}

/** "Share" row: a plain-text share sheet with the app's Play Store link — self-contained (no new
 * callback threaded through LauncherScreen.kt), swallows failures the same way shortcuts do. */
internal fun shareApp(context: android.content.Context, app: AppEntry) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, app.label)
            putExtra(Intent.EXTRA_TEXT, "https://play.google.com/store/apps/details?id=${app.packageName}")
        }
        val chooser = Intent.createChooser(send, app.label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

/** Live shortcuts for [app], refetched whenever the popover reopens on a different app. */
@Composable
internal fun rememberAppShortcuts(app: AppEntry, isDefaultHome: Boolean): List<AppShortcutUi> {
    val context = LocalContext.current
    val state by produceState(initialValue = emptyList(), app.id, isDefaultHome) {
        value = queryAppShortcuts(context, app, isDefaultHome)
    }
    return state
}

/**
 * Whether the popover opens below [anchor] ([popoverHeight] tall) within [screenHeight]; else it
 * opens above. Pure so placement is JVM-testable without measuring a real popover.
 */
internal fun popoverOpensBelow(anchor: Rect, popoverHeight: Float, screenHeight: Float, margin: Float): Boolean =
    anchor.bottom + margin + popoverHeight <= screenHeight - margin

/** Whether the popover stays left-aligned with [anchor] within [screenWidth]; else it flips to
 * the icon's trailing (right) edge. Pure, same reason as [popoverOpensBelow]. */
internal fun popoverAlignsLeft(anchor: Rect, popoverWidth: Float, screenWidth: Float, margin: Float): Boolean =
    anchor.left + popoverWidth <= screenWidth - margin

/**
 * Anchors the popover beside [anchor] (the pressed icon's bounds, root coordinates): below if
 * there is room, else above ([popoverOpensBelow]); left-aligned with the icon if there is room,
 * else flipped to its trailing edge ([popoverAlignsLeft]); always fully on-screen and, when
 * [paneRangeX] is given (fold avoidance — the icon's own pane, same coordinate space as [anchor]),
 * clamped to that pane horizontally instead of the whole window.
 */
internal fun popoverAnchorOffset(
    anchor: Rect, popoverSize: Size, screenSize: Size, margin: Float,
    paneRangeX: ClosedFloatingPointRange<Float>? = null,
): Offset {
    val loX = paneRangeX?.start ?: 0f
    val hiX = paneRangeX?.endInclusive ?: screenSize.width
    val alignLeft = popoverAlignsLeft(anchor, popoverSize.width, hiX, margin)
    val x = if (alignLeft) anchor.left else anchor.right - popoverSize.width
    val minX = loX + margin
    val maxX = (hiX - popoverSize.width - margin).coerceAtLeast(minX)
    val below = popoverOpensBelow(anchor, popoverSize.height, screenSize.height, margin)
    val y = if (below) anchor.bottom + margin else anchor.top - popoverSize.height - margin
    val minY = margin
    val maxY = (screenSize.height - popoverSize.height - margin).coerceAtLeast(minY)
    return Offset(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
}

/** The popover's static (non-shortcut) rows, grouped and ordered — pure so grouping is
 * JVM-testable without rendering a real card. [placed] decides whether "Add to Home" folds into
 * the middle group or "Remove from Home" gets its own trailing red group. */
internal enum class PopoverRowId { SHARE, MOVE_PAGE, EDIT_HOME, APP_INFO, TOGGLE_HOME, MORE }

internal fun iconPopoverGroups(placed: Boolean): List<List<PopoverRowId>> = buildList {
    add(buildList {
        add(PopoverRowId.SHARE); add(PopoverRowId.MOVE_PAGE); add(PopoverRowId.EDIT_HOME)
        add(PopoverRowId.APP_INFO)
        if (!placed) add(PopoverRowId.TOGGLE_HOME)
    })
    if (placed) add(listOf(PopoverRowId.TOGGLE_HOME))
    add(listOf(PopoverRowId.MORE))
}

@Immutable
private data class PopoverRowSpec(
    val key: String, val label: String, val icon: ImageVector,
    val destructive: Boolean = false, val onClick: () -> Unit,
)

private fun PopoverRowId.icon(placed: Boolean): ImageVector = when (this) {
    PopoverRowId.SHARE -> Icons.Rounded.Share
    PopoverRowId.MOVE_PAGE -> Icons.Rounded.MoveToInbox
    PopoverRowId.EDIT_HOME -> Icons.Rounded.Edit
    PopoverRowId.APP_INFO -> Icons.Rounded.Info
    PopoverRowId.TOGGLE_HOME -> if (placed) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.AddCircleOutline
    PopoverRowId.MORE -> Icons.Rounded.MoreHoriz
}

private fun PopoverRowId.label(placed: Boolean): String = when (this) {
    PopoverRowId.SHARE -> "Share"
    PopoverRowId.MOVE_PAGE -> "Move to page…"
    PopoverRowId.EDIT_HOME -> "Edit Home Screen"
    PopoverRowId.APP_INFO -> "App info"
    PopoverRowId.TOGGLE_HOME -> if (placed) "Remove from Home" else "Add to Home"
    PopoverRowId.MORE -> "More…"
}

/** iOS row: label LEFT, a small monochrome glyph RIGHT, 44 dp tall, 17 sp regular (destructive in
 * [IosDestructive] red — same size, not bold). */
@Composable
private fun PopoverRow(spec: PopoverRowSpec) {
    val tint = if (spec.destructive) IosDestructive else Color.White
    Row(Modifier.fillMaxWidth().height(IosMenuRowHeight).clickable(onClick = spec.onClick)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(spec.label, style = IosMenuRowStyle, color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Icon(spec.icon, null, Modifier.size(20.dp), tint = tint)
    }
}

/**
 * The popover overlay: a full-size scrim dimming the rest of the page 10 % (tap anywhere outside
 * the card, or Back, dismisses — reversing the open spring first) plus the glass card itself,
 * positioned by [popoverAnchorOffset] once it has measured its own size, and the pressed icon's
 * own "lifted" preview (scales to 1.15 with a soft shadow while the menu is open, drawn above the
 * dim so it reads as lifted rather than dimmed with everything else). [anchor] and [screenSize]
 * are both root-coordinate, matching [HomeDragState.rootBounds]; the caller places this directly
 * inside the same root Box the drag ghost renders into, so it never leaves that coordinate space
 * (and stays in the pressed icon's pane for free). [paneRangeX], when given, is that pane's
 * horizontal extent in the same coordinate space, so the card never crosses the hinge.
 */
@Composable
internal fun IconContextPopover(
    app: AppEntry,
    anchor: Rect,
    screenSize: Size,
    placed: Boolean,
    isDefaultHome: Boolean,
    onDismiss: () -> Unit,
    onEditHome: () -> Unit,
    onMovePage: () -> Unit,
    onAppInfo: () -> Unit,
    onToggleHome: () -> Unit,
    onMore: () -> Unit,
    paneRangeX: ClosedFloatingPointRange<Float>? = null,
) {
    val context = LocalContext.current
    val reduceMotion = MotionPrefs.enabled.value
    val scope = rememberCoroutineScope()
    var closing by remember(app.id) { mutableStateOf(false) }
    val scale = remember(app.id) { Animatable(if (reduceMotion) 1f else 0.9f) }
    fun requestDismiss() {
        if (closing) return
        closing = true
        scope.launch {
            if (!reduceMotion) scale.animateTo(0.9f, IosMenuSpring)
            onDismiss()
        }
    }
    // The ICON_POPOVER haptic already plays where the popover is *requested* (HomeDrag.kt's
    // `onPopover`, wired in LauncherScreen.kt's `homeDragInput` call) — not here, or it would
    // double-fire on every open.
    LaunchedEffect(app.id, reduceMotion) {
        if (!reduceMotion) scale.animateTo(1f, IosMenuSpring) else scale.snapTo(1f)
    }
    BackHandler(onBack = ::requestDismiss)
    val density = LocalDensity.current
    val marginPx = with(density) { 12.dp.toPx() }
    var measuredSize by remember(app.id) { mutableStateOf(Size.Zero) }
    val shortcuts = rememberAppShortcuts(app, isDefaultHome)
    val dark = LocalDuoPalette.current.dark

    val rows = remember(shortcuts, placed) {
        buildList {
            shortcuts.forEach { s ->
                add(PopoverRowSpec("shortcut:${s.id}", s.label, Icons.Rounded.AppShortcut) {
                    launchAppShortcut(context, app, s.id); requestDismiss()
                })
            }
            iconPopoverGroups(placed).forEach { group ->
                group.forEach { id ->
                    val onClick: () -> Unit = when (id) {
                        PopoverRowId.SHARE -> { { shareApp(context, app); requestDismiss() } }
                        PopoverRowId.MOVE_PAGE -> onMovePage
                        PopoverRowId.EDIT_HOME -> { { onEditHome(); requestDismiss() } }
                        PopoverRowId.APP_INFO -> { { onAppInfo(); requestDismiss() } }
                        PopoverRowId.TOGGLE_HOME -> { { onToggleHome(); requestDismiss() } }
                        PopoverRowId.MORE -> onMore
                    }
                    add(PopoverRowSpec("row:$id", id.label(placed), id.icon(placed),
                        destructive = id == PopoverRowId.TOGGLE_HOME && placed, onClick = onClick))
                }
            }
        }
    }

    val below = if (measuredSize == Size.Zero) true
        else popoverOpensBelow(anchor, measuredSize.height, screenSize.height, marginPx)
    val alignLeft = if (measuredSize == Size.Zero) true
        else popoverAlignsLeft(anchor, measuredSize.width, paneRangeX?.endInclusive ?: screenSize.width, marginPx)
    val transformOrigin = TransformOrigin(if (alignLeft) 0f else 1f, if (below) 0f else 1f)

    Box(Modifier.fillMaxSize().testTag("icon-popover-scrim")
        .background(Color.Black.copy(alpha = .1f))
        .pointerInput(app.id) { detectTapGestures(onTap = { requestDismiss() }) }) {
        // The pressed icon's own "lifted" preview: same bounds as the real Home/dock icon, scaled
        // up with a soft shadow, drawn above the 10 % page dim so it reads as lifted rather than
        // dimmed along with everything else underneath.
        val liftScale by animateFloatAsState(if (reduceMotion) 1f else 1.15f, IosMenuSpring, label = "popover-icon-lift")
        Image(app.icon.asImageBitmap(), null,
            Modifier.offsetPx { IntOffset(anchor.left.toInt(), anchor.top.toInt()) }
                .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
                .graphicsLayer { scaleX = liftScale; scaleY = liftScale }
                .shadow(12.dp, LocalIconStyle.current.clipShape(), clip = false)
                .clip(LocalIconStyle.current.clipShape()))

        val offset = if (measuredSize == Size.Zero) IntOffset(anchor.left.toInt(), anchor.bottom.toInt())
            else popoverAnchorOffset(anchor, measuredSize, screenSize, marginPx, paneRangeX)
                .let { IntOffset(it.x.toInt(), it.y.toInt()) }
        Box(
            modifier = Modifier.offsetPx { offset }
                .width(250.dp).heightIn(max = 420.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.transformOrigin = transformOrigin }
                .onGloballyPositioned { measuredSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
                .clip(RoundedCornerShape(IosMenuCorner))
                .frostedGlass(corner = IosMenuCorner, tintAlpha = .78f,
                    fallback = Glass.copy(alpha = .96f),
                    veilColor = if (dark) Color.Black else Color.White,
                    border = IosHairlineColor)
                .pointerInput(Unit) { detectTapGestures { /* absorb: do not fall through to the scrim */ } }
                .testTag("icon-popover-${app.id}"),
        ) {
            LazyColumn {
                itemsIndexed(rows, key = { _, r -> r.key }) { index, spec ->
                    PopoverRow(spec)
                    if (index < rows.lastIndex) HorizontalDivider(thickness = IosHairline, color = IosHairlineColor)
                }
            }
        }
    }
}

/** Like `Modifier.offset { IntOffset }` but placing in *root*-local pixels rather than relative
 * to this node's own layout position — the popover positions itself the same way the drag ghost
 * does (LauncherScreen.kt), by absolute placement inside the shared root Box. */
private fun Modifier.offsetPx(offset: () -> IntOffset): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val o = offset()
        placeable.place(o.x, o.y)
    }
}
