package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderOpenMotionTest {
    private val icon = FoldRect(100f, 200f, 64f, 64f)
    private val card = FoldRect(40f, 120f, 360f, 520f)

    @Test fun `progress 0 shrinks and re-centres the card exactly onto the icon`() {
        val frame = folderOpenFrame(0f, icon, card)
        assertEquals(icon.width / card.width, frame.scaleX, 1e-6f)
        assertEquals(icon.height / card.height, frame.scaleY, 1e-6f)
        // The card, laid out with its own centre, is scaled down (unchanged centre) then
        // translated: the result must land its centre exactly on the icon's centre.
        assertEquals(icon.centerX, card.centerX + frame.translateX, 1e-4f)
        assertEquals(icon.centerY, card.centerY + frame.translateY, 1e-4f)
    }

    @Test fun `progress 1 is the identity transform, card stays exactly where it was laid out`() {
        val frame = folderOpenFrame(1f, icon, card)
        assertEquals(1f, frame.scaleX, 1e-6f)
        assertEquals(1f, frame.scaleY, 1e-6f)
        assertEquals(0f, frame.translateX, 1e-6f)
        assertEquals(0f, frame.translateY, 1e-6f)
    }

    @Test fun `progress is clamped outside 0 to 1`() {
        assertEquals(folderOpenFrame(0f, icon, card), folderOpenFrame(-5f, icon, card))
        assertEquals(folderOpenFrame(1f, icon, card), folderOpenFrame(5f, icon, card))
    }

    @Test fun `a card with no measured size yet never divides by zero`() {
        val unmeasured = FoldRect(0f, 0f, 0f, 0f)
        val frame = folderOpenFrame(.5f, icon, unmeasured)
        assertEquals(1f, frame.scaleX, 1e-6f)
        assertEquals(1f, frame.scaleY, 1e-6f)
        assertEquals(0f, frame.translateX, 1e-6f)
        assertEquals(0f, frame.translateY, 1e-6f)
    }

    @Test fun `icon preview fades out over the first 35 percent, never negative`() {
        assertEquals(1f, folderOpenFrame(0f, icon, card).iconPreviewAlpha, 1e-6f)
        assertEquals(0f, folderOpenFrame(FOLDER_ICON_FADE_END, icon, card).iconPreviewAlpha, 1e-6f)
        assertEquals(0f, folderOpenFrame(.9f, icon, card).iconPreviewAlpha, 1e-6f)
        assertTrue(folderOpenFrame(.1f, icon, card).iconPreviewAlpha in 0f..1f)
    }

    @Test fun `content fades in only during the last 40 percent`() {
        assertEquals(0f, folderOpenFrame(.59f, icon, card).contentAlpha, 1e-6f)
        assertEquals(0f, folderOpenFrame(FOLDER_CONTENT_FADE_START, icon, card).contentAlpha, 1e-6f)
        assertEquals(1f, folderOpenFrame(1f, icon, card).contentAlpha, 1e-6f)
        assertEquals(.5f, folderOpenFrame(.8f, icon, card).contentAlpha, 1e-4f)
    }

    @Test fun `frost and dim ramp linearly with progress, dim capped at 15 percent`() {
        val frame = folderOpenFrame(.5f, icon, card)
        assertEquals(.5f, frame.frostAlpha, 1e-6f)
        assertEquals(.5f * FOLDER_MAX_DIM, frame.dimAlpha, 1e-6f)
        assertEquals(FOLDER_MAX_DIM, folderOpenFrame(1f, icon, card).dimAlpha, 1e-6f)
    }

    @Test fun `fallback frame has no icon preview and the same content fade window`() {
        val start = folderOpenFrameFallback(0f)
        assertEquals(.92f, start.scaleX, 1e-6f)
        assertEquals(0f, start.iconPreviewAlpha, 1e-6f)
        assertEquals(0f, start.translateX, 1e-6f)
        val end = folderOpenFrameFallback(1f)
        assertEquals(1f, end.scaleX, 1e-6f)
        assertEquals(1f, end.contentAlpha, 1e-6f)
    }

    @Test fun `page count is one for empty or small folders, pages of 9`() {
        assertEquals(1, folderPageCount(0))
        assertEquals(1, folderPageCount(1))
        assertEquals(1, folderPageCount(9))
        assertEquals(2, folderPageCount(10))
        assertEquals(2, folderPageCount(18))
        assertEquals(3, folderPageCount(19))
    }

    @Test fun `page count respects a custom page size`() {
        assertEquals(2, folderPageCount(7, perPage = 4))
        assertEquals(1, folderPageCount(4, perPage = 4))
    }

    @Test fun `folder card stays in the pane holding the icon, never crossing the seam`() {
        val seam = FoldSeam(xDp = 544f)
        val leftIcon = FoldRect(100f, 200f, 64f, 64f)
        val rightIcon = FoldRect(700f, 200f, 64f, 64f)
        val leftBounds = folderCardPaneBounds(leftIcon, seam, windowWidth = 1088f, railWidth = 90f)
        val rightBounds = folderCardPaneBounds(rightIcon, seam, windowWidth = 1088f, railWidth = 90f)
        assertTrue(leftBounds.endInclusive <= seam.leadingEndDp)
        assertTrue(rightBounds.start >= seam.homeStartDp)
        assertEquals(1088f - 90f, rightBounds.endInclusive, 1e-6f)
    }

    @Test fun `folder card spans the whole window minus the rail on the cover, no seam`() {
        val icon = FoldRect(10f, 10f, 64f, 64f)
        val bounds = folderCardPaneBounds(icon, seam = null, windowWidth = 555f, railWidth = 90f)
        assertEquals(0f, bounds.start, 1e-6f)
        assertEquals(465f, bounds.endInclusive, 1e-6f)
    }
}
