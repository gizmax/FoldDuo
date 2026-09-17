package cz.pflanzer.foldduo.desk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.AppEntry
import cz.pflanzer.foldduo.HapticEvent
import cz.pflanzer.foldduo.Haptics
import cz.pflanzer.foldduo.LauncherState
import cz.pflanzer.foldduo.LocalFoldPose
import cz.pflanzer.foldduo.LocalFoldSeam
import cz.pflanzer.foldduo.MotionPrefs
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.splits
import cz.pflanzer.foldduo.standby.StandByFrostScreen
import cz.pflanzer.foldduo.standby.StandByMorphController
import cz.pflanzer.foldduo.standby.StandByPrefs
import cz.pflanzer.foldduo.standby.StandByScreen
import cz.pflanzer.foldduo.standby.rememberStandByEnvironment
import kotlinx.coroutines.delay

/**
 * B47 "Stůl": the one integration point LauncherScreen.kt needs (the doc comment convention
 * StandByMorphOverlay.kt and SeamPalette.kt's overlays already use) — reads
 * [LocalFoldPose]/[LocalFoldSeam] itself, runs the 1.2 s Stand-hold trigger and the frost morph
 * (reusing [StandByMorphController]/[StandByFrostScreen] as-is, not a Desk-specific copy), and
 * renders either the split face+deck (DeskMode.Desk) or the plain StandBy face full screen
 * (DeskMode.StandByFace) — DeskMode.Off never composes anything past this point.
 */
@Composable
fun DeskOverlay(
    state: LauncherState,
    islandItems: List<IslandItem>,
    onLaunchApp: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { DeskPrefs(context) }
    val standByPrefs = remember { StandByPrefs(context) }
    val pose = LocalFoldPose.current
    val reduceMotion = MotionPrefs.enabled.value
    val transition = remember { StandByMorphController() }
    var composed by remember { mutableStateOf(false) }
    var closeRequested by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(prefs.mode) }

    // Laptop pose only: the hinge in its middle step. Collected as a distinct HingeStep so the
    // 100-200 Hz snapshot flow never recomposes this overlay per sample.
    val hingeMid by remember(context) {
        cz.pflanzer.foldduo.PoseEngine.get(context).snapshot
            .map { cz.pflanzer.foldduo.pose.HingeStep.of(it.hingeDeg) == cz.pflanzer.foldduo.pose.HingeStep.Mid }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    LaunchedEffect(pose, hingeMid) {
        mode = prefs.mode
        if (DeskRules.shouldEnter(pose, mode, DeskRules.HOLD_MS, hingeMid)) {
            delay(DeskRules.HOLD_MS)
            // The key (pose) has not changed since this coroutine started, or it would already
            // have been cancelled — still Stand.
            closeRequested = false
            composed = true
            Haptics.play(context, HapticEvent.STANDBY_ENTER)
        } else if (composed) {
            Haptics.play(context, HapticEvent.STANDBY_LEAVE)
            transition.playLeave(reduceMotion)
            composed = false
        }
    }
    LaunchedEffect(closeRequested) {
        if (closeRequested && composed) {
            Haptics.play(context, HapticEvent.STANDBY_LEAVE)
            transition.playLeave(reduceMotion)
            composed = false
            closeRequested = false
        }
    }
    if (!composed) return

    StandByFrostScreen(transition, startFrosted = false, reduceMotion = reduceMotion, modifier = modifier.testTag("desk-overlay")) {
        when (mode) {
            DeskMode.StandByFace -> {
                val faces = remember { standByPrefs.faces }
                val environment = rememberStandByEnvironment()
                StandByScreen(faces, environment, onDoubleTap = { closeRequested = true }, onSwipeUp = { closeRequested = true })
            }
            else -> DeskSplitContent(prefs, standByPrefs, state, islandItems, onLaunchApp, onClose = { closeRequested = true })
        }
    }
}

@Composable
private fun DeskSplitContent(
    prefs: DeskPrefs,
    standByPrefs: StandByPrefs,
    state: LauncherState,
    islandItems: List<IslandItem>,
    onLaunchApp: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val seam = LocalFoldSeam.current
        val verticalSeamXDp = seam?.xDp?.takeIf { seam.splits(maxWidth.value) }
        val layout = remember(maxWidth, maxHeight, verticalSeamXDp) {
            deskLayout(maxWidth.value, maxHeight.value, verticalSeamXDp)
        }
        val environment = rememberStandByEnvironment()
        val faces = remember { standByPrefs.faces }
        Box(Modifier
            .absoluteOffset(x = layout.face.left.dp, y = layout.face.top.dp)
            .size(width = layout.face.widthDp.dp, height = layout.face.heightDp.dp)) {
            DeskFacePane(faces, environment, state, Modifier.fillMaxSize())
        }
        Box(Modifier
            .absoluteOffset(x = layout.deck.left.dp, y = layout.deck.top.dp)
            .size(width = layout.deck.widthDp.dp, height = layout.deck.heightDp.dp)
            .padding(12.dp)) {
            DeskDeck(prefs, state, islandItems, onLaunchApp, Modifier.fillMaxSize())
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).testTag("desk-close")) {
            Icon(Icons.Rounded.Close, "Close Desk", tint = Color.White)
        }
    }
}
