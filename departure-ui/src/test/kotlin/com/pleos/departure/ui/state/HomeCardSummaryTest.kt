package com.pleos.departure.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 홈 카드 요약(작업 #24): 판정 순위, 조건 미설정, 첫 결과 전, 참고 카드 제외, 줄 수 제한, 주행 중 */
class HomeCardSummaryTest {

    private fun card(tone: Tone, title: String, headline: String? = null, secondary: Boolean = false) =
        StatusCard(tone = tone, icon = StatusIcon.INFO, title = title, headline = headline, secondary = secondary)

    private fun ui(content: DepartureContent?, loading: Boolean = false, controls: Boolean = true) =
        DepartureUiState(content, loading, showLoadingIndicator = false, controlsEnabled = controls, drivingNotice = null)

    private fun content(
        main: StatusCard = card(Tone.OK, "출발 마감", headline = "오후 4시 14분까지 출발"),
        radius: StatusCard? = card(Tone.OK, "목적지가 허용 반경 안이에요"),
        route: StatusCard? = card(Tone.OK, "무료도로로 안내 중이에요"),
        notice: StatusCard? = null,
    ) = DepartureContent(conditionNotice = notice, main = main, radius = radius, route = route?.let { RouteCard(it) })

    @Test
    fun allOk_saysOkWithDeadlineHeadline() {
        val s = HomeCardSummary.from(ui(content()))
        assertEquals(Tone.OK, s.tone)
        assertEquals(HomeCardSummary.VERDICT_OK, s.verdict)
        assertEquals("오후 4시 14분까지 출발", s.headline)
        assertEquals(listOf("목적지가 허용 반경 안이에요", "무료도로로 안내 중이에요"), s.lines.map { it.text })
        assertTrue(s.openEnabled)
    }

    @Test
    fun worstToneWins_dangerOverCaution() {
        val s = HomeCardSummary.from(
            ui(content(radius = card(Tone.DANGER, "목적지가 허용 반경 밖이에요"), route = card(Tone.CAUTION, "유료도로 우회를 켜 주세요")))
        )
        assertEquals(Tone.DANGER, s.tone)
        assertEquals(HomeCardSummary.VERDICT_DANGER, s.verdict)
    }

    @Test
    fun unknownRanksAboveCaution_soUnknownDoesNotLookMild() {
        val s = HomeCardSummary.from(
            ui(content(radius = card(Tone.UNKNOWN, "허용 반경을 확인할 수 없어요"), route = card(Tone.CAUTION, "유료도로 우회를 켜 주세요")))
        )
        assertEquals(Tone.UNKNOWN, s.tone)
        assertEquals(HomeCardSummary.VERDICT_UNKNOWN, s.verdict)
    }

    @Test
    fun infoOnly_isTreatedAsOk() {
        val s = HomeCardSummary.from(ui(content(main = card(Tone.INFO, "야간 운전 제한 없음"), radius = null, route = null)))
        assertEquals(Tone.OK, s.tone)
        assertEquals("야간 운전 제한 없음", s.headline) // headline 이 없으면 main 제목
    }

    @Test
    fun secondaryRadius_isShownButNotJudged() {
        // 출발 자제 단계: 반경 카드는 참고용. 주 판정은 마감(DANGER)이 정하고, 참고 카드의 톤은 판정에 넣지 않는다
        val s = HomeCardSummary.from(
            ui(
                content(
                    main = card(Tone.CAUTION, "출발 마감", headline = "오후 5시까지 출발"),
                    radius = card(Tone.DANGER, "목적지가 허용 반경 밖이에요", secondary = true),
                    route = null,
                )
            )
        )
        assertEquals(Tone.CAUTION, s.tone)
        assertEquals(listOf("목적지가 허용 반경 밖이에요"), s.lines.map { it.text })
    }

    @Test
    fun conditionNotSet_neverSaysOk() {
        val notSet = DepartureContent(
            conditionNotice = null,
            main = card(Tone.INFO, "운전자 조건 미설정"),
            radius = null,
            route = null,
            conditionSet = false,
        )
        val s = HomeCardSummary.from(ui(notSet))
        assertEquals(HomeCardSummary.VERDICT_CONDITION_NOT_SET, s.verdict)
        assertEquals(Tone.INFO, s.tone)
        assertNull(s.headline)
    }

    @Test
    fun beforeFirstResult_showsCheckingOrNotChecked() {
        assertEquals(HomeCardSummary.VERDICT_CHECKING, HomeCardSummary.from(ui(null, loading = true)).verdict)
        val idle = HomeCardSummary.from(ui(null, loading = false))
        assertEquals(HomeCardSummary.VERDICT_NOT_CHECKED, idle.verdict)
        assertEquals(Tone.UNKNOWN, idle.tone)
    }

    @Test
    fun linesAreCappedAndNoticeComesFirst() {
        val s = HomeCardSummary.from(ui(content(notice = card(Tone.CAUTION, "운전자 조건을 새로 확인하지 못했어요"))))
        assertEquals(HomeCardSummary.MAX_LINES, s.lines.size)
        assertEquals("운전자 조건을 새로 확인하지 못했어요", s.lines.first().text)
        assertEquals(Tone.CAUTION, s.tone)
    }

    @Test
    fun whileDriving_cannotOpen() {
        assertFalse(HomeCardSummary.from(ui(content(), controls = false)).openEnabled)
    }

    @Test
    fun realMapperOutput_conditionNotSetFlagIsCarried() {
        // 실제 매퍼가 조건 미설정을 conditionSet=false 로 표시하는지(홈 카드가 이 값에 기대므로)
        val mapper = DepartureDisplayMapper(java.time.ZoneId.of("Asia/Seoul"))
        val c = mapper.map(com.pleos.departure.model.DepartureSnapshot.ConditionNotSet(java.time.Instant.EPOCH), DisplayContext())
        assertFalse(c.conditionSet)
    }
}
