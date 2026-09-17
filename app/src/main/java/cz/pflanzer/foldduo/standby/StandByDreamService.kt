package cz.pflanzer.foldduo.standby

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The same StandBy faces as the system screensaver ("Screen saver" in Android settings), which
 * the system starts by itself on a charger or dock. Interactive (the pager swipes, a double tap
 * ends the dream), fullscreen, not screen-bright. No pose logic: the system decides when a
 * dream runs.
 */
class StandByDreamService : DreamService() {
    private val host = DreamHost()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        setScreenBright(false)
        val prefs = StandByPrefs(this)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent {
                StandByScreen(prefs.faces, rememberStandByEnvironment(), onDoubleTap = { finish() }, onSwipeUp = { finish() })
            }
        }
        host.moveTo(Lifecycle.State.CREATED)
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        host.moveTo(Lifecycle.State.RESUMED)
    }

    override fun onDreamingStopped() {
        host.moveTo(Lifecycle.State.CREATED)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        host.moveTo(Lifecycle.State.DESTROYED)
        super.onDetachedFromWindow()
    }
}

/** A DreamService is no LifecycleOwner; Compose (and `rememberSaveable` in the pager) needs one. */
private class DreamHost : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init { controller.performRestore(null) }

    fun moveTo(state: Lifecycle.State) {
        if (state == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = state
    }
}
