package com.pleos.departure.usecase

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteNote
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.RouteUserAction
import com.pleos.departure.model.UnavailableReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 #5b: 조건 맞춤 경로 "확인" 규칙(api-notes/navi-runtime-check.md §10.4, reviews/task-01d MAJOR-4). */
class ConditionRouteUseCaseTest {

    private val naviPlace = Place(
        point = GeoPoint(latitude = 37.5547, longitude = 126.9707),
        displayName = "서울역",
    )
    private val tollFree = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE)
    private val avoidHighway = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY)
    private val both = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE, RouteConstraint.AVOID_HIGHWAY)

    private fun guiding(option: RouteOption, toll: Int?) = DataResult.Available(RouteState.Guiding(option, toll))

    private fun navi(routeState: DataResult<RouteState>) = ScriptedNavi(
        destination = DataResult.Available(NaviDestination.Present(naviPlace)),
        routeState = routeState,
    )

    private fun check(navi: ScriptedNavi, preference: RoutePreference) =
        runBlocking { ConditionRouteUseCase(navi).check(preference) }

    // --- 무료도로: 주 신호 appliedOption == FREE, 보조 신호 tollFare ---

    @Test
    fun freeAndNoToll_isSatisfied() {
        val r = check(navi(guiding(RouteOption.FREE, 0)), tollFree) as RouteConditionCheck.Satisfied
        assertEquals(naviPlace, r.destination)
        assertTrue(r.notes.isEmpty())
    }

    /** 실측 r1: 사용자가 토글하지 않으면 RECOMMENDED·700원 → "유료도로 우회를 켜세요". */
    @Test
    fun notFree_needsTollAvoidanceToggle() {
        val r = check(navi(guiding(RouteOption.RECOMMENDED, 700)), tollFree) as RouteConditionCheck.NeedsUserAction
        assertEquals(listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), r.actions)
    }

    @Test
    fun recommended_evenWithZeroOrUnknownToll_needsToggle() {
        // 통행료 0인 경로라도 사용자 조건(무료도로 우선)이 내비에 적용된 것은 아니다 → 주 신호만으로 판정
        assertTrue(check(navi(guiding(RouteOption.RECOMMENDED, 0)), tollFree) is RouteConditionCheck.NeedsUserAction)
        assertTrue(check(navi(guiding(RouteOption.RECOMMENDED, null)), tollFree) is RouteConditionCheck.NeedsUserAction)
    }

    /**
     * 리뷰 task-05 재검토 MAJOR-R2: 토글 꺼짐으로 확인된 값은 RECOMMENDED 뿐이다. 그 밖의 FREE 아닌 값은 "켜세요"로 단정하지 않는다
     * (두 토글 조합 값 [미확인] — §10.4 추천 3). 충족도 아니다.
     */
    @Test
    fun otherNonFreeOptions_areOtherRouteOption_notToggleRequest() {
        val others = RouteOption.values().filter { it != RouteOption.FREE && it != RouteOption.RECOMMENDED }
        for (option in others) {
            for (toll in listOf(null, 0, 700)) {
                val r = check(navi(guiding(option, toll)), tollFree)
                assertTrue("$option/$toll → $r", r is RouteConditionCheck.OtherRouteOption)
            }
        }
    }

    @Test
    fun otherRouteOption_keepsHighwayNote() {
        val r = check(navi(guiding(RouteOption.SCHOOL_ZONE, null)), both) as RouteConditionCheck.OtherRouteOption
        assertEquals(listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED), r.notes)
        assertEquals(RouteOption.SCHOOL_ZONE, r.routeState.appliedOption)
    }

    /**
     * 작업 #27: FREE 는 "어린이 보호구역 우회"만 켜도 온다(실측) → 통행료를 모르면 충족이 아니라 '확인 필요'.
     * (이전 MAJOR-4 결정 "모르면 FREE 만으로 충족"을 뒤집는다)
     */
    @Test
    fun freeWithUnknownToll_isUnverified_notSatisfied() {
        val r = check(navi(guiding(RouteOption.FREE, null)), tollFree)
        assertTrue(r is RouteConditionCheck.TollAvoidanceUnverified)
        assertTrue((r as RouteConditionCheck.TollAvoidanceUnverified).notes.isEmpty())
    }

    @Test
    fun freeWithUnknownToll_keepsHighwayNote() {
        val r = check(navi(guiding(RouteOption.FREE, null)), both) as RouteConditionCheck.TollAvoidanceUnverified
        assertEquals(listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED), r.notes)
    }

    /** 작업 #27: FREE + 통행료 있음 = 유료도로 우회가 꺼져 있음(어린이 보호구역 우회만 켠 실측: 700원) → 켜 달라고 안내 */
    @Test
    fun freeButTollReported_asksToTurnOnTollAvoidance() {
        val r = check(navi(guiding(RouteOption.FREE, 700)), tollFree) as RouteConditionCheck.NeedsUserAction
        assertEquals(listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), r.actions)
    }

    @Test
    fun freeWithZeroToll_isSatisfied() {
        val r = check(navi(guiding(RouteOption.FREE, 0)), tollFree) as RouteConditionCheck.Satisfied
        assertTrue(r.notes.isEmpty())
    }

    // --- 고속도로 회피: 보장 불가, 결코 충족 아님 ---

    @Test
    fun avoidHighwayOnly_isReferenceOnly_andDoesNotReadRouteState() {
        val n = navi(guiding(RouteOption.FREE, 0))
        val r = check(n, avoidHighway) as RouteConditionCheck.ReferenceOnly
        assertEquals(listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED), r.notes)
        assertEquals(0, n.routeStateCalls)
    }

    @Test
    fun avoidHighwayIsNeverClaimedSatisfied() {
        val states = listOf(guiding(RouteOption.FREE, 0), guiding(RouteOption.FREE, null), guiding(RouteOption.RECOMMENDED, 700))
        for (state in states) {
            val r = check(navi(state), both)
            val notes = when (r) {
                is RouteConditionCheck.Satisfied -> r.notes
                is RouteConditionCheck.NeedsUserAction -> r.notes
                is RouteConditionCheck.TollAvoidanceUnverified -> r.notes // 작업 #27: FREE + 통행료 모름
                else -> error("unexpected $r")
            }
            assertTrue("$state", RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED in notes)
        }
    }

    @Test
    fun both_satisfiedTollFree_stillCarriesHighwayNote() {
        val r = check(navi(guiding(RouteOption.FREE, 0)), both) as RouteConditionCheck.Satisfied
        assertEquals(listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED), r.notes)
    }

    // --- 확인 불가 / 안내 중 아님 / 목적지 ---

    @Test
    fun routeStateUnavailable_isUnconfirmed_notSatisfied() {
        for (reason in UnavailableReason.values()) {
            val r = check(navi(DataResult.Unavailable(reason)), tollFree)
            assertEquals(RouteConditionCheck.Unconfirmed(naviPlace, reason), r)
        }
    }

    @Test
    fun notGuiding_isNotGuiding() {
        assertEquals(RouteConditionCheck.NotGuiding(naviPlace), check(navi(DataResult.Available(RouteState.NotGuiding)), tollFree))
    }

    @Test
    fun noPreference_doesNotQueryNavi() {
        val n = navi(guiding(RouteOption.RECOMMENDED, 700))
        assertEquals(RouteConditionCheck.NoRouteCondition, check(n, RoutePreference.NONE))
        assertEquals(0, n.destinationCalls)
        assertEquals(0, n.routeStateCalls)
    }

    @Test
    fun destinationNotSetOrUnavailable_doesNotReadRouteState() {
        val notSet = ScriptedNavi(destination = DataResult.Available(NaviDestination.NotSet))
        assertEquals(RouteConditionCheck.DestinationNotSet, check(notSet, tollFree))
        assertEquals(0, notSet.routeStateCalls)

        val unavailable = ScriptedNavi(destination = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED))
        assertEquals(RouteConditionCheck.DestinationUnavailable(UnavailableReason.NOT_SUPPORTED), check(unavailable, tollFree))
    }

    /** 리뷰 task-05 MINOR-2: (0,0) 목적지는 미설정. */
    @Test
    fun nullIslandDestination_isNotSet() {
        val n = ScriptedNavi(
            destination = DataResult.Available(NaviDestination.Present(Place(GeoPoint(latitude = 0.0, longitude = 0.0), ""))),
            routeState = guiding(RouteOption.FREE, 0),
        )
        assertEquals(RouteConditionCheck.DestinationNotSet, check(n, tollFree))
    }

    /** 사람이 내비에서 목적지를 바꿀 수 있으므로(onDestinationChanged 없음, §10.5) 매번 새로 조회한다. 부작용 없음. */
    @Test
    fun destinationIsQueriedFreshEveryTime_andNoTripOrSideEffectCalls() {
        val n = navi(guiding(RouteOption.FREE, 0))
        check(n, tollFree)
        check(n, tollFree)
        assertEquals(2, n.destinationCalls)
        assertEquals(0, n.tripCalls)
    }

    @Test
    fun givenDestination_isNotQueriedAgain() {
        val n = navi(guiding(RouteOption.FREE, 0))
        val r = runBlocking {
            ConditionRouteUseCase(n).check(tollFree, DataResult.Available(NaviDestination.Present(naviPlace)))
        }
        assertTrue(r is RouteConditionCheck.Satisfied)
        assertEquals(0, n.destinationCalls)
        assertFalse(n.routeStateCalls == 0)
    }
}
