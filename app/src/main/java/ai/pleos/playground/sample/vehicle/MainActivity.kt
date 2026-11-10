package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsViewModel
import ai.pleos.playground.sample.vehicle.ui.screen.DriverConditionSettingsScreen
import ai.pleos.playground.sample.vehicle.ui.screen.SampleApp
import ai.pleos.playground.sample.vehicle.ui.theme.VehicleSampleAppTheme
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

class MainActivity : ComponentActivity() {

    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private var diagnosticCount = 0
    private val diagnosticTick = object : Runnable {
        override fun run() {
            Log.i("DriveProbe", "heartbeat count=${++diagnosticCount}")
            viewModel.probeTemperatureRead()
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
    override fun onResume() { super.onResume(); Log.i("DriveProbe", "activity onResume") }
    override fun onPause() { Log.i("DriveProbe", "activity onPause"); super.onPause() }
    override fun onStop() { Log.i("DriveProbe", "activity onStop"); super.onStop() }
    override fun onDestroy() {
        Log.i("DriveProbe", "activity onDestroy")
        unregisterReceiver(probeReceiver)
        diagnosticHandler.removeCallbacks(diagnosticTick)
        super.onDestroy()
    }

    private val viewModel: MainViewModel by viewModels()
    private val driverConditionViewModel: DriverConditionSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("DriveProbe", "activity onCreate")
        ContextCompat.registerReceiver(this, probeReceiver, IntentFilter("ai.pleos.driveprobe.TEST"), ContextCompat.RECEIVER_EXPORTED)
        Log.i("DriveProbe", "Manual emulator test receiver ready; no driving-state verification")
        diagnosticHandler.post(diagnosticTick)
        enableEdgeToEdge()

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

            VehicleSampleAppTheme {
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
