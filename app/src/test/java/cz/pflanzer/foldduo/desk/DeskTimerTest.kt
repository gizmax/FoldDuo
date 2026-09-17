package cz.pflanzer.foldduo.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskTimerTest {
    @Test fun remainingCountsDownLinearly() {
        assertEquals(10_000L, DeskTimer.remainingMs(10_000L, startedAtElapsedMs = 1_000L, nowElapsedMs = 1_000L))
        assertEquals(7_000L, DeskTimer.remainingMs(10_000L, startedAtElapsedMs = 1_000L, nowElapsedMs = 4_000L))
    }

    @Test fun remainingClampsAtZeroPastTheEnd() {
        assertEquals(0L, DeskTimer.remainingMs(10_000L, startedAtElapsedMs = 1_000L, nowElapsedMs = 50_000L))
    }

    @Test fun isFinishedBoundary() {
        assertFalse(DeskTimer.isFinished(1L))
        assertTrue(DeskTimer.isFinished(0L))
        assertTrue(DeskTimer.isFinished(-5L))
    }

    @Test fun elapsedAccumulatesAcrossPauses() {
        // Ran for 5s, paused (5s banked), resumed at elapsed=100, now elapsed=103 -> 3s more.
        assertEquals(8_000L, DeskTimer.elapsedMs(startedAtElapsedMs = 100_000L, nowElapsedMs = 103_000L, pausedAccumMs = 5_000L))
    }

    @Test fun elapsedWithNoPauseIsJustTheDelta() {
        assertEquals(2_500L, DeskTimer.elapsedMs(startedAtElapsedMs = 1_000L, nowElapsedMs = 3_500L))
    }

    @Test fun formatMinutesAndSeconds() {
        assertEquals("00:00", DeskTimer.format(0L))
        assertEquals("00:09", DeskTimer.format(9_000L))
        assertEquals("01:05", DeskTimer.format(65_000L))
        assertEquals("59:59", DeskTimer.format(3_599_000L))
    }

    @Test fun formatIncludesHoursPastOneHour() {
        assertEquals("1:00:00", DeskTimer.format(3_600_000L))
        assertEquals("2:03:04", DeskTimer.format((2 * 3600 + 3 * 60 + 4) * 1000L))
    }

    @Test fun formatNegativeReadsAsZero() {
        assertEquals("00:00", DeskTimer.format(-500L))
    }
}
