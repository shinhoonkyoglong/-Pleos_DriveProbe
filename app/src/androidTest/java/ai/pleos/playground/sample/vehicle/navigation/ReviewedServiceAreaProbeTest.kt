package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepositoryImpl
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit parked diagnostic; does not pretend a command-line request was spoken driver consent. */
@RunWith(AndroidJUnit4::class)
class ReviewedServiceAreaProbeTest {
    @Test fun previewOrExplicitReviewedWrite() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("reviewedMode")
        val runId=args.getString("probeRunId") ?: "manual"
        fun log(message:String){Log.i(TAG,"runId=$runId $message")}
        assumeTrue("Explicit preview/write mode required",mode=="preview" || mode=="write")
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Emulator only" }
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DriveProbeApplication
        val vehicle=app.vehicleRepository
        fun requireParked() {
            check(vehicle.hasSpeedSignal.value && vehicle.gear.value==GearPosition.PARK && vehicle.vehicleSpeed.value==0f)
            check(vehicle.uxRestriction.value?.let{it.source==UxRestrictionSource.CAR_SERVICE && !it.requiresDistractionOptimization}==true)
        }
        withTimeout(10_000) { while(!vehicle.hasSpeedSignal.value || vehicle.uxRestriction.value==null) delay(100) }
        requireParked()
        check(!app.interventions.controller.isBusy && !app.interventions.voiceTests.state.value.running)
        val enabled=app.interventions.controller.status.value.enabled
        var proposal:RestProposal?=null
        var issued=false
        withContext(Dispatchers.Main){app.interventions.controller.setEnabled(false)}
        try {
            val p=try{app.restNavigation.prepareProposal()}catch(e:RestNavigationFailure){
                log("REVIEWED_PREPARE_FAILED status=${e.status} source=${e.source} diagnostic=${e.diagnosticCode ?: "NONE"}")
                throw e
            };proposal=p
            log("REVIEWED_PROPOSAL name=${p.candidate.place.name} road=${p.candidate.place.roadName} direction=${p.candidate.place.direction} etaSeconds=${p.candidate.trip.etaSeconds} pointTripDistanceMeters=${p.candidate.trip.distanceMeters} originalRouteDistanceMeters=UNMEASURED source=${p.source} verification=${p.candidate.verification}")
            log("REVIEWED_PROMPT ${p.prompt}")
            if(mode=="preview") return@runBlocking
            requireParked()
            val result=try{app.restNavigation.acceptForProbe(p,::requireParked)}catch(e:RestNavigationCancelled){issued=e.navigation.requestIssued;throw e}
            issued=result.requestIssued
            log("REVIEWED_WRITE status=${result.status} issued=$issued before=${result.before} after=${result.after}")
            assertEquals(RestNavigationStatus.VERIFIED_WAYPOINT,result.status)
        } finally {
            try {
                proposal?.let { p ->
                    app.restNavigation.discard(p)
                    if(issued) withContext(NonCancellable) {
                        requireParked()
                        val restored=(app.naviHelperRepository as NaviHelperRepositoryImpl).removeProbeWaypoint(p.candidate.place,p.before,::requireParked)
                        log("REVIEWED_CLEANUP_CONFIRMED restored=$restored")
                    }
                }
            } finally {withContext(NonCancellable+Dispatchers.Main){app.interventions.controller.setEnabled(enabled)}}
        }
    }
    companion object { const val TAG="NaviWaypointProbe" }
}
