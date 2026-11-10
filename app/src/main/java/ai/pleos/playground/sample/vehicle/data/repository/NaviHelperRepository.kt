package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.sample.vehicle.data.model.DrivingConditions
import ai.pleos.playground.sample.vehicle.data.model.NavigationState
import ai.pleos.playground.sample.vehicle.data.model.RestAreaInfo
import ai.pleos.playground.sample.vehicle.data.model.RouteInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * 출발 전 조건 맞춤 경로 안내 및 3단계 졸음쉼터 긴급 안내를 위한 NaviHelper 래핑 Repository
 */
interface NaviHelperRepository {

    /** 현재 내비게이션 상태 Flow */
    val navigationState: StateFlow<NavigationState>

    /** 현재 활성화된 안내 경로 Flow */
    val currentRoute: StateFlow<RouteInfo?>

    /** 현재 위치 기준 주변 졸음쉼터/휴게소 목록 Flow */
    val nearestRestAreas: StateFlow<List<RestAreaInfo>>

    /**
     * 출발 전 조건 맞춤 경로 탐색
     * (고령 운전자 조건부 면허: 야간 주행 회피, 고속도로 회피, 최고제한속도 준수 등)
     */
    suspend fun searchConditionAdaptedRoute(
        destination: String,
        conditions: DrivingConditions
    ): Result<RouteInfo>

    /**
     * 3단계 피로 개입: 가장 가까운 졸음쉼터/휴게소로 즉각 경로 전환 및 안내 시작
     */
    suspend fun guideToNearestRestArea(): Result<RestAreaInfo>

    /** 경로 안내 시작 */
    suspend fun startNavigation(routeId: String): Result<Boolean>

    /** 경로 안내 종료 */
    suspend fun stopNavigation(): Result<Boolean>

    // -------------------------------------------------------------------------
    // 에뮬레이터 테스트 및 모의 데이터 주입 API
    // -------------------------------------------------------------------------
    fun injectMockRestAreas(restAreas: List<RestAreaInfo>)
    fun setMockLocation(latitude: Double, longitude: Double)
}
