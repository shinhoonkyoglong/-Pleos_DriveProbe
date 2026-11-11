package ai.pleos.playground.sample.vehicle.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InterventionHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntervention(history: InterventionHistoryEntity): Long

    @Query("SELECT * FROM intervention_histories ORDER BY timestamp DESC LIMIT 1")
    fun getLatestIntervention(): Flow<InterventionHistoryEntity?>

    @Query("SELECT * FROM intervention_histories WHERE triggerReason = 'VOICE_TEST' ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun getLatestVoiceTest(): Flow<InterventionHistoryEntity?>

    @Query("SELECT * FROM intervention_histories WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getHistoriesBySession(sessionId: String): Flow<List<InterventionHistoryEntity>>

    @Query("SELECT * FROM intervention_histories ORDER BY timestamp DESC")
    fun getAllHistories(): Flow<List<InterventionHistoryEntity>>

    @Query("SELECT COUNT(*) FROM intervention_histories WHERE sessionId = :sessionId AND interventionLevel = :level")
    suspend fun countInterventionsByLevel(sessionId: String, level: Int): Int

    @Query("SELECT * FROM intervention_histories WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedHistories(): List<InterventionHistoryEntity>

    @Query("UPDATE intervention_histories SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM intervention_histories WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
