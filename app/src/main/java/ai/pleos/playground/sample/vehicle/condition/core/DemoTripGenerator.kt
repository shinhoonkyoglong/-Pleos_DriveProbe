package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 시연용 주행 데이터 생성기. 시드가 같으면 항상 같은 데이터가 나온다.
 *
 * 1) 운전자의 "실제 조향"을 50ms 간격으로 만든다(정상: 잦은 미세 수정 / 졸림: 무조향 후 급조향)
 * 2) SDK처럼 이벤트를 뽑는다: 조향은 값이 바뀌면 110~230ms 간격(중간값 버림), 그대로면 약 3.1초마다 재전송, 0.1° 단위
 *    속도는 100/200ms 간격(연속형, 실측 약 6.4Hz). 기어·방향지시등·브레이크는 바뀔 때만.
 *
 * 합성 데이터이므로 실제 운전자 데이터로 검증된 것이 아니다(파라미터 확인·시연용).
 */
object DemoTripGenerator {

    const val DEMO_SEED = 20_261_115L
    private const val STEP_MS = 50L
    private const val DT = STEP_MS / 1000f

    /** 약 4분: 정차(P) 15초 → 출발·가속 20초 → 정상 주행 105초(중간에 차선 변경 1회) → 졸린 주행 100초 */
    fun tripDemo(seed: Long = DEMO_SEED): ReplayTrip {
        val b = Builder(Random(seed))
        b.parked(15_000)
        b.gear(14_500, Gear.DRIVE)
        b.brake(15_000, false)
        b.segment("정차(P)", 0, 15_000)

        b.accelerate(20_000, toKph = 80f)
        b.segment("출발·가속", 15_000, 35_000)

        b.normal(105_000, kph = 80f, laneChangeAtMs = 95_000)
        b.segment("정상 주행", 35_000, 140_000)

        b.drowsy(100_000, kph = 78f)
        b.segment("졸린 주행", 140_000, 240_000)

        return b.build(
            name = "trip_demo",
            description = "정차 후 출발, 정상 주행(미세 수정 많음), 졸린 주행(무조향 후 급조향). 합성 시연 데이터",
        )
    }

    /** 정상 주행만 있는 데이터(오경보 확인·기준선 시드용). 시작부터 80km/h 고속 주행 */
    fun normalTrip(seed: Long, minutes: Int): ReplayTrip {
        val b = Builder(Random(seed))
        b.gear(0, Gear.DRIVE)
        b.brake(0, false)
        b.normal(minutes * 60_000L, kph = 80f, laneChangeAtMs = null)
        b.segment("정상 주행", 0, minutes * 60_000L)
        return b.build("normal_$seed", "정상 주행만 있는 합성 데이터")
    }

    /**
     * 미리 넣는 "평소 기록"(baseline_seed.json): 정상 주행 6회(각 8분)를 처리기로 돌려 만든 주행 요약.
     * 시연에서 isCalibrating=false(개인 기준선 사용) 상태를 보여주기 위한 것이다.
     */
    fun baselineSeed(trips: Int = 6, minutesEach: Int = 8, params: ConditionParams = ConditionParams()): List<TripSummary> {
        val store = InMemoryBaselineStore()
        for (seed in 1L..trips.toLong()) {
            val p = ConditionProcessor(store, params)
            val trip = normalTrip(seed, minutesEach)
            ReplayRunner.run(trip, p)
            p.endTrip(nowMs = seed)
        }
        return store.load()
    }

    private class Builder(val rnd: Random) {
        private val steer = ArrayList<Float>()   // 50ms 격자, 실제 조향(°)
        private val speed = ArrayList<Float>()   // 50ms 격자, km/h
        private val discrete = ArrayList<ReplayEvent>()
        private val segments = ArrayList<ReplaySegment>()
        private var cur = 0f

        val nowMs: Long get() = steer.size * STEP_MS

        init {
            discrete += ReplayEvent(0, SignalKind.GEAR, text = Gear.PARK.name)
            discrete += ReplayEvent(0, SignalKind.TURN, text = TurnSignal.OFF.name)
            discrete += ReplayEvent(0, SignalKind.BRAKE, text = "true")
        }

        fun gear(t: Long, g: Gear) { discrete += ReplayEvent(t, SignalKind.GEAR, text = g.name) }
        fun brake(t: Long, on: Boolean) { discrete += ReplayEvent(t, SignalKind.BRAKE, text = on.toString()) }
        fun turn(t: Long, s: TurnSignal) { discrete += ReplayEvent(t, SignalKind.TURN, text = s.name) }
        fun segment(name: String, start: Long, end: Long) { segments += ReplaySegment(name, start, end) }

        fun parked(durMs: Long) = repeat(steps(durMs)) { push(0f, 0f) }

        fun accelerate(durMs: Long, toKph: Float) {
            val n = steps(durMs)
            val model = NormalModel(scale = 0.6f)
            for (i in 0 until n) push(model.next(), toKph * (i + 1) / n)
        }

        fun normal(durMs: Long, kph: Float, laneChangeAtMs: Long?) {
            val start = nowMs
            val model = NormalModel(scale = 1f)
            for (i in 0 until steps(durMs)) {
                val t = start + i * STEP_MS
                var s = model.next()
                if (laneChangeAtMs != null && t in laneChangeAtMs until laneChangeAtMs + 4_000) {
                    // 차선 변경: 방향지시등 켜고 4초 동안 부드럽게 4° 꺾었다 돌아옴(점수 계산에서 제외되어야 함)
                    s += 4f * sin(PI * (t - laneChangeAtMs) / 4_000.0).toFloat()
                }
                push(s, kph + 2f * sin(2 * PI * t / 40_000.0).toFloat() + gauss(0.2f))
            }
            if (laneChangeAtMs != null) {
                turn(laneChangeAtMs - 1_000, TurnSignal.LEFT)
                turn(laneChangeAtMs + 4_000, TurnSignal.OFF)
            }
        }

        fun drowsy(durMs: Long, kph: Float) {
            val end = nowMs + durMs
            while (nowMs < end) {
                // 1) 무조향: 거의 안 움직이며 한쪽으로 천천히 흘러감
                val drift = (0.05f + rnd.nextFloat() * 0.07f) * if (rnd.nextBoolean()) 1f else -1f
                repeat(steps(rnd.nextLong(3_500, 6_500))) { cur += drift * DT; pushDrowsy(cur + gauss(0.03f), kph) }
                // 2) 급조향: 흘러간 반대쪽으로 0.4~0.7초 만에 8~12°
                val mag = 8f + rnd.nextFloat() * 4f
                val dir = if (drift != 0f) -sign(drift) else 1f
                moveTo(cur + dir * mag, rnd.nextLong(400, 700), kph)
                repeat(steps(rnd.nextLong(200, 400))) { pushDrowsy(cur, kph) }
                // 3) 되돌리기: 1~1.6초에 걸쳐 0 근처로
                moveTo(rnd.nextFloat() * 2f - 1f, rnd.nextLong(1_000, 1_600), kph)
                // 4) 가끔 작은 수정 한 번
                if (rnd.nextFloat() < 0.3f) moveTo(cur + (rnd.nextFloat() * 3f - 1.5f), 400, kph)
            }
        }

        private fun moveTo(target: Float, durMs: Long, kph: Float) {
            val n = steps(durMs)
            val from = cur
            for (i in 1..n) { cur = from + (target - from) * i / n; pushDrowsy(cur, kph) }
        }

        private fun pushDrowsy(s: Float, kph: Float) =
            push(s, kph + 3f * sin(2 * PI * nowMs / 20_000.0).toFloat() + gauss(0.2f))

        private fun push(s: Float, kph: Float) { steer += s; speed += kph.coerceAtLeast(0f) }

        private fun steps(ms: Long) = (ms / STEP_MS).toInt()

        fun gauss(sd: Float): Float {
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
            val u2 = rnd.nextDouble()
            return (sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * PI * u2)).toFloat() * sd
        }

        /** 정상 주행 조향: 0.5~1.5초 유지 → 0.25~0.6초 동안 새 목표로 이동(작은 수정)을 반복 */
        inner class NormalModel(private val scale: Float) {
            private var target = 0f
            private var from = 0f
            private var moveLeft = 0f
            private var moveDur = 1f
            private var holdLeft = 0f
            private var base = 0f

            fun next(): Float {
                if (moveLeft <= 0f && holdLeft <= 0f) {
                    from = base
                    // 수정 폭은 대부분 1~2°, 5° 이상 반전은 드물게(실제 고속도로 직진 주행에 가깝게)
                    target = ((-0.5f * base + gauss(1.0f)) * scale).coerceIn(-3f, 3f)
                    moveDur = 0.25f + rnd.nextFloat() * 0.35f
                    moveLeft = moveDur
                }
                if (moveLeft > 0f) {
                    moveLeft -= DT
                    base = from + (target - from) * (1f - (moveLeft / moveDur).coerceAtLeast(0f))
                    if (moveLeft <= 0f) holdLeft = 0.5f + rnd.nextFloat()
                } else {
                    holdLeft -= DT
                }
                cur = base
                return base + gauss(0.08f)
            }
        }

        fun build(name: String, description: String): ReplayTrip {
            val events = ArrayList<ReplayEvent>(discrete)
            // 조향: 변경 시 110~230ms 간격(최신값만), 고정이면 약 3.1초마다 재전송
            var lastSent: Float? = null
            var nextAllowed = 0L
            var nextResend = 0L
            for (i in steer.indices) {
                val t = i * STEP_MS
                val v = round1(steer[i])
                val changed = v != lastSent
                if ((changed && t >= nextAllowed) || (!changed && t >= nextResend)) {
                    events += ReplayEvent(t, SignalKind.STEERING, num = v)
                    lastSent = v
                    nextAllowed = t + rnd.nextLong(110, 231)
                    nextResend = t + rnd.nextLong(3_001, 3_201)
                }
            }
            // 속도: 100/200ms 간격
            var t = 0L
            while (t < speed.size * STEP_MS) {
                events += ReplayEvent(t, SignalKind.SPEED_KPH, num = round1(speed[(t / STEP_MS).toInt()]))
                t += if (rnd.nextFloat() < 0.45f) 100 else 200
            }
            val sorted = events.sortedWith(compareBy<ReplayEvent> { it.tMs }.thenBy { it.kind.ordinal })
            return ReplayTrip(name, description, steer.size * STEP_MS, segments.toList(), sorted)
        }

        /** 0.1 단위 반올림. -0.0은 0.0으로(값 비교·JSON 출력이 흔들리지 않게) */
        private fun round1(x: Float): Float {
            val r = (x * 10f).roundToInt() / 10f
            return if (r == 0f) 0f else r
        }
    }
}
