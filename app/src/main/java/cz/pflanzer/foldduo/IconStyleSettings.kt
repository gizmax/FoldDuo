@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cz.pflanzer.foldduo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Icons" block of the Home layout page: shape, glass effect, icon pack, refresh. */
@Composable
internal fun IconStyleSettings(style: IconStyle, model: LauncherModel) {
    val context = LocalContext.current
    var packs by remember { mutableStateOf<List<IconPackInfo>?>(null) }
    LaunchedEffect(style.pack) { packs = withContext(Dispatchers.IO) { installedIconPacks(context.applicationContext) } }
    Column(Modifier.fillMaxWidth().testTag("icon-style-settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Icons", style = MaterialTheme.typography.titleMedium)
        Text("Shape", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconShape.entries.forEach { shape ->
                FilterChip(style.shape == shape, { model.setIconShape(shape) }, label = { Text(shape.title) },
                    leadingIcon = {
                        Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .75f), iconComposeShape(shape)))
                    }, modifier = Modifier.testTag("icon-shape-${shape.name.lowercase()}"))
            }
        }
        Text("Effect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconEffect.entries.forEach { effect ->
                FilterChip(style.effect == effect, { model.setIconEffect(effect) }, label = { Text(effect.title) },
                    leadingIcon = { EffectPreview(effect, style.shape) },
                    modifier = Modifier.testTag("icon-effect-${effect.name.lowercase()}"))
            }
        }
        if (style.effect == IconEffect.ClearGlass) LiquidGlassRow(style.liquidGlass, model::setLiquidGlass)
        LiveIconsRow(style.liveIcons, model::setLiveIcons)
        Text("Icon pack", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val installed = packs.orEmpty()
        val selected = style.pack?.takeIf { pkg -> packs == null || installed.any { it.packageName == pkg } }
        IconPackRow(null, "System icons", selected == null) { model.setIconPack(null) }
        installed.forEach { pack ->
            IconPackRow(pack, pack.label, selected == pack.packageName) { model.setIconPack(pack.packageName) }
        }
        if (packs != null && installed.isEmpty()) Text(
            "Samsung Good Lock icon themes only apply to Samsung Home; icon packs from Google Play work here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("icon-pack-note"))
        TextButton(onClick = model::refreshIcons, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("icon-refresh")) {
            Text("Refresh icons")
        }
    }
}

/**
 * "Liquid Glass" (IDEAS.md B41): the Clear-glass backing (icons, dock, folder tiles) refracts a
 * live crop of the wallpaper instead of a flat tint. On by default for Clear glass; irrelevant
 * (and hidden) for the other effects, since [IconStyle.usesLiquidGlass] never applies then.
 */
@Composable
private fun LiquidGlassRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Liquid Glass", Modifier.weight(1f))
        Switch(enabled, onChange, Modifier.testTag("liquid-glass-switch"))
    }
}

/**
 * "Live icons" (IDEAS.md B49): real analog clock hands, today's date, a battery ring and a
 * compass needle drawn over a handful of known system apps' icons. On by default, independent of
 * shape/effect/pack (see [LiveIconOverlay]).
 */
@Composable
private fun LiveIconsRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Live icons", Modifier.weight(1f))
        Switch(enabled, onChange, Modifier.testTag("live-icons-switch"))
    }
}

@Composable
private fun EffectPreview(effect: IconEffect, shape: IconShape) {
    val outline = iconComposeShape(shape)
    val base = Modifier.size(16.dp).clip(outline)
    when (effect) {
        IconEffect.None -> Box(base.background(MaterialTheme.colorScheme.primary))
        IconEffect.Glass -> Box(base.background(MaterialTheme.colorScheme.primary.copy(alpha = .28f))
            .border(1.dp, Color.White.copy(alpha = .7f), outline))
        IconEffect.ClearGlass -> Box(base.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .35f), outline))
    }
}

@Composable
private fun IconPackRow(pack: IconPackInfo?, label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.RadioButton, onClick = onSelect).padding(horizontal = 6.dp)
        .testTag("icon-pack-${pack?.packageName ?: "system"}"), verticalAlignment = Alignment.CenterVertically) {
        val icon = pack?.icon
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        else Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, iconComposeShape(IconShape.RoundedSquare)))
        Text(label, Modifier.weight(1f).padding(start = 12.dp))
        RadioButton(selected, onClick = null)
    }
}
