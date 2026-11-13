package ai.pleos.playground.sample.vehicle.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationProbeControllerTest {
    private class Store(var value: NavigationProbeState? = null): NavigationProbeStateStore {
        override fun load() = value
        override fun save(state: NavigationProbeState) { value = state }
    }

    @Test fun `screen observer can disappear while application transaction completes and result survives reload`() = runTest {
        val store = Store()
        val release = CompletableDeferred<Unit>()
        val controller = NavigationProbeController(backgroundScope, { progress ->
            progress("adding"); release.await()
            ServiceAreaProbeReport(null, ProbeCleanup.VERIFIED, "restored")
        }, store)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.state.collect {} }
        assertTrue(controller.start())
        runCurrent(); observer.cancel()
        assertTrue(controller.state.value.running)
        release.complete(Unit); runCurrent()
        assertFalse(controller.isBusy)
        assertEquals(ProbeCleanup.VERIFIED, controller.state.value.cleanup)
        val reopened = NavigationProbeController(backgroundScope, { error("must not rerun") }, store)
        assertEquals(controller.state.value, reopened.state.value)
    }

    @Test fun `process death during transaction becomes unconfirmed without automatic write`() = runTest {
        var executions = 0
        val controller = NavigationProbeController(backgroundScope, {
            executions++; error("must not rerun")
        }, Store(NavigationProbeState(runId="old", running=true)))
        runCurrent()
        assertFalse(controller.state.value.running)
        assertTrue(controller.state.value.interrupted)
        assertNull(controller.state.value.cleanup)
        assertEquals(0, executions)
    }

    @Test fun `cancellation retains transaction ownership until noncancellable cleanup ends`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val controller = NavigationProbeController(backgroundScope, {
            try { awaitCancellation() } catch (e: CancellationException) {
                withContext(NonCancellable) { cleanup.await() }
                throw ServiceAreaProbeCancelled(ServiceAreaProbeReport(null, ProbeCleanup.VERIFIED, "cancelled and restored"))
            }
        }, Store())
        controller.start(); runCurrent(); controller.cancel(); runCurrent()
        assertTrue(controller.isBusy)
        assertFalse(controller.start())
        cleanup.complete(Unit); runCurrent()
        assertFalse(controller.isBusy)
        assertEquals(ProbeCleanup.VERIFIED, controller.state.value.cleanup)
        assertFalse(controller.state.value.interrupted)
    }

    @Test fun `execution deadline still records cleanup result and calls service completion`() = runTest {
        var finished = 0
        val controller = NavigationProbeController(backgroundScope, {
            try { awaitCancellation() } catch (e: CancellationException) {
                withContext(NonCancellable) { delay(30_000) }
                throw ServiceAreaProbeCancelled(ServiceAreaProbeReport(null, ProbeCleanup.FAILED, "cleanup failed"))
            }
        }, Store())
        controller.start { finished++ }; runCurrent()
        advanceTimeBy(90_000); runCurrent()
        assertTrue(controller.isBusy)
        advanceTimeBy(30_000); runCurrent()
        assertFalse(controller.isBusy)
        assertEquals(ProbeCleanup.FAILED, controller.state.value.cleanup)
        assertEquals(1, finished)
    }

    @Test fun `inactive application scope cannot start native work`() = runTest {
        val stopped = CoroutineScope(Job().apply { cancel() })
        val controller = NavigationProbeController(stopped, { error("must not run") }, Store())
        assertFalse(controller.start())
        assertTrue(controller.state.value.interrupted)
    }
}
