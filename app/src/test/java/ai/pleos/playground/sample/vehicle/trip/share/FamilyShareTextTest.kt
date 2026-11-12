package ai.pleos.playground.sample.vehicle.trip.share

import ai.pleos.playground.sample.vehicle.trip.SummaryItem
import ai.pleos.playground.sample.vehicle.trip.SummaryKey
import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FamilyShareTextTest {

    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    private val endedAt = Calendar.getInstance(seoul).apply { clear(); set(2026, Calendar.NOVEMBER, 12, 15, 20) }.timeInMillis

    private fun content(isDemo: Boolean = false, mock: Boolean = false) = TripSummaryContent(
        tripId = "t1",
        headline = "오늘도 안전하게 운전하셨어요.",
        headlineTone = SummaryTone.GOOD,
        items = listOf(
            SummaryItem(SummaryKey.DURATION, "운전 시간", "42분", "자세한 설명", SummaryTone.INFO, false),
            SummaryItem(SummaryKey.COMPLIANCE, "운전 조건", "모두 지킴", null, SummaryTone.GOOD, mock),
        ),
        endedAt = endedAt,
        isDemo = isDemo,
    )

    @Test
    fun `요약 항목만 담고 날짜만 쓴다`() {
        val text = FamilyShareText.from(content(), seoul)
        assertEquals(
            """
            [계속운전] 11월 12일 운전 요약
            오늘도 안전하게 운전하셨어요.
            · 운전 시간: 42분
            · 운전 조건: 모두 지킴
            위치와 경로는 보내지 않아요.
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("15:20"))
        assertFalse(text.contains("3:20"))
    }

    @Test
    fun `임시값과 시연 기록은 표시한다`() {
        val text = FamilyShareText.from(content(isDemo = true, mock = true), seoul)
        assertTrue(text.startsWith("[계속운전] 11월 12일 운전 요약 (시연 기록)"))
        assertTrue(text.contains("· 운전 조건: 모두 지킴 (임시)"))
        assertTrue(text.endsWith("(임시) 표시는 아직 다른 기능과 연결되지 않은 예시 값이에요."))
    }
}
