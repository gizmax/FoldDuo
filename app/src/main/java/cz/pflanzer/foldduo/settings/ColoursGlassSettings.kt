package cz.pflanzer.foldduo.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.pflanzer.foldduo.IconStyle
import cz.pflanzer.foldduo.IconStyleSettings
import cz.pflanzer.foldduo.LauncherModel
import cz.pflanzer.foldduo.colorsFromWallpaperEnabled
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_DEFAULT
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_MAX
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_MIN
import cz.pflanzer.foldduo.island.ISLAND_OPACITY_STEP
import cz.pflanzer.foldduo.island.IslandStyle
import cz.pflanzer.foldduo.island.islandOpacitySnapped
import cz.pflanzer.foldduo.island.islandPillVeilAlpha
import cz.pflanzer.foldduo.isLiveWallpaperActive
import cz.pflanzer.foldduo.openLiveWallpaperPreview
import cz.pflanzer.foldduo.setColorsFromWallpaperEnabled
import cz.pflanzer.foldduo.setWallpaperPulsesEnabled
import cz.pflanzer.foldduo.wallpaperPulsesEnabled
import kotlin.math.roundToInt

/**
 * 17. 9. reorg: "Colours from wallpaper" and "Live wallpaper" (both used to sit at the end of the
 * Wallpaper page) plus the icon-style block ([IconStyleSettings], including Liquid Glass) that
 * used to sit at the end of Home layout — everything that colours or textures the launcher, one
 * page.
 */
@Composable
internal fun ColoursGlassSettings(iconStyle: IconStyle, model: LauncherModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("colours-glass-settings")) {
        ColorsFromWallpaperRow()
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        IconStyleSettings(iconStyle, model)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LiveWallpaperRow()
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        WallpaperPulseRow()
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        IslandTransparencyRow()
    }
}

/**
 * "Colours from wallpaper" (B29 "Barvy z tapety"): on by default, self-contained — its own
 * `appearance` prefs key ([colorsFromWallpaperEnabled]/[setColorsFromWallpaperEnabled],
 * WallpaperPalette.kt) rather than going through `AppearanceStore`. Off keeps today's fixed
 * colours (Material's own light/dark scheme, white glass, white rail ink).
 */
@Composable
private fun ColorsFromWallpaperRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(colorsFromWallpaperEnabled(context)) }
    Text("Colours from wallpaper", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Tint the launcher, glass and rail text from the current wallpaper", Modifier.weight(1f))
        Switch(enabled, { enabled = it; setColorsFromWallpaperEnabled(context, it) }, Modifier.testTag("colors-from-wallpaper-switch"))
    }
    Text("Uses the system's own wallpaper colours where available, or samples the background photo otherwise. " +
        "Off keeps the fixed look.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("colors-from-wallpaper-status"))
}

/**
 * Continuum on the lock screen: the launcher background as Android's live wallpaper
 * (DuneWallpaperService). The system preview does the setting; whether it is set is re-read on
 * every resume so a round trip through the chooser updates the row.
 */
@Composable
private fun LiveWallpaperRow() {
    val context = LocalContext.current
    var active by remember { mutableStateOf(isLiveWallpaperActive(context)) }
    var unavailable by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        active = isLiveWallpaperActive(context)
        onPauseOrDispose { }
    }
    Text("Live wallpaper", style = MaterialTheme.typography.titleMedium)
    Text((if (active) "Fold Duo is the live wallpaper. " else "") +
        "One picture on both screens: the cover shows the right half of the unfolded one, and the " +
        "left half frosts in on every unfold. As the live wallpaper this unfold morph shows on the " +
        "lock screen and under Samsung Home too, not only in this launcher.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("live-wallpaper-status"))
    OutlinedButton(onClick = { unavailable = !openLiveWallpaperPreview(context) },
        modifier = Modifier.fillMaxWidth().testTag("live-wallpaper")) {
        Text(if (active) "Live wallpaper settings" else "Set as live wallpaper")
    }
    if (unavailable) Text("The system wallpaper preview is unavailable.", color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("live-wallpaper-error"))
}

/**
 * "Wallpaper pulses with notifications" (B50 "Tapeta dýchá s oznámením"): on by default, its own
 * `appearance` prefs key ([wallpaperPulsesEnabled]/[setWallpaperPulsesEnabled], DuneWallpaper.kt)
 * like the wallpaper's other toggles. The setting only gates whether the glow is drawn; reduced
 * motion and StandBy showing turn it off too, regardless of this switch — see DuneWallpaper.kt's
 * `rememberEdgePulse`/`pulseEffect`.
 */
@Composable
private fun WallpaperPulseRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(wallpaperPulsesEnabled(context)) }
    Text("Wallpaper pulses with notifications", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("A soft glow from the top edge in the app's own colour, on both screens", Modifier.weight(1f))
        Switch(enabled, { enabled = it; setWallpaperPulsesEnabled(context, it) }, Modifier.testTag("wallpaper-pulse-switch"))
    }
    Text("Off automatically with reduced motion, or while StandBy is showing.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("wallpaper-pulse-status"))
}

/**
 * "Glass transparency" (renamed 17. 9. noc from "Island transparency" — Tom: "všechny ty tabletky
 * musí brát opacity, co je nastavená v settings, default 75 %; ... všechno to musí být takové
 * matné průhledné sklo"): one slider now scales the veil of every pill and card in the app —
 * island pills/card, the status pill and ring, the notification hub pill, the seam palette panel,
 * the edit-mode "+"/"Done" controls, the Spotlight field, the continuity chip, the tent-tilt toast
 * ([Modifier.glassPill], `GlassPill.kt`, and `island/IslandCardMotion.kt`'s
 * `islandPillVeilAlpha`/`islandCardVeilAlpha`/`islandCardGradientAlpha` it delegates to) — text,
 * artwork and controls stay fully opaque either way. Self-contained like the other rows on this
 * page, but backed by [IslandStyle] (its own `appearance` prefs key, still `islandOpacity` — kept
 * unchanged by the rename so an existing persisted value survives) rather than a local `remember`,
 * so a change here is visible everywhere immediately, in the same session, without reopening the
 * launcher — same live-[androidx.compose.runtime.State] need as [cz.pflanzer.foldduo.MotionPrefs].
 */
@Composable
private fun IslandTransparencyRow() {
    val context = LocalContext.current
    val opacity by IslandStyle.current
    Text("Glass transparency", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("How much of the wallpaper shows through every pill and card in the app", Modifier.weight(1f))
        IslandTransparencyPreviewPill(opacity)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Transparency", Modifier.weight(1f))
        Text("${(opacity * 100).roundToInt()} %", color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("island-transparency-value"))
    }
    Slider(opacity, { value -> IslandStyle.setOpacity(context, islandOpacitySnapped(value)) },
        valueRange = ISLAND_OPACITY_MIN..ISLAND_OPACITY_MAX,
        steps = ((ISLAND_OPACITY_MAX - ISLAND_OPACITY_MIN) / ISLAND_OPACITY_STEP).roundToInt() - 1,
        modifier = Modifier.testTag("island-transparency-slider")
            .semantics { contentDescription = "Glass transparency" })
    TextButton(onClick = { IslandStyle.setOpacity(context, ISLAND_OPACITY_DEFAULT) },
        modifier = Modifier.testTag("island-transparency-reset")) { Text("Reset") }
    Text("Default 75 %. 100 % is today's fixed glass; 40 % is the floor for legible text.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("island-transparency-status"))
}

/** A tiny always-visible pill next to the row's description, filled at the real veil alpha the slider is about to set — the same [islandPillVeilAlpha] rule the rail island itself uses on its `.55f` base. */
@Composable
private fun IslandTransparencyPreviewPill(opacity: Float) {
    Row(Modifier.width(40.dp).height(20.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.primary.copy(alpha = islandPillVeilAlpha(opacity, .55f)))
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .28f), RoundedCornerShape(10.dp))
        .testTag("island-transparency-preview")) {}
}
