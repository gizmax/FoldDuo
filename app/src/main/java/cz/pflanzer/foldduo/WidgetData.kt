package cz.pflanzer.foldduo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/*
 * Android data behind the built-in widgets (AppleWidgets.kt): runtime permissions requested on
 * tap, calendar instances, battery levels and the latest photo. Everything permission-gated
 * degrades to "phone only" / a placeholder rather than failing.
 */

/** A runtime permission observed from Compose: [granted] follows the result and every resume. */
@Stable
class PermissionRequest(val permission: String, initial: Boolean, private val launch: () -> Unit) {
    var granted by mutableStateOf(initial)
        internal set
    fun request() { if (!granted) launch() }
}

fun hasPermission(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberPermissionRequest(permission: String, onGranted: () -> Unit = {}): PermissionRequest {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val request = remember(permission) {
        PermissionRequest(permission, hasPermission(context, permission)) { launcher.launch(permission) }
    }
    // The result callback above may fire before the activity is resumed; re-checking on every
    // resume also picks up grants made from Settings.
    LifecycleResumeEffect(permission) {
        val now = hasPermission(context, permission)
        if (now && !request.granted) onGranted()
        request.granted = now
        onPauseOrDispose { }
    }
    return request
}

/** Photos: full access, or the Android 14 "selected photos" partial grant. */
fun hasPhotoAccess(context: Context) = hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
    (Build.VERSION.SDK_INT >= 34 && hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

// --- Calendar ---------------------------------------------------------------------------

private val INSTANCE_PROJECTION = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
    CalendarContract.Instances.DISPLAY_COLOR)

/** Instances from the start of today until the end of tomorrow. Requires READ_CALENDAR. */
fun queryCalendarInstances(context: Context, from: Long, to: Long): List<CalendarEvent> = runCatching {
    val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, from); ContentUris.appendId(builder, to)
    context.contentResolver.query(builder.build(), INSTANCE_PROJECTION, null, null,
        "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(CalendarEvent(cursor.getLong(0), cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "(No title)",
                    cursor.getLong(2), cursor.getLong(3), cursor.getInt(4) != 0,
                    if (cursor.isNull(5)) null else cursor.getInt(5)))
            }
        }
    }.orEmpty()
}.getOrDefault(emptyList())

/** The calendar app at [timeMillis] (day view), or its main screen for the event. */
fun openCalendarAt(context: Context, timeMillis: Long, eventId: Long? = null) {
    val uri: Uri = if (eventId != null) ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        else ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), timeMillis).build()
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (eventId != null) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeMillis)
    runCatching { context.startActivity(intent) }
}

/**
 * Today's and tomorrow's instances while [enabled]; refreshed every minute and on every resume,
 * only while the lifecycle is resumed (the loop is cancelled off screen).
 */
@Composable
fun rememberCalendarInstances(enabled: Boolean): State<List<CalendarEvent>?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<List<CalendarEvent>?>(null, enabled) {
        if (!enabled) { value = null; return@produceState }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val zone = java.time.ZoneId.systemDefault()
                val today = java.time.LocalDate.now(zone)
                val from = today.atStartOfDay(zone).toInstant().toEpochMilli() - TimeUnit.DAYS.toMillis(1)
                val to = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                value = withContext(Dispatchers.IO) { queryCalendarInstances(context, from, to) }
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
}

// --- Batteries --------------------------------------------------------------------------

/** The phone's own battery from the sticky ACTION_BATTERY_CHANGED broadcast; live while composed. */
@Composable
fun rememberPhoneBattery(): State<DeviceBattery> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(phoneBattery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { state.value = phoneBattery(intent) }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        state.value = phoneBattery(sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

private fun phoneBattery(intent: Intent?): DeviceBattery {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return DeviceBattery(Build.MODEL.ifBlank { "Phone" }, batteryPercent(level, scale), charging, DeviceKind.PHONE)
}

/**
 * Batteries of connected Bluetooth audio devices and wearables. Needs BLUETOOTH_CONNECT; the
 * level itself comes from the hidden `BluetoothDevice.getBatteryLevel`, so devices whose level
 * cannot be read are left out rather than shown as 0 %.
 */
suspend fun loadBluetoothBatteries(context: Context): List<DeviceBattery> {
    if (!hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) return emptyList()
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    val devices = LinkedHashMap<String, BluetoothDevice>()
    for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
        connectedDevices(context, adapter, profile).forEach { devices.putIfAbsent(it.address, it) }
    }
    // Wearables (watch, earbuds without a classic audio profile) report through the LE ACL link.
    runCatching { adapter.bondedDevices }.getOrNull()?.forEach { device ->
        if (device.address !in devices && device.isConnectedCompat()) devices[device.address] = device
    }
    return devices.values.mapNotNull { device ->
        val level = device.batteryLevelCompat().takeIf { it in 0..100 } ?: return@mapNotNull null
        val name = runCatching { device.alias ?: device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
        val klass = runCatching { device.bluetoothClass }.getOrNull()
        DeviceBattery(name, level, false, deviceKindFor(klass?.majorDeviceClass ?: 0, klass?.deviceClass ?: 0))
    }
}

private suspend fun connectedDevices(context: Context, adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> =
    withTimeoutOrNull(1500L) {
        suspendCancellableCoroutine { continuation ->
            val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val result = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                    runCatching { adapter.closeProfileProxy(p, proxy) }
                    if (continuation.isActive) continuation.resume(result)
                }
                override fun onServiceDisconnected(p: Int) { if (continuation.isActive) continuation.resume(emptyList()) }
            }, profile)
            if (!ok && continuation.isActive) continuation.resume(emptyList())
        }
    }.orEmpty()

private fun BluetoothDevice.batteryLevelCompat(): Int = runCatching {
    BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(this) as Int
}.getOrDefault(-1)

private fun BluetoothDevice.isConnectedCompat(): Boolean = runCatching {
    BluetoothDevice::class.java.getMethod("isConnected").invoke(this) as Boolean
}.getOrDefault(false)

/** Bluetooth batteries while [enabled], refreshed every 30 s while resumed. */
@Composable
fun rememberBluetoothBatteries(enabled: Boolean): State<List<DeviceBattery>> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(emptyList(), enabled) {
        if (!enabled) { value = emptyList(); return@produceState }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = withContext(Dispatchers.IO) { loadBluetoothBatteries(context) }
                delay(30_000L)
            }
        }
    }
}

// --- Photos -----------------------------------------------------------------------------

data class LatestPhoto(val uri: Uri, val takenMillis: Long, val bitmap: Bitmap)

/** One decoded photo per process, keyed by MediaStore id and date; ≤ 1024 px on the long side. */
internal object LatestPhotoCache {
    @Volatile var value: Pair<String, LatestPhoto>? = null
}

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

private fun queryImages(context: Context, selection: String?, args: Array<String>?, limit: Int): List<Pair<Long, Long>> = runCatching {
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
    val bundle = android.os.Bundle().apply {
        putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
        putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        if (selection != null) putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        if (args != null) putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
    }
    context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, bundle, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val taken = cursor.getLong(1).takeIf { it > 0 } ?: cursor.getLong(2) * 1000
                add(cursor.getLong(0) to taken)
            }
        }
    }
}.getOrNull().orEmpty()

/**
 * The photo for the card, iOS "On This Day" first: a picture taken on today's date in one of
 * the last ten years, else a random one from the last 30 days (stable for six hours, so the
 * card changes between days rather than between recompositions), else the most recent image.
 * Null without access or with an empty library. Decoded ≤ 1024 px and cached per process.
 */
fun loadLatestPhoto(context: Context, now: Long = System.currentTimeMillis()): LatestPhoto? {
    if (!hasPhotoAccess(context)) return null
    val zone = java.time.ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val onThisDay = (1..10).asSequence().mapNotNull { yearsAgo ->
        val day = today.minusYears(yearsAgo.toLong())
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        queryImages(context, "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
            arrayOf(from.toString(), to.toString()), 1).firstOrNull()
    }.firstOrNull()
    val candidates = if (onThisDay != null) listOf(onThisDay) else queryImages(context, null, null, 60)
    if (candidates.isEmpty()) return null
    val recent = candidates.filter { now - it.second <= THIRTY_DAYS_MS }
    val seed = (now / (6 * 60 * 60 * 1000)).toInt()
    val (id, taken) = if (recent.isEmpty()) candidates.first() else recent[Math.floorMod(seed, recent.size)]
    val key = "$id:$taken"
    LatestPhotoCache.value?.takeIf { it.first == key }?.let { return it.second }
    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    val bitmap = runCatching { context.contentResolver.loadThumbnail(uri, Size(1024, 1024), null) }.getOrNull()
        ?: return null
    return LatestPhoto(uri, taken, bitmap).also { LatestPhotoCache.value = key to it }
}

/** The latest photo while [enabled]; reloaded on every resume so a new picture appears after a grant. */
@Composable
fun rememberLatestPhoto(enabled: Boolean): State<LatestPhoto?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<LatestPhoto?>(LatestPhotoCache.value?.second?.takeIf { enabled }, enabled) {
        if (!enabled) { value = null; return@produceState }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = withContext(Dispatchers.IO) { loadLatestPhoto(context) }
        }
    }
}
