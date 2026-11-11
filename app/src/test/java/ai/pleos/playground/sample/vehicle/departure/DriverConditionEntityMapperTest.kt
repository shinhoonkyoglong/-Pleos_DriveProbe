package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.local.entity.DriverConditionEntity
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room Entity ↔ DriverCondition 변환 규칙 검증(계약 driver-condition-api.md §3·§5.3·§7). */
class DriverConditionEntityMapperTest {

    private val full = DriverCondition(
        timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
        routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE, RouteConstraint.AVOID_HIGHWAY),
        radiusCenter = GeoPoint(37.5563, 126.9236),
        allowedRadiusMeters = 10_000.0,
    )

    private fun entity(
        time: String = "",
        route: String = "",
        lat: Double? = null,
        lng: Double? = null,
        radius: Double? = null,
    ) = DriverConditionEntity(
        timeConstraints = time, routeConstraints = route,
        radiusCenterLatitude = lat, radiusCenterLongitude = lng, allowedRadiusMeters = radius,
        updatedAt = 0L,
    )

    private fun assertServiceError(result: DataResult<DriverCondition>) {
        assertTrue("Unavailable 이어야 함: $result", result is DataResult.Unavailable)
        assertEquals(UnavailableReason.SERVICE_ERROR, (result as DataResult.Unavailable).reason)
    }

    @Test
    fun toEntity_fullCondition_sortedNamesAndColumns() {
        val e = DriverConditionEntityMapper.toEntity(full, updatedAt = 1234L)
        assertEquals(DriverConditionEntity.SINGLE_ROW_ID, e.id)
        assertEquals("NO_NIGHT_DRIVING", e.timeConstraints)
        // 계약 §3: enum 이름 오름차순
        assertEquals("AVOID_HIGHWAY,PREFER_TOLL_FREE", e.routeConstraints)
        assertEquals(37.5563, e.radiusCenterLatitude!!, 0.0)
        assertEquals(126.9236, e.radiusCenterLongitude!!, 0.0)
        assertEquals(10_000.0, e.allowedRadiusMeters!!, 0.0)
        assertEquals(1234L, e.updatedAt)
        assertFalse("새로 저장한 값은 미동기화", e.isSynced)
    }

    @Test
    fun roundTrip_fullCondition_equal() {
        val back = DriverConditionEntityMapper.toModel(DriverConditionEntityMapper.toEntity(full, updatedAt = 0L))
        assertEquals(DataResult.Available(full), back)
    }

    @Test
    fun roundTrip_clearedCondition_isEmptyNotMissing() {
        // 해제(빈 조건) ≠ 미설정: 행이 남고 빈 DriverCondition 으로 돌아온다(계약 4.3)
        val e = DriverConditionEntityMapper.toEntity(DriverCondition(), updatedAt = 0L)
        assertEquals("", e.timeConstraints)
        assertEquals("", e.routeConstraints)
        assertNull(e.radiusCenterLatitude)
        assertNull(e.allowedRadiusMeters)
        assertEquals(DataResult.Available(DriverCondition()), DriverConditionEntityMapper.toModel(e))
    }

    @Test
    fun roundTrip_radiusWithoutCenter_allowed() {
        // 계약 4.3: 중심 없이 반경만 있는 조건은 허용
        val c = DriverCondition(allowedRadiusMeters = 5_000.0)
        assertEquals(DataResult.Available(c), DriverConditionEntityMapper.toModel(DriverConditionEntityMapper.toEntity(c, 0L)))
    }

    @Test
    fun roundTrip_coordinateBoundaries_kept() {
        val c = DriverCondition(radiusCenter = GeoPoint(-90.0, 180.0))
        assertEquals(DataResult.Available(c), DriverConditionEntityMapper.toModel(DriverConditionEntityMapper.toEntity(c, 0L)))
    }

    @Test
    fun toModel_unknownTimeConstraint_serviceErrorNotSkipped() {
        // 모르는 값을 건너뛰면 야간 금지 등 제한이 조용히 사라진다 → 조회 실패(계약 §5.3·§6)
        assertServiceError(DriverConditionEntityMapper.toModel(entity(time = "NO_NIGHT_DRIVING,NO_RAIN_DRIVING")))
    }

    @Test
    fun toModel_unknownRouteConstraint_serviceError() {
        assertServiceError(DriverConditionEntityMapper.toModel(entity(route = "prefer_toll_free")))
    }

    @Test
    fun toModel_emptyToken_serviceError() {
        assertServiceError(DriverConditionEntityMapper.toModel(entity(route = "PREFER_TOLL_FREE,")))
    }

    @Test
    fun toModel_onlyLatitude_serviceError() {
        assertServiceError(DriverConditionEntityMapper.toModel(entity(lat = 37.5)))
    }

    @Test
    fun toModel_latitudeOutOfRange_serviceError() {
        assertServiceError(DriverConditionEntityMapper.toModel(entity(lat = 90.0001, lng = 127.0)))
    }

    @Test
    fun toModel_zeroRadius_serviceError() {
        // 모델 require(allowedRadiusMeters > 0) 예외를 잡아 SERVICE_ERROR 로(계약 §7)
        assertServiceError(DriverConditionEntityMapper.toModel(entity(radius = 0.0)))
    }

    @Test
    fun toModel_duplicateNames_mergedIntoSet() {
        val result = DriverConditionEntityMapper.toModel(entity(route = "PREFER_TOLL_FREE,PREFER_TOLL_FREE"))
        assertEquals(
            RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
            (result as DataResult.Available).value.routePreference,
        )
    }
}
