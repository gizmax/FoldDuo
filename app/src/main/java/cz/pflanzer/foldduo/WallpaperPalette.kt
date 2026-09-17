package cz.pflanzer.foldduo

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.Panels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * B29 "Barvy z tapety": a palette derived from the wallpaper, blended into the Material colour
 * scheme (DuoTheme, LauncherScreen.kt), FrostedBackdrop's veil wash, the status rail / rail
 * island ink (per pane, since the cover and inner panel show different crops of the same picture
 * — pane identity, WallpaperFraming.kt) and a new "Wallpaper" widget colour variant
 * (BuiltinWidgets.kt). Colours are ARGB Int throughout, matching relativeLuminance/
 * dominantColorArgb/isDarkHostBackground's existing convention, so [WallpaperPalette] itself and
 * [paneIsDark] have no Android imports and are covered by plain JVM unit tests; everything below
 * the "Android adapter" line talks to WallpaperManager/Bitmap and is exercised only on a device.
 */

/**
 * Everything the launcher paints from the wallpaper's colour, all ARGB Int. B43: `@Immutable`
 * (every field is an Int/Boolean value, genuinely never mutated after construction) so Compose
 * can skip recomposing a composable whose only changed parameter is an *equal* new instance of
 * this — it crosses many composable boundaries via [LocalWallpaperPalette] (StatusRail,
 * RailIsland, FrostedBackdrop's veil, HostWidgetFrame/AppleWidgets' "Wallpaper" tint) including
 * ones read during the morph.
 */
@Immutable
data class WallpaperPalette(
    val accent: Int,
    /** True when the wallpaper itself reads dark: the default veil/ink/fill lean light then. */
    val dark: Boolean,
    /** [Modifier.frostedGlass]'s `veilColor`: a white wash on a dark wallpaper, black on a bright one. */
    val veilArgb: Int,
    /** Ink for a card painted straight on the wallpaper (StatusRail/RailIsland's non-pane-aware fallback). */
    val railInkArgb: Int,
    /** RailIsland pill/card fill: the accent tinted toward a neutral base so any two wallpapers stay distinct. */
    val islandFillArgb: Int,
) {
    companion object {
        /** Today's fixed look (dark, white ink/veil) — used while this feature is off or before anything is sampled. */
        val Default: WallpaperPalette = of(primary = null, supportsDarkText = false, supportsDarkTheme = true)

        /** Fallback accent when nothing at all can be sampled: the app's own dark-theme primary (LauncherScreen.kt's `DuoTheme`). */
        const val FALLBACK_ACCENT = 0xFF9BC5D7.toInt()

        internal const val RAIL_INK_LIGHT = 0xFFFFFFFF.toInt()
        internal const val RAIL_INK_DARK = 0xFF10151A.toInt()
        private const val VEIL_WHITE = 0xFFFFFFFF.toInt()
        private const val VEIL_BLACK = 0xFF000000.toInt()
        private const val NEUTRAL_DARK = 0xFF17272E.toInt()
        private const val NEUTRAL_LIGHT = 0xFFF4F7F8.toInt()
        private const val ISLAND_FILL_MIX = 0.32f

        /**
         * [primary] is `WallpaperColors.primaryColor`'s ARGB (the system's own dominant-colour
         * read), null when the current wallpaper reports none (a provider that never populated
         * it); [fallbackArgb] is then a bitmap-sampled dominant colour instead ([sampleBitmapAccent]
         * over the cached launcher background / live-wallpaper photo). [supportsDarkText] and
         * [supportsDarkTheme] mirror `WallpaperColors.colorHints`' two bits: a "dark text" hint
         * means the wallpaper is bright enough for it, a "dark theme" hint means it is dark enough
         * to suit one. When neither bit is set (an old provider, or a plain-colour wallpaper).
         * luminance alone decides.
         */
        fun of(primary: Int?, supportsDarkText: Boolean, supportsDarkTheme: Boolean, fallbackArgb: Int? = null): WallpaperPalette {
            val accent = primary ?: fallbackArgb ?: FALLBACK_ACCENT
            val dark = when {
                supportsDarkText && !supportsDarkTheme -> false
                supportsDarkTheme && !supportsDarkText -> true
                else -> relativeLuminance(accent) <= .5
            }
            return WallpaperPalette(
                accent = accent,
                dark = dark,
                veilArgb = if (dark) VEIL_WHITE else VEIL_BLACK,
                railInkArgb = if (dark) RAIL_INK_LIGHT else RAIL_INK_DARK,
                islandFillArgb = mixArgb(accent, if (dark) NEUTRAL_DARK else NEUTRAL_LIGHT, ISLAND_FILL_MIX),
            )
        }

        /** Linear mix of two opaque ARGB colours at [t] (0 = [a], 1 = [b]). */
        internal fun mixArgb(a: Int, b: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

/**
 * True when [panel]'s own visible crop should take dark ink/veil: [cropAverageArgb] is the mean
 * colour of that pane's crop ([panePhotoAverageArgb], pane identity — the cover shows the right
 * half of the inner crop, so its average can differ from the whole picture's), null without a
 * photo (Duo dunes) or a sampling failure, in which case [fallback] (the global
 * [WallpaperPalette.dark]) decides instead.
 */
fun paneIsDark(cropAverageArgb: Int?, fallback: Boolean): Boolean =
    cropAverageArgb?.let { relativeLuminance(it) <= .5 } ?: fallback

// --- Android adapter --------------------------------------------------------------------

private const val WALLPAPER_PALETTE_PREFS = "appearance"
private const val WALLPAPER_PALETTE_KEY = "colorsFromWallpaper"

/** "Colours from wallpaper" (Appearance settings, last row): on by default, same `appearance` prefs file as the other toggles. */
internal fun colorsFromWallpaperEnabled(context: Context): Boolean =
    context.getSharedPreferences(WALLPAPER_PALETTE_PREFS, Context.MODE_PRIVATE).getBoolean(WALLPAPER_PALETTE_KEY, true)

internal fun setColorsFromWallpaperEnabled(context: Context, value: Boolean) {
    context.getSharedPreferences(WALLPAPER_PALETTE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(WALLPAPER_PALETTE_KEY, value).apply()
}

/**
 * Mean colour of [bitmap] over [src] (a source-pixel rect), scaled straight into a small grid via
 * a canvas draw (no full-size intermediate copy) and reduced with [dominantColorArgb]'s
 * saturation-weighted mean. Null for an empty rect or a graphics-stack refusal.
 */
private fun sampleAverageArgb(bitmap: Bitmap, src: android.graphics.Rect): Int? {
    if (bitmap.isRecycled || src.width() <= 0 || src.height() <= 0) return null
    return runCatching {
        val size = 12
        val sampled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(sampled).drawBitmap(bitmap, src, android.graphics.Rect(0, 0, size, size), null)
        val pixels = IntArray(size * size)
        sampled.getPixels(pixels, 0, size, 0, 0, size, size)
        sampled.recycle()
        dominantColorArgb(pixels)
    }.getOrNull()
}

/** Dominant colour of the whole [bitmap]; null for a recycled/empty bitmap or a sampling failure. */
internal fun sampleBitmapAccent(bitmap: Bitmap): Int? =
    if (bitmap.width <= 0 || bitmap.height <= 0) null
    else sampleAverageArgb(bitmap, android.graphics.Rect(0, 0, bitmap.width, bitmap.height))

/**
 * Average colour of the crop [panel] shows from [photo] (pane-identity framing: the cover shows
 * the right half of the inner crop — [LauncherBackgroundCache.cropFor]/[WallpaperFraming]). Null
 * without a photo.
 */
internal fun panePhotoAverageArgb(photo: Bitmap?, panel: Panel): Int? {
    if (photo == null || photo.isRecycled || photo.width <= 0 || photo.height <= 0) return null
    val (logicalW, logicalH) = if (panel == Panel.Cover) Panels.COVER_SHORT to Panels.COVER_LONG
        else Panels.INNER_LONG to Panels.INNER_SHORT
    val crop = LauncherBackgroundCache.cropFor(photo.width, photo.height, logicalW, logicalH, panel)
    val left = crop.left.roundToInt().coerceIn(0, photo.width - 1)
    val top = crop.top.roundToInt().coerceIn(0, photo.height - 1)
    val right = (crop.left + crop.width).roundToInt().coerceIn(left + 1, photo.width)
    val bottom = (crop.top + crop.height).roundToInt().coerceIn(top + 1, photo.height)
    return sampleAverageArgb(photo, android.graphics.Rect(left, top, right, bottom))
}

/**
 * [WallpaperPalette] from the system's [colors] (`WallpaperManager.getWallpaperColors`), falling
 * back to sampling [fallbackBitmap] (the cached launcher background photo) when the system
 * reports no colours at all — a plain-colour wallpaper, or a provider that predates API 27.
 */
internal fun wallpaperPaletteFrom(colors: android.app.WallpaperColors?, fallbackBitmap: Bitmap?): WallpaperPalette {
    val hints = colors?.colorHints ?: 0
    val supportsDarkText = hints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    val supportsDarkTheme = hints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_THEME != 0
    val primary = colors?.primaryColor?.toArgb()
    val fallback = if (primary == null) fallbackBitmap?.let(::sampleBitmapAccent) else null
    return WallpaperPalette.of(primary, supportsDarkText, supportsDarkTheme, fallback)
}

/** [WallpaperPalette] while the composition lives; [MainActivity] provides it from [rememberWallpaperPalette]. */
val LocalWallpaperPalette = compositionLocalOf { WallpaperPalette.Default }

/**
 * Live [WallpaperPalette]: reads `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` once, listens
 * for `WallpaperManager.OnColorsChangedListener` while composed, and re-reads on every resume (a
 * round trip through the system wallpaper chooser can change it while this activity is stopped).
 * The fixed [WallpaperPalette.Default] while "Colours from wallpaper" is off.
 *
 * B43: [refresh] used to compute this synchronously on the caller's thread — for the very first
 * call, that is [LifecycleResumeEffect]'s effect on the very first composition, i.e. it used to
 * make MainActivity's first frame wait on a `WallpaperManager` Binder round trip plus a wallpaper
 * bitmap sample. It now hands the actual work to `Dispatchers.Default` and only writes the result
 * (a single `palette = next`) back on the composition's own dispatcher, so the first frame goes
 * out without waiting for it; the palette itself still lands a moment later, same as the
 * `OnColorsChangedListener` case always worked.
 */
@Composable
fun rememberWallpaperPalette(): WallpaperPalette {
    val context = LocalContext.current.applicationContext
    var palette by remember { mutableStateOf(WallpaperPalette.Default) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        scope.launch {
            val next = withContext(Dispatchers.Default) {
                if (!colorsFromWallpaperEnabled(context)) WallpaperPalette.Default else {
                    val manager = context.getSystemService(WallpaperManager::class.java)
                    val colors = runCatching { manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
                    wallpaperPaletteFrom(colors, cachedLauncherBackground(context))
                }
            }
            palette = next
        }
    }
    val listener = remember {
        WallpaperManager.OnColorsChangedListener { _, which -> if (which and WallpaperManager.FLAG_SYSTEM != 0) refresh() }
    }
    DisposableEffect(context) {
        val manager = context.getSystemService(WallpaperManager::class.java)
        runCatching { manager?.addOnColorsChangedListener(listener, android.os.Handler(android.os.Looper.getMainLooper())) }
        onDispose { runCatching { manager?.removeOnColorsChangedListener(listener) } }
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    return palette
}

/**
 * Ink for a card painted straight on [panel]'s wallpaper (StatusRail, RailIsland): white on a
 * dark crop, near-black on a bright one ([paneIsDark]), recomputed whenever the launcher
 * background changes ([LauncherBackgroundCache.revision]). Falls back to
 * [WallpaperPalette.dark] without a photo (Duo dunes), and to the fixed white ink of
 * [WallpaperPalette.Default] while "Colours from wallpaper" is off.
 */
@Composable
fun paneInkColor(panel: Panel = currentPanel()): Color {
    val context = LocalContext.current.applicationContext
    val palette = LocalWallpaperPalette.current
    val enabled = remember(context, palette) { colorsFromWallpaperEnabled(context) }
    val revision = LauncherBackgroundCache.revision.intValue
    val dark = remember(context, palette, panel, revision, enabled) {
        if (!enabled) true else paneIsDark(panePhotoAverageArgb(cachedLauncherBackground(context), panel), palette.dark)
    }
    return Color(if (dark) WallpaperPalette.RAIL_INK_LIGHT else WallpaperPalette.RAIL_INK_DARK)
}
