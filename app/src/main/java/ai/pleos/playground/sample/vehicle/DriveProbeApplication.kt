package ai.pleos.playground.sample.vehicle

import android.app.Application
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.data.local.AppDatabase
import ai.pleos.playground.sample.vehicle.data.repository.DemoNaviFallbackRepository
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepository
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepositoryImpl
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepositoryImpl
import ai.pleos.playground.sample.vehicle.departure.DriveProbeDeparture
import ai.pleos.playground.sample.vehicle.departure.RoomDriverConditionRepository
import com.pleos.departure.DepartureConfig
import com.pleos.departure.DepartureFeature
import com.pleos.departure.port.DriverConditionRepository
import java.time.Clock

/**
 * 전역 의존성(Repository, Database) 관리 Application 클래스
 */
class DriveProbeApplication : Application() {

    lateinit var appDatabase: AppDatabase
        private set

    lateinit var vehicleRepository: VehicleRepository
        private set

    lateinit var naviHelperRepository: NaviHelperRepository
        private set

    /** 2번 '출발 전' 동작 수치. departureFeature 와 운전자 조건 설정 화면이 같은 값을 쓴다 */
    val departureConfig: DepartureConfig by lazy { DepartureConfig() }

    private val departureClock: Clock by lazy { Clock.system(departureConfig.timeZone) }

    /**
     * 운전자 조건 저장소(Room driver_conditions 테이블). departureFeature 와 운전자 조건 설정 화면(작업 #17)이 함께 쓴다.
     */
    val driverConditionRepository: DriverConditionRepository by lazy {
        RoomDriverConditionRepository(appDatabase.driverConditionDao(), departureClock)
    }

    /**
     * 2번 '출발 전'(출발 마감·반경 확인·무료도로 확인) 진입점. 처음 접근할 때 만든다.
     * 사용: `departureFeature.createSession(scope)` → `session.state` 관찰. docs/departure/README.md 참고.
     * 운전자 조건은 Room(appDatabase 의 driver_conditions 테이블)에 저장된다.
     */
    val departureFeature: DepartureFeature by lazy {
        DriveProbeDeparture.createFeature(
            vehicleRepository = vehicleRepository,
            naviHelperRepository = naviHelperRepository,
            config = departureConfig,
            clock = departureClock,
            driverConditionRepository = driverConditionRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. 로컬 Room DB 초기화
        appDatabase = AppDatabase.getInstance(this)

        // 2. Vehicle SDK 래핑 Repository 초기화
        vehicleRepository = VehicleRepositoryImpl(this).apply {
            initialize()
        }

        // 3. NaviHelper Repository 초기화
        naviHelperRepository = NaviHelperRepositoryImpl(this).let { real -> // context: NaviHelper SDK 연결용(조회 계열)
            // [시연] debug 빌드에서만: 내비가 응답하지 않으면 '출발 전' 조회 4개를 예시 값으로 대신한다(화면에 "내비 예시 데이터" 표시)
            if (BuildConfig.DEBUG) DemoNaviFallbackRepository(real) else real
        }

        // 4. 컨디션 판단 엔진 시작 (3번 포지션). 상태는 ConditionModule.engine.state 로 구독
        ConditionModule.init(this, vehicleRepository, ConditionModule.Mode.REAL)
        ai.pleos.playground.sample.vehicle.trip.TripModule.init(this) // 5. 주행 기록·종료 요약 (5번 포지션)
    }

    override fun onTerminate() {
        vehicleRepository.release()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: DriveProbeApplication
            private set
    }
}
