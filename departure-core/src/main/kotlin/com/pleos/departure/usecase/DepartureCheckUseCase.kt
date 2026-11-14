package com.pleos.departure.usecase

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.ConditionSource
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.ResolvedCondition
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import java.time.Clock

/**
 * 출발 전 화면 한 번 갱신분을 모아 계산한다(작업 #7). 화면 상태 홀더는 이 결과만 받아 문구로 바꾼다.
 *
 * ## 순서 (모두 직렬, 같은 목적지·위치를 공유)
 * 1. 운전자 조건 1회 조회 — `getCondition()`(리뷰 task-06 MINOR-A: observeCondition().first() 대신 1회 조회)
 *    - `Available` → 그대로([ConditionSource.LOADED])
 *    - `Unavailable(NO_DATA)` = **확인된 미설정** → [DepartureSnapshot.ConditionNotSet]. 내비는 조회하지 않는다(감독관 결정 D-9)
 *    - 그 밖의 `Unavailable` = 조회 실패 → [lastKnownCondition]이 있으면 그것([ConditionSource.LAST_KNOWN]),
 *      없으면 야간 제한만 있다고 보고 계산([ConditionSource.ASSUMED_NIGHT_RESTRICTION], [ASSUMED_WHEN_UNAVAILABLE])
 * 2. 내비 목적지 1회 조회((0,0) 방어)
 * 3. 현재 위치 1회 조회 — 출발 마감(출발지 일몰) 또는 반경(현재 위치 참고 판정)에 필요할 때만
 * 4. 출발 마감 → 반경 → 경로 확인. 2·3의 결과를 세 유스케이스에 같이 넘긴다(리뷰 task-05 #7-5, MINOR-4)
 *
 * 부작용 없음(조회만). 내비 경로를 요청하지 않는다(#5b).
 */
class DepartureCheckUseCase(
    private val naviRepository: NaviRepository,
    private val driverConditionRepository: DriverConditionRepository,
    private val departureDeadline: DepartureDeadlineUseCase,
    private val radiusCheck: RadiusCheckUseCase,
    private val conditionRoute: ConditionRouteUseCase,
    private val clock: Clock,
    /** false 면 반경을 확인하지 않는다(결과 radius = null, 반경용 위치 조회도 안 함). DepartureConfig.radiusCheckEnabled. 기본값 없음 */
    private val radiusCheckEnabled: Boolean,
) {

    /**
     * @param previousEstimate 직전 결과의 `AppliedEta.sdkEstimate`. 신선하고 같은 목적지면 requestTrip 을 생략한다(리뷰 task-04b #7-2).
     * @param lastKnownCondition 이 화면 세션에서 마지막으로 조회에 성공한 조건. 조회 실패 때만 쓴다(D-9).
     */
    suspend fun check(
        previousEstimate: TripEstimate? = null,
        lastKnownCondition: DriverCondition? = null,
    ): DepartureSnapshot {
        val resolved = when (val r = driverConditionRepository.getCondition()) {
            is DataResult.Available -> ResolvedCondition(r.value, ConditionSource.LOADED, lookupFailure = null)
            is DataResult.Unavailable -> when (r.reason) {
                UnavailableReason.NO_DATA -> return DepartureSnapshot.ConditionNotSet(clock.instant())
                UnavailableReason.NOT_SUPPORTED,
                UnavailableReason.TIMEOUT,
                UnavailableReason.SERVICE_ERROR,
                UnavailableReason.PERMISSION_DENIED,
                UnavailableReason.INVALID_REQUEST,
                -> if (lastKnownCondition != null) {
                    ResolvedCondition(lastKnownCondition, ConditionSource.LAST_KNOWN, r.reason)
                } else {
                    ResolvedCondition(ASSUMED_WHEN_UNAVAILABLE, ConditionSource.ASSUMED_NIGHT_RESTRICTION, r.reason)
                }
            }
        }
        val condition = resolved.condition

        val destination = naviRepository.getDestination().treatNullIslandAsNotSet()
        val destinationPresent = (destination as? DataResult.Available)?.value is NaviDestination.Present
        val needsLocation = condition.noNightDriving ||
            (radiusCheckEnabled && destinationPresent && condition.radiusCenter != null)
        // 필요 없으면 null 로 넘긴다. 이때 두 유스케이스는 위치를 쓰지 않는 분기로 끝나므로 따로 조회하지 않는다.
        val location: DataResult<GeoPoint>? = if (needsLocation) naviRepository.getCurrentLocation() else null

        val deadline = departureDeadline.calculateForNaviDestination(
            condition = condition,
            previousEstimate = previousEstimate,
            destination = destination,
            currentLocation = location,
        )
        val radius = if (radiusCheckEnabled) {
            radiusCheck.check(condition = condition, destination = destination, currentLocation = location)
        } else {
            null
        }
        val route = conditionRoute.check(preference = condition.routePreference, destination = destination)

        return DepartureSnapshot.Checked(
            evaluatedAt = deadline.evaluatedAt,
            condition = resolved,
            destination = destination,
            deadline = deadline,
            radius = radius,
            route = route,
        )
    }

    companion object {
        /**
         * 조건 조회 실패 + 마지막 값 없음일 때 쓰는 보수적 조건(감독관 결정 D-9): 야간 운전 제한만 있다고 본다.
         * 반경 기준점·경로 조건은 모르므로 비워 둔다 → 반경은 "확인 불가"(CenterNotSet), 경로 조건은 화면이 "확인 불가"로 표시한다.
         */
        val ASSUMED_WHEN_UNAVAILABLE: DriverCondition = DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING))
    }
}
