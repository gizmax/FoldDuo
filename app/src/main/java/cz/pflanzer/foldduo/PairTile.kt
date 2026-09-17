package cz.pflanzer.foldduo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * B46 "Dvojice aplikací": the Compose half of app pairs — the Home tile, its long-press menu, the
 * "turn on the accessibility service" glass banner, and the diagonal two-icon glyph shared with
 * Spotlight's row icon. Pure editing logic (creation, swap, split, remove) lives in
 * PairEditing.kt; the actual split-screen launch sequence in PairLaunch.kt/MainActivity.kt.
 */

/** The diagonal two-icon glyph: [first] top-left, [second] bottom-right, each 60 % of [size] —
 * used both by the Home tile below and by Spotlight's pair row icon. */
@Composable
fun PairIcons(first: AppEntry?, second: AppEntry?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val iconStyle = LocalIconStyle.current
    val memberSize = size * 0.6f
    Box(modifier.size(size)) {
        first?.let { app -> Image(app.icon.asImageBitmap(), null,
            Modifier.align(Alignment.TopStart).size(memberSize).clip(iconStyle.clipShape())) }
        second?.let { app -> Image(app.icon.asImageBitmap(), null,
            Modifier.align(Alignment.BottomEnd).size(memberSize).clip(iconStyle.clipShape())) }
    }
}

/**
 * The persisted Home tile: [PairIcons] on a glass backing plus a truncated "A + B" label.
 * LauncherScreen.kt's `SharedHomeGrid` separately overlays [PairFormingIndicator] on the *cell
 * underneath* while a drag dwells there long enough for [appOnAppDropOutcome] to turn a release
 * into a new pair rather than a folder.
 */
@Composable
internal fun PairTile(pair: PairEntry, apps: Map<String, AppEntry>, size: Float, labels: Boolean,
    modifier: Modifier = Modifier, onClick: (android.graphics.Rect) -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val corner = RoundedCornerShape((size * .24f).dp)
    val first = apps[pair.first]
    val second = apps[pair.second]
    val bounds = remember { android.graphics.Rect() }
    Column(modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current,
        onClick = { onClick(bounds) }).semantics(mergeDescendants = true) {
        contentDescription = "Pair ${first?.label ?: pair.first} and ${second?.label ?: pair.second}"
        onLongClick("Pair options") { onLongClick(); true }
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(corner).iconPressScale(interaction)
            .background(Glass.copy(alpha = .72f)).border(1.dp, Color.White.copy(alpha = .55f), corner)
            .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
            .testTag("pair-tile-${pair.id}"), contentAlignment = Alignment.Center) {
            PairIcons(first, second, size.dp)
        }
        if (labels) {
            val label = "${first?.label ?: pair.first} + ${second?.label ?: pair.second}"
            Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Small pulsing dot at a drop target's trailing corner: shown once a drag has dwelled long
 * enough for [appOnAppDropOutcome] to turn a release into a Pair instead of a Folder. */
@Composable
internal fun PairFormingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pair-forming")
    val alpha by transition.animateFloat(0.35f, 0.9f,
        infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pair-forming-alpha")
    Box(modifier.size(10.dp).background(Color.White.copy(alpha = alpha), RoundedCornerShape(50)))
}

/** Long-press menu content, shown in a `ModalBottomSheet` like every other Home item's actions. */
@Composable
internal fun PairActionSheet(pair: PairEntry, apps: Map<String, AppEntry>,
    onSwap: () -> Unit, onSplit: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    val first = apps[pair.first]; val second = apps[pair.second]
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PairIcons(first, second, 40.dp)
            Spacer(Modifier.width(14.dp))
            Text("${first?.label ?: pair.first} + ${second?.label ?: pair.second}",
                style = IosTitleStyle, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close pair options") }
        }
        ActionRow(Icons.Rounded.SwapHoriz, "Swap sides", onSwap, Modifier.testTag("pair-swap-${pair.id}"))
        ActionRow(Icons.Rounded.LinkOff, "Split into apps", onSplit, Modifier.testTag("pair-split-${pair.id}"))
        ActionRow(Icons.Rounded.RemoveCircleOutline, "Remove", onRemove,
            Modifier.testTag("pair-remove-${pair.id}"), tint = IosDestructive)
    }
}

/**
 * "Turn on the accessibility service for app pairs": shown when the split-screen launch sequence
 * finds `SystemShadeAccessibilityService` unbound (PairLaunch.kt's `toggleSplitScreen` returned
 * false). A plain translucent banner rather than [frostedGlass] — this overlay sits in
 * MainActivity's own Box, a sibling of LauncherScreen rather than a descendant, so it cannot rely
 * on whatever `LocalFrostedBackdrop` LauncherScreen happens to have set up beneath it.
 */
@Composable
fun PairAccessibilityPrompt(visible: Boolean, onDismiss: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
    Row(modifier.background(Color.Black.copy(alpha = .72f), RoundedCornerShape(20.dp))
        .border(1.dp, Color.White.copy(alpha = .3f), RoundedCornerShape(20.dp))
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag("pair-accessibility-prompt"), verticalAlignment = Alignment.CenterVertically) {
        Text("Turn on the accessibility service for app pairs", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenSettings) { Text("Settings", color = Color.White) }
        TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.White.copy(alpha = .8f)) }
    }
}
