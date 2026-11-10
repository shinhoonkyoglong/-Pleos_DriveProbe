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

/**
 * 주행 중 UX 제한(AAOS CarUxRestrictions) 값의 출처
 */
enum class UxRestrictionSource {
    CAR_SERVICE,    // car service(CarUxRestrictionsManager)에서 읽은 실제 값
    UNAVAILABLE,    // car service 연결 실패·미지원 기기·오류 → 제한 값으로 채움(지어낸 "제한 없음" 아님). 2번 어댑터는 RESTRICTED 로 본다
    MOCK            // injectMockUxRestriction() 으로 넣은 테스트 값
}

/**
 * 주행 중 UX 제한 상태 (AAOS CarUxRestrictions 요약)
 *
 * 에뮬레이터 실측(Pleos Connect v2.1.0): 기어 Park → requiresDistractionOptimization=false, activeRestrictions=0x0 /
 * Drive → true, 0x50. 연결 실패·오류는 [UxRestrictionSource.UNAVAILABLE] + true, [ALL_RESTRICTED](플랫폼 unknown 값과 같음).
 *
 * @param requiresDistractionOptimization true 면 주행 중 제한 적용(조작 최소화 화면만 허용)
 * @param activeRestrictions CarUxRestrictions.getActiveRestrictions() 비트 플래그 (예: 0x50 = NO_SETUP 0x40 + NO_VIDEO 0x10)
 * @param source 값의 출처. CAR_SERVICE 만 실제 값이다
 */
data class UxRestrictionState(
    val requiresDistractionOptimization: Boolean,
    val activeRestrictions: Int,
    val source: UxRestrictionSource,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /** 모든 제한(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED = 0x1ff). 실패·모름일 때 쓴다 */
        const val ALL_RESTRICTED = 0x1ff

        /** car service 를 쓸 수 없을 때의 값 — 제한 쪽으로 둔다 */
        fun unavailable(): UxRestrictionState =
            UxRestrictionState(true, ALL_RESTRICTED, UxRestrictionSource.UNAVAILABLE)
    }
}
