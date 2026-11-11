package ai.pleos.playground.sample.vehicle.intervention

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Real Android services only. Playback completion is a callback, not proof of audible sound. */
class AndroidVoiceConversation(context: Context) : VoiceConversation {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private val pleosSpeech = PleosSpeechPlayer(this.context)
    private val pleosRecognition = PleosSpeechRecognizer(this.context)
    suspend fun stopPreviousRecognition() = exclusive {
        if (pleosSpeech.services().isNotEmpty()) pleosRecognition.stopPreviousSession()
    }
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    override fun capabilities(): VoiceCapabilities {
        val services = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.serviceInfo.packageName }.distinct()
        val pleosServices = pleosSpeech.services()
        val backend = speechBackend(pleosServices.isNotEmpty(), services.size)
        return VoiceCapabilities(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            pleosServices.isNotEmpty() || SpeechRecognizer.isRecognitionAvailable(context), if (backend == SpeechBackend.PLEOS_ON_DEVICE) pleosServices else services,
            if (pleosServices.isNotEmpty()) pleosServices.first() else Settings.Secure.getString(context.contentResolver, "voice_recognition_service"), backend,
            if (pleosServices.isNotEmpty()) SpeechBackend.PLEOS_ON_DEVICE else SpeechBackend.ANDROID,
        )
    }

    override suspend fun play(prompt: String, onPhase: (VoicePhase) -> Unit): Long = exclusive {
        onPhase(VoicePhase.CHECKING)
        speak(prompt, onPhase)
    }

    override suspend fun ask(prompt: String, onPhase: (VoicePhase) -> Unit): VoiceAnswer = exclusive {
        onPhase(VoicePhase.CHECKING)
        val capabilities = capabilities()
        if (!capabilities.microphoneGranted) throw VoiceFailure(VoiceFailureCode.PERMISSION, "먼저 마이크 권한을 허용해 주세요")
        if (!capabilities.recognitionAvailable) throw VoiceFailure(VoiceFailureCode.NO_RECOGNIZER, "이 기기에 음성 인식 서비스가 없어요")
        val questionEndedAt = speak(prompt, onPhase)
        if (capabilities.recognitionBackend == SpeechBackend.PLEOS_ON_DEVICE) {
            return@exclusive pleosRecognition.listen(questionEndedAt, onPhase)
        }
        val timing = VoiceTiming(questionEndedAt)
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val answer = CompletableDeferred<VoiceAnswer>()
        val listeningReady = CompletableDeferred<Unit>()
        var accepting = true
        fun complete(text: String?, end: VoiceEnd, code: Int? = null) {
            if (accepting) answer.complete(VoiceAnswer(text, timing.responseMs, end, timing.readyLatencyMs, code))
        }
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!accepting || answer.isCompleted) return
                    timing.ready(SystemClock.elapsedRealtime())
                    listeningReady.complete(Unit)
                    onPhase(VoicePhase.WAITING)
                }
                override fun onBeginningOfSpeech() {
                    if (!accepting || answer.isCompleted) return
                    timing.speech(SystemClock.elapsedRealtime())
                    onPhase(VoicePhase.LISTENING)
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (!accepting || answer.isCompleted) return
                    listeningReady.completeExceptionally(VoiceFailure(VoiceFailureCode.RECOGNIZER, "음성 인식 서비스 오류 ($error)", error))
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> complete(null, VoiceEnd.NO_MATCH, error)
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> complete(null, VoiceEnd.TIMEOUT, error)
                        else -> answer.completeExceptionally(VoiceFailure(
                            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) VoiceFailureCode.PERMISSION else VoiceFailureCode.RECOGNIZER,
                            "음성 인식 서비스 오류 ($error)", error,
                        ))
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    complete(text, if (text.isNullOrBlank()) VoiceEnd.NO_MATCH else VoiceEnd.RECOGNIZED)
                }
            })
            onPhase(VoicePhase.PREPARING)
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            if (withTimeoutOrNull(5_000L) { listeningReady.await(); true } != true) {
                throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "음성 인식 준비 완료를 받지 못했어요. 무응답으로 판정하지 않아요")
            }
            withTimeoutOrNull(10_000L) { answer.await() }
                ?: VoiceAnswer(null, timing.responseMs, VoiceEnd.TIMEOUT, timing.readyLatencyMs)
        } finally {
            accepting = false
            try { recognizer.cancel() } finally { recognizer.destroy() }
        }
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        if (!mutex.tryLock()) throw VoiceFailure(VoiceFailureCode.BUSY, "다른 음성 작업이 진행 중이에요")
        try { block() } finally { mutex.unlock() }
    }

    private suspend fun speak(text: String, onPhase: (VoicePhase) -> Unit): Long {
        if (pleosSpeech.services().isNotEmpty()) return pleosSpeech.play(text, onPhase)
        onPhase(VoicePhase.PREPARING)
        if (capabilities().ttsServices.isEmpty()) throw VoiceFailure(VoiceFailureCode.NO_TTS, "음성 재생 서비스가 없어요")
        if (tts == null) {
            val initialized = CompletableDeferred<Boolean>()
            ready = initialized
            tts = TextToSpeech(context) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { utterances.remove(it)?.complete(SystemClock.elapsedRealtime()) }
                }
                @Deprecated("Platform callback")
                override fun onError(utteranceId: String?) { failUtterance(utteranceId, null) }
                override fun onError(utteranceId: String?, errorCode: Int) { failUtterance(utteranceId, errorCode) }
            })
        }
        if (withTimeoutOrNull(5_000L) { ready!!.await() } != true) {
            tts?.shutdown(); tts = null; ready = null
            throw VoiceFailure(VoiceFailureCode.NO_TTS, "음성 재생 엔진 초기화 실패 또는 시간 초과")
        }
        val engine = tts!!
        if (engine.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
            throw VoiceFailure(VoiceFailureCode.TTS_LANGUAGE, "한국어 음성 데이터를 사용할 수 없어요")
        }
        val id = UUID.randomUUID().toString()
        val finished = CompletableDeferred<Long>()
        utterances[id] = finished
        try {
            onPhase(VoicePhase.SPEAKING)
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) {
                throw VoiceFailure(VoiceFailureCode.TTS_PLAYBACK, "질문 재생 요청 실패")
            }
            return withTimeoutOrNull(15_000L) { finished.await() }
                ?: throw VoiceFailure(VoiceFailureCode.TTS_TIMEOUT, "질문 재생 완료 콜백을 15초 안에 받지 못했어요")
        } finally {
            utterances.remove(id)
            engine.stop()
        }
    }

    private fun failUtterance(id: String?, code: Int?) {
        id?.let { utterances.remove(it)?.completeExceptionally(VoiceFailure(VoiceFailureCode.TTS_PLAYBACK, "질문 재생 오류", code)) }
        Log.w("VoiceTest", "TTS error code=$code")
    }
}
