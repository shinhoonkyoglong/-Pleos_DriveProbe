package com.pleos.departure.model

/**
 * 차량 내비에 지금 설정된 목적지(사용자 결정 11/09: 목적지 = 내비 목적지. 저장 목록·귀가 마감 없음).
 *
 * "목적지 없음"은 오류가 아니라 이 내비의 **기본 상태**다. 그래서 `DataResult.Unavailable(NO_DATA)`로 표현하지 않고
 * 이 타입의 [NotSet]으로 구분한다(리뷰 task-01c 넘길 사항 1, task-03 7절 표).
 * - `DataResult.Available(Present)` : 목적지 있음 → 반경 확인·출발 마감 계산
 * - `DataResult.Available(NotSet)`  : 목적지 미설정(정상) → "내비에 목적지를 설정하세요" 안내
 * - `DataResult.Unavailable(...)`   : 확인 불가(내비 없음·타임아웃 등) → "목적지를 확인할 수 없어요" 안내
 *
 * 근거: api-notes/navi-runtime-check.md 3.2·3.4·3.5 [실행 확인 2026-11-09] — 주차(P) 중에도 `getDestinationInfo`가
 * 내비 목적지의 좌표·이름·poiId를 준다(사람이 내비 화면에서 넣은 목적지 포함). 목적지가 없으면 0 값 객체가 온다.
 *
 * `DestinationInfo.remainTime`/`remainDistance`는 여기에 담지 않는다. 출발 마감 ETA는 `requestTrip`(정밀 초)만 쓴다
 * (분 단위 값이라 최대 59초 짧을 수 있고, 주차 중 갱신되지 않아 조회 시각이 불분명하며, 어느 경로 옵션 기준인지는
 * `RouteStateInfo.routeOption`을 따로 읽어야 함 — 리뷰 task-01c MAJOR-4, 설계 판단).
 */
sealed interface NaviDestination {

    /**
     * 목적지가 있다.
     * @property place 내비 목적지. 어댑터는 `displayName`을 name → address(역지오코딩 구·동) → "내비 목적지" 순으로 채운다
     *   (SDK로 설정한 목적지는 name 이 빈 문자열로 옴 — navi-runtime-check.md 3.4).
     */
    data class Present(val place: Place) : NaviDestination

    /**
     * 내비에 목적지가 없다(정상 상태). 반경 경고·출발 마감을 만들지 않는다.
     * 주의: 사람이 목적지·경로 설정을 바꾸는 7~19초 동안 내비는 0 값을 보낸다(§10.5) → 그 사이 조회하면 NotSet 일 수 있다([미확인]).
     * 화면(#7)은 직전에 목적지가 있었으면 바로 "목적지 없음"으로 확정하지 않고 재확인한다(리뷰 task-05 재검토 MINOR-R4).
     */
    data object NotSet : NaviDestination
}
