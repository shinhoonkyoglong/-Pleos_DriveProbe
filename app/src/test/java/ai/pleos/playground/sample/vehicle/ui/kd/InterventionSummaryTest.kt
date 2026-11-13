package ai.pleos.playground.sample.vehicle.ui.kd

import org.junit.Assert.*
import org.junit.Test

class InterventionSummaryTest {
    @Test fun sleepStopSuccessAndUncertaintyUseTheActualFacilityKind() {
        assertEquals("졸음쉼터를 경유지로 추가했어요",interventionSummary(3,"ACCEPTED","ACCEPT","VERIFIED_WAYPOINT","SLEEP_REST_AREA"))
        assertEquals("졸음쉼터 추가에 동의했지만 적용은 확인되지 않았어요",interventionSummary(3,"ACCEPTED","ACCEPT",null,"SLEEP_REST_AREA"))
    }
    @Test fun consentAloneDoesNotClaimWaypointSuccess() {
        assertEquals("휴게소 추가에 동의했지만 적용은 확인되지 않았어요", interventionSummary(3, "ACCEPTED", "ACCEPT", "CONTEXT_CHANGED"))
        assertEquals("휴게소 추가에 동의했지만 적용은 확인되지 않았어요", interventionSummary(3, "ACCEPTED", "ACCEPT", null))
    }
    @Test fun onlyVerifiedReadbackClaimsAnAddedWaypoint() {
        assertEquals("휴게소를 경유지로 추가했어요", interventionSummary(3, "ACCEPTED", "ACCEPT", "VERIFIED_WAYPOINT"))
    }
    @Test fun cancelledDispatchedRequestKeepsTheUncertaintyVisible() {
        assertEquals("휴게소 추가 요청의 적용 여부를 내비에서 확인해 주세요", interventionSummary(3, "CANCELLED", "ACCEPT", "CANCELLED_AFTER_REQUEST_UNCONFIRMED"))
    }
    @Test fun declinedAndUnrecognizedResponsesDoNotClaimRouteWrites() {
        assertEquals("휴게소 안내를 거절했어요", interventionSummary(3, "DECLINED", "DECLINE", "NOT_REQUESTED"))
        assertEquals("안내 동의를 확인하지 못해 경로를 변경하지 않았어요", interventionSummary(3, "NO_RESPONSE", null, "NOT_REQUESTED"))
    }
    @Test fun failedMildAndUnavailableVoiceDoNotClaimSuccessfulHelp() {
        assertEquals("개입을 완료하지 못했어요", interventionSummary(1, "FAILED", null, null))
        assertEquals("개입을 완료하지 못했어요", interventionSummary(2, "UNAVAILABLE", null, null))
        assertEquals("공조 조절 결과를 확인하지 못했어요", interventionSummary(1, null, null, null))
    }
}
