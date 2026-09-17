package cz.pflanzer.foldduo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * B43 "Výkon jako feature": dumps [PerfHistory] to logcat on request, since nobody at the Mac can
 * read the phone's own logcat live while testing:
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_PERF                # last 20 frame-stats episodes + startup numbers
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_PERF --ei n 5       # only the last 5 episodes
 *
 * Everything is logged under the `FoldDuoPerf` tag — `adb logcat -s FoldDuoPerf` after sending
 * the broadcast (or just while the app runs: startup and every morph/systemfrost episode already
 * log there as they happen, this broadcast only re-prints the retained history on demand).
 *
 * The release source set supplies a no-op.
 */
internal object DebugPerf {
    private const val ACTION = "cz.pflanzer.foldduo.DEBUG_PERF"

    fun attach(activity: MainActivity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                PerfHistory.logSummary(intent?.getIntExtra("n", 20) ?: 20)
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { activity.unregisterReceiver(receiver) }
        })
    }
}
