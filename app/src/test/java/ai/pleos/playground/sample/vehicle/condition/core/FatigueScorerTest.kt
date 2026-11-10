package ai.pleos.playground.sample.vehicle.condition.core

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.CAUTION
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.DANGER
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.MILD
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel.NORMAL
import ai.pleos.playground.sample.vehicle.condition.core.TestSignals.features
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FatigueScorerTest {

    private val baseline = BaselineTracker(InMemoryBaselineStore()).current() // 기본: small 25±4, large 1±1
    private val usual = 25f
    private val day = ScoreContext(drivingMinutes = 10f, hourOfDay = 11, preDrive = null)

    @Test
    fun `평소와 같은 조향이면 점수 0, NORMAL, 이유 없음`() {
        val s = FatigueScorer()
        val f = features(small = usual, large = 1f)
        val bd = s.update(f, baseline, day)
        assertEquals(0f, bd.raw, 1e-6f)
        assertEquals(NORMAL, s.level)
        assertTrue(s.reasons(bd, f, day).isEmpty())
    }

    @Test
    fun `미세 수정 감소, 큰 수정 증가, 급조향이 모두 크면 조향 성분만으로 raw 0_75`() {
        val bd = FatigueScorer().breakdown(features(small = 0f, large = 5f, jerks = 2), baseline, day)
        assertEquals(1f, bd.microDrop, 1e-6f)  // z=-6.25
        assertEquals(1f, bd.largeRise, 1e-6f)  // z=+4
        assertEquals(1f, bd.jerk, 1e-6f)
        assertEquals(0.75f, bd.raw, 1e-6f)
    }

    @Test
    fun `졸린 창이 이어지면 지수이동평균으로 단계가 차례로 오른다`() {
        val s = FatigueScorer()
        val drowsy = features(small = 0f, large = 5f, jerks = 2)
        val levels = (1..6).map { s.update(drowsy, baseline, day); s.level }
        // score: 0.375 → 0.5625 → 0.656 → 0.703 → 0.727 → 0.738
        assertEquals(listOf(MILD, CAUTION, CAUTION, DANGER, DANGER, DANGER), levels)
    }

    @Test
    fun `단계 경계 히스테리시스 - 경계 바로 아래로는 내려가지 않는다`() {
        val p = FatigueParams()
        assertEquals(CAUTION, FatigueScorer.levelFor(0.50f, MILD, p))
        assertEquals(CAUTION, FatigueScorer.levelFor(0.45f, CAUTION, p)) // 0.42 이상이면 유지
        assertEquals(MILD, FatigueScorer.levelFor(0.41f, CAUTION, p))
        assertEquals(MILD, FatigueScorer.levelFor(0.45f, MILD, p))       // 올라갈 땐 0.50 필요
        assertEquals(NORMAL, FatigueScorer.levelFor(0.0f, DANGER, p))     // 한 번에 여러 단계 하강 가능
    }

    @Test
    fun `상황 성분(새벽, 장시간, 출발 전 반응 느림)만으로는 MILD까지만 오른다`() {
        val ctx = ScoreContext(drivingMinutes = 150f, hourOfDay = 3, preDrive = ReactionVerdict.MUCH_SLOWER)
        val s = FatigueScorer()
        val f = features(small = usual, large = 1f)
        repeat(10) { s.update(f, baseline, ctx) }
        assertEquals(0.35f, s.score, 1e-3f)
        assertEquals(MILD, s.level)
        val codes = s.reasons(s.breakdown(f, baseline, ctx), f, ctx).map { it.code }
        assertEquals(listOf(ReasonCode.LONG_DRIVE, ReasonCode.TIME_OF_DAY, ReasonCode.PRE_DRIVE_SLOWER), codes)
    }

    @Test
    fun `출발 전 반응 체크가 느렸으면 같은 조향에서도 점수가 더 높다`() {
        val f = features(small = 10f, large = 2.5f, jerks = 1)
        val base = FatigueScorer().breakdown(f, baseline, day).raw
        val slower = FatigueScorer().breakdown(f, baseline, day.copy(preDrive = ReactionVerdict.SLOWER)).raw
        assertTrue("base=$base slower=$slower", slower > base)
    }

    @Test
    fun `이유 문장은 진단이 아니라 평소 대비 표현이다`() {
        val s = FatigueScorer()
        val f = features(small = 0f, large = 5f, jerks = 1)
        val msgs = s.reasons(s.breakdown(f, baseline, day), f, day).map { it.message }
        assertTrue(msgs.any { it.contains("평소보다") })
        assertTrue(msgs.none { it.contains("진단") || it.contains("졸음운전") })
    }

    @Test
    fun `시간대 위험도`() {
        assertEquals(1f, FatigueScorer.timeOfDayRisk(3))
        assertEquals(0.5f, FatigueScorer.timeOfDayRisk(14))
        assertEquals(0f, FatigueScorer.timeOfDayRisk(10))
    }
}
