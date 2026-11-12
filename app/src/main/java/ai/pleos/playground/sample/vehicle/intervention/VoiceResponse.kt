package ai.pleos.playground.sample.vehicle.intervention

enum class VoiceConsent { ACCEPT, DECLINE, UNKNOWN }

object VoiceResponse {
    fun consent(text: String?): VoiceConsent {
        val value = text?.replace(Regex("\\s+"), "")
            ?.trim { it in ".!?。！？" } ?: return VoiceConsent.UNKNOWN
        // Negative expressions win: "안내하지 마" must never be mistaken for consent.
        if (listOf("아니", "싫", "하지마", "하지말", "원하지", "안할", "안해", "괜찮").any { it in value }) return VoiceConsent.DECLINE
        return if (value in setOf("네", "예", "응", "좋아", "좋아요", "안내해줘", "안내해주세요", "안내해", "추가해줘", "추가해주세요", "추가해", "동의", "동의해요")) {
            VoiceConsent.ACCEPT
        } else VoiceConsent.UNKNOWN
    }
}
