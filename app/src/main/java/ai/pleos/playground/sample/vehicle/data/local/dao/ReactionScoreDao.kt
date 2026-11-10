package ai.pleos.playground.sample.vehicle.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionScoreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScore(score: ReactionScoreEntity): Long

    @Query("SELECT * FROM reaction_scores ORDER BY timestamp DESC LIMIT 1")
    fun getLatestScore(): Flow<ReactionScoreEntity?>

    @Query("SELECT * FROM reaction_scores WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getScoresBySession(sessionId: String): Flow<List<ReactionScoreEntity>>

    @Query("SELECT * FROM reaction_scores ORDER BY timestamp DESC")
    fun getAllScores(): Flow<List<ReactionScoreEntity>>

    @Query("SELECT * FROM reaction_scores WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedScores(): List<ReactionScoreEntity>

    @Query("UPDATE reaction_scores SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM reaction_scores WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
