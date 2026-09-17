@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cz.pflanzer.foldduo

import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B42 "Paleta ze švu": a horizontal drag starting within 24 dp of the hinge seam
 * ([seamPaletteEligibleStart]) and moving into a pane opens a glass palette anchored to that
 * pane's seam edge — quick toggles, the 6 most recent apps and the clipboard. Pure gesture/model
 * logic lives in SeamPaletteModel.kt (JVM-testable); this file is the Compose/Android glue.
 *
 * Gesture ownership: [Modifier.seamPaletteGesture] is placed *before* `onePageGestures` in
 * LauncherScreen.kt's modifier chain (both run their Initial-pass pointer handling outer-to-inner
 * for the same event), so once a down is inside the seam's hot zone this modifier consumes every
 * subsequent change immediately — `onePageGestures`'s own Initial-pass loop then sees
 * `change.isConsumed` and cancels itself (`cancelReason = "consumed"`), exactly like any other
 * modifier that claims a gesture ahead of it. A down outside the hot zone never consumes anything,
 * so ordinary paging is unaffected.
 */
@Composable
internal fun Modifier.seamPaletteGesture(
    seam: FoldSeam?,
    enabled: Boolean,
    reduceMotion: Boolean,
    paneWidthDp: (Pane) -> Float,
    onDrag: (pane: Pane, progress: Float) -> Unit,
    onRelease: (pane: Pane, commit: Boolean) -> Unit,
): Modifier {
    val currentSeam by rememberUpdatedState(seam)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentPaneWidthDp by rememberUpdatedState(paneWidthDp)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    return this.then(Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val seamNow = currentSeam
            if (!currentEnabled || seamNow == null) return@awaitEachGesture
            val startXDp = down.position.x / density
            if (!seamPaletteEligibleStart(startXDp, seamNow.xDp)) return@awaitEachGesture
            var pane: Pane? = null
            var resolved = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dxDp = (change.position.x - down.position.x) / density
                    val dyDp = (change.position.y - down.position.y) / density
                    if (pane == null) pane = seamPaletteTargetPane(dxDp, dyDp)
                    val activePane = pane
                    if (activePane != null) {
                        change.consume()
                        if (currentReduceMotion) {
                            resolved = true
                            currentOnRelease(activePane, true)
                            break
                        }
                        val paletteWidthDp = currentPaneWidthDp(activePane) * SEAM_PALETTE_WIDTH_FRACTION
                        currentOnDrag(activePane, seamPaletteDragProgress(dxDp, paletteWidthDp))
                    }
                    if (!change.pressed) {
                        if (activePane != null) {
                            resolved = true
                            val paletteWidthDp = currentPaneWidthDp(activePane) * SEAM_PALETTE_WIDTH_FRACTION
                            val progress = seamPaletteDragProgress(dxDp, paletteWidthDp)
                            currentOnRelease(activePane, seamPaletteShouldCommitOpen(progress))
                        }
                        break
                    }
                }
            } finally {
                // Lifted mid-resolution (e.g. a second pointer landed and cancelled the gesture)
                // without ever reaching the normal release above: treat it as "didn't open".
                if (pane != null && !resolved) currentOnRelease(pane!!, false)
            }
        }
    })
}

/**
 * The seam palette overlay itself: renders whenever [dragPane] (mid-drag, following the finger)
 * or [openPane] (committed) is non-null. Self-contained like `SpotlightOverlay` — it reads
 * `LocalFoldSeam`/`BoxWithConstraints` itself to find its own pane bounds, so LauncherScreen.kt
 * only needs this one call plus the gesture modifier above.
 */
@Composable
internal fun SeamPaletteOverlay(
    openPane: Pane?,
    dragPane: Pane?,
    dragProgress: Float,
    reduceMotion: Boolean,
    state: LauncherState,
    onDismiss: () -> Unit,
    onLaunchAdjacent: (AppEntry) -> Unit,
) {
    // The overlay keeps composing itself through the close fade even after the caller has
    // already cleared both openPane and dragPane (mirrors SpotlightOverlay's `composed` flag) —
    // otherwise the whole subtree, LaunchedEffect included, would be torn down mid-animation.
    var lastPane by remember { mutableStateOf(openPane ?: dragPane) }
    var composed by remember { mutableStateOf(lastPane != null) }
    val visible = openPane != null
    val progress = remember { Animatable(0f) }
    LaunchedEffect(openPane, dragPane, dragProgress, reduceMotion) {
        (openPane ?: dragPane)?.let { lastPane = it; composed = true }
        when {
            reduceMotion -> progress.snapTo(if (visible) 1f else 0f)
            visible -> progress.animateTo(1f, tween(180))
            dragPane != null -> progress.snapTo(dragProgress)
            else -> progress.animateTo(0f, tween(180))
        }
        if (openPane == null && dragPane == null) composed = false
    }
    if (!composed) return
    val pane = lastPane ?: return

    BoxWithConstraints(Modifier.fillMaxSize().testTag("seam-palette-overlay")) {
        val density = LocalDensity.current
        val safeLeftDp = with(density) { WindowInsets.safeDrawing.getLeft(this, LocalLayoutDirection.current).toDp().value }
        val seam = LocalFoldSeam.current?.let { it.copy(xDp = it.xDp - safeLeftDp) }?.takeIf { it.splits(maxWidth.value) }
        if (seam == null) return@BoxWithConstraints
        // Matches SpotlightOverlay's own self-contained bounds computation: the palette only
        // ever shows on the expanded/inner workspace, so state.expanded's dock width is the
        // right preset regardless of which scope this overlay call happens to sit in.
        val railWidth = railWidthDp(state.expanded.dockWidth)
        val bounds = paneBounds(pane, seam, maxWidth.value, railWidth)
        val paletteWidthDp = bounds.extentDp * SEAM_PALETTE_WIDTH_FRACTION
        // Anchored to the pane's seam edge: the Left pane's palette hugs its trailing (right)
        // edge, the Right pane's hugs its leading (left, seam-side) edge.
        val paletteX = if (pane == Pane.Left) bounds.endInclusive - paletteWidthDp else bounds.start

        if (visible) Box(Modifier.fillMaxSize()
            .pointerInput(pane) { detectTapGestures { onDismiss() } }
            .semantics { contentDescription = "Seam palette scrim" })

        Box(Modifier
            .align(Alignment.TopStart)
            .offset(x = paletteX.dp)
            .width(paletteWidthDp.dp)
            .fillMaxHeight()
            .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            // "Matné sklo pro všechny pilulky" (17. 9. noc): shared primitive, opacity-reactive.
            .glassPill(corner = 0.dp, baseVeilAlpha = .30f)
            // "Drag back … closes": a horizontal drag on the open palette itself, back toward
            // the seam past the same open threshold in reverse, closes it — independent of the
            // opening gesture above (that one only ever fires from *outside* the palette, in the
            // seam's own hot zone) and consumes nothing, so ordinary taps/clicks on the tiles
            // below still resolve normally.
            .pointerInput(pane, paletteWidthDp) { detectSeamPaletteCloseDrag(pane, paletteWidthDp, onDismiss) }
            .padding(16.dp)
            .testTag("seam-palette")) {
            SeamPaletteContent(state, onLaunchAdjacent)
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectSeamPaletteCloseDrag(
    pane: Pane, paletteWidthDp: Float, onDismiss: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        val dxDp = (change.position.x - down.position.x) / density
        // Toward the seam: negative dx closes a Right-pane palette, positive dx a Left-pane one.
        val towardSeam = if (pane == Pane.Right) dxDp < 0f else dxDp > 0f
        if (!change.pressed) {
            if (towardSeam && !seamPaletteShouldCommitOpen(1f - seamPaletteDragProgress(dxDp, paletteWidthDp))) onDismiss()
            break
        }
    }
}

@Composable
private fun SeamPaletteContent(state: LauncherState, onLaunchAdjacent: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Spacer(Modifier.height(8.dp))
        SeamPaletteToggleRow(context)
        Spacer(Modifier.height(20.dp))
        Text("Recent", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        SeamPaletteRecentApps(state, onLaunchAdjacent)
        Spacer(Modifier.height(20.dp))
        SeamPaletteClipboardRow(context)
    }
}

// --- Quick toggles -----------------------------------------------------------------------

@Composable
private fun SeamPaletteToggleRow(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeamPaletteToggleTile(Icons.Rounded.Wifi, "Wi-Fi", Modifier.weight(1f).testTag("seam-toggle-wifi")) { openWifiPanel(context) }
            SeamPaletteToggleTile(Icons.Rounded.Bluetooth, "Bluetooth", Modifier.weight(1f).testTag("seam-toggle-bluetooth")) { openBluetoothSettings(context) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeamPaletteFlashlightTile(context, Modifier.weight(1f))
            SeamPaletteDndTile(context, Modifier.weight(1f))
        }
        SeamPaletteToggleTile(Icons.Rounded.ScreenRotation, "Auto-rotate", Modifier.fillMaxWidth().testTag("seam-toggle-rotate")) { openAutoRotateSettings(context) }
        SeamPaletteBrightnessSlider()
    }
}

@Composable
internal fun SeamPaletteToggleTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier
        .clip(RoundedCornerShape(16.dp))
        .background(Color.White.copy(alpha = .12f))
        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
internal fun SeamPaletteFlashlightTile(context: Context, modifier: Modifier) {
    var on by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val torchId = flashlightCameraId(cameraManager)
        val callback = if (cameraManager != null && torchId != null) object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) on = enabled }
        } else null
        if (cameraManager != null && callback != null) cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { if (cameraManager != null && callback != null) cameraManager.unregisterTorchCallback(callback) }
    }
    Row(modifier
        .clip(RoundedCornerShape(16.dp))
        .background((if (on) Color.White.copy(alpha = .28f) else Color.White.copy(alpha = .12f)))
        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp))
        .clickable { setFlashlight(context, !on) }
        .testTag("seam-toggle-flashlight")
        .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.FlashlightOn, "Flashlight", tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Flashlight", color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
internal fun SeamPaletteDndTile(context: Context, modifier: Modifier) {
    var on by remember { mutableStateOf(dndEnabled(context)) }
    LifecycleResumeEffect(Unit) { on = dndEnabled(context); onPauseOrDispose { } }
    Row(modifier
        .clip(RoundedCornerShape(16.dp))
        .background(if (on) Color.White.copy(alpha = .28f) else Color.White.copy(alpha = .12f))
        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp))
        .clickable { on = toggleDnd(context, !on) }
        .testTag("seam-toggle-dnd")
        .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.DoNotDisturbOn, "Do Not Disturb", tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Do Not Disturb", color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun SeamPaletteBrightnessSlider() {
    val activity = LocalActivity.current
    var value by remember { mutableFloatStateOf(currentWindowBrightness(activity)) }
    Column(Modifier.fillMaxWidth().testTag("seam-brightness")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BrightnessMedium, "Brightness", tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Brightness", color = Color.White, fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = { value = it; setWindowBrightness(activity, it) },
            valueRange = 0.05f..1f, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .8f)))
    }
}

// --- Recent apps ---------------------------------------------------------------------------

@Composable
internal fun SeamPaletteRecentApps(state: LauncherState, onLaunchAdjacent: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val usageAccess = remember { hasUsageAccess(context) }
    val recentPackages = produceState(seamPaletteRecentPackages(emptyList(), state.recentLaunches), usageAccess, state.recentLaunches) {
        val usage = if (usageAccess) withContext(Dispatchers.Default) {
            runCatching { recentPackagesFromUsageStats(context) }.getOrDefault(emptyList())
        } else emptyList()
        value = seamPaletteRecentPackages(usage, state.recentLaunches)
    }.value
    val byPackage = remember(state.apps) { state.apps.associateBy { it.packageName } }
    val apps = recentPackages.mapNotNull { byPackage[it] }
    if (apps.isEmpty()) { Text("No recent apps", color = Color.White.copy(alpha = .6f), fontSize = 12.sp); return }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("seam-recent-apps")) {
        items(apps, key = { it.id }) { app ->
            Column(Modifier.width(56.dp).clickable { onLaunchAdjacent(app) }.testTag("seam-recent-app-${app.id}"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(app.icon.asImageBitmap(), null, Modifier.size(44.dp).clip(LocalIconStyle.current.clipShape()))
                Spacer(Modifier.height(4.dp))
                Text(app.label, color = Color.White, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

// --- Clipboard -----------------------------------------------------------------------------

@Composable
private fun SeamPaletteClipboardRow(context: Context) {
    var text by remember { mutableStateOf<String?>(null) }
    // Read only while our own window has focus (LifecycleResumeEffect: the overlay is only
    // composed while our activity is in front, so a resume of this effect is exactly that).
    LifecycleResumeEffect(Unit) {
        text = readPrimaryClipText(context)
        onPauseOrDispose { }
    }
    if (!seamPaletteClipboardVisible(text)) return
    val clip = text ?: return
    Column(Modifier.fillMaxWidth().testTag("seam-clipboard-row")) {
        Text("Clipboard", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = .12f))
            .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ContentPaste, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(clip, color = Color.White, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Row(Modifier.clickable { shareClipboardText(context, clip) }.testTag("seam-clipboard-share"),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Share, "Vložit do…", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Vložit do…", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

private fun readPrimaryClipText(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    seamPaletteClipboardText(clip.getItemAt(0).coerceToText(context))
}.getOrNull()

private fun shareClipboardText(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Vložit do…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// --- Adjacent launch (best effort) ------------------------------------------------------

/** Best-effort split placement: a plain Intent (not LauncherApps.startMainActivity, which has no
 * adjacent-launch option) with FLAG_ACTIVITY_LAUNCH_ADJACENT. One UI is free to ignore the flag. */
internal fun launchAppAdjacent(context: Context, app: AppEntry): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = app.component
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

// --- System toggles (best effort; every OEM/permission edge falls back to opening Settings) ---

internal fun openWifiPanel(context: Context) {
    val openedPanel = runCatching { context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!openedPanel) runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** No `Settings.Panel` constant exists for Bluetooth specifically; the full settings screen is the reliable fallback. */
private fun openBluetoothSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun flashlightCameraId(cameraManager: CameraManager?): String? = runCatching {
    cameraManager?.cameraIdList?.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
}.getOrNull()

private fun setFlashlight(context: Context, on: Boolean) {
    val cameraManager = context.getSystemService(CameraManager::class.java) ?: return
    val id = flashlightCameraId(cameraManager) ?: return
    runCatching { cameraManager.setTorchMode(id, on) }
}

private fun dndEnabled(context: Context): Boolean = runCatching {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
}.getOrDefault(false)

/** Toggles DND when policy access is granted; otherwise opens the grant screen and leaves state unchanged (returns the *actual* resulting state, not the requested one). */
private fun toggleDnd(context: Context, enable: Boolean): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return dndEnabled(context)
    if (!nm.isNotificationPolicyAccessGranted) {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return dndEnabled(context)
    }
    runCatching { nm.setInterruptionFilter(if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL) }
    return dndEnabled(context)
}

internal fun openAutoRotateSettings(context: Context) {
    // Toggling Settings.System.ACCELEROMETER_ROTATION directly needs WRITE_SETTINGS (a special
    // app-op grant); opening Display settings, where the toggle already lives, needs nothing.
    runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Read-only (matches [openAutoRotateSettings] not writing it); unused today but kept for a future live indicator. */
internal fun autoRotateEnabled(context: Context): Boolean = runCatching {
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}.getOrDefault(false)

/** Per-window brightness override (`WindowManager.LayoutParams.screenBrightness`): a normal app
 * may set this for its own window without WRITE_SETTINGS; it only affects the screen while this
 * activity is in front, not the system-wide setting. */
private fun currentWindowBrightness(activity: android.app.Activity?): Float {
    val current = activity?.window?.attributes?.screenBrightness ?: -1f
    return if (current in 0f..1f) current else 0.5f
}

private fun setWindowBrightness(activity: android.app.Activity?, value: Float) {
    val window = activity?.window ?: return
    runCatching { window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) } }
}
