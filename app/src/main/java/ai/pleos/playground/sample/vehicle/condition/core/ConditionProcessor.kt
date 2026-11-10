package ai.pleos.playground.sample.vehicle.condition.core

data class ConditionParams(
    val window: WindowParams = WindowParams(),
    val fatigue: FatigueParams = FatigueParams(),
    val baseline: BaselineParams = BaselineParams(),
    val restResetMs: Long = 10 * 60_000L,
    val movingSpeedMps: Float = 1f,
    /** 주행 요약을 기준선에 넣으려면 유효 창이 최소 이만큼 있어야 함 */
    val minValidWindowsForSummary: Int = 3,
)

/** 주행 종료 결과 */
data class TripEndResult(
    val summary: TripSummary?,
    val baselineUpdated: Boolean,
    val maxLevel: FatigueLevel,
)

/**
 * core 전체를 묶는 처리기(순수 Kotlin, 스레드 안전하지 않음 — 한 코루틴에서만 호출).
 *
 * 데이터 흐름: on*(신호) → FrameBuilder(10Hz 프레임) → WindowAnalyzer(60초 창/10초마다)
 *            → FatigueScorer(기준선 대비 점수·단계) → [state]
 *
 * engine은 신호 콜백을 on*으로 넘기고, 100ms마다 [tick]을 불러 나온 state를 StateFlow로 내보내면 된다.
 */
class ConditionProcessor(
    baselineStore: BaselineStore,
    val params: ConditionParams = ConditionParams(),
) {
    private var frames = FrameBuilder(params.window.periodMs)
    private val analyzer = WindowAnalyzer(params.window)
    private val scorer = FatigueScorer(params.fatigue)
    private val baseline = BaselineTracker(baselineStore, params.baseline)
    private val timer = DrivingTimer(params.window.periodMs, params.restResetMs, params.movingSpeedMps)

    private val tripFeatures = ArrayList<SteeringFeatures>()
    private var tripMaxLevel = FatigueLevel.NORMAL
    private var preDrive: ReactionVerdict? = null

    var state: ConditionState = ConditionState.INITIAL.copy(isCalibrating = baseline.current().isCalibrating)
        private set

    /** 마지막 창 결과와 점수 구성(시연 화면·디버그용) */
    var lastWindow: WindowResult? = null
        private set
    var lastBreakdown: ScoreBreakdown? = null
        private set

    fun onSteering(timeMs: Long, deg: Float) = frames.onSteering(timeMs, deg)
    fun onSpeed(timeMs: Long, mps: Float) = frames.onSpeed(timeMs, mps)
    fun onGear(timeMs: Long, gear: Gear) = frames.onGear(timeMs, gear)
    fun onTurnSignal(timeMs: Long, signal: TurnSignal) = frames.onTurnSignal(timeMs, signal)

    fun setPreDrive(verdict: ReactionVerdict?) {
        preDrive = verdict
        state = state.copy(preDrive = verdict)
    }

    /**
     * nowMs까지 들어온 신호를 처리하고 최신 상태를 돌려준다.
     * @param hourOfDay 현재 시(0~23). 시간대 성분 계산용(시계를 밖에서 넣어 테스트 가능하게 함)
     */
    fun tick(nowMs: Long, hourOfDay: Int): ConditionState {
        for (f in frames.advanceTo(nowMs)) {
            timer.add(f)
            val w = analyzer.add(f) ?: continue
            onWindow(w, hourOfDay)
        }
        state = state.copy(drivingMinutes = timer.minutes, timeMs = nowMs)
        return state
    }

    /** 새 주행 시작. 기준선은 유지하고 나머지는 초기화한다. */
    fun startTrip() {
        frames = FrameBuilder(params.window.periodMs)
        analyzer.reset()
        scorer.reset()
        timer.reset()
        tripFeatures.clear()
        tripMaxLevel = FatigueLevel.NORMAL
        preDrive = null
        lastWindow = null
        lastBreakdown = null
        state = ConditionState.INITIAL.copy(isCalibrating = baseline.current().isCalibrating)
    }

    /** 주행 종료. 요약을 만들고, 피로 경고(CAUTION 이상)가 없었으면 기준선에 반영한다. */
    fun endTrip(nowMs: Long): TripEndResult {
        val maxLevel = tripMaxLevel
        if (tripFeatures.size < params.minValidWindowsForSummary) return TripEndResult(null, false, maxLevel)
        val summary = TripSummary(
            endTimeMs = nowMs,
            reversalsSmallPerMin = RobustStats.median(tripFeatures.map { it.reversalsSmallPerMin }),
            reversalsLargePerMin = RobustStats.median(tripFeatures.map { it.reversalsLargePerMin }),
            stdDeg = RobustStats.median(tripFeatures.map { it.stdDeg }),
        )
        val updated = baseline.record(summary, hadFatigueWarning = maxLevel >= FatigueLevel.CAUTION)
        return TripEndResult(summary, updated, maxLevel)
    }

    private fun onWindow(w: WindowResult, hourOfDay: Int) {
        lastWindow = w
        if (!w.isValid) {
            // 무효 창: 점수·단계는 직전 값 유지
            state = state.copy(lastWindowValid = false, timeMs = w.endTimeMs)
            return
        }
        val f = w.features!!
        tripFeatures += f
        val b = baseline.current()
        val ctx = ScoreContext(timer.minutes, hourOfDay, preDrive)
        val bd = scorer.update(f, b, ctx)
        lastBreakdown = bd
        if (scorer.level > tripMaxLevel) tripMaxLevel = scorer.level
        state = ConditionState(
            level = scorer.level,
            score = scorer.score,
            isCalibrating = b.isCalibrating,
            lastWindowValid = true,
            drivingMinutes = timer.minutes,
            preDrive = preDrive,
            reasons = scorer.reasons(bd, f, ctx),
            timeMs = w.endTimeMs,
        )
    }
}
