package cz.pflanzer.foldduo.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityDecisionTest {
    private val ownPackage = "cz.pflanzer.foldduo"
    private val swapAt = 1_000_000L

    @Test fun `a sighting just before the swap is eligible`() {
        val sighting = ForegroundSighting("com.other.app", "Other", swapAt - 3_000)
        assertTrue(continuityEligible(sighting, ownPackage, swapAt))
    }

    @Test fun `a sighting exactly at the window edge is still eligible`() {
        val sighting = ForegroundSighting("com.other.app", "Other", swapAt - CONTINUITY_FOREGROUND_WINDOW_MS)
        assertTrue(continuityEligible(sighting, ownPackage, swapAt))
    }

    @Test fun `a sighting older than the window is not eligible`() {
        val sighting = ForegroundSighting("com.other.app", "Other", swapAt - CONTINUITY_FOREGROUND_WINDOW_MS - 1)
        assertFalse(continuityEligible(sighting, ownPackage, swapAt))
    }

    @Test fun `a sighting timestamped after the swap is not eligible`() {
        val sighting = ForegroundSighting("com.other.app", "Other", swapAt + 1)
        assertFalse(continuityEligible(sighting, ownPackage, swapAt))
    }

    @Test fun `our own launcher is never offered as a continuity target`() {
        val sighting = ForegroundSighting(ownPackage, "Fold Duo", swapAt - 100)
        assertFalse(continuityEligible(sighting, ownPackage, swapAt))
    }

    @Test fun `null sighting is never eligible`() {
        assertFalse(continuityEligible(null, ownPackage, swapAt))
    }

    @Test fun `the chip stays visible for its whole duration then disappears`() {
        val shownAt = 5_000L
        assertTrue(continuityChipVisible(shownAt, shownAt))
        assertTrue(continuityChipVisible(shownAt, shownAt + CONTINUITY_CHIP_DURATION_MS))
        assertFalse(continuityChipVisible(shownAt, shownAt + CONTINUITY_CHIP_DURATION_MS + 1))
        assertFalse(continuityChipVisible(shownAt, shownAt - 1))
    }
}
