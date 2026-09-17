package cz.pflanzer.foldduo

import android.app.NotificationManager
import cz.pflanzer.foldduo.notifications.BADGE_CAP
import cz.pflanzer.foldduo.notifications.BadgeCandidate
import cz.pflanzer.foldduo.notifications.badgeLabel
import cz.pflanzer.foldduo.notifications.countBadgesByPackage
import cz.pflanzer.foldduo.notifications.folderBadgeCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App icon badges (Badges.kt): per-package counting rules, the "99+" cap, and a folder's summed badge. */
class BadgesTest {
    private fun candidate(pkg: String, isOngoing: Boolean = false, isMediaStyle: Boolean = false,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT) = BadgeCandidate(pkg, isOngoing, isMediaStyle, importance)

    // --- countBadgesByPackage ------------------------------------------------------------------

    @Test fun `counts one badge per eligible notification, grouped by package`() {
        val counts = countBadgesByPackage(listOf(
            candidate("com.a"), candidate("com.a"), candidate("com.b"),
        ), showSilent = false)
        assertEquals(2, counts["com.a"])
        assertEquals(1, counts["com.b"])
        assertEquals(2, counts.size)
    }

    @Test fun `ongoing notifications never add to a badge`() {
        val counts = countBadgesByPackage(listOf(candidate("com.a", isOngoing = true)), showSilent = true)
        assertTrue(counts.isEmpty())
    }

    @Test fun `media-style notifications never add to a badge`() {
        val counts = countBadgesByPackage(listOf(candidate("com.a", isMediaStyle = true)), showSilent = true)
        assertTrue(counts.isEmpty())
    }

    @Test fun `silent notifications are excluded unless the setting is on`() {
        val silent = candidate("com.a", importance = NotificationManager.IMPORTANCE_LOW)
        assertTrue(countBadgesByPackage(listOf(silent), showSilent = false).isEmpty())
        assertEquals(1, countBadgesByPackage(listOf(silent), showSilent = true)["com.a"])
    }

    @Test fun `no candidates counts to nothing`() {
        assertTrue(countBadgesByPackage(emptyList(), showSilent = true).isEmpty())
    }

    @Test fun `mixed eligible and ineligible only counts the eligible ones`() {
        val counts = countBadgesByPackage(listOf(
            candidate("com.a"),
            candidate("com.a", isOngoing = true),
            candidate("com.a", isMediaStyle = true),
            candidate("com.a", importance = NotificationManager.IMPORTANCE_MIN),
        ), showSilent = false)
        assertEquals(1, counts["com.a"])
    }

    // --- badgeLabel / BADGE_CAP ------------------------------------------------------------------

    @Test fun `plain counts render as-is under the cap`() {
        assertEquals("1", badgeLabel(1))
        assertEquals("42", badgeLabel(42))
        assertEquals(BADGE_CAP.toString(), badgeLabel(BADGE_CAP))
    }

    @Test fun `counts past the cap render as 99+`() {
        assertEquals("99+", badgeLabel(BADGE_CAP + 1))
        assertEquals("99+", badgeLabel(500))
    }

    // --- folderBadgeCount ------------------------------------------------------------------------

    @Test fun `a folder badge sums every member's own count`() {
        val counts = mapOf("com.a" to 3, "com.b" to 2, "com.c" to 0)
        assertEquals(5, folderBadgeCount(listOf("com.a", "com.b", "com.c"), counts))
    }

    @Test fun `members with no badge contribute nothing, not a crash`() {
        assertEquals(0, folderBadgeCount(listOf("com.unknown"), emptyMap()))
        assertEquals(3, folderBadgeCount(listOf("com.a", "com.unknown"), mapOf("com.a" to 3)))
    }

    @Test fun `an empty folder has no badge`() {
        assertEquals(0, folderBadgeCount(emptyList(), mapOf("com.a" to 5)))
    }
}
