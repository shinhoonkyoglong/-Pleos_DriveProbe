package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.InMemoryBaselineStore
import ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine
import ai.pleos.playground.sample.vehicle.condition.signals.VehicleSignals
import ai.pleos.playground.sample.vehicle.data.local.AppDatabase
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import ai.pleos.playground.sample.vehicle.trip.*
import ai.pleos.playground.sample.vehicle.trip.data.TripDatabase
import ai.pleos.playground.sample.vehicle.trip.source.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local engines and in-memory databases only. No SDK actions, user history or route writes. */
@RunWith(AndroidJUnit4::class)
class TripInterventionReportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun trip(id: String = "trip") = CompletedTrip(id, 1, 100, FatigueLevel.DANGER,
        1, 9, 9, emptySet(), false)

    @Test fun exactTripAndExplicitProductionSpeechDetermineCounts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.interventionHistoryDao()
            suspend fun row(level: Int = 3, response: String? = null, id: String = "trip",
                json: String = "{\"simulation\":false}", reason: String = "DANGER") {
                dao.insertIntervention(InterventionHistoryEntity(sessionId = id, timestamp = 999,
                    interventionLevel = level, fatigueScore = 70f, triggerReason = reason,
                    actionDetails = json, userResponse = response))
            }
            row(level = 2, response = "괜찮아요")
            row(response = "아니요", json = "{\"simulation\":false,\"outcome\":\"DECLINED\",\"consent\":\"DECLINE\"}")
            row(response = "글쎄요", json = "{\"simulation\":false,\"outcome\":\"UNKNOWN_RESPONSE\"}")
            row(response = "추가해 줘", json = "{\"simulation\":false,\"outcome\":\"CANCELLED\",\"navigationStatus\":\"FAILED\"}")
            row() // no response
            row(response = "  ")
            row(json = "{\"simulation\":false,\"outcome\":\"ACCEPTED\"}") // no fabricated speech
            row(json = "{\"simulation\": false,\"details\":\"공백 포함\"}")
            row(level = 1, response = "MILD")
            row(level = 4, response = "unknown level")
            row(id = "other-trip", response = "다른 주행")
            row(json = "{\"simulation\":true}", response = "시연")
            row(json = "{}")
            row(json = "broken JSON")
            row(json = "{\"simulation\":\"false\"}")
            row(json = "{\"simulation\":true,\"details\":\"\\\"simulation\\\":false\"}")
            listOf("VOICE_TEST", "DANGER_NAVIGATION_PROBE", "BACKGROUND_STAGE_PROBE").forEach {
                row(reason = it)
                row(json = "{\"simulation\":false,\"source\":\"$it\"}")
            }
            val source = InterventionRestAdviceSource(dao)
            // Counts actual stored attempts, independent of engine stage entry counts and timestamps.
            assertEquals(RestAdvice(8, 4, DataSource.REAL), source.countFor(trip()))
            assertEquals(RestAdvice(0, 0, DataSource.REAL), source.countFor(trip("empty")))
            assertEquals(RestAdvice(0, 0, DataSource.MOCK), source.countFor(trip().copy(isDemo = true)))
        } finally { db.close() }
    }

    @Test fun reportWaitsForLateInsertAndUsesRecoveredTripId() = runBlocking {
        val historyDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val tripDb = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = System.currentTimeMillis()
        val checkpoints = MemoryCheckpoints(TripAccumulator(clock = { now }, newTripId = { "recovered-trip" })
            .apply { start(false) }.snapshot())
        val bound = CompletableDeferred<String>()
        val saving = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        val history = historyDb.interventionHistoryDao()
        val controller = InterventionController(CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate),
            { 0 }, { awaitCancellation() }, { record ->
                saving.complete(Unit)
                allowSave.await()
                history.insertIntervention(InterventionHistoryEntity(sessionId = record.sessionId,
                    timestamp = Long.MAX_VALUE, interventionLevel = record.condition.level.ordinal,
                    fatigueScore = 70f, triggerReason = "CAUTION", actionDetails = "{\"simulation\":false}",
                    userResponse = record.result.response))
            })
        val engine = localEngine(scope).also { it.handle(VehicleSignalEvent.Gear(1, GearPosition.DRIVE)) }
        val recorder = TripRecorder(scope, tripDb.tripRecordDao(), InterventionRestAdviceSource(history),
            MockComplianceSource(), EngineOrMockPreDriveSource(), engines = flowOf(engine),
            checkpoints = checkpoints, onTripStarted = { _, id ->
                withContext(Dispatchers.Main.immediate) {
                    controller.update(ConditionState(FatigueLevel.CAUTION, .7f, false, lastWindowValid = true),
                        true, true, false, reportTripId = id)
                }
                bound.complete(id)
            }, onTripEnding = { id -> withContext(Dispatchers.Main.immediate) { controller.finishSession(id) } })
        try {
            recorder.start()
            assertEquals("recovered-trip", withTimeout(5_000) { bound.await() })
            val ending = scope.launch { recorder.finishNow("local test") }
            withTimeout(5_000) { saving.await() }
            assertNull(recorder.lastRecord.value)
            assertNull(tripDb.tripRecordDao().getById("recovered-trip"))
            allowSave.complete(Unit)
            withTimeout(5_000) { ending.join() }
            val report = recorder.lastRecord.value!!
            assertEquals("recovered-trip", report.tripId)
            assertEquals(1, report.restAdviceCount)
            assertEquals(0, report.restRespondedCount)
            assertEquals(DataSource.REAL.name, report.restAdviceSource)
            assertEquals(report, tripDb.tripRecordDao().getById(report.tripId))
            assertNull(checkpoints.value)
            assertFalse(TripSummaryContent.restItem(report).isMock)
        } finally {
            allowSave.complete(Unit)
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            historyDb.close()
            tripDb.close()
        }
    }

    @Test fun failedAggregationKeepsCheckpointAndDoesNotPublishReport() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkpoints = MemoryCheckpoints()
        val bound = CompletableDeferred<String>()
        val engine = localEngine(scope).also { it.handle(VehicleSignalEvent.Gear(1, GearPosition.DRIVE)) }
        val recorder = TripRecorder(scope, db.tripRecordDao(), RestAdviceSource { error("read failed") },
            MockComplianceSource(), EngineOrMockPreDriveSource(), engines = flowOf(engine),
            checkpoints = checkpoints, onTripStarted = { _, id -> bound.complete(id) })
        try {
            recorder.start()
            val id = withTimeout(5_000) { bound.await() }
            recorder.finishNow("failure test")
            assertNull(recorder.lastRecord.value)
            assertNull(db.tripRecordDao().getById(id))
            assertEquals(id, checkpoints.value!!.tripId)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            db.close()
        }
    }

    private class MemoryCheckpoints(var value: TripSnapshot? = null) : TripCheckpointStore {
        override fun load() = value
        override fun save(snapshot: TripSnapshot) { value = snapshot }
        override fun clear() { value = null }
    }

    private fun localEngine(scope: CoroutineScope) = ConditionEngine(object : VehicleSignals {
        override val isReplay = false
        override val events = emptyFlow<VehicleSignalEvent>()
        override val gear = MutableStateFlow(GearPosition.DRIVE)
        override val speedKmh = MutableStateFlow<Float?>(0f)
        override fun start() = Unit
        override fun stop() = Unit
    }, InMemoryBaselineStore(), scope, clock = { 1 }, hourOfDay = { 12 })
}
