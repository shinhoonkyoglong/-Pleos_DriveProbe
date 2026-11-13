package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.*

/** Actual service-area identity. Catalog ids are not Navi POI ids. */
data class RestPlace(
    val id: String, val name: String, val latitude: Double, val longitude: Double,
    val roadType: String, val roadName: String, val direction: String,
    val address: String, val dataDate: String,
    val poiId: String = "", val poiSubId: String = "", val poiProvider: String = "",
    val kind: ServiceAreaKind = ServiceAreaKind.SERVICE_AREA,
)
data class NaviWaypoint(
    val latitude: Double, val longitude: Double, val name: String,
    val poiId: String = "", val poiSubId: String = "", val poiProvider: String = "",
    val displayLatitude: Double? = null, val displayLongitude: Double? = null,
)
data class RestNaviSnapshot(
    val destination: NaviDestinationInfo?, val state: NaviRouteState,
    val waypoints: List<NaviWaypoint>, val routeId: String = "",
)
enum class ServiceAreaKind { SERVICE_AREA, SLEEP_REST_AREA, OTHER }
data class RouteServiceArea(
    val place: RestPlace, val distanceAlongRouteMeters: Int?, val etaSeconds: Long,
    val onCurrentRoute: Boolean, val sameDirection: Boolean,
    val kind: ServiceAreaKind = ServiceAreaKind.SERVICE_AREA,
    val routeOrder: Int? = null, val tripInfo: NaviTripInfo? = null,
    val verification: String = "SOURCE_ROUTE_MATCH",
)
/** A complete, current-route result is necessary to identify the NEXT service area. */
data class AlongRouteServiceAreas(
    val routeId: String, val observedAtMs: Long, val complete: Boolean,
    val areas: List<RouteServiceArea>, val source: String, val isMock: Boolean = false,
    val routePath: List<String> = emptyList(),
)
fun interface RouteServiceAreaSource {
    suspend fun query(route: RestNaviSnapshot, location: NaviLocation): AlongRouteServiceAreas
}
/** Used when even a route-bound discovery source is unavailable. */
class UnavailableRouteServiceAreaSource : RouteServiceAreaSource {
    override suspend fun query(route: RestNaviSnapshot, location: NaviLocation): AlongRouteServiceAreas =
        throw RestNavigationFailure(RestNavigationStatus.ROUTE_DATA_UNAVAILABLE,
            "현재 경로의 다음 휴게소를 확인할 검색 서비스가 아직 연결되지 않았어요")
}
data class RestCandidate(
    val place: RestPlace, val trip: NaviTripInfo, val distanceAlongRouteMeters: Int?,
    val routeOrder: Int? = null, val verification: String = "SOURCE_ROUTE_MATCH",
)
data class RestProposal(
    val id: String, val candidate: RestCandidate, val before: RestNaviSnapshot,
    val createdAtMs: Long, val expiresAtMs: Long, val observedAtMs: Long, val source: String,
    val routePath: List<String> = emptyList(),
) {
    val prompt: String get() = "휴식이 필요해 보여요. 현재 경로의 다음 휴게시설은 ${candidate.place.name}예요. " +
        "내비 예상 소요 시간은 약 ${(candidate.trip.etaSeconds - 1) / 60 + 1}분이에요. 경유지로 추가할까요? 원하시면 추가해 줘, 원치 않으시면 아니요라고 말씀해 주세요."
}
enum class RestNavigationStatus {
    VERIFIED_WAYPOINT, WAYPOINT_OBSERVED, PROPOSED, NOT_REQUESTED, ROUTE_DATA_UNAVAILABLE, ROUTE_CONTEXT_UNAVAILABLE, SERVICE_AREA_DETAILS_UNAVAILABLE,
    STALE_ROUTE_DATA, INCOMPLETE_ROUTE_DATA, PROPOSAL_EXPIRED, PROPOSAL_INVALID,
    LOCATION_UNAVAILABLE, MOCK_REJECTED, NO_DESTINATION, NOT_GUIDING, EXISTING_WAYPOINTS,
    NO_CANDIDATE, BUSY, CONTEXT_CHANGED, PERMISSION_DENIED, UNAVAILABLE, QUERY_TIMEOUT,
    REQUEST_FAILED, REQUEST_UNCONFIRMED, CANCELLED_BEFORE_REQUEST, CANCELLED_AFTER_REQUEST_UNCONFIRMED,
}
data class RestNavigationResult(
    val status: RestNavigationStatus, val message: String,
    val candidate: RestCandidate? = null, val before: RestNaviSnapshot? = null,
    val after: RestNaviSnapshot? = null, val requestIssued: Boolean = false,
    val source: String = "UNCONNECTED_ROUTE_SERVICE_AREA_SOURCE", val error: String? = null,
    val proposal: RestProposal? = null,
)
class RestNavigationCancelled(val navigation: RestNavigationResult) : CancellationException(navigation.message)
class RestNavigationFailure(
    val status: RestNavigationStatus, message: String, val after: RestNaviSnapshot? = null,
    val source: String = "UNCONNECTED_ROUTE_SERVICE_AREA_SOURCE",
    val diagnosticCode: String? = null,
) : Exception(message)
interface RestNavigationPort {
    suspend fun snapshot(): RestNaviSnapshot
    suspend fun location(): NaviLocation
    /** Actual facility ETA query. Point-to-point ETA does not prove current-route membership. */
    suspend fun estimate(place: RestPlace): NaviTripInfo
    suspend fun serviceAreaPlaces(): List<RestPlace> = emptyList()
    /** Real native facilities available without a new search-provider key. */
    suspend fun automaticServiceAreaPlaces(): List<RestPlace> = serviceAreaPlaces()
    val lastNativeBookmarkCount: Int? get() = null
    suspend fun tbtRoute(): NaviTbtRoute = throw RestNavigationFailure(RestNavigationStatus.UNAVAILABLE,"내비 안내 목록 조회를 지원하지 않아요")
    suspend fun addFirstWaypoint(candidate: RestCandidate, expected: RestNaviSnapshot, dispatched: () -> Unit): RestNaviSnapshot
}

class RestNavigation(
    private val port: RestNavigationPort,
    private val source: RouteServiceAreaSource,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val proposalLock = Any()
    private var activeProposal: RestProposal? = null

    /** Diagnostic path; TBT alone has no coordinates/POI id/ETA, so it cannot mint a writable proposal. */
    suspend fun readTbtRoute(): NaviTbtRoute = port.tbtRoute()

    /** Read-only preview cannot mint/replace a consent token. */
    suspend fun preview(): List<RestCandidate> = stableRouteRead {
        val before = port.snapshot()
        validateSnapshot(before)
        candidates(before).first
    }

    suspend fun prepareProposal(): RestProposal = mutex.withLock {
        synchronized(proposalLock) { activeProposal = null }
        stableRouteRead {
            val before = port.snapshot()
            validateSnapshot(before)
            val (candidates, batch) = candidates(before)
            currentCoroutineContext().ensureActive()
            val latest = port.snapshot()
            if (latest.routeId != before.routeId || !sameDestination(before.destination,latest.destination) ||
                latest.state.naviMode != before.state.naviMode || latest.state.routeOption != before.state.routeOption ||
                !sameWaypoints(before.waypoints,latest.waypoints))
                throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED,"휴게소 조회 중 경로가 바뀌어 제안하지 않았어요")
            val now = clock()
            if (now < batch.observedAtMs || now - batch.observedAtMs > SOURCE_MAX_AGE_MS)
                throw RestNavigationFailure(RestNavigationStatus.STALE_ROUTE_DATA,"경로 확인 중 휴게소 정보의 유효 시간이 지났어요")
            RestProposal(UUID.randomUUID().toString(), candidates.first(), before, now,
                minOf(now + PROPOSAL_TTL_MS, batch.observedAtMs + PROPOSAL_TTL_MS), batch.observedAtMs, batch.source, batch.routePath)
                .also { synchronized(proposalLock) { activeProposal = it } }
        }
    }

    /** Read-only retry for a periodic reroute crossing the snapshot/TBT boundary. Never retry writes. */
    private suspend fun <T> stableRouteRead(read: suspend () -> T): T {
        repeat(3) { attempt ->
            try { return read() } catch(e: RestNavigationFailure) {
                if(e.status!=RestNavigationStatus.CONTEXT_CHANGED || attempt==2) throw e
                delay(200)
            }
        }
        error("Unreachable")
    }

    fun discard(proposal: RestProposal) {
        synchronized(proposalLock) { if (activeProposal?.id == proposal.id) activeProposal = null }
    }

    private suspend fun candidates(before: RestNaviSnapshot): Pair<List<RestCandidate>, AlongRouteServiceAreas> {
        if (before.routeId.isBlank()) throw RestNavigationFailure(RestNavigationStatus.ROUTE_CONTEXT_UNAVAILABLE, "내비의 현재 경로 식별자를 확인하지 못했어요")
        val location = port.location()
        if (location.isMock) throw RestNavigationFailure(RestNavigationStatus.MOCK_REJECTED, "모의 위치로 휴게소를 안내하지 않아요")
        if (!validCoordinates(location.latitude, location.longitude))
            throw RestNavigationFailure(RestNavigationStatus.LOCATION_UNAVAILABLE, "현재 내비 위치를 확인하지 못했어요")
        val batch = source.query(before, location)
        if (batch.isMock) throw RestNavigationFailure(RestNavigationStatus.MOCK_REJECTED, "모의 경로 정보로 휴게소를 안내하지 않아요")
        if (batch.routeId != before.routeId) throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED, "휴게소 조회 결과가 현재 안내 경로와 달라요")
        val age = clock() - batch.observedAtMs
        if (age < 0 || age > SOURCE_MAX_AGE_MS) throw RestNavigationFailure(RestNavigationStatus.STALE_ROUTE_DATA, "현재 경로의 최신 휴게소 정보를 확인하지 못했어요")
        if (!batch.complete || batch.source.isBlank()) throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA, "경로상 다음 휴게소를 판단할 정보가 부족해요")
        val eligible = batch.areas.filter { it.kind in setOf(ServiceAreaKind.SERVICE_AREA,ServiceAreaKind.SLEEP_REST_AREA) && it.onCurrentRoute && it.sameDirection &&
            (it.distanceAlongRouteMeters == null || it.distanceAlongRouteMeters > 0) &&
            (it.routeOrder?.let { order -> order >= 0 } ?: (it.distanceAlongRouteMeters != null)) }
        // Never skip an earlier malformed service area and claim a later one is the next.
        if (eligible.any { it.place.id.isBlank() || it.place.name.isBlank() ||
                !validCoordinates(it.place.latitude,it.place.longitude) || it.etaSeconds <= 0 })
            throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA, "경로상 휴게소의 이름·좌표·도착 시간을 확인하지 못했어요")
        if (eligible.any { it.routeOrder != null } && eligible.any { it.routeOrder == null })
            throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA,"휴게소 순서 정보의 기준이 일치하지 않아요")
        val ordered = eligible.sortedWith(compareBy<RouteServiceArea> { it.routeOrder ?: it.distanceAlongRouteMeters!! }.thenBy { it.place.id })
            .map { area ->
                val trip = area.tripInfo ?: NaviTripInfo(area.distanceAlongRouteMeters!!,area.etaSeconds,before.state.routeOption)
                if (trip.isMock) throw RestNavigationFailure(RestNavigationStatus.MOCK_REJECTED,"모의 도착 시간으로 휴게소를 제안하지 않아요")
                if (trip.distanceMeters <= 0 || trip.etaSeconds <= 0 || trip.etaSeconds != area.etaSeconds)
                    throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA,"휴게소의 내비 거리·시간을 확인하지 못했어요")
                RestCandidate(area.place,trip,area.distanceAlongRouteMeters,area.routeOrder,area.verification)
            }
        if (ordered.isEmpty()) throw RestNavigationFailure(RestNavigationStatus.NO_CANDIDATE, "현재 경로의 진행 방향에 남아 있는 휴게소를 찾지 못했어요")
        return ordered to batch
    }

    /** Applies exactly what the driver heard; never selects a replacement after consent. */
    suspend fun accept(proposal: RestProposal): RestNavigationResult = acceptChecked(proposal) {}

    /** Explicit diagnostic only; require parked conditions again at the final Binder boundary. */
    suspend fun acceptForProbe(proposal: RestProposal,beforeDispatch: () -> Unit): RestNavigationResult =
        acceptChecked(proposal,beforeDispatch)

    private suspend fun acceptChecked(proposal: RestProposal,beforeDispatch: () -> Unit): RestNavigationResult {
        if (!mutex.tryLock()) return result(proposal,RestNavigationStatus.BUSY,"다른 휴게소 안내를 처리 중이에요")
        var dispatched = false
        try {
            val authentic = synchronized(proposalLock) { (activeProposal == proposal).also { if (it) activeProposal = null } }
            if (!authentic) return result(proposal,RestNavigationStatus.PROPOSAL_INVALID,"이미 종료됐거나 유효하지 않은 휴게소 제안이에요")
            val now = clock()
            if (now < proposal.createdAtMs || now >= proposal.expiresAtMs)
                return result(proposal,RestNavigationStatus.PROPOSAL_EXPIRED,"휴게소 제안 시간이 지나 경로를 변경하지 않았어요. 새 제안이 필요해요")
            currentCoroutineContext().ensureActive()
            val (fresh, remaining, refreshed) = stableRouteRead {
                val snapshot=port.snapshot()
                validateSnapshot(snapshot)
                val (places,batch)=candidates(snapshot)
                Triple(snapshot,places,batch)
            }
            if ((fresh.routeId != proposal.before.routeId && !sameRemainingRoutePath(proposal.routePath,refreshed.routePath)) ||
                !sameDestination(proposal.before.destination,fresh.destination) ||
                fresh.state.routeOption != proposal.before.state.routeOption || !sameWaypoints(proposal.before.waypoints,fresh.waypoints))
                return result(proposal,RestNavigationStatus.CONTEXT_CHANGED,"제안 후 경로가 바뀌어 휴게소를 추가하지 않았어요. 새 동의가 필요해요")
            if (remaining.first().place != proposal.candidate.place ||
                distanceMeters(remaining.first().place.latitude,remaining.first().place.longitude,
                    proposal.candidate.place.latitude,proposal.candidate.place.longitude) > 20.0)
                return result(proposal,RestNavigationStatus.CONTEXT_CHANGED,"제안한 휴게소가 현재 경로의 다음 휴게소가 아니에요. 새 제안과 동의가 필요해요")
            if (clock() >= proposal.expiresAtMs) return result(proposal,RestNavigationStatus.PROPOSAL_EXPIRED,"휴게소 제안 시간이 지났어요")
            currentCoroutineContext().ensureActive()
            val after = port.addFirstWaypoint(proposal.candidate,fresh) {
                beforeDispatch()
                if (clock() >= proposal.expiresAtMs) throw RestNavigationFailure(RestNavigationStatus.PROPOSAL_EXPIRED,"경로 확인 중 제안 시간이 지났어요")
                val age = clock() - refreshed.observedAtMs
                if (age < 0 || age > SOURCE_MAX_AGE_MS) throw RestNavigationFailure(RestNavigationStatus.STALE_ROUTE_DATA,"경로 확인 중 휴게소 정보의 유효 시간이 지났어요")
                dispatched = true
            }
            val status=waypointApplicationStatus(fresh,proposal.candidate.place,after)
            return result(proposal,status,when(status) {
                RestNavigationStatus.VERIFIED_WAYPOINT -> "${proposal.candidate.place.name} 경유지를 확인했어요. 원래 목적지를 유지했어요"
                RestNavigationStatus.WAYPOINT_OBSERVED -> "${proposal.candidate.place.name} 경유 항목 생성을 확인했어요. 내비가 좌표를 제공하지 않아 위치는 내비에서 확인해 주세요"
                else -> "휴게소 경유지를 요청했지만 적용 여부를 확인하지 못했어요. 내비에서 확인해 주세요"
            },dispatched,after)
        } catch (e: CancellationException) {
            throw RestNavigationCancelled(result(proposal,
                if(dispatched) RestNavigationStatus.CANCELLED_AFTER_REQUEST_UNCONFIRMED else RestNavigationStatus.CANCELLED_BEFORE_REQUEST,
                if(dispatched) "휴게소 요청 뒤 취소됐어요. 내비에 적용됐을 수 있어요" else "휴게소 안내가 취소됐어요. 경로 변경 요청은 보내지 않았어요",dispatched)).apply { initCause(e) }
        } catch (e: Exception) {
            return failure(e,dispatched).copy(candidate=proposal.candidate,before=proposal.before,source=proposal.source,proposal=proposal)
        } finally { mutex.unlock() }
    }

    private fun result(p:RestProposal,status:RestNavigationStatus,message:String,issued:Boolean=false,after:RestNaviSnapshot?=null) =
        RestNavigationResult(status,message,p.candidate,p.before,after,issued,p.source,proposal=p)

    companion object {
        const val SOURCE_MAX_AGE_MS = 30_000L
        const val PROPOSAL_TTL_MS = 60_000L
        fun failure(e:Exception,issued:Boolean=false):RestNavigationResult {
            val status=(e as? RestNavigationFailure)?.status ?: when {
                e is SecurityException || (e is NaviHelperException && e.reason == NaviHelperException.Reason.PERMISSION_DENIED) -> RestNavigationStatus.PERMISSION_DENIED
                issued -> RestNavigationStatus.REQUEST_UNCONFIRMED
                e is NaviHelperException && e.reason == NaviHelperException.Reason.TIMEOUT -> RestNavigationStatus.QUERY_TIMEOUT
                else -> RestNavigationStatus.UNAVAILABLE
            }
            return RestNavigationResult(status,e.message ?: "휴게소 정보를 확인하지 못했어요",after=(e as? RestNavigationFailure)?.after,
                requestIssued=issued,error=(e as? RestNavigationFailure)?.diagnosticCode ?: e.javaClass.simpleName,
                source=(e as? RestNavigationFailure)?.source ?: "UNCONNECTED_ROUTE_SERVICE_AREA_SOURCE")
        }
        fun validateSnapshot(snapshot:RestNaviSnapshot) {
            if(snapshot.state.isMock || snapshot.destination?.isMock==true) throw RestNavigationFailure(RestNavigationStatus.MOCK_REJECTED,"모의 내비 상태로 경로를 바꾸지 않아요")
            val destination=snapshot.destination
            if(destination==null || !validCoordinates(destination.latitude,destination.longitude)) throw RestNavigationFailure(RestNavigationStatus.NO_DESTINATION,"내비의 원래 목적지가 없거나 전환 중이에요")
            if(snapshot.state.naviMode!=NaviMode.ROUTE_GUIDING) throw RestNavigationFailure(RestNavigationStatus.NOT_GUIDING,"실제 경로 안내 중에만 휴게소를 제안해요")
            if(snapshot.waypoints.isNotEmpty()) throw RestNavigationFailure(RestNavigationStatus.EXISTING_WAYPOINTS,"기존 경유지를 보존하기 위해 휴게소를 추가하지 않았어요")
        }
        fun validCoordinates(lat:Double,lng:Double)=lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat==0.0 && lng==0.0)
        fun sameDestination(a:NaviDestinationInfo?,b:NaviDestinationInfo?)=a!=null && b!=null && !a.isMock && !b.isMock && distanceMeters(a.latitude,a.longitude,b.latitude,b.longitude)<=20.0 && a.poiId==b.poiId
        fun sameWaypoints(a:List<NaviWaypoint>,b:List<NaviWaypoint>)=a.size==b.size && a.zip(b).all { (x,y)->distanceMeters(x.latitude,x.longitude,y.latitude,y.longitude)<=20.0 }
        fun waypointMatches(place:RestPlace,waypoint:NaviWaypoint?):Boolean {
            if(waypoint==null) return false
            if(validCoordinates(waypoint.latitude,waypoint.longitude))
                return distanceMeters(place.latitude,place.longitude,waypoint.latitude,waypoint.longitude)<=50.0
            // The native service can omit a waypoint's routing coordinates. Never treat (0,0) as a location.
            if(waypoint.latitude!=0.0 || waypoint.longitude!=0.0) return false
            val exactPoi=place.poiId.isNotBlank() && place.poiId!="0" && place.poiId==waypoint.poiId &&
                place.poiSubId==waypoint.poiSubId && place.poiProvider==waypoint.poiProvider
            if(!exactPoi) return false
            val lat=waypoint.displayLatitude; val lng=waypoint.displayLongitude
            if(lat!=null && lng!=null && validCoordinates(lat,lng))
                return distanceMeters(place.latitude,place.longitude,lat,lng)<=50.0
            if((lat!=null && lat!=0.0) || (lng!=null && lng!=0.0)) return false
            // Missing coordinates require both exact native identity and the original full facility name.
            return place.name.trim()==waypoint.name.trim()
        }
        fun distanceMeters(a:Double,b:Double,c:Double,d:Double):Double {
            val lat=Math.toRadians(c-a);val lng=Math.toRadians(d-b)
            val h=sin(lat/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(lng/2).pow(2)
            return 6_371_000.0*2*asin(sqrt(h.coerceIn(0.0,1.0)))
        }
    }
}
