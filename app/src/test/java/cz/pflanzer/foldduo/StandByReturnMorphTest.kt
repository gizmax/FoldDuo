package cz.pflanzer.foldduo

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B32 (standby/StandByMorph.kt): MorphController.requestStandByReturn plays a clear on the
 * launcher's own panel once StandBy exits. Minimal wiring test, same TestFrameClock technique as
 * MorphPolishTest.kt; the transition's own state machine and curves are covered in
 * standby/StandByMorphTest.kt.
 */
class StandByReturnMorphTest {
    private class TestFrameClock(private val frameMs: Long = 8L) : MonotonicFrameClock {
        var nowMs = 0L
            private set

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            nowMs += frameMs
            val result = onFrame(nowMs * 1_000_000L)
            Snapshot.sendApplyNotifications()
            return result
        }
    }

    private fun controller() = MorphController(now = { 0L },
        snapshot = { PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover) }, log = {}, info = {})

    @Test fun `requestStandByReturn arms exactly one take`() {
        val morph = controller()
        assertFalse(morph.takeStandByReturn())
        morph.requestStandByReturn()
        assertEquals(1, morph.standByReturnRequests)
        assertTrue(morph.takeStandByReturn())
        assertFalse(morph.takeStandByReturn()) // consumed, not re-armed
    }

    @Test fun `playStandByReturn snaps fully frosted then clears to sharp`() {
        val morph = controller()
        assertEquals(0f, morph.standByReturnFrost.value, 0f)
        runBlocking(TestFrameClock()) { morph.playStandByReturn() }
        assertEquals(0f, morph.standByReturnFrost.value, 0f)
    }

    @Test fun `a second request after the first take arms another play`() {
        val morph = controller()
        morph.requestStandByReturn()
        morph.takeStandByReturn()
        assertEquals(1, morph.standByReturnRequests)
        morph.requestStandByReturn()
        assertEquals(2, morph.standByReturnRequests)
        assertTrue(morph.takeStandByReturn())
    }
}
