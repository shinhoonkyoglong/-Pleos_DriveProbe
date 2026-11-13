package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel

/** Cooldown is per action, including failures; level oscillation must not spam controls. */
class InterventionPolicy(private val cooldownMs: Long = 5 * 60_000L) {
    private val attempted = mutableMapOf<FatigueLevel, Long>()

    fun take(level: FatigueLevel, valid: Boolean, forward: Boolean, nowMs: Long): Boolean {
        if (!valid || !forward || level == FatigueLevel.NORMAL) return false
        val last = attempted[level]
        if (last != null && nowMs - last < cooldownMs) return false
        attempted[level] = nowMs
        return true
    }

    fun reset() {
        attempted.clear()
    }
}
