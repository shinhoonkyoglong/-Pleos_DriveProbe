package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TbtRouteTest {
    @Test fun `native plain parenthesized and unparenthesized direction labels share a base name`() {
        for(name in listOf("용인휴게소","용인휴게소(강릉방향)","용인휴게소 강릉방면","용인 휴게소 강릉 방향",
            "용인휴게소 인천방면","용인휴게소 [강릉방향]","용인휴게소(강릉방향) 방면")) {
            assertEquals(name,"용인휴게소",serviceAreaBaseName(name))
            assertEquals(name,TbtFacilityKind.SERVICE_AREA,NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1.0,name).facilityKind())
        }
        assertNull(recognizedServiceAreaBaseName("용인휴게소 근처"))
        assertNull(recognizedServiceAreaBaseName("용인휴게소 추천"))
        assertNotEquals(serviceAreaBaseName("용인휴게소 강릉방면"),serviceAreaBaseName("덕평휴게소 강릉방면"))
    }

    @Test fun `observed code 41 Hi-Pass center is excluded and Yongin precedes Deokpyeong`() {
        val route=NaviTbtRoute("test-route",0,listOf(
            NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,2600.0,"하이패스센터"),
            NaviTbtEntry("TURN_RESTAREA",99,3800.0,"용인졸음쉼터"),
            NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,8200.0,"용인휴게소"),
            NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1700.0,"덕평휴게소"),
            NaviTbtEntry("TURN_ARRIVE",37,10.0,"도착")))
        assertEquals(TbtFacilityKind.NON_REST_FACILITY,route.entries.first().facilityKind())
        assertEquals(2,route.serviceAreaHints.size)
        assertEquals("용인휴게소",route.nextServiceAreaHint!!.value.description)
        assertEquals(2,route.nextServiceAreaHint!!.index)
        assertTrue(route.reachesArrival)
    }

    @Test fun `an unknown earlier service facility prevents claiming a later rest stop is next`() {
        val unknown=NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1000.0,"알 수 없는 시설")
        val known=NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1000.0,"시험휴게소")
        val route=NaviTbtRoute("test-route",0,listOf(unknown,known))
        assertEquals(1,route.serviceAreaHints.size)
        assertEquals(1,route.unclassifiedServiceFacilities.size)
        assertNull(route.nextServiceAreaHint)
        assertEquals(known,route.copy(entries=listOf(known,unknown)).nextServiceAreaHint!!.value)
    }

    @Test fun `direction suffixes are retained while generic facility descriptions stay unknown`() {
        assertEquals(TbtFacilityKind.SERVICE_AREA,NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1.0,"용인휴게소 (강릉방향)").facilityKind())
        assertEquals(TbtFacilityKind.NON_REST_FACILITY,NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1.0,"하이패스 센터").facilityKind())
        assertEquals(TbtFacilityKind.UNKNOWN_SERVICE_FACILITY,NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1.0,"휴게소 근처").facilityKind())
    }

    @Test fun `service area hints preserve returned order rather than minimum segment distance`() {
        val route=NaviTbtRoute("route-A",0,listOf(
            NaviTbtEntry("TURN_LEFT",3,80.0,"좌회전"),
            NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,8200.0,"용인휴게소"),
            NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1700.0,"덕평휴게소")))
        assertEquals(listOf(1,2),route.serviceAreaHints.map{it.index})
        assertEquals("용인휴게소",route.serviceAreaHints.first().value.description)
    }

    @Test fun `sleep rest area text and mismatching type codes do not qualify as service areas`() {
        val route=NaviTbtRoute("route-A",0,listOf(
            NaviTbtEntry("TURN_RESTAREA",99,3800.0,"용인졸음쉼터"),
            NaviTbtEntry("TURN_LEFT",3,10.0,"휴게소 근처"),
            NaviTbtEntry("TURN_UNKNOWN",41,10.0,"unknown")))
        assertTrue(route.serviceAreaHints.isEmpty())
        assertEquals(3,route.entries.size)
    }

    @Test fun `real TBT hint cannot fabricate coordinates ETA or a writable proposal`()=runTest {
        val state=RestNaviSnapshot(NaviDestinationInfo(37.55,126.97,"목적지","","poi"),NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0),emptyList(),"route-A")
        var writes=0
        val port=object:RestNavigationPort {
            override suspend fun snapshot()=state
            override suspend fun location()=NaviLocation(37.4,127.1)
            override suspend fun estimate(place:RestPlace):NaviTripInfo=error("No invented coordinates")
            override suspend fun addFirstWaypoint(candidate:RestCandidate,expected:RestNaviSnapshot,dispatched:()->Unit):RestNaviSnapshot{writes++;error("No write")}
            override suspend fun tbtRoute()=NaviTbtRoute("route-A",0,listOf(
                NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,2600.0,"하이패스센터"),
                NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,8200.0,"용인휴게소")))
        }
        val navigation=RestNavigation(port,PleosTbtServiceAreaSource(port),{0})
        try{navigation.prepareProposal();fail("TBT has no coordinate/ETA fields")}
        catch(e:RestNavigationFailure){
            assertEquals(RestNavigationStatus.SERVICE_AREA_DETAILS_UNAVAILABLE,e.status)
            assertTrue(e.message!!.contains("용인휴게소"))
            assertFalse(e.message!!.contains("하이패스센터"))
        }
        assertEquals(0,writes)
    }
}
