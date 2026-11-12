package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.data.local.AppDatabase
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory Room only: no audio, route write or changes to the user's history. */
@RunWith(AndroidJUnit4::class)
class DrivingHistoryIsolationTest {
    @Test fun onlyExplicitProductionRecordsAppearInDrivingReports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.interventionHistoryDao()
            val rows = listOf(
                "real" to "{\"simulation\":false}",
                "replay" to "{\"simulation\":true}",
                "VOICE_TEST" to "{\"simulation\":true}",
                "DANGER_NAVIGATION_PROBE" to "{\"simulation\":true}",
                "BACKGROUND_STAGE_PROBE" to "{\"simulation\":false}",
                "legacy-unknown" to "{}",
                "escaped-detail" to "{\"simulation\":true,\"details\":\"\\\"simulation\\\":false\"}",
            )
            rows.forEach { (source, json) -> dao.insertIntervention(InterventionHistoryEntity(
                sessionId = source, interventionLevel = 3, fatigueScore = 0f, triggerReason = source, actionDetails = json)) }
            assertEquals(rows.size, dao.getAllHistories().first().size)
            assertEquals(listOf("real"), dao.getDrivingHistories().first().map { it.sessionId })
            val uploadable = dao.getUnsyncedHistories()
            assertEquals(listOf("real"), uploadable.map { it.sessionId })
            dao.markAsSynced(uploadable.map { it.id })
            assertTrue(dao.getUnsyncedHistories().isEmpty())
            assertEquals(rows.size, dao.getAllHistories().first().size)
        } finally { db.close() }
    }
}
