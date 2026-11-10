package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepository
import com.pleos.departure.DepartureConfig
import com.pleos.departure.DepartureDependencies
import com.pleos.departure.DepartureFeature
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import java.time.Clock
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository as DriveProbeVehicleRepository
import com.pleos.departure.port.VehicleRepository as DepartureVehicleRepository

/**
 * 2번 '출발 전' core 가 바깥에서 받는 의존성 묶음(1번 Repository 어댑터 + 시계 + 설정). DI 프레임워크 없이 생성자로 넘긴다.
 */
class DriveProbeDepartureDependencies(
    override val vehicleRepository: DepartureVehicleRepository,
    override val naviRepository: NaviRepository,
    override val driverConditionRepository: DriverConditionRepository,
    override val ttsRepository: TtsRepository,
    override val sttRepository: SttRepository,
    override val clock: Clock,
    override val config: DepartureConfig,
) : DepartureDependencies

/**
 * 2번 '출발 전' 기능 조립 지점(이 앱의 유일한 조립 코드).
 *
 * 사용(화면 쪽):
 * ```
 * val feature = DriveProbeApplication.instance.departureFeature
 * val session = feature.createSession(viewModelScope)   // 화면 하나에 세션 하나
 * session.state.collect { ... }                          // StateFlow<DepartureSessionState>
 * session.onVisible() / onHidden() / refresh() / confirmTollAvoidanceTurnedOn()
 * ```
 * 세션에는 release 가 없다. 넘긴 CoroutineScope 가 취소될 때 멈춘다(자세한 내용은 docs/departure/README.md).
 */
object DriveProbeDeparture {

    /**
     * 1번 Repository 위에 2번 기능을 만든다.
     * @param config 동작 수치(출발 여유 시간, 반경, 타임아웃, 경고 임계값 등). 바꾸려면 DepartureConfig().copy(...)로 넘긴다.
     * @param acceptMockNaviValues NaviHelper 미연결 시 Mock 값을 쓸지(DriveProbeNaviAdapter 참고). 기본 false.
     */
    fun createFeature(
        vehicleRepository: DriveProbeVehicleRepository,
        naviHelperRepository: NaviHelperRepository,
        config: DepartureConfig = DepartureConfig(),
        clock: Clock = Clock.system(config.timeZone),
        driverConditionRepository: DriverConditionRepository = InMemoryDriverConditionRepository(),
        acceptMockNaviValues: Boolean = false,
    ): DepartureFeature = DepartureFeature(
        DriveProbeDepartureDependencies(
            vehicleRepository = DriveProbeVehicleAdapter(vehicleRepository),
            naviRepository = DriveProbeNaviAdapter(naviHelperRepository, clock, acceptMockNaviValues),
            driverConditionRepository = driverConditionRepository,
            ttsRepository = UnavailableTtsRepository(),
            sttRepository = UnavailableSttRepository(),
            clock = clock,
            config = config,
        )
    )
}
