package cz.pflanzer.foldduo

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Pure model for the built-in (Duo) widgets: ids, labels, footprints and the arithmetic behind
 * the iOS-style cards in AppleWidgets.kt. No Android or Compose imports, so everything here is
 * covered by JVM unit tests (BuiltinWidgetsTest).
 */

/** iOS-style analog "World Clock" dial (2 x 2 on Home, full width in Today). */
const val CLOCK_ANALOG_WIDGET = -6
/** iOS-style Calendar: weekday, date numeral and the next events. */
const val CALENDAR_WIDGET = -7
/** iOS-style Batteries: one ring per device. */
const val BATTERIES_WIDGET = -8
/** iOS-style Photos: full-bleed latest picture. */
const val PHOTOS_WIDGET = -9

/** Every id a saved placement may carry that is rendered by the launcher itself. */
val BUILTIN_WIDGET_IDS = setOf(CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET,
    CLOCK_ANALOG_WIDGET, CALENDAR_WIDGET, BATTERIES_WIDGET, PHOTOS_WIDGET)

fun isBuiltinWidgetId(id: Int) = id in BUILTIN_WIDGET_IDS

/** Picker order and names of the built-in section. */
val BUILTIN_WIDGET_CATALOG: List<Pair<Int, String>> = listOf(
    CLOCK_ANALOG_WIDGET to "Clock",
    CALENDAR_WIDGET to "Calendar",
    BATTERIES_WIDGET to "Batteries",
    PHOTOS_WIDGET to "Photos",
    CLOCK_WIDGET to "Digital clock",
    DATE_WIDGET to "Date",
    INFO_WIDGET to "Widget panel",
)

fun builtinWidgetLabel(id: Int): String = BUILTIN_WIDGET_CATALOG.firstOrNull { it.first == id }?.second
    ?: if (id == EMPTY_WIDGET) "Add widget" else "Widget panel"

/** Footprint a built-in gets when placed on Home from the picker. */
fun builtinWidgetSpan(id: Int): WidgetSpan = when (id) {
    CALENDAR_WIDGET, PHOTOS_WIDGET -> WidgetSpan(4, 2)
    else -> WidgetSpan(2, 2)
}

/** Rows a built-in takes when it lands in the Today column: Photos fills the pane, the rest two rows. */
fun builtinTodayRows(id: Int): Int = if (id == PHOTOS_WIDGET) GRID_ROWS else 2

/** Sizes in the iOS sense: small is a square tile, medium the wide tile, large the tall wide one. */
enum class WidgetSize { SMALL, MEDIUM, LARGE }

/**
 * Size class from the rectangle a card actually gets. A Home 2 x 2 tile (about 235 x 176 dp) is
 * small, 4 x 2 and every two-row Today item is medium, and a Today item over three or more rows
 * (at least 300 dp tall and 360 dp wide) is large.
 */
fun widgetSizeFor(widthDp: Float, heightDp: Float): WidgetSize = when {
    heightDp >= 300f && widthDp >= 360f -> WidgetSize.LARGE
    widthDp >= heightDp * 1.5f -> WidgetSize.MEDIUM
    else -> WidgetSize.SMALL
}

// --- Clock ------------------------------------------------------------------------------

data class HandAngles(val hour: Float, val minute: Float, val second: Float)

/** Clockwise degrees from 12 o'clock for each hand; hands sweep continuously. */
fun handAngles(hour: Int, minute: Int, second: Int, millis: Int = 0): HandAngles {
    require(hour in 0..23 && minute in 0..59 && second in 0..59 && millis in 0..999)
    val seconds = second + millis / 1000f
    val minutes = minute + seconds / 60f
    val hours = hour % 12 + minutes / 60f
    return HandAngles(hours * 30f, minutes * 6f, seconds * 6f)
}

/** "Europe/Prague" -> "Prague", "America/Argentina/Buenos_Aires" -> "Buenos Aires", "UTC" -> "UTC". */
fun timeZoneCity(zoneId: String): String =
    zoneId.substringAfterLast('/').replace('_', ' ').ifBlank { zoneId }

// --- Calendar ---------------------------------------------------------------------------

data class CalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** ARGB colour of the event or its calendar; null when the provider has none. */
    val color: Int?,
)

/**
 * The next [limit] events, ordered by start: everything still running or starting later today,
 * then tomorrow's. Events that already ended are dropped, and so is anything after tomorrow.
 * All-day events use UTC midnight in the provider; they count for the calendar day they name.
 */
fun upcomingEvents(instances: List<CalendarEvent>, now: Long, zone: ZoneId, limit: Int = 3): List<CalendarEvent> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayAfterTomorrow = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
    return instances.filter { event ->
        if (event.allDay) {
            val day = Instant.ofEpochMilli(event.begin).atZone(ZoneId.of("UTC")).toLocalDate()
            !day.isBefore(today) && day.isBefore(today.plusDays(2))
        } else event.end > now && event.begin < dayAfterTomorrow
    }.sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.begin }).take(limit)
}

private fun eventDay(event: CalendarEvent, zone: ZoneId): LocalDate =
    if (event.allDay) Instant.ofEpochMilli(event.begin).atZone(ZoneId.of("UTC")).toLocalDate()
    else Instant.ofEpochMilli(event.begin).atZone(zone).toLocalDate()

/** "All-day", "14:30", "2:30 PM", "Tomorrow 9:00" or "Tomorrow, all-day". */
fun formatEventTime(event: CalendarEvent, now: Long, zone: ZoneId, twentyFourHour: Boolean,
    locale: Locale = Locale.getDefault()): String {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val tomorrow = eventDay(event, zone).isAfter(today)
    if (event.allDay) return if (tomorrow) "Tomorrow, all-day" else "All-day"
    val time = LocalTime.from(Instant.ofEpochMilli(event.begin).atZone(zone))
    val pattern = if (twentyFourHour) "H:mm" else "h:mm a"
    val text = time.format(DateTimeFormatter.ofPattern(pattern, locale))
    return if (tomorrow) "Tomorrow $text" else text
}

/** Minutes until a timed event starts, or null when it is all-day or already running. */
fun minutesUntil(event: CalendarEvent, now: Long): Long? =
    if (event.allDay || event.begin <= now) null else Duration.ofMillis(event.begin - now).toMinutes()

// --- Photos -----------------------------------------------------------------------------

/** iOS memory title: "On This Day" for a picture from an earlier year, otherwise "Recents". */
fun photoTitle(takenMillis: Long, now: Long, zone: ZoneId): String {
    val taken = Instant.ofEpochMilli(takenMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (taken.year < today.year) "On This Day" else "Recents"
}

// --- Batteries --------------------------------------------------------------------------

enum class DeviceKind { PHONE, HEADPHONES, WATCH, OTHER }

data class DeviceBattery(val name: String, val level: Int, val charging: Boolean, val kind: DeviceKind)

/** Clockwise sweep of a battery ring in degrees for a 0..100 level; a 3-degree minimum keeps a hairline visible. */
fun ringSweep(level: Int): Float = (level.coerceIn(0, 100) * 3.6f).coerceAtLeast(if (level > 0) 3f else 0f)

/** ARGB ring colour: green while charging, red at 20 % or lower, yellow up to 40 %, else iOS green. */
fun ringColorArgb(level: Int, charging: Boolean): Long = when {
    charging -> 0xFF30D158
    level <= 20 -> 0xFFFF453A
    level <= 40 -> 0xFFFFD60A
    else -> 0xFF34C759
}

/** Rounds a raw 0..1 fraction to whole percent, clamping out-of-range provider values. */
fun batteryPercent(level: Int, scale: Int): Int =
    if (scale <= 0 || level < 0) 0 else (level * 100f / scale).roundToInt().coerceIn(0, 100)

/** Bluetooth major/minor class to a kind: audio devices are headphones, wearables watches. */
fun deviceKindFor(majorClass: Int, deviceClass: Int): DeviceKind = when (majorClass) {
    0x0400 -> DeviceKind.HEADPHONES   // AUDIO_VIDEO
    0x0700 -> if (deviceClass == 0x0704) DeviceKind.WATCH else DeviceKind.OTHER // WEARABLE / WEARABLE_WRIST_WATCH
    else -> DeviceKind.OTHER
}

// --- Appearance -------------------------------------------------------------------------

/**
 * Per-placement look of a built-in card. Auto follows the system theme like iOS widgets;
 * Glass is the launcher's translucent card; Tinted paints the card with [WidgetPlacement.tint].
 */
/**
 * [Wallpaper] (B29 "Barvy z tapety") behaves like [Tinted] but always with the currently derived
 * wallpaper accent ([WallpaperPalette.accent]) rather than a user-picked swatch — the caller
 * (`appleWidgetColors`/`HostWidgetFrame`) resolves that accent and passes it as `tint` whenever
 * this is the active appearance, so [widgetPalette] itself stays pure.
 */
enum class WidgetAppearance { Auto, Light, Dark, Glass, Tinted, Wallpaper }

/** Saved-state key for an appearance; missing or unknown values fall back to [WidgetAppearance.Auto]. */
fun parseWidgetAppearance(raw: String?): WidgetAppearance =
    WidgetAppearance.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WidgetAppearance.Auto

/**
 * Placements that offer the appearance row: the built-ins that render as an iOS card (the legacy
 * glass cards do not) and every bound third-party app widget (id >= 0), which gets a container
 * frame from [hostFrameSpec] instead of a repaint.
 */
fun supportsWidgetAppearance(id: Int): Boolean = id >= 0 ||
    id == CLOCK_WIDGET || id == CLOCK_ANALOG_WIDGET || id == CALENDAR_WIDGET || id == BATTERIES_WIDGET || id == PHOTOS_WIDGET

/** iOS system tints offered as swatches, ARGB. */
val WIDGET_TINT_PRESETS: List<Pair<Int, String>> = listOf(
    0xFF0A84FF.toInt() to "Blue",
    0xFFBF5AF2.toInt() to "Purple",
    0xFFFF375F.toInt() to "Pink",
    0xFFFF453A.toInt() to "Red",
    0xFFFF9F0A.toInt() to "Orange",
    0xFFFFD60A.toInt() to "Yellow",
    0xFF30D158.toInt() to "Green",
    0xFF8E8E93.toInt() to "Graphite",
)

/**
 * Colours of one card, ARGB. [label] is the ink, [secondary]/[tertiary] its faded steps, [fill]
 * the track behind rings, [accent] the calendar weekday red (the ink on a tinted card, where red
 * on red would vanish). [border] is a 1 dp hairline (glass only), [inkShadow] asks for a soft text
 * shadow (glass over a bright wallpaper), [monochrome] draws battery rings in ink (tinted cards),
 * [frosted] draws the pre-blurred wallpaper slice under the card (FrostedBackdrop.kt) instead of
 * [card], which is then the fallback fill while no backdrop exists.
 */
data class WidgetPalette(
    val card: Long,
    val label: Long,
    val secondary: Long,
    val tertiary: Long,
    val fill: Long,
    val accent: Long,
    val border: Long? = null,
    val darkInk: Boolean,
    val inkShadow: Boolean = false,
    val monochrome: Boolean = false,
    val frosted: Boolean = false,
)

private const val APPLE_RED = 0xFFFF453AL

/** [rgb] with its alpha replaced by [alpha] (0..1). */
fun withAlpha(rgb: Long, alpha: Float): Long =
    ((alpha.coerceIn(0f, 1f) * 255f).roundToInt().toLong() shl 24) or (rgb and 0xFFFFFFL)

/** WCAG relative luminance of an ARGB colour, 0 (black) to 1 (white); alpha is ignored. */
fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= .03928) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
    }
    return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
}

/**
 * Black ink on bright colours, white on the rest. The 0.4 luminance cut keeps every preset at a
 * WCAG large-text contrast of 3:1 or better: yellow, orange and green take black ink, blue, red,
 * pink, purple and graphite white (white on orange would only reach 2:1).
 */
fun prefersDarkInk(argb: Int): Boolean = relativeLuminance(argb) > .4

/**
 * The palette for an appearance. Auto is Light or Dark by [systemDark]; Light is white 92 %;
 * Dark is #1C1C1E 85 %; Glass is frosted: the blurred wallpaper under the card with a white 22 %
 * veil (white 20 % flat while no backdrop exists), a white 25 % hairline and white ink; Tinted is
 * [tint] (default blue) at 90 % with ink chosen by [prefersDarkInk].
 */
fun widgetPalette(appearance: WidgetAppearance, tint: Int?, systemDark: Boolean): WidgetPalette = when (appearance) {
    WidgetAppearance.Auto -> widgetPalette(if (systemDark) WidgetAppearance.Dark else WidgetAppearance.Light, tint, systemDark)
    WidgetAppearance.Light -> WidgetPalette(card = withAlpha(0xFFFFFF, .92f), label = 0xFF000000L,
        secondary = withAlpha(0x3C3C43, .6f), tertiary = withAlpha(0x3C3C43, .3f), fill = withAlpha(0x787880, .16f),
        accent = APPLE_RED, darkInk = true)
    WidgetAppearance.Dark -> WidgetPalette(card = withAlpha(0x1C1C1E, .85f), label = 0xFFFFFFFFL,
        secondary = withAlpha(0xEBEBF5, .6f), tertiary = withAlpha(0xEBEBF5, .3f), fill = withAlpha(0x787880, .24f),
        accent = APPLE_RED, darkInk = false)
    WidgetAppearance.Glass -> WidgetPalette(card = withAlpha(0xFFFFFF, .2f), label = 0xFFFFFFFFL,
        secondary = withAlpha(0xFFFFFF, .75f), tertiary = withAlpha(0xFFFFFF, .45f), fill = withAlpha(0xFFFFFF, .22f),
        accent = APPLE_RED, border = withAlpha(0xFFFFFF, .25f), darkInk = false, inkShadow = true, frosted = true)
    WidgetAppearance.Tinted -> tintedPalette(tint ?: WIDGET_TINT_PRESETS.first().first)
    WidgetAppearance.Wallpaper -> tintedPalette(tint ?: WallpaperPalette.FALLBACK_ACCENT)
}

/** Shared body of [WidgetAppearance.Tinted] and [WidgetAppearance.Wallpaper]: a card filled with [colour], ink chosen by [prefersDarkInk]. */
private fun tintedPalette(colour: Int): WidgetPalette {
    val dark = prefersDarkInk(colour)
    val ink = if (dark) 0x000000L else 0xFFFFFFL
    return WidgetPalette(card = withAlpha(colour.toLong(), .9f), label = withAlpha(ink, 1f),
        secondary = withAlpha(ink, .7f), tertiary = withAlpha(ink, .4f), fill = withAlpha(ink, .16f),
        accent = withAlpha(ink, 1f), darkInk = dark, monochrome = true)
}

// --- Host widget frame ------------------------------------------------------------------

/** How the tint overlay of a Tinted host frame is composited over the provider's pixels. */
enum class HostFrameBlend { Multiply, Screen }

/**
 * Container treatment for a third-party AppWidgetHostView, whose RemoteViews cannot be
 * repainted. Colours are ARGB. [framed] false means the view is drawn exactly as before (no
 * clip, no layer). [backing] is filled behind the view, [border] is a 1 dp hairline on top,
 * [overlay] is drawn over the view with [blend], and [contentAlpha] is the view layer's alpha.
 * [darkGlass] is set on the Glass variant chosen for a widget whose own (now-stripped) background
 * sampled dark, so [HostWidgetFrame] darkens the frost veil instead of the usual bright one —
 * otherwise that widget's pale-on-dark text would vanish (HostWidgetSkin.kt does the sampling).
 */
data class HostFrameSpec(
    val framed: Boolean,
    val cornerDp: Float = 0f,
    val backing: Long? = null,
    val border: Long? = null,
    val overlay: Long? = null,
    val blend: HostFrameBlend? = null,
    val contentAlpha: Float = 1f,
    val darkGlass: Boolean = false,
)

const val HOST_FRAME_CORNER_DP = 22f

/**
 * Frame for a host widget. Auto: untouched. Glass: white 20 % backing (the flat fallback; the
 * live frame draws the frosted wallpaper slice instead, HostWidgetFrame.kt), white 25 % hairline,
 * view at 90 % so its own opaque card lets the wallpaper through — or, when [darkBackground] is
 * true (the provider's own background, sampled before HostWidgetSkin stripped it, came out dark),
 * a dark 55 % backing with a faint white hairline instead, so the provider's light text stays
 * legible over the frost. Light / Dark: white 88 % / #1C1C1E 85 % backing (for providers with
 * transparent backgrounds). Tinted: [tint] (default blue) at 35 % over the view, multiplied on
 * bright tints and screened on dark ones, plus a tinted hairline at 60 %. [systemDark] is
 * accepted for symmetry with [widgetPalette]; Auto keeps the provider's own day/night handling,
 * so it does not change the result, and [darkBackground] only affects Glass.
 */
@Suppress("UNUSED_PARAMETER")
fun hostFrameSpec(appearance: WidgetAppearance, tint: Int?, systemDark: Boolean, darkBackground: Boolean = false): HostFrameSpec = when (appearance) {
    WidgetAppearance.Auto -> HostFrameSpec(framed = false)
    WidgetAppearance.Glass -> if (darkBackground)
        HostFrameSpec(true, HOST_FRAME_CORNER_DP, backing = withAlpha(0x1C1C1E, .55f),
            border = withAlpha(0xFFFFFF, .18f), contentAlpha = .9f, darkGlass = true)
    else HostFrameSpec(true, HOST_FRAME_CORNER_DP, backing = withAlpha(0xFFFFFF, .2f),
        border = withAlpha(0xFFFFFF, .25f), contentAlpha = .9f)
    WidgetAppearance.Light -> HostFrameSpec(true, HOST_FRAME_CORNER_DP, backing = withAlpha(0xFFFFFF, .88f))
    WidgetAppearance.Dark -> HostFrameSpec(true, HOST_FRAME_CORNER_DP, backing = withAlpha(0x1C1C1E, .85f))
    WidgetAppearance.Tinted -> tintedFrameSpec(tint ?: WIDGET_TINT_PRESETS.first().first)
    WidgetAppearance.Wallpaper -> tintedFrameSpec(tint ?: WallpaperPalette.FALLBACK_ACCENT)
}

/** Shared body of [WidgetAppearance.Tinted] and [WidgetAppearance.Wallpaper]'s host frame: an overlay/hairline of [colour]. */
private fun tintedFrameSpec(colour: Int): HostFrameSpec = HostFrameSpec(true, HOST_FRAME_CORNER_DP,
    border = withAlpha(colour.toLong(), .6f), overlay = withAlpha(colour.toLong(), .35f),
    blend = if (prefersDarkInk(colour)) HostFrameBlend.Multiply else HostFrameBlend.Screen)

/**
 * Dominant colour of a wallpaper, from its ARGB [pixels] (a small scaled copy): pixels are binned
 * to 4 bits per channel, saturated pixels count more than grey ones, and the result is the mean
 * of the heaviest bin so it is a colour that actually occurs. Null when nothing is opaque.
 */
fun dominantColorArgb(pixels: IntArray): Int? {
    val weight = HashMap<Int, Double>()
    val sums = HashMap<Int, DoubleArray>()
    for (argb in pixels) {
        if ((argb ushr 24) < 0x80) continue
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val saturation = if (max == 0) 0.0 else (max - min).toDouble() / max
        val w = 1.0 + saturation * 3.0
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        weight[key] = (weight[key] ?: 0.0) + w
        val sum = sums.getOrPut(key) { DoubleArray(4) }
        sum[0] += r * w; sum[1] += g * w; sum[2] += b * w; sum[3] += w
    }
    val best = weight.maxByOrNull { it.value }?.key ?: return null
    val sum = sums.getValue(best)
    fun mean(index: Int) = (sum[index] / sum[3]).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (mean(0) shl 16) or (mean(1) shl 8) or mean(2)
}
