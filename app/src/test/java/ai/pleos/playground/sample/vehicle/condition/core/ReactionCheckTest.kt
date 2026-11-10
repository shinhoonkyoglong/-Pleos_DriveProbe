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
     * @param rt 시도 번호 → 반응시간(ms). null이면 누르지 않음
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
        var trial = 0
        var t = 0L
        while (t <= stopAt && s.phase != FINISHED && s.phase != ABORTED) {
            onFrame(s, t)
            val before = s.phase
            val ph = s.update(t)
            if (before == WAITING && ph == STIMULUS) { shownAt = t; stimuli += t }
            if (early.isNotEmpty() && t >= early.first() && ph == WAITING) {
                s.onTap(t); taps += t; early.removeAt(0)
            } else if (ph == STIMULUS) {
                val r = rt(trial)
                if (r != null && t - shownAt >= r) { s.onTap(t); taps += t; trial++ }
            }
            t += 10
        }
        return SimResult(s, stimuli, taps)
    }

    private val evaluator get() = ReactionEvaluator(InMemoryReactionRecordStore())

    @Test
    fun `약 60초 뒤 끝나고, 자극 간격은 직전 터치 후 2~5초 무작위`() {
        val r = simulate(rt = { 300L })
        assertEquals(FINISHED, r.session.phase)
        val intervals = r.stimuli.drop(1).mapIndexed { i, st -> st - r.taps[i] }
        assertTrue(intervals.toString(), intervals.all { it in 2_000L..5_010L })
        assertTrue("간격이 매번 같으면 안 됨", intervals.toSet().size > 3)
        assertTrue("시도 수 ${r.stimuli.size}", r.stimuli.size in 10..25)
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
        val sum = simulate(rt = { if (it == 3 || it == 7) 800L else 300L }).session.summary()
        assertEquals(2, sum.slowCount)
        assertEquals(ReactionVerdict.SLOWER, evaluator.evaluate(sum).verdict)
    }

    @Test
    fun `자극 전에 누르면 너무 빠른 터치로 기록하고 대기 시간을 다시 잡는다`() {
        val r = simulate(rt = { 300L }, earlyTaps = listOf(1_000L))
        assertEquals(TrialOutcome.FALSE_START, r.session.summary().trials.first().outcome)
        assertTrue("첫 자극 ${r.stimuli.first()}", r.stimuli.first() >= 1_000 + 2_000)
        assertEquals(1, r.session.summary().falseStartCount)
    }

    @Test
    fun `자극 후 100ms 미만 터치는 너무 빠른 터치(예측)`() {
        val sum = simulate(rt = { 50L }).session.summary()
        assertEquals(0, sum.validCount)
        assertTrue(sum.falseStartCount > 4)
        val res = evaluator.evaluate(sum)
        assertEquals(ReactionVerdict.INCONCLUSIVE, res.verdict)
        assertTrue(res.message, res.message.contains("화면이 바뀌기 전에"))
    }

    @Test
    fun `2초 동안 안 누르면 놓침으로 기록하고 다음 자극으로 넘어간다`() {
        val sum = simulate(rt = { null }).session.summary()
        assertTrue(sum.timeoutCount >= 5)
        assertEquals(sum.timeoutCount, sum.slowCount)
        assertNull(sum.medianReactionMs)
        assertEquals(ReactionVerdict.INCONCLUSIVE, evaluator.evaluate(sum).verdict)
    }

    @Test
    fun `중단되면(기어 D) 판단 보류`() {
        val r = simulate(rt = { 300L }, onFrame = { s, t -> if (t == 20_000L) s.abort() })
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
