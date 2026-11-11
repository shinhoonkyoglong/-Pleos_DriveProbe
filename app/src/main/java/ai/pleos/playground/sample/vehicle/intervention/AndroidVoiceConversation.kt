package ai.pleos.playground.sample.vehicle.intervention

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

data class VoiceAnswer(val text: String?, val responseMs: Long?)

/** Android system TTS/STT adapter. Missing engines/permission are explicit failures, never mock answers. */
class AndroidVoiceConversation(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val utterances = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    suspend fun ask(prompt: String): VoiceAnswer = withContext(Dispatchers.Main.immediate) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "음성 확인을 사용하려면 정차 중 개입 화면에서 마이크 권한을 허용해 주세요"
        }
        check(SpeechRecognizer.isRecognitionAvailable(context)) { "이 기기에 음성 인식 서비스가 없어요" }
        speak(prompt)
        // The clock starts after TTS onDone, not when speak() was queued.
        val questionEndedAt = SystemClock.elapsedRealtime()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val answer = CompletableDeferred<VoiceAnswer>()
        var responseMs: Long? = null
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() { responseMs = SystemClock.elapsedRealtime() - questionEndedAt }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    answer.complete(VoiceAnswer(null, responseMs))
                } else answer.completeExceptionally(IllegalStateException("음성 인식 오류: $error"))
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                answer.complete(VoiceAnswer(text, responseMs))
            }
        })
        try {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            withTimeoutOrNull(10_000L) { answer.await() } ?: VoiceAnswer(null, responseMs)
        } finally {
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    private suspend fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { utteranceId?.let { utterances.remove(it)?.complete(Unit) } }
                @Deprecated("Platform callback")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { utterances.remove(it)?.completeExceptionally(IllegalStateException("음성 안내 재생 실패")) }
                }
            })
        }
        check(withTimeoutOrNull(5_000L) { ready.await() } == true) { "음성 안내 엔진을 사용할 수 없어요" }
        val engine = tts!!
        check(engine.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE) { "한국어 음성 데이터를 사용할 수 없어요" }
        val id = UUID.randomUUID().toString()
        val finished = CompletableDeferred<Unit>()
        utterances[id] = finished
        try {
            check(engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.SUCCESS) { "음성 안내 요청 실패" }
            check(withTimeoutOrNull(15_000L) { finished.await(); true } == true) { "음성 안내 재생 시간 초과" }
        } finally {
            utterances.remove(id)
            engine.stop()
        }
    }
}
