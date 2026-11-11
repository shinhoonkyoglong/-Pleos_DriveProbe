package ai.pleos.playground.sample.vehicle.intervention

import org.junit.Assert.*
import org.junit.Test

class PcmResponseTimingTest {
    @Test fun `PCM bytes map to speech onset including capture setup delay`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(64_000, 4_000_000_000L)
        t.epd(32_000, 48_000)
        assertEquals(2_000L, t.snapshot().responseMs)
        assertEquals(1_000L, t.snapshot().captureLatencyMs)
        assertEquals("PCM_READ_RECEIPT_EPD_ESTIMATE", t.snapshot().source)
    }
    @Test fun `audio timestamp replaces less accurate buffer receipt anchor`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(64_000, 4_000_000_000L)
        t.timestamp(16_000, 3_200_000_000L)
        t.epd(32_000, 48_000)
        assertEquals(2_200L, t.snapshot().responseMs)
        assertEquals("PCM_AUDIO_TIMESTAMP_EPD_ESTIMATE", t.snapshot().source)
    }
    @Test fun `zero onset is a valid start of buffer`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(3_200, 2_100_000_000L); t.epd(0, 3_200)
        assertEquals(1_000L, t.snapshot().responseMs)
    }
    @Test fun `missing EPD stays unknown even after a transcript`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(3_200, 2_100_000_000L)
        assertNull(t.snapshot().responseMs)
    }
    @Test fun `EPD received before audio accounting is resolved after accounting`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.epd(0, 3_200)
        assertNull(t.snapshot().responseMs)
        t.sent(3_200, 2_100_000_000L)
        assertEquals(1_000L, t.snapshot().responseMs)
    }
    @Test fun `out of bounds EPD cannot fabricate a future response`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(3_200, 2_100_000_000L); t.epd(0, 9_600)
        assertNull(t.snapshot().responseMs)
    }
    @Test fun `malformed and negative offsets are ignored`() {
        for ((on, off) in listOf(-1L to 100L, 100L to 50L, 1L to 100L, 0L to 0L)) {
            val t = PcmResponseTiming(1_000_000_000L)
            t.sent(3_200, 2_100_000_000L); t.epd(on, off)
            assertNull(t.snapshot().responseMs)
        }
    }
    @Test fun `wrong timestamp timebase and absurd frame position are rejected`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(3_200, 2_100_000_000L)
        t.timestamp(16_000, 1_100_000_000L)
        t.timestamp(Long.MAX_VALUE, Long.MAX_VALUE)
        t.epd(0, 3_200)
        assertEquals(1_000L, t.snapshot().responseMs)
        assertEquals("PCM_READ_RECEIPT_EPD_ESTIMATE", t.snapshot().source)
    }
    @Test fun `later chunks and duplicate EPD cannot shift the first onset`() {
        val t = PcmResponseTiming(1_000_000_000L)
        t.sent(3_200, 2_100_000_000L); t.epd(0, 3_200)
        t.sent(3_200, 9_000_000_000L); t.epd(3_200, 6_400)
        assertEquals(1_000L, t.snapshot().responseMs)
    }
}
