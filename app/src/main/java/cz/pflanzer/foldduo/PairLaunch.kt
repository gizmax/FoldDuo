package cz.pflanzer.foldduo

import android.app.ActivityOptions
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.delay

private const val TAG = "FoldDuoPairs"

/** Poll interval/timeout while waiting for `first` to actually resume before opening split screen. */
internal const val PAIR_RESUME_POLL_INTERVAL_MS = 80L
internal const val PAIR_RESUME_POLL_TIMEOUT_MS = 2000L

/** Fallback wait when usage access isn't granted — the task's specified "else 600 ms". */
internal const val PAIR_RESUME_FALLBACK_DELAY_MS = 600L

/**
 * B46 "Dvojice aplikací": the Android-facing half of a pair's split-screen launch sequence. Pure
 * creation/swap/split decisions live in PairEditing.kt; the actual step-by-step — launch `first`
 * with the zoom, wait for it to resume, toggle split screen via the accessibility service, launch
 * `second` adjacent — is orchestrated by `MainActivity.launchPair`, since only the activity holds
 * both the current pose (cover vs. inner — the cover only ever launches `first`) and the
 * accessibility service's global action. Every step logs under [TAG]: Android gives no callback
 * for "did the second app really land beside the first", the same caveat
 * predict/PredictionGlue.kt's `launchContinuityApp` already documents for its own adjacent launch.
 */
object PairLaunch {
    /**
     * Waits for [packageName] to become the foreground app: polls `UsageStatsManager` every
     * [PAIR_RESUME_POLL_INTERVAL_MS] up to [timeoutMs] when usage access is granted, else a flat
     * [PAIR_RESUME_FALLBACK_DELAY_MS] delay. Never throws — a timeout just means the sequence
     * continues anyway (`first` almost certainly is the foreground app either way; this is a
     * best-effort pacing hint, not a hard gate).
     */
    suspend fun awaitResumed(context: Context, packageName: String, timeoutMs: Long = PAIR_RESUME_POLL_TIMEOUT_MS) {
        if (!cz.pflanzer.foldduo.predict.hasUsageAccess(context)) { delay(PAIR_RESUME_FALLBACK_DELAY_MS); return }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        if (usm == null) { delay(PAIR_RESUME_FALLBACK_DELAY_MS); return }
        val deadline = System.currentTimeMillis() + timeoutMs
        var since = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val resumed = runCatching {
                val events = usm.queryEvents(since, now)
                var seen = false
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName == packageName) seen = true
                }
                seen
            }.getOrDefault(false)
            since = now
            if (resumed) { Log.d(TAG, "$packageName resumed"); return }
            delay(PAIR_RESUME_POLL_INTERVAL_MS)
        }
        Log.d(TAG, "$packageName resume not observed within ${timeoutMs}ms, continuing anyway")
    }

    /**
     * `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` via the shade accessibility service. False when the
     * service is not bound (the caller shows the "turn on the accessibility service" prompt) or
     * Android rejected the action outright — either way, `second` is never launched.
     */
    fun toggleSplitScreen(context: Context): Boolean {
        val result = SystemShadeAccessibilityService.toggleSplitScreen(context)
        Log.d(TAG, "toggle split screen: $result")
        return result == ShadeOpenResult.OPENED
    }

    /**
     * `second`'s side of the pair: `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_LAUNCH_ADJACENT` with
     * [rightPaneBounds] as a best-effort split-screen hint — the same pattern
     * predict/PredictionGlue.kt's `launchContinuityApp` uses for the continuity chip's own
     * adjacent launch, including its cross-profile limitation (a plain launch intent, so a work
     * profile app is not specially handled here either).
     */
    fun launchAdjacent(context: Context, app: AppEntry, rightPaneBounds: Rect?): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) { Log.d(TAG, "no launch intent for ${app.packageName}"); return false }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val options = rightPaneBounds?.let { bounds -> ActivityOptions.makeBasic().apply { setLaunchBounds(bounds) } }
        return try {
            context.startActivity(intent, options?.toBundle())
            Log.d(TAG, "launched ${app.packageName} adjacent, bounds=$rightPaneBounds")
            true
        } catch (e: Exception) {
            Log.d(TAG, "adjacent launch failed for ${app.packageName}: ${e.message}")
            false
        }
    }
}
