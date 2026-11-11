// TODO(1번-연동): 임시 계약. 1번 공통 Repository 확정 시 이름·시그니처를 이 파일에서만 수정할 것.
//   확정 여부/차이점은 docs/pleos/integration-guide.md "1번 연동 시 수정 목록" 참고.
//
// 이 파일 = 1번(공통 기반)이 제공할 것으로 예상하는 Repository 계약 전부. 1번 대응 포트는 다른 파일에 만들지 않는다.
//
// 공통 규칙
// - 시그니처에는 core 도메인 모델(com.pleos.departure.model)만 쓴다. SDK 타입(RouteInfo, TripInfo 등) 노출 금지.
// - Pleos SDK는 전부 "요청(반환값 없음) → 콜백" 구조다(api-notes/README.md "포트 설계 시사점 0").
//   콜백 → suspend/Flow 변환, 한 번에 한 요청(Mutex), 콜백 타임아웃(DepartureConfig 값)은 구현(1번 또는 그 위 어댑터) 몫이다.
// - "모름/사용 불가"는 예외가 아니라 DataResult.Unavailable 로 돌려준다. 구현은 일반적인 실패로 예외를 던지지 않는다
//   (코루틴 취소 CancellationException 은 예외로 전파).
// - 실제 SDK 대상(사용자 결정 11/09): Playground '사용 가능'인 NaviHelper, Vehicle State 만.
//   FusedLocation·TTS·STT·Vehicle Control 은 '준비 중'이라 사용하지 않는다(api-notes/playground-recheck.md §2).
//   예외: 주행 규제는 AAOS 표준 CarUxRestrictions 를 쓴다(감독관 결정 #11). Pleos SDK 에는 없고, Pleos 주행 규제 개발 가이드가
//   앱에 이를 요구하기 때문이다(api-notes/vehicle-state.md §12.3·§12.4). → VehicleRepository 참고.
package com.pleos.departure.port

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
import kotlinx.coroutines.flow.Flow

// TODO(1번-연동): 임시 계약. 1번 공통 Repository 확정 시 이름·시그니처를 이 파일에서만 수정할 것.
//   확정 여부/차이점은 docs/pleos/integration-guide.md "1번 연동 시 수정 목록" 참고.
/**
 * 차량 상태. 2번은 "주행 중인가(화면 제한)"만 필요하다.
 *
 * ## 주행 상태의 출처 = AAOS CarUxRestrictions (감독관 결정 #11)
 * - Pleos 주행 규제 개발 가이드: "Pleos Connect 앱은 플랫폼이 제공하는 CarUxRestrictions 상태를 기준으로 주행 중 UX 제한을
 *   적용해야 합니다" → 허용이 아니라 **필수 요구**다(api-notes/vehicle-state.md §12.3 [문서 확인]).
 * - Pleos SDK 5종(Vehicle 2.3.2 포함)에는 주행 상태·UX 제한 API가 없다. 속도 리스너는 삭제됐고 기어·주차 브레이크·점화도 없다(§12.1·12.2).
 * - **속도·기어·`DrivingMode`로 판단하지 않는다.** `DrivingModeType`은 NORMAL/ECO/SPORT 성향 모드일 뿐 주행 중 여부가 아니다(§12.1).
 *   `VehicleException.ERROR_DRIVING_RESTRICTED`도 호출 거부 신호일 뿐 상태 조회가 아니다.
 * - 이 포트는 "사용 가능 Pleos SDK(NaviHelper·Vehicle State)만" 규칙의 예외다 — Pleos 가 앱에 요구하기 때문.
 * - 필요한 권한명은 Pleos 문서에 없다 [미확인](§11.1·12.3).
 */
interface VehicleRepository {

    // TODO(1번-연동): 1번이 CarUxRestrictions 를 감싸 제공 요청(integration-guide 수정 목록 5). 메서드 이름은 추정.
    //   1번이 제공하지 않으면 2번 어댑터가 android.car 표준 API 를 직접 감싸는 안을 감독관이 결정한다(:app 쪽, core 는 그대로).
    /**
     * 주행 규제 상태를 계속 알려 준다. 구독 시 현재 값을 먼저 1회 내보낸다(StateFlow 성격).
     *
     * 대응: AAOS `CarUxRestrictionsManager`(`Car.createCar` → `getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)`) —
     * `getCurrentCarUxRestrictions()`로 첫 값, `registerListener` → `onUxRestrictionsChanged(CarUxRestrictions)`로 변경.
     * 호출 형태는 AOSP 지식이며 Pleos 문서에는 리스너 이름까지만 있다(§11.1 [불확실]).
     * 앱 프로세스에서 `android.car` 라이브러리·`Car.createCar`가 동작하는지는 [미확인](앱 프로브 없음, §11.1). 필요한 권한명도 [미확인].
     * - `isRequiresDistractionOptimization() == true` → [DrivingRestriction.RESTRICTED], false → [DrivingRestriction.UNRESTRICTED].
     *   (`getActiveRestrictions()` 비트 플래그는 지금 2번이 쓰지 않는다. 필요해지면 모델에 추가.)
     * - 값을 아직 못 받았거나 서비스 연결 실패·오류면 [DrivingRestriction.UNKNOWN]. 화면은 기본 설정
     *   (DepartureConfig.treatUnknownDrivingAsRestricted = true)에서 UNKNOWN 을 제한으로 본다 — 플랫폼도 unknown 을 전부 제한(0x1ff)으로 둔다.
     *   그러니 **주차 중이면 반드시 UNRESTRICTED 를 내보내야** 조작이 보인다.
     * - 이 에뮬레이터 이미지의 baseline 설정값(dumpsys 표): Parked = DO false(0x0), Idling·Moving = DO true(0x50), unknown = DO true(0x1ff)
     *   [실행 확인 2026-11-10 — 설정값만, §11.2]. 실제 전환은 Virtual Console 기어 Park/Drive 로 Parked(0x0)·Idling(0x50)만
     *   확인했고, Moving 상태로의 전환은 [미확인](속도 주입이 효과 없음, §11.3).
     */
    fun observeDrivingRestriction(): Flow<DrivingRestriction>
}

// TODO(1번-연동): 임시 계약. 1번 공통 Repository 확정 시 이름·시그니처를 이 파일에서만 수정할 것.
//   확정 여부/차이점은 docs/pleos/integration-guide.md "1번 연동 시 수정 목록" 참고.
/**
 * NaviHelper 래핑: 현재 위치, 내비 목적지, 사전 ETA, 현재 경로 상태, 안내 중 남은 거리·시간. 경로 안내 요청(requestRoute)은 두지 않는다(#5b).
 *
 * 근거: api-notes/navi-route.md, api-notes/eta-location.md §A·§B, api-notes/navi-runtime-check.md(에뮬레이터 실행, 2026-11-09).
 * - NaviHelper 는 "내비 탑재 차량에 한해" 동작한다 → 미탑재면 모든 메서드가 Unavailable(NOT_SUPPORTED).
 * - 콜백에 요청 ID가 없다 → 구현은 한 번에 한 요청만 처리(Mutex)해야 응답을 섞지 않는다.
 *   Mutex 만으로는 요청 대기 중에 외부 사건으로 온 푸시(예: 사람이 취소해서 온 0 값 onDestinationInfo)를 거르지 못한다
 *   → 목적지·상태는 "마지막으로 받은 상태" 캐시로 다루거나 0 값이 오면 짧게 1회 재조회한다(navi-runtime-check.md 7.3, 설계 판단).
 * - 리스너 콜백은 binder 스레드에서 온다 [실행 확인, navi-runtime-check.md 7.3].
 * - 메서드 이름은 1번이 SDK 이름을 따를 것으로 보고 SDK 메서드명(getCurrentLocationInfo/getDestinationInfo/requestTrip/getRouteStateInfo)에 맞췄다.
 * 1번 질문(README §4-1, §4-5): requestTrip·getRouteStateInfo 를 노출할 예정인가? onRouteStarted 푸시(tollFare)를 받아 둘 수 있는가? 콜백을 suspend/Flow로 바꿔 주는가?
 */
interface NaviRepository {

    // TODO(1번-연동): 위치 소스 = NaviHelper getCurrentLocationInfo 만 전제(사용자 결정 11/09). FusedLocation(준비 중)·ProximityAlert 사용 안 함.
    //   에뮬레이터에서 응답함 [실행 확인]. 단 에뮬레이터 내비 위치는 기본 위치(성남 수정구)에 고정되고 geo fix 를 따르지 않는다(navi-runtime-check.md 4절).
    //   1번 질문(README §4-2): 위치를 NaviRepository 에 둘지 별도 LocationRepository 로 둘지.
    /**
     * 현재 위치 1회. 대응: `getCurrentLocationInfo()` → `onCurrentLocationInfo(CurrentLocationInfo)`.
     * 위치가 없으면(내비 없음·콜백 미도착·유효하지 않은 값) Unavailable — 반경 유스케이스는 이때 목적지 판정만 한다.
     * 콜백 대기 제한 = DepartureConfig.locationCallbackTimeout.
     */
    suspend fun getCurrentLocation(): DataResult<GeoPoint>

    // TODO(1번-연동): 1번이 getDestinationInfo 를 노출할지, "목적지 없음"을 어떻게 표현할지 미정. 이름은 SDK 이름을 따른 추정.
    /**
     * 차량 내비에 지금 설정된 목적지 1회. 대응: `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)`.
     * 근거: navi-runtime-check.md 3.4·3.5 [실행 확인 2026-11-09] — 주차(P) 중에도 응답하고, 사람이 내비 화면에서 직접 넣은
     * 목적지도 좌표·이름·poiId 까지 준다. 응답 시간: 경로 있을 때 약 1.0초, 없을 때 50ms 미만.
     *
     * **(0,0) 판별 규칙 — 어댑터 몫**: 목적지가 없으면 SDK는 오류가 아니라 0 값 객체를 준다(3.2·3.8).
     * - `latitude == 0.0 && longitude == 0.0` 이면 [NaviDestination.NotSet]을 돌려준다(필요하면 poiId·address 빈 값도 함께 본다).
     * - `RouteStateInfo.naviMode`는 보조로만 쓴다. `naviMode != ROUTE_GUIDING`을 기준으로 쓰면 SIMULATION_GUIDING 을 잘못 거른다(리뷰 task-01c MINOR-5).
     * - **(0,0) 좌표를 [NaviDestination.Present]로 넘기지 않는다.** 넘기면 집 기준 약 13,170km → 항상 "반경 밖" 오경고가 난다.
     * - 목적지 없음을 `Unavailable(NO_DATA)`로 표현하지 않는다. Unavailable 은 "확인 불가"(내비 없음 NOT_SUPPORTED, 콜백 미도착 TIMEOUT 등)에만 쓴다.
     *
     * Place 변환: displayName = name → address(역지오코딩 구·동) → "내비 목적지" 순(SDK로 설정한 목적지는 name="" 로 옴),
     * poiId/poiName/address 는 빈 문자열이면 null, poiSubId 는 null 로 옴.
     * 콜백 대기 제한 = DepartureConfig.destinationCallbackTimeout.
     * 목적지 변경 감지는 하지 않는다: `onDestinationChanged`는 SDK로 설정·취소할 때도, 사람이 내비 화면에서 바꿀 때도 오지 않았다
     * [실행 확인 2026-11-10, §10.5]. 바뀔 때는 onRouteCancelled → (0 값 onDestinationInfo) → onRouteStarted 순서다. 그 사이 0 값은
     * "경로 교체 중"일 수 있다 → 호출자는 화면 진입·백그라운드 복귀마다 다시 조회한다(navi-runtime-check.md 7.3).
     *
     * **0 값 규칙 — 경로 교체 중과 안내 없음은 구분할 수 없다** (navi-runtime-check.md §11.1·11.2 [실행 확인 2026-11-10]):
     * - 경로 교체 중(onRouteCancelled ~ onRouteStarted)과 안내 없음(FREE_DRIVE)에서 `getDestinationInfo`는 **똑같은 0 값 객체**를
     *   오류·`onError` 없이 준다(lat/lng 0.0, name·address·poiId 빈 값, remainDistance·remainTime 0). `naviMode`도 둘 다 SAFE_GUIDING.
     *   → 어댑터는 둘 다 [NaviDestination.NotSet]으로 돌려준다(구분하려 하지 않는다).
     * - 구분은 화면 쪽 재확인이 맡는다: DepartureConfig.routeChangeRecheckInterval(5초) × routeChangeRecheckMaxAttempts(6회) = 30초.
     *   정상 교체는 최대 약 19초였다(사람 조작 18.9초 §10.5, SDK 변경 7.5~16.3초 §11.2). 30초가 지나도 0 값이면 "목적지 없음"으로 확정한다
     *   — 경로 탐색 실패(onError CAUSE_SEARCH_ROUTE)면 이전 안내도 복구되지 않고 0 값이 무기한 이어진다(§11.2).
     * - **도착 직전과 헷갈리지 않는다**: 도착 직전의 DestinationInfo 값(remainTime 이 0이 되는지, name·좌표가 유지되는지)은
     *   관측하지 않았다 [미확인]. 그래서 [설계 판단] NotSet 판정은 좌표 (0,0) + 빈 name 으로 하고, remainTime == 0 만으로는 하지 않는다.
     * - 실제 어댑터에 더 나은 경계: 리스너를 계속 구독할 수 있으면 `onRouteCancelled` 이후 `onRouteStarted`(또는 `onError`)까지를 교체 구간으로
     *   볼 수 있다(§11.5). 다만 포트에는 "교체 중" 표현이 없으므로 지금은 NotSet + 화면 재확인으로 충분하다(설계 판단).
     */
    suspend fun getDestination(): DataResult<NaviDestination>

    /**
     * 출발 전 사전 ETA. 대응: `requestTrip(TripRequest)` → `onTripInfo(TripInfo.distance m, eta s)`.
     * - **안내를 시작하지 않는 사전 조회다** [실행 확인 2026-11-09, navi-runtime-check.md 3.6]. 경로 없음·안내 중 모두 0.4~0.55초 안에 응답.
     * - `TripInfo.eta` = 남은 초(소요 시간). epoch 아님 [실행 확인]. 실차 확인 전까지 `eta > 10^9`이면 거부하는 검사는 어댑터 재량(설계 판단).
     * - 경로 옵션·출발 시각을 지정할 수 없다(TripRequest 에 필드 없음) → 결과는 "지금 출발" 기준.
     *   ETA는 **내비가 그 순간 쓰는 "유료도로 우회" 설정을 따른다**(안내 중 실행 확인 2026-11-10, §10.6. 안내 없을 때는 [미확인]).
     * - **[TripEstimate.routeOption] 채우기 규칙(감독관 결정 11/10, 리뷰 task-05 재검토 MAJOR-R1)**:
     *   - 안내 중(`naviMode == ROUTE_GUIDING`·SIMULATION_GUIDING)이면 **같은 시점에 읽은 `RouteStateInfo.routeOption`**을 넣는다
     *     (requestTrip 직전 또는 직후 getRouteStateInfo 1회. 그 ETA가 내비의 현재 유료도로 우회 설정을 따르기 때문).
     *   - 안내 중이 아니거나 경로 상태를 못 읽으면 null. core 는 null 을 RECOMMENDED 로 보고 FREE 안내 조건이면 보정한다(보수적).
     *   - 그래서 사용자가 "유료도로 우회"를 켠 뒤의 ETA(FREE 기준)에는 core 가 무료도로 보정을 다시 붙이지 않는다(이중 보정 방지).
     * - 문서상 onTripInfo 도착 보장이 없다("may be delivered") → 타임아웃(DepartureConfig.tripCallbackTimeout) 시 Unavailable(TIMEOUT).
     *   `CAUSE_SEARCH_TRIP` → Unavailable(SERVICE_ERROR).
     * - 성공 시 [TripEstimate.source] = SDK_MEASURED, basis = 콜백 수신 시각, destination = 요청한 목적지 좌표
     *   (비워 둬도 core 가 요청 좌표로 채운다. 이전 ETA 재사용 판정에 쓴다 — 리뷰 task-05 MAJOR-1).
     * - TripRequest 의 빈 문자열 poiId/poiName 은 허용된다 [실행 확인 2026-11-10, §10.6] → Place 의 null 은 빈 문자열로 넘긴다.
     */
    suspend fun requestTrip(destination: Place): DataResult<TripEstimate>

    // TODO(1번-연동): 1번이 getRouteStateInfo 와 onRouteStarted 푸시(tollFare)를 노출할지 미정. 이름은 SDK 이름을 따른 추정.
    /**
     * 지금 안내 중인 경로의 상태 1회(작업 #5b). 대응: `getRouteStateInfo()` → `onRouteStateInfo(RouteStateInfo)` +
     * 마지막으로 받은 `onRouteStarted(RouteStartInfo).tollFare`.
     *
     * 근거: api-notes/navi-runtime-check.md §10.4·10.5, reviews/task-01d-navi-runtime-check-2.md MAJOR-4 [실행 확인 2026-11-10,
     * 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5].
     * - `naviMode == ROUTE_GUIDING`(또는 SIMULATION_GUIDING) → [RouteState.Guiding], `SAFE_GUIDING` → [RouteState.NotGuiding].
     *   `NOT_WORKING`은 의미 [미확인] → Unavailable(NO_DATA)로 돌려준다(설계 판단). `isRouting`은 안내 중에도 false 라 쓰지 않는다.
     * - [RouteState.Guiding.appliedOption] = `RouteStateInfo.routeOption`(보고값). 사용자가 내비에서 "유료도로 우회"를 켜면 FREE.
     *   **"어린이 보호구역 우회"만 켜도 FREE**다(실행 확인 2026-11-11, 그 경로 통행료 700원) → FREE 만으로 유료도로 회피를 판정하지 않는다
     *   (ConditionRouteUseCase 는 tollFare == 0 일 때만 충족 — 작업 #27). 두 토글을 함께 켰을 때의 값은 [미확인](확인 폐기, 사용자 결정 11/11).
     * - [RouteState.Guiding.tollFare] = 어댑터가 리스너로 받아 둔 **현재 경로의** `RouteStartInfo.tollFare`. 다시 조회할 수 없다.
     *   캐시 규칙(리뷰 task-05 재검토 MINOR-R1) — 하나라도 해당하면 null 로 돌려준다:
     *   1. 못 받았다.
     *   2. `onRouteCancelled`(경로 교체 시작)를 받았다 → 버린다.
     *   3. 리스너를 (재)등록했다(백그라운드 해제, 1번 소유 리스너 재등록 등) → 떨어져 있던 동안 경로가 바뀌었을 수 있으므로 버린다.
     *   4. tollFare 는 받을 때의 내비 목적지 좌표와 함께 저장한다. 조회 시 현재 목적지 좌표와 다르면 null.
     *   `DestinationInfo.tollFare`는 쓰지 않는다(항상 0).
     * - 콜백 대기 제한 = DepartureConfig.routeStateCallbackTimeout. 넘으면 Unavailable(TIMEOUT), 내비 없음 → NOT_SUPPORTED.
     * - 부작용 없음(조회만).
     *
     * ## requestRoute 를 포트에서 뺀 이유 (#5b, 사용자 결정 11/10)
     * 이전 계약의 `requestRoute(Place, RouteOption)`은 삭제했다. 2번은 이미 내비에 설정된 목적지만 다루며, 그 목적지에 requestRoute 를
     * 부르면 안 되기 때문이다.
     * - 옵션이 적용되지 않는다: `requestRoute(FREE)`·`changeRouteOption(FREE)` 모두 무시됐다(§10.1~10.3).
     * - 부작용: 안내 중 requestRoute 는 확인 없이 기존 안내를 덮어쓴다(§10.8). SDK 요청은 `avoid_fare:false`로 만들어져
     *   사용자가 켜 둔 "유료도로 우회"를 되돌릴 위험이 있다([미확인], task-01d "#5 설계에 미치는 영향" 2).
     * 1번 NaviRepository 에 requestRoute 가 있어도 2번 어댑터는 노출하지 않는다. 계약은 "2번이 필요한 것"만 둔다.
     */
    suspend fun getRouteState(): DataResult<RouteState>

    /**
     * 안내 중 남은 거리·시간. 대응: `onDestinationInfo(DestinationInfo)` 푸시 / `getDestinationInfo()`.
     * - 실제 SDK는 경로 취소 때 **0 값 DestinationInfo 를 요청 없이 푸시**한다(navi-runtime-check.md 3.8). 어댑터는 0 값을
     *   "안내 끝"으로 보고 내보내지 않는다. 즉 이 Flow 는 안내 중인 값만 내보낸다.
     * - [RemainingTrip.tollFare]는 0이 올 수 있어 유료 판단에 쓰지 않는다.
     * - 화면이 보일 때만 구독한다(배터리·성능 심사 항목).
     */
    fun observeRemainingTrip(): Flow<RemainingTrip>

    // TODO(1번-연동): 1번 NaviHelperRepository 에 대응 메서드가 있어야 한다(작업 #28에서 1번 앱에 openNaviSettings 추가).
    /**
     * 내비 설정 화면(차량 설정의 "내비게이션" 탭)을 연다(작업 #28). 사용자가 "경로 → 유료도로 우회"를 **직접** 켜도록 돕는다.
     * 설정값을 바꾸지는 않는다 — 이 값을 읽거나 쓰는 SDK API 는 없다(api-notes/navi-route.md 끝 절).
     *
     * 대응: `requestNavigationScreen(NavigationScreenRequest.Settings)` [실행 확인 2026-11-11 — 차량 설정 "내비게이션" 탭이 열리고
     * "경로" 절에 "유료도로 우회" 토글이 보임]. 권한 `pleos.car.permission.NAVI_CUSTOM_MAP`(Normal).
     * 전역 "유료도로 우회"를 켜 두면 새 경로가 통행료 0원 경로로 안내된다 [실행 확인 1회, evidence/2026-11-11/manual-toll-free].
     *
     * 실패(미연결·권한 없음 등) → Unavailable. 화면은 실패 시 메뉴 위치를 글로 안내한다.
     * 기본 구현은 "지원 안 함" — 화면 열기를 못 하는 어댑터·테스트 가짜는 구현하지 않아도 된다.
     */
    suspend fun openNaviSettings(): DataResult<Unit> = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
}

// TODO(1번-연동): 임시 계약. 1번 공통 Repository 확정 시 이름·시그니처를 이 파일에서만 수정할 것.
//   확정 여부/차이점은 docs/pleos/integration-guide.md "1번 연동 시 수정 목록" 참고.
/**
 * 음성 출력. **Pleos TTS 준비 중 — 사용 안 함**(사용자 결정 11/09). 경고·출발 마감 안내는 화면 표시로 한다.
 * 1번과 이름을 맞추기 위해 계약만 둔다. 2번 유스케이스는 이 인터페이스에 의존하지 않는다.
 *
 * 근거: api-notes/tts-stt.md — `speak(text, isFinal)` → `EventListener.onDone/onStop/onError`.
 * 모드(ON_DEVICE/HYBRID)·Client ID/Secret 은 포트 밖(1번 설정) 관심사다.
 */
interface TtsRepository {
    /** 말하고 끝날 때까지 기다린다. SDK 준비 중이면 즉시 [SpeakResult.Unavailable]. */
    suspend fun speak(text: String): SpeakResult
}

// TODO(1번-연동): 임시 계약. 1번 공통 Repository 확정 시 이름·시그니처를 이 파일에서만 수정할 것.
//   확정 여부/차이점은 docs/pleos/integration-guide.md "1번 연동 시 수정 목록" 참고.
/**
 * 음성 인식. **Pleos STT 준비 중 — 사용 안 함**. 2번 기능은 STT가 필요 없다(api-notes/tts-stt.md "포트 설계 시사점").
 * 1번 Repository 이름 목록과 맞추기 위해서만 둔다.
 */
interface SttRepository {
    // TODO(1번-연동): 근거 없음 — 추정. SDK는 ResultListener.onUpdated(stt, completed) 연속 콜백이라 1번 형태를 알 수 없다.
    /** 한 번 듣고 최종 인식 문자열을 돌려준다. */
    suspend fun recognizeOnce(): DataResult<String>
}

// TODO(1번-연동): 임시 계약. 1번 공통 Repository 확정 시 이름·시그니처를 이 파일에서만 수정할 것.
//   확정 여부/차이점은 docs/pleos/integration-guide.md "1번 연동 시 수정 목록" 참고.
/**
 * 운전자 조건 조회·저장. Pleos SDK와 무관(리뷰 task-01 MINOR-1). 로컬 DB·서버 동기화는 1번 구현 몫이며
 * 서버 계약은 docs/pleos/contracts/driver-condition-api.md(작업 #6)에서 정의한다.
 */
interface DriverConditionRepository {

    // TODO(1번-연동): 근거 없음 — 추정. 1번 로컬 DB/동기화 API 모양 미정.
    /** 현재 운전자 조건. 저장된 적이 없으면 Unavailable(NO_DATA). */
    suspend fun getCondition(): DataResult<DriverCondition>

    // TODO(1번-연동): 근거 없음 — 추정(1번 로컬 DB/동기화 API 모양 미정). 실패 매핑은 계약 확정됨.
    /**
     * 저장 후 저장된 값을 돌려준다. 서버 검증 실패(400 VALIDATION_FAILED·MALFORMED_JSON, 415)는 Unavailable(INVALID_REQUEST)
     * (contracts/driver-condition-api.md 5.3 확정). 그 밖의 실패 매핑도 5.3 표를 따른다.
     */
    suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition>

    // TODO(1번-연동): 근거 없음 — 추정. 1번 로컬 DB 변경 알림 형태 미정.
    /**
     * 조건 변경을 계속 알려 준다. 구독 시 현재 상태를 먼저 1회 내보낸다(감독관 결정 11/10, 계약 5.4·9절 차이-9).
     * - `Available(condition)` : 저장된 조건. 해제 저장 후에는 null 이 아니라 빈 `DriverCondition()`.
     * - `Available(null)`      : **확인된 미설정만**(서버 404 + CONDITION_NOT_SET, 로컬 미전송 저장 없음).
     * - `Unavailable(reason)`  : 조회 실패(TIMEOUT, SERVICE_ERROR, PERMISSION_DENIED, 모르는 enum 등). 실패를 null 로 내보내지 않는다.
     *   로컬에 값이 있으면 실패 대신 로컬 값을 Available 로 내보내도 된다(계약 5.3 "로컬 DB 우선 규칙").
     */
    fun observeCondition(): Flow<DataResult<DriverCondition?>>
}
