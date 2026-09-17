package cz.pflanzer.foldduo.standby

import java.time.ZoneId

/*
 * StandBy v2's World clock card: two cities picked from prefs (StandByPrefs.worldClockZoneA/B),
 * a curated preset list (no geocoding/search — a simple picker in StandBySettings) rather than
 * the full ~600-zone IANA list.
 */

data class WorldCity(val label: String, val zoneId: String)

object WorldClockCities {
    val PRESETS: List<WorldCity> = listOf(
        WorldCity("London", "Europe/London"),
        WorldCity("Paris", "Europe/Paris"),
        WorldCity("Prague", "Europe/Prague"),
        WorldCity("New York", "America/New_York"),
        WorldCity("Los Angeles", "America/Los_Angeles"),
        WorldCity("São Paulo", "America/Sao_Paulo"),
        WorldCity("Tokyo", "Asia/Tokyo"),
        WorldCity("Singapore", "Asia/Singapore"),
        WorldCity("Dubai", "Asia/Dubai"),
        WorldCity("Sydney", "Australia/Sydney"),
    )

    fun byZoneId(zoneId: String?): WorldCity? = zoneId?.let { id -> PRESETS.firstOrNull { it.zoneId == id } }

    /** A recognised, non-blank IANA zone id; guards a corrupt/edited pref from crashing the card at render. */
    fun isValidZone(zoneId: String?): Boolean {
        if (zoneId.isNullOrBlank()) return false
        return runCatching { ZoneId.of(zoneId) }.isSuccess
    }
}
