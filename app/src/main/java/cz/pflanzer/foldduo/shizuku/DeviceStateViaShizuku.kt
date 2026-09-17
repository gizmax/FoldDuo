package cz.pflanzer.foldduo.shizuku

/**
 * `cmd device_state state <id>` / `state-reset` over [ShizukuBridge.runShell] — the shell-uid
 * equivalent of [cz.pflanzer.foldduo.systemfrost.DeviceStateOverride]'s in-process reflection call.
 *
 * Verified read-only on the Fold 8 (17.9., `adb -s <serial> shell ...`, device untouched):
 * `cmd device_state print-states` lists OPENED(3)/CONCURRENT_INNER_DEFAULT(4)/
 * CONCURRENT_OUTER_DEFAULT(5)/TENT(1)/CLOSED(0) as `app_accessible=true` and only
 * HALF_OPENED(2) as `app_accessible=false`; `dumpsys package android` shows
 * `CONTROL_DEVICE_STATE` as `prot=signature`, held only by `uid=1000` (android itself). Shell is
 * uid 2000, not uid 1000 — so a Shizuku shell process has exactly the same access to
 * `requestState` as the app already has by calling it in-process (app_accessible states only),
 * and no more. This class exists for API symmetry with a would-be HALF_OPENED override and to
 * give a shell path that survives the app's own process dying mid-request, not because it unlocks
 * a new device state — see docs/research/shizuku.md, row "OPENED/HALF_OPENED override".
 */
object DeviceStateViaShizuku {
    /** `cmd device_state state <id>`; id must be one of `cmd device_state print-states`'s ids. */
    fun request(state: Int): Result<String> = ShizukuBridge.runShell("cmd device_state state $state")

    /** `cmd device_state state reset` — `state-reset` (mentioned in some docs) does not exist on
     * this build, confirmed by [cz.pflanzer.foldduo.systemfrost.DeviceStateOverride]'s file
     * comment and STATUS.md's 15.9. device log. */
    fun reset(): Result<String> = ShizukuBridge.runShell("cmd device_state state reset")
}
