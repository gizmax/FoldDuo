package cz.pflanzer.foldduo.island

/**
 * B61 phase 2: what kind of change between the same [IslandItem.key]'s previous and new state is
 * worth a pulse/haptic in the UI ("alert" state in IDEAS.md's "Stavy" list) — the point is that a
 * merely-redrawn identical item (a notification update that changed nothing meaningful) must stay
 * silent, or every Live Update tick would pulse the island.
 */
enum class IslandAlert {
    /** Nothing worth calling out changed. */
    None,

    /** The trailing value moved by enough to notice (an ETA shift of at least a minute, or the compact `keyValue` text itself changed). */
    ValueChanged,

    /** [IslandItem.stageIndex] advanced (or moved) to a different stage of [IslandItem.segments]. */
    StageChanged,

    /** The ETA crossed into "arriving now" ([LIVE_ACTIVITY_ALERT_WINDOW_MS]) for the first time — including a brand new item that is already inside the window the first time it is seen. */
    Arriving,
}

/** An ETA move smaller than this is noise (rounding, a re-post with the same minute), not a change worth pulsing. */
const val ETA_ALERT_THRESHOLD_MS = 60_000L

/**
 * Compares [previous] (the same [IslandItem.key]'s last published state, or null the first time
 * this key is seen) against [updated] and picks the single most significant [IslandAlert] —
 * checked in priority order (arriving beats a stage move beats a plain value tick, matching how
 * an actual arrival is the moment that matters most): [IslandAlert.Arriving] first, then
 * [IslandAlert.StageChanged], then [IslandAlert.ValueChanged], else [IslandAlert.None].
 */
fun liveActivityAlert(previous: IslandItem?, updated: IslandItem, now: Long): IslandAlert {
    val updatedEta = updated.etaMs
    val previousEta = previous?.etaMs
    val wasOutsideArrivalWindow = previousEta == null || previousEta - now > LIVE_ACTIVITY_ALERT_WINDOW_MS
    val isInsideArrivalWindow = updatedEta != null && updatedEta - now <= LIVE_ACTIVITY_ALERT_WINDOW_MS
    if (isInsideArrivalWindow && wasOutsideArrivalWindow) return IslandAlert.Arriving

    if (previous != null && updated.stageIndex != null && updated.stageIndex != previous.stageIndex) {
        return IslandAlert.StageChanged
    }

    if (previous != null) {
        val etaMoved = previousEta != null && updatedEta != null &&
            kotlin.math.abs(updatedEta - previousEta) >= ETA_ALERT_THRESHOLD_MS
        val keyValueMoved = updated.keyValue != null && updated.keyValue != previous.keyValue
        if (etaMoved || keyValueMoved) return IslandAlert.ValueChanged
    }

    return IslandAlert.None
}
