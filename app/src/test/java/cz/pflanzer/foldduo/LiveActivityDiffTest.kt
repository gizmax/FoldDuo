package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.island.ETA_ALERT_THRESHOLD_MS
import cz.pflanzer.foldduo.island.IslandAlert
import cz.pflanzer.foldduo.island.IslandItem
import cz.pflanzer.foldduo.island.IslandKind
import cz.pflanzer.foldduo.island.LIVE_ACTIVITY_ALERT_WINDOW_MS
import cz.pflanzer.foldduo.island.liveActivityAlert
import org.junit.Assert.assertEquals
import org.junit.Test

/** B61 phase 2: [liveActivityAlert] decides which notification-key updates are worth a UI pulse. */
class LiveActivityDiffTest {
    private val now = 1_726_000_000_000L
    private fun ride(etaMs: Long? = null, stageIndex: Int? = null, keyValue: String? = null) =
        IslandItem("ride:0", kind = IslandKind.TRANSPORT, etaMs = etaMs, stageIndex = stageIndex, keyValue = keyValue)

    @Test fun `an identical republish is silent`() {
        val item = ride(etaMs = now + 10 * 60_000L, stageIndex = 1, keyValue = "10 min")
        assertEquals(IslandAlert.None, liveActivityAlert(item, item.copy(), now))
    }

    @Test fun `a brand new item far from arrival is silent`() {
        val item = ride(etaMs = now + 10 * 60_000L, keyValue = "10 min")
        assertEquals(IslandAlert.None, liveActivityAlert(null, item, now))
    }

    @Test fun `eta moving by at least a minute is a value change`() {
        val previous = ride(etaMs = now + 10 * 60_000L, keyValue = "10 min")
        val updated = ride(etaMs = now + 8 * 60_000L, keyValue = "8 min")
        assertEquals(IslandAlert.ValueChanged, liveActivityAlert(previous, updated, now))
        // Below the threshold: no alert.
        val tinyMove = ride(etaMs = previous.etaMs!! - (ETA_ALERT_THRESHOLD_MS - 1_000L), keyValue = "10 min")
        assertEquals(IslandAlert.None, liveActivityAlert(previous, tinyMove, now))
        // Exactly at the threshold: counts.
        val atThreshold = ride(etaMs = previous.etaMs!! - ETA_ALERT_THRESHOLD_MS, keyValue = "9 min")
        assertEquals(IslandAlert.ValueChanged, liveActivityAlert(previous, atThreshold, now))
    }

    @Test fun `keyValue changing without a numeric eta is still a value change`() {
        val previous = ride(keyValue = "Připravuje se")
        val updated = ride(keyValue = "Kurýr")
        assertEquals(IslandAlert.ValueChanged, liveActivityAlert(previous, updated, now))
        assertEquals(IslandAlert.None, liveActivityAlert(previous, previous.copy(), now))
    }

    @Test fun `stage index changing is a stage change, even without an eta`() {
        val previous = ride(stageIndex = 1, keyValue = "Připravuje se")
        val updated = ride(stageIndex = 2, keyValue = "Připravuje se")
        assertEquals(IslandAlert.StageChanged, liveActivityAlert(previous, updated, now))
    }

    @Test fun `crossing into the arrival window fires Arriving even over a stage or value change`() {
        val previous = ride(etaMs = now + 5 * 60_000L, stageIndex = 1, keyValue = "5 min")
        val updated = ride(etaMs = now + 90_000L, stageIndex = 2, keyValue = "90 s")
        assertEquals(IslandAlert.Arriving, liveActivityAlert(previous, updated, now))
    }

    @Test fun `a brand new item already inside the arrival window fires Arriving the first time it is seen`() {
        val updated = ride(etaMs = now + 90_000L, keyValue = "90 s")
        assertEquals(IslandAlert.Arriving, liveActivityAlert(null, updated, now))
    }

    @Test fun `already-arriving items do not re-fire Arriving on every tick`() {
        val previous = ride(etaMs = now + 100_000L, keyValue = "100 s")
        val updated = ride(etaMs = now + 40_000L, keyValue = "40 s")
        // Both inside the window: this is a value change, not a fresh arrival.
        assertEquals(IslandAlert.ValueChanged, liveActivityAlert(previous, updated, now))
    }

    @Test fun `exactly at the arrival window boundary already counts as arriving`() {
        val previous = ride(etaMs = now + LIVE_ACTIVITY_ALERT_WINDOW_MS + 60_000L)
        val updated = ride(etaMs = now + LIVE_ACTIVITY_ALERT_WINDOW_MS)
        assertEquals(IslandAlert.Arriving, liveActivityAlert(previous, updated, now))
    }
}
