package cz.pflanzer.foldduo

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * B48 "Pant jako ovladač": maps the live squeeze depth (deg,
 * [cz.pflanzer.foldduo.pose.HingeSqueezeDetector.depthDeg]) to how far the App Library overlay has
 * slid in, `0` (hidden) .. `1` (fully open). Below [SQUEEZE_PEEK_START_DEG] nothing shows; from
 * there to [SQUEEZE_FULL_DEG] the reveal tracks the depth continuously (the task's "drag-like"
 * follow) starting from the bare [SQUEEZE_PEEK_PROGRESS] a fresh recognition already reveals (a
 * recognized squeeze is never below [cz.pflanzer.foldduo.pose.HingeSqueezeDetector.MOTION_ANGLE_DEG]
 * = 6°, i.e. never below this function's own peek start); past [SQUEEZE_FULL_DEG] it clamps at
 * fully open. Pure, no Android types.
 */
fun squeezeOverlayProgress(depthDeg: Float): Float {
    if (depthDeg <= SQUEEZE_PEEK_START_DEG) return 0f
    if (depthDeg >= SQUEEZE_FULL_DEG) return 1f
    val t = (depthDeg - SQUEEZE_PEEK_START_DEG) / (SQUEEZE_FULL_DEG - SQUEEZE_PEEK_START_DEG)
    return SQUEEZE_PEEK_PROGRESS + (1f - SQUEEZE_PEEK_PROGRESS) * t
}

/** IDEAS.md B48: "depth 6-20 deg = peek 30%". */
const val SQUEEZE_PEEK_START_DEG = 6f
const val SQUEEZE_FULL_DEG = 20f
const val SQUEEZE_PEEK_PROGRESS = 0.30f

/**
 * The App Library overlay a recognized hinge squeeze opens (`HingeSqueezeAction.AppLibrary`, the
 * default). Self-contained like `SpotlightOverlay`/`SeamPaletteOverlay` — it reads its own bounds
 * ([LocalFoldSeam]/`BoxWithConstraints`), so [LauncherScreen] only needs this one call plus the
 * squeeze state.
 *
 * The reveal is an "unfurl from the hinge" rather than a plain left/right drawer, since the
 * squeeze itself has no notion of which pane it happened in: it scales in from the seam's own
 * fraction of the width (0.5 — screen centre — without one). [visible] drives a fast spring toward
 * whatever [squeezeOverlayProgress] of [depthDeg] is *right now*, so while held the reveal keeps
 * tracking the finger (re-triggered on every [depthDeg] tick, ~50 Hz) rather than settling once;
 * on release it eases back to hidden over a fixed duration, both skipped for a plain snap under
 * [reduceMotion].
 */
@Composable
internal fun HingeSqueezeOverlay(
    visible: Boolean,
    depthDeg: Float,
    reduceMotion: Boolean,
    state: LauncherState,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var composed by remember { mutableStateOf(visible) }
    var query by rememberSaveable { mutableStateOf("") }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible, depthDeg, reduceMotion) {
        if (visible) composed = true
        val target = if (visible) squeezeOverlayProgress(depthDeg) else 0f
        when {
            reduceMotion -> progress.snapTo(target)
            visible -> progress.animateTo(target, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh))
            else -> progress.animateTo(0f, tween(180))
        }
        if (!visible && progress.value <= 0f) composed = false
    }
    LaunchedEffect(visible) { if (!visible) query = "" }
    if (!composed) return
    BackHandler(enabled = visible, onBack = onDismiss)

    BoxWithConstraints(Modifier.fillMaxSize().testTag("hinge-squeeze-overlay")) {
        val density = LocalDensity.current
        val safeLeftDp = with(density) { WindowInsets.safeDrawing.getLeft(this, LocalLayoutDirection.current).toDp().value }
        val seam = LocalFoldSeam.current?.let { it.copy(xDp = it.xDp - safeLeftDp) }?.takeIf { it.splits(maxWidth.value) }
        val pivotFractionX = seam?.let { (it.xDp / maxWidth.value).coerceIn(0f, 1f) } ?: 0.5f
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val p = progress.value.coerceIn(0f, 1f)
                alpha = p
                // Unfurl from the hinge: a small scale-up from the seam's pivot rather than a
                // plain slide, so the reveal reads as coming from the crease regardless of which
                // pane the squeeze happened in.
                scaleX = 0.86f + 0.14f * p
                scaleY = 0.97f + 0.03f * p
                transformOrigin = TransformOrigin(pivotFractionX, 0.5f)
            }
            .frostedGlass(corner = 0.dp, tintAlpha = .34f)) {
            AppLibrary(
                state = state, query = query, onQuery = { query = it },
                onLaunch = { app -> onLaunch(app, null); onDismiss() },
                onPin = onPin,
                onActions = {},
                modifier = Modifier.fillMaxSize(),
                onLaunchFrom = { app, rect -> onLaunch(app, rect); onDismiss() },
                onTurnOnWork = onTurnOnWork,
                expanded = true,
                isCurrent = visible,
            )
        }
    }
}

// --- "Now Brief" action (HingeSqueezeAction.NowBrief) --------------------------------------

private const val NOW_BRIEF_PACKAGE = "com.samsung.android.app.routines"

/**
 * Samsung's Now Brief has no documented public launch Intent; [NOW_BRIEF_PACKAGE] is a best-effort
 * guess (Bixby Routines evolved into Now Brief on recent One UI), same caveat as every other
 * unverified Samsung integration in this codebase (STATUS.md's "Frost over other apps" reflection
 * API, the hidden `DeviceStateManager` override, …) — the agent that wrote this has no device to
 * confirm it on. Degrades gracefully either way: true only if the package resolved and the intent
 * actually started; false (never throws) otherwise, so the caller (MainActivity) can fall back to
 * Spotlight.
 */
internal fun openNowBrief(context: Context): Boolean = runCatching {
    val intent = context.packageManager.getLaunchIntentForPackage(NOW_BRIEF_PACKAGE) ?: return false
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
