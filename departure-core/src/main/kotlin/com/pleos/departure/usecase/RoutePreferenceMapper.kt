package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.Guarantee
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteMapping
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference

/**
 * 운전자 경로 조건(Set) → 안내에 쓰일 것으로 보는 단일 [RouteOption]과 조건별 보장 수준(리뷰 task-03 #5-2).
 *
 * #5b 부터 이 결과는 **출발 마감 ETA 보정 판정**(DepartureDeadlineUseCase)에만 쓴다. 경로를 요청하지 않는다.
 * 무료도로 조건을 어떻게 맞추는지(사용자 안내 + 확인)는 ConditionRouteUseCase 가 정한다.
 *
 * | 조건 | 옵션 | 보장 |
 * |---|---|---|
 * | 없음 | [DepartureConfig.defaultRouteOption] (기본 RECOMMENDED) | (조건 없음 → EXACT) |
 * | 무료도로 선호 | FREE | EXACT — 내비 "유료도로 우회"를 켜면 routeOption=FREE·통행료 0(§10.4) |
 * | 고속도로 회피 | FREE | **UNSUPPORTED** — 내비에 고속도로 회피 수단이 없다. "유료도로 우회"를 켜도 통행료 없는 고속도로 7.2km가 남았다(§10.4, task-01d 재검토 제안) |
 * | 둘 다 | FREE | 무료도로 EXACT, 고속도로 회피 UNSUPPORTED |
 *
 * 고속도로 회피 → FREE 는 ETA 보정용 보수적 가정이다(더 느린 경로로 본다). 설계 판단.
 */
class RoutePreferenceMapper(private val config: DepartureConfig) {

    fun map(preference: RoutePreference): RouteMapping {
        if (preference.isEmpty) {
            return RouteMapping(preference, config.defaultRouteOption, emptyMap())
        }
        val perConstraint = preference.constraints.associateWith { constraint ->
            when (constraint) {
                RouteConstraint.PREFER_TOLL_FREE -> Guarantee.EXACT
                RouteConstraint.AVOID_HIGHWAY -> Guarantee.UNSUPPORTED
            }
        }
        return RouteMapping(preference, RouteOption.FREE, perConstraint)
    }
}
