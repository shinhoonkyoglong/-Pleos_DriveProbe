package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Pleos' speech service is an SDK endpoint, not an Android TextToSpeechService engine. */
internal class PleosSpeechPlayer(private val context: Context) {
    fun services(): List<String> = context.packageManager.queryIntentServices(
        Intent("ai.pleos.playground.caas.intent.action.SPEECH_START"), 0,
    ).map { it.serviceInfo.packageName }.distinct()

    suspend fun play(text: String, onPhase: (VoicePhase) -> Unit): Long {
        if (context.checkSelfPermission("pleos.car.permission.TTS_SERVICE") != PackageManager.PERMISSION_GRANTED) {
            throw VoiceFailure(VoiceFailureCode.PERMISSION, "Pleos TTS 권한이 없어요. 새 APK를 설치해 주세요")
        }
        onPhase(VoicePhase.PREPARING)
        val sdk = TextToSpeech(context, Mode.ON_DEVICE)
        val active = AtomicBoolean(true)
        val requested = AtomicBoolean(false)
        val initialized = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Long>()
        var listenerAdded = false
        fun fail(error: ErrorType) {
            if (!active.get()) return
            val failure = VoiceFailure(when (error) {
                ErrorType.PERMISSION_DENIED -> VoiceFailureCode.PERMISSION
                ErrorType.UNAVAILABLE_LANGUAGE -> VoiceFailureCode.TTS_LANGUAGE
                ErrorType.ON_DEVICE_ENGINE_ERROR -> VoiceFailureCode.NO_TTS
                else -> VoiceFailureCode.TTS_PLAYBACK
            }, "Pleos 기기 내 음성 재생 오류: ${error.name}")
            initialized.completeExceptionally(failure)
            finished.completeExceptionally(failure)
        }
        val listener = object : EventListener {
            override fun onStart() { Log.i("VoiceTest", "Pleos TTS onStart") }
            override fun onReady() { Log.i("VoiceTest", "Pleos TTS onReady") }
            override fun onDone() {
                if (active.get() && requested.get()) {
                    Log.i("VoiceTest", "Pleos TTS onDone")
                    finished.complete(SystemClock.elapsedRealtime())
                }
            }
            override fun onError(error: ErrorType) { fail(error) }
            override fun onStop() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        try {
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    if (active.get()) { Log.i("VoiceTest", "Pleos TTS initialized ON_DEVICE"); initialized.complete(Unit) }
                }
                override fun onFailure(error: ErrorType) { fail(error) }
            })
            if (withTimeoutOrNull(5_000L) { initialized.await(); true } != true) {
                throw VoiceFailure(VoiceFailureCode.NO_TTS, "Pleos 음성 SDK 초기화 시간 초과")
            }
            sdk.addEventListener(listener)
            listenerAdded = true
            // The on-device model rejects language-only "ko-"; it requires "ko-KR".
            sdk.setLocale(Locale.KOREA)
            sdk.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            onPhase(VoicePhase.SPEAKING)
            requested.set(true)
            sdk.speak(text)
            return withTimeoutOrNull(15_000L) { finished.await() }
                ?: throw VoiceFailure(VoiceFailureCode.TTS_TIMEOUT, "Pleos 음성 재생 완료 이벤트를 15초 안에 받지 못했어요")
        } finally {
            active.set(false)
            if (requested.get()) runCatching { sdk.stop() }
                .onFailure { Log.w("VoiceTest", "Pleos TTS stop failed: ${it.javaClass.simpleName}") }
            if (listenerAdded) runCatching { sdk.removeEventListener(listener) }
            runCatching { sdk.release() }
                .onFailure { Log.w("VoiceTest", "Pleos TTS release failed: ${it.javaClass.simpleName}") }
        }
    }
}
