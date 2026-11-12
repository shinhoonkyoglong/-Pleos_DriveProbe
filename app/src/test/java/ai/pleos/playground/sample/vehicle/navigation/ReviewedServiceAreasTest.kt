package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.intervention.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReviewedServiceAreasTest {
    private val destination=NaviDestinationInfo(37.55,126.97,"목적지","","original-poi")
    private val route=RestNaviSnapshot(destination,NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0),emptyList(),"test-route")
    private val bookmark=RestPlace("NAVI_BOOKMARK:test:poi-A","시험휴게소(동쪽방향)",37.41,127.1,"고속국도","","","시험 주소","","poi-A","0","test")
    private val tbt=NaviTbtRoute("test-route",10_000,listOf(
        NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,2600.0,"하이패스센터"),
        NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,8200.0,"시험휴게소"),
        NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,1700.0,"다음휴게소"),
        NaviTbtEntry("TURN_ARRIVE",37,10.0,"도착")))
    private class Store:ReviewedServiceAreaStore {
        var entries=emptyList<ReviewedServiceArea>()
        override suspend fun load()=entries
        override suspend fun save(values:List<ReviewedServiceArea>){entries=values}
    }
    private inner class Fixture {
        var epoch=1_000_000L
        var snapshot=route
        var raw=tbt
        var places=listOf(bookmark,bookmark.copy(id="NAVI_BOOKMARK:test:poi-B",name="시험휴게소(서쪽방향)",longitude=127.101,poiId="poi-B"))
        var trip=NaviTripInfo(31_500,1800,"FREE")
        var estimatedPlace:RestPlace?=null
        var writes=0
        var allowed=true
        val store=Store()
        val registry=ReviewedServiceAreas(store,{epoch})
        val port=object:RestNavigationPort {
            override suspend fun snapshot()=snapshot
            override suspend fun location()=NaviLocation(37.4,127.1)
            override suspend fun serviceAreaPlaces()=places
            override suspend fun tbtRoute()=raw
            override suspend fun estimate(place:RestPlace):NaviTripInfo{estimatedPlace=place;return trip}
            override suspend fun addFirstWaypoint(candidate:RestCandidate,expected:RestNaviSnapshot,dispatched:()->Unit):RestNaviSnapshot {
                dispatched();writes++
                assertEquals(bookmark.poiId,candidate.place.poiId)
                return snapshot.copy(waypoints=listOf(NaviWaypoint(candidate.place.latitude,candidate.place.longitude,candidate.place.name)))
            }
        }
        val review=ServiceAreaReview(port,registry,{allowed})
        val navigation=RestNavigation(port,PleosTbtServiceAreaSource(port,registry),{10_000})
        suspend fun confirm(){val session=review.load();review.confirm(session,session.places.first(),"시험고속도로","동쪽방향",true)}
    }
    private suspend fun rejected(block:suspend ()->Unit) {
        try { block() } catch(e:IllegalArgumentException){return} catch(e:IllegalStateException){return} catch(e:RestNavigationFailure){return}
        fail("Expected rejection")
    }

    @Test fun `missing registration reports a stable diagnostic instead of assuming a route change`()=runTest {
        val f=Fixture()
        val report=f.registry.diagnose(route,tbt)
        assertEquals("REVIEW_NOT_REGISTERED",report.code)
        assertEquals(0,report.storedCount)
        try{f.registry.resolve(route,tbt);fail("Missing registration must fail")}
        catch(e:RestNavigationFailure){assertTrue(e.diagnosticCode!!.startsWith("REVIEW_NOT_REGISTERED stored=0 matching=0"))}
    }

    @Test fun `diagnostics distinguish matching review route option sequence and expiry mismatches`()=runTest {
        val f=Fixture();f.confirm()
        assertEquals("REVIEW_MATCHED",f.registry.diagnose(route,tbt).code)
        val changed=route.copy(routeId="new-route",state=route.state.copy(routeOption="RECOMMENDED"),
            destination=destination.copy(latitude=37.6))
        f.epoch+=ReviewedServiceAreas.MAX_AGE_MS+1
        val report=f.registry.diagnose(changed,tbt.copy(routeId="new-route",entries=tbt.entries.drop(2)))
        assertEquals("REVIEW_CONTEXT_MISMATCH",report.code)
        assertEquals(setOf("ROUTE_ID","ROUTE_OPTION","DESTINATION","UPCOMING_SEQUENCE","EXPIRED"),report.reasons.keys)
        assertEquals(0,report.matchingCount)
    }

    @Test fun `duplicate matching reviews have a separate diagnostic and cannot produce coordinates`()=runTest {
        val f=Fixture();f.confirm();f.store.entries=f.store.entries+f.store.entries
        assertEquals("REVIEW_DUPLICATE",f.registry.diagnose(route,tbt).code)
        try{f.registry.resolve(route,tbt);fail("Duplicate review must fail")}
        catch(e:RestNavigationFailure){
            assertTrue(e.diagnosticCode!!.startsWith("REVIEW_DUPLICATE"))
            assertEquals(e.diagnosticCode,RestNavigation.failure(e).error)
        }
    }

    @Test fun `a unique matching favorite still requires explicit direction review`()=runTest {
        val f=Fixture();f.places=listOf(bookmark)
        rejected{f.navigation.prepareProposal()}
        assertEquals(0,f.writes);assertNull(f.estimatedPlace)
    }

    @Test fun `matching opposite direction favorites are listed without automatic nearest selection`()=runTest {
        val f=Fixture();val session=f.review.load()
        assertEquals(2,session.places.size)
        assertTrue(f.store.entries.isEmpty())
        rejected{f.review.confirm(session,session.places.first(),"시험고속도로","동쪽방향",false)}
        assertTrue(f.store.entries.isEmpty())
    }

    @Test fun `unparenthesized native direction name is listed reviewed and preserved in the proposal`()=runTest {
        val f=Fixture()
        f.places=listOf(bookmark.copy(name="시험휴게소 동쪽방면"),bookmark.copy(id="NAVI_BOOKMARK:test:other",name="시험휴게소 서쪽방면",poiId="other"))
        val session=f.review.load()
        assertEquals(2,session.places.size)
        rejected{f.navigation.prepareProposal()}
        f.review.confirm(session,session.places.first(),"시험고속도로","동쪽방향",true)
        val p=f.navigation.prepareProposal()
        assertEquals("시험휴게소 동쪽방면",p.candidate.place.name)
        assertEquals(bookmark.poiId,p.candidate.place.poiId)
        assertEquals("동쪽방향",p.candidate.place.direction)
    }

    @Test fun `reviewed native POI resolves TBT order and preserves independent trip distance`()=runTest {
        val f=Fixture();f.confirm()
        val p=f.navigation.prepareProposal()
        assertEquals(bookmark.poiId,p.candidate.place.poiId)
        assertEquals("동쪽방향",p.candidate.place.direction)
        assertEquals(31_500,p.candidate.trip.distanceMeters)
        assertEquals(1800L,p.candidate.trip.etaSeconds)
        assertNull(p.candidate.distanceAlongRouteMeters)
        assertEquals(1,p.candidate.routeOrder)
        assertEquals("MANUAL_ROUTE_DIRECTION_CONFIRMATION",p.candidate.verification)
        assertTrue(p.prompt.contains("30분"));assertTrue(p.prompt.contains(bookmark.name))
        assertEquals(bookmark.poiId,f.estimatedPlace!!.poiId)
    }

    @Test fun `review never transfers to a new route destination option or upcoming sequence`()=runTest {
        val f=Fixture();f.confirm()
        val cases=listOf(route.copy(routeId="new-route"),route.copy(destination=destination.copy(latitude=37.6)),
            route.copy(state=route.state.copy(routeOption="RECOMMENDED")))
        for(changed in cases){rejected{f.registry.resolve(changed,tbt.copy(routeId=changed.routeId))}}
        rejected{f.registry.resolve(route,tbt.copy(entries=tbt.entries.drop(2)))}
    }

    @Test fun `review expires and clock reversal does not revive it`()=runTest {
        val f=Fixture();f.confirm();val original=f.epoch
        f.epoch+=ReviewedServiceAreas.MAX_AGE_MS+1;rejected{f.registry.resolve(route,tbt)}
        f.epoch=original-1;rejected{f.registry.resolve(route,tbt)}
    }

    @Test fun `review refuses name mismatch invalid coordinates missing direction and missing confirmation`()=runTest {
        val f=Fixture()
        rejected{f.registry.confirm(route,tbt,bookmark.copy(name="다른휴게소"),"도로","동쪽",true)}
        rejected{f.registry.confirm(route,tbt,bookmark.copy(latitude=Double.NaN),"도로","동쪽",true)}
        rejected{f.registry.confirm(route,tbt,bookmark,"","동쪽",true)}
        rejected{f.registry.confirm(route,tbt,bookmark,"도로","",true)}
        rejected{f.registry.confirm(route,tbt,bookmark,"도로","동쪽",false)}
        assertTrue(f.store.entries.isEmpty())
    }

    @Test fun `confirmation rechecks the native favorite and route before saving`()=runTest {
        val f=Fixture();val session=f.review.load()
        f.places=emptyList();rejected{f.review.confirm(session,bookmark,"도로","동쪽",true)}
        f.places=listOf(bookmark);f.snapshot=route.copy(routeId="rerouted")
        f.raw=tbt.copy(routeId="rerouted");rejected{f.review.confirm(session,bookmark,"도로","동쪽",true)}
        assertTrue(f.store.entries.isEmpty())
    }

    @Test fun `leaving parked conditions blocks review persistence`()=runTest {
        val f=Fixture();val session=f.review.load();f.allowed=false
        rejected{f.review.confirm(session,bookmark,"도로","동쪽",true)}
        assertTrue(f.store.entries.isEmpty())
    }

    @Test fun `mock or unavailable ETA and partial TBT cannot produce a proposal`()=runTest {
        val f=Fixture();f.confirm()
        f.trip=f.trip.copy(isMock=true);rejected{f.navigation.prepareProposal()}
        f.trip=f.trip.copy(isMock=false,etaSeconds=0);rejected{f.navigation.prepareProposal()}
        f.trip=f.trip.copy(etaSeconds=100,routeOption="RECOMMENDED");rejected{f.navigation.prepareProposal()}
        f.trip=f.trip.copy(routeOption="FREE");f.raw=tbt.copy(entries=tbt.entries.dropLast(1));rejected{f.navigation.prepareProposal()}
        assertEquals(0,f.writes)
    }

    @Test fun `full reviewed proposal speech consent and apply path uses the same exact POI`()=runTest {
        val f=Fixture();f.confirm()
        var heard=""
        val voice=object:VoiceConversation {
            override fun capabilities()=VoiceCapabilities(true,true,emptyList())
            override suspend fun play(prompt:String,onPhase:(VoicePhase)->Unit)=0L
            override suspend fun ask(prompt:String,onPhase:(VoicePhase)->Unit):VoiceAnswer{heard=prompt;return VoiceAnswer("추가해 줘",500)}
        }
        val action=DangerNavigationAction(InterventionSpeechAction(voice),f.navigation::prepareProposal,f.navigation::accept,f.navigation::discard)
        val result=action.execute(FatigueLevel.DANGER)
        assertEquals(VoiceConsent.ACCEPT,result.consent)
        assertEquals("VERIFIED_WAYPOINT",result.navigationStatus)
        assertTrue(heard.contains(bookmark.name));assertTrue(heard.contains("30분"))
        assertEquals(1,f.writes);assertEquals(destination,result.navigation!!.after!!.destination)
    }

    @Test fun `deleting the local review prevents future automatic proposals`()=runTest {
        val f=Fixture();f.confirm();f.review.forget(f.review.load())
        rejected{f.navigation.prepareProposal()};assertEquals(0,f.writes)
    }
}
