package ai.pleos.playground.sample.vehicle.departure

import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 운전자 조건 설정 화면의 입력값. 저장할 때 [toCondition]으로 2번 모델 [DriverCondition]으로 바꾼다.
 *
 * 화면 입력 단위는 km, 저장(모델·계약) 단위는 m 다(계약 driver-condition-api.md §4.2 `allowedRadiusMeters`).
 * 중심 좌표는 위도·경도를 따로 들고 [validate]에서 "둘 다 있거나 둘 다 없거나"를 확인한다.
 */
data class DriverConditionForm(
    val noNightDriving: Boolean,
    val preferTollFree: Boolean,
    val avoidHighway: Boolean,
    val radiusKm: Double?,
    val centerLatitude: Double?,
    val centerLongitude: Double?,
) {
    /**
     * 입력 오류 목록. 비어 있으면 저장할 수 있다.
     * @param radiusEnabled false 면(반경 기능 꺼짐) 반경·중심을 검사하지 않는다. 화면에 보이지 않는 값으로 저장을 막지 않기 위해서다.
     */
    fun validate(radiusEnabled: Boolean): List<DriverConditionFormError> = buildList {
        if (!radiusEnabled) return@buildList
        when {
            radiusKm == null || radiusKm.isNaN() -> add(DriverConditionFormError.RADIUS_MISSING)
            radiusKm <= 0.0 -> add(DriverConditionFormError.RADIUS_NOT_POSITIVE)
            radiusKm > DriverConditionDefaults.MAX_RADIUS_KM -> add(DriverConditionFormError.RADIUS_TOO_LARGE)
        }
        when {
            (centerLatitude == null) != (centerLongitude == null) -> add(DriverConditionFormError.CENTER_INCOMPLETE)
            centerLatitude != null && centerLongitude != null &&
                (centerLatitude !in -90.0..90.0 || centerLongitude !in -180.0..180.0) ->
                add(DriverConditionFormError.CENTER_OUT_OF_RANGE)
        }
    }

    val hasCenter: Boolean get() = centerLatitude != null && centerLongitude != null

    /**
     * 저장할 모델로 바꾼다. [validate]가 비어 있을 때만 부른다(아니면 IllegalArgumentException).
     * 반경은 늘 값을 넣는다(km × 1000 m). 중심이 없으면 radiusCenter = null(반경 확인 "확인 불가" — 계약 §4.3).
     *
     * [radiusEnabled] = false(반경 기능 꺼짐)면 반경·중심을 검사하지 않으므로, 올바른 값만 저장하고 잘못된 값은 null 로 비운다
     * (중심은 출발 마감의 "집" 대체값으로 계속 쓰인다).
     */
    fun toCondition(radiusEnabled: Boolean): DriverCondition {
        val errors = validate(radiusEnabled)
        require(errors.isEmpty()) { "invalid form: $errors" }
        val centerValid = hasCenter && centerLatitude!! in -90.0..90.0 && centerLongitude!! in -180.0..180.0
        val radiusValid = radiusKm != null && !radiusKm.isNaN() && radiusKm > 0.0
        return DriverCondition(
            timeConstraints = if (noNightDriving) setOf(TimeConstraint.NO_NIGHT_DRIVING) else emptySet(),
            routePreference = RoutePreference(
                buildSet {
                    if (preferTollFree) add(RouteConstraint.PREFER_TOLL_FREE)
                    if (avoidHighway) add(RouteConstraint.AVOID_HIGHWAY)
                }
            ),
            radiusCenter = if (centerValid) GeoPoint(latitude = centerLatitude!!, longitude = centerLongitude!!) else null,
            allowedRadiusMeters = if (radiusValid) radiusKm!! * METERS_PER_KM else null,
        )
    }

    /** [+] 버튼: 다음 5 km 눈금으로 올린다. 상한(300 km) 이상이면 그대로. 값이 없으면 기본값으로 채운다. */
    fun increaseRadius(): DriverConditionForm {
        val step = DriverConditionDefaults.RADIUS_STEP_KM
        val max = DriverConditionDefaults.MAX_RADIUS_KM
        val current = radiusKm ?: return copy(radiusKm = DriverConditionDefaults.RADIUS_KM)
        if (current >= max) return this
        val next = (floor(current / step) + 1) * step
        return copy(radiusKm = next.coerceAtMost(max))
    }

    /** [-] 버튼: 이전 5 km 눈금으로 내린다. 5 km 아래로는 내리지 않는다. 상한을 넘은 값(저장된 이상값)은 상한으로 내린다. */
    fun decreaseRadius(): DriverConditionForm {
        val step = DriverConditionDefaults.RADIUS_STEP_KM
        val max = DriverConditionDefaults.MAX_RADIUS_KM
        val current = radiusKm ?: return copy(radiusKm = DriverConditionDefaults.RADIUS_KM)
        if (current > max) return copy(radiusKm = max)
        val previous = (ceil(current / step) - 1) * step
        return if (previous < step) this else copy(radiusKm = previous)
    }

    fun canIncreaseRadius(): Boolean = radiusKm == null || radiusKm < DriverConditionDefaults.MAX_RADIUS_KM

    fun canDecreaseRadius(): Boolean = decreaseRadius() != this

    companion object {
        const val METERS_PER_KM = 1000.0

        /** 저장된 조건이 없을 때 보여 줄 기본값. 중심은 비워 두고 화면을 열 때 현재 위치로 채운다. */
        fun defaults(): DriverConditionForm = DriverConditionForm(
            noNightDriving = DriverConditionDefaults.NO_NIGHT_DRIVING,
            preferTollFree = DriverConditionDefaults.PREFER_TOLL_FREE,
            avoidHighway = DriverConditionDefaults.AVOID_HIGHWAY,
            radiusKm = DriverConditionDefaults.RADIUS_KM,
            centerLatitude = null,
            centerLongitude = null,
        )

        /**
         * 저장된 조건 → 화면 입력값.
         * 저장된 반경이 null 이면 앱이 실제로 쓰는 값(DepartureConfig.defaultAllowedRadiusMeters)을 보여 준다
         * (계약 §4.2 "null = 앱 기본 반경 사용"). 이 화면에서 저장하면 그 값이 명시적으로 저장된다.
         */
        fun from(condition: DriverCondition, defaultAllowedRadiusMeters: Double): DriverConditionForm = DriverConditionForm(
            noNightDriving = condition.noNightDriving,
            preferTollFree = RouteConstraint.PREFER_TOLL_FREE in condition.routePreference.constraints,
            avoidHighway = RouteConstraint.AVOID_HIGHWAY in condition.routePreference.constraints,
            radiusKm = condition.effectiveRadiusMeters(defaultAllowedRadiusMeters) / METERS_PER_KM,
            centerLatitude = condition.radiusCenter?.latitude,
            centerLongitude = condition.radiusCenter?.longitude,
        )
    }
}

/** 입력 오류. 화면 문구는 화면(DriverConditionSettingsScreen)에서 붙인다. */
enum class DriverConditionFormError {
    /** 허용 반경이 비어 있다 */
    RADIUS_MISSING,

    /** 허용 반경이 0 이하다(계약 §4.2: > 0) */
    RADIUS_NOT_POSITIVE,

    /** 허용 반경이 화면 상한([DriverConditionDefaults.MAX_RADIUS_KM])을 넘는다 */
    RADIUS_TOO_LARGE,

    /** 중심 위도·경도 중 하나만 있다(둘 다 있거나 둘 다 없어야 한다 — 계약 §4.2) */
    CENTER_INCOMPLETE,

    /** 중심 좌표가 범위 밖이다(위도 -90..90, 경도 -180..180) */
    CENTER_OUT_OF_RANGE,
}
