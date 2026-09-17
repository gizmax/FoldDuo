package cz.pflanzer.foldduo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B43 "Výkon jako feature": [WidgetSnapshotPolicy.canReuse], the pure "do I need to redraw"
 * decision behind [WidgetSnapshotCache] — a close→open cycle within
 * [WidgetSnapshotPolicy.CACHE_MS] at the same widget size should reuse the last capture instead
 * of forcing HostWidgetView to draw the host view again.
 */
class WidgetSnapshotPolicyTest {

    @Test fun `no entry yet - never reusable`() {
        assertFalse(WidgetSnapshotPolicy.canReuse(null, 100, 100, nowMs = 0))
    }

    @Test fun `same size, well within the window - reusable`() {
        val entry = WidgetSnapshotPolicy.Entry(width = 200, height = 120, capturedAtMs = 1_000)
        assertTrue(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 1_500))
    }

    @Test fun `right at the window boundary - no longer reusable`() {
        val entry = WidgetSnapshotPolicy.Entry(width = 200, height = 120, capturedAtMs = 1_000)
        val cacheMs = WidgetSnapshotPolicy.CACHE_MS
        assertTrue(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 1_000 + cacheMs - 1))
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 1_000 + cacheMs))
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 1_000 + cacheMs + 5_000))
    }

    @Test fun `a size change always forces a fresh capture, even immediately`() {
        val entry = WidgetSnapshotPolicy.Entry(width = 200, height = 120, capturedAtMs = 1_000)
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 201, 120, nowMs = 1_001))
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 200, 121, nowMs = 1_001))
    }

    @Test fun `a non-positive size is never reusable`() {
        val entry = WidgetSnapshotPolicy.Entry(width = 0, height = 0, capturedAtMs = 1_000)
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 0, 0, nowMs = 1_001))
    }

    @Test fun `a capture that is somehow in the future (clock skew) is not reusable`() {
        val entry = WidgetSnapshotPolicy.Entry(width = 200, height = 120, capturedAtMs = 5_000)
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 4_000))
    }

    @Test fun `a custom cache window is honoured`() {
        val entry = WidgetSnapshotPolicy.Entry(width = 200, height = 120, capturedAtMs = 0)
        assertTrue(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 900, cacheMs = 1_000))
        assertFalse(WidgetSnapshotPolicy.canReuse(entry, 200, 120, nowMs = 900, cacheMs = 500))
    }
}
