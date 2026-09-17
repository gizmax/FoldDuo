package cz.pflanzer.foldduo

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/*
 * Built-in widgets in the visual language of iOS Home/Today widgets: 22 dp corners, no glass
 * border, a solid material card (or full-bleed photo), 16 dp content padding, large numerals in
 * the display styles and 11 sp uppercase section labels. Everything except the photo is drawn
 * with Compose (Canvas for dials and rings). Model and arithmetic live in BuiltinWidgets.kt,
 * Android data sources in WidgetData.kt.
 */

/**
 * Colours of one card, resolved from a [WidgetPalette] (BuiltinWidgets.kt). Auto follows the
 * system theme like iOS widgets do; Light/Dark pin it; Glass is the launcher's translucent card;
 * Tinted is a colour with ink picked by luminance.
 */
@Immutable
data class AppleWidgetColors(val card: Color, val label: Color, val secondary: Color, val tertiary: Color, val fill: Color,
    val accent: Color, val border: Color?, val darkInk: Boolean, val inkShadow: Boolean, val monochrome: Boolean,
    val frosted: Boolean = false) {
    companion object {
        fun from(palette: WidgetPalette) = AppleWidgetColors(Color(palette.card), Color(palette.label), Color(palette.secondary),
            Color(palette.tertiary), Color(palette.fill), Color(palette.accent), palette.border?.let(::Color), palette.darkInk,
            palette.inkShadow, palette.monochrome, palette.frosted)
        val Light get() = from(widgetPalette(WidgetAppearance.Light, null, systemDark = false))
        val Dark get() = from(widgetPalette(WidgetAppearance.Dark, null, systemDark = true))
    }
}

val AppleRed = Color(0xFFFF453A)
val AppleOrange = Color(0xFFFF9F0A)
val AppleBlue = Color(0xFF0A84FF)
private val AppleCorner = 22.dp

@Composable
fun appleWidgetColors(appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null): AppleWidgetColors {
    val dark = isSystemInDarkTheme()
    // Wallpaper (B29) ignores the placement's own tint and always follows the live accent.
    val effectiveTint = if (appearance == WidgetAppearance.Wallpaper) LocalWallpaperPalette.current.accent else tint
    return remember(appearance, effectiveTint, dark) { AppleWidgetColors.from(widgetPalette(appearance, effectiveTint, dark)) }
}

/**
 * The iOS card. [onClick] on the free area of the card; content rows may take their own taps.
 * Glass is frosted: the card draws the pre-blurred wallpaper slice under its own bounds
 * ([frostedGlass], FrostedBackdrop.kt) with the launcher hairline, and keeps white ink with a
 * soft shadow so it survives a bright wallpaper. A RenderEffect on this layer would blur the
 * card's own content, not what lies behind it, hence the pre-blurred copy; without one (first
 * frame) the glass is the plain translucent fill.
 */
@Composable
fun AppleCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, colors: AppleWidgetColors = appleWidgetColors(),
    contentPadding: Dp = 16.dp, content: @Composable BoxScope.() -> Unit) {
    val frost = if (colors.frosted) Modifier.frostedGlass(AppleCorner, fallback = colors.card, border = colors.border) else Modifier
    Surface(modifier.fillMaxSize().clip(RoundedCornerShape(AppleCorner)).then(frost)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = if (colors.frosted) Color.Transparent else colors.card, shape = RoundedCornerShape(AppleCorner), contentColor = colors.label,
        border = if (colors.frosted) null else colors.border?.let { BorderStroke(1.dp, it) }) {
        val style = if (colors.inkShadow) LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = .35f), Offset(0f, 1f), 3f))
            else LocalTextStyle.current
        ProvideTextStyle(style) { Box(Modifier.fillMaxSize().padding(contentPadding), content = content) }
    }
}

/**
 * ARGB "Match wallpaper" tint: the dominant colour of the current background photo, sampled from
 * a 32 x 32 copy off the main thread; without a photo the Duo dunes sky of the active palette.
 */
@Composable
fun rememberWallpaperTint(): Int {
    val context = LocalContext.current
    val fallback = LocalDuoPalette.current.backgroundTop.toArgb()
    val revision = LauncherBackgroundCache.revision.intValue
    val sampled by produceState<Int?>(null, revision) {
        val source = cachedLauncherBackground(context)
        value = if (source == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val small = Bitmap.createScaledBitmap(source, 32, 32, true)
                val pixels = IntArray(small.width * small.height)
                small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                if (small !== source) small.recycle()
                dominantColorArgb(pixels)
            }.getOrNull()
        }
    }
    return sampled ?: fallback
}

/** 11 sp uppercase section label with letter spacing, like "SUNDAY" or "BATTERIES". */
@Composable
fun AppleSectionLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = .8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Host for a built-in id: measures the card and picks the iOS size class for it, so the same
 * composable serves a Home 2 x 2 tile, a 4 x 2 tile and full-width Today items. B40 adds a
 * second, orthogonal axis on top of [size]: [WidgetContentVariant] trims content by measured dp
 * height alone (never by which panel this is — cover vs inner only ever differ in the height
 * they hand this box), with [widgetContentVariantFor]'s hysteresis remembered across
 * recompositions so a resize or panel-swap animation settling near the boundary does not flap
 * between Compact and Regular every frame.
 */
@Composable
fun BuiltinAppleWidget(id: Int, onClick: () -> Unit, modifier: Modifier = Modifier,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val size = widgetSizeFor(maxWidth.value, maxHeight.value)
        var lastVariant by remember(id) { mutableStateOf<WidgetContentVariant?>(null) }
        val variant = widgetContentVariantFor(maxHeight.value, lastVariant)
        SideEffect { lastVariant = variant }
        when (id) {
            CLOCK_ANALOG_WIDGET -> AnalogClockWidget(size, onClick, appearance = appearance, tint = tint, variant = variant)
            CALENDAR_WIDGET -> CalendarWidget(size, onClick, appearance = appearance, tint = tint, variant = variant)
            BATTERIES_WIDGET -> BatteriesWidget(size, onClick, compact = variant == WidgetContentVariant.Compact,
                appearance = appearance, tint = tint)
            PHOTOS_WIDGET -> PhotosWidget(size, onClick, appearance = appearance, tint = tint, variant = variant)
            else -> DigitalClockWidget(size, onClick, withDate = size != WidgetSize.SMALL && variant == WidgetContentVariant.Regular,
                appearance = appearance, tint = tint)
        }
    }
}

// --- Clock ------------------------------------------------------------------------------

/** Wall-clock time, ticking once a second only while the host is resumed and the card composed. */
@Composable
fun rememberClockTime(): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = LocalDateTime.now()
                delay(1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }
}

@Composable
private fun timePattern(): String =
    if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"

/** The iOS World Clock dial: black or white face, 60 ticks, hour and minute hands, orange second hand. */
@Composable
fun AnalogClockDial(time: LocalDateTime, modifier: Modifier = Modifier, dark: Boolean = isSystemInDarkTheme()) {
    val face = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val ink = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val angles = handAngles(time.hour, time.minute, time.second)
    Canvas(modifier.semantics { contentDescription = "Analog clock" }) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(face, radius, center)
        drawCircle(if (dark) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .08f), radius - .5.dp.toPx(), center,
            style = Stroke(1.dp.toPx()))
        for (tick in 0 until 60) {
            val major = tick % 5 == 0
            rotate(tick * 6f, center) {
                drawLine(if (major) ink else ink.copy(alpha = .35f),
                    Offset(center.x, center.y - radius + radius * .06f),
                    Offset(center.x, center.y - radius + radius * (if (major) .17f else .11f)),
                    strokeWidth = (if (major) radius * .035f else radius * .015f).coerceAtLeast(1f), cap = StrokeCap.Round)
            }
        }
        drawHand(angles.hour, radius * .52f, radius * .06f, ink, center, tail = radius * .1f)
        drawHand(angles.minute, radius * .78f, radius * .045f, ink, center, tail = radius * .1f)
        drawHand(angles.second, radius * .84f, (radius * .018f).coerceAtLeast(1f), AppleOrange, center, tail = radius * .2f)
        drawCircle(AppleOrange, radius * .055f, center)
        drawCircle(face, radius * .02f, center)
    }
}

private fun DrawScope.drawHand(angle: Float, length: Float, width: Float, color: Color, center: Offset, tail: Float) {
    rotate(angle, center) {
        drawLine(color, Offset(center.x, center.y + tail), Offset(center.x, center.y - length), width, StrokeCap.Round)
    }
}

/**
 * Analog clock card. Medium: dial on the left, digital time, date and city on the right.
 * Small: the dial with the city under it. Large: a bigger dial with the same right column.
 */
@Composable
fun AnalogClockWidget(size: WidgetSize, onClick: () -> Unit, modifier: Modifier = Modifier,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null,
    /** B40: Compact drops the weekday/date line next to the dial, keeping only the time. */
    variant: WidgetContentVariant = WidgetContentVariant.Regular) {
    val time by rememberClockTime()
    val colors = appleWidgetColors(appearance, tint)
    val city = remember { timeZoneCity(TimeZone.getDefault().id) }
    val pattern = timePattern()
    AppleCard(modifier.testTag("apple-clock"), onClick, colors, contentPadding = if (size == WidgetSize.SMALL) 12.dp else 16.dp) {
        if (size == WidgetSize.SMALL) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            AnalogClockDial(time, Modifier.weight(1f).fillMaxWidth().padding(2.dp), dark = !colors.darkInk)
            AppleSectionLabel(city, colors.secondary, Modifier.padding(top = 6.dp))
        } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            AnalogClockDial(time, Modifier.fillMaxHeight().aspectRatio1(), dark = !colors.darkInk)
            Column(Modifier.padding(start = 20.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                AppleSectionLabel(city, colors.secondary)
                Text(time.format(DateTimeFormatter.ofPattern(pattern)), color = colors.label, maxLines = 1,
                    style = if (size == WidgetSize.LARGE) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Medium, letterSpacing = (-1).sp, modifier = Modifier.testTag("apple-clock-time"))
                if (variant == WidgetContentVariant.Regular) Text(time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    color = colors.secondary, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun Modifier.aspectRatio1() = aspectRatio(1f)

/** The digital clock (`CLOCK_WIDGET`) restyled to the same card: city label, big time, date. */
@Composable
fun DigitalClockWidget(size: WidgetSize, onClick: () -> Unit, withDate: Boolean, modifier: Modifier = Modifier,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null) {
    val time by rememberClockTime()
    val colors = appleWidgetColors(appearance, tint)
    val city = remember { timeZoneCity(TimeZone.getDefault().id) }
    val pattern = timePattern()
    AppleCard(modifier.testTag("apple-digital-clock"), onClick, colors) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            AppleSectionLabel(city, colors.secondary)
            Text(time.format(DateTimeFormatter.ofPattern(pattern)), color = colors.label, maxLines = 1,
                style = when (size) {
                    WidgetSize.SMALL -> MaterialTheme.typography.headlineLarge
                    WidgetSize.MEDIUM -> MaterialTheme.typography.displayMedium
                    WidgetSize.LARGE -> MaterialTheme.typography.displayLarge
                }, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
            if (withDate) Text(time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = colors.secondary,
                fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("clock-card-date"))
            else Text("Local time", color = colors.secondary, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1)
        }
    }
}

// --- Calendar ---------------------------------------------------------------------------

/**
 * Calendar card: red weekday, big date numeral and the next events (title, time, colour dot)
 * from the device calendar. Without READ_CALENDAR a one-line hint requests it on tap; the date
 * opens the calendar app at today, an event at its start. The data lives here, the drawing in
 * [CalendarCard], so previews and tests can render the card without the provider.
 */
@Composable
fun CalendarWidget(size: WidgetSize, onClick: () -> Unit, modifier: Modifier = Modifier,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null,
    /** B40: Compact shows 2 events instead of 4 (6 instead of 8 for a Large/ExtraLarge card). */
    variant: WidgetContentVariant = WidgetContentVariant.Regular) {
    val context = LocalContext.current
    val colors = appleWidgetColors(appearance, tint)
    val now by rememberClockTime()
    val permission = rememberPermissionRequest(Manifest.permission.READ_CALENDAR)
    val instances by rememberCalendarInstances(permission.granted)
    val zone = remember { ZoneId.systemDefault() }
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val limit = calendarEventLimit(size, variant)
    val events = remember(instances, nowMillis / 60_000L, limit) {
        instances?.let { upcomingEvents(it, nowMillis, zone, limit) }
    }
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    CalendarCard(size, now, zone, events, colors, twentyFour, onClick, modifier, variant = variant,
        eventsGranted = permission.granted, onRequestEvents = { permission.request() },
        onOpenDay = { openCalendarAt(context, nowMillis) },
        onOpenEvent = { event -> openCalendarAt(context, event.begin, event.id) })
}

/**
 * The calendar card with everything it shows passed in: [now] in [zone], the [events] to list
 * (null while loading), [colors] from [appleWidgetColors] and whether READ_CALENDAR is
 * [eventsGranted]. Taps: [onClick] on the free card area, [onOpenDay] on the date,
 * [onOpenEvent] on an event row and [onRequestEvents] on the permission hint.
 */
@Composable
fun CalendarCard(size: WidgetSize, now: LocalDateTime, zone: ZoneId, events: List<CalendarEvent>?, colors: AppleWidgetColors,
    twentyFour: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, eventsGranted: Boolean = true,
    onRequestEvents: () -> Unit = {}, onOpenDay: () -> Unit = {}, onOpenEvent: (CalendarEvent) -> Unit = {},
    variant: WidgetContentVariant = WidgetContentVariant.Regular) {
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val limit = calendarEventLimit(size, variant)
    AppleCard(modifier.testTag("apple-calendar"), onClick, colors) {
        if (size == WidgetSize.SMALL) Column(Modifier.fillMaxSize()) {
            CalendarDateHeader(now, colors, onOpenDay)
            Spacer(Modifier.height(8.dp))
            CalendarEvents(events, eventsGranted, onRequestEvents, onOpenEvent, nowMillis, zone, twentyFour, colors, limit)
        } else Row(Modifier.fillMaxSize()) {
            Column(Modifier.widthIn(min = 96.dp)) { CalendarDateHeader(now, colors, onOpenDay) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Top) {
                CalendarEvents(events, eventsGranted, onRequestEvents, onOpenEvent, nowMillis, zone, twentyFour, colors, limit)
            }
        }
    }
}

/**
 * B40: how many events the calendar card fetches/shows. A Small tile is a single narrow column
 * regardless of its measured height, so it is always Compact; everything else follows
 * [variant]. Large/ExtraLarge doubles both figures (Regular 8, Compact 4) since it has whole
 * extra rows to spend, not just a few more px.
 */
private fun calendarEventLimit(size: WidgetSize, variant: WidgetContentVariant): Int {
    val compact = size == WidgetSize.SMALL || variant == WidgetContentVariant.Compact
    return when {
        size == WidgetSize.LARGE -> if (compact) 4 else 8
        else -> if (compact) 2 else 4
    }
}

@Composable
private fun CalendarDateHeader(now: LocalDateTime, colors: AppleWidgetColors, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).testTag("apple-calendar-date")) {
        AppleSectionLabel(now.format(DateTimeFormatter.ofPattern("EEEE")), colors.accent)
        Text(now.dayOfMonth.toString(), color = colors.label, style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium, letterSpacing = (-1).sp, lineHeight = 40.sp)
    }
}

@Composable
private fun CalendarEvents(events: List<CalendarEvent>?, granted: Boolean, onRequest: () -> Unit, onOpen: (CalendarEvent) -> Unit,
    now: Long, zone: ZoneId, twentyFour: Boolean, colors: AppleWidgetColors, limit: Int) {
    when {
        !granted -> Text("Tap to show your events", color = colors.secondary, fontSize = 13.sp, lineHeight = 16.sp,
            maxLines = 1, modifier = Modifier.clickable(onClick = onRequest).testTag("apple-calendar-permission"))
        events == null -> Unit
        events.isEmpty() -> Text("No more events today", color = colors.secondary, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1)
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            events.take(limit).forEach { event ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .clickable { onOpen(event) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(event.color?.let { Color(it) } ?: AppleBlue, CircleShape))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(event.title, color = colors.label, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatEventTime(event, now, zone, twentyFour), color = colors.secondary, fontSize = 12.sp,
                            lineHeight = 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// --- Batteries --------------------------------------------------------------------------

/** One ring: track plus a coloured sweep, with the percent (or a bolt while charging) inside. */
@Composable
fun BatteryRing(device: DeviceBattery, diameter: Dp, colors: AppleWidgetColors, modifier: Modifier = Modifier) {
    val ring = if (colors.monochrome) colors.label else Color(ringColorArgb(device.level, device.charging))
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size.minDimension * .12f).coerceIn(3f, 14f)
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(colors.fill, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(ring, -90f, ringSweep(device.level), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (device.charging) Icon(Icons.Rounded.Bolt, "Charging", tint = ring, modifier = Modifier.size(diameter * .42f))
        else Icon(device.kind.icon(), null, tint = colors.label, modifier = Modifier.size(diameter * .38f))
    }
}

private fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.PHONE -> Icons.Rounded.Smartphone
    DeviceKind.HEADPHONES -> Icons.Rounded.Headphones
    DeviceKind.WATCH -> Icons.Rounded.Watch
    DeviceKind.OTHER -> Icons.Rounded.Bluetooth
}

/**
 * Batteries card: the phone plus connected Bluetooth devices with a readable level. Without
 * BLUETOOTH_CONNECT only the phone shows and a tap asks for the permission. [compact] is the
 * one-row Today strip: rings in a row with the percent beside each.
 */
@Composable
fun BatteriesWidget(size: WidgetSize, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null) {
    val colors = appleWidgetColors(appearance, tint)
    val phone by rememberPhoneBattery()
    val bluetooth = rememberPermissionRequest(Manifest.permission.BLUETOOTH_CONNECT)
    val others by rememberBluetoothBatteries(bluetooth.granted)
    val devices = listOf(phone) + others
    val shown = devices.take(when { compact -> 5; size == WidgetSize.SMALL -> 2; else -> 4 })
    val tap: () -> Unit = if (bluetooth.granted) onClick else ({ bluetooth.request() })
    AppleCard(modifier.testTag("apple-batteries"), tap, colors, contentPadding = if (compact) 12.dp else 16.dp) {
        if (compact) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AppleSectionLabel("Batteries", colors.secondary, Modifier.width(72.dp))
            shown.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryRing(device, 40.dp, colors)
                    Text("${device.level} %", color = colors.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
            if (!bluetooth.granted) Text("Tap for Bluetooth devices", color = colors.secondary, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.testTag("apple-batteries-permission"))
        } else Column(Modifier.fillMaxSize()) {
            AppleSectionLabel("Batteries", colors.secondary)
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                shown.forEach { device ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        BatteryRing(device, if (size == WidgetSize.SMALL) 56.dp else 64.dp, colors)
                        Text("${device.level} %", color = colors.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                        Text(device.name, color = colors.secondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!bluetooth.granted) Text("Tap to add Bluetooth devices", color = colors.secondary, fontSize = 12.sp,
                lineHeight = 14.sp, maxLines = 1, modifier = Modifier.testTag("apple-batteries-permission"))
        }
    }
}

// --- Photos -----------------------------------------------------------------------------

/**
 * Photos card, the iPhone Duo hero: one full-bleed picture with a soft bottom gradient, the
 * memory title ("On This Day" for a picture from an earlier year, else "Recents") and its date
 * bottom-left, and a small circular play affordance bottom-right that opens the gallery. Large
 * fills the whole Today pane; small and medium are the Home tiles. Before READ_MEDIA_IMAGES is
 * granted the same card is a pastel placeholder that asks for the permission on tap. The picture
 * is full-bleed, so of the appearance only a Tinted card shows: its colour replaces the black of
 * the bottom gradient.
 */
@Composable
fun PhotosWidget(size: WidgetSize, onClick: () -> Unit, modifier: Modifier = Modifier,
    appearance: WidgetAppearance = WidgetAppearance.Auto, tint: Int? = null,
    /** B40: Compact drops the "On This Day"/"Recents" title, keeping just the date — one photo either way. */
    variant: WidgetContentVariant = WidgetContentVariant.Regular) {
    val context = LocalContext.current
    val colors = appleWidgetColors(appearance, tint)
    val overlay = if (appearance == WidgetAppearance.Tinted) colors.card.copy(alpha = 1f) else Color.Black
    val permission = rememberPermissionRequest(Manifest.permission.READ_MEDIA_IMAGES)
    val access = permission.granted || hasPhotoAccess(context)
    val photo by rememberLatestPhoto(access)
    val zone = remember { ZoneId.systemDefault() }
    val openGallery: () -> Unit = {
        val current = photo
        if (current != null) runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(current.uri, "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
    val tap: () -> Unit = when {
        !access -> ({ permission.request() })
        photo != null -> openGallery
        else -> onClick
    }
    val pad = if (size == WidgetSize.SMALL) 12.dp else 16.dp
    AppleCard(modifier.testTag("apple-photos"), tap, colors, contentPadding = 0.dp) {
        val current = photo
        if (current != null) {
            Image(current.bitmap.asImageBitmap(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent,
                1f to overlay.copy(alpha = if (overlay == Color.Black) .6f else .75f))))
            val now = System.currentTimeMillis()
            Column(Modifier.align(Alignment.BottomStart).padding(pad).padding(end = if (size == WidgetSize.SMALL) 0.dp else 56.dp)) {
                if (size != WidgetSize.SMALL && variant == WidgetContentVariant.Regular) Text(photoTitle(current.takenMillis, now, zone), color = Color.White,
                    style = if (size == WidgetSize.LARGE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("apple-photos-title"))
                Text(Instant.ofEpochMilli(current.takenMillis).atZone(zone)
                    .format(DateTimeFormatter.ofPattern(if (size == WidgetSize.SMALL) "MMM d" else "EEEE, MMMM d, yyyy")),
                    color = Color.White.copy(alpha = .85f), fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, modifier = Modifier.testTag("apple-photos-date"))
            }
            if (size != WidgetSize.SMALL) Box(Modifier.align(Alignment.BottomEnd).padding(pad).size(40.dp)
                .background(Color.White.copy(alpha = .28f), CircleShape).clickable(onClick = openGallery)
                .testTag("apple-photos-play"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Open in gallery", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        } else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFBFD8F0), Color(0xFFE9D3F2), Color(0xFFF6D9C8)))),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Photo, null, tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(36.dp))
                Text(if (access) "No recent photos" else "Tap to show your photos", color = Color(0xFF3C3C43).copy(alpha = .75f),
                    fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp).testTag("apple-photos-permission"))
            }
        }
    }
}
