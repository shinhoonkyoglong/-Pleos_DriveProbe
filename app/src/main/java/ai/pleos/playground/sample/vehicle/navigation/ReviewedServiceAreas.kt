package ai.pleos.playground.sample.vehicle.navigation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Drop only a direction suffix, retaining the full facility name. No fuzzy/nearest-name match. */
fun serviceAreaBaseName(name: String): String = recognizedServiceAreaBaseName(name)
    ?: name.replace(Regex("\\s+"), "").trim()

data class ReviewedServiceArea(
    val routeId: String, val destinationLatitude: Double, val destinationLongitude: Double,
    val destinationPoiId: String, val routeOption: String, val upcomingNames: List<String>,
    val place: RestPlace, val reviewedAtEpochMs: Long,
)

interface ReviewedServiceAreaStore {
    suspend fun load(): List<ReviewedServiceArea>
    suspend fun save(values: List<ReviewedServiceArea>)
}

data class ServiceAreaReviewDiagnostics(val storedCount: Int,val matchingCount: Int,val reasons: Map<String,Int>) {
    val code: String get() = when {
        storedCount==0 -> "REVIEW_NOT_REGISTERED"
        matchingCount>1 -> "REVIEW_DUPLICATE"
        matchingCount==1 -> "REVIEW_MATCHED"
        else -> "REVIEW_CONTEXT_MISMATCH"
    }
    fun summary(): String = "$code stored=$storedCount matching=$matchingCount reasons=" +
        reasons.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "NONE" }
}

/** Prototype approval is tied to THIS route, destination and upcoming facility sequence. */
class ReviewedServiceAreas(private val store: ReviewedServiceAreaStore, private val clock: () -> Long) {
    private val mutex = Mutex()
    private fun sequence(tbt: NaviTbtRoute) = tbt.serviceAreaHints.take(3).map { serviceAreaBaseName(it.value.description) }
    private fun mismatches(value: ReviewedServiceArea, route: RestNaviSnapshot, tbt: NaviTbtRoute, now: Long): List<String> {
        val reasons=mutableListOf<String>()
        val age=now-value.reviewedAtEpochMs
        if(age<0) reasons+="CLOCK_REVERSED" else if(age>MAX_AGE_MS) reasons+="EXPIRED"
        if(value.routeId!=route.routeId || tbt.routeId!=route.routeId) reasons+="ROUTE_ID"
        if(value.routeOption!=route.state.routeOption) reasons+="ROUTE_OPTION"
        val destination=route.destination
        if(destination==null || value.destinationPoiId!=destination.poiId ||
            !(RestNavigation.distanceMeters(value.destinationLatitude,value.destinationLongitude,destination.latitude,destination.longitude)<=20)) reasons+="DESTINATION"
        if(value.upcomingNames!=sequence(tbt)) reasons+="UPCOMING_SEQUENCE"
        return reasons
    }

    suspend fun diagnose(route: RestNaviSnapshot,tbt: NaviTbtRoute): ServiceAreaReviewDiagnostics = mutex.withLock {
        val values=store.load();val now=clock()
        val checks=values.map { mismatches(it,route,tbt,now) }
        ServiceAreaReviewDiagnostics(values.size,checks.count { it.isEmpty() },checks.flatten().groupingBy { it }.eachCount())
    }

    suspend fun resolve(route: RestNaviSnapshot, tbt: NaviTbtRoute): RestPlace = mutex.withLock {
        val values=store.load();val now=clock()
        val checks=values.map { mismatches(it,route,tbt,now) }
        val eligible=values.filterIndexed { index,_ -> checks[index].isEmpty() }
        if (eligible.size != 1) {
            val report=ServiceAreaReviewDiagnostics(values.size,eligible.size,checks.flatten().groupingBy { it }.eachCount())
            val message=when(report.code) {
                "REVIEW_NOT_REGISTERED" -> "시설 확인 저장 기록이 없어요. 내비 조회에서 시설을 선택하고 노선·방향 확인을 저장해 주세요"
                "REVIEW_DUPLICATE" -> "현재 경로와 일치하는 확인 기록이 중복됐어요. 이 경로의 확인을 해제하고 다시 저장해 주세요"
                else -> "저장된 시설 확인이 현재 경로·목적지·휴게소 순서·유효 시간과 일치하지 않아요. 현재 경로에서 다시 확인해 주세요"
            }
            throw RestNavigationFailure(RestNavigationStatus.SERVICE_AREA_DETAILS_UNAVAILABLE,message,
                source="PLEOS_TBT_REVIEW_REQUIRED",diagnosticCode=report.summary())
        }
        eligible.single().place
    }

    suspend fun confirm(route: RestNaviSnapshot,tbt: NaviTbtRoute,place: RestPlace,roadName: String,direction: String,confirmed: Boolean) = mutex.withLock {
        RestNavigation.validateSnapshot(route)
        require(confirmed) { "현재 경로의 노선·진행 방향과 시설을 확인해야 해요" }
        require(route.routeId.isNotBlank() && route.routeId == tbt.routeId && tbt.reachesArrival)
        val hint = requireNotNull(tbt.nextServiceAreaHint) { "다음 휴게소를 확인하지 못했어요" }
        require(serviceAreaBaseName(hint.value.description) == serviceAreaBaseName(place.name)) { "제안 대상과 다른 휴게소예요" }
        require(place.id.startsWith("NAVI_BOOKMARK:") && RestNavigation.validCoordinates(place.latitude,place.longitude))
        require(roadName.isNotBlank() && direction.isNotBlank()) { "확인한 노선과 방향을 입력해 주세요" }
        val destination = route.destination!!
        val review = ReviewedServiceArea(route.routeId,destination.latitude,destination.longitude,destination.poiId,
            route.state.routeOption,sequence(tbt),place.copy(roadName=roadName.trim(),direction=direction.trim()),clock())
        val others = store.load().filterNot { it.routeId == route.routeId && it.upcomingNames == review.upcomingNames }
        store.save((others + review).takeLast(32))
    }

    suspend fun forget(routeId: String) = mutex.withLock { store.save(store.load().filterNot { it.routeId == routeId }) }

    companion object { const val MAX_AGE_MS = 24 * 60 * 60 * 1000L }
}

data class ServiceAreaReviewSession(
    val route: RestNaviSnapshot,val tbt: NaviTbtRoute,val places: List<RestPlace>,
    val receivedBookmarkCount: Int? = null,val recognizedServiceAreaCount: Int = 0,
    val recognizedServiceAreaNames: List<String> = emptyList(),
)

/** Parked UI coordinator; no route writes. Rechecks both native POI identity and route at confirmation. */
class ServiceAreaReview(
    private val port: RestNavigationPort, private val reviewed: ReviewedServiceAreas,
    private val allowed: () -> Boolean = { true },
) {
    suspend fun load(): ServiceAreaReviewSession {
        check(allowed()) { "P·속도 0·화면 사용 가능 상태에서 시설을 확인해 주세요" }
        val route=port.snapshot()
        RestNavigation.validateSnapshot(route)
        val tbt=port.tbtRoute()
        require(tbt.routeId == route.routeId && tbt.reachesArrival) { "현재 경로의 도착까지 안내 목록을 확인해 주세요" }
        val hint=requireNotNull(tbt.nextServiceAreaHint) { "다음 휴게소 후보가 없어요" }
        val saved=port.serviceAreaPlaces()
        val places=saved.filter { serviceAreaBaseName(it.name) == serviceAreaBaseName(hint.value.description) }
        return ServiceAreaReviewSession(route,tbt,places,port.lastNativeBookmarkCount,saved.size,saved.map { it.name }.distinct())
    }

    suspend fun confirm(session: ServiceAreaReviewSession,place: RestPlace,road: String,direction: String,confirmed: Boolean) {
        check(allowed()) { "정차 상태에서만 시설 확인을 저장할 수 있어요" }
        require(session.places.contains(place))
        val fresh=load()
        if (fresh.route.routeId != session.route.routeId || fresh.route.state.routeOption != session.route.state.routeOption ||
            !RestNavigation.sameDestination(fresh.route.destination,session.route.destination) ||
            !RestNavigation.sameWaypoints(fresh.route.waypoints,session.route.waypoints) ||
            fresh.tbt.serviceAreaHints.take(3).map { it.value.description } != session.tbt.serviceAreaHints.take(3).map { it.value.description })
            throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED,"시설 확인 중 경로가 바뀌었어요. 다시 확인해 주세요")
        require(fresh.places.contains(place)) { "내비에 저장된 시설 정보가 바뀌었어요. 다시 조회해 주세요" }
        check(allowed()) { "정차 상태를 벗어나 시설 확인을 저장하지 않았어요" }
        reviewed.confirm(fresh.route,fresh.tbt,place,road,direction,confirmed)
    }

    suspend fun forget(session: ServiceAreaReviewSession) {
        check(allowed()) { "정차 상태에서 확인 기록을 해제해 주세요" }
        reviewed.forget(session.route.routeId)
    }
}
