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

// =============================================================================
// NaviHelper 조회 계열 모델 (2번 '출발 전' 연동에서 추가 — 기존 모델은 변경 없음)
// 근거: MyApplication/docs/pleos/api-notes/navi-route.md, eta-location.md, navi-runtime-check.md
//       (에뮬레이터 2.1.0 / 내비 3.3.2 / NaviHelper SDK 3.0.5 실행 확인)
// =============================================================================

/**
 * 내비 현재 위치 (getCurrentLocationInfo → onCurrentLocationInfo).
 * @param isMock SDK 연결 실패로 Mock 값(setMockLocation)을 돌려준 경우 true
 */
data class NaviLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val isMock: Boolean = false
)

/**
 * 내비에 지금 설정된 목적지 (getDestinationInfo → onDestinationInfo).
 * 사람이 내비 화면에서 직접 넣은 목적지도 좌표·이름까지 온다(주차 중 포함).
 * 목적지가 없으면 SDK 는 0 값 객체를 주며, Repository 는 이를 null 로 바꿔 돌려준다(이 타입으로 (0,0)을 만들지 않는다).
 *
 * @param name SDK 로 설정한 목적지는 빈 문자열일 수 있다
 * @param address 내비가 역지오코딩한 구·동(예: "용산구 동자동")
 * @param remainDistanceMeters 남은 거리(m, km 단위로 뭉개진 값)
 * @param remainTimeSeconds 남은 시간(초, 분 단위로 뭉개진 값). 주차 중에는 갱신되지 않는다
 * @param isMock SDK 연결 실패로 Mock 값(injectMockDestination)을 돌려준 경우 true
 */
data class NaviDestinationInfo(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val address: String,
    val poiId: String,
    val poiSubId: String? = null,
    val remainDistanceMeters: Int = 0,
    val remainTimeSeconds: Int = 0,
    val isMock: Boolean = false
)

/** 내비 안내 모드 (SDK NaviMode 와 같은 값). 경로 없음 = SAFE_GUIDING, 목적지 설정 = ROUTE_GUIDING */
enum class NaviMode {
    ROUTE_GUIDING,
    SIMULATION_GUIDING,
    SAFE_GUIDING,
    NOT_WORKING
}

/**
 * 현재 경로 상태 (getRouteStateInfo → onRouteStateInfo + 마지막 onRouteStarted.tollFare).
 *
 * @param routeOption 내비가 **보고한** 적용 옵션. SDK RouteOption 이름 그대로(RECOMMENDED, FREE, ...).
 *   사용자가 내비 "경로 설정"에서 "유료도로 우회"를 켜면 FREE 가 된다. requestRoute/changeRouteOption 으로는 바뀌지 않았다(실측).
 * @param tollFare 현재 경로의 onRouteStarted.tollFare(원). 다시 조회할 수 없는 값이라 리스너로 받아 둔 것이며,
 *   못 받았거나 onRouteCancelled·리스너 재등록·목적지 좌표 변경 이후면 null. DestinationInfo.tollFare 는 항상 0이라 쓰지 않는다.
 * @param isMock SDK 연결 실패로 Mock 값을 돌려준 경우 true
 */
data class NaviRouteState(
    val naviMode: NaviMode,
    val routeOption: String,
    val tollFare: Int?,
    val isMock: Boolean = false
)

/**
 * 안내를 시작하지 않는 사전 ETA (requestTrip(TripRequest) → onTripInfo).
 *
 * @param etaSeconds TripInfo.eta = **남은 초**(epoch 아님, 실측)
 * @param routeOption 안내 중이면 같은 시점 RouteStateInfo.routeOption, 아니면 null.
 *   requestTrip 결과는 내비가 그 순간 쓰는 "유료도로 우회" 설정을 따르기 때문에 함께 돌려준다.
 * @param isMock SDK 연결 실패로 직선거리 기반 추정값을 돌려준 경우 true (실측 값 아님)
 */
data class NaviTripInfo(
    val distanceMeters: Int,
    val etaSeconds: Long,
    val routeOption: String?,
    val isMock: Boolean = false
)

/**
 * NaviHelper 조회 실패 사유. Result.failure 로 전달된다.
 * (호출하는 쪽이 "내비 없음"과 "타임아웃"을 구분해 안내할 수 있게 사유를 나눈다)
 */
class NaviHelperException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    enum class Reason {
        /** NaviHelper 서비스에 연결할 수 없다(내비 미탑재·초기화 실패) */
        UNAVAILABLE,
        /** 요청은 보냈지만 콜백이 제한 시간 안에 오지 않았다 */
        TIMEOUT,
        /** 내비 처리 실패(onError CAUSE_SEARCH_TRIP 등) */
        SERVICE_ERROR,
        /** 권한(pleos.car.permission.NAVI_ROUTE) 없음 */
        PERMISSION_DENIED
    }
}
