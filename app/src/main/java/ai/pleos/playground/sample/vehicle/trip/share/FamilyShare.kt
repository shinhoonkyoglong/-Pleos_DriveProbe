package ai.pleos.playground.sample.vehicle.trip.share

import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * 주행 요약을 가족에게 보내기.
 *
 * - **운전자가 버튼을 눌렀을 때만** 보낸다(자동 전송 없음). 무엇이 가족에게 가는지 운전자가 정한다
 * - 보내는 내용은 주행 요약과 같은 항목의 글뿐이다. 위치·경로·출발/도착 시각은 넣지 않는다(날짜만)
 * - 어떻게 보낼지(서버·문자 등)는 [FamilyShareSender]만 바꾸면 된다. 지금은 서버가 없어 [MockFamilyShareSender]
 *
 * 화면 버튼은 trip/ui/FamilyShareButton.kt(값만 받는 Composable), 연결은 TripModule.shareToFamily
 */

/** 가족에게 보낼 글. 주행 요약([TripSummaryContent])에서 만든다(순수 로직) */
object FamilyShareText {

    fun from(c: TripSummaryContent, zone: TimeZone = TimeZone.getDefault()): String = buildString {
        val day = SimpleDateFormat("M월 d일", Locale.KOREAN).apply { timeZone = zone }.format(Date(c.endedAt))
        append("[계속운전] ").append(day).append(" 운전 요약")
        if (c.isDemo) append(" (시연 기록)")
        append('\n').append(c.headline).append('\n')
        c.items.forEach { item ->
            append("· ").append(item.title).append(": ").append(item.value)
            if (item.isMock) append(" (임시)")
            append('\n')
        }
        append("위치와 경로는 보내지 않아요.")
        if (c.hasMock) append("\n(임시) 표시는 아직 다른 기능과 연결되지 않은 예시 값이에요.")
    }
}

/** 보내기 결과 */
sealed interface ShareResult {
    /** 보냄. [isMock]이면 실제로는 아무에게도 가지 않았다(서버 연결 전) */
    data class Sent(val isMock: Boolean) : ShareResult

    /** 못 보냄. [reason]은 화면에 그대로 보여줄 쉬운 문장 */
    data class Failed(val reason: String) : ShareResult
}

/**
 * 가족에게 보내는 통로. 서버·문자·Pleos 메시지 등 실제 방법이 정해지면 이것만 구현해서
 * `TripModule.familyShareSender`에 넣는다. (Pleos에서 문자·메시지를 보낼 수 있는지는 확인 필요)
 */
fun interface FamilyShareSender {
    suspend fun send(tripId: String, text: String): ShareResult
}

/** 임시: 로그(tag "Trip")에만 남기고 보냈다고 답한다. 화면에는 "임시"로 표시된다 */
object MockFamilyShareSender : FamilyShareSender {
    override suspend fun send(tripId: String, text: String): ShareResult {
        Log.i("Trip", "family share (MOCK, not delivered) trip=$tripId\n$text")
        return ShareResult.Sent(isMock = true)
    }
}
