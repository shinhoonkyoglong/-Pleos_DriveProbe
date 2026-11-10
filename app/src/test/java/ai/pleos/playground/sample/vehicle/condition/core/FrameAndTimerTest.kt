package ai.pleos.playground.sample.vehicle.condition.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAndTimerTest {

    @Test
    fun `서로 다른 시각에 온 신호 4종을 같은 100ms 격자로 묶는다`() {
        val b = FrameBuilder()
        b.onGear(0, Gear.PARK)
        b.onTurnSignal(0, TurnSignal.OFF)
        b.onSteering(30, 1f)
        b.onSpeed(60, 10f)
        b.onSteering(250, -2f)
        b.onGear(270, Gear.DRIVE)
        b.onSpeed(320, 20f)
        val fs = b.advanceTo(400)

        assertEquals(listOf(100L, 200L, 300L, 400L), fs.map { it.timeMs })
        assertEquals(listOf(1f, 1f, -2f, -2f), fs.map { it.steeringDeg })
        assertEquals(listOf(10f, 10f, 10f, 20f), fs.map { it.speedMps })
        assertEquals(listOf(Gear.PARK, Gear.PARK, Gear.DRIVE, Gear.DRIVE), fs.map { it.gear })
        assertTrue(fs.all { it.turnSignal == TurnSignal.OFF && !it.steeringStale && !it.speedStale })
    }

    @Test
    fun `속도를 아직 못 받았으면 null이고 끊김으로 표시`() {
        val b = FrameBuilder()
        b.onSteering(0, 0f)
        val f = b.advanceTo(100).last()
        assertNull(f.speedMps)
        assertTrue(f.speedStale)
        assertNull(f.gear)
    }

    @Test
    fun `조향이 없으면 프레임을 만들지 않는다`() {
        val b = FrameBuilder()
        b.onSpeed(0, 20f)
        assertTrue(b.advanceTo(5_000).isEmpty())
    }

    private fun frame(t: Long, kph: Float, gear: Gear = Gear.DRIVE) =
        SignalFrame(t, 0f, false, kph / 3.6f, false, gear, TurnSignal.OFF)

    @Test
    fun `연속 주행시간은 움직일 때만 늘고 짧은 정차로는 리셋되지 않는다`() {
        val timer = DrivingTimer()
        var t = 0L
        repeat(600 * 5) { timer.add(frame(t, 80f)); t += 100 }        // 5분 주행
        repeat(600 * 2) { timer.add(frame(t, 0f)); t += 100 }         // 2분 신호 대기
        repeat(600 * 3) { timer.add(frame(t, 80f)); t += 100 }        // 3분 주행
        assertEquals(8f, timer.minutes, 1e-3f)
    }

    @Test
    fun `10분 이상 쉬면 연속 주행시간이 0이 된다`() {
        val timer = DrivingTimer()
        var t = 0L
        repeat(600 * 30) { timer.add(frame(t, 80f)); t += 100 }
        repeat(600 * 10) { timer.add(frame(t, 0f, Gear.PARK)); t += 100 }
        assertEquals(0f, timer.minutes, 1e-3f)
        repeat(600) { timer.add(frame(t, 80f)); t += 100 }
        assertEquals(1f, timer.minutes, 1e-3f)
    }
}
