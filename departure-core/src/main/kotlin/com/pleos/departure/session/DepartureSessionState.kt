package com.pleos.departure.session

import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DrivingRestriction

/*
 * '출발 전' 세션 상태(작업 #12b). 화면 기술(Compose·View·음성 등)과 무관한 값만 담는다.
 * 한국어 문구·색·아이콘은 여기 두지 않는다 — 표시 계층(:departure-ui 의 DepartureDisplayMapper 등)이 이 값을 보고 만든다.
 */

/**
 * 경로 교체 재확인 진행 상황([DepartureSession]이 DepartureConfig.routeChangeRecheckInterval 마다 다시 확인하는 중인가).
 * @property rechecking 경로 교체 중일 수 있어 다시 확인하는 중. 이때 표시 계층은 "목적지 없음"·"안내 중 아님"을 확정 문구로 보여 주지 않는다.
 * @property attempt 몇 번째 재확인인가(1부터, 재확인 중이 아니면 마지막 값 또는 0).
 * @property maxAttempts DepartureConfig.routeChangeRecheckMaxAttempts.
 */
data class RecheckProgress(
    val rechecking: Boolean = false,
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
)

/** 마지막 확인 결과. */
sealed interface CheckOutcome {

    /**
     * 확인했다.
     * @property snapshot core 계산 결과 묶음.
     * @property recheck 이 결과가 경로 교체 재확인 중에 나온 것인가.
     * @property tollToggleNotYetApplied 사용자가 "켰어요"([DepartureSession.confirmTollAvoidanceTurnedOn])를 눌렀는데
     *   다시 확인해도 아직 유료도로 우회가 적용되지 않았다.
     */
    data class Checked(
        val snapshot: DepartureSnapshot,
        val recheck: RecheckProgress,
        val tollToggleNotYetApplied: Boolean,
    ) : CheckOutcome

    /**
     * 확인 자체가 예외로 끝났다(1번 어댑터가 `DataResult` 대신 예외를 던진 경우 등, 작업 #9).
     * 이전 결과는 오래됐을 수 있어 함께 주지 않는다. 표시 계층은 "확인 불가"를 안전해 보이지 않게 보여 준다.
     * 세션은 주기 확인을 계속하므로 복구되면 [Checked]로 바뀐다.
     */
    data object Failed : CheckOutcome
}

/**
 * 세션 전체 상태. [DepartureSession.state]로 관찰한다.
 *
 * @property outcome 마지막 확인 결과. 첫 결과 전과 [DepartureSession.resetSession] 직후에는 null.
 * @property loading 확인 중(결과를 기다리는 중).
 * @property showLoadingIndicator 확인이 DepartureConfig.loadingIndicatorThreshold 를 넘겨 "확인 중" 표시를 띄워야 한다.
 * @property drivingRestriction 마지막으로 반영한 주행 규제 상태. 아직 받지 못했으면 UNKNOWN.
 * @property controlsEnabled 사용자 조작(다시 확인, "켰어요", 조작 안내)을 보여도 되는가. 주행 중이면 false.
 *   UNKNOWN 은 DepartureConfig.treatUnknownDrivingAsRestricted 를 따른다.
 *   첫 반영 전([Initial])에는 true 다 — 기존 화면 동작 유지(결과가 없어 조작 안내도 없음).
 */
data class DepartureSessionState(
    val outcome: CheckOutcome?,
    val loading: Boolean,
    val showLoadingIndicator: Boolean,
    val drivingRestriction: DrivingRestriction,
    val controlsEnabled: Boolean,
) {
    companion object {
        val Initial = DepartureSessionState(
            outcome = null,
            loading = false,
            showLoadingIndicator = false,
            drivingRestriction = DrivingRestriction.UNKNOWN,
            controlsEnabled = true,
        )
    }
}
