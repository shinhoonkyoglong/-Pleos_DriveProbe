package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AutomaticServiceAreaResolverTest {
    private val place = RestPlace("NAVI_RECENT:provider:poi", "용인휴게소(강릉방향)", 37.2, 127.3,
        "", "", "", "주소", "", "poi", "1", "provider")
    @Test fun explicitDirectionalNativeLabelsMatchWithoutManualRegistration() {
        assertEquals("강릉", AutomaticServiceAreaResolver.resolve("용인휴게소 강릉방면", listOf(place))!!.direction)
        assertNotNull(AutomaticServiceAreaResolver.resolve("용인휴게소[강릉방향]", listOf(place)))
    }
    @Test fun baseNameEvenWithOnlyOneFacilityNeverProvesDirection() {
        assertNull(AutomaticServiceAreaResolver.resolve("용인휴게소", listOf(place)))
        assertNull(AutomaticServiceAreaResolver.resolve("용인휴게소(강릉)", listOf(place)))
    }
    @Test fun oppositeDirectionAndSimilarNamesAreRejected() {
        assertNull(AutomaticServiceAreaResolver.resolve("용인휴게소 인천방향", listOf(place)))
        assertNull(AutomaticServiceAreaResolver.resolve("신용인휴게소 강릉방향", listOf(place)))
    }
    @Test fun duplicateFavoritesAndRecentsOfTheExactSameFacilityAreDeduplicated() {
        assertNotNull(AutomaticServiceAreaResolver.resolve(place.name, listOf(place, place.copy(id="NAVI_BOOKMARK:provider:poi"))))
    }
    @Test fun ambiguousPoiSubIdsAndCoordinatesAreNotAutomaticallySelected() {
        for (other in listOf(place.copy(poiId="another"),place.copy(poiSubId="2"),place.copy(latitude=37.21)))
            assertNull(AutomaticServiceAreaResolver.resolve(place.name, listOf(place,other)))
    }
    @Test fun missingNativeIdentityAndInvalidCoordinatesAreRejected() {
        for (invalid in listOf(place.copy(id="external-guess"),place.copy(poiId=""),place.copy(poiId="0"),
            place.copy(poiProvider=""),place.copy(latitude=0.0,longitude=0.0),place.copy(latitude=Double.NaN)))
            assertNull(AutomaticServiceAreaResolver.resolve(place.name,listOf(invalid)))
    }
    private inner class Fixture {
        var facilities = listOf(place)
        var writes = 0
        var description = place.name
        val route = RestNaviSnapshot(NaviDestinationInfo(37.5,127.5,"목적지","","dest"),
            NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0), emptyList(),"route")
        val port = object: RestNavigationPort {
            override suspend fun snapshot() = route
            override suspend fun location() = NaviLocation(37.1,127.1)
            override suspend fun estimate(place:RestPlace) = NaviTripInfo(10_000,600,"FREE")
            override suspend fun automaticServiceAreaPlaces() = facilities
            override suspend fun tbtRoute() = NaviTbtRoute("route",1000,listOf(
                NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1000.0,description), NaviTbtEntry("TURN_ARRIVE",37,5000.0,"도착")))
            override suspend fun addFirstWaypoint(candidate:RestCandidate,expected:RestNaviSnapshot,dispatched:()->Unit):RestNaviSnapshot {
                dispatched();writes++
                return route.copy(waypoints=listOf(NaviWaypoint(candidate.place.latitude,candidate.place.longitude,candidate.place.name)))
            }
        }
        val navigation = RestNavigation(port,PleosTbtServiceAreaSource(port),{1000})
    }
    @Test fun productionProposalUsesAutomaticDiscoveryAndStillRequiresSeparateAcceptance() = runTest {
        val f=Fixture();val proposal=f.navigation.prepareProposal()
        assertEquals(0,f.writes)
        assertEquals("NATIVE_TBT_EXPLICIT_DIRECTION",proposal.candidate.verification)
        assertEquals("PLEOS_TBT_AUTOMATIC_NATIVE_FACILITY",proposal.source)
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,f.navigation.accept(proposal).status)
        assertEquals(1,f.writes)
    }
    @Test fun changedFacilityAfterProposalIsNotReplacedOrWritten() = runTest {
        val f=Fixture();val proposal=f.navigation.prepareProposal()
        f.facilities=listOf(place.copy(id="NAVI_RECENT:new",poiId="other"))
        assertEquals(RestNavigationStatus.CONTEXT_CHANGED,f.navigation.accept(proposal).status)
        assertEquals(0,f.writes)
    }
}
