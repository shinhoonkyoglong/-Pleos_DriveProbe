package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class VoiceTestMode(val prompt: String) {
    PLAYBACK("음성 시험입니다. 이 안내가 들리는지 확인해 주세요."),
    RESPONSE("음성 응답 시험입니다. 질문이 끝나면 괜찮아요라고 말씀해 주세요."),
    CONSENT("쉼터 안내 동의 시험입니다. 원하시면 안내해 줘, 원치 않으시면 아니요라고 말씀해 주세요."),
    TIMING("응답 시간 시험입니다. 질문이 끝나면 괜찮아요라고 말씀해 주세요."),
    CAUTION_FLOW(InterventionSpeechAction.prompt(FatigueLevel.CAUTION)),
    DANGER_FLOW(InterventionSpeechAction.prompt(FatigueLevel.DANGER)),
}

enum class VoiceTestOutcome { PLAYBACK_COMPLETED, ANSWERED, ACCEPTED, DECLINED, UNKNOWN_ANSWER, NO_RESPONSE, NO_MATCH, PERMISSION_REQUIRED, UNAVAILABLE, ERROR, CANCELLED }

data class VoiceTestResult(
    val outcome: VoiceTestOutcome,
    val message: String,
    val answer: VoiceAnswer? = null,
    val error: VoiceFailureCode? = null,
    val platformCode: Int? = null,
    val intervention: InterventionResult? = null,
)

data class VoiceTestRecord(val id: String, val mode: VoiceTestMode, val result: VoiceTestResult)

data class VoiceTestState(
    val allowed: Boolean = false,
    val blockedReason: String = "P 기어·속도 0·화면 사용 가능 상태를 기다리고 있어요",
    val running: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    val mode: VoiceTestMode? = null,
    val message: String = "질문만 재생부터 순서대로 시험해 주세요",
    val capabilities: VoiceCapabilities? = null,
    val result: VoiceTestResult? = null,
    val savedId: Long? = null,
    val saveError: String? = null,
)

/** A manual test records real audio events but never changes vehicle state or routes. */
class VoiceTestController(
    private val scope: CoroutineScope,
    private val voice: VoiceConversation,
    private val save: suspend (VoiceTestRecord) -> Long,
    private val onStarted: () -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    private val _state = MutableStateFlow(VoiceTestState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var cancelReason = "사용자가 시험을 중지했어요"
    private val stageSpeech = InterventionSpeechAction(voice)

    fun refreshCapabilities() {
        _state.value = _state.value.copy(capabilities = runCatching { voice.capabilities() }.getOrNull())
    }

    fun setAllowed(allowed: Boolean, reason: String = "P 기어·속도 0·화면 사용 가능 상태에서만 시험할 수 있어요") {
        _state.value = _state.value.copy(allowed = allowed, blockedReason = reason)
        if (!allowed) cancel("$reason · 시험을 중지했어요")
    }

    fun start(mode: VoiceTestMode): Boolean {
        if (_state.value.running || !_state.value.allowed) return false
        refreshCapabilities()
        val id = "voice-test-${UUID.randomUUID()}"
        _state.value = _state.value.copy(running = true, phase = VoicePhase.CHECKING, mode = mode,
            result = null, savedId = null, saveError = null, message = "음성 서비스를 확인하고 있어요")
        cancelReason = "사용자가 시험을 중지했어요"
        onStarted()
        job = scope.launch {
            try {
                val result = try {
                    if (mode == VoiceTestMode.CAUTION_FLOW || mode == VoiceTestMode.DANGER_FLOW) {
                        val stage = if (mode == VoiceTestMode.CAUTION_FLOW) FatigueLevel.CAUTION else FatigueLevel.DANGER
                        fromStage(stageSpeech.execute(stage, ::phase))
                    } else if (mode == VoiceTestMode.PLAYBACK) {
                        voice.play(mode.prompt, ::phase)
                        VoiceTestResult(VoiceTestOutcome.PLAYBACK_COMPLETED, "재생 완료 콜백을 받았어요. 실제 소리가 들렸는지 직접 확인해 주세요")
                    } else evaluate(mode, if (mode == VoiceTestMode.TIMING) voice.askTimed(mode.prompt, ::phase) else voice.ask(mode.prompt, ::phase))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        finish(VoiceTestRecord(id, mode, VoiceTestResult(VoiceTestOutcome.CANCELLED, cancelReason)))
                    }
                    throw e
                } catch (e: VoiceFailure) {
                    VoiceTestResult(when (e.code) {
                        VoiceFailureCode.PERMISSION -> VoiceTestOutcome.PERMISSION_REQUIRED
                        VoiceFailureCode.NO_RECOGNIZER, VoiceFailureCode.NO_TTS, VoiceFailureCode.TTS_LANGUAGE -> VoiceTestOutcome.UNAVAILABLE
                        else -> VoiceTestOutcome.ERROR
                    }, e.message ?: "음성 서비스 오류", error = e.code, platformCode = e.platformCode)
                } catch (e: Exception) {
                    VoiceTestResult(VoiceTestOutcome.ERROR, e.message ?: e.javaClass.simpleName)
                }
                withContext(NonCancellable) { finish(VoiceTestRecord(id, mode, result)) }
            } finally {
                _state.value = _state.value.copy(running = false, phase = VoicePhase.FINISHED)
                onFinished()
            }
        }
        return true
    }

    fun cancel(reason: String = "사용자가 시험을 중지했어요") {
        if (!_state.value.running || job?.isActive != true) return
        cancelReason = reason
        job?.cancel()
    }

    private fun phase(phase: VoicePhase) {
        _state.value = _state.value.copy(phase = phase, message = when (phase) {
            VoicePhase.PREPARING -> "한국어 재생·인식 서비스를 준비하고 있어요. 응답 대기 표시 후 말씀해 주세요"
            VoicePhase.SPEAKING -> "질문 재생 중 — 끝날 때까지 기다려 주세요"
            VoicePhase.WAITING -> "응답 대기 중 — 지금 말씀해 주세요 (최대 10초)"
            VoicePhase.LISTENING -> "발화를 감지했어요. 인식 결과를 기다리고 있어요"
            else -> "음성 서비스를 확인하고 있어요"
        })
    }

    private suspend fun finish(record: VoiceTestRecord) {
        _state.value = _state.value.copy(phase = VoicePhase.SAVING, message = "시험 결과를 저장하고 있어요", result = record.result)
        try {
            val id = save(record)
            _state.value = _state.value.copy(savedId = id, message = record.result.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(saveError = "시험 이력 저장 실패: ${e.message}", message = record.result.message)
        }
    }

    companion object {
        internal fun fromStage(result: InterventionResult): VoiceTestResult = VoiceTestResult(
            when (result.outcome) {
                InterventionOutcome.ANSWERED -> VoiceTestOutcome.ANSWERED
                InterventionOutcome.ACCEPTED -> VoiceTestOutcome.ACCEPTED
                InterventionOutcome.DECLINED -> VoiceTestOutcome.DECLINED
                InterventionOutcome.NO_RESPONSE -> VoiceTestOutcome.NO_RESPONSE
                InterventionOutcome.NO_MATCH -> VoiceTestOutcome.NO_MATCH
                InterventionOutcome.UNKNOWN_RESPONSE -> VoiceTestOutcome.UNKNOWN_ANSWER
                InterventionOutcome.UNAVAILABLE -> if (result.error == VoiceFailureCode.PERMISSION)
                    VoiceTestOutcome.PERMISSION_REQUIRED else VoiceTestOutcome.UNAVAILABLE
                else -> VoiceTestOutcome.ERROR
            }, result.details, result.voiceAnswer, result.error, result.platformCode, result,
        )

        internal fun evaluate(mode: VoiceTestMode, answer: VoiceAnswer): VoiceTestResult {
            if (answer.end == VoiceEnd.TIMEOUT) return VoiceTestResult(VoiceTestOutcome.NO_RESPONSE, "응답 대기 시간이 끝났어요", answer)
            if (answer.end == VoiceEnd.NO_MATCH || answer.text.isNullOrBlank()) return VoiceTestResult(VoiceTestOutcome.NO_MATCH, "응답 문장을 인식하지 못했어요", answer)
            if (mode == VoiceTestMode.RESPONSE || mode == VoiceTestMode.TIMING) return VoiceTestResult(VoiceTestOutcome.ANSWERED, "응답 문장을 인식했어요", answer)
            return when (VoiceResponse.consent(answer.text)) {
                VoiceConsent.ACCEPT -> VoiceTestResult(VoiceTestOutcome.ACCEPTED, "안내 동의를 인식했어요. 시험에서는 경로를 변경하지 않아요", answer)
                VoiceConsent.DECLINE -> VoiceTestResult(VoiceTestOutcome.DECLINED, "안내 거절을 인식했어요", answer)
                VoiceConsent.UNKNOWN -> VoiceTestResult(VoiceTestOutcome.UNKNOWN_ANSWER, "동의·거절을 명확히 구분하지 못했어요", answer)
            }
        }
    }
}
