package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InterventionControllerTest {
    private val mild = ConditionState(FatigueLevel.MILD, 0.35f, false, lastWindowValid = true)
    private fun success() = InterventionResult(InterventionOutcome.VERIFIED, "verified")

    @Test fun `invalid data and non forward gear never actuate`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val c = InterventionController(scope, { 0 }, { calls++; success() }, {})
        c.update(mild.copy(lastWindowValid = false), true, true, false)
        c.update(mild, true, false, false)
        c.update(mild, false, true, false)
        assertEquals(0, calls)
        scope.cancel()
    }

    @Test fun `100 state ticks actuate once and save original score`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(scope, { 0 }, { calls++; success() }, { records += it })
        repeat(100) { c.update(mild.copy(timeMs = it.toLong()), true, true, false) }
        assertEquals(1, calls)
        assertEquals(1, records.size)
        assertEquals(0.35f, records.single().condition.score)
        assertFalse(c.status.value.running)
        scope.cancel()
    }

    @Test fun `replay and manual simulation never execute real actions`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(scope, { 0 }, { error("MUST NOT invoke hardware or voice") }, { records += it })
        c.update(mild, true, true, true)
        c.simulate(FatigueLevel.DANGER)
        assertEquals(2, records.size)
        assertTrue(records.all { it.simulation && it.result.outcome == InterventionOutcome.SIMULATED })
        assertNotEquals(records[0].sessionId, records[1].sessionId)
        scope.cancel()
    }

    @Test fun `failed action has cooldown too and a new trip can retry`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(scope, { 0 }, { calls++; error("SDK offline") }, { records += it })
        repeat(20) { c.update(mild, true, true, false) }
        assertEquals(1, calls)
        assertEquals(InterventionOutcome.FAILED, records[0].result.outcome)
        c.update(mild, false, false, false)
        c.update(mild, true, true, false)
        assertEquals(2, calls)
        assertNotEquals(records[0].sessionId, records[1].sessionId)
        scope.cancel()
    }

    @Test fun `ending trip cancels pending voice and records cancellation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(scope, { 0 }, { awaitCancellation() }, { records += it })
        c.update(mild, true, true, false)
        assertTrue(c.status.value.running)
        c.update(mild, false, false, false)
        assertFalse(c.status.value.running)
        assertEquals(InterventionOutcome.CANCELLED, records.single().result.outcome)
        scope.cancel()
    }

    @Test fun `higher level interrupts old action without waiting for cooldown`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<FatigueLevel>()
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(scope, { 0 }, { level ->
            calls += level
            if (level == FatigueLevel.MILD) awaitCancellation() else success()
        }, { records += it })
        c.update(mild, true, true, false)
        c.update(mild.copy(level = FatigueLevel.DANGER), true, true, false)
        assertEquals(listOf(FatigueLevel.MILD, FatigueLevel.DANGER), calls)
        assertEquals(listOf(InterventionOutcome.CANCELLED, InterventionOutcome.VERIFIED), records.map { it.result.outcome })
        scope.cancel()
    }

    @Test fun `database failure is visible and does not repeat physical action`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val c = InterventionController(scope, { 0 }, { calls++; success() }, { error("disk full") })
        c.update(mild, true, true, false)
        c.update(mild, true, true, false)
        assertEquals(1, calls)
        assertTrue(c.status.value.historyError!!.contains("disk full"))
        scope.cancel()
    }

    @Test fun `disabled controller never executes`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = InterventionController(scope, { 0 }, { error("disabled") }, {})
        c.setEnabled(false)
        c.update(mild, true, true, false)
        assertFalse(c.status.value.running)
        scope.cancel()
    }

    @Test fun `report trip ID is used exactly and changing trip resets cooldown`() = runTest {
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(this, { 0 }, { success() }, { records += it })
        c.update(mild, true, true, false, reportTripId = "restored-trip")
        runCurrent()
        c.update(mild, true, true, false, reportTripId = "next-trip")
        runCurrent()
        assertEquals(listOf("restored-trip", "next-trip"), records.map { it.sessionId })
    }

    @Test fun `trip settlement waits for late cancellation history even after reset`() = runTest {
        val allowSave = CompletableDeferred<Unit>()
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(this, { 0 }, { awaitCancellation() }, {
            allowSave.await()
            records += it
        })
        c.update(mild, true, true, false, reportTripId = "trip")
        runCurrent()
        c.reset() // The gear subscriber can reset before the recorder sees P.
        val settlement = launch { c.finishSession("trip") }
        runCurrent()
        assertFalse(settlement.isCompleted)
        assertTrue(records.isEmpty())
        allowSave.complete(Unit)
        settlement.join()
        assertEquals("trip", records.single().sessionId)
        assertEquals(InterventionOutcome.CANCELLED, records.single().result.outcome)
    }

    @Test fun `settlement surfaces a failed insert even after controller reset`() = runTest {
        val c = InterventionController(this, { 0 }, { success() }, { error("disk full") })
        c.update(mild, true, true, false, reportTripId = "trip")
        runCurrent()
        c.reset()
        val failure = runCatching { c.finishSession("trip") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure!!.cause!!.message)
    }
}
