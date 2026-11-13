package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PublicRestStopCatalogTest {
    private val today = LocalDate.of(2026,11,12)
    private val rest = PublicRestStop(ServiceAreaKind.SERVICE_AREA,"용인","50","영동선","고속국도","강릉","하행",37.245583,127.2419801,"2026-09-17")
    private val sleep = rest.copy(kind=ServiceAreaKind.SLEEP_REST_AREA,directionRaw="인천기점 + 강릉종점",latitude=37.28861576,longitude=127.1387267)
    private val rows=listOf(rest,rest.copy(direction="인천",directionRaw="상행",latitude=37.248318),sleep,
        rest.copy(baseName="덕평",direction="",directionRaw="양방향"))
    private val catalog=PublicRestStopCatalog(rows)
    @Test fun explicitDirectionFindsOfficialCoordinatesWithoutNativePoi() {
        val p=catalog.resolve("용인휴게소(강릉방향)",ServiceAreaKind.SERVICE_AREA,today)!!
        assertEquals(rest.latitude,p.latitude,0.00000001);assertEquals("",p.poiId);assertEquals("",p.poiProvider)
        assertTrue(p.id.startsWith("PUBLIC_CATALOG:"));assertEquals("2026-09-17",p.dataDate)
    }
    @Test fun bareNameNeverChoosesOneCarriagewayButOfficialBidirectionalFacilityCanBeUsed() {
        assertNull(catalog.resolve("용인휴게소",ServiceAreaKind.SERVICE_AREA,today))
        assertNotNull(catalog.resolve("덕평휴게소",ServiceAreaKind.SERVICE_AREA,today))
        assertEquals(2,catalog.candidates("용인휴게소",ServiceAreaKind.SERVICE_AREA,today).size)
    }
    @Test fun sleepStopAndServiceAreaWithSameBaseNameDoNotMixCoordinates() {
        val p=catalog.resolve("용인졸음쉼터 강릉방면",ServiceAreaKind.SLEEP_REST_AREA,today)!!
        assertEquals(sleep.latitude,p.latitude,0.00000001);assertEquals(ServiceAreaKind.SLEEP_REST_AREA,p.kind)
        assertNull(catalog.resolve("용인졸음쉼터",ServiceAreaKind.SLEEP_REST_AREA,today))
        assertNull(catalog.resolve("용인휴게소(강릉방향)",ServiceAreaKind.SLEEP_REST_AREA,today))
    }
    @Test fun duplicatesSimilarNamesAndUnknownDirectionDoNotProduceAutomaticMatch() {
        assertNull(PublicRestStopCatalog(rows+rest).resolve("용인휴게소 강릉방향",ServiceAreaKind.SERVICE_AREA,today))
        assertNull(catalog.resolve("신용인휴게소(강릉방향)",ServiceAreaKind.SERVICE_AREA,today))
        assertNull(catalog.resolve("용인휴게소(부산방향)",ServiceAreaKind.SERVICE_AREA,today))
    }
    @Test fun futureOrOldDataCannotBeUsedForAutomaticNavigation() {
        for(date in listOf("2027-01-01","2024-01-01")) {
            try { PublicRestStopCatalog(listOf(rest.copy(dataDate=date))).resolve("용인휴게소(강릉방향)",ServiceAreaKind.SERVICE_AREA,today);fail() }
            catch(e:RestNavigationFailure) { assertEquals(RestNavigationStatus.STALE_ROUTE_DATA,e.status) }
        }
    }
    private inner class Fixture {
        val route=RestNaviSnapshot(NaviDestinationInfo(37.5,127.5,"목적지","","dest"),NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0),emptyList(),"route")
        var description="용인졸음쉼터(강릉방향)"
        var writes=0
        val port=object:RestNavigationPort {
            override suspend fun snapshot()=route
            override suspend fun location()=NaviLocation(37.1,127.1)
            override suspend fun estimate(place:RestPlace)=NaviTripInfo(10000,600,"FREE")
            override suspend fun serviceAreaPlaces():List<RestPlace> = error("No favorites prerequisite")
            override suspend fun automaticServiceAreaPlaces():List<RestPlace> = error("No recents prerequisite")
            override suspend fun tbtRoute()=NaviTbtRoute("route",1000,listOf(
                NaviTbtEntry("TURN_RESTAREA",99,1000.0,description),
                NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,2000.0,"덕평휴게소"),NaviTbtEntry("TURN_ARRIVE",37,4000.0,"도착")))
            override suspend fun addFirstWaypoint(candidate:RestCandidate,expected:RestNaviSnapshot,dispatched:()->Unit):RestNaviSnapshot {
                dispatched();writes++
                return route.copy(waypoints=listOf(NaviWaypoint(candidate.place.latitude,candidate.place.longitude,candidate.place.name)))
            }
        }
        val lookup=PublicRestStopLookup { description,kind -> catalog.resolve(description,kind,today) }
        val navigation=RestNavigation(port,PleosTbtServiceAreaSource(port,publicCatalog=lookup),{1000})
    }
    @Test fun productionFlowProposesFirstSleepStopWithoutFavoritesAndWritesOnlyAfterAcceptance()=runTest {
        val f=Fixture();val p=f.navigation.prepareProposal()
        assertEquals(ServiceAreaKind.SLEEP_REST_AREA,p.candidate.place.kind)
        assertEquals("PLEOS_TBT_PUBLIC_REST_STOP_CATALOG",p.source)
        assertEquals(0,f.writes)
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,f.navigation.accept(p).status)
        assertEquals(1,f.writes)
    }
    @Test fun changedTbtDirectionAfterConsentPreparationDoesNotWrite()=runTest {
        val f=Fixture();val p=f.navigation.prepareProposal();f.description="용인졸음쉼터(인천방향)"
        assertNotEquals(RestNavigationStatus.VERIFIED_WAYPOINT,f.navigation.accept(p).status);assertEquals(0,f.writes)
    }
    @Test fun manualDisambiguationCanUseOfficialCandidatesWithoutFavorites()=runTest {
        val f=Fixture(); f.description="용인졸음쉼터"
        val registry=ReviewedServiceAreas(object:ReviewedServiceAreaStore {
            var rows=emptyList<ReviewedServiceArea>()
            override suspend fun load()=rows
            override suspend fun save(values:List<ReviewedServiceArea>) { rows=values }
        },{1000})
        val review=ServiceAreaReview(f.port,registry,publicCandidates={tbt ->
            catalog.candidates(tbt.nextRestStopHint!!.value.description,ServiceAreaKind.SLEEP_REST_AREA,today)
        })
        val session=review.load();val candidate=session.places.single()
        review.confirm(session,candidate,"영동선","강릉",true)
        assertEquals(candidate,registry.resolve(f.route,session.tbt))
        assertEquals("PUBLIC_CATALOG",session.facilitySource)
        assertEquals(0,f.writes)
    }
}
