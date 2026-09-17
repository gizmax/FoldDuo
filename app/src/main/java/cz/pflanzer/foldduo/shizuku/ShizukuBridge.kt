package cz.pflanzer.foldduo.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Research spike for `docs/research/shizuku.md` (17.9. investigation of RikkaApps/Shizuku for
 * Fold Duo). Nothing in the launcher calls into this package yet — no settings screen, no
 * `SystemFrost`/`OpenedOverridePlan` wiring, on purpose: the research verified that the
 * launcher's actual blockers mostly do NOT need Shizuku (OPENED/CONCURRENT_* device states are
 * already `app_accessible=true` and reachable by the foreground app itself via
 * [cz.pflanzer.foldduo.systemfrost.DeviceStateOverride]'s reflection call; the continuous hinge
 * angle Samsung sensor is permission-redacted even in `dumpsys sensorservice` for the shell uid,
 * see the doc). This is kept compile-only so a later patch can wire a real feature into it
 * without redoing the plumbing.
 *
 * Unlike [cz.pflanzer.foldduo.systemfrost.DeviceStateOverride], which calls hidden platform API
 * via reflection because the caller is the app's own uid, everything here talks to the Shizuku
 * privileged process (uid 2000 shell over wireless debugging, or uid 0 if paired via root) through
 * `rikka.shizuku:api`'s public surface — no reflection needed on our side; Shizuku's own library
 * does the Binder plumbing to the shell-uid `IShizukuService`.
 */
object ShizukuBridge {

    /** Same `SharedPreferences` file as [cz.pflanzer.foldduo.systemfrost.SystemFrost.PREFS_NAME]
     * ("appearance") — kept as a literal here rather than a cross-module import so this package
     * stays a self-contained, easily deletable spike. */
    const val PREFS_NAME = "appearance"

    /** "Shizuku (experimental)" toggle mentioned in docs/research/shizuku.md; default off, and
     * currently read by nobody but [isEnabled] itself — no settings UI wires it yet. */
    const val KEY_ENABLED = "shizukuExperimental"

    private const val PERMISSION_REQUEST_CODE = 9100

    /** Whether the not-yet-built "Shizuku (experimental)" setting is on. Gates nothing today. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** True once the Shizuku app is installed, its privileged service started (manually, or via
     * `adb shell sh /sdcard/Android/.../start.sh` after each reboot — see the doc's risk column)
     * and this process's binder to it is alive. False, never a crash, if Shizuku is absent. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    enum class PermissionState { GRANTED, DENIED, SHOULD_SHOW_RATIONALE, UNAVAILABLE }

    /** Shizuku API v11+ permission model (this module depends on `dev.rikka.shizuku:api:13.1.5`):
     * no `<uses-permission>` entry, no PackageManager involved — the check/request pair mirrors
     * Android's runtime permission dance but talks to the Shizuku service instead. */
    fun checkPermission(): PermissionState {
        if (!isAvailable()) return PermissionState.UNAVAILABLE
        return runCatching {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
                Shizuku.shouldShowRequestPermissionRationale() -> PermissionState.SHOULD_SHOW_RATIONALE
                else -> PermissionState.DENIED
            }
        }.getOrDefault(PermissionState.UNAVAILABLE)
    }

    /** Fire-and-forget: the grant/deny result arrives via
     * `Shizuku.addRequestPermissionResultListener`, which nothing registers yet — wiring a
     * listener (and an activity to receive it) is UI work explicitly out of scope for this spike. */
    fun requestPermission() {
        if (isAvailable()) runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /**
     * Runs `sh -c cmd` inside the Shizuku privileged process and returns combined stdout, or a
     * failure carrying the exit code and stderr.
     *
     * Not reflection-free after all: unlike `pingBinder`/`checkSelfPermission`/`requestPermission`
     * above (all public), `Shizuku.newProcess` was made `private` on the 13.1.5 api artifact —
     * verified by decompiling the resolved aar (`javap -p` on `rikka/shizuku/Shizuku.class`
     * shows `private static ... newProcess(String[], String[], String)`; the library now steers
     * callers towards a bound `UserService` AIDL interface instead of raw shell processes). This
     * reaches it the same way [cz.pflanzer.foldduo.systemfrost.DeviceStateOverride] reaches
     * hidden platform API: `getDeclaredMethod` + `isAccessible = true`. The returned
     * `ShizukuRemoteProcess` is a public `java.lang.Process` subclass, so no further reflection
     * is needed once the method reference itself is unlocked.
     *
     * Not called from anywhere yet. Meant for short, idempotent, read-mostly commands — this is
     * not a shell session, there is no stdin wiring, and each call spins up a fresh `sh -c`.
     */
    fun runShell(cmd: String): Result<String> {
        if (!isAvailable()) return Result.failure(IllegalStateException("Shizuku is not available"))
        if (checkPermission() != PermissionState.GRANTED) {
            return Result.failure(SecurityException("Shizuku permission not granted"))
        }
        return runCatching {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val process = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            check(exitCode == 0) { "exit=$exitCode stderr=$stderr" }
            stdout
        }
    }
}
