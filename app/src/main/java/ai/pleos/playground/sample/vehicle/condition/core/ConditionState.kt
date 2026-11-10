package ai.pleos.playground.sample.vehicle.condition.core

/** 피로 단계. 팀 합의 대상(CLAUDE.md 2장). */
enum class FatigueLevel { NORMAL, MILD, CAUTION, DANGER }

/** 출발 전 반응 체크 결과. "진단"이 아니라 본인 평소 대비 변화다. */
enum class ReactionVerdict { NORMAL, SLOWER, MUCH_SLOWER, INCONCLUSIVE }

/** 점수가 오른 이유 코드. 음성 안내·요약을 맡은 파트는 코드로 분기하고, 문장은 [ConditionReason.message]를 그대로 써도 된다. */
enum class ReasonCode {
    MICRO_CORRECTION_DROP,
    LARGE_CORRECTION_RISE,
    STEERING_JERK,
    LONG_DRIVE,
    TIME_OF_DAY,
    PRE_DRIVE_SLOWER,
}

data class ConditionReason(
    val code: ReasonCode,
    val message: String,
)

/**
 * 다른 파트에 내보내는 컨디션 상태. StateFlow<ConditionState>로 공개된다.
 *
 * 앞의 3개(level, score, isCalibrating)가 팀 합의 필드이고, 나머지는 기본값이 있어 몰라도 된다.
 *
 * @param level           피로 단계
 * @param score           0~1 피로 점수(지수이동평균으로 부드럽게 한 값)
 * @param isCalibrating   개인 기준선이 아직 없어 기본 기준선으로 비교 중이면 true
 * @param lastWindowValid 마지막 60초 창이 점수 계산 조건(60km/h 이상·D·방향지시등 꺼짐·직진)을 만족했는지.
 *                        false면 score/level은 직전 값을 유지한 것이다
 * @param drivingMinutes  연속 주행 시간(분). 10분 이상 쉬면 0으로 리셋
 * @param preDrive        이번 주행의 출발 전 반응 체크 결과(안 했으면 null)
 * @param reasons         점수가 오른 이유(비어 있으면 특이사항 없음)
 * @param timeMs          이 상태가 계산된 시각(단조 시계 ms)
 */
data class ConditionState(
    val level: FatigueLevel,
    val score: Float,
    val isCalibrating: Boolean,
    val lastWindowValid: Boolean = false,
    val drivingMinutes: Float = 0f,
    val preDrive: ReactionVerdict? = null,
    val reasons: List<ConditionReason> = emptyList(),
    val timeMs: Long = 0L,
) {
    companion object {
        val INITIAL = ConditionState(FatigueLevel.NORMAL, 0f, isCalibrating = true)
    }
}
