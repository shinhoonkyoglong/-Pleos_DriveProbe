package ai.pleos.playground.sample.vehicle.data.model

import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit

/**
 * 실시간 주행 상태 데이터 모델
 */
data class VehicleDriveData(
    val speedKmh: Float = 0.0f,
    val steeringAngleDeg: Float = 0.0f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 주행 상태 분류
 */
enum class DrivingState {
    IDLE,       // 정차 중 / 시동 직후
    DRIVING,    // 주행 중
    STOPPED     // 주행 후 정지
}

/**
 * 공조(HVAC) 제어 요청 파라미터
 */
data class HvacControlCommand(
    val power: Boolean? = null,
    val ac: Boolean? = null,
    val auto: Boolean? = null,
    val targetTemperature: Float? = null,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val fanSpeed: Int? = null,
    val airCleaner: Boolean? = null,
    val targetArea: SeatValue = SeatValue.ROW_1_LEFT
)

/**
 * 창문 제어 파라미터
 * @param position 0: 완전히 닫힘 ~ 100: 완전히 열림 (차량 사양에 따라 변동)
 */
data class WindowControlCommand(
    val targetArea: WindowValue = WindowValue.ROW_1_LEFT,
    val position: Int
)
