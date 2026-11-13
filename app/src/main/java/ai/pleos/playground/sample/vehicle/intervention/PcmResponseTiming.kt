package ai.pleos.playground.sample.vehicle.intervention

/** PCM16 mono 16 kHz. Offsets belong to the exact byte stream sent to the SDK. */
internal class PcmResponseTiming(private val questionEndNs: Long) {
    private var originNs: Long? = null
    private var originSource: String? = null
    private var sentBytes = 0L
    private var epdOn: Long? = null
    private var epdOff: Long? = null

    @Synchronized fun sent(bytes: Int, readEndNs: Long) {
        if (bytes <= 0 || bytes % 2 != 0 || sentBytes + bytes > BYTES_PER_SECOND * 60) return
        sentBytes += bytes
        if (originNs == null) {
            originNs = readEndNs - sentBytes * 1_000_000_000L / BYTES_PER_SECOND
            originSource = "PCM_READ_RECEIPT_EPD_ESTIMATE"
        }
    }

    @Synchronized fun timestamp(frame: Long, nanoTime: Long) {
        if (frame <= 0 || frame > SAMPLE_RATE * 60L || nanoTime <= 0 || sentBytes == 0L) return
        val candidate = nanoTime - frame * 1_000_000_000L / SAMPLE_RATE
        // Reject mismatched clocks/reset frame counters instead of inventing a duration.
        if (candidate < questionEndNs || candidate > nanoTime) return
        if (originSource != "PCM_AUDIO_TIMESTAMP_EPD_ESTIMATE") {
            originNs = candidate
            originSource = "PCM_AUDIO_TIMESTAMP_EPD_ESTIMATE"
        }
    }

    @Synchronized fun epd(on: Long, off: Long) {
        if (on < 0 || off <= on || on % 2 != 0L || off % 2 != 0L) return
        if (epdOn == null) { epdOn = on; epdOff = off }
    }

    @Synchronized fun snapshot(): PcmTimingResult {
        val origin = originNs
        val on = epdOn
        val off = epdOff
        val valid = origin != null && on != null && off != null && off <= sentBytes
        val speechNs = if (valid) origin!! + on!! * 1_000_000_000L / BYTES_PER_SECOND else null
        val response = speechNs?.takeIf { it >= questionEndNs }?.let { (it - questionEndNs) / 1_000_000L }
        return PcmTimingResult(response, if (response == null) "UNMEASURED_PCM_EPD" else originSource!!,
            origin?.takeIf { it >= questionEndNs }?.let { (it - questionEndNs) / 1_000_000L }, on, off, sentBytes)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SECOND = 32_000L
    }
}

internal data class PcmTimingResult(
    val responseMs: Long?, val source: String, val captureLatencyMs: Long?,
    val epdOnBytes: Long?, val epdOffBytes: Long?, val sentBytes: Long,
)
