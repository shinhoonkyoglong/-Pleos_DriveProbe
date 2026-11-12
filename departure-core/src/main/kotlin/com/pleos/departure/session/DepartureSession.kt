package com.pleos.departure.session

import com.pleos.departure.DepartureFeature
import com.pleos.departure.model.ConditionSource
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteUserAction
import com.pleos.departure.model.TripEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * '출발 전' 확인 세션(작업 #12b, 작업 #7의 화면 상태 홀더에서 표시와 무관한 동작만 떼어 core 로 옮김).
 * 백엔드로 치면 "요청 흐름을 주기적으로 돌리고 결과·세션 기억을 관리하는 서비스"다. 화면 기술(Compose 등)과 문구를 모른다.
 * 표시 계층은 [state]를 관찰해 자기 방식(문구·색·아이콘·음성)으로 바꾸고, 아래 명령만 부른다.
 * 만들기: `feature.createSession(scope)` 또는 생성자. DI 애노테이션 없이 생성자 주입.
 *
 * ## 명령
 * - [onVisible] 화면이 보이기 시작함(진입·백그라운드 복귀) / [onHidden] 가려짐
 * - [refresh] 사용자 "다시 확인"(수동 재시도)
 * - [confirmTollAvoidanceTurnedOn] 사용자 "켰어요"(내비에서 유료도로 우회를 켰다고 알림)
 * - [resetSession] 세션 기억을 지우고 다시 확인(차량·운전자 상황이 통째로 바뀜, 예: 시연 시나리오 전환)
 *
 * ## 언제 확인하나
 * - 화면이 보일 때([onVisible]: 진입·백그라운드 복귀) — 내비 목적지를 그때마다 새로 조회한다(리뷰 task-05 #7-5)
 * - 보이는 동안 DepartureConfig.screenRefreshInterval 마다(남은 시간·단계 갱신, 리뷰 task-04b #7-2)
 * - 경로 교체 중일 수 있으면 DepartureConfig.routeChangeRecheckInterval 마다 최대 routeChangeRecheckMaxAttempts 회([RecheckPolicy])
 * - 사용자가 "다시 확인"([refresh]) 또는 "켰어요"([confirmTollAvoidanceTurnedOn])를 눌렀을 때
 * 새 확인이 시작되면 진행 중인 확인은 취소한다(최신 요청만 반영).
 *
 * ## 세션 기억
 * - 직전 결과의 `AppliedEta.sdkEstimate` → 다음 확인의 previousEstimate(신선하면 requestTrip 생략)
 * - 마지막으로 조회에 성공한 운전자 조건 → 조회 실패 때 사용(D-9)
 * - 마지막으로 본 내비 목적지 → 경로 교체 중 판정
 *
 * ## 확인이 예외로 끝나면(작업 #9, 리뷰 task-07 MINOR-2)
 * 포트 계약은 실패를 `DataResult.Unavailable`로 돌려주는 것이지만, 1번 어댑터가 예외를 던질 수 있다.
 * 예외를 잡아 [CheckOutcome.Failed]로 알리고 주기 확인은 계속한다(앱 종료 방지).
 * 취소(CancellationException)는 삼키지 않는다 — 단, 우리 코루틴이 살아 있는데 어댑터 내부 타임아웃
 * (`TimeoutCancellationException`)이 새어 나온 경우는 실패로 본다.
 *
 * 상태만 알린다(TTS·알림 없음 — 사용자 결정 11/09).
 *
 * @param scope 세션 수명 범위(Compose 에서는 rememberCoroutineScope). 이 범위가 끝나면 모든 작업이 멈춘다.
 */
class DepartureSession(
    private val feature: DepartureFeature,
    private val scope: CoroutineScope,
) {
    private val config = feature.config

    private val _state = MutableStateFlow(DepartureSessionState.Initial)
    val state: StateFlow<DepartureSessionState> = _state.asStateFlow()

    private var previousEstimate: TripEstimate? = null
    private var lastKnownCondition: DriverCondition? = null
    private var recheckState = RecheckPolicy.RecheckState()
    private var awaitingToggleConfirmation = false

    private var lastSnapshot: DepartureSnapshot? = null
    /** 마지막 확인이 예외로 끝났다. true 면 [lastSnapshot] 대신 [CheckOutcome.Failed]를 알린다. */
    private var lastCheckFailed = false
    /**
     * 이번 "안내 없음" 확정으로 이미 ETA 를 버렸다([dropEstimateIfNotGuidingConfirmed]).
     * NotGuiding 이 아닌 결과(안내 재개·조건 미설정 등)나 확인 실패가 끼면 false 로 되돌린다(리뷰 task-09 MINOR-1).
     */
    private var etaDroppedForNotGuiding = false
    private var lastRecheck = RecheckProgress()
    private var lastTollToggleNotYetApplied = false
    private var drivingRestriction = DrivingRestriction.UNKNOWN

    private var loopJob: Job? = null
    private var drivingJob: Job? = null
    private var visible = false

    /** 화면이 보이기 시작했다(진입·백그라운드 복귀). 주행 상태 구독 + 즉시 확인 + 주기 확인 시작. */
    fun onVisible() {
        visible = true
        if (drivingJob?.isActive != true) {
            drivingJob = scope.launch {
                feature.observeDrivingRestriction().collect { restriction ->
                    drivingRestriction = restriction
                    republish()
                }
            }
        }
        recheckState = recheckState.resetAttempts()
        restartLoop()
    }

    /** 화면이 가려졌다. 주기 확인·구독을 멈춘다(배터리·성능). 세션 기억은 유지한다. */
    fun onHidden() {
        visible = false
        loopJob?.cancel()
        drivingJob?.cancel()
        _state.update { it.copy(loading = false, showLoadingIndicator = false) }
    }

    /** 사용자 "다시 확인"(수동 재시도). 경로 교체 재확인 횟수도 새로 센다. 가려진 상태면 무시한다. */
    fun refresh() {
        if (!visible) return
        recheckState = recheckState.resetAttempts()
        restartLoop()
    }

    /** 사용자 "켰어요": 내비에서 유료도로 우회를 켰다고 알림 → 경로 상태를 새로 조회해 확인한다(리뷰 task-05 #7-11). */
    fun confirmTollAvoidanceTurnedOn() {
        awaitingToggleConfirmation = true
        // 유료도로 우회를 켜면 내비 ETA 기준도 바뀐다(§10.6) → 이전 ETA(다른 옵션 기준)를 재사용하지 않는다.
        previousEstimate = null
        refresh()
    }

    /**
     * 세션 기억(이전 ETA·마지막 조건·직전 목적지)을 지우고 다시 확인한다.
     * 차량·운전자 상황이 통째로 바뀌었을 때(예: 시연에서 Fake 시나리오 전환) 조립 쪽이 부른다.
     */
    fun resetSession() {
        previousEstimate = null
        lastKnownCondition = null
        recheckState = RecheckPolicy.RecheckState()
        awaitingToggleConfirmation = false
        lastSnapshot = null
        lastCheckFailed = false
        etaDroppedForNotGuiding = false
        _state.update { it.copy(outcome = null) }
        refresh()
    }

    private fun restartLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                val recheckSoon = runCheck()
                delay(if (recheckSoon) config.routeChangeRecheckInterval.toMillis() else config.screenRefreshInterval.toMillis())
            }
        }
    }

    /** 확인 1회. @return 경로 교체 재확인이 필요하면 true. */
    private suspend fun runCheck(): Boolean = coroutineScope {
        _state.update { it.copy(loading = true) }
        val indicator = launch {
            delay(config.loadingIndicatorThreshold.toMillis())
            _state.update { it.copy(showLoadingIndicator = true) }
        }
        try {
            val snapshot = try {
                feature.departureCheck.check(previousEstimate, lastKnownCondition)
            } catch (e: CancellationException) {
                // 우리가 취소된 것이면 그대로 전파한다(새 확인·가려짐). 살아 있으면 어댑터 안에서 새어 나온 타임아웃 → 실패로 본다.
                currentCoroutineContext().ensureActive()
                null
            } catch (e: Exception) {
                null
            }
            if (snapshot == null) {
                // 세션 기억(ETA·조건·재확인 횟수)은 그대로 둔다. 다음 주기(screenRefreshInterval)에 다시 확인한다.
                lastCheckFailed = true
                // 실패 사이에 안내가 바뀌었을 수 있다 → 다음 "안내 없음" 확정에서 다시 판단하게 한다(리뷰 task-09 MINOR-1).
                etaDroppedForNotGuiding = false
                return@coroutineScope false
            }
            lastCheckFailed = false
            val decision = RecheckPolicy.decide(snapshot, recheckState, config.routeChangeRecheckMaxAttempts)
            val etaDropped = remember(snapshot, decision)
            lastSnapshot = snapshot
            lastRecheck = RecheckProgress(
                rechecking = decision.recheck,
                attempt = decision.next.attempts,
                maxAttempts = config.routeChangeRecheckMaxAttempts,
            )
            lastTollToggleNotYetApplied = consumeToggleConfirmation(snapshot)
            // 확정된 안내 없음으로 ETA 를 버렸으면 지금 결과는 버린 ETA 로 계산한 값이다 → 짧은 간격 뒤 새 ETA 로 다시 계산.
            decision.recheck || etaDropped
        } finally {
            indicator.cancel()
            _state.update { it.copy(loading = false, showLoadingIndicator = false) }
            republish()
        }
    }

    /** 결과를 세션 기억에 반영한다. @return 안내 없음 확정으로 보관 ETA 를 버렸으면 true. */
    private fun remember(snapshot: DepartureSnapshot, decision: RecheckPolicy.Decision): Boolean {
        recheckState = decision.next
        when (snapshot) {
            is DepartureSnapshot.ConditionNotSet -> lastKnownCondition = null // 확인된 미설정: 이전 조건을 쓰면 안 된다
            is DepartureSnapshot.Checked -> {
                when (snapshot.condition.source) {
                    ConditionSource.LOADED -> lastKnownCondition = snapshot.condition.condition
                    ConditionSource.LAST_KNOWN, ConditionSource.ASSUMED_NIGHT_RESTRICTION -> Unit
                }
                when (val d = snapshot.deadline) {
                    is DepartureDeadlineResult.Deadline -> d.eta.sdkEstimate?.let { previousEstimate = it }
                    is DepartureDeadlineResult.NoNightRestriction,
                    is DepartureDeadlineResult.BeforeSunrise,
                    is DepartureDeadlineResult.NoSunsetToday,
                    is DepartureDeadlineResult.AllDayNight,
                    is DepartureDeadlineResult.DestinationNotSet,
                    is DepartureDeadlineResult.DestinationUnavailable,
                    -> Unit // 이전 ETA 는 유지(유스케이스가 목적지 좌표가 다르면 알아서 재사용하지 않음)
                }
                dropEstimateIfRouteOptionChanged(snapshot.route)
            }
        }
        // 위에서 이번 결과의 sdkEstimate 를 보관한 뒤에 판단해야 한다(이번 확인도 같은 ETA 를 재사용했을 수 있음).
        return dropEstimateIfNotGuidingConfirmed(snapshot, decision)
    }

    /**
     * 지금 내비가 보고한 적용 옵션이 보관 중인 ETA 의 기준 옵션과 다르면 그 ETA 를 버린다(다음 확인에서 requestTrip 재조회).
     * 사용자가 "켰어요"를 누르지 않고 내비에서 옵션을 바꿔도 다음 주기에 반영되게 한다. 버리기만 하므로 보수성은 그대로다.
     */
    private fun dropEstimateIfRouteOptionChanged(route: RouteConditionCheck) {
        val applied = when (route) {
            is RouteConditionCheck.Satisfied -> route.routeState.appliedOption
            is RouteConditionCheck.OtherRouteOption -> route.routeState.appliedOption
            is RouteConditionCheck.TollAvoidanceUnverified -> route.routeState.appliedOption
            is RouteConditionCheck.NeedsUserAction -> route.routeState.appliedOption
            RouteConditionCheck.NoRouteCondition,
            RouteConditionCheck.DestinationNotSet,
            is RouteConditionCheck.DestinationUnavailable,
            is RouteConditionCheck.NotGuiding,
            is RouteConditionCheck.Unconfirmed,
            is RouteConditionCheck.ReferenceOnly,
            -> return
        }
        if (previousEstimate?.let { it.routeOption != applied } == true) previousEstimate = null
    }

    /**
     * 안내 없음(NotGuiding)이 재확인 끝에 확정되면, 안내 중에 받은 ETA(routeOption 이 있는 값)를 버린다(리뷰 task-07 MINOR-3).
     * 안내가 끝난 뒤에는 적용 옵션을 읽을 수 없어 [dropEstimateIfRouteOptionChanged]가 비교하지 못한다.
     * 예: FREE 안내 중 받은 ETA → 안내 종료 + 내비 설정 RECOMMENDED 복귀 → 그대로 두면 최대 etaFreshnessLimit(15분) 동안
     * 보정 없는 FREE 기준 ETA 를 재사용한다(마감이 늦게 나오는 비보수 방향). 버리면 다음 확인에서 requestTrip 을 다시 부르고,
     * 안내 중이 아닐 때 받은 ETA 는 routeOption=null 이라 core 가 보수적으로 보정한다.
     * 재확인 중(경로 교체 7~19초)에는 버리지 않는다 — 곧 같은 안내로 돌아올 수 있다.
     */
    private fun dropEstimateIfNotGuidingConfirmed(snapshot: DepartureSnapshot, decision: RecheckPolicy.Decision): Boolean {
        val route = (snapshot as? DepartureSnapshot.Checked)?.route
        if (route !is RouteConditionCheck.NotGuiding) {
            // 안내가 다시 시작됐거나 다른 결과(조건 미설정 = 경로 결과 없음 포함) → 다음 안내 종료 때 다시 판단
            etaDroppedForNotGuiding = false
            return false
        }
        if (decision.recheck || etaDroppedForNotGuiding) return false
        if (previousEstimate?.routeOption == null) return false
        // 한 번의 "안내 없음" 확정에 한 번만 버린다. 어댑터가 안내 중이 아닐 때도 routeOption 을 채워 보내도 짧은 간격 재조회가 반복되지 않게.
        etaDroppedForNotGuiding = true
        previousEstimate = null
        return true
    }

    /**
     * "켰어요" 뒤 첫 확정 결과에서 아직 미적용이면 true. 경로 교체 중(NotGuiding)에는 판단을 미룬다.
     */
    private fun consumeToggleConfirmation(snapshot: DepartureSnapshot): Boolean {
        if (!awaitingToggleConfirmation) return false
        val route = (snapshot as? DepartureSnapshot.Checked)?.route
        if (route is RouteConditionCheck.NotGuiding) return false
        awaitingToggleConfirmation = false
        return route is RouteConditionCheck.NeedsUserAction && RouteUserAction.TURN_ON_TOLL_AVOIDANCE in route.actions
    }

    /** 마지막 결과 + 지금 주행 상태로 상태를 다시 알린다(주행 상태가 바뀌면 결과 조회 없이 다시 알림). */
    private fun republish() {
        val outcome = if (lastCheckFailed) {
            CheckOutcome.Failed
        } else {
            lastSnapshot?.let { CheckOutcome.Checked(it, lastRecheck, lastTollToggleNotYetApplied) }
        }
        _state.update {
            it.copy(
                outcome = outcome,
                drivingRestriction = drivingRestriction,
                controlsEnabled = controlsEnabled(drivingRestriction),
            )
        }
    }

    private fun controlsEnabled(restriction: DrivingRestriction): Boolean = when (restriction) {
        DrivingRestriction.UNRESTRICTED -> true
        DrivingRestriction.RESTRICTED -> false
        DrivingRestriction.UNKNOWN -> !config.treatUnknownDrivingAsRestricted
    }
}
