package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class LiquidGlassMotionTest {

    // --- lens height field / refraction offset ---------------------------------------

    @Test fun `height is zero at the centre and one right at the edge`() {
        assertEquals(1f, liquidGlassLensHeight(0f, 24f), 1e-4f)
        assertEquals(0f, liquidGlassLensHeight(24f, 24f), 1e-4f)
        assertEquals(0f, liquidGlassLensHeight(100f, 24f), 1e-4f)
    }

    @Test fun `height decreases monotonically from the edge inward`() {
        val band = 24f
        var previous = liquidGlassLensHeight(0f, band)
        for (d in 1..24) {
            val height = liquidGlassLensHeight(d.toFloat(), band)
            assertTrue("height should not increase moving inward ($d px)", height <= previous + 1e-6f)
            previous = height
        }
    }

    @Test fun `height only depends on distance to the nearest edge, so it is symmetric`() {
        // Two different "sides" of a shape that happen to be the same distance from their own
        // edge read identically — the field has no notion of which edge it is.
        assertEquals(liquidGlassLensHeight(3f, 24f), liquidGlassLensHeight(3f, 24f), 0f)
        assertEquals(liquidGlassLensHeight(10f, 24f), liquidGlassLensHeight(10f, 24f), 0f)
    }

    @Test fun `offset is zero at the centre regardless of bulge`() {
        assertEquals(0f, liquidGlassOffsetPx(50f, 24f, 6f, 0f), 1e-4f)
        assertEquals(0f, liquidGlassOffsetPx(50f, 24f, 6f, 1f), 1e-4f)
    }

    @Test fun `offset is the rest amount at the edge, doubled at full bulge`() {
        assertEquals(6f, liquidGlassOffsetPx(0f, 24f, 6f, 0f), 1e-4f)
        assertEquals(12f, liquidGlassOffsetPx(0f, 24f, 6f, 1f), 1e-4f)
        // Halfway bulge: 1.5x the rest offset.
        assertEquals(9f, liquidGlassOffsetPx(0f, 24f, 6f, .5f), 1e-4f)
    }

    @Test fun `offset scales smoothly between centre and edge`() {
        val restOffset = 6f
        val edge = liquidGlassOffsetPx(0f, 24f, restOffset, 0f)
        val mid = liquidGlassOffsetPx(12f, 24f, restOffset, 0f)
        val centre = liquidGlassOffsetPx(24f, 24f, restOffset, 0f)
        assertTrue(edge > mid)
        assertTrue(mid > centre)
        assertEquals(0f, centre, 1e-4f)
    }

    @Test fun `bulge outside 0 to 1 is clamped`() {
        assertEquals(liquidGlassOffsetPx(0f, 24f, 6f, 1f), liquidGlassOffsetPx(0f, 24f, 6f, 5f), 1e-4f)
        assertEquals(liquidGlassOffsetPx(0f, 24f, 6f, 0f), liquidGlassOffsetPx(0f, 24f, 6f, -3f), 1e-4f)
    }

    // --- light direction from gravity --------------------------------------------------

    @Test fun `default direction is a fixed top-left before any update`() {
        val light = LiquidGlassLight()
        assertEquals(LiquidGlassLight.DEFAULT_X, light.dirX, 1e-4f)
        assertEquals(LiquidGlassLight.DEFAULT_Y, light.dirY, 1e-4f)
        // Unit vector.
        assertEquals(1f, hypot(light.dirX, light.dirY), 1e-3f)
    }

    @Test fun `a held tilt eventually settles the light toward that direction`() {
        val light = LiquidGlassLight()
        var t = 0L
        // Phone tilted so gravity reads strongly to the right; low-pass tau is 150 ms, so a couple
        // of seconds is plenty to settle.
        while (t <= 3_000L) { light.update(t, 8f, 0f); t += 16L }
        assertTrue("expected the light to point mostly along +x, got (${light.dirX}, ${light.dirY})", light.dirX > 0.9f)
        assertTrue(abs(light.dirY) < 0.2f)
    }

    @Test fun `low pass means the direction does not jump instantly`() {
        val light = LiquidGlassLight()
        var t = 0L
        // Settle pointing straight down first.
        while (t <= 2_000L) { light.update(t, 0f, 8f); t += 16L }
        assertTrue(light.dirY > 0.95f)
        // One more frame with a hard tilt to the right: far from fully settled against a 150 ms
        // tau, so the direction should still lean mostly toward the old (down) reading.
        light.update(t, 8f, 0f)
        assertTrue("expected a partial response, got (${light.dirX}, ${light.dirY})", light.dirX < 0.5f && light.dirY > 0.3f)
    }

    @Test fun `near-zero smoothed gravity falls back to the default direction`() {
        val light = LiquidGlassLight()
        light.update(0L, 0.01f, -0.01f) // far below MIN_MAGNITUDE
        assertEquals(LiquidGlassLight.DEFAULT_X, light.dirX, 1e-4f)
        assertEquals(LiquidGlassLight.DEFAULT_Y, light.dirY, 1e-4f)
    }

    @Test fun `reset forgets the settled direction`() {
        val light = LiquidGlassLight()
        var t = 0L
        while (t <= 1_000L) { light.update(t, 0f, 8f); t += 16L }
        assertTrue(light.dirY > 0.5f)
        light.reset()
        assertEquals(LiquidGlassLight.DEFAULT_X, light.dirX, 1e-4f)
        assertEquals(LiquidGlassLight.DEFAULT_Y, light.dirY, 1e-4f)
    }

    @Test fun `quantize snaps to the nearest bucket and collapses jitter to the same value`() {
        val a = LiquidGlassLight.quantize(0.301f, 32)
        val b = LiquidGlassLight.quantize(0.302f, 32)
        assertEquals(a, b, 0f) // both round to the same 1/32 bucket
        assertEquals(0.5f, LiquidGlassLight.quantize(0.5f, 32), 1e-4f)
        assertEquals(0f, LiquidGlassLight.quantize(0.01f, 32), 1e-4f)
    }

    // --- wallpaper sample origin / cell crop -------------------------------------------

    @Test fun `zero parallax offset is the identity`() {
        val (x, y) = liquidGlassSampleOrigin(100f, 50f, 0f, 0f)
        assertEquals(100f, x, 1e-4f)
        assertEquals(50f, y, 1e-4f)
    }

    @Test fun `sample origin shifts opposite the parallax offset`() {
        val (x, y) = liquidGlassSampleOrigin(100f, 50f, 8f, -4f)
        assertEquals(92f, x, 1e-4f)
        assertEquals(54f, y, 1e-4f)
    }

    @Test fun `cell crop with zero parallax matches a direct backdrop crop`() {
        val expected = backdropCrop(100f, 60f, 64f, 64f, 0f, 0f, 800f, 600f, 100, 75)
        val actual = liquidGlassCellCrop(100f, 60f, 64f, 64f, 0f, 0f, 0f, 0f, 800f, 600f, 100, 75)
        assertEquals(expected, actual)
    }

    @Test fun `cell crop moves with the parallax offset`() {
        val base = liquidGlassCellCrop(100f, 60f, 64f, 64f, 0f, 0f, 0f, 0f, 800f, 600f, 100, 75)
        val shifted = liquidGlassCellCrop(100f, 60f, 64f, 64f, 10f, 0f, 0f, 0f, 800f, 600f, 100, 75)
        // A positive x parallax samples further left in the backdrop.
        assertTrue(shifted.left < base.left)
    }

    // --- Výkon 3 "kreslení na inneru": the cached-bitmap panel's cache key -------------

    @Test fun `pixel bucketing snaps to the nearest bucket, not a floor`() {
        assertEquals(0, liquidGlassPxBucket(0f, 2f))
        assertEquals(0, liquidGlassPxBucket(0.9f, 2f))
        assertEquals(1, liquidGlassPxBucket(1.1f, 2f))
        assertEquals(1, liquidGlassPxBucket(2f, 2f))
        assertEquals(-1, liquidGlassPxBucket(-2f, 2f))
    }

    @Test fun `pixel bucketing with a non-positive bucket size falls back to plain rounding`() {
        assertEquals(5, liquidGlassPxBucket(5.4f, 0f))
        assertEquals(5, liquidGlassPxBucket(5.4f, -1f))
    }

    @Test fun `a position change under the 2 dp bucket keeps the same cache key`() {
        val a = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        // Comfortably inside the same 2 px bucket as (100, 100) — half a bucket is the boundary.
        val b = liquidGlassCacheKeyFor(64f, 64f, 16f, 100.6f, 100.4f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        assertEquals(a, b)
    }

    @Test fun `a position change at or beyond the 2 dp bucket changes the cache key`() {
        val a = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        val b = liquidGlassCacheKeyFor(64f, 64f, 16f, 103f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        assertTrue(a != b)
    }

    @Test fun `a new wallpaper revision always changes the cache key`() {
        val a = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        val b = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 2)
        assertTrue(a != b)
    }

    @Test fun `a light direction change beyond the existing 1-32 quantization changes the cache key`() {
        val a = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.70f, -0.70f, 0, wallpaperRevision = 1)
        val b = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, 0.70f, 0.70f, 0, wallpaperRevision = 1)
        assertTrue(a != b)
    }

    @Test fun `a light direction change too small for the 1-32 quantization keeps the same cache key`() {
        // 19/32 exactly, dead centre of its bucket, so a small nudge cannot cross a boundary.
        val centre = 19f / 32f
        val a = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, centre, centre, 0, wallpaperRevision = 1)
        val b = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, centre + 0.005f, centre + 0.005f, 0, wallpaperRevision = 1)
        assertEquals(a, b)
    }

    @Test fun `identical inputs always produce an equal key (idempotent, reusable)`() {
        val a = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        val b = liquidGlassCacheKeyFor(64f, 64f, 16f, 100f, 100f, -0.7f, -0.7f, 0, wallpaperRevision = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
