package com.pleos.departure.model

import java.time.Duration
import java.time.Instant

/**
 * 끝난 주행 한 번이 운전자 조건을 지켰는지(작업 #29). 5번(주행 후) 주행 기록·요약·리포트가 쓴다.
 *
 * 2번은 판정 함수만 제공한다. 언제 부를지, 어떻게 저장·표시할지는 5번 몫이다.
 * 판정은 "지금 저장된 운전자 조건" 기준이다(주행 당시 조건을 따로 보관하지 않는다).
 *
 * @property night 야간 운전 금지 조건 판정
 * @property highway 고속도로 회피 조건 판정. 주행 경로 기록이 없어 지금은 항상 NOT_JUDGED 또는 NOT_APPLICABLE
 * @property radius 허용 반경 조건 판정. 주행 위치 기록이 없어 지금은 항상 NOT_JUDGED 또는 NOT_APPLICABLE
 * @property nightOverlap 주행 중 야간(일몰 후 ~ 일출 전)에 걸친 시간. 야간 조건이 없어도 계산해 둔다
 * @property sunReference 일몰·일출 계산에 쓴 위치와 그 출처
 */
data class TripCompliance(
    val startedAt: Instant,
    val endedAt: Instant,
    val night: TripConditionStatus,
    val highway: TripConditionStatus,
    val radius: TripConditionStatus,
    val nightOverlap: Duration,
    val sunReference: SunReference,
) {
    /**
     * 전체 판정.
     * - 하나라도 VIOLATED → VIOLATED
     * - 아니고 하나라도 KEPT → KEPT(판정 못 한 조건이 있을 수 있다 — [hasUnjudged])
     * - 아니고 하나라도 NOT_JUDGED → NOT_JUDGED
     * - 모두 NOT_APPLICABLE(조건 없음) → NOT_APPLICABLE
     */
    val overall: TripConditionStatus
        get() {
            val all = listOf(night, highway, radius)
            return when {
                TripConditionStatus.VIOLATED in all -> TripConditionStatus.VIOLATED
                TripConditionStatus.KEPT in all -> TripConditionStatus.KEPT
                TripConditionStatus.NOT_JUDGED in all -> TripConditionStatus.NOT_JUDGED
                else -> TripConditionStatus.NOT_APPLICABLE
            }
        }

    /** 운전자에게 있는 조건 중 판정하지 못한 것이 있는가("모두 지킴"이라고 단정하면 안 되는 경우). */
    val hasUnjudged: Boolean
        get() = TripConditionStatus.NOT_JUDGED in listOf(night, highway, radius)

    /** 어긴 조건 목록. */
    val violations: Set<TripConditionKind>
        get() = buildSet {
            if (night == TripConditionStatus.VIOLATED) add(TripConditionKind.NO_NIGHT_DRIVING)
            if (highway == TripConditionStatus.VIOLATED) add(TripConditionKind.AVOID_HIGHWAY)
            if (radius == TripConditionStatus.VIOLATED) add(TripConditionKind.RADIUS)
        }
}

/** 조건 하나의 판정 결과. */
enum class TripConditionStatus {
    /** 조건이 있고 지켰다. */
    KEPT,

    /** 조건이 있고 어겼다. */
    VIOLATED,

    /** 조건은 있지만 판단할 자료가 없다(경로·위치 기록 없음 등). */
    NOT_JUDGED,

    /** 운전자에게 이 조건이 없다(또는 조건 미설정). */
    NOT_APPLICABLE,
}

/** 판정하는 조건 종류. */
enum class TripConditionKind { NO_NIGHT_DRIVING, AVOID_HIGHWAY, RADIUS }

/** 일몰·일출 계산 기준 위치. */
data class SunReference(val point: GeoPoint, val source: SunReferenceSource)

/** 기준 위치를 어디서 가져왔나. */
enum class SunReferenceSource {
    /** 호출자가 준 위치(주행 출발지 등). */
    TRIP_LOCATION,

    /** 운전자 조건의 반경 기준점(보통 집). */
    RADIUS_CENTER,

    /** DepartureConfig.defaultSunReferencePoint(위치를 모를 때). */
    CONFIG_DEFAULT,
}
