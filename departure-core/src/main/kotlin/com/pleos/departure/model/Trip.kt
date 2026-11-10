package com.pleos.departure.model

import java.time.Duration
import java.time.Instant

/*
 * 시간 표현 규칙: 이 모듈의 길이(소요 시간)는 전부 java.time.Duration, 시각은 java.time.Instant 로 통일한다.
 * (:app minSdk 28 이므로 Android에서도 java.time 사용 가능 — 리뷰 task-02 MAJOR-3, task-01 MINOR-5)
 */

/** ETA 값의 출처. 화면은 SDK 실측이 아니면 "예상" 표기를 강화한다(리뷰 task-01 MAJOR-2). */
enum class EstimateSource {
    /** 내비(NaviHelper requestTrip / onRouteStarted)가 준 값. */
    SDK_MEASURED,

    /** SDK 값에 우리 보정(무료도로 가산 등)을 적용한 값. */
    ESTIMATED,

    /** ETA를 모를 때 DepartureConfig.fallbackTripDuration 을 쓴 값. */
    DEFAULT,
}

/**
 * 목적지까지 예상 이동(사전 추정).
 *
 * 주의: requestTrip 은 "지금 출발 시" 값이고 경로 옵션·출발 시각을 지정할 수 없다(api-notes/eta-location.md §A).
 * 그래서 출처([source])와 조회 시각([basis])을 함께 들고 다니며, 오래된 값은 다시 조회한다.
 *
 * @property duration 남은 이동 시간(도착 시각이 아님). SDK `TripInfo.eta`는 남은 초다
 *   [실행 확인 2026-11-09 — api-notes/navi-runtime-check.md 3.6: 2704 vs epoch 1794207288, 14초 뒤 2703].
 * @property distanceMeters 거리(m). 기본값(DEFAULT)일 때 등 모르면 null.
 * @property basis 이 값을 얻은 시각.
 * @property routeOption 이 값이 **어느 경로 옵션 기준**인가(리뷰 task-01c MAJOR-4). 출발 마감 보정 여부를 이 값으로 판정한다.
 *   requestTrip ETA는 내비가 그 순간 쓰는 "유료도로 우회" 설정을 따른다(안내 중 실행 확인 2026-11-10, navi-runtime-check.md §10.6).
 *   - 안내 중: 어댑터가 같은 시점에 읽은 `RouteStateInfo.routeOption`을 넣는다(감독관 결정 11/10). FREE 면 core 는 보정하지 않는다.
 *   - null = 안내 중이 아니거나 옵션을 모름. core 는 null 을 "내비 기본 경로(RECOMMENDED) 기준"으로 보고 FREE 안내 조건이면 보정한다
 *     (안내 없을 때의 기준은 [미확인] — 보정 쪽이 보수적).
 * @property destination 이 값이 **어느 목적지 좌표 기준**인가. 출발 마감 유스케이스는 이전 값을 재사용할 때 이 좌표가 이번 목적지와
 *   같아야만 재사용한다(리뷰 task-05 MAJOR-1: 운전자가 내비에서 목적지를 바꾸면 이전 ETA를 쓰면 안 됨).
 *   어댑터는 요청한 목적지 좌표를 넣는다(비워도 됨). 유스케이스는 받은 직후 항상 요청 좌표로 덮어쓴다. null 인 이전 값은 재사용하지 않는다.
 */
data class TripEstimate(
    val duration: Duration,
    val distanceMeters: Int?,
    val source: EstimateSource,
    val basis: Instant,
    val routeOption: RouteOption? = null,
    val destination: GeoPoint? = null,
) {
    init {
        require(!duration.isNegative) { "duration must not be negative: $duration" }
        distanceMeters?.let { require(it >= 0) { "distanceMeters must be >= 0: $it" } }
    }

    /** [now] 기준으로 [freshnessLimit] 이내에 얻은 값인가. 경계(정확히 limit)는 신선한 것으로 본다. */
    fun isFresh(now: Instant, freshnessLimit: Duration): Boolean =
        Duration.between(basis, now) <= freshnessLimit // Duration.isPositive는 Java 18+(Android API 34+)라 쓰지 않음
}

/**
 * 안내 중 남은 이동. NaviHelper `DestinationInfo.remainDistance/remainTime/tollFare`에 대응
 * (api-notes/eta-location.md §A).
 * - remainTime/remainDistance 는 분·km 단위 값이다(2,704초 → 2,700초, 23,799m → 23,000m. 내림으로 보이나 방식 [불확실]).
 * - [tollFare]는 유료 경로에서도 0이 올 수 있다(1건 관측) → **유료 여부 판단에 쓰지 않는다**. 표시용으로도 신뢰하지 않는다.
 */
data class RemainingTrip(
    val remainingDistanceMeters: Int,
    val remainingTime: Duration,
    val tollFare: Int,
    val basis: Instant,
)
