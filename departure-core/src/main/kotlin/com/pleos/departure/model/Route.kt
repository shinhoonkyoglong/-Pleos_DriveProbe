package com.pleos.departure.model

/**
 * 내비에 요청할 경로 옵션(단일 값). NaviHelper `RouteOption` 7개 값과 이름을 맞춘 **우리 도메인 타입**이다.
 * SDK 타입을 core에 노출하지 않기 위해 따로 둔다. 1번 어댑터에서 SDK enum과 1:1로 변환한다
 * (같은 이름이라 어댑터에서는 `import ... as SdkRouteOption` 처럼 별칭을 쓰면 헷갈리지 않는다).
 *
 * 근거: api-notes/navi-route.md "RouteOption (enum, 단일 값)". 회피(avoid) 옵션은 SDK에 없다.
 */
enum class RouteOption {
    /** 추천 경로 (SDK 기본값). */
    RECOMMENDED,
    SHORT_DISTANCE,
    SHORT_TIME,

    /** 무료 도로 **우선** 경로. 유료/고속도로 완전 회피를 보장하지 않는다. */
    FREE,

    /** 고속도로 우선 경로. */
    HIGHWAY,
    COMFORTABLE,
    SCHOOL_ZONE,
}

/**
 * [RoutePreference](여러 조건) → [RouteOption](하나)로 줄였을 때 조건이 얼마나 지켜지는지.
 * 리뷰 task-01 MAJOR-3: "고속도로 회피"는 보장 수단이 없으므로 APPROXIMATE로 표시하고 화면에서 안내한다.
 */
enum class Guarantee {
    /** 조건과 옵션 의미가 정확히 같다. */
    EXACT,

    /** 비슷한 옵션으로 근사했다. 화면에 "포함될 수 있음" 안내가 필요하다. */
    APPROXIMATE,

    /** 맞는 옵션이 없다. */
    UNSUPPORTED,
}

/**
 * 경로 조건 매핑 결과. 매핑 규칙(어떤 조건 → 어떤 옵션)은 usecase/RoutePreferenceMapper(작업 #5).
 *
 * @property option 실제로 내비에 요청할 옵션
 * @property perConstraint 조건별 보장 수준. 화면은 APPROXIMATE/UNSUPPORTED 인 조건마다 안내 문구를 띄운다.
 */
data class RouteMapping(
    val preference: RoutePreference,
    val option: RouteOption,
    val perConstraint: Map<RouteConstraint, Guarantee>,
) {
    /** 전체 보장 수준 = 조건별 보장 중 가장 약한 것. 조건이 없으면 EXACT. */
    val overall: Guarantee
        get() = when {
            perConstraint.values.any { it == Guarantee.UNSUPPORTED } -> Guarantee.UNSUPPORTED
            perConstraint.values.any { it == Guarantee.APPROXIMATE } -> Guarantee.APPROXIMATE
            else -> Guarantee.EXACT
        }
}
