package com.pleos.departure.ui.state

import com.pleos.departure.model.AppliedEta
import com.pleos.departure.model.ConditionSource
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DeadlineStage
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.OriginSource
import com.pleos.departure.model.OriginSunset
import com.pleos.departure.model.Place
import com.pleos.departure.model.PointRadiusCheck
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.model.ResolvedCondition
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteNote
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.RouteUserAction
import com.pleos.departure.model.SunsetBasis
import com.pleos.departure.model.SunsetPlace
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 작업 #7: core 결과 → 화면 표시(문구·색·아이콘) 변환을 결과 종류마다 고정한다. */
class DepartureDisplayMapperTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val mapper = DepartureDisplayMapper(zone)
    private fun kst(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, 9, h, m, s, 0, zone)

    private val now = kst(15, 35).toInstant()
    private val home = GeoPoint(latitude = 37.5563, longitude = 126.9220)
    private val dest = Place(point = GeoPoint(latitude = 37.5547, longitude = 126.9707), displayName = "딸네 집")
    private val sunset = SunsetBasis(kst(17, 26, 21), SunsetPlace.DESTINATION, 90.833)
    private val loaded = ResolvedCondition(
        DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING), radiusCenter = home),
        ConditionSource.LOADED,
        null,
    )
    private val guidingFree = RouteState.Guiding(RouteOption.FREE, tollFare = 0)
    private val satisfied = RouteConditionCheck.Satisfied(dest, guidingFree, emptyList())
    private val radiusIn = RadiusCheckResult.Checked(dest, home, 10_000.0, PointRadiusCheck.Measured(4_300.0, true), PointRadiusCheck.Measured(0.0, true))

    private fun deadline(stage: DeadlineStage, remaining: Duration, source: EstimateSource = EstimateSource.ESTIMATED, at: Instant = now) =
        DepartureDeadlineResult.Deadline(
            evaluatedAt = at,
            stage = stage,
            destination = dest,
            deadline = kst(16, 14, 21),
            remaining = remaining,
            sunset = sunset,
            eta = AppliedEta(Duration.ofMinutes(42), source, null, if (source == EstimateSource.DEFAULT) UnavailableReason.TIMEOUT else null),
            margin = Duration.ofMinutes(30),
        )

    private fun snapshot(
        deadline: DepartureDeadlineResult = deadline(DeadlineStage.CAUTION, Duration.ofMinutes(39)),
        radius: RadiusCheckResult? = radiusIn,
        route: RouteConditionCheck = satisfied,
        condition: ResolvedCondition = loaded,
    ) = DepartureSnapshot.Checked(now, condition, DataResult.Available(NaviDestination.Present(dest)), deadline, radius, route)

    private fun map(s: DepartureSnapshot, ctx: DisplayContext = DisplayContext()) = mapper.map(s, ctx)
    private fun DepartureContent.allText(): String =
        listOfNotNull(conditionNotice, main, radius, route?.card).joinToString("\n") { c -> (listOfNotNull(c.title, c.headline) + c.lines.map { it.text }).joinToString("\n") } +
            route?.steps.orEmpty().joinToString("\n")

    // ------------------------------------------------ 반경 기능 꺼짐(2026-11-14)

    @Test
    fun radiusDisabled_noRadiusCard_mainAndRouteUnchanged() {
        val content = map(snapshot(radius = null))
        assertNull(content.radius)
        assertEquals(map(snapshot()).main, content.main)
        assertEquals(map(snapshot()).route, content.route)
        assertFalse(content.allText(), content.allText().contains("반경"))
    }

    @Test
    fun radiusDisabled_discouragedDeparture_stillNoRadiusCard() {
        val content = map(snapshot(deadline = deadline(DeadlineStage.PASSED, Duration.ofMinutes(-5)), radius = null))
        assertNull(content.radius)
    }

    // ------------------------------------------------ 출발 마감

    @Test
    fun caution_showsDeadlineRemainingAndEstimatedEtaEmphasis() {
        val c = map(snapshot()).main
        assertEquals(Tone.CAUTION, c.tone)
        assertEquals(StatusIcon.WARNING, c.icon)
        assertEquals("오후 4시 14분까지 출발", c.headline)
        assertTrue(c.lines.any { it.text == "남은 시간 39분" && it.emphasis })
        assertTrue(c.lines.any { it.text.startsWith("일몰 오후 5시 26분 (목적지 기준)") })
        assertTrue(c.lines.any { it.text.contains("예상치") && it.emphasis })
        assertTrue(c.lines.any { it.text == "준비 여유 30분 포함" })
    }

    @Test
    fun stagesDifferInIconAndTitle_notOnlyColor() {
        val cards = listOf(
            DeadlineStage.RELAXED to Duration.ofMinutes(90),
            DeadlineStage.CAUTION to Duration.ofMinutes(39),
            DeadlineStage.URGENT to Duration.ofMinutes(9),
            DeadlineStage.PASSED to Duration.ofMinutes(-34),
        ).map { (stage, rem) -> map(snapshot(deadline = deadline(stage, rem))).main }
        assertEquals(cards.size, cards.map { it.title }.toSet().size)
        assertEquals(listOf(Tone.OK, Tone.CAUTION, Tone.DANGER, Tone.DANGER), cards.map { it.tone })
        // 같은 색(URGENT·PASSED)이라도 아이콘이 다르다
        assertTrue(cards[2].icon != cards[3].icon)
    }

    @Test
    fun urgentUnderOneMinute_saysLessThanOneMinute() {
        val c = map(snapshot(deadline = deadline(DeadlineStage.URGENT, Duration.ofSeconds(40)))).main
        assertTrue(c.lines.any { it.text == "남은 시간 1분 미만" })
    }

    @Test
    fun passed_hasNoNegativeTimeNoDepartNow_andHidesRouteGuide() {
        val content = map(
            snapshot(
                deadline = deadline(DeadlineStage.PASSED, Duration.ofMinutes(-34)),
                route = RouteConditionCheck.NeedsUserAction(dest, RouteState.Guiding(RouteOption.RECOMMENDED, 700), listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), emptyList()),
            ),
        )
        assertEquals(DepartureDisplayMapper.STOP_TITLE, content.main.title)
        assertEquals(StatusIcon.STOP, content.main.icon)
        assertNull(content.main.headline)
        assertNull("마감 지남에는 경로 조작 안내를 띄우지 않는다", content.route)
        val text = content.allText()
        assertFalse(text.contains("-"))
        assertFalse(text.contains("지금 출발"))
        assertFalse(text.contains("남은 시간"))
        assertTrue(text.contains("출발 마감(오후 4시 14분)이 지났어요"))
    }

    @Test
    fun passedAfterSunset_saysSunHasSet() {
        val c = map(snapshot(deadline = deadline(DeadlineStage.PASSED, Duration.ofMinutes(-100), at = kst(17, 40).toInstant()))).main
        assertTrue(c.lines.first().text.startsWith("해가 졌어요"))
    }

    @Test
    fun etaSources_areLabeled() {
        fun etaLine(src: EstimateSource) = map(snapshot(deadline = deadline(DeadlineStage.RELAXED, Duration.ofMinutes(90), src))).main.lines[3]
        assertEquals(DisplayLine("예상 이동 시간 42분 (내비 기준)"), etaLine(EstimateSource.SDK_MEASURED))
        assertTrue(etaLine(EstimateSource.ESTIMATED).emphasis)
        val d = etaLine(EstimateSource.DEFAULT)
        assertTrue(d.emphasis)
        assertTrue(d.text.contains("예상치") && d.text.contains("응답이 없어요"))
    }

    @Test
    fun otherDeadlineResults() {
        val noNight = map(snapshot(deadline = DepartureDeadlineResult.NoNightRestriction(now))).main
        assertEquals(Tone.OK, noNight.tone)
        assertEquals("야간 운전 제한 없음", noNight.title)

        val beforeSunrise = map(snapshot(deadline = DepartureDeadlineResult.BeforeSunrise(now, kst(7, 5)))).main
        assertEquals(StatusIcon.NIGHT, beforeSunrise.icon)
        assertEquals("오늘은 출발을 자제하세요", beforeSunrise.headline)
        assertTrue(beforeSunrise.lines.any { it.text.contains("오전 7시 5분") })

        val midnightSun = map(snapshot(deadline = DepartureDeadlineResult.NoSunsetToday(now, LocalDate.of(2026, 6, 21)))).main
        assertEquals("오늘은 해가 지지 않아요", midnightSun.title)

        val polar = map(snapshot(deadline = DepartureDeadlineResult.AllDayNight(now, LocalDate.of(2026, 12, 21), SunsetPlace.ORIGIN)))
        assertEquals(Tone.DANGER, polar.main.tone)
        assertNull("야간이면 경로 조작 안내 숨김", polar.route)
    }

    @Test
    fun destinationNotSet_showsOriginSunsetOnly_andRecheckingIsNotFinal() {
        val origin = OriginSunset(home, OriginSource.CURRENT_LOCATION, sunset.copy(place = SunsetPlace.ORIGIN))
        val s = snapshot(
            deadline = DepartureDeadlineResult.DestinationNotSet(now, origin),
            radius = RadiusCheckResult.DestinationNotSet,
            route = RouteConditionCheck.DestinationNotSet,
        )
        val final = map(s)
        assertEquals("내비에 목적지를 설정하면 출발 마감을 알려 드려요", final.main.title)
        assertEquals("오늘 일몰 오후 5시 26분", final.main.headline)
        assertEquals("현재 위치 기준", final.main.lines.single().text)
        assertEquals(Tone.INFO, final.radius!!.tone)

        val rechecking = map(s, DisplayContext(rechecking = true, recheckAttempt = 1, recheckMaxAttempts = 6))
        assertEquals(DepartureDisplayMapper.RECHECKING_DESTINATION_TITLE, rechecking.main.title)
        assertEquals(DepartureDisplayMapper.RECHECKING_DESTINATION_TITLE, rechecking.radius!!.title)
        assertEquals(DepartureDisplayMapper.RECHECKING_DESTINATION_TITLE, rechecking.route!!.card.title)
        assertFalse(rechecking.allText().contains("목적지가 없어"))
    }

    @Test
    fun destinationFlags_followNaviDestinationAndRechecking() {
        // 작업 #26: 홈 카드가 목적지 없음·재확인 중을 구분하는 값
        val noDest = snapshot(
            deadline = DepartureDeadlineResult.DestinationNotSet(now, null),
            radius = RadiusCheckResult.DestinationNotSet,
            route = RouteConditionCheck.DestinationNotSet,
        ).copy(destination = DataResult.Available(NaviDestination.NotSet))
        assertFalse(map(noDest).destinationSet)
        assertTrue(map(noDest, DisplayContext(rechecking = true)).rechecking)
        assertTrue(map(snapshot()).destinationSet)
        // 목적지 확인 불가는 '목적지 없음'이 아니다(확인 불가로 표시)
        assertTrue(map(snapshot().copy(destination = DataResult.Unavailable(UnavailableReason.TIMEOUT))).destinationSet)
    }

    @Test
    fun originSunsetUnknownOrHome() {
        val unknown = map(snapshot(deadline = DepartureDeadlineResult.DestinationNotSet(now, null))).main
        assertNull(unknown.headline)
        assertTrue(unknown.lines.single().text.contains("확인할 수 없어요"))
        val polar = map(snapshot(deadline = DepartureDeadlineResult.DestinationNotSet(now, OriginSunset(home, OriginSource.HOME, null)))).main
        assertTrue(polar.lines.single().text.contains("계산할 수 없어요"))
        val homeBased = map(snapshot(deadline = DepartureDeadlineResult.DestinationNotSet(now, OriginSunset(home, OriginSource.HOME, sunset)))).main
        assertTrue(homeBased.lines.single().text.startsWith("집 기준"))
    }

    @Test
    fun destinationUnavailable_isUnknownNotSafe_andDiffersFromNotSet() {
        val c = map(snapshot(deadline = DepartureDeadlineResult.DestinationUnavailable(now, UnavailableReason.NOT_SUPPORTED, null))).main
        assertEquals(Tone.UNKNOWN, c.tone)
        assertEquals("목적지를 확인할 수 없어요", c.title)
        assertTrue(c.lines.any { it.text.contains("이 차량에서는 확인할 수 없어요") })
    }

    // ------------------------------------------------ 반경

    @Test
    fun radius_withinAndOutside() {
        val inside = map(snapshot()).radius!!
        assertEquals(Tone.OK, inside.tone)
        assertEquals("집에서 약 4.3km · 허용 10km", inside.headline)
        assertTrue(inside.lines.any { it.text.contains("직선거리 기준") })
        assertTrue(inside.lines.any { it.text.startsWith("참고: 현재 위치") })

        val outside = map(
            snapshot(radius = RadiusCheckResult.Checked(dest, home, 10_000.0, PointRadiusCheck.Measured(33_000.0, false), PointRadiusCheck.Unavailable(UnavailableReason.NO_DATA))),
        ).radius!!
        assertEquals(Tone.DANGER, outside.tone)
        assertEquals(StatusIcon.WARNING, outside.icon)
        assertEquals("목적지가 허용 반경 밖이에요", outside.title)
        assertTrue(outside.lines.any { it.text == "참고: 현재 위치는 확인하지 못했어요" })
    }

    /** 출발 자제(PASSED·NIGHT_NOW)에서 반경 카드는 참고용으로 낮춘다. 반경 안(OK)은 INFO 톤, 아이콘·문구는 그대로(리뷰 task-07 MINOR-5). */
    @Test
    fun discouragedStages_deemphasizeRadiusCard() {
        val normal = map(snapshot()).radius!!
        assertFalse(normal.secondary)
        assertEquals(Tone.OK, normal.tone)

        val passed = map(snapshot(deadline = deadline(DeadlineStage.PASSED, Duration.ofMinutes(-34)))).radius!!
        assertTrue(passed.secondary)
        assertEquals(Tone.INFO, passed.tone)
        assertEquals(StatusIcon.CHECK, passed.icon)
        assertEquals("목적지가 허용 반경 안이에요", passed.title)
        // 화면이 "참고" 글자를 붙이므로 본문에는 "참고:" 머리말이 없다(리뷰 task-09 MINOR-3)
        assertTrue(passed.lines.any { it.text.startsWith("현재 위치는 집에서") })
        assertFalse(passed.lines.any { it.text.startsWith("참고") })

        val night = map(snapshot(deadline = DepartureDeadlineResult.BeforeSunrise(now, kst(7, 5)))).radius!!
        assertTrue(night.secondary)
        assertEquals(Tone.INFO, night.tone)

        // 반경 밖은 출발 자제와 같은 방향의 경고라 톤을 낮추지 않는다(크기만 참고용)
        val outside = map(
            snapshot(
                deadline = deadline(DeadlineStage.PASSED, Duration.ofMinutes(-34)),
                radius = RadiusCheckResult.Checked(dest, home, 10_000.0, PointRadiusCheck.Measured(33_000.0, false), PointRadiusCheck.Measured(0.0, true)),
            ),
        ).radius!!
        assertTrue(outside.secondary)
        assertEquals(Tone.DANGER, outside.tone)
    }

    /** 확인 자체가 실패(예외)하면 안전해 보이지 않는 "확인 불가"만 보여 준다(리뷰 task-07 MINOR-2). */
    @Test
    fun checkFailed_isUnknownOnly() {
        val c = mapper.checkFailed(DisplayContext())
        assertEquals(Tone.UNKNOWN, c.main.tone)
        assertEquals(StatusIcon.QUESTION, c.main.icon)
        assertEquals(DepartureDisplayMapper.CHECK_FAILED_TITLE, c.main.title)
        assertNull(c.radius)
        assertNull(c.route)
        assertNull(c.conditionNotice)
        assertTrue(c.main.lines.any { it.text.contains("눌러") })
        val driving = mapper.checkFailed(DisplayContext(controlsEnabled = false))
        assertFalse("주행 중에는 버튼 안내를 하지 않는다", driving.main.lines.any { it.text.contains("눌러") })
    }

    /** 확인 불가는 절대 안전(OK)으로 보이지 않는다(리뷰 task-05 #7-2). */
    @Test
    fun radius_unknownCasesNeverLookSafe() {
        val unknowns = listOf(
            RadiusCheckResult.DestinationUnavailable(UnavailableReason.TIMEOUT),
            RadiusCheckResult.CenterNotSet(dest),
        ).map { map(snapshot(radius = it)).radius!! }
        unknowns.forEach {
            assertEquals(Tone.UNKNOWN, it.tone)
            assertEquals(StatusIcon.QUESTION, it.icon)
            assertEquals(DepartureDisplayMapper.RADIUS_UNKNOWN_TITLE, it.title)
        }
    }

    // ------------------------------------------------ 경로 조건 (9종)

    @Test
    fun route_allNineResults() {
        fun card(r: RouteConditionCheck, ctx: DisplayContext = DisplayContext()) = map(snapshot(route = r), ctx).route!!
        val recommended = RouteState.Guiding(RouteOption.RECOMMENDED, 700)

        assertEquals("경로 조건 없음", card(RouteConditionCheck.NoRouteCondition).card.title)
        assertEquals(Tone.INFO, card(RouteConditionCheck.DestinationNotSet).card.tone)
        assertEquals(Tone.UNKNOWN, card(RouteConditionCheck.DestinationUnavailable(UnavailableReason.TIMEOUT)).card.tone)
        assertEquals(DepartureDisplayMapper.RECHECKING_ROUTE_TITLE, card(RouteConditionCheck.NotGuiding(dest), DisplayContext(rechecking = true, recheckAttempt = 2, recheckMaxAttempts = 6)).card.title)
        assertEquals(Tone.UNKNOWN, card(RouteConditionCheck.NotGuiding(dest)).card.tone)
        val unconfirmed = card(RouteConditionCheck.Unconfirmed(dest, UnavailableReason.TIMEOUT))
        assertEquals(Tone.UNKNOWN, unconfirmed.card.tone)
        assertTrue(unconfirmed.card.lines.any { it.text == "내비 화면에서 경로를 확인하세요" })
        assertFalse("Unconfirmed 에는 켰어요 버튼 없음", unconfirmed.showConfirmToggle)
        assertEquals(Tone.OK, card(satisfied).card.tone)
        val other = card(RouteConditionCheck.OtherRouteOption(dest, RouteState.Guiding(RouteOption.SCHOOL_ZONE, null), emptyList()))
        assertEquals(Tone.CAUTION, other.card.tone)
        assertFalse("다른 옵션에는 켰어요 반복을 유도하지 않는다", other.showConfirmToggle)
        val turnOn = card(RouteConditionCheck.NeedsUserAction(dest, recommended, listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), emptyList()))
        assertEquals(DepartureDisplayMapper.TOGGLE_STEPS, turnOn.steps)
        assertTrue(turnOn.showConfirmToggle)
        // 작업 #28: 전역 설정(한 번만) 안내 + [내비 설정 열기]가 먼저, 이번 경로 단계는 그다음
        assertTrue(turnOn.showOpenNaviSettings)
        assertTrue(turnOn.card.lines.any { it.text == DepartureDisplayMapper.GLOBAL_TOLL_HINT })
        assertEquals(DepartureDisplayMapper.THIS_ROUTE_STEPS_INTRO, turnOn.stepsIntro)
        assertTrue(turnOn.card.lines.any { it.text == "통행료 700원" })
        // 작업 #27: FREE + 통행료 있음(어린이 보호구역 우회만) → 유료도로 우회 켜기 안내 + 켰어요 버튼
        val freeWithToll = card(RouteConditionCheck.NeedsUserAction(dest, RouteState.Guiding(RouteOption.FREE, 700), listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), emptyList()))
        assertEquals("유료도로를 지나는 경로예요", freeWithToll.card.title)
        assertTrue(freeWithToll.showConfirmToggle)
        // 작업 #27: FREE + 통행료 모름 → 주의, 확인 권함, 켰어요 반복 유도 없음
        val unverified = card(RouteConditionCheck.TollAvoidanceUnverified(dest, RouteState.Guiding(RouteOption.FREE, null), emptyList()))
        assertEquals(Tone.CAUTION, unverified.card.tone)
        assertEquals("유료도로 우회를 확인해 주세요", unverified.card.title)
        assertFalse(unverified.showConfirmToggle)
        assertTrue("작업 #28: 확인 권함 카드에도 내비 설정 열기", unverified.showOpenNaviSettings)
        val ref = card(RouteConditionCheck.ReferenceOnly(dest, listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED)))
        assertEquals(Tone.INFO, ref.card.tone)
        assertTrue(ref.notes.single().text.startsWith("고속도로 회피는"))
    }

    /** 충족이어도 참고 안내(고속도로 회피 보장 불가)는 숨기지 않는다(리뷰 task-05 #7-9). */
    @Test
    fun satisfiedNotes_areShown() {
        val r = map(
            snapshot(route = RouteConditionCheck.Satisfied(dest, RouteState.Guiding(RouteOption.FREE, 0), listOf(RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED))),
        ).route!!
        assertEquals(1, r.notes.size)
        assertTrue(r.notes[0].text.contains("고속도로 회피는"))
        assertTrue(r.card.lines.any { it.text == "통행료 0원" })
    }

    @Test
    fun drivingRestricted_hidesStepsAndConfirmButton() {
        val r = map(
            snapshot(route = RouteConditionCheck.NeedsUserAction(dest, RouteState.Guiding(RouteOption.RECOMMENDED, 700), listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), emptyList())),
            DisplayContext(controlsEnabled = false),
        ).route!!
        assertTrue(r.steps.isEmpty())
        assertFalse(r.showConfirmToggle)
        assertFalse("주행 중에는 내비 설정 열기 숨김(작업 #28)", r.showOpenNaviSettings)
        assertTrue(r.card.lines.any { it.text == DepartureDisplayMapper.PARKED_ONLY_GUIDE })
        val unverified = map(
            snapshot(route = RouteConditionCheck.TollAvoidanceUnverified(dest, RouteState.Guiding(RouteOption.FREE, null), emptyList())),
            DisplayContext(controlsEnabled = false),
        ).route!!
        assertFalse(unverified.showOpenNaviSettings)
    }

    @Test
    fun toggleNotYetApplied_showsExtraLine() {
        val r = map(
            snapshot(route = RouteConditionCheck.NeedsUserAction(dest, RouteState.Guiding(RouteOption.RECOMMENDED, 700), listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE), emptyList())),
            DisplayContext(tollToggleNotYetApplied = true),
        ).route!!
        assertTrue(r.card.lines.any { it.text.startsWith("아직 이번 경로에 '유료도로 우회'가 적용되지 않았어요") })
    }

    // ------------------------------------------------ 운전자 조건 (D-9)

    @Test
    fun conditionNotSet_isNotNoRestriction() {
        val c = map(DepartureSnapshot.ConditionNotSet(now))
        assertEquals("운전자 조건 미설정", c.main.title)
        assertNull(c.radius)
        assertNull(c.route)
        assertFalse(c.allText().contains("제한 없음"))
        assertFalse(c.allText().contains("까지 출발"))
    }

    @Test
    fun conditionAssumed_showsD9Notice_andRouteUnknown() {
        val assumed = ResolvedCondition(DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING)), ConditionSource.ASSUMED_NIGHT_RESTRICTION, UnavailableReason.TIMEOUT)
        val c = map(snapshot(condition = assumed, radius = RadiusCheckResult.CenterNotSet(dest), route = RouteConditionCheck.NoRouteCondition))
        assertNotNull(c.conditionNotice)
        assertEquals("조건 확인 불가 — 야간 제한 기준", c.conditionNotice!!.title)
        assertEquals(Tone.UNKNOWN, c.conditionNotice!!.tone)
        assertEquals("경로 조건 확인 불가", c.route!!.card.title)
        assertTrue(c.radius!!.lines.first().text.contains("확인하지 못해"))
    }

    @Test
    fun conditionLastKnown_showsNotice() {
        val last = loaded.copy(source = ConditionSource.LAST_KNOWN, lookupFailure = UnavailableReason.SERVICE_ERROR)
        val c = map(snapshot(condition = last))
        assertEquals("운전자 조건을 새로 확인하지 못했어요", c.conditionNotice!!.title)
        assertNull(map(snapshot()).conditionNotice)
    }

    @Test
    fun koreanFormat() {
        assertEquals("오후 12시", KoreanFormat.time(kst(12, 0), zone))
        assertEquals("오전 12시 5분", KoreanFormat.time(kst(0, 5), zone))
        assertEquals("1시간 5분", KoreanFormat.remaining(Duration.ofMinutes(65).plusSeconds(59)))
        assertEquals("1분 미만", KoreanFormat.remaining(Duration.ofMinutes(-3)))
        assertEquals("43분", KoreanFormat.durationCeil(Duration.ofMinutes(42).plusSeconds(1)))
        assertEquals("약 850m", KoreanFormat.distance(853.0))
        assertEquals("2.5km", KoreanFormat.radius(2_500.0))
        assertEquals("12,000원", KoreanFormat.won(12_000))
    }
}
