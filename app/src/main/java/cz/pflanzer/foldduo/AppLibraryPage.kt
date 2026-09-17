@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package cz.pflanzer.foldduo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.notifications.notificationBadge
import kotlinx.coroutines.delay

/** Largest cell of the 2×2 tile block: iPad-like on the inner display, a touch smaller on cover. */
// iOS App Library metrics: the tile hugs a 2×2 block of ~60 pt icons; the grid then packs as
// many tiles as fit (iPhone 2, iPad 5–6). Tile width comes from the icon, not from the screen.
private val TILE_ICON_MAX_EXPANDED = 64.dp
private val TILE_ICON_MAX_COMPACT = 64.dp
private val TILE_MIN_EXPANDED = 168.dp
private val TILE_MIN_COMPACT = 188.dp
private val TILE_CORNER = 26.dp
private val TILE_PADDING = 14.dp
private val TILE_GAP = 12.dp

/** iOS App Library tile fill and hairline: thin translucent squares over the dimmed wallpaper. */
internal val TileFill = Color.White.copy(alpha = .30f)
internal val TileBorder = Color.White.copy(alpha = .20f)

/** Small white label under a tile and below open-folder icons; the shadow keeps it legible on bright wallpapers. */
internal val LibraryLabelStyle = TextStyle(
    color = Color.White, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
    shadow = Shadow(Color.Black.copy(alpha = .45f), Offset(0f, 1f), blurRadius = 3f),
)

/** B31: spring used for both the tile → grid shared-bounds morph and each scattering icon. */
private val LibraryBoundsTransform = BoundsTransform { _, _ -> spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) }

/**
 * iOS/iPadOS-style App Library body: a grid of folder tiles (2 columns on cover, 4 on the inner
 * display) with the folder name below each tile, or one open folder as an alphabetical icon grid.
 * Lives inside [AppLibrary] below its search field; no Activity, no extra pager page.
 *
 * B31: opening a tile is a shared-bounds transition (`SharedTransitionLayout` + `AnimatedContent`,
 * spring `dampingRatio = 0.8`) — the tile's own square morphs into the full grid's bounds while its
 * 2×2 icons (and the small cluster icons) scatter into their positions in the alphabetical grid;
 * the other tiles fade with the rest of the grid. [reduceMotion] (system animator scale 0) skips
 * the shared-transition machinery and the tile entrance stagger entirely — the state just changes.
 * [entranceKey] increments once per pager settle on the App Library page; tiles then fade in and
 * rise 8 dp with a 25 ms stagger ([libraryEntranceDelayMs]).
 */
@Composable
internal fun LibraryBrowser(
    sections: List<LibrarySection<AppEntry>>, open: AppCategory?, onOpen: (AppCategory?) -> Unit,
    expanded: Boolean, glass: Boolean, ink: Color, loading: Boolean,
    drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    workPausedMessage: String?, onTurnOnWork: (() -> Unit)?,
    entranceKey: Int = 0, reduceMotion: Boolean = false,
    /**
     * B24 follow-up: [libraryPeekProgress] (PagerMotion.kt) on the cover pager — `null` (every
     * other host: the pin sheet, the expanded/inner workspace) leaves the grid exactly where it
     * was laid out, never applying an offset.
     */
    peekProgress: Float? = null,
    modifier: Modifier = Modifier,
) {
    if (reduceMotion) {
        // No shared-bounds machinery, no stagger: the system asked for no motion.
        if (open != null) {
            val openSection = sections.firstOrNull { it.category == open }
            CategoryApps(openSection?.apps ?: emptyList(), open, glass, ink, drag, page, onLaunchFrom, onActions,
                sharedTransitionScope = null, animatedVisibilityScope = null, modifier)
        } else {
            LibraryGridContent(sections, expanded, glass, ink, workPausedMessage, onTurnOnWork, loading, drag, page,
                onLaunchFrom, onActions, onOpen, entranceKey, reduceMotion = true, peekProgress = null,
                sharedTransitionScope = null, animatedVisibilityScope = null, modifier)
        }
        return
    }
    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = open,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "library-category",
        ) { target ->
            if (target != null) {
                val openSection = sections.firstOrNull { it.category == target }
                CategoryApps(openSection?.apps ?: emptyList(), target, glass, ink, drag, page, onLaunchFrom, onActions,
                    sharedTransitionScope = this@SharedTransitionLayout, animatedVisibilityScope = this@AnimatedContent,
                    Modifier.fillMaxSize())
            } else {
                LibraryGridContent(sections, expanded, glass, ink, workPausedMessage, onTurnOnWork, loading, drag, page,
                    onLaunchFrom, onActions, onOpen, entranceKey, reduceMotion = false, peekProgress = peekProgress,
                    sharedTransitionScope = this@SharedTransitionLayout, animatedVisibilityScope = this@AnimatedContent,
                    Modifier.fillMaxSize())
            }
        }
    }
}

/** The category tile grid itself, shared between the animated (SharedTransitionLayout) and reduce-motion paths. */
@Composable
private fun LibraryGridContent(
    sections: List<LibrarySection<AppEntry>>, expanded: Boolean, glass: Boolean, ink: Color,
    workPausedMessage: String?, onTurnOnWork: (() -> Unit)?, loading: Boolean,
    drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit, onOpen: (AppCategory) -> Unit,
    entranceKey: Int, reduceMotion: Boolean, peekProgress: Float?,
    sharedTransitionScope: SharedTransitionScope?, animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier,
) {
    LazyVerticalGrid(GridCells.Adaptive(if (expanded) TILE_MIN_EXPANDED else TILE_MIN_COMPACT),
        modifier.fillMaxSize().testTag("library-categories"),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (workPausedMessage != null) item(key = "work-paused", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(workPausedMessage, color = ink)
                if (onTurnOnWork != null) Button(onClick = onTurnOnWork,
                    Modifier.padding(top = 10.dp).testTag("turn-on-work")) { Text("Turn on work apps") }
            }
        }
        if (sections.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Text(if (loading) "Loading apps…" else "No apps found", Modifier.padding(vertical = 20.dp), color = ink)
        }
        itemsIndexed(sections, key = { _, section -> section.category.name }) { index, section ->
            CategoryTile(section, expanded, glass, ink, drag, page, onLaunchFrom, onActions, onOpen = { onOpen(section.category) },
                entranceKey = entranceKey, entranceIndex = index, reduceMotion = reduceMotion, peekProgress = peekProgress,
                sharedTransitionScope = sharedTransitionScope, animatedVisibilityScope = animatedVisibilityScope)
        }
    }
}

/**
 * One folder: a square translucent tile holding a 2×2 block — large launchable icons and,
 * for folders with five or more apps, a cluster of four small icons that opens the folder
 * (see [tileSlots]) — with the folder name centred below it.
 *
 * B31 entrance: fades in and rises 8 dp, delayed by [entranceIndex] * 25 ms, replayed whenever
 * [entranceKey] changes (once per pager settle on the App Library page, not on every
 * recomposition — see [libraryEntranceDelayMs]). Skipped entirely when [reduceMotion].
 */
@Composable
private fun CategoryTile(
    section: LibrarySection<AppEntry>, expanded: Boolean, glass: Boolean, ink: Color, drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit, onOpen: () -> Unit,
    entranceKey: Int, entranceIndex: Int, reduceMotion: Boolean, peekProgress: Float?,
    sharedTransitionScope: SharedTransitionScope?, animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    val shape = RoundedCornerShape(TILE_CORNER)
    val fill = if (glass) TileFill else MaterialTheme.colorScheme.surfaceContainerHigh
    val slots = remember(section) { tileSlots(section) }
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(entranceKey, reduceMotion) {
        if (reduceMotion) {
            entrance.snapTo(1f)
        } else {
            entrance.snapTo(0f)
            delay(libraryEntranceDelayMs(entranceIndex))
            entrance.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        }
    }
    val boundsModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) with(sharedTransitionScope) {
        Modifier.sharedBounds(
            rememberSharedContentState(key = "category-bounds-${section.category.name}"),
            animatedVisibilityScope = animatedVisibilityScope, boundsTransform = LibraryBoundsTransform,
        )
    } else Modifier
    // B24 follow-up ("App Library peek"): only the first row (the tiles visible in the sliver
    // that peeks in past the last Home page) leads in from 24 dp; the rest of the grid sits still.
    // The row width is the documented layout (LibraryBrowser's kdoc): 2 columns on the cover,
    // where this pager-driven peek exists at all, 4 on the inner display.
    val firstRowColumns = if (expanded) 4 else 2
    val peekOffsetDp = if (peekProgress != null && entranceIndex < firstRowColumns) (1f - peekProgress) * 24f else 0f
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth()
        .graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 8.dp.toPx()
            translationX = with(density) { peekOffsetDp.dp.toPx() } },
        horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).then(boundsModifier).clip(shape).background(fill, shape)
            .then(if (glass) Modifier.border(BorderStroke(1.dp, TileBorder), shape) else Modifier)
            .testTag("library-category-${section.category.name}"), contentAlignment = Alignment.Center) {
            // Each cell fills half of the inner square, capped so icons stay Home-sized.
            val cell = ((maxWidth - TILE_PADDING * 2 - TILE_GAP) / 2)
                .coerceIn(32.dp, if (expanded) TILE_ICON_MAX_EXPANDED else TILE_ICON_MAX_COMPACT)
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP), horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    for (column in 0 until 2) {
                        val index = row * 2 + column
                        val app = slots.large.getOrNull(index)
                        when {
                            app != null -> LibraryIcon(app, section.category, cell, drag, page, onLaunchFrom, onActions,
                                sharedTransitionScope, animatedVisibilityScope)
                            index == TILE_SLOTS - 1 && slots.hasCluster ->
                                MiniCluster(slots.cluster, section.category, cell, glass, onOpen, sharedTransitionScope, animatedVisibilityScope)
                            else -> Spacer(Modifier.size(cell))
                        }
                    }
                }
            }
        }
        Text(section.category.title, Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp)
            .clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 1.dp)
            .testTag("library-category-name-${section.category.name}"),
            style = if (glass) LibraryLabelStyle else LibraryLabelStyle.copy(color = ink, shadow = null),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * One launchable icon. Long-press starts the shared Home drag through the root
 * `homeDragInput`; the region key carries the section so the same app can sit in
 * Suggestions and in its category at once (drops read `appId`, not the key).
 *
 * B31: when [sharedTransitionScope] is non-null (the tile → grid transition is running), this
 * icon shares its bounds with the same package's icon in the open category's full grid — or in
 * the tile's mini cluster — under key `"library-icon-<category>:<id>"`, so it visibly scatters
 * from its tile position into its grid position instead of cross-fading in place.
 */
@Composable
private fun LibraryIcon(
    app: AppEntry, category: AppCategory, size: Dp, drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    sharedTransitionScope: SharedTransitionScope?, animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
) {
    val launchBounds = remember { android.graphics.Rect() }
    val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library("${category.name}:${app.id}"), app.id, page) else Modifier
    val click = { onLaunchFrom(app, launchBounds) }
    val shareModifier = libraryIconShareModifier(category, app.id, sharedTransitionScope, animatedVisibilityScope)
    // B23: same shared press spring as Home/dock/folder icons.
    val interaction = remember { MutableInteractionSource() }
    val iconStyle = LocalIconStyle.current
    val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[app.packageName] ?: 0
    Box(modifier.size(size).notificationBadge(badgeCount).then(shareModifier).then(dragModifier)
        .then(if (drag == null) Modifier.combinedClickable(interactionSource = interaction, indication = LocalIndication.current, onClick = click, onLongClick = { onActions(app) })
            else Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = click).semantics { onLongClick("App options") { onActions(app); true } })
        .testTag("library-icon-${category.name}-${app.id}")) {
        // B41 Liquid Glass follow-up: same wallpaper-refracting panel as Home/dock/folder tiles,
        // gated on the same style flag — App Library was left flat-tinted when B41 first landed.
        if (iconStyle.usesLiquidGlass) Box(Modifier.matchParentSize()
            .liquidGlassPanel(iconStyle.clipShape(), (size.value * ROUNDED_SQUARE_RADIUS).dp))
        Image(app.icon.asImageBitmap(), app.label, Modifier.fillMaxSize()
            .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) }
            .iconPressScale(interaction)
            .clip(iconStyle.clipShape()))
    }
}

/** Shared-element modifier for one app's icon, or [Modifier] unmodified outside a running shared transition. */
@Composable
private fun libraryIconShareModifier(
    category: AppCategory, appId: String,
    sharedTransitionScope: SharedTransitionScope?, animatedVisibilityScope: AnimatedVisibilityScope?,
): Modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) with(sharedTransitionScope) {
    Modifier.sharedElement(
        rememberSharedContentState(key = "library-icon-${category.name}:$appId"),
        animatedVisibilityScope = animatedVisibilityScope, boundsTransform = LibraryBoundsTransform,
    )
} else Modifier

/** The fourth slot: a rounded square the size of a large icon holding a 2×2 grid of tiny icons; tapping opens the folder. */
@Composable
private fun MiniCluster(
    apps: List<AppEntry>, category: AppCategory, size: Dp, glass: Boolean, onOpen: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?, animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    val inner = 6.dp
    val small = ((size - inner * 3) / 2).coerceAtLeast(12.dp)
    val shape = RoundedCornerShape(size * .22f)
    Column(Modifier.size(size).clip(shape)
        .background(if (glass) TileFill else MaterialTheme.colorScheme.surfaceContainerHighest, shape)
        .clickable(onClick = onOpen).testTag("library-cluster-${category.name}")
        .padding(inner), verticalArrangement = Arrangement.spacedBy(inner)) {
        for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(inner)) {
            for (column in 0 until 2) {
                val app = apps.getOrNull(row * 2 + column)
                if (app != null) {
                    val shareModifier = libraryIconShareModifier(category, app.id, sharedTransitionScope, animatedVisibilityScope)
                    val badgeCount = cz.pflanzer.foldduo.notifications.LocalNotificationBadges.current[app.packageName] ?: 0
                    Image(app.icon.asImageBitmap(), null, Modifier.size(small).then(shareModifier)
                        .clip(LocalIconStyle.current.clipShape()).notificationBadge(badgeCount))
                } else Spacer(Modifier.size(small))
            }
        }
    }
}

/**
 * An open folder: alphabetical icon grid, 4–6 columns by width. The "‹ name" header is drawn by
 * [AppLibrary]. B31: this grid's own bounds share-morph from the tapped tile's square (see
 * [CategoryTile]'s `boundsModifier`), and each icon it holds that was already visible in the
 * tile (large or mini-cluster) scatters in from that tile position (see [LibraryIcon]).
 */
@Composable
private fun CategoryApps(
    apps: List<AppEntry>, category: AppCategory, glass: Boolean, ink: Color, drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    sharedTransitionScope: SharedTransitionScope?, animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier,
) {
    val boundsModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) with(sharedTransitionScope) {
        Modifier.sharedBounds(
            rememberSharedContentState(key = "category-bounds-${category.name}"),
            animatedVisibilityScope = animatedVisibilityScope, boundsTransform = LibraryBoundsTransform,
        )
    } else Modifier
    BoxWithConstraints(modifier.then(boundsModifier).testTag("library-category-apps")) {
        val columns = (maxWidth / 88.dp).toInt().coerceIn(4, 6)
        val cell = maxWidth / columns
        val icon = (cell - 20.dp).coerceIn(44.dp, 64.dp)
        val label = if (glass) LibraryLabelStyle.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
            else LibraryLabelStyle.copy(color = ink, shadow = null, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
            if (apps.isEmpty()) item(key = "empty", span = { GridItemSpan(columns) }) {
                Text("No apps here", Modifier.padding(vertical = 20.dp), color = ink)
            }
            items(apps, key = { it.id }) { app ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LibraryIcon(app, category, icon, drag, page, onLaunchFrom, onActions, sharedTransitionScope, animatedVisibilityScope)
                    Text(app.label, Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp), style = label,
                        maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
