# ETA·남은 거리·소요시간 + 현재 위치·반경(Proximity)

- 출처 (모두 확인일 2026-11-09):
  - NaviHelper API Reference — `requestTrip`: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/request-trip
  - NaviHelper data: `TripRequest`, `TripInfo`, `TripSummary`, `DestinationInfo`, `RouteStartInfo`, `DrivingInfo`, `DrivingState`, `CurrentLocationInfo` — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/entry 아래 각 페이지
  - SDK 릴리즈 노트 (NaviHelper v3.0.5) — https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
  - FusedLocation Introduction — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro
  - Importing FusedLocation SDK — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup
  - Declaring permission — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions
  - 위치 데이터 접근 — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access
  - Initialization / Release — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release
  - 오류 처리 — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/error-handling
  - FusedLocation API Reference — `.../FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/entry`, `...fused.model/location-data/entry`, `...proximity-alert-request/entry`, `...listener/proximity-alert-listener/entry`
- 확인 수준: [문서 확인] + [문서 재확인(2026-11-09, 실제 브라우저 렌더링)] — requestTrip·TripInfo·addProximityAlert Reference 페이지를 브라우저로 다시 읽음. SDK 상태는 [Playground 확인(2026-11-09)]. NaviHelper 부분(requestTrip·getDestinationInfo·getCurrentLocationInfo)은 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md] — 2026-11-10 리뷰 반영표대로 아래 해당 줄을 정정했다. FusedLocation(B·C절)은 실행 확인이 없다.

---

## A. ETA·거리·소요시간 (NaviHelper)

### 초보자용 요약
값을 얻는 경로는 세 가지다. 모두 **콜백으로** 온다.

| 방법 | 언제 쓰나 | 결과 콜백 | 들어 있는 값 |
|---|---|---|---|
| `requestTrip(TripRequest)` | 경로 안내를 시작하기 **전에** 목적지까지 거리·ETA를 알고 싶을 때(안내를 시작하지 않는 사전 조회 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]) | `onTripInfo(TripInfo)` | `distance`(m), `eta`(초) |
| `requestRoute(RouteInfo)` | 실제 안내 시작 | `onRouteStarted(RouteStartInfo)` | `duration`(초), `distance`(m), `tollFare` |
| `getDestinationInfo()` / 주행 중 이벤트 | 내비에 설정된 목적지의 좌표·이름·남은 값. **주차(P) 중에도 받는다**(아래 실행 확인) | `onDestinationInfo(DestinationInfo)`, `onDrivingInfo(DrivingInfo)` | `remainDistance`(m 단위 값, km로 뭉개짐), `remainTime`(초 단위 값, 분 단위로 뭉개짐), `tollFare`(원, 유료 경로 **1건 관측**에서 0으로 옴 — 아래) / `DrivingState.duration`(초), `distance`(m) |

**`getDestinationInfo` 실행 확인** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]:
- 주차(P) 중에도 내비 목적지의 좌표·이름·poiId·주소(역지오코딩 구·동)·remainTime·remainDistance를 준다. SDK로 설정한 목적지, 사람이 내비 화면에서 설정한 목적지, 다른 프로세스가 설정한 목적지 모두 같다.
- 목적지가 없으면 오류가 아니라 **0 값 객체**(lat/lon 0.0, name·poiId "", remainTime 0, poiSubId null)가 온다. 이 내비에서는 목적지를 설정하면 바로 `ROUTE_GUIDING`이 되며, "목적지는 있는데 안내는 안 하는" 상태는 관측되지 않았다.
- 응답 시간: 경로 있을 때 약 1.0초, 없을 때 50ms 미만(경로 있을 때 수치는 전체 로그 기준, 발췌 없음).

### 핵심 시그니처 (Reference 그대로)
```kotlin
@RequiresPermission("pleos.car.permission.NAVI_ROUTE")
fun requestTrip(tripRequest: TripRequest)
// "Related events such as onTripInfo may be delivered to the registered listener."

@Serializable data class TripRequest(
    val latitude: Double, val longitude: Double,
    val poiId: String, val poiName: String, val poiSubId: String?
)

@Serializable data class TripInfo(
    val distance: Int,            // "The total distance traveled in meters to the destination."
    val eta: Int,                 // "Estimated Time of Arrival (ETA) in seconds."
    val estimatedSocAtDest: Int,  // 도착 시 예상 배터리(%)
    val energyConsumption: Double,// Wh
    val summary: TripSummary      // trafficConditions: List<TrafficInfo>, routePoints: List<RoutePointInfo>
)

@Serializable data class RouteStartInfo(val duration: Int, val distance: Int, val tollFare: Int, val name: String, val remainDestinationChargingValue: Int)
// duration: "The estimated travel time for the route in seconds." / distance: "in meters"

@Serializable data class DestinationInfo(
    val latitude: Double, val longitude: Double, val displayLatitude: Double? = null, val displayLongitude: Double? = null,
    val name: String, val address: String,
    val remainDistance: Int,  // m 단위 값이지만 km 단위로 뭉개짐(23,799m → 23,000m). "반올림"이라고 단정하지 않는다(내림으로 보이나 방식 [불확실]). 정밀 값은 TripInfo
    val remainTime: Int,      // 초 단위 값이지만 분 단위로 뭉개짐(2,704초 → 2,700초)
    val tollFare: Int,        // 원. 유료 구간이 있는 경로에서도 0이었다(경로 1건 관측). 사후 유료 신호는 RouteStartInfo.tollFare(700)를 쓴다
    val poiId: String, val poiSubId: String?, val poiProvider: String = ""
)

@Serializable data class DrivingInfo(val destination: DrivingState, val waypoints: List<DrivingState>)
@Serializable data class DrivingState(val name: String, val duration: Int /*현재 위치→지점 예상 초*/, val distance: Int /*m*/, val remainDestinationChargingValue: Int, ...)
```
오류: `NaviErrorCode.CAUSE_SEARCH_TRIP` ("Error: trip server side").

### 사용 예시
```kotlin
naviHelper.requestTrip(TripRequest(latitude = 37.48544722, longitude = 127.03636666,
    poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0"))

override fun onTripInfo(tripInfo: TripInfo) {
    val meters = tripInfo.distance
    val etaSeconds = tripInfo.eta
}
```
(문서에 `requestTrip` 전용 예제는 없음. 위 코드는 Reference 시그니처로 만든 것)

### 제약·주의 (중요)
- **`requestTrip`은 안내를 시작하지 않는 사전 조회다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 경로 없음·안내 중 모두 `onTripInfo`가 0.4~0.55초 안에 왔고 상태·목적지는 그대로였다. (아래는 문서 사실이며 그대로 유효하다.) 정정(11/09 재검토): 이전 "Reference 설명은 'Requests trip.' 한 줄뿐" → 현재 렌더링된 페이지에는 두 문장이 더 있다. onTripInfo 같은 이벤트가 등록된 리스너로 "전달될 수 있다(may be delivered)"는 것과, 실제 전달되는 이벤트는 NaviProvider SDK로 구현된 Navi 앱이 이 요청을 어떻게 처리하느냐에 따라 달라진다는 것. 즉 **`onTripInfo`가 반드시 온다는 보장도 문서에 없다**(타임아웃 필수). 권한 표기 "Protection level: Normal"(`NAVI_ROUTE`)도 같은 페이지에서 확인. 문서의 도착 보장 없음 → 타임아웃 필수는 그대로 둔다. 실측으로는 사전 조회임이 확인됐다(위).
- **`TripRequest`에는 `routeOption`이 없다.** 즉 "무료도로 우선으로 가면 몇 분?" 같은 **옵션별 사전 ETA는 문서상 불가능**하다. 문서는 "옵션을 반영한 시간은 `requestRoute(RouteInfo(routeOption=...))` 후 `onRouteStarted.duration`으로만 얻을 수 있다"는 취지였으나 **이 환경에서는 성립하지 않는다**: `requestRoute`의 `routeOption`(FREE·SHORT_DISTANCE)은 무시됐고 `onRouteStarted.duration`은 항상 RECOMMENDED 기준이었다 [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절]. 실측상 `requestTrip`의 거리·ETA는 요청에 옵션이 없는데도 **내비 앱이 그 순간 쓰는 "유료도로 우회" 설정**을 따른다(on: distance 26494·eta 4585~4588, off: 23799·4008~4036, 내비 앱 로그 `avoid_fare` 대조 [로그 관찰 — API 아님]). 안내 경로가 없을 때의 기준은 [미확인]. `DestinationInfo`와는 같은 옵션에서도 값이 다르다(ETA 136~148초).
- `TripInfo.eta`: 문서 문구("Estimated Time of Arrival (ETA) in seconds")는 모호하지만 **남은 초(소요 시간)다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 근거: 값 2704 vs 같은 시각 epoch 1794207288, 14초 뒤 재호출하니 2703(시각이면 늘어나야 함). `eta > 10^9`이면 거부하는 검사는 실차 확인 전까지의 설계 판단으로만 둘 수 있다(문서·실측 근거 아님).
- `DestinationInfo.remainDistance/remainTime` 필드 설명은 "to the waypoint"라고 적혀 있으나 클래스는 목적지 정보다(문서 표기 오류로 보임). v3.0.5에서 `totalDistance/totalTime` → `remainDistance/remainTime`으로 이름이 바뀌었다(릴리즈 노트).
- 사전 계산이 안 될 경우 대비: 내비 없는 차량에서는 NaviHelper 자체가 없다(navi-route.md 호환성 참고).

---

## B. 현재 위치 (FusedLocation SDK v1.1.3)

### 초보자용 요약
FusedLocation은 차량 위치를 주는 Pleos 전용 SDK다(구글 Play 서비스의 FusedLocationProvider와 이름만 같고 다른 것). `getLastKnownLocation`은 "지금 새로 측정"이 아니라 **마지막으로 알고 있는 위치를 한 번** 콜백으로 준다. 계속 받으려면 `registerFusedLocationCallback`.

### 의존성/설정
- 저장소: `maven("https://nexus-playground.pleos.ai/repository/maven-releases/")`
- 의존성: `implementation("ai.pleos.playground:FusedLocation:1.1.3")`
- 권한: `<uses-permission android:name="pleos.car.permission.FUSED_LOCATION" />` — 보호 수준 **`dangerous`** (Declaring permission 표). 런타임 요청이 필요한 수준이며 문서는 "권한 요청과 결과 처리는 앱의 권한 처리 흐름에 맞게 구현하세요"라고만 한다.
- 문서 원문 주의: "Android의 `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`에 관한 동작과 포그라운드·백그라운드 위치 접근 정책은 이 SDK 문서에서 별도로 보장하지 않습니다."

### 핵심 시그니처
```kotlin
class FusedLocation(context: Context)
fun isSupported(): Boolean        // "Returns whether the Fused Location Service package is present on Pleos Connect."
fun initialize()
fun release()
@RequiresPermission("pleos.car.permission.FUSED_LOCATION") fun getLastKnownLocation(callback: FusedLocationListener)
@RequiresPermission("pleos.car.permission.FUSED_LOCATION") fun registerFusedLocationCallback(listener: FusedLocationListener)
@RequiresPermission("pleos.car.permission.FUSED_LOCATION") fun unregisterFusedLocationCallback(listener: FusedLocationListener)
@RequiresPermission("pleos.car.permission.FUSED_LOCATION") fun addProximityAlert(request: ProximityAlertRequest, listener: ProximityAlertListener)
@RequiresPermission("pleos.car.permission.FUSED_LOCATION") fun removeProximityAlert(alertId: String)
fun showLocationAccessBlockedPopup()

interface FusedLocationListener {
    fun onChanged(location: LocationData?)   // null 가능
    fun onError(exception: Exception)
}
data class LocationData(val latitude: Double, val longitude: Double, val altitude: Double,
                        val heading: Float, val confidence: Float, val timestampMillis: Long)
```

### 사용 예시 (문서 요약)
```kotlin
val fusedLocation = FusedLocation(this)
if (fusedLocation.isSupported()) fusedLocation.initialize() else { /* 위치 기능 비활성 */ }

val listener = object : FusedLocationListener {
    override fun onChanged(location: LocationData?) { location ?: return /* 사용 */ }
    override fun onError(exception: Exception) { /* 처리 */ }
}
fusedLocation.registerFusedLocationCallback(listener)
// 정리 순서: unregister 먼저, 그 다음 release (서로 대체 불가)
fusedLocation.unregisterFusedLocationCallback(listener)
fusedLocation.release()
```

### 제약·주의
- **갱신 주기·정확도 설정 API가 없다.** 업데이트 간격을 지정하는 파라미터가 시그니처에 없고 주기도 문서에 없다 [불확실].
- `LocationData.confidence`의 단위·의미(미터 반경인지 0~1 확률인지) 설명 없음 [불확실].
- `onChanged`의 `null`은 "유효한 위치가 아님"으로 처리하라고 명시("`null`을 유효한 위치로 처리하지 마세요").
- 호환성: "FusedLocation SDK — 모델에 따라 다름. 가용 여부는 `isSupported` API로 확인" (API 호환성 및 가용성).
- 개인정보 설정으로 위치가 차단될 수 있고, 그때 `showLocationAccessBlockedPopup()`으로 안내만 할 수 있다(허용시키는 API 아님).
- **SDK 사용 신청 상태**: 프로젝트 생성 가이드에 "준비 중 상태 … FusedLocation, TextToSpeech, SpeechToText, Vehicle Control SDK가 이 상태에 해당합니다"라고 적혀 있다(https://document.pleos.ai/docs/pleos-only/create-my-project). [Playground 확인(2026-11-09)] 「계운」 프로젝트 FusedLocation 페이지: 항목 "정밀 위치 정보 조회" 1개, 상태 **"준비 중"**, 신청 버튼 없음. 이 상태가 에뮬레이터에서 SDK 호출 자체를 막는지, 실차 배포 승인만 막는지는 여전히 [불확실 — 에뮬레이터 실행 확인 필요].

### 대안: NaviHelper로 위치 얻기
`getCurrentLocationInfo()` → `onCurrentLocationInfo(CurrentLocationInfo)`
`CurrentLocationInfo(latitude: Double, longitude: Double, heading: Double = 0.0, address: String, routeId: String, currentRouteIndex: Int?)` — 주소 문자열까지 준다. 권한 `NAVI_ROUTE`(Normal). 단 내비 탑재 차량 한정.

---

## C. 반경 확인 — ProximityAlert (FusedLocation)

"집에서 허용 반경 안인지"에 바로 쓸 수 있는 지오펜스 API가 있다.

```kotlin
data class ProximityAlertRequest(
    val latitude: Double,      // -90..90
    val longitude: Double,     // -180..180
    val radiusMeters: Float,   // "greater than zero"
    val transitionTypes: Set<ProximityTransition>   // "Non-empty set"
)
enum class ProximityTransition { ENTER, EXIT }
data class ProximityAlertEvent(val alertId: String, val transition: ProximityTransition)
interface ProximityAlertListener {
    fun onRegistered(alertId: String)          // 등록 성공 시 ID 전달
    fun onTransition(event: ProximityAlertEvent)
    fun onError(exception: Exception)          // 잘못된 요청 포함
}
// addProximityAlert: "Throws SecurityException — If the required permission is not granted."
```
재검토 11/09 [문서 재확인]: `addProximityAlert(request: ProximityAlertRequest, listener: ProximityAlertListener)` Reference 페이지에서 "alert ID는 `onRegistered`로, 잘못된 요청은 `onError`로 보고"와 권한 보호 수준 dangerous를 다시 확인. 초기 상태 통지 여부는 여전히 문서에 없음.
주의:
- 등록 순간 이미 반경 안에 있으면 `ENTER`가 오는지(초기 상태 통지) 문서에 없다 [불확실]. "출발 전 지금 반경 안인가?"는 **현재 위치 1회 + 직접 거리 계산**으로 판단하는 편이 문서 근거상 안전하다.
- 등록 개수 제한, 앱 종료 후 유지 여부 [불확실 — 문서 없음].

## 포트 설계 시사점
ETA는 두 종류로 나눠야 한다. ① 안내 시작 전 **사전 추정**(`requestTrip` → `onTripInfo`, 경로 옵션 반영 불가) ② 안내 중/시작 시 **옵션 반영 값**(`onRouteStarted`, `onDestinationInfo`). 그래서 NaviRepository 포트에 `estimateTrip(destination): TripEstimate?`(옵션 파라미터 없음)를 두고, 실패/미지원 시 `null` 또는 Unavailable을 반환해 출발 마감 계산이 "ETA 모름" 상태를 다룰 수 있게 한다(내비 없는 차량, `CAUSE_SEARCH_TRIP`, 타임아웃). 모든 값이 콜백으로 오므로 어댑터에서 `suspendCancellableCoroutine` + 타임아웃으로 감싸고, `eta`는 남은 초로 확인됐으니 포트의 반환 타입은 `Duration`(남은 시간)으로 고정하고 단위 해석은 어댑터에 둔다. 위치는 NaviHelper `getCurrentLocationInfo`와 FusedLocation(`isSupported` 확인) 둘 다 가능하다. 정정(11/09 리뷰 반영): 이전 "FusedLocation 주 소스, NaviHelper 보조" → 위치 실제 소스 후보 1순위 NaviHelper `getCurrentLocationInfo`(「계운」 사용 가능), 2순위 FusedLocation(현재 '준비 중'). NaviHelper `getCurrentLocationInfo`는 에뮬레이터에서 응답한다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 단 에뮬레이터 위치는 geo fix를 따르지 않고 내비 기본 위치(성남 수정구 시흥동)에 고정된다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. FusedLocation은 에뮬레이터 동작 미확인. 이 우선순위는 설계 판단이며 문서 근거 아님. MVP 시연은 `departure.adapter=fake` 기준. 그래서 포트는 `currentLocation(): GeoPoint?` 단일 메서드와 `locationUpdates(): Flow<GeoPoint>`로 추상화하고 출처는 숨긴다. `LocationData?`가 null일 수 있으니 포트도 nullable. 반경 확인은 ProximityAlert가 있지만 초기 상태 통지가 불확실하므로, 핵심 판단은 core의 순수 거리 계산(위치 1회 + 집 좌표)으로 하고 ProximityAlert는 "주행 중 반경 이탈 알림" 같은 부가 기능 후보로만 둔다.
