package cz.pflanzer.foldduo.standby

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import cz.pflanzer.foldduo.MorphController
import cz.pflanzer.foldduo.PoseEngine
import cz.pflanzer.foldduo.pose.Panel
import kotlin.math.max

/*
 * The Compose side of B32 (see StandByMorph.kt for the pure logic this drives): a shared frost
 * look ([standByFrost]) used both by the launcher's own panel (LauncherScreen.kt) and by
 * StandByActivity's content, and the two bridges that wire each side to a [StandByMorphController]
 * / [MorphController] instance.
 */

/**
 * B32's uniform frost, applied directly to whatever content this modifies: a blur that grows with
 * [frost] plus a black veil fading in over it. Unlike UnfoldMorph.kt's `foldEffect` (a hinge-shaped
 * perspective for the panel swap) there is no tilt here — StandBy's frost is a flat ambient fog,
 * not a fold. [frost] is read at layer/draw time only (a lambda, like `foldEffect`'s own `tilt`),
 * so the ~50 Hz angle-driven samples never recompose the tree, only invalidate the layer.
 */
fun Modifier.standByFrost(frost: () -> Float): Modifier = this
    .graphicsLayer {
        val f = frost().coerceIn(0f, 1f)
        renderEffect = if (f <= 0f) null else RenderEffect.createBlurEffect(
            (f * StandByMorph.FROST_MAX_BLUR_PX).coerceAtLeast(0.01f),
            (f * StandByMorph.FROST_MAX_BLUR_PX).coerceAtLeast(0.01f),
            Shader.TileMode.CLAMP,
        ).asComposeRenderEffect()
    }
    .drawWithContent {
        drawContent()
        val f = frost().coerceIn(0f, 1f)
        if (f > 0f) drawRect(Color.Black.copy(alpha = f * StandByMorph.FROST_MAX_TINT_ALPHA))
    }

/** `Settings.Global.ANIMATOR_DURATION_SCALE`, 1x when unset or unreadable (mirrors MainActivity's own). */
internal fun readAnimatorDurationScale(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/**
 * StandByActivity's half of B32: [content] fades + scales in (B16-style spring) out of the frost
 * while it clears, or (leaving) frosts back over [content] before the activity finishes.
 * [startFrosted] is StandByActivity.EXTRA_START_FROSTED: the launcher already played its own
 * pre-entry frost (see [rememberLauncherStandByFrost]), so this starts already opaque.
 */
@Composable
fun StandByFrostScreen(transition: StandByMorphController, startFrosted: Boolean, reduceMotion: Boolean,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LaunchedEffect(transition) { transition.playEnter(reduceMotion, startFrosted) }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val reveal = transition.reveal.value
                alpha = reveal
                val scale = StandByMorph.ENTER_REVEAL_SCALE_FROM +
                    (StandByMorph.ENTER_REVEAL_SCALE_TO - StandByMorph.ENTER_REVEAL_SCALE_FROM) * reveal
                scaleX = scale
                scaleY = scale
            }
            .standByFrost { transition.frost.value }) {
            content()
        }
    }
}

/**
 * The launcher's half of B32, the one integration point LauncherScreen.kt needs: a live read of
 * the hinge angle for the physical approach into Tent ([StandByMorph.angleFrost], gated to the
 * cover panel where Tent lives), the timed fallback [StandByService] arms
 * ([StandBySession.enterFrostRequests]) when the angle is not confident, and the return trip —
 * arming [MorphController.requestStandByReturn] the moment StandBy stops showing and playing it
 * once the layout picks it up. Returns a lambda (like `foldEffect`'s own `tilt`) so
 * [standByFrost] reads it per frame without recomposing the launcher's root.
 */
@Composable
fun rememberLauncherStandByFrost(morph: MorphController?): () -> Float {
    val context = LocalContext.current
    val pose = remember(context) { PoseEngine.get(context) }
    val angleFrost = remember { mutableFloatStateOf(0f) }
    val timedFrost = remember { mutableFloatStateOf(0f) }
    // Armed only between StandByService's "show" decision (enterFrostRequests) and StandBy
    // actually showing: the angle ramp is a *shape* for that entrance, never a trigger. Without
    // this gate a closed phone (hinge angle 0 on the cover) read as "past the Tent approach" and
    // the cover sat fully frosted at rest (2026-09-15 21:56, Tom: "rozmazaný v defaultu při zavřeném").
    val armed = remember { mutableStateOf(false) }

    LaunchedEffect(pose) {
        pose.snapshot.collect { snap ->
            angleFrost.floatValue = StandByMorph.launcherAngleFrost(armed.value, snap.panel == Panel.Cover, snap.hingeAngleDeg)
        }
    }
    LaunchedEffect(context) {
        StandBySession.enterFrostRequests.collect { requests ->
            if (requests == 0) return@collect
            armed.value = true
            val duration = StandByMorph.enterDurationMs(StandByMorph.reduceMotion(readAnimatorDurationScale(context)))
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                timedFrost.floatValue = StandByMorph.timeFrost(SystemClock.elapsedRealtime() - startedAt, duration)
                if (timedFrost.floatValue >= 1f) break
                withFrameMillis { }
            }
            // Safety: if StandBy never comes up (service cancelled its delayed start, pose left),
            // drop the frost instead of holding the launcher frosted forever.
            while (SystemClock.elapsedRealtime() - startedAt < StandByMorph.ARM_TIMEOUT_MS) {
                if (StandBySession.showing.value) return@collect
                withFrameMillis { }
            }
            if (!StandBySession.showing.value) { armed.value = false; timedFrost.floatValue = 0f; angleFrost.floatValue = 0f }
        }
    }
    LaunchedEffect(Unit) {
        var wasShowing = false
        StandBySession.showing.collect { showing ->
            if (wasShowing && !showing) {
                morph?.requestStandByReturn()
                timedFrost.floatValue = 0f
                angleFrost.floatValue = 0f
            }
            if (!showing) armed.value = false
            wasShowing = showing
        }
    }
    LaunchedEffect(morph, morph?.standByReturnRequests) {
        if (morph == null || !morph.takeStandByReturn()) return@LaunchedEffect
        morph.playStandByReturn()
    }
    return { max(angleFrost.floatValue, max(timedFrost.floatValue, morph?.standByReturnFrost?.value ?: 0f)) }
}
