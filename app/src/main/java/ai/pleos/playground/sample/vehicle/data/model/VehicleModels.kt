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

// =========================================================================
// [컨디션 판단] 기어·방향지시등·신호 이벤트 (3번 포지션 확장)
// =========================================================================

/** 기어 위치. AAOS GEAR_SELECTION 값(N=1, R=2, P=4, D=8)을 변환한 것. 모르면 UNKNOWN */
enum class GearPosition {
    PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN;

    companion object {
        /** android.car.VehicleGear 상수 → GearPosition (D1~D9 같은 수동 단수도 DRIVE로 본다) */
        fun fromAaos(value: Int?): GearPosition = when (value) {
            null -> UNKNOWN
            4 -> PARK
            2 -> REVERSE
            1 -> NEUTRAL
            8 -> DRIVE
            in 16..0x1000 -> DRIVE // GEAR_FIRST(16) ~ GEAR_NINTH(4096)
            else -> UNKNOWN
        }
    }
}

/** 방향지시등 상태 (Pleos SDK TurnSignalValue와 같은 3가지) */
enum class TurnSignalState { OFF, LEFT, RIGHT }

/**
 * 차량 신호 1건. 콜백이 올 때마다 하나씩 [VehicleRepository.signalEvents]로 나간다.
 *
 * StateFlow와 다른 점: 같은 값이 다시 와도(조향 약 3.1초 재전송) 빠짐없이 전달된다.
 * 컨디션 판단은 이 재전송으로 "신호가 살아 있음"을 판단하므로 StateFlow만으로는 부족하다.
 *
 * @property timeMs 수신 시각. SystemClock.elapsedRealtime() 기준(부팅 후 ms, 시계 변경 영향 없음)
 */
sealed interface VehicleSignalEvent {
    val timeMs: Long

    /** 조향각(deg). Pleos SDK 값 그대로 (Virtual Console과 부호 반대) */
    data class Steering(override val timeMs: Long, val angleDeg: Float) : VehicleSignalEvent

    /** 차량 속도(km/h). AAOS PERF_VEHICLE_SPEED(m/s) × 3.6 */
    data class Speed(override val timeMs: Long, val speedKmh: Float) : VehicleSignalEvent

    data class Gear(override val timeMs: Long, val gear: GearPosition) : VehicleSignalEvent

    data class TurnSignal(override val timeMs: Long, val state: TurnSignalState) : VehicleSignalEvent

    /** 브레이크 페달을 밟았는지 (Pleos SDK가 주는 값은 true/false뿐) */
    data class Brake(override val timeMs: Long, val pressed: Boolean) : VehicleSignalEvent
}
