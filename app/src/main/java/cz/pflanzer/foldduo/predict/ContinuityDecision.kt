package cz.pflanzer.foldduo.predict

/**
 * B35 "kontinuita": pure decision logic for the "Pokračovat: …" chip. Free of Android types (the
 * UsageStats/pose glue lives in PredictionGlue.kt) so it runs as plain JUnit under app/src/test.
 */

/** An app seen in the foreground shortly before the panel swap — from real UsageStats data, or [LastLaunchTracker]'s own-launch fallback when usage access is not granted. */
data class ForegroundSighting(val packageName: String, val label: String, val timestampMs: Long)

/** How long before a cover→inner swap a foreground sighting still counts as "was running on the cover". */
const val CONTINUITY_FOREGROUND_WINDOW_MS = 10_000L

/** How long the "Pokračovat: …" chip stays up once shown. */
const val CONTINUITY_CHIP_DURATION_MS = 8_000L

/**
 * Whether [sighting] earns a continuity chip at [swapAtMs]: not our own launcher package, and
 * seen within [windowMs] strictly before (or at) the swap — a sighting timestamped after the swap
 * is not "was running on the cover before it" and is rejected, same as one that is too old.
 */
fun continuityEligible(
    sighting: ForegroundSighting?,
    ownPackage: String,
    swapAtMs: Long,
    windowMs: Long = CONTINUITY_FOREGROUND_WINDOW_MS,
): Boolean {
    if (sighting == null || sighting.packageName == ownPackage) return false
    val age = swapAtMs - sighting.timestampMs
    return age in 0..windowMs
}

/** Whether a chip shown at [shownAtMs] is still visible at [nowMs] ([CONTINUITY_CHIP_DURATION_MS] wide, inclusive both ends). */
fun continuityChipVisible(shownAtMs: Long, nowMs: Long, durationMs: Long = CONTINUITY_CHIP_DURATION_MS): Boolean =
    nowMs in shownAtMs..(shownAtMs + durationMs)
