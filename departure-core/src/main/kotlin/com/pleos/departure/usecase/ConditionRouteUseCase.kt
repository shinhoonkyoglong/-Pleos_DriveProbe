package com.pleos.departure.usecase

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteNote
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.RouteUserAction
import com.pleos.departure.port.NaviRepository

/**
 * 조건 맞춤 경로 **확인**(작업 #5b, 사용자 결정 11/10: "적용" → "안내 + 확인").
 *
 * 우리 앱은 경로 옵션을 내비에 적용하지 않는다. 이 내비 버전은 SDK로 보낸 FREE 를 무시하고(requestRoute·changeRouteOption),
 * 안내 중 requestRoute 는 사용자 경로를 덮어쓴다(api-notes/navi-runtime-check.md §10.1~10.3·10.8). 확인된 유일한 경로는
 * **사용자가 내비 "경로 설정"에서 "유료도로 우회"를 켜는 것**이다(§10.4). 그래서 이 유스케이스는
 * 운전자 조건 → 필요한 내비 상태 → 지금 경로 상태를 읽어 → "충족 / 사용자 조작 필요 / 보장 불가 참고" 를 판정만 한다. 부작용 없음.
 *
 * ## 판정 규칙
 * 1. 조건 없음 → [RouteConditionCheck.NoRouteCondition] (내비 조회 안 함)
 * 2. 내비 목적지(매번 새로 조회, (0,0) 방어) → 미설정/확인 불가면 그 결과
 * 3. 무료도로 조건이 없고 고속도로 회피만 → [RouteConditionCheck.ReferenceOnly]([RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED]).
 *    고속도로 회피는 어떤 경로로도 보장할 수 없으므로 결코 "충족"으로 보지 않는다(경로 상태도 읽지 않음).
 * 4. 무료도로 조건 → 경로 상태 조회
 *    - Unavailable → [RouteConditionCheck.Unconfirmed] / 안내 중 아님 → [RouteConditionCheck.NotGuiding]
 *    - 주 신호 `appliedOption == FREE`(다시 조회 가능), 보조 신호 tollFare(받았을 때만, task-01d MAJOR-4):
 *      - RECOMMENDED(토글 꺼짐 실측 값) → 사용자 조작 [RouteUserAction.TURN_ON_TOLL_AVOIDANCE]
 *      - 그 밖의 FREE 아닌 값 → [RouteConditionCheck.OtherRouteOption](충족 아님, 단정 안 함 — 리뷰 task-05 재검토 MAJOR-R2)
 *      - FREE + tollFare > 0 → [RouteUserAction.REVIEW_TOLL_ON_ROUTE] (충족 아님)
 *      - FREE + tollFare == 0 → 충족
 *      - FREE + tollFare 모름 → 충족 + [RouteNote.TOLL_FARE_NOT_OBSERVED]
 *    - 고속도로 회피도 있으면 notes 에 [RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED]를 붙인다.
 *
 * 호출 시점: 화면 진입·백그라운드 복귀, 사용자가 "켰어요"를 누른 뒤. 사람이 내비에서 경로를 바꾸면 onRouteCancelled → 7~19초 뒤
 * onRouteStarted 이므로(§10.5) NotGuiding 이면 잠시 뒤 다시 부른다. 재확인 간격·횟수는 DepartureConfig 에 둔다(값은 #7에서 결정 —
 * 감독관 결정, 리뷰 task-05 재검토 MINOR-R2). 그 사이 getDestination 이 0 값(NotSet)일 수 있다([미확인], MINOR-R4).
 */
class ConditionRouteUseCase(
    private val naviRepository: NaviRepository,
) {

    /**
     * @param destination 이미 조회한 내비 목적지가 있으면 넘긴다(같은 화면의 출발 마감·반경 확인과 공유). null 이면 조회한다.
     */
    suspend fun check(
        preference: RoutePreference,
        destination: DataResult<NaviDestination>? = null,
    ): RouteConditionCheck {
        if (preference.isEmpty) return RouteConditionCheck.NoRouteCondition

        val place = when (val d = (destination ?: naviRepository.getDestination()).treatNullIslandAsNotSet()) {
            is DataResult.Unavailable -> return RouteConditionCheck.DestinationUnavailable(d.reason)
            is DataResult.Available -> when (val value = d.value) {
                NaviDestination.NotSet -> return RouteConditionCheck.DestinationNotSet
                is NaviDestination.Present -> value.place
            }
        }

        val highwayNote = if (RouteConstraint.AVOID_HIGHWAY in preference.constraints) {
            listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED)
        } else {
            emptyList()
        }
        if (RouteConstraint.PREFER_TOLL_FREE !in preference.constraints) {
            return RouteConditionCheck.ReferenceOnly(place, highwayNote)
        }

        val state = when (val r = naviRepository.getRouteState()) {
            is DataResult.Unavailable -> return RouteConditionCheck.Unconfirmed(place, r.reason)
            is DataResult.Available -> when (val value = r.value) {
                RouteState.NotGuiding -> return RouteConditionCheck.NotGuiding(place)
                is RouteState.Guiding -> value
            }
        }

        val toll = state.tollFare
        return when {
            state.appliedOption == RouteOption.RECOMMENDED ->
                RouteConditionCheck.NeedsUserAction(place, state, listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), highwayNote)
            state.appliedOption != RouteOption.FREE ->
                RouteConditionCheck.OtherRouteOption(place, state, highwayNote)
            toll != null && toll > 0 ->
                RouteConditionCheck.NeedsUserAction(place, state, listOf(RouteUserAction.REVIEW_TOLL_ON_ROUTE), highwayNote)
            toll == null ->
                RouteConditionCheck.Satisfied(place, state, listOf(RouteNote.TOLL_FARE_NOT_OBSERVED) + highwayNote)
            else ->
                RouteConditionCheck.Satisfied(place, state, highwayNote)
        }
    }
}
