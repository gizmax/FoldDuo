package cz.pflanzer.foldduo

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.compose.ui.geometry.Offset

internal class ZeroPaddingWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int,
        appWidget: AppWidgetProviderInfo): AppWidgetHostView =
        ZeroPaddingWidgetHostView(context)
}

/** Logcat tag for B40's per-widget size/options plumbing (`multiSizeOptionsBundle`, [HostWidgetSkinHostView.applyPanelSizes]). */
internal const val WIDGETS_LOG_TAG = "FoldDuoWidgets"

/** Every third-party widget's real view; [HostWidgetSkinHostView] exposes the hooks LauncherScreen drives. */
internal interface HostWidgetSkinHostView {
    /**
     * [appearance] != Auto strips the provider's own opaque card so [HostWidgetFrame]'s
     * backing/frost shows through; Auto restores it. [onDarknessSampled] is called with the
     * card's pre-strip darkness (see [isDarkHostBackground]) whenever a strip newly samples one,
     * so the caller can switch Glass to its dark variant. Safe to call every recomposition —
     * it only re-walks the tree, never re-captures an already-tracked original.
     */
    fun applySkin(appearance: WidgetAppearance, onDarknessSampled: (Boolean) -> Unit)

    /**
     * B40: the dp box(es) this placement gets — one per panel it can appear on, cover and inner,
     * from [LayoutModel.bothPanelContentSizes]/[leadingPanelContentSize]. Called from
     * `HostWidgetView`'s `AndroidView` `update` block on every recomposition, i.e. on a panel
     * swap and on first bind alike; a no-op when the sizes have not changed since the last call.
     * `null` leaves the view on its own organic (single, measured) size publication.
     */
    fun applyPanelSizes(sizes: WidgetOptionsSizes?)
}

private class ZeroPaddingWidgetHostView(context: Context) : AppWidgetHostView(context), HostWidgetSkinHostView {
    private var providerOwnsVerticalGesture = false
    private var panelSizes: WidgetOptionsSizes? = null
    private val publishCurrentSize = Runnable {
        if (!isAttachedToWindow || appWidgetId < 0) return@Runnable
        val explicit = panelSizes
        if (explicit != null) publishWidgetSizes(this, explicit)
        else if (width > 0 && height > 0) {
            val density = resources.displayMetrics.density
            publishWidgetSizes(this, widgetOptionsSizes(listOf(WidgetContentSize(width / density, height / density))))
        }
    }

    // --- Skin: strips the provider's own opaque card so appearance frames show (HostWidgetSkin.kt) ---
    private val skin = HostWidgetSkin()
    private var skinAppearance: WidgetAppearance = WidgetAppearance.Auto
    private var onDarknessSampled: ((Boolean) -> Unit)? = null
    private val reskinRunnable = Runnable { reskin() }

    init {
        setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            // Samsung widgets can re-inflate their whole tree on a partial RemoteViews update
            // instead of reusing views; catch that here rather than relying on updateAppWidget
            // alone, which some providers bypass by mutating the existing tree directly.
            override fun onChildViewAdded(parent: View?, child: View?) = scheduleReskin()
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
    }

    override fun applySkin(appearance: WidgetAppearance, onDarknessSampled: (Boolean) -> Unit) {
        skinAppearance = appearance
        this.onDarknessSampled = onDarknessSampled
        scheduleReskin()
    }

    override fun applyPanelSizes(sizes: WidgetOptionsSizes?) {
        if (panelSizes == sizes) return
        panelSizes = sizes
        scheduleSizePublication()
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        scheduleReskin()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A resize or a provider that lays out asynchronously after inflation both land here;
        // coverage depends on final pixel sizes, so re-skin once the layout settles.
        if (changed) scheduleReskin()
    }

    private fun scheduleReskin() {
        removeCallbacks(reskinRunnable)
        post(reskinRunnable)
    }

    private fun reskin() {
        val strip = skinAppearance != WidgetAppearance.Auto
        val dark = skin.apply(this, strip)
        if (strip && dark != null) onDarknessSampled?.invoke(dark)
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
        scheduleSizePublication()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleSizePublication()
    }

    override fun onSizeChanged(widthPx: Int, heightPx: Int, oldWidthPx: Int, oldHeightPx: Int) {
        super.onSizeChanged(widthPx, heightPx, oldWidthPx, oldHeightPx)
        if (appWidgetId < 0 || widthPx <= 0 || heightPx <= 0) return
        // Avoid provider IPC inside a View/Compose layout callback. Coalesce size publication
        // until after the current layout pass finishes.
        scheduleSizePublication()
    }

    override fun onDetachedFromWindow() {
        if (providerOwnsVerticalGesture) parent?.requestDisallowInterceptTouchEvent(false)
        providerOwnsVerticalGesture = false
        removeCallbacks(publishCurrentSize)
        removeCallbacks(reskinRunnable)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Compose's Home column is vertically scrollable too. Give a provider's
                // ListView/ScrollView the uninterrupted native stream when DOWN hit that
                // content. The common pager observes Initial events before AndroidView, so
                // a horizontal swipe can still claim its axis and cancel this stream.
                providerOwnsVerticalGesture = nativeWidgetConsumesVerticalGesture(
                    this,
                    Offset(event.rawX, event.rawY),
                )
                if (providerOwnsVerticalGesture) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (providerOwnsVerticalGesture) parent?.requestDisallowInterceptTouchEvent(false)
                providerOwnsVerticalGesture = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun scheduleSizePublication() {
        removeCallbacks(publishCurrentSize)
        post(publishCurrentSize)
    }
}

internal fun exactWidgetSizeOptions(widthDp: Float, heightDp: Float): Bundle =
    multiSizeOptionsBundle(widgetOptionsSizes(listOf(WidgetContentSize(widthDp, heightDp))))

/**
 * B40: `OPTION_APPWIDGET_MIN/MAX_WIDTH/HEIGHT` from [sizes]' dp bounds plus
 * `OPTION_APPWIDGET_SIZES` (Android 12+, an `ArrayList<SizeF>`) listing every distinct size —
 * one per panel a placement can appear on — so a responsive RemoteViews can pick its layout for
 * whichever one it is actually bound at, not just the widest/tallest combination.
 */
internal fun multiSizeOptionsBundle(sizes: WidgetOptionsSizes): Bundle = Bundle().apply {
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, sizes.minWidthDp.toInt())
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, sizes.maxWidthDp.toInt())
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, sizes.minHeightDp.toInt())
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, sizes.maxHeightDp.toInt())
    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
        ArrayList(sizes.sizes.map { SizeF(it.widthDp, it.heightDp) }))
}

/**
 * Pushes [sizes] to the provider: the options [Bundle] (dedup logged/applied only when the
 * bounds or the size list actually changed, same as before B40) and, per the task,
 * [AppWidgetHostView.updateAppWidgetSize] — the Android 12+ entry point that both updates the
 * options and hands the provider the concrete list of sizes it may be laid out at, so a
 * responsive RemoteViews can prebuild the right layout instead of guessing from one bundle.
 */
private fun publishWidgetSizes(view: AppWidgetHostView, sizes: WidgetOptionsSizes) {
    val options = multiSizeOptionsBundle(sizes)
    val current = AppWidgetManager.getInstance(view.context).getAppWidgetOptions(view.appWidgetId)
    val sameBounds = listOf(
        AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
        AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
        AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
        AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
    ).all { current.getInt(it) == options.getInt(it) }
    val currentSizes = current.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
    val nextSizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
    if (sameBounds && currentSizes == nextSizes) return
    Log.d(WIDGETS_LOG_TAG, "widget ${view.appWidgetId}: sizes=${sizes.sizes} " +
        "min=${sizes.minWidthDp}x${sizes.minHeightDp} max=${sizes.maxWidthDp}x${sizes.maxHeightDp}")
    view.updateAppWidgetSize(options, sizes.sizes.map { SizeF(it.widthDp, it.heightDp) })
}
