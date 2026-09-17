package cz.pflanzer.foldduo.standby

import android.appwidget.AppWidgetManager
import android.content.Context
import cz.pflanzer.foldduo.isBuiltinWidgetId
import org.json.JSONObject

/*
 * StandBy v2's Weather card only ever shows when the user has placed a weather-looking host
 * widget on Today — the launcher itself has no weather data source (StandByFaces.kt's long
 * standing comment on that). [WeatherWidgetDetection] is the pure name/package heuristic;
 * [findTodayWeatherWidgetId] is the Android-side lookup, kept read-only and defensive (see doc).
 */

/** Heuristic: does a widget's label or provider package look like a weather app? */
object WeatherWidgetDetection {
    private val KEYWORDS = listOf(
        "weather", "pocasi", "počasí", "accuweather", "1weather", "weatherbug",
        "weawow", "yweather", "climate", "meteo", "forecast",
    )

    fun looksLikeWeather(label: String?, packageName: String?): Boolean {
        val haystack = (label.orEmpty() + " " + packageName.orEmpty()).lowercase()
        return KEYWORDS.any { it in haystack }
    }
}

/**
 * Best-effort, read-only search of Today (page -1) for a weather-looking host widget id.
 * StandByActivity is a separate activity with no [cz.pflanzer.foldduo.LauncherModel] of its own,
 * so this reads that model's own persisted JSON directly (`"launcher"` prefs, `"state"` key,
 * `"widgets"` array — the shape LauncherModel.kt's `persist()`/`load()` write and read) rather
 * than duplicating its loading logic. Entirely wrapped in try/catch: a schema change there just
 * means this quietly returns null (the card stays hidden), never a crash here.
 */
fun findTodayWeatherWidgetId(context: Context): Int? = try {
    val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    val json = prefs.getString("state", null)
    val widgets = json?.let { JSONObject(it).optJSONArray("widgets") }
    if (widgets == null) null else {
        val manager = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        var found: Int? = null
        for (i in 0 until widgets.length()) {
            val entry = widgets.getJSONObject(i)
            if (entry.optInt("page", 0) != -1) continue
            val id = entry.optInt("id", -1)
            if (id < 0 || isBuiltinWidgetId(id)) continue
            val info = manager.getAppWidgetInfo(id) ?: continue
            val label = try { info.loadLabel(pm) } catch (e: Exception) { null }
            if (WeatherWidgetDetection.looksLikeWeather(label, info.provider?.packageName)) { found = id; break }
        }
        found
    }
} catch (e: Exception) { null }
