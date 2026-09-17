package cz.pflanzer.foldduo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Debug-only icon style switch, so the style can be set without tapping through settings:
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ICON_STYLE --es shape squircle --es effect glass
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ICON_STYLE --es shape roundedsquare --es effect none
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ICON_STYLE --es pack com.example.pack   # "none" = system icons
 *
 * Extras left out keep their current value. The release source set supplies a no-op.
 *
 * The glass backing-removal dump is [IconDumpReceiver].
 */
internal object IconStyleDebug {
    private const val ACTION = "cz.pflanzer.foldduo.DEBUG_ICON_STYLE"
    private const val TAG = "FoldDuoIcons"

    fun attach(activity: MainActivity, model: LauncherModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                fun key(value: String) = value.lowercase().replace("_", "").replace("-", "")
                val current = model.state.value.iconStyle
                val shape = intent?.getStringExtra("shape")?.let { raw -> IconShape.entries.firstOrNull { key(it.name) == key(raw) } }
                val effect = intent?.getStringExtra("effect")?.let { raw ->
                    IconEffect.entries.firstOrNull { key(it.name) == key(raw) || key(it.title) == key(raw) } }
                val pack = intent?.getStringExtra("pack")
                val next = current.copy(shape = shape ?: current.shape, effect = effect ?: current.effect,
                    pack = if (pack == null) current.pack else pack.takeUnless { it.isBlank() || it == "none" })
                Log.i(TAG, "debug icon style $current -> $next")
                model.setIconStyle(next)
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { activity.unregisterReceiver(receiver) }
        })
    }
}
