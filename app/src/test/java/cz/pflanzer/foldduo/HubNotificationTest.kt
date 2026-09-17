package cz.pflanzer.foldduo

import android.app.NotificationManager
import cz.pflanzer.foldduo.notifications.HUB_MAX_ROWS
import cz.pflanzer.foldduo.notifications.HubNotification
import cz.pflanzer.foldduo.notifications.HubPillEvent
import cz.pflanzer.foldduo.notifications.HubPillState
import cz.pflanzer.foldduo.notifications.groupHubNotifications
import cz.pflanzer.foldduo.notifications.hubRowsReserved
import cz.pflanzer.foldduo.notifications.hubSummaryText
import cz.pflanzer.foldduo.notifications.isHubEligible
import cz.pflanzer.foldduo.notifications.reduceHubPill
import cz.pflanzer.foldduo.notifications.relativeTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The notification hub's pure rules (IDEAS.md B34, notifications/HubNotification.kt): filtering, grouping/ordering, row reservation and the relative-time label. */
class HubNotificationTest {
    private fun notification(key: String, pkg: String, whenTime: Long, label: String = pkg) =
        HubNotification(key = key, packageName = pkg, appLabel = label, whenTime = whenTime)

    // --- isHubEligible -----------------------------------------------------------------------

    @Test fun `ongoing notifications never enter the hub`() {
        assertFalse(isHubEligible(isOngoing = true, isMediaStyle = false, importance = NotificationManager.IMPORTANCE_HIGH, showSilent = true))
    }

    @Test fun `media-style notifications never enter the hub`() {
        assertFalse(isHubEligible(isOngoing = false, isMediaStyle = true, importance = NotificationManager.IMPORTANCE_HIGH, showSilent = true))
    }

    @Test fun `silent notifications are filtered out by default`() {
        assertFalse(isHubEligible(isOngoing = false, isMediaStyle = false, importance = NotificationManager.IMPORTANCE_LOW, showSilent = false))
        assertFalse(isHubEligible(isOngoing = false, isMediaStyle = false, importance = NotificationManager.IMPORTANCE_MIN, showSilent = false))
    }

    @Test fun `silent notifications show once the setting is on`() {
        assertTrue(isHubEligible(isOngoing = false, isMediaStyle = false, importance = NotificationManager.IMPORTANCE_LOW, showSilent = true))
    }

    @Test fun `default and higher importance always qualifies`() {
        assertTrue(isHubEligible(isOngoing = false, isMediaStyle = false, importance = NotificationManager.IMPORTANCE_DEFAULT, showSilent = false))
        assertTrue(isHubEligible(isOngoing = false, isMediaStyle = false, importance = NotificationManager.IMPORTANCE_HIGH, showSilent = false))
    }

    // --- groupHubNotifications -----------------------------------------------------------------

    @Test fun `groups one entry per package`() {
        val groups = groupHubNotifications(listOf(
            notification("a1", "com.a", 1_000L),
            notification("b1", "com.b", 2_000L),
        ))
        assertEquals(2, groups.size)
        assertEquals(setOf("com.a", "com.b"), groups.map { it.packageName }.toSet())
    }

    @Test fun `groups order newest package first`() {
        val groups = groupHubNotifications(listOf(
            notification("old", "com.old", 1_000L),
            notification("new", "com.new", 5_000L),
        ))
        assertEquals(listOf("com.new", "com.old"), groups.map { it.packageName })
    }

    @Test fun `within a group the newest notification is first, top and stack count follow`() {
        val group = groupHubNotifications(listOf(
            notification("older", "com.a", 1_000L),
            notification("newer", "com.a", 2_000L),
            notification("newest", "com.a", 3_000L),
        )).single()
        assertEquals("newest", group.top.key)
        assertEquals(listOf("newest", "newer", "older"), group.notifications.map { it.key })
        assertEquals(2, group.stackCount)
    }

    @Test fun `a lone notification has a zero stack count`() {
        val group = groupHubNotifications(listOf(notification("only", "com.a", 1_000L))).single()
        assertEquals(0, group.stackCount)
    }

    @Test fun `empty input groups to nothing`() {
        assertTrue(groupHubNotifications(emptyList()).isEmpty())
    }

    // --- hubRowsReserved -----------------------------------------------------------------------

    @Test fun `no groups reserves no rows`() {
        assertEquals(0, hubRowsReserved(0))
    }

    @Test fun `rows track group count up to the cap`() {
        assertEquals(1, hubRowsReserved(1))
        assertEquals(2, hubRowsReserved(2))
        assertEquals(HUB_MAX_ROWS, hubRowsReserved(3))
    }

    @Test fun `rows never exceed the cap however many groups there are`() {
        assertEquals(HUB_MAX_ROWS, hubRowsReserved(4))
        assertEquals(HUB_MAX_ROWS, hubRowsReserved(50))
    }

    // --- hubRowsReserved, compact pill (redesign) -----------------------------------------------

    @Test fun `collapsed reserves exactly one row however many groups are behind the pill`() {
        assertEquals(1, hubRowsReserved(1, expanded = false))
        assertEquals(1, hubRowsReserved(2, expanded = false))
        assertEquals(1, hubRowsReserved(50, expanded = false))
    }

    @Test fun `collapsed still reserves nothing with no groups`() {
        assertEquals(0, hubRowsReserved(0, expanded = false))
    }

    @Test fun `expanded matches the old unconditional row count`() {
        assertEquals(hubRowsReserved(2), hubRowsReserved(2, expanded = true))
        assertEquals(HUB_MAX_ROWS, hubRowsReserved(50, expanded = true))
    }

    // --- reduceHubPill, the compact hub's whole state machine -----------------------------------

    @Test fun `a tap expands a collapsed pill`() {
        assertEquals(HubPillState.EXPANDED, reduceHubPill(HubPillState.COLLAPSED, HubPillEvent.TAP))
    }

    @Test fun `a tap collapses an expanded pill`() {
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.EXPANDED, HubPillEvent.TAP))
    }

    @Test fun `leaving Today collapses, whatever the current state`() {
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.EXPANDED, HubPillEvent.LEAVE_TODAY))
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.COLLAPSED, HubPillEvent.LEAVE_TODAY))
    }

    @Test fun `resuming the app collapses, whatever the current state`() {
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.EXPANDED, HubPillEvent.RESUME))
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.COLLAPSED, HubPillEvent.RESUME))
    }

    @Test fun `the 20s timeout collapses, whatever the current state`() {
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.EXPANDED, HubPillEvent.TIMEOUT))
        assertEquals(HubPillState.COLLAPSED, reduceHubPill(HubPillState.COLLAPSED, HubPillEvent.TIMEOUT))
    }

    // --- hubSummaryText --------------------------------------------------------------------------

    @Test fun `english summary pluralises app and notification counts independently`() {
        assertEquals("1 app · 1 notification", hubSummaryText(1, 1, Locale.ENGLISH))
        assertEquals("3 apps · 7 notifications", hubSummaryText(3, 7, Locale.ENGLISH))
    }

    @Test fun `czech summary uses the 1 - 2-4 - 5+ noun forms`() {
        assertEquals("1 aplikace · 1 oznámení", hubSummaryText(1, 1, Locale("cs")))
        assertEquals("3 aplikace · 7 oznámení", hubSummaryText(3, 7, Locale("cs")))
        assertEquals("5 aplikací · 2 oznámení", hubSummaryText(5, 2, Locale("cs")))
    }

    // --- relativeTimeLabel -----------------------------------------------------------------------

    @Test fun `under a minute reads as now`() {
        assertEquals("now", relativeTimeLabel(whenMs = 0L, nowMs = 0L))
        assertEquals("now", relativeTimeLabel(whenMs = 0L, nowMs = 59_999L))
    }

    @Test fun `a future timestamp still reads as now, never negative`() {
        assertEquals("now", relativeTimeLabel(whenMs = 10_000L, nowMs = 0L))
    }

    @Test fun `minutes under an hour`() {
        assertEquals("1m", relativeTimeLabel(whenMs = 0L, nowMs = 60_000L))
        assertEquals("59m", relativeTimeLabel(whenMs = 0L, nowMs = 59L * 60_000L))
    }

    @Test fun `hours under a day`() {
        assertEquals("1h", relativeTimeLabel(whenMs = 0L, nowMs = 3_600_000L))
        assertEquals("23h", relativeTimeLabel(whenMs = 0L, nowMs = 23L * 3_600_000L))
    }

    @Test fun `days beyond a day`() {
        assertEquals("1d", relativeTimeLabel(whenMs = 0L, nowMs = 24L * 3_600_000L))
        assertEquals("3d", relativeTimeLabel(whenMs = 0L, nowMs = 3L * 24L * 3_600_000L))
    }
}
