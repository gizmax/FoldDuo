package cz.pflanzer.foldduo

import android.media.session.MediaController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.IslandNotificationListener
import cz.pflanzer.foldduo.pose.TiltDirection
import kotlinx.coroutines.delay

/**
 * B48 "Pant jako ovladač", tent tilt: while [FoldPose.Tent] holds and a media session is active,
 * tilting the propped-up phone about the hinge axis skips tracks — forward = next, back =
 * previous (IDEAS.md B48). The active session's [MediaController] is the same one the rail island
 * already reads off the notification-listener grant ([IslandNotificationListener], `IslandMedia`),
 * so this needs no permission of its own. Call once per
 * [cz.pflanzer.foldduo.pose.PoseSnapshot.tentTiltSeq] change (`MainActivity`'s `LaunchedEffect`);
 * returns the label [TentTiltToastHost] shows, or null when there is no active session to act on
 * (nothing to skip, so nothing shown either).
 */
internal fun handleTentTilt(direction: TiltDirection): String? {
    val controller = activeMediaController() ?: return null
    val transport = controller.transportControls
    return when (direction) {
        TiltDirection.FORWARD -> { transport.skipToNext(); "Next track" }
        TiltDirection.BACKWARD -> { transport.skipToPrevious(); "Previous track" }
    }
}

private fun activeMediaController(): MediaController? =
    IslandNotificationListener.items.value.firstOrNull { it.kind == IslandKind.MEDIA }?.media?.controller

/**
 * A subtle glass toast for [handleTentTilt]'s result, auto-hiding after [TOAST_MS]. Rendered as a
 * sibling of `LauncherScreen` inside `MainActivity`'s own root `Box` — the same place
 * `ContinuityChipHost` lives — rather than inside `LauncherScreen.kt`, since B48's overlay budget
 * in that file is spent on the squeeze/App Library overlay instead.
 */
@Composable
internal fun TentTiltToastHost(message: String?, seq: Int, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(seq, message) {
        if (message == null) return@LaunchedEffect
        shown = seq to message
        delay(TOAST_MS)
        if (shown?.first == seq) shown = null
    }
    val current = shown
    AnimatedVisibility(visible = current != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier
            // "Matné sklo pro všechny pilulky" (17. 9. noc): shared primitive, opacity-reactive.
            .glassPill(corner = 20.dp, baseVeilAlpha = .34f)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("tent-tilt-toast"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (current?.second == "Next track") Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious,
                null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))
            Text(current?.second.orEmpty(), color = Color.White)
        }
    }
}

private const val TOAST_MS = 1_600L
