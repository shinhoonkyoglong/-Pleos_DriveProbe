package ai.pleos.playground.sample.vehicle.trip.report

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceStatus
import ai.pleos.playground.sample.vehicle.trip.source.DataSource
import ai.pleos.playground.sample.vehicle.trip.source.Violation
import java.util.TimeZone
import kotlin.random.Random

/**
 * 시연용 **가상 주행 기록** 생성기 — 가족 요약·운전 리포트를 보여주려면 여러 주 기록이 필요해서 만든다.
 *
 * - 모든 기록은 isDemo = true, tripId는 [ID_PREFIX]로 시작(이것만 골라 지울 수 있게)
 * - 같은 seed면 항상 같은 기록(시연을 몇 번 해도 같은 화면)
 * - 시나리오: 주 3~5번 낮 운전, 초반 2주에 피로 신호·야간 운전 1번, 뒤로 갈수록 피로 점수가 조금 낮아짐
 *
 * @param integrated false(기본) = 지금 앱이 실제로 만드는 기록과 같은 출처(휴식 권고·조건 판정 MOCK)
 *                   true = 다른 파트가 모두 연동된 뒤의 모습(출처 REAL, 고속도로·반경 조건도 판정)
 */
object DemoHistoryGenerator {

    const val ID_PREFIX = "demo-history-"

    fun generate(
        now: Long,
        weeks: Int = 8,
        seed: Int = 7,
        integrated: Boolean = false,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<TripRecordEntity> {
        val random = Random(seed)
        val records = mutableListOf<TripRecordEntity>()
        val firstDay = periodStart(now, weeks * 7, zone) // 리포트 기간 첫날 0시
        val source = if (integrated) DataSource.REAL.name else DataSource.MOCK.name
        var index = 0

        for (week in 0 until weeks) {
            val tripsThisWeek = 3 + random.nextInt(3) // 3~5번
            val days = (0 until 7).shuffled(random).take(tripsThisWeek).sorted()
            for (day in days) {
                val dayStart = addDays(firstDay, week * 7 + day, zone)
                // 이번 주의 마지막 운전 중 하나만 야간(2주차), 나머지는 낮 9~16시 출발
                val night = week == 1 && day == days.last()
                val startHour = if (night) 19 else 9 + random.nextInt(8)
                val startMinute = if (night) 40 else random.nextInt(60)
                val startedAt = dayStart + (startHour * 60L + startMinute) * 60_000
                val durationMin = 15 + random.nextInt(56) // 15~70분
                val endedAt = startedAt + durationMin * 60_000L
                if (endedAt >= now) continue

                // 피로: 주가 지날수록 평균이 조금씩 낮아짐(35 → 24점 근처)
                val base = 35f - week * 1.5f
                val avg = (base + random.nextInt(-6, 7)).coerceIn(10f, 60f)
                val judged = random.nextInt(10) < 8 // 80%는 판정 구간이 있음
                val fatigueSpike = judged && ((week == 0 && day == days.first()) || (week == 2 && day == days[1]))
                val maxScore = if (fatigueSpike) 62f + random.nextInt(10) else (avg + 8 + random.nextInt(10)).coerceAtMost(48f)
                val maxLevel = when {
                    !judged -> FatigueLevel.NORMAL
                    fatigueSpike -> FatigueLevel.CAUTION
                    maxScore >= 40f -> FatigueLevel.MILD
                    else -> FatigueLevel.NORMAL
                }
                val longDrive = durationMin >= 60
                val reasons = buildList {
                    if (fatigueSpike) add(ReasonCode.MICRO_CORRECTION_DROP.name)
                    if (longDrive) add(ReasonCode.LONG_DRIVE.name)
                }
                val restCount = if (fatigueSpike) 1 else 0

                val slower = (week == 0 && day == days.last()) || (week == 3 && day == days.first())
                val violations = buildList {
                    if (night) add(Violation.NIGHT_DRIVING)
                }

                records += TripRecordEntity(
                    tripId = ID_PREFIX + (index++),
                    startedAt = startedAt,
                    endedAt = endedAt,
                    durationMs = endedAt - startedAt,
                    maxLevel = maxLevel.name,
                    mildEntries = if (maxLevel >= FatigueLevel.MILD) 1 else 0,
                    cautionEntries = if (maxLevel >= FatigueLevel.CAUTION) 1 else 0,
                    dangerEntries = 0,
                    reasonCodes = reasons.joinToString(","),
                    observedMs = endedAt - startedAt,
                    judgedMs = if (judged) (durationMin * 60_000L * (40 + random.nextInt(40)) / 100) else 0L,
                    maxFatigueScore = if (judged) maxScore else 0f,
                    avgFatigueScore = if (judged) avg else null,
                    maxDrivingMinutes = durationMin.toFloat(),
                    isCalibrating = week == 0,
                    preDriveVerdict = (if (slower) ReactionVerdict.SLOWER else ReactionVerdict.NORMAL).name,
                    preDriveSource = source,
                    restAdviceCount = restCount,
                    restRespondedCount = restCount,
                    restAdviceSource = source,
                    compliance = (if (violations.isEmpty()) ComplianceStatus.COMPLIED else ComplianceStatus.VIOLATED).name,
                    violations = violations.joinToString(",") { it.name },
                    complianceSource = source,
                    isDemo = true,
                    isSynced = true, // 서버로 보내지 않는다
                )
            }
        }
        return records
    }
}
