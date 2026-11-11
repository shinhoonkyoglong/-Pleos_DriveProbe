package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.condition.core.BaselineSeedFormat
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.InMemoryBaselineStore
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine
import ai.pleos.playground.sample.vehicle.condition.signals.ReplayVehicleSignals
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Isolated synthetic inputs, production engine/controller/actions, real emulator effects.
 * Never replaces the live engine or writes its baseline. All DB rows are test-labelled.
 */
internal class BackgroundStageProbe(
    private val context: Context,
    private val scope: CoroutineScope,
    private val assertReady: () -> Unit,
    private val execute: suspend (FatigueLevel) -> InterventionResult,
    private val save: suspend (InterventionRecord) -> Unit,
    private val onFinish: () -> Unit,
) {
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isCompleted == false

    fun start() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(!isRunning)
        val runId = "background-probe-${UUID.randomUUID()}"
        job = scope.launch {
            try {
                Log.i("BgStage", "ARMED run=$runId delayMs=20000 source=SYNTHETIC_INPUT effects=REAL_EMULATOR")
                // The visible Activity starts the microphone FGS first. No broadcast
                // is needed to start work once the user switches to navigation.
                delay(20_000)
                assertReady()
                runScenario(runId)
                Log.i("BgStage", "FINISHED run=$runId elapsedMs=${SystemClock.elapsedRealtime()}")
            } catch (e: CancellationException) {
                Log.w("BgStage", "CANCELLED run=$runId")
                throw e
            } catch (e: Exception) {
                Log.e("BgStage", "FAILED run=$runId reason=${e.message}", e)
            } finally {
                onFinish()
            }
        }
    }

    private suspend fun runScenario(runId: String) = coroutineScope {
        val signals = ReplayVehicleSignals(ReplayVehicleSignals.loadTrip(context), this)
        val seed = context.assets.open(ReplayVehicleSignals.BASELINE_SEED_ASSET)
            .bufferedReader(Charsets.UTF_8).use { BaselineSeedFormat.read(it.readText()) }
        val engine = ConditionEngine(signals, InMemoryBaselineStore(seed), this, hourOfDay = { 3 })
        engine.setPreDrive(ReactionVerdict.MUCH_SLOWER)
        val seen = mutableSetOf<FatigueLevel>()
        val saved = mutableSetOf<FatigueLevel>()
        val controller = InterventionController(this, SystemClock::elapsedRealtime,
            execute = { level ->
                assertReady()
                Log.i("BgStage", "ACTION_START run=$runId level=$level elapsedMs=${SystemClock.elapsedRealtime()}")
                val result = execute(level)
                Log.i("BgStage", "ACTION_RESULT run=$runId level=$level outcome=${result.outcome} responseMs=${result.responseMs} error=${result.error}")
                result
            },
            save = { record ->
                // simulation marks the INPUT provenance. Controller simulation=false
                // above deliberately exercises real effects in this isolated test.
                save(record.copy(sessionId = runId, simulation = true))
                saved += record.condition.level
                Log.i("BgStage", "SAVED run=$runId level=${record.condition.level} outcome=${record.result.outcome}")
            })
        val observer = launch {
            combine(engine.state, engine.isDriving, signals.gear) { state, active, gear -> Triple(state, active, gear) }
                .collect { (state, active, gear) ->
                    if (seen.add(state.level)) Log.i("BgStage", "LEVEL run=$runId level=${state.level} score=${state.score} valid=${state.lastWindowValid} elapsedMs=${SystemClock.elapsedRealtime()}")
                    controller.update(state, active, gear == GearPosition.DRIVE, simulation = false)
                }
        }
        try {
            engine.start()
            Log.i("BgStage", "STARTED run=$runId hour=3 preDrive=MUCH_SLOWER baseline=TEST_SEED productionThresholds=true")
            val startedAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startedAt < signals.trip.durationMs + 30_000) {
                assertReady()
                val d = engine.diagnostics()
                Log.i("BgStage", "SAMPLE run=$runId elapsedMs=${SystemClock.elapsedRealtime()} ticks=${d.ticks} events=${d.events} level=${engine.state.value.level} valid=${engine.state.value.lastWindowValid} replayFinished=${signals.finished.value}")
                delay(2000)
            }
            check(seen.containsAll(listOf(FatigueLevel.MILD, FatigueLevel.CAUTION, FatigueLevel.DANGER))) { "Missing computed levels: $seen" }
            check(saved.containsAll(listOf(FatigueLevel.MILD, FatigueLevel.CAUTION, FatigueLevel.DANGER))) { "Missing saved actions: $saved" }
            check(controller.status.value.historyError == null) { "History persistence failed" }
            // FINISHED means the probe ran, not that hardware/audio outcomes passed.
        } finally {
            observer.cancel()
            engine.stop()
            controller.reset()
            withContext(NonCancellable) {
                withTimeoutOrNull(15_000) { while (controller.isBusy) delay(100) }
            }
        }
    }
}
