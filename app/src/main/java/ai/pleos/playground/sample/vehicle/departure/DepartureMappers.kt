package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.model.NaviDestinationInfo
import ai.pleos.playground.sample.vehicle.data.model.NaviHelperException
import ai.pleos.playground.sample.vehicle.data.model.NaviLocation
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.model.NaviRouteState
import ai.pleos.playground.sample.vehicle.data.model.NaviTripInfo
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionState
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import java.time.Duration
import java.time.Instant

/**
 * 1번 Repository 값 → 2번 core 도메인 모델 변환(순수 함수만, 단위 테스트 대상).
 *
 * 규칙의 근거는 2번 core 계약 KDoc(com.pleos.departure.port.CommonRepositoryContract)과
 * MyApplication/docs/pleos/api-notes/navi-runtime-check.md 다.
 *
 * Mock 값(isMock = true, NaviHelper 서비스 미연결 시 1번 Repository 가 지어낸 값)은 [acceptMock] 이 false 면
 * Unavailable(NOT_SUPPORTED)로 바꾼다. 지어낸 위치·ETA 로 반경·출발 마감을 판정하면 운전자에게 거짓 안내가 되기 때문이다.
 */
internal object DepartureMappers {

    /** SDK 가 tripInfo.eta 를 epoch 로 줄 가능성 방어(실측은 남은 초). 이보다 크면 받지 않는다. */
    private const val MAX_PLAUSIBLE_ETA_SECONDS = 1_000_000_000L

    private const val DEFAULT_DESTINATION_NAME = "내비 목적지"

    /**
     * 1번 uxRestriction(AAOS CarUxRestrictions) → 2번 주행 규제.
     * - car service 실제 값(CAR_SERVICE): requiresDistractionOptimization true → RESTRICTED, false → UNRESTRICTED
     *   (에뮬레이터 실측: Park = false/0x0, Drive = true/0x50 — api-notes/vehicle-state.md §13.3)
     * - UNAVAILABLE(연결 실패·미지원 기기·오류) → RESTRICTED. 실패는 설정(treatUnknownDrivingAsRestricted)과 무관하게 제한이다
     *   (api-notes/vehicle-state.md §13.4 "실패 → 제한", 검토 #14b MAJOR-2 (a)).
     * - null(아직 첫 값을 못 읽음 — 연결 대기 중) → UNKNOWN. 기본 설정(treatUnknownDrivingAsRestricted = true)은 UNKNOWN 을 제한으로 본다.
     * - MOCK(injectMockUxRestriction): **UNRESTRICTED 를 내지 않는다**(지어낸 값으로 조작을 열지 않기 위해).
     *   true → RESTRICTED(안전 쪽이라 그대로), false → UNKNOWN.
     * 1번 drivingState(수동 setDrivingState)는 더 이상 쓰지 않는다.
     */
    fun drivingRestriction(state: UxRestrictionState?): DrivingRestriction = when (state?.source) {
        UxRestrictionSource.CAR_SERVICE ->
            if (state.requiresDistractionOptimization) DrivingRestriction.RESTRICTED else DrivingRestriction.UNRESTRICTED
        UxRestrictionSource.MOCK ->
            if (state.requiresDistractionOptimization) DrivingRestriction.RESTRICTED else DrivingRestriction.UNKNOWN
        UxRestrictionSource.UNAVAILABLE -> DrivingRestriction.RESTRICTED
        null -> DrivingRestriction.UNKNOWN
    }

    /**
     * 내비 목적지. 목적지 없음(null)·좌표 (0,0)은 NotSet(정상 상태 — Unavailable 로 표현하지 않는다).
     * displayName = name → address → "내비 목적지". 빈 문자열 POI 값은 null.
     */
    fun destination(result: Result<NaviDestinationInfo?>, acceptMock: Boolean): DataResult<NaviDestination> {
        val info = result.getOrElse { return unavailable(it) }
            ?: return DataResult.Available(NaviDestination.NotSet)
        if (info.isMock && !acceptMock) return mockRejected("destination")
        if (info.latitude == 0.0 && info.longitude == 0.0) return DataResult.Available(NaviDestination.NotSet)
        val point = geoPointOrNull(info.latitude, info.longitude)
            ?: return DataResult.Unavailable(UnavailableReason.NO_DATA, "목적지 좌표 범위 밖: ${info.latitude},${info.longitude}")
        val displayName = info.name.ifBlank { info.address.ifBlank { DEFAULT_DESTINATION_NAME } }
        return DataResult.Available(
            NaviDestination.Present(
                Place(
                    point = point,
                    displayName = displayName,
                    poiId = info.poiId.ifBlank { null },
                    poiName = info.name.ifBlank { null },
                    address = info.address.ifBlank { null },
                    poiSubId = info.poiSubId?.ifBlank { null },
                )
            )
        )
    }

    /**
     * 경로 상태. ROUTE_GUIDING·SIMULATION_GUIDING → Guiding, SAFE_GUIDING → NotGuiding,
     * NOT_WORKING(의미 미확인)·모르는 옵션 이름 → Unavailable(NO_DATA).
     */
    fun routeState(result: Result<NaviRouteState>, acceptMock: Boolean): DataResult<RouteState> {
        val state = result.getOrElse { return unavailable(it) }
        if (state.isMock && !acceptMock) return mockRejected("routeState")
        return when (state.naviMode) {
            NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING -> {
                val option = routeOption(state.routeOption)
                    ?: return DataResult.Unavailable(UnavailableReason.NO_DATA, "알 수 없는 routeOption: ${state.routeOption}")
                DataResult.Available(
                    RouteState.Guiding(
                        appliedOption = option,
                        tollFare = state.tollFare?.takeIf { it >= 0 },
                    )
                )
            }
            NaviMode.SAFE_GUIDING -> DataResult.Available(RouteState.NotGuiding)
            NaviMode.NOT_WORKING -> DataResult.Unavailable(UnavailableReason.NO_DATA, "naviMode=NOT_WORKING")
        }
    }

    /**
     * 사전 ETA. eta = 남은 초. routeOption 은 안내 중일 때 같은 시점 경로 옵션(아니면 null).
     * Mock 값을 받아들이는 경우 출처는 ESTIMATED(실측 아님).
     */
    fun trip(
        result: Result<NaviTripInfo>,
        destination: Place,
        now: Instant,
        acceptMock: Boolean,
    ): DataResult<TripEstimate> {
        val trip = result.getOrElse { return unavailable(it) }
        if (trip.isMock && !acceptMock) return mockRejected("trip")
        if (trip.etaSeconds < 0 || trip.etaSeconds > MAX_PLAUSIBLE_ETA_SECONDS) {
            return DataResult.Unavailable(UnavailableReason.SERVICE_ERROR, "비정상 eta: ${trip.etaSeconds}")
        }
        return DataResult.Available(
            TripEstimate(
                duration = Duration.ofSeconds(trip.etaSeconds),
                distanceMeters = trip.distanceMeters.takeIf { it >= 0 },
                source = if (trip.isMock) EstimateSource.ESTIMATED else EstimateSource.SDK_MEASURED,
                basis = now,
                routeOption = routeOption(trip.routeOption),
                destination = destination.point,
            )
        )
    }

    /** 현재 위치. 좌표 (0,0)·범위 밖은 Unavailable(NO_DATA). */
    fun location(result: Result<NaviLocation>, acceptMock: Boolean): DataResult<GeoPoint> {
        val location = result.getOrElse { return unavailable(it) }
        if (location.isMock && !acceptMock) return mockRejected("location")
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            return DataResult.Unavailable(UnavailableReason.NO_DATA, "위치 (0,0)")
        }
        return geoPointOrNull(location.latitude, location.longitude)
            ?.let { DataResult.Available(it) }
            ?: DataResult.Unavailable(UnavailableReason.NO_DATA, "위치 범위 밖: ${location.latitude},${location.longitude}")
    }

    /** SDK RouteOption 이름 → 2번 RouteOption. 모르는 이름·null 은 null. */
    fun routeOption(name: String?): RouteOption? = RouteOption.entries.firstOrNull { it.name == name }

    /** 1번 실패(NaviHelperException 사유) → 2번 Unavailable 사유. */
    fun unavailable(error: Throwable): DataResult.Unavailable {
        val reason = when (error) {
            is NaviHelperException -> when (error.reason) {
                NaviHelperException.Reason.UNAVAILABLE -> UnavailableReason.NOT_SUPPORTED
                NaviHelperException.Reason.TIMEOUT -> UnavailableReason.TIMEOUT
                NaviHelperException.Reason.SERVICE_ERROR -> UnavailableReason.SERVICE_ERROR
                NaviHelperException.Reason.PERMISSION_DENIED -> UnavailableReason.PERMISSION_DENIED
            }
            is SecurityException -> UnavailableReason.PERMISSION_DENIED
            else -> UnavailableReason.SERVICE_ERROR
        }
        return DataResult.Unavailable(reason, error.message)
    }

    private fun mockRejected(what: String): DataResult.Unavailable =
        DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED, "NaviHelper 미연결 — Mock $what 값은 쓰지 않음")

    private fun geoPointOrNull(latitude: Double, longitude: Double): GeoPoint? =
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude = latitude, longitude = longitude) else null
}
