package cz.pflanzer.foldduo

/**
 * Výkon 5 "stránky složené předem" (17. 9. noc): pure sequencing for HostWidgetView's
 * (LauncherScreen.kt) deferred `AndroidView` bind. Composing every Home page for the whole life
 * of the strip (see WorkspacePageMotion.kt's `composedHomePages`) removes the swipe-time
 * composition churn that used to force a fresh `AndroidView`/`AppWidgetHostView` inflate
 * (`controller.host.createView`, ~100 ms) onto a settle frame; this policy is the general backstop
 * for the cases that can still (re)compose a page — mainly a layout edit — so THAT frame never
 * carries the inflate either. HostWidgetView flips a `framePosted` flag one frame after it first
 * (re)enters composition (`withFrameNanos`, the same idiom the freeze/thaw snapshot retry already
 * used) and only calls `AndroidView(...)` once [hostWidgetShouldBindLive] says so; the frame in
 * between draws whatever [hostWidgetOverlay] picks instead. Free of `android.graphics.Bitmap`
 * (generic over the overlay type) and of any Compose/Android type at all, so both are plain-JUnit
 * testable.
 */

/** True once the live `AndroidView` may bind — exactly one frame after this widget id first (or
 * again, after a layout edit replaces it) entered composition, never the same frame. */
internal fun hostWidgetShouldBindLive(framePosted: Boolean): Boolean = framePosted

/**
 * What HostWidgetView draws over (or instead of) the live view this frame, in priority order:
 * a morph freeze's own snapshot always wins — a freeze can start on any frame, including the one
 * [hostWidgetShouldBindLive] flips true on — then its late retry (the freeze began before the host
 * view had a laid-out size to capture), then the deferred-bind placeholder (nothing bound yet),
 * then the last frame's lingering hand-over bitmap (kept one extra frame so the live view has
 * drawn once before its stand-in disappears). Null means "draw nothing over the live view".
 */
internal fun <T> hostWidgetOverlay(frozenSnapshot: T?, frozenLateSnapshot: T?, preBindSnapshot: T?, lingering: T?): T? =
    frozenSnapshot ?: frozenLateSnapshot ?: preBindSnapshot ?: lingering
