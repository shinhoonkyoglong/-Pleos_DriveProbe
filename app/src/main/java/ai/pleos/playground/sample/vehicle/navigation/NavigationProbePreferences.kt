package ai.pleos.playground.sample.vehicle.navigation

import android.content.Context
import org.json.JSONObject

/** Compact diagnostic outcome only; no recording, full POI list or route coordinate history. */
class NavigationProbePreferences(context: Context):NavigationProbeStateStore {
    private val preferences=context.applicationContext.getSharedPreferences("navigation_probe_result",Context.MODE_PRIVATE)
    override fun load():NavigationProbeState? {
        val text=preferences.getString("latest",null) ?: return null
        val row=JSONObject(text)
        return NavigationProbeState(row.getString("runId"),row.getBoolean("running"),row.getString("message"),
            row.optString("applicationStatus").takeIf{it.isNotBlank()}?.let{RestNavigationStatus.valueOf(it)},
            row.optString("cleanup").takeIf{it.isNotBlank()}?.let{ProbeCleanup.valueOf(it)},row.optBoolean("interrupted"),
            row.optString("verifierRevision",""))
    }
    override fun save(state:NavigationProbeState) {
        val row=JSONObject().apply {
            put("runId",state.runId);put("running",state.running);put("message",state.message)
            put("applicationStatus",state.applicationStatus?.name ?: "");put("cleanup",state.cleanup?.name ?: "")
            put("interrupted",state.interrupted)
            put("verifierRevision",state.verifierRevision)
        }
        preferences.edit().putString("latest",row.toString()).apply()
    }
}
