package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.intervention.VoicePhase
import org.junit.Assert.*
import org.junit.Test

class DrivingAssistStateTest {
    @Test fun enabledWithoutMicrophoneServiceDoesNotClaimFullReadiness() {
        assertEquals("음성 준비 필요", assistReadiness(true, false))
        assertEquals("공조·음성 도움 켜짐", assistReadiness(true, true))
        assertEquals("주행 도움 꺼짐", assistReadiness(false, true))
    }
    @Test fun unknownVehicleStateBusyExecutionAndPreparationBlockSettings() {
        assertFalse(DrivingAssistState().canConfigure)
        val allowed = DrivingAssistState(blockedReason = "")
        assertTrue(allowed.canConfigure)
        assertFalse(allowed.copy(busy = true).canConfigure)
        assertFalse(allowed.copy(starting = true).canConfigure)
    }
    @Test fun driverSeesAResponseCueOnlyForTheActualVoicePhase() {
        assertTrue(assistVoiceMessage(VoicePhase.WAITING)!!.contains("지금 말씀"))
        assertTrue(assistVoiceMessage(VoicePhase.SPEAKING)!!.contains("끝난 뒤"))
        assertTrue(assistVoiceMessage(VoicePhase.APPLYING_ROUTE)!!.contains("적용을 확인"))
        assertNull(assistVoiceMessage(VoicePhase.IDLE))
        assertNull(assistVoiceMessage(VoicePhase.FINISHED))
    }
}
