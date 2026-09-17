package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.island.ISLAND_OPACITY_DEFAULT
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_MAX
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_MIN
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Matné sklo pro všechny pilulky" (Tom, 2026-09-17 noc): pure alpha-mapping tests for
 * `GlassPill.kt`'s [glassVeilAlpha] — the one formula every glass pill/card in the app now shares
 * (`Modifier.glassPill`'s `tintAlpha`). No Android/Compose types involved, so this runs as a plain
 * JVM unit test, same shape as `IslandOpacityTest.kt` (which covers the same formula through its
 * island-specific names, `islandPillVeilAlpha`/`islandCardVeilAlpha`, that now delegate here).
 *
 * Call sites converted to [Modifier.glassPill] (documented for the grep-based inventory the task
 * asked for, since a compile-time "every pill uses the primitive" test is not expressible without
 * a source-scanning test — see STATUS.md "Matné sklo pro všechny pilulky"): `island/RailIsland.kt`
 * (`IslandPill`, `IslandExpandedOverlay`'s hand-rolled equivalent), `StatusRail.kt` (the ring
 * glyph's own backing), `status/StatusCard.kt` (the status pill shell),
 * `notifications/NotificationHubPanel.kt` (`NotificationHubPill`), `SeamPalette.kt` (the palette
 * panel), `HomeEditModeUI.kt` (`EditModeTopBar`'s "+"/"Done"), `Spotlight.kt`
 * (`SpotlightFieldPill`), `predict/PredictionUi.kt` (`ContinuityChip`), `TentTiltMedia.kt` (the
 * tent-tilt toast).
 */
class GlassPillTest {

    @Test fun `40pct floor`() {
        assertEquals(0.4f * 0.55f, glassVeilAlpha(ISLAND_OPACITY_MIN, 0.55f), 1e-6f)
        assertEquals(0.4f * 0.24f, glassVeilAlpha(0.1f, 0.24f), 1e-6f) // clamped to the floor
    }

    @Test fun `75pct default lets a quarter more wallpaper through than 100pct`() {
        val at100 = glassVeilAlpha(ISLAND_OPACITY_MAX, 0.34f)
        val atDefault = glassVeilAlpha(ISLAND_OPACITY_DEFAULT, 0.34f)
        assertEquals(0.34f * 0.75f, atDefault, 1e-6f)
        assertEquals(at100 * 0.75f, atDefault, 1e-6f)
    }

    @Test fun `100pct reproduces the base alpha exactly for any base`() {
        assertEquals(0.55f, glassVeilAlpha(ISLAND_OPACITY_MAX, 0.55f), 1e-6f)
        assertEquals(0.92f, glassVeilAlpha(ISLAND_OPACITY_MAX, 0.92f), 1e-6f)
        assertEquals(0.22f, glassVeilAlpha(ISLAND_OPACITY_MAX, 0.22f), 1e-6f)
    }

    @Test fun `out-of-range opacity behaves like its clamped value`() {
        assertEquals(glassVeilAlpha(ISLAND_OPACITY_MAX, 0.3f), glassVeilAlpha(2f, 0.3f), 1e-6f)
        assertEquals(glassVeilAlpha(ISLAND_OPACITY_MIN, 0.3f), glassVeilAlpha(-1f, 0.3f), 1e-6f)
    }

    @Test fun `border alpha is fixed regardless of opacity`() {
        // Task spec: "1 px hairline border 12 %" — never scaled by the transparency setting.
        assertEquals(0.12f, GLASS_PILL_BORDER_ALPHA)
    }

    @Test fun `glassPill default base veil matches frostedGlass's own historical default`() {
        assertEquals(0.22f, GLASS_PILL_BASE_VEIL_ALPHA)
    }
}
