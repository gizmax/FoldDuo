package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningMotionDetectorTest {
    private fun fires(d: OpeningMotionDetector, samples: List<Float>): List<Boolean> = samples.map { d.feed(it) }

    @Test fun `fires on the second consecutive fast sample and not before`() {
        val d = OpeningMotionDetector(thresholdRadS = 2f, consecutiveSamples = 2)
        assertEquals(listOf(false, false, true), fires(d, listOf(0.3f, 2.5f, 2.6f)))
        assertEquals(1, d.seq)
    }

    @Test fun `a single spike between slow samples does not fire`() {
        val d = OpeningMotionDetector(thresholdRadS = 2f, consecutiveSamples = 2)
        assertEquals(listOf(false, false, false, false, false), fires(d, listOf(0f, 3f, 0.1f, 2.2f, 0f)))
        assertEquals(0, d.seq)
    }

    @Test fun `direction does not matter, the threshold is on the magnitude`() {
        val d = OpeningMotionDetector(thresholdRadS = 2f, consecutiveSamples = 2)
        assertEquals(listOf(false, true), fires(d, listOf(-2.1f, -4f)))
        assertEquals(-4f, d.lastRateRadS, 0f)
    }

    @Test fun `one swing fires once and rearms only after the rate drops`() {
        val d = OpeningMotionDetector(thresholdRadS = 2f, consecutiveSamples = 2)
        // A long swing: many fast samples, one fire.
        assertEquals(listOf(false, true, false, false, false), fires(d, listOf(2.5f, 3f, 3.5f, 3f, 2.2f)))
        assertEquals(1, d.seq)
        // Still fast: nothing. Slow sample rearms; the next run fires again.
        assertFalse(d.feed(2.4f))
        assertFalse(d.feed(0.5f))
        assertEquals(listOf(false, true), fires(d, listOf(2.5f, 2.5f)))
        assertEquals(2, d.seq)
    }

    @Test fun `exactly the threshold counts as fast`() {
        val d = OpeningMotionDetector(thresholdRadS = 2f, consecutiveSamples = 2)
        assertEquals(listOf(false, true), fires(d, listOf(2f, 2f)))
    }

    @Test fun `reset forgets a partial run but keeps the sequence`() {
        val d = OpeningMotionDetector(thresholdRadS = 2f, consecutiveSamples = 3)
        assertFalse(d.feed(3f)); assertFalse(d.feed(3f))
        d.reset()
        assertFalse(d.feed(3f)); assertFalse(d.feed(3f))
        assertTrue(d.feed(3f))
        assertEquals(1, d.seq)
    }

    @Test fun `defaults are the documented tunables`() {
        val d = OpeningMotionDetector()
        // Measured on the Fold 8: handling of the closed phone peaks at 1.4-4.5 rad/s per second
        // (6-7 while flipping it over), the fastest real swings at 5.6-8.7 rad/s.
        assertEquals(5.5f, d.thresholdRadS, 0f)
        assertEquals(3, d.consecutiveSamples)
        assertEquals(1, OpeningMotionDetector.HINGE_AXIS)
    }

    @Test fun `a two-sample burst over the default threshold does not fire, three samples do`() {
        val d = OpeningMotionDetector()
        assertEquals(listOf(false, false, false), fires(d, listOf(6.05f, 7.27f, 4.0f)))
        assertEquals(listOf(false, false, true), fires(d, listOf(5.63f, 8.67f, 6.99f)))
        assertEquals(1, d.seq)
    }
}
