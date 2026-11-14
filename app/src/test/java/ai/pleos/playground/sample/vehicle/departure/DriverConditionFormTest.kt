package ai.pleos.playground.sample.vehicle.departure

import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설정 화면 입력값: 기본값, 저장값 → 입력값, 검증(계약 §4.2), 저장 변환, 반경 [+]/[-] */
class DriverConditionFormTest {

    private val center = GeoPoint(latitude = 37.5563, longitude = 126.9236)
    private val valid = DriverConditionForm.defaults().copy(centerLatitude = center.latitude, centerLongitude = center.longitude)

    @Test
    fun defaults_matchDecidedValues_andHaveNoCenter() {
        val form = DriverConditionForm.defaults()
        assertTrue(form.noNightDriving)
        assertTrue(form.preferTollFree)
        assertFalse(form.avoidHighway)
        assertEquals(50.0, form.radiusKm!!, 0.0)
        assertFalse(form.hasCenter)
        assertTrue(form.validate(radiusEnabled = true).isEmpty()) // 중심 없음도 저장 가능(반경 확인 "확인 불가", 계약 §4.3)
    }

    /** 반경 기능이 꺼져 있으면(2026-11-14) 화면에 없는 반경·중심 값으로 저장을 막지 않는다. 잘못된 값은 비워서 저장한다. */
    @Test
    fun radiusDisabled_invalidRadiusAndCenter_doNotBlockSave_andAreCleared() {
        val broken = valid.copy(radiusKm = 999.0, centerLatitude = 95.0, centerLongitude = null)
        assertTrue(broken.validate(radiusEnabled = true).isNotEmpty())
        assertTrue(broken.validate(radiusEnabled = false).isEmpty())

        val saved = broken.toCondition(radiusEnabled = false)
        assertEquals(null, saved.radiusCenter)
        assertEquals(999_000.0, saved.allowedRadiusMeters!!, 0.0) // 0보다 크면 계약상 유효 → 그대로 둔다
        assertTrue(saved.noNightDriving)
    }

    @Test
    fun radiusDisabled_validCenter_isKeptAsHome() {
        val saved = valid.copy(radiusKm = null).toCondition(radiusEnabled = false)
        assertEquals(center, saved.radiusCenter)
        assertEquals(null, saved.allowedRadiusMeters)
    }

    @Test
    fun toCondition_defaultsWithCenter_mapsToContractModel() {
        assertEquals(
            DriverCondition(
                timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
                routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
                radiusCenter = center,
                allowedRadiusMeters = 50_000.0,
            ),
            valid.toCondition(radiusEnabled = true)
        )
    }

    @Test
    fun toCondition_allOff_noCenter_isEmptyConstraintsWithRadius() {
        val form = valid.copy(noNightDriving = false, preferTollFree = false, avoidHighway = true, centerLatitude = null, centerLongitude = null)
        assertEquals(
            DriverCondition(
                timeConstraints = emptySet(),
                routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY),
                radiusCenter = null,
                allowedRadiusMeters = 50_000.0,
            ),
            form.toCondition(radiusEnabled = true)
        )
    }

    @Test
    fun from_savedCondition_roundTrips() {
        val saved = valid.copy(avoidHighway = true, radiusKm = 12.5).toCondition(radiusEnabled = true)
        val form = DriverConditionForm.from(saved, defaultAllowedRadiusMeters = 10_000.0)
        assertEquals(valid.copy(avoidHighway = true, radiusKm = 12.5), form)
        assertEquals(saved, form.toCondition(radiusEnabled = true))
    }

    @Test
    fun from_savedWithoutRadius_showsAppDefaultRadius() {
        val form = DriverConditionForm.from(DriverCondition(), defaultAllowedRadiusMeters = 10_000.0)
        assertEquals(10.0, form.radiusKm!!, 0.0)
        assertFalse(form.noNightDriving)
        assertFalse(form.preferTollFree)
    }

    @Test
    fun validate_radiusBoundaries() {
        assertEquals(listOf(DriverConditionFormError.RADIUS_MISSING), valid.copy(radiusKm = null).validate(radiusEnabled = true))
        assertEquals(listOf(DriverConditionFormError.RADIUS_NOT_POSITIVE), valid.copy(radiusKm = 0.0).validate(radiusEnabled = true))
        assertEquals(listOf(DriverConditionFormError.RADIUS_NOT_POSITIVE), valid.copy(radiusKm = -5.0).validate(radiusEnabled = true))
        assertTrue(valid.copy(radiusKm = 0.1).validate(radiusEnabled = true).isEmpty())
        assertTrue(valid.copy(radiusKm = 300.0).validate(radiusEnabled = true).isEmpty())
        assertEquals(listOf(DriverConditionFormError.RADIUS_TOO_LARGE), valid.copy(radiusKm = 300.1).validate(radiusEnabled = true))
    }

    @Test
    fun validate_centerBothOrNeither() {
        assertEquals(listOf(DriverConditionFormError.CENTER_INCOMPLETE), valid.copy(centerLongitude = null).validate(radiusEnabled = true))
        assertEquals(listOf(DriverConditionFormError.CENTER_INCOMPLETE), valid.copy(centerLatitude = null).validate(radiusEnabled = true))
        assertTrue(valid.copy(centerLatitude = null, centerLongitude = null).validate(radiusEnabled = true).isEmpty())
        assertEquals(listOf(DriverConditionFormError.CENTER_OUT_OF_RANGE), valid.copy(centerLatitude = 90.5).validate(radiusEnabled = true))
        assertEquals(listOf(DriverConditionFormError.CENTER_OUT_OF_RANGE), valid.copy(centerLongitude = -180.5).validate(radiusEnabled = true))
        assertTrue(valid.copy(centerLatitude = 90.0, centerLongitude = -180.0).validate(radiusEnabled = true).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun toCondition_invalid_throws() {
        valid.copy(radiusKm = 0.0).toCondition(radiusEnabled = true)
    }

    @Test
    fun radiusStepper_movesOnFiveKmGrid_andStopsAtLimits() {
        assertEquals(55.0, valid.increaseRadius().radiusKm!!, 0.0)
        assertEquals(45.0, valid.decreaseRadius().radiusKm!!, 0.0)
        assertEquals(15.0, valid.copy(radiusKm = 12.3).increaseRadius().radiusKm!!, 0.0)
        assertEquals(10.0, valid.copy(radiusKm = 12.3).decreaseRadius().radiusKm!!, 0.0)
        // 하한: 5 km 아래로는 내리지 않는다
        val five = valid.copy(radiusKm = 5.0)
        assertEquals(five, five.decreaseRadius())
        assertFalse(five.canDecreaseRadius())
        assertEquals(5.0, valid.copy(radiusKm = 7.0).decreaseRadius().radiusKm!!, 0.0)
        // 상한: 300 km 에서 멈추고, 저장된 이상값(500)은 [-] 한 번에 300 으로
        val max = valid.copy(radiusKm = 300.0)
        assertEquals(max, max.increaseRadius())
        assertFalse(max.canIncreaseRadius())
        assertEquals(300.0, valid.copy(radiusKm = 298.0).increaseRadius().radiusKm!!, 0.0)
        assertEquals(300.0, valid.copy(radiusKm = 500.0).decreaseRadius().radiusKm!!, 0.0)
        // 값이 없으면 기본값으로 채운다
        assertEquals(50.0, valid.copy(radiusKm = null).increaseRadius().radiusKm!!, 0.0)
    }
}
