package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.local.dao.DriverConditionDao
import ai.pleos.playground.sample.vehicle.data.local.entity.DriverConditionEntity
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.UnavailableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * RoomDriverConditionRepository 의 포트 규칙 검증(계약 §5.3·§5.4). DAO 는 메모리 가짜로 대신한다
 * (실제 Room·마이그레이션은 계측 테스트 AppDatabaseMigrationTest).
 */
class RoomDriverConditionRepositoryTest {

    private class FakeDao(var failing: Boolean = false) : DriverConditionDao {
        val row = MutableStateFlow<DriverConditionEntity?>(null)

        private fun failIfNeeded() { if (failing) throw IOException("disk error") }

        override suspend fun upsertCondition(condition: DriverConditionEntity): Long { failIfNeeded(); row.value = condition; return 1L }
        override suspend fun getCondition(): DriverConditionEntity? { failIfNeeded(); return row.value }
        override fun observeCondition(): Flow<DriverConditionEntity?> =
            if (failing) flow { throw IOException("disk error") } else row
        override suspend fun getUnsyncedCondition(): DriverConditionEntity? = row.value?.takeIf { !it.isSynced }
        override suspend fun markAsSynced(updatedAt: Long): Int {
            val current = row.value ?: return 0
            if (current.updatedAt != updatedAt) return 0
            row.value = current.copy(isSynced = true); return 1
        }
        override suspend fun deleteCondition() { row.value = null }
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-10T06:30:00Z"), ZoneId.of("Asia/Seoul"))
    private val condition = DriverCondition(
        timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
        routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
    )

    @Test
    fun noRow_getIsNoData_observeIsAvailableNull() = runBlocking {
        val repo = RoomDriverConditionRepository(FakeDao(), clock)
        assertEquals(UnavailableReason.NO_DATA, (repo.getCondition() as DataResult.Unavailable).reason)
        assertEquals(DataResult.Available(null), repo.observeCondition().first())
    }

    @Test
    fun save_returnsStoredValue_writesUpdatedAtAndUnsynced() = runBlocking {
        val dao = FakeDao()
        val repo = RoomDriverConditionRepository(dao, clock)
        assertEquals(DataResult.Available(condition), repo.saveCondition(condition))
        assertEquals(clock.millis(), dao.row.value!!.updatedAt)
        assertFalse(dao.row.value!!.isSynced)
        assertEquals(DataResult.Available(condition), repo.getCondition())
        assertEquals(DataResult.Available(condition), repo.observeCondition().first())
    }

    @Test
    fun saveCleared_observeEmitsEmptyConditionNotNull() = runBlocking {
        val repo = RoomDriverConditionRepository(FakeDao(), clock)
        repo.saveCondition(condition)
        repo.saveCondition(DriverCondition())
        assertEquals(DataResult.Available(DriverCondition()), repo.observeCondition().first())
    }

    @Test
    fun dbFailure_isServiceErrorNotNull() = runBlocking {
        val repo = RoomDriverConditionRepository(FakeDao(failing = true), clock)
        assertEquals(UnavailableReason.SERVICE_ERROR, (repo.getCondition() as DataResult.Unavailable).reason)
        assertEquals(UnavailableReason.SERVICE_ERROR, (repo.saveCondition(condition) as DataResult.Unavailable).reason)
        val emitted = repo.observeCondition().toList()
        assertEquals(1, emitted.size)
        assertEquals(UnavailableReason.SERVICE_ERROR, (emitted.single() as DataResult.Unavailable).reason)
    }

    @Test
    fun corruptedRow_observeIsServiceError() = runBlocking {
        val dao = FakeDao()
        dao.row.value = DriverConditionEntity(
            timeConstraints = "UNKNOWN_RULE", routeConstraints = "",
            radiusCenterLatitude = null, radiusCenterLongitude = null, allowedRadiusMeters = null,
        )
        val repo = RoomDriverConditionRepository(dao, clock)
        val first = repo.observeCondition().first()
        assertTrue(first is DataResult.Unavailable)
        assertEquals(UnavailableReason.SERVICE_ERROR, (first as DataResult.Unavailable).reason)
    }
}
