package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.sample.vehicle.departure.DepartureHomeViewModel
import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsViewModel
import ai.pleos.playground.sample.vehicle.ui.screen.DepartureDetailScreen
import ai.pleos.playground.sample.vehicle.ui.screen.DepartureHomeScreen
import ai.pleos.playground.sample.vehicle.ui.screen.DriverConditionSettingsScreen
import ai.pleos.playground.sample.vehicle.ui.screen.SampleApp
import ai.pleos.playground.sample.vehicle.ui.kd.KdAppViewModel
import ai.pleos.playground.sample.vehicle.ui.kd.KeepDrivingApp
import ai.pleos.playground.sample.vehicle.ui.kd.ReactionCheckViewModel
import ai.pleos.playground.sample.vehicle.ui.theme.VehicleSampleAppTheme
import com.pleos.departure.ui.DepartureHomeCard
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
    private val departureHomeViewModel: DepartureHomeViewModel by viewModels()
    private val kdViewModel: KdAppViewModel by viewModels()
    private val reactionViewModel: ReactionCheckViewModel by viewModels()

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

            // 앱 첫 화면 = '계속운전' 홈(ui/kd). 2번 '출발 전' 카드·상세 화면은 홈 안의 슬롯에 넣는다.
            // 2번 홈(DepartureHomeScreen)과 1번 API 탐색기는 [개발자 도구] 안에서 그대로 쓸 수 있다
            KeepDrivingApp(
                appViewModel = kdViewModel,
                reactionViewModel = reactionViewModel,
                departureCard = { modifier, onOpen ->
                    DepartureHomeCard(model = departureHomeViewModel.model, onOpen = onOpen, modifier = modifier)
                },
                departureDetail = { onBack ->
                    DepartureDetailScreen(model = departureHomeViewModel.model, onBack = onBack)
                },
                driverCondition = { onBack ->
                    VehicleSampleAppTheme {
                        DriverConditionSettingsScreen(viewModel = driverConditionViewModel, onBack = onBack)
                    }
                },
                devTools = {
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
                            onGoHome = viewModel::showHome,
                            home = {
                                DepartureHomeScreen(
                                    model = departureHomeViewModel.model,
                                    onOpenDepartureDetail = viewModel::showDepartureDetail,
                                    onOpenDriverCondition = viewModel::showDriverConditionSettings,
                                    onOpenApiExplorer = viewModel::openApiExplorer
                                )
                            },
                            departureDetail = {
                                DepartureDetailScreen(
                                    model = departureHomeViewModel.model,
                                    onBack = viewModel::showHome
                                )
                            },
                            driverConditionSettings = {
                                DriverConditionSettingsScreen(
                                    viewModel = driverConditionViewModel,
                                    onBack = viewModel::closeDriverConditionSettings
                                )
                            }
                        )
                    }
                },
            )
        }
    }
}
