package cz.pflanzer.foldduo.standby

import android.Manifest
import android.app.AlarmManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cz.pflanzer.foldduo.AnalogClockDial
import cz.pflanzer.foldduo.AppleBlue
import cz.pflanzer.foldduo.AppleOrange
import cz.pflanzer.foldduo.AppleRed
import cz.pflanzer.foldduo.CalendarEvent
import cz.pflanzer.foldduo.formatEventTime
import cz.pflanzer.foldduo.hasPermission
import cz.pflanzer.foldduo.hasPhotoAccess
import cz.pflanzer.foldduo.photoTitle
import cz.pflanzer.foldduo.rememberCalendarInstances
import cz.pflanzer.foldduo.rememberClockTime
import cz.pflanzer.foldduo.rememberLatestPhoto
import cz.pflanzer.foldduo.upcomingEvents
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/*
 * StandBy faces (PLAN.md Fáze 4, IDEAS.md B9): the iPhone-Duo cover when the Fold rests closed
 * or tented. The same composables serve StandByActivity (pose-driven, no charger) and
 * StandByDreamService (system screensaver on a charger). Everything is white ink on near-black
 * so the cover stays dim; the only large bright area is a photo the user chose to see.
 * Layouts follow the window: tent = landscape cover (876 x 555 dp), closed on the table =
 * portrait (555 x 876 dp). Weather is left out: the launcher has no weather data source.
 */

/** Ink on the dark faces, the Dark widget palette without the card. */
@Immutable
data class StandByInk(val label: Color = Color.White, val secondary: Color = Color.White.copy(alpha = .62f),
    val tertiary: Color = Color.White.copy(alpha = .38f), val accent: Color = AppleOrange, val calendar: Color = AppleRed)

/** What the host lets the faces read; requests are never made from StandBy itself. */
@Immutable
data class StandByEnvironment(val calendarGranted: Boolean, val photosGranted: Boolean, val nextAlarmMs: Long?)

/** Permissions now, and the next alarm clock re-read every 30 s while resumed (no permission needed). */
@Composable
fun rememberStandByEnvironment(): StandByEnvironment {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val alarm by produceState<Long?>(null) {
        val manager = context.getSystemService(AlarmManager::class.java)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = runCatching { manager?.nextAlarmClock?.triggerTime }.getOrNull()
                delay(30_000L)
            }
        }
    }
    val calendar = hasPermission(context, Manifest.permission.READ_CALENDAR)
    val photos = hasPhotoAccess(context)
    return remember(calendar, photos, alarm) { StandByEnvironment(calendar, photos, alarm) }
}

/**
 * The whole StandBy screen: glow background, a pager over [faces], page dots, double tap and
 * swipe up to leave. Single-face lists hide the dots.
 */
@Composable
fun StandByScreen(faces: List<StandByFace>, environment: StandByEnvironment, modifier: Modifier = Modifier,
    initialFace: Int = 0, onDoubleTap: () -> Unit = {}, onSwipeUp: () -> Unit = {}) {
    val shown = faces.ifEmpty { FaceOrder.DEFAULT }
    val now by rememberClockTime()
    val zone = remember { ZoneId.systemDefault() }
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val glow = GlowAlarm.progress(nowMillis, environment.nextAlarmMs)
    val background by animateColorAsState(Color(GlowAlarm.argb(glow)), tween(900), label = "glow")
    val ink = remember { StandByInk() }
    val swipeThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    MaterialTheme(colorScheme = darkColorScheme(background = background, onBackground = ink.label)) {
        Box(modifier.fillMaxSize().background(background).testTag("standby")
            .pointerInput(onDoubleTap) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .pointerInput(onSwipeUp) {
                var dy = 0f; var dx = 0f
                detectDragGestures(onDragStart = { dy = 0f; dx = 0f },
                    onDragEnd = { if (dy < -swipeThreshold && abs(dy) > abs(dx) * 1.5f) onSwipeUp() },
                    onDrag = { change, drag -> dy += drag.y; dx += drag.x; if (abs(dy) > abs(dx)) change.consume() })
            }) {
            val pager = rememberPagerState(initialPage = initialFace.coerceIn(0, shown.size - 1), pageCount = { shown.size })
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { shown[it].name }) { page ->
                when (shown[page]) {
                    StandByFace.Clock -> ClockFace(now, ink, Modifier.fillMaxSize())
                    StandByFace.Calendar -> CalendarFace(now, environment.calendarGranted, ink, Modifier.fillMaxSize())
                    StandByFace.Photos -> PhotosFace(now, environment.photosGranted, ink, Modifier.fillMaxSize())
                }
            }
            if (shown.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).testTag("standby-dots"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(shown.size) { index ->
                    Box(Modifier.size(6.dp).background(if (index == pager.currentPage) ink.label else ink.tertiary, CircleShape))
                }
            }
        }
    }
}

/** `internal`, not `private`: StandByStacksUi.kt's cards (StandBy v2) share this with the faces. */
@Composable
internal fun timePattern(): String =
    if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"

/** Large iOS World Clock dial with the digital time and the date; dial left in landscape, on top in portrait. */
@Composable
fun ClockFace(time: LocalDateTime, ink: StandByInk = StandByInk(), modifier: Modifier = Modifier) {
    val pattern = timePattern()
    val date = time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
    BoxWithConstraints(modifier.testTag("standby-clock")) {
        val landscape = maxWidth > maxHeight
        val dial = if (landscape) minOf(maxHeight * .78f, maxWidth * .46f) else minOf(maxWidth * .74f, maxHeight * .5f)
        val digits = if (landscape) 108.sp else 92.sp
        if (landscape) Row(Modifier.fillMaxSize().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            AnalogClockDial(time, Modifier.size(dial), dark = true)
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.Center) {
                DigitalTime(time.format(DateTimeFormatter.ofPattern(pattern)), digits, ink.label)
                Text(date, color = ink.secondary, fontSize = 24.sp, lineHeight = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            AnalogClockDial(time, Modifier.size(dial), dark = true)
            Spacer(Modifier.height(24.dp))
            DigitalTime(time.format(DateTimeFormatter.ofPattern(pattern)), digits, ink.label)
            Text(date, color = ink.secondary, fontSize = 22.sp, lineHeight = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** `internal`, not `private`: StandByStacksUi.kt's Digital clock card (StandBy v2) reuses this as-is. */
@Composable
internal fun DigitalTime(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = size, lineHeight = size, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp,
        maxLines = 1, modifier = modifier.testTag("standby-time"))
}

/**
 * Today and the next events, the calendar widget's data path ([rememberCalendarInstances],
 * [upcomingEvents]). Without READ_CALENDAR a hint points to the Home widget, which can ask.
 */
@Composable
fun CalendarFace(now: LocalDateTime, granted: Boolean, ink: StandByInk = StandByInk(), modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val instances by rememberCalendarInstances(granted)
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    BoxWithConstraints(modifier.testTag("standby-calendar")) {
        val landscape = maxWidth > maxHeight
        val headerWidth = maxWidth * .36f
        val limit = if (landscape) 5 else 7
        val events = remember(instances, nowMillis / 60_000L, limit) { instances?.let { upcomingEvents(it, nowMillis, zone, limit) } }
        val header: @Composable () -> Unit = {
            Column {
                Text(now.format(DateTimeFormatter.ofPattern("EEEE")).uppercase(), color = ink.calendar, fontSize = 22.sp,
                    lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1)
                Text(now.dayOfMonth.toString(), color = ink.label, fontSize = if (landscape) 148.sp else 128.sp,
                    lineHeight = if (landscape) 150.sp else 130.sp, fontWeight = FontWeight.Medium, letterSpacing = (-4).sp,
                    modifier = Modifier.testTag("standby-day"))
                Text(now.format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = ink.secondary, fontSize = 22.sp, lineHeight = 26.sp, maxLines = 1)
            }
        }
        val list: @Composable () -> Unit = { CalendarList(events, granted, nowMillis, zone, twentyFour, ink) }
        if (landscape) Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 40.dp, top = 40.dp, bottom = 40.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(headerWidth)) { header() }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { list() }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 56.dp)) {
            header()
            Spacer(Modifier.height(28.dp))
            list()
        }
    }
}

@Composable
private fun CalendarList(events: List<CalendarEvent>?, granted: Boolean, now: Long, zone: ZoneId, twentyFour: Boolean, ink: StandByInk) {
    when {
        !granted -> Text("Calendar access is off. Allow it from the Calendar widget on Home.", color = ink.secondary,
            fontSize = 18.sp, lineHeight = 24.sp, modifier = Modifier.testTag("standby-calendar-permission"))
        events == null -> Unit
        events.isEmpty() -> Text("Nothing else today", color = ink.secondary, fontSize = 22.sp, lineHeight = 28.sp)
        else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            events.forEach { event ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(event.color?.let { Color(it) } ?: AppleBlue, CircleShape))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(event.title, color = ink.label, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatEventTime(event, now, zone, twentyFour), color = ink.secondary, fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Full-bleed latest / On-This-Day photo ([rememberLatestPhoto]) with a small clock top-left and
 * the memory title bottom-left. Without photo access the face is the clock on black with a hint.
 */
@Composable
fun PhotosFace(now: LocalDateTime, granted: Boolean, ink: StandByInk = StandByInk(), modifier: Modifier = Modifier) {
    val photo by rememberLatestPhoto(granted)
    val zone = remember { ZoneId.systemDefault() }
    val pattern = timePattern()
    val shadow = remember { Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 2f), 8f) }
    Box(modifier.testTag("standby-photos")) {
        val current = photo
        if (current != null) {
            Image(current.bitmap.asImageBitmap(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .35f), .3f to Color.Transparent,
                .65f to Color.Transparent, 1f to Color.Black.copy(alpha = .55f))))
            val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
            Column(Modifier.align(Alignment.BottomStart).padding(28.dp).padding(bottom = 12.dp)) {
                Text(photoTitle(current.takenMillis, nowMillis, zone), color = Color.White, fontSize = 26.sp, lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, style = TextStyle(shadow = shadow), modifier = Modifier.testTag("standby-photos-title"))
                Text(Instant.ofEpochMilli(current.takenMillis).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
                    color = Color.White.copy(alpha = .85f), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, style = TextStyle(shadow = shadow))
            }
            Text(now.format(DateTimeFormatter.ofPattern(pattern)), color = Color.White, fontSize = 56.sp, lineHeight = 56.sp,
                fontWeight = FontWeight.Medium, letterSpacing = (-1).sp, maxLines = 1, style = TextStyle(shadow = shadow),
                modifier = Modifier.align(Alignment.TopStart).padding(28.dp).testTag("standby-time"))
        } else Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            DigitalTime(now.format(DateTimeFormatter.ofPattern(pattern)), 92.sp, ink.label)
            Text(if (granted) "No recent photos" else "Photo access is off. Allow it from the Photos widget on Home.",
                color = ink.secondary, fontSize = 18.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp).testTag("standby-photos-permission"))
        }
    }
}
