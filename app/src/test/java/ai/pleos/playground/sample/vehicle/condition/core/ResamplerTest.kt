package ai.pleos.playground.sample.vehicle.condition.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {

    private fun tv(t: Long, v: Float) = TimedValue(t, v)

    @Test
    fun `첫 입력 전에는 아무것도 내보내지 않는다`() {
        val r = Resampler<Float>()
        assertTrue(r.advanceTo(10_000).isEmpty())
    }

    @Test
    fun `불규칙 입력을 100ms 격자로 채우고 값 사이는 마지막 값을 유지한다`() {
        // 실측처럼 130~230ms 간격으로 들어오는 조향 콜백
        val samples = listOf(tv(0, 0f), tv(130, 2f), tv(360, -1f), tv(500, 3f))
        val out = Resampler.resampleAll(samples, untilMs = 700)

        assertEquals(listOf(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L), out.map { it.timeMs })
        // 0:0, 100:0(130 이전), 200:2, 300:2, 400:-1, 500:3(같은 시각은 새 값), 600:3, 700:3
        assertEquals(listOf(0f, 0f, 2f, 2f, -1f, 3f, 3f, 3f), out.map { it.value })
    }

    @Test
    fun `격자 시각은 periodMs 배수로 맞춘다`() {
        val out = Resampler.resampleAll(listOf(tv(1_234, 5f)), untilMs = 1_500)
        assertEquals(listOf(1_300L, 1_400L, 1_500L), out.map { it.timeMs })
    }

    @Test
    fun `격자 사이에 여러 입력이 오면 마지막 값만 쓴다`() {
        val samples = listOf(tv(0, 0f), tv(110, 1f), tv(150, 2f), tv(190, 9f))
        val out = Resampler.resampleAll(samples, untilMs = 200)
        assertEquals(9f, out.last { it.timeMs == 200L }.value)
    }

    @Test
    fun `push와 advanceTo를 섞어 불러도 중복이나 누락이 없다`() {
        val r = Resampler<Float>()
        val out = ArrayList<ResampledValue<Float>>()
        out += r.push(tv(50, 1f))
        out += r.advanceTo(250)       // 100, 200
        out += r.advanceTo(250)       // 같은 시각을 또 불러도 추가 없음
        out += r.push(tv(420, 2f))    // 300, 400 (이전 값 1)
        out += r.advanceTo(600)       // 500, 600 (새 값 2)

        assertEquals(listOf(100L, 200L, 300L, 400L, 500L, 600L), out.map { it.timeMs })
        assertEquals(listOf(1f, 1f, 1f, 1f, 2f, 2f), out.map { it.value })
    }

    @Test
    fun `시간이 거꾸로 가는 입력은 버린다`() {
        val r = Resampler<Float>()
        r.push(tv(1_000, 1f))
        assertTrue(r.push(tv(900, 5f)).isEmpty())
        assertEquals(1, r.droppedCount)
        assertEquals(1f, r.advanceTo(1_200).last().value)
    }

    @Test
    fun `입력이 끊기면 staleAfterMs 이후 격자부터 isStale이 된다`() {
        // 조향은 3.1초마다 재전송되므로 3.5초 기준
        val r = Resampler<Float>(staleAfterMs = StaleThresholds.STEERING_MS)
        r.push(tv(0, 1f))
        val out = r.advanceTo(4_000)

        assertFalse(out.first { it.timeMs == 3_500L }.isStale)  // 정확히 3.5초는 아직 유효
        assertTrue(out.first { it.timeMs == 3_600L }.isStale)
        assertTrue(out.last().isStale)

        // 재전송 콜백이 오면 다시 유효
        r.push(tv(4_050, 1f))
        assertFalse(r.advanceTo(4_100).last().isStale)
    }

    @Test
    fun `staleAfterMs가 null이면 오래 지나도 끊김으로 보지 않는다`() {
        // 기어처럼 변경 시에만 오는 신호
        val r = Resampler<Int>()
        r.push(TimedValue(0, 4))
        assertTrue(r.advanceTo(60_000).none { it.isStale })
    }

    @Test
    fun `10Hz 격자는 1분에 600개다`() {
        val samples = (0 until 400).map { tv(it * 150L, (it % 3).toFloat()) } // 약 6.7Hz 입력
        val out = Resampler.resampleAll(samples, untilMs = 59_900)
        assertEquals(600, out.size)
    }
}
