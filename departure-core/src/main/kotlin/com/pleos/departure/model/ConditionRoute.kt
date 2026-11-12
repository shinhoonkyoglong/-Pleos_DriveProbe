package com.pleos.departure.model

/**
 * 사용자에게 요청할 내비 조작(작업 #5b). **문구 키**다. 실제 한국어 문구·아이콘은 #7 화면이 정한다.
 * 우리 앱은 경로 옵션을 직접 적용하지 않는다(SDK 적용 경로 없음 — api-notes/navi-runtime-check.md §10.1~10.3).
 */
enum class RouteUserAction {
    /**
     * 내비 "경로 설정"에서 "유료도로 우회"를 켜고 "적용"하세요.
     * 적용 옵션 RECOMMENDED(토글 꺼짐 실측), 또는 FREE 인데 통행료가 있는 경로(어린이 보호구역 우회만 켠 경우 실측 — 2026-11-11).
     */
    TURN_ON_TOLL_AVOIDANCE,
}

/** 사용자 조작 없이 보여 줄 참고 안내(문구 키). */
enum class RouteNote {
    /**
     * 고속도로 회피는 내비 기능으로 보장할 수 없어요. "유료도로 우회"를 켜도 통행료 없는 고속도로는 남을 수 있어요
     * (§10.4: 유료도로 우회 후에도 Highway 7.2km). 이 조건은 결코 "충족"으로 표시하지 않는다.
     */
    AVOID_HIGHWAY_NOT_GUARANTEED,
}

/**
 * 조건 맞춤 경로 **확인** 결과(작업 #5b, 사용자 결정 11/10: "적용"이 아니라 "안내 + 확인").
 * 화면(#7)은 `when`으로 모두 처리한다. 화면 진입·복귀 때, 그리고 사용자가 "켰어요"를 누른 뒤 다시 확인한다.
 */
sealed interface RouteConditionCheck {

    /** 운전자 경로 조건이 없다. 확인할 것이 없다(내비 조회도 하지 않음). */
    data object NoRouteCondition : RouteConditionCheck

    /** 내비에 목적지가 없다. */
    data object DestinationNotSet : RouteConditionCheck

    /** 내비 목적지를 확인할 수 없다. */
    data class DestinationUnavailable(val reason: UnavailableReason) : RouteConditionCheck

    /** 목적지는 있지만 안내 중이 아니다(경로가 바뀌는 중일 수 있음). 잠시 뒤 다시 확인한다. */
    data class NotGuiding(val destination: Place) : RouteConditionCheck

    /** 경로 상태를 읽지 못했다(타임아웃·내비 없음 등). "확인 불가"로 표시하고 "충족"으로 보지 않는다. */
    data class Unconfirmed(val destination: Place, val reason: UnavailableReason) : RouteConditionCheck

    /**
     * 보장할 수 있는 조건(무료도로)이 모두 충족됐다 — 적용 옵션 FREE **그리고 통행료 0원 확인**(작업 #27). [notes]의 참고 안내를 함께 보여 준다.
     * 고속도로 회피 조건만 있으면 이 결과가 아니라 [ReferenceOnly]다.
     */
    data class Satisfied(
        val destination: Place,
        val routeState: RouteState.Guiding,
        val notes: List<RouteNote>,
    ) : RouteConditionCheck

    /**
     * 내비 적용 옵션은 FREE 인데 이 경로의 통행료를 받지 못했다 → **충족 아님, 단정도 안 함**(작업 #27, 사용자 결정 11/11).
     *
     * FREE 는 "유료도로 우회 켬"의 증거가 아니다: "어린이 보호구역 우회"만 켜도 FREE 가 오고 그 경로는 통행료 700원이었다
     * (api-notes/navi-runtime-check.md §10.4, evidence/2026-11-11/childzone). 통행료는 안내 시작 때 `onRouteStarted`로 한 번만 오므로
     * 우리 앱이 그 뒤에 연결되면 모른다. 화면은 "내비 경로 설정에서 '유료도로 우회'가 켜져 있는지 확인해 주세요"로 확인만 권한다.
     * 우리 앱이 떠 있을 때 안내가 (다시) 시작되면 통행료를 받아 충족/미충족으로 바뀐다.
     */
    data class TollAvoidanceUnverified(
        val destination: Place,
        val routeState: RouteState.Guiding,
        val notes: List<RouteNote>,
    ) : RouteConditionCheck

    /**
     * 내비가 FREE·RECOMMENDED 가 아닌 옵션(예: SCHOOL_ZONE)을 보고했다 → **충족 아님, 단정도 안 함**(리뷰 task-05 재검토 MAJOR-R2).
     * "유료도로 우회"가 꺼져 있다고 확인된 값은 RECOMMENDED 뿐이다(§10.2·10.4). "어린이 보호구역 우회"를 켜거나 두 토글을 함께 켰을 때의
     * 값은 [미확인]이라, 여기서 "유료도로 우회를 켜세요"라고 하면 이미 켠 사용자에게 같은 안내가 반복될 수 있다(§10.4 추천 3).
     * 화면은 "내비 경로 설정에서 '유료도로 우회'가 켜져 있는지 확인해 주세요"(#7 문구) 정도로 확인만 권하고, "켰어요" 재확인 반복을 유도하지 않는다.
     */
    data class OtherRouteOption(
        val destination: Place,
        val routeState: RouteState.Guiding,
        val notes: List<RouteNote>,
    ) : RouteConditionCheck

    /** 사용자 조작이 필요하다. [actions] 순서대로 안내한다. */
    data class NeedsUserAction(
        val destination: Place,
        val routeState: RouteState.Guiding,
        val actions: List<RouteUserAction>,
        val notes: List<RouteNote>,
    ) : RouteConditionCheck

    /**
     * 보장할 수 있는 조건이 없다(고속도로 회피만). "보장 불가, 참고 안내"만 보여 준다. 확인할 신호가 없으므로 경로 상태는 읽지 않는다.
     */
    data class ReferenceOnly(
        val destination: Place,
        val notes: List<RouteNote>,
    ) : RouteConditionCheck
}
