package ai.pleos.playground.sample.vehicle.condition.core

import ai.pleos.playground.sample.vehicle.condition.core.SteeringFeatureExtractor.countLullJerks
import ai.pleos.playground.sample.vehicle.condition.core.SteeringFeatureExtractor.countReversals
import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.concat
import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.flat
import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.ramp
import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.sine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringFeaturesTest {

    // ---------- 반전(히스테리시스) ----------

    @Test
    fun `진폭 2도 사인파는 1도 gap에서 반주기마다 반전, 5도 gap에서는 0회`() {
        val xs = sine(seconds = 60, amp = 2f, periodSec = 2f) // 60초 동안 30주기 → 반전 약 59회
        val small = countReversals(xs, 1f)
        assertTrue("small=$small", small in 57..60)
        assertEquals(0, countReversals(xs, 5f))
    }

    @Test
    fun `진폭 4도 사인파는 5도 gap에서도 반전으로 센다`() {
        val xs = sine(seconds = 60, amp = 4f, periodSec = 4f) // 15주기, 꼭짓점 간 차이 8도
        val large = countReversals(xs, 5f)
        assertTrue("large=$large", large in 28..30)
    }

    @Test
    fun `gap보다 작은 흔들림은 반전이 아니다`() {
        val xs = FloatArray(200) { if (it % 2 == 0) 0f else 0.8f }
        assertEquals(0, countReversals(xs, 1f))
    }

    @Test
    fun `히스테리시스 - 꼭짓점에서 gap 미만으로 되돌아오면 반전이 아니고 새 꼭짓점을 따라간다`() {
        // 0 → 2(상승 확정) → 1.5(0.5 하락: 무시) → 2.2(꼭짓점 갱신) → 0(2.2에서 2.2 하락: 반전 1회)
        val xs = floatArrayOf(0f, 2f, 1.5f, 2.2f, 0f)
        assertEquals(1, countReversals(xs, 1f))
    }

    @Test
    fun `처음 방향이 정해지는 것은 반전으로 세지 않는다`() {
        assertEquals(0, countReversals(ramp(50, 0f, 10f), 1f))
    }

    // ---------- 무조향 후 급조향 ----------

    @Test
    fun `4초 무조향 후 1초 동안 10도 꺾으면 급조향 1회`() {
        val xs = concat(flat(40), ramp(10, 0f, 10f), flat(20, 10f))
        assertEquals(1, countLullJerks(xs, TestSignals.PERIOD))
    }

    @Test
    fun `무조향이 2초뿐이면 급조향으로 보지 않는다`() {
        val xs = concat(flat(20), ramp(10, 0f, 10f), flat(20, 10f))
        assertEquals(0, countLullJerks(xs, TestSignals.PERIOD))
    }

    @Test
    fun `무조향 후 3초에 걸쳐 천천히 8도 꺾으면 급조향이 아니다`() {
        val xs = concat(flat(40), ramp(30, 0f, 8f), flat(20, 8f))
        assertEquals(0, countLullJerks(xs, TestSignals.PERIOD))
    }

    @Test
    fun `급조향 두 번은 각각 센다`() {
        val one = concat(flat(40), ramp(8, 0f, 9f), ramp(8, 9f, 0f))
        val xs = concat(one, one, flat(10))
        assertEquals(2, countLullJerks(xs, TestSignals.PERIOD))
    }

    @Test
    fun `잦은 미세 수정(정상 주행)에는 급조향이 없다`() {
        assertEquals(0, countLullJerks(sine(60, 2f, 2f), TestSignals.PERIOD))
    }

    // ---------- 통계 ----------

    @Test
    fun `표준편차와 평균 절댓값, 분당 환산`() {
        val xs = FloatArray(600) { if (it % 2 == 0) 3f else -3f }
        val f = SteeringFeatureExtractor.extract(listOf(xs), TestSignals.PERIOD)
        assertEquals(3f, f.stdDeg, 1e-3f)
        assertEquals(3f, f.meanAbsDeg, 1e-3f)
        assertEquals(60f, f.validSeconds, 1e-3f)
        // 첫 두 샘플로 방향이 정해지고 나머지 598개가 모두 반전. 1분이므로 횟수 = 분당 횟수
        assertEquals(598f, f.reversalsSmallPerMin, 1e-3f)
    }

    @Test
    fun `유효 구간이 끊겨 있으면 구간끼리 이어 붙이지 않는다`() {
        // 0으로 끝난 구간과 10으로 시작하는 구간을 이어 붙이면 가짜 변화가 생긴다
        val a = flat(300, 0f)
        val b = flat(300, 10f)
        val f = SteeringFeatureExtractor.extract(listOf(a, b), TestSignals.PERIOD)
        assertEquals(0f, f.reversalsSmallPerMin, 1e-3f)
        assertEquals(0, f.lullJerkCount)
    }
}
