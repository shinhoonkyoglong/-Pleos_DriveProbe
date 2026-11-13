package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestNavigationTest {
    private val place = RestPlace("service-area-A","합성 시험 휴게소",37.41,127.1,"고속국도","시험도로","순방향","","")
    private val original = NaviDestinationInfo(37.55,126.97,"기존 목적지","","original-poi")
    private val initial = RestNaviSnapshot(original,NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0),emptyList(),"route-A")
    private inner class Fixture {
        var now=100_000L
        var state=initial
        var location=NaviLocation(37.4,127.1)
        var writes=0
        var expectedWriteRouteId:String?=null
        var estimates=0
        var queries=0
        var write: (suspend (RestCandidate,()->Unit)->RestNaviSnapshot)?=null
        var batch=AlongRouteServiceAreas("route-A",now,true,listOf(area()),"SYNTHETIC_TEST_SOURCE")
        val port=object:RestNavigationPort {
            override suspend fun snapshot()=state
            override suspend fun location()=location
            override suspend fun estimate(place:RestPlace):NaviTripInfo { estimates++; error("Point ETA is not route membership") }
            override suspend fun addFirstWaypoint(candidate:RestCandidate,expected:RestNaviSnapshot,dispatched:()->Unit):RestNaviSnapshot {
                expectedWriteRouteId=expected.routeId
                write?.let { return it(candidate,dispatched) }
                dispatched();writes++
                return state.copy(waypoints=listOf(NaviWaypoint(candidate.place.latitude,candidate.place.longitude,candidate.place.name)))
            }
        }
        val nav=RestNavigation(port,RouteServiceAreaSource { _,_->queries++;batch },{now})
    }
    private fun area(distance:Int=3_000,eta:Long=480,place:RestPlace=this.place)=RouteServiceArea(place,distance,eta,true,true)
    private suspend fun status(block:suspend ()->Unit):RestNavigationStatus {
        try { block();fail("Expected a source validation failure") } catch(e:RestNavigationFailure){return e.status}
        error("unreachable")
    }

    @Test fun `selects first forward route distance rather than fastest ETA or straight distance`()=runTest {
        val f=Fixture()
        f.batch=f.batch.copy(areas=listOf(area(8_000,120,place.copy(id="fast",name="더 먼 휴게소",latitude=37.40001)),area(3_000,480)))
        val p=f.nav.prepareProposal()
        assertEquals(place,p.candidate.place)
        assertTrue(p.prompt.contains("합성 시험 휴게소"));assertTrue(p.prompt.contains("8분"))
        assertEquals(0,f.writes);assertEquals(0,f.estimates)
    }

    @Test fun `includes sleep rest areas but excludes other facilities opposite direction off route and already passed`()=runTest {
        val f=Fixture()
        f.batch=f.batch.copy(areas=listOf(area(10).copy(kind=ServiceAreaKind.SLEEP_REST_AREA),area(20).copy(sameDirection=false),
            area(30).copy(onCurrentRoute=false),area(-10),area(0),area(3_000)))
        assertEquals(10,f.nav.prepareProposal().candidate.distanceAlongRouteMeters)
        f.batch=f.batch.copy(areas=f.batch.areas.map { if(it.kind==ServiceAreaKind.SLEEP_REST_AREA) it.copy(kind=ServiceAreaKind.OTHER) else it })
        assertEquals(3_000,f.nav.prepareProposal().candidate.distanceAlongRouteMeters)
    }

    @Test fun `production missing source fails explicitly without catalog fallback`()=runTest {
        val f=Fixture()
        val nav=RestNavigation(f.port,UnavailableRouteServiceAreaSource(),{f.now})
        assertEquals(RestNavigationStatus.ROUTE_DATA_UNAVAILABLE,status { nav.prepareProposal() })
        assertEquals(0,f.writes);assertEquals(0,f.estimates)
    }

    @Test fun `incomplete stale future mock and wrong route source results are rejected`()=runTest {
        val f=Fixture()
        val cases=listOf(f.batch.copy(complete=false) to RestNavigationStatus.INCOMPLETE_ROUTE_DATA,
            f.batch.copy(observedAtMs=f.now-30_001) to RestNavigationStatus.STALE_ROUTE_DATA,
            f.batch.copy(observedAtMs=f.now+1) to RestNavigationStatus.STALE_ROUTE_DATA,
            f.batch.copy(isMock=true) to RestNavigationStatus.MOCK_REJECTED,
            f.batch.copy(routeId="other-route") to RestNavigationStatus.CONTEXT_CHANGED)
        for((batch,expected)in cases){ f.batch=batch;assertEquals(expected,status{f.nav.prepareProposal()}) }
        assertEquals(0,f.writes)
    }

    @Test fun `missing original route unknown position and existing waypoints prevent proposals`()=runTest {
        val cases=listOf(initial.copy(destination=null) to RestNavigationStatus.NO_DESTINATION,
            initial.copy(routeId="") to RestNavigationStatus.ROUTE_CONTEXT_UNAVAILABLE,
            initial.copy(state=initial.state.copy(naviMode=NaviMode.SAFE_GUIDING)) to RestNavigationStatus.NOT_GUIDING,
            initial.copy(waypoints=listOf(NaviWaypoint(37.42,127.1,"이전 경유지"))) to RestNavigationStatus.EXISTING_WAYPOINTS)
        for((state,expected)in cases){val f=Fixture();f.state=state;assertEquals(expected,status{f.nav.prepareProposal()});assertEquals(0,f.writes)}
        val f=Fixture();f.location=f.location.copy(isMock=true)
        assertEquals(RestNavigationStatus.MOCK_REJECTED,status{f.nav.prepareProposal()})
        f.location=NaviLocation(0.0,0.0)
        assertEquals(RestNavigationStatus.LOCATION_UNAVAILABLE,status{f.nav.prepareProposal()})
    }

    @Test fun `missing or malformed next service area is never silently skipped`()=runTest {
        val f=Fixture();f.batch=f.batch.copy(areas=emptyList())
        assertEquals(RestNavigationStatus.NO_CANDIDATE,status{f.nav.prepareProposal()})
        f.batch=f.batch.copy(areas=listOf(area(100).copy(place=place.copy(latitude=Double.NaN)),area(3000)))
        assertEquals(RestNavigationStatus.INCOMPLETE_ROUTE_DATA,status{f.nav.prepareProposal()})
    }

    @Test fun `accept applies exactly the proposal once and preserves destination and route option`()=runTest {
        val f=Fixture();val p=f.nav.prepareProposal();val result=f.nav.accept(p)
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,result.status);assertTrue(result.requestIssued)
        assertEquals(original,result.after!!.destination);assertEquals("FREE",result.after!!.state.routeOption)
        assertEquals(p,result.proposal);assertEquals(1,f.writes)
        assertEquals(RestNavigationStatus.PROPOSAL_INVALID,f.nav.accept(p).status);assertEquals(1,f.writes)
    }

    @Test fun `expired discarded tampered and superseded proposals never write`()=runTest {
        val f=Fixture();val p=f.nav.prepareProposal()
        assertEquals(RestNavigationStatus.PROPOSAL_INVALID,f.nav.accept(p.copy(candidate=p.candidate.copy(place=place.copy(name="바꾼 휴게소")))).status)
        f.now=p.expiresAtMs
        assertEquals(RestNavigationStatus.PROPOSAL_EXPIRED,f.nav.accept(p).status)
        f.now=100_000;val discarded=f.nav.prepareProposal();f.nav.discard(discarded)
        assertEquals(RestNavigationStatus.PROPOSAL_INVALID,f.nav.accept(discarded).status)
        val old=f.nav.prepareProposal();f.nav.prepareProposal()
        assertEquals(RestNavigationStatus.PROPOSAL_INVALID,f.nav.accept(old).status);assertEquals(0,f.writes)
    }

    @Test fun `same destination but changed route needs a new proposal`()=runTest {
        val f=Fixture();val p=f.nav.prepareProposal();f.state=f.state.copy(routeId="rerouted")
        assertEquals(RestNavigationStatus.CONTEXT_CHANGED,f.nav.accept(p).status);assertEquals(0,f.writes)
    }

    @Test fun `equivalent periodic reroute during consent keeps the same proposal and uses fresh context`()=runTest {
        val f=Fixture();f.batch=f.batch.copy(routePath=listOf("road:부산", "rest:기흥", "arrive"))
        val p=f.nav.prepareProposal();f.state=f.state.copy(routeId="periodic")
        f.batch=f.batch.copy(routeId="periodic")
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,f.nav.accept(p).status)
        assertEquals(1,f.writes)
        assertEquals("periodic",f.expectedWriteRouteId)
        assertEquals(RestNavigationStatus.PROPOSAL_INVALID,f.nav.accept(p).status)
        val changed=Fixture();changed.batch=changed.batch.copy(routePath=listOf("road:부산","arrive"))
        val old=changed.nav.prepareProposal();changed.state=changed.state.copy(routeId="different")
        changed.batch=changed.batch.copy(routeId="different",routePath=listOf("road:서울","arrive"))
        assertEquals(RestNavigationStatus.CONTEXT_CHANGED,changed.nav.accept(old).status)
        assertEquals(0,changed.writes)
    }

    @Test fun `read crossing a reroute retries coherently and still dispatches only once`()=runTest {
        val f=Fixture()
        val nav=RestNavigation(f.port,RouteServiceAreaSource { _,_ ->
            f.queries++
            if(f.queries==1) { f.state=f.state.copy(routeId="periodic");f.batch=f.batch.copy(routeId="periodic") }
            f.batch
        },{f.now})
        val proposal=nav.prepareProposal()
        assertEquals("periodic",proposal.before.routeId)
        assertEquals(2,f.queries)
        assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,nav.accept(proposal).status)
        assertEquals(1,f.writes)
    }

    @Test fun `missing SDK identity records observed entry and a repeated acceptance never writes twice`()=runTest {
        val f=Fixture();val named=place.copy(name="기흥휴게소(부산방향)")
        f.batch=f.batch.copy(areas=listOf(area(place=named)))
        f.write={ candidate,sent -> sent();f.writes++;initial.copy(waypoints=listOf(NaviWaypoint(0.0,0.0,candidate.place.name))) }
        val p=f.nav.prepareProposal();val result=f.nav.accept(p)
        assertEquals(RestNavigationStatus.WAYPOINT_OBSERVED,result.status)
        assertTrue(result.requestIssued)
        assertEquals(RestNavigationStatus.PROPOSAL_INVALID,f.nav.accept(p).status)
        assertEquals(1,f.writes)
    }

    @Test fun `passing the proposed service area does not select another after consent`()=runTest {
        val f=Fixture();val p=f.nav.prepareProposal()
        f.batch=f.batch.copy(areas=listOf(area(-10),area(5000,600,place.copy(id="B",name="다른 휴게소"))))
        assertEquals(RestNavigationStatus.CONTEXT_CHANGED,f.nav.accept(p).status);assertEquals(0,f.writes)
    }

    @Test fun `preview is read only and does not invalidate an outstanding proposal`()=runTest {
        val f=Fixture();val p=f.nav.prepareProposal();assertEquals(place,f.nav.preview().first().place)
        assertEquals(0,f.writes);assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,f.nav.accept(p).status)
    }

    @Test fun `final dispatch guard rejects a proposal that expires during SDK recheck`()=runTest {
        val f=Fixture();val p=f.nav.prepareProposal()
        f.write={_,sent->f.now=p.expiresAtMs;sent();error("must not dispatch")}
        val result=f.nav.accept(p)
        assertEquals(RestNavigationStatus.PROPOSAL_EXPIRED,result.status);assertFalse(result.requestIssued)
    }

    @Test fun `successful call without matching waypoint readback remains unconfirmed`()=runTest {
        val f=Fixture();f.write={_,sent->sent();initial}
        val result=f.nav.accept(f.nav.prepareProposal())
        assertEquals(RestNavigationStatus.REQUEST_UNCONFIRMED,result.status);assertTrue(result.requestIssued)
    }

    @Test fun `duplicate simultaneous consent dispatches at most once`()=runTest {
        val f=Fixture();val wait=CompletableDeferred<Unit>()
        f.write={_,sent->sent();f.writes++;wait.await();initial}
        val p=f.nav.prepareProposal();val first=async{f.nav.accept(p)};runCurrent()
        assertEquals(RestNavigationStatus.BUSY,f.nav.accept(p).status)
        wait.complete(Unit);first.await();assertEquals(1,f.writes)
    }

    @Test fun `cancellation before and after write records the correct uncertainty`()=runTest {
        for(sent in listOf(false,true)){
            val f=Fixture();f.write={_,dispatch->if(sent)dispatch();awaitCancellation()}
            val p=f.nav.prepareProposal();var observed:RestNavigationResult?=null
            val job=launch{try{f.nav.accept(p)}catch(e:RestNavigationCancelled){observed=e.navigation;throw e}}
            runCurrent();job.cancelAndJoin()
            assertEquals(sent,observed!!.requestIssued);assertEquals(p,observed!!.proposal)
        }
    }
}
