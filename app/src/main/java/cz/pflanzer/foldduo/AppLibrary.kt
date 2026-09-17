@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cz.pflanzer.foldduo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.launch

/** iOS App Library backdrop: the wallpaper shows through a dark scrim; every label on it is white. */
private val LibraryScrim = Color.Black.copy(alpha = .30f)

/**
 * The page after the last Home. Browsing shows the App Library folder grid
 * ([LibraryBrowser]); a focused or non-empty search, and the "Choose home apps" pin sheet
 * ([editing]), show the alphabetical list ([AppAlphabetList]).
 *
 * Like iOS there is no page title: only the search field ("App Library") sits above the
 * grid, and an open folder replaces it with a "‹ name" header. The whole page is a dim
 * scrim over the wallpaper; [contentPadding] insets the content without insetting the scrim,
 * so a caller that wants the scrim full-bleed passes its page padding here instead of in
 * [modifier].
 *
 * [isCurrent] is true while this page is the settled pager page; it gates the in-page Back
 * handling (open folder, then search) so Home's own Back handler is untouched elsewhere.
 */
@Composable
internal fun AppLibrary(
    state: LauncherState, query: String, onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit, onPin: (String, Boolean) -> Unit, onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier, editing: Boolean = false,
    drag: HomeDragState? = null, page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onTurnOnWork: (Long) -> Unit = {},
    expanded: Boolean = false,
    isCurrent: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** B31 pane identity: on the expanded (inner) display, confines the alphabetical list and its
     * A–Z index rail to the right (Home) pane's width instead of the whole two-pane workspace,
     * so search results look identical to the cover. Null (cover, pin sheet) leaves it full-width. */
    paneWidth: Dp? = null,
    /** B24 follow-up "App Library peek": [libraryPeekProgress] from the cover pager's own
     * settle state (LauncherScreen.kt). `null` everywhere else — the pin sheet and the expanded
     * workspace have no such pager page to peek in from. */
    peekProgress: Float? = null,
) {
    val glass = !editing
    val ink = if (glass) Color.White else MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    // B31 reduce-motion: honours the system's "remove animations" toggle for every App Library
    // motion below (shared-bounds category expand, entrance stagger, search tile/list swap).
    val reduceMotion = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
    // B31 entrance: bumped once each time this page becomes the settled pager page, never on a
    // plain recomposition (isCurrent is unchanged then, so this LaunchedEffect does not rerun).
    var entranceKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(isCurrent) { if (isCurrent) entranceKey++ }
    val pinned = remember(state.homeSlots, state.leadingSlots) {
        (state.homeSlots.asSequence() + state.leadingSlots.asSequence()).filterNotNull().toSet()
    }
    val hasWork = state.profiles.any { it.isWork } || state.apps.any { it.isWork }
    var showWork by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selectedProfile = if (showWork) state.profiles.firstOrNull { it.isWork } else state.profiles.firstOrNull { it.isPersonal }
    LaunchedEffect(showWork, selectedProfile?.available, selectedProfile?.quiet) {
        listState.scrollToItem(0)
    }
    val profileApps = remember(state.apps, showWork, hasWork) {
        state.apps.filter { !hasWork || it.isWork == showWork }
    }
    val visibleApps = remember(profileApps, query) {
        profileApps.filter { it.label.contains(query.trim(), true) }
    }
    var searchFocused by remember { mutableStateOf(false) }
    var openCategory by rememberSaveable { mutableStateOf<AppCategory?>(null) }
    val browsing = isLibraryBrowsing(editing, query, searchFocused)
    val sections = remember(profileApps, state.recentLaunches, browsing) {
        if (browsing) buildLibrary(profileApps, state.recentLaunches, System.currentTimeMillis()) else emptyList()
    }
    val folderOpen = browsing && openCategory != null
    val focus = LocalFocusManager.current
    LaunchedEffect(isCurrent) { openCategory = libraryOpenAfterCurrencyChange(openCategory, isCurrent) }
    BackHandler(enabled = !editing && isCurrent && folderOpen) { openCategory = null }
    BackHandler(enabled = !editing && isCurrent && !browsing) { onQuery(""); focus.clearFocus() }

    Box(modifier.then(if (glass) Modifier.background(LibraryScrim) else Modifier)) {
        CompositionLocalProvider(LocalContentColor provides ink) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp).padding(top = 12.dp)) {
            if (editing) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose home apps", Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                Text("${pinned.size} pinned", color = ink, fontSize = 12.sp)
            }
            if (hasWork) Row(Modifier.fillMaxWidth().padding(top = if (editing) 10.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showWork, onClick = { showWork = false }, label = { Text("Personal") },
                    colors = if (glass) libraryChipColors(ink) else FilterChipDefaults.filterChipColors())
                FilterChip(selected = showWork, onClick = { showWork = true }, label = { Text("Work") },
                    colors = if (glass) libraryChipColors(ink) else FilterChipDefaults.filterChipColors())
            }
            if (folderOpen) {
                // iOS folder header: a chevron back and the folder name, large, at the top-left.
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { openCategory = null }, Modifier.size(40.dp).testTag("library-back"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = ink)) {
                        Icon(Icons.Rounded.ChevronLeft, "Back to App Library", Modifier.size(30.dp))
                    }
                    Text(openCategory?.title ?: "", Modifier.weight(1f).padding(start = 2.dp), color = ink,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            } else OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(vertical = 10.dp)
                .onFocusChanged { searchFocused = it.isFocused }
                .testTag(if (editing) "pin-search" else "library-search"),
                placeholder = { Text(if (editing) "Search apps" else "App Library") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty() || (!editing && searchFocused)) IconButton(onClick = { onQuery(""); focus.clearFocus() }) { Icon(Icons.Rounded.Close, "Clear search") } },
                colors = if (glass) OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ink, unfocusedTextColor = ink, cursorColor = ink,
                    focusedContainerColor = Color.White.copy(alpha = .22f), unfocusedContainerColor = Color.White.copy(alpha = .16f),
                    focusedBorderColor = Color.White.copy(alpha = .55f), unfocusedBorderColor = Color.White.copy(alpha = .22f),
                    focusedPlaceholderColor = ink.copy(alpha = .8f), unfocusedPlaceholderColor = ink.copy(alpha = .8f),
                    focusedLeadingIconColor = ink, unfocusedLeadingIconColor = ink,
                    focusedTrailingIconColor = ink, unfocusedTrailingIconColor = ink,
                ) else OutlinedTextFieldDefaults.colors())
            val workPaused = showWork && selectedProfile?.available == false
            val workPausedMessage = if (workPaused) (if (selectedProfile?.quiet == true) "Work apps are paused" else "Work profile is unavailable") else null
            val turnOnWork: (() -> Unit)? = if (workPaused && selectedProfile?.quiet == true) ({ onTurnOnWork(selectedProfile.userSerial) }) else null
            // B31 search focus: tapping the field animates the tiles away and the alphabetical
            // results in (keyboard opens the same frame, driven by the same focus state change);
            // clearing/unfocusing restores the tiles. Reduce-motion swaps with no transition.
            AnimatedContent(
                targetState = browsing,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                label = "library-search-swap",
            ) { isBrowsing ->
                if (isBrowsing) {
                    LibraryBrowser(
                        sections = sections, open = openCategory, onOpen = { openCategory = it },
                        expanded = expanded, glass = glass, ink = ink, loading = state.loading,
                        drag = drag, page = page, onLaunchFrom = onLaunchFrom, onActions = onActions,
                        workPausedMessage = workPausedMessage, onTurnOnWork = turnOnWork,
                        entranceKey = entranceKey, reduceMotion = reduceMotion, peekProgress = peekProgress,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AppAlphabetList(
                        apps = visibleApps, pinned = pinned, editing = editing, glass = glass, ink = ink,
                        loading = state.loading, listState = listState, drag = drag, page = page,
                        onPin = onPin, onLaunchFrom = onLaunchFrom, onActions = onActions,
                        workPausedMessage = workPausedMessage, onTurnOnWork = turnOnWork,
                        paneWidth = paneWidth,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun libraryChipColors(ink: Color) = FilterChipDefaults.filterChipColors(
    labelColor = ink, selectedLabelColor = Color.Black,
    containerColor = Color.White.copy(alpha = .16f), selectedContainerColor = Color.White.copy(alpha = .85f),
)

/** Width reserved on the right of the list for the A–Z index rail. */
private val INDEX_RAIL_WIDTH = 22.dp

/**
 * The A–Z list: search results, and the whole catalog in the pin sheet. An index rail on the
 * right edge lists the letters present; tapping or dragging along it jumps to that section.
 */
@Composable
internal fun AppAlphabetList(
    apps: List<AppEntry>, pinned: Set<String>, editing: Boolean, glass: Boolean, ink: Color,
    loading: Boolean, listState: LazyListState, drag: HomeDragState?, page: Int?,
    onPin: (String, Boolean) -> Unit, onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    workPausedMessage: String?, onTurnOnWork: (() -> Unit)?,
    /** B31 pane identity: right-pane width on the expanded (inner) display; null elsewhere. */
    paneWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val groups = remember(apps) { apps.groupBy { libraryIndexLetter(it.label) } }
    // LazyColumn index of each sticky letter header, for the rail to scroll to.
    val headerIndex = remember(groups, workPausedMessage) { alphabetHeaderIndices(groups.mapValues { it.value.size }, workPausedMessage != null) }
    val showRail = groups.size > 1
    val scope = rememberCoroutineScope()
    val content: @Composable BoxScope.() -> Unit = {
        LazyColumn(Modifier.fillMaxSize().padding(end = if (showRail) INDEX_RAIL_WIDTH else 0.dp).testTag("all-apps-list"),
            state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
            if (workPausedMessage != null) item("work-paused") {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(workPausedMessage)
                    if (onTurnOnWork != null) Button(onClick = onTurnOnWork,
                        Modifier.padding(top = 10.dp).testTag("turn-on-work")) { Text("Turn on work apps") }
                }
            }
            if (groups.isEmpty()) item { Text(if (loading) "Loading apps…" else "No apps found", Modifier.padding(vertical = 20.dp)) }
            groups.forEach { (letter, entries) ->
                stickyHeader(key = "heading-$letter") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // An opaque small chip prevents text from showing through the sticky letter.
                        Box(Modifier.size(width = 32.dp, height = 28.dp).background(
                            if (glass) Color(0xFF314852) else MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text(letter, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        if (glass) HorizontalDivider(Modifier.weight(1f).padding(start = 10.dp), color = Color.White.copy(alpha = .24f))
                    }
                }
                items(entries, key = { it.id }) { app ->
                    val isPinned = app.id in pinned
                    val launchBounds = remember { android.graphics.Rect() }
                    val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
                    val click = { if (editing) onPin(app.id, !isPinned) else onLaunchFrom(app, launchBounds) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(dragModifier).clip(RoundedCornerShape(14.dp)).testTag("library-app-${app.id}")
                        .then(if (drag == null) Modifier.combinedClickable(onClick = click, onLongClick = { onActions(app) })
                            else Modifier.clickable(onClick = click).semantics { onLongClick("App options") { onActions(app); true } })
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(app.icon.asImageBitmap(), null, Modifier.size(40.dp)
                            .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) }.clip(LocalIconStyle.current.clipShape()))
                        Text(app.label, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, fontSize = 14.sp, color = ink)
                        if (editing) IconButton(onClick = { onPin(app.id, !isPinned) }, Modifier.testTag("pin-${app.id}")) {
                            Icon(if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                if (isPinned) "Remove ${app.label} from home" else "Pin ${app.label} to home",
                                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        if (showRail) AlphabetIndexRail(groups.keys.toList(), ink, glass,
            onJump = { letter -> headerIndex[letter]?.let { index -> scope.launch { listState.scrollToItem(index) } } },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    // B31 pane identity: on the inner display the list (and its index rail) is confined to the
    // right pane's width, right-aligned, instead of stretching across both panes like the wider
    // category grid intentionally does.
    if (paneWidth != null) {
        Box(modifier, contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(paneWidth).fillMaxHeight(), content = content)
        }
    } else {
        Box(modifier, content = content)
    }
}

/**
 * Item index of each letter's sticky header in [AppAlphabetList]'s LazyColumn: an optional
 * work-paused item first, then per letter one header plus its entries. Pure, for tests.
 */
internal fun alphabetHeaderIndices(groupSizes: Map<String, Int>, workPaused: Boolean): Map<String, Int> {
    var index = if (workPaused) 1 else 0
    return buildMap {
        groupSizes.forEach { (letter, size) -> put(letter, index); index += 1 + size }
    }
}

/** Which rail letter a touch at [y] (px) picks on a rail [height] px tall holding [count] letters. */
internal fun railLetterIndex(y: Float, height: Int, count: Int): Int {
    if (count == 0 || height <= 0) return 0
    return (y / height * count).toInt().coerceIn(0, count - 1)
}

/** Size of the floating letter bubble shown to the left of the A–Z rail while dragging. */
private val LIBRARY_INDEX_BUBBLE_SIZE = 56.dp

/**
 * iOS-style A–Z index on the right edge: tap or drag along it to jump to a letter's section.
 * B31: a floating letter bubble tracks the touch to the rail's left while dragging, and each
 * letter change ticks [HapticEvent.INDEX_TICK] (B36) — the same light "detent" iOS uses.
 */
@Composable
private fun AlphabetIndexRail(letters: List<String>, ink: Color, glass: Boolean, onJump: (String) -> Unit, modifier: Modifier = Modifier) {
    var height by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<String?>(null) }
    var touchY by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(letters)
    val view = LocalView.current
    Box(modifier) {
        Column(Modifier.width(INDEX_RAIL_WIDTH).fillMaxHeight().onSizeChanged { height = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pick(y: Float) {
                        touchY = y
                        val letter = current.getOrNull(railLetterIndex(y, height, current.size)) ?: return
                        if (letter != active) {
                            active = letter
                            Haptics.play(view.context, HapticEvent.INDEX_TICK, view)
                            onJump(letter)
                        }
                    }
                    pick(down.position.y); down.consume()
                    drag(down.id) { change -> pick(change.position.y); change.consume() }
                    active = null
                }
            }
            .testTag("library-index"),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            letters.forEach { letter ->
                Text(letter, Modifier.padding(vertical = 1.dp).testTag("library-index-$letter"), color = ink,
                    fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val bubbleLetter = active
        if (bubbleLetter != null) {
            Box(Modifier.align(Alignment.TopStart)
                .offset {
                    val bubblePx = LIBRARY_INDEX_BUBBLE_SIZE.roundToPx()
                    IntOffset(x = -(bubblePx + 12.dp.roundToPx()), y = (touchY - bubblePx / 2f).toInt())
                }
                .size(LIBRARY_INDEX_BUBBLE_SIZE).clip(CircleShape)
                .background(if (glass) Color.Black.copy(alpha = .55f) else MaterialTheme.colorScheme.inverseSurface)
                .testTag("library-index-bubble"), contentAlignment = Alignment.Center) {
                Text(bubbleLetter, color = if (glass) Color.White else MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
