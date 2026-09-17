package cz.pflanzer.foldduo.status

import java.util.Locale

/**
 * Pure label formatting for the status card's battery tile. No Android types (mirrors
 * `relativeTimeLabel` in notifications/HubNotification.kt): [Locale] is passed in rather than
 * read live so this stays a plain JVM unit.
 */

/** True for a Czech-language locale ("cs", "cs-CZ", …), the only alternate wording this file knows. */
private fun Locale.isCzech(): Boolean = language == "cs"

/**
 * "Nabíjí se · 41 min do plné" / "Charging · 41 min left" from
 * `BatteryManager.computeChargeTimeRemaining()` (ms; the API returns a negative value, including
 * -1, when it cannot estimate — treated the same as "unknown" here). `null` means the tile omits
 * the estimate rather than showing a stale or fabricated one.
 */
fun chargeTimeRemainingLabel(chargeTimeRemainingMs: Long, locale: Locale = Locale.getDefault()): String? {
    if (chargeTimeRemainingMs <= 0L) return null
    val totalMinutes = (chargeTimeRemainingMs / 60_000L).toInt()
    if (totalMinutes <= 0) return null
    val czech = locale.isCzech()
    if (totalMinutes < 60) return if (czech) "Nabíjí se · $totalMinutes min do plné" else "Charging · $totalMinutes min left"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        czech && minutes == 0 -> "Nabíjí se · $hours h do plné"
        czech -> "Nabíjí se · $hours h $minutes min do plné"
        minutes == 0 -> "Charging · ${hours}h left"
        else -> "Charging · ${hours}h ${minutes}m left"
    }
}

/**
 * "Vydrží ~6 h" / "About 6h left" from the system's discharge-time estimate (ms), when the OS
 * exposes one; `null` (omit the estimate, per the task spec) for anything <= 0 or unknown.
 */
fun dischargeEstimateLabel(remainingMs: Long, locale: Locale = Locale.getDefault()): String? {
    if (remainingMs <= 0L) return null
    val totalMinutes = (remainingMs / 60_000L).toInt()
    if (totalMinutes <= 0) return null
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val czech = locale.isCzech()
    return when {
        hours >= 1 -> if (czech) "Vydrží ~$hours h" else "About ${hours}h left"
        czech -> "Vydrží ~$minutes min"
        else -> "About $minutes min left"
    }
}

// --- Compact single-line labels for the sideways pill's items --------------------------------
// (2026-09-17 noc, pill reshape): each pill item is one 13sp line, so these are deliberately
// terser than chargeTimeRemainingLabel/dischargeEstimateLabel above (no "Nabíjí se ·" prefix,
// language-neutral "min"/"h" units) — task spec literal examples: "41 min" / "~6 h".

/** "41 min" / "1 h 5 min" from [chargeTimeRemainingMs] — `null` (omit, fall back to "N%") when unknown. */
fun statusPillChargeTimeLabel(chargeTimeRemainingMs: Long): String? {
    if (chargeTimeRemainingMs <= 0L) return null
    val totalMinutes = (chargeTimeRemainingMs / 60_000L).toInt()
    if (totalMinutes <= 0) return null
    if (totalMinutes < 60) return "$totalMinutes min"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (minutes == 0) "$hours h" else "$hours h $minutes min"
}

/** "~6 h" / "~45 min" from the OS discharge estimate — `null` (omit) when unknown. */
fun statusPillDischargeLabel(dischargeEstimateMs: Long): String? {
    if (dischargeEstimateMs <= 0L) return null
    val totalMinutes = (dischargeEstimateMs / 60_000L).toInt()
    if (totalMinutes <= 0) return null
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours >= 1) "~$hours h" else "~$minutes min"
}

/** Wi-Fi item text: SSID (or "Wi-Fi" if the SSID is unknown/hidden) plus link speed when available. */
fun statusPillWifiText(connected: Boolean, ssid: String?, linkSpeedMbps: Int?): String {
    if (!connected) return "Wi-Fi"
    val name = ssid ?: "Wi-Fi"
    return linkSpeedMbps?.let { "$name · $it Mb/s" } ?: name
}

/** Mobile item text: carrier (or "Mobile") plus network type ("5G"/"LTE"/…) when permitted/available. */
fun statusPillMobileText(airplane: Boolean, carrierName: String?, networkTypeLabel: String?): String {
    if (airplane) return "Airplane"
    val name = carrierName ?: "Mobile"
    return networkTypeLabel?.let { "$name · $it" } ?: name
}

/** Battery item text: charge/discharge estimate when known, else the raw percentage, else "—". */
fun statusPillBatteryText(battery: Int?, charging: Boolean, chargeTimeRemainingMs: Long, dischargeEstimateMs: Long): String {
    val estimate = if (charging) statusPillChargeTimeLabel(chargeTimeRemainingMs) else statusPillDischargeLabel(dischargeEstimateMs)
    return estimate ?: (battery?.let { "$it%" } ?: "—")
}

/** Bluetooth item text: paired device name when known and on, else "Bluetooth". */
fun statusPillBluetoothText(on: Boolean, deviceName: String?): String =
    if (!on) "Bluetooth" else deviceName ?: "Bluetooth"
