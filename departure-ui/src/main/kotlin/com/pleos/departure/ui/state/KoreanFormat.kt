package com.pleos.departure.ui.state

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * 화면용 한국어 시각·시간·거리 표기. 반올림 방향은 **보수적**으로 정한다.
 * - 시각(마감·일몰): 초를 버린다 → 16:14:21 마감은 "오후 4시 14분"(실제보다 이르게 보임)
 * - 남은 시간: 분 단위 내림 → 실제보다 짧게 보임
 * - 이동 시간: 분 단위 올림 → 실제보다 길게 보임
 */
internal object KoreanFormat {

    /** 예: "오후 4시 14분", 정각이면 "오후 5시". [zone]으로 바꿔서 쓴다(DepartureConfig.timeZone). */
    fun time(at: ZonedDateTime, zone: ZoneId): String {
        val local = at.withZoneSameInstant(zone)
        val hour = local.hour
        val ampm = if (hour < 12) "오전" else "오후"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return if (local.minute == 0) "$ampm ${h12}시" else "$ampm ${h12}시 ${local.minute}분"
    }

    /** 남은 시간(내림). 1분 미만이면 "1분 미만". 음수는 0으로 본다(화면은 음수 시간을 보여 주지 않음 — 리뷰 task-04b MINOR-5). */
    fun remaining(d: Duration): String {
        val minutes = if (d.isNegative) 0 else d.toMinutes()
        return if (minutes < 1) "1분 미만" else hoursMinutes(minutes)
    }

    /** 이동·여유 시간(올림). 예: "42분", "1시간 5분". */
    fun durationCeil(d: Duration): String {
        val seconds = if (d.isNegative) 0 else d.seconds
        val minutes = (seconds + 59) / 60
        return hoursMinutes(minutes)
    }

    private fun hoursMinutes(totalMinutes: Long): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0L -> "${m}분"
            m == 0L -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    /** 예: "약 4.3km", 1km 미만은 "약 850m". */
    fun distance(meters: Double): String =
        if (meters < 1_000.0) {
            "약 ${(meters / 10).toLong() * 10}m"
        } else {
            "약 ${String.format(Locale.ROOT, "%.1f", meters / 1_000.0)}km"
        }

    /** 반경 표기. 예: 10000.0 → "10km", 2500.0 → "2.5km". */
    fun radius(meters: Double): String {
        val km = meters / 1_000.0
        return if (km == Math.floor(km)) "${km.toLong()}km" else "${String.format(Locale.ROOT, "%.1f", km)}km"
    }

    /** 원 단위 금액. 예: 700 → "700원", 12000 → "12,000원". */
    fun won(amount: Int): String = String.format(Locale.ROOT, "%,d원", amount)
}
