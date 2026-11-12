package ai.pleos.playground.sample.vehicle.trip.report

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceStatus
import ai.pleos.playground.sample.vehicle.trip.source.DataSource
import ai.pleos.playground.sample.vehicle.trip.source.Violation
import java.util.Calendar
import java.util.TimeZone

/**
 * 기간 하나(한 주, 8주 등)의 주행 기록 집계. 가족 안심 요약·운전 리포트가 함께 쓴다(순수 로직).
 *
 * 위치는 기록에 없으므로 여기에도 없다. 값은 모두 주행 기록([TripRecordEntity])의 집계값이다.
 *
 * @param from, to           집계 기간 [from, to) (벽시계 ms, 주행 종료 시각 기준)
 * @param judgedTripCount    피로를 한 번이라도 판정한 주행 수(judgedMs > 0)
 * @param avgFatigueScore    판정 시간으로 가중한 평균 피로 점수(0~100). 판정이 없으면 null
 * @param fatigueSignalTrips 피로 단계가 CAUTION 이상으로 올라간 주행 수
 * @param longDriveTrips     오래 쉬지 않고 운전한(LONG_DRIVE) 주행 수
 * @param compliedTrips      조건을 모두 지킨 주행 수(판정 결과가 있는 주행만)
 * @param violationCounts    어긴 조건별 횟수
 * @param preDriveDone       출발 전 반응 체크 결과가 있는 주행 수
 * @param preDriveSlower     그중 평소보다 느림(SLOWER·MUCH_SLOWER)
 * @param restMock, complianceMock, preDriveMock  기간 안에 임시값(MOCK) 기록이 하나라도 있으면 true
 * @param isDemo             기록이 있고 모두 시연 기록이면 true
 */
data class TripPeriodStats(
    val from: Long,
    val to: Long,
    val tripCount: Int,
    val totalDurationMs: Long,
    val judgedTripCount: Int,
    val avgFatigueScore: Float?,
    val maxFatigueScore: Float,
    val fatigueSignalTrips: Int,
    val longDriveTrips: Int,
    val restAdviceCount: Int,
    val restRespondedCount: Int,
    val compliedTrips: Int,
    val violatedTrips: Int,
    val violationCounts: Map<Violation, Int>,
    val preDriveDone: Int,
    val preDriveSlower: Int,
    val restMock: Boolean,
    val complianceMock: Boolean,
    val preDriveMock: Boolean,
    val isDemo: Boolean,
) {
    /** 조건 판정 결과가 있는 주행 수 */
    val complianceJudged: Int get() = compliedTrips + violatedTrips

    /** 조건 준수율(0~1). 판정 결과가 없으면 null */
    val complianceRate: Float? get() = if (complianceJudged == 0) null else compliedTrips.toFloat() / complianceJudged

    val hasMock: Boolean get() = restMock || complianceMock || preDriveMock

    companion object {

        /** [records] 중 종료 시각이 [from, to)인 것만 집계한다 */
        fun of(records: List<TripRecordEntity>, from: Long, to: Long): TripPeriodStats {
            val rs = records.filter { it.endedAt in from until to }
            val judged = rs.filter { it.judgedMs > 0 && it.avgFatigueScore != null }
            val judgedMsSum = judged.sumOf { it.judgedMs }
            val avgFatigue = if (judgedMsSum <= 0L) null
            else (judged.sumOf { it.avgFatigueScore!!.toDouble() * it.judgedMs } / judgedMsSum).toFloat()

            val compliances = rs.map { enumOrNull<ComplianceStatus>(it.compliance) ?: ComplianceStatus.UNKNOWN }
            val violations = rs
                .filter { it.compliance == ComplianceStatus.VIOLATED.name }
                .flatMap { r -> r.violations.split(',').mapNotNull { enumOrNull<Violation>(it.trim()) } }
            val verdicts = rs.mapNotNull { r -> r.preDriveVerdict?.let { enumOrNull<ReactionVerdict>(it) } }

            return TripPeriodStats(
                from = from,
                to = to,
                tripCount = rs.size,
                totalDurationMs = rs.sumOf { it.durationMs },
                judgedTripCount = rs.count { it.judgedMs > 0 },
                avgFatigueScore = avgFatigue,
                maxFatigueScore = rs.filter { it.judgedMs > 0 }.maxOfOrNull { it.maxFatigueScore } ?: 0f,
                fatigueSignalTrips = rs.count { (enumOrNull<FatigueLevel>(it.maxLevel) ?: FatigueLevel.NORMAL) >= FatigueLevel.CAUTION },
                longDriveTrips = rs.count { ReasonCode.LONG_DRIVE.name in it.reasonCodes.split(',') },
                restAdviceCount = rs.sumOf { it.restAdviceCount },
                restRespondedCount = rs.sumOf { it.restRespondedCount },
                compliedTrips = compliances.count { it == ComplianceStatus.COMPLIED },
                violatedTrips = compliances.count { it == ComplianceStatus.VIOLATED },
                violationCounts = violations.groupingBy { it }.eachCount(),
                preDriveDone = verdicts.count { it != ReactionVerdict.INCONCLUSIVE },
                preDriveSlower = verdicts.count { it == ReactionVerdict.SLOWER || it == ReactionVerdict.MUCH_SLOWER },
                restMock = rs.any { it.restAdviceSource == DataSource.MOCK.name },
                complianceMock = rs.any { it.complianceSource == DataSource.MOCK.name },
                preDriveMock = rs.any { it.preDriveVerdict != null && it.preDriveSource == DataSource.MOCK.name },
                isDemo = rs.isNotEmpty() && rs.all { it.isDemo },
            )
        }
    }
}

internal inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

internal const val DAY_MS = 24L * 60 * 60 * 1000
internal const val WEEK_MS = 7 * DAY_MS

/** [timeMs]가 속한 날의 0시(벽시계 ms) */
internal fun startOfDay(timeMs: Long, zone: TimeZone): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** 달력으로 [days]일 더한 시각. 서머타임이 있는 시간대에서도 0시는 0시로 남는다(24시간 × n으로 더하지 않는다) */
internal fun addDays(timeMs: Long, days: Int, zone: TimeZone): Long =
    Calendar.getInstance(zone).apply { timeInMillis = timeMs; add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

/** 기간 글자(예: "11월 5일 ~ 11월 11일"). [to]는 기간 끝(포함 안 함) */
fun formatPeriod(from: Long, to: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val f = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN).apply { timeZone = zone }
    return "${f.format(java.util.Date(from))} ~ ${f.format(java.util.Date(to - 1))}"
}

/** 오늘을 포함한 최근 [days]일의 시작 시각(예: 7일 → 6일 전 0시). 기간이 날짜 단위로 딱 [days]일이 된다 */
fun periodStart(now: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long =
    addDays(startOfDay(now, zone), -(days - 1), zone)
