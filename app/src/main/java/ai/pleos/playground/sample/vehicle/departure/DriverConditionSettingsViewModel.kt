package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.departure.model.DataResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
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
    )

    val state: StateFlow<DriverConditionSettingsState> = controller.state

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            app.vehicleRepository.uxRestriction.collect {
                controller.onDrivingRestrictionChanged(DepartureMappers.drivingRestriction(it))
            }
        }
    }

    /** 화면에 들어올 때마다 부른다(저장된 값 또는 기본값 + 현재 위치로 다시 채움). */
    fun open() {
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
}
