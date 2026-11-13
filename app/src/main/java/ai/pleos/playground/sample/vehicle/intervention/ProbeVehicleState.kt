package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.data.model.GearPosition

/** Diagnostics use real repository telemetry; replay state is never evidence of motion. */
internal data class ProbeVehicleState(
    val gear: GearPosition,
    val speedKmh: Float?,
    val speedAgeMs: Long,
    val emulator: Boolean,
) {
    fun requireReady(moving: Boolean) {
        val speed = speedKmh
        require(speed != null && speed.isFinite() && speed >= 0f && speedAgeMs in 0..2_000) {
            "A fresh real speed sample is required: $this"
        }
        if (moving) {
            require(emulator && gear == GearPosition.DRIVE && speed > 0f) { "Driving probe requires an emulator, D gear and measured speed > 0: $this" }
        } else require(gear == GearPosition.PARK && speed == 0f) { "Parked probe requires PARK and measured zero speed: $this" }
    }
}
