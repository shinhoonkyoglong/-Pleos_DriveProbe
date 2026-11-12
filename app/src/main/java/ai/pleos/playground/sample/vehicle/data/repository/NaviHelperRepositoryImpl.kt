package ai.pleos.playground.sample.vehicle.data.repository

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.sample.vehicle.navigation.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.sample.vehicle.data.model.DrivingConditions
import ai.pleos.playground.sample.vehicle.data.model.NaviDestinationInfo
import ai.pleos.playground.sample.vehicle.data.model.NaviHelperException
import ai.pleos.playground.sample.vehicle.data.model.NaviLocation
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.model.NaviRouteState
import ai.pleos.playground.sample.vehicle.data.model.NaviTripInfo
import ai.pleos.playground.sample.vehicle.data.model.NavigationState
import ai.pleos.playground.sample.vehicle.data.model.RestAreaInfo
import ai.pleos.playground.sample.vehicle.data.model.RouteInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import ai.pleos.playground.navi.constants.NaviMode as SdkNaviMode

/**
 * @param context NaviHelper SDK 연결용. null 이면(기존 호출 `NaviHelperRepositoryImpl()`) SDK 를 쓰지 않고 조회 계열은 Mock 으로만 동작한다.
 * @param enableMockFallback NaviHelper 서비스 연결 실패 시 조회 계열을 Mock 값으로 대신할지(VehicleRepositoryImpl 과 같은 방식).
 *   false 면 Result.failure(NaviHelperException(UNAVAILABLE)) 를 돌려준다.
 *   (기존 목업 메서드 searchConditionAdaptedRoute 등은 이 값과 무관하게 지금처럼 동작한다)
 */
class NaviHelperRepositoryImpl(
    context: Context? = null,
    private val enableMockFallback: Boolean = true
) : NaviHelperRepository, RestNavigationPort {

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

    /**
     * 목업 구현: NaviHelper SDK 를 부르지 않고 조건에 맞춘 가상의 경로를 만든다. 반환 값(거리·시간·containsHighway 등)은 실측값이 아니다.
     *
     * 실제 SDK 로 바꿀 때의 실측 한계(인터페이스 KDoc 참고, 근거 MyApplication/docs/pleos/api-notes/navi-runtime-check.md §10):
     * 내비는 RouteInfo.routeOption 을 무시하고, requestRoute 는 현재 안내를 덮어쓰며, 고속도로 회피 옵션은 없고, 좌표가 필수다.
     * 무료도로 확인은 사용자가 "유료도로 우회"를 켠 뒤 getRouteStateInfo().routeOption == FREE 로 한다(docs/departure/README.md).
     */
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

    /** Legacy mock-only API; production DANGER uses RestNavigationPort instead. */
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

    // =========================================================================
    // NaviHelper 조회 계열 (2번 '출발 전' 연동에서 추가)
    //
    // 실제 NaviHelper SDK(ai.pleos.playground:NaviHelper:3.0.5)를 호출한다. 서비스 연결이 안 되면 enableMockFallback 에 따라
    // Mock 값으로 대신한다(VehicleRepositoryImpl 과 같은 방식). 위의 기존 목업 메서드는 SDK 를 쓰지 않으며 그대로다.
    //
    // SDK 동작(에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 실행 확인 — MyApplication/docs/pleos/api-notes/navi-runtime-check.md):
    // - 모든 결과는 리스너 콜백으로 온다. 콜백에 요청 ID 가 없다 → requestMutex 로 한 번에 한 요청만 보낸다.
    // - onConnected 는 main 스레드, 데이터 콜백은 binder 스레드에서 온다. SDK 호출은 프로브와 같이 main 스레드에서 한다.
    // - 요청하지 않은 푸시(onDestinationInfo 0 값, onRouteStateInfo)도 온다 → 마지막 상태(currentDestination/currentRouteState)로 반영한다.
    //   요청 대기 중 외부 사건으로 온 푸시를 응답으로 받을 수 있다(요청 ID 가 없어 구분 불가). 화면 쪽 재확인이 이를 보완한다.
    //   같은 이유로, 타임아웃 뒤 늦게 도착한 콜백이 다음 같은 종류 요청의 응답으로 쓰일 수 있다(알려진 한계).
    // - Mock Fallback 값(isMock = true)은 currentDestination/currentRouteState 에 넣지 않는다(이 두 상태는 실제 SDK 값만).
    //   미연결 + Mock 목적지 미주입이면 "목적지 없음"을 지어내지 않고 UNAVAILABLE 실패로 돌려준다.
    // - NaviHelper 인스턴스·리스너는 앱 전체에서 이 Repository 하나만 가진다(두 곳에서 등록하면 응답이 섞임).
    // =========================================================================

    private val appContext: Context? = context?.applicationContext

    @Volatile
    private var naviHelper: NaviHelper? = null

    @Volatile
    private var isNaviConnected = false

    /** 마지막 연결 실패 시각(ms). [CONNECT_RETRY_INTERVAL_MS] 안에는 다시 연결을 시도하지 않는다(매 호출 지연 방지). */
    @Volatile
    private var lastConnectFailedAtMs: Long? = null

    private val connectMutex = Mutex()
    private val requestMutex = Mutex()

    @Volatile private var pendingDestination: CompletableDeferred<DestinationInfo>? = null
    @Volatile private var pendingRouteState: CompletableDeferred<RouteStateInfo>? = null
    @Volatile private var pendingLocation: CompletableDeferred<CurrentLocationInfo>? = null
    @Volatile private var pendingTrip: CompletableDeferred<TripInfo>? = null
    @Volatile private var pendingWaypoints: CompletableDeferred<List<WaypointInfo>>? = null
    @Volatile private var pendingTbt: CompletableDeferred<List<TBTInfo>>? = null
    @Volatile private var pendingBookmarks: CompletableDeferred<List<BookmarkInfo>>? = null
    @Volatile private var routingCompletionCount = 0L
    @Volatile private var routeWriteInProgress = false
    @Volatile private var routeWriteError: NaviErrorCode? = null

    /**
     * 현재 경로의 onRouteStarted.tollFare 캐시. 다시 조회할 수 없는 값이라 리스너로 받아 둔다.
     * 버리는 때: onRouteCancelled(경로 교체 시작), 리스너 (재)등록, 묶인 목적지 좌표와 다른 목적지가 들어옴.
     * [tollFareDestination] 은 tollFare 를 받은 뒤 처음 들어온 목적지 좌표(onRouteStarted 에는 좌표가 없어 이렇게 묶는다).
     */
    private val tollFareLock = Any()
    private var tollFare: Int? = null
    private var tollFareDestination: Pair<Double, Double>? = null

    private val _currentDestination = MutableStateFlow<NaviDestinationInfo?>(null)
    override val currentDestination: StateFlow<NaviDestinationInfo?> = _currentDestination.asStateFlow()

    private val _currentRouteState = MutableStateFlow<NaviRouteState?>(null)
    override val currentRouteState: StateFlow<NaviRouteState?> = _currentRouteState.asStateFlow()

    @Volatile private var mockDestination: NaviDestinationInfo? = null
    @Volatile private var mockRouteOption: String = "RECOMMENDED"

    private val naviListener = object : NaviHelperEventListener {
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) { pendingBookmarks?.complete(bookmarkInfoList) }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            pendingTbt?.complete(tbtInfoList)
        }

        override fun onRoutingCompleted() { routingCompletionCount++ }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            pendingWaypoints?.complete(waypointInfoList)
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            synchronized(tollFareLock) {
                tollFare = routeStartInfo.tollFare
                tollFareDestination = null
            }
            Log.i(tag, "onRouteStarted tollFare=${routeStartInfo.tollFare}")
        }

        override fun onRouteCancelled() {
            // 안내 종료일 수도, 경로 교체 시작일 수도 있다(이후 0 값 목적지 → 7~19초 뒤 onRouteStarted).
            clearTollFare()
            Log.i(tag, "onRouteCancelled")
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            updateDestination(toNaviDestination(destinationInfo))
            pendingDestination?.complete(destinationInfo)
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            _currentRouteState.value = toNaviRouteState(routeStateInfo)
            pendingRouteState?.complete(routeStateInfo)
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            pendingLocation?.complete(currentLocationInfo)
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            pendingTrip?.complete(tripInfo)
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(tag, "NaviHelper onError: $errorCode")
            if (routeWriteInProgress && errorCode != NaviErrorCode.CAUSE_SEARCH_TRIP &&
                errorCode != NaviErrorCode.CAUSE_LOW_BATTERY) routeWriteError = errorCode
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) {
                pendingTrip?.completeExceptionally(
                    NaviHelperException(NaviHelperException.Reason.SERVICE_ERROR, "requestTrip 실패: $errorCode")
                )
            }
        }
    }

    override suspend fun getDestinationInfo(): Result<NaviDestinationInfo?> {
        if (!ensureConnected()) {
            // 미연결 + Mock 목적지 미주입: success(null)("목적지 없음")을 지어내지 않는다 — 실제로는 목적지를 모르는 상태다.
            val mock = mockDestination
                ?: return Result.failure(
                    NaviHelperException(
                        NaviHelperException.Reason.UNAVAILABLE,
                        "NaviHelper 미연결 — 목적지를 알 수 없음(Mock 목적지 미주입)"
                    )
                )
            return fallback("getDestinationInfo") { mock.copy(isMock = true) }
        }
        return requestMutex.withLock {
            awaitCallback<DestinationInfo>("getDestinationInfo", DESTINATION_TIMEOUT_MS, { pendingDestination = it }) {
                it.getDestinationInfo()
            }.map { toNaviDestination(it) }
        }
    }

    override suspend fun getRouteStateInfo(): Result<NaviRouteState> {
        if (!ensureConnected()) {
            return fallback("getRouteStateInfo") { mockRouteState() }
        }
        return requestMutex.withLock { routeStateLocked() }
    }

    override suspend fun openNaviSettings(): Result<Unit> {
        if (!ensureConnected()) {
            return Result.failure(NaviHelperException(NaviHelperException.Reason.UNAVAILABLE, "NaviHelper 서비스 미연결: openNaviSettings"))
        }
        val helper = naviHelper
            ?: return Result.failure(NaviHelperException(NaviHelperException.Reason.UNAVAILABLE, "NaviHelper 없음: openNaviSettings"))
        return try {
            withContext(Dispatchers.Main) { helper.requestNavigationScreen(NavigationScreenRequest.Settings) }
            Log.i(tag, "openNaviSettings 요청")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(NaviHelperException(NaviHelperException.Reason.PERMISSION_DENIED, "openNaviSettings 권한 없음", e))
        } catch (e: Exception) {
            Result.failure(NaviHelperException(NaviHelperException.Reason.SERVICE_ERROR, "openNaviSettings 실패: ${e.message}", e))
        }
    }

    override suspend fun requestTrip(
        latitude: Double,
        longitude: Double,
        poiName: String,
        poiId: String
    ): Result<NaviTripInfo> {
        if (!ensureConnected()) {
            return fallback("requestTrip") { mockTrip(latitude, longitude) }
        }
        return requestMutex.withLock {
            val trip = awaitCallback<TripInfo>("requestTrip", TRIP_TIMEOUT_MS, { pendingTrip = it }) {
                it.requestTrip(
                    TripRequest(
                        latitude = latitude,
                        longitude = longitude,
                        poiId = poiId,
                        poiName = poiName,
                        poiSubId = ""
                    )
                )
            }.getOrElse { return@withLock Result.failure(it) }

            // requestTrip 결과는 내비가 그 순간 쓰는 "유료도로 우회" 설정을 따른다(실측) → 안내 중이면 같은 시점 경로 옵션을 함께 준다.
            val state = routeStateLocked().getOrNull()
            val option = state?.takeIf { it.naviMode.isGuiding() }?.routeOption
            Result.success(
                NaviTripInfo(
                    distanceMeters = trip.distance,
                    etaSeconds = trip.eta.toLong(),
                    routeOption = option
                )
            )
        }
    }

    override suspend fun getCurrentLocation(): Result<NaviLocation> {
        if (!ensureConnected()) {
            return fallback("getCurrentLocation") {
                NaviLocation(latitude = mockCurrentLat, longitude = mockCurrentLng, isMock = true)
            }
        }
        return requestMutex.withLock {
            awaitCallback<CurrentLocationInfo>("getCurrentLocationInfo", LOCATION_TIMEOUT_MS, { pendingLocation = it }) {
                it.getCurrentLocationInfo()
            }.map { NaviLocation(latitude = it.latitude, longitude = it.longitude, address = it.address) }
        }
    }

    override fun injectMockDestination(destination: NaviDestinationInfo?) {
        mockDestination = destination
    }

    // Strict navigation port: no mock fallback and no independent SDK instance/listener.
    override suspend fun location(): NaviLocation = getCurrentLocation().getOrThrow()
    override suspend fun estimate(place: RestPlace): NaviTripInfo {
        requireRealConnection()
        return requestMutex.withLock {
            val trip=awaitCallback<TripInfo>("requestTrip",TRIP_TIMEOUT_MS,{pendingTrip=it}) {
                it.requestTrip(TripRequest(latitude=place.latitude,longitude=place.longitude,
                    poiId=place.poiId,poiName=place.name,poiSubId=place.poiSubId))
            }.getOrThrow()
            val state=routeStateLocked().getOrThrow()
            NaviTripInfo(trip.distance,trip.eta.toLong(),state.routeOption)
        }
    }

    @Volatile private var nativeBookmarkCount: Int? = null
    override val lastNativeBookmarkCount: Int? get() = nativeBookmarkCount

    override suspend fun serviceAreaPlaces(): List<RestPlace> {
        requireRealConnection()
        return requestMutex.withLock {
            nativeBookmarkCount=null
            val received=awaitCallback<List<BookmarkInfo>>("getBookmarkInfo",DESTINATION_TIMEOUT_MS,{pendingBookmarks=it}) { it.getBookmarkInfo() }.getOrThrow()
            nativeBookmarkCount=received.size
            received.filter { NaviTbtEntry("TURN_ENTER_SERVICEAREA",41,0.0,it.name).facilityKind()==TbtFacilityKind.SERVICE_AREA }
                .filter { RestNavigation.validCoordinates(it.latitude,it.longitude) }.map {
                    RestPlace("NAVI_BOOKMARK:${it.poiProvider}:${it.poiId}:${it.latitude}:${it.longitude}",it.name,it.latitude,it.longitude,
                        "미확인","","",it.address,"",it.poiId,it.poiSubId ?: "",it.poiProvider)
                }.distinctBy { it.id }
        }
    }

    override suspend fun snapshot(): RestNaviSnapshot {
        requireRealConnection()
        return requestMutex.withLock { restSnapshotLocked() }
    }

    override suspend fun tbtRoute(): NaviTbtRoute {
        requireRealConnection()
        return requestMutex.withLock {
            // Raw TBT discovery must not depend on the still-unverified empty-waypoint callback.
            val before = restSnapshotLocked(includeWaypoints = false)
            val entries = awaitCallback<List<TBTInfo>>("getTBTInfo", DESTINATION_TIMEOUT_MS, { pendingTbt = it }) {
                it.getTBTInfo()
            }.getOrThrow()
            val capturedAt = android.os.SystemClock.elapsedRealtime()
            val after = restSnapshotLocked(includeWaypoints = false)
            val sameDestination = if (before.destination == null) after.destination == null
                else RestNavigation.sameDestination(before.destination, after.destination)
            if (before.routeId != after.routeId || !sameDestination ||
                before.state.routeOption != after.state.routeOption || before.state.naviMode != after.state.naviMode ||
                !RestNavigation.sameWaypoints(before.waypoints, after.waypoints))
                throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED,"안내 목록 조회 중 경로가 바뀌었어요. 다시 조회해 주세요")
            NaviTbtRoute(before.routeId,capturedAt,entries.map { NaviTbtEntry(it.type.name,it.type.value,it.distance,it.description) })
        }
    }

    private suspend fun requireRealConnection() {
        if (!ensureConnected()) throw RestNavigationFailure(RestNavigationStatus.UNAVAILABLE, "내비 서비스에 연결하지 못했어요")
    }

    private suspend fun restSnapshotLocked(includeWaypoints: Boolean = true): RestNaviSnapshot {
        val destination = awaitCallback<DestinationInfo>("getDestinationInfo", DESTINATION_TIMEOUT_MS, { pendingDestination = it }) {
            it.getDestinationInfo()
        }.getOrThrow().let(::toNaviDestination)
        val state = routeStateLocked().getOrThrow()
        val waypoints = if (includeWaypoints) awaitCallback<List<WaypointInfo>>("getWaypointInfo", DESTINATION_TIMEOUT_MS, { pendingWaypoints = it }) {
            it.getWaypointInfo()
        }.getOrThrow().map {
            NaviWaypoint(it.latitude,it.longitude,it.name,it.poiId,it.poiSubId.orEmpty(),it.poiProvider,
                it.displayLatitude,it.displayLongitude)
        } else emptyList()
        val location = awaitCallback<CurrentLocationInfo>("getCurrentLocationInfo", LOCATION_TIMEOUT_MS, { pendingLocation = it }) {
            it.getCurrentLocationInfo()
        }.getOrThrow()
        return RestNaviSnapshot(destination, state, waypoints, routeId = location.routeId)
    }

    override suspend fun addFirstWaypoint(
        candidate: RestCandidate, expected: RestNaviSnapshot, dispatched: () -> Unit,
    ): RestNaviSnapshot {
        requireRealConnection()
        return requestMutex.withLock {
            val fresh = restSnapshotLocked()
            RestNavigation.validateSnapshot(fresh)
            if (expected.routeId != fresh.routeId || !RestNavigation.sameDestination(expected.destination, fresh.destination) ||
                !RestNavigation.sameWaypoints(expected.waypoints, fresh.waypoints) ||
                expected.state.routeOption != fresh.state.routeOption)
                throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED, "검색 중 목적지·경유지·경로 옵션이 바뀌어 쉼터 요청을 취소했어요")
            require(!candidate.trip.isMock && RestNavigation.validCoordinates(candidate.place.latitude, candidate.place.longitude))
            val helper = naviHelper ?: throw RestNavigationFailure(RestNavigationStatus.UNAVAILABLE, "내비 연결이 끊겼어요")
            val completedBefore = routingCompletionCount
            val confirmation=WaypointWriteConfirmation(expected,candidate.place)
            var lastObserved: RestNaviSnapshot? = null
            routeWriteError = null
            routeWriteInProgress = true
            try {
                withContext(Dispatchers.Main) {
                    currentCoroutineContext().ensureActive()
                    // Construct first: malformed input must not be recorded as a dispatched write.
                    val request = RequestWaypointInfo(
                        latitude = candidate.place.latitude, longitude = candidate.place.longitude,
                        poiName = candidate.place.name, waypointIndex = WaypointIndex.FIRST,
                        poiId = candidate.place.poiId, address = candidate.place.address, poiSubId = candidate.place.poiSubId,
                        poiProvider = candidate.place.poiProvider,
                    )
                    // Conservative boundary: if Binder throws, the service may still have received the request.
                    dispatched()
                    helper.addWaypoint(request)
                }
                val confirmed = withTimeoutOrNull(30_000L) {
                    var result: RestNaviSnapshot? = null
                    while (result == null) {
                        currentCoroutineContext().ensureActive()
                        routeWriteError?.let { throw RestNavigationFailure(RestNavigationStatus.REQUEST_FAILED, "내비 경유지 요청 오류: $it. 적용 여부를 내비에서 확인해 주세요", lastObserved) }
                        val observed = restSnapshotLocked()
                        lastObserved = observed
                        val verified=confirmation.observe(observed,routingCompletionCount > completedBefore)
                        Log.i("NaviWaypointProbe","ADD_READBACK ${confirmation.diagnostic}")
                        if (verified) {
                            Log.i("NaviWaypointProbe","ADD_VERIFIED evidence=${if(routingCompletionCount > completedBefore) "EVENT_AND_READBACK" else "STABLE_READBACK"}")
                            result = observed
                        } else delay(500)
                    }
                    result
                }
                confirmed ?: run {
                    val diagnostic="${confirmation.diagnostic} routingCompleted=${routingCompletionCount > completedBefore} " +
                        (lastObserved?.let { waypointObservationDiagnostic(expected,candidate.place,it) } ?: "NO_SNAPSHOT")
                    Log.w("NaviWaypointProbe","ADD_UNCONFIRMED $diagnostic")
                    throw RestNavigationFailure(RestNavigationStatus.REQUEST_UNCONFIRMED,
                        "경유지 요청 후 완료 확인이 안 됐어요. $diagnostic",lastObserved)
                }
            } catch (e: SecurityException) {
                throw RestNavigationFailure(RestNavigationStatus.PERMISSION_DENIED, "경유지 제어 권한이 없어요")
            } catch (e: NaviHelperException) {
                throw RestNavigationFailure(
                    if (e.reason == NaviHelperException.Reason.PERMISSION_DENIED) RestNavigationStatus.PERMISSION_DENIED else RestNavigationStatus.REQUEST_UNCONFIRMED,
                    "쉼터 요청 확인 중 오류가 발생했어요: ${e.message}",
                    lastObserved,
                )
            } finally {
                routeWriteInProgress = false
                routeWriteError = null
            }
        }
    }

    /** Debug instrumentation cleanup only. Never remove a waypoint after an unrelated user change. */
    suspend fun removeProbeWaypoint(place: RestPlace, original: RestNaviSnapshot,beforeDispatch: () -> Unit = {}): RestNaviSnapshot {
        check(ai.pleos.playground.sample.vehicle.BuildConfig.DEBUG)
        requireRealConnection()
        return requestMutex.withLock {
            val observed = restSnapshotLocked()
            if (!RestNavigation.sameDestination(original.destination, observed.destination) ||
                original.state.routeOption != observed.state.routeOption || original.state.naviMode != observed.state.naviMode ||
                original.waypoints.isNotEmpty() || observed.waypoints.size != 1 ||
                !RestNavigation.waypointMatches(place, observed.waypoints.firstOrNull())) {
                val diagnostic=waypointObservationDiagnostic(original,place,observed)
                Log.w("NaviWaypointProbe","CLEANUP_CONTEXT_MISMATCH $diagnostic")
                throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED,
                    "시험 경유지와 현재 경로가 달라 자동 정리하지 않았어요. $diagnostic",observed)
            }
            withContext(Dispatchers.Main) {
                currentCoroutineContext().ensureActive()
                beforeDispatch()
                naviHelper!!.removeWaypoint(WaypointIndex.FIRST)
            }
            withTimeoutOrNull(30_000L) {
                var restored: RestNaviSnapshot? = null
                while (restored == null) {
                    val snapshot = restSnapshotLocked()
                    if (RestNavigation.sameDestination(original.destination, snapshot.destination) && snapshot.waypoints.isEmpty() &&
                        snapshot.state.naviMode == original.state.naviMode && snapshot.state.routeOption == original.state.routeOption) restored = snapshot
                    else delay(500)
                }
                restored
            } ?: throw RestNavigationFailure(RestNavigationStatus.REQUEST_UNCONFIRMED, "시험 경유지 삭제 요청 후 원래 상태 복원을 확인하지 못했어요")
        }
    }

    override fun injectMockRouteOption(routeOption: String) {
        mockRouteOption = routeOption
    }

    /**
     * NaviHelper 리스너 해제 + 연결 해제. 이후 조회 시 다시 연결한다.
     * 지금은 호출처가 없다(Application.onTerminate 는 실기기에서 호출되지 않음). 수명 관리 위치를 정해 부를 때는
     * 메인 스레드에서, 진행 중인 조회가 없을 때 부른다(connectMutex·requestMutex 밖에서 실행됨).
     */
    fun releaseNaviHelper() {
        val helper = naviHelper
        naviHelper = null
        isNaviConnected = false
        clearTollFare()
        try {
            helper?.removeListener(naviListener)
            helper?.release()
        } catch (e: Exception) {
            Log.w(tag, "NaviHelper release error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // 내부 구현
    // ---------------------------------------------------------------------

    /** 연결돼 있으면 true. 아니면 한 번 연결을 시도한다(context 없음·실패·재시도 대기 중이면 false). */
    private suspend fun ensureConnected(): Boolean {
        if (isNaviConnected) return true
        val ctx = appContext ?: return false
        return connectMutex.withLock {
            if (isNaviConnected) return@withLock true
            val failedAt = lastConnectFailedAtMs
            if (failedAt != null && System.currentTimeMillis() - failedAt < CONNECT_RETRY_INTERVAL_MS) {
                return@withLock false
            }
            val helper = try {
                NaviHelper(ctx)
            } catch (e: Exception) {
                Log.w(tag, "NaviHelper 생성 실패. Mock fallback 으로 동작: ${e.message}")
                lastConnectFailedAtMs = System.currentTimeMillis()
                return@withLock false
            }
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        try {
                            helper.initialize(
                                onConnected = {
                                    if (continuation.isActive) continuation.resume(true)
                                },
                                onFailed = { throwable ->
                                    Log.w(tag, "NaviHelper initialize 실패: ${throwable.message}")
                                    if (continuation.isActive) {
                                        continuation.resume(false)
                                    } else if (naviHelper === helper) {
                                        // 연결된 뒤 늦게 온 실패(서비스 끊김 후 재연결 실패 등): 다음 조회에서 다시 연결하게 한다.
                                        // SDK 문서에는 연결 끊김 전용 콜백이 없다 → 이 경로와 아래 SDK 호출 예외만 근거로 쓴다.
                                        markDisconnected(helper, "onFailed after connected")
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.w(tag, "NaviHelper initialize 예외: ${e.message}")
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }
            } ?: false

            if (!connected) {
                try {
                    helper.release()
                } catch (e: Exception) {
                    Log.w(tag, "NaviHelper release error: ${e.message}")
                }
                lastConnectFailedAtMs = System.currentTimeMillis()
                Log.w(tag, "NaviHelper 연결 실패. Running in fallback mode (mockFallback=$enableMockFallback)")
                return@withLock false
            }

            withContext(Dispatchers.Main) { helper.addListener(naviListener) }
            // 리스너 (재)등록: 떨어져 있던 동안 경로가 바뀌었을 수 있으므로 tollFare 는 버린다.
            clearTollFare()
            naviHelper = helper
            isNaviConnected = true
            lastConnectFailedAtMs = null
            Log.i(tag, "NaviHelper SDK successfully connected.")
            true
        }
    }

    /**
     * SDK 요청을 main 스레드에서 보내고 대응 콜백을 [timeoutMs] 동안 기다린다. requestMutex 를 잡은 상태에서만 부른다.
     * 한계: 콜백에 요청 ID 가 없어, 타임아웃 뒤 늦게 온 콜백이나 대기 중 외부 사건으로 온 푸시를 이 요청의 응답으로 받을 수 있다.
     * SDK 호출 자체가 예외를 던지면(서비스 끊김 등) 연결을 끊은 것으로 보고 다음 조회에서 다시 연결한다(권한 예외 제외).
     * @param setPending 대응 콜백이 완료시킬 대기 슬롯 지정/해제
     */
    private suspend fun <T> awaitCallback(
        name: String,
        timeoutMs: Long,
        setPending: (CompletableDeferred<T>?) -> Unit,
        call: (NaviHelper) -> Unit
    ): Result<T> {
        val helper = naviHelper
            ?: return Result.failure(NaviHelperException(NaviHelperException.Reason.UNAVAILABLE, "NaviHelper 미연결: $name"))
        val deferred = CompletableDeferred<T>()
        setPending(deferred)
        return try {
            withContext(Dispatchers.Main) { call(helper) }
            val value = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (value == null) {
                Result.failure(NaviHelperException(NaviHelperException.Reason.TIMEOUT, "$name 콜백이 ${timeoutMs}ms 안에 오지 않음"))
            } else {
                Result.success(value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NaviHelperException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(NaviHelperException(NaviHelperException.Reason.PERMISSION_DENIED, "$name 권한 없음", e))
        } catch (e: Exception) {
            Log.e(tag, "$name failed: ${e.message}")
            markDisconnected(helper, "$name threw ${e.javaClass.simpleName}")
            Result.failure(NaviHelperException(NaviHelperException.Reason.SERVICE_ERROR, "$name 실패: ${e.message}", e))
        } finally {
            setPending(null)
        }
    }

    private suspend fun routeStateLocked(): Result<NaviRouteState> =
        awaitCallback<RouteStateInfo>("getRouteStateInfo", ROUTE_STATE_TIMEOUT_MS, { pendingRouteState = it }) {
            it.getRouteStateInfo()
        }.map { toNaviRouteState(it) }

    /** SDK 목적지 → 모델. 목적지 없음(좌표 (0,0) 0 값 객체)은 null. (0,0)을 목적지로 넘기면 반경 계산이 약 13,000km 가 된다. */
    private fun toNaviDestination(info: DestinationInfo): NaviDestinationInfo? {
        if (info.latitude == 0.0 && info.longitude == 0.0) return null
        return NaviDestinationInfo(
            latitude = info.latitude,
            longitude = info.longitude,
            name = info.name,
            address = info.address,
            poiId = info.poiId,
            poiSubId = info.poiSubId,
            remainDistanceMeters = info.remainDistance,
            remainTimeSeconds = info.remainTime
        )
    }

    private fun toNaviRouteState(info: RouteStateInfo): NaviRouteState {
        val mode = when (info.naviMode) {
            SdkNaviMode.ROUTE_GUIDING -> NaviMode.ROUTE_GUIDING
            SdkNaviMode.SIMULATION_GUIDING -> NaviMode.SIMULATION_GUIDING
            SdkNaviMode.SAFE_GUIDING -> NaviMode.SAFE_GUIDING
            else -> NaviMode.NOT_WORKING
        }
        return NaviRouteState(
            naviMode = mode,
            routeOption = info.routeOption.name,
            tollFare = if (mode.isGuiding()) tollFareForCurrentRoute() else null
        )
    }

    /** 목적지 갱신 + tollFare 를 목적지 좌표에 묶거나(처음) 다른 목적지면 버린다. */
    private fun updateDestination(destination: NaviDestinationInfo?) {
        if (destination != null) {
            val coords = destination.latitude to destination.longitude
            synchronized(tollFareLock) {
                val bound = tollFareDestination
                if (tollFare != null && bound == null) {
                    tollFareDestination = coords
                } else if (bound != null && bound != coords) {
                    tollFare = null
                    tollFareDestination = null
                }
            }
        }
        _currentDestination.value = destination
    }

    private fun tollFareForCurrentRoute(): Int? = synchronized(tollFareLock) {
        val bound = tollFareDestination
        val current = _currentDestination.value
        if (bound != null && current != null && bound != (current.latitude to current.longitude)) null else tollFare
    }

    /** [helper] 가 아직 현재 연결이면 끊은 것으로 표시하고 정리한다. 다음 조회의 ensureConnected 가 새로 연결한다. */
    private fun markDisconnected(helper: NaviHelper, reason: String) {
        if (naviHelper !== helper) return
        Log.w(tag, "NaviHelper 연결 해제로 판단: $reason")
        naviHelper = null
        isNaviConnected = false
        clearTollFare()
        try {
            helper.removeListener(naviListener)
            helper.release()
        } catch (e: Exception) {
            Log.w(tag, "NaviHelper release error: ${e.message}")
        }
    }

    private fun clearTollFare() {
        synchronized(tollFareLock) {
            tollFare = null
            tollFareDestination = null
        }
    }

    private fun NaviMode.isGuiding(): Boolean =
        this == NaviMode.ROUTE_GUIDING || this == NaviMode.SIMULATION_GUIDING

    private inline fun <T> fallback(name: String, mock: () -> T): Result<T> {
        if (!enableMockFallback) {
            return Result.failure(NaviHelperException(NaviHelperException.Reason.UNAVAILABLE, "NaviHelper 서비스 미연결: $name"))
        }
        Log.d(tag, "[MOCK/FALLBACK] $name")
        return Result.success(mock())
    }

    private fun mockRouteState(): NaviRouteState = NaviRouteState(
        naviMode = if (mockDestination != null) NaviMode.ROUTE_GUIDING else NaviMode.SAFE_GUIDING,
        routeOption = mockRouteOption,
        tollFare = null,
        isMock = true
    )

    /** Mock ETA: 현재 Mock 위치에서 직선거리 × 1.3(도로 보정) 을 평균 40km/h 로 간다고 본 추정값. 실측 값이 아니다(isMock = true). */
    private fun mockTrip(latitude: Double, longitude: Double): NaviTripInfo {
        val roadMeters = haversineMeters(mockCurrentLat, mockCurrentLng, latitude, longitude) * MOCK_ROAD_FACTOR
        val etaSeconds = (roadMeters / MOCK_SPEED_METERS_PER_SECOND).toLong()
        return NaviTripInfo(
            distanceMeters = roadMeters.toInt(),
            etaSeconds = etaSeconds,
            routeOption = if (mockDestination != null) mockRouteOption else null,
            isMock = true
        )
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }

    private companion object {
        /** 연결(initialize → onConnected) 대기. 실측 0.5~0.75초 */
        const val CONNECT_TIMEOUT_MS = 5_000L
        /** 연결 실패 후 재시도 대기 */
        const val CONNECT_RETRY_INTERVAL_MS = 30_000L
        /** get*Info 대기. 실측: 목적지 최대 약 1.0초, 경로 상태·위치 20~35ms. 권장 5~10초 */
        const val DESTINATION_TIMEOUT_MS = 5_000L
        const val ROUTE_STATE_TIMEOUT_MS = 5_000L
        const val LOCATION_TIMEOUT_MS = 5_000L
        /** requestTrip 대기. 실측 0.4~0.55초, 문서상 도착 보장 없음("may be delivered") */
        const val TRIP_TIMEOUT_MS = 10_000L

        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MOCK_ROAD_FACTOR = 1.3
        const val MOCK_SPEED_METERS_PER_SECOND = 40_000.0 / 3600.0
    }
}
