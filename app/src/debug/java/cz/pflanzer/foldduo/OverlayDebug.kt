package cz.pflanzer.foldduo

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.util.function.Consumer

/**
 * Debug spike (IDEAS.md B13): drives our accessibility service's own window manager to draw a
 * frost overlay over WHATEVER app is in the foreground, to see whether the Continuum morph can
 * escape the launcher. Nothing here runs unless the broadcast below arrives; the release source
 * set supplies a no-op ([SystemShadeAccessibilityService] calls [attach]/[detach] unconditionally).
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_OVERLAY                                   # 3 s, blur-behind, whole display
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_OVERLAY --ei ms 5000 --ei blur 60          # longer, stronger
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_OVERLAY --ez left true                     # left half only
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_OVERLAY --ez shot true --ei blur 30         # screenshot + RenderEffect fallback
 *
 * Look for tag FoldDuoOverlay in logcat: attach/detach, isCrossWindowBlurEnabled (and changes to
 * it), every overlay attach/remove, screenshot latency, and any exception.
 */
internal object OverlayDebug {
    private const val ACTION = "cz.pflanzer.foldduo.DEBUG_OVERLAY"
    private const val TAG = "FoldDuoOverlay"
    private const val DEFAULT_MS = 3000
    private const val DEFAULT_BLUR = 40
    private const val TICK_MS = 16L

    private var receiver: BroadcastReceiver? = null
    private var blurListener: Consumer<Boolean>? = null
    private var activeView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRemoval: Runnable? = null

    fun attach(service: AccessibilityService) {
        val wm = service.getSystemService(WindowManager::class.java)
        if (wm == null) {
            Log.e(TAG, "no WindowManager on the accessibility service, cannot attach")
            return
        }
        Log.i(TAG, "isCrossWindowBlurEnabled=${wm.isCrossWindowBlurEnabled}")
        val listener = Consumer<Boolean> { enabled -> Log.i(TAG, "crossWindowBlurEnabled changed: $enabled") }
        runCatching { wm.addCrossWindowBlurEnabledListener(listener) }
            .onFailure { e -> Log.e(TAG, "addCrossWindowBlurEnabledListener failed", e) }
        blurListener = listener

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val ms = (intent?.getIntExtra("ms", DEFAULT_MS) ?: DEFAULT_MS).coerceAtLeast(1)
                val blur = (intent?.getIntExtra("blur", DEFAULT_BLUR) ?: DEFAULT_BLUR).coerceAtLeast(0)
                val shot = intent?.getBooleanExtra("shot", false) == true
                val left = intent?.getBooleanExtra("left", false) == true
                Log.i(TAG, "DEBUG_OVERLAY received: ms=$ms blur=$blur shot=$shot left=$left")
                removeActiveOverlay(wm)
                if (shot) showScreenshotOverlay(service, wm, ms, blur, left)
                else showBlurBehindOverlay(service, wm, ms, blur, left)
            }
        }
        ContextCompat.registerReceiver(service, r, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        Log.i(TAG, "DEBUG_OVERLAY receiver registered")
    }

    fun detach(service: AccessibilityService) {
        receiver?.let { r -> runCatching { service.unregisterReceiver(r) } }
        receiver = null
        val wm = runCatching { service.getSystemService(WindowManager::class.java) }.getOrNull()
        blurListener?.let { l -> wm?.let { runCatching { it.removeCrossWindowBlurEnabledListener(l) } } }
        blurListener = null
        wm?.let { removeActiveOverlay(it) }
        Log.i(TAG, "OverlayDebug detached")
    }

    private fun removeActiveOverlay(wm: WindowManager) {
        pendingRemoval?.let { handler.removeCallbacks(it) }
        pendingRemoval = null
        activeView?.let { v -> runCatching { wm.removeView(v) } }
        activeView = null
    }

    // ---- blur-behind path (the system compositor blurs whatever is behind the window) --------

    private fun showBlurBehindOverlay(service: AccessibilityService, wm: WindowManager, ms: Int, blur: Int, left: Boolean) {
        val displayBounds = wm.currentWindowMetrics.bounds
        val plan = OverlayPlan.bounds(displayBounds.width(), displayBounds.height(), left)
        val view = View(service).apply {
            // ~0.25 alpha translucent white tint.
            setBackgroundColor(Color.argb(110, 255, 255, 255))
        }
        val params = WindowManager.LayoutParams(
            plan.widthPx,
            plan.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = plan.leftPx
            y = plan.topPx
            blurBehindRadius = 0
        }
        try {
            wm.addView(view, params)
            activeView = view
            Log.i(TAG, "attached blur-behind overlay bounds=$plan ms=$ms blur=$blur")
        } catch (e: Exception) {
            Log.e(TAG, "failed to attach blur-behind overlay", e)
            return
        }

        val start = SystemClock.uptimeMillis()
        val rampMs = OverlayPlan.rampMs(ms.toLong())
        lateinit var tick: Runnable
        tick = Runnable {
            if (activeView !== view) return@Runnable // superseded by a newer request
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= ms) {
                params.blurBehindRadius = 0
                runCatching { wm.removeView(view) }
                activeView = null
                pendingRemoval = null
                Log.i(TAG, "removed blur-behind overlay after ${elapsed}ms")
                return@Runnable
            }
            params.blurBehindRadius = OverlayPlan.blurRadiusAt(elapsed, ms.toLong(), blur, rampMs)
            runCatching { wm.updateViewLayout(view, params) }
                .onFailure { e -> Log.e(TAG, "updateViewLayout failed", e) }
            handler.postDelayed(tick, TICK_MS)
        }
        pendingRemoval = tick
        handler.post(tick)
    }

    // ---- screenshot fallback (takeScreenshot + RenderEffect blur on the bitmap view) ----------

    private fun showScreenshotOverlay(service: AccessibilityService, wm: WindowManager, ms: Int, blur: Int, left: Boolean) {
        val requestedAt = SystemClock.uptimeMillis()
        // An AccessibilityService is not a visual Context: `service.display` throws. Logical display 0
        // tracks whichever panel is active on this Fold (CONTEXT.md), so DEFAULT_DISPLAY is the one.
        val displayId = Display.DEFAULT_DISPLAY
        service.takeScreenshot(
            displayId,
            ContextCompat.getMainExecutor(service),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val latencyMs = SystemClock.uptimeMillis() - requestedAt
                    Log.i(TAG, "screenshot captured in ${latencyMs}ms displayId=$displayId")
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to wrap screenshot HardwareBuffer", e)
                        null
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    if (bitmap != null) presentScreenshot(service, wm, bitmap, ms, blur, left)
                }

                override fun onFailure(errorCode: Int) {
                    val latencyMs = SystemClock.uptimeMillis() - requestedAt
                    Log.e(TAG, "screenshot failed after ${latencyMs}ms, errorCode=$errorCode")
                }
            },
        )
    }

    private fun presentScreenshot(service: AccessibilityService, wm: WindowManager, bitmap: Bitmap, ms: Int, blur: Int, left: Boolean) {
        val displayBounds = wm.currentWindowMetrics.bounds
        val plan = OverlayPlan.bounds(displayBounds.width(), displayBounds.height(), left)
        val imageView = ImageView(service).apply {
            // MATRIX + identity matrix draws the screenshot 1:1 from (0,0); the window itself is
            // only plan.widthPx wide for the left-half case, so the right half is simply clipped.
            scaleType = ImageView.ScaleType.MATRIX
            imageMatrix = Matrix()
            setImageBitmap(bitmap)
            setBackgroundColor(Color.argb(110, 255, 255, 255))
            setRenderEffect(RenderEffect.createBlurEffect(blur.coerceAtLeast(1).toFloat(), blur.coerceAtLeast(1).toFloat(), Shader.TileMode.CLAMP))
        }
        val params = WindowManager.LayoutParams(
            plan.widthPx,
            plan.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // A window added straight through WindowManager is software-rendered unless asked
            // otherwise; a hardware Bitmap and a RenderEffect need the GPU path (2026-09-15: the
            // first run attached fine and drew nothing visible).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = plan.leftPx
            y = plan.topPx
        }
        try {
            wm.addView(imageView, params)
            activeView = imageView
            Log.i(TAG, "attached screenshot overlay bounds=$plan ms=$ms blur=$blur bitmap=${bitmap.width}x${bitmap.height} ${bitmap.config}")
            var drawn = false
            imageView.viewTreeObserver.addOnDrawListener {
                if (!drawn) {
                    drawn = true
                    Log.i(TAG, "screenshot overlay first draw: hwAccelerated=${imageView.isHardwareAccelerated} size=${imageView.width}x${imageView.height} attached=${imageView.isAttachedToWindow} shown=${imageView.isShown}")
                }
            }
            handler.postDelayed({ if (!drawn) Log.w(TAG, "screenshot overlay never drew within 500 ms") }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "failed to attach screenshot overlay", e)
            return
        }
        val removal = Runnable {
            if (activeView === imageView) {
                runCatching { wm.removeView(imageView) }
                activeView = null
                pendingRemoval = null
                Log.i(TAG, "removed screenshot overlay")
            }
        }
        pendingRemoval = removal
        handler.postDelayed(removal, ms.toLong())
    }
}
