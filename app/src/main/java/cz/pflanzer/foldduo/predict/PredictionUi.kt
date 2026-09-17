package cz.pflanzer.foldduo.predict

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.AppEntry
import cz.pflanzer.foldduo.AppTile
import cz.pflanzer.foldduo.frostedGlass
import cz.pflanzer.foldduo.glassPill
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * B35 "Předpovědi aplikací + kontinuita": the Compose half of the Today canvas addition — a
 * glass "Suggestions" strip (page -1, below the notification hub slot — see LeadingPane.kt's
 * `LeadingTopSlot`/`leadingTopSlotRows`/`SUGGESTIONS_ROW_RESERVED_ROWS`) and the "Pokračovat: …"
 * continuity chip. Kept self-contained (own package, plain callbacks in); `LeadingTopSlot` is the
 * one call site that composes it into LauncherScreen.kt's Today canvas.
 *
 * 2026-09-17 night redesign: a device report ("v Suggestions jsou popisky špatně vycentrované a
 * oříznuté, ikony divné") traced back to `SuggestionsRow` drawing its own icon+label composable
 * (fixed 48 dp icon, fixed 56 dp un-centred label) instead of the Home grid's tile. It now renders
 * [cz.pflanzer.foldduo.AppTile] — the exact same composable `SharedHomeGrid` uses for Home icons —
 * at the grid's own cellWidthDp/iconSize/labels (see `SuggestionsLayout.kt` for the pure geometry
 * behind the strip's height and per-tile width).
 */

private fun czech(): Boolean = Locale.getDefault().language == "cs"

/** Corner radius shared by both the Suggestions strip and the continuity chip. */
private val PREDICT_GLASS_CORNER = 22.dp

/**
 * Four suggested apps in a glass strip, "Návrhy"/"Suggestions" labelled, each rendered as the
 * exact same [AppTile] the Home grid uses at [iconSize] and [cellWidthDp] wide — labels, badges,
 * live icons, Liquid Glass and press scale all match Home instead of a second hand-rolled tile.
 * Each of the [SUGGESTIONS_TILE_COUNT] slots cross-fades independently when the ranked set
 * changes, so an icon swaps in place instead of the row jumping. A slot with no suggestion yet
 * renders blank rather than shrinking the row (it still reserves its layout space, so the row's
 * reserved grid height never changes just because there are, for the moment, fewer than 4
 * candidates).
 */
@Composable
fun SuggestionsRow(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onBlock: (AppEntry) -> Unit,
    onAddToHome: (AppEntry) -> Unit,
    /** The leading grid's own cell width ([cz.pflanzer.foldduo.leadingGridWidth] / `GRID_COLUMNS`) — see [suggestionCellWidthDp]. */
    cellWidthDp: Float,
    /** The leading grid's own icon size (`HomeGeometry.iconSize`), same as every `AppTile` on Home. */
    iconSize: Float,
    /** The Home grid's own "show labels" setting; defaults on since a suggestion without its name is not very suggestive. */
    labels: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .frostedGlass(corner = PREDICT_GLASS_CORNER)
            .padding(vertical = SUGGESTIONS_STRIP_PADDING_DP.dp)
            .testTag("suggestions-row")
    ) {
        Text(if (czech()) "Návrhy" else "Suggestions", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp))
        // No horizontal inset here (unlike the caption above): SUGGESTIONS_TILE_COUNT tiles at
        // cellWidthDp each exactly fill this row's width — the same width SharedHomeGrid's own
        // cells fill — so nothing is left over for SpaceEvenly to squeeze or overflow into.
        Row(Modifier.fillMaxWidth().padding(top = SUGGESTIONS_CAPTION_GAP_DP.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            for (slot in 0 until SUGGESTIONS_TILE_COUNT) {
                val app = apps.getOrNull(slot)
                Box(Modifier.width(cellWidthDp.dp), contentAlignment = Alignment.TopCenter) {
                    Crossfade(targetState = app?.id, label = "suggestion-slot-$slot") { targetId ->
                        val slotApp = apps.firstOrNull { it.id == targetId }
                        if (slotApp != null) SuggestionTile(slotApp, iconSize, labels, onLaunch, onBlock, onAddToHome)
                        else Spacer(Modifier.size(iconSize.dp))
                    }
                }
            }
        }
    }
}

/** One [AppTile] plus the long-press "block"/"add to Home" menu the strip needs on top of it. */
@Composable
private fun SuggestionTile(
    app: AppEntry,
    iconSize: Float,
    labels: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onBlock: (AppEntry) -> Unit,
    onAddToHome: (AppEntry) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        AppTile(app, iconSize, labels, modifier = Modifier.testTag("suggestion-${app.id}"),
            onClick = { onLaunch(app) }, onLongClick = { menuOpen = true })
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (czech()) "Nezobrazovat" else "Don’t show") },
                onClick = { menuOpen = false; onBlock(app) },
                modifier = Modifier.testTag("suggestion-${app.id}-block"),
            )
            DropdownMenuItem(
                text = { Text(if (czech()) "Přidat na plochu" else "Add to Home") },
                onClick = { menuOpen = false; onAddToHome(app) },
                modifier = Modifier.testTag("suggestion-${app.id}-add-home"),
            )
        }
    }
}

/** The bare "Pokračovat: …"/"Continue: …" chip; [ContinuityChipHost] adds the 8 s auto-hide on top of it. */
@Composable
fun ContinuityChip(label: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val text = if (czech()) "Pokračovat: $label" else "Continue: $label"
    Row(
        modifier
            // "Matné sklo pro všechny pilulky" (17. 9. noc): shared primitive, opacity-reactive.
            .glassPill(corner = PREDICT_GLASS_CORNER)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("continuity-chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ArrowForward, null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Shows [ContinuityChip] for [sighting] while [continuityChipVisible] says [shownAtMs] is still
 * within its window, then removes itself — no external timer needed, so a caller only has to
 * remember when the chip was shown, not when to hide it.
 */
@Composable
fun ContinuityChipHost(
    sighting: ForegroundSighting?,
    shownAtMs: Long,
    onTap: (ForegroundSighting) -> Unit,
    modifier: Modifier = Modifier,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    if (sighting == null) return
    var visible by remember(sighting, shownAtMs) { mutableStateOf(continuityChipVisible(shownAtMs, nowProvider())) }
    LaunchedEffect(sighting, shownAtMs) {
        while (continuityChipVisible(shownAtMs, nowProvider())) delay(250)
        visible = false
    }
    AnimatedVisibility(visible, modifier) { ContinuityChip(sighting.label, onTap = { onTap(sighting) }) }
}
