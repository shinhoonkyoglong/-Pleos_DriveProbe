package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.DepartureDependencies
import com.pleos.departure.DepartureFeature
import com.pleos.departure.NightStartReference
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.SpeakResult
import com.pleos.departure.model.SunReferenceSource
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.TripConditionKind
import com.pleos.departure.model.TripConditionStatus
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import com.pleos.departure.port.VehicleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 끝난 주행 조건 준수 판정(작업 #29).
 * 기준 시각: 서울 2026-11-09 일출 07:04:56, 일몰 17:26:18 (NOAA 계산값, KASI 07:05 / 17:26 — SunCalculatorTest·sunset-reference.md).
 * 11/10 일출은 약 07:06.
 */
class TripComplianceUseCaseTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val home = GeoPoint(latitude = 37.5665, longitude = 126.9780)
    private val useCase = TripComplianceUseCase(DepartureConfig())
    private val noNight = DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING))

    private fun at(date: String, time: String): Instant = LocalDateTime.parse("${date}T$time").atZone(seoul).toInstant()

    private fun assertNear(label: String, expected: Duration, actual: Duration, tolerance: Duration = Duration.ofMinutes(2)) {
        val diff = (expected - actual).abs()
        assertTrue("$label 기대 $expected, 실제 $actual", diff <= tolerance)
    }

    // ---- 조건 없음 ----

    @Test
    fun noCondition_allNotApplicable_evenAtNight() {
        val r = useCase.evaluate(null, at("2026-11-09", "21:00"), at("2026-11-09", "22:00"))

        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.night)
        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.highway)
        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.radius)
        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.overall)
        assertTrue(r.violations.isEmpty())
        // 조건이 없어도 야간 시간은 계산해 둔다
        assertEquals(Duration.ofHours(1), r.nightOverlap)
    }

    @Test
    fun nightConditionOff_nightTripIsNotViolation() {
        // PR #6 MAJOR-1: 야간 조건 없는 운전자를 야간 위반으로 기록하던 문제
        val r = useCase.evaluate(DriverCondition(), at("2026-11-09", "21:00"), at("2026-11-09", "22:00"))

        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.night)
        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.overall)
    }

    // ---- 야간 판정 ----

    @Test
    fun daytimeTrip_kept() {
        val r = useCase.evaluate(noNight, at("2026-11-09", "14:00"), at("2026-11-09", "15:00"))

        assertEquals(TripConditionStatus.KEPT, r.night)
        assertEquals(Duration.ZERO, r.nightOverlap)
        assertEquals(TripConditionStatus.KEPT, r.overall)
        assertFalse(r.hasUnjudged)
    }

    @Test
    fun endsJustBeforeSunset_kept() {
        val r = useCase.evaluate(noNight, at("2026-11-09", "16:30"), at("2026-11-09", "17:24"))

        assertEquals(TripConditionStatus.KEPT, r.night)
        assertEquals(Duration.ZERO, r.nightOverlap)
    }

    @Test
    fun crossesSunset_violatedWithOverlap() {
        val r = useCase.evaluate(noNight, at("2026-11-09", "17:00"), at("2026-11-09", "17:40"))

        assertEquals(TripConditionStatus.VIOLATED, r.night)
        assertNear("일몰 17:26 이후 시간", Duration.ofMinutes(14), r.nightOverlap)
        assertEquals(setOf(TripConditionKind.NO_NIGHT_DRIVING), r.violations)
        assertEquals(TripConditionStatus.VIOLATED, r.overall)
    }

    @Test
    fun beforeSunrise_violated() {
        val r = useCase.evaluate(noNight, at("2026-11-09", "06:30"), at("2026-11-09", "07:30"))

        assertEquals(TripConditionStatus.VIOLATED, r.night)
        assertNear("일출 07:05 전 시간", Duration.ofMinutes(35), r.nightOverlap)
    }

    @Test
    fun overnight_countsUntilNextSunrise() {
        val r = useCase.evaluate(noNight, at("2026-11-09", "22:00"), at("2026-11-10", "08:00"))

        assertEquals(TripConditionStatus.VIOLATED, r.night)
        // 22:00 ~ 11/10 일출 약 07:06
        assertNear("자정 넘는 주행", Duration.ofHours(9).plusMinutes(6), r.nightOverlap)
    }

    @Test
    fun spansWholeDay_onlyNightPartsCounted() {
        // 11/09 06:00 ~ 11/10 08:00: 새벽(~07:05) + 저녁~다음 일출(17:26~07:06)
        val r = useCase.evaluate(noNight, at("2026-11-09", "06:00"), at("2026-11-10", "08:00"))

        assertNear("하루 넘는 주행", Duration.ofMinutes(65) + Duration.ofHours(13).plusMinutes(40), r.nightOverlap, Duration.ofMinutes(3))
    }

    @Test
    fun zeroLengthTrip_kept() {
        val t = at("2026-11-09", "21:00")
        val r = useCase.evaluate(noNight, t, t)

        assertEquals(Duration.ZERO, r.nightOverlap)
        assertEquals(TripConditionStatus.KEPT, r.night)
    }

    @Test
    fun toleranceAllowsShortOverlap() {
        val tolerant = TripComplianceUseCase(DepartureConfig(tripNightTolerance = Duration.ofMinutes(15)))

        val r = tolerant.evaluate(noNight, at("2026-11-09", "17:00"), at("2026-11-09", "17:40"))

        assertEquals(TripConditionStatus.KEPT, r.night)
        assertTrue(r.nightOverlap > Duration.ZERO)
    }

    @Test
    fun civilTwilightReference_shiftsNightLater() {
        val civil = TripComplianceUseCase(
            DepartureConfig(nightStartReference = NightStartReference.CIVIL_TWILIGHT_END),
        )

        // 시민 박명 끝은 일몰보다 약 26~31분 늦다 → 17:40 종료는 아직 야간 아님
        val r = civil.evaluate(noNight, at("2026-11-09", "17:00"), at("2026-11-09", "17:40"))

        assertEquals(TripConditionStatus.KEPT, r.night)
    }

    @Test(expected = IllegalArgumentException::class)
    fun endBeforeStart_throws() {
        useCase.evaluate(noNight, at("2026-11-09", "15:00"), at("2026-11-09", "14:00"))
    }

    // ---- 기준 위치 ----

    @Test
    fun sunReference_prefersTripLocation_thenRadiusCenter_thenConfigDefault() {
        val busan = GeoPoint(latitude = 35.1796, longitude = 129.0756)
        val withHome = noNight.copy(radiusCenter = home)
        val s = at("2026-11-09", "14:00")
        val e = at("2026-11-09", "15:00")

        assertEquals(SunReferenceSource.TRIP_LOCATION, useCase.evaluate(withHome, s, e, busan).sunReference.source)
        assertEquals(busan, useCase.evaluate(withHome, s, e, busan).sunReference.point)
        assertEquals(SunReferenceSource.RADIUS_CENTER, useCase.evaluate(withHome, s, e).sunReference.source)
        assertEquals(SunReferenceSource.CONFIG_DEFAULT, useCase.evaluate(noNight, s, e).sunReference.source)
        assertEquals(DepartureConfig().defaultSunReferencePoint, useCase.evaluate(null, s, e).sunReference.point)
    }

    @Test
    fun locationChangesSunset() {
        // 울릉도(경도 130.9)는 서울보다 경도가 약 4° 동쪽 → 일몰이 약 16분 이르다(약 17:10). 17:20 종료 → 울릉도 기준만 위반
        val ulleung = GeoPoint(latitude = 37.4844, longitude = 130.9057)
        val s = at("2026-11-09", "16:30")
        val e = at("2026-11-09", "17:20")

        assertEquals(TripConditionStatus.KEPT, useCase.evaluate(noNight, s, e, home).night)
        assertEquals(TripConditionStatus.VIOLATED, useCase.evaluate(noNight, s, e, ulleung).night)
    }

    @Test
    fun polarDayAndNight_handled() {
        // 스발바르(78.2N): 6월 백야 → 야간 0, 12월 극야 → 주행 전체가 야간 (리뷰 task-29 MINOR-1)
        val svalbard = GeoPoint(latitude = 78.2232, longitude = 15.6267)

        val summer = useCase.evaluate(noNight, at("2026-06-21", "23:00"), at("2026-06-22", "01:00"), svalbard)
        val winter = useCase.evaluate(noNight, at("2026-12-21", "12:00"), at("2026-12-21", "14:00"), svalbard)

        assertEquals(Duration.ZERO, summer.nightOverlap)
        assertEquals(TripConditionStatus.KEPT, summer.night)
        assertEquals(Duration.ofHours(2), winter.nightOverlap)
        assertEquals(TripConditionStatus.VIOLATED, winter.night)
    }

    // ---- 고속도로·반경 ----

    @Test
    fun highwayAndRadius_notJudged_whenConditionsExist() {
        val all = DriverCondition(
            timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
            routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY),
            radiusCenter = home,
        )

        val r = useCase.evaluate(all, at("2026-11-09", "14:00"), at("2026-11-09", "15:00"))

        assertEquals(TripConditionStatus.NOT_JUDGED, r.highway)
        assertEquals(TripConditionStatus.NOT_JUDGED, r.radius)
        assertEquals(TripConditionStatus.KEPT, r.overall)
        assertTrue(r.hasUnjudged)
    }

    /** 반경 기능을 끄면(2026-11-14) 기준점이 있어도 반경은 NOT_APPLICABLE. 기준점은 일몰 기준 위치(집)로 계속 쓴다. */
    @Test
    fun radiusDisabled_radiusNotApplicable_centerStillUsedAsSunReference() {
        val off = TripComplianceUseCase(DepartureConfig(radiusCheckEnabled = false))
        val c = noNight.copy(radiusCenter = home)

        val r = off.evaluate(c, at("2026-11-09", "14:00"), at("2026-11-09", "15:00"))

        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.radius)
        assertEquals(SunReferenceSource.RADIUS_CENTER, r.sunReference.source)
        assertFalse(r.hasUnjudged)
    }

    @Test
    fun onlyUnjudgedConditions_overallNotJudged() {
        val c = DriverCondition(routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY, RouteConstraint.PREFER_TOLL_FREE))

        val r = useCase.evaluate(c, at("2026-11-09", "21:00"), at("2026-11-09", "22:00"))

        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.night)
        assertEquals(TripConditionStatus.NOT_JUDGED, r.overall)
    }

    // ---- DepartureFeature.evaluateTripCompliance ----

    private fun feature(condition: DataResult<DriverCondition>) = DepartureFeature(StubDependencies(condition))

    @Test
    fun feature_noSavedCondition_treatedAsNoCondition() = runBlocking {
        val r = feature(DataResult.Unavailable(UnavailableReason.NO_DATA))
            .evaluateTripCompliance(at("2026-11-09", "21:00"), at("2026-11-09", "22:00"))

        assertEquals(TripConditionStatus.NOT_APPLICABLE, r.getOrNull()?.overall)
    }

    @Test
    fun feature_conditionReadFailure_returnsUnavailable() = runBlocking {
        val r = feature(DataResult.Unavailable(UnavailableReason.TIMEOUT))
            .evaluateTripCompliance(at("2026-11-09", "21:00"), at("2026-11-09", "22:00"))

        assertEquals(DataResult.Unavailable(UnavailableReason.TIMEOUT), r)
    }

    @Test
    fun feature_usesSavedCondition() = runBlocking {
        val r = feature(DataResult.Available(noNight))
            .evaluateTripCompliance(at("2026-11-09", "21:00"), at("2026-11-09", "22:00"))

        assertEquals(TripConditionStatus.VIOLATED, r.getOrNull()?.night)
    }

    /** 운전자 조건만 바꿔 끼우는 최소 스텁. 나머지는 사용 불가. */
    private class StubDependencies(private val condition: DataResult<DriverCondition>) : DepartureDependencies {
        private val na = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        override val vehicleRepository = object : VehicleRepository {
            override fun observeDrivingRestriction(): Flow<DrivingRestriction> = flowOf(DrivingRestriction.UNKNOWN)
        }
        override val naviRepository = object : NaviRepository {
            override suspend fun getCurrentLocation(): DataResult<GeoPoint> = na
            override suspend fun getDestination(): DataResult<NaviDestination> = na
            override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> = na
            override suspend fun getRouteState(): DataResult<RouteState> = na
            override fun observeRemainingTrip(): Flow<RemainingTrip> = emptyFlow()
        }
        override val driverConditionRepository = object : DriverConditionRepository {
            override suspend fun getCondition(): DataResult<DriverCondition> = condition
            override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> = na
            override fun observeCondition(): Flow<DataResult<DriverCondition?>> = flowOf(na)
        }
        override val ttsRepository = object : TtsRepository {
            override suspend fun speak(text: String): SpeakResult = SpeakResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        }
        override val sttRepository = object : SttRepository {
            override suspend fun recognizeOnce(): DataResult<String> = na
        }
        override val clock: Clock = Clock.fixed(Instant.parse("2026-11-09T04:00:00Z"), ZoneOffset.UTC)
        override val config = DepartureConfig()
    }
}
