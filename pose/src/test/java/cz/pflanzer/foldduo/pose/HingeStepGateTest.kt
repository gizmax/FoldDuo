package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeStepGateTest {
    @Test fun flatStepPinsToFlat_whateverTheMagnetSays() {
        assertEquals(180f, HingeStepGate.apply(180f, 85.4f), 0f)
        assertEquals(180f, HingeStepGate.apply(180f, 179.2f), 0f)
    }

    @Test fun closedStepPinsToClosed() {
        assertEquals(0f, HingeStepGate.apply(0f, 40f), 0f)
    }

    @Test fun midStepInterpolatesWithinTheBand() {
        assertEquals(110f, HingeStepGate.apply(90f, 110f), 0f)
        assertEquals(HingeStepGate.MIN_MID, HingeStepGate.apply(90f, 3f), 0f)
        assertEquals(HingeStepGate.MAX_MID, HingeStepGate.apply(90f, 179f), 0f)
    }

    @Test fun unknownStepOrEstimatePassThrough() {
        assertEquals(77f, HingeStepGate.apply(Float.NaN, 77f), 0f)
        assertTrue(HingeStepGate.apply(180f, Float.NaN).isNaN())
    }

    // ---- "Zavírání jako Duo" (17. 9. noc): the closing-onset bridge ----

    @Test fun closingOnsetBridgesTheFlatPinInsteadOfHardPinning180() {
        assertEquals(170f, HingeStepGate.apply(180f, Float.NaN, closingOnsetDeg = 170f), 0f)
        // The onset wins over a stale/invalid magnetometer estimate too.
        assertEquals(165f, HingeStepGate.apply(180f, 85f, closingOnsetDeg = 165f), 0f)
    }

    @Test fun closingOnsetAppliesEvenBeforeTheFirstStepSample() {
        assertEquals(172f, HingeStepGate.apply(Float.NaN, Float.NaN, closingOnsetDeg = 172f), 0f)
    }

    @Test fun closingOnsetIsClampedToASafeRange() {
        assertEquals(HingeStepGate.MIN_MID, HingeStepGate.apply(180f, Float.NaN, closingOnsetDeg = 5f), 0f)
        assertEquals(180f, HingeStepGate.apply(180f, Float.NaN, closingOnsetDeg = 200f), 0f)
    }

    @Test fun closingOnsetNeverAppliesInsideTheMidBand() {
        // Mid-band interpolation is untouched by a (theoretically impossible, but defensive)
        // onset value while the step itself already reads 90.
        assertEquals(110f, HingeStepGate.apply(90f, 110f, closingOnsetDeg = 150f), 0f)
    }

    @Test fun defaultClosingOnsetIsNaNAndChangesNothing() {
        assertEquals(180f, HingeStepGate.apply(180f, 85.4f), 0f)
        assertEquals(0f, HingeStepGate.apply(0f, 40f), 0f)
    }
}
