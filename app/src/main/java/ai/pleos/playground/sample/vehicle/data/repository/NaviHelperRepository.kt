package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.sample.vehicle.data.model.DrivingConditions
import ai.pleos.playground.sample.vehicle.data.model.NaviDestinationInfo
import ai.pleos.playground.sample.vehicle.data.model.NaviLocation
import ai.pleos.playground.sample.vehicle.data.model.NaviRouteState
import ai.pleos.playground.sample.vehicle.data.model.NaviTripInfo
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

    // -------------------------------------------------------------------------
    // NaviHelper 조회 계열 (2번 '출발 전' 연동에서 추가 — 실제 NaviHelper SDK 호출, 연결 실패 시 Mock Fallback)
    // 실패는 Result.failure(NaviHelperException(reason)) 로 전달한다.
    // 근거: MyApplication/docs/pleos/api-notes/navi-runtime-check.md §3·§10·§11 (실행 확인)
    // -------------------------------------------------------------------------

    /**
     * 마지막으로 알고 있는 내비 목적지(요청 응답 + 내비가 보내는 푸시로 갱신). 목적지 없음 또는 모름이면 null.
     * 경로 교체 중(onRouteCancelled ~ onRouteStarted, 최대 약 19초)에도 null 이 될 수 있다.
     */
    val currentDestination: StateFlow<NaviDestinationInfo?>

    /** 마지막으로 알고 있는 경로 상태(요청 응답 + 푸시로 갱신). 받은 적 없으면 null. */
    val currentRouteState: StateFlow<NaviRouteState?>

    /**
     * 내비에 지금 설정된 목적지 1회 조회 (getDestinationInfo → onDestinationInfo).
     * 목적지 없음(0 값 객체, 좌표 (0,0))이면 success(null). 경로 교체 중에도 같은 0 값이 와서 구분할 수 없다.
     * NaviHelper 미연결이면: Mock 목적지를 주입했을 때만 success(Mock, isMock = true), 아니면 failure(NaviHelperException(UNAVAILABLE))
     * — 미연결 상태에서 "목적지 없음"(null)을 돌려주지 않는다.
     */
    suspend fun getDestinationInfo(): Result<NaviDestinationInfo?>

    /** 현재 경로 상태 1회 조회 (getRouteStateInfo → onRouteStateInfo) + 현재 경로의 tollFare(받아 둔 경우). 부작용 없음. */
    suspend fun getRouteStateInfo(): Result<NaviRouteState>

    /**
     * 안내를 시작하지 않는 사전 ETA (requestTrip(TripRequest) → onTripInfo). 경로 옵션은 지정할 수 없다(TripRequest 에 필드 없음).
     * poiName/poiId 빈 문자열은 허용된다(실측).
     */
    suspend fun requestTrip(
        latitude: Double,
        longitude: Double,
        poiName: String = "",
        poiId: String = ""
    ): Result<NaviTripInfo>

    /** 현재 위치 1회 (getCurrentLocationInfo → onCurrentLocationInfo). 에뮬레이터에서는 내비 기본 위치에 고정된다. */
    suspend fun getCurrentLocation(): Result<NaviLocation>

    /** [Mock Fallback 전용] SDK 연결이 안 될 때 getDestinationInfo 가 돌려줄 목적지. null 이면 목적지 없음 */
    fun injectMockDestination(destination: NaviDestinationInfo?)

    /** [Mock Fallback 전용] SDK 연결이 안 될 때 getRouteStateInfo 가 보고할 경로 옵션 이름(기본 RECOMMENDED) */
    fun injectMockRouteOption(routeOption: String)
}
