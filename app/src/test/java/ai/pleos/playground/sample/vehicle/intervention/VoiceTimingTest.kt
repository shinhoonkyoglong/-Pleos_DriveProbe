package ai.pleos.playground.sample.vehicle.intervention

import org.junit.Assert.*
import org.junit.Test

class VoiceTimingTest {
    @Test fun `missing speech callback is unknown even when recognizer was ready`() {
        val t = VoiceTiming(1_000)
        t.ready(1_200)
        assertEquals(200L, t.readyLatencyMs)
        assertNull(t.responseMs)
    }
    @Test fun `time starts at TTS callback and duplicate callbacks cannot replace first speech`() {
        val t = VoiceTiming(1_000)
        t.ready(1_200); t.speech(1_700); t.speech(2_000); t.ready(2_100)
        assertEquals(700L, t.responseMs)
        assertEquals(200L, t.readyLatencyMs)
    }
    @Test fun `provider can omit ready callback without fabricating ready latency`() {
        val t = VoiceTiming(1_000)
        t.speech(1_500)
        assertEquals(500L, t.responseMs)
        assertNull(t.readyLatencyMs)
    }
    @Test fun `invalid clock order is not clamped into a perfect reaction time`() {
        val t = VoiceTiming(1_000)
        t.ready(900); t.speech(950)
        assertNull(t.readyLatencyMs); assertNull(t.responseMs)
    }
}
