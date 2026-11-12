package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceAreaWriteProbeTest {
    private val place=RestPlace("test","시험휴게소",37.41,127.1,"","","","","")
    private val initial=RestNaviSnapshot(NaviDestinationInfo(37.55,126.97,"원래 목적지","","poi"),NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0),emptyList(),"route-A")
    private inner class Fixture {
        var parked=true
        var enabled=true
        var starts=0
        var finishes=0
        var writes=0
        var removes=0
        var snapshotCount=0
        var changeRouteAtSnapshot:Int?=null
        var beforeWrite:()->Unit={}
        var awaitAfterWrite:suspend ()->Unit={}
        var removeAction:suspend (()->Unit)->RestNaviSnapshot={guard->guard();removes++;initial}
        val port=object:RestNavigationPort {
            override suspend fun snapshot():RestNaviSnapshot {
                snapshotCount++
                return if(changeRouteAtSnapshot?.let{snapshotCount>=it}==true)initial.copy(routeId="route-B") else initial
            }
            override suspend fun location()=NaviLocation(37.4,127.1)
            override suspend fun estimate(place:RestPlace):NaviTripInfo=error("not used")
            override suspend fun addFirstWaypoint(candidate:RestCandidate,expected:RestNaviSnapshot,dispatched:()->Unit):RestNaviSnapshot {
                beforeWrite();dispatched();writes++;awaitAfterWrite()
                return initial.copy(waypoints=listOf(NaviWaypoint(place.latitude,place.longitude,place.name)))
            }
        }
        val nav=RestNavigation(port,RouteServiceAreaSource{_,_->
            AlongRouteServiceAreas("route-A",1000,true,listOf(RouteServiceArea(place,1000,120,true,true)),"SYNTHETIC_TEST")
        },{1000})
        val probe=ServiceAreaWriteProbe(nav,{p,before,guard->
            assertEquals(place,p);assertEquals(initial,before);removeAction(guard)
        },{check(parked){"P and speed zero required"}},
            {starts++;val previous=enabled;enabled=false;previous},
            {previous->finishes++;enabled=previous})
    }

    @Test fun `same process registered proposal is applied removed and previous automatic setting restored`()=runTest {
        val f=Fixture();val result=f.probe.run()
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,result.application!!.status)
        assertEquals(ProbeCleanup.VERIFIED,result.cleanup)
        assertEquals(1,f.writes);assertEquals(1,f.removes)
        assertEquals(1,f.starts);assertEquals(1,f.finishes);assertTrue(f.enabled)
        f.enabled=false;f.probe.run();assertFalse(f.enabled)
    }

    @Test fun `route id mismatch is still rejected without rebinding or dispatch`()=runTest {
        val f=Fixture();f.changeRouteAtSnapshot=3
        val result=f.probe.run()
        assertEquals(RestNavigationStatus.CONTEXT_CHANGED,result.application!!.status)
        assertFalse(result.application!!.requestIssued)
        assertEquals(ProbeCleanup.NOT_NEEDED,result.cleanup)
        assertEquals(0,f.writes);assertEquals(0,f.removes);assertTrue(f.enabled)
    }

    @Test fun `not parked cannot start or pause automatic interventions`()=runTest {
        val f=Fixture();f.parked=false
        val result=f.probe.run()
        assertNull(result.application);assertEquals(0,f.starts);assertEquals(0,f.finishes)
        assertEquals(0,f.writes);assertTrue(f.enabled)
    }

    @Test fun `leaving parked state at final dispatch boundary prevents the write`()=runTest {
        val f=Fixture();f.beforeWrite={f.parked=false}
        val result=f.probe.run()
        assertFalse(result.application!!.requestIssued)
        assertEquals(ProbeCleanup.NOT_NEEDED,result.cleanup)
        assertEquals(0,f.writes);assertEquals(0,f.removes);assertTrue(f.enabled)
    }

    @Test fun `failed cleanup is visible and never reported as restored`()=runTest {
        val f=Fixture();f.removeAction={throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED,"User changed the route")}
        val result=f.probe.run()
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,result.application!!.status)
        assertEquals(ProbeCleanup.FAILED,result.cleanup)
        assertTrue(result.message.contains("내비 화면"));assertEquals(0,f.removes);assertTrue(f.enabled)
    }

    @Test fun `cleanup readback mismatch is not a successful restoration`()=runTest {
        val f=Fixture();f.removeAction={guard->guard();f.removes++;initial.copy(state=initial.state.copy(routeOption="RECOMMENDED"))}
        val result=f.probe.run()
        assertEquals(ProbeCleanup.FAILED,result.cleanup);assertEquals(1,f.removes);assertTrue(f.enabled)
    }

    @Test fun `cancellation after dispatch waits for noncancellable cleanup and restores automatic state`()=runTest {
        val f=Fixture();f.awaitAfterWrite={awaitCancellation()}
        var report:ServiceAreaProbeReport?=null
        val job=launch{try{f.probe.run()}catch(e:ServiceAreaProbeCancelled){report=e.report;throw e}}
        runCurrent();assertEquals(1,f.writes);job.cancelAndJoin()
        assertEquals(RestNavigationStatus.CANCELLED_AFTER_REQUEST_UNCONFIRMED,report!!.application!!.status)
        assertEquals(ProbeCleanup.VERIFIED,report!!.cleanup)
        assertEquals(1,f.removes);assertEquals(1,f.finishes);assertTrue(f.enabled)
    }

    @Test fun `parallel probe and observer failure cannot cause duplicate writes or skip cleanup`()=runTest {
        val f=Fixture();val wait=CompletableDeferred<Unit>();f.awaitAfterWrite={wait.await()}
        val first=async{f.probe.run{error("Broken observer")}};runCurrent()
        val second=f.probe.run()
        assertNull(second.application);assertEquals(1,f.starts)
        wait.complete(Unit)
        assertEquals(ProbeCleanup.VERIFIED,first.await().cleanup)
        assertEquals(1,f.writes);assertEquals(1,f.removes)
    }
}
