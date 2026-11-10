package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.NightStartReference
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DeadlineStage
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.OriginSource
import com.pleos.departure.model.Place
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.SunsetPlace
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.sun.SunCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DepartureDeadlineUseCaseTest {

    // ------------------------------------------------------------------
    // 테스트 도구
    // ------------------------------------------------------------------

    /**
     * requestTrip 응답을 순서대로 돌려주고 호출 횟수를 센다. 응답이 바닥나면 마지막 응답을 반복.
     * 내비 목적지·현재 위치는 프로퍼티로 바꿀 수 있다(기본: 서울역 목적지, 위치 없음).
     */
    private class StubNavi(vararg responses: DataResult<TripEstimate>) : NaviRepository {
        private val queue = responses.toMutableList()
        var tripCalls = 0
            private set
        var tripDestinations = mutableListOf<Place>()
        var destination: DataResult<NaviDestination> =
            DataResult.Available(NaviDestination.Present(Place(GeoPoint(latitude = 37.5547, longitude = 126.9707), "서울역")))
        var location: DataResult<GeoPoint> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)

        override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> {
            tripCalls++
            tripDestinations += destination
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }

        var destinationCalls = 0
            private set

        override suspend fun getDestination(): DataResult<NaviDestination> {
            destinationCalls++
            return destination
        }
        override suspend fun getCurrentLocation(): DataResult<GeoPoint> = location
        override suspend fun getRouteState(): DataResult<RouteState> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        override fun observeRemainingTrip(): Flow<RemainingTrip> = emptyFlow()
    }

    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 11, 9)

    private val home = Place(GeoPoint(latitude = 37.5563, longitude = 126.9220), "집")
    private val seoulStation = Place(GeoPoint(latitude = 37.5547, longitude = 126.9707), "서울역")

    private val nightOnly = DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING))
    private val nightAndTollFree = nightOnly.copy(routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE))

    private fun seoul(hour: Int, minute: Int, second: Int = 0, day: Int = 9): Instant =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, seoulZone).toInstant()

    private fun fixed(instant: Instant, zone: ZoneId = ZoneOffset.UTC): Clock = Clock.fixed(instant, zone)

    /** 기본은 서울역(기본 목적지) 기준 ETA. 목적지가 다른 ETA 는 [destination]으로 지정한다. */
    private fun sdk(minutes: Long, basis: Instant, destination: GeoPoint? = GeoPoint(latitude = 37.5547, longitude = 126.9707)) =
        TripEstimate(Duration.ofMinutes(minutes), 9_000, EstimateSource.SDK_MEASURED, basis, destination = destination)

    private fun available(minutes: Long, basis: Instant): DataResult<TripEstimate> = DataResult.Available(sdk(minutes, basis))

    /** 테스트 오라클: 서울역(목적지)의 2026-11-09 일몰. 집보다 약 12초 이르므로 기본 케이스에서 선택된다. */
    private val seoulStationSunset: Instant =
        SunCalculator.sunTimes(date, 37.5547, 126.9707, seoulZone).sunset!!.toInstant()

    private fun useCase(navi: NaviRepository, clock: Clock, config: DepartureConfig = DepartureConfig()) =
        DepartureDeadlineUseCase(navi, clock, config)

    private fun run(
        now: Instant,
        navi: NaviRepository,
        condition: DriverCondition = nightOnly,
        config: DepartureConfig = DepartureConfig(),
        clockZone: ZoneId = ZoneOffset.UTC,
        origin: Place = home,
        destination: Place = seoulStation,
        previous: TripEstimate? = null,
    ): DepartureDeadlineResult = runBlocking {
        useCase(navi, fixed(now, clockZone), config).calculate(origin, destination, condition, previousEstimate = previous)
    }

    private fun DepartureDeadlineResult.asDeadline() = this as DepartureDeadlineResult.Deadline

    // ------------------------------------------------------------------
    // 정상
    // ------------------------------------------------------------------

    @Test
    fun normalDaytime_usesSdkEtaAndEarlierSunset() {
        val now = seoul(13, 0)
        val r = run(now, StubNavi(available(25, now))).asDeadline()

        // 일몰: 서울역(목적지)이 집보다 이르다 → DESTINATION, 독립 계산값 17:27 과 ±1분
        assertEquals(SunsetPlace.DESTINATION, r.sunset.place)
        assertEquals(seoulStationSunset, r.sunset.sunset.toInstant())
        assertTrue(Duration.between(seoul(17, 27), r.sunset.sunset.toInstant()).abs() <= Duration.ofMinutes(1))
        assertEquals(seoulZone, r.sunset.sunset.zone)
        assertEquals(SunCalculator.OFFICIAL_ZENITH, r.sunset.zenithDegrees, 0.0)

        // 마감 = 일몰 − 25분 − 여유 30분
        assertEquals(seoulStationSunset.minus(Duration.ofMinutes(55)), r.deadline.toInstant())
        assertEquals(Duration.between(now, r.deadline.toInstant()), r.remaining)
        assertEquals(EstimateSource.SDK_MEASURED, r.eta.source)
        assertEquals(Duration.ofMinutes(25), r.eta.duration)
        assertNull(r.eta.unavailableReason)
        assertEquals(Duration.ofMinutes(30), r.margin)
        assertEquals(DeadlineStage.RELAXED, r.stage)
    }

    @Test
    fun originSunsetUsedWhenEarlier() {
        // 출발 서울역, 목적지 집 → 동쪽인 출발지(서울역)의 일몰이 더 이르다
        val now = seoul(13, 0)
        val r = run(now, StubNavi(available(25, now)), origin = seoulStation, destination = home).asDeadline()
        assertEquals(SunsetPlace.ORIGIN, r.sunset.place)
        assertEquals(seoulStationSunset, r.sunset.sunset.toInstant())
    }

    // ------------------------------------------------------------------
    // 단계 경계값
    // ------------------------------------------------------------------

    /** ETA 25분(무보정)일 때의 마감. */
    private val deadline25: Instant = seoulStationSunset.minus(Duration.ofMinutes(25 + 30))

    private fun stageAt(now: Instant): DeadlineStage = run(now, StubNavi(available(25, now))).stage

    @Test
    fun stageBoundaries_exactThresholdIsHigherWarning() {
        val oneSec = Duration.ofSeconds(1)
        val caution = Duration.ofMinutes(60)
        val urgent = Duration.ofMinutes(20)

        assertEquals(DeadlineStage.RELAXED, stageAt(deadline25.minus(caution).minus(oneSec)))
        assertEquals(DeadlineStage.CAUTION, stageAt(deadline25.minus(caution)))
        assertEquals(DeadlineStage.CAUTION, stageAt(deadline25.minus(urgent).minus(oneSec)))
        assertEquals(DeadlineStage.URGENT, stageAt(deadline25.minus(urgent)))
        assertEquals(DeadlineStage.URGENT, stageAt(deadline25))
        assertEquals(DeadlineStage.PASSED, stageAt(deadline25.plus(oneSec)))
    }

    @Test
    fun stageOf_directBoundaries() {
        val u = useCase(StubNavi(), fixed(seoul(13, 0)))
        assertEquals(DeadlineStage.RELAXED, u.stageOf(Duration.ofMinutes(61)))
        assertEquals(DeadlineStage.CAUTION, u.stageOf(Duration.ofMinutes(60)))
        assertEquals(DeadlineStage.CAUTION, u.stageOf(Duration.ofMinutes(21)))
        assertEquals(DeadlineStage.URGENT, u.stageOf(Duration.ofMinutes(20)))
        assertEquals(DeadlineStage.URGENT, u.stageOf(Duration.ZERO))
        assertEquals(DeadlineStage.PASSED, u.stageOf(Duration.ofMillis(-1)))
    }

    // ------------------------------------------------------------------
    // 마감 지남 / ETA 가 일몰을 넘김
    // ------------------------------------------------------------------

    @Test
    fun deadlineAlreadyPassed_remainingNegative() {
        val now = seoul(18, 0) // 일몰 후
        val r = run(now, StubNavi(available(25, now))).asDeadline()
        assertEquals(DeadlineStage.PASSED, r.stage)
        assertTrue(r.remaining.isNegative)
        assertEquals(deadline25, r.deadline.toInstant())
    }

    @Test
    fun etaCrossesSunset_isPassedEvenBeforeSunset() {
        // 16:50 출발, ETA 40분 → 도착 17:30 > 일몰 17:26 → 이미 마감 지남
        val now = seoul(16, 50)
        val r = run(now, StubNavi(available(40, now))).asDeadline()
        assertTrue(now.isBefore(seoulStationSunset))
        assertTrue(now.plus(Duration.ofMinutes(40)).isAfter(seoulStationSunset))
        assertEquals(DeadlineStage.PASSED, r.stage)
    }

    // ------------------------------------------------------------------
    // 자정 경계 / 시간대
    // ------------------------------------------------------------------

    @Test
    fun justBeforeMidnight_isPassedWithTodaysDeadline() {
        val now = seoul(23, 59, 59)
        val r = run(now, StubNavi(available(25, now))).asDeadline()
        assertEquals(DeadlineStage.PASSED, r.stage)
        assertEquals(date, r.deadline.toLocalDate())
    }

    @Test
    fun afterMidnight_usesConfigTimeZoneDate_andIsBeforeSunrise() {
        // 2026-11-09T15:30Z = 11-10 00:30 KST. 시계 zone 이 UTC 여도 날짜는 Config(Asia/Seoul) 기준 11-10 이어야 한다.
        val now = Instant.parse("2026-11-09T15:30:00Z")
        val navi = StubNavi(available(25, now))
        val r = run(now, navi, clockZone = ZoneOffset.UTC) as DepartureDeadlineResult.BeforeSunrise

        assertEquals(DeadlineStage.NIGHT_NOW, r.stage)
        assertEquals(LocalDate.of(2026, 11, 10), r.sunrise.toLocalDate())
        assertEquals(seoulZone, r.sunrise.zone)
        assertEquals(7, r.sunrise.hour)
        assertEquals(0, navi.tripCalls) // 야간이면 ETA 조회 안 함
    }

    @Test
    fun beforeSunrise_usesLaterSunriseOfTwoPlaces_andExactSunriseIsDaytime() {
        val homeSunrise = SunCalculator.sunTimes(date, 37.5563, 126.9220, seoulZone).sunrise!!.toInstant()
        val stationSunrise = SunCalculator.sunTimes(date, 37.5547, 126.9707, seoulZone).sunrise!!.toInstant()
        assertTrue(homeSunrise.isAfter(stationSunrise)) // 집(서쪽)이 더 늦게 해가 뜬다

        val before = run(homeSunrise.minusSeconds(1), StubNavi(available(25, homeSunrise)))
        assertEquals(homeSunrise, (before as DepartureDeadlineResult.BeforeSunrise).sunrise.toInstant())

        val atSunrise = run(homeSunrise, StubNavi(available(25, homeSunrise)))
        assertEquals(DeadlineStage.RELAXED, atSunrise.stage)
    }

    // ------------------------------------------------------------------
    // ETA 폴백 / 무료도로 보정 / 재조회
    // ------------------------------------------------------------------

    @Test
    fun etaUnavailable_fallsBackToDefaultForEveryReason_withoutTollFreeCorrection() {
        val now = seoul(13, 0)
        for (reason in UnavailableReason.values()) {
            val r = run(now, StubNavi(DataResult.Unavailable(reason)), condition = nightAndTollFree).asDeadline()
            assertEquals(EstimateSource.DEFAULT, r.eta.source)
            assertEquals(Duration.ofMinutes(60), r.eta.duration)
            assertEquals(reason, r.eta.unavailableReason)
            assertNull(r.eta.sdkEstimate)
            assertEquals(seoulStationSunset.minus(Duration.ofMinutes(90)), r.deadline.toInstant())
        }
    }

    @Test
    fun tollFreeCondition_correctsSdkEta() {
        val now = seoul(13, 0)
        val r = run(now, StubNavi(available(35, now)), condition = nightAndTollFree).asDeadline()
        assertEquals(EstimateSource.ESTIMATED, r.eta.source)
        assertEquals(Duration.ofMinutes(42), r.eta.duration) // 35 × 1.2
        assertEquals(Duration.ofMinutes(35), r.eta.sdkEstimate!!.duration) // 보정 전 값 보존
        assertEquals(seoulStationSunset.minus(Duration.ofMinutes(72)), r.deadline.toInstant())
    }

    @Test
    fun tollFreeCorrection_usesConfigExtraTime() {
        val now = seoul(13, 0)
        val config = DepartureConfig(tollFreeDurationFactor = 1.5, tollFreeExtraTime = Duration.ofMinutes(5))
        val corrected = run(now, StubNavi(available(20, now)), condition = nightAndTollFree, config = config).asDeadline()
        assertEquals(Duration.ofMinutes(35), corrected.eta.duration) // 20 × 1.5 + 5
    }

    /** 리뷰 task-04b MAJOR-1: 고속도로 회피 단독도 FREE 로 안내되므로 requestTrip(기본 경로) 값을 보정한다. */
    @Test
    fun avoidHighwayAlone_isCorrectedBecauseGuidedOptionIsFree() {
        val now = seoul(13, 0)
        val avoidHighway = nightOnly.copy(routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY))
        val r = run(now, StubNavi(available(20, now)), condition = avoidHighway).asDeadline()
        assertEquals(EstimateSource.ESTIMATED, r.eta.source)
        assertEquals(Duration.ofMinutes(24), r.eta.duration) // 20 × 1.2
    }

    /** 조건 없음 → 기본 옵션 RECOMMENDED = requestTrip 기준과 같음 → 보정 없음. 기본 옵션을 FREE 로 바꾸면 보정한다. */
    @Test
    fun noRoutePreference_followsConfigDefaultOption() {
        val now = seoul(13, 0)
        val plain = run(now, StubNavi(available(20, now))).asDeadline()
        assertEquals(EstimateSource.SDK_MEASURED, plain.eta.source)
        assertEquals(Duration.ofMinutes(20), plain.eta.duration)

        val freeDefault = run(now, StubNavi(available(20, now)), config = DepartureConfig(defaultRouteOption = RouteOption.FREE)).asDeadline()
        assertEquals(EstimateSource.ESTIMATED, freeDefault.eta.source)
        assertEquals(Duration.ofMinutes(24), freeDefault.eta.duration)
    }

    /** 리뷰 task-01c MAJOR-4: ETA 가 이미 FREE 경로 기준이면 이중 보정하지 않는다. */
    @Test
    fun estimateAlreadyOnGuidedOption_isNotCorrectedTwice() {
        val now = seoul(13, 0)
        val freeBased = TripEstimate(Duration.ofMinutes(20), 9_000, EstimateSource.SDK_MEASURED, now, routeOption = RouteOption.FREE)
        val r = run(now, StubNavi(DataResult.Available(freeBased)), condition = nightAndTollFree).asDeadline()
        assertEquals(EstimateSource.SDK_MEASURED, r.eta.source)
        assertEquals(Duration.ofMinutes(20), r.eta.duration)
    }

    @Test
    fun freshPreviousEstimate_isReusedWithoutRequest_boundaryIncluded() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(99, now))
        val previous = sdk(25, now.minus(Duration.ofMinutes(15))) // 정확히 신선도 한계

        val r = run(now, navi, previous = previous).asDeadline()
        assertEquals(0, navi.tripCalls)
        assertEquals(Duration.ofMinutes(25), r.eta.duration)
    }

    @Test
    fun stalePreviousEstimate_isRequeried() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(30, now))
        val previous = sdk(25, now.minus(Duration.ofMinutes(15)).minusSeconds(1))

        val r = run(now, navi, previous = previous).asDeadline()
        assertEquals(1, navi.tripCalls)
        assertEquals(Duration.ofMinutes(30), r.eta.duration)
        assertEquals(now, r.eta.sdkEstimate!!.basis)
    }

    @Test
    fun previousEstimateFromFutureOrNotSdk_isRequeried() {
        val now = seoul(13, 0)
        val future = StubNavi(available(30, now))
        run(now, future, previous = sdk(25, now.plusSeconds(60)))
        assertEquals(1, future.tripCalls)

        val notSdk = StubNavi(available(30, now))
        run(now, notSdk, previous = TripEstimate(Duration.ofMinutes(25), null, EstimateSource.DEFAULT, now))
        assertEquals(1, notSdk.tripCalls)
    }

    @Test
    fun staleResponse_isRequeriedOnce_thenUsesFreshValue() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(40, now.minus(Duration.ofMinutes(20))), available(30, now))

        val r = run(now, navi).asDeadline()
        assertEquals(2, navi.tripCalls)
        assertEquals(Duration.ofMinutes(30), r.eta.duration)
        assertEquals(EstimateSource.SDK_MEASURED, r.eta.source)
    }

    @Test
    fun staleResponseTwice_fallsBackToDefaultNoData() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(40, now.minus(Duration.ofMinutes(20))))

        val r = run(now, navi).asDeadline()
        assertEquals(2, navi.tripCalls)
        assertEquals(EstimateSource.DEFAULT, r.eta.source)
        assertEquals(UnavailableReason.NO_DATA, r.eta.unavailableReason)
    }

    // ------------------------------------------------------------------
    // 야간 제한 없음 / 백야·극야 / 박명 기준
    // ------------------------------------------------------------------

    @Test
    fun noNightRestriction_skipsCalculationAndEta() {
        val now = seoul(23, 0)
        val navi = StubNavi(available(25, now))
        val r = run(now, navi, condition = DriverCondition(routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE)))
        assertTrue(r is DepartureDeadlineResult.NoNightRestriction)
        assertEquals(DeadlineStage.NO_RESTRICTION, r.stage)
        assertEquals(0, navi.tripCalls)
    }

    private val osloZone = ZoneId.of("Europe/Oslo")
    private val tromso = Place(GeoPoint(latitude = 69.65, longitude = 18.96), "Tromsø")
    private val oslo = Place(GeoPoint(latitude = 59.91, longitude = 10.75), "Oslo")
    private val osloConfig = DepartureConfig(timeZone = osloZone)
    private fun osloNoon(d: String) = LocalDate.parse(d).atTime(12, 0).atZone(osloZone).toInstant()

    @Test
    fun midnightSun_bothPlaces_isNoSunsetToday() {
        val now = osloNoon("2026-06-21")
        val navi = StubNavi(available(25, now))
        val r = run(now, navi, config = osloConfig, origin = tromso, destination = tromso)
        assertTrue(r is DepartureDeadlineResult.NoSunsetToday)
        assertEquals(DeadlineStage.NO_RESTRICTION, r.stage)
        assertEquals(0, navi.tripCalls)
    }

    @Test
    fun midnightSunAtOriginOnly_usesDestinationSunset() {
        val now = osloNoon("2026-06-21")
        val r = run(now, StubNavi(available(25, now)), config = osloConfig, origin = tromso, destination = oslo).asDeadline()
        assertEquals(SunsetPlace.DESTINATION, r.sunset.place)
        assertEquals(osloZone, r.sunset.sunset.zone)
        assertEquals(DeadlineStage.RELAXED, r.stage)
    }

    @Test
    fun polarNight_atEitherPlace_isAllDayNight() {
        val now = osloNoon("2026-12-21")
        val atOrigin = run(now, StubNavi(available(25, now)), config = osloConfig, origin = tromso, destination = oslo)
        assertEquals(SunsetPlace.ORIGIN, (atOrigin as DepartureDeadlineResult.AllDayNight).place)
        assertEquals(DeadlineStage.NIGHT_NOW, atOrigin.stage)

        val atDestination = run(now, StubNavi(available(25, now)), config = osloConfig, origin = oslo, destination = tromso)
        assertEquals(SunsetPlace.DESTINATION, (atDestination as DepartureDeadlineResult.AllDayNight).place)
    }

    @Test
    fun civilTwilightReference_movesSunsetLater() {
        val now = seoul(13, 0)
        val config = DepartureConfig(nightStartReference = NightStartReference.CIVIL_TWILIGHT_END)
        val r = run(now, StubNavi(available(25, now)), config = config).asDeadline()
        assertEquals(SunCalculator.CIVIL_ZENITH, r.sunset.zenithDegrees, 0.0)
        val later = Duration.between(seoulStationSunset, r.sunset.sunset.toInstant())
        assertTrue("박명 끝은 일몰보다 20~40분 늦다: $later", later > Duration.ofMinutes(20) && later < Duration.ofMinutes(40))
    }

    // ------------------------------------------------------------------
    // 내비 목적지 기준 계산(작업 #5, 감독관 결정 11/10: 목적지 없으면 마감 미계산)
    // ------------------------------------------------------------------

    private val busan = GeoPoint(latitude = 35.1151, longitude = 129.0415)
    private val nightWithHome = nightOnly.copy(radiusCenter = home.point)

    private fun runNavi(now: Instant, navi: StubNavi, condition: DriverCondition = nightWithHome): DepartureDeadlineResult =
        runBlocking { useCase(navi, fixed(now)).calculateForNaviDestination(condition) }

    private fun sunsetAt(point: GeoPoint): Instant =
        SunCalculator.sunTimes(date, point.latitude, point.longitude, seoulZone).sunset!!.toInstant()

    @Test
    fun naviDestinationPresent_matchesDirectCalculation_withCurrentLocationAsOrigin() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(25, now)).apply { location = DataResult.Available(home.point) }

        val viaNavi = runNavi(now, navi).asDeadline()
        val direct = run(now, StubNavi(available(25, now)), origin = home, destination = seoulStation).asDeadline()

        assertEquals(direct.deadline, viaNavi.deadline)
        assertEquals(direct.sunset, viaNavi.sunset)
        assertEquals(seoulStation.point, navi.tripDestinations.single().point) // ETA 는 내비 목적지로 조회
    }

    @Test
    fun destinationNotSet_doesNotCalculateDeadline_andShowsCurrentLocationSunset() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now)).apply {
            destination = DataResult.Available(NaviDestination.NotSet)
            location = DataResult.Available(busan)
        }

        val r = runNavi(now, navi) as DepartureDeadlineResult.DestinationNotSet
        assertEquals(DeadlineStage.NO_DESTINATION, r.stage)
        assertEquals(0, navi.tripCalls) // 기본 이동 시간으로 마감을 만들지 않는다
        val origin = r.originSunset!!
        assertEquals(OriginSource.CURRENT_LOCATION, origin.source)
        assertEquals(busan, origin.origin)
        assertEquals(sunsetAt(busan), origin.sunset!!.sunset.toInstant())
        assertEquals(SunsetPlace.ORIGIN, origin.sunset!!.place)
        assertEquals(seoulZone, origin.sunset!!.sunset.zone)
    }

    @Test
    fun destinationNotSet_withoutLocation_usesHomeSunset() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now)).apply { destination = DataResult.Available(NaviDestination.NotSet) }

        val origin = (runNavi(now, navi) as DepartureDeadlineResult.DestinationNotSet).originSunset!!
        assertEquals(OriginSource.HOME, origin.source)
        assertEquals(sunsetAt(home.point), origin.sunset!!.sunset.toInstant())
    }

    @Test
    fun destinationNotSet_withoutLocationAndHome_hasNoOriginSunset() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now)).apply { destination = DataResult.Available(NaviDestination.NotSet) }

        val r = runNavi(now, navi, condition = nightOnly) as DepartureDeadlineResult.DestinationNotSet
        assertNull(r.originSunset)
    }

    @Test
    fun destinationUnavailable_keepsReason_andDoesNotRequestTrip() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now)).apply { destination = DataResult.Unavailable(UnavailableReason.TIMEOUT) }

        val r = runNavi(now, navi) as DepartureDeadlineResult.DestinationUnavailable
        assertEquals(UnavailableReason.TIMEOUT, r.reason)
        assertEquals(DeadlineStage.NO_DESTINATION, r.stage)
        assertEquals(OriginSource.HOME, r.originSunset!!.source)
        assertEquals(0, navi.tripCalls)
    }

    @Test
    fun noNightRestriction_doesNotQueryNavi() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now))
        val r = runNavi(now, navi, condition = DriverCondition(radiusCenter = home.point))
        assertTrue(r is DepartureDeadlineResult.NoNightRestriction)
        assertEquals(0, navi.destinationCalls)
        assertEquals(0, navi.tripCalls)
    }

    @Test
    fun naviDestinationPresent_withoutAnyOrigin_usesDestinationSunsetOnly() {
        val now = seoul(13, 0)
        val r = runNavi(now, StubNavi(available(25, now)), condition = nightOnly).asDeadline()
        assertEquals(seoulStationSunset, r.sunset.sunset.toInstant())
    }

    @Test
    fun alreadyFetchedDestination_isNotQueriedAgain() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(25, now))
        val given = DataResult.Available(NaviDestination.Present(seoulStation))
        runBlocking { useCase(navi, fixed(now)).calculateForNaviDestination(nightWithHome, destination = given) }
        assertEquals(0, navi.destinationCalls)
        assertEquals(1, navi.tripCalls)
    }

    // ------------------------------------------------------------------
    // 리뷰 task-05 MAJOR-1: 목적지가 바뀌면 이전 ETA 를 재사용하지 않는다
    // ------------------------------------------------------------------

    @Test
    fun freshPreviousEstimateForOtherDestination_isNotReused() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(55, now))
        val previousForHome = sdk(25, now, destination = home.point) // 신선하지만 다른 목적지 값

        val r = run(now, navi, previous = previousForHome).asDeadline()
        assertEquals(1, navi.tripCalls)
        assertEquals(Duration.ofMinutes(55), r.eta.duration)
    }

    @Test
    fun previousEstimateWithoutDestination_isNotReused() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(30, now))
        run(now, navi, previous = sdk(25, now, destination = null))
        assertEquals(1, navi.tripCalls)
    }

    /** 내비에서 목적지를 바꾼 상황: 이전 결과의 sdkEstimate 를 그대로 넘겨도 새 목적지로 다시 조회한다. */
    @Test
    fun naviDestinationChanged_requeriesEvenWithFreshPrevious() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(25, now))
        val first = runBlocking { useCase(navi, fixed(now)).calculateForNaviDestination(nightOnly) }.asDeadline()
        assertEquals(seoulStation.point, first.destination.point)

        val busanPlace = Place(GeoPoint(latitude = 35.1151, longitude = 129.0415), "부산역")
        navi.destination = DataResult.Available(NaviDestination.Present(busanPlace))
        val second = runBlocking {
            useCase(navi, fixed(now)).calculateForNaviDestination(nightOnly, previousEstimate = first.eta.sdkEstimate)
        }.asDeadline()

        assertEquals(2, navi.tripCalls)
        assertEquals(busanPlace.point, navi.tripDestinations.last().point)
        assertEquals(busanPlace, second.destination)
        assertEquals(busanPlace.point, second.eta.sdkEstimate!!.destination)
    }

    /** 어댑터가 destination 을 비워도 유스케이스가 요청 좌표로 채워 다음 재사용에 쓴다. */
    @Test
    fun responseWithoutDestination_isStampedWithRequestedPoint_andReusedForSameDestination() {
        val now = seoul(13, 0)
        val navi = StubNavi(available(25, now).let { DataResult.Available((it as DataResult.Available).value.copy(destination = null)) })
        val first = run(now, navi).asDeadline()
        assertEquals(seoulStation.point, first.eta.sdkEstimate!!.destination)

        run(now, navi, previous = first.eta.sdkEstimate)
        assertEquals(1, navi.tripCalls)
    }

    // --- 리뷰 task-05 MINOR-2: core (0,0) 방어 ---

    @Test
    fun nullIslandDestination_isTreatedAsNotSet() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now)).apply {
            destination = DataResult.Available(NaviDestination.Present(Place(GeoPoint(latitude = 0.0, longitude = 0.0), "")))
        }
        assertTrue(runNavi(now, navi) is DepartureDeadlineResult.DestinationNotSet)
        assertEquals(0, navi.tripCalls)
    }

    // --- 리뷰 task-05 MINOR-4: 이미 조회한 위치를 넘기면 다시 조회하지 않는다 ---

    @Test
    fun givenCurrentLocation_isUsedAsOrigin() {
        val now = seoul(15, 35)
        val navi = StubNavi(available(25, now)).apply { destination = DataResult.Available(NaviDestination.NotSet) }
        val r = runBlocking {
            useCase(navi, fixed(now)).calculateForNaviDestination(nightWithHome, currentLocation = DataResult.Available(busan))
        } as DepartureDeadlineResult.DestinationNotSet
        assertEquals(OriginSource.CURRENT_LOCATION, r.originSunset!!.source)
        assertEquals(busan, r.originSunset!!.origin)
    }
}
