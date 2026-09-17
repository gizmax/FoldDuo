package cz.pflanzer.foldduo

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import cz.pflanzer.foldduo.systemfrost.SystemFrost
import java.lang.ref.WeakReference

internal enum class ShadePanel { NOTIFICATIONS, QUICK_SETTINGS }

internal enum class ShadeOpenResult { OPENED, SERVICE_DISABLED, SERVICE_STARTING, ACTION_REJECTED }

/**
 * The platform exposes shade expansion to third-party apps only as accessibility global actions.
 * This service deliberately observes no events and cannot inspect windows or inject gestures.
 */
class SystemShadeAccessibilityService : AccessibilityService() {
    private var systemFrost: SystemFrost? = null

    override fun onServiceConnected() {
        // The declaration lets Settings describe the service. Once bound, unsubscribe from
        // even our own package's events: global actions do not require event delivery.
        serviceInfo = serviceInfo.apply { eventTypes = 0 }
        instance = WeakReference(this)
        // Debug-only cross-app frost overlay spike (IDEAS.md B13); no-op in release builds.
        OverlayDebug.attach(this)
        // Production frost over other apps (STATUS.md "Mlha nad cizími aplikacemi").
        systemFrost = SystemFrost(this).also { it.start() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Delivery is 0 (off) except while SystemFrost is showing a B15 continuity bridge, which
        // widens it to window-content/windows-changed only — see SystemFrost.setAccessibilityEventsEnabled.
        event?.let { systemFrost?.onAccessibilityEvent(it) }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        OverlayDebug.detach(this)
        systemFrost?.stop()
        systemFrost = null
        if (instance.get() === this) instance.clear()
        super.onDestroy()
    }

    companion object {
        private var instance = WeakReference<SystemShadeAccessibilityService>(null)
        internal fun isConnected() = instance.get() != null

        internal fun open(context: Context, panel: ShadePanel): ShadeOpenResult {
            val service = instance.get()
            if (service != null) {
                val action = when (panel) {
                    ShadePanel.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                    ShadePanel.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                }
                return if (service.performGlobalAction(action)) ShadeOpenResult.OPENED
                else ShadeOpenResult.ACTION_REJECTED
            }
            return if (isEnabled(context)) ShadeOpenResult.SERVICE_STARTING
            else ShadeOpenResult.SERVICE_DISABLED
        }

        /**
         * B46 "Dvojice aplikací": `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` for a pair's launch sequence
         * (PairLaunch.kt), same "must already be bound" contract as [open] above — a cold service
         * start is too slow for the launch sequence to wait on, so the caller shows the "turn on
         * the accessibility service" prompt instead of retrying.
         */
        internal fun toggleSplitScreen(context: Context): ShadeOpenResult {
            val service = instance.get()
            if (service != null) {
                return if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN))
                    ShadeOpenResult.OPENED else ShadeOpenResult.ACTION_REJECTED
            }
            return if (isEnabled(context)) ShadeOpenResult.SERVICE_STARTING
            else ShadeOpenResult.SERVICE_DISABLED
        }

        /** Whether the accessibility service (shade gestures, Frost over other apps) is granted.
         * Also read by the Continuum settings page's "Frost over other apps" status line. */
        internal fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, SystemShadeAccessibilityService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                val service = it.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(service.packageName, service.name) == component
            }
        }

        /** Opens Android's Accessibility settings list (there is no per-service detail intent that
         * reliably resolves across One UI versions, same reasoning as [cz.pflanzer.foldduo.island.IslandNotificationListener]'s fallback). */
        internal fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
