package cz.pflanzer.foldduo

import android.content.Context
import org.json.JSONObject

/**
 * B26 Smart stack follow-up: persists a manual swipe's [StackPin] per widget slot across process
 * death (a Cover <-> Inner panel swap relaunches [MainActivity], the same reason [PoseEngine] is
 * process-scoped) — its own tiny `SharedPreferences` file, not threaded through `HomeLayout`/
 * `LayoutBackup.kt` (same self-contained pattern as `NotificationHubSettings`/`PredictionSettings`
 * in their own packages), since a pin is deliberately ephemeral ([STACK_PIN_DURATION_MS], 30
 * minutes) and never meant to round-trip through a layout backup/restore.
 */
object StackPinStore {
    private const val PREFS_NAME = "stackPins"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun keyFor(slot: Int) = "slot_$slot"

    /** Persists [pin] for [slot], replacing any previous one — a fresh manual swipe always wins. */
    fun set(context: Context, slot: Int, pin: StackPin) {
        val json = JSONObject().put("memberId", pin.memberId).put("pinnedAtMillis", pin.pinnedAtMillis)
        prefs(context).edit().putString(keyFor(slot), json.toString()).apply()
    }

    /**
     * The pin persisted for [slot], or null if none was ever recorded (or the stored JSON is
     * corrupt). Deliberately does not check [StackRotation.isPinned] itself — the caller already
     * does that with "now", so a stale pin here just reads as expired there, rather than this
     * store silently disagreeing about what "expired" means.
     */
    fun get(context: Context, slot: Int): StackPin? {
        val raw = prefs(context).getString(keyFor(slot), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            StackPin(json.getInt("memberId"), json.getLong("pinnedAtMillis"))
        }.getOrNull()
    }

    /** Removes the persisted pin for [slot] (a stack that dissolves or is removed no longer needs one). */
    fun clear(context: Context, slot: Int) {
        prefs(context).edit().remove(keyFor(slot)).apply()
    }

    /** Test-only reset so unit/instrumentation runs don't leak state across cases. */
    internal fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
