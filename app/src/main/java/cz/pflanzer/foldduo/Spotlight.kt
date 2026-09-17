@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cz.pflanzer.foldduo

import android.util.Log
import android.app.AppOpsManager
import android.app.SearchManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.KeyEventType
import cz.pflanzer.foldduo.notifications.notificationBadge
import cz.pflanzer.foldduo.predict.PredictionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * "Bold Spotlight" v2 (17. 9. 2026 evening redesign): the universal search overlay opened by a
 * pull-down on empty Home space or the rail's search button, redone à la iPhone Duo/iOS Spotlight
 * after the original B28 version (see the "Spotlight B28" STATUS.md entry it replaces) crashed on
 * focus (fixed 17. 9. morning) and — Tom's own words — "looked weird and couldn't scroll". The
 * whole pane frosts and dims the Home content behind it (page-root hook, LauncherScreen.kt), a
 * glass search pill slides in mid-height, and everything — the empty-state suggestions/recent/
 * shortcuts and, once a query lands, the ranked "Top Hit"/Aplikace/Kontakty/Nastavení/Akce/Web
 * sections — lives in *one* `LazyColumn` (the actual scrolling fix: the old layout kept the field
 * outside the scrollable area in a plain weighted `Column`, which read fine until the keyboard's
 * inset math left the list too little room to visibly move).
 *
 * B28 follow-up "Spotlight vs shade" is unchanged: PageGestures.kt's shared recognizer
 * (`downwardHomeGestureLane`/`downwardHomeGestureTarget`, SpotlightModel.kt) still arbitrates the
 * pull-down against the notification shade by where the drag started; the rail's search button
 * remains an unconditional, gesture-free way to open it. It *closes* on a swipe up anywhere on
 * its panes (the sheet slides back up the way it came; see [spotlightResultsDismissTriggered]),
 * alongside Back and a tap on the frost outside the field/results column.
 *
 * The ranking itself (app/pair fuzzy matching, settings-shortcut table, contacts, recency) is
 * still SpotlightModel.kt's pure functions, now also grouped into sections and a single "Top Hit"
 * by [pickSpotlightTopHit]/[groupSpotlightSections] — also pure and unit-tested — so this file only
 * has to map ids back to the real [AppEntry]/[PairEntry]/contact/shortcut objects it already has in
 * scope, off the main thread (see [rememberSpotlightSections]).
 *
 * "Spotlight přes oba pane" (17. 9. night): on the inner display — Tom's "na širokém otevřeném
 * udělej Spotlight přes obě půlky, teď je jen vpravo" — this now spans BOTH panes rather than
 * confining the field/results to one ([spotlightUsesTwoColumns], SpotlightModel.kt). The right
 * pane keeps the field and the results `LazyColumn` exactly as before; the left pane becomes a
 * companion column: the empty-state suggestions grid/Recent/Shortcuts cards move there entirely
 * (the right pane's empty state is then just the field plus a one-line hint), and once a query
 * lands the left pane shows a live preview of whichever result is *highlighted* — the Top Hit by
 * default ([spotlightEffectiveHighlight]), or whatever row was last tapped on the right. Tapping a
 * row on the right only moves the highlight in this two-column layout (it still opens the result
 * immediately on the cover/single-column layout, where there is no preview to show); the preview
 * panel's own primary button is what opens the highlighted result there, and the keyboard's "Go"
 * always opens whatever is currently highlighted. The frost/dim itself needed no change: it was
 * already `fillMaxSize()` (both panes) before this patch, only the field/results column was ever
 * confined to one pane — see the frost `Box` below.
 */
@Composable
internal fun SpotlightOverlay(
    visible: Boolean,
    state: LauncherState,
    onDismiss: () -> Unit,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    /** B46 "Dvojice aplikací": launches a "Dvojice: A + B" result exactly like Home's pair tile. */
    onLaunchPair: (PairEntry, android.graphics.Rect?) -> Unit = { _, _ -> },
    onOpenWallpaper: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenWidgets: () -> Unit,
    /** Spotlight v2: the 4th "Shortcuts" card action, entering Home edit ("jiggle") mode. */
    onOpenEditHome: () -> Unit = {},
    /** Spotlight v2: today's ranked Suggestions (same `predictor.suggestions` LauncherScreen.kt
     * already threads to the Today canvas, B35's `AppPredictor`) — the 4 "Siri suggestion" tiles
     * above the field in the empty state, single-column layout only. Empty (setting off, or
     * nothing ranked yet) just hides the row rather than reserving blank space for it. */
    suggestions: List<AppEntry> = emptyList(),
    /** Spotlight v2: live "how open is Spotlight" progress (0f closed .. 1f open), mirroring this
     * overlay's own frost animation, so LauncherScreen.kt's page root can scale/dim the Home
     * content behind it without this file knowing anything about that root's modifier chain. */
    motion: SpotlightMotionState? = null,
    /** Whether this app is the default Home — gates the app preview's shortcuts row, same rule
     * [queryAppShortcuts] (IconPopover.kt) already uses: `getShortcuts` throws otherwise. */
    isDefaultHome: Boolean = false,
    /** Two-column layout only: the highlighted app result's first Home-screen widget provider,
     * off the main thread — the same B49 lookup `LauncherAppActionSheet`'s quick look uses
     * (LauncherScreen.kt), threaded in as a plain suspend lambda so this file stays free of
     * `WidgetController`/`MainActivity` specifics. `null` (the default) just hides that part of
     * the preview, as if the app had no widgets. */
    widgetPreviewFor: (suspend (AppEntry) -> SpotlightWidgetPreview?)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) { systemReduceMotionEnabled(context) }
    var composed by remember { mutableStateOf(visible) }
    val frost = remember { Animatable(0f) }
    val fieldOffset = remember { Animatable(-24f) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable(visible) { mutableStateOf("") }
    LaunchedEffect(frost) { snapshotFlow { frost.value }.collect { motion?.progress = it } }
    LaunchedEffect(visible, reduceMotion) {
        if (visible) {
            composed = true
            if (reduceMotion) {
                frost.snapTo(1f); fieldOffset.snapTo(0f)
            } else {
                launch { frost.animateTo(1f, tween(220)) }
                launch { fieldOffset.animateTo(0f, spring(dampingRatio = .78f, stiffness = 380f)) }
            }
            // The keyboard is requested as the frost/slide-in animation starts — but the field is
            // only composed once `composed` flips, so wait for that frame; a FocusRequester that is
            // not attached yet throws (17. 9.: the pull-down crashed the launcher on the first frame).
            repeat(2) { withFrameNanos { } }
            runCatching { focusRequester.requestFocus() }.onFailure { Log.w("FoldDuoSpotlight", "focus not ready: ${it.message}") }
            keyboard?.show()
        } else if (composed) {
            // Close: keyboard hides first, then the frost/field reverse — matches the spec's
            // "close: reverse, keyboard hides first" (previously both fired together).
            keyboard?.hide()
            if (reduceMotion) {
                frost.snapTo(0f); fieldOffset.snapTo(-24f)
            } else {
                launch { frost.animateTo(0f, tween(220)) }
                launch { fieldOffset.animateTo(-24f, tween(180)) }
            }
            composed = false
            query = ""
        }
    }
    if (!composed && frost.value <= 0f) return
    BackHandler(enabled = visible, onBack = onDismiss)

    // Results are computed off the main thread from a debounced query (snapshotFlow + 40 ms), so
    // fast typing never blocks a frame on contact/settings/action matching.
    var debouncedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(Unit) { snapshotFlow { query }.debounce(40).collect { debouncedQuery = it } }

    val contactsPermission = rememberPermissionRequest(android.Manifest.permission.READ_CONTACTS)
    val recency = rememberSpotlightRecency(state, enabled = visible)
    val contacts = rememberSpotlightContacts(debouncedQuery, enabled = visible && contactsPermission.granted)
    val actions = remember(onOpenWallpaper, onOpenAppearance, onOpenWidgets, onOpenEditHome) {
        spotlightLauncherActions(onOpenWallpaper, onOpenAppearance, onOpenWidgets, onOpenEditHome)
    }
    val sections = rememberSpotlightSections(debouncedQuery, state.apps, state.pairs, recency.ids, contacts, contactsPermission.granted, actions)
    var queryGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(debouncedQuery) { queryGeneration++ }

    val recentApps = remember(state.apps, state.recentLaunches) {
        val byId = state.apps.associateBy { it.id }
        state.recentLaunches.take(8).mapNotNull { byId[it] }
    }
    var shortcutHistory by remember { mutableStateOf(spotlightShortcutHistory(context)) }
    val shortcutHistoryEntries = remember(shortcutHistory) {
        shortcutHistory.mapNotNull { action -> SPOTLIGHT_SETTINGS_SHORTCUTS.firstOrNull { it.action == action } }.take(4)
    }

    fun openResult(result: SpotlightResult) {
        when (result) {
            is SpotlightResult.AppResult -> { onDismiss(); onLaunch(result.app, null) }
            is SpotlightResult.PairResult -> { onDismiss(); onLaunchPair(result.pair, null) }
            is SpotlightResult.ContactResult -> { onDismiss(); openContact(context, result.contact.id) }
            is SpotlightResult.SettingsResult -> {
                recordSpotlightShortcutUse(context, result.shortcut.action)
                shortcutHistory = spotlightShortcutHistory(context)
                onDismiss(); openSettingsShortcut(context, result.shortcut.action)
            }
            is SpotlightResult.ActionResult -> { onDismiss(); result.onClick() }
            is SpotlightResult.WebSearchResult -> { onDismiss(); webSearch(context, result.query) }
        }
    }

    val czech = Locale.getDefault().language == "cs"

    BoxWithConstraints(modifier.fillMaxSize().testTag("spotlight-overlay")) {
        // Captured as a plain local, not read as `maxHeight` from deep inside the LazyColumn's
        // item {} lambdas below — BoxWithConstraintsScope's implicit receiver does not reach that
        // far in (a real compiler error, not just a style choice).
        val paneHeightDp = maxHeight.value
        val density = LocalDensity.current
        val safeLeftDp = with(density) { WindowInsets.safeDrawing.getLeft(this, LocalLayoutDirection.current).toDp().value }
        val seam = LocalFoldSeam.current?.let { it.copy(xDp = it.xDp - safeLeftDp) }?.takeIf { it.splits(maxWidth.value) }
        // "Spotlight přes oba pane": both panes on the inner display now, not just the right one —
        // spotlightUsesTwoColumns (SpotlightModel.kt) is the same seam+width rule `inner` always
        // computed here, just factored out so it is unit-testable.
        val inner = spotlightUsesTwoColumns(seam != null, maxWidth.value)
        val railWidth = railWidthDp(state.expanded.dockWidth)
        val rightBounds = if (inner && seam != null) paneBounds(Pane.Right, seam, maxWidth.value, railWidth) else 0f..maxWidth.value
        val leftBounds = if (inner && seam != null) paneBounds(Pane.Left, seam, maxWidth.value, railWidth) else null
        // The whole pane frosts — a 22 % white (or palette-tinted) veil over the real wallpaper,
        // both panes on the inner display included; this was already `fillMaxSize()` before the
        // two-column patch — only the field/results column below used to be confined to one pane.
        Box(Modifier.fillMaxSize()
            .frostedGlass(corner = 0.dp, tintAlpha = frost.value * .22f, border = null)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics { contentDescription = "Spotlight" })
        val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }
        // The keyboard itself is cleared by Modifier.imePadding() below; this is only the
        // nav-bar/minimum floor for the very last row (spotlightBottomContentPaddingDp's
        // `imeHeightDp` stays 0f here on purpose — see its doc comment).
        val bottomPaddingDp = spotlightBottomContentPaddingDp(imeHeightDp = 0f, navigationBarDp = navBottomDp)
        val listState = rememberLazyListState()
        // 17. 9. night: the field lives OUTSIDE the list, as one composable instance. As two
        // `item(key = "field")` blocks in the empty/results branches it was recreated on the
        // first letter — focus lost, keyboard gone, and it jumped from ~28 % to the top.
        val blank = debouncedQuery.isBlank()
        // While typing the field rides high, but never into the cover's camera cutout (the
        // launcher hides the status bar, so statusBarsPadding() above is 0 there): sit one
        // row under the cutout.
        val cutoutTopDp = with(density) { WindowInsets.displayCutout.getTop(density).toDp().value }
        val topSpacer by animateDpAsState(
            if (blank) (paneHeightDp * .16f).coerceAtLeast(0f).dp else (cutoutTopDp + 12f).dp,
            if (reduceMotion) snap() else spring(dampingRatio = .85f, stiffness = 300f), label = "spotlight-top")

        // Highlight (two-column layout): defaults to the Top Hit, sticks to whatever row was last
        // tapped on the right as long as it is still among the current results, and is cleared on
        // every query change below — see spotlightEffectiveHighlight's doc comment for the fallback
        // chain (topHit, then simply the first available result, e.g. a lone "Hledat na webu" row).
        var highlightedKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(debouncedQuery) { highlightedKey = null }
        val orderedResultKeys = remember(sections) {
            listOfNotNull(sections.topHit?.key) + sections.apps.map { it.key } + sections.contacts.map { it.key } +
                sections.settings.map { it.key } + sections.actions.map { it.key } + listOfNotNull(sections.web?.key)
        }
        val effectiveHighlightKey = remember(highlightedKey, sections.topHit?.key, orderedResultKeys) {
            spotlightEffectiveHighlight(highlightedKey, sections.topHit?.key, orderedResultKeys)
        }
        fun resultByKey(key: String?): SpotlightResult? = key?.let { k ->
            sections.topHit?.takeIf { it.key == k }
                ?: sections.apps.firstOrNull { it.key == k }
                ?: sections.contacts.firstOrNull { it.key == k }
                ?: sections.settings.firstOrNull { it.key == k }
                ?: sections.actions.firstOrNull { it.key == k }
                ?: sections.web?.takeIf { it.key == k }
        }
        val highlightedResult = remember(effectiveHighlightKey, sections) { resultByKey(effectiveHighlightKey) }
        // Tapping a row on the right only moves the highlight in the two-column layout — the left
        // pane's preview is what shows it. On the cover/single-column layout there is no preview to
        // show, so a tap keeps opening the result immediately, exactly as before this patch.
        // 17. 9. (Tom): a tap on a result OPENS it on both panels — the left-pane preview follows
        // the Top Hit / keyboard highlight only; making the user tap twice was pointless.
        val rowOnClick: (SpotlightResult) -> Unit = { item -> openResult(item) }

        // AppPredictor top 8 for the left pane's suggestions grid: a second, wider-topN read of the
        // same predictor LauncherScreen.kt's `suggestions` param already threads in at topN=4 for
        // the single-column row above, so this file's own PredictionController stays purely
        // additive — no change to that param, its default, or its 4-tile row.
        val predictionController = remember(context) { PredictionController(context) }
        val leftSuggestionsFull by produceState(emptyList<AppEntry>(), visible, state.apps, state.dock) {
            value = if (!visible) emptyList() else withContext(Dispatchers.Default) {
                runCatching { predictionController.suggestions(state.apps, state.dock.filterNotNull().toSet(), topN = 8) }.getOrDefault(emptyList())
            }
        }
        val leftSuggestions = remember(leftSuggestionsFull) { leftSuggestionsFull.take(spotlightSuggestionsGridCount(leftSuggestionsFull.size)) }

        // --- Right pane (or the whole width on the cover/narrow inner): field + results, unchanged
        // position/shape from Spotlight v2 — only the row taps' target (rowOnClick) and the empty
        // state's content (blank && inner drops straight to a hint line) differ from before.
        Box(Modifier
            .align(Alignment.TopStart)
            .offset(x = rightBounds.start.dp)
            .width(rightBounds.extentDp.dp)
            .statusBarsPadding()
            .graphicsLayer {
                alpha = frost.value.coerceIn(0f, 1f)
                translationY = -(1f - frost.value.coerceIn(0f, 1f)) * SPOTLIGHT_SLIDE_DP.dp.toPx()
            }
            .pointerInput(listState) { detectSpotlightCloseSwipe(listState, onDismiss) }) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().imePadding()) {
                Spacer(Modifier.height(topSpacer))
                if (blank && !inner && suggestions.isNotEmpty()) {
                    SpotlightSuggestionsRow(suggestions, reduceMotion, onClick = { app -> onDismiss(); onLaunch(app, null) },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
                SpotlightFieldPill(query, { query = it }, focusRequester,
                    onGo = {
                        (highlightedResult ?: (if (blank) recentApps.firstOrNull()?.let { SpotlightResult.AppResult(it) } else sections.web))
                            ?.let(::openResult)
                    },
                    onClear = { query = "" }, onCancel = onDismiss, czech = czech,
                    modifier = Modifier
                        // Optional hardware-keyboard ↑/↓: only meaningful in the two-column layout
                        // (orderedResultKeys is empty otherwise while blank, and a plain tap already
                        // opens results directly on the cover) — spotlightMoveHighlight clamps at
                        // either end rather than wrapping.
                        .onPreviewKeyEvent { event ->
                            if (inner && event.type == KeyEventType.KeyDown) when (event.key) {
                                Key.DirectionDown -> { highlightedKey = spotlightMoveHighlight(effectiveHighlightKey, orderedResultKeys, 1); true }
                                Key.DirectionUp -> { highlightedKey = spotlightMoveHighlight(effectiveHighlightKey, orderedResultKeys, -1); true }
                                else -> false
                            } else false
                        }
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .testTag("spotlight-results"),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = bottomPaddingDp.dp),
            ) {
                if (blank) {
                    if (inner) {
                        // Two-column layout: suggestions/Recent/Shortcuts all moved to the left
                        // pane — the right pane's empty state is just the field plus a hint line.
                        item(key = "hint-line") { SpotlightHintLine(czech) }
                    } else {
                        if (recentApps.isNotEmpty()) {
                            item(key = "recent-card") {
                                SpotlightGlassCard(if (czech) "NEDÁVNÉ" else "RECENT", maybeAnimateItem(Modifier.padding(top = 20.dp), reduceMotion)) {
                                    SpotlightRecentWrap(recentApps, onClick = { app -> onDismiss(); onLaunch(app, null) })
                                }
                            }
                        }
                        item(key = "shortcuts-card") {
                            SpotlightGlassCard(if (czech) "ZKRATKY" else "SHORTCUTS", maybeAnimateItem(Modifier.padding(top = 12.dp), reduceMotion)) {
                                SpotlightShortcutsList(shortcutHistoryEntries, actions, czech,
                                    onSettings = { shortcut ->
                                        recordSpotlightShortcutUse(context, shortcut.action); shortcutHistory = spotlightShortcutHistory(context)
                                        onDismiss(); openSettingsShortcut(context, shortcut.action)
                                    },
                                    onAction = { action -> onDismiss(); action.onClick() })
                            }
                        }
                    }
                } else {
                    sections.topHit?.let { topHit ->
                        item(key = "top-hit") {
                            SpotlightTopHitCard(topHit, reduceMotion, czech, onClick = { rowOnClick(topHit) },
                                modifier = maybeAnimateItem(Modifier.padding(bottom = 18.dp), reduceMotion))
                        }
                    }
                    if (sections.apps.isNotEmpty()) {
                        item(key = "apps-header") { SpotlightSectionHeader(if (czech) "APLIKACE" else "APPS") }
                        itemsIndexed(sections.apps.chunked(4), key = { i, _ -> "apps-row-$i" }) { rowIndex, row ->
                            SpotlightAppsGridRow(row, rowIndex, queryGeneration, reduceMotion, onClick = rowOnClick,
                                modifier = maybeAnimateItem(Modifier, reduceMotion))
                        }
                        item(key = "apps-spacer") { Spacer(Modifier.height(12.dp)) }
                    }
                    if (sections.contacts.isNotEmpty()) {
                        item(key = "contacts-header") { SpotlightSectionHeader(if (czech) "KONTAKTY" else "CONTACTS") }
                        itemsIndexed(sections.contacts, key = { _, item -> item.key }) { index, item ->
                            SpotlightRow(item, index, queryGeneration, reduceMotion, onClick = { rowOnClick(item) },
                                onCall = { callContact(context, item.contact.id) }, onMessage = { messageContact(context, item.contact.id) },
                                modifier = maybeAnimateItem(Modifier, reduceMotion))
                        }
                    }
                    if (sections.settings.isNotEmpty()) {
                        item(key = "settings-header") { SpotlightSectionHeader(if (czech) "NASTAVENÍ" else "SETTINGS") }
                        itemsIndexed(sections.settings, key = { _, item -> item.key }) { index, item ->
                            SpotlightRow(item, index, queryGeneration, reduceMotion, onClick = { rowOnClick(item) },
                                onCall = {}, onMessage = {}, modifier = maybeAnimateItem(Modifier, reduceMotion))
                        }
                    }
                    if (sections.actions.isNotEmpty()) {
                        item(key = "actions-header") { SpotlightSectionHeader(if (czech) "AKCE" else "ACTIONS") }
                        itemsIndexed(sections.actions, key = { _, item -> item.key }) { index, item ->
                            SpotlightRow(item, index, queryGeneration, reduceMotion, onClick = { rowOnClick(item) },
                                onCall = {}, onMessage = {}, modifier = maybeAnimateItem(Modifier, reduceMotion))
                        }
                    }
                    sections.web?.let { web ->
                        item(key = "web-header") { SpotlightSectionHeader(if (czech) "WEB" else "WEB") }
                        item(key = web.key) {
                            SpotlightRow(web, sections.rowCount, queryGeneration, reduceMotion, onClick = { rowOnClick(web) },
                                onCall = {}, onMessage = {}, modifier = maybeAnimateItem(Modifier, reduceMotion))
                        }
                    }
                }
            }
            }
        }

        // --- Left pane (two-column layout only): suggestions grid + Recent/Shortcuts while blank,
        // a live preview of the highlighted result once a query lands.
        if (inner && leftBounds != null) {
            Box(Modifier
                .align(Alignment.TopStart)
                .offset(x = leftBounds.start.dp)
                .width(leftBounds.extentDp.dp)
                .statusBarsPadding()
                .graphicsLayer {
                    alpha = frost.value.coerceIn(0f, 1f)
                    translationY = -(1f - frost.value.coerceIn(0f, 1f)) * SPOTLIGHT_SLIDE_DP.dp.toPx()
                }
                .pointerInput(listState) { detectSpotlightCloseSwipe(listState, onDismiss) }) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                    .testTag("spotlight-left-pane")) {
                    Spacer(Modifier.height((cutoutTopDp + 12f).dp))
                    when (spotlightLeftPaneContent(blank)) {
                        SpotlightLeftPaneContent.SUGGESTIONS -> {
                            if (leftSuggestions.isNotEmpty()) {
                                SpotlightSuggestionsGrid(leftSuggestions, reduceMotion,
                                    onClick = { app -> onDismiss(); onLaunch(app, null) })
                            }
                            if (recentApps.isNotEmpty()) {
                                SpotlightGlassCard(if (czech) "NEDÁVNÉ" else "RECENT", Modifier.padding(top = 16.dp)) {
                                    SpotlightRecentWrap(recentApps, onClick = { app -> onDismiss(); onLaunch(app, null) })
                                }
                            }
                            SpotlightGlassCard(if (czech) "ZKRATKY" else "SHORTCUTS", Modifier.padding(top = 12.dp, bottom = 24.dp)) {
                                SpotlightShortcutsList(shortcutHistoryEntries, actions, czech,
                                    onSettings = { shortcut ->
                                        recordSpotlightShortcutUse(context, shortcut.action); shortcutHistory = spotlightShortcutHistory(context)
                                        onDismiss(); openSettingsShortcut(context, shortcut.action)
                                    },
                                    onAction = { action -> onDismiss(); action.onClick() })
                            }
                        }
                        SpotlightLeftPaneContent.PREVIEW -> {
                            SpotlightPreviewPanel(highlightedResult, reduceMotion, czech, isDefaultHome, widgetPreviewFor,
                                onOpen = { highlightedResult?.let(::openResult) },
                                onCall = { id -> callContact(context, id) }, onMessage = { id -> messageContact(context, id) },
                                modifier = Modifier.padding(bottom = 24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** [base] plus `Modifier.animateItem()` under normal motion, [base] unchanged under reduce motion
 * (matches the rest of this file's reduce-motion handling: fades only, no position animation).
 * `animateItem()` only resolves inside a `LazyItemScope` (an `item {}`/`itemsIndexed {}` content
 * lambda), hence the receiver here rather than a plain `Modifier` extension. */
private fun LazyItemScope.maybeAnimateItem(base: Modifier, reduceMotion: Boolean): Modifier =
    if (reduceMotion) base else base.animateItem()

/**
 * Live "how open is Spotlight" progress (0f closed .. 1f open), written by [SpotlightOverlay] from
 * its own frost animation. LauncherScreen.kt's page root reads [progress] to scale (1 -> 0.96) and
 * dim (1 -> 0.8 alpha) the Home content behind Spotlight — a plain read-only hook, this file never
 * reads it back.
 */
/** How far (dp) the panes ride: in from above on open, back up on close — the "sheet" slides. */
private const val SPOTLIGHT_SLIDE_DP = 140f

class SpotlightMotionState {
    var progress by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun rememberSpotlightMotionState(): SpotlightMotionState = remember { SpotlightMotionState() }

// --- Empty-state pieces ------------------------------------------------------------------------

@Composable
private fun SpotlightFieldPill(
    query: String, onQueryChange: (String) -> Unit, focusRequester: FocusRequester,
    onGo: () -> Unit, onClear: () -> Unit, onCancel: () -> Unit, czech: Boolean, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // "Matné sklo pro všechny pilulky" (17. 9. noc): the field itself now sits on the shared
        // glass primitive (blur + opacity-reactive veil + hairline) instead of a flat white
        // container colour — the text field's own container/border colours are transparent so the
        // Box behind it is the only thing drawn. Results cards below keep their own veil (task
        // spec: "results cards may keep their own veil").
        Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp))
            .glassPill(corner = 28.dp, baseVeilAlpha = .18f)) {
            OutlinedTextField(query, onQueryChange,
                Modifier.fillMaxSize().focusRequester(focusRequester).testTag("spotlight-search"),
                singleLine = true, placeholder = { Text(if (czech) "Hledat" else "Search") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear, Modifier.testTag("spotlight-clear")) {
                    Icon(Icons.Rounded.Close, if (czech) "Vymazat" else "Clear")
                } },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                    focusedPlaceholderColor = Color.White.copy(alpha = .8f), unfocusedPlaceholderColor = Color.White.copy(alpha = .8f),
                    focusedLeadingIconColor = Color.White, unfocusedLeadingIconColor = Color.White,
                    focusedTrailingIconColor = Color.White, unfocusedTrailingIconColor = Color.White))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onCancel, Modifier.testTag("spotlight-cancel")) { Text(if (czech) "Zrušit" else "Cancel", color = Color.White) }
    }
}

@Composable
private fun SpotlightSuggestionsRow(apps: List<AppEntry>, reduceMotion: Boolean, onClick: (AppEntry) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        apps.take(4).forEachIndexed { index, app ->
            SpotlightAppearingTile(index, reduceMotion) { SpotlightSuggestionTile(app, onClick = { onClick(app) }) }
        }
    }
}

@Composable
private fun SpotlightSuggestionTile(app: AppEntry, onClick: () -> Unit) {
    val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[app.packageName] ?: 0
    Column(Modifier.width(72.dp).clickable(onClick = onClick).testTag("spotlight-suggestion-${app.id}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).frostedGlass(corner = 18.dp).notificationBadge(badgeCount), contentAlignment = Alignment.Center) {
            Image(app.icon.asImageBitmap(), null, Modifier.size(44.dp).clip(LocalIconStyle.current.clipShape()))
        }
        Spacer(Modifier.height(4.dp))
        Text(app.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpotlightGlassCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().frostedGlass(corner = 20.dp).padding(14.dp)) {
        Text(title, color = Color.White.copy(alpha = .65f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SpotlightRecentWrap(apps: List<AppEntry>, onClick: (AppEntry) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        apps.forEach { app ->
            Column(Modifier.width(56.dp).clickable(onClick = { onClick(app) }).testTag("spotlight-recent-${app.id}"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(app.icon.asImageBitmap(), null, Modifier.size(44.dp).clip(LocalIconStyle.current.clipShape()))
                Spacer(Modifier.height(4.dp))
                Text(app.label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SpotlightShortcutsList(
    history: List<SpotlightSettingsShortcut>, actions: List<SpotlightAction>, czech: Boolean,
    onSettings: (SpotlightSettingsShortcut) -> Unit, onAction: (SpotlightAction) -> Unit,
) {
    Column {
        history.forEach { shortcut ->
            SpotlightShortcutRow(if (czech) shortcut.labelCs else shortcut.labelEn, Icons.Rounded.Settings,
                onClick = { onSettings(shortcut) }, testTag = "spotlight-shortcut-${shortcut.action}")
        }
        actions.forEach { action ->
            SpotlightShortcutRow(action.label, action.icon, onClick = { onAction(action) }, testTag = "spotlight-shortcut-action-${action.id}")
        }
    }
}

@Composable
private fun SpotlightShortcutRow(label: String, icon: ImageVector, onClick: () -> Unit, testTag: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag(testTag)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .85f))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}

// --- Result-state pieces -----------------------------------------------------------------------

@Composable
private fun SpotlightSectionHeader(label: String) {
    Text(label, color = Color.White.copy(alpha = .6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = .8.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp))
}

@Composable
private fun SpotlightTopHitCard(result: SpotlightResult, reduceMotion: Boolean, czech: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale = remember(result.key) { Animatable(if (reduceMotion) 1f else .96f) }
    LaunchedEffect(result.key, reduceMotion) {
        if (reduceMotion) scale.snapTo(1f) else scale.animateTo(1f, spring(dampingRatio = .6f, stiffness = 300f))
    }
    Column(modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .frostedGlass(corner = 22.dp).clickable(onClick = onClick).testTag("spotlight-tophit-${result.key}")
        .padding(16.dp)) {
        SpotlightSectionHeader(if (czech) "NEJLEPŠÍ SHODA" else "TOP HIT")
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SpotlightRowIcon(result, size = 56.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(result.title, color = Color.White, fontSize = 18.sp, maxLines = 1)
                Text(spotlightTopHitSubtitle(result, czech), color = Color.White.copy(alpha = .7f), fontSize = 13.sp)
            }
        }
    }
}

private fun spotlightTopHitSubtitle(result: SpotlightResult, czech: Boolean): String = when (result) {
    is SpotlightResult.AppResult -> if (czech) "Aplikace" else "App"
    is SpotlightResult.PairResult -> if (czech) "Dvojice" else "Pair"
    is SpotlightResult.ContactResult -> if (czech) "Kontakt" else "Contact"
    is SpotlightResult.SettingsResult -> if (czech) "Nastavení" else "Settings"
    is SpotlightResult.ActionResult -> if (czech) "Akce" else "Action"
    is SpotlightResult.WebSearchResult -> if (czech) "Web" else "Web"
}

@Composable
private fun SpotlightAppsGridRow(row: List<SpotlightResult>, rowIndex: Int, generation: Int, reduceMotion: Boolean,
    onClick: (SpotlightResult) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        row.forEachIndexed { col, item ->
            SpotlightAppearingTile(rowIndex * 4 + col, reduceMotion, generation, Modifier.weight(1f)) {
                SpotlightAppGridTile(item, onClick = { onClick(item) })
            }
        }
        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun SpotlightAppGridTile(item: SpotlightResult, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).testTag("spotlight-grid-${item.key}")
        .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SpotlightRowIcon(item, size = 52.dp)
        Spacer(Modifier.height(4.dp))
        Text(item.title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Fade+rise entrance shared by the empty-state row and the Aplikace grid tiles — same stagger/curve
 * as [SpotlightRow] below, factored out since both need it per-item rather than per-`LazyColumn`-row. */
@Composable
private fun SpotlightAppearingTile(index: Int, reduceMotion: Boolean, generation: Int = 0, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var appear by remember(index, generation) { mutableStateOf(reduceMotion) }
    LaunchedEffect(index, generation, reduceMotion) {
        if (reduceMotion) { appear = true; return@LaunchedEffect }
        appear = false
        delay(spotlightRowDelayMs(index))
        appear = true
    }
    val alpha by animateFloatAsState(if (appear) 1f else 0f, tween(180), label = "spotlight-tile-alpha")
    val rise by animateDpAsState(if (appear) 0.dp else 6.dp, tween(180), label = "spotlight-tile-rise")
    Box(modifier.graphicsLayer { translationY = rise.toPx(); this.alpha = alpha }, contentAlignment = Alignment.TopCenter) { content() }
}

@Composable
private fun SpotlightRow(
    item: SpotlightResult, index: Int, generation: Int, reduceMotion: Boolean,
    onClick: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit, modifier: Modifier = Modifier,
) {
    var appear by remember(item.key, generation) { mutableStateOf(reduceMotion) }
    LaunchedEffect(item.key, generation, reduceMotion) {
        if (reduceMotion) { appear = true; return@LaunchedEffect }
        appear = false
        delay(spotlightRowDelayMs(index))
        appear = true
    }
    val alpha by animateFloatAsState(if (appear) 1f else 0f, tween(180), label = "spotlight-row-alpha")
    val rise by animateDpAsState(if (appear) 0.dp else 6.dp, tween(180), label = "spotlight-row-rise")
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .graphicsLayer { translationY = rise.toPx(); this.alpha = alpha }
        .clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).testTag("spotlight-row-${item.key}")
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SpotlightRowIcon(item)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = Color.White, fontSize = 15.sp, maxLines = 1)
            if (item.subtitle != null) Text(item.subtitle, color = Color.White.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1)
        }
        if (item is SpotlightResult.ContactResult && item.contact.hasPhone) {
            IconButton(onClick = onCall, Modifier.testTag("spotlight-call-${item.key}")) { Icon(Icons.Rounded.Call, "Call", tint = Color.White) }
            IconButton(onClick = onMessage, Modifier.testTag("spotlight-message-${item.key}")) { Icon(Icons.Rounded.Message, "Message", tint = Color.White) }
        }
    }
}

@Composable
private fun SpotlightRowIcon(item: SpotlightResult, size: Dp = 40.dp) {
    val boxModifier = Modifier.size(size)
    when (item) {
        is SpotlightResult.AppResult -> {
            // B41 Liquid Glass follow-up: same wallpaper-refracting panel as Home/dock/folder/App
            // Library tiles, gated on the same style flag — Spotlight results were left
            // flat-tinted when B41 first landed.
            val iconStyle = LocalIconStyle.current
            val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[item.app.packageName] ?: 0
            Box(boxModifier.notificationBadge(badgeCount)) {
                if (iconStyle.usesLiquidGlass) Box(Modifier.matchParentSize()
                    .liquidGlassPanel(iconStyle.clipShape(), (size.value * ROUNDED_SQUARE_RADIUS).dp))
                Image(item.app.icon.asImageBitmap(), null, Modifier.fillMaxSize().clip(iconStyle.clipShape()))
            }
        }
        is SpotlightResult.PairResult -> PairIcons(item.first, item.second, size, boxModifier)
        is SpotlightResult.ContactResult -> Icon(Icons.Rounded.Person, null, boxModifier, tint = Color.White)
        is SpotlightResult.SettingsResult -> Icon(Icons.Rounded.Settings, null, boxModifier, tint = Color.White)
        is SpotlightResult.ActionResult -> Icon(item.icon, null, boxModifier, tint = Color.White)
        is SpotlightResult.WebSearchResult -> Icon(Icons.Rounded.Language, null, boxModifier, tint = Color.White)
    }
}

// --- Left pane (two-column layout): empty-state hint, suggestions grid, live preview ----------

@Composable
private fun SpotlightHintLine(czech: Boolean, modifier: Modifier = Modifier) {
    Text(
        if (czech) "Návrhy a nedávné aplikace jsou vlevo." else "Suggestions and recents are on the left.",
        color = Color.White.copy(alpha = .6f), fontSize = 13.sp,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp).testTag("spotlight-hint-line"))
}

/** The left pane's empty-state 2×4 grid of [spotlightSuggestionsGridCount]-capped AppPredictor
 * suggestions — the same [SpotlightSuggestionTile] the single-column row above the field already
 * uses, just more of them, two rows of four instead of one row of four. */
@Composable
private fun SpotlightSuggestionsGrid(apps: List<AppEntry>, reduceMotion: Boolean, onClick: (AppEntry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("spotlight-suggestions-grid")) {
        apps.chunked(4).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEachIndexed { col, app ->
                    SpotlightAppearingTile(rowIndex * 4 + col, reduceMotion) { SpotlightSuggestionTile(app, onClick = { onClick(app) }) }
                }
            }
        }
    }
}

/** Up to two initials in a soft glass circle — the contact preview's avatar; there is no contact
 * photo lookup in this launcher (no `ContactsContract.Photo` reads anywhere else either), so this
 * is the whole story rather than a fallback for a missing photo. */
@Composable
private fun SpotlightContactAvatar(name: String, size: Dp = 72.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
        Text(spotlightInitials(name), color = Color.White, fontSize = (size.value * .33f).sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The left pane's live preview of [result] (`null` — nothing highlighted yet, e.g. mid-recompose
 * right after the query changed — renders nothing): app gets its big icon/name, its first
 * Home-screen widget preview when [widgetPreviewFor] resolves one (reusing [WidgetProviderPreview],
 * B49's quick-look composable, unmodified) and its shortcuts ([rememberAppShortcuts], reused from
 * IconPopover.kt); contact gets an avatar, name and its numbers with Call/Message chips; a settings
 * shortcut gets its icon and a one-line description; a web search gets a "Hledat na webu" card.
 * [onOpen] is the primary button every variant ends on — the one thing that actually opens/acts on
 * the highlighted result, since tapping the row on the right only moved the highlight here.
 */
@Composable
private fun SpotlightPreviewPanel(
    result: SpotlightResult?, reduceMotion: Boolean, czech: Boolean, isDefaultHome: Boolean,
    widgetPreviewFor: (suspend (AppEntry) -> SpotlightWidgetPreview?)?,
    onOpen: () -> Unit, onCall: (Long) -> Unit, onMessage: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    if (result == null) return
    Column(modifier.fillMaxWidth().testTag("spotlight-preview-${result.key}")) {
        when (result) {
            is SpotlightResult.AppResult -> SpotlightAppPreview(result.app, isDefaultHome, widgetPreviewFor, czech, onOpen)
            is SpotlightResult.ContactResult -> SpotlightContactPreview(result.contact, czech, onOpen, onCall, onMessage)
            is SpotlightResult.SettingsResult -> SpotlightSettingsPreview(result.shortcut, czech, onOpen)
            is SpotlightResult.WebSearchResult -> SpotlightWebPreview(result.query, czech, onOpen)
            else -> SpotlightGenericPreview(result, czech, onOpen)
        }
    }
}

@Composable
private fun SpotlightPreviewCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().frostedGlass(corner = 24.dp).padding(18.dp), content = content)
}

@Composable
private fun SpotlightPreviewOpenButton(label: String, onOpen: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("spotlight-preview-open")) { Text(label) }
}

@Composable
private fun SpotlightAppPreview(
    app: AppEntry, isDefaultHome: Boolean, widgetPreviewFor: (suspend (AppEntry) -> SpotlightWidgetPreview?)?, czech: Boolean, onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val preview by produceState<SpotlightWidgetPreview?>(null, app.id, widgetPreviewFor) { value = widgetPreviewFor?.invoke(app) }
    val shortcuts = rememberAppShortcuts(app, isDefaultHome)
    SpotlightPreviewCard {
        val iconStyle = LocalIconStyle.current
        Image(app.icon.asImageBitmap(), null, Modifier.size(72.dp).clip(iconStyle.clipShape()))
        Spacer(Modifier.height(12.dp))
        Text(app.label, color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (app.profileLabel != "Personal") Text(app.profileLabel, color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
        preview?.let { p ->
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val ratio = (p.span.width.toFloat() / p.span.height.coerceAtLeast(1)).coerceAtLeast(0.2f)
                Box(Modifier.fillMaxWidth().height(maxWidth / ratio).clip(RoundedCornerShape(16.dp)).testTag("spotlight-preview-widget")) {
                    WidgetProviderPreview(p.entry, p.span, Modifier.fillMaxSize())
                }
            }
        }
        if (shortcuts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(if (czech) "ZKRATKY" else "SHORTCUTS", color = Color.White.copy(alpha = .65f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
            shortcuts.forEach { shortcut ->
                SpotlightShortcutRow(shortcut.label, Icons.Rounded.AppShortcut, onClick = { launchAppShortcut(context, app, shortcut.id) },
                    testTag = "spotlight-preview-shortcut-${shortcut.id}")
            }
        }
        SpotlightPreviewOpenButton(if (czech) "Otevřít" else "Open", onOpen)
    }
}

@Composable
private fun SpotlightContactPreview(contact: SpotlightContact, czech: Boolean, onOpen: () -> Unit, onCall: (Long) -> Unit, onMessage: (Long) -> Unit) {
    val context = LocalContext.current
    val numbers by produceState(emptyList<String>(), contact.id) {
        value = withContext(Dispatchers.IO) { runCatching { lookupPhoneNumbers(context, contact.id) }.getOrDefault(emptyList()) }
    }
    SpotlightPreviewCard {
        SpotlightContactAvatar(contact.name)
        Spacer(Modifier.height(12.dp))
        Text(contact.name, color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (numbers.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            numbers.forEach { number ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("spotlight-preview-number"), verticalAlignment = Alignment.CenterVertically) {
                    Text(number, color = Color.White.copy(alpha = .85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onCall(contact.id) }, Modifier.testTag("spotlight-preview-call")) { Icon(Icons.Rounded.Call, "Call", tint = Color.White) }
                    IconButton(onClick = { onMessage(contact.id) }, Modifier.testTag("spotlight-preview-message")) { Icon(Icons.Rounded.Message, "Message", tint = Color.White) }
                }
            }
        }
        SpotlightPreviewOpenButton(if (czech) "Otevřít kontakt" else "Open contact", onOpen)
    }
}

@Composable
private fun SpotlightSettingsPreview(shortcut: SpotlightSettingsShortcut, czech: Boolean, onOpen: () -> Unit) {
    SpotlightPreviewCard {
        Icon(Icons.Rounded.Settings, null, Modifier.size(56.dp), tint = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(if (czech) shortcut.labelCs else shortcut.labelEn, color = Color.White, fontSize = 20.sp, maxLines = 1)
        Text(if (czech) "Obrazovka systémového nastavení" else "System settings screen",
            color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
        SpotlightPreviewOpenButton(if (czech) "Otevřít" else "Open", onOpen)
    }
}

@Composable
private fun SpotlightWebPreview(query: String, czech: Boolean, onOpen: () -> Unit) {
    SpotlightPreviewCard {
        Icon(Icons.Rounded.Language, null, Modifier.size(56.dp), tint = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(if (czech) "Hledat na webu" else "Search the web", color = Color.White, fontSize = 20.sp)
        Text("“$query”", color = Color.White.copy(alpha = .7f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SpotlightPreviewOpenButton(if (czech) "Hledat" else "Search", onOpen)
    }
}

/** Fallback for result kinds the spec did not call out a bespoke preview for (pairs, launcher
 * actions): icon, title, subtitle, Open — never crashes the preview panel on an unhandled kind. */
@Composable
private fun SpotlightGenericPreview(result: SpotlightResult, czech: Boolean, onOpen: () -> Unit) {
    SpotlightPreviewCard {
        SpotlightRowIcon(result, size = 56.dp)
        Spacer(Modifier.height(12.dp))
        Text(result.title, color = Color.White, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (result.subtitle != null) Text(result.subtitle, color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
        SpotlightPreviewOpenButton(if (czech) "Otevřít" else "Open", onOpen)
    }
}

// --- Gestures --------------------------------------------------------------------------------

// The pull-down that opens Spotlight used to live here as its own additive pointerInput
// (spotlightPullDownGesture); it is now arbitrated inside PageGestures.kt's shared recognizer
// (downwardHomeGestureLane/downwardHomeGestureTarget, SpotlightModel.kt) alongside the shade
// gesture it used to lose to — see the doc comment on [SpotlightOverlay].

/**
 * A swipe up anywhere on Spotlight's panes (field, empty state, results, left pane) slides it
 * back up — observed on the Initial pass so the results list keeps scrolling normally; it only
 * fires once the list cannot scroll further up itself (short lists: immediately; long lists: on
 * the overscroll at the end). See [spotlightResultsDismissTriggered].
 */
private suspend fun PointerInputScope.detectSpotlightCloseSwipe(listState: LazyListState, onDismiss: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        var triggered = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!triggered && !listState.canScrollForward) {
                val dx = (change.position.x - start.x) / density
                val dy = (change.position.y - start.y) / density
                if (spotlightResultsDismissTriggered(dx, dy)) {
                    triggered = true
                    change.consume()
                    onDismiss()
                }
            }
        }
    }
}

// --- Result model (Compose/Android-facing; ranking itself is in SpotlightModel.kt) -----------

internal data class SpotlightContact(val id: Long, val name: String, val hasPhone: Boolean)
internal data class SpotlightAction(val id: String, val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** "Spotlight přes oba pane": the app preview's widget snippet — the catalog entry plus the span
 * to render it at (same shape B49's quick look, `LauncherAppActionSheet`, threads to `WidgetProviderPreview`). */
internal data class SpotlightWidgetPreview(val entry: WidgetCatalogEntry, val span: WidgetSpan)

private sealed class SpotlightResult(val key: String, val title: String, val subtitle: String?) {
    class AppResult(val app: AppEntry) : SpotlightResult("app:${app.id}", app.label, app.profileLabel.takeIf { it != "Personal" })
    /** B46 "Dvojice aplikací": [first]/[second] may be null when a member became unavailable
     * between the pair forming and this render — the title still reads from the pair's own ids. */
    class PairResult(val pair: PairEntry, val first: AppEntry?, val second: AppEntry?) :
        SpotlightResult("pair:${pair.id}", spotlightPairTitle(first?.label ?: pair.first, second?.label ?: pair.second,
            czech = Locale.getDefault().language == "cs"), "Pair")
    class ContactResult(val contact: SpotlightContact) : SpotlightResult("contact:${contact.id}", contact.name, "Contact")
    class SettingsResult(val shortcut: SpotlightSettingsShortcut) :
        SpotlightResult("settings:${shortcut.action}", if (Locale.getDefault().language == "cs") shortcut.labelCs else shortcut.labelEn, "Settings")
    class ActionResult(val id: String, label: String, val icon: ImageVector, val onClick: () -> Unit) :
        SpotlightResult("action:$id", label, null)
    class WebSearchResult(val query: String) : SpotlightResult("web-search", "Search the web for “$query”", null)
}

private fun spotlightLauncherActions(
    onOpenWallpaper: () -> Unit, onOpenAppearance: () -> Unit, onOpenWidgets: () -> Unit, onOpenEditHome: () -> Unit,
): List<SpotlightAction> {
    val czech = Locale.getDefault().language == "cs"
    return listOf(
        SpotlightAction("wallpaper", if (czech) "Změnit tapetu" else "Change wallpaper", Icons.Rounded.Wallpaper, onOpenWallpaper),
        SpotlightAction("appearance", if (czech) "Vzhled" else "Appearance", Icons.Rounded.Settings, onOpenAppearance),
        SpotlightAction("widgets", if (czech) "Widgety" else "Widgets", Icons.Rounded.Widgets, onOpenWidgets),
        SpotlightAction("edit-home", if (czech) "Upravit plochu" else "Edit Home", Icons.Rounded.Edit, onOpenEditHome),
    )
}

private data class SpotlightRecency(val ids: List<String>, val available: Boolean)

/** UsageStats recency when the special "usage access" grant is present, else the launcher's own history. */
@Composable
private fun rememberSpotlightRecency(state: LauncherState, enabled: Boolean): SpotlightRecency {
    val context = LocalContext.current
    val fallback = SpotlightRecency(state.recentLaunches, state.recentLaunches.isNotEmpty())
    return produceState(fallback, enabled, state.recentLaunches) {
        if (!enabled) return@produceState
        val usage = withContext(Dispatchers.Default) { runCatching { recentPackagesFromUsageStats(context) }.getOrNull() }
        value = if (!usage.isNullOrEmpty()) SpotlightRecency(usage, true) else fallback
    }.value
}

/** Also reused by SeamPalette.kt (B42) for its own recent-apps row. */
internal fun hasUsageAccess(context: Context): Boolean = runCatching {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** Also reused by SeamPalette.kt (B42) for its own recent-apps row. */
internal fun recentPackagesFromUsageStats(context: Context, withinMs: Long = 30L * 24 * 60 * 60 * 1000): List<String> {
    if (!hasUsageAccess(context)) return emptyList()
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
    val end = System.currentTimeMillis()
    val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - withinMs, end) }.getOrNull().orEmpty()
    return stats.filter { it.lastTimeUsed > 0 }.sortedByDescending { it.lastTimeUsed }.map { it.packageName }.distinct()
}

@Composable
private fun rememberSpotlightContacts(query: String, enabled: Boolean): List<SpotlightContact> {
    val context = LocalContext.current
    val trimmed = query.trim()
    return produceState(emptyList(), enabled, trimmed) {
        if (!enabled || trimmed.length < 2) { value = emptyList(); return@produceState }
        delay(150)
        value = withContext(Dispatchers.IO) { runCatching { queryContacts(context, trimmed) }.getOrDefault(emptyList()) }
    }.value
}

private fun queryContacts(context: Context, query: String): List<SpotlightContact> {
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
    val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Contacts.HAS_PHONE_NUMBER)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
        val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val phoneCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
        buildList {
            while (cursor.moveToNext() && size < 5) {
                add(SpotlightContact(cursor.getLong(idCol), cursor.getString(nameCol) ?: "", cursor.getInt(phoneCol) > 0))
            }
        }
    } ?: emptyList()
}

private fun lookupPrimaryPhone(context: Context, contactId: Long): String? = runCatching {
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()

/** Every distinct number on [contactId], best-effort — the contact preview's Call/Message rows
 * (the top-hit contact card, LauncherActionSheet-style rows, only ever needed the single primary
 * number [lookupPrimaryPhone] returns). */
private fun lookupPhoneNumbers(context: Context, contactId: Long, limit: Int = 3): List<String> = runCatching {
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), null)
        ?.use { cursor -> buildList { while (cursor.moveToNext() && size < limit) cursor.getString(0)?.let(::add) } }
        .orEmpty()
}.getOrDefault(emptyList()).distinct()

private fun callContact(context: Context, contactId: Long) {
    val number = lookupPrimaryPhone(context, contactId) ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
}

private fun messageContact(context: Context, contactId: Long) {
    val number = lookupPrimaryPhone(context, contactId) ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))) }
}

private fun openContact(context: Context, contactId: Long) {
    val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

/** Best-effort: some OEMs omit a rarely-used settings screen; failing silently beats crashing. */
private fun openSettingsShortcut(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action)) }
}

/** Handled entirely by the browser/assistant app that resolves it — this app makes no network call. */
private fun webSearch(context: Context, query: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) }
}

// --- Spotlight v2: "used before" settings-shortcut history (small SharedPreferences store,
// same self-contained pattern as notifications/Badges.kt's BadgeSettings) --------------------

private const val SPOTLIGHT_SHORTCUT_PREFS = "spotlight_shortcut_history"
private const val KEY_SHORTCUT_HISTORY = "usedActions"
private const val SPOTLIGHT_SHORTCUT_HISTORY_CAP = 6

/** Most-recently-used first, capped at [SPOTLIGHT_SHORTCUT_HISTORY_CAP]; recorded from [SpotlightOverlay] whenever a settings-shortcut result is actually opened. */
internal fun recordSpotlightShortcutUse(context: Context, action: String) {
    val prefs = context.getSharedPreferences(SPOTLIGHT_SHORTCUT_PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_SHORTCUT_HISTORY, "")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    val updated = (listOf(action) + existing.filter { it != action }).take(SPOTLIGHT_SHORTCUT_HISTORY_CAP)
    prefs.edit().putString(KEY_SHORTCUT_HISTORY, updated.joinToString(",")).apply()
}

internal fun spotlightShortcutHistory(context: Context): List<String> =
    context.getSharedPreferences(SPOTLIGHT_SHORTCUT_PREFS, Context.MODE_PRIVATE).getString(KEY_SHORTCUT_HISTORY, "")
        ?.split(",")?.filter { it.isNotBlank() }.orEmpty()

// --- Sections (Android/Compose-facing mapping over SpotlightModel.kt's pure grouping) ---------

private data class SpotlightSections(
    val topHit: SpotlightResult?,
    val apps: List<SpotlightResult>,
    val contacts: List<SpotlightResult.ContactResult>,
    val settings: List<SpotlightResult.SettingsResult>,
    val actions: List<SpotlightResult.ActionResult>,
    val web: SpotlightResult.WebSearchResult?,
) {
    val rowCount: Int get() = apps.size + contacts.size + settings.size + actions.size
    companion object { val Empty = SpotlightSections(null, emptyList(), emptyList(), emptyList(), emptyList(), null) }
}

/** Computes [SpotlightSections] off the main thread (Dispatchers.Default) from the debounced
 * query — see [SpotlightOverlay]'s doc comment on why this is debounced+offloaded at all. */
@Composable
private fun rememberSpotlightSections(
    query: String, apps: List<AppEntry>, pairs: List<PairEntry>, recencyIds: List<String>,
    contacts: List<SpotlightContact>, contactsEnabled: Boolean, actions: List<SpotlightAction>,
): SpotlightSections {
    val state = produceState(SpotlightSections.Empty, query, apps, pairs, recencyIds, contacts, contactsEnabled, actions) {
        value = withContext(Dispatchers.Default) { buildSpotlightSections(query, apps, pairs, recencyIds, contacts, contactsEnabled, actions) }
    }
    return state.value
}

private fun buildSpotlightSections(
    query: String, apps: List<AppEntry>, pairs: List<PairEntry>, recencyIds: List<String>,
    contacts: List<SpotlightContact>, contactsEnabled: Boolean, actions: List<SpotlightAction>,
): SpotlightSections {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return SpotlightSections.Empty
    val byId = apps.associateBy { it.id }
    val resultsByKey = LinkedHashMap<String, SpotlightResult>()
    val candidates = mutableListOf<SpotlightCandidate>()

    val appCandidates = apps.map { SpotlightAppCandidate(it.id, it.label) }
    rankSpotlightApps(trimmed, appCandidates, recencyIds).take(6).forEach { candidate ->
        byId[candidate.id]?.let { app ->
            val result = SpotlightResult.AppResult(app)
            resultsByKey[result.key] = result
            candidates += SpotlightCandidate(SpotlightSectionKind.APPS, result.key, spotlightMatch(trimmed, app.label))
        }
    }
    val pairCandidates = pairs.map { pair -> SpotlightPairCandidate(pair.id, byId[pair.first]?.label ?: pair.first, byId[pair.second]?.label ?: pair.second) }
    rankSpotlightPairs(trimmed, pairCandidates).take(3).forEach { candidate ->
        pairs.firstOrNull { it.id == candidate.id }?.let { pair ->
            val result = SpotlightResult.PairResult(pair, byId[pair.first], byId[pair.second])
            resultsByKey[result.key] = result
            val match = maxOf(spotlightMatch(trimmed, candidate.firstLabel), spotlightMatch(trimmed, candidate.secondLabel))
            candidates += SpotlightCandidate(SpotlightSectionKind.APPS, result.key, match)
        }
    }
    if (contactsEnabled) contacts.forEach { contact ->
        val result = SpotlightResult.ContactResult(contact)
        resultsByKey[result.key] = result
        // Contacts arrive already filtered by ContentResolver's own query, not spotlightMatch —
        // treated as a solid PREFIX-level match (never EXACT, so a literal app-name match still
        // wins the Top Hit tie) since there is no local text to re-score here.
        candidates += SpotlightCandidate(SpotlightSectionKind.CONTACTS, result.key, SpotlightMatchKind.PREFIX)
    }
    SPOTLIGHT_SETTINGS_SHORTCUTS.filter {
        spotlightMatch(trimmed, it.labelEn) != SpotlightMatchKind.NONE || spotlightMatch(trimmed, it.labelCs) != SpotlightMatchKind.NONE
    }.take(4).forEach { shortcut ->
        val result = SpotlightResult.SettingsResult(shortcut)
        resultsByKey[result.key] = result
        val match = maxOf(spotlightMatch(trimmed, shortcut.labelEn), spotlightMatch(trimmed, shortcut.labelCs))
        candidates += SpotlightCandidate(SpotlightSectionKind.SETTINGS, result.key, match)
    }
    actions.filter { spotlightMatch(trimmed, it.label) != SpotlightMatchKind.NONE }.forEach { action ->
        val result = SpotlightResult.ActionResult(action.id, action.label, action.icon, action.onClick)
        resultsByKey[result.key] = result
        candidates += SpotlightCandidate(SpotlightSectionKind.ACTIONS, result.key, spotlightMatch(trimmed, action.label))
    }

    val topHitCandidate = pickSpotlightTopHit(candidates)
    val topHit = topHitCandidate?.let { resultsByKey[it.id] }
    val groups = groupSpotlightSections(candidates).associate { it.first to it.second }
    fun idsFor(kind: SpotlightSectionKind): List<String> =
        (groups[kind].orEmpty()).filter { it != topHitCandidate?.id }

    return SpotlightSections(
        topHit = topHit,
        apps = idsFor(SpotlightSectionKind.APPS).mapNotNull { resultsByKey[it] },
        contacts = idsFor(SpotlightSectionKind.CONTACTS).mapNotNull { resultsByKey[it] as? SpotlightResult.ContactResult },
        settings = idsFor(SpotlightSectionKind.SETTINGS).mapNotNull { resultsByKey[it] as? SpotlightResult.SettingsResult },
        actions = idsFor(SpotlightSectionKind.ACTIONS).mapNotNull { resultsByKey[it] as? SpotlightResult.ActionResult },
        web = SpotlightResult.WebSearchResult(trimmed),
    )
}
