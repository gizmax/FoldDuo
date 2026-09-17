package cz.pflanzer.foldduo.desk

/**
 * B47 "Stůl": the deck's timer/stopwatch card math. Foreground-free by design (task spec): no
 * `AlarmManager`, just elapsed-realtime arithmetic ticked by a coroutine while the desk overlay is
 * composed (DeskCardsUi.kt), with a `NotificationManager` post when a countdown reaches zero
 * (the one moment it must be noticed even if the phone is no longer being looked at). Pure, no
 * Android types, so this runs as plain JUnit (DeskTimerTest).
 */
object DeskTimer {
    /** Countdown remaining, clamped to 0 — never negative once it has run out. */
    fun remainingMs(totalMs: Long, startedAtElapsedMs: Long, nowElapsedMs: Long): Long =
        (totalMs - (nowElapsedMs - startedAtElapsedMs)).coerceAtLeast(0L)

    /** True the instant (and forever after) [remainingMs] has hit 0. */
    fun isFinished(remainingMs: Long): Boolean = remainingMs <= 0L

    /** Stopwatch elapsed time: time since it was (re)started plus whatever was already
     * accumulated across earlier pause/resume cycles. */
    fun elapsedMs(startedAtElapsedMs: Long, nowElapsedMs: Long, pausedAccumMs: Long = 0L): Long =
        ((nowElapsedMs - startedAtElapsedMs) + pausedAccumMs).coerceAtLeast(0L)

    /** `H:MM:SS` past an hour, `MM:SS` otherwise; negative input reads as 0. */
    fun format(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }
}
