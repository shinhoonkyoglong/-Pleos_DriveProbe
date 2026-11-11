package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.*
import ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine
import ai.pleos.playground.sample.vehicle.condition.signals.ReplayVehicleSignals
import ai.pleos.playground.sample.vehicle.condition.signals.VehicleSignals
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackgroundStageScenarioTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `unmodified engine computes all stages and controller saves each action`() = runTest {
        val assets = File(requireNotNull(System.getProperty("conditionAssetsDir")))
        val trip = ReplayFormat.read(File(assets, "trip_demo.json").readText())
        val seed = BaselineSeedFormat.read(File(assets, "baseline_seed.json").readText())
        val store = InMemoryBaselineStore(seed)
        val signals = object : VehicleSignals {
            override val isReplay = true
            override val events: Flow<VehicleSignalEvent> = emptyFlow()
            override val gear = MutableStateFlow(GearPosition.PARK)
            override val speedKmh = MutableStateFlow<Float?>(0f)
            override fun start() = Unit
            override fun stop() = Unit
        }
        var now = 1_000_000L
        val engine = ConditionEngine(signals, store, this, clock = { now }, hourOfDay = { 3 })
        engine.setPreDrive(ReactionVerdict.MUCH_SLOWER)
        val executed = mutableListOf<FatigueLevel>()
        val records = mutableListOf<InterventionRecord>()
        val controller = InterventionController(this, { now }, execute = {
            executed += it
            InterventionResult(InterventionOutcome.VERIFIED, "Fake effect for JVM test")
        }, save = { records += it })
        var i = 0
        for (t in 0L..trip.durationMs step 100) {
            now = 1_000_000L + t
            while (i < trip.events.size && trip.events[i].tMs <= t) {
                val event = ReplayVehicleSignals.toSignalEvent(trip.events[i], 1_000_000L + trip.events[i].tMs)
                i++
                if (event is VehicleSignalEvent.Gear) signals.gear.value = event.gear
                engine.handle(event)
            }
            val state = engine.tick(now)
            controller.update(state, engine.isDriving.value, signals.gear.value == GearPosition.DRIVE, simulation = false)
            runCurrent()
        }
        val expected = listOf(FatigueLevel.MILD, FatigueLevel.CAUTION, FatigueLevel.DANGER)
        assertEquals(expected, executed)
        assertEquals(expected, records.map { it.condition.level })
        assertTrue(records.all { it.condition.lastWindowValid })
        assertEquals(seed, store.load())
        controller.reset()
    }
}
