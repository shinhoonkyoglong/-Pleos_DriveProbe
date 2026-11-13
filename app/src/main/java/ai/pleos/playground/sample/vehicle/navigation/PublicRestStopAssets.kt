package ai.pleos.playground.sample.vehicle.navigation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class PublicRestStopAssets(context: Context) : PublicRestStopLookup {
    private val assets = context.applicationContext.assets
    private val catalog: PublicRestStopCatalog by lazy {
        val root = JSONObject(assets.open("navigation/public-rest-stops.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(root.getInt("schemaVersion") == 1)
        val rows = root.getJSONArray("facilities")
        PublicRestStopCatalog((0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            PublicRestStop(ServiceAreaKind.valueOf(row.getString("kind")),row.getString("baseName"),
                row.getString("roadNumber"),row.getString("roadName"),row.getString("roadType"),
                row.getString("direction"),row.getString("directionRaw"),row.getDouble("latitude"),
                row.getDouble("longitude"),row.getString("dataDate"),row.getString("address"))
        })
    }
    override suspend fun resolve(description: String, kind: ServiceAreaKind): RestPlace? = withContext(Dispatchers.IO) {
        catalog.resolve(description,kind,LocalDate.now(ZoneId.of("Asia/Seoul")))
    }
    suspend fun candidates(tbt: NaviTbtRoute): List<RestPlace> = withContext(Dispatchers.IO) {
        val hint = tbt.nextRestStopHint ?: return@withContext emptyList()
        val kind = if(hint.value.facilityKind() == TbtFacilityKind.SLEEP_REST_AREA) ServiceAreaKind.SLEEP_REST_AREA else ServiceAreaKind.SERVICE_AREA
        catalog.candidates(hint.value.description,kind,LocalDate.now(ZoneId.of("Asia/Seoul")))
    }
}
