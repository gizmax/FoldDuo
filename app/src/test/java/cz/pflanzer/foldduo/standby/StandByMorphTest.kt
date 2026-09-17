package cz.pflanzer.foldduo.standby

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B32 "Tent -> StandBy morph" (STATUS.md "Tent -> StandBy morph B32"): the pure state machine and
 * curves in StandByMorph.kt. [StandByMorphController]'s suspend plays are exercised with the same
 * `TestFrameClock` technique as MorphPolishTest.kt (Animatable needs a MonotonicFrameClock, not a
 * real Android animator, to run on the JVM).
 */
class StandByMorphTest {

    // ---- the enter/leave state machine ----

    @Test fun `enter only succeeds from idle`() {
        val machine = StandByMorphMachine()
        assertEquals(StandByMorphPhase.Idle, machine.phase)
        assertTrue(machine.requestEnter())
        assertEquals(StandByMorphPhase.Entering, machine.phase)
        assertFalse(machine.requestEnter()) // already entering
        assertEquals(StandByMorphPhase.Entering, machine.phase)
    }

    @Test fun `enter complete only advances from entering`() {
        val machine = StandByMorphMachine()
        assertFalse(machine.enterComplete()) // nothing entering yet
        machine.requestEnter()
        assertTrue(machine.enterComplete())
        assertEquals(StandByMorphPhase.Showing, machine.phase)
        assertFalse(machine.enterComplete()) // already showing
    }

    @Test fun `leave succeeds while entering or showing, never from idle`() {
        val entering = StandByMorphMachine().apply { requestEnter() }
        assertTrue(entering.requestLeave())
        assertEquals(StandByMorphPhase.Leaving, entering.phase)

        val showing = StandByMorphMachine().apply { requestEnter(); enterComplete() }
        assertTrue(showing.requestLeave())
        assertEquals(StandByMorphPhase.Leaving, showing.phase)

        val idle = StandByMorphMachine()
        assertFalse(idle.requestLeave())
        assertEquals(StandByMorphPhase.Idle, idle.phase)
    }

    @Test fun `leave cannot be requested twice`() {
        val machine = StandByMorphMachine().apply { requestEnter(); requestLeave() }
        assertFalse(machine.requestLeave())
        assertEquals(StandByMorphPhase.Leaving, machine.phase)
    }

    @Test fun `leave complete only advances from leaving, then the machine is idle again`() {
        val machine = StandByMorphMachine()
        assertFalse(machine.leaveComplete())
        machine.requestEnter(); machine.requestLeave()
        assertTrue(machine.leaveComplete())
        assertEquals(StandByMorphPhase.Idle, machine.phase)
        assertFalse(machine.leaveComplete())
        // A full round trip works again from Idle.
        assertTrue(machine.requestEnter())
    }

    @Test fun `reset always returns to idle`() {
        val machine = StandByMorphMachine().apply { requestEnter(); requestLeave() }
        machine.reset()
        assertEquals(StandByMorphPhase.Idle, machine.phase)
    }

    // ---- the angle -> frost mapping for the Tent approach ----

    @Test fun `angle frost is 0 at or above 120 degrees, still clearly open`() {
        assertEquals(0f, StandByMorph.angleFrost(120f)!!, 0f)
        assertEquals(0f, StandByMorph.angleFrost(150f)!!, 0f)
        assertEquals(0f, StandByMorph.angleFrost(180f)!!, 0f)
    }

    @Test fun `angle frost is 1 at or below 90 degrees, the tent hinge step`() {
        assertEquals(1f, StandByMorph.angleFrost(90f)!!, 0f)
        assertEquals(1f, StandByMorph.angleFrost(45f)!!, 0f)
        assertEquals(1f, StandByMorph.angleFrost(0f)!!, 0f)
    }

    @Test fun `angle frost is linear and monotonic between 120 and 90 degrees`() {
        assertEquals(0.5f, StandByMorph.angleFrost(105f)!!, 1e-4f)
        // As the hinge angle rises from 90 to 120 (opening back up), the frost must never rise.
        var prev = StandByMorph.angleFrost(90f)!!
        for (tenth in 901..1200) {
            val f = StandByMorph.angleFrost(tenth / 10f)!!
            assertTrue("frost at ${tenth / 10f} ($f) must be <= previous ($prev)", f <= prev)
            prev = f
        }
    }

    @Test fun `angle frost is null for NaN, the caller falls back to time`() {
        assertEquals(null, StandByMorph.angleFrost(Float.NaN))
    }

    @Test fun `enter frost progress prefers a confident angle over time`() {
        // A confident angle mid-approach: read directly from angleFrost, elapsed time ignored.
        assertEquals(StandByMorph.angleFrost(105f)!!, StandByMorph.enterFrostProgress(0L, 105f, 350), 0f)
        assertEquals(StandByMorph.angleFrost(105f)!!, StandByMorph.enterFrostProgress(10_000L, 105f, 350), 0f)
    }

    @Test fun `enter frost progress falls back to the timed ramp without a confident angle`() {
        assertEquals(0f, StandByMorph.enterFrostProgress(0L, Float.NaN, 350), 0f)
        assertEquals(0.5f, StandByMorph.enterFrostProgress(175L, Float.NaN, 350), 1e-4f)
        assertEquals(1f, StandByMorph.enterFrostProgress(350L, Float.NaN, 350), 0f)
        assertEquals(1f, StandByMorph.enterFrostProgress(9_999L, Float.NaN, 350), 0f) // clamped, never overshoots
    }

    @Test fun `time frost clamps and never divides by a non positive duration`() {
        assertEquals(0f, StandByMorph.timeFrost(-10L, 350), 0f)
        assertEquals(1f, StandByMorph.timeFrost(1_000L, 0))
    }

    // ---- reduce motion fallback ----

    @Test fun `reduce motion is exactly a zero animator scale`() {
        assertTrue(StandByMorph.reduceMotion(0f))
        assertFalse(StandByMorph.reduceMotion(1f))
        assertFalse(StandByMorph.reduceMotion(0.5f))
    }

    @Test fun `reduce motion collapses every phase to the same flat crossfade`() {
        assertEquals(StandByMorph.REDUCED_MOTION_MS, StandByMorph.enterDurationMs(true))
        assertEquals(StandByMorph.REDUCED_MOTION_MS, StandByMorph.leaveFrostDurationMs(true))
        assertEquals(StandByMorph.REDUCED_MOTION_MS, StandByMorph.leaveClearDurationMs(true))
        assertEquals(StandByMorph.ENTER_FROST_MS, StandByMorph.enterDurationMs(false))
        assertEquals(StandByMorph.LEAVE_FROST_MS, StandByMorph.leaveFrostDurationMs(false))
        assertEquals(StandByMorph.LEAVE_CLEAR_MS, StandByMorph.leaveClearDurationMs(false))
    }

    // ---- StandByMorphController wiring ----

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

    @Test fun `playEnter clears the frost while the reveal springs in, and marks the machine showing`() {
        val controller = StandByMorphController()
        runBlocking(TestFrameClock()) { controller.playEnter(reduceMotion = false, startFrosted = false) }
        assertEquals(StandByMorphPhase.Showing, controller.machine.phase)
        assertEquals(0f, controller.frost.value, 0f)
        assertEquals(1f, controller.reveal.value, 0f)
    }

    @Test fun `playEnter with startFrosted skips the frost-in ramp`() {
        val controller = StandByMorphController()
        var frostAtStart = -1f
        runBlocking(TestFrameClock()) {
            val play = launch { controller.playEnter(reduceMotion = false, startFrosted = true) }
            while (controller.frost.value < 1f) yield()
            frostAtStart = controller.frost.value
            play.join()
        }
        assertEquals(1f, frostAtStart, 0f) // snapped, not ramped from 0
        assertEquals(0f, controller.frost.value, 0f) // then cleared like the timed path
    }

    @Test fun `playLeave frosts up while the face fades, and marks the machine idle`() {
        val controller = StandByMorphController()
        runBlocking(TestFrameClock()) { controller.playEnter(reduceMotion = false, startFrosted = true) }
        runBlocking(TestFrameClock()) { controller.playLeave(reduceMotion = false) }
        assertEquals(StandByMorphPhase.Idle, controller.machine.phase)
        assertEquals(1f, controller.frost.value, 0f)
        assertEquals(0f, controller.reveal.value, 0f)
    }

    @Test fun `playLeave is a no-op before anything entered`() {
        val controller = StandByMorphController()
        runBlocking(TestFrameClock()) { controller.playLeave(reduceMotion = false) }
        assertEquals(StandByMorphPhase.Idle, controller.machine.phase)
        assertEquals(0f, controller.frost.value, 0f)
    }

    @Test fun `reduce motion still reaches the same rest values, just in flat REDUCED_MOTION_MS steps`() {
        val controller = StandByMorphController()
        val clock = TestFrameClock(frameMs = 8L)
        var msAtShowing = -1L
        runBlocking(clock) {
            // Not pre-frosted: the frost still rises before it clears (two sequential steps),
            // each capped at StandByMorph.REDUCED_MOTION_MS instead of the full curve/spring.
            val play = launch { controller.playEnter(reduceMotion = true, startFrosted = false) }
            while (controller.machine.phase != StandByMorphPhase.Showing) yield()
            msAtShowing = clock.nowMs
            play.cancelAndJoin()
        }
        assertTrue("reduce motion must finish within two flat steps, took ${msAtShowing}ms",
            msAtShowing <= 2 * StandByMorph.REDUCED_MOTION_MS + 100L)
        assertEquals(0f, controller.frost.value, 0f)
        assertEquals(1f, controller.reveal.value, 0f)
    }

    @Test fun `reduce motion pre-frosted enter reaches showing within one flat step`() {
        val controller = StandByMorphController()
        val clock = TestFrameClock(frameMs = 8L)
        var msAtShowing = -1L
        runBlocking(clock) {
            val play = launch { controller.playEnter(reduceMotion = true, startFrosted = true) }
            while (controller.machine.phase != StandByMorphPhase.Showing) yield()
            msAtShowing = clock.nowMs
            play.cancelAndJoin()
        }
        assertTrue("pre-frosted reduce motion must finish within its own budget, took ${msAtShowing}ms",
            msAtShowing <= StandByMorph.REDUCED_MOTION_MS + 32L)
    }

    @Test fun launcherAngleFrost_isZeroUnlessArmedOnTheCover() {
        // a closed phone at rest: angle 0 on the cover, StandBy not about to show
        org.junit.Assert.assertEquals(0f, StandByMorph.launcherAngleFrost(armed = false, onCover = true, angleDeg = 0f), 0f)
        org.junit.Assert.assertEquals(0f, StandByMorph.launcherAngleFrost(armed = true, onCover = false, angleDeg = 0f), 0f)
        org.junit.Assert.assertEquals(1f, StandByMorph.launcherAngleFrost(armed = true, onCover = true, angleDeg = 80f), 0f)
        org.junit.Assert.assertEquals(0f, StandByMorph.launcherAngleFrost(armed = true, onCover = true, angleDeg = 130f), 0f)
    }
}
