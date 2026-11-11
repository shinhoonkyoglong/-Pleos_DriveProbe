package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.local.entity.DriverConditionEntity
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.UnavailableReason

/**
 * 1번 Room Entity(DriverConditionEntity) ↔ 2번 core 모델(DriverCondition) 변환(순수 함수만, 단위 테스트 대상).
 *
 * 규칙 근거: 2번 저장소 docs/pleos/contracts/driver-condition-api.md
 * - §3 집합은 순서 의미 없음, 저장·응답은 enum 이름 오름차순.
 * - §5.3/§6 모르는 enum 값은 **건너뛰지 않고** 조회 실패(SERVICE_ERROR)로 본다. 건너뛰면 운전 제한이 조용히 사라진다.
 * - §7 모델 생성 중 `require` 예외(좌표 범위 밖, 반경 0 이하)는 잡아서 SERVICE_ERROR 로 바꾼다.
 */
internal object DriverConditionEntityMapper {

    /** 모델 → Entity. [updatedAt] 은 저장 시각(epoch ms), 새로 저장한 값은 아직 서버에 보내지 않았으므로 [isSynced] 기본 false. */
    fun toEntity(
        condition: DriverCondition,
        updatedAt: Long,
        isSynced: Boolean = false,
    ): DriverConditionEntity = DriverConditionEntity(
        id = DriverConditionEntity.SINGLE_ROW_ID,
        timeConstraints = joinNames(condition.timeConstraints.map { it.name }),
        routeConstraints = joinNames(condition.routePreference.constraints.map { it.name }),
        radiusCenterLatitude = condition.radiusCenter?.latitude,
        radiusCenterLongitude = condition.radiusCenter?.longitude,
        allowedRadiusMeters = condition.allowedRadiusMeters,
        updatedAt = updatedAt,
        isSynced = isSynced,
    )

    /**
     * Entity → 모델. 저장 값이 모델 규칙에 맞지 않으면(모르는 enum, 위도·경도 한쪽만 있음, 범위 밖 값)
     * Unavailable(SERVICE_ERROR). 값을 고쳐 쓰거나 일부만 버리지 않는다.
     */
    fun toModel(entity: DriverConditionEntity): DataResult<DriverCondition> {
        val timeConstraints = parseNames(entity.timeConstraints) { name ->
            TimeConstraint.entries.firstOrNull { it.name == name }
        } ?: return invalid("알 수 없는 timeConstraints 값: '${entity.timeConstraints}'")

        val routeConstraints = parseNames(entity.routeConstraints) { name ->
            RouteConstraint.entries.firstOrNull { it.name == name }
        } ?: return invalid("알 수 없는 routeConstraints 값: '${entity.routeConstraints}'")

        val lat = entity.radiusCenterLatitude
        val lng = entity.radiusCenterLongitude
        if ((lat == null) != (lng == null)) {
            return invalid("radiusCenter 위도·경도 중 하나만 있음: lat=$lat, lng=$lng")
        }

        return try {
            DataResult.Available(
                DriverCondition(
                    timeConstraints = timeConstraints,
                    routePreference = RoutePreference(routeConstraints),
                    radiusCenter = if (lat != null && lng != null) GeoPoint(lat, lng) else null,
                    allowedRadiusMeters = entity.allowedRadiusMeters,
                )
            )
        } catch (e: IllegalArgumentException) {
            invalid("모델 검증 실패: ${e.message}")
        }
    }

    private fun joinNames(names: Collection<String>): String =
        names.toSortedSet().joinToString(DriverConditionEntity.LIST_SEPARATOR)

    /** "" → 빈 집합. 하나라도 모르는 이름(빈 토큰 포함)이 있으면 null. */
    private fun <T> parseNames(raw: String, lookup: (String) -> T?): Set<T>? {
        if (raw.isEmpty()) return emptySet()
        val result = mutableSetOf<T>()
        for (token in raw.split(DriverConditionEntity.LIST_SEPARATOR)) {
            result += lookup(token) ?: return null
        }
        return result
    }

    private fun invalid(detail: String): DataResult<DriverCondition> =
        DataResult.Unavailable(UnavailableReason.SERVICE_ERROR, "로컬 운전자 조건 해석 실패 — $detail")
}
