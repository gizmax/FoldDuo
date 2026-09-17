package cz.pflanzer.foldduo

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * B21, "Reduce motion": the pure decision (JVM-testable, no Android types) of whether the system
 * wants animations off, from the three signals the task calls out.
 *
 * `transitionScale` / `animatorScale` are `Settings.Global.TRANSITION_ANIMATION_SCALE` /
 * `ANIMATOR_DURATION_SCALE` (Android's own "Remove animations" developer/accessibility toggle
 * drives both to 0 together, but either alone already means "don't animate"). `accessibilityFlag`
 * is a One UI-specific key with no public constant (see [MotionPrefs.KEY_ACCESSIBILITY_REDUCE_MOTION]);
 * reduce motion is on if any of the three is.
 */
object ReduceMotionLogic {
    fun decide(transitionScale: Float, animatorScale: Float, accessibilityFlag: Boolean): Boolean =
        transitionScale <= 0f || animatorScale <= 0f || accessibilityFlag
}

/**
 * The one place B21's other in-flight features can read the decision from
 * ([enabled] as a `State<Boolean>`, per the task): refreshed at resume
 * ([MainActivity.onResume], alongside the existing animator-duration-scale read) and kept live by
 * a [ContentObserver] on all three settings while the activity is started ([attach]).
 *
 * B27's wallpaper parallax and B31's App Library / B30's island already read
 * `Settings.Global.ANIMATOR_DURATION_SCALE` directly (their own call sites, predating this); left
 * alone per the task. New code should read [enabled] instead.
 */
object MotionPrefs {
    /** One UI's undocumented "Remove animations" accessibility key; no public SDK constant exists, so this is a best-effort guess behind a safe fallback (0/off if the key is absent on this firmware). */
    const val KEY_ACCESSIBILITY_REDUCE_MOTION = "accessibility_reduce_motion"

    private val state = mutableStateOf(false)

    /** True while the system wants animations off; read this, not the raw settings, from new code. */
    val enabled: State<Boolean> get() = state

    /** Re-read the three settings now (call at resume, same spot as [MainActivity]'s animator-duration-scale read). */
    fun refresh(context: Context) { state.value = readReduceMotion(context) }

    /** The current decision, without touching [enabled] (used by [refresh] and by callers without a `State` to update, e.g. a Service). */
    fun readReduceMotion(context: Context): Boolean {
        val resolver = context.contentResolver
        val transitionScale = runCatching { Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) }.getOrDefault(1f)
        val animatorScale = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        val accessibilityFlag = runCatching { Settings.Secure.getInt(resolver, KEY_ACCESSIBILITY_REDUCE_MOTION, 0) == 1 }.getOrDefault(false)
        return ReduceMotionLogic.decide(transitionScale, animatorScale, accessibilityFlag)
    }

    /**
     * Keeps [enabled] live while [lifecycleOwner] is at least STARTED: registers a
     * [ContentObserver] on the two `Settings.Global` scales and the accessibility key on
     * `ON_START`/refreshes once immediately, unregisters on `ON_STOP`.
     */
    fun attach(context: Context, lifecycleOwner: LifecycleOwner) {
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        var observer: ContentObserver? = null
        fun register() {
            if (observer != null) return
            val o = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { refresh(appContext) }
            }
            observer = o
            val resolver = appContext.contentResolver
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, o)
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, o)
            runCatching { resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_ACCESSIBILITY_REDUCE_MOTION), false, o) }
            refresh(appContext)
        }
        fun unregister() { observer?.let { appContext.contentResolver.unregisterContentObserver(it) }; observer = null }
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> register()
            Lifecycle.Event.ON_STOP -> unregister()
            else -> Unit
        } })
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
    }
}
