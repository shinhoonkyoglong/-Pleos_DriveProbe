package ai.pleos.playground.sample.vehicle.trip.source

import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.trip.CompletedTrip
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import java.util.Calendar
import java.util.TimeZone

/*
 * 주행 종료 요약에 들어가는 값 중 **다른 파트가 아직 주지 않는 값**의 출처.
 *
 * 각 값은 출처(DataSource)와 함께 저장된다. MOCK이면 요약·가족 요약·운전 리포트에 "임시" 표시가 붙는다.
 * (연동 전이라 지금은 보여주되 표시한다. 모든 파트가 연동되면 리포트 판단에서 MOCK 기록을 뺄지 다시 정한다)
 * 실제 연동은 팀원이 제공하는 방식(함수·DB·Flow)을 그대로 따른다 — 아래 TODO 참고.
 */

/** 값의 출처. MOCK = 임시값(연동 전), REAL = 다른 파트가 준 실제 값 */
enum class DataSource { MOCK, REAL }

/**
 * 주행 1회의 운전 조건 판정.
 * - COMPLIED: 판정한 조건을 모두 지킴(판정하지 못한 조건이 있을 수 있다 — [ComplianceResult.judged])
 * - VIOLATED: 어긴 조건이 있음
 * - UNKNOWN: 조건은 있지만 판정할 자료가 없거나, 조건을 읽지 못함
 * - NO_CONDITION: 운전자가 설정한 조건이 없음(야간 조건이 꺼진 경우 포함)
 * 준수율은 COMPLIED·VIOLATED만 센다(UNKNOWN·NO_CONDITION은 분모에서 빠짐).
 */
enum class ComplianceStatus { COMPLIED, VIOLATED, UNKNOWN, NO_CONDITION }

/** 어긴 조건 코드 */
enum class Violation { NIGHT_DRIVING, HIGHWAY, OUT_OF_RADIUS }

/**
 * 휴식 권고 = 주행 중 **DANGER** 개입(4번 쉼터 안내). CAUTION 음성 질문은 세지 않는다(팀 기준, '내 기록' 화면과 같음).
 * @param count          휴식 권고 횟수
 * @param respondedCount 그중 운전자가 음성으로 답한 횟수
 */
data class RestAdvice(val count: Int, val respondedCount: Int, val source: DataSource)

/**
 * @param violations 어긴 조건
 * @param judged     실제로 판정한 조건(지켰든 어겼든). 판정하지 못한 조건은 빠진다 → 화면에서 "모두 지킴"이라고 단정하지 않는다
 */
data class ComplianceResult(
    val status: ComplianceStatus,
    val violations: List<Violation>,
    val source: DataSource,
    val judged: Set<Violation> = emptySet(),
)

/** 출발 전 반응 체크 결과(null = 하지 않음) */
data class PreDriveCheck(val verdict: ReactionVerdict?, val source: DataSource)

/**
 * 휴식 권고 횟수 출처.
 * 실제 앱은 [InterventionRestAdviceSource]로 sessionId == tripId인 DANGER 이력을 센다.
 * simulation=false가 명시된 자동 개입만 포함하고, 응답은 비어 있지 않은 인식 문장으로 센다.
 * 동의·거절·경로 변경 결과는 응답 여부와 별개로 원본 이력에 유지한다.
 */
fun interface RestAdviceSource {
    suspend fun countFor(trip: CompletedTrip): RestAdvice
}

/**
 * 운전 조건 준수 여부 출처.
 * 실제 앱은 [DepartureComplianceSource](2번 `DepartureFeature.evaluateTripCompliance`)를 쓴다.
 */
fun interface ComplianceSource {
    suspend fun evaluate(trip: CompletedTrip): ComplianceResult
}

/**
 * 출발 전 반응 체크 결과 출처.
 * 3번 엔진이 주행에 반영한 값([CompletedTrip.preDrive])을 쓴다. 반응 체크 화면이 결과를 엔진에 넘기고
 * (`ConditionEngine.setPreDrive`), 엔진은 다음 주행 시작 때 반영한다.
 */
fun interface PreDriveSource {
    suspend fun checkFor(trip: CompletedTrip): PreDriveCheck
}

/** 임시(앱 밖 테스트용): DANGER로 올라간 횟수를 휴식 권고 횟수로 보고, 모두 응답했다고 본다 */
object MockRestAdviceSource : RestAdviceSource {
    override suspend fun countFor(trip: CompletedTrip): RestAdvice {
        val count = trip.dangerEntries
        return RestAdvice(count = count, respondedCount = count, source = DataSource.MOCK)
    }
}

/**
 * 임시: 시작·종료 시각이 야간(20시~6시)에 걸리면 야간 운전 조건 위반으로 본다.
 * 실제로는 2번이 일몰 시각과 운전자별 조건으로 판정한다.
 */
class MockComplianceSource(
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val nightStartHour: Int = 20,
    private val nightEndHour: Int = 6,
) : ComplianceSource {

    override suspend fun evaluate(trip: CompletedTrip): ComplianceResult {
        val night = isNight(trip.startedAt) || isNight(trip.endedAt)
        return if (night) {
            ComplianceResult(ComplianceStatus.VIOLATED, listOf(Violation.NIGHT_DRIVING), DataSource.MOCK, EVALUATED)
        } else {
            ComplianceResult(ComplianceStatus.COMPLIED, emptyList(), DataSource.MOCK, EVALUATED)
        }
    }

    private fun isNight(timeMs: Long): Boolean {
        val hour = Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)
        return hour >= nightStartHour || hour < nightEndHour
    }

    companion object {
        /** 임시 판정이 실제로 보는 조건. 나머지(고속도로·반경)는 리포트에서 "판정 안 함"으로 둔다 */
        val EVALUATED: Set<Violation> = setOf(Violation.NIGHT_DRIVING)
    }
}

/**
 * 출발 전 반응: 3번 엔진이 이번 주행에 반영한 값. 없으면 "하지 않음"(null).
 * 반응 체크 화면이 "판단 보류"(INCONCLUSIVE)는 엔진에 넘기지 않으므로 그 경우도 "하지 않음"으로 남는다.
 */
object EnginePreDriveSource : PreDriveSource {
    override suspend fun checkFor(trip: CompletedTrip): PreDriveCheck = PreDriveCheck(trip.preDrive, DataSource.REAL)
}

/** 임시(시연·테스트용): 엔진 값이 있으면 실제 값, 없으면 [mockVerdict](기본 "평소와 비슷") */
class EngineOrMockPreDriveSource(
    private val mockVerdict: ReactionVerdict = ReactionVerdict.NORMAL,
) : PreDriveSource {
    override suspend fun checkFor(trip: CompletedTrip): PreDriveCheck =
        trip.preDrive?.let { PreDriveCheck(it, DataSource.REAL) }
            ?: PreDriveCheck(mockVerdict, DataSource.MOCK)
}

/**
 * 기록 한 건에서 실제로 판정한 조건.
 * [TripRecordEntity.judgedConditions]가 있으면 그것, 없으면(그 칸이 생기기 전 기록) 출처로 추정한다:
 * 임시 판정은 야간만([MockComplianceSource.EVALUATED]), 예전 REAL(시연 '연동 완료' 기록)은 세 조건 모두.
 */
fun judgedConditionsOf(r: TripRecordEntity): Set<Violation> {
    if (r.judgedConditions.isNotBlank()) {
        return r.judgedConditions.split(',').mapNotNull { c -> Violation.entries.firstOrNull { it.name == c.trim() } }.toSet()
    }
    if (r.compliance != ComplianceStatus.COMPLIED.name && r.compliance != ComplianceStatus.VIOLATED.name) return emptySet()
    return if (r.complianceSource == DataSource.MOCK.name) MockComplianceSource.EVALUATED else Violation.entries.toSet()
}
