package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceResult
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceStatus
import ai.pleos.playground.sample.vehicle.trip.source.DataSource
import ai.pleos.playground.sample.vehicle.trip.source.EngineOrMockPreDriveSource
import ai.pleos.playground.sample.vehicle.trip.source.EnginePreDriveSource
import ai.pleos.playground.sample.vehicle.trip.source.MockComplianceSource
import ai.pleos.playground.sample.vehicle.trip.source.MockRestAdviceSource
import ai.pleos.playground.sample.vehicle.trip.source.PreDriveCheck
import ai.pleos.playground.sample.vehicle.trip.source.RestAdvice
import ai.pleos.playground.sample.vehicle.trip.source.Violation
import ai.pleos.playground.sample.vehicle.trip.source.judgedConditionsOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TripSummaryContentTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear(); set(2026, Calendar.NOVEMBER, 11, hour, minute)
        }.timeInMillis

    private fun trip(
        maxLevel: FatigueLevel = FatigueLevel.NORMAL,
        caution: Int = 0,
        danger: Int = 0,
        judgedMs: Long = 600_000,
        maxScore: Float = 0.2f,
        avgScore: Float? = 0.1f,
        minutes: Long = 30,
        maxDrivingMinutes: Float = 30f,
        reasons: Set<ReasonCode> = emptySet(),
        preDrive: ReactionVerdict? = null,
        isCalibrating: Boolean = false,
    ) = CompletedTrip(
        tripId = "t1", startedAt = at(10), endedAt = at(10) + minutes * 60_000,
        maxLevel = maxLevel, mildEntries = 0, cautionEntries = caution, dangerEntries = danger,
        reasonCodes = reasons, isDemo = false, observedMs = minutes * 60_000, judgedMs = judgedMs,
        maxScore = maxScore, avgScore = avgScore, maxDrivingMinutes = maxDrivingMinutes,
        preDrive = preDrive, isCalibrating = isCalibrating,
    )

    private fun record(
        t: CompletedTrip = trip(),
        rest: RestAdvice = RestAdvice(0, 0, DataSource.REAL),
        compliance: ComplianceResult = ComplianceResult(ComplianceStatus.COMPLIED, emptyList(), DataSource.REAL),
        preDrive: PreDriveCheck = PreDriveCheck(ReactionVerdict.NORMAL, DataSource.REAL),
    ): TripRecordEntity = TripRecorder.buildRecord(t, rest, compliance, preDrive)

    private fun TripSummaryContent.item(key: SummaryKey) = items.first { it.key == key }

    // ---- 저장 값 ----

    @Test
    fun `피로 점수는 0~100으로 저장한다`() {
        val r = record(trip(maxScore = 0.72f, avgScore = 0.31f))
        assertEquals(72f, r.maxFatigueScore, 1e-3f)
        assertEquals(31f, r.avgFatigueScore!!, 1e-3f)
        assertNull(record(trip(judgedMs = 0, avgScore = null)).avgFatigueScore)
    }

    // ---- 한 줄 요약 우선순위 ----

    @Test
    fun `아무 일 없으면 안전 운전 문장`() {
        val c = TripSummaryContent.from(record())
        assertEquals("오늘도 안전하게 운전하셨어요.", c.headline)
        assertEquals(SummaryTone.GOOD, c.headlineTone)
        assertFalse(c.hasMock)
    }

    @Test
    fun `CAUTION 이상이면 피로 문장이 가장 먼저`() {
        val c = TripSummaryContent.from(
            record(
                trip(maxLevel = FatigueLevel.CAUTION, caution = 1),
                compliance = ComplianceResult(ComplianceStatus.VIOLATED, listOf(Violation.NIGHT_DRIVING), DataSource.REAL),
            )
        )
        assertTrue(c.headline.startsWith("운전 중 피로 신호가 있었어요"))
        assertEquals(SummaryTone.WARN, c.headlineTone)
    }

    @Test
    fun `피로가 없고 조건을 어기면 조건 문장`() {
        val c = TripSummaryContent.from(
            record(compliance = ComplianceResult(ComplianceStatus.VIOLATED, listOf(Violation.NIGHT_DRIVING), DataSource.REAL))
        )
        assertTrue(c.headline.startsWith("지키지 못한 운전 조건이 있었어요"))
        assertEquals("야간 운전 조건을 지키지 못했어요.", c.item(SummaryKey.COMPLIANCE).detail)
    }

    @Test
    fun `출발 전 반응이 느리면 반응 문장`() {
        val c = TripSummaryContent.from(record(preDrive = PreDriveCheck(ReactionVerdict.SLOWER, DataSource.REAL)))
        assertTrue(c.headline.startsWith("출발 전 반응이 평소보다 느렸어요"))
        assertEquals("조금 느림", c.item(SummaryKey.PRE_DRIVE).value)
    }

    @Test
    fun `판정 구간이 없으면 정상이라고 하지 않는다`() {
        val c = TripSummaryContent.from(record(trip(judgedMs = 0, maxScore = 0f, avgScore = null)))
        assertEquals("운전을 마치셨어요. 이번에는 피로를 판정할 구간이 없었어요.", c.headline)
        val fatigue = c.item(SummaryKey.FATIGUE)
        assertEquals("판정 없음", fatigue.value)
        assertEquals(SummaryTone.INFO, fatigue.tone)
    }

    // ---- 항목 ----

    @Test
    fun `피로 점수 항목`() {
        val c = TripSummaryContent.from(record(trip(maxLevel = FatigueLevel.DANGER, danger = 1, maxScore = 0.724f, avgScore = 0.306f, isCalibrating = true)))
        val f = c.item(SummaryKey.FATIGUE)
        assertEquals("최고 72점", f.value)
        assertEquals("졸음 위험 단계까지 올라갔어요. 평균 31점이에요(높을수록 피곤). 아직 평소 운전 기록이 적어 참고용이에요.", f.detail)
        assertEquals(SummaryTone.WARN, f.tone)
        assertFalse(f.isMock)
    }

    @Test
    fun `운전 시간 항목`() {
        assertEquals("1분 미만", TripSummaryContent.durationText(59_000))
        assertEquals("32분", TripSummaryContent.durationText(32 * 60_000L))
        assertEquals("1시간", TripSummaryContent.durationText(60 * 60_000L))
        assertEquals("1시간 5분", TripSummaryContent.durationText(65 * 60_000L))

        val plain = TripSummaryContent.from(record(trip(minutes = 65, maxDrivingMinutes = 45f))).item(SummaryKey.DURATION)
        assertEquals("1시간 5분", plain.value)
        assertEquals("쉬지 않고 가장 오래 운전한 시간은 45분이에요.", plain.detail)
        assertEquals(SummaryTone.INFO, plain.tone)

        val long = TripSummaryContent.from(
            record(trip(minutes = 130, maxDrivingMinutes = 125f, reasons = setOf(ReasonCode.LONG_DRIVE)))
        ).item(SummaryKey.DURATION)
        assertEquals(SummaryTone.WARN, long.tone)
        assertTrue(long.detail!!.startsWith("쉬지 않고 2시간 5분 이어서 운전하셨어요"))
    }

    @Test
    fun `휴식 권고 항목`() {
        val none = TripSummaryContent.from(record()).item(SummaryKey.REST)
        assertEquals("없음", none.value)
        val some = TripSummaryContent.from(record(rest = RestAdvice(2, 1, DataSource.MOCK))).item(SummaryKey.REST)
        assertEquals("2번", some.value)
        assertEquals("안내에 1번 답하셨어요.", some.detail)
        assertTrue(some.isMock)
    }

    @Test
    fun `출발 전 반응을 하지 않았으면 하지 않음`() {
        val c = TripSummaryContent.from(record(preDrive = PreDriveCheck(null, DataSource.REAL)))
        assertEquals("하지 않음", c.item(SummaryKey.PRE_DRIVE).value)
    }

    @Test
    fun `지난 기록 한 줄은 요약 화면과 같은 값을 쓴다`() {
        val r = record(trip(maxLevel = FatigueLevel.DANGER, danger = 1, maxScore = 0.75f, minutes = 65))
        val h = TripHistoryItem.from(r)
        val c = TripSummaryContent.from(r)
        assertEquals("t1", h.tripId)
        assertEquals("1시간 5분", h.duration)
        assertEquals("최고 75점", h.fatigue)
        assertEquals(c.headline, h.headline)
        assertEquals(SummaryTone.WARN, h.tone)
        assertEquals(r.endedAt, h.endedAt)
    }

    // ---- 임시값(Mock) 출처 ----

    @Test
    fun `Mock 휴식 권고는 DANGER 진입 횟수만 센다`() = runBlocking {
        val rest = MockRestAdviceSource.countFor(trip(maxLevel = FatigueLevel.DANGER, caution = 1, danger = 1))
        assertEquals(RestAdvice(1, 1, DataSource.MOCK), rest)
    }

    @Test
    fun `Mock 조건 준수는 20시~6시에 걸치면 야간 운전 위반`() = runBlocking {
        val source = MockComplianceSource(timeZone = utc)
        val day = trip().copy(startedAt = at(10), endedAt = at(11))
        val night = trip().copy(startedAt = at(19, 30), endedAt = at(20, 10))
        assertEquals(ComplianceStatus.COMPLIED, source.evaluate(day).status)
        val r = source.evaluate(night)
        assertEquals(ComplianceStatus.VIOLATED, r.status)
        assertEquals(listOf(Violation.NIGHT_DRIVING), r.violations)
        assertEquals(DataSource.MOCK, r.source)
    }

    @Test
    fun `출발 전 반응은 엔진 값, 없으면 하지 않음(실제)`() = runBlocking {
        assertEquals(PreDriveCheck(ReactionVerdict.SLOWER, DataSource.REAL), EnginePreDriveSource.checkFor(trip(preDrive = ReactionVerdict.SLOWER)))
        assertEquals(PreDriveCheck(null, DataSource.REAL), EnginePreDriveSource.checkFor(trip(preDrive = null)))
        val item = TripSummaryContent.from(record(preDrive = EnginePreDriveSource.checkFor(trip(preDrive = null)))).item(SummaryKey.PRE_DRIVE)
        assertEquals("하지 않음", item.value)
        assertFalse(item.isMock)
    }

    // ---- 운전 조건 문구(판정한 조건만 말한다) ----

    @Test
    fun `야간만 판정해 지켰으면 모두 지킴이라고 하지 않는다`() {
        val c = TripSummaryContent.from(
            record(compliance = ComplianceResult(ComplianceStatus.COMPLIED, emptyList(), DataSource.REAL, setOf(Violation.NIGHT_DRIVING)))
        ).item(SummaryKey.COMPLIANCE)
        assertEquals("지킴", c.value)
        assertEquals("야간 운전 조건을 지켰어요.", c.detail)
        assertEquals(SummaryTone.GOOD, c.tone)
        assertFalse(c.isMock)
    }

    @Test
    fun `세 조건을 모두 판정해 지켰으면 모두 지킴`() {
        val c = TripSummaryContent.from(
            record(compliance = ComplianceResult(ComplianceStatus.COMPLIED, emptyList(), DataSource.REAL, Violation.entries.toSet()))
        ).item(SummaryKey.COMPLIANCE)
        assertEquals("모두 지킴", c.value)
    }

    @Test
    fun `조건이 없으면 설정 안 함, 판정 못 하면 확인 못 함`() {
        val none = TripSummaryContent.from(record(compliance = ComplianceResult(ComplianceStatus.NO_CONDITION, emptyList(), DataSource.REAL)))
        assertEquals("설정 안 함", none.item(SummaryKey.COMPLIANCE).value)
        assertEquals(SummaryTone.GOOD, none.headlineTone) // 조건이 없는 건 경고가 아니다
        val unknown = TripSummaryContent.from(record(compliance = ComplianceResult(ComplianceStatus.UNKNOWN, emptyList(), DataSource.REAL)))
        assertEquals("확인 못 함", unknown.item(SummaryKey.COMPLIANCE).value)
    }

    @Test
    fun `판정한 조건은 기록에 저장하고, 예전 기록은 출처로 추정한다`() {
        val r = record(compliance = ComplianceResult(ComplianceStatus.VIOLATED, listOf(Violation.NIGHT_DRIVING), DataSource.REAL, setOf(Violation.NIGHT_DRIVING)))
        assertEquals("NIGHT_DRIVING", r.judgedConditions)
        assertEquals(setOf(Violation.NIGHT_DRIVING), judgedConditionsOf(r))
        // 칸이 생기기 전 기록
        val oldMock = r.copy(judgedConditions = "", complianceSource = DataSource.MOCK.name)
        assertEquals(setOf(Violation.NIGHT_DRIVING), judgedConditionsOf(oldMock))
        val oldReal = r.copy(judgedConditions = "")
        assertEquals(Violation.entries.toSet(), judgedConditionsOf(oldReal))
        assertEquals(emptySet<Violation>(), judgedConditionsOf(r.copy(judgedConditions = "", compliance = ComplianceStatus.UNKNOWN.name)))
    }

    @Test
    fun `반응 체크는 엔진 값이 있으면 실제 값, 없으면 임시값`() = runBlocking {
        val source = EngineOrMockPreDriveSource()
        assertEquals(PreDriveCheck(ReactionVerdict.SLOWER, DataSource.REAL), source.checkFor(trip(preDrive = ReactionVerdict.SLOWER)))
        assertEquals(PreDriveCheck(ReactionVerdict.NORMAL, DataSource.MOCK), source.checkFor(trip(preDrive = null)))
    }

    @Test
    fun `Mock 값이 있으면 화면에 임시 표시`() {
        val c = TripSummaryContent.from(
            record(
                rest = RestAdvice(0, 0, DataSource.MOCK),
                compliance = ComplianceResult(ComplianceStatus.COMPLIED, emptyList(), DataSource.MOCK),
                preDrive = PreDriveCheck(ReactionVerdict.NORMAL, DataSource.MOCK),
            )
        )
        assertTrue(c.hasMock)
        assertEquals(
            setOf(SummaryKey.REST, SummaryKey.COMPLIANCE, SummaryKey.PRE_DRIVE),
            c.items.filter { it.isMock }.map { it.key }.toSet(),
        )
    }
}
