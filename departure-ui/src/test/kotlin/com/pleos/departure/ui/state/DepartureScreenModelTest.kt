package com.pleos.departure.ui.state

import com.pleos.departure.DepartureConfig
import com.pleos.departure.DepartureDependencies
import com.pleos.departure.DepartureFeature
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.SpeakResult
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import com.pleos.departure.port.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 작업 #12b: 얇은 표시 계층 검증. 세션 동작(재확인·주기 갱신·실패 처리·세션 기억)은 core 의 DepartureSessionTest 가 맡고,
 * 여기서는 세션 상태가 한국어 화면 상태로 제때·올바르게 바뀌는지만 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepartureScreenModelTest {

    private val config = DepartureConfig()
    private val home = GeoPoint(latitude = 37.5563, longitude = 126.9220)
    private val dest = Place(point = GeoPoint(latitude = 37.5547, longitude = 126.9707), displayName = "딸네 집")
    private val condition = DriverCondition(
        timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
        routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
        radiusCenter = home,
    )

    /** 테스트 시계: 2026-11-09 15:35 KST 고정. */
    private val clock = Clock.fixed(Instant.parse("2026-11-09T06:35:00Z"), ZoneId.of("Asia/Seoul"))

    private inner class Stub : DepartureDependencies {
        override val config: DepartureConfig = this@DepartureScreenModelTest.config
        var conditionResult: DataResult<DriverCondition> = DataResult.Available(condition)
        var routeState: DataResult<RouteState> = DataResult.Available(RouteState.Guiding(RouteOption.FREE, 0))
        var naviDelayMillis = 0L
        var destinationThrows: Exception? = null
        val driving = MutableStateFlow(DrivingRestriction.UNRESTRICTED)
        var openSettingsResult: DataResult<Unit> = DataResult.Available(Unit)
        var openSettingsCalls = 0

        override val vehicleRepository = object : VehicleRepository {
            override fun observeDrivingRestriction(): Flow<DrivingRestriction> = driving
        }
        override val naviRepository = object : NaviRepository {
            override suspend fun getCurrentLocation(): DataResult<GeoPoint> = DataResult.Available(home)
            override suspend fun getDestination(): DataResult<NaviDestination> {
                delay(naviDelayMillis)
                destinationThrows?.let { throw it }
                return DataResult.Available(NaviDestination.Present(dest))
            }
            override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> =
                DataResult.Available(TripEstimate(Duration.ofMinutes(35), 9_800, EstimateSource.SDK_MEASURED, clock.instant(), RouteOption.FREE, destination.point))
            override suspend fun getRouteState(): DataResult<RouteState> = routeState
            override fun observeRemainingTrip(): Flow<RemainingTrip> = emptyFlow()
            override suspend fun openNaviSettings(): DataResult<Unit> {
                openSettingsCalls++
                return openSettingsResult
            }
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
        override val clock: Clock = this@DepartureScreenModelTest.clock
    }

    private fun TestScope.start(stub: Stub): DepartureScreenModel =
        DepartureScreenModel(DepartureFeature(stub), backgroundScope).also {
            it.onVisible()
            runCurrent()
        }

    private val DepartureScreenModel.content get() = state.value.content!!

    /** 작업 #28: [내비 설정 열기] → 포트 호출, 실패하면 메뉴 위치 안내용 플래그 */
    @Test
    fun openNaviSettings_callsPort_andFlagsFailure() = runTest {
        val stub = Stub()
        val m = start(stub)
        m.openNaviSettings()
        runCurrent()
        assertEquals(1, stub.openSettingsCalls)
        assertFalse(m.naviSettingsFailed.value)

        stub.openSettingsResult = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        m.openNaviSettings()
        runCurrent()
        assertTrue(m.naviSettingsFailed.value)
    }

    /** 첫 결과 전 화면 상태는 기존과 같은 Initial(빈 화면, 버튼 보임, 주행 안내 없음). */
    @Test
    fun initialState_isUnchanged() = runTest {
        val m = DepartureScreenModel(DepartureFeature(Stub()), backgroundScope)
        assertEquals(DepartureUiState.Initial, m.state.value)
    }

    @Test
    fun firstCheck_mapsDeadline() = runTest {
        val m = start(Stub())
        // 일몰 17:26 − ETA 35분(FREE 안내 중 FREE 기준 → 보정 없음) − 여유 30분 = 16:21
        assertEquals("오후 4시 21분까지 출발", m.content.main.headline)
        assertEquals("무료도로 경로로 안내 중이에요", m.content.route!!.card.title)
        assertFalse(m.state.value.loading)
    }

    /** 세션의 로딩·로딩 표시 플래그가 화면 상태에 바로 반영된다. */
    @Test
    fun loadingFlags_followSession() = runTest {
        val m = start(Stub().apply { naviDelayMillis = 5_000 })
        assertTrue(m.state.value.loading)
        assertNull(m.state.value.content)
        advanceTimeBy(2_100)
        assertTrue(m.state.value.showLoadingIndicator)
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(m.state.value.loading)
        assertFalse(m.state.value.showLoadingIndicator)
        assertEquals("오후 4시 21분까지 출발", m.content.main.headline)
    }

    /** 재확인 중에는 확정 문구 대신 "확인하고 있어요"와 횟수를 보여 준다. */
    @Test
    fun recheckProgress_isShown() = runTest {
        val m = start(Stub().apply { routeState = DataResult.Available(RouteState.NotGuiding) })
        val route = m.content.route!!
        assertEquals(DepartureDisplayMapper.RECHECKING_ROUTE_TITLE, route.card.title)
        assertTrue(route.card.lines.any { it.text.contains("(1/${config.routeChangeRecheckMaxAttempts})") })
    }

    /** "켰어요" 뒤 아직 미적용이면 그 안내 줄을 보여 준다. */
    @Test
    fun tollToggleNotYetApplied_isShown() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        assertTrue(m.content.route!!.showConfirmToggle)
        m.confirmTollAvoidanceTurnedOn()
        runCurrent()
        assertTrue(m.content.route!!.card.lines.any { it.text.startsWith("아직 이번 경로에 '유료도로 우회'가 적용되지 않았어요") })
    }

    /** 주행 중이면 안내 문구를 띄우고 조작 안내를 비운다. 정차하면 되돌아온다. */
    @Test
    fun drivingRestricted_hidesControls() = runTest {
        val stub = Stub().apply { routeState = DataResult.Available(RouteState.Guiding(RouteOption.RECOMMENDED, 700)) }
        val m = start(stub)
        stub.driving.value = DrivingRestriction.RESTRICTED
        runCurrent()
        assertFalse(m.state.value.controlsEnabled)
        assertEquals(DepartureScreenModel.DRIVING_NOTICE, m.state.value.drivingNotice)
        assertTrue(m.content.route!!.steps.isEmpty())
        assertFalse(m.content.route!!.showConfirmToggle)
        stub.driving.value = DrivingRestriction.UNRESTRICTED
        runCurrent()
        assertNull(m.state.value.drivingNotice)
        assertTrue(m.content.route!!.showConfirmToggle)
        assertTrue(m.content.route!!.steps.isNotEmpty())
    }

    /** 세션 실패(Failed)는 "출발 전 확인 불가" 한 장만, 안전해 보이지 않게(UNKNOWN). */
    @Test
    fun checkFailed_isShownAsUnknown() = runTest {
        val stub = Stub().apply { destinationThrows = IllegalStateException("adapter bug") }
        val m = start(stub)
        assertEquals(DepartureDisplayMapper.CHECK_FAILED_TITLE, m.content.main.title)
        assertEquals(Tone.UNKNOWN, m.content.main.tone)
        assertNull(m.content.radius)
        assertNull(m.content.route)
        assertTrue(m.content.main.lines.any { it.text == "'다시 확인'을 눌러도 돼요" })
    }

    /** D-9 문구: 마지막 값 사용 → "새로 확인하지 못했어요", 마지막 값도 없으면 야간 제한 기준. */
    @Test
    fun conditionFailure_titles() = runTest {
        val stub = Stub()
        val m = start(stub)
        stub.conditionResult = DataResult.Unavailable(UnavailableReason.TIMEOUT)
        m.refresh()
        runCurrent()
        assertEquals("운전자 조건을 새로 확인하지 못했어요", m.content.conditionNotice!!.title)
        assertEquals(Tone.OK, m.content.radius!!.tone)

        m.resetSession()
        runCurrent()
        assertEquals(DepartureDisplayMapper.CONDITION_UNAVAILABLE_TITLE, m.content.conditionNotice!!.title)
        assertEquals(Tone.UNKNOWN, m.content.radius!!.tone)
    }
}
