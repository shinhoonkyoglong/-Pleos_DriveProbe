package ai.pleos.playground.sample.vehicle.condition.engine

import ai.pleos.playground.sample.vehicle.condition.core.BaselineStore
import ai.pleos.playground.sample.vehicle.condition.core.ConditionParams
import ai.pleos.playground.sample.vehicle.condition.core.ConditionProcessor
import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.Gear
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.TripEndResult
import ai.pleos.playground.sample.vehicle.condition.core.TurnSignal
import ai.pleos.playground.sample.vehicle.condition.signals.VehicleSignals
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.TurnSignalState
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 컨디션 판단 엔진. 다른 파트는 [state]만 구독하면 된다.
 *
 * 데이터 흐름:
 *   VehicleSignals.events ──(신호 1건씩)──▶ ConditionProcessor.on*   (10Hz 격자로 재샘플링, 60초 창)
 *   100ms 타이머 ──▶ ConditionProcessor.tick ──▶ [state] (StateFlow<ConditionState>)
 *   기어 P → 주행 종료 → 요약을 기준선에 반영 → [lastTrip]
 *
 * 주행 구분: 기어가 P가 아닌 값(D/R/N)이 되면 주행 시작, P가 되면 주행 종료.
 * 출발 전 반응 체크 결과([setPreDrive])는 다음 주행 시작 때 처리기에 넘긴다.
 *
 * @param clock 신호 이벤트 시각과 같은 시계여야 한다(기본 SystemClock.elapsedRealtime)
 * @param hourOfDay 현재 시(0~23). 시간대 성분용. 시연에서는 고정값을 넣어 결과를 재현 가능하게 한다
 */
class ConditionEngine(
    /** 입력 신호. 반응 체크 화면이 기어·속도(시작/중단 조건)를 볼 때도 쓴다 */
    val signals: VehicleSignals,
    baselineStore: BaselineStore,
    private val scope: CoroutineScope,
    params: ConditionParams = ConditionParams(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val hourOfDay: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) {
    private val lock = Any()
    private val processor = ConditionProcessor(baselineStore, params)
    private var inTrip = false
    private var pendingPreDrive: ReactionVerdict? = null

    private val _state = MutableStateFlow(processor.state)
    /** 다른 파트에 공개하는 컨디션 상태 (10초마다 창이 갱신될 때 값이 바뀐다) */
    val state: StateFlow<ConditionState> = _state.asStateFlow()

    private val _lastTrip = MutableStateFlow<TripEndResult?>(null)
    /** 마지막으로 끝난 주행의 요약 (가족 요약·리포트용). 유효 창이 3개 미만이면 summary=null */
    val lastTrip: StateFlow<TripEndResult?> = _lastTrip.asStateFlow()

    private val _isDriving = MutableStateFlow(false)
    val isDriving: StateFlow<Boolean> = _isDriving.asStateFlow()

    /** 시연 재생 중이면 true → 화면에 "시연 모드" 표시 */
    val isReplay: Boolean get() = signals.isReplay

    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        // 구독을 먼저 걸어 두고(UNDISPATCHED) 신호를 시작해야 재생 첫 이벤트를 놓치지 않는다
        val collector = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            signals.events.collect { handle(it) }
        }
        val ticker = scope.launch(Dispatchers.Default) {
            while (isActive) {
                tick(clock() - TICK_LAG_MS)
                delay(TICK_PERIOD_MS)
            }
        }
        jobs = listOf(collector, ticker)
        signals.start()
    }

    fun stop() {
        signals.stop()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /**
     * 출발 전 반응 체크 결과. 주행 중이면 바로 반영하고, 아니면 다음 주행 시작 때 반영한다.
     * null을 넣으면 지운다.
     */
    fun setPreDrive(verdict: ReactionVerdict?) = synchronized(lock) {
        pendingPreDrive = verdict
        if (inTrip) {
            processor.setPreDrive(verdict)
            _state.value = processor.state
        }
    }

    /** 신호 1건 처리. (테스트에서 직접 부를 수 있게 internal) */
    internal fun handle(e: VehicleSignalEvent) = synchronized(lock) {
        when (e) {
            is VehicleSignalEvent.Steering -> processor.onSteering(e.timeMs, e.angleDeg)
            is VehicleSignalEvent.Speed -> processor.onSpeed(e.timeMs, e.speedKmh / 3.6f)
            is VehicleSignalEvent.TurnSignal -> processor.onTurnSignal(e.timeMs, e.state.toCore())
            is VehicleSignalEvent.Brake -> Unit // 피로 점수에는 쓰지 않는다
            is VehicleSignalEvent.Gear -> {
                onGearForTrip(e)
                processor.onGear(e.timeMs, e.gear.toCore())
            }
        }
    }

    /** nowMs까지 계산하고 상태를 내보낸다. (테스트에서 직접 부를 수 있게 internal) */
    internal fun tick(nowMs: Long): ConditionState = synchronized(lock) {
        processor.tick(nowMs, hourOfDay()).also { _state.value = it }
    }

    private fun onGearForTrip(e: VehicleSignalEvent.Gear) {
        val moving = e.gear == GearPosition.DRIVE || e.gear == GearPosition.REVERSE || e.gear == GearPosition.NEUTRAL
        if (moving && !inTrip) {
            processor.startTrip()
            processor.setPreDrive(pendingPreDrive)
            inTrip = true
            _isDriving.value = true
            _state.value = processor.state
        } else if (e.gear == GearPosition.PARK && inTrip) {
            // 종료 직전까지 들어온 신호를 먼저 처리한다
            processor.tick(e.timeMs, hourOfDay())
            _lastTrip.value = processor.endTrip(e.timeMs)
            pendingPreDrive = null
            inTrip = false
            _isDriving.value = false
        }
    }

    companion object {
        const val TICK_PERIOD_MS = 100L
        /** 신호가 늦게 도착해도 버려지지 않도록 현재보다 조금 이전까지만 계산한다 */
        const val TICK_LAG_MS = 300L

        internal fun GearPosition.toCore(): Gear = when (this) {
            GearPosition.PARK -> Gear.PARK
            GearPosition.REVERSE -> Gear.REVERSE
            GearPosition.NEUTRAL -> Gear.NEUTRAL
            GearPosition.DRIVE -> Gear.DRIVE
            GearPosition.UNKNOWN -> Gear.UNKNOWN
        }

        internal fun TurnSignalState.toCore(): TurnSignal = when (this) {
            TurnSignalState.OFF -> TurnSignal.OFF
            TurnSignalState.LEFT -> TurnSignal.LEFT
            TurnSignalState.RIGHT -> TurnSignal.RIGHT
        }
    }
}
