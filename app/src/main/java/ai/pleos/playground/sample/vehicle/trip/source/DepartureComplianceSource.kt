package ai.pleos.playground.sample.vehicle.trip.source

import ai.pleos.playground.sample.vehicle.trip.CompletedTrip
import android.util.Log
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.TripCompliance
import com.pleos.departure.model.TripConditionKind
import com.pleos.departure.model.TripConditionStatus
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * 운전 조건 준수 — 2번 판정 함수(`DepartureFeature.evaluateTripCompliance`, core 0.11.0)를 **부르기만** 한다.
 * 사용법·규칙: docs/departure/README.md 2.0.2, docs/pleos/integration-guide.md "5번 연동 — 주행 조건 준수 판정".
 *
 * - 판정 기준은 주행이 끝난 순간 **지금 저장된** 운전자 조건이다. 끝날 때 한 번 판정해 기록에 저장한다.
 * - 위치는 넘기지 않는다(5번은 GPS를 저장하지 않음) → 2번이 집(반경 기준점) → 서울시청 순으로 일몰을 계산한다.
 * - 지금 2번은 야간만 판정한다. 고속도로·반경은 NOT_JUDGED → [ComplianceResult.judged]에서 빠진다.
 *
 * @param evaluate 2번 함수. 앱에서는 `departureFeature::evaluateTripCompliance`, 테스트에서는 가짜 함수
 */
class DepartureComplianceSource(
    private val evaluate: suspend (startedAt: Instant, endedAt: Instant) -> DataResult<TripCompliance>,
) : ComplianceSource {

    override suspend fun evaluate(trip: CompletedTrip): ComplianceResult {
        val result = try {
            evaluate(Instant.ofEpochMilli(trip.startedAt), Instant.ofEpochMilli(maxOf(trip.startedAt, trip.endedAt)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 판정 실패가 주행 기록 저장을 막지 않게 '확인 못 함'으로 남긴다
            Log.w(TAG, "trip compliance failed tripId=${trip.tripId}", e)
            return ComplianceResult(ComplianceStatus.UNKNOWN, emptyList(), DataSource.REAL)
        }
        return toResult(result)
    }

    companion object {
        private const val TAG = "Trip"

        /**
         * 2번 결과 → 5번 저장값(README 2.0.2 대응표).
         * - Unavailable(조건 조회 실패) → UNKNOWN
         * - VIOLATED → VIOLATED + 어긴 조건
         * - KEPT → COMPLIED(판정한 조건만 [ComplianceResult.judged]에. 화면은 "모두 지킴"이라 쓰지 않는다)
         * - NOT_JUDGED(조건은 있지만 판정할 자료 없음) → UNKNOWN
         * - NOT_APPLICABLE(조건 없음, 야간 조건 꺼짐 포함) → NO_CONDITION
         */
        fun toResult(result: DataResult<TripCompliance>): ComplianceResult {
            val c = when (result) {
                is DataResult.Available -> result.value
                is DataResult.Unavailable -> return ComplianceResult(ComplianceStatus.UNKNOWN, emptyList(), DataSource.REAL)
            }
            val judged = buildSet {
                if (c.night.isJudged()) add(Violation.NIGHT_DRIVING)
                if (c.highway.isJudged()) add(Violation.HIGHWAY)
                if (c.radius.isJudged()) add(Violation.OUT_OF_RADIUS)
            }
            val status = when (c.overall) {
                TripConditionStatus.VIOLATED -> ComplianceStatus.VIOLATED
                TripConditionStatus.KEPT -> ComplianceStatus.COMPLIED
                TripConditionStatus.NOT_JUDGED -> ComplianceStatus.UNKNOWN
                TripConditionStatus.NOT_APPLICABLE -> ComplianceStatus.NO_CONDITION
            }
            val violations = c.violations.map { it.toViolation() }.sortedBy { it.ordinal }
            return ComplianceResult(status, violations, DataSource.REAL, judged)
        }

        private fun TripConditionStatus.isJudged() = this == TripConditionStatus.KEPT || this == TripConditionStatus.VIOLATED

        private fun TripConditionKind.toViolation(): Violation = when (this) {
            TripConditionKind.NO_NIGHT_DRIVING -> Violation.NIGHT_DRIVING
            TripConditionKind.AVOID_HIGHWAY -> Violation.HIGHWAY
            TripConditionKind.RADIUS -> Violation.OUT_OF_RADIUS
        }
    }
}
