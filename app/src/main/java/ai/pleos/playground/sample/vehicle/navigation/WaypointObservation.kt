package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.NaviMode

const val WAYPOINT_VERIFICATION_REVISION = "STABLE_POI_V2"

/** Only post-dispatch reads are supplied. An event cannot substitute for verified native state. */
class WaypointWriteConfirmation(private val original: RestNaviSnapshot, private val place: RestPlace) {
    private var previousMatch: RestNaviSnapshot? = null
    private var reads=0
    private var matchedReads=0
    private var routeChanges=0
    private var mismatches=0
    var diagnostic: String = "verifier=$WAYPOINT_VERIFICATION_REVISION reads=0"
        private set

    fun observe(observed: RestNaviSnapshot, routingCompleted: Boolean): Boolean {
        reads++
        val matches=original.waypoints.isEmpty() && !observed.state.isMock &&
            RestNavigation.sameDestination(original.destination,observed.destination) &&
            observed.state.naviMode==NaviMode.ROUTE_GUIDING &&
            observed.state.routeOption==original.state.routeOption && observed.waypoints.size==1 &&
            RestNavigation.waypointMatches(place,observed.waypoints.singleOrNull())
        if(!matches) {
            mismatches++;previousMatch=null
            diagnostic="verifier=$WAYPOINT_VERIFICATION_REVISION reads=$reads matches=$matchedReads mismatches=$mismatches routeChanges=$routeChanges decision=STATE_MISMATCH"
            return false
        }
        matchedReads++
        val stable=previousMatch?.routeId==observed.routeId
        if(previousMatch!=null && !stable) routeChanges++
        previousMatch=observed
        diagnostic="verifier=$WAYPOINT_VERIFICATION_REVISION reads=$reads matches=$matchedReads mismatches=$mismatches routeChanges=$routeChanges decision=${if(routingCompleted || stable) "VERIFIED" else "WAITING_STABLE_ROUTE"}"
        return routingCompleted || stable
    }
}

/** Diagnostic only: does not relax verification or authorize cleanup. */
fun waypointObservationDiagnostic(original: RestNaviSnapshot, place: RestPlace, observed: RestNaviSnapshot): String {
    val reasons = buildList {
        if (!RestNavigation.sameDestination(original.destination, observed.destination)) add("DESTINATION")
        if (original.state.routeOption != observed.state.routeOption) add("ROUTE_OPTION")
        if (original.state.naviMode != observed.state.naviMode) add("NAVI_MODE")
        if (observed.state.isMock) add("MOCK")
        if (original.waypoints.isNotEmpty()) add("ORIGINAL_WAYPOINTS")
        if (observed.waypoints.size != 1) add("WAYPOINT_COUNT")
        else if (!RestNavigation.waypointMatches(place, observed.waypoints.first())) add("WAYPOINT_COORDINATES")
    }
    val waypoint=observed.waypoints.singleOrNull()
    val distance = waypoint?.takeIf { RestNavigation.validCoordinates(it.latitude,it.longitude) }?.let {
        RestNavigation.distanceMeters(place.latitude, place.longitude, it.latitude, it.longitude)
    }?.takeIf { it.isFinite() }?.toInt()?.toString() ?: "NA"
    return "reasons=${reasons.joinToString(",").ifEmpty { "NONE" }} " +
        "mode=${original.state.naviMode}->${observed.state.naviMode} " +
        "option=${original.state.routeOption}->${observed.state.routeOption} " +
        "waypoints=${observed.waypoints.size} waypointDeltaMeters=$distance " +
        "positionAvailable=${waypoint?.let { RestNavigation.validCoordinates(it.latitude,it.longitude) } ?: false} " +
        "poiIdMatch=${place.poiId.isNotBlank() && place.poiId==waypoint?.poiId} " +
        "poiSubIdMatch=${waypoint?.poiSubId==place.poiSubId} providerMatch=${waypoint?.poiProvider==place.poiProvider} " +
        "nameMatch=${waypoint?.name?.trim()==place.name.trim()}"
}
