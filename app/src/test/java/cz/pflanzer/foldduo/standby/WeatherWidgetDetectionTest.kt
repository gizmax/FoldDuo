package cz.pflanzer.foldduo.standby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherWidgetDetectionTest {
    @Test fun matchesByLabel() {
        assertTrue(WeatherWidgetDetection.looksLikeWeather("Weather", "com.example.foo"))
        assertTrue(WeatherWidgetDetection.looksLikeWeather("Počasí", "com.example.foo"))
    }

    @Test fun matchesByPackageName() {
        assertTrue(WeatherWidgetDetection.looksLikeWeather("Widget", "com.accuweather.android"))
        assertTrue(WeatherWidgetDetection.looksLikeWeather(null, "com.samsung.android.weather"))
    }

    @Test fun matchIsCaseInsensitive() {
        assertTrue(WeatherWidgetDetection.looksLikeWeather("WEATHER FORECAST", null))
    }

    @Test fun unrelatedWidgetsDoNotMatch() {
        assertFalse(WeatherWidgetDetection.looksLikeWeather("Calendar", "com.google.android.calendar"))
        assertFalse(WeatherWidgetDetection.looksLikeWeather(null, null))
    }
}
