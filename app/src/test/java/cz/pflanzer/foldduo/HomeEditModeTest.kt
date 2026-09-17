package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class HomeEditModeTest {
    @Test fun `entering edit mode is always active regardless of prior state`() {
        assertTrue(HomeEditMode.reduce(false, EditModeEvent.ENTER))
        assertTrue(HomeEditMode.reduce(true, EditModeEvent.ENTER))
    }

    @Test fun `done, back and tapping the wallpaper all exit edit mode`() {
        assertFalse(HomeEditMode.reduce(true, EditModeEvent.DONE))
        assertFalse(HomeEditMode.reduce(true, EditModeEvent.BACK))
        assertFalse(HomeEditMode.reduce(true, EditModeEvent.TAP_WALLPAPER))
    }

    @Test fun `wiggle rotation stays within the amplitude`() {
        for (t in 0..2000L step 37) for (index in 0..11) {
            val deg = wiggleRotationDeg(t, index)
            assertTrue("deg=$deg out of range", abs(deg) <= WIGGLE_AMPLITUDE_DEG + 1e-4f)
        }
    }

    @Test fun `wiggle rotation is periodic`() {
        for (index in 0..5) {
            val a = wiggleRotationDeg(123L, index)
            val b = wiggleRotationDeg(123L + WIGGLE_PERIOD_MS, index)
            assertEquals(a, b, 1e-3f)
        }
    }

    @Test fun `neighbouring items are out of phase`() {
        val t = 200L
        val a = wiggleRotationDeg(t, 0)
        val b = wiggleRotationDeg(t, 1)
        assertNotEquals(a, b, 1e-3f)
    }

    @Test fun `phase offset is stable and within one period`() {
        for (index in 0..50) {
            val offset = wigglePhaseOffsetMs(index)
            assertTrue(offset in 0 until WIGGLE_PERIOD_MS)
        }
        assertEquals(wigglePhaseOffsetMs(3), wigglePhaseOffsetMs(3))
    }

    @Test fun `plus page is visible for every existing temp-page reason`() {
        assertTrue(homePagingTempPageActive(dragActive = true, widgetDragging = false, pendingNewPage = false, homePagesEditing = false, editMode = false))
        assertTrue(homePagingTempPageActive(dragActive = false, widgetDragging = true, pendingNewPage = false, homePagesEditing = false, editMode = false))
        assertTrue(homePagingTempPageActive(dragActive = false, widgetDragging = false, pendingNewPage = true, homePagesEditing = false, editMode = false))
        assertTrue(homePagingTempPageActive(dragActive = false, widgetDragging = false, pendingNewPage = false, homePagesEditing = true, editMode = false))
    }

    @Test fun `plus page is visible while editing Home even with no other reason`() {
        assertTrue(homePagingTempPageActive(dragActive = false, widgetDragging = false, pendingNewPage = false, homePagesEditing = false, editMode = true))
    }

    @Test fun `plus page is hidden with no reason at all`() {
        assertFalse(homePagingTempPageActive(dragActive = false, widgetDragging = false, pendingNewPage = false, homePagesEditing = false, editMode = false))
    }
}
