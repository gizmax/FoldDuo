package cz.pflanzer.foldduo.status

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.HapticEvent
import cz.pflanzer.foldduo.Haptics
import cz.pflanzer.foldduo.MotionPrefs
import cz.pflanzer.foldduo.glassPill
import cz.pflanzer.foldduo.openWifiPanel
import kotlinx.coroutines.delay

/**
 * "Status card" (2026-09-17 noc): tapping the rail's status ring unfolds it sideways into a glass
 * PILL, not a 2x2 card — Tom's device feedback on the first version: "při rozkliku Wi-Fi jsem si
 * představoval spíše pilulku stejné výšky, co vyjede doleva, a budou tam ty jednotlivé parametry."
 * Pure geometry/stagger math lives in StatusCardMotion.kt; this file is the Compose/Android half —
 * mirrors [cz.pflanzer.foldduo.island.RailIsland]'s `IslandExpandedOverlay` (grow-into-the-page
 * morph, spring `dampingRatio = 0.8`), but the pill's height/corner never animate at all (both are
 * frozen at the ring's own for the whole morph — see [statusPillShellRect]'s doc) — only the width
 * grows left from the ring, and the four parameter items slide/fade in from behind it.
 */

/** How long an open pill stays up without a touch before it folds back into the ring. */
const val STATUS_CARD_IDLE_TIMEOUT_MS = 8_000L

/** Duration of the ring<->pill morph when animated by time rather than a spring (stagger maths). */
private const val STATUS_CARD_MORPH_MS = 420

/** Duration of the reduce-motion crossfade (task spec: "Reduce motion -> crossfade"). */
private const val STATUS_CARD_REDUCED_MOTION_MS = 180

/** Fraction of the pill's own width a rightward swipe (back toward the rail) must cross to close it. */
private const val STATUS_CARD_SWIPE_CLOSE_FRACTION = 0.28f

/** How far (dp) a parameter item slides in from behind the ring as it fades in (task spec). */
private const val STATUS_PILL_ITEM_TRAVEL_DP = 16f

@Composable
fun StatusCardOverlay(
    anchor: StatusCardAnchor,
    open: Boolean,
    ink: Color,
    onDismiss: () -> Unit,
) {
    // The pill keeps drawing through its own collapse animation even after the caller has
    // already flipped `open` back to false (same "composed" trick as SeamPaletteOverlay /
    // SpotlightOverlay), so the closing spring/crossfade is not torn down mid-flight.
    var composed by remember { mutableStateOf(open) }
    val reduceMotion = MotionPrefs.enabled.value
    val progress = remember { Animatable(if (open) 1f else 0f) }
    // Reduce motion drops the spring/slide entirely and crossfades the whole pill instead
    // (task spec item 1); full motion always keeps this at 1 since the spring itself is the show.
    val overlayAlpha = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open, reduceMotion) {
        if (open) composed = true
        if (reduceMotion) {
            if (open) {
                // Snap straight to the fully open geometry, then crossfade it in.
                progress.snapTo(1f)
                overlayAlpha.animateTo(1f, tween(STATUS_CARD_REDUCED_MOTION_MS))
            } else {
                // Crossfade out at full size first, then collapse the (by then invisible) geometry —
                // avoids a visible jump-cut to ring size underneath the fade.
                overlayAlpha.animateTo(0f, tween(STATUS_CARD_REDUCED_MOTION_MS))
                progress.snapTo(0f)
            }
        } else {
            overlayAlpha.snapTo(1f)
            progress.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        }
        if (!open) composed = false
    }
    // 8 s idle auto-collapse (task spec): restarts on every open, cancelled by the effect above
    // the moment `open` flips (a fresh LaunchedEffect key cancels the previous coroutine).
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        delay(STATUS_CARD_IDLE_TIMEOUT_MS)
        onDismiss()
    }
    if (!composed) return
    val context = LocalContext.current
    val data = rememberStatusCardState(composed)

    val itemTexts = listOf(
        statusPillWifiText(data.wifiConnected, data.wifiSsid, data.wifiLinkSpeedMbps),
        statusPillMobileText(data.airplane, data.carrierName, data.networkTypeLabel),
        statusPillBatteryText(data.battery, data.charging, data.chargeTimeRemainingMs, data.dischargeEstimateMs),
        statusPillBluetoothText(data.bluetoothOn, data.bluetoothDeviceName),
    )
    val contentWidthDp = statusPillContentWidthDp(itemTexts)
    val pill = statusPillRect(anchor, contentWidthDp)
    val shell = statusPillShellRect(anchor, pill, progress.value)
    val totalDurationMs = STATUS_CARD_MORPH_MS.toLong()

    fun dismiss() {
        Haptics.play(context, HapticEvent.ISLAND_DISMISS)
        onDismiss()
    }

    Box(Modifier.fillMaxSize().testTag("status-card-overlay")) {
        // Tap-outside scrim: transparent, only intercepts touches while the pill is (at least
        // partway) open so it never eats input during the collapse's last frames.
        if (open) Box(Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
            .semantics { contentDescription = "Status card scrim" })
        BackHandler(enabled = open, onBack = ::dismiss)

        // The pill shell: the ring's own circle growing LEFT into a sideways stadium — height and
        // corner are frozen at the ring's own the whole time (statusPillShellRect's doc), so only
        // the width (hence the left edge) is ever animated here.
        Box(Modifier
            .graphicsLayer {
                translationX = shell.leftDp.dp.toPx(); translationY = shell.topDp.dp.toPx()
                alpha = overlayAlpha.value
            }
            .size(shell.widthDp.dp, shell.heightDp.dp)
            .clip(RoundedCornerShape(shell.cornerDp.dp))
            // "Matné sklo pro všechny pilulky" (17. 9. noc, Tom: "ta s Wi-Fi nějak nefunguje"): this
            // shell used a fixed tintAlpha, never reading IslandStyle.opacity at all — glassPill's
            // default `opacity` param reads the live setting, so the Wi-Fi/status pill now actually
            // reacts to the "Glass transparency" slider like every other pill/card.
            .glassPill(corner = shell.cornerDp.dp, baseVeilAlpha = .34f)
            .pointerInput(pill.widthDp) { detectStatusCardCloseSwipe(pill.widthDp) { dismiss() } }
            .testTag("status-card")) {
            StatusPillContent(
                data = data, ink = ink, progress = progress.value, totalDurationMs = totalDurationMs,
                onOpenWifi = { openWifiPanel(context); dismiss() },
                onOpenWifiSettings = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    dismiss()
                },
                onOpenMobile = {
                    val opened = runCatching { context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    dismiss()
                },
                onOpenBattery = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    dismiss()
                },
                onOpenBluetooth = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    dismiss()
                },
            )
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectStatusCardCloseSwipe(
    pillWidthDp: Float, onClose: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var dx = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        dx += change.positionChange().x
        if (!change.pressed) {
            val widthPx = pillWidthDp * density
            if (dx > widthPx * STATUS_CARD_SWIPE_CLOSE_FRACTION) onClose()
            break
        }
    }
}

/**
 * The row of four compact parameter items (task spec: "44 dp tall centred vertically, icon 20 dp
 * + one line of text 13 sp, hairline separators between items"), left to right per
 * [STATUS_PILL_ITEM_ORDER]. Always wrapped in a horizontal scroll — harmless when everything
 * fits, and the task spec's fallback ("scrolls horizontally inside, no wrapping") when it does
 * not, without needing the pure width estimate in StatusCardMotion.kt to be pixel-exact.
 */
@Composable
private fun StatusPillContent(
    data: StatusCardData,
    ink: Color,
    progress: Float,
    totalDurationMs: Long,
    onOpenWifi: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenMobile: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenBluetooth: () -> Unit,
) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxHeight().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(STATUS_PILL_END_PADDING_DP.dp))
        PillItem(StatusPillItem.WIFI, progress, totalDurationMs, ink, "status-pill-wifi",
            onClick = onOpenWifi, onLongClick = onOpenWifiSettings,
            icon = { WifiArcsGlyph(data.wifiConnected, data.wifiLevel, ink, 20.dp) },
            text = statusPillWifiText(data.wifiConnected, data.wifiSsid, data.wifiLinkSpeedMbps))
        HairlineSeparator(ink)
        PillItem(StatusPillItem.MOBILE, progress, totalDurationMs, ink, "status-pill-mobile",
            onClick = onOpenMobile,
            icon = { MobileBarsGlyph(data.airplane, mobileSignalBars(data.cellularLevel), ink, 20.dp) },
            text = statusPillMobileText(data.airplane, data.carrierName, data.networkTypeLabel))
        HairlineSeparator(ink)
        PillItem(StatusPillItem.BATTERY, progress, totalDurationMs, ink, "status-pill-battery",
            onClick = onOpenBattery,
            icon = { BatteryRingGlyph(data.battery, data.charging, ink, 20.dp) },
            text = statusPillBatteryText(data.battery, data.charging, data.chargeTimeRemainingMs, data.dischargeEstimateMs))
        HairlineSeparator(ink)
        PillItem(StatusPillItem.BLUETOOTH, progress, totalDurationMs, ink, "status-pill-bluetooth",
            onClick = onOpenBluetooth, onLongClick = onOpenBluetooth,
            icon = { Icon(if (data.bluetoothOn) Icons.Rounded.Bluetooth else Icons.Rounded.BluetoothDisabled, null, tint = ink, modifier = Modifier.size(20.dp)) },
            text = statusPillBluetoothText(data.bluetoothOn, data.bluetoothDeviceName))
        Spacer(Modifier.width(STATUS_PILL_ITEM_SPACING_DP.dp))
    }
}

/** One compact 44dp-tall item: icon, one line of 13sp text, sliding+fading in from behind the
 * ring per [item]'s own staggered progress ([statusPillItemProgress]/[statusPillItemTranslationDp]). */
@Composable
private fun PillItem(
    item: StatusPillItem,
    progress: Float,
    totalDurationMs: Long,
    ink: Color,
    testTag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
    text: String,
) {
    val t = statusPillItemProgress(item, progress, totalDurationMs)
    Row(Modifier
        .fillMaxHeight()
        .graphicsLayer {
            translationX = statusPillItemTranslationDp(t, STATUS_PILL_ITEM_TRAVEL_DP).dp.toPx()
            alpha = t
        }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
        .padding(horizontal = STATUS_PILL_ITEM_SPACING_DP.dp)
        .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(text, color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HairlineSeparator(ink: Color) {
    Box(Modifier
        .fillMaxHeight()
        .padding(vertical = 10.dp)
        .width(STATUS_PILL_SEPARATOR_DP.dp)
        .background(ink.copy(alpha = .22f)))
}

// --- Compact glyphs (20dp) for the pill's items -----------------------------------------------

@Composable
private fun WifiArcsGlyph(connected: Boolean, level: Int?, ink: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        if (!connected) {
            drawLine(ink.copy(alpha = .55f), Offset(w * .22f, w * .22f), Offset(w * .78f, w * .78f), w * .12f, StrokeCap.Round)
            return@Canvas
        }
        val center = Offset(w / 2f, w * .82f)
        val lit = (level ?: 0).coerceIn(0, 4)
        for (i in 1..3) {
            val r = w * (0.10f + i * 0.15f)
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2f, r * 2f)
            val litArc = i <= (lit + 1) / 2
            drawArc(ink.copy(alpha = if (litArc) 1f else .3f), 215f, 110f, false, topLeft, arcSize,
                style = Stroke(w * .11f, cap = StrokeCap.Round))
        }
        drawCircle(ink.copy(alpha = if (lit > 0) 1f else .3f), w * .08f, center)
    }
}

@Composable
private fun MobileBarsGlyph(airplane: Boolean, bars: Int, ink: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        if (airplane) {
            drawLine(ink.copy(alpha = .55f), Offset(w * .18f, w * .5f), Offset(w * .82f, w * .5f), w * .1f, StrokeCap.Round)
            return@Canvas
        }
        val barWidth = w * .16f
        val gap = w * .07f
        val startX = w * .04f
        for (i in 0 until 4) {
            val heightFrac = 0.34f + i * 0.2f
            val barHeight = w * heightFrac
            val x = startX + i * (barWidth + gap)
            drawRoundRect(ink.copy(alpha = if (i < bars) 1f else .3f),
                topLeft = Offset(x, w - barHeight), size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * .3f))
        }
    }
}

@Composable
private fun BatteryRingGlyph(battery: Int?, charging: Boolean, ink: Color, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = this.size.minDimension * .16f
            val inset = strokeWidth / 2f
            val arcSize = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            drawArc(ink.copy(alpha = .28f), -90f, 360f, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            val pct = (battery ?: 0).coerceIn(0, 100) / 100f
            if (pct > 0f) drawArc(ink, -90f, 360f * pct, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        }
        if (charging) Icon(Icons.Rounded.Bolt, null, tint = ink, modifier = Modifier.size(size * .55f))
    }
}
