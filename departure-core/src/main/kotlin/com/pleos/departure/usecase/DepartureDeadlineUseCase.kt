package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.AppliedEta
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DeadlineStage
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.Place
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.OriginSource
import com.pleos.departure.model.OriginSunset
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.SunsetBasis
import com.pleos.departure.model.SunsetPlace
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.sun.SunCalculator
import com.pleos.departure.sun.SunState
import com.pleos.departure.sun.SunTimes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToLong

/**
 * 출발 마감 계산: "언제까지 출발해야 해가 지기 전에 도착하는가".
 *
 * 출발 마감 = 일몰 − 이동 시간(ETA) − 출발 여유([DepartureConfig.departureMargin]).
 *
 * ## 계산 순서
 * 1. 운전자 조건에 야간 운전 제한이 없으면 → [DepartureDeadlineResult.NoNightRestriction] (ETA 조회 안 함)
 * 2. 오늘(= [clock]의 현재 시각을 [DepartureConfig.timeZone]으로 본 날짜) 출발지·목적지의 일출·일몰 계산
 *    - 한 곳이라도 극야 → [DepartureDeadlineResult.AllDayNight]
 *    - 둘 다 백야 → [DepartureDeadlineResult.NoSunsetToday]
 *    - 지금이 일출 전 → [DepartureDeadlineResult.BeforeSunrise]
 * 3. ETA 선택(아래 규칙) → 마감 시각 → 남은 시간 → 단계([DepartureConfig.warningThresholds])
 *
 * ## 일몰 선택 — [설계 판단] 출발지·목적지 중 **더 이른 일몰**
 * 운전 중 어디서든 일몰을 넘기지 않도록 보수적으로 고른다. 한국 안 이동이면 차이는 수십 초~수 분이다.
 * 일출도 같은 이유로 두 곳 중 **더 늦은 일출**을 기준으로 "일출 전(야간)"을 판정한다.
 * 한 곳만 백야(일몰 없음)면 다른 곳의 일몰을 쓴다.
 * "일몰"의 천정각은 [DepartureConfig.nightStartReference]를 따른다. 기본은 일몰 — 도로교통법 제37조 제1항 제1호
 * "밤 = 해가 진 후부터 해가 뜨기 전까지"(api-notes/sunset-reference.md). 조건부 면허의 야간 정의 자체는 근거 없음.
 *
 * ## ETA 선택 규칙 (리뷰 task-03 "#4b-2")
 * - `previousEstimate`가 SDK 값이고 신선하고 **같은 목적지 좌표** 값이면 그대로 쓴다(재조회 안 함). basis 가 지금보다 미래면,
 *   목적지 좌표가 다르거나 없으면 재사용하지 않는다(리뷰 task-05 MAJOR-1).
 *   `selectEta`의 목적지는 내비 목적지를 매번 새로 조회한 값이라, 운전자가 내비에서 목적지를 바꾸면 자동으로 재조회된다.
 * - 아니면 [NaviRepository.requestTrip]. 받은 값이 오래됐으면(basis 기준 [DepartureConfig.etaFreshnessLimit] 초과) **1회 재조회**.
 *   재조회 값도 오래됐으면 기본 이동 시간(DEFAULT, 사유 NO_DATA).
 * - Unavailable(사유 무관) → [DepartureConfig.fallbackTripDuration], source = DEFAULT. 보정하지 않는다(이미 보수적인 값).
 * - SDK 값 + **안내에 쓸 옵션 ≠ ETA 기준 옵션** → SDK × [DepartureConfig.tollFreeDurationFactor] + [DepartureConfig.tollFreeExtraTime],
 *   source = ESTIMATED. 안내에 쓸 옵션 = [RoutePreferenceMapper] 결과(조건 맞춤 경로와 같은 규칙), ETA 기준 옵션 =
 *   [TripEstimate.routeOption](null = requestTrip, RECOMMENDED 로 봄 [미확인]). 보정 기준을 제약 종류가 아니라 매핑 결과로
 *   정한다 → 고속도로 회피 단독(FREE 로 안내)도 보정, 이미 FREE 기준인 ETA 는 이중 보정 안 함(리뷰 task-04b MAJOR-1, task-01c MAJOR-4).
 * - SDK 값 + 옵션 같음 → 그대로, source = SDK_MEASURED.
 * - `DestinationInfo.remainTime`은 쓰지 않는다(분 단위 내림 가능·주차 중 고정·옵션 기준 불명 — NaviDestination KDoc).
 *
 * ## 내비 목적지 기준 계산 — [calculateForNaviDestination] (작업 #5, 감독관 결정 11/10)
 * - 목적지 = 차량 내비에 설정된 목적지([NaviRepository.getDestination]).
 * - 목적지 미설정/확인 불가 → 마감을 **계산하지 않는다**(기본 이동 시간으로 근거 없는 마감을 만들지 않음).
 *   출발지 오늘 일몰만 담아 [DepartureDeadlineResult.DestinationNotSet] / [DepartureDeadlineResult.DestinationUnavailable].
 * - 출발지 = 현재 위치(NaviHelper getCurrentLocationInfo), 못 얻으면 운전자 조건의 radiusCenter(집).
 *   둘 다 없으면 목적지 일몰만으로 계산한다(설계 판단).
 *
 * ## 단계 경계 — [설계 판단] 경계값은 더 높은 경고 단계
 * 남은 시간 < 0 → PASSED, ≤ 긴급 임계(0 포함) → URGENT, ≤ 주의 임계 → CAUTION, 그 외 → RELAXED.
 *
 * 현재 시각은 [clock]만, 시간대는 [DepartureConfig.timeZone]만 쓴다(기본 시계·시스템 시간대 사용 금지).
 * 결과는 화면 표시용이다. TTS·예약 알림은 하지 않는다(사용자 결정 11/09).
 */
class DepartureDeadlineUseCase(
    private val naviRepository: NaviRepository,
    private val clock: Clock,
    private val config: DepartureConfig,
) {
    private val routeMapper = RoutePreferenceMapper(config)

    /**
     * 내비에 설정된 목적지 기준으로 출발 마감을 계산한다(화면의 기본 진입점).
     *
     * @param condition 운전자 조건(야간 제한, 경로 조건, 집 = radiusCenter)
     * @param previousEstimate 직전 결과의 [com.pleos.departure.model.AppliedEta.sdkEstimate]. 신선하면 재조회를 생략한다.
     *   목적지 좌표가 다르면 유스케이스가 알아서 재사용하지 않으므로 호출자는 그대로 넘겨도 된다.
     * @param destination 이미 조회한 내비 목적지가 있으면 넘긴다(반경 확인과 함께 쓸 때 중복 조회 방지). null 이면 조회한다.
     * @param currentLocation 이미 조회한 현재 위치가 있으면 넘긴다(리뷰 task-05 MINOR-4: 위치 중복 조회 방지). null 이면 조회한다.
     */
    suspend fun calculateForNaviDestination(
        condition: DriverCondition,
        previousEstimate: TripEstimate? = null,
        destination: DataResult<NaviDestination>? = null,
        currentLocation: DataResult<GeoPoint>? = null,
    ): DepartureDeadlineResult {
        val now = clock.instant()
        if (!condition.noNightDriving) return DepartureDeadlineResult.NoNightRestriction(now)

        val naviDestination = (destination ?: naviRepository.getDestination()).treatNullIslandAsNotSet()
        val origin = resolveOrigin(condition, currentLocation)
        return when (naviDestination) {
            is DataResult.Unavailable ->
                DepartureDeadlineResult.DestinationUnavailable(now, naviDestination.reason, origin?.let { originSunset(it, now) })
            is DataResult.Available -> when (val value = naviDestination.value) {
                NaviDestination.NotSet ->
                    DepartureDeadlineResult.DestinationNotSet(now, origin?.let { originSunset(it, now) })
                is NaviDestination.Present -> calculate(
                    origin = origin?.toPlace() ?: value.place,
                    destination = value.place,
                    condition = condition,
                    previousEstimate = previousEstimate,
                )
            }
        }
    }

    /** 일몰 계산용 출발지. calculate() 는 좌표만 쓰므로 Place 이름은 로그용이다. */
    private data class ResolvedOrigin(val point: GeoPoint, val source: OriginSource) {
        fun toPlace(): Place = Place(point = point, displayName = "출발지(${source.name})")
    }

    /** 출발지 = 현재 위치, 없으면 집(radiusCenter). 둘 다 없으면 null. */
    private suspend fun resolveOrigin(condition: DriverCondition, currentLocation: DataResult<GeoPoint>?): ResolvedOrigin? =
        when (val location = currentLocation ?: naviRepository.getCurrentLocation()) {
            is DataResult.Available -> ResolvedOrigin(location.value, OriginSource.CURRENT_LOCATION)
            is DataResult.Unavailable -> condition.radiusCenter?.let { ResolvedOrigin(it, OriginSource.HOME) }
        }

    /** 출발지 오늘 일몰. 백야·극야면 sunset = null. */
    private fun originSunset(origin: ResolvedOrigin, now: Instant): OriginSunset {
        val today = now.atZone(config.timeZone).toLocalDate()
        val times = SunCalculator.sunTimes(
            date = today,
            latitude = origin.point.latitude,
            longitude = origin.point.longitude,
            zone = config.timeZone,
            zenithDegrees = config.nightStartReference.zenithDegrees,
        )
        val sunset = when (times.state) {
            SunState.NORMAL -> SunsetBasis(
                sunset = checkNotNull(times.sunset) { "NORMAL SunTimes must have sunset" }.withZoneSameInstant(config.timeZone),
                place = SunsetPlace.ORIGIN,
                zenithDegrees = times.zenithDegrees,
            )
            SunState.ALWAYS_UP, SunState.ALWAYS_DOWN -> null
        }
        return OriginSunset(origin = origin.point, source = origin.source, sunset = sunset)
    }

    /**
     * @param origin 출발지(보통 현재 위치 또는 집). 일몰 계산에만 쓴다.
     * @param destination 목적지. 일몰 계산과 ETA 조회에 쓴다. 화면은 보통 [calculateForNaviDestination]을 쓴다(목적지 = 내비 목적지).
     * @param condition 운전자 조건. 야간 운전 제한 여부를 본다.
     * @param routePreference 경로 조건. 안내 옵션을 매핑해 ETA 보정 여부를 정한다. 기본은 운전자 조건의 값.
     * @param previousEstimate 직전 계산의 [com.pleos.departure.model.AppliedEta.sdkEstimate]. 신선하면 재조회를 생략한다.
     */
    suspend fun calculate(
        origin: Place,
        destination: Place,
        condition: DriverCondition,
        routePreference: RoutePreference = condition.routePreference,
        previousEstimate: TripEstimate? = null,
    ): DepartureDeadlineResult {
        val now = clock.instant()
        if (!condition.noNightDriving) return DepartureDeadlineResult.NoNightRestriction(now)

        // 날짜는 주입된 Clock 의 시각을 Config 시간대로 본 로컬 날짜(LocalDate.now(clock.withZone(zone)) 와 같고, 시각은 1회만 읽는다).
        val today = now.atZone(config.timeZone).toLocalDate()
        val daylights = listOf(
            daylightOf(SunsetPlace.ORIGIN, sunTimes(today, origin)),
            daylightOf(SunsetPlace.DESTINATION, sunTimes(today, destination)),
        )

        // 극야: 한 곳이라도 하루 종일 야간이면 보수적으로 출발 불가.
        daylights.filterIsInstance<Daylight.PolarNight>().firstOrNull()?.let {
            return DepartureDeadlineResult.AllDayNight(now, today, it.place)
        }

        // 백야인 곳은 일몰이 없으므로 후보에서 빠진다. 비교는 모두 Instant 기준
        // (고위도에서는 일몰의 로컬 날짜가 요청 날짜와 다를 수 있음 — 리뷰 task-04a 넘김 3).
        val normals = daylights.filterIsInstance<Daylight.Normal>()
        val latestSunrise = normals.maxByOrNull { it.sunrise.toInstant() }
            ?: return DepartureDeadlineResult.NoSunsetToday(now, today)
        if (now.isBefore(latestSunrise.sunrise.toInstant())) {
            return DepartureDeadlineResult.BeforeSunrise(now, latestSunrise.sunrise.withZoneSameInstant(config.timeZone))
        }

        val earliestSunset = normals.minByOrNull { it.sunset.toInstant() }
            ?: return DepartureDeadlineResult.NoSunsetToday(now, today)
        val sunset = SunsetBasis(
            sunset = earliestSunset.sunset.withZoneSameInstant(config.timeZone),
            place = earliestSunset.place,
            zenithDegrees = earliestSunset.zenithDegrees,
        )

        val eta = selectEta(destination, routePreference, previousEstimate, now)
        val deadline = sunset.sunset.minus(eta.duration).minus(config.departureMargin)
        val remaining = Duration.between(now, deadline.toInstant())

        return DepartureDeadlineResult.Deadline(
            evaluatedAt = now,
            stage = stageOf(remaining),
            destination = destination,
            deadline = deadline,
            remaining = remaining,
            sunset = sunset,
            eta = eta,
            margin = config.departureMargin,
        )
    }

    /** 남은 시간 → 단계. 경계값은 더 높은 경고 단계로 본다. */
    internal fun stageOf(remaining: Duration): DeadlineStage {
        val t = config.warningThresholds
        return when {
            remaining.isNegative -> DeadlineStage.PASSED
            remaining <= t.urgentBefore -> DeadlineStage.URGENT
            remaining <= t.cautionBefore -> DeadlineStage.CAUTION
            else -> DeadlineStage.RELAXED
        }
    }

    /** 한 장소의 하루 해 상태. SunTimes 의 nullable 일출·일몰을 상태별 non-null 값으로 바꾼다. */
    private sealed interface Daylight {
        val place: SunsetPlace

        data class Normal(
            override val place: SunsetPlace,
            val sunrise: ZonedDateTime,
            val sunset: ZonedDateTime,
            val zenithDegrees: Double,
        ) : Daylight

        /** 백야 — 일몰 없음. */
        data class MidnightSun(override val place: SunsetPlace) : Daylight

        /** 극야 — 일출 없음. */
        data class PolarNight(override val place: SunsetPlace) : Daylight
    }

    /** SunState 를 빠짐없이 처리한다(리뷰 task-04a 넘김 2). 새 상태가 생기면 컴파일 오류로 드러난다. */
    private fun daylightOf(place: SunsetPlace, times: SunTimes): Daylight = when (times.state) {
        SunState.NORMAL -> Daylight.Normal(
            place = place,
            sunrise = checkNotNull(times.sunrise) { "NORMAL SunTimes must have sunrise" },
            sunset = checkNotNull(times.sunset) { "NORMAL SunTimes must have sunset" },
            zenithDegrees = times.zenithDegrees,
        )
        SunState.ALWAYS_UP -> Daylight.MidnightSun(place)
        SunState.ALWAYS_DOWN -> Daylight.PolarNight(place)
    }

    private fun sunTimes(date: LocalDate, place: Place): SunTimes = SunCalculator.sunTimes(
        date = date,
        latitude = place.point.latitude,
        longitude = place.point.longitude,
        zone = config.timeZone,
        zenithDegrees = config.nightStartReference.zenithDegrees,
    )

    private suspend fun selectEta(
        destination: Place,
        routePreference: RoutePreference,
        previousEstimate: TripEstimate?,
        now: Instant,
    ): AppliedEta {
        // basis 가 지금보다 미래인 이전 값(시계 역행, Fake 시나리오 전환)은 재사용하지 않는다(리뷰 task-03 MINOR-3).
        // 방금 받은 응답은 basis 가 now 보다 약간 늦을 수 있으므로 이 조건은 이전 값에만 건다.
        // 다른 목적지(또는 목적지를 모르는) 값은 재사용하지 않는다(리뷰 task-05 MAJOR-1).
        val reusable = previousEstimate?.takeIf {
            it.destination == destination.point &&
                it.source == EstimateSource.SDK_MEASURED &&
                !it.basis.isAfter(now) &&
                it.isFresh(now, config.etaFreshnessLimit)
        }
        val sdk: DataResult<TripEstimate> = if (reusable != null) {
            DataResult.Available(reusable)
        } else {
            requestFreshTrip(destination, now)
        }

        return when (sdk) {
            is DataResult.Unavailable -> AppliedEta(
                duration = config.fallbackTripDuration,
                source = EstimateSource.DEFAULT,
                sdkEstimate = null,
                unavailableReason = sdk.reason,
            )
            is DataResult.Available -> if (needsRouteCorrection(routePreference, sdk.value)) {
                AppliedEta(
                    duration = tollFreeCorrected(sdk.value.duration),
                    source = EstimateSource.ESTIMATED,
                    sdkEstimate = sdk.value,
                )
            } else {
                AppliedEta(
                    duration = sdk.value.duration,
                    source = EstimateSource.SDK_MEASURED,
                    sdkEstimate = sdk.value,
                )
            }
        }
    }

    /** requestTrip, 오래된 값이면 1회 재조회. 그래도 오래됐으면 Unavailable(NO_DATA)로 본다. */
    private suspend fun requestFreshTrip(destination: Place, now: Instant): DataResult<TripEstimate> {
        repeat(MAX_TRIP_REQUESTS) {
            when (val result = naviRepository.requestTrip(destination)) {
                is DataResult.Unavailable -> return result
                is DataResult.Available -> {
                    // 이 ETA는 방금 요청한 목적지의 값이다 → 요청 좌표로 표시해 다음 계산의 재사용 판정에 쓴다(어댑터 값보다 우선).
                    val estimate = result.value.copy(destination = destination.point)
                    if (estimate.isFresh(now, config.etaFreshnessLimit)) return DataResult.Available(estimate)
                }
            }
        }
        return DataResult.Unavailable(UnavailableReason.NO_DATA, "trip estimate stale after retry")
    }

    /**
     * 안내에 쓸 옵션(매핑 결과)이 ETA 기준 옵션과 다른가. ETA 기준 옵션이 null(requestTrip)이면 RECOMMENDED 로 본다 [미확인].
     */
    internal fun needsRouteCorrection(routePreference: RoutePreference, estimate: TripEstimate): Boolean {
        val guided = routeMapper.map(routePreference).option
        val etaBasis = estimate.routeOption ?: RouteOption.RECOMMENDED
        return guided != etaBasis
    }

    private fun tollFreeCorrected(sdkDuration: Duration): Duration =
        Duration.ofMillis((sdkDuration.toMillis() * config.tollFreeDurationFactor).roundToLong())
            .plus(config.tollFreeExtraTime)

    private companion object {
        /** 최초 1회 + 오래된 값일 때 재조회 1회. */
        const val MAX_TRIP_REQUESTS = 2
    }
}
