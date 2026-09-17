package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.island.ISLAND_OPACITY_DEFAULT
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_MAX
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_MIN
import cz.pflanzer.foldduo.island.islandCardGradientAlpha
import cz.pflanzer.foldduo.island.islandCardVeilAlpha
import cz.pflanzer.foldduo.island.islandOpacityClamped
import cz.pflanzer.foldduo.island.islandOpacitySnapped
import cz.pflanzer.foldduo.island.islandPillVeilAlpha
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Island transparency" (Colours & glass settings, Tom 2026-09-17: "nastavení transparentnosti i
 * těch malých i velkých tabletek. Default 75 %"): pure alpha-mapping tests for
 * `island/IslandCardMotion.kt`'s `islandOpacityClamped`/`islandPillVeilAlpha`/
 * `islandCardVeilAlpha`/`islandCardGradientAlpha`/`islandOpacitySnapped`. No Android/Compose types
 * involved, so these run as plain JVM unit tests.
 */
class IslandOpacityTest {

    @Test fun `default is 75pct, floor is 40pct, ceiling is 100pct`() {
        assertEquals(0.75f, ISLAND_OPACITY_DEFAULT)
        assertEquals(0.4f, ISLAND_OPACITY_MIN)
        assertEquals(1.0f, ISLAND_OPACITY_MAX)
    }

    @Test fun `opacity is clamped to 0point4 to 1point0`() {
        assertEquals(ISLAND_OPACITY_MIN, islandOpacityClamped(0f))
        assertEquals(ISLAND_OPACITY_MIN, islandOpacityClamped(0.39f))
        assertEquals(ISLAND_OPACITY_MIN, islandOpacityClamped(ISLAND_OPACITY_MIN))
        assertEquals(0.6f, islandOpacityClamped(0.6f), 1e-6f)
        assertEquals(ISLAND_OPACITY_MAX, islandOpacityClamped(ISLAND_OPACITY_MAX))
        assertEquals(ISLAND_OPACITY_MAX, islandOpacityClamped(1.4f))
    }

    @Test fun `100pct opacity reproduces today's fixed pill and card alpha exactly`() {
        assertEquals(0.55f, islandPillVeilAlpha(ISLAND_OPACITY_MAX, 0.55f), 1e-6f)
        assertEquals(0.92f, islandCardVeilAlpha(ISLAND_OPACITY_MAX, 0.92f), 1e-6f)
        assertEquals(0.7f, islandCardGradientAlpha(ISLAND_OPACITY_MAX, 0.7f), 1e-6f)
    }

    @Test fun `75pct default lets a quarter more wallpaper through than 100pct`() {
        val pillAt100 = islandPillVeilAlpha(ISLAND_OPACITY_MAX, 0.55f)
        val pillAtDefault = islandPillVeilAlpha(ISLAND_OPACITY_DEFAULT, 0.55f)
        assertEquals(0.4125f, pillAtDefault, 1e-6f)
        assertEquals(pillAt100 * 0.75f, pillAtDefault, 1e-6f)
    }

    @Test fun `40pct floor is the smallest alpha the mapping ever produces`() {
        assertEquals(0.55f * 0.4f, islandPillVeilAlpha(ISLAND_OPACITY_MIN, 0.55f), 1e-6f)
        assertEquals(0.92f * 0.4f, islandCardVeilAlpha(ISLAND_OPACITY_MIN, 0.92f), 1e-6f)
        // Below the floor never scales further down than the floor itself.
        assertEquals(islandPillVeilAlpha(ISLAND_OPACITY_MIN, 0.55f), islandPillVeilAlpha(0f, 0.55f), 1e-6f)
    }

    @Test fun `card gradient alpha multiplies the morph window, not replaces it`() {
        assertEquals(0f, islandCardGradientAlpha(ISLAND_OPACITY_DEFAULT, 0f), 1e-6f)
        assertEquals(0.75f, islandCardGradientAlpha(ISLAND_OPACITY_DEFAULT, 1f), 1e-6f)
        assertEquals(0.375f, islandCardGradientAlpha(ISLAND_OPACITY_DEFAULT, 0.5f), 1e-6f)
        // Fully clamped out-of-range opacity behaves the same as its clamped value.
        assertEquals(islandCardGradientAlpha(ISLAND_OPACITY_MAX, 0.6f), islandCardGradientAlpha(1.5f, 0.6f), 1e-6f)
    }

    @Test fun `opacity snaps to the nearest 5pct step inside range`() {
        assertEquals(0.75f, islandOpacitySnapped(0.75f), 1e-6f)
        assertEquals(0.75f, islandOpacitySnapped(0.76f), 1e-6f)
        assertEquals(0.8f, islandOpacitySnapped(0.78f), 1e-6f)
        assertEquals(ISLAND_OPACITY_MIN, islandOpacitySnapped(0.1f), 1e-6f)
        assertEquals(ISLAND_OPACITY_MAX, islandOpacitySnapped(2f), 1e-6f)
    }
}
