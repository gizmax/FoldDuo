package cz.pflanzer.foldduo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StatusRail(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    iconSize: Dp = 40.dp,
    locationInUse: Boolean = false,
    /** 2026-09-17 noc "Stavová karta z railu": tapping the ring opens the status card overlay. */
    onOpenStatusCard: () -> Unit = {},
    /** True while the status card is open: the ring itself fades out (its parts are flying to
     * the card's tiles instead) rather than showing two copies of the same glyph at once. */
    cardOpen: Boolean = false,
    /** Root-relative bounds of the ring glyph, reported on every layout pass so the root-level
     * status card overlay can grow from the exact same place — mirrors the folder-open morph's
     * icon-bounds hand-off (`LayoutCoordinates.boundsInRoot()`, HomeDrag.kt's drop regions). */
    onRingBounds: (Rect) -> Unit = {},
) {
    trackRecomposition("StatusRail")
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_050L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val timeFormatter = remember(format) { DateTimeFormatter.ofPattern(format) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d") }
    val description = listOfNotNull(
        if (locationInUse) "Location in use" else null,
        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, $format")),
        status.battery?.let { "Battery $it percent${if (status.charging) ", charging" else ""}" } ?: "Battery unavailable",
        if (status.wifiConnected) "Wi-Fi connected${status.wifiLevel?.let { ", signal $it of 4" } ?: ""}" else "Wi-Fi disconnected",
        if (status.airplane) "Airplane mode" else status.cellularLevel?.let { "Cellular signal $it of 4" } ?: "Cellular signal unavailable",
    ).joinToString(". ")
    val fontScale = LocalDensity.current.fontScale
    // B29 "Barvy z tapety": light ink on a dark crop of the wallpaper, dark ink on a bright one
    // (paneInkColor samples the pane this rail is actually drawn on, not the whole picture); the
    // shadow flips the other way so it still reads as a contrast halo rather than more ink.
    val ink = paneInkColor()
    val inkShadow = if (ink.luminance() > .5f) Color.Black else Color.White
    val labelStyle = TextStyle(shadow = Shadow(inkShadow.copy(alpha = .3f), Offset(0f, 1f), 3f))
    val wifiVisual = wifiSignalVisual(status.wifiConnected, status.wifiLevel)
    val cellularVisual = cellularSignalVisual(status.cellularLevel, status.airplane)
    BoxWithConstraints(modifier.testTag("status-rail").semantics(mergeDescendants = true) { contentDescription = description }) {
        val availableWidth = (maxWidth - 4.dp).coerceAtLeast(28.dp)
        val visualSize = minOf(iconSize, availableWidth, if (compact) 40.dp else 52.dp)
        val timeSize = minOf(18f, availableWidth.value / (2.65f * fontScale)).sp
        val detailSize = minOf(11f, availableWidth.value / (3.45f * fontScale)).sp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Compact windows omit the reserve so status stays clear of the fixed dock.
            if (!compact) Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                // Callers currently leave this false; the slot waits for a truthful activity signal.
                if (locationInUse) Icon(Icons.Rounded.LocationOn, null, tint = ink,
                    modifier = Modifier.size(18.dp))
            }
            Text(now.format(timeFormatter), color = ink, fontSize = timeSize, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = labelStyle)
            if (!compact) Text(now.format(dateFormatter), color = ink.copy(alpha = .94f), fontSize = detailSize,
                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = labelStyle)
            val ringInteraction = remember { MutableInteractionSource() }
            Canvas(Modifier.size(visualSize)
                // "Matné sklo pro všechny pilulky" (17. 9. noc): the ring glyph had no glass
                // backing at all before — a bare circle painted straight on the wallpaper. Now it
                // gets the same blurred-wallpaper + veil every other pill/card gets, sized to a
                // full circle (corner == half the ring's own diameter).
                .glassPill(corner = visualSize / 2)
                .testTag("status-ring")
                .onGloballyPositioned { onRingBounds(it.boundsInRoot()) }
                .graphicsLayer { alpha = if (cardOpen) 0f else 1f }
                .clickable(
                    enabled = !cardOpen, interactionSource = ringInteraction, indication = null,
                    role = Role.Button, onClickLabel = "Status details", onClick = onOpenStatusCard,
                )) {
                val w = size.width
                val center = Offset(w / 2, w / 2)
                val radius = w * .44f
                val ringWidth = w * .072f
                val stroke = Stroke(width = ringWidth, cap = StrokeCap.Round)
                val arcSize = Size(radius * 2, radius * 2)
                val topLeft = Offset(center.x - radius, center.y - radius)
                drawArc(inkShadow.copy(alpha = .15f), 150f, 240f, false, topLeft, arcSize,
                    style = Stroke(width = ringWidth * 1.25f, cap = StrokeCap.Round))
                drawArc(ink.copy(alpha = .32f), 150f, 240f, false, topLeft, arcSize, style = stroke)
                status.battery?.let { drawArc(ink, 150f, 240f * it / 100, false, topLeft, arcSize, style = stroke) }
                // Wi-Fi glyph inside the battery arc; no fabricated bars for unknown readings.
                if (wifiVisual is WifiSignalVisual.Connected) {
                    for (i in 1..3) {
                        val r = w * (.12f + i * .067f)
                        val wifiTopLeft = Offset(center.x - r, w * .61f - r)
                        val wifiSize = Size(r * 2, r * 2)
                        drawArc(inkShadow.copy(alpha = .16f), 230f, 80f, false, wifiTopLeft, wifiSize,
                            style = Stroke(w * .073f, cap = StrokeCap.Round))
                        val strengthAlpha = signalAlpha(wifiVisual.elements[i])
                        drawArc(ink.copy(alpha = strengthAlpha),
                            230f, 80f, false, wifiTopLeft, wifiSize, style = Stroke(w * .058f, cap = StrokeCap.Round))
                    }
                    drawCircle(inkShadow.copy(alpha = .16f), w * .052f, Offset(center.x, w * .60f))
                    drawCircle(ink.copy(alpha = signalAlpha(wifiVisual.elements[0])),
                        w * .043f, Offset(center.x, w * .60f))
                } else {
                    drawLine(inkShadow.copy(alpha = .16f), Offset(w * .39f, w * .42f), Offset(w * .61f, w * .58f),
                        w * .073f, StrokeCap.Round)
                    drawLine(ink.copy(alpha = .75f), Offset(w * .39f, w * .42f), Offset(w * .61f, w * .58f),
                        w * .058f, StrokeCap.Round)
                }
                val activeDots = (cellularVisual as? CellularSignalVisual.Available)?.activeDots ?: 0
                for (i in 0..4) {
                    val angle = Math.toRadians((130 - i * 20).toDouble())
                    val lit = i < activeDots
                    val dotCenter = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                    drawCircle(inkShadow.copy(alpha = .14f), w * .052f, dotCenter)
                    drawCircle(ink.copy(alpha = if (lit) 1f else .3f), w * .043f, dotCenter)
                }
            }
            if (!compact) Text(if (status.airplane) "Airplane" else status.battery?.let { "$it%${if (status.charging) " +" else ""}" } ?: "—",
                color = ink.copy(alpha = .94f), fontSize = detailSize, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = labelStyle)
        }
    }
}

private fun signalAlpha(emphasis: SignalElementEmphasis): Float = when (emphasis) {
    SignalElementEmphasis.DIM -> .3f
    SignalElementEmphasis.NEUTRAL -> .62f
    SignalElementEmphasis.LIT -> 1f
}
