package cz.pflanzer.foldduo

import android.app.Notification
import cz.pflanzer.foldduo.island.FLAG_PROMOTED_ONGOING
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.IslandMedia
import cz.pflanzer.foldduo.island.SAMSUNG_END_TIME_GRACE_MS
import cz.pflanzer.foldduo.island.SAMSUNG_ONGOING_CHIP_BG
import cz.pflanzer.foldduo.island.SAMSUNG_ONGOING_CHRONOMETER
import cz.pflanzer.foldduo.island.SAMSUNG_ONGOING_PREFIX
import cz.pflanzer.foldduo.island.SAMSUNG_ONGOING_PRIMARY
import cz.pflanzer.foldduo.island.SAMSUNG_ONGOING_SECONDARY
import cz.pflanzer.foldduo.island.chronometerText
import cz.pflanzer.foldduo.island.compactIslandLabel
import cz.pflanzer.foldduo.island.isDuplicateNowBarMediaItem
import cz.pflanzer.foldduo.island.isMediaOngoingActivityTemplate
import cz.pflanzer.foldduo.island.isSamsungOngoingActivity
import cz.pflanzer.foldduo.island.islandKindFor
import cz.pflanzer.foldduo.island.islandKindRank
import cz.pflanzer.foldduo.island.mediaRemainingLabel
import cz.pflanzer.foldduo.island.mediaTimeLabel
import cz.pflanzer.foldduo.island.mergeIslandItems
import cz.pflanzer.foldduo.island.rankIslandItems
import cz.pflanzer.foldduo.island.resolveNowBarTitle
import cz.pflanzer.foldduo.island.samsungOngoingEndTime
import cz.pflanzer.foldduo.island.samsungOngoingKind
import cz.pflanzer.foldduo.island.samsungOngoingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The rail island's pure rules (island/IslandItem.kt): what qualifies, how it ranks, what a pill says. */
class IslandItemTest {
    private val ongoing = Notification.FLAG_ONGOING_EVENT
    private val promoted = FLAG_PROMOTED_ONGOING

    // 17. 9. device report: Google Maps' one-shot "rate this place" showed up in the camera island.
    @Test fun `an allowlisted app qualifies only through an ongoing notification`() {
        assertEquals(null, islandKindFor(0, null, appOngoingKind = IslandKind.NAVIGATION))
        assertEquals(IslandKind.NAVIGATION, islandKindFor(ongoing, null, appOngoingKind = IslandKind.NAVIGATION))
        assertEquals(IslandKind.TRANSPORT, islandKindFor(ongoing, null, appOngoingKind = IslandKind.TRANSPORT))
    }

    @Test fun `promoted flag is Android 16 FLAG_PROMOTED_ONGOING`() {
        assertEquals(1 shl 18, FLAG_PROMOTED_ONGOING)
        assertEquals(262144, FLAG_PROMOTED_ONGOING)
    }

    @Test fun `promoted ongoing notifications always qualify and take the category kind`() {
        assertEquals(IslandKind.NAVIGATION, islandKindFor(promoted, Notification.CATEGORY_NAVIGATION))
        assertEquals(IslandKind.CALL, islandKindFor(promoted or ongoing, Notification.CATEGORY_CALL))
        assertEquals(IslandKind.TIMER, islandKindFor(promoted, Notification.CATEGORY_ALARM))
        assertEquals(IslandKind.TIMER, islandKindFor(promoted, Notification.CATEGORY_STOPWATCH))
        assertEquals(IslandKind.WORKOUT, islandKindFor(promoted, Notification.CATEGORY_WORKOUT))
        assertEquals(IslandKind.TRANSPORT, islandKindFor(promoted, Notification.CATEGORY_TRANSPORT))
        assertEquals(IslandKind.PROGRESS, islandKindFor(promoted, Notification.CATEGORY_PROGRESS))
        // No category: the shape decides; nothing recognisable is still a Live Update ("other").
        assertEquals(IslandKind.TIMER, islandKindFor(promoted, null, showsChronometer = true))
        assertEquals(IslandKind.PROGRESS, islandKindFor(promoted, null, hasProgress = true))
        assertEquals(IslandKind.TRANSPORT, islandKindFor(promoted, null, mediaStyle = true))
        assertEquals(IslandKind.OTHER, islandKindFor(promoted, null))
        assertEquals(IslandKind.OTHER, islandKindFor(promoted, Notification.CATEGORY_MESSAGE))
    }

    @Test fun `plain ongoing notifications qualify only through a live category chronometer or media style`() {
        assertEquals(IslandKind.CALL, islandKindFor(ongoing, Notification.CATEGORY_CALL))
        assertEquals(IslandKind.NAVIGATION, islandKindFor(ongoing, Notification.CATEGORY_NAVIGATION))
        assertEquals(IslandKind.TRANSPORT, islandKindFor(ongoing, Notification.CATEGORY_TRANSPORT))
        assertEquals(IslandKind.PROGRESS, islandKindFor(ongoing, Notification.CATEGORY_PROGRESS))
        assertEquals(IslandKind.TIMER, islandKindFor(ongoing, Notification.CATEGORY_ALARM))
        assertEquals(IslandKind.WORKOUT, islandKindFor(ongoing, Notification.CATEGORY_WORKOUT))
        // Samsung Clock style: ongoing with a visible chronometer and no category.
        assertEquals(IslandKind.TIMER, islandKindFor(ongoing, null, showsChronometer = true))
        // MediaStyle player without a category (the session will usually replace it).
        assertEquals(IslandKind.TRANSPORT, islandKindFor(ongoing, null, mediaStyle = true))
        // A foreground service with a bare progress bar (downloads, sync) is not a Live Update.
        assertNull(islandKindFor(ongoing, null, hasProgress = true))
        assertNull(islandKindFor(ongoing, Notification.CATEGORY_SERVICE))
        assertNull(islandKindFor(ongoing, Notification.CATEGORY_MESSAGE))
        assertNull(islandKindFor(ongoing, null))
    }

    // Samsung One UI Now Bar "ongoing activity" extras, as a One UI 9 Clock timer posts them.
    private val samsungTimerKeys = setOf("android.title", "android.showWhen", "android.substName",
        SAMSUNG_ONGOING_PRIMARY, SAMSUNG_ONGOING_SECONDARY, SAMSUNG_ONGOING_CHRONOMETER, SAMSUNG_ONGOING_CHIP_BG,
        "${SAMSUNG_ONGOING_PREFIX}style", "${SAMSUNG_ONGOING_PREFIX}chronometerRemoteViewTag",
        "com.samsung.android.widgetComponentName", "com.samsung.android.widgetIcon")

    @Test fun `samsung ongoing activity is recognised by the ongoingActivityNoti extras prefix`() {
        assertEquals("android.ongoingActivityNoti.", SAMSUNG_ONGOING_PREFIX)
        assertTrue(isSamsungOngoingActivity(samsungTimerKeys))
        assertTrue(isSamsungOngoingActivity(setOf("${SAMSUNG_ONGOING_PREFIX}style")))
        assertFalse(isSamsungOngoingActivity(setOf("android.title", "android.showChronometer", "com.samsung.android.widgetIcon")))
        assertFalse(isSamsungOngoingActivity(setOf("ongoingActivityNoti.style", "android.ongoingActivityNoti")))
        assertFalse(isSamsungOngoingActivity(emptySet()))
    }

    @Test fun `samsung ongoing kind comes from the Now Bar headline first and the package second`() {
        val clock = "com.sec.android.app.clockpackage"
        assertEquals(IslandKind.TIMER, samsungOngoingKind("Timer", clock))
        assertEquals(IslandKind.TIMER, samsungOngoingKind("Stopwatch", clock))
        assertEquals(IslandKind.TIMER, samsungOngoingKind("Časovač", clock))
        assertEquals(IslandKind.TIMER, samsungOngoingKind(null, clock))
        assertEquals(IslandKind.TIMER, samsungOngoingKind("", "com.google.android.deskclock"))
        assertEquals(IslandKind.NAVIGATION, samsungOngoingKind("Navigation", "com.example"))
        assertEquals(IslandKind.NAVIGATION, samsungOngoingKind("200 m", "com.google.android.apps.maps"))
        assertEquals(IslandKind.NAVIGATION, samsungOngoingKind(null, "com.waze"))
        assertEquals(IslandKind.NAVIGATION, samsungOngoingKind("Trasa", "cz.seznam.mapy"))
        assertEquals(IslandKind.CALL, samsungOngoingKind("Call", "com.example"))
        assertEquals(IslandKind.CALL, samsungOngoingKind("Mum", "com.samsung.android.dialer"))
        assertEquals(IslandKind.CALL, samsungOngoingKind("Mum", "com.samsung.android.incallui"))
        assertEquals(IslandKind.CALL, samsungOngoingKind("Ongoing", "com.whatsapp"))
        assertEquals(IslandKind.TRANSPORT, samsungOngoingKind("Music", "com.example"))
        assertEquals(IslandKind.TRANSPORT, samsungOngoingKind("Weightless", "com.spotify.music"))
        assertEquals(IslandKind.OTHER, samsungOngoingKind("Delivery", "com.example.food"))
        assertEquals(IslandKind.OTHER, samsungOngoingKind(null, ""))
        // The headline wins over the package: a maps app driving a "Timer" is still a timer.
        assertEquals(IslandKind.TIMER, samsungOngoingKind("Timer", "com.google.android.apps.maps"))
    }

    @Test fun `samsung ongoing activities qualify like promoted ones and the category still wins`() {
        // The Clock timer: FLAG_ONGOING_EVENT only, no category, no showChronometer.
        assertEquals(IslandKind.TIMER, islandKindFor(ongoing, null, samsungOngoing = IslandKind.TIMER))
        assertEquals(IslandKind.OTHER, islandKindFor(ongoing, null, samsungOngoing = IslandKind.OTHER))
        // Now Bar activities are live by definition, whatever the flags say.
        assertEquals(IslandKind.NAVIGATION, islandKindFor(0, null, samsungOngoing = IslandKind.NAVIGATION))
        assertEquals(IslandKind.CALL, islandKindFor(ongoing, Notification.CATEGORY_CALL, samsungOngoing = IslandKind.OTHER))
        assertEquals(IslandKind.CALL, islandKindFor(promoted, Notification.CATEGORY_CALL, samsungOngoing = IslandKind.TIMER))
        // Samsung kind beats the shape heuristics but a recognised category still comes first.
        assertEquals(IslandKind.TIMER, islandKindFor(ongoing, null, mediaStyle = true, samsungOngoing = IslandKind.TIMER))
        assertEquals(IslandKind.TIMER, islandKindFor(promoted, null, hasProgress = true, samsungOngoing = IslandKind.TIMER))
        // Without the Samsung extras nothing changes.
        assertNull(islandKindFor(ongoing, null, samsungOngoing = null))
        assertNull(islandKindFor(0, null))
    }

    @Test fun `samsung end time line resolves to today in the given zone or tomorrow once past`() {
        val prague = ZoneId.of("Europe/Prague")
        val now = ZonedDateTime.of(2026, 9, 14, 20, 46, 15, 0, prague).toInstant().toEpochMilli()
        val end2051 = ZonedDateTime.of(2026, 9, 14, 20, 51, 0, 0, prague).toInstant().toEpochMilli()
        assertEquals(end2051, samsungOngoingEndTime("FoldDuo / 20:51", now, prague))
        assertEquals(end2051, samsungOngoingEndTime("20:51", now, prague))
        assertEquals(end2051, samsungOngoingEndTime("Pasta / 8:51 PM", now, prague))
        assertEquals(end2051, samsungOngoingEndTime("Pasta / 8:51 pm", now, prague))
        assertEquals(end2051, samsungOngoingEndTime("Pasta / 8:51 p.m.", now, prague))
        assertEquals(end2051, samsungOngoingEndTime("Pasta / 20.51", now, prague))
        val end0851 = ZonedDateTime.of(2026, 9, 15, 8, 51, 0, 0, prague).toInstant().toEpochMilli()
        assertEquals(end0851, samsungOngoingEndTime("Pasta / 8:51 AM", now, prague))
        // A 12 h timer wraps past midnight into tomorrow.
        val end0300 = ZonedDateTime.of(2026, 9, 15, 3, 0, 0, 0, prague).toInstant().toEpochMilli()
        assertEquals(end0300, samsungOngoingEndTime("Bread / 03:00", now, prague))
        // Within the minute-resolution grace the end stays today (the countdown reads 0:00).
        val justPast = end2051 + SAMSUNG_END_TIME_GRACE_MS
        assertEquals(end2051, samsungOngoingEndTime("FoldDuo / 20:51", justPast, prague))
        assertEquals(end2051 + 86_400_000L, samsungOngoingEndTime("FoldDuo / 20:51", justPast + 1L, prague))
        // The countdown text follows from the derived base.
        assertEquals("4:45", chronometerText(samsungOngoingEndTime("FoldDuo / 20:51", now, prague)!!, now, countDown = true))
        // Another zone gives another instant for the same wall time.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val nowTokyo = ZonedDateTime.of(2026, 9, 14, 20, 46, 15, 0, tokyo).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 9, 14, 20, 51, 0, 0, tokyo).toInstant().toEpochMilli(),
            samsungOngoingEndTime("20:51", nowTokyo, tokyo))
        // No clock time, or nonsense: no fallback.
        assertNull(samsungOngoingEndTime("FoldDuo", now, prague))
        assertNull(samsungOngoingEndTime(null, now, prague))
        assertNull(samsungOngoingEndTime("", now, prague))
        assertNull(samsungOngoingEndTime("Lap 3 / 24:99", now, prague))
        assertNull(samsungOngoingEndTime("25:10", now, prague))
        assertNull(samsungOngoingEndTime("13:10 PM", now, prague))
    }

    @Test fun `samsung detail line label is the part before the slash`() {
        assertEquals("FoldDuo", samsungOngoingLabel("FoldDuo / 20:51"))
        assertEquals("Pasta al dente", samsungOngoingLabel("  Pasta al dente / 8:51 PM "))
        assertEquals("", samsungOngoingLabel("20:51"))
        assertEquals("Lap 3", samsungOngoingLabel("Lap 3"))
        assertEquals("", samsungOngoingLabel(null))
        assertEquals("", samsungOngoingLabel(""))
    }

    @Test fun `one-shot notifications never qualify even with a live category`() {
        assertNull(islandKindFor(0, Notification.CATEGORY_CALL))
        assertNull(islandKindFor(0, Notification.CATEGORY_NAVIGATION, showsChronometer = true))
        assertNull(islandKindFor(Notification.FLAG_AUTO_CANCEL, Notification.CATEGORY_ALARM))
        assertNull(islandKindFor(0, null, mediaStyle = true))
    }

    @Test fun `ranking is call then navigation then timer then media then the rest, newest first`() {
        val items = listOf(
            IslandItem("other-new", kind = IslandKind.OTHER, postedAt = 90),
            IslandItem("media", kind = IslandKind.MEDIA, postedAt = 50, media = IslandMedia(true)),
            IslandItem("timer-old", kind = IslandKind.TIMER, postedAt = 10),
            IslandItem("timer-new", kind = IslandKind.TIMER, postedAt = 20),
            IslandItem("progress", kind = IslandKind.PROGRESS, postedAt = 95),
            IslandItem("nav", kind = IslandKind.NAVIGATION, postedAt = 5),
            IslandItem("workout", kind = IslandKind.WORKOUT, postedAt = 80),
            IslandItem("call", kind = IslandKind.CALL, postedAt = 1),
            IslandItem("transport", kind = IslandKind.TRANSPORT, postedAt = 85),
        )
        assertEquals(listOf("call", "nav", "timer-new", "timer-old", "media", "progress", "other-new", "transport", "workout"),
            rankIslandItems(items).map { it.key })
        assertTrue(islandKindRank(IslandKind.CALL) < islandKindRank(IslandKind.NAVIGATION))
        assertTrue(islandKindRank(IslandKind.NAVIGATION) < islandKindRank(IslandKind.TIMER))
        assertTrue(islandKindRank(IslandKind.TIMER) < islandKindRank(IslandKind.MEDIA))
        assertTrue(islandKindRank(IslandKind.MEDIA) < islandKindRank(IslandKind.OTHER))
        assertEquals(islandKindRank(IslandKind.OTHER), islandKindRank(IslandKind.PROGRESS))
        // Ties on kind and time resolve by key so the order never flickers between publishes.
        val tie = listOf(IslandItem("b", kind = IslandKind.OTHER), IslandItem("a", kind = IslandKind.OTHER))
        assertEquals(listOf("a", "b"), rankIslandItems(tie).map { it.key })
        assertEquals(rankIslandItems(tie), rankIslandItems(tie.reversed()))
    }

    @Test fun `a media session replaces the same package's transport notification but nothing else`() {
        val spotifyNote = IslandItem("0|com.spotify|1", packageName = "com.spotify", kind = IslandKind.TRANSPORT, postedAt = 10)
        val spotifySession = IslandItem("media:com.spotify", packageName = "com.spotify", kind = IslandKind.MEDIA, postedAt = 5,
            media = IslandMedia(true))
        val mapsNote = IslandItem("0|com.maps|2", packageName = "com.maps", kind = IslandKind.NAVIGATION, postedAt = 20)
        val podcastNote = IslandItem("0|com.pods|3", packageName = "com.pods", kind = IslandKind.TRANSPORT, postedAt = 30)
        val merged = mergeIslandItems(listOf(spotifyNote, mapsNote, podcastNote), listOf(spotifySession))
        assertEquals(listOf("0|com.maps|2", "media:com.spotify", "0|com.pods|3"), merged.map { it.key })
        // Without a session the transport notification stays; a call from the same package is never dropped.
        assertEquals(listOf("0|com.spotify|1"), mergeIslandItems(listOf(spotifyNote), emptyList()).map { it.key })
        val spotifyCall = spotifyNote.copy(key = "call", kind = IslandKind.CALL)
        assertEquals(listOf("call", "media:com.spotify"), mergeIslandItems(listOf(spotifyCall), listOf(spotifySession)).map { it.key })
        assertEquals(emptyList<IslandItem>(), mergeIslandItems(emptyList(), emptyList()))
    }

    @Test fun `chronometer text counts up or down in m ss and h mm ss and never goes negative`() {
        assertEquals("0:00", chronometerText(1_000L, 1_000L, countDown = false))
        assertEquals("0:05", chronometerText(0L, 5_500L, countDown = false))
        assertEquals("12:07", chronometerText(0L, 727_000L, countDown = false))
        assertEquals("1:00:00", chronometerText(0L, 3_600_000L, countDown = false))
        assertEquals("2:03:04", chronometerText(0L, 7_384_000L, countDown = false))
        // Countdown: the base is the target time.
        assertEquals("4:59", chronometerText(300_000L, 1_000L, countDown = true))
        assertEquals("0:00", chronometerText(300_000L, 400_000L, countDown = true))
        assertEquals("0:00", chronometerText(500_000L, 100_000L, countDown = false))
    }

    @Test fun `compact pill label prefers the chronometer then progress then the title, and media shows none`() {
        val now = 100_000L
        val timer = IslandItem("t", kind = IslandKind.TIMER, chronometerBase = now + 90_000L, countDown = true, title = "Timer")
        assertEquals("1:30", compactIslandLabel(timer, now))
        val call = IslandItem("c", kind = IslandKind.CALL, chronometerBase = now - 61_000L, title = "Mum")
        assertEquals("1:01", compactIslandLabel(call, now))
        val download = IslandItem("d", kind = IslandKind.PROGRESS, progress = .456f, title = "Update")
        assertEquals("45%", compactIslandLabel(download, now))
        val nav = IslandItem("n", kind = IslandKind.NAVIGATION, title = "200 m", text = "Turn right")
        assertEquals("200 m", compactIslandLabel(nav, now))
        val media = IslandItem("m", kind = IslandKind.MEDIA, title = "Song", progress = .5f, media = IslandMedia(false))
        assertNull(compactIslandLabel(media, now))
        assertNull(compactIslandLabel(IslandItem("blank", kind = IslandKind.OTHER, title = "  "), now))
        assertEquals("100%", compactIslandLabel(download.copy(progress = 1.4f), now))
    }

    // --- "Ostrůvek do plochy": media card elapsed/remaining labels (m:ss, -m:ss) ---

    @Test fun `media time label is m ss, never the hour digit a chronometer gets`() {
        assertEquals("0:00", mediaTimeLabel(0L))
        assertEquals("0:05", mediaTimeLabel(5_500L))
        assertEquals("1:30", mediaTimeLabel(90_000L))
        assertEquals("12:07", mediaTimeLabel(727_000L))
        // A track past an hour still just keeps counting minutes (no h:mm:ss switch for media).
        assertEquals("61:00", mediaTimeLabel(3_660_000L))
        // Negative input clamps rather than going negative.
        assertEquals("0:00", mediaTimeLabel(-500L))
    }

    @Test fun `media remaining label is a minus sign plus the time left, clamped at 0 00`() {
        assertEquals("-3:00", mediaRemainingLabel(positionMs = 0L, durationMs = 180_000L))
        assertEquals("-0:30", mediaRemainingLabel(positionMs = 150_000L, durationMs = 180_000L))
        assertEquals("-0:00", mediaRemainingLabel(positionMs = 180_000L, durationMs = 180_000L))
        // Played past the reported duration: still reads "-0:00", never a negative remaining time.
        assertEquals("-0:00", mediaRemainingLabel(positionMs = 200_000L, durationMs = 180_000L))
    }

    // --- Bug: Samsung Now Bar's own "MediaOngoingActivity" pill duplicates our media session ---

    @Test fun `a media ongoing activity template name is recognised case-insensitively, plain text is not`() {
        assertTrue(isMediaOngoingActivityTemplate("MediaOngoingActivity"))
        assertTrue(isMediaOngoingActivityTemplate("com.samsung.android.MediaOngoingActivity\$Template"))
        assertTrue(isMediaOngoingActivityTemplate("mediaongoingactivity"))
        assertFalse(isMediaOngoingActivityTemplate("Now playing"))
        assertFalse(isMediaOngoingActivityTemplate(null))
        assertFalse(isMediaOngoingActivityTemplate(""))
    }

    @Test fun `a now bar item is a duplicate when its own package already has a live media session`() {
        assertTrue(isDuplicateNowBarMediaItem("com.spotify.music", emptyList(), setOf("com.spotify.music"),
            hasMediaSessionExtra = false, templateHints = emptyList()))
    }

    @Test fun `a now bar item is a duplicate when a package named in its extras has a live session`() {
        // The Now Bar sometimes posts under a system package on the real player's behalf.
        assertTrue(isDuplicateNowBarMediaItem("com.samsung.android.rubin.app", listOf("com.spotify.music"),
            setOf("com.spotify.music"), hasMediaSessionExtra = false, templateHints = emptyList()))
        assertFalse(isDuplicateNowBarMediaItem("com.samsung.android.rubin.app", listOf("com.unrelated.app"),
            setOf("com.spotify.music"), hasMediaSessionExtra = false, templateHints = emptyList()))
    }

    @Test fun `a now bar item flagged as a media template is a duplicate only once a session exists`() {
        assertTrue(isDuplicateNowBarMediaItem("com.samsung.systemui", emptyList(), setOf("com.spotify.music"),
            hasMediaSessionExtra = true, templateHints = emptyList()))
        assertTrue(isDuplicateNowBarMediaItem("com.samsung.systemui", emptyList(), setOf("com.spotify.music"),
            hasMediaSessionExtra = false, templateHints = listOf("MediaOngoingActivity")))
        // No live session at all: nothing to prefer over it, so it is not treated as a duplicate.
        assertFalse(isDuplicateNowBarMediaItem("com.samsung.systemui", emptyList(), emptySet(),
            hasMediaSessionExtra = true, templateHints = listOf("MediaOngoingActivity")))
    }

    @Test fun `an unrelated timer or call now bar item is never treated as a media duplicate`() {
        assertFalse(isDuplicateNowBarMediaItem("com.sec.android.app.clockpackage", emptyList(), setOf("com.spotify.music"),
            hasMediaSessionExtra = false, templateHints = listOf("Timer")))
    }

    @Test fun `now bar title falls back to the app label when blank or a leaked class name`() {
        assertEquals("Spotify", resolveNowBarTitle("", "Spotify"))
        assertEquals("Spotify", resolveNowBarTitle("   ", "Spotify"))
        assertEquals("Spotify", resolveNowBarTitle("MediaOngoingActivity", "Spotify"))
        assertEquals("Bohemian Rhapsody", resolveNowBarTitle("Bohemian Rhapsody", "Spotify"))
    }
}
