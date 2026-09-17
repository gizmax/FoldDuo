package cz.pflanzer.foldduo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cz.pflanzer.foldduo.pose.HeadingSource
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * B49 "Živé ikony + náhled": composable overlays drawn OVER an already-baked icon bitmap for a
 * handful of known system packages — real analog clock hands, today's date, a battery ring, a
 * compass needle. Never baked into IconRenderer's cached 144 px bitmap: that bitmap is shared by
 * every surface (Home, dock, folders, App Library, Spotlight — IconRenderer.kt's own doc), and a
 * per-second repaint there would repaint the icon everywhere at once, including places (App
 * Library rows, Spotlight results) that never asked for a live face. Instead AppTile/
 * DockAppColumn/FolderTile draw the baked bitmap first, exactly as before, then this overlay on
 * top, clipped to the same icon shape.
 *
 * Weather (`com.sec.android.daemonapp`) has no overlay: the launcher has no weather data source
 * of its own (BuiltinWidgets.kt/AppleWidgets.kt cover Clock/Calendar/Batteries/Photos only; the
 * Samsung Weather app is host-widget-only, ZeroPaddingWidgetHost.kt), so per the task's own
 * fallback instruction it is skipped rather than faked.
 */

enum class LiveIconKind { Clock, Calendar, Battery, Maps }

private val CLOCK_PACKAGES = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock")
private val CALENDAR_PACKAGES = setOf("com.samsung.android.calendar", "com.google.android.calendar")
private val BATTERY_PACKAGES = setOf("com.samsung.android.lool")
private val MAPS_PACKAGES = setOf("com.google.android.apps.maps")

/** Which live overlay (if any) a package's icon gets. Pure package -> kind mapping, B49. */
fun liveIconKindFor(packageName: String): LiveIconKind? = when (packageName) {
    in CLOCK_PACKAGES -> LiveIconKind.Clock
    in CALENDAR_PACKAGES -> LiveIconKind.Calendar
    in BATTERY_PACKAGES -> LiveIconKind.Battery
    in MAPS_PACKAGES -> LiveIconKind.Maps
    else -> null
}

/**
 * The Samsung/Google Calendar app icon already bakes in today's date (a platform "dynamic app
 * icon"); this launcher only needs to draw its own date when that baked-in artwork is gone —
 * i.e. an icon pack replaced the artwork, or an icon effect (Glass/Clear) redrew it as a flat
 * glyph tile. Plain system artwork (no pack, no effect) already shows the real date, so the
 * overlay would double it up there.
 */
fun showsCalendarOverlay(style: IconStyle): Boolean = style.pack != null || style.effect != IconEffect.None

/**
 * Hand angles for the clock overlay: [handAngles] (`WidgetData.kt`), except the second hand is
 * pinned to 0 (no rotation drawn for it) while [reduceMotion] is on, so a still icon never
 * animates.
 */
fun clockOverlayAngles(hour: Int, minute: Int, second: Int, reduceMotion: Boolean): HandAngles =
    handAngles(hour, minute, if (reduceMotion) 0 else second)

/** 0..1 fill fraction of the battery ring overlay for a 0..100 level, clamped. */
fun batteryOverlayFraction(level: Int): Float = level.coerceIn(0, 100) / 100f

/** Weekday abbreviation for the calendar overlay, uppercase in [locale]'s own short form ("MON", "PO", …). */
fun calendarOverlayWeekday(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern("EEE", locale)).uppercase(locale)

/**
 * Draws the live overlay for [packageName]'s icon, if it has one, clipped to [shape] and sized to
 * fill [modifier]'s bounds (callers pass `Modifier.matchParentSize()` over the baked icon
 * `Image`). No-ops for every other package, and while the "Live icons" setting
 * ([IconStyle.liveIcons]) is off.
 */
@Composable
fun LiveIconOverlay(packageName: String, shape: Shape, modifier: Modifier = Modifier) {
    val kind = remember(packageName) { liveIconKindFor(packageName) } ?: return
    val style = LocalIconStyle.current
    if (!style.liveIcons) return
    val reduceMotion by MotionPrefs.enabled
    val clipped = modifier.clip(shape)
    when (kind) {
        LiveIconKind.Clock -> ClockOverlay(clipped, reduceMotion)
        LiveIconKind.Calendar -> if (showsCalendarOverlay(style)) CalendarOverlay(clipped)
        LiveIconKind.Battery -> BatteryOverlay(clipped)
        LiveIconKind.Maps -> MapsOverlay(clipped, reduceMotion)
    }
}

// --- Clock ------------------------------------------------------------------------------

@Composable
private fun ClockOverlay(modifier: Modifier, reduceMotion: Boolean) {
    val time by rememberClockTime()
    val ink = Color.Black.copy(alpha = .82f)
    val accent = AppleOrange
    Canvas(modifier) {
        val angles = clockOverlayAngles(time.hour, time.minute, time.second, reduceMotion)
        val radius = size.minDimension / 2f * .60f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawOverlayHand(angles.hour, radius * .50f, radius * .13f, ink, center)
        drawOverlayHand(angles.minute, radius * .82f, radius * .09f, ink, center)
        if (!reduceMotion) drawOverlayHand(angles.second, radius * .92f, radius * .04f, accent, center)
        drawCircle(ink, radius * .07f, center)
    }
}

private fun DrawScope.drawOverlayHand(angleDeg: Float, length: Float, width: Float, color: Color, center: Offset) {
    rotate(angleDeg, center) {
        drawLine(color, center, Offset(center.x, center.y - length), width, StrokeCap.Round)
    }
}

// --- Calendar ---------------------------------------------------------------------------

/** Today's date, refreshed once at each local midnight while resumed (no need to tick faster). */
@Composable
private fun rememberOverlayToday(): State<LocalDate> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDate.now()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = LocalDate.now()
                val zone = ZoneId.systemDefault()
                val next = value.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                delay((next - System.currentTimeMillis()).coerceAtLeast(1_000L))
            }
        }
    }
}

@Composable
private fun CalendarOverlay(modifier: Modifier) {
    val today by rememberOverlayToday()
    val locale = Locale.getDefault()
    val weekday = remember(today, locale) { calendarOverlayWeekday(today, locale) }
    val day = remember(today) { today.dayOfMonth.toString() }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Color(0xFFE0483F), size = androidx.compose.ui.geometry.Size(w, h * .30f))
        drawContext.canvas.nativeCanvas.apply {
            val weekdayPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE; isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = h * .15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawText(weekday, w / 2f, h * .21f, weekdayPaint)
            val dayPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK; isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = h * .46f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val metrics = dayPaint.fontMetrics
            val baseline = h * .30f + (h * .70f - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            drawText(day, w / 2f, baseline, dayPaint)
        }
    }
}

// --- Battery ----------------------------------------------------------------------------

@Composable
private fun BatteryOverlay(modifier: Modifier) {
    val battery by rememberPhoneBattery()
    val ring = Color(ringColorArgb(battery.level, battery.charging))
    val track = Color.White.copy(alpha = .35f)
    Canvas(modifier) {
        val stroke = (size.minDimension * .14f).coerceAtLeast(2f)
        val inset = stroke / 2f + size.minDimension * .06f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(ring, -90f, batteryOverlayFraction(battery.level) * 360f, false, topLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// --- Maps (compass) -----------------------------------------------------------------------

/**
 * Live heading while [enabled], from [HeadingSource] (pose module): starts on `ON_START`, stops
 * on `ON_STOP`, and tears the sensor down entirely when this leaves composition (the icon
 * scrolled off Home, or Live icons/reduce motion turned it off).
 */
@Composable
private fun rememberHeadingDeg(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source = remember(context) { HeadingSource(context) }
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(source, lifecycle, enabled) {
        if (!enabled) {
            heading.value = null
            return@DisposableEffect onDispose {}
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> source.start()
                Lifecycle.Event.ON_STOP -> source.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) source.start()
        onDispose { source.stop(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(source, enabled) {
        if (!enabled) return@LaunchedEffect
        source.heading.collect { heading.value = it }
    }
    return heading
}

@Composable
private fun MapsOverlay(modifier: Modifier, reduceMotion: Boolean) {
    // Reduce motion: a fixed north-up needle, no sensor, no animation.
    val heading by rememberHeadingDeg(enabled = !reduceMotion)
    val angle = if (reduceMotion) 0f else heading ?: 0f
    val red = Color(0xFFEA4335)
    val white = Color.White
    Canvas(modifier) {
        val radius = size.minDimension / 2f * .30f
        val center = Offset(size.width / 2f, size.height / 2f)
        // The needle points toward north, i.e. against the device's own heading.
        rotate(-angle, center) {
            val tip = Offset(center.x, center.y - radius)
            val tail = Offset(center.x, center.y + radius)
            val leftBase = Offset(center.x - radius * .35f, center.y)
            val rightBase = Offset(center.x + radius * .35f, center.y)
            drawPath(Path().apply {
                moveTo(tip.x, tip.y); lineTo(leftBase.x, leftBase.y); lineTo(rightBase.x, rightBase.y); close()
            }, red)
            drawPath(Path().apply {
                moveTo(tail.x, tail.y); lineTo(leftBase.x, leftBase.y); lineTo(rightBase.x, rightBase.y); close()
            }, white)
        }
        drawCircle(Color.Black.copy(alpha = .6f), radius * .12f, center)
    }
}
