package cz.pflanzer.foldduo.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Drawable

/*
 * IDEAS.md B34, "Notifikace jako skleněné karty na Today": pure data and rules behind the
 * notification hub on the leading canvas (page -1). Android types here are nullable/primitive so
 * this file's rules are exercised on the JVM (app/src/test), the same split IslandItem.kt uses
 * for the rail island. The Android glue that turns a StatusBarNotification into a [HubNotification]
 * lives in island/IslandNotificationListener.kt, which already holds the notification-listener
 * grant the island needs.
 */

/** One action button on an expanded card (at most three, [ACTIONS_PER_CARD]). */
data class HubAction(val title: String, val intent: PendingIntent? = null)

/** At most this many action chips show on an expanded card. */
const val HUB_ACTIONS_PER_CARD = 3

/**
 * One notification eligible for the hub: not ongoing/Now Bar (the island already shows those),
 * not a media session, and not silent unless the user opted in ([isHubEligible]).
 */
data class HubNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val appIcon: Drawable? = null,
    val title: String = "",
    val text: String = "",
    /** Wall-clock ms this notification was posted or last updated; drives ordering and the relative-time label. */
    val whenTime: Long = 0L,
    val actions: List<HubAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
    /** `StatusBarNotification.groupKey`; falls back to the package name when the poster set none. */
    val groupKey: String = "",
    /** False for a notification the system will not let the hub cancel (`FLAG_NO_CLEAR`, an ongoing one). */
    val isClearable: Boolean = true,
)

/**
 * One app's stack in the hub: newest first ([notifications]), a card for [top] and a "+N" for the
 * rest ([stackCount]). [groupHubNotifications] builds these, one per distinct package.
 */
data class HubGroup(val packageName: String, val appLabel: String, val appIcon: Drawable?, val notifications: List<HubNotification>) {
    init { require(notifications.isNotEmpty()) { "a group needs at least one notification" } }
    val top: HubNotification get() = notifications.first()
    /** Cards behind the top one in the collapsed stack, shown as "+N". */
    val stackCount: Int get() = notifications.size - 1
}

/**
 * Whether a posted notification belongs in the hub: never an ongoing one (the island already
 * covers Live Updates, Samsung Now Bar activities and other ongoing categories), never a media
 * session (the island's transport controls cover that), and — unless [showSilent] — never one
 * below [NotificationManager.IMPORTANCE_DEFAULT] (the user muted its channel, or the poster asked
 * for silence). A group summary is filtered by the caller before this ever runs.
 */
fun isHubEligible(isOngoing: Boolean, isMediaStyle: Boolean, importance: Int, showSilent: Boolean): Boolean {
    if (isOngoing || isMediaStyle) return false
    if (!showSilent && importance < NotificationManager.IMPORTANCE_DEFAULT) return false
    return true
}

/**
 * Group [notifications] by package, newest-first within a group and groups ordered by their
 * newest notification. A stable order (package name, then key) breaks ties so two posts in the
 * same millisecond do not reshuffle between recompositions.
 */
fun groupHubNotifications(notifications: List<HubNotification>): List<HubGroup> =
    notifications.groupBy { it.packageName }
        .map { (pkg, group) ->
            val sorted = group.sortedWith(compareByDescending<HubNotification> { it.whenTime }.thenBy { it.key })
            HubGroup(pkg, sorted.first().appLabel, sorted.first().appIcon, sorted)
        }
        .sortedWith(compareByDescending<HubGroup> { it.top.whenTime }.thenBy { it.packageName })

/** Grid rows the 4x6 leading canvas reserves for the hub: 0 empty, otherwise the group count capped at [HUB_MAX_ROWS]. */
const val HUB_MAX_ROWS = 3

/**
 * Rows reserved at the top of the leading canvas for [groupCount] hub groups: 0 with no groups,
 * exactly 1 while the compact pill shows ([expanded] false — the redesigned default, one glass
 * summary row regardless of how many groups are behind it), otherwise the group count capped at
 * [HUB_MAX_ROWS] the way the expanded cards always did. [expanded] defaults true so every caller
 * from before the compact pill existed keeps its old row count unchanged.
 */
fun hubRowsReserved(groupCount: Int, expanded: Boolean = true): Int {
    if (groupCount == 0) return 0
    return if (!expanded) 1 else groupCount.coerceIn(1, HUB_MAX_ROWS)
}

/**
 * Compact hub pill (redesign after Tom's 2026-09-17 feedback): whether the Today canvas shows the
 * one-row glass summary ([HubPillState.COLLAPSED]) or the full cards ([HubPillState.EXPANDED]).
 * [reduceHubPill] is the whole state machine — a tap toggles, everything else forces collapsed —
 * kept as one pure function so the collapse triggers (leaving Today, resuming the app, the 20 s
 * timeout) are each just a call site for the same rule instead of their own ad hoc `= false`.
 */
enum class HubPillState { COLLAPSED, EXPANDED }

/** Events [reduceHubPill] reacts to; the effects that dispatch them (LauncherScreen.kt) live outside this pure file. */
enum class HubPillEvent { TAP, LEAVE_TODAY, RESUME, TIMEOUT }

/** How long the expanded pill stays open before [HubPillEvent.TIMEOUT] collapses it again. */
const val HUB_PILL_AUTO_COLLAPSE_MS = 20_000L

fun reduceHubPill(state: HubPillState, event: HubPillEvent): HubPillState = when (event) {
    HubPillEvent.TAP -> if (state == HubPillState.COLLAPSED) HubPillState.EXPANDED else HubPillState.COLLAPSED
    HubPillEvent.LEAVE_TODAY, HubPillEvent.RESUME, HubPillEvent.TIMEOUT -> HubPillState.COLLAPSED
}

/**
 * The compact pill's own text ("3 aplikace · 7 oznámení" / "3 apps · 7 notifications"): Czech
 * pluralisation when [locale]'s language is `cs` (1/2-4/5+, "oznámení" itself never changes form),
 * plain English singular/plural otherwise. [locale] defaults to the device's so call sites never
 * have to read it themselves, but takes one directly so this stays a pure, testable function.
 */
fun hubSummaryText(appCount: Int, notificationCount: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    fun czechForm(n: Int, one: String, few: String, many: String) = when {
        n == 1 -> one
        n in 2..4 -> few
        else -> many
    }
    return if (locale.language == "cs") {
        val apps = czechForm(appCount, "aplikace", "aplikace", "aplikací")
        val notifications = czechForm(notificationCount, "oznámení", "oznámení", "oznámení")
        "$appCount $apps · $notificationCount $notifications"
    } else {
        val apps = if (appCount == 1) "app" else "apps"
        val notifications = if (notificationCount == 1) "notification" else "notifications"
        "$appCount $apps · $notificationCount $notifications"
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60L * MINUTE_MS
private const val DAY_MS = 24L * HOUR_MS

/**
 * iOS-style relative time for a card's timestamp: "now" under a minute, minutes under an hour,
 * hours under a day, days beyond that. Never negative — a [whenMs] after [nowMs] (clock skew,
 * a notification posted mid-frame) still reads as "now".
 */
fun relativeTimeLabel(whenMs: Long, nowMs: Long): String {
    val elapsed = (nowMs - whenMs).coerceAtLeast(0L)
    return when {
        elapsed < MINUTE_MS -> "now"
        elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}m"
        elapsed < DAY_MS -> "${elapsed / HOUR_MS}h"
        else -> "${elapsed / DAY_MS}d"
    }
}
