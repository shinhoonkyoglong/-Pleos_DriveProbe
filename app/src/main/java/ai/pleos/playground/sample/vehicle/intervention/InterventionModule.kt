package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.data.local.dao.InterventionHistoryDao
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
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
import org.json.JSONObject

class InterventionModule(
    context: Context,
    private val repository: VehicleRepository,
    private val history: InterventionHistoryDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val voice = AndroidVoiceConversation(context.applicationContext)
    val controller = InterventionController(scope, SystemClock::elapsedRealtime, ::execute, ::save)
    private var probeRunning = false

    private var speedSampleMs: Long? = null

    private fun probeVehicleState() = ProbeVehicleState(
        gear = repository.gear.value,
        speedKmh = repository.vehicleSpeed.value.takeIf { repository.hasSpeedSignal.value },
        speedAgeMs = speedSampleMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE,
        emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
    )

    fun probeControl(kind: String, seat: SeatValue, moving: Boolean) {
        if (!BuildConfig.DEBUG || probeRunning) return
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
            repository.signalEvents.collect { event ->
                if (event is VehicleSignalEvent.Speed) speedSampleMs = event.timeMs
            }
        }
        scope.launch {
            ConditionModule.engines.filterNotNull().collectLatest { engine ->
                controller.reset()
                try {
                    combine(engine.state, engine.isDriving, engine.signals.gear) { state, active, gear -> Triple(state, active, gear) }
                        .collect { (state, active, gear) ->
                            controller.update(state, active, gear == GearPosition.DRIVE, simulation = engine.isReplay)
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
            onSuccess = { InterventionResult(InterventionOutcome.VERIFIED, "운전석 설정 온도 1℃ 하향·풍량 1단계 증가 후 재조회 확인. 지원 범위 한계에서는 유지") },
            onFailure = { InterventionResult(InterventionOutcome.FAILED, "공조 개입 실패: ${it.message}. 일부 설정은 이미 적용됐을 수 있어요") },
        )
        FatigueLevel.CAUTION -> ask(level, "피로 징후가 감지됐어요. 괜찮으시면 괜찮아요라고 말씀해 주세요.")
        FatigueLevel.DANGER -> ask(level, "휴식이 필요해 보여요. 가까운 쉼터 안내를 원하시면 안내해 줘, 원치 않으시면 아니요라고 말씀해 주세요.")
    }

    private suspend fun ask(level: FatigueLevel, prompt: String): InterventionResult = try {
        val answer = voice.ask(prompt)
        when {
            answer.text.isNullOrBlank() -> InterventionResult(InterventionOutcome.NO_RESPONSE, "음성 응답이 없어요. 자동 경로 변경은 하지 않았어요", responseMs = answer.responseMs)
            level == FatigueLevel.CAUTION -> InterventionResult(InterventionOutcome.ANSWERED,
                "음성 응답 수신. 피로 점수 재반영 정책은 판단 모듈 담당자와 연동이 필요해요", answer.text, answer.responseMs)
            VoiceResponse.consent(answer.text) == VoiceConsent.DECLINE -> InterventionResult(InterventionOutcome.DECLINED, "쉼터 안내를 거절했어요", answer.text, answer.responseMs)
            VoiceResponse.consent(answer.text) == VoiceConsent.ACCEPT -> InterventionResult(InterventionOutcome.UNAVAILABLE,
                "쉼터 안내 동의 수신. 실제 NaviHelper·쉼터 검색 연동이 없어 경로를 변경하지 않았어요", answer.text, answer.responseMs)
            else -> InterventionResult(InterventionOutcome.NO_RESPONSE, "쉼터 안내 동의를 확인하지 못했어요", answer.text, answer.responseMs)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        InterventionResult(InterventionOutcome.UNAVAILABLE, e.message ?: "음성 기능을 사용할 수 없어요")
    }

    private suspend fun save(record: InterventionRecord) {
        val result = record.result
        val details = JSONObject().apply {
            put("version", 1)
            put("outcome", result.outcome.name)
            put("simulation", record.simulation)
            put("details", result.details)
            put("responseMs", result.responseMs ?: JSONObject.NULL)
            put("responseTiming", "TTS_onDone_to_speech_onBeginning")
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

    companion object {
        const val ACTION_SIMULATE = "ai.pleos.driveprobe.INTERVENTION_SIMULATE"
        const val ACTION_PROBE = "ai.pleos.driveprobe.INTERVENTION_PROBE"
    }
}
