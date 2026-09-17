package cz.pflanzer.foldduo

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for HostWidgetSkin.kt: candidate selection ([findHostSkinCandidates]),
 * the dark/light read of a sampled colour ([isDarkHostBackground]), and the strip/restore state
 * machine ([SkinTracker]) exercised against a fake view — no android.view.View, so these run as
 * plain JVM unit tests.
 */
class HostWidgetSkinTest {
    private fun node(width: Float, height: Float, hasBackground: Boolean, isImageContent: Boolean = false,
        children: List<SkinNode> = emptyList()) =
        SkinNode(Rect(0f, 0f, width, height), hasBackground, isImageContent, children)

    // --- candidate selection by coverage --------------------------------------------------

    @Test fun `a view covering the whole host and carrying a background is a candidate`() {
        val root = node(100f, 100f, hasBackground = true)
        assertEquals(listOf(root), findHostSkinCandidates(root, hostArea = 100f * 100f))
    }

    @Test fun `a view below the coverage threshold is not a candidate`() {
        // 50x50 of a 100x100 host is 25% coverage, well under the 85% cut.
        val root = node(50f, 50f, hasBackground = true)
        assertTrue(findHostSkinCandidates(root, hostArea = 100f * 100f).isEmpty())
        // 92x92 of 100x100 is 84.6%, just under threshold.
        val justUnder = node(92f, 92f, hasBackground = true)
        assertTrue(findHostSkinCandidates(justUnder, hostArea = 100f * 100f).isEmpty())
        // 93x93 of 100x100 is 86.5%, over threshold.
        val justOver = node(93f, 93f, hasBackground = true)
        assertEquals(listOf(justOver), findHostSkinCandidates(justOver, hostArea = 100f * 100f))
    }

    @Test fun `a view without a background is never a candidate however large`() {
        val root = node(100f, 100f, hasBackground = false)
        assertTrue(findHostSkinCandidates(root, hostArea = 100f * 100f).isEmpty())
    }

    // --- root-only when nested children are small ------------------------------------------

    @Test fun `only the full-bleed card is returned when its children are too small to qualify`() {
        // Typical Samsung layout: a transparent RemoteViews root, a full-size card child with the
        // opaque background, and small content children (icon, labels) nested in the card.
        val icon = node(24f, 24f, hasBackground = true)          // tiny, well under threshold
        val label = node(80f, 20f, hasBackground = false)
        val card = node(100f, 100f, hasBackground = true, children = listOf(icon, label))
        val root = node(100f, 100f, hasBackground = false, children = listOf(card))
        assertEquals(listOf(card), findHostSkinCandidates(root, hostArea = 100f * 100f))
    }

    @Test fun `both root and a full-bleed nested card qualify when both cover enough`() {
        val innerCard = node(100f, 100f, hasBackground = true)
        val root = node(100f, 100f, hasBackground = true, children = listOf(innerCard))
        assertEquals(listOf(root, innerCard), findHostSkinCandidates(root, hostArea = 100f * 100f))
    }

    @Test fun `image content is skipped even at full coverage`() {
        val photo = node(100f, 100f, hasBackground = true, isImageContent = true)
        assertTrue(findHostSkinCandidates(photo, hostArea = 100f * 100f).isEmpty())
    }

    @Test fun `zero host area yields no candidates instead of dividing by zero`() {
        val root = node(100f, 100f, hasBackground = true)
        assertTrue(findHostSkinCandidates(root, hostArea = 0f).isEmpty())
    }

    // --- dark/light decision from a colour --------------------------------------------------

    @Test fun `black and dark greys are a dark host background`() {
        assertTrue(isDarkHostBackground(0xFF000000.toInt()))
        assertTrue(isDarkHostBackground(0xFF1C1C1E.toInt()))
    }

    @Test fun `white and pale colours are not a dark host background`() {
        assertFalse(isDarkHostBackground(0xFFFFFFFF.toInt()))
        assertFalse(isDarkHostBackground(0xFFE0E0E0.toInt()))
    }

    // --- strip/restore state machine (fake handle, no android.view.View) -------------------

    /** Minimal background-owning handle for [SkinTracker], standing in for a real View. */
    private class FakeView(var background: String?, var tint: String? = null)

    private fun tracker() = SkinTracker<FakeView, String, String>(
        getBackground = { it.background },
        setBackground = { v, b -> v.background = b },
        getTint = { it.tint },
        setTint = { v, t -> v.tint = t },
    )

    @Test fun `strip nulls the background and remembers the original`() {
        val view = FakeView(background = "opaque-card", tint = "brand")
        val t = tracker()
        t.strip(view)
        assertEquals(null, view.background)
        assertEquals(null, view.tint)
        assertEquals("opaque-card", t.originalOf(view))
        assertTrue(t.isTracked(view))
    }

    @Test fun `restore on Default puts the original back and forgets the view`() {
        val view = FakeView(background = "opaque-card")
        val t = tracker()
        t.strip(view)
        t.restoreAll()
        assertEquals("opaque-card", view.background)
        assertFalse(t.isTracked(view))
        assertEquals(0, t.trackedCount)
    }

    @Test fun `idempotent re-apply never overwrites the remembered original with null`() {
        val view = FakeView(background = "opaque-card")
        val t = tracker()
        t.strip(view)               // first strip: remembers "opaque-card"
        t.strip(view)                // re-apply while already stripped (background is now null)
        t.strip(view)                // and again, e.g. a relayout pass re-confirming the candidate
        assertEquals("opaque-card", t.originalOf(view))
        assertEquals(null, view.background)
        assertEquals(1, t.trackedCount)
        t.restoreAll()
        assertEquals("opaque-card", view.background)
    }

    @Test fun `restore with nothing tracked is a no-op`() {
        val t = tracker()
        t.restoreAll()
        assertEquals(0, t.trackedCount)
    }
}
