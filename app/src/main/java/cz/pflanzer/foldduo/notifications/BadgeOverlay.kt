package cz.pflanzer.foldduo.notifications

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.pflanzer.foldduo.MotionPrefs

/*
 * iOS-style unread badges (redesign after Tom's 2026-09-17 feedback): a single modifier every
 * icon surface appends to its existing chain — Home tiles, dock, folder tiles/children, App
 * Library (mini-cluster and full grid) and Spotlight rows — rather than each restructuring its Box
 * to insert an overlay child. Counting lives in Badges.kt so it stays testable on the JVM; this
 * file is the drawn half, styled like a native iOS badge: red circle/pill, white bold count,
 * top-right, overlapping the icon by about a quarter of the badge's own size.
 */

/** iOS red, #FF3B30. */
private const val BADGE_RED = 0xFFFF3B30.toInt()
/** iOS: a 60 pt icon carries a 20 pt badge (one third); smaller icons scale down with it. */
private val BADGE_MIN_SIZE = 20.dp
private const val BADGE_ICON_FRACTION = 1f / 3f
/** iOS pill: ~6 pt of air left and right of a multi-digit count. */
private val BADGE_H_PADDING = 6.dp
/** iOS: SF Semibold 13 pt on a 20 pt badge. */
private val BADGE_TEXT_SIZE = 13.sp
private const val BADGE_POP_SCALE = 1.3f
/** iOS: the badge sticks out past the icon's top-right corner by about a third of its own height. */
private const val BADGE_OUTSIDE_FRACTION = .35f
private val BadgePopSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
private val BadgeFadeSpring = spring<Float>(stiffness = Spring.StiffnessMedium)

/**
 * Draws an iOS-style badge in the top-right corner of whatever this modifier is attached to:
 * [count] 0 (or less) fades any existing badge out instead of snapping it away, a changed count
 * pops the badge (scale [BADGE_POP_SCALE] -> 1) with a spring, and [badgeLabel] caps the text at
 * "99+". Reduced motion ([MotionPrefs]) skips both animations — the badge just appears/updates/
 * disappears. A single call at the end of an icon's existing modifier chain is all a call site
 * needs; nothing here restructures the Box it is drawn into.
 *
 * [pulseAtMs] (IDEAS.md B50 "Tapeta dýchá s oznámením", default: the latest
 * [cz.pflanzer.foldduo.notifications.NotificationPulse]) also pops the badge when it changes, even
 * if [count] itself did not — one notification replacing another can leave the count unchanged,
 * and this is the badge's own half of "the rail island and the icon badge of the same app pop in
 * the same rhythm" the task asks for. [Pulse] deliberately carries no package name, so this is not
 * scoped to *this* badge's app; in the common case (one notification, one pulse) it happens to be,
 * but a badge whose own count did not change can still pop from a different app's pulse.
 */
@Composable
fun Modifier.notificationBadge(
    count: Int,
    minSize: Dp = BADGE_MIN_SIZE,
    pulseAtMs: Long? = NotificationPulse.pulse.collectAsState().value?.atMs,
): Modifier {
    val reduceMotion = MotionPrefs.enabled.value
    var shownCount by remember { mutableIntStateOf(count.coerceAtLeast(0)) }
    var shownPulseAtMs by remember { mutableStateOf<Long?>(null) }
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(if (count > 0) 1f else 0f) }
    LaunchedEffect(count, pulseAtMs, reduceMotion) {
        if (count > 0) {
            val freshPulse = pulseAtMs != null && pulseAtMs != shownPulseAtMs
            if (count != shownCount || freshPulse) {
                shownCount = count
                shownPulseAtMs = pulseAtMs
                if (reduceMotion) scale.snapTo(1f)
                else { scale.snapTo(BADGE_POP_SCALE); scale.animateTo(1f, BadgePopSpring) }
            }
            if (reduceMotion) alpha.snapTo(1f) else alpha.animateTo(1f, BadgeFadeSpring)
        } else {
            if (reduceMotion) alpha.snapTo(0f) else alpha.animateTo(0f, BadgeFadeSpring)
        }
    }
    val density = LocalDensity.current
    val minSizePx = with(density) { minSize.toPx() }
    val hPaddingPx = with(density) { BADGE_H_PADDING.toPx() }
    val textSizePx = with(density) { BADGE_TEXT_SIZE.toPx() }
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true; textAlign = Paint.Align.CENTER; color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 600, false)
        }
    }
    val backgroundPaint = remember { Paint().apply { isAntiAlias = true } }
    val textBounds = remember { android.graphics.Rect() }
    return this.drawWithContent {
        drawContent()
        val a = alpha.value
        if (a <= 0f) return@drawWithContent
        val label = badgeLabel(shownCount)
        // Scale with the icon like iOS (20 pt on a 60 pt icon), never below the minimum.
        val badgeHeight = maxOf(minSizePx, size.width * BADGE_ICON_FRACTION).coerceAtMost(minSizePx * 1.25f)
        textPaint.textSize = textSizePx * (badgeHeight / minSizePx)
        val textWidth = textPaint.measureText(label)
        val badgeWidth = if (label.length <= 1) badgeHeight else maxOf(badgeHeight, textWidth + hPaddingPx * 2f)
        // Right/top edges stick out past the corner by BADGE_OUTSIDE_FRACTION of the badge height.
        val outside = badgeHeight * BADGE_OUTSIDE_FRACTION
        val cx = size.width + outside - badgeWidth / 2f
        val cy = -outside + badgeHeight / 2f
        val s = scale.value
        drawContext.canvas.nativeCanvas.apply {
            val saved = save()
            translate(cx, cy)
            if (s != 1f) scale(s, s)
            backgroundPaint.color = BADGE_RED
            backgroundPaint.alpha = (a * 255).toInt()
            drawRoundRect(RectF(-badgeWidth / 2f, -badgeHeight / 2f, badgeWidth / 2f, badgeHeight / 2f),
                badgeHeight / 2f, badgeHeight / 2f, backgroundPaint)
            textPaint.alpha = (a * 255).toInt()
            // Centre the glyphs' actual ink bounds (digits = cap height) on the pill's centre.
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            drawText(label, 0f, -(textBounds.top + textBounds.bottom) / 2f, textPaint)
            restoreToCount(saved)
        }
    }
}
