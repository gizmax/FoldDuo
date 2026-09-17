package cz.pflanzer.foldduo.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import cz.pflanzer.foldduo.HapticIntensity
import cz.pflanzer.foldduo.HapticTier
import cz.pflanzer.foldduo.Haptics
import cz.pflanzer.foldduo.MotionPrefs

/**
 * 17. 9. reorg: touch feedback and animation status, one page — the "Haptics" intensity chips
 * [AppearanceSettings] used to append at the end of the Wallpaper page, plus three read-only
 * status lines the task asks for ([MotionPrefs]'s reduce-motion decision, [Haptics]'s resolved
 * device tier, and 120 Hz-during-morph, which is simply always on — HighRefreshRate.kt requests
 * it unconditionally for every morph/systemfrost episode, no setting gates it).
 */
@Composable
internal fun MotionHapticsSettings() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("motion-haptics-settings")) {
        HapticsIntensityRow()
        Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        val reduceMotion = MotionPrefs.enabled.value
        Text("Reduce motion: ${if (reduceMotion) "on" else "off"} (system)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("reduce-motion-status"))
        val tier = remember { Haptics.tier(context) }
        Text("Haptics tier: ${tier.label()}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("haptics-tier-status"))
        Text("120 Hz during morph: on",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("high-refresh-rate-status"))
    }
}

private fun HapticTier.label(): String = when (this) {
    HapticTier.PRIMITIVES -> "primitives"; HapticTier.PREDEFINED -> "predefined"; HapticTier.CONSTANTS -> "constants"
}

/**
 * B36 "Haptický jazyk": one dial over every named `HapticEvent` in [Haptics] — `OFF` mutes the
 * whole vocabulary, `LIGHT`/`NORMAL`/`STRONG` scale the `VibrationEffect.Composition` amplitudes.
 * Self-contained: reads/writes its own `appearance` prefs key ([Haptics.intensity] /
 * [Haptics.setIntensity]) so every call site can read it without threading it through
 * `AppearanceState`.
 */
@Composable
private fun HapticsIntensityRow() {
    val context = LocalContext.current
    var intensity by remember { mutableStateOf(Haptics.intensity(context)) }
    Text("Haptics", style = MaterialTheme.typography.titleMedium)
    Text("How strongly the launcher's touch language (pages, drag, folders, stack, island, StandBy) taps back.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HapticIntensity.entries.forEach { level ->
            FilterChip(selected = intensity == level, onClick = { intensity = level; Haptics.setIntensity(context, level) },
                label = { Text(when (level) {
                    HapticIntensity.OFF -> "Off"; HapticIntensity.LIGHT -> "Light"
                    HapticIntensity.NORMAL -> "Normal"; HapticIntensity.STRONG -> "Strong"
                }) }, modifier = Modifier.testTag("haptics-intensity-${level.name.lowercase()}"))
        }
    }
}
