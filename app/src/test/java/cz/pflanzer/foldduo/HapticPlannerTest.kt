package cz.pflanzer.foldduo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B36 "Haptický jazyk": [HapticPlanner.plan]'s pure decision table — which [HapticPlan] (if any)
 * a device offering [HapticTier] X should play for [HapticEvent] Y at [HapticIntensity] Z, with
 * "reduce motion" able to silence everything except [HapticEvent.FLAT] and the StandBy pair. No
 * Android types involved, so this runs as a plain JVM test.
 */
class HapticPlannerTest {

    @Test fun `every event has a plan on the richest tier at normal intensity, no reduce motion`() {
        HapticEvent.entries.forEach { event ->
            val plan = HapticPlanner.plan(event, HapticIntensity.NORMAL, HapticTier.PRIMITIVES, reduceMotion = false)
            assertNotNull("expected a plan for $event", plan)
            assertTrue("expected primitive steps for $event", (plan as HapticPlan.Primitives).steps.isNotEmpty())
        }
    }

    @Test fun `off intensity mutes every event on every tier`() {
        HapticEvent.entries.forEach { event ->
            HapticTier.entries.forEach { tier ->
                assertNull("$event on $tier should be muted at OFF",
                    HapticPlanner.plan(event, HapticIntensity.OFF, tier, reduceMotion = false))
            }
        }
    }

    @Test fun `off intensity mutes even flat and standby, unlike reduce motion`() {
        assertNull(HapticPlanner.plan(HapticEvent.FLAT, HapticIntensity.OFF, HapticTier.PRIMITIVES, reduceMotion = false))
        assertNull(HapticPlanner.plan(HapticEvent.STANDBY_ENTER, HapticIntensity.OFF, HapticTier.PRIMITIVES, reduceMotion = false))
        assertNull(HapticPlanner.plan(HapticEvent.STANDBY_LEAVE, HapticIntensity.OFF, HapticTier.PRIMITIVES, reduceMotion = false))
    }

    @Test fun `reduce motion mutes everything except flat and standby`() {
        val survivors = setOf(HapticEvent.FLAT, HapticEvent.STANDBY_ENTER, HapticEvent.STANDBY_LEAVE)
        HapticEvent.entries.forEach { event ->
            val plan = HapticPlanner.plan(event, HapticIntensity.NORMAL, HapticTier.PRIMITIVES, reduceMotion = true)
            if (event in survivors) assertNotNull("expected $event to survive reduce motion", plan)
            else assertNull("expected $event to be muted by reduce motion", plan)
        }
    }

    @Test fun `primitives tier scales amplitude by intensity and clamps to 1`() {
        val light = (HapticPlanner.plan(HapticEvent.ICON_DROP, HapticIntensity.LIGHT, HapticTier.PRIMITIVES, false) as HapticPlan.Primitives).steps.single()
        val normal = (HapticPlanner.plan(HapticEvent.ICON_DROP, HapticIntensity.NORMAL, HapticTier.PRIMITIVES, false) as HapticPlan.Primitives).steps.single()
        val strong = (HapticPlanner.plan(HapticEvent.ICON_DROP, HapticIntensity.STRONG, HapticTier.PRIMITIVES, false) as HapticPlan.Primitives).steps.single()
        // Base amplitude for ICON_DROP (THUD) is 0.8: .6x -> .48, 1x -> .8, 1.3x -> 1.04 clamped to 1.
        assertEquals(0.48f, light.amplitude, 1e-4f)
        assertEquals(0.8f, normal.amplitude, 1e-4f)
        assertEquals(1f, strong.amplitude, 1e-4f)
        assertEquals(HapticPrimitive.THUD, normal.primitive)
    }

    @Test fun `the explicit B36 assignments from IDEAS md are honoured`() {
        fun step(event: HapticEvent) = (HapticPlanner.plan(event, HapticIntensity.NORMAL, HapticTier.PRIMITIVES, false) as HapticPlan.Primitives).steps
        assertEquals(listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.5f)), step(HapticEvent.PAGE_SETTLE))
        assertEquals(listOf(HapticStep(HapticPrimitive.QUICK_RISE, 0.6f)), step(HapticEvent.ICON_PICKUP))
        assertEquals(listOf(HapticStep(HapticPrimitive.THUD, 0.8f)), step(HapticEvent.ICON_DROP))
        assertEquals(listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.5f), HapticStep(HapticPrimitive.TICK, 0.4f, delayMs = 30)), step(HapticEvent.FOLDER_OPEN))
        assertEquals(listOf(HapticStep(HapticPrimitive.SPIN, 0.6f)), step(HapticEvent.STACK_FLIP))
        assertEquals(listOf(HapticStep(HapticPrimitive.SLOW_RISE, 0.7f)), step(HapticEvent.STANDBY_ENTER))
        assertEquals(listOf(HapticStep(HapticPrimitive.QUICK_FALL, 0.5f)), step(HapticEvent.STANDBY_LEAVE))
    }

    @Test fun `predefined tier ignores amplitude and returns the event's fallback effect`() {
        val plan = HapticPlanner.plan(HapticEvent.ICON_DROP, HapticIntensity.LIGHT, HapticTier.PREDEFINED, false)
        assertEquals(HapticPlan.Predefined(HapticPredefined.HEAVY_CLICK), plan)
    }

    @Test fun `constants tier returns the event's fallback constant regardless of intensity`() {
        val light = HapticPlanner.plan(HapticEvent.PAGE_SETTLE, HapticIntensity.LIGHT, HapticTier.CONSTANTS, false)
        val strong = HapticPlanner.plan(HapticEvent.PAGE_SETTLE, HapticIntensity.STRONG, HapticTier.CONSTANTS, false)
        assertEquals(HapticPlan.Constant(HapticConstant.CLOCK_TICK), light)
        assertEquals(light, strong)
    }
}
