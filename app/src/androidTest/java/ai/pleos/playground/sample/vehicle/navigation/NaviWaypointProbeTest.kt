package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import android.os.Build
import android.util.Log
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Default: queries only. An explicitly enabled emulator test adds then removes one supplied waypoint. */
@RunWith(AndroidJUnit4::class)
class NaviWaypointProbeTest {
    @Test fun queryOrExplicitWaypointProbe() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DriveProbeApplication
        val navi = app.realNaviHelperRepository
        val tbt=navi.tbtRoute()
        Log.i(TAG,"TBT routeId=${tbt.routeId} entries=${tbt.entries.size} serviceAreaHints=${tbt.serviceAreaHints.size} unclassifiedFacilities=${tbt.unclassifiedServiceFacilities.size} reachesArrival=${tbt.reachesArrival}")
        tbt.entries.forEachIndexed { index,entry -> Log.i(TAG,"TBT[$index] type=${entry.type} code=${entry.typeValue} distanceMeters=${entry.distanceMeters} description=${entry.description}") }
        tbt.nextServiceAreaHint?.let { Log.i(TAG,"NEXT_SERVICE_AREA index=${it.index} name=${it.value.description} rawDistanceMeters=${it.value.distanceMeters} distanceMeaning=NOT_CUMULATIVE coordinatesInTbt=NONE etaInTbt=NONE") }
        val places=navi.serviceAreaPlaces()
        val next=tbt.nextServiceAreaHint?.value?.description?.let(::serviceAreaBaseName)
        val matching=places.filter{serviceAreaBaseName(it.name)==next}
        Log.i(TAG,"SERVICE_AREA_BOOKMARKS received=${navi.lastNativeBookmarkCount} recognized=${places.size} matchingNext=${matching.size}")
        matching.forEach{Log.i(TAG,"MATCHING_SERVICE_AREA name=${it.name} latitude=${it.latitude} longitude=${it.longitude} poiId=${it.poiId} poiProvider=${it.poiProvider}")}
        if (args.getString("writeWaypoint") != "true") return@runBlocking
        val before = navi.snapshot()
        val location = navi.location()
        Log.i(TAG, "QUERY before=$before location=$location")
        assertFalse(location.isMock)
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
        val vehicle = app.vehicleRepository
        withTimeout(10_000) {
            while (vehicle.gear.value == GearPosition.UNKNOWN || !vehicle.hasSpeedSignal.value || vehicle.uxRestriction.value == null) delay(100)
        }
        fun requireParked() {
            check(vehicle.hasSpeedSignal.value && vehicle.gear.value == GearPosition.PARK && vehicle.vehicleSpeed.value == 0f)
            check(vehicle.uxRestriction.value?.let { it.source == UxRestrictionSource.CAR_SERVICE && !it.requiresDistractionOptimization } == true)
        }
        requireParked()
        check(!app.interventions.controller.isBusy && !app.interventions.voiceTests.state.value.running)
        RestNavigation.validateSnapshot(before)
        val lat = args.getString("latitude")?.toDoubleOrNull() ?: error("latitude required")
        val lng = args.getString("longitude")?.toDoubleOrNull() ?: error("longitude required")
        check(RestNavigation.validCoordinates(lat, lng))
        val name = args.getString("placeNameBase64")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
            ?: "명시적 SDK 경유지 시험"
        val place = RestPlace("explicit-sdk-probe", name, lat, lng, "", "", "", "", "")
        val trip = navi.estimate(place)
        // Protocol probe only: point-to-point ETA is not evidence this point is on the route.
        val candidate = RestCandidate(place, trip, trip.distanceMeters)
        assertFalse(candidate.trip.isMock)
        val enabledBefore = app.interventions.controller.status.value.enabled
        var dispatched = false
        app.interventions.controller.setEnabled(false)
        try {
            val after = navi.addFirstWaypoint(candidate, before) { requireParked(); dispatched = true }
            Log.i(TAG, "WRITE_CONFIRMED after=$after")
            assertTrue(RestNavigation.sameDestination(before.destination, after.destination))
            assertTrue(RestNavigation.waypointMatches(place, after.waypoints.firstOrNull()))
            assertEquals(before.state.routeOption, after.state.routeOption)
        } finally {
            try {
                if (dispatched) withContext(NonCancellable) {
                    requireParked()
                    val restored = navi.removeProbeWaypoint(place, before)
                    Log.i(TAG, "CLEANUP_CONFIRMED restored=$restored")
                }
            } finally { app.interventions.controller.setEnabled(enabledBefore) }
        }
    }

    companion object { const val TAG = "NaviWaypointProbe" }
}
