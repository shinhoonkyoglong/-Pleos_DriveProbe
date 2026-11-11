package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class InterventionOutcome { VERIFIED, ANSWERED, ACCEPTED, DECLINED, NO_RESPONSE, NO_MATCH, UNKNOWN_RESPONSE, UNAVAILABLE, FAILED, CANCELLED, SIMULATED }

data class InterventionResult(
    val outcome: InterventionOutcome,
    val details: String,
    val response: String? = null,
    val responseMs: Long? = null,
    val voiceAnswer: VoiceAnswer? = null,
    val consent: VoiceConsent? = null,
    val navigationStatus: String? = null,
    val error: VoiceFailureCode? = null,
    val platformCode: Int? = null,
)

data class InterventionRecord(
    val sessionId: String,
    val condition: ConditionState,
    val simulation: Boolean,
    val result: InterventionResult,
)

data class InterventionStatus(
    val enabled: Boolean = true,
    val running: Boolean = false,
    val sessionId: String? = null,
    val level: FatigueLevel = FatigueLevel.NORMAL,
    val message: String = "유효한 주행 데이터를 기다리고 있어요",
    val result: InterventionResult? = null,
    val historyError: String? = null,
)

/** Owned by an application scope, never by an Activity. All entry points use one dispatcher. */
class InterventionController(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val execute: suspend (FatigueLevel) -> InterventionResult,
    private val save: suspend (InterventionRecord) -> Unit,
    private val policy: InterventionPolicy = InterventionPolicy(),
) {
    private val _status = MutableStateFlow(InterventionStatus())
    val status = _status.asStateFlow()
    private var sessionId: String? = null
    private var action: Job? = null
    private var activeLevel = FatigueLevel.NORMAL
    private var generation = 0
    private val inFlight = mutableSetOf<Job>()
    val isBusy: Boolean get() = inFlight.any { !it.isCompleted }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) cancelAction()
        _status.value = _status.value.copy(enabled = enabled, running = if (enabled) _status.value.running else false)
    }

    fun update(condition: ConditionState, tripActive: Boolean, forward: Boolean, simulation: Boolean) {
        if (!tripActive) {
            if (sessionId != null) reset()
            return
        }
        if (sessionId == null) sessionId = UUID.randomUUID().toString()
        _status.value = _status.value.copy(sessionId = sessionId, level = condition.level)
        if (!_status.value.enabled) return
        if (!forward || !condition.lastWindowValid || condition.level == FatigueLevel.NORMAL) {
            cancelAction()
            return
        }
        if (action?.isActive == true && condition.level <= activeLevel) return
        if (!policy.take(condition.level, condition.lastWindowValid, forward, clock())) return
        cancelAction()
        start(condition, simulation, sessionId!!)
    }

    /** Debug scenarios always use an isolated session and can never invoke real effects. */
    fun simulate(level: FatigueLevel) {
        if (action?.isActive == true || level == FatigueLevel.NORMAL) return
        val condition = ConditionState(level, when (level) {
            FatigueLevel.NORMAL -> 0f
            FatigueLevel.MILD -> 0.35f
            FatigueLevel.CAUTION -> 0.55f
            FatigueLevel.DANGER -> 0.75f
        }, isCalibrating = false, lastWindowValid = true)
        val token = generation
        scope.launch {
            val result = InterventionResult(InterventionOutcome.SIMULATED, "$level 시연: 차량·음성·내비 호출 없음")
            persist(InterventionRecord("simulation-${UUID.randomUUID()}", condition, true, result), token)
            if (token == generation) _status.value = _status.value.copy(message = result.details, result = result)
        }
    }

    fun reset() {
        cancelAction()
        sessionId = null
        policy.reset()
        _status.value = _status.value.copy(sessionId = null, level = FatigueLevel.NORMAL)
    }

    private fun cancelAction() {
        if (action?.isActive != true) return
        generation++
        action?.cancel()
        action = null
        _status.value = _status.value.copy(running = false)
    }

    private fun start(condition: ConditionState, simulation: Boolean, id: String) {
        val token = ++generation
        activeLevel = condition.level
        _status.value = _status.value.copy(running = true, message = "${condition.level} 개입 실행 중", historyError = null)
        val predecessors = inFlight.filter { !it.isCompleted }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                // Cancellation cleanup can suspend while stopping a microphone/SDK session.
                predecessors.forEach { it.join() }
                if (simulation) InterventionResult(InterventionOutcome.SIMULATED, "${condition.level} 시연: 차량·음성·내비 호출 없음")
                else execute(condition.level)
            } catch (e: CancellationException) {
                val cancelled = InterventionResult(InterventionOutcome.CANCELLED, "주행 종료·상태 변경·개입 중지로 취소됨. 이미 적용된 차량 설정은 유지될 수 있음")
                withContext(NonCancellable) { persist(InterventionRecord(id, condition, simulation, cancelled), token) }
                throw e
            } catch (e: Exception) {
                InterventionResult(InterventionOutcome.FAILED, e.message ?: e.javaClass.simpleName)
            }
            withContext(NonCancellable) { persist(InterventionRecord(id, condition, simulation, result), token) }
            if (token == generation) _status.value = _status.value.copy(running = false, message = result.details, result = result)
        }
        action = job
        inFlight += job
        job.invokeOnCompletion { inFlight -= job }
        job.start()
    }

    private suspend fun persist(record: InterventionRecord, token: Int) {
        try {
            save(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token == generation) _status.value = _status.value.copy(historyError = "이력 저장 실패: ${e.message}")
        }
    }
}
