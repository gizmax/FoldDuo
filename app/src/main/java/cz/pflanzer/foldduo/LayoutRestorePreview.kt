package cz.pflanzer.foldduo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LayoutRestorePreview(preview: LayoutImportPreview, onRestore: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier) {
    AlertDialog(onDismissRequest = onCancel, modifier = modifier.testTag("layout-restore-preview"),
        title = { Text("Review restored layout") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${preview.appCount} apps · ${preview.folderCount} folders · ${preview.pairCount} pairs · ${preview.widgetCount} widgets")
                if (preview.layout.leadingSlots.any { it != null } || preview.layout.widgetPlacements.any { it.page == -1 })
                    Text("Includes your unfolded-only page.", style = MaterialTheme.typography.bodySmall)
                Text("This also restores icon layout, labels, search, and status settings.")
                Text("Your selected launcher background photo is not included in layout backups.",
                    style = MaterialTheme.typography.bodySmall)
                if (preview.missingApps.isNotEmpty()) {
                    Text("Unavailable apps (${preview.missingApps.size})", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    preview.missingApps.forEach { saved ->
                        val label = saved.substringAfterLast('(').removeSuffix(")").takeIf { it.isNotBlank() } ?: "Unavailable app"
                        Text("• $label — its saved position will stay empty")
                    }
                }
                if (preview.profileIssues.isNotEmpty()) {
                    Text("Profile attention", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    preview.profileIssues.forEach { Text("• $it") }
                }
                val reconnect = preview.layout.widgetPlacements.count { it.id == NEEDS_BINDING_WIDGET }
                if (reconnect > 0) Text("$reconnect widget${if (reconnect == 1) "" else "s"} will keep their saved space and ask to reconnect after restore.")
                Text("Nothing changes until you choose Restore.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(onClick = onRestore, modifier = Modifier.testTag("layout-restore-apply")) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("layout-restore-cancel")) { Text("Cancel") } })
}
