package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.condition.core.Gear
import ai.pleos.playground.sample.vehicle.condition.core.ReactionEvaluator
import ai.pleos.playground.sample.vehicle.condition.core.ReactionGate
import ai.pleos.playground.sample.vehicle.condition.core.ReactionParams
import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase
import ai.pleos.playground.sample.vehicle.condition.core.ReactionRecordStore
import ai.pleos.playground.sample.vehicle.condition.core.ReactionResult
import ai.pleos.playground.sample.vehicle.condition.core.ReactionSession
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.TrialOutcome
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** 반응 체크 화면 상태 */
data class ReactionUiState(
    val phase: ReactionPhase = ReactionPhase.READY,
    val stimulusVisible: Boolean = false,
    val remainingMs: Long = 0,
    val totalMs: Long = 0,
    val tapCount: Int = 0,
    /** 방금 누른 결과(잠깐 보여 주는 피드백) */
    val lastOutcome: TrialOutcome? = null,
    val result: ReactionResult? = null,
    /** 지금 시작할 수 없는 이유. null 이면 시작 가능 */
    val blockedReason: String? = null,
)

/**
 * 출발 전 반응 체크(PVT 축소판) 화면 쪽 연결. 판단은 3번 condition/core 의
 * [ReactionSession]·[ReactionEvaluator]·[ReactionGate]를 그대로 쓴다.
 *
 * 결과 처리:
 * 1. ConditionModule.engine.setPreDrive(verdict) → 다음 주행 피로 점수에 반영
 * 2. Room reaction_scores 에 1건 저장 → 기록·리포트 화면
 * 3. 평소 기록(반응시간 중앙값 목록)은 SharedPreferences 에 저장(ReactionRecordStore)
 */
class ReactionCheckViewModel(application: Application) : AndroidViewModel(application) {

    private val params = ReactionParams()
    private val evaluator = ReactionEvaluator(PrefsReactionRecordStore(application))
    private var session = ReactionSession(params)

    private val _state = MutableStateFlow(ReactionUiState(totalMs = params.durationMs, remainingMs = params.durationMs))
    val state: StateFlow<ReactionUiState> = _state.asStateFlow()

    val personalRecordCount: Int get() = evaluator.recordCount

    /** 화면이 보일 때·매 프레임 불러서 시작 가능 여부를 갱신한다 */
    fun refreshGate() {
        if (_state.value.phase != ReactionPhase.READY) return
        _state.value = _state.value.copy(blockedReason = blockedReason())
    }

    fun start(nowMs: Long) {
        val reason = blockedReason()
        if (reason != null) {
            _state.value = _state.value.copy(blockedReason = reason)
            return
        }
        session = ReactionSession(params)
        session.start(nowMs)
        publish(nowMs)
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

    fun onTap(nowMs: Long) {
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
        session = ReactionSession(params)
        _state.value = ReactionUiState(
            totalMs = params.durationMs,
            remainingMs = params.durationMs,
            blockedReason = blockedReason(),
        )
    }

    private fun publish(nowMs: Long, outcome: TrialOutcome? = _state.value.lastOutcome) {
        _state.value = _state.value.copy(
            phase = session.phase,
            stimulusVisible = session.isStimulusVisible,
            remainingMs = session.remainingMs(nowMs),
            tapCount = session.summary().trials.size,
            lastOutcome = outcome,
            blockedReason = null,
        )
    }

    private fun finish() {
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
        save(result)
    }

    private fun save(result: ReactionResult) {
        val median = result.summary.medianReactionMs ?: return
        val entity = ReactionScoreEntity(
            sessionId = UUID.randomUUID().toString(),
            simpleReactionTimeMs = median,
            reactionScore = scoreOf(median),
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

    private companion object {
        const val TAG = "KdReaction"

        /** 화면·리포트용 0~100 환산(250ms 이하 100점, 650ms 이상 0점). 판정에는 쓰지 않는다 */
        fun scoreOf(medianMs: Long): Int = (100 - (medianMs - 250) / 4).toInt().coerceIn(0, 100)
    }
}

/** 평소 기록(반응시간 중앙값 목록) 저장소 */
private class PrefsReactionRecordStore(context: Context) : ReactionRecordStore {
    private val prefs = context.getSharedPreferences("kd_reaction_baseline", Context.MODE_PRIVATE)

    override fun load(): List<Float> =
        prefs.getString(KEY, "").orEmpty().split(',').mapNotNull { it.toFloatOrNull() }

    override fun save(medians: List<Float>) {
        prefs.edit().putString(KEY, medians.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "medians"
    }
}
