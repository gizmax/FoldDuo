package cz.pflanzer.foldduo.standby

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import cz.pflanzer.foldduo.desk.DeskMode
import cz.pflanzer.foldduo.desk.DeskPrefs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.pflanzer.foldduo.rememberPermissionRequest

/**
 * The "StandBy" section of the customization sheet: master toggle (runs [StandByService]),
 * "Also when closed on a table", face order, the overlay permission and a preview.
 */
@Composable
internal fun StandBySettings() {
    val context = LocalContext.current
    val prefs = remember { StandByPrefs(context) }
    val deskPrefs = remember { DeskPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var closedOnTable by remember { mutableStateOf(prefs.closedOnTable) }
    var faces by remember { mutableStateOf(prefs.faces) }
    var nightMode by remember { mutableStateOf(prefs.nightMode) }
    var sleepHoursEnabled by remember { mutableStateOf(prefs.sleepHoursEnabled) }
    var sleepStart by remember { mutableStateOf(prefs.sleepStartMinute) }
    var sleepEnd by remember { mutableStateOf(prefs.sleepEndMinute) }
    var worldA by remember { mutableStateOf(prefs.worldClockZoneA) }
    var worldB by remember { mutableStateOf(prefs.worldClockZoneB) }
    var deskMode by remember { mutableStateOf(deskPrefs.mode) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // The foreground service's notification needs POST_NOTIFICATIONS on 33+; the service runs without it too.
    val notifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS)
    LifecycleResumeEffect(Unit) {
        overlay = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    Column(Modifier.testTag("standby-settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tent the closed Fold and the cover shows a clock, your calendar or a photo, with no charger. Lift it and you’re back.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StandBySwitch("StandBy in Tent", enabled, "standby-enabled") { on ->
            enabled = on; prefs.enabled = on
            if (on) { notifications.request(); StandByService.start(context) } else StandByService.stop(context)
        }
        StandBySwitch("Also when closed on a table", closedOnTable, "standby-closed", enabled = enabled) {
            closedOnTable = it; prefs.closedOnTable = it
        }
        Text("Starts after 4 seconds of rest with the cover on; leaves when you pick the phone up.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Faces", style = MaterialTheme.typography.titleSmall)
        val rows = faces + FaceOrder.DEFAULT.filter { it !in faces }
        rows.forEach { face ->
            val shown = face in faces
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(shown, { on -> faces = FaceOrder.toggle(faces, face, on); prefs.faces = faces },
                    Modifier.testTag("standby-face-${face.name.lowercase()}"))
                Text(face.name, Modifier.weight(1f))
                IconButton(onClick = { faces = FaceOrder.moveUp(faces, face); prefs.faces = faces },
                    enabled = shown && faces.indexOf(face) > 0,
                    modifier = Modifier.semantics { contentDescription = "Move ${face.name} up" }) {
                    Icon(Icons.Rounded.ArrowUpward, null)
                }
            }
        }
        Text("Swipe between faces on the cover. Double tap or swipe up to leave.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // StandBy v2 (17. 9. "StandBy ve stanu"): the Tent (landscape) cover gets two swipeable
        // stacks instead of the plain face pager; night mode and the world-clock cities apply to
        // that new screen (StandByStacksUi.kt's StandByV2Screen), not to Closed-on-a-table.
        Text("Night mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightMode.entries.forEach { mode ->
                FilterChip(selected = nightMode == mode, onClick = { nightMode = mode; prefs.nightMode = mode },
                    label = { Text(when (mode) {
                        NightMode.Auto -> "Auto"; NightMode.Always -> "Always"; NightMode.Off -> "Off"
                    }) }, modifier = Modifier.testTag("standby-night-mode-${mode.name.lowercase()}"))
            }
        }
        val sleepHoursApply = nightMode == NightMode.Auto
        StandBySwitch("Sleep hours", sleepHoursEnabled, "standby-sleep-enabled", enabled = sleepHoursApply) { on ->
            sleepHoursEnabled = on; prefs.sleepHoursEnabled = on
        }
        if (sleepHoursApply && sleepHoursEnabled) {
            MinuteStepper("From", sleepStart, "standby-sleep-start") { sleepStart = it; prefs.sleepStartMinute = it }
            MinuteStepper("To", sleepEnd, "standby-sleep-end") { sleepEnd = it; prefs.sleepEndMinute = it }
        }
        Text("Auto dims and tints red when it's dark or during sleep hours, and shows just the next alarm; tap to wake for 10 seconds. " +
            "Always keeps the whole cover — clock, widgets, dots — pure black and red all the time; a long press on the moon glyph in " +
            "StandBy forces this for just the current session.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("World clock", style = MaterialTheme.typography.titleSmall)
        WorldCityPicker("City A", worldA, "standby-world-a") { worldA = it; prefs.worldClockZoneA = it }
        WorldCityPicker("City B", worldB, "standby-world-b") { worldB = it; prefs.worldClockZoneB = it }
        // B47 "Stůl": Fold 8 has no Flex mode; standing the open phone up like a laptop (Stand
        // pose) replaces it in the launcher, held for 1.2 s.
        Text("Desk when standing", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeskMode.entries.forEach { desk ->
                FilterChip(selected = deskMode == desk, onClick = { deskMode = desk; deskPrefs.mode = desk },
                    label = { Text(when (desk) {
                        DeskMode.Off -> "Off"; DeskMode.Desk -> "Desk"; DeskMode.StandByFace -> "StandBy face"
                    }) }, modifier = Modifier.testTag("desk-mode-${desk.name.lowercase()}"))
            }
        }
        Text("Prop the open Fold up like a laptop and hold still for a moment: the cover half shows a clock and widgets, the bottom half a control deck (media, timer, notes, calculator). \"StandBy face\" shows just the plain StandBy face instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (overlay) "Allowed over other apps: StandBy can also appear over the lock screen."
            else "Without “Display over other apps” StandBy only starts while Home is in front. Allow it so it also appears over the lock screen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!overlay) OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("standby-overlay")) { Text("Allow over other apps") }
        Button(onClick = { context.startActivity(StandByActivity.intent(context, preview = true)) },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("standby-preview")) { Text("Preview StandBy") }
    }
}

@Composable
private fun StandBySwitch(label: String, checked: Boolean, tag: String, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChecked, Modifier.testTag(tag), enabled = enabled)
    }
}

/** A "23:00" readout with -/+ 30 min steppers, wrapping at midnight ([Math.floorMod]). */
@Composable
private fun MinuteStepper(label: String, minute: Int, tag: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text("−", Modifier.padding(horizontal = 12.dp).testTag("$tag-down")
            .clickable { onChange(Math.floorMod(minute - 30, 1440)) }, fontSize = 20.sp)
        Text("%02d:%02d".format(minute / 60, minute % 60), Modifier.testTag(tag))
        Text("+", Modifier.padding(horizontal = 12.dp).testTag("$tag-up")
            .clickable { onChange(Math.floorMod(minute + 30, 1440)) }, fontSize = 20.sp)
    }
}

/** Cycles through [WorldClockCities.PRESETS] with ‹/› arrows; [zoneId] outside the preset list shows the first one. */
@Composable
private fun WorldCityPicker(label: String, zoneId: String?, tag: String, onChange: (String) -> Unit) {
    val presets = WorldClockCities.PRESETS
    val index = presets.indexOfFirst { it.zoneId == zoneId }.let { if (it < 0) 0 else it }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text("‹", Modifier.padding(horizontal = 8.dp).testTag("$tag-prev")
            .clickable { onChange(presets[Math.floorMod(index - 1, presets.size)].zoneId) }, fontSize = 20.sp)
        Text(presets[index].label, Modifier.padding(horizontal = 8.dp).testTag(tag))
        Text("›", Modifier.padding(horizontal = 8.dp).testTag("$tag-next")
            .clickable { onChange(presets[Math.floorMod(index + 1, presets.size)].zoneId) }, fontSize = 20.sp)
    }
}
