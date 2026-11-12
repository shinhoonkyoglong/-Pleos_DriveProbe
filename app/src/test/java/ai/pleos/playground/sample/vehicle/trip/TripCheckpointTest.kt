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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCheckpointTest {

    private var now = 1_000_000L
    private fun acc() = TripAccumulator(clock = { now }, newTripId = { "trip-1" })

    private fun s(level: FatigueLevel, score: Float, valid: Boolean, timeMs: Long, minutes: Float = 0f, vararg codes: ReasonCode) =
        ConditionState(
            level, score, isCalibrating = false, lastWindowValid = valid, drivingMinutes = minutes,
            preDrive = ReactionVerdict.NORMAL, reasons = codes.map { ConditionReason(it, it.name) }, timeMs = timeMs,
        )

    /** 판정 구간 20초(점수 0.4, 0.6) + 단계 MILD→CAUTION */
    private fun TripAccumulator.drive() {
        start(isDemo = false)
        onState(s(NORMAL, 0f, false, 1_000))
        onState(s(MILD, 0.4f, true, 11_000, minutes = 3f, ReasonCode.MICRO_CORRECTION_DROP))
        onState(s(CAUTION, 0.6f, true, 21_000, minutes = 5f))
        onState(s(CAUTION, 0.6f, true, 31_000, minutes = 6f, ReasonCode.LONG_DRIVE))
    }

    @Test
    fun `중간 저장 글자로 바꿨다가 되돌리면 같다`() {
        val a = acc().apply { drive() }
        val snap = a.snapshot()!!
        assertEquals(snap, TripSnapshot.decode(snap.encode()))
        assertEquals(setOf(ReasonCode.MICRO_CORRECTION_DROP, ReasonCode.LONG_DRIVE), snap.reasonCodes)
        assertEquals(now, snap.savedAt)
    }

    @Test
    fun `깨진 중간 저장은 버린다`() {
        assertNull(TripSnapshot.decode(""))
        assertNull(TripSnapshot.decode("v=1\ntripId=x"))
        assertNull(TripSnapshot.decode("v=99\ntripId=x"))
        val bad = acc().apply { drive() }.snapshot()!!.encode().replace("maxLevel=CAUTION", "maxLevel=WHAT")
        assertNull(TripSnapshot.decode(bad))
    }

    @Test
    fun `되살린 주행을 바로 끝내면 끊기기 전과 같은 결과`() {
        val original = acc().apply { drive() }
        val snap = original.snapshot()!!
        val expected = original.finish(endedAt = snap.savedAt)!!

        now += 5 * 60_000L // 앱이 5분 뒤 다시 켜짐
        val restored = acc().apply { restore(snap) }
        assertEquals(expected, restored.finish(endedAt = snap.savedAt))
    }

    @Test
    fun `되살린 주행에 이어서 집계한다(엔진 시계는 새로 잰다)`() {
        val snap = acc().apply { drive() }.snapshot()!!
        val restored = acc().apply { restore(snap) }
        // 새 엔진: 시계가 0부터 다시 시작
        restored.onState(s(DANGER, 0.8f, true, 500, minutes = 1f))
        restored.onState(s(DANGER, 0.8f, true, 10_500, minutes = 2f))
        val trip = restored.finish()!!

        assertEquals("trip-1", trip.tripId)
        assertEquals(DANGER, trip.maxLevel)
        assertEquals(1, trip.mildEntries)
        assertEquals(1, trip.cautionEntries)
        assertEquals(1, trip.dangerEntries)
        assertEquals(20_000L + 10_000L, trip.judgedMs) // 끊기기 전 20초 + 새 엔진에서 이어서 10초
        assertEquals(6f, trip.maxDrivingMinutes) // 새 엔진의 연속 운전 시간은 0부터라 끊기기 전 최대값 유지
    }

    @Test
    fun `복구 판단 - 주행 중이고 10분 안이면 이어 간다`() {
        val snap = acc().apply { drive() }.snapshot()!!
        assertEquals(RecoveryAction.RESUME, TripRecovery.decide(snap, snap.savedAt + 60_000, driving = true, engineIsDemo = false))
        assertEquals(RecoveryAction.FINALIZE, TripRecovery.decide(snap, snap.savedAt + 60_000, driving = false, engineIsDemo = false))
        assertEquals(
            RecoveryAction.FINALIZE,
            TripRecovery.decide(snap, snap.savedAt + TripRecovery.RESUME_WITHIN_MS + 1, driving = true, engineIsDemo = false),
        )
        assertEquals(RecoveryAction.FINALIZE, TripRecovery.decide(snap, snap.savedAt + 60_000, driving = true, engineIsDemo = true))
        assertEquals(RecoveryAction.FINALIZE, TripRecovery.decide(snap.copy(isDemo = true), snap.savedAt, driving = true, engineIsDemo = true))
        assertEquals(RecoveryAction.FINALIZE, TripRecovery.decide(snap, snap.savedAt - 1, driving = true, engineIsDemo = false)) // 시계가 거꾸로
    }

    @Test
    fun `주행 중이 아니면 중간 저장할 것이 없다`() {
        val a = acc()
        assertNull(a.snapshot())
        a.drive()
        a.finish()
        assertNull(a.snapshot())
        assertTrue(acc().apply { drive() }.snapshot()!!.observedMs > 0)
    }
}
