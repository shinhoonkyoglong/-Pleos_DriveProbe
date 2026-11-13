package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.random.Random

/*
 * 출발 전 반응 체크 (PVT 축소판) — 화면(ui)과 분리된 순수 로직.
 *
 * 데이터 흐름: ui가 매 프레임 update(now)를 부르고, 반응 입력을 넘긴다
 *              - 터치: onTap(now)
 *              - 브레이크 페달: onPress(now) / onRelease(now) (밟은 채로는 다음 자극이 나오지 않는다)
 *            → 세션이 자극 일정·시도 기록을 관리 → 유효 반응 targetValidTrials회 또는 durationMs가 되면 끝 → summary()
 *            → ReactionEvaluator가 본인 기준선과 비교해 ReactionResult(판정·문구)
 *            → ConditionProcessor.setPreDrive(result.verdict)로 피로 점수에 가산
 *
 * 소요 시간(2026-11-13 결정): "유효 반응 10회가 모이면 끝, 최대 40초" → 보통 약 30초.
 */

/** 반응을 받는 방법. 터치와 브레이크는 반응 시간이 달라 기준선을 따로 쓴다 */
enum class ReactionInput { TOUCH, BRAKE }

data class ReactionParams(
    /** 최대 검사 시간 */
    val durationMs: Long = 40_000L,
    val minIntervalMs: Long = 1_500L,
    val maxIntervalMs: Long = 3_000L,
    /** 유효 반응이 이만큼 모이면 바로 끝낸다. null이면 durationMs까지 계속 */
    val targetValidTrials: Int? = 10,
    /** 자극 후 이 시간 미만 반응 = 너무 빠른 반응(보고 반응한 게 아니라 예측) */
    val falseStartMs: Long = 100L,
    /** 이 시간 초과 = 느린 반응 */
    val slowMs: Long = 500L,
    /** 자극 후 이 시간 동안 반응이 없으면 놓침(느린 반응으로도 셈) */
    val timeoutMs: Long = 2_000L,
) {
    companion object {
        /**
         * 브레이크 페달용. 발을 옮기는 시간만큼 손가락 터치보다 느리므로 느린 반응 기준을 올린다.
         * 700ms는 "터치 500ms + 발 이동 약 200ms"로 잡은 **임시값**(실측·문헌 확인 전).
         */
        val BRAKE = ReactionParams(slowMs = 700L)

        fun of(input: ReactionInput): ReactionParams = when (input) {
            ReactionInput.TOUCH -> ReactionParams()
            ReactionInput.BRAKE -> BRAKE
        }
    }
}

enum class TrialOutcome { VALID, FALSE_START, TIMEOUT }

data class ReactionTrial(
    /** 자극이 보인 시각. 자극 전에 누른 경우 null */
    val stimulusAtMs: Long?,
    val reactionMs: Long?,
    val outcome: TrialOutcome,
)

enum class ReactionPhase { READY, WAITING, STIMULUS, FINISHED, ABORTED }

data class ReactionSummary(
    val trials: List<ReactionTrial>,
    val validCount: Int,
    val medianReactionMs: Long?,
    val meanReactionMs: Long?,
    /** 느린 반응(slowMs 초과) + 놓침 */
    val slowCount: Int,
    /** 자극 전 터치 + 너무 빠른 터치 */
    val falseStartCount: Int,
    val timeoutCount: Int,
    val aborted: Boolean,
)

/**
 * 반응 체크 1회. 시각은 ui가 넘겨준다(프레임 시각·터치 이벤트 시각, 같은 단조 시계 기준).
 * 스레드 안전하지 않다(ui 스레드에서만 호출).
 */
class ReactionSession(
    private val params: ReactionParams = ReactionParams(),
    private val random: Random = Random.Default,
) {
    var phase: ReactionPhase = ReactionPhase.READY
        private set

    private var startMs = 0L
    private var nextStimulusAtMs = 0L
    private var stimulusShownAtMs = 0L
    private val trials = ArrayList<ReactionTrial>()

    /** 브레이크 페달을 밟고 있는지. 밟은 채로는 자극을 내지 않는다(터치는 늘 false) */
    var isHeld: Boolean = false
        private set

    val isStimulusVisible: Boolean get() = phase == ReactionPhase.STIMULUS

    /** 지금까지 유효 반응 수. 진행 표시용 */
    val validCount: Int get() = trials.count { it.outcome == TrialOutcome.VALID }

    /** 남은 시간(ms). 진행 표시용 */
    fun remainingMs(nowMs: Long): Long =
        if (phase == ReactionPhase.READY) params.durationMs
        else (params.durationMs - (nowMs - startMs)).coerceAtLeast(0)

    /** @param held 시작 순간 페달을 밟고 있으면 true → 뗄 때까지 자극을 내지 않는다 */
    fun start(nowMs: Long, held: Boolean = false) {
        check(phase == ReactionPhase.READY) { "already started" }
        startMs = nowMs
        trials.clear()
        isHeld = held
        schedule(nowMs)
        phase = ReactionPhase.WAITING
    }

    /** 매 프레임 호출. 자극 표시 시각이 되면 STIMULUS로, 시간이 다 되면 FINISHED로 바뀐다. */
    fun update(nowMs: Long): ReactionPhase {
        when (phase) {
            ReactionPhase.WAITING -> {
                val ended = nowMs - startMs >= params.durationMs
                if (ended) {
                    phase = ReactionPhase.FINISHED
                } else if (!isHeld && nowMs >= nextStimulusAtMs) {
                    stimulusShownAtMs = nowMs
                    phase = ReactionPhase.STIMULUS
                }
            }
            ReactionPhase.STIMULUS -> if (nowMs - stimulusShownAtMs > params.timeoutMs) {
                trials += ReactionTrial(stimulusShownAtMs, null, TrialOutcome.TIMEOUT)
                afterTrial(nowMs)
            }
            else -> Unit
        }
        return phase
    }

    /**
     * 브레이크를 밟음(false→true). 반응으로 처리한다.
     * 이미 밟은 상태에서 다시 오면(같은 값 재전송) 무시한다.
     */
    fun onPress(nowMs: Long): TrialOutcome? {
        if (isHeld) return null
        isHeld = true
        return onTap(nowMs)
    }

    /** 브레이크를 뗌(true→false). 대기 시간을 이 시각부터 다시 잡는다(뗀 직후 바로 자극이 나오지 않게). */
    fun onRelease(nowMs: Long) {
        if (!isHeld) return
        isHeld = false
        if (phase == ReactionPhase.WAITING) schedule(nowMs)
    }

    /** 터치(또는 브레이크 밟음). 기록된 시도 결과를 돌려준다(진행 중이 아니면 null). */
    fun onTap(nowMs: Long): TrialOutcome? {
        return when (phase) {
            ReactionPhase.WAITING -> {
                // 자극 전에 반응 → 너무 빠른 반응. 대기 시간을 다시 잡아 연타로 맞히는 것을 막는다
                trials += ReactionTrial(null, null, TrialOutcome.FALSE_START)
                schedule(nowMs)
                TrialOutcome.FALSE_START
            }
            ReactionPhase.STIMULUS -> {
                val rt = nowMs - stimulusShownAtMs
                val outcome = if (rt < params.falseStartMs) TrialOutcome.FALSE_START else TrialOutcome.VALID
                trials += ReactionTrial(stimulusShownAtMs, rt, outcome)
                afterTrial(nowMs)
                outcome
            }
            else -> null
        }
    }

    /** 기어가 D로 바뀌는 등 중단 조건이 생기면 호출 */
    fun abort() {
        if (phase == ReactionPhase.WAITING || phase == ReactionPhase.STIMULUS || phase == ReactionPhase.READY) {
            phase = ReactionPhase.ABORTED
        }
    }

    fun summary(): ReactionSummary {
        val valid = trials.filter { it.outcome == TrialOutcome.VALID }.mapNotNull { it.reactionMs }
        val timeouts = trials.count { it.outcome == TrialOutcome.TIMEOUT }
        return ReactionSummary(
            trials = trials.toList(),
            validCount = valid.size,
            medianReactionMs = if (valid.isEmpty()) null else RobustStats.median(valid.map { it.toFloat() }).toLong(),
            meanReactionMs = if (valid.isEmpty()) null else valid.average().toLong(),
            slowCount = valid.count { it > params.slowMs } + timeouts,
            falseStartCount = trials.count { it.outcome == TrialOutcome.FALSE_START },
            timeoutCount = timeouts,
            aborted = phase == ReactionPhase.ABORTED,
        )
    }

    private fun afterTrial(nowMs: Long) {
        val enough = params.targetValidTrials?.let { validCount >= it } ?: false
        val timeUp = nowMs - startMs >= params.durationMs
        phase = if (enough || timeUp) ReactionPhase.FINISHED else ReactionPhase.WAITING
        if (phase == ReactionPhase.WAITING) schedule(nowMs)
    }

    private fun schedule(fromMs: Long) {
        nextStimulusAtMs = fromMs + random.nextLong(params.minIntervalMs, params.maxIntervalMs + 1)
    }
}

/** 반응 체크를 시작·유지해도 되는 차량 상태인지 (기어 P이고 정지) */
object ReactionGate {
    /** 이 속도 이하면 정지로 본다 */
    const val MAX_STOPPED_SPEED_MPS = 0.3f

    /** 시작 조건: 기어 P이고 정지. 기어·속도를 모르면 시작하지 않는다 */
    fun canStart(gear: Gear?, speedMps: Float?): Boolean =
        gear == Gear.PARK && speedMps != null && speedMps <= MAX_STOPPED_SPEED_MPS

    /** 중단 조건: P가 아니게 되거나 움직이기 시작함 */
    fun mustAbort(gear: Gear?, speedMps: Float?): Boolean =
        (gear != null && gear != Gear.PARK) || (speedMps != null && speedMps > MAX_STOPPED_SPEED_MPS)
}

data class ReactionResult(
    val verdict: ReactionVerdict,
    /** 운전자에게 보여줄 문구("진단"이 아니라 평소 대비 변화) */
    val message: String,
    val summary: ReactionSummary,
    /** 본인 기준선 대비 z (기준선 대비 몇 배 흩어짐만큼 느린지). 판단 보류면 null */
    val zScore: Float?,
    val isCalibrating: Boolean,
)

data class ReactionBaselineParams(
    val maxRecords: Int = 20,
    val minRecordsForPersonal: Int = 3,
    /** 기록이 쌓이기 전 기본값(반응시간 중앙값 ms) */
    val defaultMedian: RobustStat = RobustStat(median = 350f, scale = 50f),
    val minScaleMs: Float = 30f,
    val minValidTrials: Int = 8,
    val maxFalseStarts: Int = 4,
    val slowerZ: Float = 1.5f,
    val muchSlowerZ: Float = 3f,
    val slowerSlowCount: Int = 2,
    /** 유효 반응 10회 기준(이전 60초·약 15회일 때는 4) */
    val muchSlowerSlowCount: Int = 3,
) {
    companion object {
        /**
         * 브레이크 페달용 기본 기준선. 터치 350ms에 발 이동 약 100ms를 더한 **임시값**(실측·문헌 확인 전).
         * 개인 기록이 minRecordsForPersonal회 모이면 본인 기준으로 바뀌므로 첫 몇 회에만 쓰인다.
         */
        val BRAKE = ReactionBaselineParams(defaultMedian = RobustStat(median = 450f, scale = 60f))

        fun of(input: ReactionInput): ReactionBaselineParams = when (input) {
            ReactionInput.TOUCH -> ReactionBaselineParams()
            ReactionInput.BRAKE -> BRAKE
        }
    }
}

/** 반응 체크 기준선 저장소(반응시간 중앙값 목록). 앱에서는 Room reaction_scores 등으로 구현 */
interface ReactionRecordStore {
    fun load(): List<Float>
    fun save(medians: List<Float>)
}

class InMemoryReactionRecordStore(initial: List<Float> = emptyList()) : ReactionRecordStore {
    private var data = initial
    override fun load(): List<Float> = data
    override fun save(medians: List<Float>) { data = medians }
}

class ReactionEvaluator(
    private val store: ReactionRecordStore,
    private val params: ReactionBaselineParams = ReactionBaselineParams(),
) {
    private var records: List<Float> = store.load().takeLast(params.maxRecords)

    val recordCount: Int get() = records.size

    fun baseline(): Pair<RobustStat, Boolean> =
        if (records.size < params.minRecordsForPersonal) params.defaultMedian to true
        else RobustStats.of(records, params.minScaleMs) to false

    fun evaluate(s: ReactionSummary): ReactionResult {
        val (base, calibrating) = baseline()
        val median = s.medianReactionMs
        val inconclusiveMsg = when {
            s.aborted -> "반응 체크가 중간에 멈춰서 이번 기록은 평소와 비교하지 않았어요"
            s.falseStartCount > params.maxFalseStarts -> "신호가 나오기 전에 반응한 경우가 많아 이번에는 평소와 비교하지 않았어요"
            s.validCount < params.minValidTrials || median == null -> "반응 기록이 충분하지 않아 이번에는 평소와 비교하지 않았어요"
            else -> null
        }
        if (inconclusiveMsg != null) {
            return ReactionResult(ReactionVerdict.INCONCLUSIVE, inconclusiveMsg, s, null, calibrating)
        }
        val z = base.z(median!!.toFloat())
        val verdict = when {
            z >= params.muchSlowerZ || s.slowCount >= params.muchSlowerSlowCount -> ReactionVerdict.MUCH_SLOWER
            z >= params.slowerZ || s.slowCount >= params.slowerSlowCount -> ReactionVerdict.SLOWER
            else -> ReactionVerdict.NORMAL
        }
        val message = when (verdict) {
            ReactionVerdict.NORMAL -> "평소와 비슷한 반응 속도예요"
            ReactionVerdict.SLOWER -> "평소보다 반응이 조금 느려요. 출발 전에 잠시 쉬어 가도 좋아요"
            else -> "평소보다 반응이 많이 느려요. 충분히 쉬고 출발하는 걸 권해요"
        }
        return ReactionResult(verdict, message, s, z, calibrating)
    }

    /** 기준선 갱신. 판단 보류(INCONCLUSIVE)와 많이 느림(MUCH_SLOWER)은 "평소"가 아니므로 넣지 않는다. */
    fun record(r: ReactionResult): Boolean {
        val median = r.summary.medianReactionMs ?: return false
        if (r.verdict == ReactionVerdict.INCONCLUSIVE || r.verdict == ReactionVerdict.MUCH_SLOWER) return false
        records = (records + median.toFloat()).takeLast(params.maxRecords)
        store.save(records)
        return true
    }
}
