package ai.pleos.playground.sample.vehicle.condition.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    private fun trip(i: Int, small: Float = 20f, large: Float = 1f, std: Float = 2f) =
        TripSummary(endTimeMs = i.toLong(), reversalsSmallPerMin = small, reversalsLargePerMin = large, stdDeg = std)

    @Test
    fun `중앙값과 MAD는 튀는 값 하나에 흔들리지 않는다`() {
        val xs = listOf(1f, 2f, 3f, 4f, 100f)
        assertEquals(3f, RobustStats.median(xs))
        assertEquals(1f, RobustStats.mad(xs)) // |x-3| = 2,1,0,1,97 → 중앙값 1
        assertEquals(2.5f, RobustStats.median(listOf(1f, 2f, 3f, 4f)))
    }

    @Test
    fun `scale은 1_4826 x MAD이고 최소값 아래로 내려가지 않는다`() {
        assertEquals(1.4826f * 4f, RobustStats.of(listOf(10f, 14f, 18f, 22f, 26f), minScale = 1f).scale, 1e-4f)
        assertEquals(3f, RobustStats.of(listOf(20f, 20f, 20f), minScale = 3f).scale) // MAD 0 → 최소값
    }

    @Test
    fun `기록이 5회 미만이면 기본 기준선을 쓰고 isCalibrating`() {
        val t = BaselineTracker(InMemoryBaselineStore((1..4).map { trip(it, small = 40f) }))
        val b = t.current()
        assertTrue(b.isCalibrating)
        assertEquals(BaselineParams().defaultSmall, b.small)
        assertEquals(4, b.tripCount)
    }

    @Test
    fun `5회부터 개인 기준선을 쓴다`() {
        val t = BaselineTracker(InMemoryBaselineStore((1..5).map { trip(it, small = 30f + it) }))
        val b = t.current()
        assertFalse(b.isCalibrating)
        assertEquals(33f, b.small.median)
    }

    @Test
    fun `피로 경고가 뜬 주행은 기준선에 넣지 않는다`() {
        val store = InMemoryBaselineStore()
        val t = BaselineTracker(store)
        assertFalse(t.record(trip(1, small = 5f), hadFatigueWarning = true))
        assertEquals(0, t.tripCount)
        assertTrue(store.load().isEmpty())

        assertTrue(t.record(trip(2), hadFatigueWarning = false))
        assertEquals(1, store.load().size)
    }

    @Test
    fun `최근 20회만 유지하고 저장소에도 반영한다`() {
        val store = InMemoryBaselineStore()
        val t = BaselineTracker(store)
        for (i in 1..25) t.record(trip(i, small = i.toFloat()), hadFatigueWarning = false)
        assertEquals(20, t.tripCount)
        assertEquals((6..25).map { it.toLong() }, store.load().map { it.endTimeMs })
        assertEquals(15.5f, t.current().small.median) // 6..25의 중앙값
    }

    @Test
    fun `저장소에서 불러올 때도 20회로 자른다`() {
        val t = BaselineTracker(InMemoryBaselineStore((1..30).map { trip(it) }))
        assertEquals(20, t.tripCount)
    }
}
