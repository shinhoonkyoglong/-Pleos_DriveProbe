package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.navigation.*
import kotlinx.coroutines.CancellationException

class InterventionCancelled(val result: InterventionResult) : CancellationException(result.details)

/** Production only: search -> name/ETA proposal -> explicit consent -> apply that proposal. */
class DangerNavigationAction(
    private val speech: InterventionSpeechAction,
    private val prepare: suspend () -> RestProposal,
    private val accept: suspend (RestProposal) -> RestNavigationResult,
    private val discard: (RestProposal) -> Unit,
) {
    suspend fun execute(level: FatigueLevel): InterventionResult {
        if (level == FatigueLevel.CAUTION) return speech.execute(level)
        require(level == FatigueLevel.DANGER)
        val proposal = try { prepare() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val navigation = RestNavigation.failure(e)
            var voiceFailure: VoiceFailure? = null
            try { speech.announce("휴식이 필요해 보여요. ${navigation.message}") }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: VoiceFailure) { voiceFailure = failure }
            catch (_: Exception) { /* Route failure is preserved even if the notification cannot play. */ }
            return InterventionResult(InterventionOutcome.UNAVAILABLE,navigation.message,
                navigationStatus=navigation.status.name,navigation=navigation,
                error=voiceFailure?.code,platformCode=voiceFailure?.platformCode)
        }
        var response: InterventionResult? = null
        fun navigation(status: RestNavigationStatus,message: String) = RestNavigationResult(status,message,
            proposal.candidate,proposal.before,source=proposal.source,proposal=proposal)
        try {
            val answer = speech.execute(level,promptOverride=proposal.prompt)
            response = answer
            if (answer.outcome != InterventionOutcome.ACCEPTED || answer.consent != VoiceConsent.ACCEPT) {
                val declined = navigation(RestNavigationStatus.NOT_REQUESTED,answer.details)
                return answer.copy(navigationStatus=declined.status.name,navigation=declined)
            }
            val result = accept(proposal)
            return answer.copy(details=result.message,navigationStatus=result.status.name,navigation=result)
        } catch (e: CancellationException) {
            val cancelled = (e as? RestNavigationCancelled)?.navigation ?:
                navigation(RestNavigationStatus.CANCELLED_BEFORE_REQUEST,"휴게소 제안이 취소됐어요. 경로 변경 요청은 보내지 않았어요")
            throw InterventionCancelled((response ?: InterventionResult(InterventionOutcome.CANCELLED,cancelled.message))
                .copy(outcome=InterventionOutcome.CANCELLED,details=cancelled.message,
                    navigationStatus=cancelled.status.name,navigation=cancelled)).apply { initCause(e) }
        } finally { discard(proposal) }
    }
}
