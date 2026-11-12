package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.sample.vehicle.data.model.NaviDestinationInfo
import ai.pleos.playground.sample.vehicle.data.model.NaviLocation
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.model.NaviRouteState
import ai.pleos.playground.sample.vehicle.data.model.NaviTripInfo
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [시연용, debug 빌드 전용] 내비가 응답하지 않을 때 '출발 전' 확인이 비지 않도록 예시 값을 대신 돌려준다.
 *
 * 왜 필요한가: 에뮬레이터 내비가 "서비스 연결 중"에 멈추면 NaviHelper 는 연결은 되지만 조회 콜백이 오지 않는다.
 * 이때 1번 Repository 의 Mock Fallback(연결 실패 시에만 동작)도 타지 않아 2번 화면이 전부 "응답이 없어요"가 된다.
 *
 * 동작:
 * - 조회 4개(현재 위치·목적지·경로 상태·ETA)만 바꾼다. 나머지는 실제 Repository 에 그대로 넘긴다.
 * - 먼저 실제 내비에 묻고, **실패(시간 초과 등)했을 때만** 예시 값을 쓴다 → 내비가 정상이면 실제 값이 나온다.
 * - 한 번 실패하면 [SKIP_REAL_MS] 동안은 실제 조회를 건너뛴다(조회마다 5~10초 시간 초과를 기다리지 않게).
 * - 예시 값은 2번이 에뮬레이터에서 실측한 값(docs/departure/README.md 8절): 수정구 시흥동 → 서울역 23km·약 46분, 추천 경로·통행료 700원.
 *
 * 주의: 예시 값은 isMock = false 로 낸다. Mock 값은 2번 어댑터·운전 조건 화면이 일부러 버리기 때문이다(반경 중심을 못 정함).
 * 대신 [usingDemo] 를 화면에 "내비 예시 데이터"로 반드시 표시한다. release 빌드에서는 만들지 않는다(DriveProbeApplication).
 */
class DemoNaviFallbackRepository(
    private val real: NaviHelperRepository,
) : NaviHelperRepository by real {

    private val _usingDemo = MutableStateFlow(false)
    /** 지금 예시 값을 쓰는 중이면 true → 화면에 "내비 예시 데이터" 표시 */
    val usingDemo: StateFlow<Boolean> = _usingDemo.asStateFlow()

    @Volatile private var skipRealUntil = 0L

    override suspend fun getCurrentLocation(): Result<NaviLocation> =
        orDemo("getCurrentLocation", { real.getCurrentLocation() }) { DEMO_LOCATION }

    override suspend fun getDestinationInfo(): Result<NaviDestinationInfo?> =
        orDemo("getDestinationInfo", { real.getDestinationInfo() }) { DEMO_DESTINATION }

    override suspend fun getRouteStateInfo(): Result<NaviRouteState> =
        orDemo("getRouteStateInfo", { real.getRouteStateInfo() }) { DEMO_ROUTE_STATE }

    override suspend fun requestTrip(
        latitude: Double,
        longitude: Double,
        poiName: String,
        poiId: String,
    ): Result<NaviTripInfo> =
        orDemo("requestTrip", { real.requestTrip(latitude, longitude, poiName, poiId) }) { DEMO_TRIP }

    private suspend fun <T> orDemo(name: String, call: suspend () -> Result<T>, demo: () -> T): Result<T> {
        if (SystemClock.elapsedRealtime() < skipRealUntil) return Result.success(demo())
        val result = call()
        // 실제 값(목적지 없음 = success(null) 포함)을 받았고 1번 Mock 도 아니면 실제 값을 그대로 쓴다
        val value = result.getOrNull()
        if (result.isSuccess && !isMockValue(value)) {
            _usingDemo.value = false
            return result
        }
        Log.w(TAG, "[DEMO] $name 실제 내비 응답 없음 → 예시 값 사용 (${result.exceptionOrNull()?.message ?: "Mock 값"})")
        skipRealUntil = SystemClock.elapsedRealtime() + SKIP_REAL_MS
        _usingDemo.value = true
        return Result.success(demo())
    }

    private fun isMockValue(value: Any?): Boolean = when (value) {
        is NaviLocation -> value.isMock
        is NaviDestinationInfo -> value.isMock
        is NaviRouteState -> value.isMock
        is NaviTripInfo -> value.isMock
        else -> false
    }

    companion object {
        private const val TAG = "DemoNavi"
        private const val SKIP_REAL_MS = 60_000L

        /** 에뮬레이터 내비 기본 위치(실측 2026-11-10) */
        val DEMO_LOCATION = NaviLocation(latitude = 37.4127212, longitude = 127.094681, address = "수정구 시흥동")

        /** 서울역(실측 2026-11-10) */
        val DEMO_DESTINATION = NaviDestinationInfo(
            latitude = 37.5547,
            longitude = 126.9707,
            name = "서울역",
            address = "용산구 동자동",
            poiId = "demo-seoul-station",
            remainDistanceMeters = 23_000,
            remainTimeSeconds = 2_700,
        )

        /** 안내 중 · 추천 경로 · 통행료 700원 → 무료도로 선호면 "유료도로 우회를 켜 주세요" 안내가 나온다 */
        val DEMO_ROUTE_STATE = NaviRouteState(naviMode = NaviMode.ROUTE_GUIDING, routeOption = "RECOMMENDED", tollFare = 700)

        val DEMO_TRIP = NaviTripInfo(distanceMeters = 23_799, etaSeconds = 2_750, routeOption = "RECOMMENDED")
    }
}
