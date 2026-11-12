package com.pleos.departure

import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteOption
import com.pleos.departure.sun.SunCalculator
import java.time.Duration
import java.time.ZoneId

/**
 * 2번 '출발 전' 동작 수치를 한 곳에 모은 설정. 코드 다른 곳에 수치를 하드코딩하지 않는다.
 * :app 의 DepartureWiring 에서 만들어 주입한다(기본값 그대로 써도 된다).
 *
 * 각 값의 KDoc에 [근거] 또는 [설계 판단]을 적는다. [설계 판단]은 문서 근거가 없는 우리 선택이라
 * 시연·실차 확인 후 조정 대상이다.
 */
data class DepartureConfig(
    /**
     * 출발 마감 여유 시간. 출발 마감 = 일몰 − 예상 이동 시간 − 이 값.
     * [설계 판단] 30분. 고령 운전자의 준비 시간·주차 시간을 감안한 보수적 값.
     */
    val departureMargin: Duration = Duration.ofMinutes(30),

    /**
     * 운전자 조건에 반경 값이 없을 때 쓰는 허용 반경(m).
     * [설계 판단] 10km. 조건부 면허 반경 기준은 Pleos 문서에 없다. 운전자 조건(서버)이 우선한다.
     */
    val defaultAllowedRadiusMeters: Double = 10_000.0,

    /**
     * 일몰·출발 마감 계산 기준 시간대.
     * [설계 판단] Asia/Seoul. 국내 공모전 기준. 차량 시스템 시간대와 다를 수 있어 고정한다.
     */
    val timeZone: ZoneId = ZoneId.of("Asia/Seoul"),

    /**
     * "야간"이 시작되는 기준. 출발 마감 계산은 이 기준 시각(일몰 또는 시민 박명 끝)을 "일몰"로 쓴다.
     * [근거] 도로교통법 제37조 제1항 제1호: 밤 = "해가 진 후부터 해가 뜨기 전까지"(법 전체에 적용되는 일반 정의,
     *   api-notes/sunset-reference.md "결론"). → 기본 SUNSET(천정각 90.833°).
     * [설계 판단] 고령 조건부 면허의 '야간' 시간 정의는 공식 자료가 없다(제도 논의 중, 같은 문서). 그래서 설정값으로 둔다.
     *   일몰 기준은 시민 박명 끝보다 약 26~31분 일찍 야간이 되는 보수적 선택이다.
     */
    val nightStartReference: NightStartReference = NightStartReference.SUNSET,

    /**
     * ETA를 모를 때(내비 미탑재, 콜백 타임아웃, CAUSE_SEARCH_TRIP) 쓰는 기본 이동 시간.
     * [근거] 사전 ETA 콜백 도착이 보장되지 않음(api-notes/eta-location.md §A, requestTrip "may be delivered").
     * [설계 판단] 값 60분. 마감을 일찍 알려 주는 쪽(보수적)으로 크게 잡는다.
     */
    val fallbackTripDuration: Duration = Duration.ofMinutes(60),

    /**
     * 경로 옵션 보정 계수. 안내에 쓸 옵션(RoutePreference → RouteMapping.option)이 ETA 기준 경로 옵션
     * (TripEstimate.routeOption, null 이면 RECOMMENDED 로 봄)과 다르면 보정 시간 = SDK ETA × 계수 + [tollFreeExtraTime].
     * 지금 매핑 결과는 RECOMMENDED 또는 FREE 뿐이라 사실상 "무료도로 보정"이다(이름 유지).
     * [근거] requestTrip 은 경로 옵션을 받지 않아 무료도로 경로의 사전 ETA를 얻을 수 없다(리뷰 task-01 MAJOR-2).
     *   보정 기준을 제약 종류가 아니라 매핑 결과로 정한다(리뷰 task-04b MAJOR-1: AVOID_HIGHWAY 단독도 FREE 로 안내되므로 보정).
     * [설계 판단] 1.2 (20% 가산).
     */
    val tollFreeDurationFactor: Double = 1.2,

    /** 무료도로 보정 가산 시간. 위 계수와 함께 쓴다. [설계 판단] 0분(계수만 사용). */
    val tollFreeExtraTime: Duration = Duration.ZERO,

    /**
     * ETA 신선도 한계. 이보다 오래된 TripEstimate 는 다시 조회한다.
     * [근거] requestTrip 은 "지금 출발" 기준이라 시간이 지나면 교통 변화로 오차가 커진다(리뷰 task-01 MAJOR-2).
     * [설계 판단] 15분.
     */
    val etaFreshnessLimit: Duration = Duration.ofMinutes(15),

    /**
     * 사전 ETA(requestTrip → onTripInfo) 콜백 대기 제한. 넘으면 Unavailable(TIMEOUT).
     * [근거] 문서상 콜백 도착 보장 없음(api-notes/eta-location.md §A "may be delivered").
     *   실측 0.4~0.55초(api-notes/navi-runtime-check.md 3.6, 에뮬레이터 2회).
     * [설계 판단] 10초. 실측의 약 20배 여유(실차·혼잡 서버 대비).
     */
    val tripCallbackTimeout: Duration = Duration.ofSeconds(10),

    /**
     * 경로 상태(getRouteStateInfo → onRouteStateInfo) 콜백 대기 제한. 넘으면 Unavailable(TIMEOUT) → "확인 불가".
     * [근거] get*Info 실측 20~35ms(api-notes/navi-runtime-check.md 3.2), 권장 5~10초(7.3).
     * [설계 판단] 5초. (이전 routeCallbackTimeout 20초는 requestRoute 용이었고 #5b 에서 requestRoute 를 쓰지 않아 없앴다)
     */
    val routeStateCallbackTimeout: Duration = Duration.ofSeconds(5),

    /**
     * 현재 위치(getCurrentLocationInfo → onCurrentLocationInfo) 콜백 대기 제한.
     * [근거] 실측 20~35ms(api-notes/navi-runtime-check.md 3.2). [설계 판단] 5초.
     */
    val locationCallbackTimeout: Duration = Duration.ofSeconds(5),

    /**
     * 내비 목적지(getDestinationInfo → onDestinationInfo) 콜백 대기 제한. 넘으면 Unavailable(TIMEOUT).
     * [근거] 실측: 경로 있을 때 약 1.0초(최대 1.03초, 전체 로그 기준·발췌 없음), 없을 때 50ms 미만
     *   (api-notes/navi-runtime-check.md 3.4, 7.3 "get*Info 5~10초 권장").
     * [설계 판단] 5초.
     */
    val destinationCallbackTimeout: Duration = Duration.ofSeconds(5),

    /**
     * 이 시간 안에 결과가 안 나오면 화면에 "확인 중" 표시를 띄운다.
     * [근거] Pleos 앱 심사 가이드(기능·성능 안정성): "2초 이내에 버튼 동작 또는 콘텐츠를 로드하지 못하는 경우,
     * UI를 통해 앱이 동작 중임을 알려야 합니다." (reviews/task-01b-playground-recheck.md MINOR-1, 원문 대조됨)
     */
    val loadingIndicatorThreshold: Duration = Duration.ofSeconds(2),

    /** 출발 마감 경고 단계 임계값. */
    val warningThresholds: WarningThresholds = WarningThresholds(),

    /**
     * 운전자 경로 조건이 없을 때(RoutePreference 가 빈 Set) 안내에 쓰일 것으로 보는 경로 옵션. 출발 마감 ETA 보정 판정에만 쓴다
     * (#5b 부터 우리 앱은 경로를 요청하지 않는다). 매핑은 usecase/RoutePreferenceMapper.
     * [설계 판단] RECOMMENDED(SDK 기본값). "조건 없음 = 추천 경로"라는 기대에 맞춘다(리뷰 task-03 MINOR-5).
     *   무료도로는 운전자 조건(PREFER_TOLL_FREE)이 있을 때만 요청한다. 감독관 확정 대상.
     * 이 값이 RECOMMENDED 가 아니면 조건이 없어도 출발 마감 ETA 를 보정한다(requestTrip 기준 경로와 다르므로).
     */
    val defaultRouteOption: RouteOption = RouteOption.RECOMMENDED,

    /**
     * "경로 교체 중일 수 있는" 결과(경로 확인 NotGuiding, 또는 직전에 있던 목적지가 NotSet 으로 바뀜)를 받았을 때 다시 확인하는 간격.
     * 다시 확인하는 동안 화면은 "목적지 없음"·"안내 중 아님"을 확정하지 않고 "다시 확인하고 있어요"로 보여 준다.
     * [근거] 경로가 바뀌면 onRouteCancelled → (0 값 목적지, SAFE_GUIDING) → onRouteStarted 까지 아래 시간이 걸렸다.
     *   - 사람이 내비 화면에서 바꿈: 7.3초(안내 중 목적지 변경), 18.9초(r4 "적용" 재탐색), 16.0초(r5 "유료도로 우회" 켜고 적용)
     *     (api-notes/navi-runtime-check.md §10.5 [실행 확인 2026-11-10], 리뷰 task-05 재검토 MINOR-R4·#7-10)
     *   - SDK requestRoute 로 바꿈: 7.5초(13km), 11.2초(375km), 16.3초(513km) — 거리가 길수록 늘지만 국내 최장 수준도 16초
     *     (§11.2 [실행 확인 2026-11-10])
     *   이 구간의 getDestinationInfo 는 오류 없이 "안내 없음"과 똑같은 0 값을 준다(§11.1) → 0 값만으로는 구분할 수 없어 다시 확인한다.
     * [설계 판단] 5초. 가장 짧은 실측(약 7초) 안에 한 번은 다시 보도록 그보다 짧게 잡았다. 조회 1회는 20~35ms 라 부담이 없다.
     */
    val routeChangeRecheckInterval: Duration = Duration.ofSeconds(5),

    /**
     * 위 재확인의 최대 횟수. 넘으면 그때의 결과("목적지 없음", "안내가 시작되지 않았어요")를 확정해 보여 준다.
     * [근거] 정상 변경의 실측 최대 약 19초(§10.5 사람 조작 18.9초, §11.2 SDK 변경 16.3초).
     *   경로 탐색이 실패하면(onError CAUSE_SEARCH_ROUTE) 이전 안내도 복구되지 않고 0 값이 무기한 이어진다(§11.2, 55초 관측) →
     *   기다리기만 하면 끝나지 않으므로 상한이 필요하다.
     * [설계 판단] 6회 × 5초 = 30초. 최대 실측의 약 1.5배라 느린 재탐색도 덮고, 넘으면 "목적지 없음"으로 확정한다(탐색 실패·안내 종료로 본다).
     *   사람이 내비 미리보기에서 오래 머무는 경우의 상한은 [미확인]이라 30초가 보장은 아니다(§11.5) — 이후 screenRefreshInterval 주기로 반영된다.
     */
    val routeChangeRecheckMaxAttempts: Int = 6,

    /**
     * 화면이 보이는 동안 전체 확인(목적지·출발 마감·반경·경로)을 다시 하는 주기. 남은 시간과 경고 단계 전환이 화면에 반영되게 한다
     * (리뷰 task-04b #7-2). ETA 는 [etaFreshnessLimit] 안에서는 재사용하므로 이 주기마다 requestTrip 을 부르지는 않는다.
     * [설계 판단] 60초. 남은 시간을 분 단위로 보여 주고 경고 임계값도 분 단위라 1분이면 충분하다. 목적지 변경도 이 주기로 반영된다
     * (onDestinationChanged 가 오지 않음 — §10.5).
     */
    val screenRefreshInterval: Duration = Duration.ofSeconds(60),

    /**
     * 주행 규제 상태를 모를 때(DrivingRestriction.UNKNOWN) 화면 조작 요소를 숨길지.
     * [근거] 주행 상태의 출처는 AAOS CarUxRestrictions 로 정했다(감독관 결정 #11, Pleos 주행 규제 개발 가이드가 앱에 요구 —
     *   api-notes/vehicle-state.md §12.3·§12.4). 플랫폼 자체가 주행 상태 unknown 을 "전부 제한"(Requires DO true, 0x1ff)으로
     *   다룬다(§11.2 [실행 확인 2026-11-10]).
     * [설계 판단] true(숨긴다). 값 미수신·어댑터 오류·구독 전 초기값은 제한 쪽(큰 글씨·최소 입력)으로 보여 플랫폼 기본과 맞춘다.
     *   그래서 어댑터는 주차 중이면 반드시 UNRESTRICTED 를 내보내야 한다(Fake 시나리오도 주차/주행을 명시). 1번이 주행 상태를
     *   제공하지 못하는 동안 실제 연동에서는 늘 UNKNOWN → 조작이 숨겨진다(integration-guide 수정 목록 5).
     */
    val treatUnknownDrivingAsRestricted: Boolean = true,

    /**
     * 끝난 주행의 야간 조건 판정(작업 #29)에서 위치를 모를 때 일몰을 계산할 기준점.
     * 순서: 호출자가 준 위치 → 운전자 조건의 반경 기준점(집) → 이 값.
     * [설계 판단] 서울시청(37.5665, 126.9780). 국내 일몰은 지역에 따라 30분 안쪽으로 달라 시연 판정에는 충분하다.
     */
    val defaultSunReferencePoint: GeoPoint = GeoPoint(latitude = 37.5665, longitude = 126.9780),

    /**
     * 끝난 주행이 야간에 걸친 시간이 이 값 이하이면 야간 조건을 지킨 것으로 본다(작업 #29).
     * [설계 판단] 0(1초라도 걸치면 위반). 도로교통법 '해가 진 후'를 그대로 따른다. 실차 확인 후 너그럽게 할지 조정 대상.
     */
    val tripNightTolerance: Duration = Duration.ZERO,
) {
    init {
        require(!departureMargin.isNegative) { "departureMargin must not be negative" }
        require(defaultAllowedRadiusMeters > 0.0) { "defaultAllowedRadiusMeters must be > 0" }
        require(fallbackTripDuration > Duration.ZERO) { "fallbackTripDuration must be > 0" }
        require(tollFreeDurationFactor >= 1.0) { "tollFreeDurationFactor must be >= 1.0 (보정은 늘리기만 한다)" }
        require(!tollFreeExtraTime.isNegative) { "tollFreeExtraTime must not be negative" }
        require(etaFreshnessLimit > Duration.ZERO) { "etaFreshnessLimit must be > 0" }
        require(tripCallbackTimeout > Duration.ZERO) { "tripCallbackTimeout must be > 0" }
        require(routeStateCallbackTimeout > Duration.ZERO) { "routeStateCallbackTimeout must be > 0" }
        require(locationCallbackTimeout > Duration.ZERO) { "locationCallbackTimeout must be > 0" }
        require(destinationCallbackTimeout > Duration.ZERO) { "destinationCallbackTimeout must be > 0" }
        require(loadingIndicatorThreshold > Duration.ZERO) { "loadingIndicatorThreshold must be > 0" }
        require(routeChangeRecheckInterval > Duration.ZERO) { "routeChangeRecheckInterval must be > 0" }
        require(routeChangeRecheckMaxAttempts >= 0) { "routeChangeRecheckMaxAttempts must be >= 0" }
        require(screenRefreshInterval > Duration.ZERO) { "screenRefreshInterval must be > 0" }
        require(!tripNightTolerance.isNegative) { "tripNightTolerance must not be negative" }
    }
}

/**
 * "야간" 시작 기준. 값마다 SunCalculator 에 넘길 태양 천정각을 가진다.
 * 기본 SUNSET 의 근거는 도로교통법 제37조 "해가 진 후"(api-notes/sunset-reference.md). 다른 값은 제도 확정 시 대비용.
 */
enum class NightStartReference(val zenithDegrees: Double) {
    /** 일몰(해의 윗가장자리가 지평선에 닿는 시각, 대기 굴절 포함). */
    SUNSET(SunCalculator.OFFICIAL_ZENITH),

    /** 시민 박명 끝(해가 지평선 아래 6°). 일몰보다 약 26~31분 늦다(서울·부산, api-notes/sunset-reference.md). */
    CIVIL_TWILIGHT_END(SunCalculator.CIVIL_ZENITH),
}

/**
 * 출발 마감까지 남은 시간에 따른 경고 단계 임계값. 판정은 usecase/DepartureDeadlineUseCase.
 * 남은 시간 > [cautionBefore] → 여유, ≤ [cautionBefore] → 주의, ≤ [urgentBefore] → 긴급(0 포함), 0 미만 → 마감 지남.
 * 경계값(정확히 같을 때)은 더 높은 경고 단계로 본다.
 * [설계 판단] 주의 60분, 긴급 20분.
 */
data class WarningThresholds(
    val cautionBefore: Duration = Duration.ofMinutes(60),
    val urgentBefore: Duration = Duration.ofMinutes(20),
) {
    init {
        require(urgentBefore > Duration.ZERO) { "urgentBefore must be > 0" }
        require(cautionBefore > urgentBefore) { "cautionBefore must be greater than urgentBefore" }
    }
}
