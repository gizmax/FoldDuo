package cz.pflanzer.foldduo.systemfrost

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * B14 "Inner od ~35°" (STATUS.md, IDEAS.md): ask the system for the OPENED device state while
 * the phone is still opening (hinge step 0->90, ~35-50°), so the inner panel lights up there
 * instead of waiting for One UI's own ~91° `HALF_OPENED` switch. Moved here from the debug-only
 * spike (2026-09-15) once the production trigger ([OpenedOverridePlan], driven from
 * [SystemFrost]) needed it too; the debug broadcast in `MorphDebug.kt` still calls straight into
 * this object to replay it by hand.
 *
 * `adb shell cmd device_state state 3` did exactly this on Tom's Fold 8 (inner ON 420 ms after
 * the commit, override survived hinge 180/90/180, cancelled only by `state reset`).
 * `dumpsys device_state` lists OPENED (3) as `app_accessible=true`, so the top app may request it
 * without CONTROL_DEVICE_STATE — but `DeviceStateManager.requestState` is not in the public SDK
 * (checked android-36/37 stubs), hence reflection. Whether Samsung's hidden-API policy lets a
 * third-party app through in production is what this answers; every outcome is logged, and
 * [SystemFrost] disables further attempts for the process the first time [request] fails.
 */
internal object DeviceStateOverride {
    private const val TAG = "FoldDuoMorph"
    private const val OPENED = 3

    /** Requests [state] (default OPENED); returns a one-line outcome that is also logged. */
    fun request(context: Context, state: Int = OPENED): String = outcome("request state=$state") {
        val dsm = context.getSystemService("device_state") ?: error("no device_state service")
        val reqCls = Class.forName("android.hardware.devicestate.DeviceStateRequest")
        val builder = reqCls.getMethod("newBuilder", Int::class.javaPrimitiveType).invoke(null, state)!!
        val req = builder.javaClass.getMethod("build").invoke(builder)!!
        val cbCls = Class.forName("android.hardware.devicestate.DeviceStateRequest\$Callback")
        val cb = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
            Log.i(TAG, "device state override callback: ${m.name}(${args?.joinToString() ?: ""})")
            null
        }
        dsm.javaClass.getMethod("requestState", reqCls, Executor::class.java, cbCls)
            .invoke(dsm, req, ContextCompat.getMainExecutor(context), cb)
        "requested"
    }

    /** Cancels this process's active request, if any. */
    fun cancel(context: Context): String = outcome("cancel") {
        val dsm = context.getSystemService("device_state") ?: error("no device_state service")
        dsm.javaClass.getMethod("cancelStateRequest").invoke(dsm)
        "cancelled"
    }

    private inline fun outcome(what: String, body: () -> String): String {
        val text = try {
            "device state override: $what -> ${body()}"
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            "device state override: $what FAILED ${cause.javaClass.simpleName}: ${cause.message}"
        }
        Log.i(TAG, text)
        return text
    }
}
