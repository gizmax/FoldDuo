package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPacksTest {
    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <!-- Fallback treatment -->
            <iconback img1="iconback_a" img2='iconback_b' />
            <iconmask img1="iconmask" />
            <iconupon img1="iconupon" />
            <scale factor="0.8" />
            <item component="ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" drawable="chrome" />
            <item component="ComponentInfo{com.spotify.music/.MainActivity}" drawable="spotify"/>
            <item component="ComponentInfo{com.spotify.music/com.spotify.music.Other}" drawable="spotify_alt"/>
            <!-- <item component="ComponentInfo{com.hidden/.Main}" drawable="hidden" /> -->
            <item component=":LAUNCHER_ACTION_APP_DRAWER" drawable="drawer" />
            <item component="ComponentInfo{com.a&amp;b/.Main}" drawable="amp" />
        </resources>
    """.trimIndent()

    private val filter = parseAppFilter(appFilterElements(sample))

    @Test fun parsesComponentsAndExpandsShortClassNames() {
        assertEquals("chrome", filter.drawableFor("com.android.chrome", "com.google.android.apps.chrome.Main"))
        assertEquals("spotify", filter.drawableFor("com.spotify.music", "com.spotify.music.MainActivity"))
        assertEquals("spotify_alt", filter.drawableFor("com.spotify.music", "com.spotify.music.Other"))
        assertEquals("amp", filter.drawableFor("com.a&b", "com.a&b.Main"))
    }

    @Test fun fallsBackToFirstMappingOfThePackage() {
        assertEquals("spotify", filter.drawableFor("com.spotify.music", "com.spotify.music.Unknown"))
        assertNull(filter.drawableFor("com.unknown", "com.unknown.Main"))
    }

    @Test fun skipsCommentsAndPlaceholders() {
        assertNull(filter.drawableFor("com.hidden", "com.hidden.Main"))
        assertEquals(4, filter.components.size)
    }

    @Test fun readsTreatmentForUncoveredApps() {
        assertEquals(listOf("iconback_a", "iconback_b"), filter.iconBacks)
        assertEquals("iconmask", filter.iconMask)
        assertEquals("iconupon", filter.iconUpon)
        assertEquals(.8f, filter.scale, 1e-6f)
        assertTrue(filter.hasTreatment)
        val back = filter.iconBackFor("com.unknown", "com.unknown.Main")
        assertTrue(back in filter.iconBacks)
        assertEquals(back, filter.iconBackFor("com.unknown", "com.unknown.Main"))
    }

    @Test fun minimalPackHasNoTreatment() {
        val plain = parseAppFilter(appFilterElements("""<resources><item component="ComponentInfo{a.b/a.b.C}" drawable="c"/><scale factor="nope"/></resources>"""))
        assertFalse(plain.hasTreatment)
        assertEquals(1f, plain.scale, 0f)
        assertNull(plain.iconBackFor("a.b", "a.b.C"))
    }

    @Test fun normalizesComponentStrings() {
        assertEquals("a.b/a.b.C", normalizeAppFilterComponent("ComponentInfo{a.b/.C}"))
        assertEquals("a.b/x.Y", normalizeAppFilterComponent(" ComponentInfo{a.b/x.Y} "))
        assertNull(normalizeAppFilterComponent("ComponentInfo{}"))
        assertNull(normalizeAppFilterComponent(":BROWSER"))
    }

    @Test fun elementEventsFromAnySourceUseTheSameBuilder() {
        val events = sequenceOf("item" to mapOf("component" to "ComponentInfo{p/.A}", "drawable" to "a"),
            "iconmask" to mapOf("img1" to "m"))
        val parsed = parseAppFilter(events)
        assertEquals("a", parsed.drawableFor("p", "p.A"))
        assertEquals("m", parsed.iconMask)
    }
}
