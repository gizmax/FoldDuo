package cz.pflanzer.foldduo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.pflanzer.foldduo.cameraisland.CameraIslandDebugInjector

/**
 * Debug-only, manifest-declared (so it works without Home in front): injects fake [cz.pflanzer.foldduo.island.IslandItem]s
 * into the camera island (`cameraisland/CameraIsland.kt`'s [CameraIslandDebugInjector]) so it can
 * be screenshotted without a real Uber ride, timer or call.
 *
 *   adb shell am broadcast -p cz.pflanzer.foldduo -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind timer
 *   adb shell am broadcast -p cz.pflanzer.foldduo -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind uber --ei count 2
 *   adb shell am broadcast -p cz.pflanzer.foldduo -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind media
 *   adb shell am broadcast -p cz.pflanzer.foldduo -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind call
 *   adb shell am broadcast -p cz.pflanzer.foldduo -a cz.pflanzer.foldduo.DEBUG_ISLAND --es kind none
 *
 * Look for tag FoldDuoIsland in logcat: every injection and every state/rect change the overlay
 * plays afterwards.
 */
class CameraIslandDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind")
        val count = intent.getIntExtra("count", 1)
        CameraIslandDebugInjector.inject(kind, count, intent.getBooleanExtra("expand", false))
    }
}
