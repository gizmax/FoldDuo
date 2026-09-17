@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cz.pflanzer.foldduo.island

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.HapticEvent
import cz.pflanzer.foldduo.Haptics
import cz.pflanzer.foldduo.LocalFrostedBackdrop
import cz.pflanzer.foldduo.LocalWallpaperPalette
import cz.pflanzer.foldduo.RAIL_GAP_DP
import cz.pflanzer.foldduo.RAIL_ISLAND_ITEM_HEIGHT_DP
import cz.pflanzer.foldduo.RAIL_ISLAND_MAX_COLLAPSED
import cz.pflanzer.foldduo.GLASS_PILL_BORDER_ALPHA
import cz.pflanzer.foldduo.backdropPlacement
import cz.pflanzer.foldduo.glassPill
import cz.pflanzer.foldduo.notifications.NotificationPulse
import cz.pflanzer.foldduo.paneInkColor
import cz.pflanzer.foldduo.trackRecomposition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long an expanded card stays open without a touch before it folds back into a pill. */
const val ISLAND_EXPANDED_TIMEOUT_MS = 6_000L

/** Pill corner radius, half its own height ([RAIL_ISLAND_ITEM_HEIGHT_DP] / 2), animated via [animateDpAsState]; the expanded card's corner is [ISLAND_MORPH_CARD_CORNER_DP] (island/IslandCardMotion.kt). */
private val ISLAND_PILL_CORNER_DP = 22.dp

/** `spring(dampingRatio 0.75, stiffness Medium)`, the motion spec every island transition shares. */
private const val ISLAND_SPRING_DAMPING = 0.75f

/** How far a swipe-flung pill travels off-screen before it is dropped from composition. */
private const val ISLAND_DISMISS_FLING_FACTOR = 1.4f

/**
 * The shared motion spec for every island animation: `spring(dampingRatio = 0.75, stiffness =
 * Medium)`, or an instant [snap] once the system asks for reduced motion
 * ([Settings.Global.ANIMATOR_DURATION_SCALE] `== 0`, B30 item 4).
 */
private fun <T> islandSpec(reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else spring(dampingRatio = ISLAND_SPRING_DAMPING, stiffness = Spring.StiffnessMedium)

/** True once the system's animator duration scale is zero: the reduce-motion gate for B30 item 4. */
private fun reduceMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

private const val ISLAND_STYLE_PREFS = "appearance"
private const val ISLAND_OPACITY_KEY = "islandOpacity"

/**
 * "Island transparency" (Colours & glass settings, Tom's 2026-09-17 request): how opaque the pill
 * veil and the expanded card's glass/gradient are ([islandPillVeilAlpha]/[islandCardVeilAlpha]/
 * [islandCardGradientAlpha] in `IslandCardMotion.kt`), `0.4..1.0`, default `0.75`. Same
 * `appearance` `SharedPreferences` file the other Colours & glass rows use
 * (`WallpaperPalette.kt`/`DuneWallpaper.kt`), and the same live-[State] pattern as
 * [cz.pflanzer.foldduo.MotionPrefs]/[cz.pflanzer.foldduo.notifications.NotificationHubSettings] so
 * the settings slider and the rail island — often far apart in the composition — agree within one
 * session without a restart. [refresh] seeds [current] from prefs; nothing else in this file calls
 * it directly (`RailIsland`/`IslandExpandedOverlay` each do once, on first composition, since
 * either can mount before the other depending on whether any item exists yet).
 */
object IslandStyle {
    private val state = mutableFloatStateOf(ISLAND_OPACITY_DEFAULT)

    /** Read by `IslandPill`/`IslandExpandedOverlay`; live across the whole process once seeded. */
    val current: State<Float> get() = state

    /** Re-read from `SharedPreferences` (call once per composable lifetime; see [IslandStyle]'s own doc). */
    fun refresh(context: Context) { state.floatValue = opacity(context) }

    /** The persisted value directly, without touching the live [current] state. */
    fun opacity(context: Context): Float = islandOpacityClamped(
        context.getSharedPreferences(ISLAND_STYLE_PREFS, Context.MODE_PRIVATE)
            .getFloat(ISLAND_OPACITY_KEY, ISLAND_OPACITY_DEFAULT))

    /** Persist [value] (clamped) and update [current] immediately so every live reader reflects it this frame. */
    fun setOpacity(context: Context, value: Float) {
        val clamped = islandOpacityClamped(value)
        context.getSharedPreferences(ISLAND_STYLE_PREFS, Context.MODE_PRIVATE).edit().putFloat(ISLAND_OPACITY_KEY, clamped).apply()
        state.floatValue = clamped
    }
}

/**
 * The rail island (iPhone Duo's vertical Dynamic Island, PLAN.md rail item 3): a stack of at most
 * [RAIL_ISLAND_MAX_COLLAPSED] pills directly under the status block, each [RAIL_ISLAND_ITEM_HEIGHT_DP]
 * tall, with any further items folded into a "+N" badge on the last pill. A tap on a pill expands
 * it; a long-press opens the pill's app directly, without expanding.
 *
 * "Ostrůvek do plochy" (17. 9. večer, Tom's device feedback that a squeezed media card wrapped its
 * text badly): the expanded card itself no longer lives here. It grows INTO the page like Dynamic
 * Island — a root-level overlay ([IslandExpandedOverlay], composed once in `LauncherScreen.kt`
 * next to `SpotlightOverlay`) anchored to the tapped pill's own position but free to extend past
 * this composable's width, which stays capped to the rail column. This composable now only draws
 * the collapsed stack, fading a pill to invisible ("ghosted") while its own key is [expandedKey]
 * — the overlay's card visually takes its place at the same top/right anchor. [expandedKey] is
 * still hoisted here (not just read) because the auto-collapse and idle-timeout rules below own
 * it: an expanded card whose item vanishes from [items], or one left untouched for
 * [ISLAND_EXPANDED_TIMEOUT_MS], collapses on its own.
 *
 * B61 "Ostrov kolem kamery" rail hand-off: self-guards to nothing while
 * [cz.pflanzer.foldduo.cameraisland.CameraIslandSettings]'s "Live activities" setting is Camera
 * island (the default) — the same [items] then show around the camera cutout instead
 * (`cameraisland/CameraIsland.kt`'s `CameraIslandOverlay`). `LayoutModel.kt`'s `islandSlotHeight`
 * collapses this composable's reserved rail space to match, so the dock slides up rather than
 * leaving a gap where these pills would have been.
 */
@Composable
fun RailIsland(
    items: List<IslandItem>,
    expandedKey: String?,
    onExpandedChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cz.pflanzer.foldduo.cameraisland.cameraIslandIsActiveSurface()) return
    trackRecomposition("RailIsland")
    val context = LocalContext.current
    val reduceMotion = reduceMotionEnabled(context)
    // B29 "Barvy z tapety": ink for pills/cards painted straight on the wallpaper, sampled from
    // this window's own pane (cover and inner can show different crops of the same picture).
    val ink = paneInkColor()
    LaunchedEffect(Unit) { IslandStyle.refresh(context) }
    val opacity by IslandStyle.current
    val ticking = items.any { it.chronometerBase != null || it.media != null }
    val now by produceState(System.currentTimeMillis(), ticking) {
        while (ticking) {
            value = System.currentTimeMillis()
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    LaunchedEffect(expandedKey, items) {
        if (islandShouldAutoCollapse(expandedKey, items)) onExpandedChange(null)
    }
    LaunchedEffect(expandedKey) {
        if (expandedKey != null) {
            delay(ISLAND_EXPANDED_TIMEOUT_MS)
            onExpandedChange(null)
        }
    }
    Box(modifier.testTag("rail-island")) {
        Column(verticalArrangement = Arrangement.spacedBy(RAIL_GAP_DP.dp)) {
            val visible = items.take(RAIL_ISLAND_MAX_COLLAPSED)
            visible.forEachIndexed { index, item ->
                val overflow = if (index == visible.lastIndex) items.size - visible.size else 0
                IslandPill(item, now, overflow, reduceMotion, ink, opacity, ghosted = item.key == expandedKey,
                    onExpand = { Haptics.play(context, HapticEvent.ISLAND_EXPAND); onExpandedChange(item.key) },
                    onOpen = { launchIsland(context, item.contentIntent) })
            }
        }
    }
}

/**
 * The transition an item's content plays when it changes: a Now Bar activity replacing another
 * ([IslandItem.isNowBar] on either side, B30 item 3) cross-slides vertically (old up, new in from
 * below); any other change — including the pill's front slot switching kind, timer to call to
 * media, B30 item 1 — crossfades with a slight 0.9→1 scale. Either way the container follows with
 * a spring-based [SizeTransform].
 */
private fun islandContentTransitionSpec(
    initial: IslandItem,
    target: IslandItem,
    reduceMotion: Boolean,
): ContentTransform {
    val base = if (islandContentTransition(initial.key, target.key) == IslandContentTransition.REPLACE &&
        (initial.isNowBar || target.isNowBar)
    ) {
        (slideInVertically(islandSpec(reduceMotion)) { full -> full } + fadeIn()) togetherWith
            (slideOutVertically(islandSpec(reduceMotion)) { full -> -full } + fadeOut())
    } else {
        (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f))
    }
    return ContentTransform(base.targetContentEnter, base.initialContentExit,
        sizeTransform = SizeTransform { _, _ -> islandSpec(reduceMotion) })
}

/** Continuously advances a 2 %/4 s breathing scale (B30 item 4) while [enabled]; `1f` otherwise. */
@Composable
private fun rememberIslandBreathing(enabled: Boolean): State<Float> {
    val scale = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            scale.floatValue = 1f
            return@LaunchedEffect
        }
        var start = -1L
        while (true) {
            withFrameMillis { frameTime ->
                if (start < 0L) start = frameTime
                scale.floatValue = islandBreathingScale(frameTime - start)
            }
        }
    }
    return scale
}

/** How far a pill pops when a pulse fires (B50, subtler than the badge's own 1.3x — BadgeOverlay.kt — since a whole pill enlarging that much would jostle its neighbours in the rail's tight column). */
private const val ISLAND_PULSE_POP_SCALE = 1.12f
private val IslandPulsePopSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

/**
 * IDEAS.md B50 "Tapeta dýchá s oznámením": pops a pill (scale [ISLAND_PULSE_POP_SCALE] -> 1, a
 * spring) whenever [NotificationPulse.pulse] changes — the rail's half of "the rail island and the
 * icon badge of the same app pop in the same rhythm" (see [Modifier.notificationBadge]'s own copy
 * of this for the same caveat: [Pulse] carries no package name, so every pill pops together rather
 * than only the one for the app that actually got a notification).
 */
@Composable
private fun rememberIslandPulsePop(reduceMotion: Boolean): Animatable<Float, AnimationVector1D> {
    val pulseAtMs = NotificationPulse.pulse.collectAsState().value?.atMs
    val pop = remember { Animatable(1f) }
    LaunchedEffect(pulseAtMs, reduceMotion) {
        if (pulseAtMs == null || reduceMotion) return@LaunchedEffect
        pop.snapTo(ISLAND_PULSE_POP_SCALE)
        pop.animateTo(1f, IslandPulsePopSpring)
    }
    return pop
}

@Composable
private fun IslandPill(
    item: IslandItem,
    now: Long,
    overflow: Int,
    reduceMotion: Boolean,
    ink: Color,
    /** "Island transparency" (Colours & glass settings): scales the pill's veil/fill alpha, see [islandPillVeilAlpha]. */
    opacity: Float,
    /** True while this exact pill is the one expanded — its own overlay card ([IslandExpandedOverlay]) draws in its place, so the pill itself fades out ("Ostrůvek do plochy" item 1: "the pill itself stays in the rail, hidden/ghosted while expanded"). */
    ghosted: Boolean,
    onExpand: () -> Unit,
    onOpen: () -> Unit,
) {
    val description = listOfNotNull(islandKindLabel(item.kind), item.title.takeIf { it.isNotBlank() },
        compactIslandLabel(item, now), if (overflow > 0) "$overflow more" else null).joinToString(", ")
    val corner by animateDpAsState(ISLAND_PILL_CORNER_DP, islandSpec(reduceMotion), label = "island-pill-corner")
    val dismissible = islandItemDismissible(item.kind)
    val offsetX = remember(item.key) { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val breathing by rememberIslandBreathing(item.chronometerBase != null && !reduceMotion)
    val pulsePop = rememberIslandPulsePop(reduceMotion)
    val ghostAlpha by animateFloatAsState(if (ghosted) 0f else 1f, islandSpec(reduceMotion), label = "island-pill-ghost")
    Box(Modifier.fillMaxWidth().height(RAIL_ISLAND_ITEM_HEIGHT_DP.dp)
        .onSizeChanged { widthPx = it.width.toFloat() }
        .graphicsLayer {
            translationX = offsetX.value
            scaleX = breathing * pulsePop.value
            scaleY = breathing * pulsePop.value
            alpha = ghostAlpha
        }
        .clip(RoundedCornerShape(corner))
        // "Matné sklo pro všechny pilulky" (17. 9. noc): blurred wallpaper + veil instead of a flat
        // colour fill — islandFill's item-accent blend is now the veil's own tint, not an opaque
        // background, so it still reads as a colour hint but through actual frosted glass.
        .glassPill(corner = corner, opacity = opacity, tint = islandFill(item, 1f), baseVeilAlpha = .55f)
        .combinedClickable(onClick = onExpand, onLongClick = onOpen, role = Role.Button)
        .semantics { contentDescription = description }
        .pointerInput(item.key, dismissible) {
            if (!dismissible) return@pointerInput
            val velocityTracker = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { velocityTracker.resetTracking() },
                onDragEnd = {
                    val velocity = velocityTracker.calculateVelocity().x
                    val dismissedNow = islandSwipeDismissed(offsetX.value, widthPx, velocity)
                    scope.launch {
                        if (dismissedNow) {
                            val distance = widthPx.coerceAtLeast(RAIL_ISLAND_ITEM_HEIGHT_DP) * ISLAND_DISMISS_FLING_FACTOR
                            val target = if (offsetX.value >= 0f) distance else -distance
                            offsetX.animateTo(target, islandSpec(reduceMotion))
                            IslandNotificationListener.dismiss(item.key)
                        } else {
                            offsetX.animateTo(0f, islandSpec(reduceMotion))
                        }
                    }
                },
                onDragCancel = { scope.launch { offsetX.animateTo(0f, islandSpec(reduceMotion)) } },
            ) { change, dragAmount ->
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
            }
        }
        .testTag("island-pill-${item.kind.name.lowercase()}")) {
        Row(Modifier.fillMaxWidth().height(RAIL_ISLAND_ITEM_HEIGHT_DP.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                item.progress?.let { ProgressRing(it, ink, Modifier.size(26.dp)) }
                // "Apple Music karta a morph" item 3: a media pill shows the track's own artwork
                // thumbnail (20 dp) instead of the app icon once one is known.
                if (item.kind == IslandKind.MEDIA) PillArtworkGlyph(item, 20.dp, ink) else IslandGlyph(item, 18.dp, ink)
            }
            AnimatedContent(targetState = item, modifier = Modifier.weight(1f),
                transitionSpec = { islandContentTransitionSpec(initialState, targetState, reduceMotion) },
                label = "island-pill-content") { current ->
                val currentLabel = compactIslandLabel(current, now)
                when {
                    // A 3-bar equalizer glyph (animated while playing, static while paused) plus
                    // the track title, marquee-scrolled only once and only if it does not fit.
                    current.kind == IslandKind.MEDIA -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EqualizerGlyph(current.media?.playing == true, reduceMotion, ink, Modifier.width(16.dp).height(14.dp))
                        Text(current.title, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).basicMarquee(iterations = 1, initialDelayMillis = 1_500))
                    }
                    currentLabel != null && current.chronometerBase != null ->
                        DigitRollText(currentLabel, ink, 11.sp, FontWeight.SemiBold, reduceMotion)
                    currentLabel != null -> Text(currentLabel, color = ink, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (overflow > 0) Text("+$overflow", color = ink, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 7.dp).testTag("island-overflow"))
    }
}

/** The rail island's own right-edge padding (`LauncherScreen.kt`'s `RailIsland` call site); [IslandExpandedOverlay] anchors its card to this same edge, not [cz.pflanzer.foldduo.RAIL_EDGE_PADDING_DP] (the dock/status/search column, a slightly different padding). */
const val RAIL_ISLAND_RIGHT_PADDING_DP = 8f

/** `spring(dampingRatio 0.82, stiffness 300)`, the one morph spec item 1 asks for — noticeably snappier/less bouncy than [islandSpec]'s 0.75/Medium, since this spring is now doing the whole pill<->card transform, not just a small pop. */
private val ISLAND_MORPH_SPRING = spring<Float>(dampingRatio = 0.82f, stiffness = 300f)

/**
 * "Apple Music karta a morph" (17. 9. noc, Tom's device feedback: "udělej lepší animaci,
 * maximalizovanou verzi lépe poskládat, ideálně ve stylu oficiálního widgetu Apple Music"): the
 * expanded island card, grown into the page like Dynamic Island ([RailIsland]'s own doc explains
 * the anchor). A single [morph] `Animatable` (0 = pill, 1 = card) now drives every layer of the
 * transform through [island.IslandCardMotion]'s pure functions, sampled only inside
 * `graphicsLayer`/`layout`/draw lambdas ([Modifier.layout], a `RoundedCornerShape` rebuilt fresh each frame inside `graphicsLayer`, `drawWithCache`)
 * so the composable body itself never reads it — one spring animation, no per-frame recomposition
 * of this function or its children. [ISLAND_MORPH_SPRING] plays it (item 1's `dampingRatio 0.82,
 * stiffness 300`); reduced motion collapses it into a flat [ISLAND_MORPH_REDUCED_MOTION_MS] tween,
 * which — since every layer's opacity/position is itself a function of the same `morph` value —
 * reads as a short crossfade rather than a snap. Collapse plays the identical spec in reverse.
 */
@Composable
fun IslandExpandedOverlay(
    items: List<IslandItem>,
    expandedKey: String?,
    anchor: IslandCardAnchor,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = reduceMotionEnabled(context)
    val ink = paneInkColor()
    LaunchedEffect(Unit) { IslandStyle.refresh(context) }
    val opacity by IslandStyle.current
    val expandedItem = items.firstOrNull { it.key == expandedKey }
    // The card keeps drawing the last item through its collapse animation.
    val lastItem = remember { arrayOfNulls<IslandItem>(1) }
    if (expandedItem != null) lastItem[0] = expandedItem
    val item = expandedItem ?: lastItem[0] ?: return
    val visible = expandedItem != null
    val ticking = item.chronometerBase != null && item.media == null
    val now by produceState(System.currentTimeMillis(), item.key, ticking) {
        while (ticking) {
            value = System.currentTimeMillis()
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    val index = items.take(RAIL_ISLAND_MAX_COLLAPSED).indexOfFirst { it.key == item.key }.coerceAtLeast(0)
    val pillTopDp = islandPillTopDp(anchor.islandTopDp, index)
    val rect = islandCardRect(anchor, pillTopDp, item.media != null)
    val railRightDp = anchor.paneStartDp + anchor.paneWidthDp
    // Both rects share the same top-right anchor by construction (islandCardRect uses pillTopDp
    // as its own top), so only width/height need to morph — the container never has to slide.
    val pillRect = remember(item.key, pillTopDp, anchor.pillWidthDp, railRightDp) {
        MorphRect(railRightDp - anchor.pillWidthDp, pillTopDp, anchor.pillWidthDp, RAIL_ISLAND_ITEM_HEIGHT_DP)
    }
    val cardRect = remember(item.key, rect.leftDp, rect.topDp, rect.widthDp, rect.heightDp) {
        MorphRect(rect.leftDp, rect.topDp, rect.widthDp, rect.heightDp)
    }
    val morph = remember(item.key) { Animatable(if (visible) 0f else 1f) }
    LaunchedEffect(item.key, visible, reduceMotion) {
        val target = if (visible) 1f else 0f
        val spec: FiniteAnimationSpec<Float> = if (reduceMotion) tween(ISLAND_MORPH_REDUCED_MOTION_MS) else ISLAND_MORPH_SPRING
        morph.animateTo(target, spec)
    }
    if (!visible && morph.value <= 0.001f && !morph.isRunning) return
    val progress: () -> Float = { morph.value }
    val offsetY = remember(item.key) { Animatable(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun dismiss() {
        Haptics.play(context, HapticEvent.ISLAND_DISMISS)
        onDismiss()
    }
    fun open() {
        launchIsland(context, item.contentIntent)
        dismiss()
    }
    fun runAction(action: IslandAction) {
        launchIsland(context, action.intent)
        dismiss()
    }
    val baseFill = islandFill(item, islandCardVeilAlpha(opacity, .92f))
    val borderColor = ink.copy(alpha = GLASS_PILL_BORDER_ALPHA)
    val dominant = item.accentColor
    val gradientTop = dominant?.let { Color(mediaCardGradientTopArgb(it)) }
    val gradientBottom = dominant?.let { Color(mediaCardGradientBottomArgb(it)) }
    // "Matné sklo pro všechny pilulky" (17. 9. noc): the same blurred-wallpaper backing every other
    // glass surface gets via Modifier.glassPill, hand-rolled here instead of going through that
    // composable-level modifier because this container's corner/rect animate every single frame
    // inside graphicsLayer/drawWithCache without ever recomposing this function (see the doc above)
    // — glassPill's default parameters would force a recomposition per frame to track that. baseFill
    // (already opacity-scaled via islandCardVeilAlpha) is drawn as the veil ON TOP of the blur, and
    // the album gradient sits between the two, exactly like the task spec's "gradient stays under
    // the glass" — never replacing the blur with an opaque colour.
    val backdrop = LocalFrostedBackdrop.current
    val windowX = remember { mutableFloatStateOf(0f) }
    val windowY = remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxSize().testTag("island-expanded-overlay")) {
        Box(Modifier.align(Alignment.TopEnd).padding(end = RAIL_ISLAND_RIGHT_PADDING_DP.dp)
            .offset(y = pillTopDp.dp)
            // Layout-phase size read: the one glass container's rect interpolates pill -> card.
            .layout { measurable, _ ->
                val r = islandMorphRect(morph.value, pillRect, cardRect)
                val w = r.widthDp.dp.roundToPx().coerceAtLeast(0)
                val h = r.heightDp.dp.roundToPx().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .onSizeChanged { heightPx = it.height.toFloat() }
            .onGloballyPositioned { coords ->
                val position = coords.positionInWindow()
                windowX.floatValue = position.x
                windowY.floatValue = position.y
            }
            // Draw-phase reads: translation and the corner radius both sample `morph` fresh every
            // frame without recomposing this composable — a `RoundedCornerShape` built fresh inside
            // the same block the radius is read in, so the layer's own invalidation always ties the
            // two together (a Shape object built outside this block, only referencing `morph.value`
            // in its own `createOutline`, is not guaranteed to be re-evaluated every frame here).
            .graphicsLayer {
                translationY = offsetY.value
                val corner = islandMorphCornerDp(morph.value, RAIL_ISLAND_ITEM_HEIGHT_DP)
                shape = RoundedCornerShape(corner.dp)
                clip = true
            }
            .drawWithCache {
                val hairline = 1.dp.toPx()
                val inset = hairline / 2f
                onDrawBehind {
                    val image = backdrop?.image
                    if (image != null && backdrop.width > 0f && backdrop.height > 0f) {
                        val placement = backdropPlacement(windowX.floatValue, windowY.floatValue, backdrop.originX,
                            backdrop.originY, backdrop.width, backdrop.height, image.width, image.height)
                        withTransform({
                            translate(placement.offsetX, placement.offsetY)
                            scale(placement.scaleX, placement.scaleY, Offset.Zero)
                        }) { drawImage(image) }
                    }
                    drawRect(baseFill)
                    if (gradientTop != null && gradientBottom != null) {
                        val gradientAlpha = islandCardGradientAlpha(opacity, islandMorphWindow(morph.value, 0.3f, 1f))
                        if (gradientAlpha > 0f) drawRect(Brush.verticalGradient(listOf(gradientTop, gradientBottom)), alpha = gradientAlpha)
                    }
                    val radius = islandMorphCornerDp(morph.value, RAIL_ISLAND_ITEM_HEIGHT_DP).dp.toPx()
                    drawRoundRect(borderColor, Offset(inset, inset), Size(size.width - hairline, size.height - hairline),
                        CornerRadius((radius - inset).coerceAtLeast(0f)), Stroke(hairline))
                }
            }
            .combinedClickable(onClick = ::open, onLongClick = ::open, role = Role.Button)
            .pointerInput(item.key) {
                val velocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { velocityTracker.resetTracking() },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().y
                        val swipedUp = offsetY.value < 0f && islandSwipeDismissed(offsetY.value, heightPx, velocity)
                        scope.launch {
                            if (swipedUp) { offsetY.animateTo(-heightPx, islandSpec(reduceMotion)); dismiss() }
                            else offsetY.animateTo(0f, islandSpec(reduceMotion))
                        }
                    },
                    onDragCancel = { scope.launch { offsetY.animateTo(0f, islandSpec(reduceMotion)) } },
                ) { change, dragAmount ->
                    if (dragAmount < 0f) change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offsetY.snapTo((offsetY.value + dragAmount).coerceAtMost(0f)) }
                }
            }
            .testTag("island-card")) {
            // Pill-style content (icon/equalizer/marquee) fades out over the morph's first 30%.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = islandPillContentAlpha(morph.value) }) {
                OverlayPillContent(item, now, reduceMotion, ink)
            }
            // Card content: media gets the Apple-Music-style layout (item 2); everything else
            // keeps its existing frame, just fading in over the same window as the media title.
            if (item.media != null) {
                val lightText = remember(gradientTop, gradientBottom) {
                    if (gradientTop != null && gradientBottom != null)
                        mediaCardUsesLightText(gradientTop.toArgb(), gradientBottom.toArgb()) else true
                }
                MediaCardContent(item, if (lightText) Color.White else Color.Black, progress)
                MorphingMediaArtwork(item, ink, progress, pillIconLocalRect, cardArtworkLocalRect)
            } else {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = islandCardTextAlpha(morph.value) }) {
                    GenericCardContent(item, now, reduceMotion, ink, onAction = ::runAction)
                }
            }
        }
    }
}

/** The pill's own leading-icon slot, in the container's LOCAL coordinates (top-left = `0,0`) — the shared artwork/icon element's morph-start rect. */
private val pillIconLocalRect = MorphRect(8f, (RAIL_ISLAND_ITEM_HEIGHT_DP - 26f) / 2f, 26f, 26f)

/** The Apple-Music-style card's artwork frame, in the container's LOCAL coordinates — the shared element's morph-end rect (task spec item 2: "left: artwork 96 dp with 14 dp corners"). */
private val cardArtworkLocalRect = MorphRect(14f, 14f, 96f, 96f)

/**
 * The pill's small artwork/icon travelling and scaling into the card's large artwork position —
 * shared-element style (task spec item 1), not a crossfade: one [Image]/[IslandGlyph], its rect
 * lerped every frame between [pillIconLocalRect] and [cardIconLocalRect] via [islandArtworkRect],
 * position through a placement-phase `offset` lambda and size through a draw-phase `graphicsLayer`
 * scale — neither recomposes this composable.
 */
@Composable
private fun MorphingMediaArtwork(item: IslandItem, ink: Color, progress: () -> Float, pillIconLocalRect: MorphRect, cardIconLocalRect: MorphRect) {
    val media = item.media ?: return
    val artwork = remember(media.artwork) { media.artwork?.asImageBitmap() }
    Box(Modifier.size(cardIconLocalRect.widthDp.dp)
        .offset {
            val r = islandArtworkRect(progress(), pillIconLocalRect, cardIconLocalRect)
            IntOffset(r.leftDp.dp.roundToPx(), r.topDp.dp.roundToPx())
        }
        .graphicsLayer {
            val r = islandArtworkRect(progress(), pillIconLocalRect, cardIconLocalRect)
            val scale = (r.widthDp / cardIconLocalRect.widthDp).coerceAtLeast(0.01f)
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
        .clip(RoundedCornerShape(14.dp))
        .background(ink.copy(alpha = .12f)),
        contentAlignment = Alignment.Center) {
        if (artwork != null) Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else IslandGlyph(item, cardIconLocalRect.widthDp.dp * 0.4f, ink)
    }
}

/**
 * A miniature of the pill's own row (icon slot left empty — [MorphingMediaArtwork] draws over it
 * for media; a plain glyph for everything else — plus the compact label/equalizer), shown only
 * while the morph is still mostly a pill ([islandPillContentAlpha]'s 0-30% window): once the real
 * container has grown into the card, the real rail pill underneath is already fully hidden by this
 * one's opaque background, so without this the user would briefly see a blank rounded box.
 */
@Composable
private fun OverlayPillContent(item: IslandItem, now: Long, reduceMotion: Boolean, ink: Color) {
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            item.progress?.let { ProgressRing(it, ink, Modifier.size(26.dp)) }
            if (item.kind != IslandKind.MEDIA) IslandGlyph(item, 18.dp, ink)
        }
        if (item.kind == IslandKind.MEDIA) {
            EqualizerGlyph(item.media?.playing == true, reduceMotion, ink, Modifier.width(16.dp).height(14.dp))
            Text(item.title, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else compactIslandLabel(item, now)?.let { label ->
            if (item.chronometerBase != null) DigitRollText(label, ink, 11.sp, FontWeight.SemiBold, reduceMotion)
            else Text(label, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How long a bar's phase runs before repeating (matches [ISLAND_EQUALIZER_PERIOD_MS]). */
@Composable
private fun rememberEqualizerElapsed(playing: Boolean, reduceMotion: Boolean): State<Long> {
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, reduceMotion) {
        if (!playing || reduceMotion) return@LaunchedEffect
        var start = -1L
        while (true) withFrameMillis { frameTime ->
            if (start < 0L) start = frameTime
            elapsed.longValue = frameTime - start
        }
    }
    return elapsed
}

/**
 * The pill's "now playing" glyph (task spec item 3): [ISLAND_EQUALIZER_BAR_COUNT] bars, animated
 * out of phase while [playing] ([islandEqualizerBarLevel]), held at a static mid-height while
 * paused or under reduced motion.
 */
@Composable
private fun EqualizerGlyph(playing: Boolean, reduceMotion: Boolean, ink: Color, modifier: Modifier = Modifier) {
    val elapsedMs by rememberEqualizerElapsed(playing, reduceMotion)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(ISLAND_EQUALIZER_BAR_COUNT) { bar ->
            val level = if (reduceMotion) ISLAND_EQUALIZER_PAUSED_LEVEL else islandEqualizerBarLevel(bar, elapsedMs, playing)
            Box(Modifier.width(3.dp).fillMaxHeight(level).clip(RoundedCornerShape(1.dp)).background(ink))
        }
    }
}

/** A media pill's leading icon: the track's own artwork once known, the app icon otherwise ([IslandGlyph]'s own fallback). */
@Composable
private fun PillArtworkGlyph(item: IslandItem, size: Dp, ink: Color) {
    val artwork = remember(item.media?.artwork) { item.media?.artwork?.asImageBitmap() }
    if (artwork != null) Image(artwork, null, Modifier.size(size).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
    else IslandGlyph(item, size, ink)
}

/**
 * The Apple Music widget / Now Playing style layout (task spec item 2): the artwork column is
 * left as empty space here — [MorphingMediaArtwork] draws the actual shared-element artwork on
 * top, sized/positioned by the same morph — a title/artist column, a live progress bar (1 Hz
 * extrapolation via [extrapolatedPositionMs], the same clock `PlaybackState.lastPositionUpdateTime`
 * itself uses), and a centred transport row with an output-picker glyph at the far right when the
 * system resolves one. Title/artist fade in from 40% of the morph, the transport row from 55% with
 * an 8 dp rise — both computed once per frame inside `graphicsLayer` lambdas, not read in this
 * function's body.
 */
@Composable
private fun MediaCardContent(item: IslandItem, textColor: Color, progress: () -> Float) {
    val media = item.media ?: return
    val context = LocalContext.current
    val subColor = textColor.copy(alpha = .8f)
    val nowElapsed by produceState(SystemClock.elapsedRealtime(), item.key, media.playing) {
        while (media.playing) {
            value = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val livePositionMs = remember(media.positionMs, media.positionUpdatedAtMs, media.playbackSpeed, media.playing, media.durationMs, nowElapsed) {
        extrapolatedPositionMs(media.positionMs, media.positionUpdatedAtMs, nowElapsed, media.playbackSpeed, media.playing, media.durationMs)
    }
    val outputIntent = remember(context) { mediaOutputPickerIntent(context) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.size(96.dp)) // the shared artwork element draws here, over the whole card
            Column(Modifier.weight(1f).graphicsLayer { alpha = islandCardTextAlpha(progress()) },
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, color = textColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (item.text.isNotBlank()) Text(item.text, color = subColor, fontSize = 13.sp,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (media.durationMs > 0L) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator({ (livePositionMs / media.durationMs.toFloat()).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = textColor, trackColor = textColor.copy(alpha = .35f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(mediaTimeLabel(livePositionMs), color = subColor, fontSize = 11.sp, maxLines = 1, softWrap = false)
                        Text(mediaRemainingLabel(livePositionMs, media.durationMs), color = subColor, fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = islandControlsAlpha(progress())
                translationY = islandControlsRiseDp(progress()).dp.toPx()
            }) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaControlButton(Icons.Rounded.SkipPrevious, "Previous", textColor) { media.controller?.transportControls?.skipToPrevious() }
                MediaControlButton(if (media.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (media.playing) "Pause" else "Play", textColor, size = 48.dp, glyphSize = 26.dp) {
                    media.controller?.transportControls?.let { if (media.playing) it.pause() else it.play() }
                }
                MediaControlButton(Icons.Rounded.SkipNext, "Next", textColor) { media.controller?.transportControls?.skipToNext() }
            }
            if (outputIntent != null) MediaControlButton(Icons.Rounded.Cast, "Output", textColor,
                size = 36.dp, glyphSize = 18.dp, modifier = Modifier.align(Alignment.CenterEnd)) {
                runCatching { context.startActivity(outputIntent) }
            }
        }
    }
}

/** The system's media-output-switcher intent, tried in a defensive order and returned only once [android.content.pm.PackageManager] confirms something will actually handle it — the output glyph stays hidden otherwise (task spec item 2). */
private fun mediaOutputPickerIntent(context: Context): Intent? {
    val candidates = listOf(
        Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName),
        Intent(Settings.Panel.ACTION_VOLUME),
    )
    return candidates.firstOrNull { runCatching { it.resolveActivity(context.packageManager) != null }.getOrDefault(false) }
}

@Composable
private fun MediaControlButton(
    icon: ImageVector, label: String, tint: Color,
    size: Dp = 48.dp, glyphSize: Dp = 24.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.size(size).clip(RoundedCornerShape(size / 2))
        .combinedClickable(onClick = onClick, role = Role.Button)
        .testTag("island-${label.lowercase()}"), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(glyphSize))
    }
}

/**
 * Every other item kind (task spec item 3: timer, call, navigation, progress, workout, Now Bar):
 * icon left, primary/secondary text start-aligned filling the middle, up to two right-aligned
 * action chips. The digit roll (a running chronometer) still drives the primary line.
 */
@Composable
private fun GenericCardContent(item: IslandItem, now: Long, reduceMotion: Boolean, ink: Color, onAction: (IslandAction) -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            item.progress?.let { ProgressRing(it, ink, Modifier.size(32.dp)) }
            IslandGlyph(item, 22.dp, ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (item.chronometerBase != null) {
                DigitRollText(chronometerText(item.chronometerBase, now, item.countDown), ink, 17.sp, FontWeight.Bold, reduceMotion)
            } else if (item.title.isNotBlank()) {
                Text(item.title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (item.text.isNotBlank()) Text(item.text, color = ink.copy(alpha = .75f), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            when {
                item.progressIndeterminate -> LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp),
                    color = ink, trackColor = ink.copy(alpha = .25f))
                item.progress != null && item.chronometerBase == null -> LinearProgressIndicator({ item.progress },
                    Modifier.fillMaxWidth().height(3.dp), color = ink, trackColor = ink.copy(alpha = .25f))
            }
        }
        if (item.actions.isNotEmpty()) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.actions.take(2).forEach { action ->
                Text(action.title, color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = .18f))
                        .combinedClickable(onClick = { onAction(action) }, role = Role.Button)
                        .padding(vertical = 6.dp, horizontal = 10.dp).testTag("island-action"))
            }
        }
    }
}

/** How long one digit's roll takes (B30 item 3, "a small custom composable that slides digits, ~120 ms"). */
private const val ISLAND_DIGIT_ROLL_MS = 120

/**
 * Renders [text] one character at a time, each in its own [AnimatedContent] that slides the old
 * character up and out while the new one slides in from below (B30 item 3's "number roll"):
 * [digitRollDiff] decides, per character, whether it changed at all so a steady colon or an
 * unchanged digit never animates. Used for chronometer text, where only the digits that actually
 * ticked over should move.
 */
@Composable
private fun DigitRollText(text: String, color: Color, fontSize: TextUnit, fontWeight: FontWeight, reduceMotion: Boolean) {
    val previous = remember { PreviousText() }
    val diff = remember(text) {
        val chars = digitRollDiff(previous.value, text)
        previous.value = text
        chars
    }
    val rollSpec = if (reduceMotion) snap<IntOffset>()
        else spring(dampingRatio = ISLAND_SPRING_DAMPING, stiffness = Spring.StiffnessMedium)
    val fadeSpec = tween<Float>(if (reduceMotion) 0 else ISLAND_DIGIT_ROLL_MS)
    Row {
        diff.forEach { digit ->
            AnimatedContent(
                targetState = digit.new,
                transitionSpec = {
                    (slideInVertically(rollSpec) { full -> full } + fadeIn(fadeSpec)) togetherWith
                        (slideOutVertically(rollSpec) { full -> -full } + fadeOut(fadeSpec))
                },
                label = "island-digit-roll",
            ) { ch -> Text(ch.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false) }
        }
    }
}

/** Plain mutable holder for [DigitRollText]'s last-rendered text; not Compose state, so writing it never triggers recomposition. */
private class PreviousText { var value: String? = null }

/** How much of an item's accent shows through the glass; keeps white text legible on any chip colour. */
const val ISLAND_ACCENT_BLEND = .6f

/**
 * Pill / card fill: B29 "Barvy z tapety" bases it on the wallpaper's derived
 * [cz.pflanzer.foldduo.WallpaperPalette.islandFillArgb] (a fixed dark-theme tone while that
 * feature is off, `WallpaperPalette.Default`), then blends in the item's own accent (Samsung Now
 * Bar chip colour) when it has one.
 */
@Composable
private fun islandFill(item: IslandItem, alpha: Float): Color {
    val base = Color(LocalWallpaperPalette.current.islandFillArgb)
    val accent = item.accentColor ?: return base.copy(alpha = alpha)
    return lerp(base, Color(accent), ISLAND_ACCENT_BLEND).copy(alpha = alpha)
}

@Composable
private fun ProgressRing(progress: Float, ink: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = Stroke(width = size.width * .09f, cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(ink.copy(alpha = .25f), -90f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        drawArc(ink, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = stroke)
    }
}

/** The app's small notification icon tinted with [ink]; the app icon for media; a kind glyph as the fallback. */
@Composable
private fun IslandGlyph(item: IslandItem, size: Dp, ink: Color) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val small = remember(item.key, item.smallIcon, px) { item.smallIcon?.let { icon -> runCatching { icon.loadDrawable(context) }.getOrNull()?.toImageBitmap(px) } }
    val app = remember(item.key, item.appIcon, px) { item.appIcon?.toImageBitmap(px) }
    when {
        item.kind == IslandKind.MEDIA && app != null -> Image(app, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
        small != null -> Image(small, null, Modifier.size(size), colorFilter = ColorFilter.tint(ink))
        app != null -> Image(app, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
        else -> Icon(islandKindIcon(item.kind), null, tint = ink, modifier = Modifier.size(size))
    }
}

private fun islandKindIcon(kind: IslandKind): ImageVector = when (kind) {
    IslandKind.CALL -> Icons.Rounded.Call
    IslandKind.NAVIGATION -> Icons.Rounded.Navigation
    IslandKind.TIMER -> Icons.Rounded.Timer
    IslandKind.MEDIA, IslandKind.TRANSPORT -> Icons.Rounded.MusicNote
    IslandKind.PROGRESS -> Icons.Rounded.Downloading
    IslandKind.WORKOUT -> Icons.Rounded.FitnessCenter
    IslandKind.OTHER -> Icons.Rounded.Notifications
}

private fun islandKindLabel(kind: IslandKind): String = when (kind) {
    IslandKind.CALL -> "Call"
    IslandKind.NAVIGATION -> "Navigation"
    IslandKind.TIMER -> "Timer"
    IslandKind.MEDIA -> "Now playing"
    IslandKind.TRANSPORT -> "Media"
    IslandKind.PROGRESS -> "Progress"
    IslandKind.WORKOUT -> "Workout"
    IslandKind.OTHER -> "Live update"
}

private fun Drawable.toImageBitmap(px: Int): ImageBitmap? {
    if (px <= 0) return null
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/** Send a content / action intent from the foreground launcher; a cancelled intent is ignored. */
private fun launchIsland(context: Context, intent: PendingIntent?) {
    intent ?: return
    runCatching {
        // Android 14+ blocks background activity starts through another app's PendingIntent
        // unless the sender opts in; the launcher is in the foreground, so allow it.
        @Suppress("DEPRECATION")
        val mode = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            Build.VERSION.SDK_INT >= 34 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> null
        }
        if (mode != null) {
            val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
            intent.send(context, 0, null, null, null, null, options.toBundle())
        } else intent.send()
    }
}
