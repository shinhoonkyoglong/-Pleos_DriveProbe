package ai.pleos.playground.sample.vehicle.condition.signals

import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 실제 차량 신호. 팀 공용 [VehicleRepository]를 그대로 감싼다.
 * (조향·방향지시등·브레이크 = Pleos SDK, 속도·기어 = AAOS CarPropertyManager — 모두 Repository가 받는다)
 *
 * Repository 수명(initialize/release)은 Application이 관리하므로 start/stop은 아무것도 하지 않는다.
 */
class RepositoryVehicleSignals(
    private val repository: VehicleRepository,
    scope: CoroutineScope,
) : VehicleSignals {
    override val isReplay: Boolean = false

    override val events: Flow<VehicleSignalEvent> = repository.signalEvents

    override val gear: StateFlow<GearPosition> = repository.gear

    override val speedKmh: StateFlow<Float?> =
        combine(repository.vehicleSpeed, repository.hasSpeedSignal) { v, has -> if (has) v else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override fun start() = Unit
    override fun stop() = Unit
}
