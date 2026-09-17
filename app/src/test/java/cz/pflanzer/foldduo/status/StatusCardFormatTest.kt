package cz.pflanzer.foldduo.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Battery-tile label formatting (status/StatusCardFormat.kt): charge-time-remaining and
 * discharge-estimate wording in Czech and English, minutes vs. hours+minutes. */
class StatusCardFormatTest {

    @Test fun `charge time under an hour is minutes only, Czech`() {
        assertEquals("Nabíjí se · 41 min do plné", chargeTimeRemainingLabel(41 * 60_000L, Locale("cs")))
    }

    @Test fun `charge time under an hour is minutes only, English`() {
        assertEquals("Charging · 41 min left", chargeTimeRemainingLabel(41 * 60_000L, Locale.US))
    }

    @Test fun `charge time an hour or more splits into hours and minutes, Czech`() {
        assertEquals("Nabíjí se · 1 h 5 min do plné", chargeTimeRemainingLabel(65 * 60_000L, Locale("cs")))
    }

    @Test fun `charge time an hour or more splits into hours and minutes, English`() {
        assertEquals("Charging · 1h 5m left", chargeTimeRemainingLabel(65 * 60_000L, Locale.US))
    }

    @Test fun `charge time on an exact hour omits the zero minutes remainder`() {
        assertEquals("Nabíjí se · 2 h do plné", chargeTimeRemainingLabel(120 * 60_000L, Locale("cs")))
        assertEquals("Charging · 2h left", chargeTimeRemainingLabel(120 * 60_000L, Locale.US))
    }

    @Test fun `charge time zero, negative or under a minute is unknown, omitted`() {
        assertNull(chargeTimeRemainingLabel(0L, Locale.US))
        assertNull(chargeTimeRemainingLabel(-1L, Locale.US))
        assertNull(chargeTimeRemainingLabel(30_000L, Locale.US))
    }

    @Test fun `discharge estimate under an hour, Czech and English`() {
        assertEquals("Vydrží ~45 min", dischargeEstimateLabel(45 * 60_000L, Locale("cs")))
        assertEquals("About 45 min left", dischargeEstimateLabel(45 * 60_000L, Locale.US))
    }

    @Test fun `discharge estimate an hour or more rounds down to whole hours, Czech and English`() {
        assertEquals("Vydrží ~6 h", dischargeEstimateLabel(6 * 60 * 60_000L + 40 * 60_000L, Locale("cs")))
        assertEquals("About 6h left", dischargeEstimateLabel(6 * 60 * 60_000L + 40 * 60_000L, Locale.US))
    }

    @Test fun `discharge estimate zero, negative or under a minute is omitted`() {
        assertNull(dischargeEstimateLabel(0L, Locale.US))
        assertNull(dischargeEstimateLabel(-5L, Locale.US))
        assertNull(dischargeEstimateLabel(59_000L, Locale.US))
    }

    @Test fun `a regional Czech locale (cs-CZ) still reads as Czech`() {
        assertEquals("Nabíjí se · 10 min do plné", chargeTimeRemainingLabel(10 * 60_000L, Locale("cs", "CZ")))
    }

    // --- Compact pill item labels (2026-09-17 noc, pill reshape) ------------------------------

    @Test fun `pill charge time label is terse, no prefix, minutes only under an hour`() {
        assertEquals("41 min", statusPillChargeTimeLabel(41 * 60_000L))
    }

    @Test fun `pill charge time label splits hours and minutes, omitting a zero remainder`() {
        assertEquals("1 h 5 min", statusPillChargeTimeLabel(65 * 60_000L))
        assertEquals("2 h", statusPillChargeTimeLabel(120 * 60_000L))
    }

    @Test fun `pill charge time label is null for zero, negative or under a minute`() {
        assertNull(statusPillChargeTimeLabel(0L))
        assertNull(statusPillChargeTimeLabel(-1L))
        assertNull(statusPillChargeTimeLabel(30_000L))
    }

    @Test fun `pill discharge label is approximate minutes under an hour, hours at or above`() {
        assertEquals("~45 min", statusPillDischargeLabel(45 * 60_000L))
        assertEquals("~6 h", statusPillDischargeLabel(6 * 60 * 60_000L + 40 * 60_000L))
    }

    @Test fun `pill discharge label is null for zero, negative or under a minute`() {
        assertNull(statusPillDischargeLabel(0L))
        assertNull(statusPillDischargeLabel(-5L))
        assertNull(statusPillDischargeLabel(59_000L))
    }

    @Test fun `wifi item text falls back to Wi-Fi when disconnected, ignores ssid and speed`() {
        assertEquals("Wi-Fi", statusPillWifiText(connected = false, ssid = "Home", linkSpeedMbps = 300))
    }

    @Test fun `wifi item text shows ssid, and appends link speed when known`() {
        assertEquals("Home", statusPillWifiText(connected = true, ssid = "Home", linkSpeedMbps = null))
        assertEquals("Home · 300 Mb/s", statusPillWifiText(connected = true, ssid = "Home", linkSpeedMbps = 300))
        assertEquals("Wi-Fi", statusPillWifiText(connected = true, ssid = null, linkSpeedMbps = null))
    }

    @Test fun `mobile item text is Airplane when in airplane mode, regardless of carrier`() {
        assertEquals("Airplane", statusPillMobileText(airplane = true, carrierName = "Vodafone", networkTypeLabel = "5G"))
    }

    @Test fun `mobile item text shows carrier, and appends network type when known`() {
        assertEquals("Vodafone", statusPillMobileText(airplane = false, carrierName = "Vodafone", networkTypeLabel = null))
        assertEquals("Vodafone · 5G", statusPillMobileText(airplane = false, carrierName = "Vodafone", networkTypeLabel = "5G"))
        assertEquals("Mobile", statusPillMobileText(airplane = false, carrierName = null, networkTypeLabel = null))
    }

    @Test fun `battery item text prefers the estimate, then percentage, then a dash`() {
        assertEquals("41 min", statusPillBatteryText(battery = 55, charging = true, chargeTimeRemainingMs = 41 * 60_000L, dischargeEstimateMs = -1L))
        assertEquals("~6 h", statusPillBatteryText(battery = 80, charging = false, chargeTimeRemainingMs = -1L, dischargeEstimateMs = 6 * 60 * 60_000L))
        assertEquals("55%", statusPillBatteryText(battery = 55, charging = true, chargeTimeRemainingMs = -1L, dischargeEstimateMs = -1L))
        assertEquals("—", statusPillBatteryText(battery = null, charging = false, chargeTimeRemainingMs = -1L, dischargeEstimateMs = -1L))
    }

    @Test fun `bluetooth item text is Bluetooth when off or the device is unknown, else the device name`() {
        assertEquals("Bluetooth", statusPillBluetoothText(on = false, deviceName = "Buds"))
        assertEquals("Bluetooth", statusPillBluetoothText(on = true, deviceName = null))
        assertEquals("Buds", statusPillBluetoothText(on = true, deviceName = "Buds"))
    }
}
