package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Manual timing test only. Audio is streamed to the local SDK and never written to disk. */
internal class PcmTimedRecognizer(private val context: Context) {
    @SuppressLint("MissingPermission") // The shared voice adapter checks RECORD_AUDIO first.
    suspend fun listen(questionEndMs: Long, phase: (VoicePhase) -> Unit): VoiceAnswer = coroutineScope {
        phase(VoicePhase.PREPARING)
        val sdk = SpeechToText(context, Mode.ON_DEVICE)
        val active = AtomicBoolean(true)
        val initialized = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Pair<String?, VoiceEnd>>()
        val epdReceived = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val stopping = AtomicBoolean(false)
        val timing = PcmResponseTiming(questionEndMs * 1_000_000L)
        var listenerAdded = false
        var sentAudio = false
        var recorder: AudioRecord? = null
        var producer: Job? = null
        var readyAt: Long? = null
        val listener = object : ResultListener {
            override fun onReady() { if (active.get()) Log.i("VoiceTest", "PCM STT ready") }
            override fun onStartedRecognition() { if (active.get()) phase(VoicePhase.LISTENING) }
            override fun onEndedRecognition() { if (stopping.get()) stopped.complete(Unit) }
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onUpdatedEpdData(on: Long, off: Long) {
                if (!active.get()) return
                timing.epd(on, off)
                if (on >= 0 && off > on) epdReceived.complete(Unit)
            }
            override fun onUpdated(stt: String, completed: Boolean) {
                if (active.get() && completed) {
                    Log.i("VoiceTest", "PCM STT final chars=${stt.length}")
                    // EPD commonly arrives immediately AFTER this callback.
                    finish(stt)
                }
            }
            private fun finish(stt: String) {
                completed.complete(stt.takeIf { it.isNotBlank() } to
                    if (stt.isBlank()) VoiceEnd.NO_MATCH else VoiceEnd.RECOGNIZED)
            }
            override fun onError(errorType: ErrorType) {
                if (!active.get()) return
                val error = VoiceFailure(if (errorType == ErrorType.PERMISSION_DENIED) VoiceFailureCode.PERMISSION else VoiceFailureCode.RECOGNIZER,
                    "응답 시간 시험 STT 오류: ${errorType.name}")
                initialized.completeExceptionally(error)
                if (errorType == ErrorType.TIMEOUT && sentAudio) completed.complete(null to VoiceEnd.TIMEOUT)
                else completed.completeExceptionally(error)
            }
        }
        try {
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() { if (active.get()) initialized.complete(Unit) }
                override fun onFailure(errorType: ErrorType) { listener.onError(errorType) }
            })
            if (withTimeoutOrNull(5_000L) { initialized.await(); true } != true)
                throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "응답 시간 시험 SDK 초기화 시간 초과")
            sdk.addListener(listener); listenerAdded = true
            sdk.setLocale(Locale.KOREA)
            val minimum = AudioRecord.getMinBufferSize(PcmResponseTiming.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "16kHz 마이크 입력을 지원하지 않아요")
            val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(PcmResponseTiming.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 12_800)).build()
            recorder = audio
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "마이크 초기화 실패")
            producer = launch(Dispatchers.IO) {
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                    throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "마이크 입력 시작 실패")
                readyAt = SystemClock.elapsedRealtime()
                phase(VoicePhase.WAITING)
                val buffer = ByteArray(3_200)
                val timestamp = AudioTimestamp()
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                while (isActive && !completed.isCompleted && SystemClock.elapsedRealtime() < deadline) {
                    val size = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (!isActive) break
                    if (size <= 0 || size % 2 != 0) throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "마이크 읽기 오류 ($size)")
                    timing.sent(size, SystemClock.elapsedRealtimeNanos())
                    if (audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS)
                        timing.timestamp(timestamp.framePosition, timestamp.nanoTime)
                    sentAudio = true
                    sdk.sendAudio(buffer.copyOf(size))
                }
                if (isActive) sdk.completeAudioSend()
            }
            val result = withTimeoutOrNull(13_000L) { completed.await() } ?: (null to VoiceEnd.TIMEOUT)
            // Stop local capture before waiting for the SDK's final EPD metadata.
            producer?.cancel()
            withContext(NonCancellable + Dispatchers.IO) { runCatching { audio.stop() }; producer?.join() }
            if (result.second == VoiceEnd.RECOGNIZED) withTimeoutOrNull(250L) { epdReceived.await() }
            val measured = timing.snapshot()
            Log.i("VoiceTest", "PCM timing responseMs=${measured.responseMs} source=${measured.source} bytes=${measured.sentBytes}")
            VoiceAnswer(result.first, measured.responseMs, result.second,
                readyLatencyMs = readyAt?.let { it - questionEndMs }, responseTiming = measured.source,
                captureLatencyMs = measured.captureLatencyMs, epdOnBytes = measured.epdOnBytes,
                epdOffBytes = measured.epdOffBytes, audioBytes = measured.sentBytes)
        } finally {
            active.set(false)
            withContext(NonCancellable) {
                producer?.cancel()
                withContext(Dispatchers.IO) { runCatching { recorder?.stop() }; producer?.join(); recorder?.release() }
                if (sentAudio) {
                    stopping.set(true)
                    runCatching { sdk.stop(); withTimeoutOrNull(1_500L) { stopped.await() } }
                }
                if (listenerAdded) runCatching { sdk.removeListener(listener) }
                runCatching { sdk.release() }
            }
        }
    }
}
