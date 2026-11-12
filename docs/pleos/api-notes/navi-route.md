# NaviHelper SDK — 목적지 설정·경로 요청·경로 옵션

- 출처 (모두 확인일 2026-11-09):
  - NaviHelper Introduction — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro
  - Importing NaviHelper SDK — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk
  - Declaring permissions — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions
  - Initialization — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release
  - Event listeners — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
  - Destinations — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations
  - Routes (경로 옵션 변경 / 경로 재탐색 섹션) — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/routes
  - Route requests with waypoints — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints
  - Error handling — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/error-handling
  - API Reference (Dokka): `.../NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry`, `.../ai.pleos.playground.navi.constants/route-option/entry`, `.../ai.pleos.playground.navi.data/route-info/entry`, `.../route-start-info/entry`
  - SDK 릴리즈 노트 (NaviHelper v3.0.5 섹션) — https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
- 용도: 차량에 설치된 **Navigation 앱에** 목적지·경로 옵션·경유지를 요청하고, 그 결과를 이벤트로 받는다.
- 확인 수준: [문서 확인] + [문서 재확인(2026-11-09)] Routes 페이지의 RouteOption 7개 값·설명(FREE = "무료 도로 우선 경로", 회피 값 없음) + [Playground 확인(2026-11-09)] NaviHelper 사용 상태("경로 정보 조회", "경로 탐색 설정" 모두 사용 가능) + [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md] 에뮬레이터에서 `initialize`·`get*Info` 3종·`requestRoute`(RECOMMENDED)·`requestTrip`·`cancelRoute`를 실행했다. [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절] `requestRoute(FREE)`·`changeRouteOption(FREE)`는 이 환경에서 **적용되지 않았다**(10.2·10.3). 주행 중·실차는 실행하지 않았다 [미확인]. 실행 확인 줄은 아래 각 항목에 표시한다.

## 초보자용 개념 설명
- NaviHelper는 **경로를 직접 계산해 주는 라이브러리가 아니다.** 차량의 Navigation 앱(내비)에게 "이 목적지로 안내해 줘"라고 **원격 요청**을 보내는 클라이언트다. 경로 계산과 안내 화면은 Navigation 앱이 한다.
- 요청 메서드는 반환값이 없다(`fun requestRoute(...)` → `Unit`). 결과는 미리 등록한 `NaviHelperEventListener`의 콜백(`onRouteStarted`, `onRoutingCompleted`, `onError` 등)으로 **나중에** 온다(문서 설명. 이 내비 버전에서 실제로 온 순서는 아래 콜백 표 밑 "실측 콜백 순서"에 있다 — `onRoutingCompleted`는 오지 않았다). 백엔드 감각으로는 "요청은 fire-and-forget, 결과는 이벤트 구독"이다.
- 문서 원문: "`requestRoute()` 호출이 정상적으로 수행되더라도 경로 탐색 완료를 의미하지 않습니다. 최종 결과는 이벤트 콜백으로 확인합니다. 정보 조회 API는 값을 직접 반환하지 않으며, 대응하는 이벤트 콜백에서 결과를 처리합니다." (Destinations)
- 각 API 상세 페이지 문구: "The actual events delivered may vary depending on how the Navi app, implemented with the NaviProvider SDK, handles and responds to this request." → **어떤 콜백이 실제로 오는지는 Navigation 앱 구현에 달려 있다.**

## 의존성/설정
- 저장소 (settings.gradle.kts):
  ```kotlin
  dependencyResolutionManagement {
      repositories {
          maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
      }
  }
  ```
- 의존성: `implementation("ai.pleos.playground:NaviHelper:3.0.5")`
  - 전이 의존성(Maven aar POM, [aar 확인 2026-11-09 — Maven aar/POM 직접 열람, navi-runtime-check.md]): `navi-data:3.0.4`(data·constants 패키지가 여기 있음), `kotlin-stdlib:1.9.25`, 런타임 `navi-aidl:3.0.1`. aar 매니페스트에 서비스 최소 버전 `min_version_name=2.0.1`, `<queries>` action `ai.pleos.playground.intent.action.NAVI_START`(매니페스트 병합 시 참고). 서비스 3.0.4와 SDK 3.0.5 조합이 연결·호출 모두 동작했다.
  - 저장소는 인증 없이 받았다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md] (NaviHelper만 확인).
- 매니페스트 권한 (Declaring permissions 표 그대로):

  | Permission | 주요 API |
  |---|---|
  | `pleos.car.permission.NAVI_ROUTE` | `get*Info()`, `requestTrip()` |
  | `pleos.car.permission.NAVI_ROUTE_SEARCH` | `requestRoute()`, `requestRouteWithWaypoints()`, `cancelRoute()`, `requestReRoute()`, `addWaypoint()`, `removeWaypoint()`, `changeRouteOption()` |
  | `pleos.car.permission.NAVI_CUSTOM_MAP` | `requestNavigationScreen()` |

  API Reference 상 보호 수준은 모두 `Normal`(설치 시 자동 허용, 런타임 팝업 불필요).
- SDK 사용 신청: "Vehicle State와 NaviHelper SDK가 이 상태(사용 가능)에 해당합니다. 이 두 SDK는 신청 없이 바로 사용 가능합니다." (프로젝트 생성 및 SDK 사용 신청 — https://document.pleos.ai/docs/pleos-only/create-my-project)

## 핵심 시그니처 (API Reference 그대로)
패키지: `ai.pleos.playground.navi.helper.NaviHelper`

```kotlin
class NaviHelper(context: Context)

fun initialize()
fun initialize(onConnected: () -> Unit, onFailed: (Throwable) -> Unit)
fun release()
fun addListener(listener: NaviHelperEventListener)
fun removeListener(listener: NaviHelperEventListener)

@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun requestRoute(routeInfo: RouteInfo)
@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun requestRouteWithWaypoints(request: RouteWithWaypointsRequest)
@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun changeRouteOption(routeOption: RouteOption)
@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun requestReRoute()
@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun cancelRoute()
@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun addWaypoint(requestWaypointInfo: RequestWaypointInfo)
@RequiresPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") fun removeWaypoint(waypointIndex: WaypointIndex)
@RequiresPermission("pleos.car.permission.NAVI_ROUTE") fun requestTrip(tripRequest: TripRequest)          // → eta-location.md
@RequiresPermission("pleos.car.permission.NAVI_ROUTE") fun getDestinationInfo()                          // → onDestinationInfo
@RequiresPermission("pleos.car.permission.NAVI_ROUTE") fun getRouteStateInfo()                           // → onRouteStateInfo
@RequiresPermission("pleos.car.permission.NAVI_ROUTE") fun getCurrentLocationInfo()                      // → onCurrentLocationInfo
@RequiresPermission("pleos.car.permission.NAVI_ROUTE") fun getWaypointInfo() / getTBTInfo() / getBookmarkInfo() / getRecentDestinationInfo() / getChargerOperatorInfo()
@RequiresPermission("pleos.car.permission.NAVI_CUSTOM_MAP") fun requestNavigationScreen(requestScreen: NavigationScreenRequest)
```

### RouteInfo (목적지 + 경로 옵션)
```kotlin
@Serializable
data class RouteInfo(
    val latitude: Double, val longitude: Double,
    val displayLatitude: Double? = null, val displayLongitude: Double? = null,
    val poiName: String, val poiId: String, val address: String, val poiSubId: String,
    val routeOption: RouteOption = RouteOption.RECOMMENDED,
    val poiProvider: String = "", val isMobileRequest: Boolean = false
)
```
- `poiName`, `poiId`, `address`, `poiSubId`는 **non-null 필수**. 문서에는 빈 문자열 허용 여부가 없다. **`requestRoute`는 빈 문자열 4개를 받는다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 좌표만으로 경로가 잡히고 안내가 시작됐다. 응답 `address`는 내비의 역지오코딩 값("용산구 동자동")으로 바뀌고, `poiSubId "0"`은 `null`로 돌아오며, `poiName`·`poiId`는 그대로 온다. 임의 poiId("probe-seoul-station")도 거부하지 않았다. **`TripRequest`의 빈 문자열도 받는다** [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절](`onTripInfo` 0.56초).
- v3.0.5에서 생성자 좌표 인자 순서가 `longitude, latitude` → `latitude, longitude`로 바뀌었다(릴리즈 노트). 문서의 Java 예제는 아직 옛 순서(`127.03..., 37.48...`)로 남아 있으니 **named argument로 호출할 것.**

### RouteOption (enum, 단일 값)
`@Serializable enum RouteOption` — Routes 페이지 표 그대로:

| 값 | 설명 |
|---|---|
| `RECOMMENDED` | 추천 경로 (기본값) |
| `SHORT_DISTANCE` | 최단 거리 경로 |
| `SHORT_TIME` | 최단 시간 경로 |
| `FREE` | 무료 도로 우선 경로 |
| `HIGHWAY` | 고속도로 우선 경로 |
| `COMFORTABLE` | 편안한 경로 |
| `SCHOOL_ZONE` | 스쿨존 고려 경로 |

- 지정 방법 두 가지: 처음 설정 시 `RouteInfo.routeOption`, 안내 중 `changeRouteOption()`. 문서상 결과 콜백은 `onRouteOptionChanged(routeOption: RouteOption)`.
  - 실측: `requestRoute`를 RECOMMENDED로 했을 때 `onRouteOptionChanged`는 **오지 않았다.** 적용 옵션은 푸시로 오는 `RouteStateInfo.routeOption`으로 확인된다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. FREE 요청·`changeRouteOption`은 **효과가 없었다**: `onRouteOptionChanged`는 어떤 경우에도 오지 않았고 `routeOption`은 RECOMMENDED 그대로, `RouteStartInfo.tollFare=700` [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절]. `RouteInfo.routeOption`은 시험한 두 값(FREE, SHORT_DISTANCE)에서 내비 앱에 전달된 요청에 필드 자체가 없었다 [로그 관찰 — API 아님]. 나머지 값은 [미확인].
- "경로 옵션 변경과 재탐색은 활성 경로가 있는 상태에서 요청합니다." (Routes, 정보 박스)
- **"고속도로 회피"·"유료도로 회피" 같은 회피 옵션은 SDK 문서에 없다.** 다만 내비 앱 UI에는 경로 설정의 "유료도로 우회"(내부 `42_route_AOP_02`)·"어린이 보호구역 우회" 토글이 있고, 유료도로 우회를 켜고 안내를 시작하면 `RouteStateInfo.routeOption`이 `FREE`로 보고된다 [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절](10.4). **SDK 호출로 이 토글을 켤 방법은 확인되지 않았다.** `FREE`는 "회피"가 아니라 "무료 도로 **우선**"이다. 옵션을 여러 개 동시에 켜는 방법(비트 플래그 등)도 문서에 없다 → 한 번에 하나만 지정.

### NaviHelperEventListener (주요 콜백. Kotlin interface, 모든 메서드 default 구현(javap) = 필요한 것만 override)
| 콜백 | 시그니처 | 설명(Reference) |
|---|---|---|
| 경로 탐색 시작 | `onRouteStarted(routeStartInfo: RouteStartInfo)` | Called when navigation route search begins |
| 경로 탐색 완료 | `onRoutingCompleted()` | |
| 경로 취소 | `onRouteCancelled()` | |
| 재탐색 완료 | `onReRouteEnded()` | |
| 옵션 변경 | `onRouteOptionChanged(routeOption: RouteOption)` | |
| 목적지 변경 | `onDestinationChanged()` | 목적지 설정/제거 시 |
| 목적지 도착 | `onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo)` | |
| 목적지 정보 | `onDestinationInfo(destinationInfo: DestinationInfo)` | ETA 관련 → eta-location.md |
| 경로 상태 | `onRouteStateInfo(routeStateInfo: RouteStateInfo)` | |
| 내비 상태 | `onNavigationStatus(status: NavigationStatus)` | 상태 변경 시마다 |
| 주행 정보 | `onDrivingInfo(drivingInfo: DrivingInfo)` | |
| Trip 정보 | `onTripInfo(tripInfo: TripInfo)` | `requestTrip` 결과 |
| 오류 | `onError(errorCode: NaviErrorCode)` | |
| 경로 종료 | `onRouteEnded()` | 실측: `onRouteStarted` 직후에 온다(안내 종료 아님, 탐색이 끝났다는 신호로 보임) |
| 경유지 변경 | `onWaypointChanged(WaypointChangedInfo)` | javap로 확인, 미실행 |
| 경유지 도착 | `onWayPointArrived(WaypointArrivedInfo)` | javap로 확인, 미실행 |
| 즐겨찾기 변경 | `onBookMarkChanged()` | javap로 확인, 미실행 |
| 경유지 정보 | `onWayPointInfo(List<WaypointInfo>)` | javap로 확인, 미실행 |
| 최근 목적지 | `onRecentDestinationInfo(List)` | javap로 확인, 미실행 |
| 즐겨찾기 | `onBookmarkInfo(List)` | javap로 확인, 미실행 |
| 제한속도 | `onCurrentRoadSpeedLimit(Int)` | javap로 확인, 미실행 |
| 카메라 경고 | `onCameraAlert(List)` | javap로 확인, 미실행 |
| TBT | `onTBTInfo(List)` | javap로 확인, 미실행 |
| 충전소 운영사 | `onChargerOperatorInfo(List)` | javap로 확인, 미실행 |
| 주의 구간 | `onCautionPointInfo(List)` | javap로 확인, 미실행 |

**이 내비 버전 실측 콜백 순서** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]:
- 목적지 설정(`requestRoute`): `onRouteStarted → onRouteEnded → onNavigationStatus(ROUTE_DRIVE) → onRouteStateInfo(푸시) → onDrivingInfo(푸시)`
- 취소(`cancelRoute`): `onRouteCancelled → onNavigationStatus(FREE_DRIVE) → onDestinationInfo(0 값, 푸시) → onRouteStateInfo(푸시)`
- `onRoutingCompleted`·`onDestinationChanged`는 SDK로 설정·취소할 때 오지 않았다. 사람이 내비 화면에서 목적지를 설정·변경할 때 어떤 콜백이 오는지는 프로브가 꺼져 있어 [미확인].
- 스레드: `initialize`의 `onConnected`는 main 스레드, 리스너 콜백은 binder 스레드에서 온다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md].

`RouteStartInfo(duration: Int /*초*/, distance: Int /*m*/, tollFare: Int, name: String, remainDestinationChargingValue: Int)`

`RouteStateInfo(termsAgreement: Boolean, naviMode: NaviMode, isRouting: Boolean, routeOption: RouteOption, remainDestinationChargingInt: Int, remainWaypointChargingInt: List<Int>, trafficStatus: TrafficStatus?)` 
- `termsAgreement`(사용자가 내비 약관에 동의했는지): 이 에뮬레이터에서는 true였다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. false일 때 동작은 [미확인](문서에도 동작 설명 없음).
- `isRouting`: 경로 안내 중에도 **false**였다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 안내 중 판단은 `naviMode == ROUTE_GUIDING` 또는 `NavigationStatus.ROUTE_DRIVE`로 한다. `isRouting`은 탐색 진행 중을 뜻하는 값으로 보인다(추정).
- `NaviMode` 값(javap): `NOT_WORKING`, `SAFE_GUIDING`, `ROUTE_GUIDING`, `SIMULATION_GUIDING`. 경로 없음 = `SAFE_GUIDING` [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 이 내비는 목적지를 설정하면 바로 `ROUTE_GUIDING`이 된다("목적지는 있는데 안내는 안 하는" 상태는 관측되지 않음).

`NavigationStatus`: `INACTIVE`, `INITIALIZING`, `FREE_DRIVE`(경로 없이 지도만), `ROUTE_DRIVE`(경로 안내 중).

`NaviErrorCode`: `CAUSE_LOW_BATTERY`, `CAUSE_SEARCH_ROUTE_OVER_DISTANCE`, `CAUSE_SEARCH_ROUTE`, `CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE`, `CAUSE_SEARCH_TRIP`.

## 사용 예시 (문서 예제 요약, Kotlin)
```kotlin
val naviHelper = NaviHelper(applicationContext)
naviHelper.initialize(
    onConnected = { naviHelper.addListener(naviEventListener) /* 이후 API 호출 */ },
    onFailed = { throwable -> /* 초기 연결 재시도 모두 실패 */ }
)

naviHelper.requestRoute(
    RouteInfo(
        latitude = 37.48544722, longitude = 127.03636666,
        poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5", poiSubId = "0",
        address = "서울시 강남구 남부순환로 2621",
        routeOption = RouteOption.FREE          // 문서 예제는 기본값 사용. 주의: 이 환경(내비 3.3.2)에서는 이 값이 무시된다(10절)
    )
)
// 안내 중 옵션 변경
naviHelper.changeRouteOption(RouteOption.HIGHWAY)   // 주의: 이 환경에서 changeRouteOption은 요청이 내비 앱에 닿지만 아무 효과가 없었다(FREE·RECOMMENDED 시험, 10.3)

// 정리
naviHelper.removeListener(naviEventListener)
naviHelper.release()   // release 후 재사용하려면 새 객체 생성 + initialize
```
경유지: 최대 2개(`WaypointIndex.FIRST`, `SECOND`, 전체 삭제는 `ALL`).

## 제약·주의
- **호환성**: "NaviHelper SDK — 내비게이션이 탑재된 차량에 한해 전체 호환" (API 호환성 및 가용성 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability). 내비 탑재 여부 확인 방법은 "현재 퍼블릭 오픈하지 않고 있습니다" (FAQ — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq).
- `requestRoute()`는 **실제 Navigation 앱의 목적지를 바꾸고 경로 안내를 시작시키는 요청**이다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]: 기어 P(경로 없는 상태)에서도 사용자 확인 없이 바로 안내 화면으로 바뀐다. **안내 중에 다른 목적지로 `requestRoute`하면 기존 안내를 취소하고 덮어쓴다**(사람 입력 없이 약 7초 뒤 새 안내 시작) [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절]. `onRouteStarted`까지 8.3초·11.3초(2회) 걸렸다(타임아웃 20초 이상 권장). 사용자가 이미 목적지를 설정한 상태에서는 호출하지 않는다(덮어쓰기 + 옵션이 무시되어 사용자가 켜 둔 "유료도로 우회"가 풀릴 위험이 있으나 FREE 안내 중 재요청은 시험하지 않음 [미확인]). "미리보기"용으로도 쓰지 않는다. 사전 계산 용도는 `requestTrip` 참고(eta-location.md).
- 요청과 응답을 묶는 ID가 없다. 콜백에는 "어느 요청에 대한 응답인지"가 없으므로 동시에 여러 요청을 보내면 구분 불가.
- 오류 확인 목록(Error handling): SDK 연결 완료 후 호출했는지 / 퍼미션 선언했는지 / 좌표·POI ID·경유지 인덱스가 올바른지 / Navigation 앱이 요청을 처리할 수 있는 상태인지.
- 에뮬레이터에 Navigation 앱이 있고(`ai.umos.maps.android.navigation.app` 3.3.2-rc5-hotfix.3), 시험한 요청(init·get*Info 3종·requestRoute·requestTrip·cancelRoute)에 모두 응답했다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md].
- Maps/Navigation 앱이 아닌 우리 앱은 자체 경로 안내(음성·TBT)를 하면 안 된다: "Inactive Route / Background 상태에서 경로 안내 제공 시 정책 위반" (Maps/Navigation 앱 개발 가이드 — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide). 우리는 NaviHelper로 OEM 내비에 위임하는 구조가 맞다.

## 포트 설계 시사점
`RouteOption`은 비트 플래그가 아니라 **단일 선택 enum**이고 회피 옵션이 없다. 그래서 우리 도메인의 "운전자 조건"(무료도로 선호, 고속도로 회피 등)을 `Set`으로 받더라도, 어댑터에서는 **하나의 SDK 옵션으로 줄이는 규칙**이 필요하다. 예: 무료도로 선호 → `FREE`. "고속도로 회피"에 정확히 맞는 값은 없으므로 어댑터가 `FREE` 등으로 근사하고, 그렇게 바꿨다는 사실을 결과에 담아 UI가 "완전 회피 아님"을 보여 줄 수 있어야 한다(근사 매핑은 우리 설계 판단이다. 문서에 근거한 내용이 아님). 포트는 `requestRoute(destination, preference)` 형태로 두고, 실제로 적용된 옵션(실측상 RECOMMENDED 요청에서는 `onRouteOptionChanged`가 오지 않고 푸시 `RouteStateInfo.routeOption`으로 확인됨. FREE 요청·`changeRouteOption`은 이 환경에서 무시되므로 **요청 직후가 아니라 사용자가 내비에서 토글한 뒤** `getRouteStateInfo().routeOption`(재조회 가능)으로 판정한다)을 결과로 돌려준다. MVP(2026-11-10 사용자 결정)는 "사용자에게 내비의 유료도로 우회 토글을 안내 + 사후 확인"이며 이미 설정된 목적지에 `requestRoute`를 부르지 않는다. 요청은 반환값이 없고 결과는 콜백으로 오므로 어댑터에서 `suspend` + 타임아웃(또는 `Flow`)으로 바꾼다. 요청 ID가 없으니 어댑터 안에서 **한 번에 한 요청만** 처리(Mutex)한다. `poiId`/`poiName`/`address`가 필수라서 우리 도메인의 목적지 모델에도 이 값을 담을 자리(nullable)가 있어야 한다.

## 공식 샘플 앱 참고 (2026-11-11, 감독관 확인)
- 출처: https://github.com/Pleos-Playground/pleos-sample-navi-app (커밋 76ed592 "Initial commit", NaviHelper 3.0.5). 읽기만 했고 빌드·실행하지 않았다.
- 샘플은 API를 샘플 데이터로 호출하고 로그만 보여 준다. **경로 옵션을 해석하거나 유료도로·어린이 보호구역을 구분하는 로직은 없다.** `changeRouteOption(RouteOption.FREE)`를 예시로 호출하고 `onRouteOptionChanged`를 로그로 찍을 뿐이다(우리 실측상 changeRouteOption(FREE)는 무시됨 — navi-runtime-check.md 10.3).
- 매니페스트 권한: `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `NAVI_CUSTOM_ROUTE`, `NAVI_CUSTOM_ETC`.
- **`requestNavigationScreen(NavigationScreenRequest.RouteOptionList)`** 예시가 있다(권한 `NAVI_CUSTOM_MAP`). 앱에서 내비의 경로 옵션 화면을 여는 API다.
  - [SDK 클래스 확인 — navi-data 3.0.4 classes.jar] `NavigationScreenRequest` 하위: `AddHomeFavorite`, `AddWorkFavorite`, `EditRoute`, `FavoriteMain`, `Foreground`, `MoveToCurrentLocationOnMap`, `NearbyChargingStations`, `OverviewRoute`, `POIDetailPopup`, `RecentDestinations`, `RouteOptionList`, `SearchPlace`, `Settings`.
  - **[실행 확인 2026-11-11] `RouteOptionList`는 경로 설정 시트를 열지 않는다.** 안내 없음 → 내비 기본 화면("어디로 갈까요?")이 앞으로 나올 뿐, 안내 중 → 안내 화면이 앞으로 나올 뿐이다. 호출은 예외 없이 성공(권한 `NAVI_CUSTOM_MAP`). 즉 사실상 "내비 앞으로 가져오기"로 동작한다(근거 `evidence/2026-11-11/route-option-screen/`, 시험 코드 사본 포함 — 1번 저장소에는 넣지 않음)
  - 결론: [내비 경로 설정 열기] 버튼 용도로는 쓸 수 없다. 사용자에게는 지금처럼 내비 조작 순서를 글로 안내한다
  - **[실행 확인 2026-11-11] `NavigationScreenRequest.Settings`는 차량 설정(`ai.umos.vehiclecontrol/.VehicleControlActivity`)의 "내비게이션" 탭을 연다.** 같은 화면 아래 "경로" 절에 전역 "유료도로 우회" 토글이 보인다(`evidence/2026-11-11/route-option-screen/s3-settings-request.jpg`). 호출 예외 없음(권한 `NAVI_CUSTOM_MAP`, 내비 안내 없음 상태에서 1회). 토글을 켜 주지는 않는다 — 사용자가 직접 켜야 한다
  - 쓸모(설계 판단): "처음 한 번 '유료도로 우회'를 켜 두세요" 안내에 [내비 설정 열기] 버튼으로 쓸 수 있다. 앱에 넣으려면 main 매니페스트에 `NAVI_CUSTOM_MAP` 권한 추가가 필요하다(지금은 1번 앱 debug 에도 없음 — 시험 뒤 되돌림)
