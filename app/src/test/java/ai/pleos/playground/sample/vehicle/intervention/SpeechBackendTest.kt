package ai.pleos.playground.sample.vehicle.intervention

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechBackendTest {
    @Test fun `Pleos endpoint does not require an Android engine`() {
        assertEquals(SpeechBackend.PLEOS_ON_DEVICE, speechBackend(true, 0))
    }
    @Test fun `native Android engine remains a fallback off Pleos`() {
        assertEquals(SpeechBackend.ANDROID, speechBackend(false, 1))
    }
    @Test fun `no supported endpoints must be unavailable`() {
        assertEquals(SpeechBackend.NONE, speechBackend(false, 0))
    }
}
