package ai.pleos.playground.sample.vehicle

import android.app.Application
import ai.pleos.playground.sample.vehicle.data.local.AppDatabase
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepository
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepositoryImpl
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepositoryImpl
import ai.pleos.playground.sample.vehicle.departure.DriveProbeDeparture
import ai.pleos.playground.sample.vehicle.departure.RoomDriverConditionRepository
import com.pleos.departure.DepartureConfig
import com.pleos.departure.DepartureFeature
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

    /**
     * 2번 '출발 전'(출발 마감·반경 확인·무료도로 확인) 진입점. 처음 접근할 때 만든다.
     * 사용: `departureFeature.createSession(scope)` → `session.state` 관찰. docs/departure/README.md 참고.
     * 운전자 조건은 Room(appDatabase 의 driver_conditions 테이블)에 저장된다.
     */
    val departureFeature: DepartureFeature by lazy {
        val config = DepartureConfig()
        val clock = Clock.system(config.timeZone)
        DriveProbeDeparture.createFeature(
            vehicleRepository = vehicleRepository,
            naviHelperRepository = naviHelperRepository,
            config = config,
            clock = clock,
            driverConditionRepository = RoomDriverConditionRepository(appDatabase.driverConditionDao(), clock),
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
        naviHelperRepository = NaviHelperRepositoryImpl(this) // context: NaviHelper SDK 연결용(조회 계열)
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
