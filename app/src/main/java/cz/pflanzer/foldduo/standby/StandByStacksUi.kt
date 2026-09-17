package cz.pflanzer.foldduo.standby

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.AlarmClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cz.pflanzer.foldduo.AnalogClockDial
import cz.pflanzer.foldduo.AppleBlue
import cz.pflanzer.foldduo.DeviceBattery
import cz.pflanzer.foldduo.StackFlipContainer
import cz.pflanzer.foldduo.ZeroPaddingWidgetHost
import cz.pflanzer.foldduo.desk.DeskTimer
import cz.pflanzer.foldduo.formatEventTime
import cz.pflanzer.foldduo.hasPermission
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.IslandNotificationListener
import cz.pflanzer.foldduo.notifications.NotificationBadges
import cz.pflanzer.foldduo.ringColorArgb
import cz.pflanzer.foldduo.rememberBluetoothBatteries
import cz.pflanzer.foldduo.rememberCalendarInstances
import cz.pflanzer.foldduo.rememberLatestPhoto
import cz.pflanzer.foldduo.rememberClockTime
import cz.pflanzer.foldduo.rememberPhoneBattery
import cz.pflanzer.foldduo.upcomingEvents
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/*
 * StandBy v2 (Tom's request 17. 9., iPhone StandBy on the Tent cover): the cover splits into a
 * left "clock" stack and a right "info" stack (each an independent vertical carousel, cards from
 * StandByStacks.kt), plus a horizontal swipe between three views (Widgets = the two stacks,
 * Photos, Clock). This file is the Compose layer only; StandByActivity.kt picks it for the
 * landscape (Tent) window and keeps the classic StandByScreen (StandByFaces.kt) for the portrait
 * (closed on a table) one — the same `maxWidth > maxHeight` test ClockFace/CalendarFace already
 * use to tell the two windows apart.
 *
 * Reuse: the vertical-swipe flip, its fading page dots and the haptic on flip are
 * [cz.pflanzer.foldduo.StackFlipContainer] as-is (B26 "Smart stack", `internal` so this file can
 * import it across the module). The card-edit sheet below mirrors that same file's
 * `StackEditSheet` interaction (toggle + reorder) rather than calling it directly: that one is
 * typed to `WidgetPlacement`/`stackMembers` (Home-grid widget ids), which does not fit StandBy's
 * card enums without changing a file outside this task's assigned area.
 */

private enum class StandByStackKind { Clock, Info }

private const val STANDBY_WIDGET_HOST_ID = 1030

/** Whether the device's locale is Czech, for [NextAlarmFormat] and the night face's two labels. */
@Composable
private fun rememberCzechLocale(): Boolean = remember { Locale.getDefault().language == "cs" }

/**
 * Flat red-on-black ink for the forced whole-screen theme (StandByNightMode.kt's `NightPalette`:
 * `#FF3B30`-ish primary, `#B0261E` secondary) — used for the ambient night face outright, and for
 * every card/dot/edit-sheet text and icon while [NightModeRules.isForcedRed] is true.
 */
private fun nightInk(): StandByInk {
    val primary = Color(NightPalette.PRIMARY_ARGB)
    val secondary = Color(NightPalette.SECONDARY_ARGB)
    return StandByInk(label = primary.copy(alpha = .85f), secondary = secondary, tertiary = secondary.copy(alpha = .6f),
        accent = primary, calendar = primary)
}

/**
 * Artwork's side of the red theme: photos, album art and the live Weather widget can't just swap
 * an ink colour, so instead a translucent red wash is drawn over whatever they rendered — a plain
 * src-over blend, i.e. linear interpolation toward the tint ([NightPalette.mixTowardRed] is that
 * same blend kept pure/testable, since this draws it at composite time instead of precomputing
 * pixels). A no-op when [active] is false.
 */
private fun Modifier.nightArtworkTint(active: Boolean): Modifier =
    if (!active) this else this.drawWithContent {
        drawContent()
        drawRect(Color(NightPalette.PRIMARY_ARGB), alpha = NightPalette.ARTWORK_TINT_ALPHA)
    }

/**
 * StandBy v2 top-level screen for the Tent (landscape) cover. Owns the two stacks' state
 * (seeded from [prefs], written back on edit), the view pager, night mode and the edit sheet;
 * [onDoubleTap]/[onSwipeUp] are the same leave gestures StandByScreen exposes. [onDimStateChanged]
 * lets the caller (StandByActivity) drop the window brightness further while the night face
 * shows — the composable itself has no window access.
 */
@Composable
fun StandByV2Screen(
    prefs: StandByPrefs,
    environment: StandByEnvironment,
    modifier: Modifier = Modifier,
    onDoubleTap: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    onDimStateChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val now by rememberClockTime()
    val zone = remember { ZoneId.systemDefault() }
    val ink = remember { StandByInk() }
    val czech = rememberCzechLocale()

    var clockCards by remember { mutableStateOf(prefs.clockCards) }
    var infoCardsRaw by remember { mutableStateOf(prefs.infoCards) }
    val weatherWidgetId = remember { findTodayWeatherWidgetId(context) }
    val infoCards = remember(infoCardsRaw, weatherWidgetId) { InfoCardOrder.available(infoCardsRaw, weatherWidgetId != null) }
    var editing by remember { mutableStateOf<StandByStackKind?>(null) }

    // --- Night mode ---
    // Tom's 17. 9. night ask: a manual "Night mode: Auto / Always / Off" setting plus a
    // per-session quick toggle (the moon glyph below), on top of the original Auto-only ambient
    // rule. `ambientNight` is that original rule (only under Auto) and swaps to the minimal
    // alarm/clock-only `NightModeFace`, same as before; `forcedRed` (Always, or the quick toggle)
    // instead keeps the full stacks UI but tints every element red — see `nightInk`/
    // `nightArtworkTint` above. Both share the same tap-to-wake brightness floor.
    val nightMode = prefs.nightMode
    var sessionForcedRed by remember { mutableStateOf(false) }
    val lowLightMs by rememberLowLightMs()
    var wokenAtMs by remember { mutableStateOf(Long.MIN_VALUE) }
    val nowMinute = remember(now) { now.hour * 60 + now.minute }
    val ambientNight = remember(nightMode, lowLightMs, prefs.sleepHoursEnabled, nowMinute, prefs.sleepStartMinute, prefs.sleepEndMinute) {
        NightModeRules.isAmbientNight(NightModeInputs(nightMode, lowLightMs, prefs.sleepHoursEnabled,
            nowMinute, prefs.sleepStartMinute, prefs.sleepEndMinute))
    }
    val forcedRed = remember(nightMode, sessionForcedRed) { NightModeRules.isForcedRed(nightMode, sessionForcedRed) }
    val night = ambientNight || forcedRed
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(wokenAtMs) {
        if (wokenAtMs != Long.MIN_VALUE) {
            awake = true
            while (NightModeRules.isAwake(SystemClock.elapsedRealtime(), wokenAtMs)) delay(500L)
            awake = false
        }
    }
    val dimmed = night && !awake
    LaunchedEffect(dimmed) { onDimStateChanged(dimmed) }
    val activeInk = if (forcedRed) nightInk() else ink

    val swipeThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, onBackground = activeInk.label)) {
        Box(modifier.fillMaxSize().background(Color.Black).testTag("standby-v2")
            .pointerInput(onDoubleTap, dimmed, forcedRed) {
                detectTapGestures(
                    onDoubleTap = { if (!dimmed && !forcedRed) onDoubleTap() },
                    onTap = { if (dimmed) wokenAtMs = SystemClock.elapsedRealtime() },
                )
            }
            // Mostly-vertical drags leave StandBy, same threshold/shape as StandByScreen; a drag
            // that starts on one of the two stacks below is consumed by StackFlipContainer first
            // (cycling that stack instead) — swipe-to-leave only "wins" on the Photos/Clock views,
            // where nothing else claims the vertical axis. Double tap always leaves. Both exit
            // gestures are suppressed while `forcedRed`, not just while `dimmed`: a tap-to-wake
            // during the forced red theme must only brighten the screen (per spec), never also
            // arm the ordinary double-tap/swipe-up dismiss for the 10 s it stays "awake".
            .pointerInput(onSwipeUp, dimmed, forcedRed) {
                var dy = 0f; var dx = 0f
                detectDragGestures(onDragStart = { dy = 0f; dx = 0f },
                    onDragEnd = { if (!dimmed && !forcedRed && dy < -swipeThreshold && abs(dy) > abs(dx) * 1.5f) onSwipeUp() },
                    onDrag = { change, drag -> dy += drag.y; dx += drag.x; if (abs(dy) > abs(dx)) change.consume() })
            }) {
            if (ambientNight && !forcedRed && dimmed) {
                NightModeFace(environment.nextAlarmMs, now, czech)
            } else {
                val pager = rememberPagerState(initialPage = 0, pageCount = { StandByViewOrder.ORDER.size })
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { StandByViewOrder.ORDER[it].name }) { page ->
                    when (StandByViewOrder.ORDER[page]) {
                        StandByView.Widgets -> WidgetsView(clockCards, infoCards, environment, prefs, now, czech, activeInk,
                            weatherWidgetId, forcedRed, onEdit = { editing = it }, modifier = Modifier.fillMaxSize())
                        StandByView.Photos -> PhotosFace(now, environment.photosGranted, activeInk,
                            Modifier.fillMaxSize().nightArtworkTint(forcedRed))
                        StandByView.Clock -> ClockFace(now, activeInk, Modifier.fillMaxSize().nightArtworkTint(forcedRed))
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).testTag("standby-view-dots"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StandByViewOrder.ORDER.indices.forEach { index ->
                        Box(Modifier.size(6.dp).background(if (index == pager.currentPage) activeInk.label else activeInk.tertiary, CircleShape))
                    }
                }
            }
            // Quick session toggle (17. 9. night ask): a long press forces/releases the whole-screen
            // red theme for just this StandBy session, on top of whatever "Night mode" is set to.
            Icon(Icons.Rounded.Bedtime, if (sessionForcedRed) "Night mode forced on" else "Night mode",
                tint = if (sessionForcedRed) Color(NightPalette.PRIMARY_ARGB) else Color.White.copy(alpha = .3f),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(20.dp).testTag("standby-night-toggle")
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { sessionForcedRed = !sessionForcedRed }) })
            editing?.let { kind ->
                StandByStackEditOverlay(kind, clockCards, infoCardsRaw, forcedRed,
                    onClockChange = { clockCards = it; prefs.clockCards = it },
                    onInfoChange = { infoCardsRaw = it; prefs.infoCards = it },
                    onClose = { editing = null })
            }
        }
    }
}

// --- The two stacks (Widgets view) -------------------------------------------------------

@Composable
private fun WidgetsView(
    clockCards: List<ClockCard>,
    infoCards: List<InfoCard>,
    environment: StandByEnvironment,
    prefs: StandByPrefs,
    now: LocalDateTime,
    czech: Boolean,
    ink: StandByInk,
    weatherWidgetId: Int?,
    red: Boolean,
    onEdit: (StandByStackKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeClock by remember(clockCards) { mutableStateOf(clockCards.firstOrNull() ?: ClockCard.Digital) }
    var activeInfo by remember(infoCards) { mutableStateOf(infoCards.firstOrNull() ?: InfoCard.Calendar) }
    Row(modifier) {
        Box(Modifier.weight(1f).fillMaxHeight().testTag("standby-clock-stack")
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onEdit(StandByStackKind.Clock) }) }) {
            StackFlipContainer(activeClock.ordinal, clockCards.map { it.ordinal },
                onSwipe = { forward -> activeClock = CardStack.cycle(clockCards, activeClock, forward) }) { id ->
                ClockCardContent(ClockCard.entries[id], now, environment, prefs, czech, ink, red, Modifier.fillMaxSize())
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = if (red) .05f else .12f)))
        Box(Modifier.weight(1f).fillMaxHeight().testTag("standby-info-stack")
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onEdit(StandByStackKind.Info) }) }) {
            StackFlipContainer(activeInfo.ordinal, infoCards.map { it.ordinal },
                onSwipe = { forward -> activeInfo = CardStack.cycle(infoCards, activeInfo, forward) }) { id ->
                InfoCardContent(InfoCard.entries[id], now, environment, weatherWidgetId, ink, red, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ClockCardContent(card: ClockCard, now: LocalDateTime, environment: StandByEnvironment, prefs: StandByPrefs,
    czech: Boolean, ink: StandByInk, red: Boolean, modifier: Modifier) {
    val tinted = modifier.nightArtworkTint(red)
    when (card) {
        ClockCard.Digital -> DigitalClockCard(now, ink, modifier)
        ClockCard.Analog -> AnalogClockCard(now, tinted)
        ClockCard.WorldClock -> WorldClockCard(prefs.worldClockZoneA, prefs.worldClockZoneB, now, ink, modifier)
        ClockCard.NextAlarm -> NextAlarmCard(environment.nextAlarmMs, now, czech, ink, modifier)
        ClockCard.Timer -> TimerCard(ink, modifier)
    }
}

@Composable
private fun InfoCardContent(card: InfoCard, now: LocalDateTime, environment: StandByEnvironment, weatherWidgetId: Int?,
    ink: StandByInk, red: Boolean, modifier: Modifier) {
    val tinted = modifier.nightArtworkTint(red)
    when (card) {
        InfoCard.Calendar -> CalendarCompactCard(now, environment.calendarGranted, ink, tinted)
        InfoCard.Weather -> if (weatherWidgetId != null) WeatherHostCard(weatherWidgetId, tinted) else EmptyCard("Weather", ink, modifier)
        InfoCard.Batteries -> BatteriesCard(ink, tinted)
        InfoCard.NowPlaying -> NowPlayingCard(ink, tinted)
        InfoCard.Notifications -> NotificationsSummaryCard(ink, modifier)
        InfoCard.Photos -> PhotosCompactCard(environment.photosGranted, tinted)
    }
}

@Composable
private fun EmptyCard(label: String, ink: StandByInk, modifier: Modifier = Modifier) {
    Box(modifier.testTag("standby-card-empty-$label"), contentAlignment = Alignment.Center) {
        Text(label, color = ink.tertiary, fontSize = 14.sp)
    }
}

// --- Clock stack cards -------------------------------------------------------------------

@Composable
private fun DigitalClockCard(now: LocalDateTime, ink: StandByInk, modifier: Modifier = Modifier) {
    val pattern = timePattern()
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-digital"), verticalArrangement = Arrangement.Center) {
        DigitalTime(now.format(DateTimeFormatter.ofPattern(pattern)), 56.sp, ink.label)
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = ink.secondary, fontSize = 16.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnalogClockCard(now: LocalDateTime, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp).testTag("standby-card-analog"), contentAlignment = Alignment.Center) {
        AnalogClockDial(now, Modifier.fillMaxSize(), dark = true)
    }
}

@Composable
private fun WorldClockCard(zoneA: String?, zoneB: String?, now: LocalDateTime, ink: StandByInk, modifier: Modifier = Modifier) {
    val here = remember { ZoneId.systemDefault() }
    val pattern = timePattern()
    val cities = remember(zoneA, zoneB) { listOfNotNull(zoneA, zoneB).filter { WorldClockCities.isValidZone(it) } }
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-worldclock"), verticalArrangement = Arrangement.Center) {
        if (cities.isEmpty()) {
            Text("Add cities in StandBy settings", color = ink.secondary, fontSize = 14.sp)
        } else cities.forEach { zoneId ->
            val city = WorldClockCities.byZoneId(zoneId)?.label ?: zoneId
            val there = now.atZone(here).withZoneSameInstant(ZoneId.of(zoneId)).toLocalDateTime()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(city, color = ink.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(there.format(DateTimeFormatter.ofPattern(pattern)), color = ink.secondary, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun NextAlarmCard(nextAlarmMs: Long?, now: LocalDateTime, czech: Boolean, ink: StandByInk, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val pattern = timePattern()
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-next-alarm")
        .clickable { runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        verticalArrangement = Arrangement.Center) {
        Text(if (czech) "BUDÍK" else "ALARM", color = ink.secondary, fontSize = 13.sp, letterSpacing = 1.5.sp)
        if (nextAlarmMs != null) {
            val alarmTime = Instant.ofEpochMilli(nextAlarmMs).atZone(zone)
            Text(alarmTime.format(DateTimeFormatter.ofPattern(pattern)), color = ink.label, fontSize = 40.sp, fontWeight = FontWeight.Medium)
            val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
            Text(NextAlarmFormat.countdown(nowMillis, nextAlarmMs, czech), color = ink.secondary, fontSize = 15.sp,
                modifier = Modifier.testTag("standby-next-alarm-countdown"))
        } else {
            Text(if (czech) "Žádný budík" else "No alarms", color = ink.secondary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun TimerCard(ink: StandByInk, modifier: Modifier = Modifier) {
    var running by remember { mutableStateOf(false) }
    var totalMs by remember { mutableStateOf(5 * 60_000L) }
    var startedAtElapsed by remember { mutableStateOf(0L) }
    var remaining by remember { mutableStateOf(totalMs) }
    LaunchedEffect(running, totalMs) {
        if (running) {
            startedAtElapsed = SystemClock.elapsedRealtime() - (totalMs - remaining)
            while (running) {
                remaining = DeskTimer.remainingMs(totalMs, startedAtElapsed, SystemClock.elapsedRealtime())
                if (DeskTimer.isFinished(remaining)) { running = false }
                delay(250L)
            }
        }
    }
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-timer"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(DeskTimer.format(remaining), color = ink.label, fontSize = 40.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("standby-timer-remaining"))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(1, 5, 10).forEach { minutes ->
                Text("${minutes}m", color = ink.secondary, fontSize = 15.sp, modifier = Modifier
                    .testTag("standby-timer-preset-$minutes")
                    .clickable { totalMs = minutes * 60_000L; remaining = totalMs; running = false })
            }
            Text(if (running) "❙❙" else "▶", color = ink.label, fontSize = 15.sp, modifier = Modifier
                .testTag("standby-timer-toggle")
                .clickable {
                    if (!running && DeskTimer.isFinished(remaining)) remaining = totalMs
                    running = !running
                })
        }
    }
}

// --- Info stack cards --------------------------------------------------------------------

@Composable
private fun CalendarCompactCard(now: LocalDateTime, granted: Boolean, ink: StandByInk, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val instances by rememberCalendarInstances(granted)
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    val events = remember(instances, nowMillis / 60_000L) { instances?.let { upcomingEvents(it, nowMillis, zone, limit = 3) } }
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-calendar")) {
        Text("TODAY", color = ink.calendar, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        when {
            !granted -> Text("Calendar access is off", color = ink.secondary, fontSize = 14.sp)
            events == null -> Unit
            events.isEmpty() -> Text("Nothing else today", color = ink.secondary, fontSize = 15.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                events.forEach { event ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(event.color?.let { Color(it) } ?: AppleBlue, CircleShape))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(event.title, color = ink.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(formatEventTime(event, nowMillis, zone, twentyFour), color = ink.secondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A live but non-interactive-in-spirit small render of the weather host widget bound at [widgetId]. */
@Composable
private fun WeatherHostCard(widgetId: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val info = remember(widgetId) { runCatching { manager.getAppWidgetInfo(widgetId) }.getOrNull() }
    if (info == null) { EmptyCard("Weather", StandByInk(), modifier); return }
    val host = remember { ZeroPaddingWidgetHost(context, STANDBY_WIDGET_HOST_ID) }
    DisposableEffect(host) {
        runCatching { host.startListening() }
        onDispose { runCatching { host.stopListening() } }
    }
    AndroidView(factory = { ctx -> runCatching { host.createView(ctx, widgetId, info).apply { setPadding(0, 0, 0, 0) } }.getOrNull()
        ?: android.view.View(ctx) }, modifier = modifier.fillMaxSize().testTag("standby-card-weather"))
}

@Composable
private fun BatteriesCard(ink: StandByInk, modifier: Modifier = Modifier) {
    val phone by rememberPhoneBattery()
    val context = LocalContext.current
    val btEnabled = remember { hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT) }
    val bt by rememberBluetoothBatteries(btEnabled)
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-batteries"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BatteryRow(phone, ink)
        bt.take(2).forEach { BatteryRow(it, ink) }
    }
}

@Composable
private fun BatteryRow(battery: DeviceBattery, ink: StandByInk) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Color(ringColorArgb(battery.level, battery.charging)), CircleShape))
        Text(battery.name, color = ink.label, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text("${battery.level}%", color = ink.secondary, fontSize = 15.sp)
    }
}

/** The controller behind a live [IslandKind.MEDIA] session, the same source DeskMediaCard uses. */
@Composable
private fun NowPlayingCard(ink: StandByInk, modifier: Modifier = Modifier) {
    val items by IslandNotificationListener.items.collectAsState()
    val media = items.firstOrNull { it.kind == IslandKind.MEDIA }
    val controller = media?.media?.controller
    if (media == null || controller == null) { EmptyCard("Nothing playing", ink, modifier); return }
    var playback by remember(controller) { mutableStateOf(controller.playbackState) }
    var metadata by remember(controller) { mutableStateOf(controller.metadata) }
    DisposableEffect(controller) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { playback = state }
            override fun onMetadataChanged(meta: MediaMetadata?) { metadata = meta }
        }
        controller.registerCallback(callback)
        onDispose { controller.unregisterCallback(callback) }
    }
    val playing = playback?.state == PlaybackState.STATE_PLAYING || playback?.state == PlaybackState.STATE_BUFFERING
    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: media.title.ifBlank { "Not playing" }
    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
    val art = media.media?.artwork
    val tint = media.accentColor?.let { Color(it) } ?: ink.accent
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-nowplaying")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .28f)),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = tint) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = ink.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist.isNotBlank()) Text(artist, color = ink.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { runCatching { controller.transportControls.skipToPrevious() } },
                modifier = Modifier.testTag("standby-nowplaying-prev")) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = ink.label) }
            IconButton(onClick = { runCatching { if (playing) controller.transportControls.pause() else controller.transportControls.play() } },
                modifier = Modifier.testTag("standby-nowplaying-playpause")) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = ink.label)
            }
            IconButton(onClick = { runCatching { controller.transportControls.skipToNext() } },
                modifier = Modifier.testTag("standby-nowplaying-next")) { Icon(Icons.Rounded.SkipNext, "Next", tint = ink.label) }
        }
    }
}

@Composable
private fun NotificationsSummaryCard(ink: StandByInk, modifier: Modifier = Modifier) {
    val counts by NotificationBadges.counts.collectAsState()
    val context = LocalContext.current
    val rows = remember(counts) {
        val pm = context.packageManager
        counts.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5).map { (pkg, count) ->
            val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
            label to count
        }
    }
    Column(modifier.fillMaxSize().padding(20.dp).testTag("standby-card-notifications"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rows.isEmpty()) Text("No notifications", color = ink.secondary, fontSize = 15.sp)
        else rows.forEach { (label, count) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = ink.label, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count.toString(), color = ink.secondary, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PhotosCompactCard(granted: Boolean, modifier: Modifier = Modifier) {
    val photo by rememberLatestPhoto(granted)
    Box(modifier.fillMaxSize().testTag("standby-card-photos")) {
        val current = photo
        if (current != null) Image(current.bitmap.asImageBitmap(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .06f)))
    }
}

// --- Night mode ----------------------------------------------------------------------------

/**
 * Dim + red-tinted alarm/clock-only face (iOS Night Mode): no wallpaper, no card backgrounds. A
 * tap wakes the caller ([StandByV2Screen]'s `wokenAtMs`) via the parent's own tap handling.
 *
 * Device report (17. 9. night): this used to show the *alarm's* time as the big digits (falling
 * back to the clock only without an alarm), so a real alarm set for local midnight briefly read
 * as a giant red "00:00" right before the activity exited — indistinguishable from a data bug.
 * The big digits are now always the current time ([NightFaceFormat.clockText]); the alarm (or
 * "Žádný budík"/"No alarm") is the small line below ([NightFaceFormat.alarmText]).
 */
@Composable
private fun NightModeFace(nextAlarmMs: Long?, now: LocalDateTime, czech: Boolean, modifier: Modifier = Modifier) {
    val ink = nightInk()
    val pattern = timePattern()
    val zone = remember { ZoneId.systemDefault() }
    Box(modifier.fillMaxSize().background(Color.Black).testTag("standby-night-mode"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(NightFaceFormat.clockText(now, pattern), color = ink.label, fontSize = 60.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("standby-night-time"))
            Spacer(Modifier.height(10.dp))
            Text(NightFaceFormat.alarmText(nextAlarmMs, zone, pattern, czech), color = ink.secondary, fontSize = 18.sp,
                modifier = Modifier.testTag("standby-night-alarm"))
        }
    }
}

/** Ambient light, reduced to "how long has it been continuously dark" ([LowLightAccumulator]). */
@Composable
private fun rememberLowLightMs(): State<Long> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(0L) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensorManager == null || lightSensor == null) { value = 0L; return@produceState }
        var lastLux = Float.MAX_VALUE
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { lastLux = event.values.getOrElse(0) { Float.MAX_VALUE } }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
            try {
                var lastTick = SystemClock.elapsedRealtime()
                while (true) {
                    delay(1_000L)
                    val nowElapsed = SystemClock.elapsedRealtime()
                    value = LowLightAccumulator.update(value, lastLux, nowElapsed - lastTick)
                    lastTick = nowElapsed
                }
            } finally {
                sensorManager.unregisterListener(listener)
            }
        }
    }
}

// --- Edit sheet ----------------------------------------------------------------------------

@Composable
private fun StandByStackEditOverlay(
    kind: StandByStackKind,
    clockCards: List<ClockCard>,
    infoCards: List<InfoCard>,
    red: Boolean,
    onClockChange: (List<ClockCard>) -> Unit,
    onInfoChange: (List<InfoCard>) -> Unit,
    onClose: () -> Unit,
) {
    val chrome = if (red) Color(NightPalette.PRIMARY_ARGB) else Color.White
    val panelBg = if (red) Color.Black else Color(0xFF1C1C1E)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).testTag("standby-stack-edit-scrim")
        .pointerInput(onClose) { detectTapGestures(onTap = { onClose() }) }) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth(.86f)
            .background(panelBg, RoundedCornerShape(20.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }) { // swallow taps so they don't reach the scrim behind
            when (kind) {
                StandByStackKind.Clock -> EditSheetBody("Clock stack", ClockCard.entries.toList(), clockCards, chrome,
                    onToggle = { card, shown -> onClockChange(ClockCardOrder.toggle(clockCards, card, shown)) },
                    onMoveUp = { card -> onClockChange(CardStack.moveUp(clockCards, card)) }, onClose = onClose)
                StandByStackKind.Info -> EditSheetBody("Info stack", InfoCard.entries.toList(), infoCards, chrome,
                    onToggle = { card, shown -> onInfoChange(InfoCardOrder.toggle(infoCards, card, shown)) },
                    onMoveUp = { card -> onInfoChange(CardStack.moveUp(infoCards, card)) }, onClose = onClose)
            }
        }
    }
}

@Composable
private fun <T : Enum<T>> EditSheetBody(title: String, all: List<T>, shown: List<T>, chrome: Color, onToggle: (T, Boolean) -> Unit,
    onMoveUp: (T) -> Unit, onClose: () -> Unit) {
    val rows = shown + all.filter { it !in shown }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).testTag("standby-stack-edit")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = chrome, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onClose, modifier = Modifier.testTag("standby-stack-edit-close")) {
                Icon(Icons.Rounded.Close, "Close", tint = chrome)
            }
        }
        rows.forEach { card ->
            val isShown = card in shown
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(isShown, { onToggle(card, it) }, modifier = Modifier.testTag("standby-stack-edit-${card.name}"))
                Text(card.name, Modifier.weight(1f), color = chrome)
                IconButton(onClick = { onMoveUp(card) }, enabled = isShown && shown.indexOf(card) > 0,
                    modifier = Modifier.testTag("standby-stack-edit-up-${card.name}")) {
                    Icon(Icons.Rounded.ArrowUpward, "Move ${card.name} up", tint = chrome)
                }
            }
        }
    }
}
