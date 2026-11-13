package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Only called after a user starts a response/consent test, or an eligible real intervention. */
internal class PleosSpeechRecognizer(private val context: Context) {
    private open class SilentListener : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = Unit
        override fun onError(errorType: ErrorType) = Unit
    }

    /** Stop this app's abandoned session after a process restart; never calls request(). */
    suspend fun stopPreviousSession() {
        val sdk = SpeechToText(context, Mode.ON_DEVICE)
        val initialized = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val listener = object : SilentListener() {
            override fun onEndedRecognition() { stopped.complete(Unit) }
        }
        var added = false
        try {
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() { initialized.complete(Unit) }
                override fun onFailure(errorType: ErrorType) {
                    initialized.completeExceptionally(IllegalStateException(errorType.name))
                }
            })
            if (withTimeoutOrNull(5_000L) { initialized.await(); true } != true) return
            sdk.addListener(listener); added = true
            sdk.stop()
            withTimeoutOrNull(1_500L) { stopped.await() }
            Log.i("VoiceTest", "Pleos STT previous-session stop dispatched; no recording requested")
        } finally {
            if (added) runCatching { sdk.removeListener(listener) }
            runCatching { sdk.release() }
        }
    }

    suspend fun listen(questionEndedAt: Long, onPhase: (VoicePhase) -> Unit): VoiceAnswer {
        onPhase(VoicePhase.PREPARING)
        val sdk = SpeechToText(context, Mode.ON_DEVICE)
        val active = AtomicBoolean(true)
        val initialized = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Long>()
        val result = CompletableDeferred<VoiceAnswer>()
        val startedAt = AtomicReference<Long?>(null)
        val rmsCount = AtomicInteger(0)
        val maxRms = AtomicReference<Double?>(null)
        val stopping = AtomicBoolean(false)
        val stopped = CompletableDeferred<Unit>()
        var listenerAdded = false
        var requested = false
        fun answer(text: String?, end: VoiceEnd) = VoiceAnswer(
            text = text, responseMs = null, end = end,
            readyLatencyMs = startedAt.get()?.let { it - questionEndedAt },
            // SDK EPD reports byte offsets, not Android's speech-onset monotonic timestamp.
            responseTiming = "UNMEASURED_PLEOS_EPD_BYTE_OFFSETS",
            inputLevelCallbacks = rmsCount.get(), maxInputRms = maxRms.get(),
        )
        fun fail(error: ErrorType) {
            if (!active.get()) return
            Log.w("VoiceTest", "Pleos STT error=${error.name} started=${startedAt.get() != null}")
            val failure = VoiceFailure(
                if (error == ErrorType.PERMISSION_DENIED) VoiceFailureCode.PERMISSION else VoiceFailureCode.RECOGNIZER,
                "Pleos 음성 인식 오류: ${error.name}",
            )
            initialized.completeExceptionally(failure)
            started.completeExceptionally(failure)
            if (error == ErrorType.TIMEOUT && startedAt.get() != null) result.complete(answer(null, VoiceEnd.TIMEOUT))
            else result.completeExceptionally(failure)
        }
        val listener = object : ResultListener {
            override fun onReady() {
                if (!active.get()) return
                val now = SystemClock.elapsedRealtime()
                if (startedAt.compareAndSet(null, now)) {
                    Log.i("VoiceTest", "Pleos STT onReady")
                    onPhase(VoicePhase.WAITING)
                    started.complete(now)
                }
            }
            override fun onStartedRecognition() {
                if (active.get()) { Log.i("VoiceTest", "Pleos STT speech-start event"); onPhase(VoicePhase.LISTENING) }
            }
            override fun onUpdated(stt: String, completed: Boolean) {
                if (!active.get()) return
                Log.i("VoiceTest", "Pleos STT result completed=$completed chars=${stt.length}")
                if (completed) {
                    result.complete(answer(stt.takeIf { it.isNotBlank() },
                        if (stt.isBlank()) VoiceEnd.NO_MATCH else VoiceEnd.RECOGNIZED))
                    // A real final result is valid even if readiness/speech-start events were omitted.
                    started.complete(SystemClock.elapsedRealtime())
                }
            }
            override fun onUpdatedEpdData(on: Long, off: Long) {
                if (active.get()) Log.i("VoiceTest", "Pleos STT epd onBytes=$on offBytes=$off")
            }
            override fun onUpdatedRms(rms: Double) {
                if (!active.get() || !rms.isFinite()) return
                maxRms.updateAndGet { old -> if (old == null) rms else maxOf(old, rms) }
                if (rmsCount.incrementAndGet() == 1) Log.i("VoiceTest", "Pleos STT first RMS callback")
            }
            override fun onEndedRecognition() {
                if (stopping.get()) stopped.complete(Unit)
                if (active.get()) Log.i("VoiceTest", "Pleos STT onEndedRecognition")
            }
            override fun onError(errorType: ErrorType) = fail(errorType)
        }
        try {
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    if (active.get()) { Log.i("VoiceTest", "Pleos STT initialized ON_DEVICE"); initialized.complete(Unit) }
                }
                override fun onFailure(errorType: ErrorType) = fail(errorType)
            })
            if (withTimeoutOrNull(5_000L) { initialized.await(); true } != true)
                throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "Pleos STT 초기화 시간 초과")
            sdk.addListener(listener)
            listenerAdded = true
            sdk.setLocale(Locale.KOREA)
            requested = true
            sdk.request()
            if (withTimeoutOrNull(5_000L) { started.await(); true } != true)
                throw VoiceFailure(VoiceFailureCode.RECOGNIZER, "Pleos 인식 시작을 확인하지 못했어요. 무응답으로 판정하지 않아요")
            return withTimeoutOrNull(10_000L) { result.await() } ?: answer(null, VoiceEnd.TIMEOUT)
        } finally {
            active.set(false)
            Log.i("VoiceTest", "Pleos STT cleanup rmsCallbacks=${rmsCount.get()} maxRms=${maxRms.get()}")
            withContext(NonCancellable) {
                if (requested) {
                    stopping.set(true)
                    runCatching { sdk.stop(); withTimeoutOrNull(1_500L) { stopped.await() } }
                        .onFailure { Log.w("VoiceTest", "STT stop failed: ${it.javaClass.simpleName}") }
                }
                if (listenerAdded) runCatching { sdk.removeListener(listener) }
                runCatching { sdk.release() }.onFailure { Log.w("VoiceTest", "STT release failed: ${it.javaClass.simpleName}") }
            }
        }
    }
}
