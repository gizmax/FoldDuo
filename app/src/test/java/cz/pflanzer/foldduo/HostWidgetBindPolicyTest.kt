package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostWidgetBindPolicyTest {
    @Test fun `live view never binds before its deferred frame has posted`() {
        assertFalse(hostWidgetShouldBindLive(framePosted = false))
        assertTrue(hostWidgetShouldBindLive(framePosted = true))
    }

    @Test fun `overlay prefers a frozen snapshot over every other source`() {
        assertEquals("frozen", hostWidgetOverlay("frozen", "late", "preBind", "lingering"))
        assertEquals("frozen", hostWidgetOverlay("frozen", null, null, null))
    }

    @Test fun `overlay falls back to the late frozen retry next`() {
        assertEquals("late", hostWidgetOverlay(null, "late", "preBind", "lingering"))
        assertEquals("late", hostWidgetOverlay(null, "late", null, null))
    }

    @Test fun `overlay falls back to the deferred-bind placeholder next`() {
        assertEquals("preBind", hostWidgetOverlay(null, null, "preBind", "lingering"))
        assertEquals("preBind", hostWidgetOverlay(null, null, "preBind", null))
    }

    @Test fun `overlay finally falls back to the lingering hand-over bitmap`() {
        assertEquals("lingering", hostWidgetOverlay(null, null, null, "lingering"))
    }

    @Test fun `overlay is null when nothing is available`() {
        assertEquals(null, hostWidgetOverlay<String>(null, null, null, null))
    }
}
