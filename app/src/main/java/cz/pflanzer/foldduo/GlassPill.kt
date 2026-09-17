package cz.pflanzer.foldduo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import cz.pflanzer.foldduo.island.IslandStyle
import cz.pflanzer.foldduo.island.islandOpacityClamped

/**
 * "Matné sklo pro všechny pilulky" (Tom, 2026-09-17 noc): "všechny ty tabletky musí brát opacity,
 * co je nastavená v settings, default 75 % ... všechno to musí být takové matné průhledné sklo."
 * The one glass primitive every pill/card in Fold Duo now goes through instead of its own ad-hoc
 * [frostedGlass] call: the same blurred-wallpaper backing, plus a veil at `opacity × baseVeilAlpha`
 * ([glassVeilAlpha] — the exact formula the island pill/card already proved, see
 * `island/IslandCardMotion.kt`'s `islandPillVeilAlpha`/`islandCardVeilAlpha`, generalised here so
 * every call site computes the identical number) driven by the live "Glass transparency" setting
 * ([IslandStyle.current] — object name and the `islandOpacity` pref key kept exactly as-is by the
 * rename so the persisted value survives), and a 1 px hairline border fixed at
 * [GLASS_PILL_BORDER_ALPHA] regardless of the opacity knob — an edge is not "how much wallpaper
 * shows through", so it never scales with it.
 *
 * [tint] both colours the veil and (via [fallback]/border) everything else this primitive draws;
 * pass the pane's neutral wallpaper-derived veil (the default) for a plain glass surface, or a
 * state colour (an item's accent, say) for a tinted one — a single flat colour is all this takes.
 * A surface that needs more than one colour (e.g. the expanded island card's album-colour
 * gradient, which must show through the veil rather than being replaced by it) draws that layer
 * itself, between the blurred backdrop and this primitive's own veil — see
 * `island/RailIsland.kt`'s `IslandExpandedOverlay` doc for why that one case is hand-rolled rather
 * than routed through this modifier (its corner/rect animate every frame without recomposing,
 * which this composable-level primitive cannot do). Text, icons and artwork are always drawn by
 * the caller on top and stay fully opaque either way — no solid, opaque colour fill belongs here.
 *
 * [opacity] defaults to the live setting so ordinary callers never have to think about it; a
 * caller previewing the setting itself (the slider's own swatch, `ColoursGlassSettings.kt`) can
 * pass a candidate value that has not been persisted yet.
 */
@Composable
fun Modifier.glassPill(
    corner: Dp,
    opacity: Float = IslandStyle.current.value,
    tint: Color = Color(LocalWallpaperPalette.current.veilArgb),
    baseVeilAlpha: Float = GLASS_PILL_BASE_VEIL_ALPHA,
    fallback: Color = tint.copy(alpha = .3f),
): Modifier = frostedGlass(
    corner = corner,
    tintAlpha = glassVeilAlpha(opacity, baseVeilAlpha),
    fallback = fallback,
    veilColor = tint,
    border = tint.copy(alpha = GLASS_PILL_BORDER_ALPHA),
)

/** Fixed hairline tint every glass pill/card now shares ("1 px hairline border 12 %") — never scaled by [glassVeilAlpha]; matches the iOS menu language's own [IosHairlineColor] alpha. */
const val GLASS_PILL_BORDER_ALPHA = .12f

/** [glassPill]'s own default base veil when a call site does not pass one — [frostedGlass]'s own historical default tint. */
const val GLASS_PILL_BASE_VEIL_ALPHA = .22f

/**
 * The pure alpha mapping every glass surface's veil now shares: [baseAlpha] (today's fixed look
 * at 100 %) scaled by the clamped "Glass transparency" opacity (`0.4..1.0`, default `0.75`) —
 * `1.0` reproduces [baseAlpha] exactly, `0.75` lets a quarter more wallpaper through, `0.4` is the
 * floor. Delegates to [islandOpacityClamped] (`island/IslandCardMotion.kt`) so the clamp/floor/
 * ceiling logic — already covered by `IslandOpacityTest.kt` — lives in exactly one place.
 */
fun glassVeilAlpha(opacity: Float, baseAlpha: Float): Float = baseAlpha * islandOpacityClamped(opacity)
