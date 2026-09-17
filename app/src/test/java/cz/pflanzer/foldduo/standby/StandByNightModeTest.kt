package cz.pflanzer.foldduo.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandByNightModeTest {

    // --- SleepWindow ---

    @Test fun sleepWindowSameDayRange() {
        assertTrue(SleepWindow.contains(13 * 60, 12 * 60, 14 * 60))
        assertFalse(SleepWindow.contains(15 * 60, 12 * 60, 14 * 60))
        assertFalse(SleepWindow.contains(11 * 60, 12 * 60, 14 * 60))
    }

    @Test fun sleepWindowSameDayRangeIsStartInclusiveEndExclusive() {
        assertTrue(SleepWindow.contains(12 * 60, 12 * 60, 14 * 60))
        assertFalse(SleepWindow.contains(14 * 60, 12 * 60, 14 * 60))
    }

    @Test fun sleepWindowOvernightWraparound() {
        // 23:00-06:00
        assertTrue(SleepWindow.contains(23 * 60 + 30, 23 * 60, 6 * 60))
        assertTrue(SleepWindow.contains(0, 23 * 60, 6 * 60))
        assertTrue(SleepWindow.contains(5 * 60 + 59, 23 * 60, 6 * 60))
        assertFalse(SleepWindow.contains(6 * 60, 23 * 60, 6 * 60))
        assertFalse(SleepWindow.contains(12 * 60, 23 * 60, 6 * 60))
    }

    @Test fun sleepWindowZeroLengthNeverMatches() {
        assertFalse(SleepWindow.contains(0, 90, 90))
        assertFalse(SleepWindow.contains(1439, 90, 90))
    }

    // --- NightModeRules.isAmbientNight (mode x lux x hours) ---

    private fun inputs(
        mode: NightMode = NightMode.Auto,
        lowLightMs: Long = 0L,
        sleepHoursEnabled: Boolean = true,
        nowMinuteOfDay: Int = 12 * 60,
        sleepStartMinute: Int = NightModeRules.DEFAULT_SLEEP_START_MINUTE,
        sleepEndMinute: Int = NightModeRules.DEFAULT_SLEEP_END_MINUTE,
    ) = NightModeInputs(mode, lowLightMs, sleepHoursEnabled, nowMinuteOfDay, sleepStartMinute, sleepEndMinute)

    @Test fun offNeverGoesAmbientNight() {
        assertFalse(NightModeRules.isAmbientNight(inputs(mode = NightMode.Off, lowLightMs = 60_000L)))
        assertFalse(NightModeRules.isAmbientNight(inputs(mode = NightMode.Off, nowMinuteOfDay = 0)))
    }

    @Test fun alwaysNeverGoesThroughTheAmbientRule() {
        // Always is handled by isForcedRed, not the ambient face — even with light and hours both saying "day".
        assertFalse(NightModeRules.isAmbientNight(inputs(mode = NightMode.Always, lowLightMs = 0L, sleepHoursEnabled = false)))
    }

    @Test fun lowLightHeldLongEnoughGoesNightRegardlessOfTimeOfDay() {
        assertTrue(NightModeRules.isAmbientNight(inputs(lowLightMs = NightModeRules.LOW_LIGHT_HOLD_MS, sleepHoursEnabled = false, nowMinuteOfDay = 12 * 60)))
        assertFalse(NightModeRules.isAmbientNight(inputs(lowLightMs = NightModeRules.LOW_LIGHT_HOLD_MS - 1, sleepHoursEnabled = false, nowMinuteOfDay = 12 * 60)))
    }

    @Test fun sleepHoursAloneGoNight() {
        assertTrue(NightModeRules.isAmbientNight(inputs(lowLightMs = 0L, sleepHoursEnabled = true, nowMinuteOfDay = 23 * 60 + 30)))
        assertFalse(NightModeRules.isAmbientNight(inputs(lowLightMs = 0L, sleepHoursEnabled = true, nowMinuteOfDay = 12 * 60)))
    }

    @Test fun sleepHoursDisabledIgnoresTimeOfDay() {
        assertFalse(NightModeRules.isAmbientNight(inputs(lowLightMs = 0L, sleepHoursEnabled = false, nowMinuteOfDay = 23 * 60 + 30)))
    }

    @Test fun eitherConditionIsEnoughOnceModeIsAuto() {
        assertTrue(NightModeRules.isAmbientNight(inputs(lowLightMs = NightModeRules.LOW_LIGHT_HOLD_MS, sleepHoursEnabled = false, nowMinuteOfDay = 12 * 60)))
        assertTrue(NightModeRules.isAmbientNight(inputs(lowLightMs = 0L, sleepHoursEnabled = true, nowMinuteOfDay = 0)))
    }

    // --- NightModeRules.isForcedRed (Always setting, or the session's quick toggle) ---

    @Test fun alwaysForcesRedRegardlessOfSession() {
        assertTrue(NightModeRules.isForcedRed(NightMode.Always, sessionForced = false))
        assertTrue(NightModeRules.isForcedRed(NightMode.Always, sessionForced = true))
    }

    @Test fun sessionToggleForcesRedEvenWhenOff() {
        // The quick toggle is an explicit, in-the-moment user action — it overrides "Off" too.
        assertTrue(NightModeRules.isForcedRed(NightMode.Off, sessionForced = true))
        assertTrue(NightModeRules.isForcedRed(NightMode.Auto, sessionForced = true))
    }

    @Test fun autoAndOffAreNotForcedRedWithoutTheSessionToggle() {
        assertFalse(NightModeRules.isForcedRed(NightMode.Auto, sessionForced = false))
        assertFalse(NightModeRules.isForcedRed(NightMode.Off, sessionForced = false))
    }

    // --- NightModeRules.isAwake (tap-to-wake) ---

    @Test fun tapWakesForTheWakeWindow() {
        assertTrue(NightModeRules.isAwake(nowMs = 5_000L, wokenAtMs = 0L))
        assertTrue(NightModeRules.isAwake(nowMs = NightModeRules.WAKE_WINDOW_MS - 1, wokenAtMs = 0L))
    }

    @Test fun wakeWindowExpires() {
        assertFalse(NightModeRules.isAwake(nowMs = NightModeRules.WAKE_WINDOW_MS, wokenAtMs = 0L))
        assertFalse(NightModeRules.isAwake(nowMs = NightModeRules.WAKE_WINDOW_MS + 5_000L, wokenAtMs = 0L))
    }

    // --- LowLightAccumulator ---

    @Test fun lowLightAccumulatorGrowsInTheDark() {
        var lowMs = 0L
        lowMs = LowLightAccumulator.update(lowMs, 2f, 1_000L)
        assertEquals(1_000L, lowMs)
        lowMs = LowLightAccumulator.update(lowMs, 0f, 2_000L)
        assertEquals(3_000L, lowMs)
    }

    @Test fun lowLightAccumulatorResetsAboveThreshold() {
        val afterBright = LowLightAccumulator.update(currentLowMs = 4_000L, luxNow = 50f, deltaMs = 1_000L)
        assertEquals(0L, afterBright)
    }

    @Test fun lowLightAccumulatorThresholdIsExclusiveAtTheEdge() {
        // exactly at the threshold counts as "light enough" (>=), matching NightModeRules.LUX_THRESHOLD's doc.
        val result = LowLightAccumulator.update(currentLowMs = 3_000L, luxNow = NightModeRules.LUX_THRESHOLD, deltaMs = 1_000L)
        assertEquals(0L, result)
    }

    // --- NightPalette (red mapping, pure) ---

    @Test fun paletteConstantsMatchTomsSpec() {
        assertEquals(0xFFFF3B30.toInt(), NightPalette.PRIMARY_ARGB)
        assertEquals(0xFFB0261E.toInt(), NightPalette.SECONDARY_ARGB)
    }

    @Test fun mixTowardRedAtFullStrengthIsExactlyTheTint() {
        assertEquals(NightPalette.PRIMARY_ARGB, NightPalette.mixTowardRed(0xFF00FF00.toInt(), strength = 1f))
    }

    @Test fun mixTowardRedAtZeroStrengthIsUnchanged() {
        val white = 0xFFFFFFFF.toInt()
        assertEquals(white, NightPalette.mixTowardRed(white, strength = 0f))
    }

    @Test fun mixTowardRedPreservesAlpha() {
        val translucentBlue = 0x800000FF.toInt()
        val result = NightPalette.mixTowardRed(translucentBlue, strength = NightPalette.ARTWORK_TINT_ALPHA)
        assertEquals(0x80, (result ushr 24) and 0xFF)
    }

    @Test fun mixTowardRedMovesChannelsTowardTheTint() {
        // A green pixel mixed halfway toward red should land between the two on every channel.
        val green = 0xFF00FF00.toInt()
        val result = NightPalette.mixTowardRed(green, strength = 0.5f)
        val r = (result ushr 16) and 0xFF; val g = (result ushr 8) and 0xFF; val b = result and 0xFF
        assertEquals(0x7F, r) // 0 + (0xFF - 0) * .5 = 127.5, truncated
        assertEquals(0x9D, g) // 0xFF + (0x3B - 0xFF) * .5 = 157
        assertEquals(0x18, b) // 0 + (0x30 - 0) * .5 = 24

    }
}
