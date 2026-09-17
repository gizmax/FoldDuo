package cz.pflanzer.foldduo

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics

/**
 * Which physical device a [DeviceProfile] describes. Only ever used to pick a *default* for
 * something [DeviceProfile] itself cannot measure (pose/HingeAngleEstimator's magnetometer
 * calibration table, continuum/FoldConfig's hinge-angle constants) — every geometric field on
 * [DeviceProfile] is measured, never looked up from this tag.
 */
enum class DeviceModel {
    FOLD8, FOLD7, GENERIC;

    companion object {
        /** `Build.MODEL` (e.g. "SM-F971B") -> tag. An unrecognised model is [GENERIC]: geometry
         * still works (it is computed from the real displays), only the per-model *extras* that
         * have no way to be measured at runtime (a magnetometer table, tuned hinge constants) go
         * without a shipped default. */
        fun forBuildModel(model: String): DeviceModel = when {
            model.startsWith("SM-F971") -> FOLD8 // Galaxy Z Fold 8
            model.startsWith("SM-F966") -> FOLD7 // Galaxy Z Fold 7
            else -> GENERIC
        }
    }
}

/**
 * A display cutout's bounding box, in dp, relative to its own panel's top-left. Every field is
 * 0 for "no cutout"; only a top-anchored camera cutout (the shape both the Fold 8 cover and,
 * per spec, the Fold 7 cover and inner have) is modelled — [heightDp]/[widthDp] are the total
 * inset it costs a panel on that axis, not a claim about where exactly it sits.
 */
data class CutoutDp(
    val leftDp: Float = 0f,
    val topDp: Float = 0f,
    val rightDp: Float = 0f,
    val bottomDp: Float = 0f,
) {
    val heightDp: Float get() = topDp + bottomDp
    val widthDp: Float get() = leftDp + rightDp

    companion object {
        val NONE = CutoutDp()
    }
}

/**
 * One physical panel (cover or inner), measured — never a per-model constant. [widthPx]/[heightPx]
 * are a display mode's physical size in its own natural orientation: the cover's is portrait on
 * both the Fold 8 and the Fold 7 spec (narrow, tall), the inner's is landscape on both (wider
 * than tall) — `pose/Panels.kt` relies on the same convention.
 */
data class PanelSpec(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val cutout: CutoutDp = CutoutDp.NONE,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "panel size must be positive, got ${widthPx}x$heightPx" }
        require(density > 0f) { "density must be positive, got $density" }
    }

    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density
    val areaPx: Long get() = widthPx.toLong() * heightPx.toLong()

    /** Aspect ratio in dp (same as px, density cancels out): >1 = landscape, <1 = portrait. */
    val aspect: Float get() = widthDp / heightDp

    /** [widthDp]/[heightDp] with the cutout's inset subtracted — what content can actually use. */
    val usableWidthDp: Float get() = widthDp - cutout.widthDp
    val usableHeightDp: Float get() = heightDp - cutout.heightDp
}

/**
 * Everything the launcher used to assume about "the Fold 8" (CONTEXT.md, PLAN.md "fact 1"),
 * generalized to any book-style foldable with two physical panels behind one logical display:
 * cover/inner identified by area (never by a hardcoded size — [cover] must be the panel with
 * the smaller area), the hinge seam from `FoldingFeature` when a caller has one (or its
 * fallback, [inner]'s own centre — `FoldGeometry.kt`'s `FoldSeam` already documents this
 * fallback; [DeviceProfile] just gives it a home) and cutouts as measured rects, never a
 * constant.
 *
 * [FOLD8] reproduces every geometric constant this launcher shipped with before this class
 * existed (see `DeviceProfileTest`, `LayoutModelTest`); [fold7] is a synthetic profile from the
 * 2026-09-17 Galaxy Z Fold 7 spec (no physical unit to measure, so it is not a shipped default —
 * callers ask for it explicitly, tests only); [detect] builds the live profile of whatever
 * device is actually running from `DisplayManager` and `Build.MODEL`.
 */
data class DeviceProfile(
    val model: DeviceModel,
    val cover: PanelSpec,
    val inner: PanelSpec,
    /**
     * Hinge seam along the inner panel's width, dp from its left edge. Pass the live
     * `FoldingFeature.bounds` centre when a caller has one; the default is [inner]'s own centre,
     * the same fallback `FoldGeometry.kt` already uses (on the Fold 8 the two coincide: the
     * measured seam sits at x=1224px, exactly half of the inner's 2448px width).
     */
    val seamXDp: Float = inner.widthDp / 2f,
    val seamGutterDp: Float = FOLD_GUTTER_DP,
) {
    init {
        require(cover.areaPx <= inner.areaPx) {
            "cover must be the smaller-area panel (${cover.areaPx}px vs inner ${inner.areaPx}px) " +
                "— panel identity is never a hardcoded size"
        }
    }

    /** The pane the cover shows 1:1 once the device is unfolded ("pane identity", PLAN.md fact 1
     * — how closely this holds is a property of the hardware, not this class; on the Fold 8 the
     * cover and the inner's half are within a dp of each other, on the Fold 7's 21:9 cover they
     * are not, and callers that need an exact match should not assume it). */
    val coverPaneWidthDp: Float get() = cover.usableWidthDp
    val coverPaneHeightDp: Float get() = cover.usableHeightDp

    /** Raw left/right split of the open inner panel at [seamXDp] — no gutter, i.e. "this pane as
     * its own standalone window" (what a widget host measures on the panel it is actually bound
     * to). Use [leftPaneContentWidthDp]/[rightPaneContentWidthDp] when both panes share one
     * window and must clear the crease. */
    val innerLeftPaneWidthDp: Float get() = seamXDp
    val innerRightPaneWidthDp: Float get() = inner.widthDp - seamXDp
    val innerPaneHeightDp: Float get() = inner.usableHeightDp

    /** Pane width net of the reserved folding region on both sides of the seam (`FoldGeometry.kt`
     * `FoldSeam`), for the case where Today and Home share one expanded window. */
    val leftPaneContentWidthDp: Float get() = (seamXDp - seamGutterDp).coerceAtLeast(0f)
    val rightPaneContentWidthDp: Float get() = (inner.widthDp - seamXDp - seamGutterDp).coerceAtLeast(0f)

    companion object {
        /**
         * Reproduces the launcher's original Fold 8 numbers (CONTEXT.md, ADB measurement
         * 2026-08-13): cover 1248x1972 px with a 104 px top camera cutout, inner 2448x1848 px,
         * both at the device's density override of 360 (2.25, not the physical 420) — the same
         * figure `LayoutModel.kt`'s `COVER_PANE_*_DP`/`INNER_PANE_*_DP`/`COVER_CUTOUT_DP` were
         * hand-computed from. Those constants stay as their own literals (some are rounded to a
         * whole dp, this class is not) — `DeviceProfileTest` checks the two agree within half a
         * dp, not bit for bit.
         */
        val FOLD8: DeviceProfile = DeviceProfile(
            model = DeviceModel.FOLD8,
            cover = PanelSpec(widthPx = 1248, heightPx = 1972, density = FOLD8_DENSITY, cutout = CutoutDp(topDp = 104f / FOLD8_DENSITY)),
            inner = PanelSpec(widthPx = 2448, heightPx = 1848, density = FOLD8_DENSITY),
        )

        /** The Fold 8's density override (360dpi / 160 = 2.25), not its physical 420dpi panel density. */
        const val FOLD8_DENSITY = 2.25f

        /**
         * Synthetic Galaxy Z Fold 7 (SM-F966B) profile for tests — no physical unit exists to
         * measure, so this is never a shipped default, only a fixture. Numbers are the request's
         * 2026-09-17 spec: cover 6.5" 2520x1080 px (21:9, portrait: 1080 wide x 2520 tall), inner
         * 8.0" 2184x1968 px (near-square, landscape-native: 2184 wide x 1968 tall — same
         * convention as the Fold 8's inner). The real device's density override is unknown, so
         * [density] is a parameter; [DeviceProfileTest] exercises both ends of the stated
         * "~2.625-3.0" range.
         */
        fun fold7(density: Float): DeviceProfile = DeviceProfile(
            model = DeviceModel.FOLD7,
            cover = PanelSpec(widthPx = 1080, heightPx = 2520, density = density),
            inner = PanelSpec(widthPx = 2184, heightPx = 1968, density = density),
        )

        /** A device this launcher has no model-specific defaults for: geometry from measurement
         * only (used by [detect] as the shape of its result, never as a canned size). */
        fun generic(cover: PanelSpec, inner: PanelSpec): DeviceProfile =
            DeviceProfile(model = DeviceModel.GENERIC, cover = cover, inner = inner)

        /**
         * Live profile of whatever device is actually running: every physical display's mode
         * size and density from [DisplayManager] (never a per-model constant), the smaller-area
         * one is [cover] ([PanelSpec]'s own `init` re-asserts this), [Build.MODEL] only tags
         * which [DeviceModel] it is. Falls back to [FOLD8] when fewer than two distinct physical
         * panels are visible (a single-display device, an emulator, or a permission issue) —
         * that is the one case this class cannot measure its way out of.
         *
         * Cutouts are not read here: they need a live `Window`/`View` (`WindowInsets.displayCutout`),
         * which a bare [Context] does not have. Compose call sites already read the cutout live
         * (`LauncherScreen.kt`'s `cutoutBottomDp`); this profile's [PanelSpec.cutout] is [CutoutDp.NONE]
         * until a caller with a live inset refines it.
         */
        fun detect(context: Context): DeviceProfile {
            val displayManager = context.getSystemService(DisplayManager::class.java) ?: return FOLD8
            val fallbackDensity = context.resources.displayMetrics.density
            val specs = displayManager.displays.orEmpty()
                .mapNotNull { display ->
                    val mode = runCatching { display.mode }.getOrNull() ?: return@mapNotNull null
                    if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return@mapNotNull null
                    val density = runCatching {
                        val metrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        display.getRealMetrics(metrics)
                        metrics.density.takeIf { it > 0f }
                    }.getOrNull() ?: fallbackDensity
                    PanelSpec(mode.physicalWidth, mode.physicalHeight, density)
                }
                .distinctBy { it.widthPx to it.heightPx }
            if (specs.size < 2) return FOLD8
            val sorted = specs.sortedBy { it.areaPx }
            return generic(cover = sorted.first(), inner = sorted.last())
                .copy(model = DeviceModel.forBuildModel(Build.MODEL))
        }
    }
}

/**
 * Process-wide holder for the live [DeviceProfile], the same pattern `LauncherBackgroundCache`
 * already uses for the process-wide background bitmap: a `DrawScope` extension (`drawDunes`,
 * `LauncherBackgroundCache.cropFor`) has no `Context` to call [DeviceProfile.detect] itself, so
 * an entry point with one ([MainActivity], `DuneWallpaperService`'s engine) sets this once and
 * everything downstream reads it. Defaults to [DeviceProfile.FOLD8] so a caller that runs before
 * [initFrom] (or a JVM unit test) gets today's behaviour, not a crash.
 */
object DeviceProfileHolder {
    @Volatile
    var current: DeviceProfile = DeviceProfile.FOLD8
        private set

    /** Idempotent-ish: cheap to call from every entry point's `onCreate`, only replaces [current]
     * when detection actually found two distinct panels (never regresses to a mid-swap single-display read). */
    fun initFrom(context: Context) {
        val detected = DeviceProfile.detect(context)
        current = detected
    }
}
