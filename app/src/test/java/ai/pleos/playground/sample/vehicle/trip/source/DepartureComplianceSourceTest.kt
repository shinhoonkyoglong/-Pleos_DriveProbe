package ai.pleos.playground.sample.vehicle.trip.source

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.trip.CompletedTrip
import ai.pleos.playground.sample.vehicle.trip.TripRecorder
import ai.pleos.playground.sample.vehicle.trip.report.ConditionStatus
import ai.pleos.playground.sample.vehicle.trip.report.DrivingReport
import ai.pleos.playground.sample.vehicle.trip.report.ReportCondition
import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.TripCompliance
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.usecase.TripComplianceUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2번 판정(실제 TripComplianceUseCase, 순수 계산) → 5번 저장값 대응. README 2.0.2 표 그대로 */
class DepartureComplianceSourceTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val useCase = TripComplianceUseCase(DepartureConfig())
    private val home = GeoPoint(latitude = 37.5665, longitude = 126.9780)

    /** 서울 2026-11-12 [hour]:[minute] (일몰 약 17:25) */
    private fun at(hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 11, 12, hour, minute, 0, 0, seoul).toInstant()

    private fun trip(from: Instant, to: Instant) = CompletedTrip(
        tripId = "t", startedAt = from.toEpochMilli(), endedAt = to.toEpochMilli(),
        maxLevel = FatigueLevel.NORMAL, mildEntries = 0, cautionEntries = 0, dangerEntries = 0,
        reasonCodes = emptySet(), isDemo = false,
    )

    /** 운전자 조건 [condition]으로 판정하는 5번 출처(앱에서는 departureFeature가 저장된 조건을 읽는다) */
    private fun source(condition: DriverCondition?) =
        DepartureComplianceSource { from, to -> DataResult.Available(useCase.evaluate(condition, from, to)) }

    private val nightOnly = DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING))
    private val allThree = DriverCondition(
        timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
        routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY),
        radiusCenter = home,
    )

    @Test
    fun `낮 주행은 지킴, 판정한 조건은 야간만`() = runBlocking {
        val r = source(allThree).evaluate(trip(at(10), at(11)))
        assertEquals(ComplianceStatus.COMPLIED, r.status)
        assertEquals(emptyList<Violation>(), r.violations)
        assertEquals(setOf(Violation.NIGHT_DRIVING), r.judged) // 고속도로·반경은 NOT_JUDGED라 빠진다
        assertEquals(DataSource.REAL, r.source)
    }

    @Test
    fun `일몰 뒤에 걸친 주행은 야간 운전 위반`() = runBlocking {
        val r = source(nightOnly).evaluate(trip(at(17), at(18)))
        assertEquals(ComplianceStatus.VIOLATED, r.status)
        assertEquals(listOf(Violation.NIGHT_DRIVING), r.violations)
        assertEquals(setOf(Violation.NIGHT_DRIVING), r.judged)
    }

    @Test
    fun `야간 조건이 없는 운전자는 밤에 달려도 위반이 아니다(조건 없음)`() = runBlocking {
        // PR #6 리뷰 MAJOR-1: 예전 임시 판정은 이 경우를 20~6시 위반으로 기록했다
        assertEquals(ComplianceStatus.NO_CONDITION, source(null).evaluate(trip(at(21), at(22))).status)
        assertEquals(ComplianceStatus.NO_CONDITION, source(DriverCondition()).evaluate(trip(at(21), at(22))).status)
    }

    @Test
    fun `고속도로·반경만 있으면 판정할 자료가 없어 확인 못 함`() = runBlocking {
        val c = DriverCondition(routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY), radiusCenter = home)
        val r = source(c).evaluate(trip(at(21), at(22)))
        assertEquals(ComplianceStatus.UNKNOWN, r.status)
        assertEquals(emptySet<Violation>(), r.judged)
    }

    @Test
    fun `조건을 읽지 못하면 확인 못 함`() {
        val r = DepartureComplianceSource.toResult(DataResult.Unavailable(UnavailableReason.SERVICE_ERROR))
        assertEquals(ComplianceResult(ComplianceStatus.UNKNOWN, emptyList(), DataSource.REAL), r)
    }

    @Test
    fun `종료 시각이 시작보다 앞서도 판정이 터지지 않는다`() = runBlocking {
        var received: Pair<Instant, Instant>? = null
        val s = DepartureComplianceSource { from, to ->
            received = from to to
            DataResult.Available(useCase.evaluate(nightOnly, from, to))
        }
        s.evaluate(trip(at(11), at(10)))
        assertEquals(at(11) to at(11), received)
    }

    @Test
    fun `리포트는 판정한 조건만 센다 - 고속도로·반경은 판정 안 함`() = runBlocking {
        val day = TripRecorder.buildRecord(
            trip(at(10), at(11)).copy(tripId = "day"), RestAdvice(0, 0, DataSource.REAL),
            source(allThree).evaluate(trip(at(10), at(11))), PreDriveCheck(null, DataSource.REAL),
        )
        val night = TripRecorder.buildRecord(
            trip(at(17), at(18)).copy(tripId = "night"), RestAdvice(0, 0, DataSource.REAL),
            source(allThree).evaluate(trip(at(17), at(18))), PreDriveCheck(null, DataSource.REAL),
        )
        val records = listOf(day, night)
        val nightRow = DrivingReport.conditionRow(ReportCondition.NO_NIGHT_DRIVING, records)
        assertEquals(ConditionStatus.VIOLATED, nightRow.status)
        assertEquals(2, nightRow.judgedTrips)
        assertEquals(1, nightRow.violations)
        assertEquals(false, nightRow.isMock)
        assertEquals(ConditionStatus.NOT_JUDGED, DrivingReport.conditionRow(ReportCondition.AVOID_HIGHWAY, records).status)
        assertEquals(ConditionStatus.NOT_JUDGED, DrivingReport.conditionRow(ReportCondition.RADIUS, records).status)
    }
}
