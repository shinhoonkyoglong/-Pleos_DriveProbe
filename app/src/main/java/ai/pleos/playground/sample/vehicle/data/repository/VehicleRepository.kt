package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.sample.vehicle.data.model.DrivingState
import ai.pleos.playground.sample.vehicle.data.model.HvacControlCommand
import ai.pleos.playground.sample.vehicle.data.model.VehicleDriveData
import ai.pleos.playground.sample.vehicle.data.model.WindowControlCommand
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.flow.StateFlow

/**
 * 3번 포지션(피로 점수 계산)과 4번 포지션(공조/창문 제어)이 공통으로 사용하는 Vehicle SDK 래핑 Repository 인터페이스
 */
interface VehicleRepository {

    // =========================================================================
    // 3번 포지션 (조향각 및 속도 실시간 데이터 구독)
    // =========================================================================

    /** 실시간 조향각 (deg, 좌회전 음수, 우회전 양수) */
    val steeringAngle: StateFlow<Float>

    /** 실시간 차량 주행 속도 (km/h) */
    val vehicleSpeed: StateFlow<Float>

    /** 조향각과 속도, 타임스탬프가 결합된 실시간 주행 데이터 Flow */
    val driveData: StateFlow<VehicleDriveData>

    /** 현재 차량 주행 상태 (정차 중 / 주행 중 / 종료) */
    val drivingState: StateFlow<DrivingState>


    // =========================================================================
    // 4번 포지션 (HVAC 공조 및 창문 제어)
    // =========================================================================

    /** 공조 전원 On/Off */
    suspend fun setHvacPower(enabled: Boolean, area: SeatValue = SeatValue.ROW_1_LEFT): Result<Boolean>

    /** A/C 에어컨 가동 On/Off */
    suspend fun setHvacAc(enabled: Boolean, area: SeatValue = SeatValue.ROW_1_LEFT): Result<Boolean>

    /** 목표 온도 설정 */
    suspend fun setHvacTemperature(
        temperature: Float,
        unit: TemperatureUnit = TemperatureUnit.CELSIUS,
        area: SeatValue = SeatValue.ROW_1_LEFT
    ): Result<Float>

    /** 풍량 조절 */
    suspend fun setHvacFanSpeed(speed: Int, area: SeatValue = SeatValue.ROW_1_LEFT): Result<Int>

    /** 공조 자동(Auto) 모드 On/Off */
    suspend fun setHvacAuto(enabled: Boolean, area: SeatValue = SeatValue.ROW_1_LEFT): Result<Boolean>

    /** 공조 통합 커맨드 제어 */
    suspend fun controlHvac(command: HvacControlCommand): Result<Unit>

    /** 창문 위치 제어 (0: 닫힘 ~ 100: 완전 열림) */
    suspend fun setWindowPosition(
        position: Int,
        area: WindowValue = WindowValue.ROW_1_LEFT
    ): Result<Int>

    /** 창문 통합 커맨드 제어 */
    suspend fun controlWindow(command: WindowControlCommand): Result<Int>

    // -------------------------------------------------------------------------
    // 1단계 개입용 프리셋 (피로 감지 시 4번 팀원이 즉시 호출 가능한 고수준 편의 메서드)
    // -------------------------------------------------------------------------

    /** 1단계 피로 개입: 에어컨 가동, 풍량 증가, 운전석 창문 살짝 열기 환기 */
    suspend fun triggerFatigueVentilationIntervention(): Result<Unit>

    /** 환기용 창문 미세 개방 (기본 15%) */
    suspend fun openVentilationWindow(percentage: Int = 15): Result<Unit>

    /** 모든 창문 닫기 */
    suspend fun closeAllWindows(): Result<Unit>


    // =========================================================================
    // 에뮬레이터 개발 및 테스트용 Mock 데이터 주입 API (1번 포지션 역할)
    // =========================================================================

    /** 가짜 조향각 주입 */
    fun injectMockSteeringAngle(angleDeg: Float)

    /** 가짜 주행 속도 주입 */
    fun injectMockSpeed(speedKmh: Float)

    /** 가짜 주행 데이터 통합 주입 */
    fun injectMockDriveData(speedKmh: Float, angleDeg: Float)

    /** 주행 상태 강제 변경 */
    fun setDrivingState(state: DrivingState)

    /** 수명주기 초기화 및 해제 */
    fun initialize(): Boolean
    fun release()
}
