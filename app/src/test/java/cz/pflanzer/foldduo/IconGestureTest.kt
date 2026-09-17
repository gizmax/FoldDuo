package cz.pflanzer.foldduo

import org.junit.Assert.*
import org.junit.Test

class IconGestureTest {
    @Test fun `long press opens the popover, never a drag directly`() {
        assertEquals(IconGesturePhase.POPOVER, IconGesture.afterLongPress())
    }

    @Test fun `holding still keeps the popover open`() {
        val phase = IconGesture.afterMove(IconGesturePhase.POPOVER, beyondSlop = false)
        assertEquals(IconGesturePhase.POPOVER, phase)
    }

    @Test fun `moving past touch slop while the popover is open converts to a drag`() {
        val phase = IconGesture.afterMove(IconGesturePhase.POPOVER, beyondSlop = true)
        assertEquals(IconGesturePhase.DRAGGING, phase)
    }

    @Test fun `movement while idle or already dragging is a no-op here`() {
        assertEquals(IconGesturePhase.IDLE, IconGesture.afterMove(IconGesturePhase.IDLE, beyondSlop = true))
        assertEquals(IconGesturePhase.DRAGGING, IconGesture.afterMove(IconGesturePhase.DRAGGING, beyondSlop = true))
    }

    @Test fun `releasing a drag returns to idle`() {
        assertEquals(IconGesturePhase.IDLE, IconGesture.afterRelease(IconGesturePhase.DRAGGING))
    }

    @Test fun `releasing while the popover is still open leaves it open`() {
        assertEquals(IconGesturePhase.POPOVER, IconGesture.afterRelease(IconGesturePhase.POPOVER))
    }

    @Test fun `releasing while idle stays idle`() {
        assertEquals(IconGesturePhase.IDLE, IconGesture.afterRelease(IconGesturePhase.IDLE))
    }

    @Test fun `dismissing always returns to idle`() {
        assertEquals(IconGesturePhase.IDLE, IconGesture.afterDismiss())
    }

    @Test fun `full sequence, long press then hold then release, ends with the popover open`() {
        var phase = IconGesture.afterLongPress()
        phase = IconGesture.afterMove(phase, beyondSlop = false)
        phase = IconGesture.afterRelease(phase)
        assertEquals(IconGesturePhase.POPOVER, phase)
    }

    @Test fun `full sequence, long press then move then release, ends idle after a drop`() {
        var phase = IconGesture.afterLongPress()
        phase = IconGesture.afterMove(phase, beyondSlop = true)
        phase = IconGesture.afterRelease(phase)
        assertEquals(IconGesturePhase.IDLE, phase)
    }
}
