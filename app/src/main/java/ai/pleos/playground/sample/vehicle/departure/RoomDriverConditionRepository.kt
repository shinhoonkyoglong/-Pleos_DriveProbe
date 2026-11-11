package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.local.dao.DriverConditionDao
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * 운전자 조건 저장소 — 1번 Room DB(driver_conditions 테이블) 위 2번 포트 어댑터. 앱을 다시 시작해도 조건이 남는다.
 *
 * 규칙(2번 저장소 docs/pleos/contracts/driver-condition-api.md §5.3·§5.4):
 * - 행 없음 = 확인된 미설정: getCondition → Unavailable(NO_DATA), observeCondition → Available(null).
 * - 해제 저장(빈 DriverCondition) 후에는 행이 남아 Available(DriverCondition()) 을 내보낸다(해제 ≠ 미설정).
 * - DB 오류·저장 값 해석 실패는 Unavailable(SERVICE_ERROR). null 로 내보내지 않는다. 예외는 던지지 않는다(코루틴 취소만 전파).
 *
 * TODO(1번-동기화): 지금은 로컬 DB 만 원본이다. 서버(GET/PUT /v1/drivers/me/driver-condition)가 생기면
 *   - saveCondition: 로컬 저장(isSynced=false) 후 PUT, 성공 시 dao.markAsSynced(updatedAt).
 *     오프라인 저장 허용 여부는 계약 8절 D-6.
 *   - 행 없음 → 바로 Available(null) 이 아니라 서버 조회 결과로 판정한다(404 + CONDITION_NOT_SET 일 때만 미설정, 그 밖의 실패는 Unavailable).
 *   - 로컬에 미전송 저장(getUnsyncedCondition)이 있으면 그 값을 우선한다.
 *
 * @param clock 저장 시각(updatedAt) 기록용. 2번 core 에 넘기는 Clock 과 같은 것을 쓴다.
 */
class RoomDriverConditionRepository(
    private val dao: DriverConditionDao,
    private val clock: Clock,
) : DriverConditionRepository {

    override suspend fun getCondition(): DataResult<DriverCondition> {
        val entity = try {
            dao.getCondition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return dbError("조회", e)
        }
        return entity?.let(DriverConditionEntityMapper::toModel)
            ?: DataResult.Unavailable(UnavailableReason.NO_DATA, "운전자 조건 미설정(로컬 DB 행 없음)")
    }

    override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> {
        val stored = try {
            dao.upsertCondition(DriverConditionEntityMapper.toEntity(condition, updatedAt = clock.millis()))
            dao.getCondition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return dbError("저장", e)
        }
        // 포트 계약: 저장 후 "저장된 값"을 돌려준다 → 다시 읽어 변환한 값
        return stored?.let(DriverConditionEntityMapper::toModel)
            ?: DataResult.Unavailable(UnavailableReason.SERVICE_ERROR, "저장 직후 조회 결과 없음")
    }

    /**
     * Room Flow 라 구독 시 현재 값을 먼저 1회 내보내고, 테이블이 바뀔 때마다 다시 내보낸다.
     * DB 오류가 나면 Unavailable(SERVICE_ERROR)을 내보내고 Flow 가 끝난다(다시 구독하면 재시도).
     */
    override fun observeCondition(): Flow<DataResult<DriverCondition?>> =
        dao.observeCondition()
            .map { entity ->
                if (entity == null) DataResult.Available(null) else DriverConditionEntityMapper.toModel(entity)
            }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(DataResult.Unavailable(UnavailableReason.SERVICE_ERROR, "운전자 조건 관찰 실패: ${e.javaClass.simpleName}"))
            }
            .distinctUntilChanged()

    private fun dbError(action: String, e: Exception): DataResult<Nothing> =
        DataResult.Unavailable(UnavailableReason.SERVICE_ERROR, "운전자 조건 $action 실패: ${e.javaClass.simpleName}: ${e.message}")
}
