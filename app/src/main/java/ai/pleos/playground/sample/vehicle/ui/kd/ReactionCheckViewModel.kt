package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.condition.core.Gear
import ai.pleos.playground.sample.vehicle.condition.core.ReactionBaselineParams
import ai.pleos.playground.sample.vehicle.condition.core.ReactionEvaluator
import ai.pleos.playground.sample.vehicle.condition.core.ReactionGate
import ai.pleos.playground.sample.vehicle.condition.core.ReactionInput
import ai.pleos.playground.sample.vehicle.condition.core.ReactionParams
import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase
import ai.pleos.playground.sample.vehicle.condition.core.ReactionRecordStore
import ai.pleos.playground.sample.vehicle.condition.core.ReactionResult
import ai.pleos.playground.sample.vehicle.condition.core.ReactionSession
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.TrialOutcome
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.util.UUID

/** 반응 체크 화면 상태 */
data class ReactionUiState(
    val phase: ReactionPhase = ReactionPhase.READY,
    val stimulusVisible: Boolean = false,
    val remainingMs: Long = 0,
    val totalMs: Long = 0,
    val tapCount: Int = 0,
    /** 방금 반응한 결과(잠깐 보여 주는 피드백) */
    val lastOutcome: TrialOutcome? = null,
    val result: ReactionResult? = null,
    /** 지금 시작할 수 없는 이유. null 이면 시작 가능 */
    val blockedReason: String? = null,
    /** 반응 방법. 실제 차량 = 브레이크, 시연 재생·신호 없음 = 터치 */
    val input: ReactionInput = ReactionInput.TOUCH,
    /** 브레이크를 밟고 있어 다음 신호를 기다리는 중(→ "발을 떼 주세요") */
    val pedalHeld: Boolean = false,
    /** 지금까지 제때 반응한 횟수 / 목표 횟수(모이면 끝) */
    val validCount: Int = 0,
    val targetValid: Int = 0,
)

/**
 * 출발 전 반응 체크(PVT 축소판) 화면 쪽 연결. 판단은 3번 condition/core 의
 * [ReactionSession]·[ReactionEvaluator]·[ReactionGate]를 그대로 쓴다.
 *
 * 반응 입력(2026-11-13): 실제 차량에서는 **브레이크 페달**(SDK 콜백 지연이 에뮬레이터 실측 표준편차 3ms로 작음),
 * 시연 재생 중이거나 차량 신호가 없으면 터치. 유효 반응 5회 또는 최대 25초.
 * 시각은 모두 SystemClock.elapsedRealtime 기준(브레이크 이벤트 시각과 같은 시계).
 *
 * 결과 처리:
 * 1. ConditionModule.engine.setPreDrive(verdict) → 다음 주행 피로 점수에 반영
 * 2. Room reaction_scores 에 1건 저장 → 기록·리포트 화면
 * 3. 평소 기록(반응시간 중앙값 목록)은 SharedPreferences 에 입력 방법별로 따로 저장(ReactionRecordStore)
 */
class ReactionCheckViewModel(application: Application) : AndroidViewModel(application) {

    private val evaluators = ReactionInput.entries.associateWith { input ->
        ReactionEvaluator(PrefsReactionRecordStore(application, input), ReactionBaselineParams.of(input))
    }
    private var input = plannedInput()
    private var params = ReactionParams.of(input)
    private var session = ReactionSession(params)
    private var brakeJob: Job? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ReactionUiState> = _state.asStateFlow()

    val personalRecordCount: Int get() = evaluators.getValue(input).recordCount

    /** 화면이 보일 때·매 프레임 불러서 시작 가능 여부를 갱신한다 */
    fun refreshGate() {
        if (_state.value.phase != ReactionPhase.READY) return
        val planned = plannedInput()
        if (planned != input) {
            // 시연 모드 전환 등으로 신호 출처가 바뀜 → 입력 방법·기준을 다시 잡는다
            input = planned
            params = ReactionParams.of(input)
            session = ReactionSession(params)
            _state.value = initialState()
            return
        }
        _state.value = _state.value.copy(blockedReason = blockedReason())
    }

    fun start(nowMs: Long) {
        val reason = blockedReason()
        if (reason != null) {
            _state.value = _state.value.copy(blockedReason = reason)
            return
        }
        session = ReactionSession(params)
        val signals = ConditionModule.signals
        if (input == ReactionInput.BRAKE && signals != null) {
            session.start(nowMs, held = signals.brakePressed.value == true)
            brakeJob?.cancel()
            brakeJob = viewModelScope.launch {
                signals.events.filterIsInstance<VehicleSignalEvent.Brake>().collect { onBrake(it) }
            }
        } else {
            session.start(nowMs)
        }
        publish(nowMs)
    }

    /** 브레이크 신호 1건. 반응 시각은 SDK 콜백 시각(이벤트 시각)을 쓴다 → 화면 갱신 지연이 섞이지 않는다 */
    private fun onBrake(ev: VehicleSignalEvent.Brake) {
        val phase = session.phase
        if (phase != ReactionPhase.WAITING && phase != ReactionPhase.STIMULUS) return
        if (ev.pressed) {
            val outcome = session.onPress(ev.timeMs) ?: return
            publish(ev.timeMs, outcome)
            if (session.phase == ReactionPhase.FINISHED) finish()
        } else {
            session.onRelease(ev.timeMs)
            publish(ev.timeMs)
        }
    }

    /** 매 프레임 */
    fun onFrame(nowMs: Long) {
        val phase = session.phase
        if (phase != ReactionPhase.WAITING && phase != ReactionPhase.STIMULUS) return
        if (mustAbort()) {
            session.abort()
            finish()
            return
        }
        session.update(nowMs)
        if (session.phase == ReactionPhase.FINISHED) finish() else publish(nowMs)
    }

    /** 화면 터치. 브레이크 입력일 때는 무시한다(두 입력을 섞으면 반응 시간 기준이 달라짐) */
    fun onTap(nowMs: Long) {
        if (input != ReactionInput.TOUCH) return
        val outcome = session.onTap(nowMs) ?: return
        publish(nowMs, outcome)
        if (session.phase == ReactionPhase.FINISHED) finish()
    }

    fun cancel() {
        if (session.phase == ReactionPhase.WAITING || session.phase == ReactionPhase.STIMULUS) {
            session.abort()
        }
        reset()
    }

    fun reset() {
        stopBrake()
        input = plannedInput()
        params = ReactionParams.of(input)
        session = ReactionSession(params)
        _state.value = initialState()
    }

    override fun onCleared() {
        stopBrake()
    }

    private fun stopBrake() {
        brakeJob?.cancel()
        brakeJob = null
    }

    private fun initialState() = ReactionUiState(
        totalMs = params.durationMs,
        remainingMs = params.durationMs,
        blockedReason = blockedReason(),
        input = input,
        targetValid = params.targetValidTrials ?: 0,
    )

    private fun publish(nowMs: Long, outcome: TrialOutcome? = _state.value.lastOutcome) {
        _state.value = _state.value.copy(
            phase = session.phase,
            stimulusVisible = session.isStimulusVisible,
            remainingMs = session.remainingMs(nowMs),
            tapCount = session.summary().trials.size,
            lastOutcome = outcome,
            blockedReason = null,
            pedalHeld = session.isHeld,
            validCount = session.validCount,
        )
    }

    private fun finish() {
        stopBrake()
        val evaluator = evaluators.getValue(input)
        val result = evaluator.evaluate(session.summary())
        evaluator.record(result)
        _state.value = _state.value.copy(
            phase = session.phase,
            stimulusVisible = false,
            remainingMs = 0,
            result = result,
        )
        if (result.verdict != ReactionVerdict.INCONCLUSIVE && ConditionModule.isInitialized) {
            ConditionModule.engine.setPreDrive(result.verdict)
        }
        // 판정 보류는 화면 문구("내 기록에 쓰지 않아요")대로 Room에도 남기지 않는다(홈 "최근 반응 속도"에 보이지 않게)
        if (result.verdict != ReactionVerdict.INCONCLUSIVE) save(result)
    }

    private fun save(result: ReactionResult) {
        // 한 번도 반응하지 못한 '많이 느림'도 남긴다. 반응 시간은 놓침 기준(timeoutMs)으로 적는다(가장 느린 값).
        // 남기지 않으면 오늘 체크가 안 된 것으로 보여 덜 나쁜 결과가 나올 때까지 다시 할 수 있다(PR #21 리뷰)
        val median = result.summary.medianReactionMs
            ?: if (result.verdict == ReactionVerdict.MUCH_SLOWER) params.timeoutMs else return
        val entity = ReactionScoreEntity(
            sessionId = UUID.randomUUID().toString(),
            simpleReactionTimeMs = median,
            reactionScore = scoreOf(median, input),
            passed = result.verdict != ReactionVerdict.MUCH_SLOWER,
            notes = result.verdict.name,
        )
        viewModelScope.launch {
            runCatching { DriveProbeApplication.instance.appDatabase.reactionScoreDao().insertScore(entity) }
                .onFailure { Log.w(TAG, "반응 체크 저장 실패", it) }
        }
    }

    private fun currentGear(): Gear? = when (ConditionModule.signals?.gear?.value) {
        GearPosition.PARK -> Gear.PARK
        GearPosition.REVERSE -> Gear.REVERSE
        GearPosition.NEUTRAL -> Gear.NEUTRAL
        GearPosition.DRIVE -> Gear.DRIVE
        GearPosition.UNKNOWN, null -> null
    }

    private fun currentSpeedMps(): Float? = ConditionModule.signals?.speedKmh?.value?.let { it / 3.6f }

    private fun blockedReason(): String? {
        val gear = currentGear()
        val speed = currentSpeedMps()
        if (ReactionGate.canStart(gear, speed)) return null
        // 에뮬레이터에서 속도 권한이 없으면 속도를 모른다. debug 빌드에서는 P 기어만 확인하고 시작을 허용한다
        if (BuildConfig.DEBUG && gear == Gear.PARK && speed == null) return null
        return when {
            gear == null -> "차량 상태를 확인하는 중이에요"
            gear != Gear.PARK -> "기어를 P(주차)에 두면 시작할 수 있어요"
            else -> "차가 완전히 멈추면 시작할 수 있어요"
        }
    }

    private fun mustAbort(): Boolean = ReactionGate.mustAbort(currentGear(), currentSpeedMps())

    /** 실제 차량 신호면 브레이크, 시연 재생이거나 신호가 없으면 터치 */
    private fun plannedInput(): ReactionInput {
        val signals = ConditionModule.signals
        return if (signals != null && !signals.isReplay) ReactionInput.BRAKE else ReactionInput.TOUCH
    }

    private companion object {
        const val TAG = "KdReaction"

        /**
         * 화면·리포트용 0~100 환산(터치 250ms 이하 100점, 650ms 이상 0점). 판정에는 쓰지 않는다.
         * 브레이크는 발 이동만큼 느리므로 100ms를 빼고 같은 척도로 맞춘다(기본 기준선 차이 450-350과 같은 **임시값**).
         */
        fun scoreOf(medianMs: Long, input: ReactionInput): Int {
            val ms = if (input == ReactionInput.BRAKE) medianMs - 100 else medianMs
            return (100 - (ms - 250) / 4).toInt().coerceIn(0, 100)
        }
    }
}

/** 평소 기록(반응시간 중앙값 목록) 저장소. 터치와 브레이크는 반응 시간이 달라 따로 저장한다 */
private class PrefsReactionRecordStore(context: Context, input: ReactionInput) : ReactionRecordStore {
    private val prefs = context.getSharedPreferences("kd_reaction_baseline", Context.MODE_PRIVATE)

    // 터치는 기존 키를 그대로 써서 이전 기록을 이어 간다
    private val key = when (input) {
        ReactionInput.TOUCH -> "medians"
        ReactionInput.BRAKE -> "medians_brake"
    }

    override fun load(): List<Float> =
        prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.toFloatOrNull() }

    override fun save(medians: List<Float>) {
        prefs.edit().putString(key, medians.joinToString(",")).apply()
    }
}
