package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DangerNavigationProbeControllerTest {
    private class Store(var value: DangerNavigationProbeState? = null) : DangerNavigationProbeStore {
        var fail = false
        override fun load() = value
        override fun save(state: DangerNavigationProbeState) { check(!fail); value = state }
    }
    private val accepted = InterventionResult(InterventionOutcome.ACCEPTED, "추가 확인", consent = VoiceConsent.ACCEPT,
        navigationStatus = "VERIFIED_WAYPOINT", navigation = RestNavigationResult(
            RestNavigationStatus.VERIFIED_WAYPOINT, "추가 확인", requestIssued = true))

    @Test fun `accepted result keeps consent distinct from verified navigation and saves once`() = runTest {
        val rows = mutableListOf<InterventionResult>(); var restored: Boolean? = null
        val store = Store()
        val c = DangerNavigationProbeController(this, {}, { true }, { restored = it },
            { phase -> phase(VoicePhase.WAITING); accepted }, { _, result -> rows += result; 8L }, store)
        assertTrue(c.start()); runCurrent()
        assertEquals(listOf(accepted), rows); assertEquals(true, restored)
        assertEquals(VoiceConsent.ACCEPT, c.state.value.consent)
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT, c.state.value.navigationStatus)
        assertTrue(c.state.value.requestIssued); assertEquals(8L, c.state.value.savedId)
        assertEquals(c.state.value, store.value); assertFalse(c.isBusy)
    }

    @Test fun `declined timeout and unavailable outcomes are preserved without a request`() = runTest {
        for (outcome in listOf(InterventionOutcome.DECLINED, InterventionOutcome.NO_RESPONSE, InterventionOutcome.UNAVAILABLE)) {
            val answer = InterventionResult(outcome, "실제 요청 없음", navigation = RestNavigationResult(RestNavigationStatus.NOT_REQUESTED, "없음"))
            val c = DangerNavigationProbeController(this, {}, { false }, {}, { answer }, { _, _ -> 1L }, Store())
            assertTrue(c.start()); runCurrent()
            assertEquals(outcome, c.state.value.outcome); assertFalse(c.state.value.requestIssued)
        }
    }

    @Test fun `missing parked or voice readiness never reserves or executes`() = runTest {
        var began = false
        val c = DangerNavigationProbeController(this, { error("음성 준비 필요") }, { began = true; false }, {},
            { error("must not execute") }, { _, _ -> error("must not save") }, Store())
        assertFalse(c.start()); assertFalse(began); assertFalse(c.isBusy)
        assertTrue(c.state.value.message.contains("음성 준비"))
    }

    @Test fun `another intervention owning resources rejects without execution`() = runTest {
        val c = DangerNavigationProbeController(this, {}, { error("다른 시험 중") }, { error("not reserved") },
            { error("must not execute") }, { _, _ -> 1L }, Store())
        assertFalse(c.start()); assertFalse(c.isBusy)
    }

    @Test fun `immediate cancellation still saves once and restores the automatic setting`() = runTest {
        var saved = 0; var finished = 0
        val c = DangerNavigationProbeController(this, {}, { false }, { assertFalse(it); finished++ },
            { awaitCancellation() }, { _, result -> saved++; assertEquals(InterventionOutcome.CANCELLED, result.outcome); 1L }, Store())
        assertTrue(c.start()); assertFalse(c.start()); c.cancel(); runCurrent()
        assertEquals(1, saved); assertEquals(1, finished); assertFalse(c.isBusy)
        assertTrue(c.state.value.interrupted)
    }

    @Test fun `post dispatch cancellation preserves consent and uncertain actual request`() = runTest {
        val cancelled = accepted.copy(outcome = InterventionOutcome.CANCELLED,
            navigation = RestNavigationResult(RestNavigationStatus.CANCELLED_AFTER_REQUEST_UNCONFIRMED, "요청 후 취소", requestIssued = true))
        val c = DangerNavigationProbeController(this, {}, { true }, {}, {
            try { awaitCancellation() } catch (_: CancellationException) { throw InterventionCancelled(cancelled) }
        }, { _, result -> assertEquals(cancelled, result); 1L }, Store())
        c.start(); c.cancel(); runCurrent()
        assertEquals(VoiceConsent.ACCEPT, c.state.value.consent)
        assertEquals(RestNavigationStatus.CANCELLED_AFTER_REQUEST_UNCONFIRMED, c.state.value.navigationStatus)
        assertTrue(c.state.value.requestIssued)
    }

    @Test fun `cancel during saving cannot start a second test until persistence finishes`() = runTest {
        val saved = CompletableDeferred<Unit>(); var finished = 0; var rows = 0
        val c = DangerNavigationProbeController(this, {}, { true }, { finished++ }, { accepted }, { _, _ ->
            rows++; saved.await(); 1L
        }, Store())
        c.start(); assertTrue(c.isBusy); c.cancel(); runCurrent()
        assertFalse(c.start()); assertEquals(0, finished)
        saved.complete(Unit); runCurrent()
        assertEquals(1, rows); assertEquals(1, finished); assertEquals(InterventionOutcome.ACCEPTED, c.state.value.outcome)
    }

    @Test fun `process interruption restores uncertainty and never auto retries`() = runTest {
        val c = DangerNavigationProbeController(this, {}, { error("no auto retry") }, {}, { error("no auto retry") },
            { _, _ -> 1L }, Store(DangerNavigationProbeState(runId = "old", running = true)))
        assertFalse(c.isBusy); assertTrue(c.state.value.interrupted); assertEquals("old", c.state.value.runId)
    }

    @Test fun `running marker persistence failure releases ownership before any real effect`() = runTest {
        var finished = 0
        val c = DangerNavigationProbeController(this, {}, { true }, { finished++ }, { error("no effects") },
            { _, _ -> error("no history") }, Store().apply { fail = true })
        assertFalse(c.start()); assertEquals(1, finished); assertFalse(c.isBusy); assertNotNull(c.state.value.saveError)
    }

    @Test fun `history failure is visible without discarding actual waypoint outcome`() = runTest {
        val c = DangerNavigationProbeController(this, {}, { false }, {}, { accepted }, { _, _ -> error("DB failure") }, Store())
        c.start(); runCurrent()
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT, c.state.value.navigationStatus)
        assertTrue(c.state.value.requestIssued); assertNotNull(c.state.value.saveError); assertNull(c.state.value.savedId)
    }

    @Test fun `time limit cancels stalled execution and restores resources`() = runTest {
        var finished = 0
        val c = DangerNavigationProbeController(this, {}, { true }, { finished++ }, { awaitCancellation() }, { _, _ -> 1L }, Store())
        c.start(); advanceTimeBy(90_001); runCurrent()
        assertFalse(c.isBusy); assertEquals(1, finished); assertEquals(InterventionOutcome.CANCELLED, c.state.value.outcome)
    }
}
