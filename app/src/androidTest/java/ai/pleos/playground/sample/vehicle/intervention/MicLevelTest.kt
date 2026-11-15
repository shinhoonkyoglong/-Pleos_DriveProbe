package ai.pleos.playground.sample.vehicle.intervention

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 시연 준비용 마이크 입력 확인: 5초 녹음하며 0.5초마다 소리 크기(RMS·최대값)를 logcat "MicLevel"에 남긴다.
 * 말하는 동안 값이 오르면 PC 마이크가 에뮬레이터로 들어오고 있는 것이다. 명시적 인수(micLevel=true) 없으면 건너뛴다.
 */
class MicLevelTest {
    @SuppressLint("MissingPermission")
    @Test fun recordLevel() {
        assumeTrue("micLevel=true required", InstrumentationRegistry.getArguments().getString("micLevel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "RECORD_AUDIO required" }
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = InstrumentationRegistry.getArguments().getString("source")?.toIntOrNull() ?: MediaRecorder.AudioSource.VOICE_RECOGNITION
        Log.i("MicLevel", "source=$source")
        val record = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        val chunk = ShortArray(rate / 2)
        var overallPeak = 0
        try {
            record.startRecording()
            val chunks = (InstrumentationRegistry.getArguments().getString("seconds")?.toIntOrNull() ?: 5) * 2
            repeat(chunks) { i ->
                var read = 0
                while (read < chunk.size) {
                    val n = record.read(chunk, read, chunk.size - read)
                    if (n <= 0) break
                    read += n
                }
                var sum = 0.0
                var peak = 0
                for (k in 0 until read) { val v = abs(chunk[k].toInt()); sum += v.toDouble() * v; if (v > peak) peak = v }
                val rms = if (read > 0) sqrt(sum / read) else 0.0
                overallPeak = maxOf(overallPeak, peak)
                Log.i("MicLevel", "t=%.1fs rms=%.0f peak=%d %s".format((i + 1) * 0.5, rms, peak, "#".repeat((rms / 200).toInt().coerceAtMost(40))))
            }
        } finally {
            record.stop(); record.release()
        }
        Log.i("MicLevel", "overallPeak=$overallPeak (무음이면 0 근처, 말소리는 보통 수천 이상)")
    }
}
