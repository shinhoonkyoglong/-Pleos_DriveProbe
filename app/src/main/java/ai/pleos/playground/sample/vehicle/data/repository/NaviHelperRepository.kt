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
     *
     * **실측 한계(에뮬레이터 Pleos Connect 2.1.0 / 내비 3.3.2 / NaviHelper SDK 3.0.5, 2026-11-10)** — 실제 SDK 로 구현할 때 주의.
     * 근거: 2번 저장소 MyApplication/docs/pleos/api-notes/navi-runtime-check.md §10.2·§10.3·§10.4·§10.8.
     * - 내비가 경로 옵션을 무시한다: `requestRoute(RouteInfo(routeOption = FREE))`, `changeRouteOption(FREE)` 모두 적용 옵션 RECOMMENDED·통행료 그대로.
     * - `requestRoute` 는 확인 없이 현재 안내를 덮어쓰고 바로 안내를 시작한다(사용자가 설정한 목적지·옵션이 사라질 수 있음). "탐색만" 하는 SDK 기능은 없다.
     * - 고속도로 회피 옵션은 SDK 에 없다(RouteOption 에 회피 값 없음). [DrivingConditions.avoidHighways]·`maxSpeedLimitKmh` 는 경로로 보장할 수 없다.
     * - `RouteInfo` 는 위도·경도가 필수라 `destination: String` 만으로는 요청을 만들 수 없다.
     *
     * 실제로 확인된 무료도로 흐름: 사용자가 내비 "경로 설정"에서 "유료도로 우회"를 켠다 →
     * [getRouteStateInfo] 의 `routeOption == FREE` 로 확인한다(2번 '출발 전' 어댑터가 이 방식, docs/departure/README.md 6절 한계 3).
     * 현재 구현은 SDK 를 부르지 않는 목업이다(시그니처·동작 변경 없음).
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
     * 차량 설정의 "내비게이션" 탭을 연다 (requestNavigationScreen(NavigationScreenRequest.Settings), 권한 NAVI_CUSTOM_MAP).
     * 그 화면 "경로" 절에 전역 "유료도로 우회" 토글이 있다(에뮬레이터 실측 2026-11-11). 설정값은 바꾸지 않는다.
     * 미연결이면 failure(UNAVAILABLE) — Mock 으로 성공을 지어내지 않는다(화면을 연 것처럼 보이면 안 되므로).
     */
    suspend fun openNaviSettings(): Result<Unit>

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
