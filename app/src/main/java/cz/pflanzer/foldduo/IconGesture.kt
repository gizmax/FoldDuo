package cz.pflanzer.foldduo

/**
 * iPhone-style icon interaction (2026-09-17 evening redesign, fixing the regression where a
 * long-press started a drag straight away and the context menu never appeared): a long-press on
 * a movable app icon opens a compact popover instead of immediately lifting the icon into a
 * drag. Only once the finger actually moves past touch slop *while the popover is showing* does
 * the hold turn into a real drag ("touch and hold then drag", like iOS). Releasing without ever
 * moving leaves the popover open — it is dismissed explicitly (tap outside, Back, or picking an
 * action), never by lifting the finger.
 *
 * This is the pure state machine; [HomeDrag.kt]'s `homeDragInput` is the pointer-input glue that
 * drives it (it needs Compose's gesture APIs, which are not JVM-testable on their own).
 */
internal enum class IconGesturePhase { IDLE, POPOVER, DRAGGING }

internal object IconGesture {
    /** A long-press on a popover-eligible icon always opens the popover first — never a drag. */
    fun afterLongPress(): IconGesturePhase = IconGesturePhase.POPOVER

    /**
     * Movement beyond touch slop converts an open popover into a drag; once already dragging
     * (or still idle) further movement is a no-op here (the drag loop tracks it separately).
     */
    fun afterMove(phase: IconGesturePhase, beyondSlop: Boolean): IconGesturePhase =
        if (phase == IconGesturePhase.POPOVER && beyondSlop) IconGesturePhase.DRAGGING else phase

    /** Releasing ends a drag; a popover that never converted stays open. */
    fun afterRelease(phase: IconGesturePhase): IconGesturePhase =
        if (phase == IconGesturePhase.DRAGGING) IconGesturePhase.IDLE else phase

    /** Tap-outside/Back/picking an action explicitly dismisses the popover. */
    fun afterDismiss(): IconGesturePhase = IconGesturePhase.IDLE
}
