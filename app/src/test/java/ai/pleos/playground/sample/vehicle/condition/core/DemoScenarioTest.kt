package ai.pleos.playground.sample.vehicle.condition.core

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.CAUTION
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.MILD
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.NORMAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시연 데이터(trip_demo)로 돌렸을 때: 정상 구간은 NORMAL, 졸린 구간은 CAUTION 이상.
 * 정상 주행만 있는 데이터에서는 MILD를 넘지 않음(오경보 확인).
 */
class DemoScenarioTest {

    companion object {
        val trip: ReplayTrip by lazy { DemoTripGenerator.tripDemo() }
        val seed: List<TripSummary> by lazy { DemoTripGenerator.baselineSeed() }
        const val MAX_DELAY_TO_CAUTION_MS = 70_000L
    }

    private fun seg(name: String) = trip.segments.first { it.name == name }

    private fun assertDemoLevels(points: List<ReplayPoint>) {
        val normal = seg("정상 주행")
        val drowsy = seg("졸린 주행")

        val normalPts = points.filter { it.timeMs in normal.startMs until normal.endMs && it.window.isValid }
        assertTrue("정상 구간 유효 창 수=${normalPts.size}", normalPts.size >= 5)
        normalPts.forEach { assertEquals("정상 구간 t=${it.timeMs}", NORMAL, it.state.level) }

        val drowsyPts = points.filter { it.timeMs in drowsy.startMs..drowsy.endMs }
        val first = drowsyPts.firstOrNull { it.state.level >= CAUTION }
        assertNotNull("졸린 구간에서 CAUTION 이상이 나와야 함", first)
        val delay = first!!.timeMs - drowsy.startMs
        println("졸린 구간 시작 후 CAUTION까지 ${delay / 1000.0}초, 마지막 단계=${drowsyPts.last().state.level}")
        assertTrue("CAUTION까지 ${delay}ms", delay <= MAX_DELAY_TO_CAUTION_MS)
        assertTrue("졸린 구간 끝 단계=${drowsyPts.last().state.level}", drowsyPts.last().state.level >= CAUTION)
    }

    @Test
    fun `기본 기준선(기록 없음) - 정상 구간 NORMAL, 졸린 구간 CAUTION 이상`() {
        val points = ReplayRunner.run(trip, ConditionProcessor(InMemoryBaselineStore()))
        println("=== trip_demo / 기본 기준선(isCalibrating=true) ===")
        println(DemoDiagnostics.table(trip, points))
        assertTrue(points.all { it.state.isCalibrating })
        assertDemoLevels(points)
    }

    @Test
    fun `미리 넣은 평소 기록(baseline_seed) - 개인 기준선으로도 같은 결과`() {
        assertEquals(6, seed.size)
        val points = ReplayRunner.run(trip, ConditionProcessor(InMemoryBaselineStore(seed)))
        println("=== trip_demo / 평소 기록 시드(isCalibrating=false) ===")
        println(DemoDiagnostics.table(trip, points))
        assertTrue(points.none { it.state.isCalibrating })
        assertDemoLevels(points)
    }

    @Test
    fun `졸린 구간의 이유에 급조향과 큰 수정 증가가 들어간다`() {
        val points = ReplayRunner.run(trip, ConditionProcessor(InMemoryBaselineStore()))
        val codes = points.last().state.reasons.map { it.code }
        assertTrue(codes.toString(), ReasonCode.STEERING_JERK in codes)
        assertTrue(codes.toString(), ReasonCode.LARGE_CORRECTION_RISE in codes)
        println("마지막 이유: " + points.last().state.reasons.joinToString(" / ") { it.message })
    }

    @Test
    fun `정차와 가속 구간(60km per h 미만)은 점수를 계산하지 않는다`() {
        val points = ReplayRunner.run(trip, ConditionProcessor(InMemoryBaselineStore()))
        val early = points.filter { it.timeMs < 75_000 }
        assertTrue(early.isNotEmpty())
        early.forEach { assertFalse("t=${it.timeMs}", it.window.isValid) }
    }

    @Test
    fun `오경보 확인 - 정상 주행만 10분씩 10회, 기본·개인 기준선 모두 MILD를 넘지 않는다`() {
        var normalWindows = 0
        var mildWindows = 0
        for (s in 101L..110L) {
            val t = DemoTripGenerator.normalTrip(s, 10)
            for (store in listOf(InMemoryBaselineStore(), InMemoryBaselineStore(seed))) {
                val pts = ReplayRunner.run(t, ConditionProcessor(store))
                val max = pts.maxOf { it.state.level }
                assertTrue("seed=$s max=$max", max <= MILD)
                normalWindows += pts.count { it.state.level == NORMAL }
                mildWindows += pts.count { it.state.level == MILD }
            }
        }
        println("오경보 확인: 창 ${normalWindows + mildWindows}개 중 NORMAL=$normalWindows, MILD=$mildWindows, CAUTION 이상=0")
    }

    @Test
    fun `같은 시드면 항상 같은 데이터가 나온다`() {
        assertEquals(DemoTripGenerator.tripDemo(), DemoTripGenerator.tripDemo())
        assertFalse(DemoTripGenerator.tripDemo(1) == DemoTripGenerator.tripDemo(2))
    }
}
