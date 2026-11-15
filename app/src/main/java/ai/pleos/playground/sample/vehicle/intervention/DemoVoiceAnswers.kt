package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import android.content.Context

/**
 * Debug 시연 전용: 에뮬레이터 마이크가 PC 소리를 받지 못할 때 음성 질문의 답을 정해진 문장으로 바로 넣는다.
 *
 * - 질문은 실제 TTS로 재생하고, STT 대신 [DELAY_MS] 뒤 아래 답을 받은 것으로 처리한다. 이후 판정·경유지 추가는 실제 흐름 그대로다.
 * - 이력에는 responseTiming=[TIMING]이 남아 실제 음성 응답과 구분된다. release 빌드에서는 항상 꺼져 있다.
 * - 켜기/끄기(설정은 저장되어 앱을 다시 시작해도 유지):
 *   adb shell am broadcast --user 10 -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.voice.DEMO_ANSWERS --ez enabled true
 */
object DemoVoiceAnswers {
    const val ACTION = "ai.pleos.voice.DEMO_ANSWERS"
    const val TIMING = "DEMO_SCRIPTED_ANSWER"
    const val DELAY_MS = 1_500L
    private const val PREFS = "demo_voice_answers"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    var enabled = false
        private set

    fun load(context: Context) {
        enabled = BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun set(context: Context, on: Boolean) {
        if (!BuildConfig.DEBUG) return
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun answerFor(level: FatigueLevel): String? = if (!BuildConfig.DEBUG || !enabled) null else when (level) {
        FatigueLevel.CAUTION -> "괜찮아요"
        FatigueLevel.DANGER -> "안내해 줘"
        else -> null
    }
}
