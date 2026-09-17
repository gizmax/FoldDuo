package cz.pflanzer.foldduo.standby

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * StandBy v2 (Tom's request 17. 9. — "postaví se jako stan, dvě půlky, swipe mění hodiny a
 * informace jako u iPhone"): the Tent cover splits into two independent vertical carousels
 * (clock | info) plus three horizontally-swiped views (Widgets | Photos | Clock). Everything
 * here is pure Kotlin, no Android types, so ordering, cycling and availability run as plain
 * JUnit (StandByStacksTest) the same way FaceOrder/StandByRules already do.
 */

/** Cards the left "clock" stack can cycle through. */
enum class ClockCard { Digital, Analog, WorldClock, NextAlarm, Timer }

/** Cards the right "info" stack can cycle through. */
enum class InfoCard { Calendar, Weather, Batteries, NowPlaying, Notifications, Photos }

/** The three views a horizontal swipe on the Tent cover cycles between. */
enum class StandByView { Widgets, Photos, Clock }

/** Cycles a value within a list (wrap-around); a no-op on an empty or single-item list. */
object CardStack {
    fun <T> cycle(cards: List<T>, current: T, forward: Boolean): T {
        if (cards.size <= 1) return cards.firstOrNull() ?: current
        val index = cards.indexOf(current).coerceAtLeast(0)
        val next = if (forward) (index + 1) % cards.size else (index - 1 + cards.size) % cards.size
        return cards[next]
    }

    /** [FaceOrder.moveUp], generically: swaps [item] one place earlier; a no-op at index 0 or absent. */
    fun <T> moveUp(items: List<T>, item: T): List<T> {
        val index = items.indexOf(item)
        if (index <= 0) return items
        return items.toMutableList().also { it[index] = it[index - 1]; it[index - 1] = item }
    }
}

/** "Digital,Analog,NextAlarm" order/visibility of the clock stack, tolerant like [FaceOrder]. */
object ClockCardOrder {
    val DEFAULT: List<ClockCard> = listOf(ClockCard.Digital, ClockCard.Analog, ClockCard.NextAlarm)

    fun encode(cards: List<ClockCard>): String = cards.joinToString(",") { it.name }

    fun decode(text: String?): List<ClockCard> {
        if (text == null) return DEFAULT
        val cards = text.split(',').mapNotNull { name ->
            ClockCard.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }.distinct()
        return cards.ifEmpty { DEFAULT }
    }

    fun toggle(cards: List<ClockCard>, card: ClockCard, shown: Boolean): List<ClockCard> = when {
        shown && card !in cards -> cards + card
        !shown && cards.size > 1 -> cards - card
        else -> cards
    }
}

/** Order/visibility of the info stack; [available] additionally hides Weather without a host widget. */
object InfoCardOrder {
    val DEFAULT: List<InfoCard> =
        listOf(InfoCard.Calendar, InfoCard.NowPlaying, InfoCard.Batteries, InfoCard.Notifications, InfoCard.Photos)

    fun encode(cards: List<InfoCard>): String = cards.joinToString(",") { it.name }

    fun decode(text: String?): List<InfoCard> {
        if (text == null) return DEFAULT
        val cards = text.split(',').mapNotNull { name ->
            InfoCard.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }.distinct()
        return cards.ifEmpty { DEFAULT }
    }

    fun toggle(cards: List<InfoCard>, card: InfoCard, shown: Boolean): List<InfoCard> = when {
        shown && card !in cards -> cards + card
        !shown && cards.size > 1 -> cards - card
        else -> cards
    }

    /**
     * Card availability rule: Weather only ever shows once the user has placed a weather host
     * widget on Today, regardless of stored order — a stack that would otherwise be left empty
     * (Weather was the only member) falls back to [DEFAULT] rather than showing nothing.
     */
    fun available(cards: List<InfoCard>, hasWeatherWidget: Boolean): List<InfoCard> {
        val filtered = if (hasWeatherWidget) cards else cards.filterNot { it == InfoCard.Weather }
        return filtered.ifEmpty { DEFAULT } // DEFAULT never includes Weather, so this is always safe
    }
}

/** Fixed left-to-right cycle order of the three StandBy views. */
object StandByViewOrder {
    val ORDER: List<StandByView> = listOf(StandByView.Widgets, StandByView.Photos, StandByView.Clock)

    fun cycle(current: StandByView, forward: Boolean): StandByView = CardStack.cycle(ORDER, current, forward)
}

/**
 * `AlarmManager.nextAlarmClock`'s countdown, iOS-style: "za 6 h 12 min" (Czech) / "in 6h 12m"
 * (English), rounded up to the next whole minute so a 20 s countdown never freezes at "0 min".
 * Already-due or unknown alarms read as "now"/"teď" rather than a negative duration.
 */
object NextAlarmFormat {
    fun countdown(nowMs: Long, alarmMs: Long, czech: Boolean): String {
        val diff = alarmMs - nowMs
        if (diff <= 0L) return if (czech) "teď" else "now"
        val totalMinutes = (diff + 59_999L) / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (czech) czechCountdown(hours, minutes) else englishCountdown(hours, minutes)
    }

    private fun czechCountdown(hours: Long, minutes: Long): String = when {
        hours > 0 && minutes > 0 -> "za $hours h $minutes min"
        hours > 0 -> "za $hours h"
        else -> "za $minutes min"
    }

    private fun englishCountdown(hours: Long, minutes: Long): String = when {
        hours > 0 && minutes > 0 -> "in ${hours}h ${minutes}m"
        hours > 0 -> "in ${hours}h"
        else -> "in ${minutes}m"
    }
}

/**
 * Text for the night face (StandByStacksUi.kt's `NightModeFace`). Device report (17. 9. night):
 * the face used to show the *alarm's* time as its big digits, falling back to the clock only
 * without an alarm — so a real alarm that happened to be set for local midnight read as a giant
 * red "00:00" moments before the activity exited, which looked like a rendering bug. The big
 * digits are now always the current wall-clock time; the alarm (or its absence) is a small
 * second line. `java.time` only, no Android types, so this runs as plain JUnit.
 */
object NightFaceFormat {
    /** The primary, large digits: always the current time, never the alarm's own time. */
    fun clockText(now: LocalDateTime, pattern: String): String = now.format(DateTimeFormatter.ofPattern(pattern))

    /** The secondary line: the next alarm at [pattern], or the "no alarm" label in [czech]/English. */
    fun alarmText(nextAlarmMs: Long?, zone: ZoneId, pattern: String, czech: Boolean): String {
        if (nextAlarmMs == null) return if (czech) "Žádný budík" else "No alarm"
        val at = Instant.ofEpochMilli(nextAlarmMs).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))
        return if (czech) "BUDÍK $at" else "ALARM $at"
    }
}
