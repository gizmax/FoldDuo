package cz.pflanzer.foldduo.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.ALL_MODE_ID
import cz.pflanzer.foldduo.HomeMode
import cz.pflanzer.foldduo.LauncherModel
import cz.pflanzer.foldduo.LauncherState
import cz.pflanzer.foldduo.ModeSchedule

/**
 * 17. 9. reorg: "Přehled stránek a režimy plochy" (B39) gets its own settings page — a button that
 * opens the pinch-out [cz.pflanzer.foldduo.PageOverviewOverlay] ("Přidat stránku"/"Odebrat
 * stránku" stay in Home layout, unchanged, per the task) plus a plain list of [HomeMode]s reusing
 * [LauncherModel]'s own rename/delete/switch APIs — the same ones the overlay's mode-chip row
 * calls, just as rows instead of chips, with a schedule summary line.
 */
@Composable
internal fun PagesModesSettings(state: LauncherState, model: LauncherModel, onOpenPageOverview: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pages-modes-settings")) {
        Text("Page overview", style = MaterialTheme.typography.titleMedium)
        Text("Reorder, hide, or remove Home pages, and switch between modes, from the same overlay a pinch-out on Home opens.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenPageOverview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("open-page-overview")) { Text("Open page overview") }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Home modes", style = MaterialTheme.typography.titleMedium)
        Text("A mode is a named set of visible Home pages; \"Vše\" always shows every page.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
        var renameText by rememberSaveable { mutableStateOf("") }
        state.homeModes.forEach { mode ->
            ModeRow(mode, active = mode.id == state.activeModeId, renaming = renamingId == mode.id, renameText = renameText,
                onRenameTextChange = { renameText = it },
                onSwitch = { model.switchMode(mode.id) },
                onStartRename = { renameText = mode.name; renamingId = mode.id },
                onCommitRename = { model.renameMode(mode.id, renameText.trim().ifBlank { mode.name }); renamingId = null },
                onDelete = { model.deleteMode(mode.id) })
        }
        var creating by rememberSaveable { mutableStateOf(false) }
        var newName by rememberSaveable { mutableStateOf("") }
        if (creating) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("pages-modes-new-field"),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) model.createMode(newName.trim()); newName = ""; creating = false
                    }))
            }
        } else {
            TextButton(onClick = { creating = true }, modifier = Modifier.testTag("pages-modes-add-mode")) {
                Icon(Icons.Rounded.Add, null); Text(" Add mode")
            }
        }
    }
}

@Composable
private fun ModeRow(mode: HomeMode, active: Boolean, renaming: Boolean, renameText: String,
    onRenameTextChange: (String) -> Unit, onSwitch: () -> Unit, onStartRename: () -> Unit,
    onCommitRename: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("pages-modes-mode-${mode.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (renaming) {
                OutlinedTextField(value = renameText, onValueChange = onRenameTextChange, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("pages-modes-rename-field-${mode.id}"),
                    keyboardActions = KeyboardActions(onDone = { onCommitRename() }))
            } else {
                Text(mode.name, Modifier.weight(1f).testTag("pages-modes-name-${mode.id}"),
                    fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            if (!active && !renaming) TextButton(onClick = onSwitch, Modifier.testTag("pages-modes-switch-${mode.id}")) { Text("Switch") }
            if (mode.id != ALL_MODE_ID) {
                IconButton(onClick = onStartRename, Modifier.testTag("pages-modes-rename-${mode.id}")) {
                    Icon(Icons.Rounded.Edit, "Rename ${mode.name}")
                }
                IconButton(onClick = onDelete, Modifier.testTag("pages-modes-delete-${mode.id}")) {
                    Icon(Icons.Rounded.Close, "Delete ${mode.name}")
                }
            }
        }
        if (mode.id != ALL_MODE_ID) Text(formatModeSchedule(mode.schedule),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp).testTag("pages-modes-schedule-${mode.id}"))
    }
}

private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Pure formatting (no Android types, JVM-testable): the schedule summary line under a mode row.
 * `null` reads as manual-only; every weekday reads as "Every day" instead of listing all seven.
 */
internal fun formatModeSchedule(schedule: ModeSchedule?): String {
    if (schedule == null) return "No schedule — switch manually from the page overview"
    val days = if (schedule.weekdays.size == 7) "Every day"
        else schedule.weekdays.sorted().joinToString(", ") { WEEKDAY_LABELS[(it - 1).coerceIn(0, 6)] }
    return "$days, ${minuteLabel(schedule.startMinute)}–${minuteLabel(schedule.endMinute)}"
}

private fun minuteLabel(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, 1439)
    return "%02d:%02d".format(clamped / 60, clamped % 60)
}
