package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.*
import org.junit.Assert.*
import org.junit.Test

class InterventionPolicyTest {
    @Test fun `oscillation cannot bypass per level cooldown`() {
        val p = InterventionPolicy(1_000)
        assertTrue(p.take(MILD, true, true, 0))
        assertTrue(p.take(CAUTION, true, true, 10))
        assertFalse(p.take(MILD, true, true, 20))
        assertFalse(p.take(CAUTION, true, true, 30))
        assertTrue(p.take(MILD, true, true, 1_000))
    }

    @Test fun `invalid input must not consume cooldown`() {
        val p = InterventionPolicy()
        assertFalse(p.take(MILD, false, true, 0))
        assertFalse(p.take(MILD, true, false, 0))
        assertFalse(p.take(NORMAL, true, true, 0))
        assertTrue(p.take(MILD, true, true, 0))
    }

    @Test fun `reset starts new trip cooldown`() {
        val p = InterventionPolicy()
        assertTrue(p.take(DANGER, true, true, 0))
        p.reset()
        assertTrue(p.take(DANGER, true, true, 1))
    }
}
