package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.model.DrivingState
import ai.pleos.playground.sample.vehicle.data.model.NaviDestinationInfo
import ai.pleos.playground.sample.vehicle.data.model.NaviHelperException
import ai.pleos.playground.sample.vehicle.data.model.NaviLocation
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.model.NaviRouteState
import ai.pleos.playground.sample.vehicle.data.model.NaviTripInfo
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** 1번 Repository 값 → 2번 core 모델 변환 규칙 검증. */
class DepartureMappersTest {

    private val seoulStation = NaviDestinationInfo(
        latitude = 37.5547, longitude = 126.9707,
        name = "서울역", address = "용산구 동자동", poiId = "probe-seoul-station",
        remainDistanceMeters = 23000, remainTimeSeconds = 2700,
    )

    // --- 주행 상태 ---

    @Test
    fun drivingState_onlyDrivingIsRestricted_othersUnknown() {
        assertEquals(DrivingRestriction.RESTRICTED, DepartureMappers.drivingRestriction(DrivingState.DRIVING))
        // IDLE 은 기본값이라 실차 주행 중에도 IDLE 일 수 있다 → UNRESTRICTED 로 매핑하지 않는다
        assertEquals(DrivingRestriction.UNKNOWN, DepartureMappers.drivingRestriction(DrivingState.IDLE))
        assertEquals(DrivingRestriction.UNKNOWN, DepartureMappers.drivingRestriction(DrivingState.STOPPED))
    }

    // --- 목적지 ---

    @Test
    fun destination_present_mapsPlace() {
        val result = DepartureMappers.destination(Result.success(seoulStation), acceptMock = false)
        val place = ((result as DataResult.Available).value as NaviDestination.Present).place
        assertEquals(GeoPoint(latitude = 37.5547, longitude = 126.9707), place.point)
        assertEquals("서울역", place.displayName)
        assertEquals("probe-seoul-station", place.poiId)
        assertEquals("용산구 동자동", place.address)
    }

    @Test
    fun destination_emptyName_fallsBackToAddressThenDefault() {
        val noName = seoulStation.copy(name = "", poiId = "")
        val place = ((DepartureMappers.destination(Result.success(noName), false) as DataResult.Available).value
            as NaviDestination.Present).place
        assertEquals("용산구 동자동", place.displayName)
        assertNull(place.poiId)
        assertNull(place.poiName)

        val nothing = noName.copy(address = "")
        val place2 = ((DepartureMappers.destination(Result.success(nothing), false) as DataResult.Available).value
            as NaviDestination.Present).place
        assertEquals("내비 목적지", place2.displayName)
    }

    @Test
    fun destination_nullOrZeroCoordinates_isNotSet() {
        assertEquals(
            DataResult.Available(NaviDestination.NotSet),
            DepartureMappers.destination(Result.success(null), false),
        )
        assertEquals(
            DataResult.Available(NaviDestination.NotSet),
            DepartureMappers.destination(Result.success(seoulStation.copy(latitude = 0.0, longitude = 0.0)), false),
        )
    }

    @Test
    fun destination_mockRejectedUnlessAccepted() {
        val mock = Result.success(seoulStation.copy(isMock = true))
        assertReason(UnavailableReason.NOT_SUPPORTED, DepartureMappers.destination(mock, acceptMock = false))
        assertTrue(DepartureMappers.destination(mock, acceptMock = true) is DataResult.Available)
    }

    /**
     * 리뷰 task-12c MAJOR: NaviHelper 미연결 + Mock 목적지 미주입이면 1번 Impl 은 success(null)이 아니라
     * failure(NaviHelperException(UNAVAILABLE))를 준다 → "목적지 없음(NotSet)"이 아니라 확인 불가여야 한다.
     */
    @Test
    fun destination_notConnectedWithoutMock_isUnavailableNotNotSet() {
        val notConnected = Result.failure<NaviDestinationInfo?>(
            NaviHelperException(NaviHelperException.Reason.UNAVAILABLE, "NaviHelper 미연결 — 목적지를 알 수 없음(Mock 목적지 미주입)")
        )
        assertReason(UnavailableReason.NOT_SUPPORTED, DepartureMappers.destination(notConnected, acceptMock = false))
        // Mock 을 받아들이는 시연 설정에서도 지어낸 NotSet 이 나오지 않는다
        assertReason(UnavailableReason.NOT_SUPPORTED, DepartureMappers.destination(notConnected, acceptMock = true))
    }

    @Test
    fun failureReasons_mapToUnavailableReason() {
        fun fail(reason: NaviHelperException.Reason) =
            DepartureMappers.destination(Result.failure(NaviHelperException(reason, "x")), false)
        assertReason(UnavailableReason.NOT_SUPPORTED, fail(NaviHelperException.Reason.UNAVAILABLE))
        assertReason(UnavailableReason.TIMEOUT, fail(NaviHelperException.Reason.TIMEOUT))
        assertReason(UnavailableReason.SERVICE_ERROR, fail(NaviHelperException.Reason.SERVICE_ERROR))
        assertReason(UnavailableReason.PERMISSION_DENIED, fail(NaviHelperException.Reason.PERMISSION_DENIED))
        assertReason(
            UnavailableReason.SERVICE_ERROR,
            DepartureMappers.destination(Result.failure(IllegalStateException("x")), false),
        )
    }

    // --- 경로 상태 ---

    @Test
    fun routeState_guidingFree_withTollFare() {
        val result = DepartureMappers.routeState(
            Result.success(NaviRouteState(NaviMode.ROUTE_GUIDING, "FREE", tollFare = 0)), false,
        )
        assertEquals(DataResult.Available(RouteState.Guiding(RouteOption.FREE, 0)), result)
    }

    @Test
    fun routeState_simulationGuiding_isGuiding_tollFareUnknown() {
        val result = DepartureMappers.routeState(
            Result.success(NaviRouteState(NaviMode.SIMULATION_GUIDING, "RECOMMENDED", tollFare = null)), false,
        )
        assertEquals(DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, null)), result)
    }

    @Test
    fun routeState_safeGuiding_isNotGuiding() {
        val result = DepartureMappers.routeState(
            Result.success(NaviRouteState(NaviMode.SAFE_GUIDING, "RECOMMENDED", tollFare = null)), false,
        )
        assertEquals(DataResult.Available(RouteState.NotGuiding), result)
    }

    @Test
    fun routeState_notWorkingOrUnknownOption_isUnavailable() {
        assertReason(
            UnavailableReason.NO_DATA,
            DepartureMappers.routeState(Result.success(NaviRouteState(NaviMode.NOT_WORKING, "RECOMMENDED", null)), false),
        )
        assertReason(
            UnavailableReason.NO_DATA,
            DepartureMappers.routeState(Result.success(NaviRouteState(NaviMode.ROUTE_GUIDING, "NEW_OPTION", null)), false),
        )
    }

    // --- 사전 ETA ---

    private val destinationPlace = Place(point = GeoPoint(latitude = 37.5547, longitude = 126.9707), displayName = "서울역")
    private val now = Instant.parse("2026-11-10T07:00:00Z")

    @Test
    fun trip_mapsSecondsAndOption() {
        val result = DepartureMappers.trip(
            Result.success(NaviTripInfo(distanceMeters = 26494, etaSeconds = 4585, routeOption = "FREE")),
            destinationPlace, now, acceptMock = false,
        )
        val trip = (result as DataResult.Available).value
        assertEquals(Duration.ofSeconds(4585), trip.duration)
        assertEquals(26494, trip.distanceMeters)
        assertEquals(EstimateSource.SDK_MEASURED, trip.source)
        assertEquals(now, trip.basis)
        assertEquals(RouteOption.FREE, trip.routeOption)
        assertEquals(destinationPlace.point, trip.destination)
    }

    @Test
    fun trip_notGuiding_routeOptionNull() {
        val result = DepartureMappers.trip(
            Result.success(NaviTripInfo(23799, 2704, routeOption = null)), destinationPlace, now, false,
        )
        assertNull((result as DataResult.Available).value.routeOption)
    }

    @Test
    fun trip_epochLikeEta_rejected() {
        assertReason(
            UnavailableReason.SERVICE_ERROR,
            DepartureMappers.trip(Result.success(NaviTripInfo(1, 1_794_207_288L, null)), destinationPlace, now, false),
        )
    }

    @Test
    fun trip_mock_rejectedByDefault_estimatedWhenAccepted() {
        val mock = Result.success(NaviTripInfo(1000, 90, null, isMock = true))
        assertReason(UnavailableReason.NOT_SUPPORTED, DepartureMappers.trip(mock, destinationPlace, now, false))
        val accepted = DepartureMappers.trip(mock, destinationPlace, now, acceptMock = true)
        assertEquals(EstimateSource.ESTIMATED, (accepted as DataResult.Available).value.source)
    }

    // --- 현재 위치 ---

    @Test
    fun location_validZeroAndMock() {
        assertEquals(
            DataResult.Available(GeoPoint(latitude = 37.4127212, longitude = 127.094681)),
            DepartureMappers.location(Result.success(NaviLocation(37.4127212, 127.094681)), false),
        )
        assertReason(UnavailableReason.NO_DATA, DepartureMappers.location(Result.success(NaviLocation(0.0, 0.0)), false))
        assertReason(
            UnavailableReason.NOT_SUPPORTED,
            DepartureMappers.location(Result.success(NaviLocation(37.46, 127.04, isMock = true)), false),
        )
    }

    private fun assertReason(expected: UnavailableReason, actual: DataResult<*>) {
        assertTrue("expected Unavailable($expected) but was $actual", actual is DataResult.Unavailable)
        assertEquals(expected, (actual as DataResult.Unavailable).reason)
    }
}
