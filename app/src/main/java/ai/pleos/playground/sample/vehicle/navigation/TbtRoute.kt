package ai.pleos.playground.sample.vehicle.navigation

/** Raw SDK values: distance semantics and list coverage must be checked against the Navi UI. */
data class NaviTbtEntry(val type: String, val typeValue: Long, val distanceMeters: Double, val description: String)

enum class TbtFacilityKind { SERVICE_AREA, NON_REST_FACILITY, SLEEP_REST_AREA, UNKNOWN_SERVICE_FACILITY, MANEUVER }

// Native favorites use both "휴게소(강릉방향)" and "휴게소 강릉방면".
// Match only explicit suffix forms; never use substring/fuzzy matching for the facility identity.
private val serviceAreaNamePattern = Regex("(.+?휴게소)(?:(?:\\([^)]*\\)|\\[[^]]*\\])|(?:[가-힣A-Za-z0-9·]+(?:방향|방면)))?(?:입구|진입|방면)?")

fun recognizedServiceAreaBaseName(name: String): String? =
    serviceAreaNamePattern.matchEntire(name.replace(Regex("\\s+"),"").trim())?.groupValues?.get(1)

/** Code 41 is not sufficient: the emulator also reports Hi-Pass centers with this code. */
fun NaviTbtEntry.facilityKind(): TbtFacilityKind {
    if (type == "TURN_RESTAREA" && typeValue == 99L) return TbtFacilityKind.SLEEP_REST_AREA
    if (type != "TURN_ENTER_SERVICEAREA" || typeValue != 41L) return TbtFacilityKind.MANEUVER
    val name = description.replace(Regex("\\s+"), "").trim()
    if (name.contains("하이패스센터")) return TbtFacilityKind.NON_REST_FACILITY
    return if (recognizedServiceAreaBaseName(name)!=null) TbtFacilityKind.SERVICE_AREA else TbtFacilityKind.UNKNOWN_SERVICE_FACILITY
}

data class NaviTbtRoute(val routeId: String, val capturedAtMs: Long, val entries: List<NaviTbtEntry>) {
    /** Preserve SDK order; never sort segment distances and call the smallest value the next stop. */
    val serviceAreaHints: List<IndexedValue<NaviTbtEntry>> get() = entries.withIndex().filter {
        it.value.facilityKind() == TbtFacilityKind.SERVICE_AREA
    }
    val unclassifiedServiceFacilities: List<IndexedValue<NaviTbtEntry>> get() = entries.withIndex().filter {
        it.value.facilityKind() == TbtFacilityKind.UNKNOWN_SERVICE_FACILITY
    }
    /** Do not silently skip an unknown code-41 facility and claim a later one comes next. */
    val nextServiceAreaHint: IndexedValue<NaviTbtEntry>? get() = serviceAreaHints.firstOrNull()?.takeIf { first ->
        unclassifiedServiceFacilities.none { it.index < first.index }
    }
    val reachesArrival: Boolean get() = entries.lastOrNull()?.let { it.type == "TURN_ARRIVE" && it.typeValue == 37L } == true
}

/** TBT supplies selection order; reviewed native favorites supply facility identity; requestTrip supplies ETA. */
class PleosTbtServiceAreaSource(
    private val port: RestNavigationPort,
    private val reviewed: ReviewedServiceAreas? = null,
) : RouteServiceAreaSource {
    override suspend fun query(route: RestNaviSnapshot, location: ai.pleos.playground.sample.vehicle.data.model.NaviLocation): AlongRouteServiceAreas {
        val tbt = port.tbtRoute()
        if (tbt.routeId != route.routeId)
            throw RestNavigationFailure(RestNavigationStatus.CONTEXT_CHANGED,"내비 안내 목록의 경로가 바뀌었어요",source="PLEOS_TBT")
        if (tbt.nextServiceAreaHint == null && tbt.unclassifiedServiceFacilities.isNotEmpty())
            throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA,"휴게소인지 확인하지 못한 안내 시설이 앞에 있어 다음 휴게소를 확정하지 않았어요",source="PLEOS_TBT")
        val hint = tbt.nextServiceAreaHint
            ?: throw RestNavigationFailure(RestNavigationStatus.NO_CANDIDATE,"내비가 반환한 안내 목록에 휴게소 유형 항목이 없어요. 전체 경로에 휴게소가 없다는 뜻은 아니에요",source="PLEOS_TBT")
        val description = hint.value.description.takeIf { it.isNotBlank() } ?: "휴게소"
        val registry=reviewed ?: throw RestNavigationFailure(RestNavigationStatus.SERVICE_AREA_DETAILS_UNAVAILABLE,
            "내비 안내에서 ${description} 항목을 찾았지만 경유지에 필요한 시설 좌표를 아직 확인하지 못했어요",source="PLEOS_TBT")
        if (!tbt.reachesArrival) throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA,
            "도착까지의 안내 목록을 확인하지 못해 자동 제안을 중단했어요",source="PLEOS_TBT")
        val place=registry.resolve(route,tbt)
        val trip=port.estimate(place)
        if (trip.isMock) throw RestNavigationFailure(RestNavigationStatus.MOCK_REJECTED,"모의 도착 시간은 사용하지 않아요",source="PLEOS_TBT")
        if (trip.distanceMeters<=0 || trip.etaSeconds<=0 || trip.routeOption!=route.state.routeOption)
            throw RestNavigationFailure(RestNavigationStatus.INCOMPLETE_ROUTE_DATA,"휴게소까지의 실제 내비 거리·시간·경로 옵션을 확인하지 못했어요",source="PLEOS_TBT")
        // Point-to-point distance is NOT the distance along the original route. Keep it separate.
        return AlongRouteServiceAreas(route.routeId,tbt.capturedAtMs,true,listOf(RouteServiceArea(place,null,trip.etaSeconds,
            onCurrentRoute=true,sameDirection=true,routeOrder=hint.index,tripInfo=trip,verification="MANUAL_ROUTE_DIRECTION_CONFIRMATION")),
            source="PLEOS_TBT_REVIEWED_NAVI_BOOKMARK")
    }
}
