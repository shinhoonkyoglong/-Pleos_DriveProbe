package com.pleos.departure.model

import java.time.Instant

/**
 * 이번 확인에 쓴 운전자 조건을 어디서 얻었나(감독관 결정 D-9, contracts/driver-condition-api.md).
 */
enum class ConditionSource {
    /** 방금 조회한 값. */
    LOADED,

    /** 조회에 실패해 이 화면 세션에서 마지막으로 받은 값을 썼다. 화면은 "새로 확인하지 못함"을 알린다. */
    LAST_KNOWN,

    /**
     * 조회에 실패했고 마지막 값도 없어 **야간 제한만 있다고 보고** 계산했다(보수적 처리).
     * 화면은 "조건 확인 불가 — 야간 제한 기준"을 표시한다. 반경 기준점·경로 조건은 모르는 상태다.
     */
    ASSUMED_NIGHT_RESTRICTION,
}

/**
 * 출발 전 확인에 실제로 쓴 운전자 조건.
 * @property lookupFailure 조회 실패 사유. [ConditionSource.LOADED] 면 null.
 */
data class ResolvedCondition(
    val condition: DriverCondition,
    val source: ConditionSource,
    val lookupFailure: UnavailableReason?,
)

/**
 * 화면 한 번 갱신에 필요한 결과 묶음(작업 #7). [com.pleos.departure.usecase.DepartureCheckUseCase] 가 만든다.
 * 출발 마감·반경·경로 확인은 **같은 내비 목적지 조회 결과 하나**로 계산한다(리뷰 task-05 #7-5: 결과끼리 목적지 불일치 방지).
 */
sealed interface DepartureSnapshot {
    val evaluatedAt: Instant

    /**
     * 운전자 조건이 **확인된 미설정**이다(getCondition → Unavailable(NO_DATA), 감독관 결정 D-9).
     * "제한 없음"으로 보지 않는다. 출발 마감·반경·경로를 계산하지 않고 "조건 미설정" 안내만 한다.
     */
    data class ConditionNotSet(override val evaluatedAt: Instant) : DepartureSnapshot

    /**
     * 확인했다.
     * @property destination 이번에 한 번 조회한 내비 목적지((0,0) 방어 적용 후). 화면은 직전 목적지와 비교해 경로 교체 중인지 판단한다.
     */
    data class Checked(
        override val evaluatedAt: Instant,
        val condition: ResolvedCondition,
        val destination: DataResult<NaviDestination>,
        val deadline: DepartureDeadlineResult,
        val radius: RadiusCheckResult,
        val route: RouteConditionCheck,
    ) : DepartureSnapshot
}
