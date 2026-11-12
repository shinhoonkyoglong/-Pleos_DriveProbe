package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.intervention.DrivingVoiceService
import ai.pleos.playground.sample.vehicle.intervention.VoicePhase
import ai.pleos.playground.sample.vehicle.intervention.parkedSettingsBlockReason
import ai.pleos.playground.sample.vehicle.navigation.*
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DrivingAssistState(
    val blockedReason: String = "차량 상태를 확인하고 있어요",
    val busy: Boolean = false,
    val testBusy: Boolean = false,
    val automaticEnabled: Boolean = false,
    val voiceReady: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    val starting: Boolean = false,
    val message: String = "주행 중 피로 신호에 따라 공조·음성으로 도와드려요",
    val voiceError: String? = null,
    val loading: Boolean = false,
    val session: ServiceAreaReviewSession? = null,
    val selected: RestPlace? = null,
    val road: String = "",
    val direction: String = "",
    val confirmed: Boolean = false,
    val candidate: RestCandidate? = null,
    val routeMessage: String = "현재 경로의 휴게소를 확인해 주세요",
) {
    val canConfigure: Boolean get() = blockedReason.isEmpty() && !busy && !starting
    val summary: String get() = assistReadiness(automaticEnabled, voiceReady)
}

internal fun assistReadiness(enabled: Boolean, voiceReady: Boolean): String = when {
    !enabled -> "주행 도움 꺼짐"
    voiceReady -> "공조·음성 도움 켜짐"
    else -> "음성 준비 필요"
}

internal fun assistVoiceMessage(phase: VoicePhase): String? = when (phase) {
    VoicePhase.PREPARING, VoicePhase.CHECKING -> "음성 안내를 준비하고 있어요"
    VoicePhase.SPEAKING -> "음성 안내 중 · 질문이 끝난 뒤 답해 주세요"
    VoicePhase.WAITING -> "응답을 기다리고 있어요 · 지금 말씀해 주세요"
    VoicePhase.LISTENING -> "응답을 듣고 있어요"
    VoicePhase.APPLYING_ROUTE -> "휴게소 경유지 적용을 확인하고 있어요"
    else -> null
}

/** Normal app setup. Reads real navigation only; never creates consent tokens or writes a waypoint. */
class DrivingAssistViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DriveProbeApplication
    private val module = app.interventions
    private val _state = MutableStateFlow(DrivingAssistState())
    val state = _state.asStateFlow()
    private var lookup: Job? = null
    private var preparing: Job? = null
    private var permissionPending = false

    init {
        viewModelScope.launch {
            while (isActive) {
                val blocked = parkedReason()
                val busy = diagnosticsBusy() || module.controller.isBusy
                _state.update { it.copy(blockedReason = blocked, busy = busy, testBusy = diagnosticsBusy(),
                    automaticEnabled = module.controller.status.value.enabled,
                    voiceReady = DrivingVoiceService.ready.value, voiceError = DrivingVoiceService.error.value,
                    phase = module.voicePhase.value) }
                if (blocked.isNotEmpty() || busy) {
                    lookup?.cancel()
                    _state.update { it.copy(session = null, selected = null, confirmed = false, candidate = null) }
                }
                delay(250)
            }
        }
    }

    private fun parkedReason(): String = app.vehicleRepository.let {
        parkedSettingsBlockReason(it.gear.value, it.vehicleSpeed.value, it.hasSpeedSignal.value, it.uxRestriction.value)
    }

    private fun diagnosticsBusy() = module.voiceTests.state.value.running ||
        module.dangerNavigationProbe.isBusy || module.navigationProbeController.isBusy

    private fun requireEditable(includeProduction: Boolean = true) {
        check(parkedReason().isEmpty()) { parkedReason() }
        check(!diagnosticsBusy() && (!includeProduction || !module.controller.isBusy)) { "진행 중인 도움이 끝난 뒤 다시 시도해 주세요" }
    }

    fun startHelp(context: Context, requestPermission: () -> Unit) {
        if (_state.value.starting || lookup?.isCompleted == false) return
        try { requireEditable() } catch (e: Exception) { _state.update { it.copy(message = e.message ?: "정차 상태를 확인해 주세요") }; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            _state.update { it.copy(message = "음성 도움에 필요한 마이크 권한을 허용해 주세요") }
            requestPermission()
        } else prepare(context)
    }

    fun onPermissionResult(context: Context, granted: Boolean) {
        if (!permissionPending) return
        permissionPending = false
        if (!granted) { _state.update { it.copy(message = "마이크 권한이 없어 음성 도움을 준비하지 않았어요") }; return }
        try { requireEditable(); prepare(context) }
        catch (e: Exception) { _state.update { it.copy(message = e.message ?: "정차 후 다시 준비해 주세요") } }
    }

    private fun prepare(context: Context) {
        module.setAutomaticEnabled(true)
        _state.update { it.copy(starting = true, automaticEnabled = true, message = "음성 도움을 준비하고 있어요") }
        DrivingVoiceService.prepare(context)
        preparing = viewModelScope.launch {
            val result = withTimeoutOrNull(10_000L) {
                combine(DrivingVoiceService.ready, DrivingVoiceService.error) { ready, error -> ready to error }
                    .first { it.first || it.second != null }
            }
            _state.update { it.copy(starting = false, voiceReady = DrivingVoiceService.ready.value,
                message = if (result?.first == true) "주행 도움을 켰어요. 질문·응답 중에만 마이크를 사용해요"
                else result?.second ?: "음성 준비가 늦어지고 있어요. 정차 중 다시 시도해 주세요") }
        }
    }

    fun stopHelp(context: Context) {
        try { requireEditable(includeProduction = false) }
        catch (e: Exception) { _state.update { it.copy(message = e.message ?: "정차 후 중지해 주세요") }; return }
        permissionPending = false
        preparing?.cancel()
        module.setAutomaticEnabled(false)
        DrivingVoiceService.stop(context)
        _state.update { it.copy(starting = false, automaticEnabled = false, voiceReady = false, message = "주행 도움을 껐어요. 이미 적용한 공조 설정·경유지는 유지돼요") }
    }

    fun loadServiceAreas() = lookup {
        _state.update { it.copy(session = null, selected = null, confirmed = false, candidate = null,
            road = "", direction = "", routeMessage = "현재 안내 경로의 휴게소를 확인하고 있어요") }
        val session = app.serviceAreaReview.load()
        requireEditable()
        _state.update { it.copy(session = session, routeMessage = if (session.places.isEmpty())
            "다음 휴게소를 내비 즐겨찾기에 저장한 뒤 다시 불러와 주세요" else "진행 방향에 맞는 시설을 직접 선택해 주세요") }
        preview()
    }

    private suspend fun preview() {
        try {
            // preview() cannot mint or invalidate the proposal currently used by a voice conversation.
            val candidate = app.restNavigation.preview().first()
            requireEditable()
            _state.update { it.copy(candidate = candidate, routeMessage = "이 경로에서 안내할 휴게소를 확인했어요") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(candidate = null, routeMessage = e.message ?: "휴게소를 다시 확인해 주세요") } }
    }

    fun select(place: RestPlace) {
        if (!_state.value.canConfigure || _state.value.loading || _state.value.session?.places?.contains(place) != true) return
        _state.update { it.copy(selected = place, confirmed = false) }
    }
    fun road(value: String) { if (_state.value.canConfigure && !_state.value.loading) _state.update { it.copy(road = value.take(100), confirmed = false) } }
    fun direction(value: String) { if (_state.value.canConfigure && !_state.value.loading) _state.update { it.copy(direction = value.take(100), confirmed = false) } }
    fun confirmDirection(value: Boolean) { if (_state.value.canConfigure && !_state.value.loading) _state.update { it.copy(confirmed = value) } }

    fun saveServiceArea() = lookup {
        val s = _state.value
        app.serviceAreaReview.confirm(checkNotNull(s.session), checkNotNull(s.selected), s.road, s.direction, s.confirmed)
        requireEditable()
        preview()
    }

    fun forgetServiceArea() = lookup {
        app.serviceAreaReview.forget(checkNotNull(_state.value.session))
        _state.update { it.copy(candidate = null, confirmed = false, routeMessage = "이 경로의 휴게소 확인을 해제했어요") }
    }

    private fun lookup(block: suspend () -> Unit) {
        if (lookup?.isCompleted == false) return
        try { requireEditable() } catch (e: Exception) { _state.update { it.copy(routeMessage = e.message ?: "정차 후 확인해 주세요") }; return }
        _state.update { it.copy(loading = true) }
        lookup = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withTimeout(30_000L) { block() } }
            catch (e: TimeoutCancellationException) { _state.update { it.copy(routeMessage = "내비 응답이 늦어지고 있어요. 잠시 후 다시 불러와 주세요", candidate = null) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(routeMessage = e.message ?: "휴게소 정보를 확인하지 못했어요", candidate = null) } }
            finally { _state.update { it.copy(loading = false) } }
        }
    }

    fun leaveServiceAreaSettings() { lookup?.cancel() }
}
