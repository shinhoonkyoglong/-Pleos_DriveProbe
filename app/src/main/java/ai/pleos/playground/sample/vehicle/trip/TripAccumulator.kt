package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import java.util.UUID

/**
 * 끝난 주행 1회의 집계 결과 (3번 컨디션 엔진 값만 모은 것. 휴식 권고·조건 준수는 아직 붙기 전).
 *
 * @param observedMs        엔진 상태를 받은 시간(ms, 엔진 시계 기준)
 * @param judgedMs          그중 마지막 60초 창이 피로 판정 조건(60km/h 이상·D·방향지시등 꺼짐·직진)을 만족한 시간(ms).
 *                          0이면 이번 주행은 피로를 판정하지 못한 것이다("정상"과 다름)
 * @param maxScore          판정된 동안 가장 높은 피로 점수(0~1). 판정이 없으면 0
 * @param avgScore          판정된 동안의 평균 피로 점수(0~1, 시간 가중). 판정이 없으면 null
 * @param maxDrivingMinutes 쉬지 않고 이어서 운전한 시간의 최대값(분, 3번 DrivingTimer 기준: 10분 이상 쉬면 0으로)
 * @param preDrive          이번 주행에 반영된 출발 전 반응 체크 결과(안 했으면 null)
 * @param isCalibrating     주행이 끝날 때 개인 기준선이 아직 없었으면 true(점수는 참고용)
 */
data class CompletedTrip(
    val tripId: String,
    val startedAt: Long,
    val endedAt: Long,
    val maxLevel: FatigueLevel,
    val mildEntries: Int,
    val cautionEntries: Int,
    val dangerEntries: Int,
    val reasonCodes: Set<ReasonCode>,
    val isDemo: Boolean,
    val observedMs: Long = 0,
    val judgedMs: Long = 0,
    val maxScore: Float = 0f,
    val avgScore: Float? = null,
    val maxDrivingMinutes: Float = 0f,
    val preDrive: ReactionVerdict? = null,
    val isCalibrating: Boolean = true,
) {
    val durationMs: Long get() = endedAt - startedAt
}

/**
 * 주행 1회 동안의 컨디션 상태를 모으는 순수 로직(스레드 안전하지 않음 — 한 코루틴에서만 호출).
 *
 * 사용 순서: [start] → [onState] 여러 번 → [finish] (또는 중단 시 [abort])
 * 중간 저장: 주행 중 [snapshot]을 저장해 두고, 앱을 다시 켜면 [restore]로 이어 가거나 바로 [finish]한다
 *
 * 단계 진입 횟수는 "더 높은 단계로 올라간 순간"만 센다.
 * 예) NORMAL → CAUTION(+1) → MILD(내려감, 안 셈) → CAUTION(+1) → DANGER(+1)
 *
 * 3번 엔진은 0.1초마다 [ConditionState.timeMs]를 갱신하고, 점수·단계·lastWindowValid는 10초마다 창이 끝날 때 바뀐다.
 * 그래서 판정 여부·평균 점수는 "창 개수"가 아니라 상태가 유지된 **시간**으로 센다
 * (StateFlow라 중간 상태를 건너뛰어 받아도 시간 합은 맞다).
 */
class TripAccumulator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newTripId: () -> String = { UUID.randomUUID().toString() },
) {
    private var draft: Draft? = null

    val isInTrip: Boolean get() = draft != null

    /**
     * 주행 시작. 이미 주행 중이면 무시한다.
     *
     * @param current 시작 순간 엔진이 들고 있던 상태. 이전 주행의 상태일 수 있어서
     *                같은 상태(timeMs)는 이번 주행에 넣지 않는다
     */
    fun start(isDemo: Boolean, current: ConditionState? = null) {
        if (draft != null) return
        draft = Draft(newTripId(), clock(), isDemo, staleTimeMs = current?.timeMs?.takeIf { it > 0 })
    }

    /** 주행 중 컨디션 상태 반영. 주행 중이 아니면 무시한다 */
    fun onState(state: ConditionState) {
        draft?.update(state)
    }

    /**
     * 주행 종료. 주행 중이 아니었으면 null
     * @param endedAt 종료 시각(기본 지금). 중간 저장에서 복구할 때는 마지막 저장 시각을 넣는다
     */
    fun finish(endedAt: Long = clock()): CompletedTrip? {
        val d = draft ?: return null
        draft = null
        return d.complete(endedAt)
    }

    /** 진행 중인 주행의 지금 집계 상태(중간 저장용). 주행 중이 아니면 null */
    fun snapshot(): TripSnapshot? = draft?.toSnapshot(clock())

    /**
     * 중간 저장에서 주행을 되살린다(진행 중이던 주행은 버린다). 이후 [onState]로 이어서 집계하거나 [finish]한다.
     * 엔진 시계는 이어지지 않으므로 다음 상태부터 새로 시간을 잰다.
     */
    fun restore(snapshot: TripSnapshot) {
        draft = Draft(snapshot.tripId, snapshot.startedAt, snapshot.isDemo, staleTimeMs = null).apply { load(snapshot) }
    }

    /** 저장하지 않고 버린다 (예: 주행 중 엔진 모드가 바뀐 경우) */
    fun abort() {
        draft = null
    }

    private class Draft(
        val tripId: String,
        val startedAt: Long,
        val isDemo: Boolean,
        val staleTimeMs: Long?,
    ) {
        private var lastLevel = FatigueLevel.NORMAL
        private var maxLevel = FatigueLevel.NORMAL
        private val entries = IntArray(FatigueLevel.entries.size)
        private val reasons = LinkedHashSet<ReasonCode>()

        private var prev: ConditionState? = null
        private var observedMs = 0L
        private var judgedMs = 0L
        private var scoreMsSum = 0.0
        private var maxScore = 0f
        private var maxDrivingMinutes = 0f
        private var preDrive: ReactionVerdict? = null
        private var isCalibrating = true

        fun update(s: ConditionState) {
            if (staleTimeMs != null && s.timeMs == staleTimeMs) return // 이전 주행의 상태
            if (s.level > lastLevel) entries[s.level.ordinal]++
            lastLevel = s.level
            if (s.level > maxLevel) maxLevel = s.level
            s.reasons.forEach { reasons += it.code }

            // 직전 상태가 유지된 시간을 직전 상태 몫으로 더한다
            val p = prev
            if (p != null && p.timeMs > 0 && s.timeMs > p.timeMs) {
                val dt = s.timeMs - p.timeMs
                observedMs += dt
                if (p.lastWindowValid) {
                    judgedMs += dt
                    scoreMsSum += p.score.toDouble() * dt
                }
            }
            if (s.timeMs > 0 && (p == null || s.timeMs >= p.timeMs)) prev = s
            if (s.lastWindowValid && s.score > maxScore) maxScore = s.score
            if (s.drivingMinutes > maxDrivingMinutes) maxDrivingMinutes = s.drivingMinutes
            if (s.preDrive != null) preDrive = s.preDrive
            isCalibrating = s.isCalibrating
        }

        fun toSnapshot(savedAt: Long) = TripSnapshot(
            tripId = tripId,
            startedAt = startedAt,
            savedAt = savedAt,
            isDemo = isDemo,
            lastLevel = lastLevel,
            maxLevel = maxLevel,
            mildEntries = entries[FatigueLevel.MILD.ordinal],
            cautionEntries = entries[FatigueLevel.CAUTION.ordinal],
            dangerEntries = entries[FatigueLevel.DANGER.ordinal],
            reasonCodes = reasons.toSet(),
            observedMs = observedMs,
            judgedMs = judgedMs,
            scoreMsSum = scoreMsSum,
            maxScore = maxScore,
            maxDrivingMinutes = maxDrivingMinutes,
            preDrive = preDrive,
            isCalibrating = isCalibrating,
        )

        fun load(s: TripSnapshot) {
            lastLevel = s.lastLevel
            maxLevel = s.maxLevel
            entries[FatigueLevel.MILD.ordinal] = s.mildEntries
            entries[FatigueLevel.CAUTION.ordinal] = s.cautionEntries
            entries[FatigueLevel.DANGER.ordinal] = s.dangerEntries
            reasons.clear(); reasons += s.reasonCodes
            prev = null
            observedMs = s.observedMs
            judgedMs = s.judgedMs
            scoreMsSum = s.scoreMsSum
            maxScore = s.maxScore
            maxDrivingMinutes = s.maxDrivingMinutes
            preDrive = s.preDrive
            isCalibrating = s.isCalibrating
        }

        fun complete(endedAt: Long) = CompletedTrip(
            tripId = tripId,
            startedAt = startedAt,
            endedAt = endedAt,
            maxLevel = maxLevel,
            mildEntries = entries[FatigueLevel.MILD.ordinal],
            cautionEntries = entries[FatigueLevel.CAUTION.ordinal],
            dangerEntries = entries[FatigueLevel.DANGER.ordinal],
            reasonCodes = reasons.toSet(),
            isDemo = isDemo,
            observedMs = observedMs,
            judgedMs = judgedMs,
            maxScore = if (judgedMs == 0L) 0f else maxScore,
            avgScore = if (judgedMs == 0L) null else (scoreMsSum / judgedMs).toFloat(),
            maxDrivingMinutes = maxDrivingMinutes,
            preDrive = preDrive,
            isCalibrating = isCalibrating,
        )
    }
}
