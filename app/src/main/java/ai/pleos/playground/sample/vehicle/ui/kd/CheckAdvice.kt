package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel

/**
 * 홈에서 출발 전 반응 체크를 어떻게 보여 줄지.
 *
 * 팀 결정(2026-11-10): "시동 후 체크는 시간을 뺏기는 느낌" → 강요하지 않는다.
 * ① 선택형("체크할까요?" + 바로 출발) + ③ 밤·새벽이거나 지난 주행에 피로 신호가 있었을 때만 권유.
 */
enum class CheckAdvice {
    /** 오늘 이미 체크함 → 결과 표시 */
    DONE_TODAY,
    /** 오늘은 [바로 출발]을 고름 → 조용히 둔다 */
    SKIPPED_TODAY,
    /** 밤·새벽이라 권유 */
    RECOMMEND_NIGHT,
    /** 지난 주행에 피로 신호(CAUTION 이상)가 있어서 권유 */
    RECOMMEND_AFTER_FATIGUE,
    /** 권유하지 않음. 원하면 할 수 있다는 정도로만 */
    OPTIONAL,
}

/** 밤·새벽 시간대(이 시각 이상 또는 [NIGHT_END_HOUR] 미만) */
const val NIGHT_START_HOUR = 19
const val NIGHT_END_HOUR = 6

/**
 * @param checkedToday     오늘 반응 체크 기록이 있는지
 * @param skippedToday     오늘 [바로 출발]을 눌렀는지
 * @param hourOfDay        지금 시각(0~23, 한국 시간)
 * @param lastTripMaxLevel 지난 주행에서 가장 높았던 피로 단계(모르면 null)
 * @param recentWarning    최근 24시간 안에 음성 확인·휴식 권고(개입 2·3단계)가 있었는지
 */
fun checkAdvice(
    checkedToday: Boolean,
    skippedToday: Boolean,
    hourOfDay: Int,
    lastTripMaxLevel: FatigueLevel?,
    recentWarning: Boolean,
): CheckAdvice = when {
    checkedToday -> CheckAdvice.DONE_TODAY
    skippedToday -> CheckAdvice.SKIPPED_TODAY
    (lastTripMaxLevel != null && lastTripMaxLevel >= FatigueLevel.CAUTION) || recentWarning ->
        CheckAdvice.RECOMMEND_AFTER_FATIGUE
    hourOfDay >= NIGHT_START_HOUR || hourOfDay < NIGHT_END_HOUR -> CheckAdvice.RECOMMEND_NIGHT
    else -> CheckAdvice.OPTIONAL
}

val CheckAdvice.isRecommended: Boolean
    get() = this == CheckAdvice.RECOMMEND_NIGHT || this == CheckAdvice.RECOMMEND_AFTER_FATIGUE
