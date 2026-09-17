package cz.pflanzer.foldduo.status

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Everything the status card's four tiles show. Collected only while the card is open
 * ([rememberStatusCardState]'s `open` flag): the persistent [cz.pflanzer.foldduo.DeviceStatus]
 * already covers the rail's own always-on glyph (battery %, Wi-Fi/cellular bars, airplane mode),
 * so this only adds the *extra* detail the card needs on top of that (SSID, link speed, carrier,
 * charge-time estimate, Bluetooth) — nothing here requests a permission the manifest does not
 * already declare; a field that would need one it does not hold stays `null`.
 */
data class StatusCardData(
    val battery: Int? = null,
    val charging: Boolean = false,
    /** [BatteryManager.computeChargeTimeRemaining] (ms); <= 0 means unknown. */
    val chargeTimeRemainingMs: Long = -1L,
    /** The OS's own discharge estimate (ms) when exposed via the sticky battery intent; <= 0 means unknown/unexposed. */
    val dischargeEstimateMs: Long = -1L,
    val wifiConnected: Boolean = false,
    val wifiLevel: Int? = null,
    val wifiSsid: String? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val cellularLevel: Int? = null,
    val carrierName: String? = null,
    /** "5G"/"LTE"/… — only ever set when [Manifest.permission.READ_PHONE_STATE] is already granted. */
    val networkTypeLabel: String? = null,
    val airplane: Boolean = false,
    val bluetoothOn: Boolean = false,
    /** Best-effort: set only when [Manifest.permission.BLUETOOTH_CONNECT] is granted and a classic
     * audio profile (A2DP/HEADSET) reports connected — see the doubt in the task report. */
    val bluetoothDeviceName: String? = null,
)

/** Hidden-but-stable extra key for Android 15's discharge estimate; read as a raw string (not an
 * SDK constant) so this compiles against any compileSdk, the same defensive style
 * [cz.pflanzer.foldduo.MotionPrefs.KEY_ACCESSIBILITY_REDUCE_MOTION] uses for a One UI-only key. */
private const val EXTRA_BATTERY_REMAINING_TIME = "android.os.extra.BATTERY_REMAINING_TIME"

/**
 * Registers battery/Wi-Fi/telephony/Bluetooth listeners only while [open] is true, unregistering
 * the moment it flips back (mirrors [cz.pflanzer.foldduo.DeviceStatusMonitor]'s lifecycle-gated
 * pattern, scoped to the card's own open/closed state instead of the whole app's).
 */
@Composable
fun rememberStatusCardState(open: Boolean): StatusCardData {
    val context = LocalContext.current
    var data by remember { mutableStateOf(StatusCardData()) }
    DisposableEffect(open) {
        if (!open) {
            data = StatusCardData()
            return@DisposableEffect onDispose { }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasBluetoothConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun updateNetwork() {
            val caps = runCatching { connectivity?.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            val wifiInfo = (caps?.transportInfo as? WifiInfo) ?: runCatching { wifiManager?.connectionInfo }.getOrNull()
            val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                wifiInfo?.supplicantState == android.net.wifi.SupplicantState.COMPLETED
            val level = wifiInfo?.rssi?.takeIf { connected && it > -127 }?.let { WifiManager.calculateSignalLevel(it, 5) }
            val ssid = wifiInfo?.ssid?.removeSurrounding("\"")?.takeIf { connected && it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
            val linkSpeed = wifiInfo?.linkSpeed?.takeIf { connected && it > 0 }
            val airplane = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrDefault(false)
            data = data.copy(wifiConnected = connected, wifiLevel = level, wifiSsid = ssid, wifiLinkSpeedMbps = linkSpeed, airplane = airplane)
        }
        fun updateBattery(intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            val chargeTimeRemaining = if (charging) runCatching { batteryManager?.computeChargeTimeRemaining() ?: -1L }.getOrDefault(-1L) else -1L
            val dischargeEstimate = if (!charging && intent.hasExtra(EXTRA_BATTERY_REMAINING_TIME))
                runCatching { intent.getLongExtra(EXTRA_BATTERY_REMAINING_TIME, -1L) }.getOrDefault(-1L) else -1L
            data = data.copy(
                battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null,
                charging = charging, chargeTimeRemainingMs = chargeTimeRemaining, dischargeEstimateMs = dischargeEstimate,
            )
        }
        fun updateBluetooth() {
            if (!hasBluetoothConnect) { data = data.copy(bluetoothOn = false, bluetoothDeviceName = null); return }
            val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            val on = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
            // Best-effort only: BluetoothAdapter has no synchronous "which device" query for the
            // classic audio profiles without a BluetoothProfile.ServiceListener round-trip, so this
            // reads "a connected audio profile exists" and names the first bonded device — right
            // for the common single-paired-device case, not guaranteed for multiple devices.
            val connectedProfile = runCatching {
                adapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                    adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
            val deviceName = if (connectedProfile) runCatching { adapter?.bondedDevices?.firstOrNull()?.name }.getOrNull() else null
            data = data.copy(bluetoothOn = on, bluetoothDeviceName = deviceName)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                    BluetoothAdapter.ACTION_STATE_CHANGED -> updateBluetooth()
                    else -> updateNetwork()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // The sticky ACTION_BATTERY_CHANGED intent registerReceiver(null, ...) would normally
        // return is not available here (this registration already needs a real receiver for the
        // other three actions); ask updateBattery to run once explicitly instead, same as
        // DeviceStatusMonitor's own onStart.
        context.registerReceiver(receiver, filter)
        updateNetwork()
        updateBluetooth()
        run {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) updateBattery(sticky)
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateNetwork()
            override fun onLost(network: Network) = updateNetwork()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateNetwork()
        }
        val networkRegistered = runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback); true }.getOrDefault(false)

        val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                data = data.copy(cellularLevel = signalStrength.level.coerceIn(0, 4))
            }
        }
        val telephonyRegistered = runCatching { telephony?.registerTelephonyCallback(context.mainExecutor, telephonyCallback); true }.getOrDefault(false)
        val carrierName = runCatching { telephony?.networkOperatorName?.takeIf { it.isNotBlank() } }.getOrNull()
        val networkTypeLabel = if (hasPhoneState) runCatching { networkTypeLabel(telephony?.dataNetworkType) }.getOrNull() else null
        data = data.copy(
            cellularLevel = runCatching { telephony?.signalStrength?.level }.getOrNull(),
            carrierName = carrierName, networkTypeLabel = networkTypeLabel,
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            if (telephonyRegistered) runCatching { telephony?.unregisterTelephonyCallback(telephonyCallback) }
        }
    }
    return data
}

private fun networkTypeLabel(networkType: Int?): String? = when (networkType) {
    TelephonyManager.NETWORK_TYPE_NR -> "5G"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
    TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
    else -> null
}
