package cz.pflanzer.foldduo.pose

import cz.pflanzer.foldduo.pose.HingeMotionDetectors.Fired
import org.junit.Assert.assertEquals
import org.junit.Test

class HingeMotionDetectorsTest {
    private fun detectors() = HingeMotionDetectors(
        opening = OpeningMotionDetector(thresholdRadS = 5.5f, consecutiveSamples = 3),
        closing = OpeningMotionDetector(thresholdRadS = 5.5f, consecutiveSamples = 3))

    @Test fun `fast rotation on the cover fires the opening detector only`() {
        val d = detectors()
        assertEquals(listOf(Fired.None, Fired.None, Fired.Opening), listOf(6f, 6f, 6f).map { d.feed(Panel.Cover, it) })
        assertEquals(1, d.opening.seq)
        assertEquals(0, d.closing.seq)
        assertEquals(6f, d.lastRateRadS, 0f)
    }

    @Test fun `fast rotation on the inner panel fires the closing detector only`() {
        val d = detectors()
        assertEquals(listOf(Fired.None, Fired.None, Fired.Closing), listOf(-6f, -7f, -6f).map { d.feed(Panel.Inner, it) })
        assertEquals(0, d.opening.seq)
        assertEquals(1, d.closing.seq)
        assertEquals(-6f, d.lastRateRadS, 0f)
    }

    @Test fun `a run does not straddle a panel swap`() {
        val d = detectors()
        d.feed(Panel.Cover, 6f); d.feed(Panel.Cover, 6f)
        // The third fast sample lands on the inner panel: the opening run is forgotten and the closing run starts at 1.
        assertEquals(Fired.None, d.feed(Panel.Inner, 6f))
        assertEquals(Fired.None, d.feed(Panel.Inner, 6f))
        assertEquals(Fired.Closing, d.feed(Panel.Inner, 6f))
        assertEquals(0, d.opening.seq)
        assertEquals(1, d.closing.seq)
        // Back on the cover after the swing: nothing until a fresh run of three.
        assertEquals(Fired.None, d.feed(Panel.Cover, 6f))
        assertEquals(Fired.None, d.feed(Panel.Cover, 6f))
        assertEquals(Fired.Opening, d.feed(Panel.Cover, 6f))
    }

    @Test fun `an unknown panel feeds neither detector`() {
        val d = detectors()
        repeat(5) { assertEquals(Fired.None, d.feed(Panel.Unknown, 9f)) }
        assertEquals(0, d.opening.seq)
        assertEquals(0, d.closing.seq)
        assertEquals(9f, d.lastRateRadS, 0f)
    }

    @Test fun `handling below the threshold never fires, the measured swings do`() {
        val d = detectors()
        // Measured on the Fold 8: per-second handling peaks of 1.4-4.5 rad/s.
        listOf(1.4f, 3.2f, 4.5f, 4.52f, 4.09f, 2.86f, 0.5f).forEach { assertEquals(Fired.None, d.feed(Panel.Cover, it)) }
        // A flip-over burst crosses 5.5 for two samples only: still nothing at three consecutive.
        listOf(6.05f, 7.27f, 4.0f).forEach { assertEquals(Fired.None, d.feed(Panel.Cover, it)) }
        assertEquals(0, d.opening.seq)
        // A sustained swing (three samples at or above 5.5) fires once.
        assertEquals(listOf(Fired.None, Fired.None, Fired.Opening, Fired.None),
            listOf(5.63f, 8.67f, 6.99f, 6.1f).map { d.feed(Panel.Cover, it) })
        assertEquals(1, d.opening.seq)
    }

    @Test fun `reset clears both detectors and the last rate`() {
        val d = detectors()
        d.feed(Panel.Inner, 6f); d.feed(Panel.Inner, 6f)
        d.reset()
        assertEquals(0f, d.lastRateRadS, 0f)
        assertEquals(Fired.None, d.feed(Panel.Inner, 6f))
        assertEquals(Fired.None, d.feed(Panel.Inner, 6f))
        assertEquals(Fired.Closing, d.feed(Panel.Inner, 6f))
    }
}
