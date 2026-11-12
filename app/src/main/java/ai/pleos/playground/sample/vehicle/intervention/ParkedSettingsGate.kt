package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionState

/** Unknown or simulated vehicle state cannot enable parked configuration. */
fun parkedSettingsBlockReason(gear: GearPosition, speed: Float, known: Boolean, ux: UxRestrictionState?): String = when {
    gear != GearPosition.PARK -> "P 기어에서 설정할 수 있어요"
    !known -> "차량 속도를 확인하고 있어요"
    speed != 0f -> "차가 완전히 멈춘 뒤 설정해 주세요"
    ux?.source != UxRestrictionSource.CAR_SERVICE -> "차량의 화면 사용 가능 상태를 확인하고 있어요"
    ux.requiresDistractionOptimization -> "차량에서 화면 사용을 제한하고 있어요"
    else -> ""
}
