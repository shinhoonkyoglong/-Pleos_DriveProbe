package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.departure.model.DataResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 운전자 조건 설정 화면 ViewModel. 1번 MainViewModel 처럼 AndroidViewModel 로 Application 의 Repository 를 꺼내 쓴다.
 * 로직은 [DriverConditionSettingsController]에 있고, 여기서는 코루틴 범위와 1번 Repository 연결만 한다.
 *
 * - 저장: Application.driverConditionRepository(Room, 작업 #15)
 * - 반경 중심 기본값: NaviHelperRepository.getCurrentLocation(). 내비 미연결 Mock 값은 쓰지 않는다(acceptMock = false)
 * - 주행 규제: VehicleRepository.uxRestriction → DepartureMappers.drivingRestriction
 */
class DriverConditionSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DriveProbeApplication

    private val controller = DriverConditionSettingsController(
        repository = app.driverConditionRepository,
        currentLocation = {
            val result = app.naviHelperRepository.getCurrentLocation()
            when (val point = DepartureMappers.location(result, acceptMock = false)) {
                is DataResult.Available -> DataResult.Available(
                    LocatedCenter(point.value, result.getOrNull()?.address.orEmpty())
                )
                is DataResult.Unavailable -> point
            }
        },
        defaultAllowedRadiusMeters = app.departureConfig.defaultAllowedRadiusMeters,
        treatUnknownDrivingAsRestricted = app.departureConfig.treatUnknownDrivingAsRestricted,
        radiusEnabled = app.departureConfig.radiusCheckEnabled,
    )

    val state: StateFlow<DriverConditionSettingsState> = controller.state

    private var loadJob: Job? = null

    /** 이 ViewModel 이 한 번이라도 [open]으로 값을 채웠는지. 프로세스가 죽었다 살아나면 false 로 시작한다. */
    private var loadedOnce = false

    init {
        viewModelScope.launch {
            app.vehicleRepository.uxRestriction.collect {
                controller.onDrivingRestrictionChanged(DepartureMappers.drivingRestriction(it))
            }
        }
    }

    /**
     * 화면에 들어올 때 부른다(저장된 값 또는 기본값 + 현재 위치로 다시 채움).
     *
     * @param reload 새로 들어온 경우 true. 설정 변경(차량 주간/야간 모드 전환, 글자 크기·언어 변경 등)으로 Activity 만 다시 만들어진 경우 false 를 넘기면
     *   ViewModel 에 남아 있는 저장 전 입력을 그대로 둔다(단, 아직 한 번도 채우지 않았으면 채운다).
     */
    fun open(reload: Boolean = true) {
        if (!reload && loadedOnce) return
        loadedOnce = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch { controller.load() }
    }

    fun refreshLocation() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { controller.refreshLocation() }
    }

    fun setNoNightDriving(value: Boolean) = controller.setNoNightDriving(value)
    fun setPreferTollFree(value: Boolean) = controller.setPreferTollFree(value)
    fun setAvoidHighway(value: Boolean) = controller.setAvoidHighway(value)
    fun increaseRadius() = controller.increaseRadius()
    fun decreaseRadius() = controller.decreaseRadius()

    fun save() {
        viewModelScope.launch { controller.save() }
    }

    private val _naviSettingsFailed = MutableStateFlow(false)

    /** 마지막 [openNaviSettings]가 실패했는가(내비 미연결 등). 화면은 메뉴 위치를 글로 안내한다. */
    val naviSettingsFailed: StateFlow<Boolean> = _naviSettingsFailed.asStateFlow()

    /**
     * [내비 설정 열기](2번 작업 #28): 차량 설정 "내비게이션" 탭을 연다. 사용자가 "경로 → 유료도로 우회"를 한 번 켜 두면
     * 다음 경로부터 무료도로로 안내된다(에뮬레이터 실측). 주행 중에는 화면이 버튼을 숨긴다.
     */
    fun openNaviSettings() {
        viewModelScope.launch {
            _naviSettingsFailed.value = app.naviHelperRepository.openNaviSettings().isFailure
        }
    }
}
