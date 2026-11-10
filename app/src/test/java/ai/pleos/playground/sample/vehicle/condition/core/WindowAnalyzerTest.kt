package ai.pleos.playground.sample.vehicle.condition.core

import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.frames
import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.sine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowAnalyzerTest {

    private fun run(fs: List<SignalFrame>): List<WindowResult> {
        val a = WindowAnalyzer()
        return fs.mapNotNull { a.add(it) }
    }

    @Test
    fun `창은 60초가 찬 뒤 처음 나오고 이후 10초마다 나온다`() {
        val results = run(frames(sine(90, 2f, 2f)))
        // 60, 70, 80, 90초 → 4개
        assertEquals(4, results.size)
        assertEquals(listOf(59_900L, 69_900L, 79_900L, 89_900L), results.map { it.endTimeMs })
    }

    @Test
    fun `고속 직진 정상 주행은 유효하고 특징이 계산된다`() {
        val r = run(frames(sine(60, 2f, 2f))).single()
        assertTrue(r.isValid)
        assertNull(r.invalidReason)
        val f = assertNotNullAndGet(r.features)
        assertTrue("small=${f.reversalsSmallPerMin}", f.reversalsSmallPerMin in 57f..60f)
    }

    @Test
    fun `60km per h 미만이면 무효(SPEED_LOW)`() {
        val r = run(frames(sine(60, 2f, 2f), speedKph = 50f)).single()
        assertFalse(r.isValid)
        assertEquals(InvalidReason.SPEED_LOW, r.invalidReason)
    }

    @Test
    fun `콘솔 60km per h(16_666668 m per s)는 유효`() {
        val r = run(frames(sine(60, 2f, 2f), speedKph = 16.666668f * 3.6f)).single()
        assertTrue(r.isValid)
    }

    @Test
    fun `기어가 D가 아니면 무효`() {
        val r = run(frames(sine(60, 2f, 2f), gear = Gear.NEUTRAL)).single()
        assertEquals(InvalidReason.GEAR_NOT_DRIVE, r.invalidReason)
    }

    @Test
    fun `방향지시등을 오래 켜면 무효`() {
        val r = run(frames(sine(60, 2f, 2f), turn = { if (it < 200) TurnSignal.LEFT else TurnSignal.OFF })).single()
        assertFalse(r.isValid)
        assertEquals(InvalidReason.TURN_SIGNAL_ON, r.invalidReason)
    }

    @Test
    fun `방향지시등을 잠깐(1초) 켜면 그 1초와 꺼진 뒤 1초만 빠지고 창은 유효`() {
        val r = run(frames(sine(60, 2f, 2f), turn = { if (it in 300 until 310) TurnSignal.RIGHT else TurnSignal.OFF })).single()
        assertTrue(r.isValid)
        // 켜진 10프레임 + 꺼진 뒤 1초 미만 9프레임(100~900ms) = 19프레임 제외
        assertEquals((600 - 19) / 600f, r.validFraction, 1e-4f)
    }

    @Test
    fun `평균 조향 절댓값이 15도를 넘으면 굽은 길로 보고 무효(CURVE)`() {
        val r = run(frames(sine(60, 2f, 2f, offset = 20f))).single()
        assertEquals(InvalidReason.CURVE, r.invalidReason)
    }

    @Test
    fun `조향 신호가 끊기면 무효(SIGNAL_LOST)`() {
        val r = run(frames(sine(60, 2f, 2f), steeringStale = { it > 100 })).single()
        assertEquals(InvalidReason.SIGNAL_LOST, r.invalidReason)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
