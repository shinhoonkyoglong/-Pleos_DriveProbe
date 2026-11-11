package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsViewModel
import ai.pleos.playground.sample.vehicle.ui.screen.DriverConditionSettingsScreen
import ai.pleos.playground.sample.vehicle.ui.screen.SampleApp
import ai.pleos.playground.sample.vehicle.ui.screen.InterventionScreen
import ai.pleos.playground.sample.vehicle.ui.screen.VoiceTestScreen
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.intervention.DrivingVoiceService
import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import ai.pleos.playground.sample.vehicle.ui.theme.VehicleSampleAppTheme
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val CAR_SPEED = "android.car.permission.CAR_SPEED"

class MainActivity : ComponentActivity() {

    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private var diagnosticCount = 0
    private val diagnosticTick = object : Runnable {
        override fun run() {
            Log.i("DriveProbe", "heartbeat count=${++diagnosticCount}")
            // Temperature SET_TEST remains available in the ViewModel, but is never automatic.
            diagnosticHandler.postDelayed(this, 5000)
        }
    }

    private val probeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "ai.pleos.driveprobe.TEST") return
            viewModel.runManualProbe(
                intent.getStringExtra("kind").orEmpty(),
                intent.getStringExtra("step").orEmpty(),
                intent.getStringExtra("value")
            )
        }
    }

    override fun onStart() { super.onStart(); Log.i("DriveProbe", "activity onStart") }
    override fun onResume() {
        super.onResume()
        Log.i("DriveProbe", "activity onResume")
        // Emulator-only setup, consumed before observation starts. Never records audio.
        if (BuildConfig.DEBUG && android.os.Build.HARDWARE in setOf("ranchu", "goldfish") &&
            intent.hasExtra("bg_probe_voice")) {
            val prepare = intent.getBooleanExtra("bg_probe_voice", false)
            intent.removeExtra("bg_probe_voice")
            if (prepare) DrivingVoiceService.prepare(this) else DrivingVoiceService.stop(this)
        }
        if (BuildConfig.DEBUG && android.os.Build.HARDWARE in setOf("ranchu", "goldfish") &&
            intent.getBooleanExtra("bg_probe_stages", false)) {
            intent.removeExtra("bg_probe_stages")
            DriveProbeApplication.instance.interventions.startBackgroundStageProbe()
        }
        DriveProbeApplication.instance.interventions.voiceTests.refreshCapabilities()
    }
    override fun onPause() { Log.i("DriveProbe", "activity onPause"); super.onPause() }
    override fun onStop() {
        DriveProbeApplication.instance.interventions.voiceTests.cancel("앱 화면이 닫혀 음성 시험을 중지했어요")
        Log.i("DriveProbe", "activity onStop")
        super.onStop()
    }
    override fun onDestroy() {
        Log.i("DriveProbe", "activity onDestroy")
        unregisterReceiver(probeReceiver)
        diagnosticHandler.removeCallbacks(diagnosticTick)
        super.onDestroy()
    }

    private val viewModel: MainViewModel by viewModels()
    private val driverConditionViewModel: DriverConditionSettingsViewModel by viewModels()

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i("Intervention", "RECORD_AUDIO granted=$granted")
            DriveProbeApplication.instance.interventions.voiceTests.refreshCapabilities()
        }

    // [컨디션 판단] 속도(android.car.permission.CAR_SPEED)는 실행 중 허용이 필요하다. 허용되면 속도 구독을 다시 시작한다
    private val carSpeedPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i("DriveProbe", "CAR_SPEED granted=$granted")
            if (granted) DriveProbeApplication.instance.vehicleRepository.startCarPropertyUpdates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("DriveProbe", "activity onCreate")
        if (ContextCompat.checkSelfPermission(this, CAR_SPEED) != PackageManager.PERMISSION_GRANTED) {
            carSpeedPermissionLauncher.launch(CAR_SPEED)
        }
        ContextCompat.registerReceiver(this, probeReceiver, IntentFilter("ai.pleos.driveprobe.TEST"), ContextCompat.RECEIVER_EXPORTED)
        Log.i("DriveProbe", "Manual emulator test receiver ready; no driving-state verification")
        diagnosticHandler.post(diagnosticTick)
        enableEdgeToEdge()

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val app = DriveProbeApplication.instance
            val intervention = app.interventions.controller.status.collectAsStateWithLifecycle().value
            val latest = remember(app) { app.appDatabase.interventionHistoryDao().getLatestIntervention() }
                .collectAsStateWithLifecycle(initialValue = null).value
            val gear = app.vehicleRepository.gear.collectAsStateWithLifecycle().value
            val voiceState = app.interventions.voiceTests.state.collectAsStateWithLifecycle().value
            val drivingVoicePrepared = DrivingVoiceService.ready.collectAsStateWithLifecycle().value
            val drivingVoiceError = DrivingVoiceService.error.collectAsStateWithLifecycle().value
            val voiceHistory = remember(app) { app.appDatabase.interventionHistoryDao().getLatestVoiceTest() }
                .collectAsStateWithLifecycle(initialValue = null).value
            var activeTab by rememberSaveable { mutableStateOf("vehicle") }

            VehicleSampleAppTheme {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { app.interventions.voiceTests.cancel("음성 시험 화면을 나갔어요"); activeTab = "vehicle" }) { Text("차량 API") }
                        TextButton(onClick = { app.interventions.voiceTests.cancel("음성 시험 화면을 나갔어요"); activeTab = "intervention" }) { Text("개입 상태") }
                        if (BuildConfig.DEBUG) TextButton(onClick = {
                            activeTab = "voice"
                            app.interventions.voiceTests.refreshCapabilities()
                        }) { Text("음성 시험") }
                    }
                    Box(Modifier.weight(1f)) {
                        if (activeTab == "voice" && BuildConfig.DEBUG) {
                            VoiceTestScreen(
                                state = voiceState,
                                latest = voiceHistory,
                                onMicrophone = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onRefresh = app.interventions.voiceTests::refreshCapabilities,
                                onStart = app.interventions::startVoiceTest,
                                onStop = { app.interventions.voiceTests.cancel() },
                            )
                        } else if (activeTab == "intervention") {
                            InterventionScreen(
                                status = intervention,
                                latest = latest,
                                parked = gear == GearPosition.PARK,
                                voicePrepared = drivingVoicePrepared,
                                voicePreparationError = drivingVoiceError,
                                onPrepareVoice = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                        DrivingVoiceService.prepare(this@MainActivity)
                                    else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onReleaseVoice = { DrivingVoiceService.stop(this@MainActivity) },
                                onEnabled = { enabled ->
                                    if (!voiceState.running) {
                                        app.interventions.controller.setEnabled(enabled)
                                        if (!enabled) DrivingVoiceService.stop(this@MainActivity)
                                    }
                                },
                                onMicrophone = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onSimulate = app.interventions.controller::simulate,
                            )
                        } else {
                            SampleApp(
                                uiState = uiState,
                                onOpenDomain = viewModel::showApiExplorer,
                                onSelectFeature = viewModel::selectFeature,
                                onSearchFeatures = viewModel::searchFeatures,
                                onRunCapability = viewModel::runFeatureCapability,
                                onRunRead = viewModel::runFeatureRead,
                                onRunSet = viewModel::runFeatureSet,
                                onStartUpdates = viewModel::startFeatureUpdates,
                                onStopUpdates = viewModel::stopFeatureUpdates,
                                onBackToFeatureList = viewModel::backToFeatureList,
                                onDismissError = viewModel::dismissError,
                                onOpenDriverCondition = viewModel::showDriverConditionSettings,
                                driverConditionSettings = {
                                    DriverConditionSettingsScreen(
                                        viewModel = driverConditionViewModel,
                                        onBack = viewModel::closeDriverConditionSettings
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
