package ai.pleos.playground.sample.vehicle.trip.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TripRecordEntity)

    /** 가장 최근에 끝난 주행 (주행 종료 요약용) */
    @Query("SELECT * FROM trip_records ORDER BY endedAt DESC LIMIT 1")
    fun observeLatest(): Flow<TripRecordEntity?>

    /** 특정 주행 (요약 화면용) */
    @Query("SELECT * FROM trip_records WHERE tripId = :tripId")
    fun observeById(tripId: String): Flow<TripRecordEntity?>

    /** 특정 주행 1건(중간 저장 복구 때 이미 저장됐는지 확인) */
    @Query("SELECT * FROM trip_records WHERE tripId = :tripId")
    suspend fun getById(tripId: String): TripRecordEntity?

    /** 최근 주행 [limit]건 (지난 기록 목록용) */
    @Query("SELECT * FROM trip_records ORDER BY endedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TripRecordEntity>>

    @Query("SELECT * FROM trip_records ORDER BY endedAt DESC")
    fun observeAll(): Flow<List<TripRecordEntity>>

    /** [from, to) 사이에 끝난 주행 (주간 요약용) */
    @Query("SELECT * FROM trip_records WHERE endedAt >= :from AND endedAt < :to ORDER BY endedAt ASC")
    suspend fun getEndedBetween(from: Long, to: Long): List<TripRecordEntity>

    /** 종료 시각이 [from] 이후인 주행 (가족 요약·운전 리포트용, 기간 자르기는 순수 로직이 한다) */
    @Query("SELECT * FROM trip_records WHERE endedAt >= :from ORDER BY endedAt ASC")
    fun observeEndedSince(from: Long): Flow<List<TripRecordEntity>>

    /** 시연용 가상 기록 넣기 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TripRecordEntity>)

    /** tripId가 [prefix]로 시작하는 기록 지우기(시연용 가상 기록만 지울 때) */
    @Query("DELETE FROM trip_records WHERE tripId LIKE :prefix || '%'")
    suspend fun deleteByIdPrefix(prefix: String): Int

    @Query("SELECT * FROM trip_records WHERE isSynced = 0 ORDER BY endedAt ASC")
    suspend fun getUnsynced(): List<TripRecordEntity>

    @Query("UPDATE trip_records SET isSynced = 1 WHERE tripId IN (:tripIds)")
    suspend fun markAsSynced(tripIds: List<String>)
}
