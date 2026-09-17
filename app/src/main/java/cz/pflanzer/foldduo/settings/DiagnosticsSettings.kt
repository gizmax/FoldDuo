package cz.pflanzer.foldduo.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.pflanzer.foldduo.PerfHistory
import cz.pflanzer.foldduo.SystemShadeAccessibilityService
import cz.pflanzer.foldduo.hasUsageAccess
import cz.pflanzer.foldduo.island.IslandNotificationListener

private const val EPISODE_ROWS = 8

/**
 * 17. 9. reorg's last overview tile: B43 "Výkon jako feature"'s numbers plus every permission
 * status line scattered across the other new pages, gathered in one place for troubleshooting —
 * this is the only page that reads [PerfHistory] directly rather than through a settings row.
 */
@Composable
internal fun DiagnosticsSettings(onResetMorphPreview: () -> Unit) {
    val context = LocalContext.current
    var accessibilityGranted by remember { mutableStateOf(SystemShadeAccessibilityService.isEnabled(context)) }
    var notificationGranted by remember { mutableStateOf(IslandNotificationListener.isAccessGranted(context)) }
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    LifecycleResumeEffect(Unit) {
        accessibilityGranted = SystemShadeAccessibilityService.isEnabled(context)
        notificationGranted = IslandNotificationListener.isAccessGranted(context)
        usageGranted = hasUsageAccess(context)
        onPauseOrDispose { }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("diagnostics-settings")) {
        Text("Frame stats", style = MaterialTheme.typography.titleMedium)
        val startup = PerfHistory.startup
        Text("Cold start → first frame: ${startup?.coldStartToFirstFrameMs?.let { "${it} ms" } ?: "not recorded yet"}" +
            (startup?.firstFrameToInteractiveMs?.let { " · first frame → interactive: $it ms" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diagnostics-startup"))
        val episodes = remember { PerfHistory.episodes(EPISODE_ROWS) }
        if (episodes.isEmpty()) {
            Text("No frame-stats episodes recorded yet this session.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("diagnostics-episodes-empty"))
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text("Episode", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium)
                Text("Frames", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("p95", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Jank", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
            episodes.asReversed().forEach { episode ->
                Row(Modifier.fillMaxWidth().testTag("diagnostics-episode-${episode.label}-${episode.atElapsedMs}")) {
                    Text(episode.label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                    Text("${episode.stats.frames}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${"%.1f".format(java.util.Locale.ROOT, episode.stats.p95Ms)} ms", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${episode.stats.jankCount}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        PermissionStatusRow("Accessibility service (Frost over other apps, shade gestures)", accessibilityGranted,
            "diagnostics-accessibility") { SystemShadeAccessibilityService.openAccessibilitySettings(context) }
        PermissionStatusRow("Notification access (Live Updates, notification hub, badges)", notificationGranted,
            "diagnostics-notifications") { IslandNotificationListener.openAccessSettings(context) }
        PermissionStatusRow("Usage access (Suggestions on Today, recents)", usageGranted,
            "diagnostics-usage") {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Tuning", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { PerfHistory.logSummary() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("diagnostics-dump-perf")) { Text("Dump perf to logcat") }
        OutlinedButton(onClick = onResetMorphPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("diagnostics-reset-morph")) { Text("Reset morph tuning") }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, tag: String, onOpenSettings: () -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ${if (granted) "granted" else "not granted"}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$tag-status"))
        if (!granted) OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().testTag("$tag-open")) {
            Text("Open settings")
        }
    }
}
