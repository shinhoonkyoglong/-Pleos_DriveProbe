package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.intervention.DrivingVoiceService
import android.app.ActivityManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Observation only: no service, wake lock, alarm, broadcast, or vehicle control. */
internal object BackgroundDiagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start(app: DriveProbeApplication) {
        if (!BuildConfig.DEBUG || started) return
        started = true
        Log.i("BgProbe", "START pid=${Process.myPid()} elapsedMs=${SystemClock.elapsedRealtime()}")
        scope.launch {
            app.appDatabase.interventionHistoryDao().getLatestIntervention().collect { record ->
                if (record != null) Log.i("BgProbe", "DB_LATEST id=${record.id} session=${record.sessionId} level=${record.interventionLevel}")
            }
        }
        scope.launch {
            var previous = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                val engine = ConditionModule.engine
                val d = engine.diagnostics()
                val state = engine.state.value
                val intervention = app.interventions.controller.status.value
                Log.i("BgProbe", "SAMPLE elapsedMs=$now gapMs=${now - previous} pid=${Process.myPid()} importance=${info.importance} " +
                    "engine=${System.identityHashCode(engine)} replay=${engine.isReplay} ticks=${d.ticks} events=${d.events} " +
                    "speedEvents=${d.speedEvents} steeringEvents=${d.steeringEvents} lastInputMs=${d.lastInputMs} lastTickMs=${d.lastTickMs} " +
                    "stateMs=${state.timeMs} valid=${state.lastWindowValid} score=${state.score} level=${state.level} " +
                    "driving=${engine.isDriving.value} gear=${engine.signals.gear.value} voiceReady=${DrivingVoiceService.ready.value} " +
                    "enabled=${intervention.enabled} actionRunning=${intervention.running} outcome=${intervention.result?.outcome}")
                previous = now
                delay(2000)
            }
        }
    }
}
