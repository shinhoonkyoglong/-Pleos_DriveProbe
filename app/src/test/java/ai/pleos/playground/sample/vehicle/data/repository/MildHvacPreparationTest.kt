package ai.pleos.playground.sample.vehicle.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MildHvacPreparationTest {
    @Test fun `power off is verified before reading and disabling AUTO`() = runTest {
        val events = mutableListOf<String>()
        prepareManualHvac({ events += "read power"; false }, { events += "power ON verified" },
            { events += "read auto"; true }, { events += "auto OFF verified" })
        assertEquals(listOf("read power", "power ON verified", "read auto", "auto OFF verified"), events)
    }
    @Test fun `manual enabled HVAC sends no redundant power or AUTO setters`() = runTest {
        prepareManualHvac({ true }, { error("already on") }, { false }, { error("already manual") })
    }
    @Test fun `power ON may enable AUTO so AUTO is read afterwards`() = runTest {
        var auto = false; var disabled = false
        prepareManualHvac({ false }, { auto = true }, { auto }, { disabled = true })
        assertTrue(disabled)
    }
    @Test fun `failed power readback prevents AUTO changes`() = runTest {
        try {
            prepareManualHvac({ false }, { error("readback mismatch") }, { error("must not read") }, { error("must not write") })
            fail("must fail")
        } catch (e: IllegalStateException) { assertEquals("readback mismatch", e.message) }
    }
    @Test fun `AUTO failure is propagated instead of claiming manual mode`() = runTest {
        try {
            prepareManualHvac({ true }, {}, { true }, { error("AUTO readback mismatch") })
            fail("must fail")
        } catch (e: IllegalStateException) { assertEquals("AUTO readback mismatch", e.message) }
    }
    @Test fun `cancellation stops the preparation without followup commands`() = runTest {
        try {
            prepareManualHvac({ false }, { throw CancellationException("stop") }, { error("must not read") }, { error("must not write") })
            fail("must cancel")
        } catch (e: CancellationException) { assertEquals("stop", e.message) }
    }
}
