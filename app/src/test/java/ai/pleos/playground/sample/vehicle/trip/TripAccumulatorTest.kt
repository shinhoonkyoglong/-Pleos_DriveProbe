package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.condition.core.ConditionReason
import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.CAUTION
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.DANGER
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.MILD
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.NORMAL
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAccumulatorTest {

    private var now = 1_000L
    private val acc = TripAccumulator(clock = { now }, newTripId = { "trip-1" })

    private fun state(level: FatigueLevel, vararg codes: ReasonCode) =
        ConditionState(level, 0f, isCalibrating = false, reasons = codes.map { ConditionReason(it, it.name) })

    @Test
    fun `시작 전 상태와 종료는 무시된다`() {
        acc.onState(state(DANGER))
        assertNull(acc.finish())
        assertFalse(acc.isInTrip)
    }

    @Test
    fun `주행 시간과 최고 단계를 기록한다`() {
        acc.start(isDemo = false)
        listOf(NORMAL, MILD, CAUTION, MILD).forEach { acc.onState(state(it)) }
        now += 30 * 60_000L
        val trip = acc.finish()!!

        assertEquals("trip-1", trip.tripId)
        assertEquals(30 * 60_000L, trip.durationMs)
        assertEquals(CAUTION, trip.maxLevel)
        assertFalse(trip.isDemo)
        assertFalse(acc.isInTrip)
    }

    @Test
    fun `단계 진입은 올라간 순간만 센다`() {
        acc.start(isDemo = false)
        // NORMAL → CAUTION(+1) → CAUTION(그대로) → MILD(내려감) → CAUTION(+1) → DANGER(+1) → MILD → MILD(+0)
        listOf(NORMAL, CAUTION, CAUTION, MILD, CAUTION, DANGER, MILD, MILD).forEach { acc.onState(state(it)) }
        val trip = acc.finish()!!

        assertEquals(0, trip.mildEntries)
        assertEquals(2, trip.cautionEntries)
        assertEquals(1, trip.dangerEntries)
    }

    @Test
    fun `NORMAL에서 MILD로 오르면 MILD 진입 1회`() {
        acc.start(isDemo = false)
        listOf(NORMAL, MILD, NORMAL, MILD).forEach { acc.onState(state(it)) }
        assertEquals(2, acc.finish()!!.mildEntries)
    }

    @Test
    fun `이유 코드는 중복 없이 모은다`() {
        acc.start(isDemo = true)
        acc.onState(state(MILD, ReasonCode.STEERING_JERK))
        acc.onState(state(CAUTION, ReasonCode.STEERING_JERK, ReasonCode.LONG_DRIVE))
        val trip = acc.finish()!!

        assertEquals(setOf(ReasonCode.STEERING_JERK, ReasonCode.LONG_DRIVE), trip.reasonCodes)
        assertTrue(trip.isDemo)
    }

    @Test
    fun `중단하면 저장할 주행이 없다`() {
        acc.start(isDemo = false)
        acc.onState(state(DANGER))
        acc.abort()
        assertNull(acc.finish())
    }

    @Test
    fun `주행 중 다시 시작해도 첫 시작 시각을 유지한다`() {
        acc.start(isDemo = false)
        now += 5_000
        acc.start(isDemo = true)
        val trip = acc.finish()!!
        assertEquals(1_000L, trip.startedAt)
        assertFalse(trip.isDemo)
    }

    private fun window(timeMs: Long, valid: Boolean, score: Float, level: FatigueLevel = NORMAL, minutes: Float = 0f) =
        ConditionState(level, score, isCalibrating = false, lastWindowValid = valid, drivingMinutes = minutes, timeMs = timeMs)

    @Test
    fun `판정 시간과 평균 점수는 상태가 유지된 시간으로 센다`() {
        acc.start(isDemo = false)
        acc.onState(window(10_000, valid = false, score = 0f))               // 10~20초: 판정 없음
        acc.onState(window(20_000, valid = true, score = 0.2f, minutes = 1f)) // 20~30초: 0.2
        acc.onState(window(25_000, valid = true, score = 0.2f, minutes = 1f)) // 0.1초 tick처럼 timeMs만 바뀜
        acc.onState(window(30_000, valid = true, score = 0.6f, minutes = 2f)) // 30~50초: 0.6
        acc.onState(window(50_000, valid = false, score = 0.6f, minutes = 2.5f))
        acc.onState(window(60_000, valid = false, score = 0.6f, minutes = 2.5f))
        val trip = acc.finish()!!

        assertEquals(50_000L, trip.observedMs)
        assertEquals(30_000L, trip.judgedMs)
        assertEquals(0.6f, trip.maxScore, 1e-6f)
        assertEquals((0.2f * 10 + 0.6f * 20) / 30, trip.avgScore!!, 1e-6f)
        assertEquals(2.5f, trip.maxDrivingMinutes, 1e-6f)
        assertFalse(trip.isCalibrating)
    }

    @Test
    fun `판정이 없으면 평균 점수는 null이고 최고 점수는 0`() {
        acc.start(isDemo = false)
        acc.onState(window(10_000, valid = false, score = 0.3f))
        acc.onState(window(20_000, valid = false, score = 0.3f))
        val trip = acc.finish()!!
        assertEquals(0L, trip.judgedMs)
        assertEquals(0f, trip.maxScore, 0f)
        assertNull(trip.avgScore)
    }

    @Test
    fun `시작 순간의 이전 주행 상태는 넣지 않는다`() {
        val stale = window(99_000, valid = true, score = 0.9f, level = DANGER, minutes = 40f)
        acc.start(isDemo = false, current = stale)
        acc.onState(stale)
        acc.onState(window(0, valid = false, score = 0f)) // 엔진이 주행 시작 때 초기화한 상태
        acc.onState(window(100_000, valid = false, score = 0f))
        acc.onState(window(110_000, valid = true, score = 0.1f))
        acc.onState(window(120_000, valid = true, score = 0.1f))
        val trip = acc.finish()!!

        assertEquals(NORMAL, trip.maxLevel)
        assertEquals(0, trip.dangerEntries)
        assertEquals(20_000L, trip.observedMs)
        assertEquals(10_000L, trip.judgedMs)
        assertEquals(0.1f, trip.maxScore, 1e-6f)
        assertEquals(0f, trip.maxDrivingMinutes, 1e-6f)
    }

    @Test
    fun `출발 전 반응 체크 결과를 기록한다`() {
        acc.start(isDemo = false)
        acc.onState(ConditionState(NORMAL, 0f, isCalibrating = true, preDrive = ReactionVerdict.SLOWER, timeMs = 10_000))
        acc.onState(ConditionState(NORMAL, 0f, isCalibrating = true, preDrive = null, timeMs = 20_000))
        val trip = acc.finish()!!
        assertEquals(ReactionVerdict.SLOWER, trip.preDrive)
        assertTrue(trip.isCalibrating)
    }
}
