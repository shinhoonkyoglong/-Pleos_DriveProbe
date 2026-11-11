package ai.pleos.playground.sample.vehicle.intervention

enum class VoicePhase { IDLE, CHECKING, PREPARING, SPEAKING, WAITING, LISTENING, SAVING, FINISHED }
enum class VoiceEnd { RECOGNIZED, TIMEOUT, NO_MATCH }
enum class VoiceFailureCode { PERMISSION, NO_RECOGNIZER, NO_TTS, TTS_LANGUAGE, TTS_PLAYBACK, TTS_TIMEOUT, RECOGNIZER, BUSY }

class VoiceFailure(val code: VoiceFailureCode, message: String, val platformCode: Int? = null) : IllegalStateException(message)

data class VoiceCapabilities(
    val microphoneGranted: Boolean,
    val recognitionAvailable: Boolean,
    val ttsServices: List<String>,
    val recognitionService: String? = null,
    val ttsBackend: SpeechBackend = SpeechBackend.ANDROID,
    val recognitionBackend: SpeechBackend = SpeechBackend.ANDROID,
)

enum class SpeechBackend { PLEOS_ON_DEVICE, ANDROID, NONE }

internal fun speechBackend(pleosAvailable: Boolean, androidEngines: Int): SpeechBackend = when {
    pleosAvailable -> SpeechBackend.PLEOS_ON_DEVICE
    androidEngines > 0 -> SpeechBackend.ANDROID
    else -> SpeechBackend.NONE
}

data class VoiceAnswer(
    val text: String?,
    val responseMs: Long?,
    val end: VoiceEnd = VoiceEnd.RECOGNIZED,
    val readyLatencyMs: Long? = null,
    val platformCode: Int? = null,
    val responseTiming: String = "TTS_onDone_to_speech_onBeginning",
    val inputLevelCallbacks: Int? = null,
    val maxInputRms: Double? = null,
    val captureLatencyMs: Long? = null,
    val epdOnBytes: Long? = null,
    val epdOffBytes: Long? = null,
    val audioBytes: Long? = null,
)

interface VoiceConversation {
    fun capabilities(): VoiceCapabilities
    suspend fun play(prompt: String, onPhase: (VoicePhase) -> Unit = {}): Long
    suspend fun ask(prompt: String, onPhase: (VoicePhase) -> Unit = {}): VoiceAnswer
    suspend fun askTimed(prompt: String, onPhase: (VoicePhase) -> Unit = {}): VoiceAnswer = ask(prompt, onPhase)
}

/** Monotonic times. Missing callbacks remain unknown, never a fabricated zero response time. */
internal class VoiceTiming(private val questionEndedAt: Long) {
    var readyLatencyMs: Long? = null
        private set
    var responseMs: Long? = null
        private set
    fun ready(at: Long) { if (readyLatencyMs == null && at >= questionEndedAt) readyLatencyMs = at - questionEndedAt }
    fun speech(at: Long) { if (responseMs == null && at >= questionEndedAt) responseMs = at - questionEndedAt }
}
