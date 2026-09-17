package cz.pflanzer.foldduo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.pose.Panel
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Android Studio previews of the built-in Calendar card in every WidgetAppearance, side by side
 * on the day and the night dunes (the scene the launcher paints under its panes). The colours
 * come straight from widgetPalette(), the same call the live card makes, so a change to the
 * palette shows here without a device. Auto follows the ground (system dark = night). The ground
 * also provides a frosted backdrop built the way LauncherScreen builds it (FrostedBackdrop.kt),
 * so the Glass card shows the blurred dune crests through itself, the veil, the hairline and
 * the top highlight; the crest under the card lines up with the crest beside it.
 */
private val previewZone: ZoneId = ZoneId.of("Europe/Prague")
private val previewNow: LocalDateTime = LocalDateTime.of(2026, 9, 14, 9, 30)

private fun at(hour: Int, minute: Int): Long = previewNow.withHour(hour).withMinute(minute).atZone(previewZone).toInstant().toEpochMilli()

private val previewEvents = listOf(
    CalendarEvent(1, "Design review", at(10, 0), at(11, 0), allDay = false, color = 0xFF0A84FF.toInt()),
    CalendarEvent(2, "Lunch with Petra", at(12, 30), at(13, 30), allDay = false, color = 0xFF30D158.toInt()),
    CalendarEvent(3, "Flight to Vienna", at(18, 15), at(19, 30), allDay = false, color = 0xFFFF9F0A.toInt()),
)

/** Yellow, the preset that takes black ink, so Tinted shows the luminance-picked ink too. */
private val previewTint = WIDGET_TINT_PRESETS[5].first

/**
 * The dunes of [dark] filling this box, with [LocalFrostedBackdrop] set to their blurred copy
 * (built synchronously from the box's pixel size, the inner-panel scene) so frosted cards in
 * [content] work as on the device. The backdrop is keyed on the box's window position too, as
 * the live one is on the launcher root's.
 */
@Composable
private fun FrostedDuneGround(dark: Boolean, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val image = remember(dark, width, height) {
            runCatching { buildFrostedBackdrop(null, dark, Panel.Inner, width, height) }.getOrNull()?.asImageBitmap()
        }
        val backdrop = remember(image, origin, width, height) {
            image?.let { FrostedBackdrop(it, origin.x, origin.y, width.toFloat(), height.toFloat()) }
        }
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) { drawDunes(dark, Panel.Inner) }
        CompositionLocalProvider(LocalFrostedBackdrop provides backdrop) { content() }
    }
}

@Composable
private fun CalendarAppearanceStrip(dark: Boolean) {
    val palette = if (dark) DarkDuoPalette else LightDuoPalette
    DuoTheme(dark) {
        FrostedDuneGround(dark) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WidgetAppearance.entries.forEach { appearance ->
                    Column(Modifier.weight(1f)) {
                        Text(appearance.name, color = palette.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp))
                        Box(Modifier.fillMaxWidth().height(140.dp)) {
                            CalendarCard(WidgetSize.MEDIUM, previewNow, previewZone, previewEvents,
                                AppleWidgetColors.from(widgetPalette(appearance, previewTint, systemDark = dark)),
                                twentyFour = true, onClick = {})
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Calendar appearances, day dunes", widthDp = 1200, heightDp = 200, showBackground = false)
@Composable
private fun CalendarAppearancesDayPreview() = CalendarAppearanceStrip(dark = false)

@Preview(name = "Calendar appearances, night dunes", widthDp = 1200, heightDp = 200, showBackground = false)
@Composable
private fun CalendarAppearancesNightPreview() = CalendarAppearanceStrip(dark = true)

/** Glass over the dune crests: the card sits low on the scene where the crest lines run, so the blur is visible. */
@Preview(name = "Calendar Glass over dunes, medium", widthDp = 520, heightDp = 360, showBackground = false)
@Composable
private fun CalendarGlassOverDunesPreview() {
    DuoTheme(dark = false) {
        FrostedDuneGround(dark = false) {
            Box(Modifier.fillMaxSize().padding(start = 40.dp, top = 170.dp, end = 40.dp, bottom = 24.dp)) {
                CalendarCard(WidgetSize.MEDIUM, previewNow, previewZone, previewEvents,
                    AppleWidgetColors.from(widgetPalette(WidgetAppearance.Glass, null, systemDark = false)),
                    twentyFour = true, onClick = {})
            }
        }
    }
}

@Preview(name = "Calendar Glass, small tile, without calendar access", widthDp = 240, heightDp = 200, showBackground = false)
@Composable
private fun CalendarGlassSmallPreview() {
    DuoTheme(dark = true) {
        FrostedDuneGround(dark = true) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                CalendarCard(WidgetSize.SMALL, previewNow, previewZone, events = null,
                    AppleWidgetColors.from(widgetPalette(WidgetAppearance.Glass, null, systemDark = true)),
                    twentyFour = true, onClick = {}, eventsGranted = false)
            }
        }
    }
}
