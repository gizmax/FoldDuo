package cz.pflanzer.foldduo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B21, "Reduce motion": [ReduceMotionLogic.decide]'s pure table over the three signals
 * [MotionPrefs] reads (`Settings.Global.TRANSITION_ANIMATION_SCALE`, `ANIMATOR_DURATION_SCALE`,
 * and the One UI accessibility key) — any one of the three being "off" turns reduce motion on.
 */
class ReduceMotionLogicTest {

    @Test fun `all normal scales, accessibility flag off - not reduced`() {
        assertFalse(ReduceMotionLogic.decide(transitionScale = 1f, animatorScale = 1f, accessibilityFlag = false))
    }

    @Test fun `transition scale zero alone turns reduce motion on`() {
        assertTrue(ReduceMotionLogic.decide(transitionScale = 0f, animatorScale = 1f, accessibilityFlag = false))
    }

    @Test fun `animator duration scale zero alone turns reduce motion on`() {
        assertTrue(ReduceMotionLogic.decide(transitionScale = 1f, animatorScale = 0f, accessibilityFlag = false))
    }

    @Test fun `accessibility flag alone turns reduce motion on, scales otherwise normal`() {
        assertTrue(ReduceMotionLogic.decide(transitionScale = 1f, animatorScale = 1f, accessibilityFlag = true))
    }

    @Test fun `all three off is still just reduced, not double-counted`() {
        assertTrue(ReduceMotionLogic.decide(transitionScale = 0f, animatorScale = 0f, accessibilityFlag = true))
    }

    @Test fun `a slowed-down but nonzero scale (0_5x, the Fold 8's own setup) is not reduce motion`() {
        assertFalse(ReduceMotionLogic.decide(transitionScale = 0.5f, animatorScale = 0.5f, accessibilityFlag = false))
    }

    @Test fun `a negative scale (unexpected, but should not crash the caller) counts as off too`() {
        assertTrue(ReduceMotionLogic.decide(transitionScale = -1f, animatorScale = 1f, accessibilityFlag = false))
    }
}
