package ai.pleos.playground.sample.vehicle.departure

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 화면을 열 때 읽은 현재 위치(반경 중심 후보). [address]는 내비가 준 구·동 이름(없으면 빈 문자열). */
data class LocatedCenter(val point: GeoPoint, val address: String = "")

/**
 * 운전자 조건 설정 화면의 상태와 동작. Android 의존이 없는 일반 클래스라 JVM 단위 테스트로 검증한다
 * (ViewModel 은 [DriverConditionSettingsViewModel]이 이 클래스를 감싸 코루틴 범위만 붙인다).
 *
 * 동작 규칙
 * - [load]: 저장된 조건이 있으면 그 값을 보여 준다. 없으면 기본값([DriverConditionDefaults])을 채우고 현재 위치를 중심으로 읽는다.
 *   기본값은 [save] 전까지 저장하지 않는다(저장 안 됨 = 조건 미설정).
 * - 주행 중(RESTRICTED, 또는 UNKNOWN + treatUnknownDrivingAsRestricted)에는 모든 입력·저장·위치 다시 읽기를 무시한다.
 * - 저장 중([DriverConditionSettingsState.saving])에는 입력을 무시한다. 저장이 끝나면 화면 값이 저장값으로 바뀌므로,
 *   그 사이 입력을 받으면 사용자가 바꾼 값이 말없이 사라지기 때문이다.
 * - 스레드: 모든 메서드는 한 스레드(메인)에서 부른다는 전제다(ViewModel 의 viewModelScope).
 *
 * @param currentLocation 현재 위치 1회 조회. Mock(내비 미연결) 값은 호출하는 쪽에서 Unavailable 로 바꿔 넘긴다.
 * @param defaultAllowedRadiusMeters 저장된 반경이 null 일 때 보여 줄 값(DepartureConfig.defaultAllowedRadiusMeters)
 */
class DriverConditionSettingsController(
    private val repository: DriverConditionRepository,
    private val currentLocation: suspend () -> DataResult<LocatedCenter>,
    private val defaultAllowedRadiusMeters: Double,
    private val treatUnknownDrivingAsRestricted: Boolean,
    /** DepartureConfig.radiusCheckEnabled. false 면 반경·중심을 화면에 보이지 않고 저장 검사에서도 뺀다 */
    private val radiusEnabled: Boolean = true,
) {
    private val _state = MutableStateFlow(DriverConditionSettingsState(radiusEnabled = radiusEnabled))
    val state: StateFlow<DriverConditionSettingsState> = _state.asStateFlow()

    /** 화면을 열 때마다 부른다. 이전 입력은 버리고 저장된 값(없으면 기본값)으로 다시 채운다. */
    suspend fun load() {
        val restriction = _state.value.drivingRestriction
        _state.value = DriverConditionSettingsState(
            drivingRestriction = restriction,
            editingLocked = isLocked(restriction),
            radiusEnabled = radiusEnabled,
        )
        when (val saved = repository.getCondition()) {
            is DataResult.Available -> {
                val form = DriverConditionForm.from(saved.value, defaultAllowedRadiusMeters)
                _state.update { it.copy(loading = false, form = form, savedForm = form) }
            }
            is DataResult.Unavailable -> {
                // NO_DATA = 확인된 미설정. 그 밖(DB 오류 등)은 읽기 실패를 알리고 기본값을 보여 준다
                _state.update {
                    it.copy(
                        loading = false,
                        form = DriverConditionForm.defaults(),
                        savedForm = null,
                        loadFailed = saved.reason != UnavailableReason.NO_DATA,
                    )
                }
                fetchLocation()
            }
        }
    }

    /** 사용자 "다시 시도" / "지금 위치로 바꾸기". 주행 중이면 무시한다. */
    suspend fun refreshLocation() {
        if (_state.value.editingLocked) return
        fetchLocation()
    }

    fun onDrivingRestrictionChanged(restriction: DrivingRestriction) {
        _state.update { it.copy(drivingRestriction = restriction, editingLocked = isLocked(restriction)) }
    }

    fun setNoNightDriving(value: Boolean) = edit { it.copy(noNightDriving = value) }
    fun setPreferTollFree(value: Boolean) = edit { it.copy(preferTollFree = value) }
    fun setAvoidHighway(value: Boolean) = edit { it.copy(avoidHighway = value) }
    fun increaseRadius() = edit { it.increaseRadius() }
    fun decreaseRadius() = edit { it.decreaseRadius() }

    /** 입력값을 검증하고 저장한다. 오류가 있으면 저장하지 않고 [DriverConditionSettingsState.errors]에 남긴다. */
    suspend fun save() {
        val current = _state.value
        // 위치를 읽는 중에는 저장하지 않는다(중심이 빈 채로 저장되는 것을 막음)
        if (current.editingLocked || current.saving || current.loading || current.locationStatus == LocationStatus.LOADING) return
        val errors = current.form.validate(radiusEnabled)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, saveResult = null) }
            return
        }
        _state.update { it.copy(saving = true, errors = emptyList(), saveResult = null) }
        when (val stored = repository.saveCondition(current.form.toCondition(radiusEnabled))) {
            is DataResult.Available -> {
                val storedForm = DriverConditionForm.from(stored.value, defaultAllowedRadiusMeters)
                _state.update {
                    it.copy(saving = false, form = storedForm, savedForm = storedForm, loadFailed = false, saveResult = SaveResult.SAVED)
                }
            }
            is DataResult.Unavailable -> _state.update { it.copy(saving = false, saveResult = SaveResult.FAILED) }
        }
    }

    private suspend fun fetchLocation() {
        _state.update { it.copy(locationStatus = LocationStatus.LOADING) }
        when (val located = currentLocation()) {
            is DataResult.Available -> _state.update {
                it.copy(
                    form = it.form.copy(
                        centerLatitude = located.value.point.latitude,
                        centerLongitude = located.value.point.longitude,
                    ),
                    centerAddress = located.value.address.ifBlank { null },
                    locationStatus = LocationStatus.IDLE,
                    errors = emptyList(),
                    saveResult = null,
                )
            }
            // 이미 중심이 있으면(저장된 값·이전에 읽은 값) 그대로 두고 실패만 알린다
            is DataResult.Unavailable -> _state.update { it.copy(locationStatus = LocationStatus.FAILED) }
        }
    }

    private fun edit(change: (DriverConditionForm) -> DriverConditionForm) {
        val current = _state.value
        if (current.editingLocked || current.loading || current.saving) return
        _state.update { it.copy(form = change(it.form), errors = emptyList(), saveResult = null) }
    }

    private fun isLocked(restriction: DrivingRestriction): Boolean = when (restriction) {
        DrivingRestriction.UNRESTRICTED -> false
        DrivingRestriction.RESTRICTED -> true
        DrivingRestriction.UNKNOWN -> treatUnknownDrivingAsRestricted
    }
}

/**
 * 설정 화면 상태.
 * @property savedForm 지금 DB 에 저장된 값(화면 단위). null 이면 아직 한 번도 저장하지 않았다(조건 미설정)
 * @property loadFailed 저장된 조건을 읽지 못했다(DB 오류 등). 기본값을 보여 주는 중이다
 * @property centerAddress 이번에 읽은 현재 위치의 구·동 이름. 저장된 중심에는 없다(DB 에 주소를 두지 않음)
 * @property errors 마지막 [DriverConditionSettingsController.save] 시도의 입력 오류
 * @property radiusEnabled 반경 기능을 쓰는가. false 면 화면에 허용 반경·반경 중심 줄을 그리지 않는다
 */
data class DriverConditionSettingsState(
    val radiusEnabled: Boolean = true,
    val loading: Boolean = true,
    val form: DriverConditionForm = DriverConditionForm.defaults(),
    val savedForm: DriverConditionForm? = null,
    val loadFailed: Boolean = false,
    val centerAddress: String? = null,
    val locationStatus: LocationStatus = LocationStatus.IDLE,
    val drivingRestriction: DrivingRestriction = DrivingRestriction.UNKNOWN,
    val editingLocked: Boolean = true,
    val saving: Boolean = false,
    val errors: List<DriverConditionFormError> = emptyList(),
    val saveResult: SaveResult? = null,
) {
    val saveStatus: SaveStatus
        get() = when {
            savedForm == null -> SaveStatus.NOT_SAVED
            form == savedForm -> SaveStatus.SAVED
            else -> SaveStatus.MODIFIED
        }
}

/** 저장 상태(화면 맨 위 안내) */
enum class SaveStatus {
    /** 저장된 조건이 없다. 기본값을 보여 주는 중 — "아직 저장 안 됨" */
    NOT_SAVED,

    /** 화면 값 = 저장된 값 */
    SAVED,

    /** 저장된 값이 있지만 화면에서 바꿨고 아직 저장하지 않았다 */
    MODIFIED,
}

enum class LocationStatus { IDLE, LOADING, FAILED }

/** 마지막 저장 시도 결과 */
enum class SaveResult { SAVED, FAILED }
