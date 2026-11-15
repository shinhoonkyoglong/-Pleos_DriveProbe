package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* 판단 결과 → 화면 문구·상태. 문구는 '진단'이 아니라 '평소 대비 권고'로 쓴다(기획서: 시동을 막지 않고 권고만). */

data class KdStatus(val tone: KdTone, val title: String, val detail: String)

fun FatigueLevel.toStatus(): KdStatus = when (this) {
    FatigueLevel.NORMAL -> KdStatus(KdTone.GOOD, "컨디션 좋아요", "평소처럼 운전하고 있어요")
    FatigueLevel.MILD -> KdStatus(KdTone.CAUTION, "조금 피곤해 보여요", "차 안 공기를 바꿔 드릴게요")
    FatigueLevel.CAUTION -> KdStatus(KdTone.SERIOUS, "주의가 필요해요", "음성으로 잠깐 여쭤볼게요")
    FatigueLevel.DANGER -> KdStatus(KdTone.DANGER, "쉬어 가세요", "가까운 쉼터를 안내해 드릴게요")
}

fun ReactionVerdict.toStatus(): KdStatus = when (this) {
    ReactionVerdict.NORMAL -> KdStatus(KdTone.GOOD, "평소와 비슷해요", "오늘 반응 속도는 평소 수준이에요")
    ReactionVerdict.SLOWER -> KdStatus(KdTone.CAUTION, "평소보다 조금 느려요", "출발 전에 잠시 쉬어 가도 좋아요")
    ReactionVerdict.MUCH_SLOWER -> KdStatus(KdTone.DANGER, "평소보다 많이 느려요", "충분히 쉬고 출발하는 걸 권해요")
    ReactionVerdict.INCONCLUSIVE -> KdStatus(KdTone.UNKNOWN, "이번엔 비교하지 못했어요", "다시 해 보시면 평소와 비교해 드려요")
}

fun GearPosition.label(): String = when (this) {
    GearPosition.PARK -> "P 주차"
    GearPosition.REVERSE -> "R 후진"
    GearPosition.NEUTRAL -> "N 중립"
    GearPosition.DRIVE -> "D 주행"
    GearPosition.UNKNOWN -> "기어 확인 중"
}

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
private val DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 a h시 m분", Locale.KOREAN)
private val DATE_SHORT = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private val TODAY = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

fun formatDateTime(epochMs: Long): String = DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZONE))
fun formatShortDate(epochMs: Long): String = DATE_SHORT.format(Instant.ofEpochMilli(epochMs).atZone(ZONE))
fun formatToday(): String = TODAY.format(Instant.now().atZone(ZONE))

fun formatMinutes(minutes: Float): String {
    val m = minutes.toInt()
    return if (m < 60) "${m}분" else "${m / 60}시간 ${m % 60}분"
}
