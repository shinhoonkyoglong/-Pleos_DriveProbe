package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.navigation.*
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/** Actual bundled data and coordinate-only ETA query. No guidance, waypoint, voice or vehicle writes. */
class PublicRestStopAssetsTest {
    @Test fun loadsBothOfficialFilesAndQueriesActualEtaWithoutNativePoi() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DriveProbeApplication
        val root=JSONObject(app.assets.open("navigation/public-rest-stops.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals(468,root.getJSONArray("facilities").length())
        val catalog=PublicRestStopAssets(app)
        val rest=checkNotNull(catalog.resolve("용인휴게소(강릉방향)",ServiceAreaKind.SERVICE_AREA))
        val sleep=checkNotNull(catalog.resolve("용인졸음쉼터(강릉방향)",ServiceAreaKind.SLEEP_REST_AREA))
        assertEquals(37.245583,rest.latitude,0.00000001)
        assertEquals(37.28861576,sleep.latitude,0.00000001)
        assertEquals("",rest.poiId);assertEquals("",sleep.poiId)
        val trip=app.realNaviHelperRepository.estimate(rest)
        assertFalse(trip.isMock);assertTrue(trip.distanceMeters>0);assertTrue(trip.etaSeconds>0)
        Log.i("PublicRestStop","officialRows=468 restLatitude=${rest.latitude} sleepLatitude=${sleep.latitude} coordinateOnlyEtaSeconds=${trip.etaSeconds} routeWrites=0")
        Unit
    }
}
