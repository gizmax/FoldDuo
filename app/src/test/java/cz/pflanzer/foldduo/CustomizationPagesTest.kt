package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 17. 9. reorg ("uprav i nastavení launcheru, kde půjdou nastavit a ladit naše novinky"): pure
 * checks on [CUSTOMIZATION_OVERVIEW_ORDER] and [customizationPageTitle], the only two bits of the
 * new page split that are plain Kotlin (no Compose/Android), so they are the ones the task's
 * "pure test for any new page-order/enum mapping helper" note is about.
 */
class CustomizationPagesTest {
    @Test fun `overview order lists every page except OVERVIEW, exactly once`() {
        val expected = CustomizationPage.entries.filter { it != CustomizationPage.OVERVIEW }.toSet()
        assertEquals(expected, CUSTOMIZATION_OVERVIEW_ORDER.toSet())
        assertEquals(CUSTOMIZATION_OVERVIEW_ORDER.size, CUSTOMIZATION_OVERVIEW_ORDER.toSet().size)
    }

    @Test fun `overview order matches the 17-9 reorg task's tile order`() {
        assertEquals(
            listOf(
                CustomizationPage.WALLPAPER, CustomizationPage.HOME, CustomizationPage.CONTINUUM, CustomizationPage.TODAY,
                CustomizationPage.COLOURS_GLASS, CustomizationPage.MOTION_HAPTICS, CustomizationPage.PAGES_MODES,
                CustomizationPage.GESTURES, CustomizationPage.STANDBY, CustomizationPage.BACKUP,
                CustomizationPage.DIAGNOSTICS, CustomizationPage.HELP, CustomizationPage.ABOUT,
            ),
            CUSTOMIZATION_OVERVIEW_ORDER,
        )
    }

    @Test fun `every destination page has a non-blank title`() {
        CUSTOMIZATION_OVERVIEW_ORDER.forEach { page -> assertTrue(page.name, customizationPageTitle(page).isNotBlank()) }
    }

    @Test fun `new pages keep the sheet's English tone, no translated labels`() {
        assertEquals("Continuum", customizationPageTitle(CustomizationPage.CONTINUUM))
        assertEquals("Today", customizationPageTitle(CustomizationPage.TODAY))
        assertEquals("Colours & glass", customizationPageTitle(CustomizationPage.COLOURS_GLASS))
        assertEquals("Motion & haptics", customizationPageTitle(CustomizationPage.MOTION_HAPTICS))
        assertEquals("Pages & modes", customizationPageTitle(CustomizationPage.PAGES_MODES))
        assertEquals("Diagnostics", customizationPageTitle(CustomizationPage.DIAGNOSTICS))
    }
}
