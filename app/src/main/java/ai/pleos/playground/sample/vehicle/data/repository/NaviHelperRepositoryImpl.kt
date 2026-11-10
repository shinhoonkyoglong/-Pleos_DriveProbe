package ai.pleos.playground.sample.vehicle.data.repository

import android.util.Log
import ai.pleos.playground.sample.vehicle.data.model.DrivingConditions
import ai.pleos.playground.sample.vehicle.data.model.NavigationState
import ai.pleos.playground.sample.vehicle.data.model.RestAreaInfo
import ai.pleos.playground.sample.vehicle.data.model.RouteInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class NaviHelperRepositoryImpl : NaviHelperRepository {

    private val tag = "NaviHelperRepository"

    private val _navigationState = MutableStateFlow(NavigationState.IDLE)
    override val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _currentRoute = MutableStateFlow<RouteInfo?>(null)
    override val currentRoute: StateFlow<RouteInfo?> = _currentRoute.asStateFlow()

    private val defaultRestAreas = listOf(
        RestAreaInfo(
            id = "REST_001",
            name = "서울 만남의광장 졸음쉼터",
            distanceKm = 2.4,
            estimatedArrivalMinutes = 3,
            latitude = 37.4601,
            longitude = 127.0423
        ),
        RestAreaInfo(
            id = "REST_002",
            name = "판교 임시 졸음쉼터",
            distanceKm = 5.8,
            estimatedArrivalMinutes = 7,
            latitude = 37.4020,
            longitude = 127.1086
        ),
        RestAreaInfo(
            id = "REST_003",
            name = "죽전 휴게소 (서울방향)",
            distanceKm = 11.2,
            estimatedArrivalMinutes = 12,
            latitude = 37.3325,
            longitude = 127.1042
        )
    )

    private val _nearestRestAreas = MutableStateFlow(defaultRestAreas)
    override val nearestRestAreas: StateFlow<List<RestAreaInfo>> = _nearestRestAreas.asStateFlow()

    private var mockCurrentLat = 37.4600
    private var mockCurrentLng = 127.0400

    override suspend fun searchConditionAdaptedRoute(
        destination: String,
        conditions: DrivingConditions
    ): Result<RouteInfo> {
        _navigationState.value = NavigationState.CALCULATING

        val routeSummary = buildString {
            append("목적지: $destination | ")
            if (conditions.avoidHighways) {
                append("고속도로 제외(일반국도 우선), ")
            }
            if (conditions.avoidNightDriving) {
                append("일몰 전 도착 추천 경로, ")
            }
            if (conditions.maxSpeedLimitKmh != null) {
                append("최고속도 ${conditions.maxSpeedLimitKmh}km/h 이하 도로")
            }
        }.trimEnd(',', ' ')

        val calculatedRoute = RouteInfo(
            routeId = UUID.randomUUID().toString(),
            destinationName = destination,
            totalDistanceKm = if (conditions.avoidHighways) 24.5 else 18.2,
            estimatedDurationMinutes = if (conditions.avoidHighways) 45 else 25,
            containsHighway = !conditions.avoidHighways,
            isNightDrivingExpected = false,
            summary = routeSummary
        )

        _currentRoute.value = calculatedRoute
        _navigationState.value = NavigationState.IDLE
        Log.i(tag, "Route adapted: $calculatedRoute")
        return Result.success(calculatedRoute)
    }

    override suspend fun guideToNearestRestArea(): Result<RestAreaInfo> {
        val closest = _nearestRestAreas.value.minByOrNull { it.distanceKm }
            ?: return Result.failure(IllegalStateException("주변에 안내 가능한 졸음쉼터가 없습니다."))

        Log.w(tag, "Level 3 Emergency: Diverting route to rest area -> ${closest.name}")

        val emergencyRoute = RouteInfo(
            routeId = UUID.randomUUID().toString(),
            destinationName = closest.name,
            totalDistanceKm = closest.distanceKm,
            estimatedDurationMinutes = closest.estimatedArrivalMinutes,
            containsHighway = false,
            summary = "피로/졸음 감지로 인한 긴급 졸음쉼터 우회 안내"
        )

        _currentRoute.value = emergencyRoute
        _navigationState.value = NavigationState.REST_AREA_DIVERTED

        return Result.success(closest)
    }

    override suspend fun startNavigation(routeId: String): Result<Boolean> {
        val route = _currentRoute.value
        if (route == null || route.routeId != routeId) {
            return Result.failure(IllegalArgumentException("유효하지 않은 경로 ID입니다."))
        }

        _navigationState.value = NavigationState.NAVIGATING
        Log.i(tag, "Navigation started for: ${route.destinationName}")
        return Result.success(true)
    }

    override suspend fun stopNavigation(): Result<Boolean> {
        _navigationState.value = NavigationState.ARRIVED
        Log.i(tag, "Navigation stopped.")
        return Result.success(true)
    }

    override fun injectMockRestAreas(restAreas: List<RestAreaInfo>) {
        _nearestRestAreas.value = restAreas
    }

    override fun setMockLocation(latitude: Double, longitude: Double) {
        mockCurrentLat = latitude
        mockCurrentLng = longitude
    }
}
