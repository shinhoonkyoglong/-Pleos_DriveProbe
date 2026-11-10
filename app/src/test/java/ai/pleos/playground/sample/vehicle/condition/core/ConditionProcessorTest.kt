package ai.pleos.playground.sample.vehicle.condition.core

import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.sine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionProcessorTest {

    /** 조향 배열을 실제 콜백처럼 processor에 넣는다(조향 10Hz, 속도 5Hz, 기어·방향지시등은 처음 한 번) */
    private fun feed(p: ConditionProcessor, steering: FloatArray, startMs: Long = 0L, speedKph: Float = 80f): ConditionState {
        p.onGear(startMs, Gear.DRIVE)
        p.onTurnSignal(startMs, TurnSignal.OFF)
        var last = p.state
        for (i in steering.indices) {
            val t = startMs + i * 100L
            p.onSteering(t, steering[i])
            if (i % 2 == 0) p.onSpeed(t, speedKph / 3.6f)
            if (i % 10 == 9) last = p.tick(t, hourOfDay = 11)
        }
        return last
    }

    @Test
    fun `처음 60초 동안은 창이 없어 lastWindowValid=false, 이후 정상 주행은 NORMAL`() {
        val p = ConditionProcessor(InMemoryBaselineStore())
        val early = feed(p, sine(30, 2f, 3f))
        assertFalse(early.lastWindowValid)
        assertTrue(early.isCalibrating)

        val later = feed(p, sine(120, 2f, 3f), startMs = 30_000)
        assertTrue(later.lastWindowValid)
        assertEquals(FatigueLevel.NORMAL, later.level)
        assertTrue("drivingMinutes=${later.drivingMinutes}", later.drivingMinutes > 2.4f)
    }

    @Test
    fun `저속 주행에서는 점수를 계산하지 않는다`() {
        val p = ConditionProcessor(InMemoryBaselineStore())
        val s = feed(p, sine(90, 2f, 3f), speedKph = 40f)
        assertFalse(s.lastWindowValid)
        assertEquals(InvalidReason.SPEED_LOW, p.lastWindow?.invalidReason)
        assertEquals(0f, s.score)
    }

    @Test
    fun `주행 종료 시 경고가 없었으면 기준선에 반영된다`() {
        val store = InMemoryBaselineStore()
        val p = ConditionProcessor(store)
        feed(p, sine(120, 2f, 3f))
        val r = p.endTrip(nowMs = 120_000)
        assertNotNull(r.summary)
        assertTrue(r.baselineUpdated)
        assertEquals(1, store.load().size)
    }

    @Test
    fun `출발 전 반응 체크 결과가 상태에 실린다`() {
        val p = ConditionProcessor(InMemoryBaselineStore())
        p.setPreDrive(ReactionVerdict.SLOWER)
        assertEquals(ReactionVerdict.SLOWER, p.state.preDrive)
        p.startTrip()
        assertEquals(null, p.state.preDrive)
    }
}
