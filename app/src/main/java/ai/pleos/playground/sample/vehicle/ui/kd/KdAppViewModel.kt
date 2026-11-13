package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.TripEndResult
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.repository.DemoNaviFallbackRepository
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** '계속운전' 앱 화면 목록 */
enum class KdPage {
    HOME,
    REACTION_CHECK,
    DRIVING,
    RECORDS,
    FAMILY_SUMMARY,
    REPORT,
    DRIVER_CONDITION,
    DEPARTURE_DETAIL,
    DRIVING_ASSIST,
    REST_STOP_SETTINGS,
    DEV_TOOLS,
}

/** 화면이 보는 차량·컨디션 상태 한 묶음 */
data class KdVehicleState(
    val condition: ConditionState = ConditionState.INITIAL,
    val isDriving: Boolean = false,
    val isReplay: Boolean = false,
    val gear: GearPosition = GearPosition.UNKNOWN,
    val speedKmh: Float? = null,
    val lastTrip: TripEndResult? = null,
)

data class KdUiState(
    val page: KdPage = KdPage.HOME,
    val vehicle: KdVehicleState = KdVehicleState(),
    val reactionHistory: List<ReactionScoreEntity> = emptyList(),
    val interventions: List<InterventionHistoryEntity> = emptyList(),
    /** 방금 끝난 주행 요약을 홈에 보여 줄지 */
    val showTripEnd: Boolean = false,
    /** 내비가 응답하지 않아 예시 값을 쓰는 중(시연용, debug 빌드) → "내비 예시 데이터" 표시 */
    val naviDemo: Boolean = false,
    /** 홈의 반응 체크 권유 방식 */
    val checkAdvice: CheckAdvice = CheckAdvice.OPTIONAL,
)

/**
 * 화면 이동 + 다른 파트 상태 모으기. 판단 로직은 하지 않는다(각 파트 모듈 그대로 사용).
 *
 * - 컨디션: ConditionModule.engine (시연 모드 전환 시 엔진이 바뀌므로 매번 다시 읽는다)
 * - 기록: Room reaction_scores / intervention_histories
 * - 주행이 시작되면(engine.isDriving) 주행 화면으로 자동 전환, 끝나면 홈 + 주행 요약
 */
class KdAppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("kd_home", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(KdUiState())
    val state: StateFlow<KdUiState> = _state.asStateFlow()

    private var wasDriving = false

    init {
        val db = DriveProbeApplication.instance.appDatabase
        viewModelScope.launch {
            db.reactionScoreDao().getAllScores().collect { list ->
                _state.update { it.copy(reactionHistory = list).withAdvice() }
            }
        }
        viewModelScope.launch {
            db.interventionHistoryDao().getDrivingHistories().collect { list ->
                _state.update { it.copy(interventions = list).withAdvice() }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                pollVehicle()
                delay(POLL_MS)
            }
        }
    }

    private fun pollVehicle() {
        if (!ConditionModule.isInitialized) return
        val engine = ConditionModule.engine
        val signals = ConditionModule.signals
        val vehicle = KdVehicleState(
            condition = engine.state.value,
            isDriving = engine.isDriving.value,
            isReplay = engine.isReplay,
            gear = signals?.gear?.value ?: GearPosition.UNKNOWN,
            speedKmh = signals?.speedKmh?.value,
            lastTrip = engine.lastTrip.value,
        )
        _state.update { s ->
            var page = s.page
            var showTripEnd = s.showTripEnd
            if (vehicle.isDriving && !wasDriving) {
                // 주행 시작: 화면 조작을 막고 한눈에 보는 주행 화면으로
                page = KdPage.DRIVING
                showTripEnd = false
            } else if (!vehicle.isDriving && wasDriving) {
                page = KdPage.HOME
                showTripEnd = true
            }
            s.copy(vehicle = vehicle, page = page, showTripEnd = showTripEnd, naviDemo = naviDemo()).withAdvice()
        }
        wasDriving = vehicle.isDriving
    }

    private fun naviDemo(): Boolean =
        (DriveProbeApplication.instance.naviHelperRepository as? DemoNaviFallbackRepository)?.usingDemo?.value == true

    fun open(page: KdPage) {
        // 주행 중에는 주행 화면에서 다른 화면으로 가지 않는다(Pleos 주행 중 UX 제한)
        if (_state.value.vehicle.isDriving && page != KdPage.DRIVING) return
        _state.update { it.copy(page = page) }
    }

    fun home() = open(KdPage.HOME)

    fun dismissTripEnd() = _state.update { it.copy(showTripEnd = false) }

    /** 홈의 [바로 출발]: 오늘은 체크를 권하지 않는다 */
    fun skipCheckToday() {
        prefs.edit().putString(KEY_SKIPPED_DATE, today().toString()).apply()
        _state.update { it.withAdvice() }
    }

    private fun KdUiState.withAdvice(): KdUiState {
        val now = System.currentTimeMillis()
        val advice = checkAdvice(
            checkedToday = reactionHistory.any { it.timestamp.toDate() == today() },
            skippedToday = prefs.getString(KEY_SKIPPED_DATE, null) == today().toString(),
            hourOfDay = ZonedDateTime.now(ZONE).hour,
            lastTripMaxLevel = vehicle.lastTrip?.maxLevel,
            recentWarning = interventions.any { it.interventionLevel >= 2 && now - it.timestamp < DAY_MS },
        )
        return if (advice == checkAdvice) this else copy(checkAdvice = advice)
    }

    private companion object {
        const val POLL_MS = 500L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val KEY_SKIPPED_DATE = "check_skipped_date"
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        fun today(): LocalDate = LocalDate.now(ZONE)
        fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZONE).toLocalDate()
    }
}
