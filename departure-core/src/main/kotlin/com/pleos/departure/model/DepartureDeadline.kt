package com.pleos.departure.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 화면에 보여 줄 출발 마감 단계. 모든 [DepartureDeadlineResult]가 하나씩 가진다.
 * 화면(작업 #7)은 색만으로 구분하지 않고 단계별 문구 + 아이콘을 붙인다.
 */
enum class DeadlineStage {
    /** 야간 운전 제한이 없다(운전자 조건에 없음, 또는 백야로 오늘 일몰이 없음). 마감 표시 안 함. */
    NO_RESTRICTION,

    /** 마감까지 주의 임계값보다 많이 남음. */
    RELAXED,

    /** 마감까지 주의 임계값 이하. */
    CAUTION,

    /** 마감까지 긴급 임계값 이하(정확히 마감 시각 포함). */
    URGENT,

    /** 마감이 지났다 → 출발하지 않도록 안내. */
    PASSED,

    /** 지금이 야간이다(일출 전, 극야) → 출발하지 않도록 안내. */
    NIGHT_NOW,

    /**
     * 내비 목적지가 없거나 확인할 수 없어 마감을 계산하지 않았다(감독관 결정 11/10). 출발지 기준 오늘 일몰만 보여 주고
     * "내비에 목적지를 설정하면 출발 마감을 알려 드려요"라고 안내한다.
     */
    NO_DESTINATION,
}

/** 출발지(일몰 계산 기준점)를 어디서 얻었나. */
enum class OriginSource {
    /** NaviHelper `getCurrentLocationInfo` 현재 위치. */
    CURRENT_LOCATION,

    /** 현재 위치를 못 얻어 운전자 조건의 radiusCenter(보통 집)를 썼다. */
    HOME,
}

/**
 * 목적지가 없을 때 보여 줄 출발지 기준 오늘 일몰.
 * @property sunset 출발지 오늘 일몰(Config 시간대). 출발지는 있지만 오늘 해가 지지 않거나(백야) 뜨지 않으면(극야) null.
 */
data class OriginSunset(
    val origin: GeoPoint,
    val source: OriginSource,
    val sunset: SunsetBasis?,
)

/** 계산에 쓴 일몰이 어느 장소의 것인가. */
enum class SunsetPlace { ORIGIN, DESTINATION }

/**
 * 마감 계산에 쓴 "야간 시작" 시각.
 * @property zenithDegrees 계산에 쓴 천정각(DepartureConfig.nightStartReference). 90.833 = 일몰.
 */
data class SunsetBasis(
    val sunset: ZonedDateTime,
    val place: SunsetPlace,
    val zenithDegrees: Double,
)

/**
 * 마감 계산에 실제로 쓴 이동 시간.
 *
 * @property duration 계산에 쓴 이동 시간(보정 후).
 * @property source SDK_MEASURED = SDK 값 그대로, ESTIMATED = 무료도로 보정, DEFAULT = 기본 이동 시간.
 *   화면은 SDK_MEASURED 가 아니면 "예상" 표기를 강화한다.
 * @property sdkEstimate 보정 전 SDK 값. DEFAULT 면 null. 호출자는 이 값을 보관했다가 다음 계산의
 *   `previousEstimate`로 넘기면 신선한 동안 재조회를 생략한다.
 * @property unavailableReason DEFAULT 일 때 그 이유. ETA 를 못 얻었으면 그 사유, 재조회 후에도 오래된 값이면 NO_DATA.
 */
data class AppliedEta(
    val duration: Duration,
    val source: EstimateSource,
    val sdkEstimate: TripEstimate?,
    val unavailableReason: UnavailableReason? = null,
)

/**
 * 출발 마감 계산 결과. 화면 표시용이다(TTS·예약 알림 없음 — 사용자 결정 11/09).
 * 호출부는 `when`으로 모든 경우를 처리한다.
 *
 * @property evaluatedAt 계산 기준 시각(주입된 Clock).
 */
sealed interface DepartureDeadlineResult {
    val evaluatedAt: Instant
    val stage: DeadlineStage

    /** 운전자 조건에 야간 운전 제한이 없다. 마감을 계산하지 않는다. */
    data class NoNightRestriction(override val evaluatedAt: Instant) : DepartureDeadlineResult {
        override val stage: DeadlineStage get() = DeadlineStage.NO_RESTRICTION
    }

    /**
     * 출발 마감을 계산했다.
     * @property deadline 출발 마감 시각 = [sunset] − [eta] − [margin].
     * @property remaining 마감까지 남은 시간. 마감이 지났으면 음수.
     * @property stage RELAXED / CAUTION / URGENT / PASSED 중 하나.
     * @property destination 이 마감을 계산한 목적지. 화면은 이 값으로 목적지 이름을 보여 주고, 목적지가 바뀌었는지 비교한다.
     */
    data class Deadline(
        override val evaluatedAt: Instant,
        override val stage: DeadlineStage,
        val destination: Place,
        val deadline: ZonedDateTime,
        val remaining: Duration,
        val sunset: SunsetBasis,
        val eta: AppliedEta,
        val margin: Duration,
    ) : DepartureDeadlineResult

    /** 지금은 오늘 일출(박명 기준이면 박명 시작) 전이다 → 야간. [sunrise] 이후 다시 계산한다. */
    data class BeforeSunrise(
        override val evaluatedAt: Instant,
        val sunrise: ZonedDateTime,
    ) : DepartureDeadlineResult {
        override val stage: DeadlineStage get() = DeadlineStage.NIGHT_NOW
    }

    /** 백야: 출발지·목적지 모두 오늘 해가 지지 않는다 → 야간 제한에 걸리지 않음. */
    data class NoSunsetToday(
        override val evaluatedAt: Instant,
        val date: LocalDate,
    ) : DepartureDeadlineResult {
        override val stage: DeadlineStage get() = DeadlineStage.NO_RESTRICTION
    }

    /** 극야: 출발지 또는 목적지에서 오늘 해가 뜨지 않는다 → 하루 종일 야간. */
    data class AllDayNight(
        override val evaluatedAt: Instant,
        val date: LocalDate,
        val place: SunsetPlace,
    ) : DepartureDeadlineResult {
        override val stage: DeadlineStage get() = DeadlineStage.NIGHT_NOW
    }

    /**
     * 내비에 목적지가 없다 → 마감을 계산하지 않는다(감독관 결정 11/10: 추정 ETA로 근거 없는 마감 시각을 만들지 않음).
     * @property originSunset 출발지(현재 위치, 없으면 집) 오늘 일몰. 출발지를 전혀 모르면 null.
     */
    data class DestinationNotSet(
        override val evaluatedAt: Instant,
        val originSunset: OriginSunset?,
    ) : DepartureDeadlineResult {
        override val stage: DeadlineStage get() = DeadlineStage.NO_DESTINATION
    }

    /**
     * 내비 목적지를 확인할 수 없다(내비 없음·타임아웃 등) → 목적지 미설정과 같이 마감을 계산하지 않는다.
     * 화면 문구는 "목적지를 확인할 수 없어요"로 [DestinationNotSet]과 구분한다.
     */
    data class DestinationUnavailable(
        override val evaluatedAt: Instant,
        val reason: UnavailableReason,
        val originSunset: OriginSunset?,
    ) : DepartureDeadlineResult {
        override val stage: DeadlineStage get() = DeadlineStage.NO_DESTINATION
    }
}
