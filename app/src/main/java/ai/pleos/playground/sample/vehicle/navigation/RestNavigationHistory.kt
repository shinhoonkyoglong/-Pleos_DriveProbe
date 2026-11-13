package ai.pleos.playground.sample.vehicle.navigation

import org.json.JSONArray
import org.json.JSONObject

/** Stored in the existing actionDetails JSON; no Room schema change or data reset. */
fun RestNavigationResult.toHistoryJson(): JSONObject = JSONObject().apply {
    put("version", 2)
    put("status", status.name)
    put("requestIssued", requestIssued)
    put("source", source)
    put("error", error ?: JSONObject.NULL)
    put("selectionPolicy", "NEXT_FORWARD_REST_STOP_ON_CURRENT_ROUTE")
    put("routeMembershipVerified", proposal != null)
    put("directionMatchVerified", proposal != null)
    put("proposalId", proposal?.id ?: JSONObject.NULL)
    put("proposalPrompt", proposal?.prompt ?: JSONObject.NULL)
    put("proposalCreatedAtMs", proposal?.createdAtMs ?: JSONObject.NULL)
    put("proposalExpiresAtMs", proposal?.expiresAtMs ?: JSONObject.NULL)
    put("routeDataObservedAtMs", proposal?.observedAtMs ?: JSONObject.NULL)
    put("candidate", candidate?.let { c -> JSONObject().apply {
        put("id", c.place.id); put("name", c.place.name)
        put("latitude", c.place.latitude); put("longitude", c.place.longitude)
        put("roadType", c.place.roadType); put("roadName", c.place.roadName)
        put("direction", c.place.direction); put("dataDate", c.place.dataDate)
        put("distanceMeters", c.trip.distanceMeters); put("etaSeconds", c.trip.etaSeconds)
        put("distanceAlongRouteMeters", c.distanceAlongRouteMeters ?: JSONObject.NULL)
        put("tbtOrderIndex", c.routeOrder ?: JSONObject.NULL)
        put("routeMatchMethod", c.verification)
        put("etaSource", "POINT_TO_POINT_NAVI_REQUEST_TRIP")
        put("poiId", c.place.poiId);put("poiProvider",c.place.poiProvider)
        put("facilityKind",c.place.kind.name)
        put("nativePoiAvailable",c.place.poiId.isNotBlank())
        put("isMock", c.trip.isMock)
    } } ?: JSONObject.NULL)
    fun snapshot(value: RestNaviSnapshot?): Any = value?.let { s -> JSONObject().apply {
        put("destination", s.destination?.let { d -> JSONObject().apply {
            put("name", d.name); put("latitude", d.latitude); put("longitude", d.longitude)
            put("poiId", d.poiId); put("isMock", d.isMock)
        } } ?: JSONObject.NULL)
        put("naviMode", s.state.naviMode.name); put("routeOption", s.state.routeOption)
        put("routeId", s.routeId)
        put("waypoints", JSONArray().apply { s.waypoints.forEach { w -> put(JSONObject().apply {
            put("name", w.name); put("latitude", w.latitude); put("longitude", w.longitude)
        }) } })
    } } ?: JSONObject.NULL
    put("before", snapshot(before)); put("after", snapshot(after))
}
