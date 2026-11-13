package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterventionCleanupTest {
    private val caution = ConditionState(FatigueLevel.CAUTION, .55f, false, lastWindowValid = true)
    private val danger = caution.copy(level = FatigueLevel.DANGER, score = .75f)

    @Test fun `danger waits for cancelled caution microphone cleanup before executing`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<FatigueLevel>()
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(this, { 0 }, { level ->
            calls += level
            if (level == FatigueLevel.CAUTION) try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            InterventionResult(InterventionOutcome.ACCEPTED, "consented")
        }, { records += it })
        c.update(caution, true, true, false); runCurrent()
        c.update(danger, true, true, false); runCurrent()
        assertEquals(listOf(FatigueLevel.CAUTION), calls)
        assertTrue(c.isBusy); assertTrue(c.status.value.running)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf(FatigueLevel.CAUTION, FatigueLevel.DANGER), calls)
        assertEquals(listOf(InterventionOutcome.CANCELLED, InterventionOutcome.ACCEPTED), records.map { it.result.outcome })
        assertFalse(c.isBusy)
    }

    @Test fun `new trip cannot bypass cleanup even after queued danger is cancelled`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<FatigueLevel>()
        val c = InterventionController(this, { 0 }, { level ->
            calls += level
            if (calls.size == 1) try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            InterventionResult(InterventionOutcome.ANSWERED, "done")
        }, {})
        c.update(caution, true, true, false); runCurrent()
        c.update(danger, true, true, false); runCurrent()
        c.reset(); c.update(danger, true, true, false); runCurrent()
        assertEquals(1, calls.size); assertTrue(c.isBusy)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf(FatigueLevel.CAUTION, FatigueLevel.DANGER), calls)
        assertFalse(c.isBusy)
    }

    @Test fun `disabling blocks manual work until cleanup completes`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(this, { 0 }, {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }, { records += it })
        c.update(caution, true, true, false); runCurrent()
        c.setEnabled(false); runCurrent()
        assertTrue(c.isBusy)
        cleanup.complete(Unit); runCurrent()
        assertFalse(c.isBusy)
        assertEquals(InterventionOutcome.CANCELLED, records.single().result.outcome)
    }

    @Test fun `preemption during saving preserves one completed result and then starts danger`() = runTest {
        val saving = CompletableDeferred<Unit>()
        val records = mutableListOf<InterventionRecord>()
        val calls = mutableListOf<FatigueLevel>()
        val c = InterventionController(this, { 0 }, { level -> calls += level; InterventionResult(InterventionOutcome.ANSWERED, "done") }, {
            records += it
            if (it.condition.level == FatigueLevel.CAUTION) saving.await()
        })
        c.update(caution, true, true, false); runCurrent()
        c.update(danger, true, true, false); runCurrent()
        assertEquals(listOf(FatigueLevel.CAUTION), calls)
        saving.complete(Unit); runCurrent()
        assertEquals(2, records.size)
        assertEquals(listOf(InterventionOutcome.ANSWERED, InterventionOutcome.ANSWERED), records.map { it.result.outcome })
        assertFalse(c.isBusy)
    }
}
