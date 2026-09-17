package cz.pflanzer.foldduo.notifications

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Redesign after Tom's 2026-09-17 device feedback ("ty notifikace duplikují defaultní … zároveň
 * berou půlku obrazovky"): app icon badges replace most of what the B34 hub used to do inline on
 * Today. [countBadgesByPackage] is the pure per-package counting rule (same eligibility
 * [isHubEligible] already defines for the hub — never ongoing/media, silent only with the setting
 * on), kept independent of Android types so it runs on the JVM (app/src/test) like
 * HubNotification.kt's own rules. [NotificationBadges] is the live StateFlow the Android glue in
 * island/IslandNotificationListener.kt publishes into (same listener grant, no new permission);
 * [BadgeSettings]/[badgesEnabled] is the "Badges" Appearance toggle, on by default, following the
 * exact shape of [NotificationHubSettings]/[notificationHubEnabled].
 */

/**
 * One posted notification's badge-relevant signals: same shape [isHubEligible] takes, plus the
 * package it belongs to. A group summary never reaches this (callers filter it out the same way
 * [HubNotification] does), so [countBadgesByPackage] only ever sees real, single notifications.
 */
data class BadgeCandidate(
    val packageName: String,
    val isOngoing: Boolean,
    val isMediaStyle: Boolean,
    val importance: Int,
)

/**
 * Active badge count per package: [candidates] filtered by the same rule the hub itself uses
 * ([isHubEligible] — never ongoing, never media, silent only while [showSilent]), then counted per
 * package. Independent of "Notifications on Today" — badges are their own toggle ([BadgeSettings]),
 * so this never checks that setting.
 */
fun countBadgesByPackage(candidates: List<BadgeCandidate>, showSilent: Boolean): Map<String, Int> =
    candidates.filter { isHubEligible(it.isOngoing, it.isMediaStyle, it.importance, showSilent) }
        .groupingBy { it.packageName }.eachCount()

/** The badge cap: a count at or past this renders as [BADGE_CAP]"+" ([badgeLabel]) instead of growing forever. */
const val BADGE_CAP = 99

/** Text drawn inside the badge: the plain count under [BADGE_CAP], "99+" at or past it. */
fun badgeLabel(count: Int): String = if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString()

/**
 * A folder's own badge: the sum of every member app's count ([memberPackages], resolved from
 * `FolderEntry.appIds` by the caller), 0 when none of them have one. A closed folder tile shows
 * one badge for the whole stack rather than one per child.
 */
fun folderBadgeCount(memberPackages: List<String>, countsByPackage: Map<String, Int>): Int =
    memberPackages.sumOf { countsByPackage[it] ?: 0 }

/**
 * Live counts (IslandNotificationListener.publishHub publishes into this alongside the hub
 * itself): package name to active badge count. Nothing persisted; like [NotificationHub.items]
 * this only ever holds what is currently posted and eligible.
 */
object NotificationBadges {
    private val countsState = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = countsState.asStateFlow()

    internal fun publish(counts: Map<String, Int>) { countsState.value = counts }
}

/**
 * [LocalNotificationBadges] carries the current (already gated by [BadgeSettings]) counts to every
 * icon surface — Home tiles, dock, folder tiles/children, App Library, Spotlight — without
 * threading a parameter through every intermediate composable, the same way [cz.pflanzer.foldduo.LocalIconStyle]
 * reaches those same call sites.
 */
val LocalNotificationBadges = compositionLocalOf<Map<String, Int>> { emptyMap() }

private const val BADGES_PREFS = "notification_badges"
private const val KEY_BADGES_ENABLED = "badgesEnabled"

/** "Badges" (Appearance settings): on by default. */
fun badgesEnabled(context: Context): Boolean =
    context.getSharedPreferences(BADGES_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BADGES_ENABLED, true)

fun setBadgesEnabled(context: Context, value: Boolean) {
    context.getSharedPreferences(BADGES_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BADGES_ENABLED, value).apply()
}

/** Live Compose mirror of [badgesEnabled], the same self-contained pattern as [NotificationHubSettings]. */
object BadgeSettings {
    private val enabledState = mutableStateOf(true)
    val enabled: State<Boolean> get() = enabledState

    fun refresh(context: Context) { enabledState.value = badgesEnabled(context) }

    fun setEnabled(context: Context, value: Boolean) {
        setBadgesEnabled(context, value); enabledState.value = value
    }
}
