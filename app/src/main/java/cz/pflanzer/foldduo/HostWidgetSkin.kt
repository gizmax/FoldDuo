package cz.pflanzer.foldduo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.geometry.Rect
import java.util.WeakHashMap

/*
 * Third-party host widgets (Samsung Weather, Calendar, Routines, Health, ChatGPT, …) paint their
 * own opaque rounded card over their whole RemoteViews tree, so HostWidgetFrame's Glass/colour
 * backing never shows: it sits behind or around the AppWidgetHostView, and the provider's card
 * hides it completely (the 2026-09-15 "widget looks the same after Glass" report). The fix is to
 * find that card view and null its background so the frame underneath becomes visible, restoring
 * it for Auto. Two layers:
 *  - Pure logic ([SkinNode], [findHostSkinCandidates], [isDarkHostBackground], [SkinTracker]):
 *    no android.view import, unit-tested in app/src/test without Robolectric.
 *  - The Android adapter ([HostWidgetSkin]) that walks a real View tree and is driven by
 *    ZeroPaddingWidgetHostView (subclassed AppWidgetHostView) after every RemoteViews update,
 *    hierarchy change and layout pass.
 */

/** Coverage fraction (of the host view's own area) above which a background-owning view counts as its card. */
const val HOST_SKIN_COVERAGE_THRESHOLD = 0.85f

/**
 * Pure description of one node of a host widget's view tree for skin logic. [bounds] is in the
 * host view's own coordinate space (not relative to the node's parent). [hasBackground] is true
 * when the view carries a background Drawable or a backgroundTintList — any Drawable subtype
 * counts (ColorDrawable, GradientDrawable, RippleDrawable, InsetDrawable, LayerDrawable, a
 * NinePatch/Bitmap, StateListDrawable, Samsung's own shape drawables). [isImageContent] marks an
 * ImageView whose *picture* (its `drawable`/`src`, never its `background`) is the content the
 * skin must never touch, regardless of coverage.
 */
data class SkinNode(
    val bounds: Rect,
    val hasBackground: Boolean,
    val isImageContent: Boolean = false,
    val children: List<SkinNode> = emptyList(),
)

/**
 * Background-owning nodes of [root] covering at least [HOST_SKIN_COVERAGE_THRESHOLD] of
 * [hostArea] — the views a real widget paints its opaque card on, root child included. Walks the
 * whole tree (a card can sit on the root, its first child, or a couple of levels down) rather
 * than stopping at the first match, but a node's own small children never qualify on their own
 * once they no longer cover enough of the host, so a plain full-bleed card yields only the one
 * (root or near-root) node that actually holds it. Image content is skipped outright. Order is
 * outer to inner, so a caller stripping backgrounds strips the card before anything nested in it.
 */
fun findHostSkinCandidates(root: SkinNode, hostArea: Float): List<SkinNode> {
    if (hostArea <= 0f) return emptyList()
    val result = mutableListOf<SkinNode>()
    fun walk(node: SkinNode) {
        if (!node.isImageContent && node.hasBackground) {
            val coverage = (node.bounds.width * node.bounds.height) / hostArea
            if (coverage >= HOST_SKIN_COVERAGE_THRESHOLD) result += node
        }
        node.children.forEach(::walk)
    }
    walk(root)
    return result
}

/**
 * True when [argb] is dark enough that pale (light-on-dark) widget text would lose contrast
 * against the launcher's usual bright glass — the same 0.4 relative-luminance cut
 * [prefersDarkInk] uses, just read the other way: a background this dark asks for the dark glass
 * variant ([HostFrameSpec.darkGlass]) instead of white ink vanishing on white frost.
 */
fun isDarkHostBackground(argb: Int): Boolean = relativeLuminance(argb) < 0.4

/**
 * Strip/restore bookkeeping for host widget skinning, generic over a background-owning handle
 * [V] (a real `android.view.View` in [HostWidgetSkin]; a plain fake in tests) so the state
 * machine — remember the original once, restore everything, do nothing on a repeat strip of an
 * already-tracked handle — is unit-testable without Android. [store] defaults to a plain map for
 * tests; the real adapter passes a [WeakHashMap] so a view thrown away by re-inflation is not
 * held forever.
 */
class SkinTracker<V, B, T>(
    private val getBackground: (V) -> B?,
    private val setBackground: (V, B?) -> Unit,
    private val getTint: (V) -> T?,
    private val setTint: (V, T?) -> Unit,
    private val store: MutableMap<V, Pair<B?, T?>> = LinkedHashMap(),
) {
    /** Handles currently stripped and tracked for restore. */
    val trackedCount: Int get() = store.size

    fun isTracked(view: V): Boolean = store.containsKey(view)

    /** The background this handle had before its first strip; null if never tracked. */
    fun originalOf(view: V): B? = store[view]?.first

    /**
     * Nulls [view]'s background and tint. The very first time a handle is seen its current
     * background/tint are remembered for [restoreAll]; a repeat call (idempotent re-apply, or a
     * relayout pass re-confirming the same candidate) never overwrites that memory with the
     * now-already-null value.
     */
    fun strip(view: V) {
        if (!store.containsKey(view)) store[view] = getBackground(view) to getTint(view)
        setBackground(view, null)
        setTint(view, null)
    }

    /** Puts every tracked handle back to its remembered background/tint and forgets it. */
    fun restoreAll() {
        store.forEach { (view, original) -> setBackground(view, original.first); setTint(view, original.second) }
        store.clear()
    }
}

// --- Android adapter --------------------------------------------------------------------

/**
 * Applies/restores [findHostSkinCandidates]-style stripping on a real AppWidgetHostView subtree.
 * One instance per host widget (owned by its ZeroPaddingWidgetHostView), so the [SkinTracker]'s
 * WeakHashMap only ever holds that widget's own views.
 */
internal class HostWidgetSkin {
    private val tracker = SkinTracker<View, Drawable, android.content.res.ColorStateList>(
        getBackground = { it.background },
        setBackground = { view, drawable -> view.background = drawable },
        getTint = { it.backgroundTintList },
        setTint = { view, tint -> view.backgroundTintList = tint },
        store = WeakHashMap(),
    )

    /**
     * [strip] false (Auto): restores every tracked view and returns null. [strip] true: finds
     * this pass's candidates in [root]'s live tree, strips them (idempotent — an already-tracked
     * view is just re-confirmed, never re-captured), and returns whether the *original* card
     * background sampled dark ([isDarkHostBackground]) — null when nothing could be sampled, so
     * the caller keeps following the system theme.
     */
    fun apply(root: View, strip: Boolean): Boolean? {
        if (!strip) {
            tracker.restoreAll()
            return null
        }
        val hostArea = root.width.toFloat() * root.height.toFloat()
        if (hostArea <= 0f) return null
        var darkResult: Boolean? = null
        collectCandidates(root, hostArea).forEach { view ->
            val original = if (tracker.isTracked(view)) tracker.originalOf(view) else view.background
            tracker.strip(view)
            if (darkResult == null) sampleColor(original)?.let { darkResult = isDarkHostBackground(it) }
        }
        return darkResult
    }

    private fun collectCandidates(root: View, hostArea: Float): List<View> {
        val result = mutableListOf<View>()
        fun isImageContent(view: View) = view is ImageView && view.drawable != null
        fun hasBackground(view: View) =
            view.background != null || view.backgroundTintList != null || tracker.isTracked(view)
        fun walk(view: View) {
            if (!isImageContent(view) && hasBackground(view)) {
                val coverage = (view.width.toFloat() * view.height.toFloat()) / hostArea
                if (coverage >= HOST_SKIN_COVERAGE_THRESHOLD) result += view
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return result
    }
}

/**
 * A representative ARGB colour of [drawable] for the dark/light read: a ColorDrawable's own
 * colour, a bitmap's average pixel, or — for anything else (GradientDrawable, shape drawables,
 * LayerDrawable, …) — the drawable rendered into a 1x1 canvas and read back. Null when there is
 * no drawable or it refuses to render (a hardware bitmap, an unbounded picture drawable).
 */
private fun sampleColor(drawable: Drawable?): Int? = when (drawable) {
    null -> null
    is ColorDrawable -> drawable.color
    is BitmapDrawable -> drawable.bitmap?.let { averagePixel(it) }
    else -> runCatching {
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val savedBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, 1, 1)
        drawable.draw(Canvas(probe))
        drawable.bounds = savedBounds
        probe.getPixel(0, 0)
    }.getOrNull()
}

/** Mean colour of [bitmap] via a 1x1 bilinear downscale; null for a recycled or empty bitmap. */
private fun averagePixel(bitmap: Bitmap): Int? {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
    return runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val pixel = scaled.getPixel(0, 0)
        if (scaled !== bitmap) scaled.recycle()
        pixel
    }.getOrNull()
}
