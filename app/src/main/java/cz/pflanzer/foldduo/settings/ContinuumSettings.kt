package cz.pflanzer.foldduo.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cz.pflanzer.foldduo.AppearanceState
import cz.pflanzer.foldduo.DuneWallpaper
import cz.pflanzer.foldduo.HingeSqueezeAction
import cz.pflanzer.foldduo.LocalMorphController
import cz.pflanzer.foldduo.MorphCurve
import cz.pflanzer.foldduo.MorphPreviewTuning
import cz.pflanzer.foldduo.SystemShadeAccessibilityService
import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldEffectCache
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.continuum.foldEffect

/**
 * 17. 9. reorg: the fold-morph tuning rows [AppearanceSettings] used to append at the end of the
 * Wallpaper page, now their own "Continuum" page — same rows, same testTags, same
 * [cz.pflanzer.foldduo.CustomizationSheet]'s onAppearance* callback plumbing.
 */
@Composable
internal fun ContinuumSettings(state: AppearanceState, onMotionFrost: (Boolean) -> Unit,
    onSystemFrost: (Boolean) -> Unit, onOpenedOverride: (Boolean) -> Unit, onHapticAtFlat: (Boolean) -> Unit,
    onMorphFrostFactor: (Float) -> Unit, onMorphTiltFactor: (Float) -> Unit, onResetMorphPreview: () -> Unit,
    onHingeSqueeze: (HingeSqueezeAction) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("continuum-settings")) {
        MorphPreviewCard(state.morphFrostFactor, state.morphTiltFactor, onMorphFrostFactor, onMorphTiltFactor, onResetMorphPreview)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        SystemFrostRow(state.systemFrost, onSystemFrost)
        OpenedOverrideRow(state.openedOverride, onOpenedOverride)
        MotionFrostRow(state.motionFrost, onMotionFrost)
        HapticAtFlatRow(state.hapticAtFlat, onHapticAtFlat)
        SheetBendRow()
        WallpaperDepthRow()
        HingeSqueezeRow(state.hingeSqueezeAction, onHingeSqueeze)
        Text("Pacing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("The inner half's clear plays for a minimum of ${MorphCurve.DURATION_MS} ms, so it reads as glass " +
            "clearing rather than content arriving underneath it.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("continuum-pacing-status"))
    }
}

/**
 * B20, "Náhled morphu v nastavení": lets Tom see and tune the unfold's curves without folding the
 * phone. The angle slider drives [LocalMorphController]'s existing debug hook
 * ([cz.pflanzer.foldduo.MorphController.debugAngle], the same one `DEBUG_MORPH --ef angle` uses)
 * continuously while dragging, so on the inner display the real frost layers behind this
 * side-anchored sheet play along; the thumbnail below repeats the same tilt on a small
 * always-visible crop so the effect is visible from the cover too, where the sheet covers the
 * frost. "Frost intensity" / "Tilt" persist to [AppearanceState] and are read by both the
 * launcher's frost layers (LauncherScreen.kt) and the system-wide overlay
 * (systemfrost/SystemFrost.kt) through [MorphPreviewTuning].
 */
@Composable
private fun MorphPreviewCard(frostFactor: Float, tiltFactor: Float, onFrostFactor: (Float) -> Unit,
    onTiltFactor: (Float) -> Unit, onReset: () -> Unit) {
    val morph = LocalMorphController.current
    var previewAngle by remember { mutableStateOf(90f) }
    Text("Morph preview", style = MaterialTheme.typography.titleMedium)
    Text("Drag the angle to preview the unfold's frost without folding the phone.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        MorphPreviewThumbnail(previewAngle, frostFactor, tiltFactor)
    }
    Row { Text("Hinge angle", Modifier.weight(1f)); Text("${previewAngle.toInt()}°", color = MaterialTheme.colorScheme.primary) }
    Slider(previewAngle, { angle -> previewAngle = angle; morph?.debugAngle(angle, MORPH_PREVIEW_HOLD_MS) },
        valueRange = 0f..180f, modifier = Modifier.testTag("morph-preview-angle")
            .semantics { contentDescription = "Morph preview angle" })
    Row { Text("Frost intensity", Modifier.weight(1f)); Text("${(frostFactor * 100).toInt()}%", color = MaterialTheme.colorScheme.primary) }
    Slider(frostFactor, onFrostFactor, valueRange = MorphPreviewTuning.MIN_FACTOR..MorphPreviewTuning.MAX_FACTOR,
        modifier = Modifier.testTag("morph-preview-frost").semantics { contentDescription = "Frost intensity" })
    Row { Text("Tilt", Modifier.weight(1f)); Text("${(tiltFactor * 100).toInt()}%", color = MaterialTheme.colorScheme.primary) }
    Slider(tiltFactor, onTiltFactor, valueRange = MorphPreviewTuning.MIN_FACTOR..MorphPreviewTuning.MAX_FACTOR,
        modifier = Modifier.testTag("morph-preview-tilt").semantics { contentDescription = "Tilt" })
    TextButton(onClick = onReset, modifier = Modifier.testTag("morph-preview-reset")) { Text("Reset") }
}

/** Debug preview holds each dragged angle this long before handing back to the real sensor (UnfoldMorph.kt's `debugAngle`); short enough that a slow drag never snaps back to sharp between two samples. */
private const val MORPH_PREVIEW_HOLD_MS = 1_500L

/**
 * The always-visible 160×120 dp crop the card shows even while this side-anchored sheet covers
 * the cover's own frost layer (CustomizationSheet.kt is anchored on the inner display; on the
 * cover the sheet fills the screen). Same shader, same [cz.pflanzer.foldduo.continuum.foldEffect]
 * plumbing as the real layers, an independent [FoldEffectCache] so it never fights their cached
 * effect.
 */
@Composable
private fun MorphPreviewThumbnail(angleDeg: Float, frostFactor: Float, tiltFactor: Float) {
    val context = LocalContext.current
    val shader = remember(context) { FoldShader.shared(context) }
    val pxPerMm = remember(context) { FoldShader.pxPerMm(context) }
    val cache = remember { FoldEffectCache() }
    val config = remember(frostFactor) {
        MorphPreviewTuning.buildConfig(FoldConfig(foldSplitsLong = true), frostFactor, MorphPreviewTuning.DEFAULT_FACTOR)
    }
    val tilt = MorphPreviewTuning.previewTilt(angleDeg, tiltFactor)
    Box(Modifier.size(width = 160.dp, height = 120.dp)
        .clip(RoundedCornerShape(12.dp)).testTag("morph-preview-thumbnail")) {
        DuneWallpaper()
        if (shader != null) Box(Modifier.matchParentSize()
            .foldEffect(shader, tilt = { tilt }, config = config, pxPerMm = pxPerMm, fold = null, cache = cache))
    }
}

/**
 * B16, "Život po vyčištění": a single `EFFECT_TICK` (UnfoldMorph.kt's settleGeneration) when the
 * left half's unfold morph reaches Flat, at most once per unfold. On by default.
 */
@Composable
private fun HapticAtFlatRow(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Text("Haptic at flat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("A short tick when the unfold settles flat", Modifier.weight(1f))
        Switch(checked, onChecked, Modifier.testTag("haptic-at-flat-switch"))
    }
}

/**
 * "Wallpaper depth" (IDEAS.md B27): a subtle parallax of the wallpaper against the icons and
 * widgets from the gravity vector, on both panels; the wallpaper also backs off a little during
 * the unfold/close morph. Self-contained: its own `appearance` prefs key
 * ([cz.pflanzer.foldduo.wallpaperDepthEnabled] / [cz.pflanzer.foldduo.setWallpaperDepthEnabled],
 * DuneWallpaper.kt). On by default; the system's reduced-motion turns it off automatically
 * wherever it is read.
 */
@Composable
private fun WallpaperDepthRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(cz.pflanzer.foldduo.wallpaperDepthEnabled(context)) }
    Text("Wallpaper depth", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Let the wallpaper drift slightly behind the icons and widgets as you tilt the phone", Modifier.weight(1f))
        Switch(enabled, { enabled = it; cz.pflanzer.foldduo.setWallpaperDepthEnabled(context, it) }, Modifier.testTag("wallpaper-depth-switch"))
    }
    Text("A subtle parallax from the gravity sensor, on both screens; the wallpaper also eases back a " +
        "touch while a fold is mid-morph. Off automatically when the system's remove-animations setting is on.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("wallpaper-depth-status"))
}

/**
 * B14 "Inner od ~35°" (systemfrost/OpenedOverridePlan.kt): while opening, ask the system for the
 * OPENED device state as soon as the hinge step reaches ~35-50° instead of waiting for One UI's
 * own ~91° switch. Experimental and off by default.
 */
@Composable
private fun OpenedOverrideRow(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Text("Inner display from ~35°", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Inner display from ~35° (experimental)", Modifier.weight(1f))
        Switch(checked, onChecked, Modifier.testTag("opened-override-switch"))
    }
    Text("Lights the inner panel as soon as you start opening the phone, instead of waiting for it to reach ~91°. " +
        "Uses a hidden system API that may not work on every firmware; the switch just stops asking if a request ever fails.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("opened-override-status"))
}

/**
 * "Mlha i nad aplikacemi" (systemfrost/SystemFrost.kt): the same Continuum frost played by the
 * accessibility service over whatever app is in front, not only inside this launcher. On by
 * default; needs the accessibility service — 17. 9. reorg adds a live granted/not-granted status
 * line plus a button straight to Accessibility settings when it is not (the same service the
 * shade-open feature already asks for).
 */
@Composable
private fun SystemFrostRow(checked: Boolean, onChecked: (Boolean) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SystemShadeAccessibilityService.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        granted = SystemShadeAccessibilityService.isEnabled(context)
        onPauseOrDispose { }
    }
    Text("Frost over other apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Frost the screen while opening or closing over other apps too", Modifier.weight(1f))
        Switch(checked, onChecked, Modifier.testTag("system-frost-switch"))
    }
    Text(if (granted) "Accessibility service is on — this has effect."
        else "Needs the accessibility service (Settings → Accessibility → Fold Duo). Without it this has no effect.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("system-frost-status"))
    if (!granted) OutlinedButton(onClick = { SystemShadeAccessibilityService.openAccessibilitySettings(context) },
        modifier = Modifier.fillMaxWidth().testTag("system-frost-open-accessibility")) { Text("Open accessibility settings") }
}

/**
 * Continuum A's gyro triggers (UnfoldMorph.kt): off, the cover frosts on the hinge step while
 * opening and the inner left half on the hinge step while folding; on, the gyroscope's
 * hinge-axis rotation triggers them earlier but also on ordinary handling of the closed phone.
 */
@Composable
private fun MotionFrostRow(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Text("Unfold morph", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Frost on motion (experimental)", Modifier.weight(1f))
        Switch(checked, onChecked, Modifier.testTag("motion-frost-switch"))
    }
    Text("Frost the screens from the gyroscope as soon as the phone starts to swing, before the hinge sensor reports. " +
        "Turning the closed phone in your hand can trigger it too; off, only the hinge step does.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("motion-frost-status"))
}

/**
 * "Hinge squeeze" (IDEAS.md B48 "Pant jako ovladač"): a light closing squeeze the user holds
 * without completing a real close. `AppLibrary` (default) opens the launcher's own overlay
 * (`HingeSqueezeOverlay`, `LauncherScreen.kt`); `NowBrief` fires Samsung's Now Brief if it
 * resolves, else falls back to Spotlight (`MainActivity`'s squeeze `LaunchedEffect`); `Off`
 * ignores the gesture entirely (the detector still runs in `pose/`, nothing reacts to it).
 */
@Composable
private fun HingeSqueezeRow(action: HingeSqueezeAction, onAction: (HingeSqueezeAction) -> Unit) {
    Text("Hinge squeeze", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text("Lightly close the fold a little and hold, without letting go all the way",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(HingeSqueezeAction.Off to "Off", HingeSqueezeAction.AppLibrary to "App Library",
            HingeSqueezeAction.NowBrief to "Now Brief").forEach { (option, label) ->
            FilterChip(selected = action == option, onClick = { onAction(option) }, label = { Text(label) },
                modifier = Modifier.testTag("hinge-squeeze-${option.name.lowercase()}"))
        }
    }
    Text("While standing in a tent, tilt to skip tracks when something is playing.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("hinge-squeeze-status"))
}

/**
 * "Wallpaper bends at the hinge" (IDEAS.md B37 "Tapeta jako ohýbaný list"): the wallpaper is drawn
 * as a sheet of paper bent at the hinge by the real angle, on the inner display (continuum/
 * SheetBend.kt + res/raw/sheet_bend.agsl), instead of a flat picture. On by default; off
 * automatically under the system's reduced-motion.
 */
@Composable
private fun SheetBendRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(cz.pflanzer.foldduo.sheetBendEnabled(context)) }
    Text("Wallpaper bends at the hinge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Draw the wallpaper as a sheet of paper bent at the hinge, matching the real angle", Modifier.weight(1f))
        Switch(enabled, { enabled = it; cz.pflanzer.foldduo.setSheetBendEnabled(context, it) }, Modifier.testTag("sheet-bend-switch"))
    }
    Text("Only on the inner display; the cover always shows a flat crop. Straightens with a small " +
        "crack when the phone reaches fully flat. Off automatically when the system's remove-animations setting is on.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("sheet-bend-status"))
}
