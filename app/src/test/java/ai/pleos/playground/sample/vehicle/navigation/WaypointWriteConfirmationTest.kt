package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import org.junit.Assert.*
import org.junit.Test

class WaypointWriteConfirmationTest {
    private val place=RestPlace("favorite","Rest area east",37.24,127.24,"","","","","",poiId="p",poiSubId="east",poiProvider="native")
    private val before=RestNaviSnapshot(NaviDestinationInfo(35.0,129.0,"Destination","","d"),
        NaviRouteState(NaviMode.ROUTE_GUIDING,"RECOMMENDED",0),emptyList(),"old-route")
    private val after=before.copy(routeId="new-route",waypoints=listOf(NaviWaypoint(0.0,0.0,place.name,"p","east","native")))

    @Test fun `missing routing event accepts two matching post dispatch reads`() {
        val verifier=WaypointWriteConfirmation(before,place)
        assertFalse(verifier.observe(after,false))
        assertTrue(verifier.observe(after,false))
    }

    @Test fun `routing event still requires exact waypoint destination option and guiding state`() {
        val invalid=listOf(before,after.copy(destination=before.destination!!.copy(poiId="other")),
            after.copy(state=before.state.copy(routeOption="FREE")),
            after.copy(state=before.state.copy(naviMode=NaviMode.SAFE_GUIDING)),
            after.copy(waypoints=listOf(after.waypoints.single().copy(poiSubId="west"))))
        invalid.forEach { assertFalse(WaypointWriteConfirmation(before,place).observe(it,true)) }
        assertTrue(WaypointWriteConfirmation(before,place).observe(after,true))
    }

    @Test fun `intervening mismatch or changed route id restarts stable read requirement`() {
        val verifier=WaypointWriteConfirmation(before,place)
        assertFalse(verifier.observe(after,false))
        assertFalse(verifier.observe(before,false))
        assertFalse(verifier.observe(after,false))
        val rerouted=after.copy(routeId="another-route")
        assertFalse(verifier.observe(rerouted,false))
        assertTrue(verifier.observe(rerouted,false))
    }
}
