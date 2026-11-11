package com.pleos.departure.session

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RouteConditionCheck

/**
 * "경로 교체 중일 수 있는" 결과를 다시 확인할지 정하는 순수 규칙(리뷰 task-05 #7-10, 재검토 MINOR-R4).
 *
 * 사람이 내비에서 목적지·경로 설정을 바꾸면 7~19초 동안 안내가 끊기고(NotGuiding) 목적지가 0 값(NotSet)일 수 있다
 * (api-notes/navi-runtime-check.md §10.5). 그래서
 * - 경로 확인이 [RouteConditionCheck.NotGuiding] 이거나
 * - 직전에 목적지가 있었는데 이번에 [NaviDestination.NotSet] 이면
 * 바로 확정하지 않고 [maxAttempts]번까지 다시 확인한다(간격은 [DepartureSession]이 DepartureConfig.routeChangeRecheckInterval 로 기다림).
 * 다 써도 같으면 그 결과를 확정하고([RecheckState.exhausted]), 결과가 바뀌거나 사용자가 다시 확인을 누를 때까지 재확인을 반복하지 않는다.
 */
internal object RecheckPolicy {

    /**
     * @property lastPresentDestination 이 세션에서 마지막으로 본 목적지. 목적지가 확정적으로 없어지면 null.
     * @property attempts 지금까지 한 재확인 횟수.
     * @property exhausted 재확인을 다 써서 결과를 확정했다.
     */
    data class RecheckState(
        val lastPresentDestination: Place? = null,
        val attempts: Int = 0,
        val exhausted: Boolean = false,
    ) {
        /** 사용자가 직접 다시 확인하거나 화면에 돌아왔을 때: 재확인 횟수를 새로 센다(직전 목적지는 기억). */
        fun resetAttempts(): RecheckState = copy(attempts = 0, exhausted = false)
    }

    /** @property recheck true 면 짧은 간격 뒤 다시 확인하고, 세션은 [RecheckProgress.rechecking]으로 알린다(표시 계층이 "다시 확인하고 있어요" 등으로 보여 줌). */
    data class Decision(val next: RecheckState, val recheck: Boolean)

    fun decide(snapshot: DepartureSnapshot, state: RecheckState, maxAttempts: Int): Decision {
        val checked = when (snapshot) {
            is DepartureSnapshot.ConditionNotSet -> return Decision(RecheckState(state.lastPresentDestination), recheck = false)
            is DepartureSnapshot.Checked -> snapshot
        }
        val destination = when (val d = checked.destination) {
            is DataResult.Unavailable -> null
            is DataResult.Available -> d.value
        }
        val presentPlace = (destination as? NaviDestination.Present)?.place
        val destinationVanished = destination == NaviDestination.NotSet && state.lastPresentDestination != null
        val notGuiding = checked.route is RouteConditionCheck.NotGuiding
        val transient = destinationVanished || notGuiding

        if (!transient) {
            val lastPresent = when (destination) {
                is NaviDestination.Present -> destination.place
                NaviDestination.NotSet -> null
                null -> state.lastPresentDestination // 확인 불가: 직전 값을 그대로 기억
            }
            return Decision(RecheckState(lastPresentDestination = lastPresent), recheck = false)
        }

        val lastPresent = presentPlace ?: state.lastPresentDestination
        if (state.exhausted || state.attempts >= maxAttempts) {
            // 확정: 목적지가 정말 없어졌으면 잊는다(다음 NotSet 은 바로 "목적지 없음").
            val keep = if (destinationVanished) null else lastPresent
            return Decision(RecheckState(lastPresentDestination = keep, attempts = state.attempts, exhausted = true), recheck = false)
        }
        return Decision(RecheckState(lastPresentDestination = lastPresent, attempts = state.attempts + 1), recheck = true)
    }
}
