package cz.pflanzer.foldduo

import android.accessibilityservice.AccessibilityService

/** Debug source set supplies the broadcast-driven cross-app frost overlay spike (IDEAS.md B13). */
internal object OverlayDebug {
    fun attach(service: AccessibilityService) = Unit
    fun detach(service: AccessibilityService) = Unit
}
