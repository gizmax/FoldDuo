package cz.pflanzer.foldduo

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/*
 * Container treatment for third-party app widgets (AppWidgetHostView). Their RemoteViews cannot
 * be recoloured, so the appearance is applied around and over the view: the AndroidView renders
 * into this Compose layer, which clips it, paints a backing behind it, fades it, and draws a tint
 * overlay and a hairline on top. Every modifier here is draw-only, so touches, scrolling and
 * long-press still reach the provider's views unchanged. Pure selection logic: hostFrameSpec.
 * Glass takes the frosted backing ([frostedGlass], FrostedBackdrop.kt) in place of the spec's
 * flat backing and hairline, which stay its fallback while no backdrop exists.
 *
 * [sampledDark], when non-null, is HostWidgetSkin's read of the widget's own (now-stripped)
 * background before it painted its opaque card: true picks the dark Glass variant so the
 * provider's pale text keeps enough contrast against the frost. Null (no sample yet, or the
 * widget was never framed) falls back to plain bright glass, matching earlier behaviour.
 */
@Composable
internal fun HostWidgetFrame(appearance: WidgetAppearance, tint: Int?, sampledDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Wallpaper (B29) ignores the placement's own tint and always follows the live accent.
    val effectiveTint = if (appearance == WidgetAppearance.Wallpaper) LocalWallpaperPalette.current.accent else tint
    val spec = remember(appearance, effectiveTint, dark, sampledDark) {
        hostFrameSpec(appearance, effectiveTint, dark, darkBackground = sampledDark == true)
    }
    val frost = if (appearance == WidgetAppearance.Glass && spec.framed) Modifier.frostedGlass(spec.cornerDp.dp,
        fallback = spec.backing?.let { Color(it) } ?: Color.Transparent, border = spec.border?.let { Color(it) },
        veilColor = if (spec.darkGlass) Color.Black else Color.White) else null
    Box(Modifier.fillMaxSize().hostWidgetFrame(spec, frost)) { content() }
}

/** The frame of [spec]; [frost], when given, draws the backing and the hairline in place of the spec's flat ones. */
internal fun Modifier.hostWidgetFrame(spec: HostFrameSpec, frost: Modifier? = null): Modifier {
    if (!spec.framed) return this
    val shape = RoundedCornerShape(spec.cornerDp.dp)
    val backing = spec.backing?.let { Color(it) }
    val overlay = spec.overlay?.let { Color(it) }
    val overlayBlend = if (spec.blend == HostFrameBlend.Multiply) BlendMode.Multiply else BlendMode.Screen
    return this
        // Offscreen layer: the clip holds the provider's square corners and the overlay's blend
        // mode composites against the widget's pixels only, not the wallpaper under the page.
        .graphicsLayer { clip = true; this.shape = shape; compositingStrategy = CompositingStrategy.Offscreen }
        .then(frost ?: if (backing != null) Modifier.drawBehind { drawRect(backing) } else Modifier)
        .then(if (overlay != null) Modifier.drawWithContent { drawContent(); drawRect(overlay, blendMode = overlayBlend) } else Modifier)
        .then(if (frost == null) spec.border?.let { Modifier.border(1.dp, Color(it), shape) } ?: Modifier else Modifier)
        .then(if (spec.contentAlpha < 1f) Modifier.graphicsLayer { alpha = spec.contentAlpha } else Modifier)
}
