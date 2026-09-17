package cz.pflanzer.foldduo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.settings.ColoursGlassSettings
import cz.pflanzer.foldduo.settings.ContinuumSettings
import cz.pflanzer.foldduo.settings.DiagnosticsSettings
import cz.pflanzer.foldduo.settings.MotionHapticsSettings
import cz.pflanzer.foldduo.settings.PagesModesSettings
import cz.pflanzer.foldduo.settings.TodaySettings

/**
 * 17. 9. reorg ("uprav i nastavení launcheru, kde půjdou nastavit a ladit naše novinky"): the flat
 * tail of B14-B43 rows [AppearanceSettings] used to append to the Wallpaper page is now its own
 * set of pages ([CONTINUUM], [TODAY], [COLOURS_GLASS], [MOTION_HAPTICS], [PAGES_MODES],
 * [DIAGNOSTICS]), same sheet, same components. [WALLPAPER] keeps only mode + location/sunset.
 */
internal enum class CustomizationPage {
    OVERVIEW, WALLPAPER, HOME, CONTINUUM, TODAY, COLOURS_GLASS, MOTION_HAPTICS,
    GESTURES, STANDBY, BACKUP, PAGES_MODES, DIAGNOSTICS, HELP, ABOUT
}

/** Overview tile order per the 17. 9. reorg task: every destination but [CustomizationPage.OVERVIEW] itself. */
internal val CUSTOMIZATION_OVERVIEW_ORDER: List<CustomizationPage> = listOf(
    CustomizationPage.WALLPAPER, CustomizationPage.HOME, CustomizationPage.CONTINUUM, CustomizationPage.TODAY,
    CustomizationPage.COLOURS_GLASS, CustomizationPage.MOTION_HAPTICS, CustomizationPage.PAGES_MODES,
    CustomizationPage.GESTURES, CustomizationPage.STANDBY, CustomizationPage.BACKUP,
    CustomizationPage.DIAGNOSTICS, CustomizationPage.HELP, CustomizationPage.ABOUT,
)

/** Pure title mapping (also the sheet's header text) — [CustomizationPage.OVERVIEW] never shows a back button so it isn't looked up. */
internal fun customizationPageTitle(page: CustomizationPage): String = when (page) {
    CustomizationPage.OVERVIEW -> "Make it yours"
    CustomizationPage.WALLPAPER -> "Wallpaper & appearance"
    CustomizationPage.HOME -> "Home layout"
    CustomizationPage.CONTINUUM -> "Continuum"
    CustomizationPage.TODAY -> "Today"
    CustomizationPage.COLOURS_GLASS -> "Colours & glass"
    CustomizationPage.MOTION_HAPTICS -> "Motion & haptics"
    CustomizationPage.GESTURES -> "Gestures & search"
    CustomizationPage.STANDBY -> "StandBy"
    CustomizationPage.BACKUP -> "Backup"
    CustomizationPage.PAGES_MODES -> "Pages & modes"
    CustomizationPage.DIAGNOSTICS -> "Diagnostics"
    CustomizationPage.HELP -> "Help & setup"
    CustomizationPage.ABOUT -> "About"
}

@Composable
internal fun CustomizationSheet(state: LauncherState, initiallyWide: Boolean, model: LauncherModel,
    isDefaultHome: Boolean, page: CustomizationPage, onPage: (CustomizationPage) -> Unit,
    onMakeDefault: () -> Unit, onClose: () -> Unit, onEditPins: () -> Unit, onWidget: (Int) -> Unit,
    onAddWidget: (Int) -> Unit, onRemoveWidget: (Int) -> Unit, onWallpaperPreview: () -> Unit,
    /** "Odebrat stránku" (2026-09-17 noc "Mazání stránek s dotazem"): routed through the same
     * `requestDeletePage` every other delete-page entry point uses (LauncherScreen.kt), so a
     * non-empty page gets the "Smazat stránku?" confirmation instead of the sheet refusing it. */
    onDeletePage: (Int) -> Unit = {},
    onExportLayout: () -> Unit, onImportLayout: () -> Unit,
    appearance: AppearanceState, onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit, onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit, backgrounds: LauncherBackgroundController, homePage: Int = 0,
    onShadeSetup: () -> Unit = {},
    onAppearanceMotionFrost: (Boolean) -> Unit = {},
    onAppearanceSystemFrost: (Boolean) -> Unit = {},
    onAppearanceOpenedOverride: (Boolean) -> Unit = {},
    onAppearanceHapticAtFlat: (Boolean) -> Unit = {},
    onAppearanceMorphFrostFactor: (Float) -> Unit = {},
    onAppearanceMorphTiltFactor: (Float) -> Unit = {},
    onAppearanceResetMorphPreview: () -> Unit = {},
    onOpenPageOverview: () -> Unit = {},
    onAppearanceHingeSqueeze: (HingeSqueezeAction) -> Unit = {},
) {
    var wide by rememberSaveable { mutableStateOf(initiallyWide) }
    val title = if (page == CustomizationPage.OVERVIEW) "Make it yours" else customizationPageTitle(page)
    val bodyScroll = rememberScrollState()
    LaunchedEffect(page) { bodyScroll.scrollTo(0) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != CustomizationPage.OVERVIEW) IconButton(onClick = { onPage(CustomizationPage.OVERVIEW) },
                Modifier.testTag("customization-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close customization") }
        }
        Column(Modifier.weight(1f).verticalScroll(bodyScroll).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (page) {
                CustomizationPage.OVERVIEW -> {
                    if (!isDefaultHome) Button(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("default-home-settings")) { Text("Set as home app") }
                    if (state.canUndoEdit) OutlinedButton(onClick = { model.undoEdit(); onClose() },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Undo last layout change") }
                    MiniHomePreview(backgrounds.previewBitmap, state, 176.dp)
                    CUSTOMIZATION_OVERVIEW_ORDER.forEach { destination ->
                        val (icon, detail, tag) = customizationOverviewMeta(destination, backgrounds)
                        CustomizationDestination(icon, customizationPageTitle(destination), detail, tag) { onPage(destination) }
                    }
                    if (isDefaultHome) TextButton(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("default-home-settings")) { Text("Change home app") }
                }
                CustomizationPage.WALLPAPER -> {
                    MiniHomePreview(backgrounds.previewBitmap, state, 228.dp)
                    Text("Launcher background", style = MaterialTheme.typography.titleMedium)
                    Text("Changes the image behind Duo’s Home screens.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = backgrounds::choosePhoto, enabled = !backgrounds.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-choose")) {
                        Text(if (backgrounds.previewPending) "Choose a different photo" else "Choose a photo")
                    }
                    if (backgrounds.previewPending) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = backgrounds::cancelPreview, Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("background-preview-cancel")) { Text("Cancel") }
                        Button(onClick = backgrounds::applyPreview, enabled = backgrounds.previewBitmap != null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("background-preview-apply")) { Text("Apply") }
                    }
                    if (backgrounds.photoSelected && !backgrounds.previewPending) OutlinedButton(onClick = backgrounds::reset,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-reset")) { Text("Reset to Duo dunes") }
                    if (backgrounds.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("background-loading"))
                    (backgrounds.errorMessage ?: backgrounds.successMessage)?.let { message ->
                        TextButton(onClick = backgrounds::clearMessage, Modifier.fillMaxWidth().testTag("background-message")) { Text(message) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Android wallpaper", style = MaterialTheme.typography.titleMedium)
                    Text("Opens Android’s preview to change the phone wallpaper. It does not change Duo’s launcher background.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onWallpaperPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("wallpaper-preview")) { Icon(Icons.Rounded.Wallpaper, null); Spacer(Modifier.width(8.dp)); Text("Preview Android wallpaper") }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    AppearanceSettings(appearance, onAppearanceMode, onAppearanceManual, onAppearanceDeviceLocation, onAppearanceClear)
                }
                CustomizationPage.HOME -> HomeLayoutSettings(state, wide, { wide = it }, model, homePage,
                    onEditPins, onWidget, onAddWidget, onRemoveWidget, onDeletePage)
                CustomizationPage.CONTINUUM -> ContinuumSettings(appearance, onAppearanceMotionFrost, onAppearanceSystemFrost,
                    onAppearanceOpenedOverride, onAppearanceHapticAtFlat, onAppearanceMorphFrostFactor,
                    onAppearanceMorphTiltFactor, onAppearanceResetMorphPreview, onAppearanceHingeSqueeze)
                CustomizationPage.TODAY -> TodaySettings()
                CustomizationPage.COLOURS_GLASS -> ColoursGlassSettings(state.iconStyle, model)
                CustomizationPage.MOTION_HAPTICS -> MotionHapticsSettings()
                CustomizationPage.PAGES_MODES -> PagesModesSettings(state, model, onOpenPageOverview)
                CustomizationPage.DIAGNOSTICS -> DiagnosticsSettings(onAppearanceResetMorphPreview)
                CustomizationPage.GESTURES -> {
                    SettingsSwitch("Show app names", state.labels, model::setLabels, "label-switch")
                    SettingsSwitch("Show status at upper right", state.verticalStatus, model::setVerticalStatus, "status-switch")
                    SettingsSwitch("Search button opens Google", state.googleSearch, model::setGoogleSearch, "google-search-switch")
                    Text("All apps always keeps local app search.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Swipe sideways anywhere on Home to change pages. Swipe down for notifications or quick settings.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                CustomizationPage.BACKUP -> {
                    Text("Save the current Home layout, folders, widgets, and layout settings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportLayout, Modifier.weight(1f).heightIn(min = 48.dp).testTag("layout-export")) { Text("Save") }
                        Button(onClick = onImportLayout, Modifier.weight(1f).heightIn(min = 48.dp).testTag("layout-import")) { Text("Restore") }
                    }
                    Text("Restore shows a review before changing Home.", style = MaterialTheme.typography.bodySmall)
                }
                CustomizationPage.STANDBY -> cz.pflanzer.foldduo.standby.StandBySettings()
                CustomizationPage.HELP -> LauncherHelp(
                    isDefaultHome = isDefaultHome,
                    onHomeSettings = onMakeDefault,
                    onAddWidget = { onAddWidget(homePage) },
                    onShadeSetup = onShadeSetup,
                )
                CustomizationPage.ABOUT -> cz.pflanzer.foldduo.settings.AboutPage()
            }
        }
    }
}

@Composable
private fun LauncherHelp(
    isDefaultHome: Boolean,
    onHomeSettings: () -> Unit,
    onAddWidget: () -> Unit,
    onShadeSetup: () -> Unit,
) {
    HelpSection(Icons.Rounded.Home, "Home app",
        if (isDefaultHome) "Duo is your Home app. You can switch launchers in Android’s Home settings."
        else "Choose Duo in Android’s Home settings to use it when you press Home.")
    Button(onClick = onHomeSettings, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("help-home-settings")) {
        Text(if (isDefaultHome) "Change home app" else "Set Duo as Home")
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    HelpSection(Icons.Rounded.TouchApp, "Customize any page",
        "Long-press empty space, then choose Customize launcher. If a page is full, long-press the slim area at its left edge.")
    HelpSection(Icons.Rounded.Widgets, "Widgets",
        "Add Android widgets to empty Home cells. Hold a widget to move or remove it.")
    OutlinedButton(onClick = onAddWidget, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("help-add-widget")) {
        Text("Add widget to this page")
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    HelpSection(Icons.Rounded.SwipeDown, "Notifications and quick settings",
        "Swipe down on Home. The first time, Duo explains Android’s optional Accessibility setting. The service only opens the system panels.")
    TextButton(onClick = onShadeSetup, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("help-shade-setup")) {
        Text("Set up shade gestures")
    }
}

@Composable
private fun HelpSection(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Icon + subtitle + testTag for one overview tile. Kept alongside [CustomizationDestination] since only [CustomizationPage.WALLPAPER]'s subtitle is dynamic (pending-photo review). */
@Composable private fun customizationOverviewMeta(page: CustomizationPage, backgrounds: LauncherBackgroundController): Triple<ImageVector, String, String> = when (page) {
    CustomizationPage.WALLPAPER -> Triple(Icons.Rounded.Wallpaper,
        if (backgrounds.previewPending) "Photo ready to review" else "Background, colors, and light", "customization-wallpaper")
    CustomizationPage.HOME -> Triple(Icons.Rounded.GridView, "Icons, spacing, dock, and widgets", "customization-home")
    CustomizationPage.CONTINUUM -> Triple(Icons.Rounded.Layers, "Unfold morph, frost, and the hinge", "customization-continuum")
    CustomizationPage.TODAY -> Triple(Icons.Rounded.ViewAgenda, "Suggestions, notifications, and badges", "customization-today")
    CustomizationPage.COLOURS_GLASS -> Triple(Icons.Rounded.Palette, "Icon style, glass, and wallpaper colors", "customization-colours-glass")
    CustomizationPage.MOTION_HAPTICS -> Triple(Icons.Rounded.Vibration, "Touch feedback and animation status", "customization-motion-haptics")
    CustomizationPage.GESTURES -> Triple(Icons.Rounded.Search, "Labels, status, and search behavior", "customization-gestures")
    CustomizationPage.STANDBY -> Triple(Icons.Rounded.Bedtime, "Clock, calendar, and photos on the cover", "customization-standby")
    CustomizationPage.BACKUP -> Triple(Icons.Rounded.Save, "Save or restore this layout", "customization-backup")
    CustomizationPage.PAGES_MODES -> Triple(Icons.Rounded.ViewCarousel, "Page overview and Home modes", "customization-pages-modes")
    CustomizationPage.DIAGNOSTICS -> Triple(Icons.Rounded.Speed, "Frame stats, permissions, and tuning", "customization-diagnostics")
    CustomizationPage.HELP -> Triple(Icons.Rounded.HelpOutline, "Home app, widgets, and gestures", "customization-help")
    CustomizationPage.ABOUT -> Triple(Icons.Rounded.Info, "Fold Duo, its author and its roots", "customization-about")
    CustomizationPage.OVERVIEW -> throw IllegalArgumentException("OVERVIEW is not a destination tile")
}

@Composable private fun CustomizationDestination(icon: ImageVector, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
}

@Composable private fun MiniHomePreview(stagedBitmap: android.graphics.Bitmap?, state: LauncherState,
    previewHeight: androidx.compose.ui.unit.Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backgroundRevision = LauncherBackgroundCache.revision.intValue
    val committedBitmap = remember(backgroundRevision) { cachedLauncherBackground(context) }
    val bitmap = stagedBitmap ?: committedBitmap
    val apps = remember(state.apps) { state.apps.associateBy { it.id } }
    val homeIcons = state.homeSlots.mapNotNull { id -> id?.let(apps::get) }.take(8)
    val dockIcons = state.dock.mapNotNull { id -> id?.let(apps::get) }.take(5)
    val scale = previewHeight.value * .632f / 250f
    fun unit(value: Float) = (value * scale).dp
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.height(previewHeight).width(previewHeight * .632f).clip(RoundedCornerShape(unit(24f)))
            .testTag("customization-home-preview")) {
            DuneWallpaper()
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            Column(Modifier.fillMaxSize().padding(start = unit(16f), top = unit(18f), end = unit(54f)),
                verticalArrangement = Arrangement.spacedBy(unit(10f))) {
                Box(Modifier.fillMaxWidth().height(unit(42f)).background(MaterialTheme.colorScheme.surface.copy(alpha = .38f), RoundedCornerShape(unit(12f))))
                homeIcons.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { app -> Image(app.icon.asImageBitmap(), null, Modifier.size(unit(24f)).clip(state.iconStyle.clipShape())) }
                } }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = unit(10f)).width(unit(36f))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .42f), RoundedCornerShape(unit(18f)))
                .padding(vertical = unit(8f)), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(unit(8f))) {
                dockIcons.forEach { app -> Image(app.icon.asImageBitmap(), null, Modifier.size(unit(22f)).clip(state.iconStyle.clipShape())) }
            }
        }
    }
}

@Composable private fun HomeLayoutSettings(state: LauncherState, wide: Boolean, onWide: (Boolean) -> Unit,
    model: LauncherModel, homePage: Int, onEditPins: () -> Unit, onWidget: (Int) -> Unit,
    onAddWidget: (Int) -> Unit, onRemoveWidget: (Int) -> Unit, onDeletePage: (Int) -> Unit) {
    val p = if (wide) state.expanded else state.compact
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!wide, { onWide(false) }, label = { Text("Cover") })
        FilterChip(wide, { onWide(true) }, label = { Text("Inner") })
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text("Inner paging", style = MaterialTheme.typography.titleMedium)
    Text("Spread pages Today together with Home, one pane per swipe, like an open book. " +
        "Right pane only keeps Today fixed and pages just the Home side.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(state.innerPagingSpread, { model.setInnerPagingSpread(true) },
            label = { Text("Spread") }, modifier = Modifier.testTag("inner-paging-spread"))
        FilterChip(!state.innerPagingSpread, { model.setInnerPagingSpread(false) },
            label = { Text("Right pane only") }, modifier = Modifier.testTag("inner-paging-right-only"))
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    OutlinedButton(onClick = onEditPins, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose Home apps") }
    CustomizationSlider("App icon size", "${p.iconSize.toInt()} dp", p.iconSize, 40f..68f) { model.setPreset(wide, p.copy(iconSize = it)) }
    CustomizationSlider("Space between rows", "${p.rowGap.toInt()} dp", p.rowGap, 0f..28f) { model.setPreset(wide, p.copy(rowGap = it)) }
    CustomizationSlider("Dock width", "${p.dockWidth.toInt()} dp", p.dockWidth, 56f..84f) { model.setPreset(wide, p.copy(dockWidth = it)) }
    // The rail centres the dock in its free span; this slider biases it up or down (50 % = centre).
    CustomizationSlider("Dock height on screen", "${(p.dockPosition * 100).toInt()}%", p.dockPosition, .25f.. .75f) { model.setPreset(wide, p.copy(dockPosition = it)) }
    TextButton(onClick = { model.setPreset(wide, LayoutPreset()) }, Modifier.fillMaxWidth()) { Text("Reset this layout") }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text("Widgets · Page ${homePage + 1}", style = MaterialTheme.typography.titleMedium)
    state.widgetPlacements.filter { it.page == homePage || (wide && it.page == -1) }.forEach { placement ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (placement.page == -1) "Today · row ${placement.row + 1}" else "${placement.spanX} × ${placement.spanY} widget · row ${placement.row + 1}", Modifier.weight(1f))
            IconButton(onClick = { onRemoveWidget(placement.slot) }, modifier = Modifier.semantics { contentDescription = if (placement.page == -1) "Remove widget from Today" else "Remove widget" }) { Icon(Icons.Rounded.DeleteOutline, null) }
            TextButton(onClick = { onWidget(placement.slot) }) { Text("Replace") }
        }
    }
    TextButton(onClick = { onAddWidget(homePage) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add widget to this page") }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text("Pages", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { model.addHomePage() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .testTag("add-home-page")) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Přidat stránku") }
    // 2026-09-17 noc "Mazání stránek s dotazem": no longer disabled for a non-empty page — it now
    // routes through onDeletePage, which shows "Smazat stránku?" instead of the old flat refusal.
    if (state.homePages > 1) TextButton(onClick = { onDeletePage(homePage) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remove-home-page")) {
        Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text("Odebrat stránku", color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun SettingsSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChecked, Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier))
    }
}

@Composable private fun CustomizationSlider(label: String, valueLabel: String, value: Float,
    range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column { Row { Text(label, Modifier.weight(1f)); Text(valueLabel, color = MaterialTheme.colorScheme.primary) }
        Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label }) }
}
