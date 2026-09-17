package cz.pflanzer.foldduo.desk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.R
import kotlinx.coroutines.delay

/**
 * B47 "Stůl": the deck cards that are not the media player (DeskMedia.kt) or the quick
 * toggles/recents (both reused straight from SeamPalette.kt via DeskDeck.kt).
 */

// --- Timer / stopwatch ---------------------------------------------------------------------

private enum class DeskTimerMode { Idle, Countdown, Stopwatch }

/**
 * Foreground-free (task spec): the countdown/stopwatch only ticks while this card is composed
 * (Desk is showing), via [LaunchedEffect] — no `AlarmManager`. A finished countdown posts a
 * plain [NotificationManager] notification so it is still noticed if the phone was set down and
 * the desk overlay already left (Stand pose ended).
 */
@Composable
internal fun DeskTimerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(DeskTimerMode.Idle) }
    var totalMs by remember { mutableLongStateOf(5 * 60_000L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var pausedAccum by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var displayMs by remember { mutableLongStateOf(totalMs) }
    var notified by remember { mutableStateOf(false) }

    LaunchedEffect(mode, running, startedAt, totalMs) {
        if (!running) return@LaunchedEffect
        while (running) {
            val now = SystemClock.elapsedRealtime()
            displayMs = when (mode) {
                DeskTimerMode.Countdown -> DeskTimer.remainingMs(totalMs, startedAt, now)
                DeskTimerMode.Stopwatch -> DeskTimer.elapsedMs(startedAt, now, pausedAccum)
                DeskTimerMode.Idle -> 0L
            }
            if (mode == DeskTimerMode.Countdown && DeskTimer.isFinished(displayMs) && !notified) {
                notified = true
                running = false
                postDeskTimerFinishedNotification(context)
            }
            delay(200L)
        }
    }

    Column(modifier.testTag("desk-timer-card")) {
        Text(DeskTimer.format(displayMs), color = Color.White, fontSize = 32.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                mode = DeskTimerMode.Countdown; totalMs = 5 * 60_000L; startedAt = SystemClock.elapsedRealtime()
                pausedAccum = 0L; displayMs = totalMs; notified = false; running = true
            }, modifier = Modifier.testTag("desk-timer-start-5m")) { Text("5 min") }
            IconButton(onClick = {
                when {
                    mode == DeskTimerMode.Idle -> {
                        mode = DeskTimerMode.Stopwatch; startedAt = SystemClock.elapsedRealtime(); pausedAccum = 0L; running = true
                    }
                    running -> {
                        running = false
                        if (mode == DeskTimerMode.Stopwatch) pausedAccum = DeskTimer.elapsedMs(startedAt, SystemClock.elapsedRealtime(), pausedAccum)
                        else pausedAccum = DeskTimer.remainingMs(totalMs, startedAt, SystemClock.elapsedRealtime())
                    }
                    else -> {
                        startedAt = SystemClock.elapsedRealtime()
                        if (mode == DeskTimerMode.Countdown) totalMs = pausedAccum
                        running = true
                    }
                }
            }, modifier = Modifier.testTag("desk-timer-toggle")) {
                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (running) "Pause" else "Start", tint = Color.White)
            }
            IconButton(onClick = {
                mode = DeskTimerMode.Idle; running = false; totalMs = 5 * 60_000L; pausedAccum = 0L; displayMs = totalMs; notified = false
            }, modifier = Modifier.testTag("desk-timer-reset")) { Icon(Icons.Rounded.Refresh, "Reset", tint = Color.White.copy(alpha = .8f)) }
        }
    }
}

private fun postDeskTimerFinishedNotification(context: Context) {
    runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(DESK_TIMER_CHANNEL, "Desk timer", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The Desk mode timer/stopwatch card finished a countdown."
        })
        val notification = Notification.Builder(context, DESK_TIMER_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Timer done")
            .setContentText("The Desk timer finished.")
            .setAutoCancel(true)
            .build()
        manager.notify(DESK_TIMER_NOTIFICATION_ID, notification)
    }
}

private const val DESK_TIMER_CHANNEL = "desk_timer"
private const val DESK_TIMER_NOTIFICATION_ID = 4701

// --- Quick note -----------------------------------------------------------------------------

/** Single multiline field, persisted straight to [DeskPrefs] (no separate view-model — the whole
 * card is small enough that reading/writing prefs on every keystroke is cheap, same convention as
 * `StandByPrefs`'s other single-value settings). */
@Composable
internal fun DeskNoteCard(prefs: DeskPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(prefs.noteText) }
    Column(modifier.testTag("desk-note-card")) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; prefs.noteText = it },
            modifier = Modifier.fillMaxWidth().height(96.dp).testTag("desk-note-field"),
            placeholder = { Text("Note…", color = Color.White.copy(alpha = .5f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = .4f), unfocusedBorderColor = Color.White.copy(alpha = .2f),
            ),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { shareDeskNote(context, text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("desk-note-share")) {
                Icon(Icons.Rounded.Share, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.padding(end = 6.dp))
                Text("Share", color = Color.White.copy(alpha = .8f))
            }
        }
    }
}

private fun shareDeskNote(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// --- Calculator ------------------------------------------------------------------------------

@Composable
internal fun DeskCalculatorCard(modifier: Modifier = Modifier) {
    var expression by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<DeskCalcEntry>()) }
    fun evaluate() {
        when (val result = DeskCalculator.evaluate(expression)) {
            is DeskCalculator.Result.Value -> {
                error = false
                history = DeskCalculatorHistory.push(history, DeskCalcEntry(expression, result.value))
                expression = formatCalcNumber(result.value)
            }
            is DeskCalculator.Result.Error -> error = true
        }
    }
    Column(modifier.testTag("desk-calculator-card")) {
        OutlinedTextField(
            value = expression,
            onValueChange = { expression = it; error = false },
            modifier = Modifier.fillMaxWidth().testTag("desk-calculator-field"),
            singleLine = true,
            placeholder = { Text("0", color = Color.White.copy(alpha = .5f)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            isError = error,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = .4f), unfocusedBorderColor = Color.White.copy(alpha = .2f),
            ),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("+", "-", "×", "÷", "%", "C").forEach { op ->
                TextButton(onClick = { if (op == "C") { expression = ""; error = false } else expression += op },
                    modifier = Modifier.testTag("desk-calculator-op-$op")) { Text(op, color = Color.White) }
            }
            TextButton(onClick = { evaluate() }, modifier = Modifier.testTag("desk-calculator-equals")) { Text("=", color = Color.White) }
        }
        if (error) Text("Error", color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (history.isNotEmpty()) Column(Modifier.padding(top = 8.dp)) {
            history.forEach { entry ->
                Text("${entry.expression} = ${formatCalcNumber(entry.result)}", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
            }
        }
    }
}

private fun formatCalcNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString()
    else value.toString()
