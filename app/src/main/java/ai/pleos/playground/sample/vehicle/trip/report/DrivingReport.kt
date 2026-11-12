package ai.pleos.playground.sample.vehicle.trip.report

import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent.Companion.durationText
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceStatus
import ai.pleos.playground.sample.vehicle.trip.source.DataSource
import ai.pleos.playground.sample.vehicle.trip.source.MockComplianceSource
import ai.pleos.playground.sample.vehicle.trip.source.Violation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/*
 * 운전 리포트 — 여러 주(기본 8주)의 운전을 **주별 그래프와 변화 문장**으로 보여주는 기간 요약(순수 로직).
 *
 * - 판정(좋다/나쁘다 등급)을 내리지 않는다. "앞 4주보다 피로 점수가 낮아졌어요"처럼 기록의 변화만 말한다
 * - 운전자·가족이 함께 보는 자료. 인쇄하면 의사·가족과 이야기할 때 참고 자료로 쓸 수 있다
 *   (조건부 면허는 아직 시행 전이라 "조건 해제 근거"로 내세우지 않는다)
 *
 * 그래프 화면은 trip/ui/TripReportComponents.kt, 인쇄용 HTML은 DrivingReportHtml.kt
 */

/** 리포트에 나오는 운전 조건(2번 DriverCondition에서 가져온다). [violation] = 이 조건을 어긴 기록 코드 */
enum class ReportCondition(val label: String, val violation: Violation) {
    NO_NIGHT_DRIVING("야간 운전 안 함", Violation.NIGHT_DRIVING),
    AVOID_HIGHWAY("고속도로 운전 안 함", Violation.HIGHWAY),
    RADIUS("정한 반경 안에서만 운전", Violation.OUT_OF_RADIUS),
}

/**
 * 운전자가 가진 조건(2번 운전자 조건 설정 값).
 * @param radiusKm 반경 조건의 반경(km). 반경 조건이 없으면 null
 */
data class DriverConditions(val conditions: Set<ReportCondition>, val radiusKm: Double? = null)

enum class ConditionStatus { KEPT, VIOLATED, NOT_JUDGED }

/**
 * 조건 하나의 기록.
 * @param judgedTrips 이 조건을 판정한 주행 수(판정할 수 없던 주행은 빠짐)
 * @param isMock      판정 값이 임시값(2번 연동 전)이면 true
 */
data class ConditionRow(
    val condition: ReportCondition,
    val status: ConditionStatus,
    val judgedTrips: Int,
    val violations: Int,
    val isMock: Boolean,
)

/** 그래프의 한 주 */
data class WeekRow(val stats: TripPeriodStats) {
    val from: Long get() = stats.from
    val to: Long get() = stats.to

    /** 그래프 x축 글자(예: "11/5") */
    fun label(zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("M/d", Locale.KOREAN).apply { timeZone = zone }.format(Date(from))

    /** 기간 글자(예: "11/5~11/11") */
    fun rangeLabel(zone: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("M/d", Locale.KOREAN).apply { timeZone = zone }
        return "${f.format(Date(from))}~${f.format(Date(to - 1))}"
    }
}

enum class FatigueTrend { IMPROVING, STEADY, WORSENING, UNKNOWN }

/** 변화 문장 하나. [tone]은 색·글자(좋음/확인/참고) 표시용 */
data class ReportFinding(val text: String, val tone: SummaryTone)

/**
 * @param conditions        운전자 조건. 설정이 없으면 null → [conditionRows]는 세 조건 모두 참고로 보여준다
 * @param total             전체 기간 집계
 * @param weekly            주별 집계(오래된 주 → 최근 주). 그래프의 x축
 * @param firstHalfAvg, secondHalfAvg  앞 절반·뒤 절반의 평균 피로 점수
 * @param headline          가장 눈에 띄는 변화 한 문장
 * @param findings          변화 문장(운전량 → 피로 → 운전 조건 → 휴식 권고 → 출발 전 반응)
 * @param dataNotes         데이터 출처 안내(임시값, 위치 미수집 등)
 */
data class DrivingReport(
    val from: Long,
    val to: Long,
    val weeks: Int,
    val generatedAt: Long,
    val conditions: DriverConditions?,
    val total: TripPeriodStats,
    val weekly: List<WeekRow>,
    val conditionRows: List<ConditionRow>,
    val fatigueTrend: FatigueTrend,
    val firstHalfAvg: Float?,
    val secondHalfAvg: Float?,
    val headline: String,
    val headlineTone: SummaryTone,
    val findings: List<ReportFinding>,
    val dataNotes: List<String>,
    val isDemo: Boolean,
) {
    val hasMock: Boolean get() = total.hasMock || conditionRows.any { it.isMock }
    val activeWeeks: Int get() = weekly.count { it.stats.tripCount > 0 }
    val halfWeeks: Int get() = weeks / 2

    fun periodText(zone: TimeZone = TimeZone.getDefault()): String = formatPeriod(from, to, zone)

    companion object {

        const val DISCLAIMER =
            "이 리포트는 차량 앱(계속운전)이 운전 기록을 정리한 참고 자료입니다. 의학적 진단이나 운전 능력 판정이 아닙니다."

        /** 앞 절반과 뒤 절반의 평균 피로 점수가 이만큼(점) 넘게 차이 나면 낮아짐/높아짐 */
        const val TREND_DELTA = 3f

        /**
         * @param records    주행 기록(기간 밖 기록은 알아서 뺀다)
         * @param conditions 운전자 조건(없으면 null)
         * @param now        기준 시각. 기간은 오늘을 포함한 [weeks]×7일(날짜 단위)
         */
        fun build(
            records: List<TripRecordEntity>,
            conditions: DriverConditions?,
            now: Long,
            weeks: Int = 8,
            zone: TimeZone = TimeZone.getDefault(),
        ): DrivingReport {
            require(weeks >= 2) { "weeks must be >= 2: $weeks" }
            val from = periodStart(now, weeks * 7, zone)
            val inPeriod = records.filter { it.endedAt in from until now }
            val total = TripPeriodStats.of(inPeriod, from, now)
            val weekly = (0 until weeks).map { i ->
                val wFrom = addDays(from, i * 7, zone)
                WeekRow(TripPeriodStats.of(inPeriod, wFrom, minOf(addDays(wFrom, 7, zone), now)))
            }

            val half = addDays(from, (weeks / 2) * 7, zone)
            val firstAvg = TripPeriodStats.of(inPeriod, from, half).avgFatigueScore
            val secondAvg = TripPeriodStats.of(inPeriod, half, now).avgFatigueScore
            val trend = when {
                firstAvg == null || secondAvg == null -> FatigueTrend.UNKNOWN
                secondAvg - firstAvg > TREND_DELTA -> FatigueTrend.WORSENING
                firstAvg - secondAvg > TREND_DELTA -> FatigueTrend.IMPROVING
                else -> FatigueTrend.STEADY
            }

            val shown = conditions?.conditions?.takeIf { it.isNotEmpty() } ?: ReportCondition.entries.toSet()
            val rows = ReportCondition.entries.filter { it in shown }.map { conditionRow(it, inPeriod) }

            val (headline, tone) = headline(total, weeks, trend)
            return DrivingReport(
                from = from, to = now, weeks = weeks, generatedAt = now, conditions = conditions,
                total = total, weekly = weekly, conditionRows = rows,
                fatigueTrend = trend, firstHalfAvg = firstAvg, secondHalfAvg = secondAvg,
                headline = headline, headlineTone = tone,
                findings = findings(total, weeks, rows, firstAvg, secondAvg, trend),
                dataNotes = dataNotes(total, rows, conditions),
                isDemo = total.isDemo,
            )
        }

        /**
         * 조건 하나를 판정한 주행 수와 어긴 횟수.
         * 임시 판정(MockComplianceSource)은 야간 운전만 보므로, 그 기록으로는 고속도로·반경을 "판정 안 함"으로 둔다.
         */
        fun conditionRow(condition: ReportCondition, records: List<TripRecordEntity>): ConditionRow {
            val judged = records.filter { r ->
                r.compliance != ComplianceStatus.UNKNOWN.name &&
                    (r.complianceSource == DataSource.REAL.name || condition.violation in MockComplianceSource.EVALUATED)
            }
            val violations = judged.count { r -> condition.violation.name in r.violations.split(',').map { it.trim() } }
            val status = when {
                judged.isEmpty() -> ConditionStatus.NOT_JUDGED
                violations > 0 -> ConditionStatus.VIOLATED
                else -> ConditionStatus.KEPT
            }
            return ConditionRow(condition, status, judged.size, violations, judged.any { it.complianceSource == DataSource.MOCK.name })
        }

        private fun headline(t: TripPeriodStats, weeks: Int, trend: FatigueTrend): Pair<String, SummaryTone> {
            val half = weeks / 2
            return when {
                t.tripCount == 0 -> "${weeks}주 동안 운전 기록이 없어요." to SummaryTone.INFO
                trend == FatigueTrend.IMPROVING -> "최근 ${half}주는 그 전보다 피로 점수가 낮아졌어요." to SummaryTone.GOOD
                trend == FatigueTrend.WORSENING -> "최근 ${half}주는 그 전보다 피로 점수가 높아졌어요. 쉬어 가며 운전하세요." to SummaryTone.WARN
                trend == FatigueTrend.STEADY -> "${weeks}주 동안 운전 컨디션이 꾸준했어요." to SummaryTone.GOOD
                else -> "${weeks}주 동안 ${t.tripCount}번 운전하셨어요." to SummaryTone.INFO
            }
        }

        private fun findings(
            t: TripPeriodStats,
            weeks: Int,
            rows: List<ConditionRow>,
            firstAvg: Float?,
            secondAvg: Float?,
            trend: FatigueTrend,
        ): List<ReportFinding> = buildList {
            if (t.tripCount == 0) return@buildList
            val half = weeks / 2

            // 운전량
            val perWeek = t.tripCount.toFloat() / weeks
            add(
                ReportFinding(
                    "${weeks}주 동안 ${t.tripCount}번, ${durationText(t.totalDurationMs)} 운전하셨어요(일주일에 ${"%.1f".format(perWeek)}번 꼴).",
                    SummaryTone.INFO,
                ),
            )

            // 피로 점수 변화
            if (firstAvg != null && secondAvg != null) {
                val a = firstAvg.roundToInt()
                val b = secondAvg.roundToInt()
                val tone = when (trend) {
                    FatigueTrend.IMPROVING, FatigueTrend.STEADY -> SummaryTone.GOOD
                    FatigueTrend.WORSENING -> SummaryTone.WARN
                    FatigueTrend.UNKNOWN -> SummaryTone.INFO
                }
                add(ReportFinding("평균 피로 점수: 앞 ${half}주 ${a}점 → 최근 ${weeks - half}주 ${b}점.", tone))
            } else if (t.judgedTripCount == 0) {
                add(ReportFinding("시속 60km 이상으로 곧게 달린 구간이 없어 피로 점수가 없어요.", SummaryTone.INFO))
            }

            // 피로 신호
            if (t.judgedTripCount > 0) {
                add(
                    if (t.fatigueSignalTrips == 0) ReportFinding("피로 주의 이상 신호는 한 번도 없었어요.", SummaryTone.GOOD)
                    else ReportFinding("${t.tripCount}번 운전 중 ${t.fatigueSignalTrips}번 피로 주의 이상 신호가 있었어요.", SummaryTone.WARN),
                )
            }

            // 운전 조건
            val judged = rows.filter { it.status != ConditionStatus.NOT_JUDGED }
            if (judged.isNotEmpty()) {
                val violated = judged.filter { it.violations > 0 }
                if (violated.isEmpty()) {
                    add(ReportFinding("${judged.joinToString(", ") { it.condition.label }} 조건을 ${weeks}주 동안 모두 지켰어요.", SummaryTone.GOOD))
                } else {
                    violated.forEach { add(ReportFinding("${it.condition.label} 조건을 ${it.violations}번 지키지 못했어요.", SummaryTone.WARN)) }
                }
            }

            // 휴식 권고
            if (t.restAdviceCount > 0) {
                add(
                    ReportFinding(
                        "휴식 권고 ${t.restAdviceCount}번 중 ${t.restRespondedCount}번 답하셨어요.",
                        if (t.restRespondedCount >= t.restAdviceCount) SummaryTone.INFO else SummaryTone.WARN,
                    ),
                )
            }

            // 출발 전 반응
            if (t.preDriveDone > 0) {
                add(
                    if (t.preDriveSlower == 0) ReportFinding("출발 전 반응은 늘 평소와 비슷했어요.", SummaryTone.GOOD)
                    else ReportFinding("출발 전 반응이 평소보다 느렸던 날은 ${t.preDriveSlower}번이었어요.", SummaryTone.WARN),
                )
            }
        }

        private fun dataNotes(t: TripPeriodStats, rows: List<ConditionRow>, conditions: DriverConditions?): List<String> = buildList {
            if (conditions == null || conditions.conditions.isEmpty()) {
                add("운전 조건이 설정되어 있지 않아 세 가지 조건을 모두 참고로 보여드려요.")
            }
            if (t.isDemo) add("이 리포트는 시연용 가상 기록으로 만들었어요.")
            if (rows.any { it.status == ConditionStatus.NOT_JUDGED }) {
                add("\"판정 안 함\" 조건은 아직 판정 기능이 연결되지 않았어요.")
            }
            if (t.hasMock || rows.any { it.isMock }) {
                add("\"임시\" 표시 값은 다른 기능과 연결되기 전의 예시 값이에요.")
            }
            add("피로 점수는 시속 60km 이상으로 곧게 달린 구간의 조향 패턴으로 계산해요(0~100, 낮을수록 좋아요).")
            add("위치와 경로는 기록하지 않아요.")
        }
    }
}
