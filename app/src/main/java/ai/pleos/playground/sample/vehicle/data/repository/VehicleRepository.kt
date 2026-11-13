package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.sample.vehicle.data.model.DrivingState
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.HvacControlCommand
import ai.pleos.playground.sample.vehicle.data.model.TurnSignalState
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionState
import ai.pleos.playground.sample.vehicle.data.model.VehicleDriveData
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import ai.pleos.playground.sample.vehicle.data.model.WindowControlCommand
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.flow.SharedFlow
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

    // -------------------------------------------------------------------------
    // 3번 포지션 확장 (컨디션 판단: 유효 구간 판정, 반응 체크 시작 조건)
    // -------------------------------------------------------------------------

    /** 기어 위치 (AAOS GEAR_SELECTION). 아직 못 받았으면 UNKNOWN */
    val gear: StateFlow<GearPosition>

    /** 방향지시등 (Pleos SDK TurnSignal) */
    val turnSignal: StateFlow<TurnSignalState>

    /** 브레이크 페달 밟음 여부 (Pleos SDK Brake) */
    val brakePressed: StateFlow<Boolean>

    /**
     * 실제 속도를 한 번이라도 받았는지. false면 [vehicleSpeed]의 0은 "정지"가 아니라 "모름"이다.
     * (CAR_SPEED 권한이 없거나 Car API를 못 쓰는 경우)
     */
    val hasSpeedSignal: StateFlow<Boolean>

    /**
     * 모든 차량 신호 콜백을 하나도 빠짐없이 흘려보내는 스트림 (같은 값 재전송 포함).
     * StateFlow는 같은 값을 다시 내보내지 않고 느린 수신자는 중간 값을 건너뛰므로, 신호를 하나하나 써야 하면 이것을 구독한다.
     */
    val signalEvents: SharedFlow<VehicleSignalEvent>

    /**
     * AAOS 속도·기어 구독을 (다시) 시작한다. CAR_SPEED 권한을 허용받은 직후 한 번 불러 주면 속도가 들어오기 시작한다.
     * @return 속도 구독 중이면 true
     */
    fun startCarPropertyUpdates(): Boolean

    /**
     * 주행 중 UX 제한 상태 (AAOS CarUxRestrictions, initialize() 때 car service 에 연결해 받는다)
     * - null: 아직 첫 값을 읽지 못함(모름 → 사용하는 쪽은 제한으로 취급)
     * - 연결 실패·미지원 기기·오류: source = UNAVAILABLE, requiresDistractionOptimization = true (제한 쪽)
     * Pleos 주행 규제 가이드가 이 값을 기준으로 주행 중 UX 제한을 하도록 요구한다. 위 drivingState(수동 설정)와는 별개다.
     */
    val uxRestriction: StateFlow<UxRestrictionState?>


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

    /** Raw SDK position in the area capability range; not percent. */
    suspend fun setWindowPosition(
        position: Int,
        area: WindowValue = WindowValue.ROW_1_LEFT
    ): Result<Int>

    /** 창문 통합 커맨드 제어 */
    suspend fun controlWindow(command: WindowControlCommand): Result<Int>

    // -------------------------------------------------------------------------
    // 1단계 개입용 프리셋 (피로 감지 시 4번 팀원이 즉시 호출 가능한 고수준 편의 메서드)
    // -------------------------------------------------------------------------

    /** Lower driver temperature by 1 C, increase fan by one step, verify both. */
    suspend fun triggerFatigueVentilationIntervention(): Result<Unit>

    /** 환기용 창문 미세 개방 (기본 15%) */
    suspend fun openVentilationWindow(percentage: Int = 15): Result<Unit>

    /** Close the supported front driver/passenger windows. */
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

    /** 가짜 기어 주입 */
    fun injectMockGear(gear: GearPosition)

    /** 가짜 방향지시등 주입 */
    fun injectMockTurnSignal(state: TurnSignalState)

    /** 가짜 브레이크 주입 */
    fun injectMockBrake(pressed: Boolean)

    /** 가짜 주행 중 UX 제한 상태 주입 (source = MOCK). 실제 car service 값이 바뀌면 그 값으로 덮어써진다 */
    fun injectMockUxRestriction(requiresDistractionOptimization: Boolean, activeRestrictions: Int = 0)

    /** 수명주기 초기화 및 해제 */
    fun initialize(): Boolean
    fun release()
}
