package ai.pleos.playground.sample.vehicle.condition.engine

import ai.pleos.playground.sample.vehicle.condition.core.BaselineSeedFormat
import ai.pleos.playground.sample.vehicle.condition.core.ConditionProcessor
import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.DemoTripGenerator
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.InMemoryBaselineStore
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReplayEvent
import ai.pleos.playground.sample.vehicle.condition.core.ReplayRunner
import ai.pleos.playground.sample.vehicle.condition.core.ReplaySegment
import ai.pleos.playground.sample.vehicle.condition.core.ReplayTrip
import ai.pleos.playground.sample.vehicle.condition.core.SignalKind
import ai.pleos.playground.sample.vehicle.condition.core.TripSummary
import ai.pleos.playground.sample.vehicle.condition.signals.ReplayVehicleSignals
import ai.pleos.playground.sample.vehicle.condition.signals.VehicleSignals
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * engine이 신호 이벤트(VehicleSignalEvent)를 받아 core와 같은 결과를 내는지, 주행 시작/종료와 기준선 저장을 맞게 하는지 확인한다.
 * 안드로이드 기기 없이 PC에서 돈다(시계와 신호를 가짜로 넣는다).
 */
class ConditionEngineTest {

    private class FakeSignals : VehicleSignals {
        override val isReplay = false
        override val events: Flow<VehicleSignalEvent> = emptyFlow()
        override val gear: StateFlow<GearPosition> = MutableStateFlow(GearPosition.UNKNOWN)
        override val speedKmh: StateFlow<Float?> = MutableStateFlow(null)
        override fun start() = Unit
        override fun stop() = Unit
    }

    private val unusedScope = CoroutineScope(Dispatchers.Unconfined)

    private fun engine(store: InMemoryBaselineStore = InMemoryBaselineStore()) =
        ConditionEngine(FakeSignals(), store, unusedScope, clock = { error("테스트에서는 tick을 직접 부른다") }, hourOfDay = { 11 })

    /** 실제 기기처럼 큰 시각(부팅 후 ms)에서 시작해, 재생 파일을 100ms 단위로 engine에 흘려 넣는다 */
    private fun feed(engine: ConditionEngine, trip: ReplayTrip, base: Long = 5_000_000L): List<ConditionState> {
        val states = ArrayList<ConditionState>()
        var idx = 0
        var t = 0L
        while (t <= trip.durationMs) {
            while (idx < trip.events.size && trip.events[idx].tMs <= t) {
                engine.handle(ReplayVehicleSignals.toSignalEvent(trip.events[idx], base + trip.events[idx].tMs))
                idx++
            }
            states += engine.tick(base + t)
            t += 100
        }
        return states
    }

    /**
     * core만으로 engine과 같은 일을 한다: 기어가 P에서 D가 되는 순간 startTrip (창 60초가 주행 시작 기준으로 맞춰짐).
     * ReplayRunner는 startTrip을 부르지 않아 창 위치가 0초 기준이므로 결과가 조금 다르다.
     */
    private fun coreWithTripStart(trip: ReplayTrip): List<ConditionState> {
        val p = ConditionProcessor(InMemoryBaselineStore())
        val out = ArrayList<ConditionState>()
        var inTrip = false
        var idx = 0
        var t = 0L
        while (t <= trip.durationMs) {
            while (idx < trip.events.size && trip.events[idx].tMs <= t) {
                val ev = trip.events[idx++]
                if (ev.kind == SignalKind.GEAR && ev.text == "DRIVE" && !inTrip) { p.startTrip(); inTrip = true }
                p.applyEvent(ev)
            }
            out += p.tick(t, 11)
            t += 100
        }
        return out
    }

    private fun ConditionProcessor.applyEvent(e: ReplayEvent) {
        // core의 확장 함수 apply(e)와 같다 (이름이 Kotlin 표준 apply와 겹쳐 직접 부른다)
        when (e.kind) {
            SignalKind.STEERING -> onSteering(e.tMs, e.num)
            SignalKind.SPEED_KPH -> onSpeed(e.tMs, e.num / 3.6f)
            SignalKind.GEAR -> onGear(e.tMs, ai.pleos.playground.sample.vehicle.condition.core.Gear.valueOf(e.text!!))
            SignalKind.TURN -> onTurnSignal(e.tMs, ai.pleos.playground.sample.vehicle.condition.core.TurnSignal.valueOf(e.text!!))
            SignalKind.BRAKE -> Unit
        }
    }

    @Test
    fun `engine 결과가 core와 같다 (시연 데이터, 시각 기준만 다름)`() {
        val trip = DemoTripGenerator.tripDemo()
        val expected = coreWithTripStart(trip)
        val got = feed(engine(), trip)

        assertEquals(expected.size, got.size)
        expected.zip(got).forEach { (a, b) ->
            assertEquals(a.level, b.level)
            assertEquals(a.score, b.score, 1e-6f)
            assertEquals(a.reasons, b.reasons)
            assertEquals(a.lastWindowValid, b.lastWindowValid)
        }
    }

    @Test
    fun `engine 경로로도 시연 목표를 만족한다 - 정상 구간 NORMAL, 졸린 구간 CAUTION 이상`() {
        val trip = DemoTripGenerator.tripDemo()
        val e = engine()
        val states = feed(e, trip, base = 0L)
        val normal = trip.segments.first { it.name == "정상 주행" }
        val drowsy = trip.segments.first { it.name == "졸린 주행" }

        // 유효 창이 갱신되어 점수가 바뀐 시점만 골라 출력 (tick마다 timeMs는 늘어나므로 점수·이유로 구분)
        val windows = states.filter { it.lastWindowValid }
            .zipWithNext().filter { (a, b) -> a.score != b.score || a.reasons != b.reasons }.map { it.second }
            .let { listOfNotNull(states.firstOrNull { s -> s.lastWindowValid }) + it }
        println("=== engine 경로 (주행 시작 14.5초 기준 창) ===")
        windows.forEach { println("t=%6.1fs level=%-7s score=%.2f".format(it.timeMs / 1000.0, it.level, it.score)) }

        windows.filter { it.timeMs in normal.startMs until normal.endMs }.forEach {
            assertEquals("정상 구간 t=${it.timeMs}", FatigueLevel.NORMAL, it.level)
        }
        val first = windows.first { it.timeMs >= drowsy.startMs && it.level >= FatigueLevel.CAUTION }
        val delay = first.timeMs - drowsy.startMs
        println("졸린 구간 시작 후 CAUTION까지 ${delay / 1000.0}초, 마지막=${e.state.value.level}")
        assertTrue("CAUTION까지 ${delay}ms", delay <= 70_000)
        assertTrue(e.state.value.level >= FatigueLevel.CAUTION)
        assertTrue("주행 중", e.isDriving.value)
    }

    @Test
    fun `P로 바꾸면 주행이 끝나고, 경고가 있었던 주행은 기준선에 넣지 않는다`() {
        val store = InMemoryBaselineStore()
        val e = engine(store)
        val trip = DemoTripGenerator.tripDemo()
        feed(e, trip)
        assertNull(e.lastTrip.value)

        e.handle(VehicleSignalEvent.Gear(5_000_000L + trip.durationMs + 100, GearPosition.PARK))
        val r = e.lastTrip.value
        assertNotNull(r)
        assertNotNull("유효 창이 충분하므로 요약이 있다", r!!.summary)
        assertTrue("maxLevel=${r.maxLevel}", r.maxLevel >= FatigueLevel.CAUTION)
        assertFalse(r.baselineUpdated)
        assertTrue(store.load().isEmpty())
        assertFalse(e.isDriving.value)
    }

    @Test
    fun `정상 주행은 기준선에 쌓이고 파일로 저장된다 (다시 읽어도 같음)`() {
        val dir = Files.createTempDirectory("condition").toFile()
        val file = File(dir, "condition/baseline.json")
        val store = FileBaselineStore(file)
        val e = ConditionEngine(FakeSignals(), store, unusedScope, clock = { 0L }, hourOfDay = { 11 })

        val trip = DemoTripGenerator.normalTrip(201, 5)
        feed(e, trip)
        e.handle(VehicleSignalEvent.Gear(5_000_000L + trip.durationMs + 100, GearPosition.PARK))

        val r = e.lastTrip.value!!
        assertTrue(r.baselineUpdated)
        assertEquals(FatigueLevel.NORMAL, r.maxLevel)
        assertTrue(file.exists())
        val reloaded = FileBaselineStore(file).load()
        assertEquals(1, reloaded.size)
        assertEquals(r.summary!!.reversalsSmallPerMin, reloaded[0].reversalsSmallPerMin, 0.01f)
        dir.deleteRecursively()
    }

    @Test
    fun `출발 전 반응 체크 결과는 다음 주행 시작 때 반영되고, 주행이 끝나면 지워진다`() {
        val e = engine()
        e.handle(VehicleSignalEvent.Gear(1_000, GearPosition.PARK))
        e.setPreDrive(ReactionVerdict.SLOWER)

        e.handle(VehicleSignalEvent.Gear(2_000, GearPosition.DRIVE))
        assertEquals(ReactionVerdict.SLOWER, e.state.value.preDrive)

        e.handle(VehicleSignalEvent.Gear(3_000, GearPosition.PARK))
        e.handle(VehicleSignalEvent.Gear(4_000, GearPosition.DRIVE))
        assertNull("새 주행에는 이전 결과가 남지 않는다", e.state.value.preDrive)
    }

    @Test
    fun `기어를 모르는 상태(UNKNOWN)로는 주행을 시작하지 않는다`() {
        val e = engine()
        e.handle(VehicleSignalEvent.Gear(1_000, GearPosition.UNKNOWN))
        assertFalse(e.isDriving.value)
        e.handle(VehicleSignalEvent.Gear(2_000, GearPosition.REVERSE))
        assertTrue(e.isDriving.value)
    }
}

class FileBaselineStoreTest {
    @Test
    fun `파일이 없으면 initial, 깨진 파일이면 빈 기록`() {
        val dir = Files.createTempDirectory("condition").toFile()
        val f = File(dir, "baseline.json")
        val seed = listOf(TripSummary(1, 25f, 0f, 1f))
        assertEquals(seed, FileBaselineStore(f, seed).load())

        f.writeText("{ 깨진 내용")
        assertTrue(FileBaselineStore(f, seed).load().isEmpty())

        FileBaselineStore(f).save(seed)
        assertEquals(BaselineSeedFormat.write(seed), f.readText())
        assertFalse(File(dir, "baseline.json.tmp").exists())
        dir.deleteRecursively()
    }
}

class ReplayVehicleSignalsTest {

    private val tiny = ReplayTrip(
        name = "tiny", description = "", durationMs = 400,
        segments = listOf(ReplaySegment("정차", 0, 200), ReplaySegment("주행", 200, 400)),
        events = listOf(
            ReplayEvent(0, SignalKind.GEAR, text = "PARK"),
            ReplayEvent(100, SignalKind.SPEED_KPH, num = 0f),
            ReplayEvent(200, SignalKind.GEAR, text = "DRIVE"),
            ReplayEvent(250, SignalKind.STEERING, num = -2.5f),
            ReplayEvent(300, SignalKind.BRAKE, text = "true"),
            ReplayEvent(350, SignalKind.TURN, text = "LEFT"),
        ),
    )

    @Test
    fun `재생 이벤트 변환 - 단위와 값을 실제 신호와 같게`() {
        val ev = tiny.events.map { ReplayVehicleSignals.toSignalEvent(it, 1_000 + it.tMs) }
        assertEquals(VehicleSignalEvent.Gear(1_000, GearPosition.PARK), ev[0])
        assertEquals(VehicleSignalEvent.Speed(1_100, 0f), ev[1])
        assertEquals(VehicleSignalEvent.Steering(1_250, -2.5f), ev[3])
        assertEquals(VehicleSignalEvent.Brake(1_300, true), ev[4])
        assertEquals(ai.pleos.playground.sample.vehicle.data.model.TurnSignalState.LEFT, (ev[5] as VehicleSignalEvent.TurnSignal).state)
    }

    @Test
    fun `실제 시간으로 재생한다 - 순서, 시각 간격, 기어와 속도 상태, 끝 표시`() = runBlocking {
        val clock = { System.nanoTime() / 1_000_000 }
        val r = ReplayVehicleSignals(tiny, this, clock = clock)
        assertTrue(r.isReplay)
        assertNull("재생 전에는 속도를 모름", r.speedKmh.value)

        val collected = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val got = r.events.take(tiny.events.size).toList()
            val offsets = got.map { it.timeMs - got.first().timeMs }
            assertEquals(tiny.events.map { it.tMs }, offsets)
        }
        val started = clock()
        r.start()
        withTimeout(3_000) { collected.join() }
        while (!r.finished.value) kotlinx.coroutines.delay(10)
        val elapsed = clock() - started

        assertTrue("1배속 재생 elapsed=$elapsed", elapsed in 380L..1_500L)
        assertEquals(GearPosition.DRIVE, r.gear.value)
        assertEquals(0f, r.speedKmh.value)
        assertEquals(400L, r.positionMs.value)
    }
}

class GearPositionTest {
    @Test
    fun `AAOS GEAR_SELECTION 값 변환 (실측 N=1 R=2 P=4 D=8)`() {
        assertEquals(GearPosition.NEUTRAL, GearPosition.fromAaos(1))
        assertEquals(GearPosition.REVERSE, GearPosition.fromAaos(2))
        assertEquals(GearPosition.PARK, GearPosition.fromAaos(4))
        assertEquals(GearPosition.DRIVE, GearPosition.fromAaos(8))
        assertEquals(GearPosition.DRIVE, GearPosition.fromAaos(32)) // GEAR_SECOND
        assertEquals(GearPosition.UNKNOWN, GearPosition.fromAaos(0))
        assertEquals(GearPosition.UNKNOWN, GearPosition.fromAaos(null))
    }
}
