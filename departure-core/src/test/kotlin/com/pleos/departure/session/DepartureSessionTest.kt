package com.pleos.departure.session

import com.pleos.departure.DepartureConfig
import com.pleos.departure.DepartureDependencies
import com.pleos.departure.DepartureFeature
import com.pleos.departure.model.ConditionSource
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.RouteUserAction
import com.pleos.departure.model.SpeakResult
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import com.pleos.departure.port.VehicleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 작업 #12b(원래 #7 의 :departure-ui DepartureScreenModelTest): core 세션의 시간 흐름(로딩 표시·재확인·주기 갱신),
 * 세션 기억(ETA·조건), 주행 중 조작 제한, 실패 처리를 가상 시간으로 검증한다.
 * 화면 문구 대신 core 상태([DepartureSessionState])의 결과 타입·플래그로 단언한다(문구 단언은 ui 의 DepartureScreenModelTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepartureSessionTest {

    private val config = DepartureConfig()
    private val home = GeoPoint(latitude = 37.5563, longitude = 126.9220)
    private val dest = Place(point = GeoPoint(latitude = 37.5547, longitude = 126.9707), displayName = "딸네 집")
    private val condition = DriverCondition(
        timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
        routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
        radiusCenter = home,
    )

    /** 테스트 시계: 2026-11-09 15:35 KST 고정(가상 시간과 무관). */
    private val clock = Clock.fixed(Instant.parse("2026-11-09T06:35:00Z"), ZoneId.of("Asia/Seoul"))

    /** 일몰 17:26 − ETA 35분(FREE 안내 중 FREE 기준 → 보정 없음) − 여유 30분. */
    private val expectedDeadline = LocalTime.of(16, 21)

    private inner class Stub(
        override val config: DepartureConfig = this@DepartureSessionTest.config,
    ) : DepartureDependencies {
        var conditionResult: DataResult<DriverCondition> = DataResult.Available(condition)
        var destination: DataResult<NaviDestination> = DataResult.Available(NaviDestination.Present(dest))
        var routeState: DataResult<RouteState> = DataResult.Available(RouteState.Guiding(RouteOption.FREE, 0))
        var naviDelayMillis = 0L
        /** non-null 이면 getDestination 이 이 예외를 던진다(1번 어댑터가 DataResult 대신 예외를 던지는 경우 흉내). */
        var destinationThrows: Exception? = null
        /** true 면 getDestination 대기 중 취소될 때 원래 예외 대신 어댑터 자신의 CancellationException 을 던진다(리뷰 task-09 MINOR-2). */
        var adapterOwnCancellation = false
        /** requestTrip 응답의 routeOption. 어댑터 규칙상 안내 중이 아니면 null. */
        var tripOption: RouteOption? = RouteOption.FREE
        var tripCalls = 0
        var destinationCalls = 0
        val driving = MutableStateFlow(DrivingRestriction.UNRESTRICTED)

        override val vehicleRepository = object : VehicleRepository {
            override fun observeDrivingRestriction(): Flow<DrivingRestriction> = driving
        }
        override val naviRepository = object : NaviRepository {
            override suspend fun getCurrentLocation(): DataResult<GeoPoint> = DataResult.Available(home)
            override suspend fun getDestination(): DataResult<NaviDestination> {
                destinationCalls++
                try {
                    delay(naviDelayMillis)
                } catch (e: CancellationException) {
                    if (adapterOwnCancellation) throw CancellationException("adapter: own cancellation") else throw e
                }
                destinationThrows?.let { throw it }
                return destination
            }
            override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> {
                tripCalls++
                return DataResult.Available(TripEstimate(Duration.ofMinutes(35), 9_800, EstimateSource.SDK_MEASURED, clock.instant(), tripOption, destination.point))
            }
            override suspend fun getRouteState(): DataResult<RouteState> = routeState
            override fun observeRemainingTrip(): Flow<RemainingTrip> = emptyFlow()
        }
        override val driverConditionRepository = object : DriverConditionRepository {
            override suspend fun getCondition(): DataResult<DriverCondition> = conditionResult
            override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> = DataResult.Available(condition)
            override fun observeCondition(): Flow<DataResult<DriverCondition?>> = emptyFlow()
        }
        override val ttsRepository = object : TtsRepository {
            override suspend fun speak(text: String): SpeakResult = SpeakResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        }
        override val sttRepository = object : SttRepository {
            override suspend fun recognizeOnce(): DataResult<String> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        }
        override val clock: Clock = this@DepartureSessionTest.clock
    }

    private fun TestScope.start(stub: Stub): DepartureSession =
        DepartureFeature(stub).createSession(backgroundScope).also {
            it.onVisible()
            runCurrent()
        }

    // ------------------------------------------------ 단언 도우미

    private val DepartureSession.outcome get() = state.value.outcome
    private val DepartureSession.checked get() = outcome as CheckOutcome.Checked
    private val DepartureSession.snapshot get() = checked.snapshot as DepartureSnapshot.Checked
    private val DepartureSession.route get() = snapshot.route

    private fun DepartureSession.deadlineTime(): LocalTime =
        (snapshot.deadline as DepartureDeadlineResult.Deadline).deadline.withZoneSameInstant(config.timeZone).toLocalTime().truncatedTo(ChronoUnit.MINUTES) // 화면은 분 단위로 보여 준다

    private fun DepartureSession.assertDeadlineShown() = assertEquals(expectedDeadline, deadlineTime())

    /** 경로 교체 재확인 중인 "안내 없음"(화면: "경로를 확인하고 있어요"). */
    private fun DepartureSession.isRecheckingRoute() = checked.recheck.rechecking && route is RouteConditionCheck.NotGuiding

    /** 확정된 "안내 없음"(화면: "내비 경로 안내가 시작되지 않았어요"). */
    private fun DepartureSession.isNotGuidingConfirmed() = !checked.recheck.rechecking && route is RouteConditionCheck.NotGuiding

    private fun DepartureSession.isTollToggleOffered(): Boolean {
        val r = route
        return r is RouteConditionCheck.NeedsUserAction && RouteUserAction.TURN_ON_TOLL_AVOIDANCE in r.actions
    }

    // ------------------------------------------------ 작업 #7

    @Test
    fun firstCheck_computesDeadline() = runTest {
        val m = start(Stub())
        m.assertDeadlineShown()
        assertEquals(RecheckProgress(rechecking = false, attempt = 0, maxAttempts = config.routeChangeRecheckMaxAttempts), m.checked.recheck)
        assertFalse(m.state.value.loading)
    }

    /** 2초 안에 끝나면 "확인 중" 표시를 띄우지 않고, 넘으면 띄운다(DepartureConfig.loadingIndicatorThreshold). */
    @Test
    fun loadingIndicator_onlyAfterThreshold() = runTest {
        val stub = Stub().apply { naviDelayMillis = 5_000 }
        val m = start(stub)
        assertTrue(m.state.value.loading)
        advanceTimeBy(1_900)
        assertFalse(m.state.value.showLoadingIndicator)
        advanceTimeBy(200)
        assertTrue(m.state.value.showLoadingIndicator)
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(m.state.value.loading)
        assertFalse(m.state.value.showLoadingIndicator)
    }

    /** 주기 갱신은 이전 ETA(sdkEstimate)를 넘겨 requestTrip 을 다시 부르지 않는다. 목적지는 매번 새로 조회한다. */
    @Test
    fun periodicRefresh_reusesEta_andRequeriesDestination() = runTest {
        val stub = Stub()
        val m = start(stub)
        assertEquals(1, stub.tripCalls)
        advanceTimeBy(config.screenRefreshInterval.toMillis() + 1)
        assertEquals(2, stub.destinationCalls)
        assertEquals(1, stub.tripCalls)
        m.onHidden()
        advanceTimeBy(config.screenRefreshInterval.toMillis() * 3)
        assertEquals("가려지면 멈춘다", 2, stub.destinationCalls)
        m.onVisible()
        runCurrent()
        assertEquals("복귀하면 바로 다시 조회", 3, stub.destinationCalls)
    }

    /** 경로 교체 중(NotGuiding)은 5초 간격으로 최대 6회 재확인하고, 그동안 재확인 중으로 알린다. */
    @Test
    fun notGuiding_rechecksThenSettles() = runTest {
        // 안내 중이 아니면 어댑터는 ETA routeOption 을 비운다(integration-guide 매핑표 requestTrip 행)
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.NotGuiding); tripOption = null }
        val m = start(stub)
        assertTrue(m.isRecheckingRoute())
        assertEquals(1, m.checked.recheck.attempt)
        repeat(config.routeChangeRecheckMaxAttempts) { advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) }
        runCurrent()
        assertEquals(1 + config.routeChangeRecheckMaxAttempts, stub.destinationCalls)
        assertTrue(m.isNotGuidingConfirmed())
        // 확정 뒤에는 짧은 간격 재확인을 반복하지 않는다
        advanceTimeBy(config.routeChangeRecheckInterval.toMillis() * 3)
        assertEquals(1 + config.routeChangeRecheckMaxAttempts, stub.destinationCalls)
    }

    @Test
    fun notGuiding_thenGuiding_isSatisfied() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.NotGuiding) }
        val m = start(stub)
        stub.routeState = DataResult.Available(RouteState.Guiding(RouteOption.FREE, 0))
        advanceTimeBy(config.routeChangeRecheckInterval.toMillis() + 1)
        assertTrue(m.route is RouteConditionCheck.Satisfied)
        assertFalse(m.checked.recheck.rechecking)
    }

    /** 직전에 목적지가 있었는데 NotSet 이 오면 바로 "목적지 없음"으로 확정하지 않는다(경로 교체 7~19초). */
    @Test
    fun destinationVanishing_isRecheckedBeforeFinal() = runTest {
        val stub = Stub()
        val m = start(stub)
        stub.destination = DataResult.Available(NaviDestination.NotSet)
        m.refresh()
        runCurrent()
        assertTrue(m.snapshot.deadline is DepartureDeadlineResult.DestinationNotSet)
        assertTrue("재확인 중", m.checked.recheck.rechecking)
        repeat(config.routeChangeRecheckMaxAttempts) { advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) }
        runCurrent()
        assertTrue(m.snapshot.deadline is DepartureDeadlineResult.DestinationNotSet)
        assertFalse("확정", m.checked.recheck.rechecking)
    }

    /** 처음부터 목적지가 없으면 재확인 없이 바로 확정한다. */
    @Test
    fun noDestinationFromStart_isFinalImmediately() = runTest {
        val stub = Stub().apply {
            destination = DataResult.Available(NaviDestination.NotSet)
            routeState = DataResult.Available(RouteState.NotGuiding)
        }
        val m = start(stub)
        assertTrue(m.snapshot.deadline is DepartureDeadlineResult.DestinationNotSet)
        assertFalse(m.checked.recheck.rechecking)
    }

    /** "켰어요" → 다시 조회. 그대로 RECOMMENDED 면 "아직 미적용", FREE 면 충족. */
    @Test
    fun confirmToggle_rechecksRouteState() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        assertTrue(m.isTollToggleOffered())
        assertFalse(m.checked.tollToggleNotYetApplied)
        m.confirmTollAvoidanceTurnedOn()
        runCurrent()
        assertTrue(m.checked.tollToggleNotYetApplied)

        stub.routeState = DataResult.Available(RouteState.Guiding(RouteOption.FREE, 0))
        m.confirmTollAvoidanceTurnedOn()
        runCurrent()
        assertTrue(m.route is RouteConditionCheck.Satisfied)
        assertFalse(m.checked.tollToggleNotYetApplied)
    }

    /** 내비 적용 옵션이 바뀌면 이전 ETA(다른 옵션 기준)를 재사용하지 않는다(§10.6: ETA는 내비의 유료도로 우회 설정을 따름). */
    @Test
    fun routeOptionChange_dropsCachedEta() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        assertEquals(1, stub.tripCalls) // Stub requestTrip 은 routeOption=FREE 로 응답 → 보고 옵션(RECOMMENDED)과 달라 버림
        m.refresh()
        runCurrent()
        assertEquals(2, stub.tripCalls)

        stub.routeState = DataResult.Available(RouteState.Guiding(RouteOption.FREE, 0))
        m.confirmTollAvoidanceTurnedOn()
        runCurrent()
        assertEquals(3, stub.tripCalls)
        m.refresh()
        runCurrent()
        assertEquals("옵션이 같으면 재사용", 3, stub.tripCalls)
    }

    /** D-9: 조회 성공 후 실패하면 마지막 값을 쓰고, 처음부터 실패면 야간 제한 가정. */
    @Test
    fun conditionFailure_usesLastKnownThenAssumed() = runTest {
        val stub = Stub()
        val m = start(stub)
        stub.conditionResult = DataResult.Unavailable(UnavailableReason.TIMEOUT)
        m.refresh()
        runCurrent()
        assertEquals(ConditionSource.LAST_KNOWN, m.snapshot.condition.source)
        val radius = m.snapshot.radius
        assertTrue("마지막 조건의 기준점으로 반경 판정", radius is RadiusCheckResult.Checked && !radius.isDestinationOutside)

        m.resetSession()
        runCurrent()
        assertEquals(ConditionSource.ASSUMED_NIGHT_RESTRICTION, m.snapshot.condition.source)
        assertTrue("기준점 모름 → 판정 불가", m.snapshot.radius is RadiusCheckResult.CenterNotSet)
    }

    /** D-9: 확인된 미설정은 "조건 미설정"만. 이전 조건을 기억해 쓰지 않는다. */
    @Test
    fun conditionNotSet_isReported_andForgetsLastKnown() = runTest {
        val stub = Stub()
        val m = start(stub)
        stub.conditionResult = DataResult.Unavailable(UnavailableReason.NO_DATA)
        m.refresh()
        runCurrent()
        assertTrue(m.checked.snapshot is DepartureSnapshot.ConditionNotSet)
        stub.conditionResult = DataResult.Unavailable(UnavailableReason.SERVICE_ERROR)
        m.refresh()
        runCurrent()
        assertEquals(ConditionSource.ASSUMED_NIGHT_RESTRICTION, m.snapshot.condition.source)
    }

    /** 주행 중이면 조작을 막고, 정차하면 다시 허용한다(조회 없이 다시 알림). */
    @Test
    fun drivingRestricted_disablesControls() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        stub.driving.value = DrivingRestriction.RESTRICTED
        runCurrent()
        assertFalse(m.state.value.controlsEnabled)
        assertEquals(DrivingRestriction.RESTRICTED, m.state.value.drivingRestriction)
        assertTrue("결과는 그대로", m.isTollToggleOffered())
        val calls = stub.destinationCalls
        stub.driving.value = DrivingRestriction.UNRESTRICTED
        runCurrent()
        assertTrue(m.state.value.controlsEnabled)
        assertEquals(DrivingRestriction.UNRESTRICTED, m.state.value.drivingRestriction)
        assertEquals(calls, stub.destinationCalls)
    }

    /**
     * UNKNOWN 은 기본 설정(treatUnknownDrivingAsRestricted=true)이면 제한으로 본다 — 플랫폼도 상태 unknown 을 전부 제한(0x1ff)으로
     * 다룬다(vehicle-state.md §11.2, 작업 #11).
     */
    @Test
    fun drivingUnknown_isRestrictedByDefault() = runTest {
        assertTrue(config.treatUnknownDrivingAsRestricted)
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        assertTrue(m.state.value.controlsEnabled)
        stub.driving.value = DrivingRestriction.UNKNOWN
        runCurrent()
        assertFalse(m.state.value.controlsEnabled)
        assertEquals(DrivingRestriction.UNKNOWN, m.state.value.drivingRestriction)
        assertSame(config, DepartureFeature(stub).config)
    }

    /** UNKNOWN 으로 막힌 조작은 UNRESTRICTED(주차)가 오면 조회 없이 다시 허용된다(리뷰 task-11 MINOR). */
    @Test
    fun drivingUnknown_thenUnrestricted_reenablesControls() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        stub.driving.value = DrivingRestriction.UNKNOWN
        runCurrent()
        assertFalse(m.state.value.controlsEnabled)
        val calls = stub.destinationCalls
        stub.driving.value = DrivingRestriction.UNRESTRICTED
        runCurrent()
        assertTrue(m.state.value.controlsEnabled)
        assertTrue(m.isTollToggleOffered())
        assertEquals(calls, stub.destinationCalls)
    }

    /** 설정을 false 로 주입하면 UNKNOWN 에서도 조작을 허용한다(값은 DepartureConfig 에서만 바꾼다). */
    @Test
    fun drivingUnknown_allowedWhenConfigured() = runTest {
        val stub = Stub(DepartureConfig(treatUnknownDrivingAsRestricted = false))
        val m = start(stub)
        stub.driving.value = DrivingRestriction.UNKNOWN
        runCurrent()
        assertTrue(m.state.value.controlsEnabled)
    }

    // ------------------------------------------------ 작업 #9 (리뷰 task-07 MINOR-2·3)

    /** 확인 중 예외가 나도 죽지 않고 Failed 를 알리며, 주기 확인을 이어 가 복구되면 정상 결과로 돌아온다. */
    @Test
    fun checkException_reportsFailed_andKeepsRefreshing() = runTest {
        val stub = Stub().apply { destinationThrows = IllegalStateException("adapter bug") }
        val m = start(stub)
        assertEquals(CheckOutcome.Failed, m.outcome)
        assertFalse(m.state.value.loading)
        assertFalse(m.state.value.showLoadingIndicator)

        stub.destinationThrows = null
        advanceTimeBy(config.screenRefreshInterval.toMillis() + 1)
        assertEquals("주기 확인이 살아 있다", 2, stub.destinationCalls)
        m.assertDeadlineShown()
    }

    /** 정상 결과 뒤에 예외가 나면 이전(오래된) 결과 대신 Failed 로 바꾼다. "다시 확인"으로 복구된다. */
    @Test
    fun checkException_afterSuccess_replacesStaleResult_andRefreshRecovers() = runTest {
        val stub = Stub()
        val m = start(stub)
        m.assertDeadlineShown()
        stub.destinationThrows = RuntimeException("boom")
        m.refresh()
        runCurrent()
        assertEquals(CheckOutcome.Failed, m.outcome)
        stub.destinationThrows = null
        m.refresh()
        runCurrent()
        m.assertDeadlineShown()
    }

    /** 어댑터 안에서 새어 나온 취소 예외(예: withTimeout)는 우리 코루틴이 살아 있으면 실패로 보고 루프를 유지한다. */
    @Test
    fun leakedCancellationFromAdapter_isTreatedAsFailure() = runTest {
        val stub = Stub().apply { destinationThrows = CancellationException("adapter timeout") }
        val m = start(stub)
        assertEquals(CheckOutcome.Failed, m.outcome)
        stub.destinationThrows = null
        advanceTimeBy(config.screenRefreshInterval.toMillis() + 1)
        assertEquals(2, stub.destinationCalls)
        m.assertDeadlineShown()
    }

    /**
     * 진행 중 확인이 가려짐으로 취소되면 실패로 바꾸지 않는다(취소를 삼키지 않음).
     * 리뷰 task-09 MINOR-2: 정상 결과가 있는 상태에서 시작해, 그 결과가 유지되고 Failed 가 한 번도 나오지 않는지 본다.
     */
    @Test
    fun cancellationByHide_isNotReportedAsFailure() = runTest {
        val stub = Stub()
        val m = start(stub)
        val history = recordOutcomes(m)
        m.assertDeadlineShown()

        stub.naviDelayMillis = 5_000
        m.refresh()
        runCurrent()
        assertTrue(m.state.value.loading)
        advanceTimeBy(1_000)
        m.onHidden()
        advanceTimeBy(config.screenRefreshInterval.toMillis() * 2)
        assertFalse("Failed 로 바뀐 적이 없다", CheckOutcome.Failed in history)
        m.assertDeadlineShown()
        assertFalse(m.state.value.loading)
        assertEquals("가려진 뒤 주기 확인 없음", 2, stub.destinationCalls)
    }

    /**
     * 리뷰 task-09 MINOR-2: 우리 코루틴이 취소된 상태에서 어댑터가 자기 CancellationException 을 던져도(원래 취소 예외가 아님)
     * 실패로 바꾸지 않고 취소를 전파한다(`ensureActive` 분기). 새 확인(다시 확인)이 정상 결과를 알린다.
     */
    @Test
    fun ourCancellation_withAdapterOwnCancellation_isPropagated() = runTest {
        val stub = Stub()
        val m = start(stub)
        val history = recordOutcomes(m)

        stub.naviDelayMillis = 5_000
        stub.adapterOwnCancellation = true
        m.refresh()
        runCurrent()
        advanceTimeBy(1_000)
        stub.naviDelayMillis = 0
        m.refresh() // 진행 중 확인을 취소 -> 어댑터가 자기 CancellationException 을 던짐
        runCurrent()

        assertFalse("Failed 로 바뀐 적이 없다", CheckOutcome.Failed in history)
        m.assertDeadlineShown()
        assertEquals(3, stub.destinationCalls)
        // 루프는 새 확인 쪽으로 이어진다
        advanceTimeBy(config.screenRefreshInterval.toMillis() + 1)
        assertEquals(4, stub.destinationCalls)
    }

    /** 상태가 바뀔 때마다 결과를 빠짐없이 기록한다(Unconfined 수집으로 StateFlow 값 합쳐짐 방지). */
    private fun TestScope.recordOutcomes(m: DepartureSession): List<CheckOutcome?> {
        val outcomes = mutableListOf<CheckOutcome?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            m.state.collect { outcomes += it.outcome }
        }
        return outcomes
    }

    /**
     * FREE 안내 중 받은 ETA 를 보관한 뒤 안내가 끝나(NotGuiding) 재확인 끝에 확정되면 그 ETA 를 버리고 다시 조회한다(리뷰 task-07 MINOR-3).
     * 재확인 중(경로 교체 가능성)에는 버리지 않는다.
     */
    @Test
    fun notGuidingConfirmed_dropsEtaFromGuiding() = runTest {
        val stub = Stub() // FREE 안내 중, requestTrip routeOption=FREE
        val m = start(stub)
        assertEquals(1, stub.tripCalls)

        stub.routeState = DataResult.Available(RouteState.NotGuiding)
        stub.tripOption = null // 안내 중이 아니면 어댑터는 routeOption 을 비운다
        m.refresh()
        runCurrent()
        repeat(config.routeChangeRecheckMaxAttempts - 1) { advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) }
        runCurrent()
        assertEquals("재확인 중에는 보관 ETA 를 재사용", 1, stub.tripCalls)

        advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) // 마지막 재확인 → 확정 → 버림
        runCurrent()
        assertTrue(m.isNotGuidingConfirmed())
        assertEquals(1, stub.tripCalls)
        advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) // 버린 직후 짧은 간격으로 새 ETA 조회
        runCurrent()
        assertEquals(2, stub.tripCalls)

        // 새 ETA 는 routeOption=null 이라 다시 버리지 않는다 → 이후 주기 갱신은 재사용
        advanceTimeBy(config.screenRefreshInterval.toMillis() + 1)
        assertEquals(2, stub.tripCalls)
    }

    /** 어댑터가 안내 중이 아닐 때도 routeOption 을 채워 보내도, 한 번의 확정에 한 번만 버려 5초 재조회가 반복되지 않는다. */
    @Test
    fun notGuidingConfirmed_dropsOnlyOnce_evenIfAdapterKeepsRouteOption() = runTest {
        val stub = Stub()
        val m = start(stub)
        stub.routeState = DataResult.Available(RouteState.NotGuiding) // tripOption 은 FREE 그대로(어댑터 규칙 위반 흉내)
        m.refresh()
        runCurrent()
        repeat(config.routeChangeRecheckMaxAttempts + 1) { advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) }
        runCurrent()
        assertEquals(2, stub.tripCalls)
        val calls = stub.destinationCalls
        advanceTimeBy(config.routeChangeRecheckInterval.toMillis() * 3)
        assertEquals("짧은 간격 재확인이 반복되지 않는다", calls, stub.destinationCalls)
        assertEquals(2, stub.tripCalls)
    }

    // ------------------------------------------------ 작업 #11 (리뷰 task-09 MINOR-1)

    /** "안내 없음" 확정으로 한 번 버린다. 어댑터가 routeOption 을 계속 채우는 경우라 버릴 ETA(FREE)가 다시 보관된다. */
    private fun TestScope.confirmNotGuidingAndDropOnce(stub: Stub, m: DepartureSession) {
        stub.routeState = DataResult.Available(RouteState.NotGuiding) // tripOption = FREE 유지
        m.refresh()
        runCurrent()
        repeat(config.routeChangeRecheckMaxAttempts + 1) { advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) }
        runCurrent()
        assertEquals("확정 -> 버림 -> 새 ETA(FREE) 보관", 2, stub.tripCalls)
    }

    /** 다시 "안내 없음"을 재확인 끝까지 확정시키고 짧은 간격 뒤까지 진행한다. */
    private fun TestScope.confirmNotGuidingAgain(m: DepartureSession) {
        m.refresh()
        runCurrent()
        repeat(config.routeChangeRecheckMaxAttempts + 1) { advanceTimeBy(config.routeChangeRecheckInterval.toMillis()) }
        runCurrent()
    }

    /** 확인 실패가 끼면 "이미 버림" 표시를 지워, 다음 "안내 없음" 확정에서 보관 ETA(routeOption 있음)를 다시 버린다. */
    @Test
    fun checkFailure_resetsEtaDroppedFlag() = runTest {
        val stub = Stub()
        val m = start(stub)
        confirmNotGuidingAndDropOnce(stub, m)

        stub.destinationThrows = RuntimeException("boom")
        m.refresh()
        runCurrent()
        assertEquals(CheckOutcome.Failed, m.outcome)
        stub.destinationThrows = null

        confirmNotGuidingAgain(m)
        assertEquals("실패 뒤 확정에서 다시 버리고 새로 조회", 3, stub.tripCalls)
    }

    /** 조건 미설정(경로 결과 없음)이 끼어도 같은 규칙. */
    @Test
    fun conditionNotSet_resetsEtaDroppedFlag() = runTest {
        val stub = Stub()
        val m = start(stub)
        confirmNotGuidingAndDropOnce(stub, m)

        stub.conditionResult = DataResult.Unavailable(UnavailableReason.NO_DATA)
        m.refresh()
        runCurrent()
        assertTrue(m.checked.snapshot is DepartureSnapshot.ConditionNotSet)
        stub.conditionResult = DataResult.Available(condition)

        confirmNotGuidingAgain(m)
        assertEquals("조건 미설정 뒤 확정에서 다시 버리고 새로 조회", 3, stub.tripCalls)
    }

    // ------------------------------------------------ 작업 #12b (core 이동으로 새로 드러난 공개 상태)

    /** 첫 결과 전 상태는 Initial(결과 없음, 조작 허용 — 기존 화면 동작). resetSession 은 결과를 지우고 바로 다시 확인한다. */
    @Test
    fun initialState_andResetClearsOutcome() = runTest {
        val stub = Stub().apply { naviDelayMillis = 1_000 }
        val session = DepartureFeature(stub).createSession(backgroundScope)
        assertEquals(DepartureSessionState.Initial, session.state.value)
        session.onVisible()
        advanceTimeBy(1_001)
        session.assertDeadlineShown()

        session.resetSession()
        runCurrent()
        assertNull("다시 확인 중에는 결과 없음", session.outcome)
        assertTrue(session.state.value.loading)
        advanceTimeBy(1_001)
        session.assertDeadlineShown()
    }

    /** 가려진 상태에서는 "다시 확인"을 무시한다. */
    @Test
    fun refreshWhileHidden_isIgnored() = runTest {
        val stub = Stub()
        val m = start(stub)
        m.onHidden()
        m.refresh()
        runCurrent()
        assertEquals(1, stub.destinationCalls)
    }
}
