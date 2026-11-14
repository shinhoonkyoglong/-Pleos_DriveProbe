package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.ConditionSource
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 작업 #7: 화면 한 번 갱신분 계산. 감독관 결정 D-9(조건 미설정 / 조회 실패)와 목적지·위치 1회 조회를 고정한다. */
class DepartureCheckUseCaseTest {

    private val config = DepartureConfig()

    /** 2026-11-09 15:35 KST(서울 일몰 약 17:26 전). */
    private val clock = Clock.fixed(Instant.parse("2026-11-09T06:35:00Z"), ZoneOffset.UTC)

    private val home = GeoPoint(latitude = 37.5563, longitude = 126.9220)
    private val destination = Place(point = GeoPoint(latitude = 37.5547, longitude = 126.9707), displayName = "딸네 집")

    private val fullCondition = DriverCondition(
        timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
        routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
        radiusCenter = home,
        allowedRadiusMeters = 10_000.0,
    )

    private class ScriptedCondition(var result: DataResult<DriverCondition>) : DriverConditionRepository {
        var getCalls = 0
        override suspend fun getCondition(): DataResult<DriverCondition> {
            getCalls++
            return result
        }

        override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> = DataResult.Available(condition)
        override fun observeCondition(): Flow<DataResult<DriverCondition?>> = emptyFlow()
    }

    private fun navi() = ScriptedNavi(
        destination = DataResult.Available(NaviDestination.Present(destination)),
        location = DataResult.Available(home),
        routeState = DataResult.Available(RouteState.Guiding(appliedOption = RouteOption.FREE, tollFare = 0)),
    )

    private fun useCase(
        navi: ScriptedNavi,
        condition: DriverConditionRepository,
        radiusCheckEnabled: Boolean = true,
    ) = DepartureCheckUseCase(
        naviRepository = navi,
        driverConditionRepository = condition,
        departureDeadline = DepartureDeadlineUseCase(navi, clock, config),
        radiusCheck = RadiusCheckUseCase(navi, config),
        conditionRoute = ConditionRouteUseCase(navi),
        clock = clock,
        radiusCheckEnabled = radiusCheckEnabled,
    )

    /** 반경 기능을 끄면(2026-11-14) 기준점이 있어도 반경을 확인하지 않는다. 출발 마감·경로는 그대로 계산한다. */
    @Test
    fun radiusDisabled_skipsRadius_keepsDeadlineAndRoute() = runBlocking {
        val navi = navi()
        val r = useCase(navi, ScriptedCondition(DataResult.Available(fullCondition)), radiusCheckEnabled = false)
            .check() as DepartureSnapshot.Checked

        assertNull(r.radius)
        assertTrue(r.deadline is DepartureDeadlineResult.Deadline)
        assertTrue(r.route is RouteConditionCheck.Satisfied)
    }

    /** 반경 기능을 끄면 야간 제한이 없을 때 위치를 조회하지 않는다(반경 때문에 조회하던 것). */
    @Test
    fun radiusDisabled_withoutNightRestriction_doesNotQueryLocation() = runBlocking {
        val navi = navi()
        val dayOnly = fullCondition.copy(timeConstraints = emptySet())
        val r = useCase(navi, ScriptedCondition(DataResult.Available(dayOnly)), radiusCheckEnabled = false)
            .check() as DepartureSnapshot.Checked

        assertNull(r.radius)
        assertEquals(0, navi.locationCalls)
    }

    @Test
    fun loadedCondition_queriesDestinationAndLocationOnce_andSharesThem() = runBlocking {
        val navi = navi()
        val r = useCase(navi, ScriptedCondition(DataResult.Available(fullCondition))).check() as DepartureSnapshot.Checked

        assertEquals(ConditionSource.LOADED, r.condition.source)
        assertNull(r.condition.lookupFailure)
        assertEquals(1, navi.destinationCalls)
        assertEquals(1, navi.locationCalls)
        assertEquals(destination, (r.deadline as DepartureDeadlineResult.Deadline).destination)
        assertEquals(destination, (r.radius as RadiusCheckResult.Checked).destination)
        assertTrue(r.route is RouteConditionCheck.Satisfied)
        assertEquals(r.deadline.evaluatedAt, r.evaluatedAt)
    }

    /** D-9: 확인된 미설정(NO_DATA)은 "제한 없음"이 아니다. 아무것도 계산하지 않고 내비도 조회하지 않는다. */
    @Test
    fun conditionNotSet_returnsNotSet_withoutNaviCalls() = runBlocking {
        val navi = navi()
        val r = useCase(navi, ScriptedCondition(DataResult.Unavailable(UnavailableReason.NO_DATA))).check()

        assertEquals(DepartureSnapshot.ConditionNotSet(clock.instant()), r)
        assertEquals(0, navi.destinationCalls)
        assertEquals(0, navi.locationCalls)
        assertEquals(0, navi.tripCalls)
    }

    /** D-9: 조회 실패 + 마지막 값 없음 → 야간 제한만 있다고 보고 마감을 계산한다. 반경은 "확인 불가", 경로 조건은 비어 있다. */
    @Test
    fun lookupFailure_withoutLastKnown_assumesNightRestriction() = runBlocking {
        for (reason in UnavailableReason.values().filter { it != UnavailableReason.NO_DATA }) {
            val r = useCase(navi(), ScriptedCondition(DataResult.Unavailable(reason))).check() as DepartureSnapshot.Checked

            assertEquals("$reason", ConditionSource.ASSUMED_NIGHT_RESTRICTION, r.condition.source)
            assertEquals(reason, r.condition.lookupFailure)
            assertEquals(DepartureCheckUseCase.ASSUMED_WHEN_UNAVAILABLE, r.condition.condition)
            assertTrue("$reason", r.condition.condition.noNightDriving)
            assertTrue("$reason deadline", r.deadline is DepartureDeadlineResult.Deadline)
            assertTrue("$reason radius", r.radius is RadiusCheckResult.CenterNotSet)
            assertEquals(RouteConditionCheck.NoRouteCondition, r.route)
        }
    }

    /** D-9: 조회 실패 + 이 세션의 마지막 값 있음 → 그 값을 쓰고 실패 사유를 함께 돌려준다. */
    @Test
    fun lookupFailure_withLastKnown_usesLastKnown() = runBlocking {
        val r = useCase(navi(), ScriptedCondition(DataResult.Unavailable(UnavailableReason.TIMEOUT)))
            .check(lastKnownCondition = fullCondition) as DepartureSnapshot.Checked

        assertEquals(ConditionSource.LAST_KNOWN, r.condition.source)
        assertEquals(UnavailableReason.TIMEOUT, r.condition.lookupFailure)
        assertEquals(fullCondition, r.condition.condition)
        assertTrue(r.radius is RadiusCheckResult.Checked)
    }

    /** 조회 성공이면 마지막 값을 넘겨도 쓰지 않는다. */
    @Test
    fun loadedCondition_ignoresLastKnown() = runBlocking {
        val loaded = fullCondition.copy(allowedRadiusMeters = 5_000.0)
        val r = useCase(navi(), ScriptedCondition(DataResult.Available(loaded)))
            .check(lastKnownCondition = fullCondition) as DepartureSnapshot.Checked
        assertEquals(loaded, r.condition.condition)
    }

    /** 야간 제한도 반경 기준점도 없으면 위치가 필요 없다 → 조회하지 않는다. */
    @Test
    fun noNightNoCenter_doesNotQueryLocation() = runBlocking {
        val navi = navi()
        val condition = DriverCondition(routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE))
        val r = useCase(navi, ScriptedCondition(DataResult.Available(condition))).check() as DepartureSnapshot.Checked

        assertEquals(0, navi.locationCalls)
        assertTrue(r.deadline is DepartureDeadlineResult.NoNightRestriction)
        assertTrue(r.radius is RadiusCheckResult.CenterNotSet)
    }

    /** (0,0) 목적지는 core 에서도 NotSet 으로 바뀌어 스냅샷에 담긴다(화면의 직전 목적지 비교에 쓰임). */
    @Test
    fun nullIslandDestination_isStoredAsNotSet() = runBlocking {
        val navi = navi().apply {
            destination = DataResult.Available(NaviDestination.Present(Place(point = GeoPoint(0.0, 0.0), displayName = "")))
        }
        val r = useCase(navi, ScriptedCondition(DataResult.Available(fullCondition))).check() as DepartureSnapshot.Checked
        assertEquals(DataResult.Available(NaviDestination.NotSet), r.destination)
        assertTrue(r.deadline is DepartureDeadlineResult.DestinationNotSet)
        assertEquals(RadiusCheckResult.DestinationNotSet, r.radius)
        assertEquals(RouteConditionCheck.DestinationNotSet, r.route)
    }
}
