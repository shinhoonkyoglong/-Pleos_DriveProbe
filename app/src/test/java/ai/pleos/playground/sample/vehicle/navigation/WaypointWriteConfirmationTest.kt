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

    @Test fun `name only entry is observed after two stable reads and never identity verified`() {
        val named=place.copy(name="기흥휴게소(부산방향)")
        val missing=after.copy(waypoints=listOf(NaviWaypoint(0.0,0.0,named.name)))
        val verifier=WaypointWriteConfirmation(before,named)
        assertFalse(verifier.observe(missing,true))
        assertTrue(verifier.observe(missing,true))
        assertEquals(RestNavigationStatus.WAYPOINT_OBSERVED,verifier.status)
        assertFalse(RestNavigation.waypointMatches(named,missing.waypoints.single()))
        val receipt=ProbeWaypointReceipt(before,named,missing,1000)
        assertTrue(receipt.authorizes(before,named,missing,1001))
        assertFalse(receipt.authorizes(before,named,missing,121001))
        assertFalse(receipt.authorizes(before,named,missing,999))
        assertFalse(receipt.authorizes(before,named,missing.copy(routeId="unrelated"),1001))
        assertFalse(receipt.authorizes(before,named.copy(id="other"),missing,1001))
        assertFalse(receipt.authorizes(before,named,missing.copy(destination=before.destination!!.copy(poiId="other")),1001))
    }

    @Test fun `opposite direction conflicting coordinates partial POI mock and extra waypoints cannot fall back to a name`() {
        val named=place.copy(name="기흥휴게소(부산방향)")
        val entry=NaviWaypoint(0.0,0.0,named.name)
        val invalid=listOf(entry.copy(name="기흥휴게소(서울방향)"),entry.copy(latitude=35.0,longitude=129.0),
            entry.copy(latitude=Double.NaN),entry.copy(poiId="another"),entry.copy(poiProvider="native"),
            entry.copy(displayLatitude=35.0,displayLongitude=129.0))
        invalid.forEach { w ->
            val verifier=WaypointWriteConfirmation(before,named)
            repeat(2) { assertFalse(verifier.observe(after.copy(waypoints=listOf(w)),true)) }
        }
        val missing=after.copy(waypoints=listOf(entry))
        assertEquals(RestNavigationStatus.REQUEST_UNCONFIRMED,waypointApplicationStatus(before,named,missing.copy(state=missing.state.copy(isMock=true))))
        assertEquals(RestNavigationStatus.REQUEST_UNCONFIRMED,waypointApplicationStatus(before,named,missing.copy(waypoints=listOf(entry,entry))))
        assertEquals(RestNavigationStatus.REQUEST_UNCONFIRMED,waypointApplicationStatus(before,named.copy(name="기흥휴게소"),missing.copy(waypoints=listOf(entry.copy(name="기흥휴게소")))))
    }
}
