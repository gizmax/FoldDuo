package cz.pflanzer.foldduo.notifications

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import cz.pflanzer.foldduo.island.IslandNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live hub state (IDEAS.md B34): published by [IslandNotificationListener], the service already
 * granted notification access for the rail island, so the hub needs no separate permission.
 * Nothing is persisted; like the island's own [IslandNotificationListener.items] this only ever
 * holds what is currently posted.
 */
object NotificationHub {
    private val itemsState = MutableStateFlow<List<HubNotification>>(emptyList())
    val items: StateFlow<List<HubNotification>> = itemsState.asStateFlow()

    /** Called by [IslandNotificationListener.publish]; empty while the keyguard is locked. */
    internal fun publish(notifications: List<HubNotification>) { itemsState.value = notifications }

    /** Swipe-to-dismiss and "Clear all": cancel the notification behind [key] like the shade would. */
    fun dismiss(key: String) = IslandNotificationListener.dismiss(key)
}

private const val HUB_PREFS = "notification_hub"
private const val KEY_ENABLED = "hubEnabled"
private const val KEY_SHOW_SILENT = "showSilent"

/**
 * "Notifications on Today" (Appearance settings): off by default (redesign after Tom's 2026-09-17
 * feedback — the hub duplicated the system shade and ate up to half the screen; badges
 * (Badges.kt) now carry that job by default, this stays opt-in).
 */
fun notificationHubEnabled(context: Context): Boolean =
    context.getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

fun setNotificationHubEnabled(context: Context, value: Boolean) {
    context.getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
}

/** "Show silent notifications" (Appearance settings): off by default, read live by the listener. */
fun showSilentNotifications(context: Context): Boolean =
    context.getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_SILENT, false)

fun setShowSilentNotifications(context: Context, value: Boolean) {
    context.getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_SILENT, value).apply()
}

/**
 * The two Appearance toggles as live Compose state (same need [cz.pflanzer.foldduo.MotionPrefs]
 * fills for reduced motion): the settings row and the leading canvas can be far apart in the
 * composition, so a plain `remember` at each site would miss the other one's change within the
 * same session. [refresh] seeds this from `SharedPreferences` once at startup; both setters keep
 * it in sync from then on because they are the only way the UI changes these two keys.
 */
object NotificationHubSettings {
    private val hubEnabledState = mutableStateOf(false)
    private val showSilentState = mutableStateOf(false)
    val hubEnabled: State<Boolean> get() = hubEnabledState
    val showSilent: State<Boolean> get() = showSilentState

    fun refresh(context: Context) {
        hubEnabledState.value = notificationHubEnabled(context)
        showSilentState.value = showSilentNotifications(context)
    }

    fun setHubEnabled(context: Context, value: Boolean) {
        setNotificationHubEnabled(context, value); hubEnabledState.value = value
    }

    fun setShowSilent(context: Context, value: Boolean) {
        setShowSilentNotifications(context, value); showSilentState.value = value
    }
}
