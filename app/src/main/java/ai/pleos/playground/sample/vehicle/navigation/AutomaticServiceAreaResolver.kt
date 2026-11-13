package ai.pleos.playground.sample.vehicle.navigation

data class DirectedServiceAreaName(val base: String, val direction: String)

/** Only explicit native direction labels are evidence; a base name or heading is not. */
fun directedServiceAreaName(name: String): DirectedServiceAreaName? {
    val compact = name.replace(Regex("\\s+"), "")
    val match = Regex("(.+?(?:휴게소|졸음쉼터))(?:\\(([^()]+?)(?:방향|방면)\\)|\\[([^\\[\\]]+?)(?:방향|방면)\\]|([^()\\[\\]]+?)(?:방향|방면))").matchEntire(compact) ?: return null
    val direction = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: return null
    return DirectedServiceAreaName(match.groupValues[1], direction)
}

/** Never choose by ETA, distance, uniqueness of a base name, or a guessed road direction. */
object AutomaticServiceAreaResolver {
    fun resolve(description: String, facilities: List<RestPlace>): RestPlace? {
        val target = directedServiceAreaName(description) ?: return null
        val matches = facilities.filter {
            directedServiceAreaName(it.name) == target && RestNavigation.validCoordinates(it.latitude, it.longitude) &&
                it.poiId.isNotBlank() && it.poiId != "0" && it.poiProvider.isNotBlank() &&
                (it.id.startsWith("NAVI_BOOKMARK:") || it.id.startsWith("NAVI_RECENT:"))
        }
        // Favorites and recent destinations may contain the same exact native facility.
        val unique = matches.distinctBy { listOf(it.poiProvider, it.poiId, it.poiSubId, it.latitude, it.longitude) }
        return unique.singleOrNull()?.copy(direction = target.direction)
    }
}
