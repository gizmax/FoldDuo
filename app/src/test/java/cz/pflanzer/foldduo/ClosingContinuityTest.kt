package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Zavírání jako Duo" (STATUS.md, 17. 9. noc), item 4: [ClosingContinuity]'s process-wide stash
 * bridging the panel-swap activity relaunch, in isolation from [MorphController] (see
 * `MorphPolishTest.kt` for the end-to-end wiring through `playCoverSettle`).
 */
class ClosingContinuityTest {

    @Test fun `takeForCoverSettle falls back when nothing was ever noted`() {
        ClosingContinuity.clearForTest()
        assertEquals(12f, ClosingContinuity.takeForCoverSettle(12f), 0f)
    }

    @Test fun `note then takeForCoverSettle returns the stashed value once, then falls back again`() {
        ClosingContinuity.clearForTest()
        ClosingContinuity.note(40f)
        assertEquals(40f, ClosingContinuity.takeForCoverSettle(12f), 0f)
        // Consumed: a second read falls back again.
        assertEquals(12f, ClosingContinuity.takeForCoverSettle(12f), 0f)
    }

    @Test fun `note ignores 0 or negative tilts`() {
        ClosingContinuity.clearForTest()
        ClosingContinuity.note(40f)
        ClosingContinuity.note(0f)
        ClosingContinuity.note(-5f)
        assertEquals(40f, ClosingContinuity.takeForCoverSettle(12f), 0f)
    }

    @Test fun `the latest noted value wins`() {
        ClosingContinuity.clearForTest()
        ClosingContinuity.note(10f)
        ClosingContinuity.note(30f)
        assertEquals(30f, ClosingContinuity.takeForCoverSettle(12f), 0f)
    }
}
