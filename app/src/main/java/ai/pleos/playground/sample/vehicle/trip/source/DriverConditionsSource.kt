package ai.pleos.playground.sample.vehicle.trip.source

import ai.pleos.playground.sample.vehicle.trip.report.DriverConditions
import ai.pleos.playground.sample.vehicle.trip.report.ReportCondition
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.port.DriverConditionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 운전 리포트에 넣을 운전자 조건. null = 설정 없음·읽기 실패 */
fun interface DriverConditionsSource {
    fun observe(): Flow<DriverConditions?>

    companion object {
        val NONE = DriverConditionsSource { flowOf(null) }
    }
}

/**
 * 2번 운전자 조건 저장소([DriverConditionRepository.observeCondition])를 **읽기만** 한다.
 * - 야간 금지 → NO_NIGHT_DRIVING, 고속도로 회피 → AVOID_HIGHWAY, 반경 기능이 켜져 있고 기준점이 있으면 RADIUS
 * - 무료도로 선호는 면허 조건이 아니라 경로 선호라 리포트에 넣지 않는다
 */
class DepartureDriverConditionsSource(
    private val repository: () -> DriverConditionRepository,
    private val defaultRadiusMeters: () -> Double,
    /** DepartureConfig.radiusCheckEnabled. false 면 리포트에 반경 조건을 넣지 않는다. 기본값 없음(빠뜨리면 컴파일 오류) */
    private val radiusEnabled: () -> Boolean,
) : DriverConditionsSource {

    override fun observe(): Flow<DriverConditions?> =
        repository().observeCondition().map { result ->
            (result as? DataResult.Available)?.value?.let { toReport(it, defaultRadiusMeters(), radiusEnabled()) }
        }

    companion object {
        fun toReport(c: DriverCondition, defaultRadiusMeters: Double, radiusEnabled: Boolean): DriverConditions {
            val set = buildSet {
                if (c.noNightDriving) add(ReportCondition.NO_NIGHT_DRIVING)
                if (RouteConstraint.AVOID_HIGHWAY in c.routePreference.constraints) add(ReportCondition.AVOID_HIGHWAY)
                if (radiusEnabled && c.radiusCenter != null) add(ReportCondition.RADIUS)
            }
            val radiusKm = if (ReportCondition.RADIUS in set) c.effectiveRadiusMeters(defaultRadiusMeters) / 1000.0 else null
            return DriverConditions(set, radiusKm)
        }
    }
}
