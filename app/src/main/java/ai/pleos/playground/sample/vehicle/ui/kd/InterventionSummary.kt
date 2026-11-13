package ai.pleos.playground.sample.vehicle.ui.kd

/** Consent alone must never be presented as a successfully applied waypoint. */
internal fun interventionSummary(level: Int, outcome: String?, consent: String?, navigation: String?, facilityKind: String? = null): String =
    interventionSummaryText(level,outcome,consent,navigation).let {
        if(facilityKind == "SLEEP_REST_AREA") it.replace("휴게소", "졸음쉼터") else it
    }

private fun interventionSummaryText(level: Int, outcome: String?, consent: String?, navigation: String?): String {
    if (level == 3) {
        if (navigation == "VERIFIED_WAYPOINT") return "휴게소를 경유지로 추가했어요"
        if (navigation == "WAYPOINT_OBSERVED") return "휴게소 경유 항목 생성을 확인했어요. 위치는 내비에서 확인해 주세요"
        if (navigation in setOf("REQUEST_UNCONFIRMED", "CANCELLED_AFTER_REQUEST_UNCONFIRMED"))
            return "휴게소 추가 요청의 적용 여부를 내비에서 확인해 주세요"
        if (consent == "ACCEPT") return "휴게소 추가에 동의했지만 적용은 확인되지 않았어요"
        if (outcome == "DECLINED") return "휴게소 안내를 거절했어요"
        if (outcome in setOf("NO_RESPONSE", "NO_MATCH", "UNKNOWN_RESPONSE"))
            return "안내 동의를 확인하지 못해 경로를 변경하지 않았어요"
    }
    if (outcome == "CANCELLED") return "개입이 중지됐어요"
    if (outcome in setOf("FAILED", "UNAVAILABLE")) return "개입을 완료하지 못했어요"
    return when {
        level == 1 && outcome == "VERIFIED" -> "공조 온도·풍량 조절을 확인했어요"
        level == 1 -> "공조 조절 결과를 확인하지 못했어요"
        level == 2 && outcome == "ANSWERED" -> "안전 확인 질문의 응답을 기록했어요"
        level == 2 -> "안전 확인 질문의 응답을 확인하지 못했어요"
        else -> "휴게소 안내 결과를 확인해 주세요"
    }
}
