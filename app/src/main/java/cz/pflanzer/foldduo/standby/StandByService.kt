package cz.pflanzer.foldduo.standby

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import cz.pflanzer.foldduo.MainActivity
import cz.pflanzer.foldduo.PoseEngine
import cz.pflanzer.foldduo.R
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.PoseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pose-driven ambient display: a foreground service (type `specialUse` on API 34+) that holds
 * the Pose Engine and opens [StandByActivity] when [StandByRules.shouldStart] says so.
 *
 * Foreground detection: the launcher is "in front" when our own [MainActivity] is resumed,
 * observed through [Application.ActivityLifecycleCallbacks]. `ActivityManager.getRunningAppProcesses`
 * would give the same answer (on modern Android it only reports our own process, so
 * `IMPORTANCE_FOREGROUND` means exactly "our activity is resumed") but by polling; the callbacks
 * are an event and cost nothing. "Nothing in front" is the keyguard showing or the display off;
 * anything else is presumably another app and is never covered.
 *
 * Starting an activity from a service on Android 10+ needs an exemption: while Home is resumed
 * the app is in the foreground and may start activities; otherwise the user must grant
 * `SYSTEM_ALERT_WINDOW` ("Display over other apps", requested from the StandBy settings). With
 * it granted a 1 x 1 px transparent overlay is attached right before the start, so the launch
 * also satisfies the stricter "visible overlay window" reading of that exemption on Android 14+.
 */
class StandByService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var pose: PoseRepository
    private lateinit var prefs: StandByPrefs
    private lateinit var displayManager: DisplayManager
    private lateinit var keyguard: KeyguardManager
    private var launcherResumed = false
    private var nextLaunchAllowedMs = Long.MIN_VALUE
    private var lastClosingMotionSeq = 0
    private var lastClosingMotionMs = Long.MIN_VALUE
    private var lastLogged: String? = null
    private var anchor: View? = null
    private var evaluatePosted = false
    private val pendingEvaluate = Runnable { evaluatePosted = false; evaluate() }
    private val afterLaunch = Runnable { checkLaunch() }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) { launcherResumed = true; requestEvaluate() }
            if (activity is StandByActivity) removeAnchor()
        }
        override fun onActivityPaused(activity: Activity) {
            if (activity is MainActivity) { launcherResumed = false; requestEvaluate() }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestEvaluate()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) requestEvaluate() }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = StandByPrefs(this)
        displayManager = getSystemService(DisplayManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        startInForeground()
        pose = PoseEngine.acquire(this)
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, handler)
        scope.launch { pose.snapshot.collect { requestEvaluate() } }
        scope.launch { StandBySession.showing.collect { showing -> if (showing) removeAnchor() else requestEvaluate() } }
        running = true
        Log.i(TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !prefs.enabled) { stopSelf(); return START_NOT_STICKY }
        requestEvaluate()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pendingEvaluate)
        handler.removeCallbacks(afterLaunch)
        handler.removeCallbacks(pendingStandByLaunch)
        removeAnchor()
        scope.cancel()
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { unregisterReceiver(screenReceiver) }
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        PoseEngine.release()
        Log.i(TAG, "service stopped")
        super.onDestroy()
    }

    // ---- decision ----

    /** Coalesces the ~50 Hz gravity samples and every other trigger into one [evaluate] per [EVALUATE_EVERY_MS]. */
    private fun requestEvaluate(delayMs: Long = EVALUATE_EVERY_MS) {
        if (evaluatePosted) return
        evaluatePosted = true
        handler.postDelayed(pendingEvaluate, delayMs)
    }

    private fun evaluate() {
        if (!::pose.isInitialized) return
        val snap = pose.snapshot.value
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val displayOn = display?.state == Display.STATE_ON
        val foreground = when {
            launcherResumed -> Foreground.Launcher
            !displayOn || keyguard.isKeyguardLocked -> Foreground.Nothing
            else -> Foreground.Other
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (snap.closingMotionSeq != lastClosingMotionSeq) { lastClosingMotionSeq = snap.closingMotionSeq; lastClosingMotionMs = nowMs }
        val sinceClosing = if (lastClosingMotionMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastClosingMotionMs
        val inputs = StandByInputs(snap.pose, snap.stillMs, displayOn, foreground, Settings.canDrawOverlays(this),
            prefs.closedOnTable, StandBySession.showing.value, StandBySession.msSinceGestureExit(),
            panel = snap.panel, hingeRateRadS = snap.hingeRateRadS, msSinceClosingMotion = sinceClosing)
        val start = StandByRules.shouldStart(inputs)
        val summary = "pose=${snap.pose} panel=${snap.panel} still=${snap.stillMs / 500 * 500}ms rate=%.2f closing=${if (sinceClosing == Long.MAX_VALUE) "never" else "${sinceClosing / 1000}s"} fg=$foreground cover=$displayOn overlay=${inputs.overlayGranted} showing=${inputs.showing} start=$start".format(snap.hingeRateRadS)
        if (summary != lastLogged) { Log.d(TAG, summary); lastLogged = summary }
        if (start) {
            if (SystemClock.elapsedRealtime() < nextLaunchAllowedMs) return
            launch(foreground)
        } else if (snap.pose == FoldPose.Closed || snap.pose == FoldPose.Tent) {
            // A resting phone may stop producing gravity samples; make sure the 4 s and the debounce still fire.
            val need = if (snap.pose == FoldPose.Tent) StandByRules.TENT_STILL_MS else StandByRules.CLOSED_STILL_MS
            requestEvaluate((need - snap.stillMs).coerceIn(EVALUATE_EVERY_MS, 2_000L))
        }
    }

    /**
     * B32 (StandByMorph.kt): while the launcher is in front it hands off to StandByActivity
     * frosted, not cut. A confident hinge angle means the launcher's own frost
     * (`rememberLauncherStandByFrost`) is already physically driven by the fold and is likely
     * there already, so the activity starts at once, already opaque
     * ([StandByActivity.EXTRA_START_FROSTED]); without one there is no fold to lean on, so
     * [StandBySession.requestEnterFrost] arms the launcher's timed fallback and the start waits
     * [StandByMorph.enterDurationMs] for it to catch up. Any other foreground starts at once,
     * not pre-frosted: there is no launcher panel in front to hand off from.
     */
    private fun launch(foreground: Foreground) {
        nextLaunchAllowedMs = SystemClock.elapsedRealtime() + LAUNCH_COOLDOWN_MS
        handler.removeCallbacks(afterLaunch)
        handler.postDelayed(afterLaunch, LAUNCH_CHECK_MS)
        if (foreground != Foreground.Launcher) {
            addAnchor()
            Log.i(TAG, "starting StandBy over $foreground")
            startStandByActivity(startFrosted = false)
            return
        }
        val angleConfident = !pose.snapshot.value.hingeAngleDeg.isNaN()
        Log.i(TAG, "starting StandBy over Launcher (angle confident=$angleConfident)")
        if (angleConfident) {
            startStandByActivity(startFrosted = true)
        } else {
            val reduceMotion = StandByMorph.reduceMotion(readAnimatorDurationScale(this))
            StandBySession.requestEnterFrost()
            handler.removeCallbacks(pendingStandByLaunch)
            handler.postDelayed(pendingStandByLaunch, StandByMorph.enterDurationMs(reduceMotion).toLong())
        }
    }

    private val pendingStandByLaunch = Runnable { startStandByActivity(startFrosted = true) }

    private fun startStandByActivity(startFrosted: Boolean) {
        runCatching { startActivity(StandByActivity.intent(this, startFrosted = startFrosted)) }
            .onFailure { Log.w(TAG, "StandBy start failed", it) }
    }

    /**
     * A background start Android refused is only a log line, not an exception. If StandBy is not
     * up [LAUNCH_CHECK_MS] after the start, back off [LAUNCH_BACKOFF_MS] instead of retrying every cooldown.
     */
    private fun checkLaunch() {
        removeAnchor()
        if (!StandBySession.showing.value) {
            Log.w(TAG, "StandBy did not appear; backing off ${LAUNCH_BACKOFF_MS / 1000} s (background start refused?)")
            nextLaunchAllowedMs = SystemClock.elapsedRealtime() + LAUNCH_BACKOFF_MS
        }
    }

    /** The 1 x 1 px transparent overlay that backs the background-start exemption (see class doc). */
    private fun addAnchor() {
        if (anchor != null || !Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        anchor = runCatching { View(this).also { wm.addView(it, params) } }
            .onFailure { Log.w(TAG, "overlay anchor failed", it) }.getOrNull()
    }

    private fun removeAnchor() {
        val view = anchor ?: return
        anchor = null
        runCatching { getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    // ---- foreground plumbing ----

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "StandBy", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while StandBy watches how the Fold is placed."
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("StandBy is on")
            .setContentText("Tent the Fold to show the clock on the cover.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "StandByService"
        private const val CHANNEL = "standby"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "cz.pflanzer.foldduo.standby.STOP"
        private const val EVALUATE_EVERY_MS = 250L
        /** Don't fire a second start while the first activity is still coming up. */
        private const val LAUNCH_COOLDOWN_MS = 3_000L
        private const val LAUNCH_CHECK_MS = 2_500L
        private const val LAUNCH_BACKOFF_MS = 30_000L

        @Volatile var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StandByService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StandByService::class.java))
        }
    }
}

/** Brings the service back after a reboot when StandBy is enabled. */
class StandByBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (StandByPrefs(context).enabled) StandByService.start(context)
    }
}
