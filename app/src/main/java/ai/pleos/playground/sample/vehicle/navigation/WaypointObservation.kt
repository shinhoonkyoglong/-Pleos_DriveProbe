package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.NaviMode

const val WAYPOINT_VERIFICATION_REVISION = "GRADED_READBACK_V3"

/** A named entry proves creation, not physical location. Contradictory metadata never falls back. */
fun waypointApplicationStatus(original: RestNaviSnapshot, place: RestPlace, observed: RestNaviSnapshot): RestNavigationStatus {
    if(original.waypoints.isNotEmpty() || observed.state.isMock ||
        !RestNavigation.sameDestination(original.destination,observed.destination) ||
        observed.state.naviMode!=NaviMode.ROUTE_GUIDING || observed.state.routeOption!=original.state.routeOption ||
        observed.waypoints.size!=1) return RestNavigationStatus.REQUEST_UNCONFIRMED
    val waypoint=observed.waypoints.single()
    if(RestNavigation.waypointMatches(place,waypoint)) return RestNavigationStatus.VERIFIED_WAYPOINT
    val identityMissing=listOf(waypoint.poiId,waypoint.poiSubId,waypoint.poiProvider).all { it.isBlank() || it=="0" }
    val coordinatesMissing=waypoint.latitude==0.0 && waypoint.longitude==0.0 &&
        (waypoint.displayLatitude==null || waypoint.displayLatitude==0.0) &&
        (waypoint.displayLongitude==null || waypoint.displayLongitude==0.0)
    return if(identityMissing && coordinatesMissing && observed.routeId.isNotBlank() &&
        RestNavigation.validCoordinates(place.latitude,place.longitude) && directedServiceAreaName(place.name)!=null &&
        waypoint.name.trim()==place.name.trim()) RestNavigationStatus.WAYPOINT_OBSERVED else RestNavigationStatus.REQUEST_UNCONFIRMED
}

/** In-memory ownership receipt, only for removing this process's diagnostic write. */
data class ProbeWaypointReceipt(val original: RestNaviSnapshot,val place: RestPlace,val observed: RestNaviSnapshot,val createdAtMs: Long) {
    fun authorizes(requestedOriginal: RestNaviSnapshot, requestedPlace: RestPlace,current: RestNaviSnapshot,now:Long): Boolean =
        now-createdAtMs in 0..120_000 && requestedPlace==place && requestedOriginal.waypoints.isEmpty() &&
            RestNavigation.sameDestination(original.destination,requestedOriginal.destination) &&
            original.state.routeOption==requestedOriginal.state.routeOption &&
            current.routeId.isNotBlank() && current.routeId==observed.routeId && current.waypoints==observed.waypoints &&
            waypointApplicationStatus(original,place,current) in setOf(RestNavigationStatus.VERIFIED_WAYPOINT,RestNavigationStatus.WAYPOINT_OBSERVED)
}

/** Only post-dispatch reads are supplied. An event cannot substitute for verified native state. */
class WaypointWriteConfirmation(private val original: RestNaviSnapshot, private val place: RestPlace) {
    private var previousMatch: RestNaviSnapshot? = null
    private var reads=0
    private var matchedReads=0
    private var routeChanges=0
    private var mismatches=0
    var diagnostic: String = "verifier=$WAYPOINT_VERIFICATION_REVISION reads=0"
        private set
    var status: RestNavigationStatus = RestNavigationStatus.REQUEST_UNCONFIRMED
        private set

    fun observe(observed: RestNaviSnapshot, routingCompleted: Boolean): Boolean {
        reads++
        val application=waypointApplicationStatus(original,place,observed)
        val matches=application!=RestNavigationStatus.REQUEST_UNCONFIRMED
        if(!matches) {
            mismatches++;previousMatch=null;status=RestNavigationStatus.REQUEST_UNCONFIRMED
            diagnostic="verifier=$WAYPOINT_VERIFICATION_REVISION reads=$reads matches=$matchedReads mismatches=$mismatches routeChanges=$routeChanges decision=STATE_MISMATCH"
            return false
        }
        matchedReads++
        val stable=previousMatch?.let { it.routeId==observed.routeId && it.waypoints==observed.waypoints } == true
        if(previousMatch!=null && !stable) routeChanges++
        previousMatch=observed
        // Missing identity always needs two stable reads, even when a routing event arrives.
        val confirmed=stable || (routingCompleted && application==RestNavigationStatus.VERIFIED_WAYPOINT)
        status=if(confirmed) application else RestNavigationStatus.REQUEST_UNCONFIRMED
        diagnostic="verifier=$WAYPOINT_VERIFICATION_REVISION reads=$reads matches=$matchedReads mismatches=$mismatches routeChanges=$routeChanges decision=${if(confirmed) application.name else "WAITING_STABLE_ROUTE"}"
        return confirmed
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
