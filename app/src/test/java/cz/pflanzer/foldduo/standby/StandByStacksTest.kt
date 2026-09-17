package cz.pflanzer.foldduo.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StandByStacksTest {

    // --- CardStack.cycle ---

    @Test fun cycleWrapsForwardAndBackward() {
        val cards = listOf(ClockCard.Digital, ClockCard.Analog, ClockCard.NextAlarm)
        assertEquals(ClockCard.Analog, CardStack.cycle(cards, ClockCard.Digital, forward = true))
        assertEquals(ClockCard.Digital, CardStack.cycle(cards, ClockCard.NextAlarm, forward = true))
        assertEquals(ClockCard.Digital, CardStack.cycle(cards, ClockCard.Analog, forward = false))
        assertEquals(ClockCard.NextAlarm, CardStack.cycle(cards, ClockCard.Digital, forward = false))
    }

    @Test fun cycleIsNoOpOnSingleOrEmptyList() {
        assertEquals(ClockCard.Digital, CardStack.cycle(listOf(ClockCard.Digital), ClockCard.Digital, forward = true))
        assertEquals(ClockCard.Digital, CardStack.cycle(emptyList(), ClockCard.Digital, forward = true))
    }

    @Test fun cycleRecoversFromACurrentNotInTheList() {
        val cards = listOf(ClockCard.Digital, ClockCard.Analog)
        assertEquals(ClockCard.Analog, CardStack.cycle(cards, ClockCard.Timer, forward = true))
    }

    // --- CardStack.moveUp ---

    @Test fun moveUpSwapsWithThePreviousItem() {
        val cards = listOf(ClockCard.Digital, ClockCard.Analog, ClockCard.NextAlarm)
        assertEquals(listOf(ClockCard.Analog, ClockCard.Digital, ClockCard.NextAlarm), CardStack.moveUp(cards, ClockCard.Analog))
    }

    @Test fun moveUpIsNoOpAtTheFrontOrWhenAbsent() {
        val cards = listOf(ClockCard.Digital, ClockCard.Analog)
        assertEquals(cards, CardStack.moveUp(cards, ClockCard.Digital))
        assertEquals(cards, CardStack.moveUp(cards, ClockCard.Timer))
    }

    // --- ClockCardOrder ---

    @Test fun clockCardOrderRoundTrips() {
        val cards = listOf(ClockCard.Timer, ClockCard.Digital, ClockCard.WorldClock)
        assertEquals(cards, ClockCardOrder.decode(ClockCardOrder.encode(cards)))
    }

    @Test fun clockCardOrderDefaultsOnNullOrEmpty() {
        assertEquals(ClockCardOrder.DEFAULT, ClockCardOrder.decode(null))
        assertEquals(ClockCardOrder.DEFAULT, ClockCardOrder.decode(""))
    }

    @Test fun clockCardOrderDropsUnknownAndDuplicates() {
        assertEquals(listOf(ClockCard.Digital, ClockCard.Analog), ClockCardOrder.decode("Digital,Bogus,Analog,digital"))
    }

    @Test fun clockCardToggleShowsAndHides() {
        val shown = ClockCardOrder.toggle(ClockCardOrder.DEFAULT, ClockCard.Timer, true)
        assertTrue(ClockCard.Timer in shown)
        val hidden = ClockCardOrder.toggle(shown, ClockCard.Timer, false)
        assertFalse(ClockCard.Timer in hidden)
    }

    @Test fun clockCardToggleNeverHidesTheLastCard() {
        val single = listOf(ClockCard.Digital)
        assertEquals(single, ClockCardOrder.toggle(single, ClockCard.Digital, false))
    }

    // --- InfoCardOrder ---

    @Test fun infoCardOrderRoundTrips() {
        val cards = listOf(InfoCard.Weather, InfoCard.Calendar, InfoCard.Photos)
        assertEquals(cards, InfoCardOrder.decode(InfoCardOrder.encode(cards)))
    }

    @Test fun infoCardOrderDefaultsOnNullOrEmpty() {
        assertEquals(InfoCardOrder.DEFAULT, InfoCardOrder.decode(null))
        assertEquals(InfoCardOrder.DEFAULT, InfoCardOrder.decode(""))
    }

    @Test fun infoCardAvailabilityHidesWeatherWithoutAHostWidget() {
        val cards = listOf(InfoCard.Weather, InfoCard.Calendar)
        assertEquals(listOf(InfoCard.Calendar), InfoCardOrder.available(cards, hasWeatherWidget = false))
        assertEquals(cards, InfoCardOrder.available(cards, hasWeatherWidget = true))
    }

    @Test fun infoCardAvailabilityFallsBackToDefaultWhenWeatherWasTheOnlyCard() {
        assertEquals(InfoCardOrder.DEFAULT, InfoCardOrder.available(listOf(InfoCard.Weather), hasWeatherWidget = false))
    }

    @Test fun infoCardAvailabilityLeavesOtherCardsAloneWhenWeatherIsAvailable() {
        assertEquals(listOf(InfoCard.Weather), InfoCardOrder.available(listOf(InfoCard.Weather), hasWeatherWidget = true))
    }

    // --- StandByViewOrder ---

    @Test fun standByViewCyclesForward() {
        assertEquals(StandByView.Photos, StandByViewOrder.cycle(StandByView.Widgets, forward = true))
        assertEquals(StandByView.Clock, StandByViewOrder.cycle(StandByView.Photos, forward = true))
        assertEquals(StandByView.Widgets, StandByViewOrder.cycle(StandByView.Clock, forward = true))
    }

    @Test fun standByViewCyclesBackward() {
        assertEquals(StandByView.Clock, StandByViewOrder.cycle(StandByView.Widgets, forward = false))
        assertEquals(StandByView.Widgets, StandByViewOrder.cycle(StandByView.Photos, forward = false))
        assertEquals(StandByView.Photos, StandByViewOrder.cycle(StandByView.Clock, forward = false))
    }

    // --- NextAlarmFormat ---

    @Test fun nextAlarmCountdownCzechHoursAndMinutes() {
        val now = 0L
        val alarm = (6 * 60 + 12) * 60_000L
        assertEquals("za 6 h 12 min", NextAlarmFormat.countdown(now, alarm, czech = true))
        assertEquals("in 6h 12m", NextAlarmFormat.countdown(now, alarm, czech = false))
    }

    @Test fun nextAlarmCountdownWholeHourOmitsMinutes() {
        val alarm = 60 * 60_000L
        assertEquals("za 1 h", NextAlarmFormat.countdown(0L, alarm, czech = true))
        assertEquals("in 1h", NextAlarmFormat.countdown(0L, alarm, czech = false))
    }

    @Test fun nextAlarmCountdownUnderAnHourOmitsHours() {
        val alarm = 45 * 60_000L
        assertEquals("za 45 min", NextAlarmFormat.countdown(0L, alarm, czech = true))
        assertEquals("in 45m", NextAlarmFormat.countdown(0L, alarm, czech = false))
    }

    @Test fun nextAlarmCountdownRoundsUpToTheNextMinute() {
        // 30 s left still reads as "in 1m", not "in 0m" — a countdown must never show zero while due.
        assertEquals("za 1 min", NextAlarmFormat.countdown(0L, 30_000L, czech = true))
        assertEquals("in 1m", NextAlarmFormat.countdown(0L, 30_000L, czech = false))
    }

    @Test fun nextAlarmCountdownDueOrPastReadsAsNow() {
        assertEquals("teď", NextAlarmFormat.countdown(1_000L, 1_000L, czech = true))
        assertEquals("now", NextAlarmFormat.countdown(2_000L, 1_000L, czech = false))
    }

    // --- WorldClockCities ---

    @Test fun worldClockLooksUpAPresetByZoneId() {
        assertEquals("Tokyo", WorldClockCities.byZoneId("Asia/Tokyo")?.label)
        assertEquals(null, WorldClockCities.byZoneId("Bogus/Zone"))
        assertEquals(null, WorldClockCities.byZoneId(null))
    }

    @Test fun worldClockValidatesZoneIds() {
        assertTrue(WorldClockCities.isValidZone("Europe/Prague"))
        assertFalse(WorldClockCities.isValidZone("Not/AZone"))
        assertFalse(WorldClockCities.isValidZone(null))
        assertFalse(WorldClockCities.isValidZone(""))
    }

    @Test fun everyPresetCityIsAValidZone() {
        WorldClockCities.PRESETS.forEach { assertTrue(it.zoneId, WorldClockCities.isValidZone(it.zoneId)) }
    }

    // --- NightFaceFormat ---
    // Device report (17. 9. night): the night face used to show the *alarm's* time as its big
    // digits, so a real midnight alarm briefly read as a giant red "00:00" — indistinguishable
    // from a data bug. clockText is now always the current time; alarmText is the secondary line.

    @Test fun nightFaceClockTextIsAlwaysTheCurrentTimeNeverTheAlarm() {
        val now = LocalDateTime.of(2026, 9, 18, 0, 0)
        // Midnight exactly: the classic bug would have shown an alarm's "00:00" here too, but this
        // function never even sees an alarm — it only ever formats `now`.
        assertEquals("00:00", NightFaceFormat.clockText(now, "HH:mm"))
        assertEquals("13:45", NightFaceFormat.clockText(LocalDateTime.of(2026, 9, 18, 13, 45), "HH:mm"))
    }

    @Test fun nightFaceAlarmTextShowsNoAlarmLabelWhenAbsent() {
        assertEquals("Žádný budík", NightFaceFormat.alarmText(null, ZoneId.of("UTC"), "HH:mm", czech = true))
        assertEquals("No alarm", NightFaceFormat.alarmText(null, ZoneId.of("UTC"), "HH:mm", czech = false))
    }

    @Test fun nightFaceAlarmTextFormatsTheAlarmWhenPresent() {
        val zone = ZoneId.of("UTC")
        val alarmMs = LocalDateTime.of(2026, 9, 18, 6, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("BUDÍK 06:30", NightFaceFormat.alarmText(alarmMs, zone, "HH:mm", czech = true))
        assertEquals("ALARM 06:30", NightFaceFormat.alarmText(alarmMs, zone, "HH:mm", czech = false))
    }
}
