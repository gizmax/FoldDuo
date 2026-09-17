package cz.pflanzer.foldduo.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.pflanzer.foldduo.cameraisland.CameraIslandSettings
import cz.pflanzer.foldduo.cameraisland.LiveActivitiesSurface
import cz.pflanzer.foldduo.island.IslandNotificationListener
import cz.pflanzer.foldduo.notifications.BadgeSettings
import cz.pflanzer.foldduo.notifications.NotificationHubSettings
import cz.pflanzer.foldduo.predict.PredictionSettings

/**
 * 17. 9. reorg: everything that shows up on the Today pane (page -1) as its own page — suggestions,
 * the continuity chip, notifications/badges, and where to find Smart Stack's own rotation toggle.
 * Same rows/testTags [AppearanceSettings] used to append at the end of the Wallpaper page.
 */
@Composable
internal fun TodaySettings() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("today-settings")) {
        SuggestionsOnTodayRow()
        ContinuityChipRow()
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        NotificationHubRow()
        ShowSilentNotificationsRow()
        BadgesRow()
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        SmartRotateInfoRow()
        LiveActivitiesSurfaceRow()
        IslandAccessRow()
    }
}

/**
 * B61 "Ostrov kolem kamery" rail hand-off: which surface shows live activities — the camera
 * island built around the cutout (default) or the rail's own pill stack under the status block
 * (`island/RailIsland.kt`, today's behaviour before B61). Both read the exact same `IslandItem`
 * list from [IslandNotificationListener]; this only decides where they are drawn.
 */
@Composable
private fun LiveActivitiesSurfaceRow() {
    val context = LocalContext.current
    var surface by remember { mutableStateOf(CameraIslandSettings.surface(context)) }
    Text("Live activities", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text("Where timers, navigation, calls and playing media show up.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        LiveActivitiesSurface.entries.forEach { option ->
            FilterChip(selected = surface == option, onClick = { surface = option; CameraIslandSettings.setSurface(context, option) },
                label = { Text(if (option == LiveActivitiesSurface.CAMERA_ISLAND) "Camera island" else "Rail") },
                modifier = Modifier.testTag("live-activities-surface-${option.name.lowercase()}"))
        }
    }
}

/**
 * B35 "Předpovědi aplikací": the Today "Návrhy"/"Suggestions" row (`predict/PredictionUi.kt`).
 * Self-contained — its own prefs file ([PredictionSettings]), not threaded through
 * `AppearanceStore`, so this row and `predict/PredictionController` read the same flag without it.
 * On by default.
 */
@Composable
private fun SuggestionsOnTodayRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PredictionSettings.suggestionsEnabled(context)) }
    Text("Suggestions on Today", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Show up to four predicted apps at the top of Today, from time of day, weekday and Wi-Fi/mobile — all on-device, nothing sent anywhere", Modifier.weight(1f))
        Switch(enabled, { enabled = it; PredictionSettings.setSuggestionsEnabled(context, it) },
            Modifier.testTag("suggestions-on-today-switch"))
    }
}

/**
 * B35 "kontinuita": the "Pokračovat: …"/"Continue: …" chip offered after a cover -> inner panel
 * swap for whatever ran on the cover just before it. On by default.
 */
@Composable
private fun ContinuityChipRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PredictionSettings.continuityEnabled(context)) }
    Text("Continuity chip", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Offer to continue whatever was open on the cover when you unfold", Modifier.weight(1f))
        Switch(enabled, { enabled = it; PredictionSettings.setContinuityEnabled(context, it) },
            Modifier.testTag("continuity-chip-switch"))
    }
}

/**
 * "Notifications on Today" (B34, notifications/NotificationHub.kt): off by default since the
 * 2026-09-17 redesign — the always-on cards duplicated the system shade and ate up to half the
 * screen, so [BadgesRow] now carries that job on the icons themselves and this stays an opt-in,
 * collapsed-by-default summary pill (`NotificationHubPill`) that only expands into the old cards
 * on tap.
 */
@Composable
private fun NotificationHubRow() {
    val context = LocalContext.current
    val enabled = NotificationHubSettings.hubEnabled.value
    Text("Notifications on Today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Show a compact notification summary at the top of Today", Modifier.weight(1f))
        Switch(enabled, { NotificationHubSettings.setHubEnabled(context, it) },
            Modifier.testTag("notification-hub-switch"))
    }
    Text("A one-line summary you tap to expand; needs notification access, the same grant Live Updates in the rail uses.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("notification-hub-status"))
}

/**
 * "Show silent notifications": off by default, so a muted channel's notifications stay out of the
 * hub the way they stay out of the status bar. Grouped under, and indented from, [NotificationHubRow]
 * per the 17. 9. reorg (only meaningful while the hub itself is on) — the switch stays interactive
 * either way (flipping it ahead of turning the hub on is harmless) but the row visually reads as a
 * sub-setting.
 */
@Composable
private fun ShowSilentNotificationsRow() {
    val context = LocalContext.current
    val hubEnabled = NotificationHubSettings.hubEnabled.value
    val enabled = NotificationHubSettings.showSilent.value
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Show silent notifications", Modifier.weight(1f),
            color = if (hubEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(enabled, { NotificationHubSettings.setShowSilent(context, it) },
            Modifier.testTag("show-silent-notifications-switch"))
    }
}

/**
 * "Badges" (redesign after the 2026-09-17 feedback): on by default, the small red counts on app
 * icons (Badges.kt/BadgeOverlay.kt) that now carry most of what the always-on hub used to.
 */
@Composable
private fun BadgesRow() {
    val context = LocalContext.current
    val enabled = BadgeSettings.enabled.value
    Text("Badges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Show unread notification counts on app icons", Modifier.weight(1f))
        Switch(enabled, { BadgeSettings.setEnabled(context, it) }, Modifier.testTag("badges-switch"))
    }
    Text("Needs notification access, the same grant Live Updates in the rail uses.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("badges-status"))
}

/**
 * Smart Stack's "Smart rotate" (StackRotation.kt/SmartStack.kt) is per-stack, not a single global
 * default — [cz.pflanzer.foldduo.HomeEditing.smartRotate] lives on each `WidgetPlacement`, toggled
 * from that stack's own long-press menu (SmartStack.kt). No global switch exists to put here, so
 * this is a pointer rather than a duplicate control.
 */
@Composable
private fun SmartRotateInfoRow() {
    Text("Smart Stack rotation", style = MaterialTheme.typography.titleMedium)
    Text("\"Smart rotate\" is set per stack, from that stack's own long-press menu on Home or Today — there is no single switch for every stack.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("smart-rotate-info"))
}

/**
 * Rail island (Live Updates) needs the notification-listener grant, a Settings toggle Android
 * never prompts for. This row shows the current state and opens the listener's Settings page; the
 * state is re-read on every resume so a round trip through Settings updates it.
 */
@Composable
private fun IslandAccessRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(IslandNotificationListener.isAccessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = IslandNotificationListener.isAccessGranted(context)
        IslandNotificationListener.refreshEnabled(context)
        onPauseOrDispose { }
    }
    Text("Live Updates in the rail", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text(if (granted) "Timers, navigation, calls and playing media show as an island under the status block. Tap a pill to expand it, long-press to open the app."
        else "Needs notification access. Duo Launcher only reads ongoing notifications to show them in the rail; nothing is stored or forwarded.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("island-access-status"))
    OutlinedButton(onClick = { IslandNotificationListener.openAccessSettings(context) },
        modifier = Modifier.fillMaxWidth().testTag("island-access")) {
        Text(if (granted) "Notification access settings" else "Allow notification access")
    }
}
