package ai.pleos.playground.sample.vehicle.condition.signals

import ai.pleos.playground.sample.vehicle.condition.core.ReplayEvent
import ai.pleos.playground.sample.vehicle.condition.core.ReplayFormat
import ai.pleos.playground.sample.vehicle.condition.core.ReplayTrip
import ai.pleos.playground.sample.vehicle.condition.core.SignalKind
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.TurnSignalState
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 시연용 재생. assets의 재생 파일(예: condition/trip_demo.json)을 실제 시간 그대로(1배속) 흘려보낸다.
 *
 * 데이터 흐름: assets JSON → [ReplayFormat.read] → 시각에 맞춰 [events]로 발행 → engine
 * 이벤트 시각은 "재생 시작 시각 + 파일의 tMs"라서, 실제 차량 신호와 똑같이 처리된다.
 *
 * @param clock 현재 시각(ms). 기본은 SystemClock.elapsedRealtime (engine과 같은 시계를 써야 한다)
 */
class ReplayVehicleSignals(
    val trip: ReplayTrip,
    private val scope: CoroutineScope,
    private val loop: Boolean = false,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : VehicleSignals {
    override val isReplay: Boolean = true

    private val _events = MutableSharedFlow<VehicleSignalEvent>(extraBufferCapacity = 256)
    override val events: Flow<VehicleSignalEvent> = _events.asSharedFlow()

    private val _gear = MutableStateFlow(GearPosition.UNKNOWN)
    override val gear: StateFlow<GearPosition> = _gear.asStateFlow()

    private val _speedKmh = MutableStateFlow<Float?>(null)
    override val speedKmh: StateFlow<Float?> = _speedKmh.asStateFlow()

    private val _brakePressed = MutableStateFlow<Boolean?>(null)
    override val brakePressed: StateFlow<Boolean?> = _brakePressed.asStateFlow()

    /** 재생 위치(ms, 파일 기준)와 현재 구간 이름 — 시연 화면 표시용 */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _segmentName = MutableStateFlow<String?>(null)
    val segmentName: StateFlow<String?> = _segmentName.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            do {
                _finished.value = false
                playOnce()
            } while (loop)
            _finished.value = true
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun playOnce() {
        val base = clock()
        for (e in trip.events) {
            val wait = base + e.tMs - clock()
            if (wait > 0) delay(wait)
            setPosition(e.tMs)
            val ev = toSignalEvent(e, base + e.tMs)
            when (ev) {
                is VehicleSignalEvent.Gear -> _gear.value = ev.gear
                is VehicleSignalEvent.Speed -> _speedKmh.value = ev.speedKmh
                is VehicleSignalEvent.Brake -> _brakePressed.value = ev.pressed
                else -> Unit
            }
            _events.emit(ev)
        }
        val tail = base + trip.durationMs - clock()
        if (tail > 0) delay(tail)
        setPosition(trip.durationMs)
    }

    private fun setPosition(tMs: Long) {
        _positionMs.value = tMs
        _segmentName.value = trip.segments.firstOrNull { tMs >= it.startMs && tMs < it.endMs }?.name
    }

    companion object {
        const val DEMO_ASSET = "condition/trip_demo.json"
        const val BASELINE_SEED_ASSET = "condition/baseline_seed.json"

        fun loadTrip(context: Context, assetPath: String = DEMO_ASSET): ReplayTrip =
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { ReplayFormat.read(it.readText()) }

        /** 재생 파일의 이벤트 1건 → 실제 차량과 같은 신호 이벤트 */
        fun toSignalEvent(e: ReplayEvent, timeMs: Long): VehicleSignalEvent = when (e.kind) {
            SignalKind.STEERING -> VehicleSignalEvent.Steering(timeMs, e.num)
            SignalKind.SPEED_KPH -> VehicleSignalEvent.Speed(timeMs, e.num)
            SignalKind.GEAR -> VehicleSignalEvent.Gear(timeMs, GearPosition.valueOf(e.text!!))
            SignalKind.TURN -> VehicleSignalEvent.TurnSignal(timeMs, TurnSignalState.valueOf(e.text!!))
            SignalKind.BRAKE -> VehicleSignalEvent.Brake(timeMs, e.text == "true")
        }
    }
}
