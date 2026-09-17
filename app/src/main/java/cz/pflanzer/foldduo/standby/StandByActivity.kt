package cz.pflanzer.foldduo.standby

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cz.pflanzer.foldduo.HapticEvent
import cz.pflanzer.foldduo.Haptics
import cz.pflanzer.foldduo.PoseEngine
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseRepository
import cz.pflanzer.foldduo.pose.panel
import kotlinx.coroutines.launch

/**
 * StandBy without a charger (PLAN.md Fáze 4). Android only starts a DreamService while charging
 * or docked and offers no API to start one, so the cover's ambient screen is this activity,
 * launched by [StandByService] from the fold pose (or by the settings "Preview").
 *
 * Window: `showWhenLocked` + `turnScreenOn` in the manifest, keep-screen-on, immersive, and the
 * brightness drops to [DIM_BRIGHTNESS] after [DIM_AFTER_MS] without a touch. Leaves on a double
 * tap or swipe up (Home), when the pose leaves {Tent, Closed} or the inner panel takes over
 * ([StandByRules.shouldExit]). The lock screen is not handled: we simply `finish()`, and if the
 * keyguard is showing it is still there behind us.
 *
 * B32 (STATUS.md "Tent -> StandBy morph"): entering and leaving is a frost morph
 * ([StandByFrostScreen], [StandByMorphController]), not a cut. [EXTRA_START_FROSTED] means the
 * launcher already played its own pre-entry frost (StandByMorphOverlay.kt's
 * `rememberLauncherStandByFrost`, StandByService.kt's `launch`), so this activity starts already
 * opaque and only plays the clear; [overrideActivityTransition] (API 34+) turns off the system's
 * own window animation both ways so nothing competes with our own frost draw.
 *
 * Every path that can end up finishing this activity logs a `FoldDuoStandBy` "exit: ..." line
 * with its [StandByExitReason] (device report, 17. 9. night: the cover went dark — a red night
 * face, then a WindowManager CLOSE transition — with no double tap, no swipe and no pose change
 * in the log, i.e. [onStop] fired on its own and our old code treated *any* such hide as a user
 * dismissal). [onStop] now tells a keyguard reassertion (the phone hasn't moved; not the user's
 * doing) apart from a real interruption, so the former does not pay the gesture-exit debounce.
 */
class StandByActivity : ComponentActivity() {
    private lateinit var pose: PoseRepository
    private lateinit var prefs: StandByPrefs
    private val handler = Handler(Looper.getMainLooper())
    private val dim = Runnable { setBrightness(DIM_BRIGHTNESS) }
    private var preview = false
    private var startedAt = 0L
    private val transition = StandByMorphController()
    private var reduceMotion = false
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val startFrosted = intent.getBooleanExtra(EXTRA_START_FROSTED, false)
        prefs = StandByPrefs(this)
        // Tent StandBy is cover-only (StandByRules.tentResting): if display 0 is the inner panel
        // by the time this starts (the phone kept closing/opening), leave before showing anything.
        if (!preview && runCatching { display.panel() }.getOrDefault(Panel.Unknown) == Panel.Inner) {
            Log.i(TAG, "exit: inner panel at start, StandBy is cover-only")
            finish()
            return
        }
        pose = PoseEngine.get(this)
        reduceMotion = StandByMorph.reduceMotion(readAnimatorDurationScale(this))
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onBackPressedDispatcher.addCallback(this) { exitByGesture(goHome = false, reason = StandByExitReason.GestureBack) }
        observePose()
        setContent {
            val faces = remember { prefs.faces }
            val environment = rememberStandByEnvironment()
            StandByFrostScreen(transition, startFrosted, reduceMotion) {
                // StandBy v2 (17. 9., iPhone-style two stacks) only replaces the Tent (landscape)
                // window; Closed-on-a-table stays the classic single-face pager (portrait) — the
                // same `maxWidth > maxHeight` test ClockFace/CalendarFace already use.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth > maxHeight) {
                        StandByV2Screen(prefs, environment,
                            onDoubleTap = { exitByGesture(goHome = false, reason = StandByExitReason.GestureDoubleTap) },
                            onSwipeUp = { exitByGesture(goHome = true, reason = StandByExitReason.GestureSwipeUp) },
                            onDimStateChanged = { dim -> onNightDimStateChanged(dim) })
                    } else {
                        StandByScreen(faces, environment,
                            onDoubleTap = { exitByGesture(goHome = false, reason = StandByExitReason.GestureDoubleTap) },
                            onSwipeUp = { exitByGesture(goHome = true, reason = StandByExitReason.GestureSwipeUp) })
                    }
                }
            }
        }
    }

    /**
     * Pose exits. The first [START_GRACE_MS] after start are ignored: a freshly started engine
     * reports `stillMs = 0`, which would read as a pick-up before the first real sample.
     */
    private fun observePose() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pose.snapshot.collect { snap ->
                    if (SystemClock.elapsedRealtime() - startedAt < START_GRACE_MS) return@collect
                    if (StandByRules.shouldExit(snap.pose, snap.panel, snap.stillMs, preview)) {
                        Log.i(TAG, "exit: reason=${StandByExitReason.PoseOrPanel} pose=${snap.pose} panel=${snap.panel} still=${snap.stillMs}ms")
                        leaveWithFrost()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startedAt = SystemClock.elapsedRealtime()
        StandBySession.showing.value = true
        Haptics.play(this, HapticEvent.STANDBY_ENTER)
        PoseEngine.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        scheduleDim()
    }

    override fun onPause() {
        handler.removeCallbacks(dim)
        super.onPause()
    }

    /**
     * Hidden without our own `finish()`: the user left (Home gesture, power button), something
     * came over us (alarm, call, another app), or — the 17. 9. night device report — the keyguard
     * reasserted itself on its own timer while StandBy sat untouched in Tent. Only the first two
     * are a real user dismissal: [StandByExitReason.countsAsGestureExit] gates whether the service
     * pays [StandByRules.RELAUNCH_ANYWAY_MS] worth of blackout, or is free to relaunch as soon as
     * pose says so again (typically well under a second, [cz.pflanzer.foldduo.standby.StandByService]'s
     * `EVALUATE_EVERY_MS`).
     */
    override fun onStop() {
        if (!isFinishing && !isChangingConfigurations) {
            val keyguardLocked = runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull() ?: false
            val reason = hiddenExitReason(keyguardLocked)
            Log.i(TAG, "exit: reason=$reason keyguardLocked=$keyguardLocked")
            if (reason.countsAsGestureExit) StandBySession.noteGestureExit()
            finish()
        }
        PoseEngine.release()
        super.onStop()
    }

    override fun onDestroy() {
        StandBySession.showing.value = false
        Haptics.play(this, HapticEvent.STANDBY_LEAVE)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            scheduleDim()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dim)
        handler.postDelayed(dim, DIM_AFTER_MS)
    }

    private fun setBrightness(value: Float) {
        if (window.attributes.screenBrightness == value) return
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    /**
     * StandBy v2's night mode (StandByStacksUi.kt's `StandByV2Screen`) has no window access of
     * its own, so it reports its dim/bright state up here: dimmed drops well below the ordinary
     * [DIM_BRIGHTNESS] but never to 0 (a `screenBrightness` override of exactly 0 can blank the
     * panel entirely on some OEM stacks, which would defeat "never below the OS minimum"); bright
     * again just resumes the ordinary touch-driven dim-after-30s cycle.
     */
    private fun onNightDimStateChanged(dimmed: Boolean) {
        Log.i(TAG, "night dim=$dimmed")
        if (dimmed) { handler.removeCallbacks(dim); setBrightness(NIGHT_DIM_BRIGHTNESS) }
        else { setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE); scheduleDim() }
    }

    /** User dismissal: remembered for the service's relaunch debounce. Swipe up also goes Home. */
    private fun exitByGesture(goHome: Boolean, reason: StandByExitReason) {
        Log.i(TAG, "exit: reason=$reason")
        StandBySession.noteGestureExit()
        leaveWithFrost(goHome)
    }

    /**
     * B32: the face frosts up over [StandByMorph.leaveFrostDurationMs] (or the reduce-motion
     * crossfade), then this finishes — going Home first is deferred behind it so the frost is
     * what plays, not the system's own task switch. [leaving] guards re-entrancy: a still-showing
     * pose can keep reporting [StandByRules.shouldExit] while the frost plays.
     */
    private fun leaveWithFrost(goHome: Boolean = false) {
        if (leaving || isFinishing) return
        leaving = true
        // AndroidUiDispatcher carries the MonotonicFrameClock Animatable needs; a bare
        // lifecycleScope launch crashed with "A MonotonicFrameClock is not available" (17. 9. 20:28).
        lifecycleScope.launch(AndroidUiDispatcher.Main) {
            transition.playLeave(reduceMotion)
            if (goHome) runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "FoldDuoStandBy"
        const val EXTRA_PREVIEW = "cz.pflanzer.foldduo.standby.PREVIEW"
        /** B32: the launcher already played its own pre-entry frost; start already opaque and only play the clear. */
        const val EXTRA_START_FROSTED = "cz.pflanzer.foldduo.standby.START_FROSTED"
        const val DIM_AFTER_MS = 30_000L
        const val DIM_BRIGHTNESS = 0.35f
        /** StandBy v2 night mode's floor: dim but never literally 0 (see [onNightDimStateChanged]). */
        const val NIGHT_DIM_BRIGHTNESS = 0.02f
        private const val START_GRACE_MS = 1_500L

        fun intent(context: Context, preview: Boolean = false, startFrosted: Boolean = false): Intent =
            Intent(context, StandByActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PREVIEW, preview)
                .putExtra(EXTRA_START_FROSTED, startFrosted)
    }
}
