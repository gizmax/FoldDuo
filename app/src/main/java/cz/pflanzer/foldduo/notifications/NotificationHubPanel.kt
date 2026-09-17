package cz.pflanzer.foldduo.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cz.pflanzer.foldduo.Glass
import cz.pflanzer.foldduo.Ink
import cz.pflanzer.foldduo.MotionPrefs
import cz.pflanzer.foldduo.frostedGlass
import cz.pflanzer.foldduo.glassPill
import cz.pflanzer.foldduo.island.islandSwipeDismissed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * IDEAS.md B34, "Notifikace jako skleněné karty na Today": the glass cards themselves, rendered
 * at the top of the leading canvas (LeadingPane.kt's slot, above icons/widgets). Grouping,
 * filtering and the relative-time label live in HubNotification.kt so they run on the JVM
 * (app/src/test); this file is the Compose half, styled like every other Duo card
 * ([cz.pflanzer.foldduo.frostedGlass], corner 20 dp — [HUB_CARD_CORNER]).
 */

private val HUB_CARD_CORNER = 20.dp
private val HUB_ICON_SIZE = 28.dp
private const val HUB_DISMISS_FLING_FACTOR = 1.4f
/** Dragging right (away from the dismiss direction) only moves the card this much of the finger's travel. */
private const val HUB_RUBBER_BAND_FACTOR = 0.3f
/** Two decoy cards behind the top one, per the spec ("grouped stack shows two offset cards behind"). */
private const val HUB_STACK_DECOYS = 2

/**
 * The hub itself: one card per group, newest group first, a "+N" stack collapsed until tapped.
 * Renders nothing when [groups] is empty — callers reserve rows with [hubRowsReserved] and can
 * skip composing this entirely once that is 0.
 */
@Composable
fun NotificationHubPanel(
    groups: List<HubGroup>,
    modifier: Modifier = Modifier,
    expandedGroup: String? = null,
    onExpandGroup: (String?) -> Unit = {},
    onOpen: (HubNotification) -> Unit = {},
    onDismiss: (String) -> Unit = {},
    onClearGroup: (HubGroup) -> Unit = {},
) {
    if (groups.isEmpty()) return
    Column(modifier.testTag("notification-hub").fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { group ->
            key(group.packageName) {
                HubGroupCard(group, expanded = expandedGroup == group.packageName,
                    onToggle = { onExpandGroup(if (expandedGroup == group.packageName) null else group.packageName) },
                    onOpen = onOpen, onDismiss = onDismiss, onClearAll = { onClearGroup(group) })
            }
        }
    }
}

@Composable
private fun HubGroupCard(
    group: HubGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (HubNotification) -> Unit,
    onDismiss: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val reduceMotion = MotionPrefs.enabled.value
    Box(Modifier.fillMaxWidth().testTag("hub-group-${group.packageName}")
        .animateContentSize(hubSpring(reduceMotion))) {
        if (!expanded) {
            val decoys = minOf(group.stackCount, HUB_STACK_DECOYS)
            for (depth in decoys downTo 1) {
                Box(Modifier.fillMaxWidth()
                    .padding(top = (depth * 6).dp, start = (depth * 5).dp, end = (depth * 5).dp)
                    .graphicsLayer { scaleX = 1f - depth * .035f; scaleY = 1f - depth * .035f; alpha = 1f - depth * .2f }
                    .frostedGlass(HUB_CARD_CORNER, tintAlpha = .2f, fallback = Glass.copy(alpha = .45f))
                    .fillMaxWidth().height(HUB_CARD_MIN_HEIGHT))
            }
            HubNotificationCard(group.top, expanded = false, stackCount = group.stackCount,
                onOpen = { onOpen(group.top) }, onDismiss = onDismiss,
                onTap = if (group.stackCount > 0) onToggle else null)
        } else {
            Column(Modifier.fillMaxWidth()
                .frostedGlass(HUB_CARD_CORNER, tintAlpha = .26f, fallback = Glass.copy(alpha = .55f))
                .padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(group.appLabel, style = MaterialTheme.typography.titleSmall, color = Ink, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClearAll, modifier = Modifier.testTag("hub-clear-${group.packageName}")) {
                            Text("Clear all", color = Ink)
                        }
                        IconButton(onClick = onToggle, modifier = Modifier.testTag("hub-collapse-${group.packageName}")) {
                            Icon(Icons.Rounded.ExpandLess, "Collapse", tint = Ink)
                        }
                    }
                }
                group.notifications.forEach { notification ->
                    key(notification.key) {
                        HubNotificationCard(notification, expanded = true, stackCount = 0,
                            onOpen = { onOpen(notification) }, onDismiss = onDismiss, onTap = null,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

private val HUB_CARD_MIN_HEIGHT = 64.dp

/**
 * One card: app icon, title, one line of text (the full text once [expanded]), a relative
 * timestamp and — expanded only — up to [HUB_ACTIONS_PER_CARD] action chips. [onTap] is non-null
 * only for a collapsed stack's top card, where a tap expands the group instead of opening it;
 * everywhere else a tap fires [onOpen]. Swipe left past the threshold cancels the notification
 * ([onDismiss]); dragging the other way rubber-bands instead of following the finger.
 */
@Composable
private fun HubNotificationCard(
    notification: HubNotification,
    expanded: Boolean,
    stackCount: Int,
    onOpen: () -> Unit,
    onDismiss: (String) -> Unit,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = MotionPrefs.enabled.value
    val offsetX = remember(notification.key) { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(30_000L) }
    }
    Box(modifier.fillMaxWidth()
        .onSizeChanged { widthPx = it.width.toFloat() }
        .graphicsLayer { translationX = offsetX.value }
        .then(if (notification.isClearable) Modifier.pointerInput(notification.key) {
            val velocityTracker = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { velocityTracker.resetTracking() },
                onDragEnd = {
                    val velocity = velocityTracker.calculateVelocity().x
                    val dismissed = offsetX.value < 0f && islandSwipeDismissed(offsetX.value, widthPx, velocity)
                    scope.launch {
                        if (dismissed) {
                            offsetX.animateTo(-widthPx.coerceAtLeast(1f) * HUB_DISMISS_FLING_FACTOR, hubSpring(reduceMotion))
                            onDismiss(notification.key)
                        } else offsetX.animateTo(0f, hubSpring(reduceMotion))
                    }
                },
                onDragCancel = { scope.launch { offsetX.animateTo(0f, hubSpring(reduceMotion)) } },
            ) { change, dragAmount ->
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val next = offsetX.value + dragAmount
                // Rubber-band: only a leftward pull tracks the finger 1:1; the other way is damped.
                scope.launch { offsetX.snapTo(if (next > 0f) next * HUB_RUBBER_BAND_FACTOR else next) }
            }
        } else Modifier)
        .clip(RoundedCornerShape(HUB_CARD_CORNER))
        .frostedGlass(HUB_CARD_CORNER, tintAlpha = .24f, fallback = Glass.copy(alpha = .5f))
        .clickable {
            // A collapsed stack's top card expands the group; everywhere else a tap opens it.
            if (onTap != null) onTap() else { launchHubIntent(context, notification.contentIntent); onOpen() }
        }
        .testTag("hub-card-${notification.key}")
        .padding(12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubIcon(notification.appIcon, HUB_ICON_SIZE)
                Column(Modifier.weight(1f)) {
                    Text(notification.title.ifBlank { notification.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(notification.text, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = .85f))
                }
                Text(relativeTimeLabel(notification.whenTime, now), style = MaterialTheme.typography.labelSmall,
                    color = Ink.copy(alpha = .7f))
                if (stackCount > 0) StackBadge(stackCount)
            }
            if (expanded && notification.actions.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    notification.actions.forEach { action ->
                        Box(Modifier.clip(RoundedCornerShape(14.dp))
                            .frostedGlass(14.dp, tintAlpha = .18f, fallback = Glass.copy(alpha = .4f))
                            .clickable { launchHubIntent(context, action.intent) }
                            .testTag("hub-action-${notification.key}-${action.title}")) {
                            Text(action.title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Ink, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private val HUB_PILL_HEIGHT = 44.dp
private val HUB_PILL_ICON_SIZE = 20.dp
/** Pill shows at most this many mini app icons before just relying on the count text. */
private const val HUB_PILL_MAX_ICONS = 5

/**
 * The compact hub (redesign after Tom's 2026-09-17 feedback): one glass row instead of up to
 * [HUB_MAX_ROWS] card rows — up to [HUB_PILL_MAX_ICONS] overlapping mini app icons plus
 * [hubSummaryText] ("3 aplikace · 7 oznámení" / "3 apps · 7 notifications", locale-aware), the
 * whole row tappable to expand into [NotificationHubPanel]'s cards ([LeadingTopSlot] switches
 * between the two). Renders nothing when [groups] is empty, same contract as [NotificationHubPanel].
 */
@Composable
fun NotificationHubPill(groups: List<HubGroup>, onTap: () -> Unit, modifier: Modifier = Modifier) {
    if (groups.isEmpty()) return
    val notificationCount = groups.sumOf { it.notifications.size }
    val locale = LocalConfiguration.current.locales[0]
    val summary = remember(groups.size, notificationCount, locale) { hubSummaryText(groups.size, notificationCount, locale) }
    Row(modifier.fillMaxWidth().height(HUB_PILL_HEIGHT).clip(RoundedCornerShape(HUB_PILL_HEIGHT / 2))
        // "Matné sklo pro všechny pilulky" (17. 9. noc): shared primitive, opacity-reactive.
        .glassPill(corner = HUB_PILL_HEIGHT / 2, baseVeilAlpha = .24f, fallback = Glass.copy(alpha = .5f))
        .clickable(onClick = onTap).testTag("notification-hub-pill").padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            groups.take(HUB_PILL_MAX_ICONS).forEachIndexed { index, group ->
                Box(Modifier.offset(x = (-index * HUB_PILL_ICON_SIZE.value * .4f).dp)
                    .zIndex((HUB_PILL_MAX_ICONS - index).toFloat())
                    .border(1.5.dp, Color.White.copy(alpha = .7f), CircleShape)
                    .clip(CircleShape)) {
                    HubIcon(group.appIcon, HUB_PILL_ICON_SIZE)
                }
            }
        }
        Text(summary, style = MaterialTheme.typography.labelLarge, color = Ink, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * Cover-only stand-in for the hub (B34, item 2): the leading canvas never shows on the cover
 * (pane identity), so an unread count next to the rail island is all it gets. [count] is every
 * notification across every group, not just the group count.
 */
@Composable
fun NotificationHubBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(shape = CircleShape, color = Glass.copy(alpha = .85f),
        modifier = modifier.size(18.dp).testTag("hub-unread-badge")) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (count > 9) "9+" else count.toString(), color = Ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StackBadge(count: Int) {
    Surface(shape = CircleShape, color = Glass.copy(alpha = .6f), modifier = Modifier.padding(start = 4.dp).testTag("hub-stack-badge")) {
        Text("+$count", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Ink, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HubIcon(icon: Drawable?, size: Dp) {
    val density = LocalDensity.current
    val px = remember(density, size) { with(density) { size.roundToPx() } }
    val bitmap = remember(icon, px) { icon?.toHubBitmap(px) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.size(size))
        else Icon(Icons.Rounded.Notifications, null, tint = Ink, modifier = Modifier.size(size * .7f))
    }
}

private fun Drawable.toHubBitmap(px: Int): ImageBitmap? {
    if (px <= 0) return null
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/** Same background-activity-start allowance the rail island uses (island/RailIsland.kt's `launchIsland`): the launcher is foreground, so opt in on Android 14+. */
private fun launchHubIntent(context: android.content.Context, intent: PendingIntent?) {
    intent ?: return
    runCatching {
        @Suppress("DEPRECATION")
        val mode = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            Build.VERSION.SDK_INT >= 34 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> null
        }
        if (mode != null) {
            val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
            intent.send(context, 0, null, null, null, null, options.toBundle())
        } else intent.send()
    }
}

private fun <T> hubSpring(reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
