package com.pleos.departure.usecase

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.NaviRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * core 유스케이스 테스트용 내비 스텁. 응답을 프로퍼티로 정하고, 호출 횟수를 기록한다.
 * (:departure-fake 는 core 를 의존하므로 core 테스트에서 쓰지 않는다)
 */
internal class ScriptedNavi(
    var destination: DataResult<NaviDestination> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED),
    var location: DataResult<GeoPoint> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED),
    var routeState: DataResult<RouteState> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED),
) : NaviRepository {
    var destinationCalls = 0
    var locationCalls = 0
    var routeStateCalls = 0
    var tripCalls = 0

    override suspend fun getDestination(): DataResult<NaviDestination> {
        destinationCalls++
        return destination
    }

    override suspend fun getCurrentLocation(): DataResult<GeoPoint> {
        locationCalls++
        return location
    }

    override suspend fun getRouteState(): DataResult<RouteState> {
        routeStateCalls++
        return routeState
    }

    override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> {
        tripCalls++
        return DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
    }

    override fun observeRemainingTrip(): Flow<RemainingTrip> = emptyFlow()
}
