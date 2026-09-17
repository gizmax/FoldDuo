package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.Panels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperFramingTest {
    // Fold 8 (PLAN.md fact 1): inner 2448×1848 px landscape, cover 1248×1972 px; in dp @360:
    // inner 1088×821, cover 555×830 usable.
    private val innerW = Panels.INNER_LONG.toFloat()
    private val innerH = Panels.INNER_SHORT.toFloat()
    private val coverW = Panels.COVER_SHORT.toFloat()
    private val coverH = Panels.COVER_LONG.toFloat()
    private val eps = 0.01f

    private fun crop(imageW: Float, imageH: Float, panel: Panel) =
        WallpaperFraming.wallpaperCrop(imageW, imageH, innerW, innerH, coverW, coverH, panel)

    @Test fun `inner crop is a centre crop to the inner aspect for 4 3, 16 9 and square sources`() {
        val four3 = crop(4000f, 3000f, Panel.Inner)
        assertEquals(innerW / innerH, four3.aspect, 1e-4f)
        assertEquals(3000f, four3.height, eps)
        assertEquals(3974.03f, four3.width, eps)
        assertEquals(12.99f, four3.left, eps)
        assertEquals(0f, four3.top, 0f)
        assertEquals(2000f, four3.centerX, eps)

        val wide = crop(3840f, 2160f, Panel.Inner)
        assertEquals(innerW / innerH, wide.aspect, 1e-4f)
        assertEquals(2160f, wide.height, eps)
        assertEquals(2861.30f, wide.width, eps)
        assertEquals(489.35f, wide.left, eps)

        val square = crop(3000f, 3000f, Panel.Inner)
        assertEquals(innerW / innerH, square.aspect, 1e-4f)
        assertEquals(3000f, square.width, eps)
        assertEquals(2264.71f, square.height, eps)
        assertEquals(367.65f, square.top, eps)
        assertEquals(1500f, square.centerY, eps)
    }

    @Test fun `cover crop is the right half of the inner crop, fitted by height and centred in the half`() {
        for ((w, h) in listOf(4000f to 3000f, 3840f to 2160f, 3000f to 3000f)) {
            val inner = crop(w, h, Panel.Inner)
            val cover = crop(w, h, Panel.Cover)
            assertEquals("aspect $w×$h", coverW / coverH, cover.aspect, 1e-4f)
            // Fit by height: the whole height of the inner crop, a slice a bit narrower than the
            // half (cover px aspect 0.633 vs half 0.662): 1898.6 of the 1987 px half on 4:3.
            assertEquals("height $w×$h", inner.height, cover.height, eps)
            assertEquals("top $w×$h", inner.top, cover.top, eps)
            val halfLeft = inner.left + inner.width / 2f
            assertEquals("centred in the right half $w×$h", halfLeft + inner.width / 4f, cover.centerX, eps)
            assertTrue("inside the right half $w×$h", cover.left >= halfLeft - eps && cover.right <= inner.right + eps)
            assertEquals("cover width / half width $w×$h", (coverW / coverH) / (innerW / innerH / 2f),
                cover.width / (inner.width / 2f), 1e-3f)
        }
        val four3 = crop(4000f, 3000f, Panel.Cover)
        assertEquals(1898.58f, four3.width, eps)
        assertEquals(2044.21f, four3.left, eps)
    }

    @Test fun `in dp the cover is a touch wider than the half and overscans it by under 2 percent`() {
        // 4:3 picture in dp units; inner 1088×821, cover 555×830.
        val inner = WallpaperFraming.wallpaperCrop(1600f, 1200f, 1088f, 821f, 555f, 830f, Panel.Inner)
        val cover = WallpaperFraming.wallpaperCrop(1600f, 1200f, 1088f, 821f, 555f, 830f, Panel.Cover)
        assertEquals(555f / 830f, cover.aspect, 1e-4f)
        assertEquals(inner.height, cover.height, eps)
        val halfW = inner.width / 2f
        val overscan = (cover.width - halfW) / halfW
        assertTrue("overscan $overscan", overscan > 0f && overscan <= WallpaperFraming.COVER_OVERSCAN_MAX)
        assertEquals(0.0092f, overscan, 1e-3f)
        // The spill is split evenly: the crop stays centred on the right half.
        assertEquals(inner.left + halfW * 1.5f, cover.centerX, eps)
    }

    @Test fun `a cover much wider than the half is fitted by width and loses height instead`() {
        val inner = WallpaperFraming.innerCrop(4000f, 3000f, innerW, innerH)
        val cover = WallpaperFraming.coverCrop(inner, 800f, 1000f, 4000f, 3000f)
        assertEquals(0.8f, cover.aspect, 1e-4f)
        assertEquals(inner.width / 2f, cover.width, eps)
        assertEquals(inner.width / 2f / 0.8f, cover.height, eps)
        assertEquals(inner.centerY, cover.centerY, eps)
        assertEquals(inner.left + inner.width / 2f, cover.left, eps)
    }

    @Test fun `overscan at the image edge shifts the crop left rather than reading outside the picture`() {
        // A picture exactly the inner size in dp: the right half ends at the image edge.
        val cover = WallpaperFraming.wallpaperCrop(1088f, 821f, 1088f, 821f, 555f, 830f, Panel.Cover)
        assertEquals(1088f, cover.right, eps)
        assertEquals(0f, cover.top, 0f)
        assertEquals(555f / 830f, cover.aspect, 1e-4f)
        assertTrue(cover.left >= 0f)
    }

    @Test fun `unknown panel gets the inner crop and surfaceCrop fills in the Fold 8 partner panel`() {
        assertEquals(crop(4000f, 3000f, Panel.Inner), crop(4000f, 3000f, Panel.Unknown))
        assertEquals(crop(4000f, 3000f, Panel.Inner),
            WallpaperFraming.surfaceCrop(4000f, 3000f, innerW, innerH, Panel.Inner, coverW, coverH))
        assertEquals(crop(4000f, 3000f, Panel.Cover),
            WallpaperFraming.surfaceCrop(4000f, 3000f, coverW, coverH, Panel.Cover, innerW, innerH))
        // The dune scene: a picture the size of the inner panel; the cover is its right half.
        val dunes = WallpaperFraming.surfaceCrop(innerW, innerH, coverW, coverH, Panel.Cover, innerW, innerH)
        assertEquals(innerH, dunes.height, 0f)
        assertEquals(innerW * 0.75f, dunes.centerX, eps)
        assertTrue(dunes.left >= innerW / 2f)
    }
}
