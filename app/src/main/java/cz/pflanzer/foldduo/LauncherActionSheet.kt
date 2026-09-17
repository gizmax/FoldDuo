package cz.pflanzer.foldduo

import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Handles Back on the ComponentDialog which owns a Material modal sheet. A regular Compose
 * BackHandler sees the activity owner inherited by the sheet composition, while platform Back is
 * dispatched to the dialog first.
 */
@Composable
internal fun ModalDialogBackHandler(onBack: () -> Unit) {
    val localView = androidx.compose.ui.platform.LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBack by rememberUpdatedState(onBack)
    val dispatcherOwner = remember(localView) {
        val dialogWindow = (localView.parent as? DialogWindowProvider)?.window
        dialogWindow?.decorView?.findViewTreeOnBackPressedDispatcherOwner()
    }
    DisposableEffect(dispatcherOwner, lifecycleOwner) {
        val callback = object : OnBackPressedCallback(dispatcherOwner != null) {
            override fun handleOnBackPressed() = currentOnBack()
        }
        dispatcherOwner?.onBackPressedDispatcher?.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}

/*
 * iOS alert/sheet type language (2026-09-17 night, see IosStyle.kt): titles 17 sp semibold,
 * captions/messages 13 sp, destructive rows in iOS system red — applied here to [ActionRow] (used
 * by this file, PairTile.kt's pair menu and FolderPanel.kt's picker) and the sheet titles below.
 */

@Composable
internal fun LauncherAppActionSheet(app: AppEntry, placed: Boolean, homePages: Int,
    moving: Boolean, onMoving: (Boolean) -> Unit,
    onAddOrRemove: () -> Unit, onMoveFirst: () -> Unit, onMoveEarlier: () -> Unit, onMoveLater: () -> Unit,
    onMovePage: (Int) -> Unit, onInfo: () -> Unit, onWidgets: (() -> Unit)?, onCreateFolder: () -> Unit,
    onClose: () -> Unit,
    /** B49 "Quick look": the app's first widget provider, for the preview section below `onWidgets`. */
    widgetPreview: WidgetCatalogEntry? = null, widgetPreviewSpan: WidgetSpan = WidgetSpan(2, 2),
    onAddToToday: (() -> Unit)? = null) {
    ModalDialogBackHandler { if (moving) onMoving(false) else onClose() }
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * .88f).toDp() }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            if (moving) IconButton(onClick = { onMoving(false) }) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Image(app.icon.asImageBitmap(), null, Modifier.size(48.dp).clip(LocalIconStyle.current.clipShape()))
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
                Text(if (moving) "Move ${app.label}" else app.label, style = IosTitleStyle)
                Text("${app.profileLabel} profile", style = IosSectionLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close app options") }
        }
        if (moving) {
            ActionRow(Icons.Rounded.ArrowUpward, "Move to first position", onMoveFirst)
            ActionRow(Icons.Rounded.KeyboardArrowUp, "Move earlier", onMoveEarlier)
            ActionRow(Icons.Rounded.KeyboardArrowDown, "Move later", onMoveLater)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            repeat(homePages) { page -> ActionRow(Icons.Rounded.GridView, "Move to page ${page + 1}",
                { onMovePage(page) }, Modifier.testTag("app-move-${app.id}-page-$page")) }
            ActionRow(Icons.Rounded.Add, "New page",
                { onMovePage(homePages) }, Modifier.testTag("app-move-${app.id}-page-$homePages"))
        } else {
            if (placed) ActionRow(Icons.Rounded.DragIndicator, "Move on Home", { onMoving(true) })
            else ActionRow(Icons.Rounded.Home, "Add to Home", onAddOrRemove)
            onWidgets?.let { ActionRow(Icons.Rounded.Widgets, "Widgets", it) }
            if (widgetPreview != null) WidgetPreviewSection(widgetPreview, widgetPreviewSpan, onAddToToday)
            ActionRow(Icons.Rounded.CreateNewFolder, "Create folder", onCreateFolder)
            ActionRow(Icons.Rounded.Info, "App info", onInfo)
            if (placed) {
                Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                ActionRow(Icons.Rounded.RemoveCircleOutline, "Remove from Home", onAddOrRemove,
                    tint = IosDestructive)
            }
        }
    }
}

/**
 * B49 "Quick look": the app's first widget provider rendered at its own aspect ratio, scaled to
 * the sheet's width (same [WidgetProviderPreview] the full picker uses, `WidgetPicker.kt`), with
 * an "Add to Today" button. No temporary binding happens here — the button only pre-selects this
 * provider and opens the same picker/bind flow "Widgets" above does ([onAddToToday]).
 */
@Composable
private fun WidgetPreviewSection(entry: WidgetCatalogEntry, span: WidgetSpan, onAddToToday: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Widget preview", style = IosSectionLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ratio = span.width.toFloat() / span.height.coerceAtLeast(1)
            val height = maxWidth / ratio
            Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp))
                .testTag("app-sheet-widget-preview")) {
                WidgetProviderPreview(entry, span, Modifier.fillMaxSize())
            }
        }
        if (onAddToToday != null) Button(onClick = onAddToToday, modifier = Modifier.fillMaxWidth()
            .padding(top = 10.dp).testTag("app-sheet-add-to-today")) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("Add to Today")
        }
    }
}

@Composable
internal fun EmptySpaceActionSheet(onWidgets: () -> Unit, onWallpaper: () -> Unit,
    onCustomize: () -> Unit, onClose: () -> Unit) {
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * .75f).toDp() }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add to Home", Modifier.weight(1f), style = IosTitleStyle)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close empty space options") }
        }
        Text("Choose what belongs in this space.", style = IosSectionLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        ActionRow(Icons.Rounded.Widgets, "Widgets", onWidgets, Modifier.testTag("empty-space-widgets"))
        ActionRow(Icons.Rounded.Wallpaper, "Wallpaper", onWallpaper, Modifier.testTag("empty-space-wallpaper"))
        ActionRow(Icons.Rounded.Tune, "Customize launcher", onCustomize, Modifier.testTag("empty-space-customize"))
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = IosMenuRowHeight), color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            Spacer(Modifier.width(14.dp))
            Text(label, style = IosMenuRowStyle, color = if (tint == IosDestructive) tint else MaterialTheme.colorScheme.onSurface)
        }
    }
}
