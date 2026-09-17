package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.island.EXTRA_PROGRESS_SEGMENT_INDEX
import cz.pflanzer.foldduo.island.EXTRA_PROGRESS_SEGMENT_LABELS
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.LIVE_ACTIVITY_ALERT_WINDOW_MS
import cz.pflanzer.foldduo.island.NotificationFacts
import cz.pflanzer.foldduo.island.PKG_BOLT
import cz.pflanzer.foldduo.island.PKG_FOODORA
import cz.pflanzer.foldduo.island.PKG_GOOGLE_MAPS
import cz.pflanzer.foldduo.island.PKG_MAPY
import cz.pflanzer.foldduo.island.PKG_ROHLIK
import cz.pflanzer.foldduo.island.PKG_UBER
import cz.pflanzer.foldduo.island.PKG_WOLT
import cz.pflanzer.foldduo.island.appOngoingKindFor
import cz.pflanzer.foldduo.island.liveActivityBucket
import cz.pflanzer.foldduo.island.matchLiveActivity
import cz.pflanzer.foldduo.island.parseEtaAndKeyValue
import cz.pflanzer.foldduo.island.rankIslandItemsWithLiveActivities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B61 phase 2 ("Ostrov kolem kamery" live activities) — the pure adapters over [NotificationFacts]. */
class LiveActivityAdaptersTest {
    private val now = 1_726_000_000_000L

    private fun facts(
        packageName: String,
        title: String = "",
        text: String = "",
        subText: String? = null,
        ongoing: Boolean = true,
        promoted: Boolean = false,
        extras: Map<String, Any?> = emptyMap(),
        progress: Int? = null,
        progressMax: Int? = null,
        progressIndeterminate: Boolean = false,
        largeIconPresent: Boolean = false,
    ) = NotificationFacts(
        packageName = packageName, title = title, text = text, subText = subText, ongoing = ongoing,
        promoted = promoted, extras = extras, progress = progress, progressMax = progressMax,
        progressIndeterminate = progressIndeterminate, largeIconPresent = largeIconPresent,
    )

    @Test fun `appOngoingKindFor recognises the ride, delivery and navigation apps and nothing else`() {
        assertEquals(IslandKind.TRANSPORT, appOngoingKindFor(PKG_UBER))
        assertEquals(IslandKind.TRANSPORT, appOngoingKindFor(PKG_BOLT))
        assertEquals(IslandKind.TRANSPORT, appOngoingKindFor(PKG_ROHLIK))
        assertEquals(IslandKind.TRANSPORT, appOngoingKindFor(PKG_WOLT))
        assertEquals(IslandKind.TRANSPORT, appOngoingKindFor(PKG_FOODORA))
        assertEquals(IslandKind.NAVIGATION, appOngoingKindFor(PKG_MAPY))
        assertEquals(IslandKind.NAVIGATION, appOngoingKindFor(PKG_GOOGLE_MAPS))
        assertNull(appOngoingKindFor("com.example.unknown"))
    }

    @Test fun `eta parsing reads minutes, ranges and za-prefixed czech text`() {
        assertEquals(now + 4 * 60_000L to "4 min", parseEtaAndKeyValue("Your driver is 4 min away", now))
        assertEquals(now + 3 * 60_000L to "3 min", parseEtaAndKeyValue("Řidič dorazí za 3 min", now))
        assertEquals(now + 15 * 60_000L to "12–15 min", parseEtaAndKeyValue("Kurýr je na cestě · 12–15 min", now))
        assertEquals(now + 14 * 60_000L to "14 min", parseEtaAndKeyValue("Za 300 m odbočte vlevo · 14 min", now))
        assertEquals(now + 3 * 60_000L to "3 min", parseEtaAndKeyValue("arriving in 3 min", now))
        assertEquals(null to null, parseEtaAndKeyValue("Preparing your order", now))
    }

    @Test fun `uber driver-away text yields eta and the ride-jede stage`() {
        val match = matchLiveActivity(facts(PKG_UBER, title = "Uber", text = "Your driver is 4 min away"), now)
        requireNotNull(match)
        assertEquals(now + 4 * 60_000L, match.etaMs)
        assertEquals("4 min", match.keyValue)
        assertEquals(listOf("Hledá se řidič", "Řidič jede", "Přijel", "Jízda"), match.segments)
        assertEquals(1, match.stageIndex)
    }

    @Test fun `bolt czech arrival text yields eta and the ride-jede stage`() {
        val match = matchLiveActivity(facts(PKG_BOLT, text = "Řidič dorazí za 3 min"), now)
        requireNotNull(match)
        assertEquals(now + 3 * 60_000L, match.etaMs)
        assertEquals("3 min", match.keyValue)
        assertEquals(1, match.stageIndex)
    }

    @Test fun `rohlik range eta picks the courier stage and the upper bound`() {
        val match = matchLiveActivity(facts(PKG_ROHLIK, text = "Kurýr je na cestě · 12–15 min"), now)
        requireNotNull(match)
        assertEquals(now + 15 * 60_000L, match.etaMs)
        assertEquals("12–15 min", match.keyValue)
        assertEquals(listOf("Objednáno", "Připravuje se", "Kurýr", "Doručeno"), match.segments)
        assertEquals(2, match.stageIndex)
    }

    @Test fun `wolt preparing text has no eta but resolves the preparing stage as keyValue`() {
        val match = matchLiveActivity(facts(PKG_WOLT, text = "Preparing your order"), now)
        requireNotNull(match)
        assertNull(match.etaMs)
        assertEquals("Připravuje se", match.keyValue)
        assertEquals(1, match.stageIndex)
    }

    @Test fun `foodora shares the delivery adapter with rohlik and wolt`() {
        val match = matchLiveActivity(facts(PKG_FOODORA, text = "Doručeno"), now)
        requireNotNull(match)
        assertEquals("Doručeno", match.keyValue)
        assertEquals(3, match.stageIndex)
    }

    @Test fun `mapy turn-by-turn text yields eta with no stage segments`() {
        val match = matchLiveActivity(facts(PKG_MAPY, text = "Za 300 m odbočte vlevo · 14 min"), now)
        requireNotNull(match)
        assertEquals(now + 14 * 60_000L, match.etaMs)
        assertEquals("14 min", match.keyValue)
        assertTrue(match.segments.isEmpty())
        assertNull(match.stageIndex)
    }

    @Test fun `google maps nav uses the same navigation adapter as mapy`() {
        val match = matchLiveActivity(facts(PKG_GOOGLE_MAPS, text = "5 min - 2.1 km"), now)
        requireNotNull(match)
        assertEquals(now + 5 * 60_000L, match.etaMs)
    }

    @Test fun `unrecognised app with blank text yields no match`() {
        assertNull(matchLiveActivity(facts(PKG_UBER, title = "", text = ""), now))
        assertNull(matchLiveActivity(facts("com.example.unknown", text = "hello"), now))
    }

    @Test fun `android 16 live updates progress-style segments are read from extras when promoted`() {
        val extras = mapOf(
            EXTRA_PROGRESS_SEGMENT_LABELS to listOf("Placed", "Packed", "Shipped", "Delivered"),
            EXTRA_PROGRESS_SEGMENT_INDEX to 2,
        )
        val match = matchLiveActivity(facts("com.example.livedelivery", promoted = true, extras = extras), now)
        requireNotNull(match)
        assertEquals(listOf("Placed", "Packed", "Shipped", "Delivered"), match.segments)
        assertEquals(2, match.stageIndex)
    }

    @Test fun `a known app adapter wins over Live Updates segments for the same notification`() {
        // Uber already has its own ride segments (d); a promoted extra (a) must not replace them.
        val extras = mapOf(EXTRA_PROGRESS_SEGMENT_LABELS to listOf("Requested", "En route", "Arrived"))
        val match = matchLiveActivity(facts(PKG_UBER, text = "3 min", promoted = true, extras = extras), now)
        requireNotNull(match)
        assertEquals(now + 3 * 60_000L, match.etaMs)
        assertEquals(listOf("Hledá se řidič", "Řidič jede", "Přijel", "Jízda"), match.segments)
    }

    @Test fun `generic ongoing progress bar without a known package reports a percentage`() {
        val match = matchLiveActivity(facts("com.example.upload", ongoing = true, progress = 45, progressMax = 100), now)
        requireNotNull(match)
        assertEquals("45%", match.keyValue)
        assertNull(match.etaMs)
    }

    @Test fun `generic indeterminate progress reports no keyValue but still a match`() {
        val match = matchLiveActivity(facts("com.example.sync", ongoing = true, progressIndeterminate = true), now)
        requireNotNull(match)
        assertNull(match.keyValue)
    }

    @Test fun `non-ongoing unrecognised progress notification is not a live activity`() {
        assertNull(matchLiveActivity(facts("com.example.upload", ongoing = false, progress = 10, progressMax = 100), now))
    }

    @Test fun `pictureKey is a stable non-null marker only when a large icon is present`() {
        val withIcon = matchLiveActivity(facts(PKG_MAPY, text = "14 min", largeIconPresent = true), now)
        val withoutIcon = matchLiveActivity(facts(PKG_MAPY, text = "14 min", largeIconPresent = false), now)
        assertTrue(requireNotNull(withIcon).pictureKey != null)
        assertNull(requireNotNull(withoutIcon).pictureKey)
    }

    @Test fun `priority bucket is call, navigation, imminent ride, timer, media, other`() {
        assertEquals(0, liveActivityBucket(IslandItem("c", kind = IslandKind.CALL), now))
        assertEquals(1, liveActivityBucket(IslandItem("n", kind = IslandKind.NAVIGATION), now))
        val imminent = IslandItem("r", kind = IslandKind.TRANSPORT, etaMs = now + 90_000L)
        assertEquals(2, liveActivityBucket(imminent, now))
        val farAway = IslandItem("r2", kind = IslandKind.TRANSPORT, etaMs = now + 10 * 60_000L)
        assertEquals(5, liveActivityBucket(farAway, now))
        assertEquals(5, liveActivityBucket(IslandItem("r3", kind = IslandKind.TRANSPORT), now))
        assertEquals(3, liveActivityBucket(IslandItem("t", kind = IslandKind.TIMER), now))
        assertEquals(4, liveActivityBucket(IslandItem("m", kind = IslandKind.MEDIA), now))
        assertEquals(5, liveActivityBucket(IslandItem("o", kind = IslandKind.OTHER), now))
        // exactly at the window boundary still counts as imminent.
        assertEquals(2, liveActivityBucket(IslandItem("edge", kind = IslandKind.TRANSPORT, etaMs = now + LIVE_ACTIVITY_ALERT_WINDOW_MS), now))
    }

    @Test fun `an imminent ride jumps ahead of a running timer but not ahead of navigation`() {
        val items = listOf(
            IslandItem("timer", kind = IslandKind.TIMER, postedAt = 10),
            IslandItem("ride", kind = IslandKind.TRANSPORT, postedAt = 5, etaMs = now + 60_000L),
            IslandItem("nav", kind = IslandKind.NAVIGATION, postedAt = 1),
            IslandItem("call", kind = IslandKind.CALL, postedAt = 1),
            IslandItem("media", kind = IslandKind.MEDIA, postedAt = 1),
            IslandItem("farRide", kind = IslandKind.TRANSPORT, postedAt = 1, etaMs = now + 20 * 60_000L),
        )
        assertEquals(listOf("call", "nav", "ride", "timer", "media", "farRide"),
            rankIslandItemsWithLiveActivities(items, now).map { it.key })
    }

    @Test fun `a notification's key never changes across an eta update, only enrichment does`() {
        val first = matchLiveActivity(facts(PKG_UBER, text = "8 min"), now)
        val second = matchLiveActivity(facts(PKG_UBER, text = "2 min"), now)
        val itemBefore = IslandItem("uber:0", kind = IslandKind.TRANSPORT, etaMs = first?.etaMs, keyValue = first?.keyValue)
        val itemAfter = itemBefore.copy(etaMs = second?.etaMs, keyValue = second?.keyValue)
        assertEquals(itemBefore.key, itemAfter.key)
        assertTrue(itemAfter.etaMs!! < itemBefore.etaMs!!)
    }
}
