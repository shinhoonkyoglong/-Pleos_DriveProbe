package ai.pleos.playground.sample.vehicle.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.pleos.playground.sample.vehicle.data.local.entity.DriverConditionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 운전자 조건 DAO. 테이블에는 행이 하나(id = [DriverConditionEntity.SINGLE_ROW_ID])만 있다.
 */
@Dao
interface DriverConditionDao {

    /** 저장(전체 교체). 행이 있으면 덮어쓴다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCondition(condition: DriverConditionEntity): Long

    /** 현재 조건. 저장된 적이 없으면 null. */
    @Query("SELECT * FROM driver_conditions WHERE id = ${DriverConditionEntity.SINGLE_ROW_ID} LIMIT 1")
    suspend fun getCondition(): DriverConditionEntity?

    /** 조건 변경 관찰. 구독 시 현재 값을 먼저 내보내고, 행이 없으면 null. */
    @Query("SELECT * FROM driver_conditions WHERE id = ${DriverConditionEntity.SINGLE_ROW_ID} LIMIT 1")
    fun observeCondition(): Flow<DriverConditionEntity?>

    /** 서버로 아직 보내지 않은 조건. 없으면 null. */
    @Query("SELECT * FROM driver_conditions WHERE isSynced = 0 LIMIT 1")
    suspend fun getUnsyncedCondition(): DriverConditionEntity?

    /**
     * 동기화 완료 표시. 보낸 뒤 그사이 새로 저장된 값까지 완료로 표시하지 않도록 보낸 행의 [updatedAt] 이 같을 때만 바꾼다.
     * @return 바뀐 행 수(0 이면 그사이 다시 저장됨 → 다시 보내야 한다)
     */
    @Query("UPDATE driver_conditions SET isSynced = 1 WHERE id = ${DriverConditionEntity.SINGLE_ROW_ID} AND updatedAt = :updatedAt")
    suspend fun markAsSynced(updatedAt: Long): Int

    /** 조건 삭제(미설정 상태로 되돌림). 해제(빈 조건 저장)와 다르다. */
    @Query("DELETE FROM driver_conditions")
    suspend fun deleteCondition()
}
