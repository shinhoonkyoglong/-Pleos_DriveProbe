package com.pleos.departure.model

/**
 * 한 지점의 반경 판정. 직선거리(Haversine) 기준이다.
 * 판정은 core 순수 계산(usecase/GeoDistance)이고 FusedLocation ProximityAlert 는 쓰지 않는다(사용자 결정 11/09).
 */
sealed interface PointRadiusCheck {

    /**
     * 거리를 쟀다.
     * @property distanceMeters 반경 기준점에서의 직선거리(m)
     * @property within 거리 ≤ 반경이면 true(경계 = 정확히 반경은 **안**으로 본다)
     */
    data class Measured(val distanceMeters: Double, val within: Boolean) : PointRadiusCheck

    /** 지점 좌표를 얻지 못했다(현재 위치 Unavailable 등). */
    data class Unavailable(val reason: UnavailableReason) : PointRadiusCheck
}

/**
 * 반경 확인 결과(작업 #5). 경고 기준은 **내비 목적지**가 허용 반경 밖인가이다(사용자 결정 11/09).
 * 화면(작업 #7)은 `when`으로 모두 처리한다.
 *
 * 한계(화면 문구에 반영): 직선거리만 본다. 경로가 중간에 반경을 벗어나는지는 판정할 수 없다.
 */
sealed interface RadiusCheckResult {

    /**
     * 판정했다.
     * @property center 허용 반경 기준점(운전자 조건 radiusCenter, 보통 집)
     * @property radiusMeters 실제로 쓴 반경(운전자 값, 없으면 DepartureConfig.defaultAllowedRadiusMeters)
     * @property destinationCheck 목적지 판정 — 경고 기준
     * @property currentLocationCheck 현재 위치 판정 — 참고용. 위치를 못 얻으면 Unavailable 이고 목적지 판정만 한다.
     *   (에뮬레이터에서는 내비 위치가 기본 위치(성남)에 고정된다 — navi-runtime-check.md 4절)
     */
    data class Checked(
        val destination: Place,
        val center: GeoPoint,
        val radiusMeters: Double,
        val destinationCheck: PointRadiusCheck.Measured,
        val currentLocationCheck: PointRadiusCheck,
    ) : RadiusCheckResult {
        /** 목적지가 허용 반경 밖 → 경고. */
        val isDestinationOutside: Boolean get() = !destinationCheck.within
    }

    /** 내비에 목적지가 없다. 경고하지 않는다(0,0 좌표로 오경고하지 않도록 따로 둔다 — 리뷰 task-01c 7절). */
    data object DestinationNotSet : RadiusCheckResult

    /** 내비 목적지를 확인할 수 없다(내비 없음·타임아웃 등). "확인 불가"로 표시한다. */
    data class DestinationUnavailable(val reason: UnavailableReason) : RadiusCheckResult

    /** 운전자 조건에 반경 기준점이 없다 → "확인 불가"(리뷰 task-03 #5-4). */
    data class CenterNotSet(val destination: Place) : RadiusCheckResult
}
