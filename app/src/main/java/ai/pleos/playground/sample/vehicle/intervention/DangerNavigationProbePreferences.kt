package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.navigation.RestNavigationStatus
import android.content.Context
import org.json.JSONObject

class DangerNavigationProbePreferences(context: Context) : DangerNavigationProbeStore {
    private val prefs = context.applicationContext.getSharedPreferences("danger_navigation_probe", Context.MODE_PRIVATE)
    override fun load(): DangerNavigationProbeState? {
        val row = JSONObject(prefs.getString("latest", null) ?: return null)
        return DangerNavigationProbeState(
            runId = row.getString("runId"), running = row.getBoolean("running"),
            phase = if (row.getBoolean("running")) VoicePhase.CHECKING else VoicePhase.FINISHED,
            message = row.getString("message"),
            outcome = row.optString("outcome").takeIf { it.isNotBlank() }?.let(InterventionOutcome::valueOf),
            consent = row.optString("consent").takeIf { it.isNotBlank() }?.let(VoiceConsent::valueOf),
            navigationStatus = row.optString("navigationStatus").takeIf { it.isNotBlank() }?.let(RestNavigationStatus::valueOf),
            requestIssued = row.optBoolean("requestIssued"), savedId = row.optLong("savedId").takeIf { it > 0 },
            interrupted = row.optBoolean("interrupted"), saveError = row.optString("saveError").takeIf { it.isNotBlank() },
            revision = row.optString("revision", "이전 버전"),
        )
    }
    override fun save(state: DangerNavigationProbeState) {
        val row = JSONObject().apply {
            put("runId", state.runId); put("running", state.running); put("message", state.message)
            put("outcome", state.outcome?.name ?: ""); put("consent", state.consent?.name ?: "")
            put("navigationStatus", state.navigationStatus?.name ?: ""); put("requestIssued", state.requestIssued)
            put("savedId", state.savedId ?: 0L); put("interrupted", state.interrupted)
            put("saveError", state.saveError ?: ""); put("revision", state.revision)
        }
        check(prefs.edit().putString("latest", row.toString()).commit()) { "시험 상태 저장 실패" }
    }
}
