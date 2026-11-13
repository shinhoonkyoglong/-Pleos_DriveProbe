package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.MainActivity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.intervention.DrivingVoiceService
import ai.pleos.playground.sample.vehicle.intervention.InterventionOutcome
import ai.pleos.playground.sample.vehicle.intervention.VoiceConsent
import ai.pleos.playground.sample.vehicle.intervention.VoicePhase
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit real-device verification. No fake speech/nav ports and no fatigue threshold changes. */
class WaypointE2ETest {
    @Test fun explicitWaypointVerification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("waypointMode").orEmpty()
        assumeTrue("Explicit waypoint mode required", mode in setOf("prepare", "write", "voice", "inspect", "cleanup"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
        val runId = args.getString("probeRunId") ?: error("probeRunId required")
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val app = instrumentation.targetContext.applicationContext as DriveProbeApplication
        val vehicle = app.vehicleRepository
        val navi = app.realNaviHelperRepository
        val report = JSONObject().put("runId", runId).put("mode", mode).put("passed", false)
        val output = File(app.cacheDir, "waypoint-$runId.json")
        fun save() = output.writeText(report.toString(2), Charsets.UTF_8)
        fun log(message: String) { Log.i("WaypointE2E", "runId=$runId $message") }
        fun requireParked() {
            check(vehicle.gear.value == GearPosition.PARK && vehicle.hasSpeedSignal.value && vehicle.vehicleSpeed.value == 0f)
            check(vehicle.uxRestriction.value?.let { it.source == UxRestrictionSource.CAR_SERVICE && !it.requiresDistractionOptimization } == true)
        }
        withTimeout(20_000) { while (!vehicle.hasSpeedSignal.value || vehicle.uxRestriction.value == null) delay(100) }
        requireParked()
        val wasEnabled = app.interventions.controller.status.value.enabled
        val voiceWasReady = DrivingVoiceService.ready.value
        var activity: ActivityScenario<MainActivity>? = null
        var helper: NaviHelper? = null
        var before: RestNaviSnapshot? = null
        var candidate: RestCandidate? = null
        var dispatched = false
        var review: ServiceAreaReviewSession? = null
        var primaryFailure: Throwable? = null
        try {
            withContext(Dispatchers.Main) { app.interventions.controller.setEnabled(false) }
            if (mode == "voice") {
                check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                activity = ActivityScenario.launch(MainActivity::class.java)
                activity.onActivity { DrivingVoiceService.prepare(it) }
                withTimeout(10_000) { DrivingVoiceService.ready.first { it } }
            }
            val initialDestination = navi.getDestinationInfo().getOrThrow()
            report.put("initialDestination", initialDestination?.name ?: JSONObject.NULL)
            if (mode == "prepare" && initialDestination == null) {
                // A real official facility is the fixture destination. This branch never
                // replaces an existing destination or invents a native POI identifier.
                val rows = JSONObject(app.assets.open("navigation/public-rest-stops.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }).getJSONArray("facilities")
                val destination = (0 until rows.length()).map { rows.getJSONObject(it) }
                    .single { it.getString("kind") == "SERVICE_AREA" && it.getString("baseName") == "안성" && it.getString("direction") == "부산" }
                val connected = CompletableDeferred<Unit>()
                helper = NaviHelper(app)
                withContext(Dispatchers.Main) {
                    helper!!.initialize(onConnected = { connected.complete(Unit) }, onFailed = { connected.completeExceptionally(it) })
                }
                withTimeout(10_000) { connected.await() }
                requireParked()
                withContext(Dispatchers.Main) {
                    helper!!.requestRoute(RouteInfo(latitude = destination.getDouble("latitude"),
                        longitude = destination.getDouble("longitude"), poiName = "안성휴게소(부산방향)",
                        poiId = "", address = destination.getString("address"), poiSubId = "", routeOption = RouteOption.RECOMMENDED))
                }
                withTimeout(40_000) {
                    while (navi.getRouteStateInfo().getOrThrow().naviMode != NaviMode.ROUTE_GUIDING || navi.getDestinationInfo().getOrThrow() == null) delay(500)
                }
                report.put("fixtureCreated", true)
            }
            val original = navi.snapshot()
            before = original
            report.put("before", snapshotJson(original))
            if (mode == "cleanup") {
                // Explicit operator cleanup of THIS test's receipt. Production
                // verification remains strict and still rejects unknown coordinates.
                val receiptId = args.getString("cleanupReceipt") ?: error("cleanupReceipt required")
                require(receiptId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                val receipt = JSONObject(File(app.cacheDir, "waypoint-$receiptId.json").readText())
                check(receipt.optBoolean("requestIssued"))
                val recorded = receipt.getJSONObject("after")
                val expectedDestination = recorded.getJSONObject("destination")
                val routeIdChanged = original.routeId != recorded.getString("routeId")
                if (routeIdChanged) {
                    check(args.getString("cleanupAcknowledgeRouteIdRefresh") == "true") { "Route ID changed; explicit operator acknowledgement required" }
                    report.put("operatorAcknowledgedRouteIdRefresh", true)
                }
                check(original.state.routeOption == recorded.getString("option") && original.state.naviMode.name == recorded.getString("mode"))
                check(original.destination?.name == expectedDestination.getString("name") &&
                    original.destination?.poiId == expectedDestination.getString("poiId") &&
                    RestNavigation.distanceMeters(original.destination!!.latitude, original.destination!!.longitude,
                        expectedDestination.getDouble("latitude"), expectedDestination.getDouble("longitude")) <= 20)
                val expectedWaypoint = recorded.getJSONArray("waypoints").getJSONObject(0)
                check(recorded.getJSONArray("waypoints").length() == 1 && original.waypoints.size == 1)
                val actualWaypoint = original.waypoints.single()
                check(actualWaypoint.name == expectedWaypoint.getString("name") && actualWaypoint.poiId == expectedWaypoint.getString("poiId") &&
                    actualWaypoint.latitude == expectedWaypoint.getDouble("latitude") && actualWaypoint.longitude == expectedWaypoint.getDouble("longitude"))
                val connected = CompletableDeferred<Unit>()
                helper = NaviHelper(app)
                withContext(Dispatchers.Main) { helper!!.initialize(onConnected = { connected.complete(Unit) }, onFailed = { connected.completeExceptionally(it) }) }
                withTimeout(10_000) { connected.await() }
                requireParked()
                val freshCleanup = navi.snapshot()
                check(freshCleanup.routeId == original.routeId &&
                    RestNavigation.sameDestination(original.destination, freshCleanup.destination) &&
                    freshCleanup.state.routeOption == original.state.routeOption && freshCleanup.state.naviMode == original.state.naviMode &&
                    freshCleanup.waypoints == original.waypoints) { "Route changed during explicit cleanup" }
                withContext(Dispatchers.Main) { helper!!.removeWaypoint(WaypointIndex.FIRST) }
                val restored = withTimeout(30_000) {
                    var value = navi.snapshot()
                    while (value.waypoints.isNotEmpty() || !RestNavigation.sameDestination(original.destination, value.destination) ||
                        value.state.routeOption != original.state.routeOption || value.state.naviMode != original.state.naviMode) {
                        report.put("lastCleanupObservation", snapshotJson(value))
                        delay(500)
                        value = navi.snapshot()
                    }
                    value
                }
                report.put("restored", snapshotJson(restored))
                assertTrue(RestNavigation.sameDestination(original.destination, restored.destination))
                assertEquals(original.state.routeOption, restored.state.routeOption)
                assertEquals(original.state.naviMode, restored.state.naviMode)
                report.put("explicitCleanupFor", receiptId).put("cleanupPassed", true).put("passed", true)
                log("EXPLICIT_RECEIPT_CLEANUP_CONFIRMED")
                return@runBlocking
            }
            if (mode == "inspect") {
                report.put("passed", true).put("note", "Read-only snapshot; no waypoint success assertion")
                return@runBlocking
            }
            RestNavigation.validateSnapshot(original)
            val tbt = navi.tbtRoute()
            report.put("tbt", JSONArray(tbt.entries.map { JSONObject().put("type", it.type).put("description", it.description) }))
            log("ROUTE destination=${original.destination?.name} option=${original.state.routeOption} next=${tbt.nextRestStopHint?.value?.description}")
            val session = app.serviceAreaReview.load()
            review = session
            report.put("facilities", JSONArray(session.places.map { JSONObject().put("name", it.name).put("road", it.roadName).put("direction", it.direction) }))
            log("FACILITIES ${session.places.joinToString { "${it.name}/${it.roadName}/${it.direction}" }}")
            if (mode == "prepare") {
                report.put("passed", true).put("note", "Read-only candidate preparation; fixture route remains for explicit write/voice verification")
                log("PREPARED")
                return@runBlocking
            }
            val direction = args.getString("directionBase64")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
                ?: error("Explicit operator-confirmed directionBase64 required")
            val place = session.places.single { it.direction == direction }
            app.serviceAreaReview.confirm(session, place, place.roadName, direction, confirmed = true)
            val rerouteCount=args.getString("rerouteCount")?.toInt() ?: 0
            require(rerouteCount in 0..3)
            var previousId=session.route.routeId
            val reroutes=JSONArray()
            repeat(rerouteCount) {
                val refreshed=withTimeout(360_000) {
                    var value=navi.snapshot()
                    while(value.routeId==previousId || value.routeId.isBlank()) { delay(2000);requireParked();value=navi.snapshot() }
                    value
                }
                // Production reads retry only mixed read contexts; no re-confirmation is made here.
                val stillValid=app.restNavigation.prepareProposal()
                assertEquals(place.id,stillValid.candidate.place.id)
                app.restNavigation.discard(stillValid)
                reroutes.put(JSONObject().put("before",previousId).put("after",refreshed.routeId)
                    .put("candidate",stillValid.candidate.place.name).put("source",stillValid.source))
                previousId=refreshed.routeId
                report.put("periodicReroutes",reroutes);save()
                log("PERIODIC_REVIEW_REUSED count=${reroutes.length()} candidate=${stillValid.candidate.place.name}")
            }
            candidate = app.restNavigation.preview().first()
            assertEquals(place.id, candidate!!.place.id)
            report.put("candidate", JSONObject().put("name", place.name).put("latitude", place.latitude).put("longitude", place.longitude)
                .put("etaSeconds", candidate!!.trip.etaSeconds).put("verification", candidate!!.verification))
            if (mode == "write") {
                val proposal = app.restNavigation.prepareProposal()
                val result = try {
                    app.restNavigation.acceptForProbe(proposal) { requireParked(); dispatched = true }
                } finally { app.restNavigation.discard(proposal) }
                report.put("navigationStatus", result.status.name).put("requestIssued", result.requestIssued)
                report.put("after", result.after?.let(::snapshotJson) ?: JSONObject.NULL)
                save()
                assertTrue(result.status in setOf(RestNavigationStatus.VERIFIED_WAYPOINT,RestNavigationStatus.WAYPOINT_OBSERVED))
            } else {
                val probe = app.interventions.dangerNavigationProbe
                assertTrue(withContext(Dispatchers.Main) { probe.start() })
                val id = probe.state.value.runId
                val final = withTimeout(105_000) {
                    probe.state.onEach { state ->
                        log("VOICE_PHASE ${state.phase}")
                        if (state.phase == VoicePhase.WAITING) log("AUDIO_INPUT_READY")
                    }.first { it.runId == id && !it.running && it.phase == VoicePhase.FINISHED }
                }
                dispatched = final.requestIssued
                report.put("voiceOutcome", final.outcome?.name).put("consent", final.consent?.name)
                    .put("navigationStatus", final.navigationStatus?.name).put("requestIssued", final.requestIssued)
                    .put("historyId", final.savedId).put("probeId", id).put("saveError", final.saveError ?: JSONObject.NULL)
                save()
                assertEquals(InterventionOutcome.ACCEPTED, final.outcome)
                assertEquals(VoiceConsent.ACCEPT, final.consent)
                assertTrue(final.navigationStatus in setOf(RestNavigationStatus.VERIFIED_WAYPOINT,RestNavigationStatus.WAYPOINT_OBSERVED))
                assertTrue(final.requestIssued)
                assertNotNull(final.savedId)
                assertNull(final.saveError)
            }
            val after = navi.snapshot()
            report.put("after", snapshotJson(after))
            assertTrue(RestNavigation.sameDestination(original.destination, after.destination))
            assertEquals(original.state.routeOption, after.state.routeOption)
            assertEquals(original.state.naviMode, after.state.naviMode)
            assertEquals(1, after.waypoints.size)
            val observedStatus=waypointApplicationStatus(original,candidate!!.place,after)
            assertEquals(report.getString("navigationStatus"),observedStatus.name)
            report.put("identityVerified",RestNavigation.waypointMatches(candidate!!.place,after.waypoints.single()))
            log("$observedStatus destinationPreserved=true waypoint=${after.waypoints.single().name}")
            report.put("passed", true)
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failure", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            try {
                if (app.interventions.dangerNavigationProbe.isBusy) {
                    withContext(Dispatchers.Main) { app.interventions.dangerNavigationProbe.cancel() }
                    withContext(NonCancellable) { withTimeout(15_000) { while (app.interventions.dangerNavigationProbe.isBusy) delay(100) } }
                    dispatched = dispatched || app.interventions.dangerNavigationProbe.state.value.requestIssued
                }
                if (dispatched && candidate != null && before != null) {
                    val current = navi.snapshot()
                    val restored = if (current.waypoints.isEmpty() && RestNavigation.sameDestination(before!!.destination, current.destination)) current
                        else navi.removeProbeWaypoint(candidate!!.place, before!!, ::requireParked)
                    report.put("restored", snapshotJson(restored))
                    assertTrue(restored.waypoints.isEmpty())
                    assertTrue(RestNavigation.sameDestination(before!!.destination, restored.destination))
                    report.put("cleanupPassed", true)
                    log("CLEANUP_CONFIRMED")
                }
                if (mode != "prepare") review?.let { app.serviceAreaReview.forget(it) }
            } catch (cleanup: Throwable) {
                report.put("passed", false).put("cleanupFailure", "${cleanup.javaClass.simpleName}: ${cleanup.message}")
                if (primaryFailure != null) primaryFailure!!.addSuppressed(cleanup) else throw cleanup
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!voiceWasReady) DrivingVoiceService.stop(app)
                    app.interventions.controller.setEnabled(wasEnabled)
                    helper?.release()
                }
                activity?.close()
                save()
                log("RESULT passed=${report.optBoolean("passed")} file=${output.name}")
            }
        }
    }

    private fun snapshotJson(snapshot: RestNaviSnapshot) = JSONObject()
        .put("routeId", snapshot.routeId).put("mode", snapshot.state.naviMode.name).put("option", snapshot.state.routeOption)
        .put("destination", snapshot.destination?.let { JSONObject().put("name", it.name).put("latitude", it.latitude).put("longitude", it.longitude).put("poiId", it.poiId) } ?: JSONObject.NULL)
        .put("waypoints", JSONArray(snapshot.waypoints.map { JSONObject().put("name", it.name).put("latitude", it.latitude).put("longitude", it.longitude)
            .put("displayLatitude", it.displayLatitude ?: JSONObject.NULL).put("displayLongitude", it.displayLongitude ?: JSONObject.NULL)
            .put("poiId", it.poiId).put("poiSubId", it.poiSubId).put("poiProvider", it.poiProvider) }))
}
