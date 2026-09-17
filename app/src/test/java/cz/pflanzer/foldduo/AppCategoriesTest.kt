package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCategoriesTest {
    private data class Item(
        override val packageName: String,
        override val label: String,
        override val manifestCategory: Int = ManifestCategory.UNDEFINED,
        override val isSystem: Boolean = false,
        override val firstInstallTime: Long = 0L,
    ) : LibraryItem

    private val day = 24L * 60 * 60 * 1000
    private val now = 100L * day

    @Test fun `manifest category classifies apps the package table does not know`() {
        assertEquals(AppCategory.Games, categorize("x.unknown", "Puzzle", ManifestCategory.GAME, false))
        assertEquals(AppCategory.Music, categorize("x.unknown", "Player", ManifestCategory.AUDIO, false))
        assertEquals(AppCategory.Entertainment, categorize("x.unknown", "Tube", ManifestCategory.VIDEO, true))
        assertEquals(AppCategory.Entertainment, categorize("x.unknown", "Novinky", ManifestCategory.NEWS, false))
        assertEquals(AppCategory.Photos, categorize("x.unknown", "Gallery", ManifestCategory.IMAGE, true))
        assertEquals(AppCategory.Social, categorize("x.unknown", "Chat", ManifestCategory.SOCIAL, false))
        assertEquals(AppCategory.Maps, categorize("x.unknown", "Maps", ManifestCategory.MAPS, true))
        assertEquals(AppCategory.Productivity, categorize("x.unknown", "Notes", ManifestCategory.PRODUCTIVITY, false))
        assertEquals(AppCategory.Utilities, categorize("x.unknown", "Reader", ManifestCategory.ACCESSIBILITY, false))
        // The manifest beats the bank keyword, the label table and the vendor fallback.
        assertEquals(AppCategory.Games, categorize("cz.somebank.game", "Bank Game", ManifestCategory.GAME, false))
        assertEquals(AppCategory.Social, categorize("com.samsung.android.unknown", "Unknown", ManifestCategory.SOCIAL, true))
    }

    @Test fun `curated package table beats the installer category hint`() {
        // Regression: Play's "Communication" store category arrives as ApplicationInfo.category
        // == CATEGORY_SOCIAL (an installer override, not a manifest value), which put Chrome
        // and Gmail into Social. Curated rows win over that hint.
        assertEquals(AppCategory.Utilities, categorize("com.android.chrome", "Chrome", ManifestCategory.SOCIAL, true))
        assertEquals(AppCategory.Utilities, categorize("com.sec.android.app.sbrowser", "Samsung Internet", ManifestCategory.SOCIAL, true))
        assertEquals(AppCategory.Utilities, categorize("org.mozilla.firefox", "Firefox", ManifestCategory.SOCIAL, false))
        assertEquals(AppCategory.Productivity, categorize("com.google.android.gm", "Gmail", ManifestCategory.SOCIAL, true))
        assertEquals(AppCategory.Social, categorize("com.whatsapp", "WhatsApp", ManifestCategory.GAME, false))
    }

    @Test fun `folder titles follow iOS naming and keep finance separate`() {
        assertEquals("Photo & Video", AppCategory.Photos.title)
        assertEquals("Health & Fitness", AppCategory.Health.title)
        assertEquals("Travel", AppCategory.Maps.title)
        assertEquals("Shopping & Food", AppCategory.Shopping.title)
        assertEquals("Finance", AppCategory.Finance.title)
        assertEquals("Recently Added", AppCategory.RecentlyAdded.title)
        assertEquals("Suggestions", AppCategory.Suggestions.title)
    }

    @Test fun `package table covers undefined manifest categories`() {
        val undefined = ManifestCategory.UNDEFINED
        assertEquals(AppCategory.Social, categorize("com.whatsapp", "WhatsApp", undefined, false))
        assertEquals(AppCategory.Social, categorize("com.facebook.katana", "Facebook", undefined, false))
        assertEquals(AppCategory.Social, categorize("org.telegram.messenger", "Telegram", undefined, false))
        assertEquals(AppCategory.Social, categorize("com.discord", "Discord", undefined, false))
        assertEquals(AppCategory.Finance, categorize("com.revolut.revolut", "Revolut", undefined, false))
        assertEquals(AppCategory.Finance, categorize("cz.csob.smart", "ČSOB Smart", undefined, false))
        assertEquals(AppCategory.Finance, categorize("com.google.android.apps.walletnfcrel", "Wallet", undefined, true))
        assertEquals(AppCategory.Shopping, categorize("cz.alza.eshop", "Alza", undefined, false))
        assertEquals(AppCategory.Shopping, categorize("com.amazon.mShop.android.shopping", "Amazon", undefined, false))
        assertEquals(AppCategory.Productivity, categorize("com.samsung.android.app.notes", "Samsung Notes", undefined, true))
        assertEquals(AppCategory.Productivity, categorize("com.google.android.apps.docs.editors.sheets", "Sheets", undefined, false))
        assertEquals(AppCategory.Productivity, categorize("com.microsoft.office.outlook", "Outlook", undefined, false))
        assertEquals(AppCategory.Entertainment, categorize("com.netflix.mediaclient", "Netflix", undefined, false))
        assertEquals(AppCategory.Music, categorize("com.spotify.music", "Spotify", undefined, false))
        assertEquals(AppCategory.Music, categorize("com.google.android.apps.youtube.music", "YouTube Music", undefined, false))
        assertEquals(AppCategory.Photos, categorize("com.google.android.apps.photos", "Photos", undefined, true))
        assertEquals(AppCategory.Health, categorize("com.sec.android.app.shealth", "Samsung Health", undefined, true))
        assertEquals(AppCategory.Maps, categorize("com.waze", "Waze", undefined, false))
        assertEquals(AppCategory.Utilities, categorize("com.android.chrome", "Chrome", undefined, true))
    }

    @Test fun `prefix rules do not match look-alike packages`() {
        // "com.discord" matches itself and sub-packages only.
        assertEquals(AppCategory.Other, categorize("com.discordia.app", "Discordia", ManifestCategory.UNDEFINED, false))
        assertEquals(AppCategory.Social, categorize("com.discord.beta", "Discord Beta", ManifestCategory.UNDEFINED, false))
    }

    @Test fun `bank keyword in package or label means finance`() {
        assertEquals(AppCategory.Finance, categorize("cz.unknownbank.mobile", "Mobile", ManifestCategory.UNDEFINED, false))
        assertEquals(AppCategory.Finance, categorize("cz.something", "Moje banka", ManifestCategory.UNDEFINED, false))
        assertEquals(AppCategory.Finance, categorize("cz.something", "George Bank", ManifestCategory.UNDEFINED, false))
    }

    @Test fun `vendor system apps fall back to utilities and the rest to other`() {
        assertEquals(AppCategory.Utilities, categorize("com.sec.android.app.clockpackage", "Clock", ManifestCategory.UNDEFINED, true))
        assertEquals(AppCategory.Utilities, categorize("com.samsung.android.calculator", "Calculator", ManifestCategory.UNDEFINED, true))
        assertEquals(AppCategory.Utilities, categorize("com.google.android.apps.wellbeing", "Digital Wellbeing", ManifestCategory.UNDEFINED, true))
        // A user-installed Google app without a rule is not a "system utility".
        assertEquals(AppCategory.Other, categorize("com.google.android.apps.unknown", "Unknown", ManifestCategory.UNDEFINED, false))
        assertEquals(AppCategory.Other, categorize("cz.pflanzer.foldminders", "Foldminders", ManifestCategory.UNDEFINED, false))
        // A non-vendor system app is not blindly a utility either.
        assertEquals(AppCategory.Other, categorize("com.vendor.preload", "Preload", ManifestCategory.UNDEFINED, true))
    }

    @Test fun `library sections omit empty categories and sort apps by label`() {
        val apps = listOf(
            Item("com.whatsapp", "WhatsApp"),
            Item("com.discord", "Discord"),
            Item("cz.alza.eshop", "alza"),
            Item("com.other.one", "Zeta"),
            Item("com.other.two", "alpha"),
        )
        val sections = buildLibrary(apps, emptyList(), now)
        assertEquals(listOf(AppCategory.Social, AppCategory.Shopping, AppCategory.Other), sections.map { it.category })
        assertEquals(listOf("Discord", "WhatsApp"), sections[0].apps.map { it.label })
        assertEquals(listOf("alpha", "Zeta"), sections[2].apps.map { it.label })
        assertFalse(sections.any { it.apps.isEmpty() })
    }

    @Test fun `suggestions follow launch order, cap at eight and ignore uninstalled packages`() {
        val apps = (1..12).map { Item("app.$it", "App $it") }
        val recent = listOf("app.5", "app.1", "gone.pkg", "app.5", "app.9", "app.2", "app.3", "app.4", "app.6", "app.7", "app.8")
        val sections = buildLibrary(apps, recent, now)
        assertEquals(AppCategory.Suggestions, sections.first().category)
        assertEquals(listOf("app.5", "app.1", "app.9", "app.2", "app.3", "app.4", "app.6", "app.7"),
            sections.first().apps.map { it.packageName })
        assertEquals(SUGGESTION_LIMIT, sections.first().apps.size)
        // Suggested apps still appear in their own category.
        assertTrue(sections.last { it.category == AppCategory.Other }.apps.any { it.packageName == "app.5" })
    }

    @Test fun `recently added takes installs of any age sorted by label and precedes categories`() {
        // iOS keeps the last installs however old they are; only a missing or future-dated
        // install time leaves an app out.
        val apps = listOf(
            Item("com.whatsapp", "WhatsApp", firstInstallTime = now - 20 * day),
            Item("new.a", "New A", firstInstallTime = now - 13 * day),
            Item("new.b", "New B", firstInstallTime = now - 1 * day),
            Item("ancient", "Ancient", firstInstallTime = day),
            Item("old", "Old", firstInstallTime = now - 15 * day),
            Item("just.now", "Just now", firstInstallTime = now),
            Item("unknown", "Unknown", firstInstallTime = 0L),
            Item("future", "Future", firstInstallTime = now + day),
        )
        val sections = buildLibrary(apps, listOf("old"), now)
        assertEquals(listOf(AppCategory.Suggestions, AppCategory.RecentlyAdded, AppCategory.Social, AppCategory.Other),
            sections.map { it.category })
        assertEquals(listOf("ancient", "just.now", "new.a", "new.b", "old", "com.whatsapp"), sections[1].apps.map { it.packageName })
    }

    @Test fun `recently added keeps only the eight newest installs regardless of age`() {
        // Installed 30..360 days ago: none would have passed a fourteen-day window.
        val now = 1000L * day
        val apps = (1..12).map { Item("app.$it", "App ${'a' + (it - 1)}", firstInstallTime = now - it * 30 * day) }
        val recent = buildLibrary(apps, emptyList(), now).first { it.category == AppCategory.RecentlyAdded }.apps
        assertEquals(RECENTLY_ADDED_LIMIT, recent.size)
        assertEquals(8, RECENTLY_ADDED_LIMIT)
        // The eight newest (30..240 days old), then in label order.
        assertEquals((1..8).map { "app.$it" }, recent.map { it.packageName })
        assertFalse(recent.any { it.packageName == "app.9" })
        // The tile shows the three newest of those large, newest first.
        val slots = tileSlots(LibrarySection(AppCategory.RecentlyAdded, recent))
        assertEquals(listOf("app.1", "app.2", "app.3"), slots.large.map { it.packageName })
    }

    @Test fun `recently added is omitted only when no app has an install time`() {
        val sections = buildLibrary(listOf(Item("com.whatsapp", "WhatsApp"), Item("x", "X", firstInstallTime = 0L)), emptyList(), now)
        assertEquals(listOf(AppCategory.Social, AppCategory.Other), sections.map { it.category })
        val dated = buildLibrary(listOf(Item("com.whatsapp", "WhatsApp", firstInstallTime = day)), emptyList(), now)
        assertEquals(listOf(AppCategory.RecentlyAdded, AppCategory.Social), dated.map { it.category })
    }

    @Test fun `no history and no fresh installs yields plain categories`() {
        val sections = buildLibrary(listOf(Item("com.whatsapp", "WhatsApp")), emptyList(), now)
        assertEquals(listOf(AppCategory.Social), sections.map { it.category })
    }
}
