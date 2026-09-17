package cz.pflanzer.foldduo.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPredictorTest {
    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L // arbitrary fixed "now"

    private fun event(pkg: String, daysAgo: Long, hourBucket: Int, weekend: Boolean = false,
                       network: NetworkContext = NetworkContext.UNKNOWN, place: String? = null) =
        LaunchEvent(pkg, now - daysAgo * dayMs, hourBucket, weekend, network, place)

    @Test fun `hour bucket groups two hours together`() {
        assertEquals(0, hourBucket(0)); assertEquals(0, hourBucket(1))
        assertEquals(1, hourBucket(2)); assertEquals(1, hourBucket(3))
        assertEquals(11, hourBucket(23))
    }

    @Test fun `an app launched mostly at the current time of day is preferred over an equally frequent one at a different time`() {
        val morningBucket = hourBucket(8)
        val eveningBucket = hourBucket(20)
        val events = (1..6).map { event("com.morning.app", it.toLong(), morningBucket) } +
            (1..6).map { event("com.evening.app", it.toLong(), eveningBucket) }
        val morningContext = PredictionContext(morningBucket, weekend = false, network = NetworkContext.UNKNOWN, place = null)
        val top = predictTopApps(events, now, morningContext, topN = 2)
        assertEquals("com.morning.app", top.first())
        val eveningContext = PredictionContext(eveningBucket, weekend = false, network = NetworkContext.UNKNOWN, place = null)
        val topEvening = predictTopApps(events, now, eveningContext, topN = 2)
        assertEquals("com.evening.app", topEvening.first())
    }

    @Test fun `old launches decay so a recently-dominant app overtakes an old one-time favourite`() {
        // com.old.app launched a lot, but all 30+ days ago (well past the 7-day half-life).
        val oldEvents = (1..20).map { event("com.old.app", 40L + it, hourBucket(10)) }
        // com.new.app launched only twice, both very recently.
        val newEvents = listOf(event("com.new.app", 0, hourBucket(10)), event("com.new.app", 1, hourBucket(10)))
        val context = PredictionContext(hourBucket(10), weekend = false, network = NetworkContext.UNKNOWN, place = null)
        val ranked = predictTopApps(oldEvents + newEvents, now, context, topN = 2)
        assertEquals("com.new.app", ranked.first())
    }

    @Test fun `decay weight halves every half-life`() {
        val halfLife = 1000L
        assertEquals(1.0, decayWeight(now, now, halfLife), 1e-9)
        assertEquals(0.5, decayWeight(now - halfLife, now, halfLife), 1e-9)
        assertEquals(0.25, decayWeight(now - 2 * halfLife, now, halfLife), 1e-9)
    }

    @Test fun `apps already in the dock are excluded even if they would otherwise top the ranking`() {
        val events = (1..10).map { event("com.dock.app", it.toLong(), hourBucket(9)) } +
            listOf(event("com.other.app", 1, hourBucket(9)))
        val context = PredictionContext(hourBucket(9), weekend = false, network = NetworkContext.UNKNOWN, place = null)
        val withoutExclusion = predictTopApps(events, now, context, topN = 4)
        assertEquals("com.dock.app", withoutExclusion.first())
        val excluded = predictTopApps(events, now, context, excludePackages = setOf("com.dock.app"), topN = 4)
        assertFalse(excluded.contains("com.dock.app"))
        assertTrue(excluded.contains("com.other.app"))
    }

    @Test fun `blocked packages never appear regardless of score`() {
        val events = (1..10).map { event("com.blocked.app", it.toLong(), hourBucket(9)) } +
            listOf(event("com.other.app", 1, hourBucket(9)))
        val context = PredictionContext(hourBucket(9), weekend = false, network = NetworkContext.UNKNOWN, place = null)
        val ranked = predictTopApps(events, now, context, blocked = setOf("com.blocked.app"), topN = 4)
        assertFalse(ranked.contains("com.blocked.app"))
        assertEquals(listOf("com.other.app"), ranked)
    }

    @Test fun `only the top N packages are returned, most recent tie broken alphabetically`() {
        val events = listOf("a", "b", "c", "d", "e").map { event("com.$it.app", 1, hourBucket(9)) }
        val context = PredictionContext(hourBucket(9), weekend = false, network = NetworkContext.UNKNOWN, place = null)
        val ranked = predictTopApps(events, now, context, topN = 4)
        assertEquals(4, ranked.size)
        assertEquals(ranked, ranked.sorted())
    }

    @Test fun `no events means no suggestions`() {
        val context = PredictionContext(0, weekend = false, network = NetworkContext.UNKNOWN, place = null)
        assertTrue(predictTopApps(emptyList(), now, context).isEmpty())
    }

    @Test fun `weekend and network context bias the ranking between two otherwise-even apps`() {
        val weekdayEvents = (1..6).map { event("com.weekday.app", it.toLong(), hourBucket(9), weekend = false, network = NetworkContext.WIFI) }
        val weekendEvents = (1..6).map { event("com.weekend.app", it.toLong(), hourBucket(9), weekend = true, network = NetworkContext.MOBILE) }
        val weekdayContext = PredictionContext(hourBucket(9), weekend = false, network = NetworkContext.WIFI, place = null)
        assertEquals("com.weekday.app", predictTopApps(weekdayEvents + weekendEvents, now, weekdayContext, topN = 1).first())
        val weekendContext = PredictionContext(hourBucket(9), weekend = true, network = NetworkContext.MOBILE, place = null)
        assertEquals("com.weekend.app", predictTopApps(weekdayEvents + weekendEvents, now, weekendContext, topN = 1).first())
    }

    @Test fun `place context biases the ranking when a manual place is set`() {
        val homeEvents = (1..6).map { event("com.home.app", it.toLong(), hourBucket(9), place = "Home") }
        val workEvents = (1..6).map { event("com.work.app", it.toLong(), hourBucket(9), place = "Work") }
        val atHome = PredictionContext(hourBucket(9), weekend = false, network = NetworkContext.UNKNOWN, place = "Home")
        assertEquals("com.home.app", predictTopApps(homeEvents + workEvents, now, atHome, topN = 1).first())
        val atWork = PredictionContext(hourBucket(9), weekend = false, network = NetworkContext.UNKNOWN, place = "Work")
        assertEquals("com.work.app", predictTopApps(homeEvents + workEvents, now, atWork, topN = 1).first())
    }

    @Test fun `first free home slot index finds the first null, or appends past the end when full`() {
        assertEquals(0, firstFreeHomeSlotIndex(listOf(null, "a", "b")))
        assertEquals(1, firstFreeHomeSlotIndex(listOf("a", null, "b")))
        assertEquals(3, firstFreeHomeSlotIndex(listOf("a", "b", "c")))
        assertEquals(0, firstFreeHomeSlotIndex(emptyList()))
    }
}
