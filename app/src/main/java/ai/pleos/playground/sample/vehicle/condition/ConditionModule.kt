package ai.pleos.playground.sample.vehicle.condition

import ai.pleos.playground.sample.vehicle.condition.core.BaselineSeedFormat
import ai.pleos.playground.sample.vehicle.condition.core.InMemoryBaselineStore
import ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine
import ai.pleos.playground.sample.vehicle.condition.engine.FileBaselineStore
import ai.pleos.playground.sample.vehicle.condition.signals.ReplayVehicleSignals
import ai.pleos.playground.sample.vehicle.condition.signals.RepositoryVehicleSignals
import ai.pleos.playground.sample.vehicle.condition.signals.VehicleSignals
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * 컨디션 판단 조립 담당. 앱에서는 이것 하나만 알면 된다.
 *
 * ```kotlin
 * // Application.onCreate (vehicleRepository 초기화 다음)
 * ConditionModule.init(this, vehicleRepository, mode = ConditionModule.Mode.REAL)
 *
 * // 다른 파트: 상태 구독
 * ConditionModule.engine.state.collect { s -> if (s.level >= FatigueLevel.CAUTION) ... }
 * ```
 */
object ConditionModule {

    enum class Mode {
        /** 실제 차량 신호 (Repository) + 기기에 저장되는 개인 기준선 */
        REAL,
        /** 시연: assets/condition/trip_demo.json 재생 + 기록 없음(기본 기준선, isCalibrating=true) */
        DEMO,
        /** 시연: 재생 + 미리 넣은 평소 기록 6회(baseline_seed.json, isCalibrating=false) */
        DEMO_WITH_SEED,
    }

    /** 시연 결과를 테스트(DemoScenarioTest)와 같게 만들려고 시간대를 오전 11시로 고정한다 */
    private const val DEMO_HOUR = 11

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var _engine: ConditionEngine? = null

    private val _engines = MutableStateFlow<ConditionEngine?>(null)
    /** Rebind consumers when the debug replay mode replaces the engine. */
    val engines = _engines.asStateFlow()

    val engine: ConditionEngine
        get() = _engine ?: error("ConditionModule.init()을 먼저 불러야 합니다")

    val isInitialized: Boolean get() = _engine != null

    /** 엔진을 만들고 시작한다. 이미 있으면 멈추고 새로 만든다(모드 전환) */
    @Synchronized
    fun init(context: Context, repository: VehicleRepository, mode: Mode = Mode.REAL): ConditionEngine {
        _engine?.stop()
        val app = context.applicationContext
        val engine = when (mode) {
            Mode.REAL -> ConditionEngine(
                signals = RepositoryVehicleSignals(repository, scope),
                baselineStore = FileBaselineStore(File(app.filesDir, "condition/baseline.json")),
                scope = scope,
            )
            Mode.DEMO, Mode.DEMO_WITH_SEED -> {
                val seed = if (mode == Mode.DEMO_WITH_SEED) loadSeed(app) else emptyList()
                ConditionEngine(
                    signals = ReplayVehicleSignals(ReplayVehicleSignals.loadTrip(app), scope),
                    // 시연 기록이 실제 기준선 파일을 더럽히지 않도록 메모리에만 둔다
                    baselineStore = InMemoryBaselineStore(seed),
                    scope = scope,
                    hourOfDay = { DEMO_HOUR },
                )
            }
        }
        _engine = engine
        _engines.value = engine
        engine.start()
        logJob?.cancel()
        logJob = scope.launch { logStateChanges(engine, mode) }
        registerDebugReceiver(app, repository)
        return engine
    }

    // ---------------------------------------------------------------------
    // 확인용: logcat "Condition" 태그
    //   & $adb logcat -s Condition
    // 모드 전환(debug 빌드만):
    //   & $adb shell am broadcast --user 10 -a ai.pleos.condition.MODE --es mode DEMO
    // ---------------------------------------------------------------------
    private const val TAG = "Condition"
    const val ACTION_MODE = "ai.pleos.condition.MODE"
    private var logJob: Job? = null
    private var receiverRegistered = false

    private suspend fun logStateChanges(engine: ConditionEngine, mode: Mode) {
        Log.i(TAG, "engine started mode=$mode isReplay=${engine.isReplay}")
        engine.state
            .map { listOf(it.level, "%.2f".format(it.score), it.lastWindowValid, it.isCalibrating, it.reasons.map { r -> r.code }) }
            .distinctUntilChanged()
            .collect {
                val s = engine.state.value
                Log.i(TAG, "level=${s.level} score=${"%.2f".format(s.score)} valid=${s.lastWindowValid} calibrating=${s.isCalibrating} " +
                    "drive=${"%.1f".format(s.drivingMinutes)}min reasons=${s.reasons.joinToString { it.message }}")
            }
    }

    private fun registerDebugReceiver(app: Context, repository: VehicleRepository) {
        if (receiverRegistered) return
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val name = intent?.getStringExtra("mode") ?: return
                val mode = Mode.entries.firstOrNull { it.name == name } ?: return Unit.also { Log.w(TAG, "unknown mode=$name") }
                init(app, repository, mode)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_MODE), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    /** 반응 체크 화면 등에서 쓰는 현재 신호(기어·속도) */
    val signals: VehicleSignals? get() = _engine?.signals

    private fun loadSeed(app: Context) =
        app.assets.open(ReplayVehicleSignals.BASELINE_SEED_ASSET).bufferedReader(Charsets.UTF_8)
            .use { BaselineSeedFormat.read(it.readText()) }
}
