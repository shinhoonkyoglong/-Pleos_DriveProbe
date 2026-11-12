package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.*
import ai.pleos.playground.sample.vehicle.data.model.*
import ai.pleos.playground.sample.vehicle.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DangerNavigationActionTest {
    private val candidate=RestCandidate(RestPlace("A","시험 휴게소",37.4,127.1,"","","","",""),NaviTripInfo(8000,480,"FREE"),8000)
    private val route=RestNaviSnapshot(NaviDestinationInfo(37.55,126.97,"목적지","","poi"),NaviRouteState(NaviMode.ROUTE_GUIDING,"FREE",0),emptyList(),"route-A")
    private val proposal=RestProposal("test-proposal",candidate,route,1000,61000,1000,"SYNTHETIC_TEST_SOURCE")
    private class Voice(var answer:VoiceAnswer=VoiceAnswer("추가해 줘",500)):VoiceConversation {
        val events=mutableListOf<String>()
        var prompt=""
        override fun capabilities()=VoiceCapabilities(true,true,emptyList())
        override suspend fun play(prompt:String,onPhase:(VoicePhase)->Unit):Long{events+="notify";return 0}
        override suspend fun ask(prompt:String,onPhase:(VoicePhase)->Unit):VoiceAnswer {this.prompt=prompt;events+="ask";return answer}
    }

    @Test fun `prepares named proposal before asking and applies the same proposal after consent`()=runTest {
        val voice=Voice();val events=voice.events
        val action=DangerNavigationAction(InterventionSpeechAction(voice),{events+="prepare";proposal},{p->
            events+="accept";assertSame(proposal,p)
            RestNavigationResult(RestNavigationStatus.VERIFIED_WAYPOINT,"추가 확인",proposal=proposal)
        },{events+="discard"})
        val result=action.execute(FatigueLevel.DANGER)
        assertEquals(listOf("prepare","ask","accept","discard"),events)
        assertTrue(voice.prompt.contains("시험 휴게소"));assertTrue(voice.prompt.contains("8분"))
        assertEquals(VoiceConsent.ACCEPT,result.consent);assertEquals("VERIFIED_WAYPOINT",result.navigationStatus)
    }

    @Test fun `negative unknown timeout and no match discard the proposal without writing`()=runTest {
        for(answer in listOf(VoiceAnswer("아니요",500),VoiceAnswer("추가하지 마",500),VoiceAnswer("모르겠어요",500),
            VoiceAnswer("네",null,VoiceEnd.TIMEOUT),VoiceAnswer("네",null,VoiceEnd.NO_MATCH))){
            val voice=Voice(answer);var accepted=0;var discarded=0
            val result=DangerNavigationAction(InterventionSpeechAction(voice),{proposal},{accepted++;error("no write")},{discarded++}).execute(FatigueLevel.DANGER)
            assertEquals(0,accepted);assertEquals(1,discarded);assertEquals("NOT_REQUESTED",result.navigationStatus)
            assertEquals(proposal,result.navigation!!.proposal)
        }
    }

    @Test fun `missing route data plays notification without requesting consent`()=runTest {
        val voice=Voice();val action=DangerNavigationAction(InterventionSpeechAction(voice),{
            throw RestNavigationFailure(RestNavigationStatus.ROUTE_DATA_UNAVAILABLE,"검색 연결 없음")
        },{error("no write")},{error("no proposal")})
        val result=action.execute(FatigueLevel.DANGER)
        assertEquals(listOf("notify"),voice.events);assertEquals(InterventionOutcome.UNAVAILABLE,result.outcome)
        assertNull(result.consent);assertEquals("ROUTE_DATA_UNAVAILABLE",result.navigationStatus)
    }

    @Test fun `caution and manual speech flow do not prepare or apply a proposal`()=runTest {
        val voice=Voice(VoiceAnswer("괜찮아요",500))
        val action=DangerNavigationAction(InterventionSpeechAction(voice),{error("not danger")},{error("not danger")},{error("not danger")})
        assertEquals(InterventionOutcome.ANSWERED,action.execute(FatigueLevel.CAUTION).outcome)
        voice.answer=VoiceAnswer("추가해 줘",500)
        val manual=InterventionSpeechAction(voice).execute(FatigueLevel.DANGER)
        assertEquals("NOT_CONNECTED",manual.navigationStatus);assertNull(manual.navigation)
    }

    @Test fun `cancelled accepted request stores one row with proposal consent and uncertainty`()=runTest {
        val saved=mutableListOf<InterventionRecord>();var discarded=0
        val action=DangerNavigationAction(InterventionSpeechAction(Voice()),{proposal},{
            try{awaitCancellation()}catch(e:CancellationException){
                throw RestNavigationCancelled(RestNavigationResult(RestNavigationStatus.CANCELLED_AFTER_REQUEST_UNCONFIRMED,"요청 후 취소",requestIssued=true,proposal=proposal))
            }
        },{discarded++})
        val controller=InterventionController(backgroundScope,{0L},action::execute,{saved+=it})
        controller.update(ConditionState(FatigueLevel.DANGER,0.8f,false,true),true,true,false)
        runCurrent();controller.reset();runCurrent()
        val row=saved.single()
        assertEquals(VoiceConsent.ACCEPT,row.result.consent);assertEquals(InterventionOutcome.CANCELLED,row.result.outcome)
        assertEquals(proposal,row.result.navigation!!.proposal);assertTrue(row.result.navigation!!.requestIssued)
        assertEquals(1,discarded)
    }
}
