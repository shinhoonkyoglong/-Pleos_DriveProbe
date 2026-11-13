package ai.pleos.playground.sample.vehicle.navigation

import java.time.LocalDate

data class PublicRestStop(
    val kind: ServiceAreaKind, val baseName: String, val roadNumber: String, val roadName: String,
    val roadType: String, val direction: String, val directionRaw: String,
    val latitude: Double, val longitude: Double, val dataDate: String, val address: String = "",
) {
    val name: String get() = baseName + if (kind == ServiceAreaKind.SLEEP_REST_AREA) "졸음쉼터" else "휴게소"
}

fun interface PublicRestStopLookup {
    suspend fun resolve(description: String, kind: ServiceAreaKind): RestPlace?
}

/** Offline public identity is deliberately separate from a native navigation POI ID. */
class PublicRestStopCatalog(val entries: List<PublicRestStop>) {
    init {
        require(entries.isNotEmpty())
        entries.forEach {
            require(it.baseName.isNotBlank() && it.roadNumber.isNotBlank() && it.roadName.isNotBlank())
            require(RestNavigation.validCoordinates(it.latitude,it.longitude))
            LocalDate.parse(it.dataDate)
        }
    }

    private fun matching(description: String, kind: ServiceAreaKind): Pair<List<PublicRestStop>,String?> {
        val compact = description.replace(Regex("\\s+"), "")
        val pattern = Regex("(.+?(?:휴게소|졸음쉼터))(?:(?:\\(([^()]+)\\)|\\[([^\\[\\]]+)\\])|([^()\\[\\]]+?(?:방향|방면)))?")
        val match = pattern.matchEntire(compact) ?: return emptyList<PublicRestStop>() to null
        val base = match.groupValues[1]
        val direction = match.groupValues.drop(2).firstOrNull { it.isNotBlank() }
            ?.removeSuffix("방향")?.removeSuffix("방면")
        val named = entries.filter { it.kind == kind && it.name.replace(Regex("\\s+"), "") == base }
        return named to direction
    }

    fun candidates(description: String, kind: ServiceAreaKind, today: LocalDate): List<RestPlace> {
        val (named,direction) = matching(description,kind)
        return named.filter { direction == null || it.direction == direction }.map { toPlace(it,today) }
    }

    fun resolve(description: String, kind: ServiceAreaKind, today: LocalDate): RestPlace? {
        val (named,direction) = matching(description,kind)
        // A bare name never proves which carriageway is intended. Explicitly bidirectional data is the exception.
        val selected = if (direction != null) named.filter { it.direction == direction }
            else named.filter { named.size == 1 && it.directionRaw == "양방향" }
        val entry = selected.singleOrNull() ?: return null
        return toPlace(entry,today)
    }

    private fun toPlace(entry: PublicRestStop, today: LocalDate): RestPlace {
        val age = today.toEpochDay() - LocalDate.parse(entry.dataDate).toEpochDay()
        if (age !in 0..366) throw RestNavigationFailure(RestNavigationStatus.STALE_ROUTE_DATA,
            "공식 시설 데이터의 기준일을 갱신해야 해요",source="PUBLIC_REST_STOP_CATALOG")
        return RestPlace("PUBLIC_CATALOG:${entry.kind.name}:${entry.roadNumber}:${entry.name}:${entry.latitude}:${entry.longitude}",
            entry.name + if (entry.direction.isNotBlank()) "(${entry.direction}방향)" else "",
            entry.latitude, entry.longitude, entry.roadType, entry.roadName,
            entry.direction.ifBlank { entry.directionRaw }, entry.address, entry.dataDate,
            kind=entry.kind)
    }
}
