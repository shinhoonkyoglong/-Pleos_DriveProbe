package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.PointRadiusCheck
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.port.NaviRepository

/**
 * 반경 확인: "내비 목적지가 허용 반경 안인가"(작업 #5, 사용자 결정 11/09).
 *
 * ## 순서
 * 1. 내비 목적지 조회([NaviRepository.getDestination])
 *    - 미설정 → [RadiusCheckResult.DestinationNotSet] (경고 없음. 위치도 조회하지 않음)
 *    - 확인 불가 → [RadiusCheckResult.DestinationUnavailable]
 * 2. 운전자 조건에 기준점(radiusCenter)이 없으면 → [RadiusCheckResult.CenterNotSet] ("확인 불가")
 * 3. 반경 = 운전자 값, 없으면 [DepartureConfig.defaultAllowedRadiusMeters]
 * 4. 목적지 판정(경고 기준) + 현재 위치 판정(참고). 현재 위치를 못 얻으면 목적지 판정만 한다.
 *
 * 판정은 [GeoDistance] 순수 계산(직선거리, 경계 = 안). 경로 중간 이탈은 판정하지 않는다.
 * 위치는 NaviHelper `getCurrentLocationInfo`(NaviRepository.getCurrentLocation)만 쓴다.
 */
class RadiusCheckUseCase(
    private val naviRepository: NaviRepository,
    private val config: DepartureConfig,
) {

    /**
     * @param condition 운전자 조건(기준점·반경)
     * @param destination 이미 조회한 내비 목적지가 있으면 넘긴다(같은 화면에서 출발 마감과 함께 쓸 때 중복 조회 방지). null 이면 조회한다.
     * @param currentLocation 이미 조회한 현재 위치가 있으면 넘긴다(리뷰 task-05 MINOR-4: 위치 중복 조회 방지). null 이면 필요할 때 조회한다.
     */
    suspend fun check(
        condition: DriverCondition,
        destination: DataResult<NaviDestination>? = null,
        currentLocation: DataResult<GeoPoint>? = null,
    ): RadiusCheckResult {
        val place = when (val d = (destination ?: naviRepository.getDestination()).treatNullIslandAsNotSet()) {
            is DataResult.Unavailable -> return RadiusCheckResult.DestinationUnavailable(d.reason)
            is DataResult.Available -> when (val value = d.value) {
                NaviDestination.NotSet -> return RadiusCheckResult.DestinationNotSet
                is NaviDestination.Present -> value.place
            }
        }
        val center = condition.radiusCenter ?: return RadiusCheckResult.CenterNotSet(place)
        val radius = condition.effectiveRadiusMeters(config.defaultAllowedRadiusMeters)

        val currentLocationCheck = when (val location = currentLocation ?: naviRepository.getCurrentLocation()) {
            is DataResult.Available -> measure(center, location.value, radius)
            is DataResult.Unavailable -> PointRadiusCheck.Unavailable(location.reason)
        }
        return RadiusCheckResult.Checked(
            destination = place,
            center = center,
            radiusMeters = radius,
            destinationCheck = measure(center, place.point, radius),
            currentLocationCheck = currentLocationCheck,
        )
    }

    private fun measure(center: GeoPoint, point: GeoPoint, radiusMeters: Double): PointRadiusCheck.Measured {
        val distance = GeoDistance.meters(center, point)
        return PointRadiusCheck.Measured(distanceMeters = distance, within = GeoDistance.isWithin(distance, radiusMeters))
    }
}
