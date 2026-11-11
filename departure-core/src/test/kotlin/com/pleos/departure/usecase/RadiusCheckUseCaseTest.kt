package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.PointRadiusCheck
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.model.UnavailableReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiusCheckUseCaseTest {

    private val home = GeoPoint(latitude = 37.5563, longitude = 126.9220)
    private val condition = DriverCondition(radiusCenter = home, allowedRadiusMeters = 10_000.0)

    private fun northOf(meters: Double) = GeoPoint(
        latitude = home.latitude + Math.toDegrees(meters / GeoDistance.EARTH_MEAN_RADIUS_METERS),
        longitude = home.longitude,
    )

    private fun present(point: GeoPoint) =
        DataResult.Available(NaviDestination.Present(Place(point = point, displayName = "목적지")))

    private fun check(
        navi: ScriptedNavi,
        condition: DriverCondition = this.condition,
        config: DepartureConfig = DepartureConfig(),
    ): RadiusCheckResult = runBlocking { RadiusCheckUseCase(navi, config).check(condition) }

    @Test
    fun destinationNotSet_isNotAWarning_andLocationIsNotQueried() {
        val navi = ScriptedNavi(destination = DataResult.Available(NaviDestination.NotSet))
        assertEquals(RadiusCheckResult.DestinationNotSet, check(navi))
        assertEquals(0, navi.locationCalls)
    }

    @Test
    fun destinationUnavailable_keepsReason() {
        val navi = ScriptedNavi(destination = DataResult.Unavailable(UnavailableReason.TIMEOUT))
        assertEquals(RadiusCheckResult.DestinationUnavailable(UnavailableReason.TIMEOUT), check(navi))
    }

    @Test
    fun centerNotSet_isUnknown() {
        val navi = ScriptedNavi(destination = present(northOf(1_000.0)))
        val r = check(navi, condition = DriverCondition())
        assertTrue(r is RadiusCheckResult.CenterNotSet)
        assertEquals(0, navi.locationCalls)
    }

    @Test
    fun exactlyOnRadius_isWithin_andJustOutsideWarns() {
        val onBoundary = check(ScriptedNavi(destination = present(northOf(10_000.0)))) as RadiusCheckResult.Checked
        // 북쪽 10,000m 점의 계산 거리는 10,000 ± 1e-6m. 경계 처리는 GeoDistanceTest 가 정확히 고정한다.
        assertEquals(10_000.0, onBoundary.destinationCheck.distanceMeters, 1e-6)

        val inside = check(ScriptedNavi(destination = present(northOf(9_999.5)))) as RadiusCheckResult.Checked
        assertFalse(inside.isDestinationOutside)

        val outside = check(ScriptedNavi(destination = present(northOf(10_000.5)))) as RadiusCheckResult.Checked
        assertTrue(outside.isDestinationOutside)
        assertEquals(10_000.0, outside.radiusMeters, 0.0)
    }

    @Test
    fun exactRadiusFromDriverCondition_isWithin() {
        val point = northOf(10_000.0)
        val exact = condition.copy(allowedRadiusMeters = GeoDistance.meters(home, point))
        val r = check(ScriptedNavi(destination = present(point)), condition = exact) as RadiusCheckResult.Checked
        assertFalse(r.isDestinationOutside)
    }

    @Test
    fun radiusFallsBackToConfigDefault() {
        val noRadius = condition.copy(allowedRadiusMeters = null)
        val navi = ScriptedNavi(destination = present(northOf(4_000.0)))
        val r = check(navi, condition = noRadius, config = DepartureConfig(defaultAllowedRadiusMeters = 3_000.0)) as RadiusCheckResult.Checked
        assertEquals(3_000.0, r.radiusMeters, 0.0)
        assertTrue(r.isDestinationOutside)
    }

    @Test
    fun locationUnavailable_judgesDestinationOnly() {
        val navi = ScriptedNavi(
            destination = present(northOf(4_000.0)),
            location = DataResult.Unavailable(UnavailableReason.NO_DATA),
        )
        val r = check(navi) as RadiusCheckResult.Checked
        assertFalse(r.isDestinationOutside)
        assertEquals(PointRadiusCheck.Unavailable(UnavailableReason.NO_DATA), r.currentLocationCheck)
    }

    @Test
    fun currentLocationIsMeasuredSeparately_andDoesNotDriveWarning() {
        // 에뮬레이터 상황: 내비 위치는 성남 기본 위치(집에서 약 22km), 목적지는 반경 안.
        val naviDefault = GeoPoint(latitude = 37.4127212, longitude = 127.094681)
        val navi = ScriptedNavi(destination = present(northOf(4_000.0)), location = DataResult.Available(naviDefault))
        val r = check(navi) as RadiusCheckResult.Checked
        assertFalse(r.isDestinationOutside)
        val location = r.currentLocationCheck as PointRadiusCheck.Measured
        assertFalse(location.within)
        assertEquals(22_070.0, location.distanceMeters, 10.0)
    }

    @Test
    fun alreadyFetchedDestination_isNotQueriedAgain() {
        val navi = ScriptedNavi(destination = DataResult.Unavailable(UnavailableReason.TIMEOUT))
        val r = runBlocking { RadiusCheckUseCase(navi, DepartureConfig()).check(condition, present(northOf(1_000.0))) }
        assertTrue(r is RadiusCheckResult.Checked)
        assertEquals(0, navi.destinationCalls)
    }

    /** 리뷰 task-05 MINOR-2: 어댑터가 (0,0)을 Present 로 넘겨도 반경 밖 오경고를 내지 않는다. */
    @Test
    fun nullIslandDestination_isNotSet_notOutsideWarning() {
        val navi = ScriptedNavi(destination = present(GeoPoint(latitude = 0.0, longitude = 0.0)))
        assertEquals(RadiusCheckResult.DestinationNotSet, check(navi))
    }

    /** 리뷰 task-05 MINOR-4: 이미 조회한 위치를 넘기면 다시 조회하지 않는다. */
    @Test
    fun givenCurrentLocation_isNotQueriedAgain() {
        val navi = ScriptedNavi(destination = present(northOf(1_000.0)))
        val r = runBlocking {
            RadiusCheckUseCase(navi, DepartureConfig()).check(condition, currentLocation = DataResult.Available(home))
        } as RadiusCheckResult.Checked
        assertEquals(0, navi.locationCalls)
        assertEquals(0.0, (r.currentLocationCheck as PointRadiusCheck.Measured).distanceMeters, 1e-9)
    }
}
