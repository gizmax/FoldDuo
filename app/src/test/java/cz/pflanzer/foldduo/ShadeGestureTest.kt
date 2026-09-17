package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Downward swipe → shade panel (PLAN.md, rail item 5). Over the rail column the rail's own
 * vertical thirds decide (top third Quick Settings, the rest Notifications); anywhere else the
 * historical 70/30 horizontal split stays. Sizes are the Fold 8 cover (555 x 876 dp, rail 96 dp)
 * and inner (1088 x 821 dp) windows, in dp for readability.
 */
class ShadeGestureTest {
    private val rail = railWidthDp(LayoutPreset().dockWidth) // 96 dp

    @Test fun `top third of the rail opens Quick Settings and the lower two thirds Notifications`() {
        val width = 555f; val height = 876f
        val x = width - rail / 2f
        assertEquals(ShadePanel.QUICK_SETTINGS, shadePanelFor(x, 0f, width, height, rail))
        assertEquals(ShadePanel.QUICK_SETTINGS, shadePanelFor(x, height / 3f - .01f, width, height, rail))
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(x, height / 3f, width, height, rail))
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(x, height / 2f, width, height, rail))
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(x, height - 1f, width, height, rail))
    }

    @Test fun `the rail column is exactly the right railWidth of the gesture box`() {
        val width = 555f; val height = 876f
        val y = height * .8f // Notifications over the rail; Quick Settings by the 70/30 rule outside it.
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(width - rail, y, width, height, rail))
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(width - 1f, y, width, height, rail))
        assertEquals(ShadePanel.QUICK_SETTINGS, shadePanelFor(width - rail - .01f, y, width, height, rail))
        val top = 10f // Quick Settings over the rail; Notifications by the 70/30 rule left of 70 %.
        assertEquals(ShadePanel.QUICK_SETTINGS, shadePanelFor(width - rail, top, width, height, rail))
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(width * .5f, top, width, height, rail))
    }

    @Test fun `swipes starting over the content keep the 70 30 rule on both panels`() {
        for ((width, height) in listOf(555f to 876f, 1088f to 821f)) {
            for (y in listOf(0f, height / 3f, height - 1f)) {
                assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(0f, y, width, height, rail))
                assertEquals(ShadePanel.NOTIFICATIONS, shadePanelFor(width * .7f - 1f, y, width, height, rail))
                // 70 % of the inner window (761 dp) lies well left of the rail (992 dp): the
                // horizontal rule alone opens Quick Settings there.
                assertEquals(ShadePanel.QUICK_SETTINGS, shadePanelFor(width * .7f, y, width, height, rail))
                assertEquals(shadePanelForStart(width * .7f, width), shadePanelFor(width * .7f, y, width, height, rail))
            }
        }
    }

    @Test fun `a zero-width rail never captures and reproduces the old rule`() {
        val width = 555f; val height = 876f
        for (x in listOf(0f, 200f, width * .7f - 1f, width * .7f, width - 1f)) for (y in listOf(0f, height / 2f, height - 1f)) {
            assertEquals(shadePanelForStart(x, width), shadePanelFor(x, y, width, height, 0f))
        }
        assertEquals(ShadePanel.NOTIFICATIONS, shadePanelForStart(69.999f, 100f))
        assertEquals(ShadePanel.QUICK_SETTINGS, shadePanelForStart(70f, 100f))
    }
}
