package cz.pflanzer.foldduo.island

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.session.MediaController
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What a rail island entry stands for; the order here is not the ranking (see [islandKindRank]). */
enum class IslandKind { CALL, NAVIGATION, TIMER, MEDIA, PROGRESS, TRANSPORT, WORKOUT, OTHER }

/** One notification action shown in the expanded card (at most two per item). */
data class IslandAction(val title: String, val intent: PendingIntent? = null)

/**
 * The live media session behind a [IslandKind.MEDIA] item; the controller drives the transport
 * buttons. [artwork]/[positionMs]/[durationMs] feed the expanded card's growing layout ("Ostrůvek
 * do plochy": artwork thumbnail, elapsed/remaining labels) — all default to "unknown" so a session
 * without rich metadata still renders, just without those extras.
 */
data class IslandMedia(
    val playing: Boolean,
    val controller: MediaController? = null,
    val artwork: Bitmap? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** `PlaybackState.lastPositionUpdateTime` (`SystemClock.elapsedRealtime()`, never wall time) — the base [extrapolatedPositionMs] advances from while playing. */
    val positionUpdatedAtMs: Long = 0L,
    /** `PlaybackState.playbackSpeed` (1.0 normal); feeds the same extrapolation. */
    val playbackSpeed: Float = 1f,
)

/**
 * One promoted / ongoing entry of the rail island (Android 16 Live Update, an ongoing call,
 * navigation, a timer, or an active media session). Android types are nullable so the pure
 * ranking and layout functions can be exercised on the JVM.
 */
data class IslandItem(
    val key: String,
    val packageName: String = "",
    val appIcon: Drawable? = null,
    val smallIcon: Icon? = null,
    val title: String = "",
    val text: String = "",
    /** 0..1 fraction; null when the notification carries no determinate progress. */
    val progress: Float? = null,
    val progressIndeterminate: Boolean = false,
    /** Wall-clock base (`Notification.when`, ms) of a running chronometer; counts down when [countDown]. */
    val chronometerBase: Long? = null,
    val countDown: Boolean = false,
    val contentIntent: PendingIntent? = null,
    val actions: List<IslandAction> = emptyList(),
    val kind: IslandKind = IslandKind.OTHER,
    /** `StatusBarNotification.postTime` (or the moment a media session appeared); newest ranks first within a kind. */
    val postedAt: Long = 0L,
    val media: IslandMedia? = null,
    /** ARGB tint of the pill (Samsung Now Bar `chipBgColor`); null keeps the plain glass. */
    val accentColor: Int? = null,
    /** True for a Samsung Now Bar ongoing activity; drives the cross-slide-vs-crossfade choice in [cz.pflanzer.foldduo.island.RailIsland] (B30 item 3). */
    val isNowBar: Boolean = false,
    /** B61 phase 2 (live activities): wall-clock estimated arrival/completion time, from [cz.pflanzer.foldduo.island.matchLiveActivity]; null when no adapter parsed an ETA. */
    val etaMs: Long? = null,
    /** B61: stage names of a delivery/ride/Live-Update journey, e.g. "Objednáno · Připravuje se · Kurýr · Doručeno"; empty when the source carries no stage list. */
    val segments: List<String> = emptyList(),
    /** B61: index into [segments] of the current stage; null without a recognised stage. */
    val stageIndex: Int? = null,
    /** B61: the short trailing-slot text a compact pill shows next to the icon — "4 min", "12:34", "Kurýr" — distinct from [progress]'s percentage. */
    val keyValue: String? = null,
    /** B61: a stable, non-null marker once the source notification carries a large icon / picture (`EXTRA_PICTURE`-style); the expanded card uses its presence to decide whether to reserve a picture slot. Never the actual image — that stays in [appIcon]/[smallIcon]. */
    val pictureKey: String? = null,
)

/**
 * `Notification.FLAG_PROMOTED_ONGOING` (Android 16 Live Updates, `1 shl 18`). compileSdk 36 ships
 * the constant; it is repeated here by value so the pure classifier stays free of platform stubs.
 */
const val FLAG_PROMOTED_ONGOING = 1 shl 18

/** Ongoing categories that qualify for the island without the promoted flag (the pre-16 fallback). */
internal val ISLAND_CATEGORY_KINDS: Map<String, IslandKind> = mapOf(
    Notification.CATEGORY_CALL to IslandKind.CALL,
    Notification.CATEGORY_NAVIGATION to IslandKind.NAVIGATION,
    Notification.CATEGORY_TRANSPORT to IslandKind.TRANSPORT,
    Notification.CATEGORY_PROGRESS to IslandKind.PROGRESS,
    Notification.CATEGORY_ALARM to IslandKind.TIMER,
    Notification.CATEGORY_STOPWATCH to IslandKind.TIMER,
    Notification.CATEGORY_WORKOUT to IslandKind.WORKOUT,
)

/**
 * Prefix of the extras Samsung One UI attaches to a Now Bar "ongoing activity" notification
 * (One UI 7+; Clock timers and stopwatches, Samsung calls, some navigation apps). These carry
 * no `android.category`, no `showChronometer` and no promoted flag, so they need their own path.
 */
const val SAMSUNG_ONGOING_PREFIX = "android.ongoingActivityNoti."
/** Now Bar headline, e.g. "Timer", "Stopwatch". */
const val SAMSUNG_ONGOING_PRIMARY = SAMSUNG_ONGOING_PREFIX + "nowbarPrimaryInfo"
/** Now Bar detail line, e.g. "Pasta / 20:51" (timer label / end time). */
const val SAMSUNG_ONGOING_SECONDARY = SAMSUNG_ONGOING_PREFIX + "secondaryInfo"
/** A `RemoteViews` holding a running `Chronometer`; the countdown is only published this way. */
const val SAMSUNG_ONGOING_CHRONOMETER = SAMSUNG_ONGOING_PREFIX + "chronometerRemoteView"
/** The chip's `Icon` and ARGB background as the Now Bar draws them. */
const val SAMSUNG_ONGOING_CHIP_ICON = SAMSUNG_ONGOING_PREFIX + "chipIcon"
const val SAMSUNG_ONGOING_CHIP_BG = SAMSUNG_ONGOING_PREFIX + "chipBgColor"

/** True when the notification's extras mark it as a Samsung Now Bar ongoing activity. */
fun isSamsungOngoingActivity(extrasKeys: Set<String>): Boolean =
    extrasKeys.any { it.startsWith(SAMSUNG_ONGOING_PREFIX) }

private val SAMSUNG_TIMER_WORDS = listOf("timer", "stopwatch", "časovač", "stopky", "minutka")
private val SAMSUNG_NAVIGATION_WORDS = listOf("navigation", "navigace", "navigat", "route", "trasa")
private val SAMSUNG_CALL_WORDS = listOf("call", "hovor", "volání")
private val SAMSUNG_MEDIA_WORDS = listOf("music", "hudba", "playing", "přehráv", "podcast", "radio")
private val SAMSUNG_NAVIGATION_PACKAGES = listOf("maps", "navigation", "navigator", "waze", "mapy.cz", "sygic", "here.app", "tomtom", "osmand")
private val SAMSUNG_CALL_PACKAGES = listOf("dialer", "incallui", "telecom", "telephony", "whatsapp", "telegram", "viber", "signal", "meet", "teams", "zoom", "skype")
private val SAMSUNG_MEDIA_PACKAGES = listOf("music", "spotify", "youtube", "podcast", "audible", "deezer", "tidal", "soundcloud", "player", "radio")

/**
 * The island kind of a Samsung ongoing activity, from the Now Bar headline first ("Timer",
 * "Stopwatch", "Navigation", "Call") and the package name second (maps, dialers, players).
 * Anything unrecognised is still shown, as [IslandKind.OTHER].
 */
fun samsungOngoingKind(primaryInfo: String?, packageName: String): IslandKind {
    val info = primaryInfo.orEmpty().trim().lowercase()
    val pkg = packageName.lowercase()
    fun String.hasAny(words: List<String>) = words.any { it in this }
    return when {
        info.hasAny(SAMSUNG_TIMER_WORDS) -> IslandKind.TIMER
        info.hasAny(SAMSUNG_NAVIGATION_WORDS) -> IslandKind.NAVIGATION
        info.hasAny(SAMSUNG_CALL_WORDS) -> IslandKind.CALL
        info.hasAny(SAMSUNG_MEDIA_WORDS) -> IslandKind.TRANSPORT
        pkg == "com.sec.android.app.clockpackage" || pkg.endsWith(".deskclock") -> IslandKind.TIMER
        pkg.hasAny(SAMSUNG_NAVIGATION_PACKAGES) -> IslandKind.NAVIGATION
        pkg.hasAny(SAMSUNG_CALL_PACKAGES) -> IslandKind.CALL
        pkg.hasAny(SAMSUNG_MEDIA_PACKAGES) -> IslandKind.TRANSPORT
        else -> IslandKind.OTHER
    }
}

private val SAMSUNG_END_TIME = Regex("""(\d{1,2})[:.](\d{2})\s*([AaPp])?\.?[Mm]?\.?\s*$""")

/** Past by up to this much still counts as "ending now": the Now Bar line only has minute resolution. */
const val SAMSUNG_END_TIME_GRACE_MS = 60_000L

/**
 * Fallback countdown for a Samsung timer whose `RemoteViews` chronometer could not be read:
 * the Now Bar detail line ends with the end time of day ("Pasta / 20:51", "8:51 PM"), which is
 * resolved against today in [zone]; an end more than [SAMSUNG_END_TIME_GRACE_MS] in the past
 * means tomorrow. Null when the line carries no clock time.
 */
fun samsungOngoingEndTime(secondaryInfo: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val match = SAMSUNG_END_TIME.find(secondaryInfo.orEmpty().trim()) ?: return null
    val (h, m, meridiem) = match.destructured
    var hour = h.toInt()
    val minute = m.toInt()
    if (meridiem.isNotEmpty()) {
        if (hour !in 1..12) return null
        hour %= 12
        if (meridiem.equals("p", ignoreCase = true)) hour += 12
    }
    if (hour !in 0..23 || minute !in 0..59) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    var end = today.atTime(LocalTime.of(hour, minute)).atZone(zone)
    if (end.toInstant().toEpochMilli() < now - SAMSUNG_END_TIME_GRACE_MS) end = end.plusDays(1)
    return end.toInstant().toEpochMilli()
}

private val MEDIA_ONGOING_ACTIVITY_MARKER = Regex("mediaongoingactivity", RegexOption.IGNORE_CASE)

/**
 * True when [value] names Samsung's own "MediaOngoingActivity" Now Bar template/class — the bug
 * Tom hit on the device: starting a track posts a second, redundant pill for the same playback
 * our [IslandKind.MEDIA] session item already shows, because the Now Bar's ongoing-activity
 * notification for media carries no readable headline extra and the raw template/class name leaks
 * into the title instead.
 */
fun isMediaOngoingActivityTemplate(value: String?): Boolean = value != null && MEDIA_ONGOING_ACTIVITY_MARKER.containsMatchIn(value)

/**
 * True when a Samsung Now Bar item at [nowBarPackage] duplicates a live [IslandKind.MEDIA] session
 * we already show: either its own package or a package literally named in one of its string
 * extras ([extraPackageCandidates] — the Now Bar sometimes posts under a system package on behalf
 * of the player) matches [sessionPackages], or — only once we know at least one session exists —
 * it flags itself as a media template ([hasMediaSessionExtra], `Notification.EXTRA_MEDIA_SESSION`)
 * or its title/class/tag matches [isMediaOngoingActivityTemplate] ([templateHints]). Without any
 * active session there is nothing to prefer over it, so it is never treated as a duplicate purely
 * from the template marker — see [resolveNowBarTitle] for sanitizing its title instead.
 */
fun isDuplicateNowBarMediaItem(
    nowBarPackage: String,
    extraPackageCandidates: Collection<String>,
    sessionPackages: Set<String>,
    hasMediaSessionExtra: Boolean,
    templateHints: Collection<String>,
): Boolean {
    if (nowBarPackage in sessionPackages) return true
    if (extraPackageCandidates.any { it in sessionPackages }) return true
    if (sessionPackages.isEmpty()) return false
    return hasMediaSessionExtra || templateHints.any(::isMediaOngoingActivityTemplate)
}

/**
 * The title actually shown for a Now Bar item: [rawTitle] unless it is blank or itself a leaked
 * "MediaOngoingActivity" class/template name, in which case [appLabel] is shown instead — "never
 * show a bare class-name title to the user."
 */
fun resolveNowBarTitle(rawTitle: String, appLabel: String): String =
    if (rawTitle.isBlank() || isMediaOngoingActivityTemplate(rawTitle)) appLabel else rawTitle

/** The label part of a Now Bar detail line ("Pasta / 20:51" → "Pasta"); the whole line when it has no time. */
fun samsungOngoingLabel(secondaryInfo: String?): String {
    val line = secondaryInfo.orEmpty().trim()
    val slash = line.lastIndexOf(" / ")
    return (if (slash >= 0) line.substring(0, slash) else line.takeUnless { SAMSUNG_END_TIME.matches(it) }.orEmpty()).trim()
}

/**
 * Decide whether a posted notification belongs in the island and as what. Promoted ongoing
 * notifications (Live Updates), Samsung Now Bar ongoing activities ([samsungOngoing], see
 * [samsungOngoingKind]) and recognised ride/delivery/navigation apps without any of those signals
 * ([appOngoingKind], see [cz.pflanzer.foldduo.island.appOngoingKindFor] — B61 phase 2: Uber, Bolt,
 * Rohlík, Wolt and friends post a plain ongoing notification with no `android.category` and no
 * progress bar) always qualify; other plain ongoing ones only through a recognised category, a
 * visible chronometer (Samsung Clock timers), or a media-style template.
 * Returns null for everything else, including one-shot notifications with a live category.
 */
fun islandKindFor(
    flags: Int,
    category: String?,
    showsChronometer: Boolean = false,
    hasProgress: Boolean = false,
    mediaStyle: Boolean = false,
    samsungOngoing: IslandKind? = null,
    appOngoingKind: IslandKind? = null,
): IslandKind? {
    val ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0
    // A recognised ride/delivery/navigation app only qualifies through its *ongoing* notification:
    // Google Maps' one-shot "rate this place" got into the camera island (17. 9. device report).
    val appLive = appOngoingKind?.takeIf { ongoing }
    val promoted = flags and FLAG_PROMOTED_ONGOING != 0 || samsungOngoing != null || appLive != null
    val byCategory = ISLAND_CATEGORY_KINDS[category]
    val byShape = when {
        mediaStyle -> IslandKind.TRANSPORT
        showsChronometer -> IslandKind.TIMER
        hasProgress -> IslandKind.PROGRESS
        else -> null
    }
    return when {
        promoted -> byCategory ?: samsungOngoing ?: appLive ?: byShape ?: IslandKind.OTHER
        !ongoing -> null
        byCategory != null -> byCategory
        else -> byShape.takeIf { it != IslandKind.PROGRESS }
    }
}

/** call > navigation > timer > media > everything else. */
fun islandKindRank(kind: IslandKind): Int = when (kind) {
    IslandKind.CALL -> 0
    IslandKind.NAVIGATION -> 1
    IslandKind.TIMER -> 2
    IslandKind.MEDIA -> 3
    IslandKind.PROGRESS, IslandKind.TRANSPORT, IslandKind.WORKOUT, IslandKind.OTHER -> 4
}

/** Stable island order: by kind rank, newest first within a kind, key as the final tiebreak. */
fun rankIslandItems(items: List<IslandItem>): List<IslandItem> =
    items.sortedWith(compareBy<IslandItem> { islandKindRank(it.kind) }.thenByDescending { it.postedAt }.thenBy { it.key })

/**
 * Merge notification-derived items with active media sessions. A media-style (transport)
 * notification whose package also has a live session is dropped: the session carries the
 * same track with real transport controls, so the island must not show the player twice.
 */
fun mergeIslandItems(notifications: List<IslandItem>, sessions: List<IslandItem>): List<IslandItem> {
    val sessionPackages = sessions.mapTo(HashSet()) { it.packageName }
    val kept = notifications.filterNot { it.kind == IslandKind.TRANSPORT && it.packageName in sessionPackages }
    return rankIslandItems(kept + sessions)
}

/** `m:ss` under an hour, `h:mm:ss` above; never negative. */
fun chronometerText(base: Long, now: Long, countDown: Boolean): String {
    val total = (if (countDown) base - now else now - base).coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = total % 3600L / 60L
    val seconds = total % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * `m:ss` of a non-negative duration (never `h:mm:ss` — a track's elapsed/remaining time never
 * needs the hour digit the way a long chronometer does); a negative input clamps to `0:00`.
 * "Ostrůvek do plochy": the expanded media card's elapsed label.
 */
fun mediaTimeLabel(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

/**
 * The remaining-time label for a media card ("-m:ss"): [mediaTimeLabel] of `duration - position`,
 * clamped to `0:00` once played past the reported duration rather than going negative.
 */
fun mediaRemainingLabel(positionMs: Long, durationMs: Long): String =
    "-" + mediaTimeLabel(durationMs - positionMs)

/**
 * The one-line glyph text of a collapsed pill: a running chronometer for timers, calls and
 * workouts, a percentage for progress, the title for navigation and other text-led updates.
 * Null means "no text; the pill shows an icon only" (media, and anything without a label).
 */
fun compactIslandLabel(item: IslandItem, now: Long): String? {
    item.chronometerBase?.let { return chronometerText(it, now, item.countDown) }
    if (item.kind == IslandKind.MEDIA) return null
    item.progress?.let { return "${(it * 100f).toInt().coerceIn(0, 100)}%" }
    return item.title.takeIf { it.isNotBlank() }
}
