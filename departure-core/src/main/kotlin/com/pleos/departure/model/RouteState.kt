package com.pleos.departure.model

/**
 * 내비가 지금 안내 중인 경로의 상태(작업 #5b). NaviRepository.getRouteState 결과.
 *
 * 근거(api-notes/navi-runtime-check.md §10.4, reviews/task-01d MAJOR-4):
 * - 사용자가 내비 화면 "경로 설정"에서 **"유료도로 우회"**를 켜고 적용하면 `RouteStateInfo.routeOption == FREE`가 되고
 *   `onRouteStarted`의 `tollFare == 0`이 된다 [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5].
 * - `routeOption`은 `getRouteStateInfo`로 **언제든 다시 조회할 수 있다** → 주 판정 신호.
 * - `tollFare`는 `onRouteStarted` 푸시로 **한 번만** 온다(다시 조회 불가). 우리 리스너가 붙기 전에 안내가 시작됐으면 모른다 → 보조 신호, null 가능.
 *   `DestinationInfo.tollFare`는 FREE·RECOMMENDED 모두 0이라 쓰지 않는다.
 */
sealed interface RouteState {

    /**
     * 경로 안내 중이 아니다(`naviMode == SAFE_GUIDING` 등). 목적지가 바뀌는 중일 수도 있다
     * (사람이 목적지를 바꾸거나 경로 설정을 "적용"하면 onRouteCancelled → 7~19초 뒤 onRouteStarted — §10.5).
     */
    data object NotGuiding : RouteState

    /**
     * 경로 안내 중이다(`naviMode == ROUTE_GUIDING`, SIMULATION_GUIDING 포함).
     * @property appliedOption 내비가 보고한 적용 옵션(`RouteStateInfo.routeOption`). 요청값이 아니라 **보고값**이다.
     * @property tollFare 지금 경로의 `onRouteStarted.tollFare`(원). 그 푸시를 받지 못했으면 null. 어댑터 캐시 규칙(onRouteCancelled·리스너 재등록·목적지 좌표 불일치 → null)은
     *   NaviRepository.getRouteState KDoc. 저장·복원한 값은 표시용이고 판정은 항상 새로 조회한 값으로 한다.
     */
    data class Guiding(
        val appliedOption: RouteOption,
        val tollFare: Int?,
    ) : RouteState {
        init {
            tollFare?.let { require(it >= 0) { "tollFare must be >= 0: $it" } }
        }
    }
}
