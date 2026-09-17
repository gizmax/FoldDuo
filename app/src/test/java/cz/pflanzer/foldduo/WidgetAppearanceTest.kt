package cz.pflanzer.foldduo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAppearanceTest {
    private val yellow = WIDGET_TINT_PRESETS[5].first
    private val blue = WIDGET_TINT_PRESETS[0].first

    private fun contrast(a: Long, b: Long): Double {
        val la = relativeLuminance(a.toInt()); val lb = relativeLuminance(b.toInt())
        return (maxOf(la, lb) + .05) / (minOf(la, lb) + .05)
    }

    // --- palette --------------------------------------------------------------------

    @Test fun `auto follows the system theme, light and dark pin it`() {
        assertEquals(widgetPalette(WidgetAppearance.Light, null, systemDark = false), widgetPalette(WidgetAppearance.Auto, null, systemDark = false))
        assertEquals(widgetPalette(WidgetAppearance.Dark, null, systemDark = true), widgetPalette(WidgetAppearance.Auto, null, systemDark = true))
        assertEquals(widgetPalette(WidgetAppearance.Light, null, systemDark = false), widgetPalette(WidgetAppearance.Light, null, systemDark = true))
        assertEquals(widgetPalette(WidgetAppearance.Dark, null, systemDark = true), widgetPalette(WidgetAppearance.Dark, null, systemDark = false))
        val light = widgetPalette(WidgetAppearance.Light, null, systemDark = false)
        assertEquals(0xEBFFFFFF, light.card)          // white 92 %
        assertEquals(0xFF000000, light.label)
        assertTrue(light.darkInk); assertNull(light.border); assertFalse(light.inkShadow); assertFalse(light.monochrome)
        val dark = widgetPalette(WidgetAppearance.Dark, null, systemDark = false)
        assertEquals(0xD91C1C1E, dark.card)           // #1C1C1E 85 %
        assertEquals(0xFFFFFFFF, dark.label)
        assertFalse(dark.darkInk); assertNull(dark.border)
    }

    @Test fun `glass is a translucent white card with a hairline, white ink and a text shadow`() {
        val glass = widgetPalette(WidgetAppearance.Glass, null, systemDark = false)
        assertEquals(0x33FFFFFF, glass.card)          // white 20 %
        assertEquals(0x40FFFFFFL, glass.border)       // white 25 %
        assertEquals(0xFFFFFFFF, glass.label)
        assertFalse(glass.darkInk); assertTrue(glass.inkShadow); assertFalse(glass.monochrome)
        assertEquals(glass, widgetPalette(WidgetAppearance.Glass, yellow, systemDark = true))
    }

    /** Source-over of a translucent ARGB [top] on an opaque [ground], like the card over the wallpaper. */
    private fun composite(top: Long, ground: Long): Long {
        val a = ((top ushr 24) and 0xFF) / 255.0
        fun channel(shift: Int): Long {
            val t = (top shr shift) and 0xFF; val g = (ground shr shift) and 0xFF
            return Math.round(t * a + g * (1 - a)).coerceIn(0, 255)
        }
        return 0xFF000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    @Test fun `glass is its own card, not the auto, dark or light one, whatever the theme or tint`() {
        listOf(false, true).forEach { dark ->
            listOf(null, yellow, blue).forEach { tint ->
                val glass = widgetPalette(WidgetAppearance.Glass, tint, systemDark = dark)
                assertNotEquals("auto/dark=$dark", widgetPalette(WidgetAppearance.Auto, tint, systemDark = true).card, glass.card)
                assertNotEquals("auto/light", widgetPalette(WidgetAppearance.Auto, tint, systemDark = false).card, glass.card)
                assertNotEquals("dark", widgetPalette(WidgetAppearance.Dark, tint, dark).card, glass.card)
                assertNotEquals("light", widgetPalette(WidgetAppearance.Light, tint, dark).card, glass.card)
                assertNotEquals("tinted", widgetPalette(WidgetAppearance.Tinted, tint, dark).card, glass.card)
                assertNotEquals(widgetPalette(WidgetAppearance.Dark, tint, dark), glass)
                assertNotEquals(widgetPalette(WidgetAppearance.Auto, tint, dark), glass)
            }
        }
        // The four pinned looks are four different cards; Auto is one of Light and Dark, never a fifth.
        val pinned = listOf(WidgetAppearance.Light, WidgetAppearance.Dark, WidgetAppearance.Glass, WidgetAppearance.Tinted)
            .map { widgetPalette(it, null, systemDark = true).card }
        assertEquals(4, pinned.distinct().size)
        listOf(false, true).forEach { dark ->
            assertTrue(widgetPalette(WidgetAppearance.Auto, null, dark).card in pinned)
        }
    }

    @Test fun `glass stays visibly different from the dark card on the launcher grounds and lighter even on black`() {
        val glass = widgetPalette(WidgetAppearance.Glass, null, systemDark = true)
        val dark = widgetPalette(WidgetAppearance.Dark, null, systemDark = true)
        // Night dune sky and sand (DarkDuoPalette), then pure black: a 20 % white fill can only
        // reach #333333 there, 1.4:1 against the dark card, so on a near-black photo the two cards
        // are told apart by the hairline and the text shadow, not the fill.
        listOf(0xFF132832L to 1.8, 0xFF463F35L to 1.8, 0xFF000000L to 1.4).forEach { (ground, floor) ->
            val glassOver = composite(glass.card, ground)
            val darkOver = composite(dark.card, ground)
            assertNotEquals("ground %08X".format(ground), darkOver, glassOver)
            assertTrue("glass lighter than dark on %08X".format(ground), relativeLuminance(glassOver.toInt()) > relativeLuminance(darkOver.toInt()))
            assertTrue("contrast on %08X".format(ground), contrast(glassOver, darkOver) >= floor)
            // The hairline still outlines the card on a dark ground; only glass has one.
            assertTrue("hairline on %08X".format(ground), contrast(composite(glass.border!!, ground), darkOver) >= 1.5)
            assertNull(dark.border)
            // White ink with a shadow keeps reading on the glass card over any of them.
            assertTrue(contrast(glassOver, glass.label) >= 4.5); assertTrue(glass.inkShadow)
        }
        assertEquals(0xFF42535BL, composite(glass.card, 0xFF132832L))
        assertEquals(0xFF1B1E21L, composite(dark.card, 0xFF132832L))
        assertEquals(0xFF333333L, composite(glass.card, 0xFF000000L))
    }

    @Test fun `tinted picks ink by luminance and keeps contrast on every preset`() {
        WIDGET_TINT_PRESETS.forEach { (argb, name) ->
            val palette = widgetPalette(WidgetAppearance.Tinted, argb, systemDark = false)
            assertEquals(name, 0xE6000000 or (argb.toLong() and 0xFFFFFF), palette.card) // tint at 90 %
            assertEquals(name, prefersDarkInk(argb), palette.darkInk)
            assertEquals(name, if (palette.darkInk) 0xFF000000 else 0xFFFFFFFF, palette.label)
            assertEquals(name, palette.label, palette.accent)                            // no red-on-red weekday
            assertTrue(name, palette.monochrome)
            assertTrue("$name ink contrast", contrast(palette.card, palette.label) >= 3.0)
        }
        assertTrue(prefersDarkInk(yellow)); assertFalse(prefersDarkInk(blue))
        assertTrue(prefersDarkInk(WIDGET_TINT_PRESETS[4].first))                        // orange: white ink would be 2:1
        assertTrue(prefersDarkInk(WIDGET_TINT_PRESETS[6].first))                        // green
        assertEquals(listOf(false, false, false, false, true, true, true, false), WIDGET_TINT_PRESETS.map { prefersDarkInk(it.first) })
        assertTrue(prefersDarkInk(0xFFFFFFFF.toInt())); assertFalse(prefersDarkInk(0xFF000000.toInt()))
        assertEquals(1.0, relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.0, relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(.2126, relativeLuminance(0xFFFF0000.toInt()), 1e-6)
    }

    @Test fun `tinted without a colour is blue and the presets are the eight iOS tints`() {
        assertEquals(widgetPalette(WidgetAppearance.Tinted, blue, false), widgetPalette(WidgetAppearance.Tinted, null, false))
        assertEquals(listOf("Blue", "Purple", "Pink", "Red", "Orange", "Yellow", "Green", "Graphite"), WIDGET_TINT_PRESETS.map { it.second })
        assertEquals(8, WIDGET_TINT_PRESETS.map { it.first }.distinct().size)
        assertTrue(WIDGET_TINT_PRESETS.all { (it.first ushr 24) == 0xFF })
    }

    @Test fun `light and dark ink stays readable on its card`() {
        listOf(WidgetAppearance.Light, WidgetAppearance.Dark).forEach { appearance ->
            val palette = widgetPalette(appearance, null, systemDark = false)
            assertTrue(appearance.name, contrast(palette.card, palette.label) >= 7.0)
            assertTrue(appearance.name, contrast(palette.card, palette.secondary) >= 1.5)
        }
    }

    @Test fun `withAlpha replaces only the alpha byte`() {
        assertEquals(0xFF123456, withAlpha(0x123456, 1f))
        assertEquals(0x00123456, withAlpha(0xAB123456, 0f))
        assertEquals(0x80123456, withAlpha(0x123456, .5f + .002f))
        assertEquals(0xFF123456, withAlpha(0x123456, 5f))
    }

    // --- model ----------------------------------------------------------------------

    @Test fun `appearance names parse tolerantly and the iOS cards and host widgets offer the row`() {
        assertEquals(WidgetAppearance.Glass, parseWidgetAppearance("Glass"))
        assertEquals(WidgetAppearance.Tinted, parseWidgetAppearance("tinted"))
        assertEquals(WidgetAppearance.Auto, parseWidgetAppearance(null))
        assertEquals(WidgetAppearance.Auto, parseWidgetAppearance(""))
        assertEquals(WidgetAppearance.Auto, parseWidgetAppearance("Neon"))
        assertTrue(listOf(CLOCK_WIDGET, CLOCK_ANALOG_WIDGET, CALENDAR_WIDGET, BATTERIES_WIDGET, PHOTOS_WIDGET).all(::supportsWidgetAppearance))
        assertFalse(supportsWidgetAppearance(DATE_WIDGET)); assertFalse(supportsWidgetAppearance(INFO_WIDGET))
        assertFalse(supportsWidgetAppearance(EMPTY_WIDGET)); assertFalse(supportsWidgetAppearance(NEEDS_BINDING_WIDGET))
        assertTrue(supportsWidgetAppearance(0)); assertTrue(supportsWidgetAppearance(41)); assertTrue(supportsWidgetAppearance(Int.MAX_VALUE))
    }

    @Test fun `styleWidget restyles a built-in in place and ignores legacy cards`() {
        val calendar = WidgetPlacement(3, CALENDAR_WIDGET, 0, 0, 0, 4, 2)
        val host = WidgetPlacement(4, 41, 0, 0, 2, 2, 2)
        val date = WidgetPlacement(5, DATE_WIDGET, 1, 0, 0, 2, 2)
        val layout = HomeLayout(emptyList(), List(4) { null }, listOf(calendar, host, date))
        val styled = styleWidget(layout, 3, WidgetAppearance.Tinted, yellow)
        assertEquals(calendar.copy(appearance = WidgetAppearance.Tinted, tint = yellow), styled.placement(3))
        assertEquals(host, styled.placement(4))
        assertEquals(layout, styleWidget(layout, 5, WidgetAppearance.Glass, null))
        assertEquals(layout, styleWidget(layout, 9, WidgetAppearance.Glass, null))
        // The tint survives switching to another appearance, so Tinted comes back with the same colour.
        val glass = styleWidget(styled, 3, WidgetAppearance.Glass, yellow)
        assertEquals(WidgetAppearance.Glass, glass.placement(3)?.appearance)
        assertEquals(yellow, glass.placement(3)?.tint)
        // Geometry and the resize path keep the style.
        assertEquals(WidgetAppearance.Glass, resizeWidget(glass, 3, 2, 2).placement(3)?.appearance)
        assertEquals(WidgetPlacement(3, CALENDAR_WIDGET, 0, 0, 0, 4, 2), WidgetPlacement(3, CALENDAR_WIDGET, 0, 0, 0, 4, 2, WidgetAppearance.Auto, null))
    }

    @Test fun `styleWidget frames a host widget in place, on Home and on the left pane`() {
        val host = WidgetPlacement(4, 41, 0, 0, 2, 2, 2)
        val leading = WidgetPlacement(6, 57, -1, 0, 0, 4, 2)
        val layout = HomeLayout(emptyList(), List(4) { null }, listOf(host, leading))
        val glass = styleWidget(layout, 4, WidgetAppearance.Glass, null)
        assertEquals(host.copy(appearance = WidgetAppearance.Glass), glass.placement(4))
        assertEquals(leading, glass.placement(6))
        val tinted = styleWidget(glass, 6, WidgetAppearance.Tinted, blue)
        assertEquals(leading.copy(appearance = WidgetAppearance.Tinted, tint = blue), tinted.placement(6))
        assertEquals(WidgetAppearance.Glass, tinted.placement(4)?.appearance)
        assertEquals(tinted.widgetPlacements.map { it.copy(appearance = WidgetAppearance.Auto, tint = null) },
            layout.widgetPlacements)
        assertEquals(layout.placement(4), styleWidget(glass, 4, WidgetAppearance.Auto, null).placement(4))
    }

    @Test fun `host frame per appearance`() {
        listOf(false, true).forEach { dark ->
            assertEquals(HostFrameSpec(framed = false), hostFrameSpec(WidgetAppearance.Auto, yellow, dark))
            val glass = hostFrameSpec(WidgetAppearance.Glass, yellow, dark)
            assertTrue(glass.framed); assertEquals(22f, glass.cornerDp)
            assertEquals(0x33FFFFFFL, glass.backing); assertEquals(0x40FFFFFFL, glass.border)
            assertNull(glass.overlay); assertNull(glass.blend); assertEquals(.9f, glass.contentAlpha)
            val light = hostFrameSpec(WidgetAppearance.Light, null, dark)
            assertEquals(HostFrameSpec(true, 22f, backing = 0xE0FFFFFFL), light)
            val darkFrame = hostFrameSpec(WidgetAppearance.Dark, null, dark)
            assertEquals(HostFrameSpec(true, 22f, backing = 0xD91C1C1EL), darkFrame)
        }
        assertEquals(1f, hostFrameSpec(WidgetAppearance.Auto, null, false).contentAlpha)
    }

    @Test fun `tinted host frame overlays the tint and blends by its luminance`() {
        val yellowFrame = hostFrameSpec(WidgetAppearance.Tinted, yellow, systemDark = false)
        assertTrue(yellowFrame.framed); assertEquals(22f, yellowFrame.cornerDp)
        assertNull(yellowFrame.backing); assertEquals(1f, yellowFrame.contentAlpha)
        assertEquals(0x59000000L or (yellow.toLong() and 0xFFFFFF), yellowFrame.overlay)   // 35 %
        assertEquals(0x99000000L or (yellow.toLong() and 0xFFFFFF), yellowFrame.border)    // 60 %
        assertEquals(HostFrameBlend.Multiply, yellowFrame.blend)
        assertEquals(HostFrameBlend.Screen, hostFrameSpec(WidgetAppearance.Tinted, blue, false).blend)
        assertEquals(hostFrameSpec(WidgetAppearance.Tinted, blue, false), hostFrameSpec(WidgetAppearance.Tinted, null, true))
        WIDGET_TINT_PRESETS.forEach { (argb, name) ->
            val blend = hostFrameSpec(WidgetAppearance.Tinted, argb, false).blend
            assertEquals(name, if (prefersDarkInk(argb)) HostFrameBlend.Multiply else HostFrameBlend.Screen, blend)
        }
        assertEquals(HostFrameBlend.Multiply, hostFrameSpec(WidgetAppearance.Tinted, 0xFFFFFFFF.toInt(), false).blend)
        assertEquals(HostFrameBlend.Screen, hostFrameSpec(WidgetAppearance.Tinted, 0xFF000000.toInt(), false).blend)
    }

    @Test fun `dominant wallpaper colour is the heaviest saturated bin`() {
        val sky = 0xFF3A78C2.toInt(); val sand = 0xFFD8CEB6.toInt(); val grey = 0xFF808080.toInt()
        val pixels = IntArray(100) { index -> when { index < 40 -> sky; index < 70 -> sand; else -> grey } }
        assertEquals(sky, dominantColorArgb(pixels))
        // Saturation weighting: 45 grey pixels lose to 40 blue ones, 60 do not.
        assertEquals(sky, dominantColorArgb(IntArray(85) { if (it < 40) sky else grey }))
        assertEquals(grey, dominantColorArgb(IntArray(200) { if (it < 40) sky else grey }))
        assertNull(dominantColorArgb(IntArray(0)))
        assertNull(dominantColorArgb(IntArray(5) { 0x10FFFFFF }))
        assertNotNull(dominantColorArgb(IntArray(1) { 0xFF000000.toInt() }))
    }

    // --- persistence ----------------------------------------------------------------

    @Test fun `saved-state fields round-trip and are optional`() {
        val tinted = WidgetPlacement(3, CALENDAR_WIDGET, 0, 0, 0, 4, 2, WidgetAppearance.Tinted, yellow)
        val json = JSONObject().put("slot", 3).putWidgetAppearance(tinted)
        assertEquals("Tinted", json.getString("appearance"))
        assertEquals(yellow, json.getInt("tint"))
        val back = JSONObject(json.toString())
        assertEquals(WidgetAppearance.Tinted, back.widgetAppearance())
        assertEquals(yellow, back.widgetTint())
        val glass = JSONObject().putWidgetAppearance(tinted.copy(appearance = WidgetAppearance.Glass, tint = null))
        assertFalse(glass.has("tint"))
        assertEquals(WidgetAppearance.Glass, glass.widgetAppearance()); assertNull(glass.widgetTint())
        val host = WidgetPlacement(4, 41, -1, 0, 0, 4, 2, WidgetAppearance.Glass, blue)
        val hostJson = JSONObject(JSONObject().put("slot", 4).putWidgetAppearance(host).toString())
        assertEquals(WidgetAppearance.Glass, hostJson.widgetAppearance()); assertEquals(blue, hostJson.widgetTint())
        val legacy = JSONObject("""{"slot":3,"id":-7}""")
        assertEquals(WidgetAppearance.Auto, legacy.widgetAppearance()); assertNull(legacy.widgetTint())
        val odd = JSONObject("""{"appearance":"Neon","tint":null}""")
        assertEquals(WidgetAppearance.Auto, odd.widgetAppearance()); assertNull(odd.widgetTint())
    }

    @Test fun `layout backup carries appearance and tint and reads older files as Auto`() {
        val placements = listOf(
            WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2, WidgetAppearance.Glass),
            WidgetPlacement(1, BATTERIES_WIDGET, 0, 2, 0, 2, 2, WidgetAppearance.Tinted, blue),
            WidgetPlacement(2, CALENDAR_WIDGET, 1, 0, 0, 4, 2),
        )
        val state = LauncherState(widgetPlacements = placements, loading = false)
        val raw = encodeLayoutBackup(state, emptyList(), "scope-a")
        val preview = decodeLayoutBackup(raw, emptyList(), emptyList(), "scope-a")
        assertEquals(placements, preview.layout.widgetPlacements)
        assertEquals(LAYOUT_BACKUP_VERSION, JSONObject(raw).getInt("version"))
        // A file written before the fields existed decodes with Auto and no tint.
        val root = JSONObject(raw)
        val widgets = root.getJSONArray("widgets")
        repeat(widgets.length()) { widgets.getJSONObject(it).remove("appearance"); widgets.getJSONObject(it).remove("tint") }
        val older = decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a")
        assertEquals(placements.map { it.copy(appearance = WidgetAppearance.Auto, tint = null) }, older.layout.widgetPlacements)
        // A tint that is not an integer is rejected like any other malformed field.
        widgets.getJSONObject(1).put("tint", "blue")
        assertTrue(runCatching { decodeLayoutBackup(root.toString(), emptyList(), emptyList(), "scope-a") }.isFailure)
    }

    // --- Wallpaper (B29 "Barvy z tapety") ---------------------------------------------------

    @Test fun `wallpaper appearance behaves like tinted with the caller-supplied accent`() {
        val accent = 0xFF16324A.toInt() // dark; caller resolves this from WallpaperPalette, not a preset
        val wallpaper = widgetPalette(WidgetAppearance.Wallpaper, accent, systemDark = true)
        val tinted = widgetPalette(WidgetAppearance.Tinted, accent, systemDark = true)
        assertEquals(tinted, wallpaper)
        assertTrue(wallpaper.monochrome)
        assertFalse(wallpaper.darkInk) // dark accent -> white ink, same rule as Tinted
    }

    @Test fun `wallpaper appearance with no tint falls back to the fixed wallpaper accent, not the first preset`() {
        val wallpaper = widgetPalette(WidgetAppearance.Wallpaper, null, systemDark = false)
        val fallbackTinted = widgetPalette(WidgetAppearance.Tinted, WallpaperPalette.FALLBACK_ACCENT, systemDark = false)
        assertEquals(fallbackTinted, wallpaper)
        assertNotEquals(widgetPalette(WidgetAppearance.Tinted, null, systemDark = false), wallpaper)
    }

    @Test fun `wallpaper host frame behaves like tinted with the caller-supplied accent`() {
        val accent = WIDGET_TINT_PRESETS[2].first // pink, bright -> Multiply blend
        assertEquals(hostFrameSpec(WidgetAppearance.Tinted, accent, systemDark = true),
            hostFrameSpec(WidgetAppearance.Wallpaper, accent, systemDark = true))
    }

    @Test fun `parseWidgetAppearance round-trips Wallpaper like every other value`() {
        WidgetAppearance.entries.forEach { assertEquals(it, parseWidgetAppearance(it.name)) }
    }
}
