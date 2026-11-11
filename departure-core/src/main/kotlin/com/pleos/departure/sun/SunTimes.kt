package com.pleos.departure.sun

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 하루 동안 해가 어떻게 움직이는지에 대한 분류.
 *
 * null 하나로 "해가 안 뜬다/안 진다/계산 실패"를 섞어 표현하지 않기 위해 상태를 따로 둔다.
 */
enum class SunState {
    /** 일출·일몰이 모두 있다(한국에서는 항상 이 값). */
    NORMAL,

    /** 백야(midnight sun): 하루 종일 해가 지평선(지정 천정각) 위에 있다. 일출·일몰 없음. */
    ALWAYS_UP,

    /** 극야(polar night): 하루 종일 해가 지평선(지정 천정각) 아래에 있다. 일출·일몰 없음. */
    ALWAYS_DOWN,
}

/**
 * [SunCalculator.sunTimes] 결과.
 *
 * - [state] == [SunState.NORMAL] 이면 [sunrise], [sunset] 모두 non-null.
 * - 그 외 상태면 둘 다 null. (생성 시 검증)
 *
 * 시각은 요청한 시간대(zone)로 표현된다. 저위도·중위도에서는 [date]와 같은 로컬 날짜에 놓이지만,
 * 고위도에서 백야 직전·직후처럼 일몰이 자정을 넘기는 날에는 다음 날짜로 표시될 수 있다.
 *
 * @property date 계산을 요청한 로컬 날짜
 * @property solarNoon 남중 시각(태양이 가장 높은 순간). 상태와 무관하게 항상 있다.
 * @property zenithDegrees 계산에 사용한 태양 천정각(도). 기본 90.833 = 일출·일몰.
 */
data class SunTimes(
    val date: LocalDate,
    val state: SunState,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val solarNoon: ZonedDateTime,
    val zenithDegrees: Double,
) {
    init {
        if (state == SunState.NORMAL) {
            require(sunrise != null && sunset != null) { "NORMAL 상태에는 sunrise/sunset 이 모두 필요하다" }
        } else {
            require(sunrise == null && sunset == null) { "$state 상태에는 sunrise/sunset 이 없어야 한다" }
        }
    }
}
