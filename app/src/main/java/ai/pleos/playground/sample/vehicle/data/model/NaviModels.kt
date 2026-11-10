package ai.pleos.playground.sample.vehicle.data.model

/**
 * 고령 운전자 조건부 면허 및 안전 운전 조건
 */
data class DrivingConditions(
    val avoidNightDriving: Boolean = false, // 야간 운전 제한 (일몰 후 주행 제한)
    val avoidHighways: Boolean = false,     // 고속도로 제한 (국도/일반도로 우선)
    val maxSpeedLimitKmh: Int? = null,      // 최고 제한 속도 설정
    val preferComfortableRoute: Boolean = true // 편안한 도로(곡률 적고 차선 넓은 경로) 우선
)

/**
 * 경로 안내 정보
 */
data class RouteInfo(
    val routeId: String,
    val destinationName: String,
    val totalDistanceKm: Double,
    val estimatedDurationMinutes: Int,
    val containsHighway: Boolean,
    val isNightDrivingExpected: Boolean = false,
    val summary: String
)

/**
 * 졸음쉼터 / 휴게소 정보 (3단계 긴급 개입용)
 */
data class RestAreaInfo(
    val id: String,
    val name: String,
    val distanceKm: Double,
    val estimatedArrivalMinutes: Int,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hasRestFacility: Boolean = true
)

/**
 * 네비게이션 현재 상태
 */
enum class NavigationState {
    IDLE,
    CALCULATING,
    NAVIGATING,
    REST_AREA_DIVERTED, // 졸음쉼터로 경로 변경됨
    ARRIVED
}
