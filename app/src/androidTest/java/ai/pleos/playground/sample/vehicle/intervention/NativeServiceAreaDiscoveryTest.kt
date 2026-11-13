package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.navigation.RestNavigation
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Read-only native acquisition. Does not alter favorites, destination, waypoints or voice state. */
class NativeServiceAreaDiscoveryTest {
    @Test fun readsActualNativeFacilitiesWithoutExternalApiKey() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DriveProbeApplication
        val places=app.realNaviHelperRepository.automaticServiceAreaPlaces()
        assertTrue(places.all { it.id.startsWith("NAVI_BOOKMARK:") || it.id.startsWith("NAVI_RECENT:") })
        assertTrue(places.all { RestNavigation.validCoordinates(it.latitude,it.longitude) })
        Log.i("AutoFacility","nativeFavorites=${places.count { it.id.startsWith("NAVI_BOOKMARK:") }} nativeRecents=${places.count { it.id.startsWith("NAVI_RECENT:") }}")
        Unit
    }
}
