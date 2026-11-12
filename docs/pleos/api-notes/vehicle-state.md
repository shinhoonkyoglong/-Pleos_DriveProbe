# 차량 상태 (Vehicle SDK v2.3.2) + 주행 중 여부·속도·기어 + 주행 규제(Driver Distraction)

- 출처 (모두 확인일 2026-11-09):
  - Vehicle Introduction — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
  - Importing Vehicle SDK — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
  - Obtaining a domain object — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object
  - Get / Callback / Capability — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/get-property , `.../Vehicle/callback` , `.../Vehicle/check-capability`
  - Declaring permission — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
  - Error code — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code
  - Vehicle API Reference — `.../Vehicle/vehicle/ai.pleos.playground.vehicle/vehicle/entry`, `.../ai.pleos.playground.vehicle.api/brake/entry`
  - SDK 릴리즈 노트 (Vehicle SDK v2.3.2 · Shutdown API) — https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
  - SDK API 정책 (API 선택 기준 표) — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api
  - API 호환성 및 가용성 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability
  - FAQ - 앱 실행 환경 및 플랫폼 리소스 (클러스터 속도 조회 방법) — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq
  - 주행 규제 개발 가이드 — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
  - 지역별 주행 규제 정책 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy
  - 주행 안전을 고려한 설계 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
  - 앱 구조 및 동작 모델 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model
- 확인 수준: [문서 확인] + [문서 재확인(2026-11-09)] 주행 규제 개발 가이드·지역별 주행 규제 정책 + [Playground 확인(2026-11-09)] Vehicle State/Control 항목·상태. 실행 확인 안 함.

## 결론 먼저 (초보자용)
| 알고 싶은 것 | 어디서 얻나 | 근거 |
|---|---|---|
| **주행 중 화면 제한 여부** | AAOS 표준 `CarUxRestrictionsManager` (Pleos SDK 아님) | 주행 규제 개발 가이드 |
| **차량 속도** | AAOS `CarPropertyManager`의 `PERF_VEHICLE_SPEED` | FAQ "클러스터 속도 조회 방법" |
| **기어 상태** | AAOS Car API (SDK API 정책 표: "표준 데이터 — AAOS Car API — 속도, 기어 상태, …") | SDK API 정책. 정확한 프로퍼티 이름은 Pleos 문서에 없음 [불확실] |
| 브레이크 페달 | Vehicle SDK `Brake` | API Reference |
| 주행거리·주행시간 | Vehicle SDK `Odometer` | Vehicle intro 도메인 표 |

- Vehicle SDK v2.3.2에는 **속도·기어 API가 없다.** 릴리즈 노트 Shutdown API: "`VehicleSpeedListener` — 삭제. 리스너와 `onSpeedUpdated` 제거". `Vehicle` 클래스의 `getXxx()` 목록에도 `getGear()`가 없다(Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, HVAC, Light, Odometer, Safety, Seat, SideMirror, SteeringWheel, Tire, Toll, TurnSignal, Window, Wiper).
  - 주의: `Vehicle` 클래스 Reference의 "Callback API example"에는 아직 `vehicle.getGear()` / `GearSelectionListener`가 남아 있다. 함수 목록과 맞지 않으므로 **옛 예제로 보고 사용하지 말 것** [문서 불일치].
- 정책상 "동일하거나 유사한 기능의 API가 AAOS 표준에 이미 존재하는 경우, 해당 표준 API 사용을 권장하며 Pleos Connect SDK에서는 중복 기능을 제공하지 않습니다." → 속도/기어/주행 상태는 Android Automotive 표준 API 영역이다.

## A. 주행 규제 (Driver Distraction) — 가장 중요
- 원문: "Pleos Connect 앱은 플랫폼이 제공하는 `CarUxRestrictions` 상태를 기준으로 주행 중 UX 제한을 적용해야 합니다."
- 방법: AAOS `CarUxRestrictionsManager`에 `registerListener()` → `onUxRestrictionsChanged()`로 `CarUxRestrictions` 수신 → `isRequiresDistractionOptimization()`과 `getActiveRestrictions()` 비트 플래그 확인.
- 문서 샘플(Java):
  ```java
  if (mCurrentCarUxRestrictions.isRequiresDistractionOptimization()
          && (mCurrentCarUxRestrictions.getActiveRestrictions()
              & CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) != 0) {
      stopPlayingVideo();
  }
  ```
- "플랫폼이 지역별 규제 사양에 맞는 `ActiveRestrictions`를 내려주므로, 앱은 notify로 수신한 `CarUxRestrictions`만 반영하면 됩니다."
- 주행 규제는 "차량의 속도·기어·브레이크 등 하드웨어 신호와 지역별 안전 규정에 따라 적용"된다 → **앱이 속도/기어로 직접 판단하지 말고 CarUxRestrictions를 따른다.**
- **DO(Distraction Optimization) 미선언 앱**: Parking 사용 가능 / **Drive 사용 불가**(지역별 주행 규제 정책). DO를 선언하는 구체적 방법(매니페스트 메타데이터 등)은 Pleos 문서에 없다 [불확실 — AAOS 표준 `distractionOptimized` 메타데이터로 추정되나 Pleos 문서 근거 없음]. 재검토 11/09: 문서 검색 `distractionOptimized` → 결과 없음, `Distraction Optimization` → 지역별 주행 규제 정책 1건뿐. 주행 규제 개발 가이드는 DO를 선언한 앱도 규제가 활성화되면 CarUxRestrictions와 ActiveRestrictions를 확인해 기능별 UX 제한을 직접 적용해야 한다고만 한다. 여전히 [불확실].
- 지역별 기능 제한(한국은 "그외 지역" 표로 보임 [불확실]): Driving 시 키보드 ✅, 다이얼패드 ✅, 검색 ✅, 미디어 로그인/설정 ✅, 차량 설정 ⛔, 동영상 ⛔, 내비 검색 ✅, QR 로그인 ⛔, 매뉴얼(대용량 텍스트, `UX_RESTRICTIONS_LIMIT_CONTENT(32)`, `LIMIT_STRING_LENGTH(4)`) ⛔. 북미는 거의 전부 ⛔.
- 지역 코드: 시스템 프로퍼티 `product.sw.target_region` (`KOR`, `EUR`, `USA`, `CAN`) (FAQ).
- 설계 원칙(주행 안전을 고려한 설계): 큰 터치 영역·고대비·텍스트 최소화, 주행 중 애니메이션 최소화, 키보드보다 프리셋·최근 항목·음성 우선.
- 에뮬레이터에서 규제 강제 ON/OFF: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off` (emulator-injection.md).

## B. Vehicle SDK 사용법 (참고)
- 의존성: `implementation("ai.pleos.playground:Vehicle:2.3.2")` (빠른 시작 문서는 `2.3.1`로 적혀 있어 서로 다름 → 릴리즈 노트 기준 2.3.2)
- 구조: `Vehicle(context)` → `initialize()` → `getBrake()` 등 도메인 객체 → Capability/Get/Set/Callback → `release()`.
- "차량 통신은 IPC이므로 capability·get·set 결과는 즉시 반환되지 않고 `onComplete`·`onFailed` 비동기 람다로 전달됩니다. 비동기 콜백이 끝나기 전에 `release()`를 호출하지 않도록 주의"
- 예: Brake (Reference 그대로)
  ```kotlin
  interface Brake {
      @RequiresPermission("pleos.car.permission.CAR_DRIVE")
      abstract fun getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)
      @RequiresPermission("pleos.car.permission.CAR_DRIVE")
      abstract fun registerBrakePedalStatus(listener: BrakePedalStatusListener)
      abstract fun unregisterBrakePedalStatus(listener: BrakePedalStatusListener)
      abstract fun checkBrakePedalStatusCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)
  }
  ```
- 권한 형식: 조회 `pleos.car.permission.CAR_*`, 제어 `pleos.car.permission.CONTROL_CAR_*`. 예: DrivingMode·Brake·Odometer → `CAR_DRIVE`, Safety·Tire → `CAR_SAFETY`, EvBattery·Fuel → `CAR_ENERGY`.
- 오류: `onFailed(e: Exception)`, `e is VehicleException`이면 `e.errorCode`. 주목할 코드: **`ERROR_DRIVING_RESTRICTED` (주행 중 기능 제한)**, `ERROR_NOT_SUPPORTED_PROPERTY`, `ERROR_SECURITY_PERMISSION`, `ERROR_SERVICE_CONNECTION_FAILURE`.
- 호환성: "Vehicle SDK — 모델에 따라 다름. `checkXXXCapabilities` API로 확인."
- 사용 신청: "Vehicle State … 신청 없이 바로 사용 가능", "Vehicle Control SDK"는 준비 중(create-my-project).
  - [Playground 확인(2026-11-09)] Vehicle State 11개 항목 모두 "사용 가능": 차량 제원, 차량 주행 정보(브레이크 페달·드라이브 모드·크루즈 컨트롤·주행 거리), 도어/트렁크/프렁크, EV·연료, 공조, 라이트, 안전 경고등, 좌석, 사이드미러, 통행료 시스템, 창문. **속도·기어 항목은 목록에 없다** → "속도·기어는 AAOS" 결론과 일치.
  - Vehicle Control 8개 항목 모두 "준비 중". 설명 문구: 일부 API는 주행 중 제한될 수 있고 사전 승인된 권한이 필요(우리 파트는 Control 불필요).

## C. 속도 — AAOS CarPropertyManager
- FAQ 원문: "Pleos Connect 클러스터는 구글 `PERF_VEHICLE_SPEED` 프로퍼티로 차량 속도를 표시하므로, 클러스터와 동일한 속도를 쓰려면 `CarPropertyManager`로 `PERF_VEHICLE_SPEED`를 읽습니다." "필요한 권한을 선언한 후 `CarPropertyManager`를 획득하고, 현재 값 조회 또는 변경 이벤트 구독 API를 사용합니다."
- 필요한 권한 이름·코드 예제는 Pleos 문서에 없다(구글 문서로 위임) [불확실 — Android 공식 문서 확인 필요].
- 주의: "모델명을 기준으로 분기 처리하지 말고 `VehiclePropertyIds`와 `CarPropertyManager`를 사용하여 실제 지원 여부와 접근 가능 상태를 확인".

## 제약·주의
- 앱은 백그라운드에 남을 수 있고 포그라운드·백그라운드가 자주 바뀐다. "백그라운드에서 불필요한 상주, 타이머, 폴링 및 네트워크 요청을 최소화" (앱 구조 및 동작 모델).
- CPU: 내비 아닌 앱 주행 중 5% 미만, 정차 중 25% 이하, RAM 400MB 이하 (성능 및 리소스 효율 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt).
- 화면은 portrait(1 window)/landscape(2 window) 둘 다 설계. `screenOrientation` 미지정 시 portrait.

## 포트 설계 시사점
'출발 전' 기능이 차량 상태에서 필요한 것은 사실상 "지금 화면을 얼마나 단순하게 보여야 하나"뿐이다. 이 값의 근거는 Vehicle SDK가 아니라 **AAOS `CarUxRestrictions`**다. 그래서 VehicleRepository 포트에 속도·기어를 노출하지 말고 `drivingRestriction(): Flow<DrivingRestriction>`(예: `NONE` / `LIMITED(noKeyboard, limitContent, …)` 같은 우리 도메인 값)을 둔다. 어댑터는 `CarUxRestrictionsManager` 리스너를 Flow로 바꾸고, `getActiveRestrictions()` 비트 플래그를 우리 enum의 `Set`으로 매핑한다(플래그는 비트 OR 조합이라 Set이 맞다). "주행 중 여부"가 별도로 필요하면 `isRequiresDistractionOptimization()`에서 파생한다(속도·기어로 판단하지 않는 것이 문서 원칙). 1번 공통 기반이 VehicleRepository를 Pleos Vehicle SDK만으로 만들면 이 값이 없을 수 있다. 그러니 `CommonRepositoryContract.kt`에 "CarUxRestrictions 기반 Flow 필요"를 `TODO(1번-연동)` 메모로 남긴다. 모든 Vehicle SDK 호출은 콜백 + 모델별 미지원 가능성이 있으므로 포트 반환값은 nullable 또는 Unavailable 상태를 포함해야 한다.


## 11. 2026-11-10 실행 확인 — 주행 규제 수신 경로와 에뮬레이터 전환 (task #10)

- 담당 pleos-sdk-researcher. 환경: `emulator-5554` Pleos Connect v2.1.0 (API 34, `ro.build.type=user`). 증거: `evidence/2026-11-10/task10/driving-state.txt`. Pleos 문서는 재조회하지 않았다(기존 A절·릴리즈 노트 기록으로 충분, 로그인·약관 동의 불필요).

### 11.1 주행 상태·규제를 앱이 받는 방법 (사용 가능 SDK 기준)
- **Pleos Vehicle SDK(Vehicle State)에는 주행 상태·규제 수신 API가 없다 [문서 확인 — 위 A절, 속도·기어 API 삭제 기록].** 단서는 `VehicleException.ERROR_DRIVING_RESTRICTED`(제한 기능 호출이 거부될 때의 오류 코드)뿐이다. `Vehicle.getDrivingMode()`(DrivingMode 도메인)가 "주행 중 여부"를 준다는 문서 근거는 없다 [불확실 — 시험하지 않음].
- **NaviHelper에도 주행 상태·규제 API가 없다 [문서·관측 확인 — navi-route.md, navi-runtime-check.md 콜백 목록].**
- 문서상 유일한 경로는 **AAOS 표준 `CarUxRestrictionsManager`**(A절)다. 형태: `Car.createCar(context)` → `getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)` → `registerListener`의 `onUxRestrictionsChanged` / `getCurrentCarUxRestrictions()` → `isRequiresDistractionOptimization()`, `getActiveRestrictions()`. (이 호출 형태는 AOSP 지식이며 Pleos 문서에는 리스너 이름까지만 있다 [불확실].) 이는 Pleos SDK가 아니므로 "사용 가능 SDK는 Vehicle State·NaviHelper뿐"이라는 프로젝트 제약과 충돌한다. **결정 필요(감독관·사용자)**: (a) 1번 공통 기반이 `CarUxRestrictions`를 제공한다, (b) 이 앱이 `android.car` 표준 API를 직접 쓴다.
- 앱 프로세스에서 `android.car` 라이브러리·`Car.createCar`가 동작하는지, 필요한 권한이 무엇인지: **[미확인 — 앱 프로브를 만들지 않았고 Pleos 문서에 권한 기재 없음]**.

### 11.2 이 이미지의 UX 제한 설정(플랫폼 값) [실행 확인 2026-11-10 — 터미널]
`adb shell dumpsys car_service`의 `CarUxRestrictionsManagerService` baseline:

| 주행 상태 | Requires DO | Restrictions |
|---|---|---|
| Parked | false | 0x0 (제한 없음) |
| Idling | true | 0x50 |
| Moving | true | 0x50 |
| **unknown** | **true** | **0x1ff (전부 제한)** |

- 0x50은 AOSP 상수상 `UX_RESTRICTIONS_NO_SETUP`(0x40) + `UX_RESTRICTIONS_NO_VIDEO`(0x10)로 해석된다(상수 값은 AOSP 지식, 이 이미지에서 직접 확인하지는 않음). 한국 표(키보드·검색 허용, 동영상 금지)와 방향이 맞는다.
- **플랫폼 자체가 상태 unknown을 "전부 제한"으로 취급한다.** 앱이 규제 값을 못 받는 동안 제한 쪽으로 보이는 것이 플랫폼 기본과 같다.
- Max String length 120, Max Cumulative Content Items 21, Max Content depth 3.

### 11.3 에뮬레이터에서 주행 상태 전환
- **adb로는 불가(user 빌드)**: `adb root` → "adbd cannot run as root in production builds". `cmd car_service help` → `SecurityException: The command 'help' requires non-user build`. `cmd hmg_car_service`는 서비스가 없다(기존 기록). `car_service`의 VHAL 주입 명령도 같은 제한일 것으로 추정 [미시험].
- **Virtual Console HTTP API로 가능 [실행 확인 2026-11-10 — 터미널]**: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`.
  1. 콘솔 페이지를 브라우저로 열고 키 입력(`d`)을 하면 브릿지가 활성화된다(`GET /vehicle/state`의 `gear.bridge_ready` false → true). 활성화 전 `POST /vehicle/gear`는 `{"error":"emulator_inactive"}`.
  2. 활성화 후 `curl -X POST "http://127.0.0.1:8088/vehicle/gear?gear=Drive"` → `dumpsys car_service`가 `Current Driving State: 1`, `UXR: DO: true UxR: 80`(0x50). `gear=Park`로 되돌리면 `Current Driving State: 0`, `DO: false UxR: 0`.
  - 지원 기어: Park, Reverse, Neutral, Drive. 속도 0에 기어 D만으로 상태 1(Idling)이 된다. 속도 주입(`PUT /vehicle/controls`, 쿼리 `speed`·`speed_kph`·`accelerator`)은 효과가 없었다. 그래서 Moving 상태는 시험하지 못했다 [미확인].
- 시험 후 기어를 Park으로 복구했다.

### 11.4 포트 설계 시사점
- `treatUnknownDrivingAsRestricted = true`(주행 상태·규제를 모르면 제한으로 취급)는 플랫폼 기본(unknown = 0x1ff)과 같으므로 근거가 있다. 값 미수신·어댑터 오류·Fake의 unknown은 제한 쪽(큰 글씨·최소 입력)으로 보여야 한다.
- 에뮬레이터 시연은 Virtual Console 기어 변경으로 실제 규제(Park = 없음, Drive = 0x50)를 재현할 수 있다. adb 명령은 쓸 수 없다.
- 어댑터는 `getActiveRestrictions()` 비트 플래그를 `Set`으로, `isRequiresDistractionOptimization()`을 "주행 중 간주"로 매핑한다(A절 방침 유지). 수신 주체(우리 앱 vs 1번 공통 기반)는 11.1대로 결정이 필요하다.


## 12. 차량 SDK 재확인 (2026-11-10)

- 담당 pleos-sdk-researcher. 목적: 주행 중 여부·기어·속도·주차 브레이크·UX 제한을 알려 주는 Pleos SDK가 있는지 재확인. 확인일 모두 2026-11-10.
- 출처:
  - SDK 릴리즈 노트 (2026-10-19 릴리즈) — https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
  - SDK API 정책 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api
  - 주행 규제 개발 가이드 — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
  - Vehicle API Reference 패키지 `ai.pleos.playground.vehicle.api` — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/entry
  - `DrivingMode` — `.../ai.pleos.playground.vehicle.api/driving-mode/entry`, `DrivingModeType` — `.../ai.pleos.playground.vehicle.constant.control/driving-mode-type/entry`
  - Playground 메인 — https://developers.pleos.ai/playground
- 환경 한계: 이 세션의 브라우저는 Pleos 로그인이 안 된 상태였고(로그인 화면으로 이동됨) 로그인은 하지 않았다. 그래서 「계운」 프로젝트(My Project)의 "사용 가능" 표시는 이번에 **확인하지 못했다**. Reference 페이지는 `get_page_text`가 제목만 반환해 스크린샷으로 읽었다.

### 12.1 Q1. Vehicle SDK(릴리즈 노트 기준 최신 v2.3.2)에서 주행과 관련된 클래스·리스너
패키지 `ai.pleos.playground.vehicle.api`의 Types 목록은 Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, HVAC, Light, Odometer, Safety 등이다(스크린샷으로 Safety까지 직접 확인, 이후는 9절 기록 유지). 각 설명 문구:

| 항목 | 현재 함수 목록 존재 | 시그니처(문서 그대로) | 권한 | 수준 |
|---|---|---|---|---|
| 기어 | **없음** (`Vehicle`의 `getXxx()` 목록에 Gear 도메인 없음. 옛 Callback 예제에만 `getGear`·`GearSelectionListener` 잔존) | — | — | [문서 확인] 최신 버전 목록에 없음. 이번에 `Vehicle` 클래스 페이지는 본문이 렌더링되지 않아 재열람 못함 [불확실] |
| 속도 | **없음** — 릴리즈 노트 Shutdown API: `VehicleSpeedListener` 삭제(리스너와 `onSpeedUpdated` 제거) | — | — | [문서 확인] |
| 주차 브레이크(EPB) | **없음** — 클래스·릴리즈 노트 어디에도 언급 없음 | — | — | [문서 확인: 부재] |
| 브레이크 페달 | 있음 — `Brake`("Provides APIs for brake pedal status.") | `getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)`, `registerBrakePedalStatus(listener: BrakePedalStatusListener)`, `checkBrakePedalStatusCapabilities(...)` | `pleos.car.permission.CAR_DRIVE` | [문서 확인] (9절) |
| 드라이브 모드 | 있음 — `DrivingMode`("Provides APIs for driving mode and cruise control.") | `getCurrentDrivingModeState(onComplete: (DrivingModeType) -> Unit, onFailed: (Exception) -> Unit)`, `registerDrivingModeState(listener: DrivingModeStateListener)`, `checkDrivingModeStateCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)` | `pleos.car.permission.CAR_DRIVE` (함수마다 `@RequiresPermission`) | [문서 확인] |
| 크루즈 | 있음 — `DrivingMode` 안 | `getCruiseControlState(onComplete: (CruiseControl?) -> Unit, ...)`, `getCruiseControlTargetSpeed(onComplete: (Float?) -> Unit, ...)` (m/s), `registerCruiseControlState(listener: CruiseControlStateListener)`, `registerCruiseControlTargetSpeed(listener: CruiseControlTargetSpeedListener)` | `CAR_DRIVE` | [문서 확인] 상태 값: OTHER, ENABLED, ACTIVATED, USER_OVERRIDE, SUSPENDED, FORCED_DEACTIVATION_WARNING (릴리즈 노트) |
| 점화(ignition) | **없음** | — | — | [문서 확인: 부재] |
| 주행 상태(driving state)·UX 제한 | **없음** | — | — | [문서 확인: 부재] |

- **`DrivingModeType`은 NORMAL, ECO, SPORT 3개뿐인 드라이브 "성향" 모드**다(enum 항목 3개를 화면에서 직접 확인). 정차/주행 상태가 아니다. 따라서 11.1에서 불확실로 남겼던 "`getDrivingMode`가 주행 중 여부를 주는가"는 **아니오**로 정리한다 [문서 확인].
- 에러 코드 `ERROR_DRIVING_RESTRICTED`는 호출 거부 신호일 뿐 상태 조회 API가 아니다(B절).
- Odometer(주행거리·시간, `CAR_DRIVE`)는 값이 오르는지로 주행을 추정할 수 있으나 문서가 그런 용도를 말하지 않는다. 쓰지 않는다.

### 12.2 Q2. 다른 Pleos SDK·더 새로운 Vehicle 버전에 주행 상태/UX 제한이 있는가
- 릴리즈 노트(2026-10-19)의 SDK 전체 목록은 5개: Vehicle v2.3.2, NaviHelper v3.0.5, TextToSpeech v3.3.1, SpeechToText v3.3.1, FusedLocation v1.1.3. 이보다 새 Vehicle 버전은 문서에 없다 [문서 확인].
- 변경 이력상 5개 중 주행 상태·UX 제한·기어·속도를 추가한 것은 없다. FusedLocation은 위치와 Proximity Alert, NaviHelper는 경로·내비 상태(`NavigationStatus`)만 다룬다 [문서 확인].
- Playground 메인은 Vehicle/NaviHelper/TextToSpeech를 대표로 소개하고 "더 많은 SDK 알아보기" 링크는 SDK 샘플 앱 가이드로 연결된다. 그 외 SDK 후보(Gleo Interface는 음성 명령 연동 가이드)에도 주행 상태 API 언급은 없었다 [문서 확인].
- **「계운」 프로젝트의 "사용 가능" 표시: [불확실 — 로그인 필요, 미확인].** 로그인은 하지 않았다. 감독관/사용자가 직접 확인해야 한다(앞서 기록한 Vehicle State 11개 항목 "사용 가능" 외에 추가 SDK가 있는지).

### 12.3 Q3. AAOS 표준 API 사용에 대한 정책 문구
- SDK API 정책 원문: "표준 우선 정책: 동일하거나 유사한 기능의 API가 AAOS 표준에 이미 존재하는 경우, 해당 표준 API 사용을 권장하며 Pleos Connect SDK에서는 중복 기능을 제공하지 않습니다." / "확장 기능 제공: AAOS 표준 API로 접근이 불가능하거나, 제조사 고유의 보안 및 특화 제어가 필요한 영역에 한하여 Pleos Connect SDK 전용 API를 제공합니다." [문서 확인]
- API 선택 기준 표: 표준 데이터 → 접근 경로 "AAOS Car API", 대상 "속도, 기어 상태, 외기 온도, 연료 레벨 등" / 특화 기능 → "Pleos Connect SDK", 대상 "제조사 전용 시트 제어, 특화 라이팅, 고유 보안 인증 등" [문서 확인]
- 주행 규제 개발 가이드 원문: "Pleos Connect 앱은 플랫폼이 제공하는 CarUxRestrictions 상태를 기준으로 주행 중 UX 제한을 적용해야 합니다." "앱은 AAOS CarUxRestrictionsManager를 통해 CarUxRestrictions 값을 얻어 … 구현합니다." → 허용을 넘어 **필수 요구**다 [문서 확인].
- `CarPropertyManager`·`CarUxRestrictionsManager`를 위해 필요한 **권한(예: android.car.permission.CAR_SPEED, CAR_POWERTRAIN)은 Pleos 문서 어디에도 적혀 있지 않다**. 주행 규제 가이드는 권한을 언급하지 않고, FAQ(C절)는 "필요한 권한을 선언한 후"라고만 한다 [문서 확인: 부재 / 구체 권한명은 불확실].
- "AAOS 표준 API를 쓰면 심사·사용 가능 SDK 규칙에 걸리는가"에 대한 별도 금지 문구는 이번에 찾지 못했다. 정책 문구는 오히려 권장 쪽이다 [불확실: 앱 심사 가이드(App Review Guidelines)는 이번에 읽지 않음].

### 12.4 결론과 권고 (주행 상태의 출처)
1. 주행 중/제한 상태: Pleos SDK 5종 어디에도 없다. 유일한 문서 근거는 AAOS `CarUxRestrictionsManager`이며 Pleos가 앱에 이를 요구한다.
2. 속도·기어: Pleos Vehicle SDK에서 삭제됐거나 없다. AAOS Car API 영역이다. 주차 브레이크·점화도 Pleos에는 없다.
3. 「계운」 "사용 가능" 목록은 로그인 필요로 미확인. 단 SDK 카탈로그상 추가 후보가 없으므로 결과가 달라질 가능성은 낮다.
- **권고**: 주행 상태의 출처는 `CarUxRestrictions`(`isRequiresDistractionOptimization()`, `getActiveRestrictions()`)로 하고, 속도·기어로 직접 판단하지 않는다. "사용 가능 Pleos SDK만" 규칙은 "Pleos가 앱에 요구하는 AAOS 표준 `CarUxRestrictions`는 예외"로 정리하는 것이 문서와 맞다. 구현 주체(1번 공통 기반 또는 우리 어댑터)는 11.1의 결정 사항을 따른다. 요구 권한명은 안드로이드 공식 문서/에뮬레이터 시험으로 별도 확인이 필요하다.

### 12.5 포트 설계 시사점
`DrivingRestriction` 포트는 현재 방침(11.4)을 유지한다. `DrivingModeType`(NORMAL/ECO/SPORT)을 "주행 중"의 대용으로 쓰지 않도록 어댑터 주석에 명시한다. 포트에 속도·기어·주차 브레이크를 넣지 않는다(Pleos SDK에 근거 없음).


## 13. CarUxRestrictions 앱 실측 (2026-11-10)

- 담당 pleos-sdk-researcher (task #14a). 환경: `emulator-5554` Pleos Connect v2.1.0 (API 34, user 빌드, `hasSystemFeature(automotive)=true`). 일반 앱(applicationId `ai.probe.uxr`, 권한 선언 0개)으로 실행했다. 증거: `evidence/2026-11-10/task14/` (프로브 소스 `UxrActivity.kt.txt`, `app-build.gradle.kts.txt`, `AndroidManifest.xml.txt`, 로그 `run1~3`). 빌드 툴체인: AGP 9.5.1, Gradle 9.6.0, compileSdk 37, JDK 17. Kotlin은 AGP 내장 지원으로 별도 Kotlin 플러그인 없이 컴파일됐다.

### 13.1 Q1. 컴파일·빌드 설정 [Playground 실행 확인 — 에뮬레이터]
- **`android.car`는 compileSdk 37 기본 android.jar에 없다.** `useLibrary("android.car")`(android 블록 안)를 빼면 `Unresolved reference 'car'`로 컴파일이 실패한다. SDK의 `platforms/android-37.0/optional/android.car.jar`(스텁)가 `useLibrary`로 붙는다. 추가 Gradle 의존성·저장소는 필요 없다.
- **매니페스트 `<uses-library android:name="android.car" android:required="false"/>`는 시험한 범위에서 없어도 동작했다**(빼고 빌드·설치·실행해도 Car 연결·값 조회 동일). `android:required="false"`로 선언해 두는 것은 일반 안드로이드 지식상 권장이며 이 에뮬레이터에서 효과 차이는 보지 못했다 [불확실].
- 이 이미지에서 쓴 API 전부 컴파일·실행됨: `Car.createCar(Context)`, `createCar(Context, Handler)`, `createCar(Context, Handler, long, Car.CarServiceLifecycleListener)`, `getCarManager(String)`, `CarUxRestrictionsManager.getCurrentCarUxRestrictions()`, `registerListener(OnUxRestrictionsChangedListener)`, `CarUxRestrictions.isRequiresDistractionOptimization()/getActiveRestrictions()/getMaxRestrictedStringLength()/getMaxCumulativeContentItems()/getMaxContentDepth()`.

### 13.2 Q2. 권한 [Playground 실행 확인]
- **권한 없이 동작한다.** 매니페스트에 `uses-permission`이 하나도 없는 APK에서 `Car.createCar`, `getCarManager(CAR_UX_RESTRICTION_SERVICE)`, `getCurrentCarUxRestrictions()`, `registerListener` 모두 `SecurityException` 없이 성공했다. 실패하지 않았으므로 후보 권한은 시험하지 않았고 SecurityException 문구도 없다.
- 이 결과는 이 에뮬레이터 이미지 한정이다. 실차 이미지가 같은지는 확인하지 못했다 [불확실].

### 13.3 Q3. Park/Drive 값과 리스너 [Playground 실행 확인]

| 기어(Virtual Console) | requiresDistractionOptimization | activeRestrictions | maxStringLen / maxItems / maxDepth |
|---|---|---|---|
| Park (시작·복구 후) | false | 0x0 | 120 / 21 / 3 |
| Drive | true | 0x50 (80) | 120 / 21 / 3 |

- **리스너가 변경 시 호출된다.** 기기 시계 기준 Drive POST 15:47:56.311 → 리스너 15:47:56.327(약 16ms), Park POST 15:48:00.479 → 리스너 15:48:00.515(약 36ms). **등록 직후 초기값 콜백은 없었다.** 초기값은 `getCurrentCarUxRestrictions()`로 직접 읽어야 한다.
- 콜백 스레드는 확인하지 않았다 [불확실]. `dumpsys car_service`의 `Current Driving State`와 일치했다.
- 0x50의 비트 의미(NO_SETUP 0x40 + NO_VIDEO 0x10)는 11.2절과 같이 AOSP 상수 지식이다. Moving(속도>0)은 속도 주입이 안 돼 시험하지 못했다 [미확인].

### 13.4 Q4. 연결 실패·경계 동작
- 에뮬레이터에서 car service는 항상 연결돼 있었다. **car service 미연결·종료 상태는 만들 수 없어(user 빌드, adb root 불가) 시험하지 못했다 [미확인].** 시험 가능한 범위:
  - `createCar(ctx)` 직후 `isConnected=true`, 바로 `getCarManager` 가능(이 이미지에서는 동기 연결). `createCar(ctx, handler, 10000, lifecycleListener)`는 `ready=true`가 즉시(약 3ms) 호출됐다.
  - 존재하지 않는 서비스 이름 `getCarManager("no_such_service")` → 예외 없이 `null`.
  - `car.disconnect()` 후 `isConnected=false`, `getCarManager(CAR_UX_RESTRICTION_SERVICE)` → 예외 없이 `null`.
  - 자동차가 아닌 기기에서의 `createCar` 결과, `android.car` 클래스가 없는 기기의 `NoClassDefFoundError` 가능성은 시험하지 못했다 [불확실].
- 방어 코드는 필수로 둔다: `getCarManager` 결과 `as?` 캐스팅, `Throwable` catch, 실패 시 제한 쪽(`restricted = true`)으로 취급.

### 13.5 복사용 스니펫 (프로브와 동일 호출, 실행 확인)
`app/build.gradle.kts` (android 블록, 추가 dependency 없음):
```kotlin
android {
    compileSdk { version = release(37) }   // optional/android.car.jar 가 있는 플랫폼
    useLibrary("android.car")              // 필수. 없으면 import android.car.* 컴파일 실패
}
```
`AndroidManifest.xml` (권한 없음; uses-library는 권장 선언):
```xml
<application ...>
    <uses-library android:name="android.car" android:required="false" />
</application>
```
Kotlin:
```kotlin
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager

private var car: Car? = null

fun start(context: Context, onChanged: (restricted: Boolean, active: Int) -> Unit) {
    try {
        Car.createCar(context, Handler(Looper.getMainLooper()), 10_000L) { c, ready ->
            if (!ready) { onChanged(true, 0x1ff); return@createCar }   // 이 분기는 실행하지 못한 방어 코드
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                ?: run { onChanged(true, 0x1ff); return@createCar }
            m.currentCarUxRestrictions?.let { onChanged(it.isRequiresDistractionOptimization, it.activeRestrictions) }
            m.registerListener { r: CarUxRestrictions ->   // 초기값은 푸시되지 않으므로 위에서 직접 읽는다
                onChanged(r.isRequiresDistractionOptimization, r.activeRestrictions)
            }
        }
    } catch (t: Throwable) {
        onChanged(true, 0x1ff)                              // 실패 = 제한 쪽
    }
}
// 해제: car?.disconnect()
```

### 13.6 포트 설계 시사점
- 어댑터는 권한·SDK 의존성 추가 없이 `android.car`만으로 구현 가능하다(`useLibrary` 한 줄). `:departure-core`에는 넣지 않고 어댑터(앱/1번 측)에만 둔다.
- 초기값 푸시가 없으므로 포트의 Flow는 "현재값 1회 읽기 + 변경 리스너" 조합으로 만들어야 한다.
- 에뮬레이터 시연은 Virtual Console Drive/Park로 값이 약 16~36ms 안에 바뀐다.
- 연결 실패·null은 모두 "제한됨"으로 매핑한다(11.4절 `treatUnknownDrivingAsRestricted`와 일치).
