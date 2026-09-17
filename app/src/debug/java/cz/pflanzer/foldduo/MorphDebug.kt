package cz.pflanzer.foldduo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cz.pflanzer.foldduo.systemfrost.DeviceStateOverride

/**
 * Debug-only replay of the Continuum A morph, since nobody at the Mac can fold the phone:
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH                  # phase 2: inner left half clears (needs the inner panel)
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ei duration 3000 # stretched, for screenshots
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ez cover true  # phase 1: cover frost, held 1.2 s then released (needs the cover)
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ez close true  # phase 0: inner left half frosts and slides out, held 1 s then released (needs the inner panel)
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ez settle true # cover settle fade after a fold
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ef angle 120  # angle mode preview: fake hinge angle for 3 s (inner: 120° = ~29° tilt; cover: 15..90°)
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ez opened true       # spike: request device state OPENED (inner panel on while at TENT), see DeviceStateOverride
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ez openedCancel true # spike: cancel that request
 *
 * The release source set supplies a no-op.
 */
internal object MorphDebug {
    private const val ACTION = "cz.pflanzer.foldduo.DEBUG_MORPH"
    private const val TAG = "FoldDuoMorph"

    fun attach(activity: MainActivity, morph: MorphController) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cover = intent?.getBooleanExtra("cover", false) == true
                val close = intent?.getBooleanExtra("close", false) == true
                val settle = intent?.getBooleanExtra("settle", false) == true
                val duration = intent?.getIntExtra("duration", MorphCurve.DURATION_MS) ?: MorphCurve.DURATION_MS
                val angle = intent?.getFloatExtra("angle", Float.NaN) ?: Float.NaN
                val opened = intent?.getBooleanExtra("opened", false) == true
                val openedCancel = intent?.getBooleanExtra("openedCancel", false) == true
                val angleHold = intent?.getLongExtra("angleHold", MorphCurve.ANGLE_DEBUG_HOLD_MS) ?: MorphCurve.ANGLE_DEBUG_HOLD_MS
                Log.i(TAG, "debug replay requested (cover=$cover, close=$close, settle=$settle, angle=$angle, duration=$duration ms, warmedUp=${morph.warmedUp})")
                when {
                    opened -> DeviceStateOverride.request(activity.applicationContext)
                    openedCancel -> DeviceStateOverride.cancel(activity.applicationContext)
                    !angle.isNaN() -> morph.debugAngle(angle, angleHold)
                    cover -> morph.requestCoverFrost("debug")
                    close -> morph.requestCloseFrost("debug")
                    settle -> morph.requestCoverSettle()
                    else -> morph.requestEnter(duration)
                }
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { activity.unregisterReceiver(receiver) }
        })
    }
}
