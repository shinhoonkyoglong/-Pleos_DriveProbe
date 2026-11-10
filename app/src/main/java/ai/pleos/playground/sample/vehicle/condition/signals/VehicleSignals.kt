package ai.pleos.playground.sample.vehicle.condition.signals

import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 컨디션 판단이 받는 차량 신호의 입구. 실제 차량([RepositoryVehicleSignals])과 시연 재생([ReplayVehicleSignals])이
 * 같은 모양이라, engine과 화면은 어느 쪽인지 몰라도 된다(재생이면 [isReplay]로 "시연 모드"만 표시).
 */
interface VehicleSignals {
    /** 시연 재생 중이면 true → 화면에 "시연 모드"를 반드시 표시한다 */
    val isReplay: Boolean

    /** 신호 콜백 1건씩 (같은 값 재전송 포함, 시간순) */
    val events: Flow<VehicleSignalEvent>

    /** 현재 기어. 반응 체크 시작/중단 조건에 쓴다 */
    val gear: StateFlow<GearPosition>

    /** 현재 속도(km/h). 아직 못 받았으면 null(= 모름, 정지가 아님) */
    val speedKmh: StateFlow<Float?>

    fun start()
    fun stop()
}
