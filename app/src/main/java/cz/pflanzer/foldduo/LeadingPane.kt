package cz.pflanzer.foldduo

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.notifications.HubGroup
import cz.pflanzer.foldduo.notifications.HubNotification
import cz.pflanzer.foldduo.notifications.NotificationHubPanel
import cz.pflanzer.foldduo.notifications.NotificationHubPill
import cz.pflanzer.foldduo.notifications.hubRowsReserved
import cz.pflanzer.foldduo.predict.SuggestionsRow

/*
 * Leading pane (PLAN.md, 4 Dual Home, revision of 13 Sep): the left pane of the unfolded Home is
 * a full Home canvas, cell page -1. It is rendered by the same grid as Home pages (HomePagePane /
 * SharedHomeGrid in LauncherScreen.kt): `leadingSlots` hold its icons and folders, page -1
 * WidgetPlacements its widgets, and a widget may span the whole pane (4 x GRID_ROWS). The pure helpers
 * here size that grid to the pane and describe its virtual default, the iPhone Duo hero: one
 * large Photos card. The cover never shows page -1 (pane identity).
 */

/** Space between the leading grid and the fold gutter; mirrors [HOME_START_DP] on the other side. */
const val LEADING_END_DP = 16f

/**
 * B16, "life after cleaning": reading order (top-left to bottom-right) rank of a grid cell at
 * ([row], [column]) on the leading pane. The settle spring on Today's items (LauncherScreen.kt's
 * `SharedHomeGrid`) sorts by this instead of by raw slot index, so gaps between occupied cells
 * never stretch the ~40 ms stagger out.
 */
fun leadingReadingRank(row: Int, column: Int): Int = row * GRID_COLUMNS + column

/**
 * Width of the leading grid: the pane minus the options margin on the left and the gap to the
 * gutter on the right. Wider than the Home grid (there is no rail in this pane), same four columns.
 */
fun leadingGridWidth(paneWidthDp: Float): Float =
    (paneWidthDp - HOME_START_DP - LEADING_END_DP).coerceAtLeast(192f)

/**
 * Provider sizing for page -1: the leading grid's column pitch with the Home row pitches, so a
 * widget on the left pane is measured against the cells it actually gets.
 */
fun leadingGridSizing(paneWidthDp: Float, geometry: HomeGeometry): WidgetGridSizing {
    val topPitch = (geometry.widgetHeight + 18f) / 2f
    return WidgetGridSizing(GRID_COLUMNS, GRID_ROWS,
        cellWidthDp = leadingGridWidth(paneWidthDp) / GRID_COLUMNS,
        cellHeightDp = minOf(topPitch, geometry.rowHeight),
        maximumCellHeightDp = maxOf(topPitch, geometry.rowHeight),
        horizontalGapDp = 10f, verticalGapDp = 18f,
        topRowHeightDp = topPitch, appRowHeightDp = geometry.rowHeight)
}

/**
 * Built-in content shown while page -1 is empty: one Photos card ([PHOTOS_WIDGET]) over the
 * whole pane. Before READ_MEDIA_IMAGES is granted the same card is a placeholder that asks for
 * it on tap. The negative slot marks it as virtual: it is never persisted and registers no drag
 * region, and it vanishes as soon as the user places anything (icon, folder or widget) on the
 * pane ([leadingContent]). Long-press on it opens the empty-space options like a free cell.
 */
val DEFAULT_LEADING_PLACEMENTS = listOf(
    WidgetPlacement(-1, PHOTOS_WIDGET, -1, 0, 0, GRID_COLUMNS, GRID_ROWS),
)

fun WidgetPlacement.isLeadingDefault() = slot < 0

/** True when nothing is placed on page -1: no icon or folder in [leadingSlots], no widget, no pending widget. */
fun leadingPaneEmpty(leadingSlots: List<String?>, placements: List<WidgetPlacement>, pending: WidgetPlacement? = null): Boolean =
    leadingSlots.all { it.isNullOrBlank() } && placements.none { it.page == -1 } && pending?.page != -1

/** The page -1 widgets, or the built-in default when the pane is empty. */
fun leadingContent(leadingSlots: List<String?>, placements: List<WidgetPlacement>, pending: WidgetPlacement? = null): List<WidgetPlacement> =
    if (leadingPaneEmpty(leadingSlots, placements, pending)) DEFAULT_LEADING_PLACEMENTS else placements.filter { it.page == -1 }

/**
 * Footprint a built-in gets when picked for page -1: Photos takes the whole pane (the default it
 * replaces), everything else keeps its Home footprint. [builtinTodayRows] is the row count.
 */
fun leadingBuiltinSpan(id: Int): WidgetSpan {
    val home = builtinWidgetSpan(id)
    return if (id == PHOTOS_WIDGET) WidgetSpan(GRID_COLUMNS, GRID_ROWS)
    else WidgetSpan(home.width, maxOf(home.height, builtinTodayRows(id)).coerceAtMost(GRID_ROWS))
}

/**
 * The virtual default card on page -1. A tap is the card's own action (permission, gallery, or
 * [onOptions] when there is nothing to show); a long-press opens the empty-space options for the
 * pane's first cell and cancels the tap underneath.
 */
@Composable
internal fun LeadingDefaultWidget(id: Int, onOptions: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.testTag("leading-default-$id").pointerInput(onOptions) {
        detectTapGestures(onLongPress = { onOptions() })
    }) {
        BuiltinAppleWidget(id, onOptions)
    }
}

/**
 * Top of the leading canvas (B34, [HomePagePane]'s `topSlot`): the notification hub first, then
 * B35's suggestions row, then the grid below — each addition only ever appends here, so neither
 * feature has to know about the other's layout. Renders nothing at all while both are empty (the
 * settings are off, or nothing is posted/ranked); [HomePagePane] reserves no extra space either
 * way, since both cards' own heights already push the grid below them down inside the pane's
 * scrollable column.
 *
 * Redesign after Tom's 2026-09-17 feedback ("notifications duplicate the system shade and eat
 * half the screen"): the hub itself is now [hubExpanded] false by default — a one-row
 * [NotificationHubPill] summary — and only shows the full [NotificationHubPanel] cards once
 * [onHubToggle] flips it, wrapped in [animateContentSize] for the spring between the two.
 * LauncherScreen.kt owns the actual [hubExpanded] state and dispatches the collapse triggers
 * (leaving Today, resuming, the 20 s timeout) through `reduceHubPill` — this slot only renders it.
 *
 * Oprava naklánění na Today (17. 9. noc): both cards used to skip [Modifier.hingeUnfold] entirely
 * (HingeUnfold.kt, B33), so the hub pill/panel and the suggestions row never unfolded from the
 * hinge with the rest of Today, even though every grid item (AppTile, FolderTile, PairTile,
 * MovableWidget, the leading default) already did. They are full-width — one item spans every
 * column — so there is no real per-column stagger to pick; they take the grid's own first two
 * reading rows (hub = row 0, suggestions = row 1, whichever is actually showing) and column 0, so
 * they blend into the same diagonal wave the grid rows below continue, and pivot on their own
 * right edge exactly like a grid cell (the seam is the leading pane's own right edge).
 * Second redesign, after a 2026-09-17 night device report ("v Suggestions jsou popisky špatně
 * vycentrované a oříznuté, ikony divné" — the strip drew its own icon/label composables instead
 * of the grid's): [SuggestionsRow] now renders the exact same tile as [SharedHomeGrid]'s icons
 * ([AppTile] in LauncherScreen.kt, made `internal` for this), so this slot hands it that grid's
 * own geometry — [cellWidthDp] and [iconSize] from the caller's `leadingGridWidth`/`geometry`,
 * [labels] from the same setting the grid itself uses — instead of guessing its own sizes.
 */
@Composable
internal fun LeadingTopSlot(
    hubGroups: List<HubGroup>,
    hubExpanded: Boolean,
    onHubToggle: () -> Unit,
    expandedHubGroup: String?,
    onHubExpandGroup: (String?) -> Unit,
    onHubOpen: (HubNotification) -> Unit,
    onHubDismiss: (String) -> Unit,
    onHubClearGroup: (HubGroup) -> Unit,
    /** B35: today's ranked Suggestions, already empty when "Suggestions on Today" is off (`PredictionController.suggestions`) — this slot never re-checks the setting itself. */
    suggestions: List<AppEntry> = emptyList(),
    onSuggestionLaunch: (AppEntry) -> Unit = {},
    onSuggestionBlock: (AppEntry) -> Unit = {},
    onSuggestionAddToHome: (AppEntry) -> Unit = {},
    /** B33 follow-up: the shared hinge morph, so the hub/suggestions unfold with the rest of Today (page -1 only — [LauncherScreen.kt]'s callers on ordinary Home pages pass null via not calling this slot at all). Null (previews, tests) is a no-op, same contract as [Modifier.hingeUnfold]. */
    morph: MorphController? = null,
    /** The leading grid's own cell width and icon size (`SharedHomeGrid`'s geometry) — see above. */
    cellWidthDp: Float = 0f,
    iconSize: Float = 0f,
    labels: Boolean = true,
) {
    trackRecomposition("LeadingTopSlot")
    if (hubGroups.isNotEmpty()) {
        val reduceMotion = MotionPrefs.enabled.value
        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .animateContentSize(if (reduceMotion) spring(stiffness = Spring.StiffnessHigh)
                else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            .hingeUnfold(morph, row = 0, column = 0)) {
            if (hubExpanded) {
                NotificationHubPanel(hubGroups, Modifier.fillMaxWidth(),
                    expandedHubGroup, onHubExpandGroup, onHubOpen, onHubDismiss, onHubClearGroup)
            } else {
                NotificationHubPill(hubGroups, onTap = onHubToggle, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (suggestions.isNotEmpty()) {
        SuggestionsRow(suggestions, onSuggestionLaunch, onSuggestionBlock, onSuggestionAddToHome,
            cellWidthDp = cellWidthDp, iconSize = iconSize, labels = labels,
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).hingeUnfold(morph, row = 1, column = 0))
    }
}

/**
 * B35 "Předpovědi aplikací": the top of page -1 stacks fixed-height slots above the icon/widget
 * grid — the notification hub (B34) and the Suggestions strip (`predict/PredictionUi.kt`'s
 * `SuggestionsRow`), in that order — each reserving one grid row so the grid below never overlaps
 * them. [LeadingTopSlot] renders both, in that order; [leadingTopSlotRows] is the pure row count
 * behind that reservation, for anything that needs to know the total ahead of composing it.
 */
const val SUGGESTIONS_ROW_RESERVED_ROWS = 1

/** Grid rows actually available for icons/widgets on page -1 once [topSlotsShowing] fixed slots (hub, suggestions) are subtracted; never below one row. */
fun leadingAvailableRows(topSlotsShowing: Int): Int = (GRID_ROWS - topSlotsShowing).coerceAtLeast(1)

/**
 * B34/B35 stacking: total rows [LeadingTopSlot] reserves right now — the hub's own
 * [hubRowsReserved] (0 when [hubGroupCount] is 0, i.e. the setting is off or nothing is posted;
 * exactly 1 while the compact pill is collapsed, however many groups are behind it, once
 * [hubExpanded] is false) plus [SUGGESTIONS_ROW_RESERVED_ROWS] more while [suggestionsShowing].
 * Zero when neither shows, matching [LeadingTopSlot] rendering nothing in that case. [hubExpanded]
 * defaults true so every caller from before the compact pill existed keeps its old row count.
 */
fun leadingTopSlotRows(hubGroupCount: Int, suggestionsShowing: Boolean, hubExpanded: Boolean = true): Int =
    hubRowsReserved(hubGroupCount, hubExpanded) + if (suggestionsShowing) SUGGESTIONS_ROW_RESERVED_ROWS else 0
