package ai.pleos.playground.sample.vehicle.navigation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Only explicitly reviewed service areas are stored; never persist the user's full favorites list. */
class ReviewedServiceAreaPreferences(context: Context) : ReviewedServiceAreaStore {
    private val preferences=context.applicationContext.getSharedPreferences("reviewed_route_service_areas",Context.MODE_PRIVATE)

    override suspend fun load(): List<ReviewedServiceArea> = withContext(Dispatchers.IO) {
        val text=preferences.getString("records",null) ?: return@withContext emptyList()
        try {
            val root=JSONObject(text)
            require(root.getInt("version") in 1..2)
            val rows=root.getJSONArray("entries")
            require(rows.length()<=32)
            (0 until rows.length()).map { index ->
                val row=rows.getJSONObject(index)
                val p=row.getJSONObject("place")
                val place=RestPlace(p.getString("id"),p.getString("name"),p.getDouble("latitude"),p.getDouble("longitude"),
                    p.optString("roadType","미확인"),p.getString("roadName"),p.getString("direction"),p.getString("address"),p.optString("dataDate",""),
                    p.getString("poiId"),p.getString("poiSubId"),p.getString("poiProvider"),
                    ServiceAreaKind.valueOf(p.optString("kind",ServiceAreaKind.SERVICE_AREA.name)))
                require((place.id.startsWith("NAVI_BOOKMARK:") || place.id.startsWith("PUBLIC_CATALOG:")) && place.name.isNotBlank() &&
                    place.roadName.isNotBlank() && place.direction.isNotBlank() && RestNavigation.validCoordinates(place.latitude,place.longitude))
                val sequence=row.getJSONArray("upcomingNames")
                require(sequence.length() in 1..3)
                ReviewedServiceArea(row.getString("routeId"),row.getDouble("destinationLatitude"),row.getDouble("destinationLongitude"),
                    row.getString("destinationPoiId"),row.getString("routeOption"),
                    (0 until sequence.length()).map { sequence.getString(it) },place,row.getLong("reviewedAtEpochMs"),
                    row.optJSONArray("routePath").strings(),row.optJSONArray("directedUpcomingNames").strings())
            }
        } catch(e: Exception) { throw IOException("휴게소 시설 확인 기록을 읽지 못했어요. 확인 기록을 다시 등록해 주세요",e) }
    }

    override suspend fun save(values: List<ReviewedServiceArea>): Unit = withContext(Dispatchers.IO) {
        val rows=JSONArray()
        values.forEach { value -> rows.put(JSONObject().apply {
            put("routeId",value.routeId);put("destinationLatitude",value.destinationLatitude);put("destinationLongitude",value.destinationLongitude)
            put("destinationPoiId",value.destinationPoiId);put("routeOption",value.routeOption)
            put("upcomingNames",JSONArray(value.upcomingNames));put("reviewedAtEpochMs",value.reviewedAtEpochMs)
            put("routePath",JSONArray(value.routePath));put("directedUpcomingNames",JSONArray(value.directedUpcomingNames))
            put("place",JSONObject().apply {
                val p=value.place
                put("id",p.id);put("name",p.name);put("latitude",p.latitude);put("longitude",p.longitude)
                put("roadName",p.roadName);put("direction",p.direction);put("address",p.address)
                put("roadType",p.roadType)
                put("poiId",p.poiId);put("poiSubId",p.poiSubId);put("poiProvider",p.poiProvider)
                put("kind",p.kind.name)
                put("dataDate",p.dataDate)
            })
        }) }
        val result=JSONObject().apply { put("version",2);put("entries",rows) }
        if (!preferences.edit().putString("records",result.toString()).commit()) throw IOException("휴게소 확인 기록을 저장하지 못했어요")
    }
    private fun JSONArray?.strings(): List<String> = this?.let { rows -> (0 until rows.length()).map { rows.getString(it) } } ?: emptyList()
}
