package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.data.local.dao.InterventionHistoryDao
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepositoryImpl
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ai.pleos.playground.sample.vehicle.navigation.toHistoryJson

class InterventionModule(
    context: Context,
    private val repository: VehicleRepository,
    private val history: InterventionHistoryDao,
    restNavigation: ai.pleos.playground.sample.vehicle.navigation.RestNavigation,
    removeProbeWaypoint: suspend (ai.pleos.playground.sample.vehicle.navigation.RestPlace,
        ai.pleos.playground.sample.vehicle.navigation.RestNaviSnapshot,() -> Unit) -> ai.pleos.playground.sample.vehicle.navigation.RestNaviSnapshot,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = context.applicationContext.getSharedPreferences("driving_assist_settings", Context.MODE_PRIVATE)
    private val voice = AndroidVoiceConversation(context.applicationContext)
    private val speech = InterventionSpeechAction(voice)
    private val drivingSpeech = DangerNavigationAction(speech, restNavigation::prepareProposal, restNavigation::accept, restNavigation::discard)
    val controller = InterventionController(scope, SystemClock::elapsedRealtime, ::execute, ::save).apply {
        setEnabled(settings.getBoolean("automaticEnabled", true))
    }
    private val _voicePhase = kotlinx.coroutines.flow.MutableStateFlow(VoicePhase.IDLE)
    val voicePhase: kotlinx.coroutines.flow.StateFlow<VoicePhase> = _voicePhase

    private data class ReportSession(val engine: ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine, val id: String)
    private val reportSession = kotlinx.coroutines.flow.MutableStateFlow<ReportSession?>(null)

    /** TripRecorder owns the ID, including restored checkpoints. All controller access stays on Main. */
    suspend fun bindReportTrip(engine: ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine, id: String) =
        withContext(Dispatchers.Main.immediate) { reportSession.value = ReportSession(engine, id) }

    suspend fun finishReportTrip(id: String) = withContext(Dispatchers.Main.immediate) {
        if (reportSession.value?.id == id) reportSession.value = null
        controller.finishSession(id)
    }

    /** User preference only; temporary diagnostic suspension still uses the controller directly. */
    fun setAutomaticEnabled(enabled: Boolean) {
        settings.edit().putBoolean("automaticEnabled", enabled).apply()
        controller.setEnabled(enabled)
    }
    private var probeRunning = false
    private var automaticWasEnabled = false
    val voiceTests = VoiceTestController(scope, voice, ::saveVoiceTest,
        onStarted = {
            automaticWasEnabled = controller.status.value.enabled
            controller.setEnabled(false)
        },
        onFinished = { if (automaticWasEnabled) controller.setEnabled(true) },
    )

    val navigationWriteProbe=ai.pleos.playground.sample.vehicle.navigation.ServiceAreaWriteProbe(
        restNavigation,removeProbeWaypoint,
        requireParked={
            check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu","goldfish")) { "휴게소 추가 시험은 Debug 에뮬레이터 전용이에요" }
            val ux=repository.uxRestriction.value
            check(repository.gear.value==GearPosition.PARK && repository.hasSpeedSignal.value && repository.vehicleSpeed.value==0f &&
                ux?.source==UxRestrictionSource.CAR_SERVICE && !ux.requiresDistractionOptimization) { "P·속도 0·화면 사용 가능 상태를 유지해 주세요" }
        },
        begin={
            check(!probeRunning && !voiceTests.state.value.running && !controller.isBusy) { "다른 개입·시험이 끝난 뒤 실행해 주세요" }
            val enabled=controller.status.value.enabled
            controller.setEnabled(false);probeRunning=true
            enabled
        },
        finish={enabled->probeRunning=false;controller.setEnabled(enabled)},
    )

    val navigationProbeController=ai.pleos.playground.sample.vehicle.navigation.NavigationProbeController(
        scope,navigationWriteProbe::run,ai.pleos.playground.sample.vehicle.navigation.NavigationProbePreferences(context.applicationContext),
    )

    private fun requireDangerProbeReady() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish")) { "DANGER 통합 시험은 Debug 에뮬레이터 전용이에요" }
        val ux = repository.uxRestriction.value
        check(repository.gear.value == GearPosition.PARK && repository.hasSpeedSignal.value &&
            repository.vehicleSpeed.value == 0f && ux?.source == UxRestrictionSource.CAR_SERVICE &&
            !ux.requiresDistractionOptimization) { "P·속도 0·차량 화면 사용 가능 상태를 유지해 주세요" }
        check(DrivingVoiceService.ready.value) { "먼저 주행 음성을 준비해 주세요" }
    }

    private val dangerProbeAction = DangerNavigationAction(speech, restNavigation::prepareProposal,
        { restNavigation.acceptForProbe(it, ::requireDangerProbeReady) }, restNavigation::discard)

    val dangerNavigationProbe = DangerNavigationProbeController(scope, ::requireDangerProbeReady,
        begin = {
            check(!probeRunning && !voiceTests.state.value.running && !controller.isBusy && !navigationProbeController.isBusy) {
                "다른 개입·시험이 끝난 뒤 실행해 주세요"
            }
            val enabled = controller.status.value.enabled
            controller.setEnabled(false)
            probeRunning = true
            enabled
        },
        finish = { enabled -> probeRunning = false; controller.setEnabled(enabled) },
        execute = { phase -> dangerProbeAction.execute(FatigueLevel.DANGER, phase) },
        save = ::saveDangerProbe,
        store = DangerNavigationProbePreferences(context.applicationContext),
    )

    fun startVoiceTest(mode: VoiceTestMode) {
        if (BuildConfig.DEBUG && !probeRunning && !controller.isBusy) voiceTests.start(mode)
    }

    private var speedSampleMs: Long? = null

    private fun probeVehicleState() = ProbeVehicleState(
        gear = repository.gear.value,
        speedKmh = repository.vehicleSpeed.value.takeIf { repository.hasSpeedSignal.value },
        speedAgeMs = speedSampleMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE,
        emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
    )

    fun probeControl(kind: String, seat: SeatValue, moving: Boolean) {
        if (!BuildConfig.DEBUG || probeRunning || voiceTests.state.value.running || controller.isBusy) return
        val initial = probeVehicleState()
        try { initial.requireReady(moving) } catch (e: IllegalArgumentException) {
            Log.w("Intervention", "PROBE REJECTED kind=$kind moving=$moving: ${e.message}")
            return
        }
        val wasEnabled = controller.status.value.enabled
        controller.setEnabled(false)
        probeRunning = true
        Log.i("Intervention", "PROBE START kind=$kind seat=$seat moving=$moving elapsedMs=${SystemClock.elapsedRealtime()} state=$initial source=REAL_REPOSITORY")
        scope.launch {
            try {
                val result = (repository as VehicleRepositoryImpl).controlRoundTrip(kind, seat) {
                    probeVehicleState().requireReady(moving)
                }
                Log.i("Intervention", "PROBE END kind=$kind seat=$seat moving=$moving ${if (result.isSuccess) "VERIFIED" else "FAILED"}: ${result.getOrNull() ?: result.exceptionOrNull()?.message} elapsedMs=${SystemClock.elapsedRealtime()} state=${probeVehicleState()}")
                if (result.isSuccess && wasEnabled) controller.setEnabled(true)
            } finally {
                probeRunning = false
            }
        }
    }

    init {
        if (BuildConfig.DEBUG) scope.launch {
            runCatching { voice.stopPreviousRecognition() }
                .onFailure { Log.w("VoiceTest", "Previous-session cleanup failed: ${it.javaClass.simpleName}") }
        }
        scope.launch {
            combine(repository.gear, repository.vehicleSpeed, repository.hasSpeedSignal, repository.uxRestriction) { gear, speed, known, ux ->
                val reason = when {
                    gear != GearPosition.PARK -> "P 기어가 아니에요 (현재 $gear)"
                    !known -> "차량 속도 신호를 확인하지 못했어요"
                    speed != 0f -> "속도가 0km/h가 아니에요 (현재 ${speed}km/h)"
                    ux?.source != UxRestrictionSource.CAR_SERVICE -> "차량의 화면 사용 가능 상태를 확인하지 못했어요"
                    ux.requiresDistractionOptimization -> "차량에서 화면 사용을 제한하고 있어요"
                    else -> ""
                }
                if (reason.isNotEmpty() && voiceTests.state.value.running)
                    Log.w("VoiceTest", "Gate blocked: gear=$gear speed=$speed known=$known ux=$ux")
                (reason.isEmpty()) to reason
            }.collect { (allowed, reason) -> voiceTests.setAllowed(allowed, reason) }
        }
        scope.launch {
            combine(voiceTests.state, DrivingVoiceService.ready) { state, ready -> state.allowed && ready }
                .collect { allowed -> if (!allowed) dangerNavigationProbe.cancel() }
        }
        if (BuildConfig.DEBUG) scope.launch {
            repository.signalEvents.collect { event ->
                if (event is VehicleSignalEvent.Speed) speedSampleMs = event.timeMs
            }
        }
        scope.launch {
            ConditionModule.engines.filterNotNull().collectLatest { engine ->
                controller.reset()
                try {
                    combine(engine.state, engine.isDriving, engine.signals.gear) { state, active, gear -> Triple(state, active, gear) }
                        .combine(DrivingVoiceService.ready) { input, prepared -> input to prepared }
                        .combine(reportSession) { input, session -> input to session }
                        .collect { (voiceInput, session) ->
                            val (input, prepared) = voiceInput
                            val (state, active, gear) = input
                            val voiceAllowed = engine.isReplay || state.level < FatigueLevel.CAUTION || prepared
                            // Recheck the current binding so queued emissions cannot restart a finished trip.
                            val bound = session != null && session.engine === engine && reportSession.value === session
                            controller.update(state, active && bound, gear == GearPosition.DRIVE && voiceAllowed,
                                simulation = engine.isReplay, reportTripId = session?.id?.takeIf { bound })
                        }
                } finally {
                    controller.reset()
                }
            }
        }
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val level = FatigueLevel.entries.firstOrNull { it.name == intent?.getStringExtra("level") } ?: return
                    controller.simulate(level)
                }
            }, IntentFilter(ACTION_SIMULATE), ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val requested = intent?.getStringExtra("seat") ?: "ROW_1_LEFT"
                    val seat = SeatValue.entries.firstOrNull { it.name == requested } ?: return
                    val kind = intent?.getStringExtra("kind") ?: "temperature"
                    if (kind !in setOf("temperature", "fan", "window")) return
                    probeControl(kind, seat, intent?.getBooleanExtra("driving", false) == true)
                }
            }, IntentFilter(ACTION_PROBE), android.Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private suspend fun execute(level: FatigueLevel): InterventionResult = when (level) {
        FatigueLevel.NORMAL -> InterventionResult(InterventionOutcome.FAILED, "NORMAL에는 개입하지 않아요")
        FatigueLevel.MILD -> repository.triggerFatigueVentilationIntervention().fold(
            onSuccess = { InterventionResult(InterventionOutcome.VERIFIED, "공조 전원 ON·AUTO OFF 확인 후 운전석 설정 온도 1℃ 하향·풍량 1단계 증가를 재조회 확인. 지원 범위 한계에서는 유지") },
            onFailure = { InterventionResult(InterventionOutcome.FAILED, "공조 개입 실패: ${it.message}. 일부 설정은 이미 적용됐을 수 있어요") },
        )
        FatigueLevel.CAUTION, FatigueLevel.DANGER -> try {
            drivingSpeech.execute(level) { _voicePhase.value = it }
        } finally { _voicePhase.value = VoicePhase.IDLE }
    }

    private suspend fun save(record: InterventionRecord) {
        val result = record.result
        val details = JSONObject().apply {
            put("version", 2)
            put("outcome", result.outcome.name)
            put("simulation", record.simulation)
            put("details", result.details)
            put("responseMs", result.responseMs ?: JSONObject.NULL)
            put("responseTiming", result.voiceAnswer?.responseTiming ?: JSONObject.NULL)
            put("readyLatencyMs", result.voiceAnswer?.readyLatencyMs ?: JSONObject.NULL)
            put("captureLatencyMs", result.voiceAnswer?.captureLatencyMs ?: JSONObject.NULL)
            put("epdOnBytes", result.voiceAnswer?.epdOnBytes ?: JSONObject.NULL)
            put("epdOffBytes", result.voiceAnswer?.epdOffBytes ?: JSONObject.NULL)
            put("audioBytes", result.voiceAnswer?.audioBytes ?: JSONObject.NULL)
            put("recognitionEnd", result.voiceAnswer?.end?.name ?: JSONObject.NULL)
            put("consent", result.consent?.name ?: JSONObject.NULL)
            put("navigationStatus", result.navigationStatus ?: JSONObject.NULL)
            put("navigation", result.navigation?.toHistoryJson() ?: JSONObject.NULL)
            put("error", result.error?.name ?: JSONObject.NULL)
            put("platformCode", result.platformCode ?: result.voiceAnswer?.platformCode ?: JSONObject.NULL)
            put("recognitionBackend", voice.capabilities().recognitionBackend.name)
            put("calibrating", record.condition.isCalibrating)
        }.toString()
        val id = history.insertIntervention(InterventionHistoryEntity(
            sessionId = record.sessionId,
            interventionLevel = record.condition.level.ordinal,
            // Entity/server contract uses 0..100; the condition engine remains 0..1.
            fatigueScore = record.condition.score.coerceIn(0f, 1f) * 100f,
            triggerReason = record.condition.reasons.joinToString { it.code.name }.ifEmpty { "${record.condition.level}" },
            actionDetails = details,
            userResponse = result.response,
        ))
        Log.i("Intervention", "history=$id session=${record.sessionId} level=${record.condition.level} outcome=${result.outcome} simulation=${record.simulation} details=${result.details}")
    }

    private suspend fun saveDangerProbe(id: String, result: InterventionResult): Long {
        val details = JSONObject().apply {
            put("version", 2); put("source", "DANGER_NAVIGATION_PROBE")
            put("revision", DANGER_PROBE_REVISION); put("simulation", true)
            put("inputSource", "MANUAL_PARKED_TRIGGER"); put("effects", "REAL_EMULATOR")
            put("executionPath", "DANGER_NAVIGATION_ACTION")
            put("outcome", result.outcome.name); put("details", result.details)
            put("consent", result.consent?.name ?: JSONObject.NULL)
            put("navigationStatus", result.navigationStatus ?: JSONObject.NULL)
            put("navigation", result.navigation?.toHistoryJson() ?: JSONObject.NULL)
            put("responseMs", result.responseMs ?: JSONObject.NULL)
            put("responseTiming", result.voiceAnswer?.responseTiming ?: JSONObject.NULL)
            put("error", result.error?.name ?: JSONObject.NULL)
            put("platformCode", result.platformCode ?: JSONObject.NULL)
        }.toString()
        val row = history.insertIntervention(InterventionHistoryEntity(
            sessionId = id, interventionLevel = 3, fatigueScore = 0f,
            triggerReason = "DANGER_NAVIGATION_PROBE", actionDetails = details, userResponse = result.response,
        ))
        Log.i("DangerProbe", "history=$row run=$id outcome=${result.outcome} consent=${result.consent} navigation=${result.navigationStatus} requestIssued=${result.navigation?.requestIssued == true}")
        return row
    }

    private suspend fun saveVoiceTest(record: VoiceTestRecord): Long {
        val result = record.result
        val details = JSONObject().apply {
            put("version", 1)
            put("source", "VOICE_TEST")
            put("mode", record.mode.name)
            put("stageOutcome", result.intervention?.outcome?.name ?: JSONObject.NULL)
            put("consent", result.intervention?.consent?.name ?: JSONObject.NULL)
            put("navigationStatus", result.intervention?.navigationStatus ?: JSONObject.NULL)
            put("executionPath", if (record.mode in setOf(VoiceTestMode.CAUTION_FLOW, VoiceTestMode.DANGER_FLOW)) "INTERVENTION_SPEECH_ACTION" else "VOICE_TEST")
            put("simulation", true) // Exclude manual tests from real driving reports.
            put("audioMode", "REAL_PLATFORM_TTS_STT")
            put("ttsBackend", voice.capabilities().ttsBackend.name)
            put("outcome", result.outcome.name)
            put("details", result.message)
            put("responseMs", result.answer?.responseMs ?: JSONObject.NULL)
            put("readyLatencyMs", result.answer?.readyLatencyMs ?: JSONObject.NULL)
            put("captureLatencyMs", result.answer?.captureLatencyMs ?: JSONObject.NULL)
            put("epdOnBytes", result.answer?.epdOnBytes ?: JSONObject.NULL)
            put("epdOffBytes", result.answer?.epdOffBytes ?: JSONObject.NULL)
            put("audioBytes", result.answer?.audioBytes ?: JSONObject.NULL)
            put("responseTiming", result.answer?.responseTiming ?: JSONObject.NULL)
            put("recognitionBackend", voice.capabilities().recognitionBackend.name)
            put("inputLevelCallbacks", result.answer?.inputLevelCallbacks ?: JSONObject.NULL)
            put("maxInputRms", result.answer?.maxInputRms ?: JSONObject.NULL)
            put("recognitionEnd", result.answer?.end?.name ?: JSONObject.NULL)
            put("error", result.error?.name ?: JSONObject.NULL)
            put("platformCode", result.platformCode ?: result.answer?.platformCode ?: JSONObject.NULL)
        }.toString()
        val id = history.insertIntervention(InterventionHistoryEntity(
            sessionId = record.id,
            interventionLevel = if (record.mode in setOf(VoiceTestMode.CONSENT, VoiceTestMode.DANGER_FLOW)) 3 else 2,
            fatigueScore = 0f, // Not a measured fatigue score. source=VOICE_TEST distinguishes it.
            triggerReason = "VOICE_TEST",
            actionDetails = details,
            userResponse = result.answer?.text,
        ))
        Log.i("VoiceTest", "history=$id mode=${record.mode} outcome=${result.outcome} responseMs=${result.answer?.responseMs} error=${result.error} platformCode=${result.platformCode ?: result.answer?.platformCode}")
        return id
    }

    companion object {
        const val ACTION_SIMULATE = "ai.pleos.driveprobe.INTERVENTION_SIMULATE"
        const val ACTION_PROBE = "ai.pleos.driveprobe.INTERVENTION_PROBE"
    }
}
