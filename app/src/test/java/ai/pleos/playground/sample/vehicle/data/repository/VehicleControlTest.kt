package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VehicleControlTest {
    @Test fun `percentage conversion respects actual device range`() {
        assertEquals(2, ControlMath.windowPosition(15, 0, 10))
        assertEquals(15, ControlMath.windowPosition(15, 0, 100))
        assertEquals(5, ControlMath.windowPosition(0, 5, 25))
        assertEquals(25, ControlMath.windowPosition(100, 5, 25))
    }

    @Test(expected = IllegalArgumentException::class) fun `invalid percentage is rejected`() {
        ControlMath.windowPosition(101, 0, 10)
    }

    @Test(expected = IllegalArgumentException::class) fun `NaN temperature is rejected`() {
        ControlMath.requireRange(Float.NaN, 15f, 30f)
    }

    @Test fun `SDK missing must fail instead of mock success`() = runBlocking {
        val client = VehicleControlClient { null }
        assertTrue(client.temperature(20f, TemperatureUnit.CELSIUS, SeatValue.ROW_1_LEFT).isFailure)
        assertTrue(client.mild().isFailure)
    }

    @Test fun `first callback wins even if SDK completes twice`() = runBlocking {
        val actual = request<Int>("duplicate") { ok, fail ->
            ok(3)
            ok(4)
            fail(IllegalStateException("late failure"))
        }
        assertEquals(3, actual)
    }

    @Test fun `null completion cannot masquerade as applied request`() = runBlocking {
        val r = controlResult { request<Int>("null") { ok, _ -> ok(null) } }
        assertTrue(r.isFailure)
    }

    @Test(expected = CancellationException::class) fun `cancellation is never converted to failure`() = runBlocking<Unit> {
        controlResult { throw CancellationException("trip ended") }
        Unit
    }
}
