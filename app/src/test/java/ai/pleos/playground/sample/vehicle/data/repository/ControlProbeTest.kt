package ai.pleos.playground.sample.vehicle.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ControlProbeTest {
    @Test fun `changes and restores original value`() = runBlocking {
        var actual = 3
        val writes = mutableListOf<Int>()
        val report = controlProbe("fan", { actual }, { it + 1 }, { writes += it; actual = it; Result.success(it) })
        assertEquals(listOf(4, 3), writes)
        assertEquals(3, actual)
        assertTrue(report.contains("readback=4 restored=3"))
    }

    @Test fun `write failure still restores because write may have applied`() = runBlocking {
        val writes = mutableListOf<Int>()
        val r = controlResult { controlProbe("fan", { 3 }, { 4 }, {
            writes += it
            if (it == 4) Result.failure(IllegalStateException("readback mismatch")) else Result.success(it)
        }) }
        assertTrue(r.isFailure)
        assertEquals(listOf(4, 3), writes)
    }

    @Test fun `cancelled operation still attempts restore and propagates cancellation`() = runBlocking {
        val writes = mutableListOf<Int>()
        try {
            controlProbe("window", { 0 }, { 1 }, {
                writes += it
                if (it == 1) throw CancellationException("state changed")
                Result.success(it)
            })
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(1, 0), writes)
    }

    @Test fun `restore failure retains original failure and is never reported as success`() = runBlocking {
        val r = controlResult { controlProbe("fan", { 3 }, { 4 }, {
            Result.failure<Int>(IllegalStateException("write $it failed"))
        }) }
        val e = r.exceptionOrNull()!!
        assertTrue(e.message!!.contains("restore FAILED"))
        assertEquals("write 4 failed", (e as ProbeRestoreException).operationFailure!!.message)
    }

    @Test fun `missing state before read prevents all writes`() = runBlocking {
        var writes = 0
        val r = controlResult { controlProbe("fan", { 3 }, { 4 }, { writes++; Result.success(it) }, { error("no fresh speed") }) }
        assertTrue(r.isFailure)
        assertEquals(0, writes)
    }
}
