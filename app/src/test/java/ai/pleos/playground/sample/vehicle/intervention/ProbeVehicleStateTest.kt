package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import org.junit.Assert.*
import org.junit.Test

class ProbeVehicleStateTest {
    @Test fun `parked and moving modes require corresponding real state`() {
        ProbeVehicleState(GearPosition.PARK, 0f, 100, true).requireReady(false)
        ProbeVehicleState(GearPosition.DRIVE, 60f, 100, true).requireReady(true)
        assertTrue(runCatching { ProbeVehicleState(GearPosition.DRIVE, 0f, 100, true).requireReady(true) }.isFailure)
        assertTrue(runCatching { ProbeVehicleState(GearPosition.REVERSE, 60f, 100, true).requireReady(true) }.isFailure)
        assertTrue(runCatching { ProbeVehicleState(GearPosition.DRIVE, 60f, 100, true).requireReady(false) }.isFailure)
    }

    @Test fun `driving probe is emulator only and rejects missing or stale speed`() {
        assertTrue(runCatching { ProbeVehicleState(GearPosition.DRIVE, 60f, 100, false).requireReady(true) }.isFailure)
        for (speed in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertTrue(runCatching { ProbeVehicleState(GearPosition.DRIVE, speed, 100, true).requireReady(true) }.isFailure)
        }
        assertTrue(runCatching { ProbeVehicleState(GearPosition.DRIVE, 60f, 2_001, true).requireReady(true) }.isFailure)
    }
}
