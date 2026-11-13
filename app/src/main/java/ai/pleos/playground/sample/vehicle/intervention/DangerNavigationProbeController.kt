package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.navigation.RestNavigationStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

const val DANGER_PROBE_REVISION = "DANGER_CONSENT_V1"

data class DangerNavigationProbeState(
    val runId: String = "",
    val running: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    val message: String = "음성 동의 후 실제 경유지를 추가하고 유지하는 정차 시험이에요",
    val outcome: InterventionOutcome? = null,
    val consent: VoiceConsent? = null,
    val navigationStatus: RestNavigationStatus? = null,
    val requestIssued: Boolean = false,
    val savedId: Long? = null,
    val interrupted: Boolean = false,
    val saveError: String? = null,
    val revision: String = DANGER_PROBE_REVISION,
)

interface DangerNavigationProbeStore {
    fun load(): DangerNavigationProbeState?
    fun save(state: DangerNavigationProbeState)
}

/** App-owned real-effects test. Never removes an accepted waypoint or changes engine thresholds. */
class DangerNavigationProbeController(
    private val scope: CoroutineScope,
    private val requireReady: () -> Unit,
    private val begin: () -> Boolean,
    private val finish: (Boolean) -> Unit,
    private val execute: suspend ((VoicePhase) -> Unit) -> InterventionResult,
    private val save: suspend (String, InterventionResult) -> Long,
    private val store: DangerNavigationProbeStore,
) {
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()
    private var job: Job? = null
    val isBusy: Boolean get() = _state.value.running || job?.isCompleted == false

    private fun restore(): DangerNavigationProbeState = try {
        val previous = store.load() ?: DangerNavigationProbeState()
        if (previous.running) previous.copy(running = false, interrupted = true,
            message = "이전 시험 중 앱이 종료됐어요. 경유지 적용 여부를 내비에서 확인해 주세요. 자동 재시도·삭제는 하지 않았어요")
        else previous
    } catch (_: Exception) {
        DangerNavigationProbeState(interrupted = true, message = "이전 결과를 읽지 못했어요. 내비의 경유지를 확인해 주세요")
    }

    private fun publish(value: DangerNavigationProbeState) {
        _state.value = value
        try { store.save(value) } catch (_: Exception) {
            _state.value = value.copy(saveError = "시험 상태 저장 실패. 현재 화면·로그와 내비를 확인해 주세요")
        }
    }

    fun start(): Boolean {
        if (isBusy || !scope.isActive) return false
        val wasEnabled: Boolean
        try {
            requireReady()
            wasEnabled = begin()
        } catch (e: Exception) {
            publish(DangerNavigationProbeState(message = e.message ?: "시험 시작 조건을 확인해 주세요"))
            return false
        }
        val id = "danger-probe-${UUID.randomUUID()}"
        // Persist the running marker before any real audio or route request.
        val initial = DangerNavigationProbeState(runId = id, running = true, phase = VoicePhase.CHECKING,
            message = "실제 경로의 휴게소·이름·예상 시간을 확인하고 있어요")
        try { store.save(initial) } catch (_: Exception) {
            finish(wasEnabled)
            _state.value = initial.copy(running = false, saveError = "시작 기록을 저장하지 못해 시험을 시작하지 않았어요")
            return false
        }
        _state.value = initial
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var result: InterventionResult
            var observedResult: InterventionResult? = null
            try {
                result = withTimeout(90_000L) {
                    try {
                        execute { phase ->
                            publish(_state.value.copy(phase = phase, message = when (phase) {
                                VoicePhase.SPEAKING -> "휴게소 이름·예상 시간 질문 재생 중 — 끝난 뒤 응답해 주세요"
                                VoicePhase.WAITING -> "응답 대기 중 — 추가해 줘 / 아니요라고 말씀해 주세요"
                                VoicePhase.LISTENING -> "발화를 감지했어요. 인식 결과를 기다리고 있어요"
                                VoicePhase.APPLYING_ROUTE -> "동의한 휴게소 경유지의 실제 적용을 확인하고 있어요"
                                else -> "음성·내비 처리를 진행하고 있어요"
                            }))
                        }.also { observedResult = it }
                    } catch (e: InterventionCancelled) {
                        // withTimeout can rethrow its parent cancellation instead of this richer exception.
                        observedResult = e.result
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                result = observedResult ?: (e as? InterventionCancelled)?.result ?: InterventionResult(
                    InterventionOutcome.CANCELLED,
                    "시험이 취소되거나 제한 시간이 지났어요. 경유지 상태를 내비에서 확인해 주세요")
            } catch (e: Exception) {
                result = InterventionResult(InterventionOutcome.FAILED, e.message ?: "통합 시험 오류")
            }
            // Keep ownership until SDK cleanup and one history write have finished, even after cancellation.
            withContext(NonCancellable) {
                try {
                    publish(_state.value.copy(phase = VoicePhase.SAVING, message = "음성·내비 결과를 시험 이력으로 저장하고 있어요"))
                    var savedId: Long? = null
                    var saveError: String? = null
                    try { savedId = withTimeout(10_000L) { save(id, result) } }
                    catch (e: Exception) { saveError = "시험 이력 저장 실패: ${e.message ?: e.javaClass.simpleName}" }
                    publish(_state.value.copy(running = false, phase = VoicePhase.FINISHED,
                        message = result.details, outcome = result.outcome, consent = result.consent,
                        navigationStatus = result.navigation?.status,
                        requestIssued = result.navigation?.requestIssued == true,
                        savedId = savedId, saveError = saveError,
                        interrupted = result.outcome == InterventionOutcome.CANCELLED && result.navigation == null))
                } finally { finish(wasEnabled) }
            }
        }
        return true
    }

    fun cancel() { job?.cancel() }
}
