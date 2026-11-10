package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepository
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.SpeakResult
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepository as DriveProbeVehicleRepository
import com.pleos.departure.port.VehicleRepository as DepartureVehicleRepository

// 2번 '출발 전' core 포트(com.pleos.departure.port) ↔ 1번 Repository 어댑터.
// 어댑터는 1번 타입 → core 도메인 모델 변환만 한다(변환 규칙은 DepartureMappers).
// 이름이 같은 VehicleRepository 가 양쪽에 있어 import 별칭을 쓴다.

/**
 * 주행 규제 포트 ← 1번 VehicleRepository.drivingState.
 * DRIVING → RESTRICTED, IDLE/STOPPED → UNKNOWN (DepartureMappers.drivingRestriction KDoc 참고).
 * TODO(1번-연동): 1번이 CarUxRestrictions 기반 상태를 제공하면 그 값으로 바꾼다(주차 → UNRESTRICTED 를 낼 수 있게).
 */
class DriveProbeVehicleAdapter(
    private val vehicleRepository: DriveProbeVehicleRepository,
) : DepartureVehicleRepository {
    override fun observeDrivingRestriction(): Flow<DrivingRestriction> =
        vehicleRepository.drivingState
            .map(DepartureMappers::drivingRestriction)
            .distinctUntilChanged()
}

/**
 * 내비 포트 ← 1번 NaviHelperRepository 의 조회 계열(getDestinationInfo·getRouteStateInfo·requestTrip·getCurrentLocation).
 * 1번 기존 searchConditionAdaptedRoute·startNavigation 등은 부르지 않는다(경로 옵션 무시·기존 안내 덮어쓰기 — README 참고).
 * 콜백 대기 제한(타임아웃)·한 번에 한 요청은 1번 NaviHelperRepositoryImpl 이 맡는다.
 *
 * @param acceptMockValues NaviHelper 서비스 미연결 시 1번 Repository 가 돌려주는 Mock 값(isMock = true)을 쓸지.
 *   기본 false: Mock 값은 Unavailable(NOT_SUPPORTED)로 바꿔 core 가 보수적 기본값(ETA 60분 등)으로 판정하게 한다.
 *   서비스 없는 환경에서 시연할 때만 true(Mock ETA 는 출처 ESTIMATED 로 표시).
 */
class DriveProbeNaviAdapter(
    private val naviHelperRepository: NaviHelperRepository,
    private val clock: Clock,
    private val acceptMockValues: Boolean = false,
) : NaviRepository {

    override suspend fun getCurrentLocation(): DataResult<GeoPoint> =
        DepartureMappers.location(naviHelperRepository.getCurrentLocation(), acceptMockValues)

    override suspend fun getDestination(): DataResult<NaviDestination> =
        DepartureMappers.destination(naviHelperRepository.getDestinationInfo(), acceptMockValues)

    override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> {
        val result = naviHelperRepository.requestTrip(
            latitude = destination.point.latitude,
            longitude = destination.point.longitude,
            poiName = destination.poiName.orEmpty(),
            poiId = destination.poiId.orEmpty(),
        )
        return DepartureMappers.trip(result, destination, clock.instant(), acceptMockValues)
    }

    override suspend fun getRouteState(): DataResult<RouteState> =
        DepartureMappers.routeState(naviHelperRepository.getRouteStateInfo(), acceptMockValues)

    /**
     * 안내 중 남은 거리·시간 ← 1번 currentDestination(요청 응답·푸시로 갱신되는 마지막 목적지).
     * 목적지 없음(null, 경로 취소 때의 0 값 푸시 포함)은 내보내지 않는다. tollFare 는 SDK 값이 늘 0이라 0으로 둔다(유료 판단에 쓰지 않음).
     * Mock 값(isMock)은 다른 조회와 같이 [acceptMockValues] 가 true 일 때만 내보낸다(1번 Impl 은 Mock 을 이 상태에 넣지 않지만 방어).
     * 2번 core 유스케이스는 지금 이 Flow 를 쓰지 않는다(화면 표시용 계약).
     */
    override fun observeRemainingTrip(): Flow<RemainingTrip> =
        naviHelperRepository.currentDestination
            .filterNotNull()
            .filter { acceptMockValues || !it.isMock }
            .map { info ->
                RemainingTrip(
                    remainingDistanceMeters = info.remainDistanceMeters.coerceAtLeast(0),
                    remainingTime = Duration.ofSeconds(info.remainTimeSeconds.coerceAtLeast(0).toLong()),
                    tollFare = 0,
                    basis = clock.instant(),
                )
            }
}

/**
 * 운전자 조건 저장소 — 인메모리 구현(앱을 다시 시작하면 사라진다).
 * TODO(1번-연동): 1번 Room 스키마는 건드리지 않았다. 운전자 조건 테이블(+ 서버 동기화 GET/PUT /v1/drivers/me/driver-condition)이
 *   생기면 이 클래스를 그 Repository 위 어댑터로 바꾼다. 필드는 2번 문서 contracts/driver-condition-api.md 참고.
 *
 * @param initial 시작 조건. null 이면 "확인된 미설정"(getCondition 은 Unavailable(NO_DATA), observeCondition 은 Available(null)).
 */
class InMemoryDriverConditionRepository(
    initial: DriverCondition? = null,
) : DriverConditionRepository {

    private val condition = MutableStateFlow(initial)

    override suspend fun getCondition(): DataResult<DriverCondition> =
        condition.value?.let { DataResult.Available(it) }
            ?: DataResult.Unavailable(UnavailableReason.NO_DATA, "운전자 조건 미설정(인메모리)")

    override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> {
        this.condition.value = condition
        return DataResult.Available(condition)
    }

    override fun observeCondition(): Flow<DataResult<DriverCondition?>> =
        condition.map { DataResult.Available(it) }
}

/** TTS 포트 — 2번은 쓰지 않는다(Pleos TTS 준비 중). 계약 정렬용 최소 구현. */
class UnavailableTtsRepository : TtsRepository {
    override suspend fun speak(text: String): SpeakResult =
        SpeakResult.Unavailable(UnavailableReason.NOT_SUPPORTED, "TTS 미사용")
}

/** STT 포트 — 2번은 쓰지 않는다(Pleos STT 준비 중). 계약 정렬용 최소 구현. */
class UnavailableSttRepository : SttRepository {
    override suspend fun recognizeOnce(): DataResult<String> =
        DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED, "STT 미사용")
}
