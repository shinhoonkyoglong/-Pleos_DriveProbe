package ai.pleos.playground.sample.vehicle.trip.source

import ai.pleos.playground.sample.vehicle.data.local.dao.InterventionHistoryDao
import ai.pleos.playground.sample.vehicle.trip.CompletedTrip
import org.json.JSONObject

/** Read-only adapter from production intervention history to the existing report contract. */
class InterventionRestAdviceSource(private val history: InterventionHistoryDao) : RestAdviceSource {
    override suspend fun countFor(trip: CompletedTrip): RestAdvice {
        // Replay stays explicitly temporary, never a source of production counts.
        if (trip.isDemo) return RestAdvice(0, 0, DataSource.MOCK)
        var count = 0
        var responded = 0
        history.getRestAdviceForTrip(trip.tripId).forEach { row ->
            val details = runCatching { JSONObject(row.actionDetails) }.getOrNull() ?: return@forEach
            // Require an actual JSON boolean: absent/legacy/string flags are not production evidence.
            if (details.opt("simulation") != false) return@forEach
            if (details.optString("source") in EXCLUDED_SOURCES) return@forEach
            count++
            // Recognized speech, including refusal/unclear consent and later route failure/cancellation,
            // is a response. Consent or successful navigation alone is not evidence of speech.
            if (!row.userResponse.isNullOrBlank()) responded++
        }
        return RestAdvice(count, responded, DataSource.REAL)
    }

    private companion object {
        val EXCLUDED_SOURCES = setOf("VOICE_TEST", "DANGER_NAVIGATION_PROBE", "BACKGROUND_STAGE_PROBE")
    }
}
