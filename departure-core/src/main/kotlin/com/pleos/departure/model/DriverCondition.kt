package com.pleos.departure.model

/**
 * 운전자 조건(조건부 면허 조건). 서버 저장 계약(작업 #6, contracts/driver-condition-api.md)과 필드를 맞춘다.
 *
 * 조건 종류를 성격별로 나눈다(리뷰 task-01 MINOR-2):
 * - [timeConstraints]: 언제 운전할 수 있나 (야간 금지 → 일몰 계산, 작업 #4)
 * - [routePreference]: 어떤 길로 가나 (무료도로 선호, 고속도로 회피 → 경로 옵션, 작업 #5)
 * - [radiusCenter] / [allowedRadiusMeters]: 어디까지 갈 수 있나 (반경 확인, 작업 #5)
 *
 * @property radiusCenter 허용 반경의 기준점(보통 집). null 이면 반경 조건을 확인할 수 없다("확인 불가").
 *   NaviHelper Bookmark HOME 은 기본값 후보일 뿐 근거로 쓰지 않는다(리뷰 task-01 MINOR-4).
 * @property allowedRadiusMeters 허용 반경(m). null 이면 DepartureConfig.defaultAllowedRadiusMeters 를 쓴다.
 */
data class DriverCondition(
    val timeConstraints: Set<TimeConstraint> = emptySet(),
    val routePreference: RoutePreference = RoutePreference.NONE,
    val radiusCenter: GeoPoint? = null,
    val allowedRadiusMeters: Double? = null,
) {
    init {
        allowedRadiusMeters?.let { require(it > 0.0) { "allowedRadiusMeters must be > 0: $it" } }
    }

    /** 야간(일몰 이후) 운전 금지 조건이 있는가. */
    val noNightDriving: Boolean get() = TimeConstraint.NO_NIGHT_DRIVING in timeConstraints

    /** 반경 확인에 실제로 쓸 반경(m). 운전자 값이 없으면 [defaultRadiusMeters]. */
    fun effectiveRadiusMeters(defaultRadiusMeters: Double): Double = allowedRadiusMeters ?: defaultRadiusMeters
}

/** 시간 조건. */
enum class TimeConstraint {
    /** 일몰 이후 운전 금지. */
    NO_NIGHT_DRIVING,
}

/** 경로 조건 하나. 여러 개를 동시에 가질 수 있다([RoutePreference]). */
enum class RouteConstraint {
    /** 무료도로 선호(유료도로 회피). */
    PREFER_TOLL_FREE,

    /** 고속도로 회피. SDK에 회피 옵션이 없어 근사로만 적용된다(api-notes/navi-route.md "RouteOption"). */
    AVOID_HIGHWAY,
}

/**
 * 운전자가 원하는 경로 조건 묶음(Set 기반).
 *
 * SDK `RouteOption`은 단일 선택 enum이라 이 Set을 옵션 하나로 줄이는 매핑이 필요하다.
 * 매핑 규칙은 core 유스케이스(작업 #5)에 두고, 결과는 [RouteMapping]으로 표현한다.
 */
data class RoutePreference(val constraints: Set<RouteConstraint>) {
    val isEmpty: Boolean get() = constraints.isEmpty()

    companion object {
        val NONE = RoutePreference(emptySet())
        fun of(vararg constraints: RouteConstraint) = RoutePreference(constraints.toSet())
    }
}
