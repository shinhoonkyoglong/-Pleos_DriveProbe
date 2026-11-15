package ai.pleos.playground.sample.vehicle.trip.report

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.trip.SummaryKey
import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceStatus
import ai.pleos.playground.sample.vehicle.trip.source.DataSource
import ai.pleos.playground.sample.vehicle.trip.source.DepartureDriverConditionsSource
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TripReportTest {

    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    private val now = 1_800_000_000_000L
    private var seq = 0

    private fun trip(
        endedAt: Long,
        durationMin: Int = 30,
        level: FatigueLevel = FatigueLevel.NORMAL,
        judgedMs: Long = 600_000,
        avg: Float? = 25f,
        max: Float = 35f,
        rest: Int = 0,
        responded: Int = rest,
        compliance: ComplianceStatus = ComplianceStatus.COMPLIED,
        violations: String = "",
        complianceSource: DataSource = DataSource.MOCK,
        preDrive: ReactionVerdict? = ReactionVerdict.NORMAL,
        reasons: String = "",
        isDemo: Boolean = false,
    ) = TripRecordEntity(
        tripId = "t${seq++}",
        startedAt = endedAt - durationMin * 60_000L,
        endedAt = endedAt,
        durationMs = durationMin * 60_000L,
        maxLevel = level.name,
        mildEntries = 0, cautionEntries = 0, dangerEntries = 0,
        reasonCodes = reasons,
        judgedMs = judgedMs,
        maxFatigueScore = max,
        avgFatigueScore = avg,
        preDriveVerdict = preDrive?.name,
        preDriveSource = DataSource.REAL.name,
        restAdviceCount = rest,
        restRespondedCount = responded,
        restAdviceSource = DataSource.MOCK.name,
        compliance = compliance.name,
        violations = violations,
        complianceSource = complianceSource.name,
        isDemo = isDemo,
    )

    private fun daysAgo(d: Double) = now - (d * DAY_MS).toLong()

    // ---- 기간 집계 ----

    @Test
    fun `기간 집계는 기간 안 기록만 세고 평균 피로는 판정 시간으로 가중한다`() {
        val rs = listOf(
            trip(daysAgo(1.0), avg = 20f, judgedMs = 300_000),
            trip(daysAgo(2.0), avg = 50f, judgedMs = 100_000, level = FatigueLevel.CAUTION, rest = 1, responded = 0),
            trip(daysAgo(3.0), judgedMs = 0, avg = null), // 판정 없음
            trip(daysAgo(9.0)), // 기간 밖
        )
        val s = TripPeriodStats.of(rs, now - WEEK_MS, now)
        assertEquals(3, s.tripCount)
        assertEquals(2, s.judgedTripCount)
        assertEquals(27.5f, s.avgFatigueScore!!, 0.01f) // (20*3 + 50*1) / 4
        assertEquals(1, s.fatigueSignalTrips)
        assertEquals(1, s.restAdviceCount)
        assertEquals(0, s.restRespondedCount)
        assertTrue(s.restMock)
        assertFalse(s.isDemo)
    }

    @Test
    fun `위반 코드는 조건별로 센다`() {
        val rs = listOf(
            trip(daysAgo(1.0), compliance = ComplianceStatus.VIOLATED, violations = "NIGHT_DRIVING"),
            trip(daysAgo(2.0), compliance = ComplianceStatus.VIOLATED, violations = "NIGHT_DRIVING,HIGHWAY"),
            trip(daysAgo(3.0)),
            trip(daysAgo(4.0), compliance = ComplianceStatus.UNKNOWN),
        )
        val s = TripPeriodStats.of(rs, now - WEEK_MS, now)
        assertEquals(1, s.compliedTrips)
        assertEquals(2, s.violatedTrips)
        assertEquals(3, s.complianceJudged)
        assertEquals(2, s.violationCounts[ai.pleos.playground.sample.vehicle.trip.source.Violation.NIGHT_DRIVING])
        assertEquals(1, s.violationCounts[ai.pleos.playground.sample.vehicle.trip.source.Violation.HIGHWAY])
    }

    // ---- 운전 리포트 ----

    /** 8주 동안 주 4번, 문제 없는 운전 */
    private fun steadyEightWeeks(complianceSource: DataSource = DataSource.MOCK): List<TripRecordEntity> =
        (0 until 8).flatMap { w -> (0 until 4).map { i -> trip(daysAgo(w * 7 + i + 0.5), complianceSource = complianceSource) } }

    @Test
    fun `리포트 - 변화가 없으면 꾸준하다고 말하고 판정 등급은 없다`() {
        val r = DrivingReport.build(steadyEightWeeks(), DriverConditions(setOf(ReportCondition.NO_NIGHT_DRIVING)), now)
        assertEquals(FatigueTrend.STEADY, r.fatigueTrend)
        assertEquals("8주 동안 운전 컨디션이 꾸준했어요.", r.headline)
        assertEquals(SummaryTone.GOOD, r.headlineTone)
        assertEquals(8, r.activeWeeks)
        assertEquals(8, r.weekly.size)
        assertEquals(32, r.total.tripCount)
        assertTrue(r.findings.first().text.startsWith("8주 동안 32번"))
        assertTrue(r.findings.any { it.text == "야간 운전 안 함 조건을 8주 동안 모두 지켰어요." })
        assertTrue(r.hasMock)
    }

    @Test
    fun `리포트 - 피로가 높아지면 쉬어 가라고 말한다`() {
        val rs = steadyEightWeeks().map { t ->
            val weeksAgo = ((now - t.endedAt) / WEEK_MS).toInt()
            t.copy(avgFatigueScore = 40f - weeksAgo * 3f) // 최근일수록 높음
        }
        val r = DrivingReport.build(rs, null, now)
        assertEquals(FatigueTrend.WORSENING, r.fatigueTrend)
        assertEquals(SummaryTone.WARN, r.headlineTone)
        assertTrue(r.headline.startsWith("최근 4주는 그 전보다 피로 점수가 높아졌어요."))
        assertTrue(r.findings.any { it.text.startsWith("평균 피로 점수: 앞 4주") && it.tone == SummaryTone.WARN })
    }

    @Test
    fun `리포트 - 못 지킨 조건은 횟수로 말한다`() {
        val rs = steadyEightWeeks().mapIndexed { i, t ->
            if (i % 8 == 0) t.copy(compliance = ComplianceStatus.VIOLATED.name, violations = "NIGHT_DRIVING") else t
        }
        val r = DrivingReport.build(rs, DriverConditions(setOf(ReportCondition.NO_NIGHT_DRIVING)), now)
        assertEquals(4, r.conditionRows.single().violations)
        assertEquals(ConditionStatus.VIOLATED, r.conditionRows.single().status)
        assertTrue(r.findings.any { it.text == "야간 운전 안 함 조건을 4번 지키지 못했어요." && it.tone == SummaryTone.WARN })
    }

    @Test
    fun `리포트 - 기록이 없으면 없다고만 말한다`() {
        val r = DrivingReport.build(emptyList(), null, now)
        assertEquals("8주 동안 운전 기록이 없어요.", r.headline)
        assertTrue(r.findings.isEmpty())
        assertEquals(FatigueTrend.UNKNOWN, r.fatigueTrend)
    }

    @Test
    fun `리포트 - 임시 조건 판정으로는 고속도로와 반경을 판정하지 않는다`() {
        val all = DriverConditions(ReportCondition.entries.toSet(), radiusKm = 10.0)
        val mock = DrivingReport.build(steadyEightWeeks(DataSource.MOCK), all, now)
        assertEquals(
            listOf(ConditionStatus.KEPT, ConditionStatus.NOT_JUDGED, ConditionStatus.NOT_JUDGED),
            mock.conditionRows.map { it.status },
        )
        val real = DrivingReport.build(steadyEightWeeks(DataSource.REAL), all, now)
        assertTrue(real.conditionRows.all { it.status == ConditionStatus.KEPT && !it.isMock })
    }

    @Test
    fun `리포트 - 조건 설정이 없으면 세 조건을 참고로 보여준다`() {
        val r = DrivingReport.build(steadyEightWeeks(), null, now)
        assertEquals(3, r.conditionRows.size)
        assertTrue(r.dataNotes.first().contains("설정되어 있지 않아"))
    }

    @Test
    fun `그래프 3개 - 주별 값과 y축 최대값`() {
        val rs = steadyEightWeeks() + trip(daysAgo(0.2), level = FatigueLevel.CAUTION, avg = null, judgedMs = 0)
        val charts = DrivingReport.build(rs, null, now).charts()
        assertEquals(listOf("주별 운전 횟수", "주별 평균 피로 점수", "주별 확인할 일"), charts.map { it.title })
        val counts = charts[0].series.single().values
        assertEquals(8, counts.size)
        assertEquals(5f, counts.last()) // 마지막 주에 1번 더
        assertEquals(8f, charts[0].yMax) // 5 → 2칸 단위로 짝수 칸이 되도록 8, 눈금 0·4·8
        assertEquals(ChartKind.LINE, charts[1].kind)
        assertEquals(40f, charts[1].yMax) // 평균 25점 + 5 → 최소 40
        assertEquals(2, charts[2].series.size) // 범례가 필요한 묶음 막대
        assertEquals(1f, charts[2].series[0].values.last())
    }

    @Test
    fun `피로를 판정 못한 주는 선 그래프 값이 비어 있다`() {
        val rs = steadyEightWeeks().map { t -> if (now - t.endedAt < WEEK_MS) t.copy(judgedMs = 0, avgFatigueScore = null) else t }
        val fatigue = DrivingReport.build(rs, null, now).charts()[1].series.single().values
        assertNull(fatigue.last())
        assertEquals(25f, fatigue.first()!!, 0.01f)
    }

    @Test
    fun `2번 운전자 조건을 리포트 조건으로 바꾼다`() {
        val c = DriverCondition(
            timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
            routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
            radiusCenter = GeoPoint(37.5, 127.0),
            allowedRadiusMeters = null,
        )
        val r = DepartureDriverConditionsSource.toReport(c, defaultRadiusMeters = 10_000.0, radiusEnabled = true)
        assertEquals(setOf(ReportCondition.NO_NIGHT_DRIVING, ReportCondition.RADIUS), r.conditions)
        assertEquals(10.0, r.radiusKm!!, 0.0)
        assertNull(DepartureDriverConditionsSource.toReport(DriverCondition(), 10_000.0, radiusEnabled = true).radiusKm)
    }

    @Test
    fun `반경 기능이 꺼져 있으면 기준점이 있어도 리포트에 반경 조건을 넣지 않는다`() {
        val c = DriverCondition(
            timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
            radiusCenter = GeoPoint(37.5, 127.0),
        )
        val r = DepartureDriverConditionsSource.toReport(c, defaultRadiusMeters = 10_000.0, radiusEnabled = false)
        assertEquals(setOf(ReportCondition.NO_NIGHT_DRIVING), r.conditions)
        assertNull(r.radiusKm)
    }

    @Test
    fun `인쇄용 HTML은 그래프 3개와 표를 담고 글자를 이스케이프한다`() {
        val r = DrivingReport.build(steadyEightWeeks(), DriverConditions(setOf(ReportCondition.NO_NIGHT_DRIVING)), now)
        val html = DrivingReportHtml.render(r, zone = seoul)
        assertTrue(html.startsWith("<!doctype html>"))
        assertEquals(3, Regex("<svg ").findAll(html).count())
        listOf("운전 리포트", "주요 변화", "주별 그래프", "주별 기록 표", DrivingReport.DISCLAIMER).forEach {
            assertTrue(it, html.contains(it))
        }
        assertFalse(html.contains("조건 해제"))
        assertFalse(html.contains("http"))
        assertEquals("&lt;b&gt; &amp; &quot;", DrivingReportHtml.esc("<b> & \""))
    }

    @Test
    fun `서머타임이 있는 시간대에서도 주 경계는 0시이고 겹치지 않는다`() {
        val la = TimeZone.getTimeZone("America/Los_Angeles") // 11월 첫 일요일에 서머타임이 끝난다
        val now = java.util.Calendar.getInstance(la).apply { clear(); set(2026, java.util.Calendar.NOVEMBER, 11, 0, 43) }.timeInMillis
        val r = DrivingReport.build(emptyList(), null, now, zone = la)
        r.weekly.forEach { w ->
            val c = java.util.Calendar.getInstance(la).apply { timeInMillis = w.from }
            assertEquals(0, c.get(java.util.Calendar.HOUR_OF_DAY))
        }
        r.weekly.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from) }
        assertEquals("9월 17일~9월 23일", r.weekly.first().rangeLabel(la))
        assertEquals("11월 5일~11월 11일", r.weekly.last().rangeLabel(la))
    }

    // ---- 시연용 가상 기록 ----

    @Test
    fun `시연 기록은 같은 seed면 같고 모두 시연 표시가 있다`() {
        val a = DemoHistoryGenerator.generate(now, zone = seoul)
        val b = DemoHistoryGenerator.generate(now, zone = seoul)
        assertEquals(a, b)
        assertTrue(a.size in 20..40) // 주 3~5번 × 8주(오늘 이후로 끝나는 운전은 빠짐)
        assertTrue(a.all { it.isDemo && it.tripId.startsWith(DemoHistoryGenerator.ID_PREFIX) && it.endedAt < now })
        assertTrue(a.all { it.endedAt >= now - 8 * WEEK_MS })
    }

    @Test
    fun `시연 기록으로 만든 리포트는 피로가 낮아지는 이야기가 나온다`() {
        val rs = DemoHistoryGenerator.generate(now, zone = seoul)
        val r = DrivingReport.build(rs, DriverConditions(setOf(ReportCondition.NO_NIGHT_DRIVING)), now, zone = seoul)
        assertTrue(r.isDemo)
        assertEquals(8, r.activeWeeks)
        assertEquals(FatigueTrend.IMPROVING, r.fatigueTrend)
        assertEquals(1, r.conditionRows.single().violations) // 2주차 야간 운전 1번
        assertTrue(r.dataNotes.any { it.contains("시연용 가상 기록") })

        val integrated = DrivingReport.build(
            DemoHistoryGenerator.generate(now, integrated = true, zone = seoul),
            DriverConditions(ReportCondition.entries.toSet(), 10.0), now, zone = seoul,
        )
        assertFalse(integrated.hasMock)
    }
}
