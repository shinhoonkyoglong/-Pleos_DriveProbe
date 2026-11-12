package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.SunReference
import com.pleos.departure.model.SunReferenceSource
import com.pleos.departure.model.TripCompliance
import com.pleos.departure.model.TripConditionStatus
import com.pleos.departure.sun.SunCalculator
import com.pleos.departure.sun.SunState
import java.time.Duration
import java.time.Instant

/**
 * 끝난 주행 한 번의 운전자 조건 준수 판정(작업 #29). 순수 계산이라 상태·시계·포트가 없다.
 *
 * - 야간: 주행 구간 [startedAt, endedAt] 중 낮(일출~일몰)이 아닌 시간을 더해 [DepartureConfig.tripNightTolerance]보다 길면 위반.
 *   일몰 기준은 출발 마감과 같은 [DepartureConfig.nightStartReference](기본: 도로교통법 '해가 진 후'), 일출도 같은 천정각으로 계산한다.
 *   시간대는 [DepartureConfig.timeZone].
 * - 고속도로·반경: 주행 경로·위치 기록이 없어 조건이 있으면 NOT_JUDGED.
 * - 조건 미설정(null)·빈 조건이면 모두 NOT_APPLICABLE.
 *
 * 기준 위치 순서: [location] → 운전자 조건의 반경 기준점(집) → [DepartureConfig.defaultSunReferencePoint].
 */
class TripComplianceUseCase(
    private val config: DepartureConfig,
) {

    /**
     * @param condition 운전자 조건. null = 미설정
     * @param startedAt 주행 시작 시각
     * @param endedAt 주행 종료 시각(시작 이후)
     * @param location 일몰 계산 기준 위치(주행 출발지 등). 모르면 null
     * @throws IllegalArgumentException [endedAt]이 [startedAt]보다 앞설 때
     */
    fun evaluate(
        condition: DriverCondition?,
        startedAt: Instant,
        endedAt: Instant,
        location: GeoPoint? = null,
    ): TripCompliance {
        require(!endedAt.isBefore(startedAt)) { "endedAt($endedAt) is before startedAt($startedAt)" }

        val reference = when {
            location != null -> SunReference(location, SunReferenceSource.TRIP_LOCATION)
            condition?.radiusCenter != null -> SunReference(condition.radiusCenter, SunReferenceSource.RADIUS_CENTER)
            else -> SunReference(config.defaultSunReferencePoint, SunReferenceSource.CONFIG_DEFAULT)
        }
        val nightOverlap = nightOverlap(startedAt, endedAt, reference.point)

        val night = when {
            condition?.noNightDriving != true -> TripConditionStatus.NOT_APPLICABLE
            nightOverlap > config.tripNightTolerance -> TripConditionStatus.VIOLATED
            else -> TripConditionStatus.KEPT
        }
        val highway = if (condition != null && RouteConstraint.AVOID_HIGHWAY in condition.routePreference.constraints) {
            TripConditionStatus.NOT_JUDGED // TODO(경로 기록): 주행 경로 기록이 생기면 판정
        } else {
            TripConditionStatus.NOT_APPLICABLE
        }
        val radius = if (condition?.radiusCenter != null) {
            TripConditionStatus.NOT_JUDGED // TODO(위치 기록): 주행 위치 기록이 생기면 GeoDistance 로 판정
        } else {
            TripConditionStatus.NOT_APPLICABLE
        }

        return TripCompliance(
            startedAt = startedAt,
            endedAt = endedAt,
            night = night,
            highway = highway,
            radius = radius,
            nightOverlap = nightOverlap,
            sunReference = reference,
        )
    }

    /** 주행 구간 중 야간 시간 = 전체 − 낮 구간과 겹친 시간. */
    internal fun nightOverlap(startedAt: Instant, endedAt: Instant, point: GeoPoint): Duration {
        val total = Duration.between(startedAt, endedAt)
        if (total.isZero) return Duration.ZERO
        var daylight = Duration.ZERO
        for ((from, to) in daylightIntervals(startedAt, endedAt, point)) {
            val s = maxOf(from, startedAt)
            val e = minOf(to, endedAt)
            if (e.isAfter(s)) daylight += Duration.between(s, e)
        }
        return (total - daylight).coerceAtLeast(Duration.ZERO)
    }

    /** 주행 구간 앞뒤 하루씩을 포함한 날짜별 낮 구간(겹치면 합친다). */
    private fun daylightIntervals(startedAt: Instant, endedAt: Instant, point: GeoPoint): List<Pair<Instant, Instant>> {
        val zone = config.timeZone
        val first = startedAt.atZone(zone).toLocalDate().minusDays(1)
        val last = endedAt.atZone(zone).toLocalDate().plusDays(1)
        val raw = generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .mapNotNull { date ->
                val times = SunCalculator.sunTimes(
                    date = date,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    zone = zone,
                    zenithDegrees = config.nightStartReference.zenithDegrees,
                )
                when (times.state) {
                    SunState.NORMAL -> checkNotNull(times.sunrise).toInstant() to checkNotNull(times.sunset).toInstant()
                    SunState.ALWAYS_UP -> date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()
                    SunState.ALWAYS_DOWN -> null
                }
            }
            .sortedBy { it.first }
            .toList()
        val merged = mutableListOf<Pair<Instant, Instant>>()
        for (interval in raw) {
            val prev = merged.lastOrNull()
            if (prev != null && !interval.first.isAfter(prev.second)) {
                merged[merged.lastIndex] = prev.first to maxOf(prev.second, interval.second)
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun Duration.coerceAtLeast(min: Duration): Duration = if (this < min) min else this
}
