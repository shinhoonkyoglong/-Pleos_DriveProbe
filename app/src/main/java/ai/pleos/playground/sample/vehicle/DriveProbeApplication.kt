package ai.pleos.playground.sample.vehicle

import android.app.Application
import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.data.local.AppDatabase
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepository
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepositoryImpl
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepositoryImpl

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
        naviHelperRepository = NaviHelperRepositoryImpl()

        // 4. 컨디션 판단 엔진 시작 (3번 포지션). 상태는 ConditionModule.engine.state 로 구독
        ConditionModule.init(this, vehicleRepository, ConditionModule.Mode.REAL)
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
