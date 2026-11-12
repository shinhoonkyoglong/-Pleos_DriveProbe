package ai.pleos.playground.sample.vehicle.navigation

import org.junit.Assert.*
import org.junit.Test

class WaypointObservationTest {
    private val place=RestPlace("bookmark","Yongin service area Gangneung",37.24551111,127.24230833,
        "","","","","",poiId="native-poi",poiSubId="south-entry",poiProvider="native")
    private val omitted=NaviWaypoint(0.0,0.0,place.name,place.poiId,place.poiSubId,place.poiProvider)

    @Test fun `omitted routing coordinates match only exact native identity and full name`() {
        assertTrue(RestNavigation.waypointMatches(place,omitted))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(poiId="opposite-facility")))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(poiSubId="north-entry")))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(poiProvider="other-provider")))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(name="Other direction")))
    }

    @Test fun `name alone or placeholder native id cannot verify omitted coordinates`() {
        assertFalse(RestNavigation.waypointMatches(place.copy(poiId=""),omitted.copy(poiId="")))
        assertFalse(RestNavigation.waypointMatches(place.copy(poiId="0"),omitted.copy(poiId="0")))
        assertFalse(RestNavigation.waypointMatches(place,NaviWaypoint(0.0,0.0,place.name)))
    }

    @Test fun `reported distant coordinates reject even exact native identity`() {
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(latitude=35.0,longitude=129.0)))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(latitude=Double.NaN)))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(latitude=127.0,longitude=37.0)))
    }

    @Test fun `display position must agree when routing position is absent`() {
        assertTrue(RestNavigation.waypointMatches(place,omitted.copy(displayLatitude=place.latitude,displayLongitude=place.longitude)))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(displayLatitude=35.0,displayLongitude=129.0)))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(displayLatitude=Double.NaN,displayLongitude=127.0)))
        assertFalse(RestNavigation.waypointMatches(place,omitted.copy(poiId="other",displayLatitude=place.latitude,displayLongitude=place.longitude)))
    }

    @Test fun `normal nearby coordinate verification remains available without native poi metadata`() {
        assertTrue(RestNavigation.waypointMatches(place,NaviWaypoint(place.latitude,place.longitude,place.name)))
    }
}
