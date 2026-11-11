package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import kotlinx.coroutines.CancellationException

/** Shared by real automatic interventions and explicitly selected parked flow tests. */
class InterventionSpeechAction(private val voice: VoiceConversation) {
    suspend fun execute(level: FatigueLevel, phase: (VoicePhase) -> Unit = {}): InterventionResult {
        require(level == FatigueLevel.CAUTION || level == FatigueLevel.DANGER)
        return try {
            val answer = voice.askTimed(prompt(level), phase)
            evaluate(level, answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceFailure) {
            InterventionResult(InterventionOutcome.UNAVAILABLE, e.message ?: "음성 서비스를 사용할 수 없어요",
                error = e.code, platformCode = e.platformCode)
        } catch (e: Exception) {
            InterventionResult(InterventionOutcome.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        fun prompt(level: FatigueLevel): String = when (level) {
            FatigueLevel.CAUTION -> "피로 징후가 감지됐어요. 질문이 끝나면 괜찮아요라고 말씀해 주세요."
            FatigueLevel.DANGER -> "휴식이 필요해 보여요. 쉼터 안내를 원하시면 안내해 줘, 원치 않으시면 아니요라고 말씀해 주세요."
            else -> error("This action only handles CAUTION and DANGER")
        }

        internal fun evaluate(level: FatigueLevel, answer: VoiceAnswer): InterventionResult {
            fun result(outcome: InterventionOutcome, message: String, consent: VoiceConsent? = null,
                navigation: String? = null) = InterventionResult(outcome, message, answer.text, answer.responseMs,
                    answer, consent, navigation)
            if (answer.end == VoiceEnd.TIMEOUT) return result(InterventionOutcome.NO_RESPONSE, "응답 대기 시간이 끝났어요. 자동 경로 변경은 하지 않았어요")
            if (answer.end == VoiceEnd.NO_MATCH || answer.text.isNullOrBlank())
                return result(InterventionOutcome.NO_MATCH, "응답 문장을 인식하지 못했어요. 자동 경로 변경은 하지 않았어요")
            if (level == FatigueLevel.CAUTION)
                return result(InterventionOutcome.ANSWERED, "응답 문장과 발화 시간 추정치를 기록했어요. 피로 점수는 변경하지 않았어요")
            return when (val consent = VoiceResponse.consent(answer.text)) {
                VoiceConsent.ACCEPT -> result(InterventionOutcome.ACCEPTED,
                    "쉼터 안내 동의를 받았어요. 쉼터 검색·내비 연동 준비 중으로 경로는 변경하지 않았어요", consent, "NOT_CONNECTED")
                VoiceConsent.DECLINE -> result(InterventionOutcome.DECLINED, "쉼터 안내를 거절했어요", consent, "NOT_REQUESTED")
                VoiceConsent.UNKNOWN -> result(InterventionOutcome.UNKNOWN_RESPONSE,
                    "안내 동의 여부를 확인하지 못했어요. 경로는 변경하지 않았어요", consent, "NOT_REQUESTED")
            }
        }
    }
}
