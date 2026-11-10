package com.pleos.departure.sun

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * NOAA 방식 일출·일몰 계산기. 순수 함수만 있고 상태가 없다(현재 시각을 읽지 않는다).
 *
 * ## 출처
 * - NOAA GML Solar Calculator: https://gml.noaa.gov/grad/solcalc/
 * - 계산식 스프레드시트(NOAA_Solar_Calculations_day.xls): https://gml.noaa.gov/grad/solcalc/calcdetails.html
 * - "General Solar Position Calculations" (NOAA GML): https://gml.noaa.gov/grad/solcalc/solareqns.PDF
 * - 원 출처: Jean Meeus, "Astronomical Algorithms"
 *
 * 구현은 스프레드시트의 Julian Century 기반 식을 따른다(PDF의 fractional-year 근사식보다 정확).
 *
 * ## 공식 요약 (각도 단위: 도, T = (JD - 2451545) / 36525)
 * - 평균 황경 L0 = 280.46646 + T(36000.76983 + 0.0003032T)  (mod 360)
 * - 평균 근점이각 M = 357.52911 + T(35999.05029 - 0.0001537T)
 * - 이심률 e = 0.016708634 - T(0.000042037 + 0.0000001267T)
 * - 중심차 C = sin(M)(1.914602 - T(0.004817 + 0.000014T)) + sin(2M)(0.019993 - 0.000101T) + sin(3M)·0.000289
 * - 겉보기 황경 λ = L0 + C - 0.00569 - 0.00478·sin(Ω),  Ω = 125.04 - 1934.136T
 * - 황도 경사 ε = ε0 + 0.00256·cos(Ω),  ε0 = 23 + (26 + (21.448 - T(46.815 + T(0.00059 - 0.001813T)))/60)/60
 * - 적위 δ = asin(sin ε · sin λ)
 * - 균시차(분) EoT = 4·deg( y·sin2L0 - 2e·sinM + 4ey·sinM·cos2L0 - 0.5y²·sin4L0 - 1.25e²·sin2M ),  y = tan²(ε/2)
 * - 시간각 HA = acos( cos(z) / (cosφ·cosδ) - tanφ·tanδ ),  z = 천정각
 * - UTC 기준 분: 남중 = 720 - 4·경도 - EoT,  일출 = 남중 - 4·HA,  일몰 = 남중 + 4·HA  (경도는 동경 +)
 *
 * 천정각 90.833° = 90° + 대기 굴절 약 0.567° + 태양 반지름 약 0.267°. 고도(해발)는 반영하지 않는다(NOAA 계산기와 동일).
 * 일출·일몰은 해당 시각의 T로 2회 재계산해(반복 보정) 정확도를 높인다.
 * NOAA는 중위도에서 ±1분 수준, 위도 ±72° 밖에서는 정확도가 떨어진다고 밝히고 있다.
 */
object SunCalculator {

    /** 일출·일몰 천정각(대기 굴절 포함). NOAA Solar Calculator 기준. */
    const val OFFICIAL_ZENITH = 90.833

    /** 시민 박명(civil twilight) 천정각. 이 각도로 계산하면 "박명 시작/끝" 시각이 나온다. */
    const val CIVIL_ZENITH = 96.0

    private const val REFINE_ITERATIONS = 2
    private const val MINUTES_PER_DAY = 1440.0
    private val HALF_DAY: Duration = Duration.ofHours(12)

    /**
     * [date] 하루(로컬)의 일출·남중·일몰 시각을 [zone] 시간대로 돌려준다.
     *
     * @param latitude 위도(북위 +), -90..90
     * @param longitude 경도(동경 +), -180..180
     * @param zenithDegrees 태양 천정각. 기본 [OFFICIAL_ZENITH], 시민 박명은 [CIVIL_ZENITH]
     * @throws IllegalArgumentException 위도·경도 범위를 벗어나거나 천정각이 0..180 밖일 때
     */
    fun sunTimes(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        zenithDegrees: Double = OFFICIAL_ZENITH,
    ): SunTimes {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        require(zenithDegrees > 0.0 && zenithDegrees < 180.0) { "zenithDegrees out of range: $zenithDegrees" }

        val baseUtcMidnight = utcMidnightNearestToLocalDay(date, longitude, zone)
        val baseJd = julianDay(baseUtcMidnight)

        // 남중 시각: 정오 근처 T로 한 번 계산 후, 구한 남중 시각의 T로 한 번 더 보정
        var noonMin = solarNoonUtcMinutes(baseJd, longitude, 720.0)
        noonMin = solarNoonUtcMinutes(baseJd, longitude, noonMin)
        val solarNoon = toZoned(baseUtcMidnight, noonMin, zone)

        // 남중 시각의 적위로 하루 상태(백야/극야) 판정
        val noonPosition = solarPosition(julianCentury(baseJd + noonMin / MINUTES_PER_DAY))
        val noonHa = hourAngleDegrees(latitude, noonPosition.declinationDeg, zenithDegrees)
        if (noonHa == null) {
            val state = if (cosHourAngle(latitude, noonPosition.declinationDeg, zenithDegrees) < -1.0) {
                SunState.ALWAYS_UP
            } else {
                SunState.ALWAYS_DOWN
            }
            return SunTimes(date, state, null, null, solarNoon, zenithDegrees)
        }

        val sunriseMin = refineEvent(baseJd, latitude, longitude, zenithDegrees, noonMin - 4.0 * noonHa, rising = true)
        val sunsetMin = refineEvent(baseJd, latitude, longitude, zenithDegrees, noonMin + 4.0 * noonHa, rising = false)

        return SunTimes(
            date = date,
            state = SunState.NORMAL,
            sunrise = toZoned(baseUtcMidnight, sunriseMin, zone),
            sunset = toZoned(baseUtcMidnight, sunsetMin, zone),
            solarNoon = solarNoon,
            zenithDegrees = zenithDegrees,
        )
    }

    // ---------------------------------------------------------------------
    // 날짜 기준점
    // ---------------------------------------------------------------------

    /**
     * 계산 기준이 될 UTC 자정을 고른다.
     *
     * 기본은 [date]의 UTC 자정이지만, 시간대가 경도와 크게 어긋나는 곳(날짜 변경선 부근 등)에서는
     * 그 기준으로 구한 남중 시각이 로컬 날짜로 하루 밀릴 수 있다. 그래서 대략적인 남중 시각이
     * 로컬 [date]의 정오와 12시간 이내가 되도록 기준일을 ±1일 옮긴다.
     */
    private fun utcMidnightNearestToLocalDay(date: LocalDate, longitude: Double, zone: ZoneId): Instant {
        val localNoon = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        var base = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        repeat(2) {
            // 균시차(최대 ±17분)는 무시한 근사 남중
            val approxNoon = base.plusSeconds(((720.0 - 4.0 * longitude) * 60).roundToLong())
            val diff = Duration.between(localNoon, approxNoon)
            base = when {
                diff > HALF_DAY -> base.minus(Duration.ofDays(1))
                diff < HALF_DAY.negated() -> base.plus(Duration.ofDays(1))
                else -> return base
            }
        }
        return base
    }

    private fun julianDay(utcMidnight: Instant): Double =
        // 1970-01-01T00:00Z = JD 2440587.5
        2440587.5 + floor(utcMidnight.epochSecond / 86400.0)

    private fun julianCentury(jd: Double): Double = (jd - 2451545.0) / 36525.0

    private fun toZoned(baseUtcMidnight: Instant, utcMinutes: Double, zone: ZoneId): ZonedDateTime =
        baseUtcMidnight.plusMillis((utcMinutes * 60_000.0).roundToLong()).atZone(zone)

    // ---------------------------------------------------------------------
    // 이벤트 시각
    // ---------------------------------------------------------------------

    private fun solarNoonUtcMinutes(baseJd: Double, longitude: Double, atUtcMinutes: Double): Double {
        val t = julianCentury(baseJd + atUtcMinutes / MINUTES_PER_DAY)
        return 720.0 - 4.0 * longitude - solarPosition(t).equationOfTimeMin
    }

    /**
     * 추정 시각의 태양 위치로 일출(또는 일몰) 시각을 다시 계산한다.
     * 보정 중 시간각이 정의되지 않으면(극지 경계) 직전 추정값을 그대로 쓴다.
     */
    private fun refineEvent(
        baseJd: Double,
        latitude: Double,
        longitude: Double,
        zenith: Double,
        initialUtcMinutes: Double,
        rising: Boolean,
    ): Double {
        var minutes = initialUtcMinutes
        repeat(REFINE_ITERATIONS) {
            val pos = solarPosition(julianCentury(baseJd + minutes / MINUTES_PER_DAY))
            val ha = hourAngleDegrees(latitude, pos.declinationDeg, zenith) ?: return minutes
            val noon = 720.0 - 4.0 * longitude - pos.equationOfTimeMin
            minutes = if (rising) noon - 4.0 * ha else noon + 4.0 * ha
        }
        return minutes
    }

    private fun cosHourAngle(latitude: Double, declinationDeg: Double, zenith: Double): Double {
        val lat = Math.toRadians(latitude)
        val dec = Math.toRadians(declinationDeg)
        return cos(Math.toRadians(zenith)) / (cos(lat) * cos(dec)) - tan(lat) * tan(dec)
    }

    /** 시간각(도). 해가 그 천정각을 지나지 않으면 null. */
    private fun hourAngleDegrees(latitude: Double, declinationDeg: Double, zenith: Double): Double? {
        val c = cosHourAngle(latitude, declinationDeg, zenith)
        if (c.isNaN() || abs(c) > 1.0) return null
        return Math.toDegrees(acos(c))
    }

    // ---------------------------------------------------------------------
    // 태양 위치 (NOAA 스프레드시트 식)
    // ---------------------------------------------------------------------

    private class SolarPosition(val declinationDeg: Double, val equationOfTimeMin: Double)

    private fun solarPosition(t: Double): SolarPosition {
        val l0 = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0 + 360.0) % 360.0
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mRad = Math.toRadians(m)
        val center = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mRad) * (0.019993 - 0.000101 * t) +
            sin(3 * mRad) * 0.000289
        val trueLong = l0 + center
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val apparentLong = trueLong - 0.00569 - 0.00478 * sin(omega)

        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = meanObliquity + 0.00256 * cos(omega)
        val oblRad = Math.toRadians(obliquity)

        val declination = Math.toDegrees(asin(sin(oblRad) * sin(Math.toRadians(apparentLong))))

        val y = tan(oblRad / 2).let { it * it }
        val l0Rad = Math.toRadians(l0)
        val eot = y * sin(2 * l0Rad) -
            2 * e * sin(mRad) +
            4 * e * y * sin(mRad) * cos(2 * l0Rad) -
            0.5 * y * y * sin(4 * l0Rad) -
            1.25 * e * e * sin(2 * mRad)
        return SolarPosition(declination, 4.0 * Math.toDegrees(eot))
    }
}
