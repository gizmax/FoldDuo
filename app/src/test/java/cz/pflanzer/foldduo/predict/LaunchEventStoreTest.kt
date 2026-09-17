package cz.pflanzer.foldduo.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchEventStoreTest {
    private fun event(pkg: String, t: Long) = LaunchEvent(pkg, t, 0, false, NetworkContext.UNKNOWN, null)

    @Test fun `appending under the cap just grows the list`() {
        val existing = listOf(event("a", 1), event("b", 2))
        val result = appendLaunchEvent(existing, event("c", 3), cap = 10)
        assertEquals(listOf("a", "b", "c"), result.map { it.packageName })
    }

    @Test fun `appending past the cap drops the oldest event, not the newest`() {
        val existing = (1..5).map { event(it.toString(), it.toLong()) }
        val result = appendLaunchEvent(existing, event("6", 6), cap = 5)
        assertEquals(5, result.size)
        assertEquals(listOf("2", "3", "4", "5", "6"), result.map { it.packageName })
    }

    @Test fun `the store never exceeds 2000 events by default`() {
        var events = emptyList<LaunchEvent>()
        repeat(2100) { i -> events = appendLaunchEvent(events, event("pkg$i", i.toLong())) }
        assertEquals(LAUNCH_EVENT_CAP, events.size)
        assertEquals(2000, events.size)
        // The oldest 100 were dropped; the newest survived, oldest-first.
        assertEquals("pkg100", events.first().packageName)
        assertEquals("pkg2099", events.last().packageName)
    }

    @Test fun `merging deduplicates by package and timestamp and sorts oldest first`() {
        val existing = listOf(event("a", 5), event("b", 2))
        val additions = listOf(event("a", 5), event("c", 1))
        val merged = mergeLaunchEvents(existing, additions)
        assertEquals(listOf("c", "b", "a"), merged.map { it.packageName })
        assertTrue(merged.map { it.timestampMs } == merged.map { it.timestampMs }.sorted())
    }

    @Test fun `merging past the cap also drops the oldest`() {
        val existing = (1..4).map { event(it.toString(), it.toLong()) }
        val additions = listOf(event("5", 5), event("6", 6))
        val merged = mergeLaunchEvents(existing, additions, cap = 4)
        assertEquals(listOf("3", "4", "5", "6"), merged.map { it.packageName })
    }
}
