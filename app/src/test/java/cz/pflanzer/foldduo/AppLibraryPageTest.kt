package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules behind the App Library tiles and the A–Z index rail. */
class AppLibraryPageTest {
    private data class Item(
        override val packageName: String,
        override val label: String,
        override val manifestCategory: Int = ManifestCategory.UNDEFINED,
        override val isSystem: Boolean = false,
        override val firstInstallTime: Long = 0L,
    ) : LibraryItem

    private fun apps(count: Int) = (1..count).map { Item("app.$it", "App $it") }

    @Test fun `five or more apps show three large icons and a cluster of four`() {
        val slots = tileSlots(LibrarySection(AppCategory.Social, apps(5)))
        assertEquals(listOf("app.1", "app.2", "app.3"), slots.large.map { it.packageName })
        assertEquals(listOf("app.4", "app.5"), slots.cluster.map { it.packageName })
        assertTrue(slots.hasCluster)
        val big = tileSlots(LibrarySection(AppCategory.Utilities, apps(20)))
        assertEquals(TILE_LARGE_ICONS, big.large.size)
        assertEquals(TILE_CLUSTER_ICONS, big.cluster.size)
        assertEquals(listOf("app.4", "app.5", "app.6", "app.7"), big.cluster.map { it.packageName })
    }

    @Test fun `fewer than five apps are all large and never a cluster`() {
        for (count in 1..4) {
            val slots = tileSlots(LibrarySection(AppCategory.Games, apps(count)))
            assertEquals(count, slots.large.size)
            assertFalse(slots.hasCluster)
        }
    }

    @Test fun `suggestions show up to four large icons and never a cluster`() {
        val slots = tileSlots(LibrarySection(AppCategory.Suggestions, apps(SUGGESTION_LIMIT)))
        assertEquals(listOf("app.1", "app.2", "app.3", "app.4"), slots.large.map { it.packageName })
        assertFalse(slots.hasCluster)
        assertEquals(2, tileSlots(LibrarySection(AppCategory.Suggestions, apps(2))).large.size)
    }

    @Test fun `recently added shows the three newest large and the rest in folder order`() {
        val day = 24L * 60 * 60 * 1000
        // Folder order is alphabetical; install recency picks the large icons.
        val folder = listOf(
            Item("a", "Alpha", firstInstallTime = 10 * day),
            Item("b", "Beta", firstInstallTime = 90 * day),
            Item("c", "Gamma", firstInstallTime = 50 * day),
            Item("d", "Delta", firstInstallTime = 80 * day),
            Item("e", "Epsilon", firstInstallTime = 20 * day),
            Item("f", "Zeta", firstInstallTime = 70 * day),
        )
        val slots = tileSlots(LibrarySection(AppCategory.RecentlyAdded, folder))
        assertEquals(listOf("b", "d", "f"), slots.large.map { it.packageName })
        assertEquals(listOf("a", "c", "e"), slots.cluster.map { it.packageName })
        // Under five apps: all large, still newest first.
        val few = tileSlots(LibrarySection(AppCategory.RecentlyAdded, folder.take(4)))
        assertEquals(listOf("b", "d", "c", "a"), few.large.map { it.packageName })
        assertFalse(few.hasCluster)
    }

    @Test fun `library sections feed the tile rules end to end`() {
        val day = 24L * 60 * 60 * 1000
        val now = 1000L * day
        // Labels run backwards so folder order (by label) differs from install order; the
        // installs are months old, since Recently Added has no age limit.
        val installed = (1..6).map { Item("new.$it", "New ${'f' - (it - 1)}", firstInstallTime = now - it * 40 * day) }
        val sections = buildLibrary(installed + apps(3), listOf("app.1", "app.2"), now)
        val suggestions = tileSlots(sections.first { it.category == AppCategory.Suggestions })
        assertEquals(2, suggestions.large.size); assertFalse(suggestions.hasCluster)
        val recent = tileSlots(sections.first { it.category == AppCategory.RecentlyAdded })
        assertEquals(listOf("new.1", "new.2", "new.3"), recent.large.map { it.packageName })
        assertEquals(listOf("new.6", "new.5", "new.4"), recent.cluster.map { it.packageName })
    }

    @Test fun `alphabet header indices count the sticky header and its rows`() {
        val groups = linkedMapOf("#" to 2, "A" to 3, "C" to 1)
        assertEquals(mapOf("#" to 0, "A" to 3, "C" to 7), alphabetHeaderIndices(groups, workPaused = false))
        assertEquals(mapOf("#" to 1, "A" to 4, "C" to 8), alphabetHeaderIndices(groups, workPaused = true))
        assertTrue(alphabetHeaderIndices(emptyMap(), workPaused = false).isEmpty())
    }

    @Test fun `rail touch position maps onto letters and clamps at the edges`() {
        assertEquals(0, railLetterIndex(0f, 260, 26))
        assertEquals(12, railLetterIndex(125f, 260, 26))
        assertEquals(25, railLetterIndex(259f, 260, 26))
        assertEquals(25, railLetterIndex(999f, 260, 26))
        assertEquals(0, railLetterIndex(-5f, 260, 26))
        assertEquals(0, railLetterIndex(50f, 0, 26))
        assertEquals(0, railLetterIndex(50f, 260, 0))
    }
}
