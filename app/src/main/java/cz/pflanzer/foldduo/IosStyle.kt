package cz.pflanzer.foldduo

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * iOS 17/18 type + shape language (2026-09-17 night, "iOS styl menu a režimu úprav"): Tom's
 * feedback was "v kontextu nad ikonami je nějakej divnej font, měl by být default, a celý ten
 * UX/UI by mělo být více iOS" (the popover font looks odd, should be the platform default, and
 * the whole UX should read more like iOS).
 *
 * Font finding: no `FontFamily.Serif`/`Monospace`/custom `Typography()` override existed anywhere
 * in this app's Compose code (grepped the whole module) — every `Text` here already rendered with
 * Material3's default `Typography()`, whose styles default to `FontFamily.Default` (the platform
 * sans, which One UI reskins system-wide). The *only* font declaration in the whole codebase was
 * `android:fontFamily="sans"` on `Theme.Duo`/`Theme.Duo.StandBy` in styles.xml — "sans" is not the
 * canonical generic family name (that's "sans-serif"); it is the one deliberate, and wrong, font
 * override in this project, so it is removed here (the theme now inherits its parent's default
 * text appearance, same platform default Compose's `FontFamily.Default` already resolves to).
 * On top of that, this file gives the surfaces Tom pointed at explicit iOS-scale text (17 sp
 * menu rows, 13 sp captions/messages) instead of Material's bodyMedium(14sp)/titleMedium(16sp),
 * which read a size or two off from iOS's own menu/alert type scale.
 */

/** iOS context-menu row label: 17 sp regular, platform default sans. */
internal val IosMenuRowStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 17.sp)

/** iOS section header / caption / dialog message: 13 sp, platform default sans. */
internal val IosSectionLabelStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp)

/** iOS alert/sheet title: 17 sp semibold, platform default sans. */
internal val IosTitleStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)

/** iOS system red (`UIColor.systemRed`), used for every destructive row/button below. */
internal val IosDestructive = Color(0xFFFF3B30)

/** iOS `UIColor.systemGray` — the "-" remove badge's circle. */
internal val IosGray = Color(0xFF8E8E93)

/** Context-menu card corner radius (iOS 17/18 `UIMenu`). */
internal val IosMenuCorner = 13.dp

/** Alert/sheet card corner radius. */
internal val IosAlertCorner = 14.dp

/** Alert card width (iOS `UIAlertController` on a compact width class). */
internal val IosAlertWidth = 270.dp

internal val IosMenuRowHeight = 44.dp
internal val IosHairline = 0.5.dp
internal val IosHairlineColor = Color.White.copy(alpha = .12f)

/** Open/close spring for the context menu and its lifted icon preview: 0.9→1 / 1→1.15,
 * `dampingRatio = 0.75`, medium stiffness so it settles in well under 300 ms. */
internal val IosMenuSpring = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
