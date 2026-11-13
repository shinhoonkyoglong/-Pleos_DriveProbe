package ai.pleos.playground.sample.vehicle.intervention

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceResponseTest {
    @Test fun `only explicit positive phrases permit routing`() {
        for (text in listOf("네", "안내해 줘", "안내해 주세요")) assertEquals(VoiceConsent.ACCEPT, VoiceResponse.consent(text))
        for (text in listOf("아니요", "안내하지 마", "안내 원하지 않아요", "괜찮아요")) assertEquals(VoiceConsent.DECLINE, VoiceResponse.consent(text))
        for (text in listOf(null, "", "안내", "잘 모르겠어요", "안내하지 않은 건가요")) assertEquals(VoiceConsent.UNKNOWN, VoiceResponse.consent(text))
    }
}
