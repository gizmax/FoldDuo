package cz.pflanzer.foldduo.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rail's "Status card" (2026-09-17 noc, reshaped into a sideways glass PILL per Tom's device
 * feedback) motion rules (status/StatusCardMotion.kt): the pill rect grown from the measured ring
 * rect + pane bounds (cover/inner/seam), the shell rect mid-morph (height/corner frozen at the
 * ring's own), and the staggered per-item slide-in progress for any number of items. */
class StatusCardMotionTest {

    // --- Pill rect: height == ring height, grows left, pinned right edge, seam/pane clamp -----

    @Test fun `pill rect height equals the ring's own measured height exactly`() {
        val anchor = StatusCardAnchor(ringTopDp = 40f, ringLeftDp = 460f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 100f, paneWidthDp = 444f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 260f)
        assertEquals(anchor.ringHeightDp, pill.heightDp, 1e-4f)
        assertEquals(40f, pill.topDp, 1e-4f)
    }

    @Test fun `pill rect corner is always half its (frozen) height`() {
        val anchor = StatusCardAnchor(ringTopDp = 0f, ringLeftDp = 200f, ringWidthDp = 52f, ringHeightDp = 52f,
            paneStartDp = 0f, paneWidthDp = 400f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 300f)
        assertEquals(26f, pill.cornerDp, 1e-4f)
    }

    @Test fun `pill rect right edge is pinned at the ring's own right edge, growing left by content`() {
        val anchor = StatusCardAnchor(ringTopDp = 16f, ringLeftDp = 460f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 100f, paneWidthDp = 444f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 260f)
        assertEquals(504f, pill.rightDp, 1e-4f) // ringLeftDp + ringWidthDp
        assertEquals(260f, pill.widthDp, 1e-4f)
        assertEquals(504f - 260f, pill.leftDp, 1e-4f)
    }

    @Test fun `pill rect on the inner is capped at pane width minus 32dp, never crossing the seam`() {
        val anchor = StatusCardAnchor(ringTopDp = 16f, ringLeftDp = 540f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 100f, paneWidthDp = 484f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 5000f) // absurdly wide content
        assertEquals(484f - 32f, pill.widthDp, 1e-4f)
        assertTrue(pill.leftDp >= anchor.paneStartDp - 1e-4f)
    }

    @Test fun `pill rect on the cover has no extra cap beyond the screen-edge margins`() {
        val anchor = StatusCardAnchor(ringTopDp = 16f, ringLeftDp = 511f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 0f, paneWidthDp = 555f, isCover = true)
        val pill = statusPillRect(anchor, contentWidthDp = 5000f)
        assertEquals(555f - 32f, pill.widthDp, 1e-4f)
        assertEquals(32f, pill.leftDp, 1e-4f) // 2 * STATUS_CARD_MARGIN_DP from the screen edge
    }

    @Test fun `pill rect never puts the left edge past the seam even for a too-narrow pane`() {
        val cramped = StatusCardAnchor(ringTopDp = 16f, ringLeftDp = 505f, ringWidthDp = 40f, ringHeightDp = 40f,
            paneStartDp = 500f, paneWidthDp = 10f, isCover = false)
        val pill = statusPillRect(cramped, contentWidthDp = 300f)
        assertEquals(0f, pill.widthDp, 1e-4f)
        assertEquals(545f, pill.leftDp, 1e-4f) // collapses onto the (still seam-safe) right edge
        assertTrue(pill.leftDp >= cramped.paneStartDp)
    }

    @Test fun `statusPillWidthDp never goes negative on an impossibly narrow pane`() {
        assertEquals(0f, statusPillWidthDp(300f, 10f), 1e-4f)
    }

    // --- Shell rect mid-morph: height/corner frozen, right edge pinned, only left/width travel --

    @Test fun `shell rect at progress 0 equals the closed ring exactly`() {
        val anchor = StatusCardAnchor(ringTopDp = 40f, ringLeftDp = 460f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 100f, paneWidthDp = 444f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 260f)
        val shell = statusPillShellRect(anchor, pill, 0f)
        assertEquals(anchor.ringLeftDp, shell.leftDp, 1e-4f)
        assertEquals(anchor.ringWidthDp, shell.widthDp, 1e-4f)
        assertEquals(anchor.ringHeightDp, shell.heightDp, 1e-4f)
    }

    @Test fun `shell rect at progress 1 equals the fully open pill exactly`() {
        val anchor = StatusCardAnchor(ringTopDp = 40f, ringLeftDp = 460f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 100f, paneWidthDp = 444f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 260f)
        val shell = statusPillShellRect(anchor, pill, 1f)
        assertEquals(pill.leftDp, shell.leftDp, 1e-4f)
        assertEquals(pill.widthDp, shell.widthDp, 1e-4f)
    }

    @Test fun `shell rect keeps the right edge pinned and height frozen at every progress`() {
        val anchor = StatusCardAnchor(ringTopDp = 40f, ringLeftDp = 460f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 100f, paneWidthDp = 444f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 260f)
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val shell = statusPillShellRect(anchor, pill, progress)
            assertEquals(pill.rightDp, shell.rightDp, 1e-3f)
            assertEquals(anchor.ringHeightDp, shell.heightDp, 1e-4f)
            assertEquals(anchor.ringTopDp, shell.topDp, 1e-4f)
        }
    }

    @Test fun `shell rect width grows monotonically with progress`() {
        val anchor = StatusCardAnchor(ringTopDp = 0f, ringLeftDp = 460f, ringWidthDp = 44f, ringHeightDp = 44f,
            paneStartDp = 0f, paneWidthDp = 600f, isCover = false)
        val pill = statusPillRect(anchor, contentWidthDp = 260f)
        var previous = -1f
        for (progress in listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)) {
            val width = statusPillShellRect(anchor, pill, progress).widthDp
            assertTrue(width >= previous)
            previous = width
        }
    }

    // --- Content width by item -----------------------------------------------------------------

    @Test fun `content width is zero for no items`() {
        assertEquals(0f, statusPillContentWidthDp(emptyList()), 1e-4f)
    }

    @Test fun `content width grows with more or longer item texts`() {
        val short = statusPillContentWidthDp(listOf("Wi-Fi"))
        val longer = statusPillContentWidthDp(listOf("A very long SSID name here"))
        assertTrue(longer > short)
        val one = statusPillContentWidthDp(listOf("Wi-Fi"))
        val four = statusPillContentWidthDp(listOf("Wi-Fi", "Mobile", "82%", "Bluetooth"))
        assertTrue(four > one)
    }

    @Test fun `item width estimate is clamped between its min and max`() {
        assertEquals(64f, statusPillItemWidthDp("", minDp = 64f, maxDp = 132f), 1e-4f)
        assertEquals(132f, statusPillItemWidthDp("a".repeat(50), minDp = 64f, maxDp = 132f), 1e-4f)
    }

    // --- Item order + staggered progress, generalised over N items ----------------------------

    @Test fun `canonical item order is Wi-Fi, Mobile, Battery, Bluetooth`() {
        assertEquals(listOf(StatusPillItem.WIFI, StatusPillItem.MOBILE, StatusPillItem.BATTERY, StatusPillItem.BLUETOOTH),
            STATUS_PILL_ITEM_ORDER)
    }

    @Test fun `earlier items in a 4-item stagger are always further along at the same instant`() {
        val total = 400L
        val globalMid = 0.5f
        val wifi = statusPillItemProgress(0, 4, globalMid, total)
        val mobile = statusPillItemProgress(1, 4, globalMid, total)
        val battery = statusPillItemProgress(2, 4, globalMid, total)
        val bluetooth = statusPillItemProgress(3, 4, globalMid, total)
        assertTrue(wifi > mobile)
        assertTrue(mobile > battery)
        assertTrue(battery > bluetooth)
    }

    @Test fun `stagger progress generalises to any item count, not just 4`() {
        val total = 400L
        for (itemCount in listOf(1, 2, 3, 6, 10)) {
            var previous = Float.MAX_VALUE
            for (index in 0 until itemCount) {
                val p = statusPillItemProgress(index, itemCount, 0.5f, total)
                assertTrue(p <= previous + 1e-4f)
                previous = p
            }
        }
    }

    @Test fun `every item reaches exactly 1 when the global morph finishes, any item count`() {
        val total = 400L
        for (itemCount in listOf(1, 4, 8)) {
            for (index in 0 until itemCount) {
                assertEquals(1f, statusPillItemProgress(index, itemCount, 1f, total), 1e-4f)
            }
        }
    }

    @Test fun `every item is 0 at the very start of the morph`() {
        val total = 400L
        for (index in 0..3) assertEquals(0f, statusPillItemProgress(index, 4, 0f, total), 1e-4f)
    }

    @Test fun `enum overload matches the raw index overload via STATUS_PILL_ITEM_ORDER`() {
        val total = 400L
        for (item in StatusPillItem.entries) {
            val viaEnum = statusPillItemProgress(item, 0.5f, total)
            val viaIndex = statusPillItemProgress(STATUS_PILL_ITEM_ORDER.indexOf(item), STATUS_PILL_ITEM_ORDER.size, 0.5f, total)
            assertEquals(viaIndex, viaEnum, 1e-6f)
        }
    }

    @Test fun `a zero or negative total duration falls back to the raw global progress, clamped`() {
        assertEquals(0.5f, statusPillItemProgress(3, 4, 0.5f, 0L), 1e-4f)
        assertEquals(1f, statusPillItemProgress(3, 4, 1.4f, -10L), 1e-4f)
    }

    @Test fun `zero item count falls back to the raw global progress instead of dividing by zero`() {
        assertEquals(0.5f, statusPillItemProgress(0, 0, 0.5f, 400L), 1e-4f)
    }

    @Test fun `stagger longer than the total duration still clamps to a sane non-negative local duration`() {
        // Last of 4 items (index 3) delayed 3*100 = 300ms against a 200ms total: local duration
        // would be negative without the coerceAtLeast(1L) — this must not throw or go out of [0,1].
        val progress = statusPillItemProgress(3, 4, 0.5f, totalDurationMs = 200L, staggerMs = 100L)
        assertTrue(progress in 0f..1f)
    }

    @Test fun `an out-of-range index clamps into 0 until itemCount`() {
        val total = 400L
        assertEquals(statusPillItemProgress(0, 4, 0.5f, total), statusPillItemProgress(-1, 4, 0.5f, total), 1e-4f)
        assertEquals(statusPillItemProgress(3, 4, 0.5f, total), statusPillItemProgress(9, 4, 0.5f, total), 1e-4f)
    }

    // --- Slide-in translation -------------------------------------------------------------------

    @Test fun `item translation starts at the full travel distance and eases to zero`() {
        assertEquals(16f, statusPillItemTranslationDp(0f, 16f), 1e-4f)
        assertEquals(0f, statusPillItemTranslationDp(1f, 16f), 1e-4f)
        assertEquals(8f, statusPillItemTranslationDp(0.5f, 16f), 1e-4f)
    }

    @Test fun `item translation clamps progress outside 0 to 1`() {
        assertEquals(16f, statusPillItemTranslationDp(-1f, 16f), 1e-4f)
        assertEquals(0f, statusPillItemTranslationDp(2f, 16f), 1e-4f)
    }

    // --- Signal level -> bars ---------------------------------------------------------------------

    @Test fun `mobileSignalBars mirrors the raw level, clamped 0 to 4, and treats unknown as 0`() {
        assertEquals(0, mobileSignalBars(null))
        assertEquals(0, mobileSignalBars(-3))
        assertEquals(2, mobileSignalBars(2))
        assertEquals(4, mobileSignalBars(4))
        assertEquals(4, mobileSignalBars(9))
    }
}
