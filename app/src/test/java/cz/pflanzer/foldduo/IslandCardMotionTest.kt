package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.island.ISLAND_EQUALIZER_PAUSED_LEVEL
import cz.pflanzer.foldduo.island.MorphRect
import cz.pflanzer.foldduo.island.darkenArgb
import cz.pflanzer.foldduo.island.extrapolatedPositionMs
import cz.pflanzer.foldduo.island.islandArtworkRect
import cz.pflanzer.foldduo.island.islandCardTextAlpha
import cz.pflanzer.foldduo.island.islandControlsAlpha
import cz.pflanzer.foldduo.island.islandControlsRiseDp
import cz.pflanzer.foldduo.island.islandEqualizerBarLevel
import cz.pflanzer.foldduo.island.islandLerp
import cz.pflanzer.foldduo.island.islandMorphCornerDp
import cz.pflanzer.foldduo.island.islandMorphRect
import cz.pflanzer.foldduo.island.islandMorphWindow
import cz.pflanzer.foldduo.island.islandPillContentAlpha
import cz.pflanzer.foldduo.island.mediaCardGradientBottomArgb
import cz.pflanzer.foldduo.island.mediaCardGradientTopArgb
import cz.pflanzer.foldduo.island.mediaCardUsesLightText
import cz.pflanzer.foldduo.island.withAlphaFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Ostrůvek: Apple Music karta a morph" (17. 9. noc): the pure rules behind the pill<->card morph
 * (island/IslandCardMotion.kt) — container/artwork rect lerp, the three opacity windows, the
 * equalizer glyph's phase, live position extrapolation, and the gradient/text-colour choice.
 */
class IslandCardMotionTest {
    private val pill = MorphRect(leftDp = 400f, topDp = 20f, widthDp = 80f, heightDp = 44f)
    private val card = MorphRect(leftDp = 40f, topDp = 20f, widthDp = 420f, heightDp = 160f)

    @Test fun `lerp is exact at both ends and the midpoint`() {
        assertEquals(0f, islandLerp(0f, 10f, 0f), 1e-4f)
        assertEquals(10f, islandLerp(0f, 10f, 1f), 1e-4f)
        assertEquals(5f, islandLerp(0f, 10f, 0.5f), 1e-4f)
        assertEquals(-5f, islandLerp(10f, 0f, 1.5f), 1e-4f)
    }

    @Test fun `a window is 0 before start, 1 after end, linear between, and degenerate windows snap`() {
        assertEquals(0f, islandMorphWindow(0f, 0.4f, 1f), 1e-4f)
        assertEquals(0f, islandMorphWindow(0.39f, 0.4f, 1f), 1e-4f)
        assertEquals(1f, islandMorphWindow(1f, 0.4f, 1f), 1e-4f)
        assertEquals(1f, islandMorphWindow(2f, 0.4f, 1f), 1e-4f)
        assertEquals(0.5f, islandMorphWindow(0.7f, 0.4f, 1f), 1e-3f)
        // A zero-width window is a step function at its end.
        assertEquals(0f, islandMorphWindow(0.49f, 0.5f, 0.5f), 1e-4f)
        assertEquals(1f, islandMorphWindow(0.5f, 0.5f, 0.5f), 1e-4f)
    }

    @Test fun `container rect lerps every edge from the pill to the card`() {
        val start = islandMorphRect(0f, pill, card)
        assertEquals(pill, start)
        val end = islandMorphRect(1f, pill, card)
        assertEquals(card, end)
        val mid = islandMorphRect(0.5f, pill, card)
        assertEquals((pill.leftDp + card.leftDp) / 2f, mid.leftDp, 1e-4f)
        assertEquals((pill.widthDp + card.widthDp) / 2f, mid.widthDp, 1e-4f)
        assertEquals((pill.heightDp + card.heightDp) / 2f, mid.heightDp, 1e-4f)
        // Same top throughout (top-right anchor never moves) still lerps consistently at 0/1/mid.
        assertEquals(pill.topDp, mid.topDp, 1e-4f)
    }

    @Test fun `the shared artwork rect travels the same way as the container rect`() {
        val artworkPill = MorphRect(408f, 29f, 26f, 26f)
        val artworkCard = MorphRect(54f, 32f, 96f, 96f)
        assertEquals(artworkPill, islandArtworkRect(0f, artworkPill, artworkCard))
        assertEquals(artworkCard, islandArtworkRect(1f, artworkPill, artworkCard))
        val mid = islandArtworkRect(0.5f, artworkPill, artworkCard)
        assertEquals((artworkPill.widthDp + artworkCard.widthDp) / 2f, mid.widthDp, 1e-4f)
    }

    @Test fun `corner radius lerps from half the pill height to the fixed card corner`() {
        assertEquals(22f, islandMorphCornerDp(0f, pillHeightDp = 44f), 1e-4f)
        assertEquals(28f, islandMorphCornerDp(1f, pillHeightDp = 44f), 1e-4f)
        assertEquals(25f, islandMorphCornerDp(0.5f, pillHeightDp = 44f), 1e-4f)
    }

    // --- Opacity windows: pill fades 0-30%, card text 40-100%, controls 55-100% + 8dp rise ---

    @Test fun `pill content fades out over the first 30 percent of the morph`() {
        assertEquals(1f, islandPillContentAlpha(0f), 1e-4f)
        assertEquals(0.5f, islandPillContentAlpha(0.15f), 1e-3f)
        assertEquals(0f, islandPillContentAlpha(0.3f), 1e-4f)
        assertEquals(0f, islandPillContentAlpha(1f), 1e-4f)
    }

    @Test fun `card title and artist fade in from 40 percent`() {
        assertEquals(0f, islandCardTextAlpha(0.39f), 1e-4f)
        assertEquals(0f, islandCardTextAlpha(0.4f), 1e-4f)
        assertEquals(0.5f, islandCardTextAlpha(0.7f), 1e-3f)
        assertEquals(1f, islandCardTextAlpha(1f), 1e-4f)
    }

    @Test fun `transport controls fade in from 55 percent and rise 8dp as they do`() {
        assertEquals(0f, islandControlsAlpha(0.54f), 1e-4f)
        assertEquals(1f, islandControlsAlpha(1f), 1e-4f)
        assertEquals(8f, islandControlsRiseDp(0f), 1e-4f)
        assertEquals(0f, islandControlsRiseDp(1f), 1e-4f)
        assertEquals(4f, islandControlsRiseDp(0.775f), 1e-3f) // halfway through the 0.55-1 window
    }

    // --- Equalizer glyph -------------------------------------------------------------------

    @Test fun `equalizer bars hold a static level while paused`() {
        assertEquals(ISLAND_EQUALIZER_PAUSED_LEVEL, islandEqualizerBarLevel(0, 0L, playing = false))
        assertEquals(ISLAND_EQUALIZER_PAUSED_LEVEL, islandEqualizerBarLevel(1, 5_000L, playing = false))
        assertEquals(ISLAND_EQUALIZER_PAUSED_LEVEL, islandEqualizerBarLevel(2, 900_000L, playing = false))
    }

    @Test fun `equalizer bars oscillate out of phase while playing, always within 0_25 to 1`() {
        val samples = (0..20).map { it * 45L }
        for (t in samples) {
            for (bar in 0..2) {
                val level = islandEqualizerBarLevel(bar, t, playing = true)
                assertTrue("bar $bar at $t was $level", level in 0.25f..1f)
            }
        }
        // Different bars read different levels at the same instant (phase-shifted, not lockstep).
        val t = 200L
        val levels = (0..2).map { islandEqualizerBarLevel(it, t, playing = true) }
        assertTrue(levels[0] != levels[1] || levels[1] != levels[2])
        // The animation repeats every period.
        assertEquals(islandEqualizerBarLevel(0, 100L, playing = true),
            islandEqualizerBarLevel(0, 100L + 900L, playing = true), 1e-4f)
    }

    // --- Live position extrapolation --------------------------------------------------------

    @Test fun `paused playback never advances regardless of elapsed time or speed`() {
        assertEquals(30_000L, extrapolatedPositionMs(30_000L, baseAtMs = 1_000L, nowMs = 50_000L, speed = 1f, playing = false))
        assertEquals(30_000L, extrapolatedPositionMs(30_000L, baseAtMs = 1_000L, nowMs = 999_000L, speed = 2f, playing = false))
    }

    @Test fun `playing playback advances by elapsed time scaled by speed`() {
        assertEquals(31_000L, extrapolatedPositionMs(30_000L, baseAtMs = 0L, nowMs = 1_000L, speed = 1f, playing = true))
        assertEquals(32_000L, extrapolatedPositionMs(30_000L, baseAtMs = 0L, nowMs = 1_000L, speed = 2f, playing = true))
        // 1.5x speed for four seconds.
        assertEquals(36_000L, extrapolatedPositionMs(30_000L, baseAtMs = 0L, nowMs = 4_000L, speed = 1.5f, playing = true))
    }

    @Test fun `extrapolated position clamps to 0 and to the known duration`() {
        assertEquals(0L, extrapolatedPositionMs(0L, baseAtMs = 5_000L, nowMs = 0L, speed = 1f, playing = true))
        assertEquals(180_000L, extrapolatedPositionMs(179_000L, baseAtMs = 0L, nowMs = 5_000L, speed = 1f, playing = true, durationMs = 180_000L))
        // No known duration (0): never clamped from above.
        assertEquals(184_000L, extrapolatedPositionMs(179_000L, baseAtMs = 0L, nowMs = 5_000L, speed = 1f, playing = true, durationMs = 0L))
    }

    // --- Gradient stops / auto text colour --------------------------------------------------

    @Test fun `darken scales rgb channels toward black and leaves alpha alone`() {
        val opaqueRed = 0xFFFF0000.toInt()
        assertEquals(0xFF800000.toInt(), darkenArgb(opaqueRed, 0.5f))
        assertEquals(0xFF000000.toInt(), darkenArgb(opaqueRed, 1f))
        assertEquals(opaqueRed, darkenArgb(opaqueRed, 0f))
        val halfAlphaGreen = 0x8000FF00.toInt()
        assertEquals((0x80 shl 24) or 0x008000, darkenArgb(halfAlphaGreen, 0.5f))
    }

    @Test fun `withAlphaFraction replaces only the alpha channel`() {
        val rgb = 0x112233
        assertEquals((0xD9 shl 24) or rgb, withAlphaFraction(0xFF112233.toInt(), 0.85f))
        assertEquals(rgb, withAlphaFraction(0xFF112233.toInt(), 0f))
    }

    @Test fun `gradient stops are 85pct top and 30pct-darkened bottom of the same dominant colour`() {
        val dominant = 0xFF3355AA.toInt()
        val top = mediaCardGradientTopArgb(dominant)
        assertEquals(0xD9, (top ushr 24) and 0xFF) // round(0.85 * 255)
        val bottom = mediaCardGradientBottomArgb(dominant)
        assertEquals(0xFF, (bottom ushr 24) and 0xFF)
        assertEquals(darkenArgb(dominant, 0.30f), bottom)
    }

    @Test fun `dark album art gets light text, bright album art gets dark text`() {
        val darkTop = 0xFF101018.toInt()
        val darkBottom = darkenArgb(darkTop, 0.30f)
        assertTrue(mediaCardUsesLightText(darkTop, darkBottom))
        val brightTop = 0xFFF5F5F0.toInt()
        val brightBottom = darkenArgb(brightTop, 0.30f)
        assertFalse(mediaCardUsesLightText(brightTop, brightBottom))
    }
}
