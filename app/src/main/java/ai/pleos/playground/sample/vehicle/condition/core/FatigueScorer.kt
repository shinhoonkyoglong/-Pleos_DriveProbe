package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.math.roundToInt

data class FatigueParams(
    // 최종 raw = min(1, steeringWeight × 조향성분 + contextWeight × 상황성분)
    val steeringWeight: Float = 0.75f,
    val contextWeight: Float = 0.35f,
    // 조향 성분 내부 가중치 (합 1)
    val wMicroDrop: Float = 0.35f,
    val wLargeRise: Float = 0.25f,
    val wJerk: Float = 0.40f,
    /** z가 zStart를 넘으면 오르기 시작해 zFull에서 1 */
    val zStart: Float = 1f,
    val zFull: Float = 3f,
    /** 창 하나에 급조향이 이 횟수면 급조향 성분 1 */
    val jerkFullCount: Int = 2,
    // 상황 성분 내부 가중치 (합 1)
    val wDrive: Float = 0.4f,
    val wTimeOfDay: Float = 0.3f,
    val wPreDrive: Float = 0.3f,
    val driveStartMin: Float = 60f,
    val driveFullMin: Float = 120f,
    /** 지수이동평균 계수(창마다): score = α·raw + (1-α)·score */
    val emaAlpha: Float = 0.5f,
    // 단계 경계(올라갈 때). 내려갈 때는 hysteresis만큼 더 내려가야 한다
    val mildAt: Float = 0.30f,
    val cautionAt: Float = 0.50f,
    val dangerAt: Float = 0.70f,
    val hysteresis: Float = 0.08f,
)

data class ScoreContext(
    val drivingMinutes: Float,
    val hourOfDay: Int,
    val preDrive: ReactionVerdict?,
)

/** 점수 구성 요소(각 0~1). 시연 화면·로그에서 "왜 이 점수인지" 보여줄 때 쓴다. */
data class ScoreBreakdown(
    val microDrop: Float,
    val largeRise: Float,
    val jerk: Float,
    val driveTime: Float,
    val timeOfDay: Float,
    val preDrive: Float,
    val raw: Float,
)

class FatigueScorer(private val p: FatigueParams = FatigueParams()) {

    var score: Float = 0f
        private set
    var level: FatigueLevel = FatigueLevel.NORMAL
        private set

    fun update(f: SteeringFeatures, b: SteeringBaseline, ctx: ScoreContext): ScoreBreakdown {
        val bd = breakdown(f, b, ctx)
        score = p.emaAlpha * bd.raw + (1 - p.emaAlpha) * score
        level = levelFor(score, level, p)
        return bd
    }

    fun breakdown(f: SteeringFeatures, b: SteeringBaseline, ctx: ScoreContext): ScoreBreakdown {
        val micro = ramp(-b.small.z(f.reversalsSmallPerMin))   // 미세 수정이 평소보다 적을수록
        val large = ramp(b.large.z(f.reversalsLargePerMin))    // 큰 수정이 평소보다 많을수록
        val jerk = (f.lullJerkCount.toFloat() / p.jerkFullCount).coerceIn(0f, 1f)
        val steer = p.wMicroDrop * micro + p.wLargeRise * large + p.wJerk * jerk

        val drive = ((ctx.drivingMinutes - p.driveStartMin) / (p.driveFullMin - p.driveStartMin)).coerceIn(0f, 1f)
        val tod = timeOfDayRisk(ctx.hourOfDay)
        val pre = when (ctx.preDrive) {
            ReactionVerdict.SLOWER -> 0.5f
            ReactionVerdict.MUCH_SLOWER -> 1f
            else -> 0f
        }
        val context = p.wDrive * drive + p.wTimeOfDay * tod + p.wPreDrive * pre
        val raw = (p.steeringWeight * steer + p.contextWeight * context).coerceIn(0f, 1f)
        return ScoreBreakdown(micro, large, jerk, drive, tod, pre, raw)
    }

    /** 점수 이유 문장. "진단"이 아니라 "평소 대비 변화"로 표현한다. */
    fun reasons(bd: ScoreBreakdown, f: SteeringFeatures, ctx: ScoreContext): List<ConditionReason> {
        val out = ArrayList<ConditionReason>()
        if (bd.microDrop >= REASON_MIN) out += ConditionReason(ReasonCode.MICRO_CORRECTION_DROP, "평소보다 작은 조향 수정이 줄었어요")
        if (bd.largeRise >= REASON_MIN) out += ConditionReason(ReasonCode.LARGE_CORRECTION_RISE, "평소보다 크게 꺾는 조향이 늘었어요")
        if (f.lullJerkCount > 0) out += ConditionReason(ReasonCode.STEERING_JERK, "한동안 조향이 없다가 갑자기 크게 꺾은 적이 ${f.lullJerkCount}번 있었어요")
        if (bd.driveTime > 0f) out += ConditionReason(ReasonCode.LONG_DRIVE, "쉬지 않고 ${ctx.drivingMinutes.roundToInt()}분째 운전 중이에요")
        if (bd.timeOfDay > 0f) out += ConditionReason(ReasonCode.TIME_OF_DAY, "졸음이 오기 쉬운 시간대예요")
        if (bd.preDrive > 0f) out += ConditionReason(ReasonCode.PRE_DRIVE_SLOWER, "출발 전 반응 체크가 평소보다 느렸어요")
        return out
    }

    fun reset() {
        score = 0f
        level = FatigueLevel.NORMAL
    }

    private fun ramp(z: Float): Float = ((z - p.zStart) / (p.zFull - p.zStart)).coerceIn(0f, 1f)

    companion object {
        private const val REASON_MIN = 0.3f

        /** 시간대 위험도(0~1). 새벽(2~4시)이 가장 높고, 점심 이후(14~15시)에도 조금 오른다. */
        fun timeOfDayRisk(hour: Int): Float = when (hour) {
            2, 3, 4 -> 1f
            1, 5 -> 0.6f
            0 -> 0.4f
            14, 15 -> 0.5f
            else -> 0f
        }

        /** 단계 판정(히스테리시스): 올라갈 때는 경계값, 내려갈 때는 경계값 - hysteresis 아래로 가야 한다. */
        fun levelFor(score: Float, current: FatigueLevel, p: FatigueParams = FatigueParams()): FatigueLevel {
            val up = floatArrayOf(0f, p.mildAt, p.cautionAt, p.dangerAt)
            var l = current.ordinal
            while (l < 3 && score >= up[l + 1]) l++
            while (l > 0 && score < up[l] - p.hysteresis) l--
            return FatigueLevel.entries[l]
        }
    }
}
