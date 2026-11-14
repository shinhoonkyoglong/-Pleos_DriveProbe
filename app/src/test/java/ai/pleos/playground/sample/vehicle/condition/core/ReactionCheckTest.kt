package ai.pleos.playground.sample.vehicle.condition.core

import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase.ABORTED
import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase.FINISHED
import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase.STIMULUS
import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase.WAITING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReactionCheckTest {

    private class SimResult(val session: ReactionSession, val stimuli: List<Long>, val taps: List<Long>)

    /**
     * 10ms 프레임으로 반응 체크를 흉내 낸다.
     * @param rt 자극 번호(0부터) → 반응시간(ms). null이면 누르지 않음(놓침)
     * @param earlyTaps 자극 전에 누를 시각들
     */
    private fun simulate(
        rt: (Int) -> Long?,
        earlyTaps: List<Long> = emptyList(),
        seed: Int = 1,
        stopAt: Long = 70_000,
        onFrame: (ReactionSession, Long) -> Unit = { _, _ -> },
    ): SimResult {
        val s = ReactionSession(random = Random(seed))
        s.start(0)
        val stimuli = ArrayList<Long>()
        val taps = ArrayList<Long>()
        val early = earlyTaps.toMutableList()
        var shownAt = -1L
        var t = 0L
        while (t <= stopAt && s.phase != FINISHED && s.phase != ABORTED) {
            onFrame(s, t)
            val before = s.phase
            val ph = s.update(t)
            if (before == WAITING && ph == STIMULUS) { shownAt = t; stimuli += t }
            if (early.isNotEmpty() && t >= early.first() && ph == WAITING) {
                s.onTap(t); taps += t; early.removeAt(0)
            } else if (ph == STIMULUS) {
                val r = rt(stimuli.size - 1)
                if (r != null && t - shownAt >= r) { s.onTap(t); taps += t }
            }
            t += 10
        }
        return SimResult(s, stimuli, taps)
    }

    private val evaluator get() = ReactionEvaluator(InMemoryReactionRecordStore())

    @Test
    fun `유효 반응 5회가 모이면 끝나고, 자극 간격은 직전 터치 후 1_5~3초 무작위`() {
        val r = simulate(rt = { 300L })
        assertEquals(FINISHED, r.session.phase)
        assertEquals(5, r.session.summary().validCount)
        assertEquals(5, r.stimuli.size)
        val intervals = r.stimuli.drop(1).mapIndexed { i, st -> st - r.taps[i] }
        assertTrue(intervals.toString(), intervals.all { it in 1_500L..3_010L })
        assertTrue("간격이 매번 같으면 안 됨", intervals.toSet().size > 1)
        // 5회 × (1.5~3초 + 0.3초) = 9~16.5초
        assertTrue("끝난 시각 ${r.taps.last()}ms", r.taps.last() in 9_000L..16_500L)
    }

    @Test
    fun `반응을 계속 놓치면 최대 25초 무렵 끝난다`() {
        var lastFrame = 0L
        val r = simulate(rt = { null }, onFrame = { _, t -> lastFrame = t })
        assertEquals(FINISHED, r.session.phase)
        // 25초가 됐을 때 자극이 떠 있으면 그 자극의 놓침(2초)까지 기다린 뒤 끝난다
        assertTrue("끝난 시각 ${lastFrame}ms", lastFrame in 25_000L..27_100L)
        assertEquals(0, r.session.summary().validCount)
    }

    @Test
    fun `반응시간 300ms 일정 - 중앙값 300, 느린 반응 0, 평소와 비슷(NORMAL)`() {
        val s = simulate(rt = { 300L }).session.summary()
        assertEquals(300L, s.medianReactionMs)
        assertEquals(0, s.slowCount)
        assertEquals(0, s.falseStartCount)
        assertEquals(ReactionVerdict.NORMAL, evaluator.evaluate(s).verdict)
    }

    @Test
    fun `기본 기준선 350ms 대비 450ms면 조금 느림(SLOWER)`() {
        val res = evaluator.evaluate(simulate(rt = { 450L }).session.summary())
        assertEquals(ReactionVerdict.SLOWER, res.verdict)
        assertEquals(2f, res.zScore!!, 1e-3f)
        assertTrue(res.isCalibrating)
    }

    @Test
    fun `600ms 반응이 계속되면 많이 느림(MUCH_SLOWER), 느린 반응으로 센다`() {
        val sum = simulate(rt = { 600L }).session.summary()
        assertEquals(sum.validCount, sum.slowCount)
        assertEquals(ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(sum).verdict)
    }

    @Test
    fun `평소엔 빠르지만 가끔 놓치면(느린 반응 2회) SLOWER`() {
        val sum = simulate(rt = { if (it == 1 || it == 3) 800L else 300L }).session.summary()
        assertEquals(2, sum.slowCount)
        assertEquals(ReactionVerdict.SLOWER, evaluator.evaluate(sum).verdict)
    }

    @Test
    fun `느린 반응 3회부터 많이 느림(MUCH_SLOWER)`() {
        val sum = simulate(rt = { if (it == 0 || it == 2 || it == 4) 800L else 300L }).session.summary()
        assertEquals(3, sum.slowCount)
        assertEquals(ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(sum).verdict)
    }

    @Test
    fun `자극 전에 누르면 너무 빠른 터치로 기록하고 대기 시간을 다시 잡는다`() {
        val r = simulate(rt = { 300L }, earlyTaps = listOf(1_000L))
        assertEquals(TrialOutcome.FALSE_START, r.session.summary().trials.first().outcome)
        assertTrue("첫 자극 ${r.stimuli.first()}", r.stimuli.first() >= 1_000 + 1_500)
        assertEquals(1, r.session.summary().falseStartCount)
    }

    @Test
    fun `자극 후 100ms 미만 터치는 너무 빠른 터치(예측)`() {
        val sum = simulate(rt = { 50L }).session.summary()
        assertEquals(0, sum.validCount)
        assertTrue(sum.falseStartCount > 3)
        val res = evaluator.evaluate(sum)
        assertEquals(ReactionVerdict.INCONCLUSIVE, res.verdict)
        assertTrue(res.message, res.message.contains("신호가 나오기 전에"))
    }

    @Test
    fun `2초 동안 안 누르면 놓침으로 기록하고 다음 자극으로 넘어간다`() {
        val sum = simulate(rt = { null }).session.summary()
        assertTrue(sum.timeoutCount >= 3)
        assertEquals(sum.timeoutCount, sum.slowCount)
        assertNull(sum.medianReactionMs)
        // 한 번도 반응하지 못했으면 기록 부족이 아니라 많이 느림으로 본다
        assertEquals(ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(sum).verdict)
    }

    private fun summary(valid: Int, timeouts: Int, median: Long? = 300) = ReactionSummary(
        trials = emptyList(), validCount = valid, medianReactionMs = median, meanReactionMs = median,
        slowCount = timeouts, falseStartCount = 0, timeoutCount = timeouts, aborted = false,
    )

    @Test
    fun `3번 이상 놓치면 유효 반응이 모자라도 판단 보류가 아니라 많이 느림`() {
        assertEquals(ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(summary(valid = 1, timeouts = 4)).verdict)
        assertEquals(ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(summary(valid = 0, timeouts = 3, median = null)).verdict)
    }

    @Test
    fun `seed 200개 - 처음 4번 놓치면 많이 느림`() {
        for (seed in seeds) {
            val sum = simulate(rt = { if (it < 4) null else 350L }, seed = seed).session.summary()
            assertEquals("seed=$seed $sum", ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(sum).verdict)
        }
    }

    @Test
    fun `놓침이 2회뿐이고 유효 반응이 모자라면 판단 보류`() {
        assertEquals(ReactionVerdict.INCONCLUSIVE, evaluator.evaluate(summary(valid = 2, timeouts = 2)).verdict)
    }

    @Test
    fun `중단되면(기어 D) 판단 보류`() {
        // 5회가 모이기 전(최소 1.8초 × 5 = 9초)에 중단
        val r = simulate(rt = { 300L }, onFrame = { s, t -> if (t == 8_000L) s.abort() })
        assertEquals(ABORTED, r.session.phase)
        val res = evaluator.evaluate(r.session.summary())
        assertEquals(ReactionVerdict.INCONCLUSIVE, res.verdict)
        assertTrue(res.message.contains("중간에 멈춰서"))
    }

    @Test
    fun `기록이 3회 쌓이면 본인 기준선과 비교한다`() {
        val store = InMemoryReactionRecordStore(listOf(400f, 410f, 420f))
        val ev = ReactionEvaluator(store)
        val (base, calibrating) = ev.baseline()
        assertFalse(calibrating)
        assertEquals(410f, base.median)
        assertEquals(30f, base.scale) // MAD 10 × 1.4826 < 최소 30

        // 기본 기준선(350)으로는 SLOWER일 450ms가, 평소 410ms인 사람에게는 NORMAL
        assertEquals(ReactionVerdict.NORMAL, ev.evaluate(simulate(rt = { 450L }).session.summary()).verdict)
        assertEquals(ReactionVerdict.SLOWER, ev.evaluate(simulate(rt = { 470L }).session.summary()).verdict)
    }

    @Test
    fun `많이 느림과 판단 보류는 기준선에 넣지 않는다`() {
        val store = InMemoryReactionRecordStore()
        val ev = ReactionEvaluator(store)
        assertFalse(ev.record(ev.evaluate(simulate(rt = { 600L }).session.summary())))
        assertFalse(ev.record(ev.evaluate(simulate(rt = { null }).session.summary())))
        assertTrue(ev.record(ev.evaluate(simulate(rt = { 300L }).session.summary())))
        assertEquals(listOf(300f), store.load())
    }

    @Test
    fun `문구는 진단이 아니라 평소 대비 변화`() {
        for (ms in listOf(300L, 450L, 600L)) {
            val msg = evaluator.evaluate(simulate(rt = { ms }).session.summary()).message
            assertTrue(msg, msg.contains("평소"))
            assertFalse(msg, msg.contains("진단") || msg.contains("치매") || msg.contains("이상"))
        }
    }

    // ---- 무작위 간격에 기대지 않는지: seed 200개로 확인 (PR #20 리뷰: seed=1 고정이라 우연히 통과) ----

    private val seeds = 1..200

    @Test
    fun `seed 200개 - 놓침 없이 터치하면 늘 시간 안에 유효 5회를 채운다`() {
        for (seed in seeds) {
            val r = simulate(rt = { 400L }, seed = seed)
            assertEquals("seed=$seed", 5, r.session.summary().validCount)
            assertTrue("seed=$seed 끝난 시각 ${r.taps.last()}", r.taps.last() < ReactionParams().durationMs)
        }
    }

    @Test
    fun `seed 200개 - 2번 놓쳐도 판단 보류가 아니라 조금 느림(SLOWER)`() {
        for (seed in seeds) {
            val sum = simulate(rt = { if (it == 1 || it == 3) null else 300L }, seed = seed).session.summary()
            assertEquals("seed=$seed", 2, sum.timeoutCount)
            assertEquals("seed=$seed $sum", ReactionVerdict.SLOWER, evaluator.evaluate(sum).verdict)
        }
    }

    @Test
    fun `seed 200개 - 3번 놓치면(가장 피곤한 경우) 판단 보류가 아니라 많이 느림(MUCH_SLOWER)`() {
        for (seed in seeds) {
            val sum = simulate(rt = { if (it in listOf(0, 2, 4)) null else 350L }, seed = seed).session.summary()
            assertEquals("seed=$seed $sum", ReactionVerdict.MUCH_SLOWER, evaluator.evaluate(sum).verdict)
        }
    }

    @Test
    fun `seed 200개 - 브레이크로 천천히(반응 600ms, 1초 밟음) 해도 시간 안에 유효 5회를 채운다`() {
        for (seed in seeds) {
            val sum = simulateBrake(rt = 600, holdMs = 1_000, seed = seed).first.summary()
            assertEquals("seed=$seed", 5, sum.validCount)
            assertEquals("seed=$seed", 0, sum.timeoutCount)
        }
    }

    // ---- 브레이크 페달 입력 ----

    /**
     * 브레이크 반응을 흉내 낸다: 자극 후 rt ms에 밟고, holdMs 뒤에 뗀다.
     * @return 세션, 자극 시각들, 뗀 시각들
     */
    private fun simulateBrake(
        rt: Long,
        holdMs: Long = 300,
        heldAtStart: Boolean = false,
        releaseAtStartMs: Long = 0,
        seed: Int = 1,
    ): Triple<ReactionSession, List<Long>, List<Long>> {
        val s = ReactionSession(ReactionParams.BRAKE, Random(seed))
        s.start(0, held = heldAtStart)
        val stimuli = ArrayList<Long>()
        val releases = ArrayList<Long>()
        var shownAt = -1L
        var releaseAt = if (heldAtStart) releaseAtStartMs else -1L
        var t = 0L
        while (t <= 60_000 && s.phase != FINISHED) {
            if (releaseAt in 0..t) { s.onRelease(t); releases += t; releaseAt = -1 }
            val before = s.phase
            val ph = s.update(t)
            if (before == WAITING && ph == STIMULUS) { shownAt = t; stimuli += t }
            if (ph == STIMULUS && t - shownAt >= rt) { s.onPress(t); releaseAt = t + holdMs }
            t += 10
        }
        return Triple(s, stimuli, releases)
    }

    @Test
    fun `브레이크 - 밟았다 떼기를 반복해 유효 5회가 모이면 끝난다`() {
        val (s, _, _) = simulateBrake(rt = 450)
        assertEquals(FINISHED, s.phase)
        assertEquals(5, s.summary().validCount)
        assertEquals(450L, s.summary().medianReactionMs)
    }

    @Test
    fun `브레이크 - 다음 자극은 페달을 뗀 시각부터 1_5~3초 뒤에 나온다`() {
        val (_, stimuli, releases) = simulateBrake(rt = 450, holdMs = 800)
        // i번째 뗀 시각 → i+1번째 자극
        val gaps = stimuli.drop(1).mapIndexed { i, st -> st - releases[i] }
        assertTrue(gaps.toString(), gaps.all { it in 1_500L..3_010L })
    }

    @Test
    fun `브레이크 - 시작할 때 밟고 있으면 뗄 때까지 자극이 나오지 않는다`() {
        val (_, stimuli, _) = simulateBrake(rt = 450, heldAtStart = true, releaseAtStartMs = 10_000)
        assertTrue("첫 자극 ${stimuli.first()}", stimuli.first() >= 10_000 + 1_500)
    }

    @Test
    fun `브레이크 - 밟은 채 같은 값이 다시 와도 반응을 두 번 세지 않는다`() {
        val s = ReactionSession(ReactionParams.BRAKE, Random(1))
        s.start(0)
        assertEquals(TrialOutcome.FALSE_START, s.onPress(500))
        assertNull("이미 밟은 상태", s.onPress(600))
        assertTrue(s.isHeld)
        // 밟고 있는 동안은 시간이 지나도 자극이 나오지 않는다
        assertEquals(WAITING, s.update(10_000))
        s.onRelease(10_000)
        assertFalse(s.isHeld)
        assertEquals(1, s.summary().falseStartCount)
    }

    @Test
    fun `브레이크 기준선(450ms)으로는 평소, 터치 기준선(350ms)으로는 조금 느림`() {
        val sum = simulateBrake(rt = 480).first.summary()
        assertEquals(0, sum.slowCount) // 브레이크 느린 반응 기준 700ms
        val brake = ReactionEvaluator(InMemoryReactionRecordStore(), ReactionBaselineParams.BRAKE).evaluate(sum)
        assertEquals(ReactionVerdict.NORMAL, brake.verdict)
        assertEquals(ReactionVerdict.SLOWER, evaluator.evaluate(sum).verdict)
    }

    @Test
    fun `시작 조건은 기어 P이고 정지, D가 되거나 움직이면 중단`() {
        assertTrue(ReactionGate.canStart(Gear.PARK, 0f))
        assertFalse(ReactionGate.canStart(Gear.DRIVE, 0f))
        assertFalse(ReactionGate.canStart(Gear.PARK, 2f))
        assertFalse("기어를 모르면 시작 안 함", ReactionGate.canStart(null, 0f))
        assertFalse("속도를 모르면 시작 안 함", ReactionGate.canStart(Gear.PARK, null))

        assertFalse(ReactionGate.mustAbort(Gear.PARK, 0f))
        assertTrue(ReactionGate.mustAbort(Gear.DRIVE, 0f))
        assertTrue(ReactionGate.mustAbort(Gear.REVERSE, 0f))
        assertTrue(ReactionGate.mustAbort(Gear.PARK, 1f))
    }
}
