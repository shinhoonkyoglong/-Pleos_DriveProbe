# NaviHelper 런타임 확인 (에뮬레이터 실행 결과)

- 실행일: 2026-11-09 15:51~15:58 (KST) · 담당 pleos-kotlin-developer
- 목적: 차량 내비에 설정된 목적지를 출발 전(주차 상태)에 NaviHelper `getDestinationInfo`로 받을 수 있는지 확인한다. 함께 requestRoute, requestTrip, getRouteStateInfo, getCurrentLocationInfo의 실제 응답도 확인한다.
- 확인 수준: 이 문서의 모든 결과는 아래 환경에서 **[실행 확인]**. 실차와 다를 수 있다. 특히 위치·ETA 값은 에뮬레이터 내비 앱의 기본 위치에서 계산된 값이다.
- 근거 노트: navi-route.md, eta-location.md, sdk-setup.md, emulator-injection.md("실행 확인" 절), playground-recheck.md. 실행 시점에는 기존 노트를 수정하지 않고 "정정 필요"(6절)에만 적었다. **2026-11-10 리뷰(reviews/task-01c-navi-runtime-check.md) 후 기존 노트에 반영했다.** 반영 기준은 6절이 아니라 리뷰의 "정정 반영표"(23항목)이고, 6절은 최초 목록이라 일부(반올림·tollFare·5번)를 정정했다. 목록은 dev-log.md 참고.

## 1. 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | `emulator-5554`, Pleos Connect v2.1.0, API 34, 42dot 이미지, swiftshader 렌더링 |
| 사용자 | user 10 (`am start --user current`로 실행) |
| 주행 상태 | `dumpsys car_service` → `Current Driving State: 0` (Parked). 실행 전·중·후 모두 0, 화면 기어 P, 0 km/h |
| 내비 앱 | `ai.umos.maps.android.navigation.app` 3.3.2-rc5-hotfix.3 |
| NaviHelper 서비스 | `ai.pleos.playground.service.navi` 3.0.4 |
| SDK | Maven `ai.pleos.playground:NaviHelper:3.0.5` (인증 없이 받음). 서비스(3.0.4)와 버전이 달랐지만 **연결·호출 문제 없음** |
| 위치 주입 | `adb emu geo fix 126.9780 37.5665`(서울시청). 결과는 4절 참고(내비 앱에는 반영 안 됨) |

### SDK 실제 클래스 확인 (Maven aar + javap)
- `NaviHelper-3.0.5.aar`의 POM 의존성: `navi-data:3.0.4`(compile, 데이터 클래스·enum이 여기 있음), `kotlin-stdlib:1.9.25`, 런타임 `navi-aidl:3.0.1`, `core-ktx:1.8.0`, `kotlinx-coroutines-android:1.6.3`. navi-data는 `kotlinx-serialization-json:1.6.3`에 의존한다.
- aar 매니페스트: 서비스 최소 버전 메타데이터 `min_version_name=2.0.1`(code 11), SDK `current_version_name=3.0.5`(code 75). `<queries>`에 action `ai.pleos.playground.intent.action.NAVI_START`.
- `NaviHelper` public 메서드는 navi-route.md 시그니처 목록과 **일치**한다.
- `RouteInfo(double, double, Double, Double, String×4, RouteOption, String, boolean)`, `TripRequest(double, double, String, String, String)`, `DestinationInfo(...12개)`, `RouteStateInfo(...7개)`, `CurrentLocationInfo(...6개)`, `RouteStartInfo(...5개)`, `TripInfo(...5개)`도 노트와 일치한다. 프로브는 모두 named argument로 호출했다.
- 노트와 다른 점은 6절에 적었다(리스너 콜백 추가분, `NaviMode` 값).

## 2. 프로브 앱

- **프로브 앱·원본 로그는 이전 PC scratchpad에 있었고 저장소에 없음. 2026-11-10 리뷰는 문서 대조로만 수행(재실행 검증 없음).** 아래 경로·산출물은 이전 PC 기준이다.
- 위치: `C:/Users/SSAFY/AppData/Local/Temp/claude/eaa64d6835f13062/c16c3f36-28e6-4377-aae9-d138fef4e42a/scratchpad/pleos-probe` (별도 Gradle 프로젝트이며 저장소 코드·빌드 설정은 건드리지 않았다)
  - 코드: `app/src/main/java/ai/probe/pleos/ProbeActivity.kt` 한 파일
  - 빌드: 저장소의 gradlew·wrapper(Gradle 9.6.0), AGP 9.5.1(내장 Kotlin), compileSdk 37, minSdk 28, targetSdk 34
  - 매니페스트 권한: `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`. 설치 후 `dumpsys package`에서 둘 다 `granted=true`(Normal 권한이라 자동 허용)
- 동작
  - UI가 없다. `onCreate`에서 러너 스레드가 단계를 순서대로 실행한다.
  - SDK 호출은 메인 스레드로 post한다. 대응 콜백이 오거나 10초 타임아웃이 날 때까지 기다린다.
  - 모든 콜백을 `Log.i("PleosProbe", "[스레드명] ...")`로 남긴다.
  - 실행할 단계는 인텐트 extra로 정한다. 예: `adb shell am start -S --user current -n ai.probe.pleos/.ProbeActivity --es steps "init,state,dest,loc,release"`
  - 단계 이름: `init`, `state`(getRouteStateInfo), `dest`(getDestinationInfo), `loc`(getCurrentLocationInfo), `routeEmpty`, `routeFilled`, `trip`, `cancel`, `wait<초>`, `release`
- 요청 값
  - `routeEmpty` = `RouteInfo(latitude=37.5547, longitude=126.9707, poiName="", poiId="", address="", poiSubId="", routeOption=RECOMMENDED)`
  - `routeFilled` = 위와 같은 좌표에 `poiName="서울역", poiId="probe-seoul-station", address="서울특별시 중구 한강대로 405", poiSubId="0"`
  - `trip` = `TripRequest(37.5547, 126.9707, poiId="probe-seoul-station", poiName="서울역", poiSubId="0")`
- 산출물(scratchpad)
  - 로그: `.../scratchpad/logs/run{1..4}-probe.txt`(발췌), `run{1..4}-full.txt`(전체 logcat)
  - 화면: `.../scratchpad/shots/00-before.png` ~ `07-after-cancel.png`

## 3. 단계별 실행 결과 (로그 발췌)

교통 정보 목록(`congestionItemList`)은 길어서 `[...]`로 줄였다. `nowEpochSec`는 프로브가 찍은 현재 epoch 초다.

### 3.1 initialize + 리스너 등록 (run1)
```
15:51:49.166 [probe-runner] >>> STEP initialize start
15:51:49.898 [main] CB initialize.onConnected
15:51:49.899 [main] addListener done
```
- 4번 실행 모두 1초 미만(0.5~0.75초; run1 발췌 0.732초)에 `onConnected`가 왔다. `onFailed`는 한 번도 오지 않았다.
- `onConnected`는 **main 스레드**에서 온다. 데이터 콜백(`onRouteStateInfo` 등)은 **binder 스레드**(`binder:<pid>_N`)에서 온다.

### 3.2 경로 없음 상태 (run1)
```
CB onRouteStateInfo RouteStateInfo(termsAgreement=true, naviMode=SAFE_GUIDING, isRouting=false, routeOption=RECOMMENDED, remainDestinationChargingInt=0, remainWaypointChargingInt=[0, 0], trafficStatus=...)
CB onDestinationInfo DestinationInfo(latitude=0.0, longitude=0.0, displayLatitude=null, displayLongitude=null, name=, address=, remainDistance=0, remainTime=0, tollFare=0, poiId=, poiSubId=null, poiProvider=)
CB onCurrentLocationInfo CurrentLocationInfo(latitude=37.4127212, longitude=127.094681, heading=0.0, address=수정구 시흥동, routeId=, currentRouteIndex=0)
```
- 세 API 모두 20~35ms 안에 응답했다.
- **목적지가 없어도 `onDestinationInfo`가 온다.** 값이 0과 빈 문자열로 채워진 객체가 오고, `onError`나 무응답은 없다.
- `termsAgreement=true`라서 약관 화면 때문에 막히지는 않았다(화면 `00-before.png`에도 약관 화면 없음).
- 현재 위치는 geo fix 값(서울시청 37.5665, 126.9780)이 **아니다**. 원인은 4절.

### 3.3 목적지 설정: requestRoute, POI 문자열을 모두 빈 문자열로 (run2)
```
15:53:41.806 >>> STEP requestRoute(empty poi strings) start
15:53:50.081 CB onRouteStarted RouteStartInfo(duration=2700, distance=23000, tollFare=700, name=, remainDestinationChargingValue=74353)
15:53:50.082 CB onRouteEnded
15:53:50.086 CB onNavigationStatus ROUTE_DRIVE
15:53:50.180 CB onRouteStateInfo RouteStateInfo(termsAgreement=true, naviMode=ROUTE_GUIDING, isRouting=false, routeOption=RECOMMENDED, remainDestinationChargingInt=74353, ...)
15:53:50.948 CB onDrivingInfo DrivingInfo(destination=DrivingState(name=, duration=2700, distance=23000, ...), waypoints=[])
```
- **빈 문자열 `poiName/poiId/address/poiSubId`도 받아 준다.** 좌표만으로 경로가 잡히고 안내가 시작됐다.
- `onRouteStarted`까지 **8.3초** 걸렸다. `onRouteOptionChanged`, `onRoutingCompleted`, `onDestinationChanged`, `onError`는 오지 않았다.
- 요청하지 않았는데 `onRouteStateInfo`와 `onDrivingInfo`가 **푸시로** 왔다.
- `onRouteEnded`는 `onRouteStarted` 직후에 바로 온다. 안내 종료 의미가 아니라 경로 탐색이 끝났다는 신호로 보인다.
- 화면(`02-after-wait.png`): 기어 P인데 내비가 곧바로 **실제 안내 화면**으로 바뀌었다. 표시 값은 "4:38 PM / 45분 · 23km", 버튼 "안내 종료". 사용자 확인 단계는 없었다.

### 3.4 목적지 설정 후, 주차 상태 (run2·run3)
```
(run2, 같은 프로세스) 15:53:56 CB onDestinationInfo DestinationInfo(latitude=37.5547, longitude=126.9707, displayLatitude=null, displayLongitude=null, name=, address=용산구 동자동, remainDistance=23000, remainTime=2700, tollFare=0, poiId=, poiSubId=null, poiProvider=)
(run2, 18초 뒤)        15:54:14 CB onDestinationInfo DestinationInfo(... remainDistance=23000, remainTime=2700, tollFare=0 ...)
(run3, 새 프로세스)    15:54:47 CB onDestinationInfo DestinationInfo(latitude=37.5547, longitude=126.9707, ..., name=, address=용산구 동자동, remainDistance=23000, remainTime=2700, tollFare=0, ...)
```
- 좌표가 요청한 목적지와 **정확히 같다**(37.5547, 126.9707).
- 새로 시작한 프로세스(다른 SDK 세션)에서도 같은 값을 받았다. 응답은 "내가 요청한 목적지"가 아니라 **내비 앱의 현재 목적지 상태**다.
- `remainTime=2700`초(45분), `remainDistance=23000`m. 내비 화면의 "45분 · 23km"와 같다.
  - 값이 분·km 단위 값이다(같은 목적지의 TripInfo는 23,799m / 2,704초, DestinationInfo는 23,000m / 2,700초). 23,799m → 23,000m는 반올림이면 24,000m가 되므로 **내림으로 보이나 계산 방식은 [불확실]**. 두 값은 다른 API에서 왔다.
  - 주차 상태로 약 50초 동안 값이 **변하지 않았다**(발췌 기준 15:53:56 → 15:54:47. 본문의 끝 시각 15:54:55는 전체 로그 기준이며 발췌에 없음).
- **`tollFare=0`이다.** 같은 경로의 `RouteStartInfo.tollFare=700`과 다르다(경로 혼잡 목록에 남산1호터널톨게이트가 있음). 경로 1개 관측이며 700원이 어느 구간 요금인지는 확인하지 않았다.
- 경로가 있을 때는 `getDestinationInfo` 응답이 약 **1.0초** 걸렸다(전체 로그 기준, 발췌 없음). 경로가 없을 때는 50ms 미만이다.

### 3.5 사람이 내비 화면에서 직접 설정한 목적지 (run4) — 핵심 질문
목적지 설정 과정(`adb shell input`):
1. "어디로 갈까요?"를 누른다.
2. 화상 키보드로 "강남역"을 입력하고 검색한다.
3. "강남역 2호선" 항목의 길안내 버튼을 누른다.

곧바로 안내가 시작됐다(`06-after-tap-gangnam.png`: "4:25 PM / 29분 · 13km / 강남역 2호선"). 그다음 프로브를 새로 실행했다.
```
15:57:29 CB onRouteStateInfo RouteStateInfo(termsAgreement=true, naviMode=ROUTE_GUIDING, isRouting=false, routeOption=RECOMMENDED, remainDestinationChargingInt=75674, ...)
15:57:31 CB onDestinationInfo DestinationInfo(latitude=37.49813055, longitude=127.02820277, displayLatitude=null, displayLongitude=null, name=강남역 2호선, address=강남구 역삼동, remainDistance=13000, remainTime=1740, tollFare=0, poiId=36khp38gq8dscm5nqhe3, poiSubId=null, poiProvider=)
15:57:44 CB onDestinationInfo (같은 값, 13초 뒤에도 remainTime=1740 그대로)
```
- **SDK를 거치지 않고 사용자가 내비에 직접 넣은 목적지도 좌표·이름·POI ID까지 그대로 돌려준다.**
- `remainTime=1740`(29분), `remainDistance=13000`(13km). 화면 값과 같다.

### 3.6 requestTrip (run3)
```
(경로 안내 중)  15:54:48 CB onTripInfo distance=23799 eta=2704 soc=96 energy=3047.36 nowEpochSec=1794207288 trafficCount=25 routePointCount=1
(경로 취소 후)  15:55:02 CB onTripInfo distance=23799 eta=2703 soc=96 energy=3047.49 nowEpochSec=1794207302 trafficCount=25 routePointCount=1
```
- `onTripInfo`는 두 번 모두 0.4~0.55초 안에 왔다. 경로가 있을 때도 없을 때도 온다.
- **`eta`는 남은 초(소요시간)다.**
  - 값 2704는 epoch(1794207288)와 자릿수부터 다르다.
  - 같은 목적지의 `RouteStartInfo.duration=2700`, `DestinationInfo.remainTime=2700`과 거의 같다.
  - 14초 뒤 다시 호출해도 2703으로, 시각처럼 늘어나지 않는다.
- **requestTrip은 안내를 시작하지 않는다.** 경로가 없는 상태에서 호출하고 3초 뒤 확인했을 때도 상태가 그대로였다(전체 로그 기준, 발췌 없음).
  - `naviMode=SAFE_GUIDING`, `getDestinationInfo`는 0 값 그대로.
  - `onRouteStarted`, `onNavigationStatus`도 오지 않았다.
- 경로 안내 중에 호출해도 기존 목적지(서울역)와 상태는 그대로였다.
- `TripInfo` 값은 분·km 단위로 뭉개지지 않은 정밀 값이다(m, 초 단위).

### 3.7 POI 문자열을 채워 requestRoute (run3)
```
15:55:08.130 >>> STEP requestRoute(filled poi strings) start
15:55:18.134 <<< STEP requestRoute(filled poi strings) TIMEOUT(10s)
15:55:19.454 CB onRouteStarted RouteStartInfo(duration=2700, distance=23000, tollFare=700, name=서울역, remainDestinationChargingValue=74352)
15:55:23 CB onDestinationInfo DestinationInfo(latitude=37.5547, longitude=126.9707, ..., name=서울역, address=용산구 동자동, ..., poiId=probe-seoul-station, poiSubId=null, poiProvider=)
```
- **`onRouteStarted`가 11.3초 뒤에 왔다. 10초 타임아웃으로는 놓친다.**
- 요청 값과 응답 값의 비교:
  - `poiName`, `poiId`는 그대로 돌아온다.
  - `address`는 우리가 보낸 값("서울특별시 중구 한강대로 405")이 아니라 내비가 역지오코딩한 "용산구 동자동"이 돌아온다.
  - `poiSubId="0"`을 보냈는데 `null`이 돌아온다.

### 3.8 cancelRoute (run3·run4)
```
CB onDrivingInfo DrivingInfo(destination=DrivingState(name=..., duration=0, distance=0, ...))
CB onRouteCancelled
CB onNavigationStatus FREE_DRIVE
CB onDestinationInfo DestinationInfo(latitude=0.0, longitude=0.0, ... 전부 0/빈 값)   ← 요청 안 했는데 푸시
CB onRouteStateInfo RouteStateInfo(... naviMode=SAFE_GUIDING ...)                  ← 요청 안 했는데 푸시
```
- 0.2~0.4초 안에 `onRouteCancelled`가 왔다. 이어서 목적지·상태 콜백이 **요청 없이** 왔다.
- 취소 후 `getDestinationInfo`는 다시 0 값 객체를 돌려준다.

### 3.9 release
- `removeListener` 다음 `release`를 호출했다. 예외는 없었다.
- 마지막 화면(`07-after-cancel.png`)은 시작 전과 같다(목적지 없음, P).

## 4. 위치: 내비 앱은 geo fix를 따르지 않는다
- `getCurrentLocationInfo`는 4번 실행 모두 `37.4127212, 127.094681, "수정구 시흥동"`(성남)을 돌려줬다. 내비 화면 하단 주소도 "수정구 시흥동"이다.
- 원인(전체 logcat, 내비 앱 프로세스): 내비 앱은 GNSS **NMEA 문장**을 직접 파싱한다. 에뮬레이터가 보내는 NMEA가 체크섬 검사에서 탈락한다.
  ```
  W/GpsLocationSource: Invalid NMEA message: $GPGGA,065150,3733.9899,N,12658.6799,E,1,6,,0.0,M,0.,M,,,*47
  W/GpsLocationSource: java.lang.IllegalArgumentException: NMEA checksum mismatch: expected=47, actual=7D
      at ai.umos.maps.android.navigation.app.data.manager.NmeaParser.validateChecksum(NmeaParser.kt:97)
  I/navi_engine: ... mapmatching: [mm] org(127.094681,37.4127212:0) ...
  ```
  - 이 NMEA 좌표는 geo fix 값과 같다(37°33.9899' ≈ 37.5665).
  - 같은 시각 FusedLocation 서비스(`[Pleos]`)는 `FusedLocationData(latitude=37.566498..., longitude=126.977998...)`를 정상으로 받았다.
- 결론 [실행 확인]: 이 에뮬레이터에서는 `adb emu geo fix`로 **NaviHelper의 현재 위치를 바꿀 수 없다.** 내비 앱이 자체 기본 위치에 머물기 때문에 ETA·거리도 그 위치에서 계산된다.
  - 다른 주입 방법 중 `adb emu geo nmea`는 2026-11-10에 시험했고 효과 없었다(10.7). 내비 앱의 시뮬레이션 주행 기능은 시도하지 않았다(미확인).

## 5. 결론 표

| 질문 | 결과 | 확인 수준 |
|---|---|---|
| NaviHelper 3.0.5가 서비스 3.0.4에 연결되나 | 됨. `onConnected` 1초 미만(0.5~0.75초), 오류 없음 | [실행 확인] |
| 출발 전(주차) 내비에 설정된 목적지를 `getDestinationInfo`로 받나 | **받는다.** 좌표가 정확하고 이름·poiId·주소·remainTime·remainDistance가 온다. 사용자가 내비 화면에서 직접 넣은 목적지도 받는다 | [실행 확인] |
| 다른 앱/세션이 설정한 목적지도 보이나 | 보인다. 새 프로세스에서도 같은 값 | [실행 확인] |
| 목적지가 없을 때 응답 | `onDestinationInfo`가 0 값 객체로 온다(lat/lon 0.0, name·poiId "", remainTime 0, poiSubId null). 오류·무응답 아님 | [실행 확인] |
| `remainTime` 단위 | 초. 내비 화면 분 값과 일치(2700=45분, 1740=29분). 분·km 단위 값(23,799m → 23,000m. 내림으로 보이나 계산 방식은 [불확실]) | [실행 확인] (단위 값) / [불확실] (계산 방식) |
| 주차 중 `remainTime` 변화 | 약 50초 동안 변하지 않음(차가 안 움직이면 고정) | [실행 확인] (에뮬레이터 위치 고정 상태 기준) |
| `DestinationInfo.tollFare` | 유료 구간이 있는 경로에서도 0이었다(경로 1개 관측). 사후 신호로는 `RouteStartInfo.tollFare`(700)를 쓴다 | [실행 확인 1건] |
| `getRouteStateInfo` 값 | 경로 없음 `naviMode=SAFE_GUIDING`, 경로 안내 중 `ROUTE_GUIDING`. **`isRouting`은 안내 중에도 false**. `termsAgreement=true` | [실행 확인] |
| `requestRoute`의 빈 문자열 POI 허용 | 허용. 좌표만으로 경로가 잡히고 안내가 시작됨 | [실행 확인] |
| `requestRoute` 응답 시간 | `onRouteStarted`까지 8.3초 / 11.3초. 10초 타임아웃으로 부족 | [실행 확인] |
| `requestRoute`가 실제 안내를 시작하나 | 시작한다. 주차(P) 상태에서도 확인 없이 바로 안내 화면으로 바뀜 | [실행 확인] |
| `requestRoute`의 `onRouteOptionChanged` | 오지 않음(RECOMMENDED 요청, 옵션 변경 없음). `RouteStateInfo.routeOption=RECOMMENDED`로 확인 가능 | [실행 확인] |
| `requestTrip` → `onTripInfo` | 옴(0.4~0.55초). 경로가 있을 때도 없을 때도 옴 | [실행 확인] |
| `TripInfo.eta` 의미 | **남은 초(소요시간)**. epoch 아님 | [실행 확인] |
| `requestTrip`이 안내를 시작하나 | 시작하지 않음. 상태·목적지 그대로 | [실행 확인] |
| `getCurrentLocationInfo` = geo fix 위치? | 아님. 내비 앱이 NMEA 체크섬 오류로 GNSS를 버리고 기본 위치(성남 수정구 시흥동)에 머묾 | [실행 확인] |
| `onDestinationChanged` / `onRoutingCompleted` | SDK로 목적지를 설정·취소한 4번 동안 한 번도 오지 않음(11/09). 사람이 내비 화면에서 목적지를 바꿀 때도 오지 않고 `onRouteCancelled → … → onRouteStarted`가 온다(2026-11-10, 10.5) | [실행 확인] (SDK 설정·취소, 11/09) / [실행 확인 2026-11-10 — 터미널·캡처] (사람이 화면에서 변경, 10.5) |
| `requestRoute(FREE)`·`changeRouteOption` | 2026-11-10 시험: 둘 다 적용되지 않음(`tollFare=700`, `routeOption=RECOMMENDED`, `onRouteOptionChanged` 없음). `requestTrip` ETA는 내비의 현재 유료도로 우회 설정을 따름 (10.1~10.6) | [실행 확인 2026-11-10] |
| `TripRequest`의 빈 문자열 POI | 허용(2026-11-10, 10.6) | [실행 확인 2026-11-10] |
| `termsAgreement=false`일 때 동작 | 시험하지 않음(항상 true) | [미확인] |
| `adb emu geo nmea`(체크섬을 맞춘 문장), 내비 시뮬레이션 주행 | `geo nmea`: 2026-11-10 시험, 효과 없음(10.7). 내비 시뮬레이션 주행은 시험하지 않음 | [실행 확인 2026-11-10] (`geo nmea`) / [미확인] (시뮬레이션) |
| 주행 중(DrivingState≠0) 동작 | 시험하지 않음 | [미확인] |
| 실차 동작, 다른 내비 버전 | 시험하지 않음 | [미확인] |

## 6. 정정 필요 (기존 노트 대비 — 최초 목록, 2026-11-10 리뷰 후 반영 완료)
반영 기준은 reviews/task-01c-navi-runtime-check.md의 "정정 반영표"다. 이 절에서 리뷰로 바뀐 것: 3번(TripRequest 미시험), 5번("모든 요청" → 시험한 요청), 10번(경로 1개 관측), 11번("반올림" 삭제).

1. **navi-route.md / eta-location.md — `TripInfo.eta` [불확실] 해소**: 남은 초(소요시간)다. "어댑터에서 값 범위로 판별하는 방어 코드"는 필요 없다. 다만 실차 확인 전까지 `eta > 10^9`이면 epoch로 보고 거부하는 정도의 검사는 둘 만하다(설계 판단).
2. **eta-location.md — requestTrip이 사전 계산인지 [불확실] 해소**: 안내를 시작하지 않는 사전 조회다. 경로 안내 중에 호출해도 기존 목적지에 영향이 없다.
3. **navi-route.md — RouteInfo 필수 문자열의 빈 문자열 허용 여부 [불확실] 해소**: 빈 문자열도 받는다.
   - 응답에서 `address`는 내비가 역지오코딩한 값으로 바뀐다.
   - `poiSubId`는 `null`로 돌아온다.
   - `poiName`, `poiId`는 그대로 돌아온다.
   - `TripRequest`의 빈 문자열은 2026-11-10에 시험했고 허용된다(10.6).
4. **navi-route.md — `termsAgreement`**: 이 에뮬레이터에서는 true다. false일 때의 동작은 여전히 [미확인].
5. **navi-route.md — 에뮬레이터에 NaviHelper 요청을 처리할 내비 앱이 있는지 [불확실] 해소**: 있고, 시험한 요청(init·get*Info 3종·requestRoute·requestTrip·cancelRoute)에 모두 응답했다.
6. **navi-route.md — `RouteStateInfo.isRouting`**: 경로 안내 중에도 false다. "안내 중" 판단은 `naviMode == ROUTE_GUIDING`(또는 `NavigationStatus.ROUTE_DRIVE`)으로 해야 한다. `isRouting`은 경로 탐색 진행 중을 뜻하는 값으로 보인다(추정).
7. **navi-route.md — `NaviMode` 값 누락**: 실제 enum은 `NOT_WORKING`, `SAFE_GUIDING`, `ROUTE_GUIDING`, `SIMULATION_GUIDING`이다(javap). 경로 없음 상태는 `SAFE_GUIDING`이다.
8. **navi-route.md — 리스너 콜백 표 누락분**: 실제 `NaviHelperEventListener`(Kotlin interface, 모든 메서드 default)에는 노트에 없는 콜백이 더 있다.
   - 목록: `onRouteEnded()`, `onWaypointChanged(WaypointChangedInfo)`, `onWayPointArrived(WaypointArrivedInfo)`, `onBookMarkChanged()`, `onWayPointInfo(List<WaypointInfo>)`, `onRecentDestinationInfo(List)`, `onBookmarkInfo(List)`, `onCurrentRoadSpeedLimit(Int)`, `onCameraAlert(List)`, `onTBTInfo(List)`, `onChargerOperatorInfo(List)`, `onCautionPointInfo(List)`
   - `onRouteEnded`는 `onRouteStarted` 직후에 온다(안내 종료가 아님).
9. **navi-route.md — 콜백 흐름**: 문서 흐름("requestRoute → onRouteStarted → onRoutingCompleted, 목적지 변경 시 onDestinationChanged")과 실제가 다르다.
   - `onRoutingCompleted`와 `onDestinationChanged`는 오지 않았다.
   - 대신 `onRouteStarted → onRouteEnded → onNavigationStatus(ROUTE_DRIVE) → onRouteStateInfo(푸시) → onDrivingInfo` 순서로 온다.
   - 취소 때는 `onRouteCancelled → onNavigationStatus(FREE_DRIVE) → onDestinationInfo(0 값, 푸시) → onRouteStateInfo(푸시)` 순서로 온다.
10. **eta-location.md — `DestinationInfo.tollFare`**: 유료 경로에서도 0으로 온다(이 내비 버전). 경로 1개 관측이다. 사후 유료 신호로는 `RouteStartInfo.tollFare`를 쓴다.
11. **eta-location.md — `DestinationInfo.remainTime/remainDistance`**: 분·km 단위 값이다(23,799m → 23,000m. 내림으로 보이나 방식은 [불확실]). 정밀 값은 `TripInfo`에 있다.
12. **emulator-injection.md "실행 확인" — "NaviHelper getCurrentLocationInfo가 geo fix 값을 따르는지는 미확인" 해소**: 따르지 않는다.
    - 내비 앱 `NmeaParser`가 에뮬레이터 NMEA를 체크섬 불일치로 거부한다.
    - 그래서 기본 위치 37.4127212, 127.094681(수정구 시흥동)에 고정된다.
    - FusedLocation 서비스는 geo fix 값을 정상으로 받는다.
13. **sdk-setup.md / navi-route.md — 의존성**:
    - NaviHelper 3.0.5는 `navi-data:3.0.4`를 끌어온다. 이 안에 data·constants 패키지가 있다.
    - Kotlin stdlib 1.9.25로 빌드됐다. Kotlin 2.2 프로젝트에서도 문제없이 컴파일됐다.
    - 서비스 3.0.4와 SDK 3.0.5의 버전 차이로 생긴 문제는 없다.
14. **콜백 스레드(노트에 없음)**: `initialize`의 `onConnected`는 main 스레드에서, 리스너 콜백은 binder 스레드에서 온다.

## 7. '출발 전' 설계에 주는 영향

### 7.1 목적지 판정 (허용 반경 밖 경고)
- **가능하다.** 출발 전 주차 상태에서 `getDestinationInfo` 한 번으로 내비 목적지 좌표를 받을 수 있다. 응답은 1초 안팎이다.
  - 우리 앱이 설정한 목적지가 아니어도 된다. 사용자가 내비에서 직접 고른 목적지도 받는다.
  - 받은 좌표로 core의 반경 판정(집 좌표 ↔ 목적지 직선거리)을 그대로 쓸 수 있다.
- **목적지 없음 판별**: 응답 객체가 0 값으로 온다. 어댑터에서 "목적지 없음"을 판별해 포트에는 `null`(또는 sealed `NoDestination`)로 넘겨야 한다.
  - 기본 판별: `latitude == 0.0 && longitude == 0.0`(필요하면 `poiId`·`address`가 빈 값인지도 함께 본다)
  - `naviMode`는 보조로만 쓴다. `naviMode != ROUTE_GUIDING`을 판별 기준으로 쓰면 `SIMULATION_GUIDING`(javap로 확인된 값)을 목적지 없음으로 잘못 본다. 호출도 1번 더 든다.

  0,0 좌표를 그대로 넘기면 "반경 밖"으로 잘못 경고한다.
- 목적지가 없는 상태는 "경고 없음"이 아니라 "목적지 미설정 안내" 화면 상태로 따로 다루는 것이 맞다(설계 판단).

### 7.2 `remainTime`을 출발 마감 ETA로 쓸 수 있나
- **쓸 수 있다(조건부).** 단위는 초이고, 내비가 현재 위치에서 계산한 소요시간이다. **`remainTime`은 내비가 지금 안내 중인 경로의 옵션 기준이다.** 그 옵션은 사용자가 내비에서 고른 것이다(우리가 `requestRoute`로 FREE를 요청해도 적용되지 않는다 — 10절). `requestTrip`은 옵션을 받지 않으므로(eta-location.md) 두 값을 같은 값으로 취급하려면 옵션이 같을 때만 가능하다(`RouteStateInfo.routeOption`으로 확인). "두 값 차이 4초"는 둘 다 RECOMMENDED일 때 1건 비교한 결과다. 출발 마감 계산의 "목적지까지 걸리는 시간"으로 바로 쓸 수 있다: 도착 예상 = now + remainTime.
- 주의할 점:
  1. 분 단위 값이다(내림으로 보이나 방식은 [불확실]. 내림이면 최대 59초 짧게 잡혀 출발 마감에서는 비보수 방향이다). 정밀도가 필요하면 `requestTrip`의 `TripInfo.eta`(정밀 초, 안내 시작 안 함)가 낫다. 두 값의 차이는 4초였다.
  2. 주차 중에는 갱신되지 않는다(고정). 오래 기다린 뒤에는 `requestTrip`으로 다시 조회하는 편이 안전하다.
     - 교통 반영으로 바뀌는지는 이번 실행 시간(약 1분) 안에서는 확인하지 못했다.
  3. 에뮬레이터에서는 출발지가 내비 기본 위치(성남)로 고정된다. geo fix로 바꿀 수 없어서 시연 수치를 원하는 값으로 만들기 어렵다. 시연은 Fake 시나리오를 유지하는 것이 맞다.
- 추천 흐름(설계 판단):
  1. `getDestinationInfo`로 목적지를 받는다.
  2. 목적지가 있으면 `remainTime`을 1차 ETA로 쓴다(현재 안내 경로의 옵션 기준 — 위 주의 참고).
  3. 필요하면 같은 좌표로 `requestTrip`을 호출해 정밀 ETA로 갱신한다.

  두 호출 모두 안내 상태를 바꾸지 않는다.
- `requestRoute`는 실제 안내를 즉시 시작한다(경로 없는 상태에서 확인). 안내 중에 다른 목적지로 `requestRoute`하면 기존 안내를 덮어쓴다(2026-11-10 확인, 10.8). 또 `RouteInfo.routeOption`이 무시되므로 "무료도로 경로 요청"은 아예 호출하지 않는 것이 MVP 방침이다(10.4). 응답이 8~11초 걸리므로 타임아웃은 **20초 이상**으로 잡아야 한다.

### 7.3 어댑터(1번 Repository 위) 구현 시 반영할 점
- 콜백이 binder 스레드에서 오므로 `suspendCancellableCoroutine` 안에서 스레드 전환·동기화가 필요하다.
- 요청하지 않은 푸시 콜백(취소 시 `onDestinationInfo` 0 값, 경로 시작 시 `onRouteStateInfo`)이 온다. 요청 ID가 없으므로 "요청 직후 첫 응답" 방식이면 푸시를 응답으로 오인할 수 있다. 요청 직렬화(Mutex)는 필수지만 그것만으로는 부족하다. Mutex로는 요청 대기 중에 외부 사건으로 온 푸시(예: 사람이 취소해서 온 0 값 `onDestinationInfo`)를 걸러낼 수 없다. 목적지·상태는 요청-응답보다 "마지막으로 받은 상태" 캐시로 다루거나, 0 값이 오면 짧게 1회 재조회한다(설계 판단).
- 타임아웃 권장값(실측 기반):
  - `get*Info`·`requestTrip`: 5~10초(실측 최대 1.03초 — 전체 로그 기준, 발췌 없음)
  - `requestRoute`: 20초 이상
  - `cancelRoute`: 5초
- `onDestinationChanged`는 SDK로 설정·취소할 때 오지 않았으므로 목적지 변경 감지에 쓸 수 없다. **기본은 화면 진입·백그라운드 복귀마다 조회**한다. 사람이 내비 화면에서 바꿀 때는 `onDestinationChanged`가 오지 않고 `onRouteCancelled → FREE_DRIVE → … → onRouteStarted`가 온다(2026-11-10, 10.5). 다음 신호를 보조로 쓸 수 있다. 단 `onRouteCancelled`는 "경로 교체 중"일 수 있다.
  - `onNavigationStatus(ROUTE_DRIVE/FREE_DRIVE)`
  - `onRouteStarted`
  - `onRouteCancelled`
- 통행료(`tollFare`)는 `DestinationInfo`에서 읽지 않는다.

### 7.4 integration-guide / 계약 파일 갱신 제안 (이번 작업에서는 수정하지 않음)
- `NaviRepository`의 목적지 조회 메서드는 "목적지 없음"을 표현해야 한다(nullable 또는 sealed).
- `requestTrip` 기반 ETA 조회는 실제로 동작한다. integration-guide "1번 연동 시 수정 목록" 2번(requestTrip 노출 여부)에 "에뮬레이터 실행 확인됨, 1번이 노출해 주길 요청" 근거로 추가할 수 있다.
- 위 두 가지는 계약 파일 변경이 따르므로 #5 작업자·감독관 판단에 맡긴다.
- 제안(감독관 판단, MINOR-7): 다음 실행부터는 프로브 소스(`ProbeActivity.kt` 한 파일)를 저장소의 별도 폴더(예: `tools/pleos-probe/`, 빌드에 포함하지 않음)에 두고, 로그 발췌 원문을 `docs/pleos/api-notes/` 아래에 남긴다. 이번(11/09)에는 둘 다 이전 PC에만 있어 재확인하지 못했다. **결정(2026-11-10): `tools/pleos-probe/`가 아니라 `evidence/2026-11-10/probe/`에 둔다.**

## 8. 부수 효과·한계
- 내비 앱의 "최근 목적지" 목록에 서울역, 이름 없는 항목, 강남역 2호선이 남았다(사용자 데이터 성격). 지우려면 내비 화면 "최근 > 편집"을 조작해야 해서 그대로 두었다.
- 경로는 모두 취소해 시작 전 상태로 되돌렸다.
- 프로브 앱 `ai.probe.pleos`는 다시 실행할 수 있도록 설치된 채로 두었다. 제거하려면 `adb uninstall ai.probe.pleos`.
- 주행 상태(DrivingState ≠ 0)와 주행 규제 중 동작은 시험하지 않았다. 기어 변경은 Virtual Console을 거쳐야 해서 범위 밖으로 두었다.

## 9. 다음 실행에서 확인할 것
- (2026-11-10에 모두 시험함 → 10절. 남은 것은 10.10절 목록)

## 10. 2026-11-10 실행 확인 (9절 미확인 6항목 + 내비 앱 디버그 로그 대조)

- 실행: 2026-11-10 09:07~09:21 (에뮬레이터 시계, 로그 `11-10 09:xx`). 담당 pleos-sdk-researcher. 1차 작성 후 2026-11-10 리뷰(reviews/task-01d-navi-runtime-check-2.md) 반영본이다.
- 환경: `emulator-5554` Pleos Connect v2.1.0 (API 34, swiftshader_indirect), 사용자 10, `Current Driving State: 0`(P, 0 km/h) 전 구간. 내비 앱 `ai.umos.maps.android.navigation.app` **3.3.2-rc5-hotfix.3**(rc·hotfix 빌드), 서비스 `ai.pleos.playground.service.navi` 3.0.4, SDK(프로브) 3.0.5. 아래 모든 결론은 이 조합 한정이다.
- 증거 위치(저장소): `docs/pleos/api-notes/evidence/2026-11-10/` — `rN-probe.txt`(SDK 콜백), `rN-navi.txt`(내비 앱 디버그 로그), `rN-key.txt`(전체 logcat에서 뽑은 `MainViewModel`·`Navi42`·NMEA 줄), `probe/`(프로브 소스·스크립트·NMEA 입력), `screens/`(인용한 캡처). 파일 설명과 수집 방법은 그 폴더의 `README.md`. 이하 `evidence/…`는 이 폴더 기준 경로다.
- **확인 수준 표기** (근거 종류를 분리했다)
  - **[실행 확인 2026-11-10 — 로그]**: SDK 프로브 로그(`rN-probe.txt`)에 원본이 있다.
  - **[실행 확인 2026-11-10 — 터미널·캡처]**: 원본 로그 파일이 없고 터미널 출력과 화면 캡처로만 봤다. 값은 터미널 출력을 옮겨 적은 것이라 시각·순서의 재검증이 안 된다.
  - **[로그 관찰 — API 아님]**: 내비 앱(다른 앱)의 디버그 logcat. 일반 앱은 READ_LOGS 권한이 없어 못 읽는다. 판정 보조 근거로만 쓰고 **앱 기능의 근거로 쓰지 않는다.**
- 프로브: 11/09 프로브(`ProbeActivity.kt` 한 파일)에 단계 3개를 추가했다(`evidence/…/probe/ProbeActivity.kt.txt`): `route:<lat>:<lon>:<OPTION>:<name>`(RouteInfo.routeOption 지정), `opt:<OPTION>`(changeRouteOption), `tripEmpty`(TripRequest 빈 문자열). 빌드는 Android Studio jbr(JVM 17+)로 했다.

### 10.0 실행 이력 (목적지 전환 포함)

`run.sh`가 실행마다 `logcat -c`를 하므로 **실행 사이의 adb input 조작 구간은 로그가 없다.** 표의 "사이 조작"은 캡처(`s*.png`)로 복원한 것이다.

| 태그 | 시각 | 시작 상태 → 한 일 | 목적지 | 관련 항목 |
|---|---|---|---|---|
| r1 | 09:09 | 경로 없음 → `requestRoute(37.5547,126.9707, FREE)` → 12초 대기 → state·dest·trip | 서울역 좌표 | 1 |
| r2 | 09:10 | 서울역 안내 중 → `changeRouteOption(FREE)` → state·dest·loc·trip | 서울역 | 2 |
| r3 | 09:11 | 서울역 안내 중 → `requestRoute(37.4981,127.0282, SHORT_DISTANCE)` | 서울역 → 강남 | 6, 1 보강 |
| (로그 없음) | 09:12~09:14 | `wait150` 프로브만 켜고 adb input으로 조작: 강남 안내 중(s2) 최근 목록에서 서울역 선택(s3~s6) → 서울역 안내(s7, 09:13:22 시작) → 다시 강남 선택(s8 팝업)·강남 안내(s9, 09:14) → 안내 종료 → 서울역 미리보기(s11) → 서울역 안내(s12). 콜백은 터미널 출력으로만 확인 | 강남 ↔ 서울역 반복 | 3 |
| 사이 조작 | 09:14:5x | 서울역 안내 종료 → 첫 번째 최근 항목(강남) 미리보기 → 경로 설정 시트(s13) | 강남 | — |
| r4 | 09:15 | **강남 미리보기에서** 시트의 토글 탭이 안 먹힌 채(꺼진 상태) "적용"(s15) → 경로 후보 2개(700원) → 안내 시작 | 강남 | 3 (같은 목적지, 옵션 변경 없음 재탐색) |
| 사이 조작 | 09:16~09:17 | 강남 안내 종료 → 첫 번째 최근 항목(서울역) 미리보기 → 경로 설정 시트 → **swipe로 "유료도로 우회" 켬** | 강남 → 서울역 | — |
| r5 | 09:17~09:18 | **서울역 미리보기에서** "적용"(s20 후보에 통행료 없음) → 안내 시작 → state·dest·trip | 서울역 | 1·2·3·4 판정 (같은 목적지, 옵션만 변경 재탐색) |
| r6 | 09:18 | 서울역 FREE 안내 중 → `tripEmpty` → `changeRouteOption(RECOMMENDED)` → state·dest | 서울역 | 4, 2 |
| r7·r8 | 09:19~09:20 | `adb emu geo nmea` 주입 후 `getCurrentLocationInfo` | (경로 있음) | 5 |

- 사이 조작 중 목적지가 서울역 ↔ 강남으로 오갔다. r4 직전 상태는 서울역 안내 → 사람이 강남으로 바꾼 직후이고, r5 직전에는 강남 안내 → 사람이 서울역으로 바꿨다. 이 변경 자체의 콜백은 로그에 없다(10.5).

### 10.1 항목별 결론

| # | 질문 | 결론 | 확인 수준 |
|---|---|---|---|
| 1 | `requestRoute(FREE)`의 tollFare·적용 옵션·`onRouteOptionChanged` | **FREE는 적용되지 않는다.** `RouteStartInfo.tollFare=700`(RECOMMENDED와 동일), `routeOption=RECOMMENDED`, `onRouteOptionChanged` 오지 않음. 내비 앱이 받은 요청에 옵션 필드가 없다 | [실행 확인 2026-11-10 — 로그] + [로그 관찰 — API 아님] |
| 2 | `changeRouteOption(FREE)` 동작 | **아무 효과 없다.** 콜백·상태 변화 없음(10초 타임아웃). 요청은 내비 앱까지 닿지만 처리되지 않는다 | [실행 확인 2026-11-10 — 로그] + [로그 관찰 — API 아님] |
| 3 | 사람이 내비 화면에서 목적지를 바꿀 때 콜백 | `onDestinationChanged`는 오지 않는다. 안내 중 목적지 변경은 `onRouteCancelled → FREE_DRIVE → onDestinationInfo(0값) → onRouteStateInfo → … → onRouteStarted → onRouteEnded → ROUTE_DRIVE → onRouteStateInfo → onDrivingInfo` | 순서·시각: [실행 확인 2026-11-10 — 터미널·캡처](09:13 구간, s7·s8). `onDestinationChanged` 없음과 같은 순서의 재탐색: [실행 확인 2026-11-10 — 로그](r4·r5, 10.5) |
| 4 | `TripRequest` 빈 문자열 허용, requestTrip ETA의 옵션 기준 | 빈 문자열 **허용**. ETA는 **내비 앱이 그 순간 쓰는 "유료도로 우회" 설정을 따른다**(요청으로 옵션을 줄 수 없음) | [실행 확인 2026-11-10 — 로그] + [로그 관찰 — API 아님] |
| 5 | `adb emu geo nmea`(체크섬 맞춘 문장)로 내비 위치 주입 | **효과 없음**(r8 한 건이 판별 근거, 10.7). 내비 위치는 처음부터 GNSS와 무관하게 고정돼 있다 | [실행 확인 2026-11-10 — 로그] + [로그 관찰 — API 아님] |
| 6 | 안내 중 다른 목적지로 `requestRoute` | **덮어쓴다.** 기존 안내를 취소하고 새 목적지로 안내가 시작된다(SDK 호출 쪽에 사람 입력 없이 약 7초 후) | [실행 확인 2026-11-10 — 로그] (r3) |

### 10.2 항목 1: `requestRoute(FREE)` (r1)

`evidence/…/r1-probe.txt`:
```
09:09:16.716 STEP requestRoute(37.5547,126.9707,FREE,) start        # RouteInfo(routeOption=FREE), poi 문자열 모두 ""
09:09:24.824 CB onRouteStarted RouteStartInfo(duration=3900, distance=23000, tollFare=700, name=, ...)   # 8.1초
09:09:24.826 CB onRouteEnded                                         # 안내 종료가 아님: 경로 시작 직후에 온다
09:09:24.829 CB onNavigationStatus ROUTE_DRIVE
09:09:24.865 CB onRouteStateInfo RouteStateInfo(... naviMode=ROUTE_GUIDING, isRouting=false, routeOption=RECOMMENDED ...)
09:09:37.999 CB onRouteStateInfo (12초 뒤 조회) routeOption=RECOMMENDED
09:09:40.055 CB onDestinationInfo (... remainDistance=23000, remainTime=3900, tollFare=0 ...)
            (onRouteOptionChanged 없음)
```
- 주의: `onRouteEnded`는 `onRouteStarted` 직후(2ms)에 온다. 어댑터가 이것을 "안내 종료"로 해석하면 안 된다(10.5에서도 같은 순서).
- [로그 관찰 — API 아님] `evidence/…/r1-key.txt`: 내비 앱이 받은 요청 `OnRouteRequest(routeInfo=RouteInfo(address=, latitude=37.5547, longitude=126.9707, poiId=, poiName=, poiSubId=, isMobileRequest=false))` — **`routeOption` 필드가 없다**(SDK→서비스→내비 앱 중 어디서 떨어지는지는 모름). `Navi42: [createTrip] tripTollAvoidance null, tripSchoolZoneAvoidance null`. `r1-navi.txt`: `routeMainOption: [42_route_MOP_01]`, 요청 JSON `"avoid_fare":false`, `avoidOptions=[]`, `roadFare=700`, `roadSegments=[Highway 14.9km, Main1 7.8km, Etc1 1.0km]`, `currentRouteOption: RECOMMENDED`. 즉 고속도로와 통행료 700원이 그대로다.
- r3에서 `routeOption=SHORT_DISTANCE`로 요청해도 같다(`r3-probe.txt` 09:11:42.728 `routeOption=RECOMMENDED`, `r3-key.txt` `tripTollAvoidance null`, `r3-navi.txt` `MOP_01`). **시험한 두 값(FREE, SHORT_DISTANCE) 모두 무시된다.** 나머지 값은 [미확인].
- 소요시간이 11/09(2700초)과 다르다(3900초). 같은 좌표·옵션이다. 교통 반영 시각 차이로 보이나 원인은 [불확실]. 시연 수치는 실행 시각에 따라 바뀐다.

### 10.3 항목 2: `changeRouteOption` (r2, r6)

`evidence/…/r2-probe.txt`:
```
09:10:31.913 STEP changeRouteOption(FREE) start          # ROUTE_GUIDING 중
09:10:41.914 STEP ... TIMEOUT(10s)                        # 콜백 없음
09:10:54.945 CB onRouteStateInfo (... routeOption=RECOMMENDED ...)   # 변화 없음
09:10:56.955 CB onDestinationInfo (... remainTime=3900 ...)           # 변화 없음
```
- [로그 관찰 — API 아님] `r2-key.txt`: `[NaviProvider][SDK]onChangeRouteOptionRequest`, `ChangeRouteOptionRequest(routeOption=FREE)`로 내비 앱에 도달한다. 이후 재탐색 로그(`createTrip`, `find_route`)가 없고(`r2-navi.txt`) `mainOptionName=MOP_01, avoidOptions=[], roadFare=700, currentRouteOption: RECOMMENDED` 그대로다.
- 반대 방향(유료도로 우회 안내 중 `changeRouteOption(RECOMMENDED)`, r6)도 효과 없다: `r6-probe.txt` 09:19:17.360 `routeOption=FREE` 유지, `r6-navi.txt` `avoidOptions=[42_route_AOP_02]` 유지.
- 반환값·`onRouteOptionChanged`·`onError` 모두 없어 호출자는 성공·실패를 알 수 없다.

### 10.4 "무료도로"를 실제로 적용하는 경로 (내비 화면 조작, r5)와 추천

내비 앱 UI에는 경로 미리보기 화면의 필터 버튼 → "경로 설정" 시트가 있다(`evidence/…/screens/s13.png`). 토글은 **"유료도로 우회"**(유료도로를 피해 안내)와 **"어린이 보호구역 우회"** 두 개이고 "적용" 버튼이 있다. 토글은 adb 탭으로는 안 켜지고 짧은 swipe(`input swipe 1375 361 1415 361 150`)로 켜졌다(r4가 탭 실패, r5가 swipe 성공). 켜고 적용한 뒤 안내를 시작했다. `evidence/…/r5-probe.txt`:
```
09:17:42.667 CB onRouteCancelled                      # 미리보기에서 "적용" → 재탐색
09:17:42.720 CB onNavigationStatus FREE_DRIVE
09:17:42.726 CB onDestinationInfo (0 값 푸시)
09:17:58.638 CB onRouteStarted RouteStartInfo(duration=4440, distance=26000, tollFare=0, name=서울역, ...)   # 안내 시작
09:17:58.707 CB onRouteStateInfo (... routeOption=FREE ...)
09:18:15.464 CB onRouteStateInfo (16초 뒤 재조회) routeOption=FREE
09:18:17.485 CB onDestinationInfo (name=서울역, remainDistance=26000, remainTime=4440, tollFare=0 ...)
09:18:19.056 CB onTripInfo distance=26494 eta=4588 ...
            (onRouteOptionChanged 없음)
```
r6-probe 09:18:53.761: **새 프로세스(새 SDK 세션)가 `getRouteStateInfo`로 `routeOption=FREE`를 읽었다**(재조회 가능).
- [로그 관찰 — API 아님] `r5-navi.txt`: `tripTollAvoidance true`, `avoidOptions=[42_route_AOP_02]`, `roadFare=0`, `roadSegments`는 Highway 7.2km + CityHighway 7.7km + Nation 1.9km 등(통행료 구간 제거. RECOMMENDED는 Highway 14.9km). 화면 `screens/s20.png`: 통행료 표시 없음, 후보마다 "유료도로 우회" 배지(`s15.png`는 토글이 꺼진 채 적용한 경우로 "700원" 표시).
- 효과: 통행료 0원, 거리 23km → 26km, 시간 +14%(3900 → 4440초). 통행료 없는 고속도로 7.2km가 남으므로 "유료도로 회피"이지 "고속도로 회피"가 아니다.
- **SDK 쪽에서 볼 수 있는 신호와 그 성질** (앱이 쓸 수 있는 근거는 이 둘뿐)
  1. `RouteStateInfo.routeOption == FREE`: **언제든 `getRouteStateInfo()`로 재조회할 수 있다**(새 프로세스에서도 읽힘, r6). 푸시로도 온다(`onRouteStateInfo`). **주 판정 신호.**
  2. `RouteStartInfo.tollFare == 0`(RECOMMENDED는 700): **`onRouteStarted` 푸시로 경로 시작 때 한 번만 온다.** 재조회 API가 없다. 우리 앱 리스너가 붙기 전(앱이 백그라운드이거나 사용자가 먼저 안내를 시작한 경우)에는 받을 수 없다. 받았을 때만 쓰는 **보조 신호**다.
  3. `DestinationInfo.tollFare`는 FREE·RECOMMENDED 모두 0이라 구분에 못 쓴다(r1 09:09:40.055, r5 09:18:17.485).
- **조합의 한계**: `routeOption == FREE`가 "유료도로 우회 on"과 1:1인지는 이 한 조합만 봤다. "어린이 보호구역 우회"를 켜거나 두 토글을 함께 켰을 때 `routeOption`이 어떤 값이 되는지(FREE가 아닌 값·SCHOOL_ZONE 등)는 **[미확인]**이다.
- 이 설정이 경로 한 건에만 속하는지는 **[불확실]**: 서울역 FREE 안내를 끝낸 뒤 **다른 목적지(강남)** 미리보기에서 시트를 열었을 때 토글이 꺼져 있었다(`screens/s21.png`, 관찰 1건). 같은 목적지 재진입은 확인하지 않았다.

- **[실행 확인 2026-11-11] "어린이 보호구역 우회"만 켜도 `routeOption=FREE`다.** 그때 선택 경로는 23km·통행료 700원(추천과 같은 유료 경로)이었다. 즉 FREE는 "유료도로 우회 켬"의 증거가 아니라 "어느 회피 옵션이든 켬" 정도의 신호다. 2번 판정은 이 경우를 `Satisfied`로 잘못 본다(근거 `evidence/2026-11-11/childzone/`). 두 토글 동시는 여전히 [미확인]

- **[실행 확인 2026-11-11, 1회] 사람이 "유료도로 우회"를 켜고 안내를 시작하는 동안 우리 앱이 백그라운드였어도, 앱이 다시 앞으로 올 때 우리 앱에만 `onRouteCancelled` → `onRouteStarted(tollFare=0)`이 다시 왔다.** 그래서 작업 #27 판정(FREE + 0원 → 충족)이 실제 흐름에서 충족으로 나왔다(`evidence/2026-11-11/manual-toll-free/`). 추가 확인 결과 이것은 재전달이 아니라 **얼려진(isFrozen) 앱에 보낸 콜백이 깨어날 때 늦게 도착**한 것으로 보인다. 앱을 완전히 종료했다 다시 열면 받지 못했다(같은 evidence README 추가 확인 절). tollFare 를 '다시 조회 가능'으로 보지 않는다

- **[실행 확인 2026-11-11] 차량 설정 → 내비게이션 → 경로 → "유료도로 우회"(전역 기본값)를 켜 두면, 시트를 건드리지 않아도 새 경로가 유료도로 우회(tollFare=0)로 안내된다.** 기본값은 꺼짐. SDK로 읽기·쓰기 방법은 없음(근거 `evidence/2026-11-11/manual-toll-free/` 추가 확인 절)

**추천: 내비에서 사용자가 이미 고른 목적지에 무료도로를 적용하는 방법**

(2026-11-10 사용자 결정: MVP 경로 기능은 "내비에서 유료도로 우회를 켜도록 안내 + 사후 확인"이며, **이미 설정된 목적지에 `requestRoute`를 호출하지 않는다.** 아래는 그 흐름을 뒷받침하는 근거다.)

1. **SDK 호출로는 적용할 수 없다.** `requestRoute(FREE)`·`changeRouteOption(FREE)`는 무시된다(10.2·10.3). `requestRoute`는 기존 안내를 취소하고 덮어쓰며(10.8), 그때 옵션이 `tripTollAvoidance null`/`avoid_fare:false`로 만들어지므로 **사용자가 이미 켜 둔 "유료도로 우회"가 풀릴 위험이 있다**(FREE 안내 중 재요청은 시험하지 않았다 [미확인], 위험 방향이 분명하다 [추정]). 그래서 이미 설정된 목적지에는 호출하지 않는다.
2. **흐름**: (a) `getDestinationInfo`로 내비 목적지를 읽는다. (b) `getRouteStateInfo().routeOption`이 `FREE`가 아니면 "내비 경로 설정에서 '유료도로 우회'를 켜고 적용하세요"를 화면에 보인다. (c) 판정은 **`getRouteStateInfo().routeOption == FREE`(주, 재조회 가능)** 이다. 푸시(`onRouteStateInfo`)나 화면 복귀 때 다시 조회해 확인한다. (d) `onRouteStarted`를 받은 경우에만 `tollFare == 0`을 보조로 본다(못 받았다고 실패로 보지 않는다). `tollFare`를 필수 값으로 가정하면 안 된다.
3. 두 토글을 함께 켠 경우는 [미확인]이므로 `routeOption != FREE`일 때 "유료도로 우회 미적용"으로 단정하지 말고 "확인 불가/다른 옵션" 상태를 둔다.
4. 시연(Fake)도 같은 흐름(미적용 → 사용자 안내 → 토글 후 FREE 확인)을 모사한다. "요청하면 적용된다"는 Fake는 실제 SDK와 어긋난다.
5. 실차·다른 내비 버전에서 SDK 옵션이 적용될 가능성은 [미확인]이다. 문서에는 `RouteInfo.routeOption`("무료 도로 우선")이 있지만 이 환경에서는 무시된다. 어댑터는 요청이 아니라 **실제 적용된 `routeOption`을 읽어 돌려주는** 구조로 만든다(navi-route.md 포트 설계 시사점).
6. 내비에 목적지가 아직 없을 때 우리 앱이 처음 설정하는 경우(`requestRoute` 사용)도 옵션은 무시되므로 같은 "토글 안내 + 확인"을 거쳐야 한다. 이 경우는 MVP 범위 밖이다.
- adb로 내비 UI를 눌러 적용하는 것은 에뮬레이터 시연 전용이다. 앱 기능 근거가 아니다.

### 10.5 항목 3: 사람이 내비 화면에서 목적지를 바꿀 때

**(a) 안내 중 목적지 변경, 09:13 구간 — [실행 확인 2026-11-10 — 터미널·캡처]. 원본 로그 없음(터미널 출력을 옮겨 적음).** 이 변경(강남 → 서울역)의 결과 화면만 `screens/s7.png`(서울역 안내 "1시간 6분·23km" = 3960초와 일치)로 확인된다. `screens/s8.png`는 그 뒤 다음 변경 때 뜬 "목적지로/경유지로" 3초 자동 선택 팝업(기본 "목적지로")이다. 프로브 리스너는 켜 둔 상태였다(`wait150`).
```
09:13:15.465 CB onRouteCancelled
09:13:15.527 CB onNavigationStatus FREE_DRIVE
09:13:15.538 CB onDestinationInfo DestinationInfo(latitude=0.0, longitude=0.0, ... 0 값)
09:13:15.578 CB onRouteStateInfo (naviMode=SAFE_GUIDING ...)
09:13:15.588/.589 CB onDrivingInfo (duration=0, distance=0) x2
   -- 경로 미리보기 표시 (5초 카운트다운 후 자동 시작) --
09:13:22.770 CB onRouteStarted RouteStartInfo(duration=3960, distance=23000, tollFare=700, name=서울역, ...)
09:13:22.771 CB onRouteEnded
09:13:22.782 CB onNavigationStatus ROUTE_DRIVE
09:13:22.852 CB onRouteStateInfo (naviMode=ROUTE_GUIDING ...)
09:13:23.721 CB onDrivingInfo (duration=3960 ...)
```
**(b) 로그가 있는 근거 — r4·r5 (같은 목적지 미리보기에서 경로 설정 "적용" 재탐색)** [실행 확인 2026-11-10 — 로그]:
- r4(`r4-probe.txt`, 강남 미리보기, 토글이 안 켜진 채 적용): 09:15:22.293 `onRouteCancelled` → .351 `FREE_DRIVE` → .382 `onDestinationInfo`(0 값) → .419 `onRouteStateInfo`(SAFE_GUIDING) → 09:15:41.230 `onRouteStarted`(13000m/3360초) → .232 `onRouteEnded` → .234 `ROUTE_DRIVE` → .283 `onRouteStateInfo` → 09:15:42.059 `onDrivingInfo`.
- r5(`r5-probe.txt`, 서울역 미리보기, "유료도로 우회" 켜고 적용): 09:17:42.667 `onRouteCancelled` → .720 `FREE_DRIVE` → .726 `onDestinationInfo`(0 값) → .807 `onRouteStateInfo` → 09:17:58.638 `onRouteStarted`(4440초) → `onRouteEnded` → `ROUTE_DRIVE` → 09:17:58.707 `onRouteStateInfo`(`routeOption=FREE`) → 09:17:59.480 `onDrivingInfo`.
- 두 실행 모두 `onDestinationChanged`·`onRoutingCompleted`가 오지 않았다(9절 SDK 설정·취소 때와 같다). r4·r5에는 (a)의 `onDrivingInfo ×2`가 없다.
- 이 두 실행은 목적지는 미리보기 단계에서 이미 정해져 있고(그 선택 조작은 사이 조작이라 로그가 없음) **재탐색("적용")만 로그에 있다.** 그러므로 "사람이 안내 중 목적지를 바꿀 때"의 정확한 콜백 순서는 (a)의 터미널 출력뿐이고, **"같은 목적지에서 옵션만 바꾼 재탐색"은 r5가 로그로 보여 준다**(r4는 옵션 변경 없는 "적용"). 즉 목적지 변경과 옵션 변경은 모두 `onRouteCancelled`로 시작하는 같은 형태의 순서를 낳는다.
- 결론(한정): **목적지 변경 감지는 `onDestinationChanged`가 아니라 `onRouteCancelled`(또는 `FREE_DRIVE`) 후 `onRouteStarted`로 해야 하고, 이 순서만으로는 목적지가 바뀐 것인지 옵션만 바뀐 것인지 알 수 없다**(`onDestinationInfo`의 좌표를 비교해야 함). `onRouteCancelled`는 "안내 종료"가 아니라 "경로 교체 중"일 수 있다. 취소~시작 사이는 7~19초이고 그 사이 `getDestinationInfo`는 0 값일 것으로 보이나 직접 조회하지 않았다 [미확인].
- 안내 없이(FREE_DRIVE) 시작해 사람이 처음 목적지를 고르는 경우, 옵션만 바꾼 재탐색의 다른 경우는 시험하지 않았다 [미확인].
- 7.3절 방침(마지막 상태 캐시, 0 값 푸시를 "목적지 없음"으로 즉시 해석하지 않기)이 유지된다.

### 10.6 항목 4: `TripRequest` 빈 문자열과 requestTrip ETA 기준

`evidence/…/r6-probe.txt`:
```
09:18:54.763 STEP requestTrip(empty strings) start     # TripRequest(37.5547,126.9707, poiId="", poiName="", poiSubId="")
09:18:55.322 CB onTripInfo distance=26494 eta=4585 soc=95 energy=3541.65 ... trafficCount=25 routePointCount=1   # 0.56초
```
- [로그 관찰 — API 아님] `r6-key.txt`: `OnTripRequest(latitude=37.5547, longitude=126.9707, poiId=, poiName=, poiSubId=)`로 내비 앱까지 들어가 처리됐다. **빈 문자열 허용.** 문자열 값이 응답에 영향을 주는지는 보지 않았다.
- ETA 옵션 기준 비교(출발지 37.4127212,127.094681 → 서울역 37.5547,126.9707, 모두 `requestTrip(서울역)`):

| 실행 | 안내 중이던 목적지 | 내비 유료도로 우회 | requestTrip 결과 | 요청 JSON `avoid_fare` [로그 관찰 — API 아님] |
|---|---|---|---|---|
| r1 | 서울역(RECOMMENDED) | off | distance=23799, eta=4036 | false |
| r2 | 서울역 | off | 23799, 4020 | false |
| r3 | **강남** | off | 23799, 4008 | false |
| r4 | **강남** | off | 23799, 4028 | false |
| r5 | 서울역 | **on** | 26494, 4588 | true |
| r6 | 서울역 | **on** | 26494, 4585 | true |

  → **requestTrip의 거리·ETA는 내비 앱이 그 시점에 쓰는 유료도로 우회 설정을 따른다.** `TripRequest`에는 옵션 필드가 없어 호출 쪽에서 고를 수 없다. r3·r4는 안내 목적지가 강남인 채 서울역을 요청했는데 값이 r1·r2와 같으므로 **현재 안내 중인 목적지와는 무관하고 옵션(과 요청 좌표)만 따른다.** 안내 경로가 없을 때의 요청은 로그를 대조하지 않았다 [미확인].
- 같은 때 `DestinationInfo`와의 차이: r1 안내 remainDistance=23000/remainTime=3900 vs trip 23799/4036, r5 26000/4440 vs 26494/4588. 거리는 `DestinationInfo`가 분·km 단위로 뭉개진 값이라 설명되지만 ETA는 +136~148초(약 3.5%) 차이가 반올림만으로 설명되지 않는다. 이유(탐색 엔진·시각 차이)는 [불확실]. 출발 마감 계산은 한 값으로 일관되게 쓰고 두 값을 섞어 비교하지 않는다.

### 10.7 항목 5: `adb emu geo nmea`

- 입력(체크섬은 python XOR로 계산, `evidence/…/probe/nmea.txt`·`nmea2.txt`): ① 서울시청 `$GPGGA,010203.00,3733.9900,N,12658.6800,E,…*64`, `$GPRMC,…*59` ② 강남 `…3729.8860,N,12701.6920,E…`. `adb emu geo nmea "<문장>"`은 모두 `OK`. 주입 시각·응답은 터미널 출력뿐이다(로그 없음).
- **판별 근거는 ② 이후의 r8 한 건**이다. ①의 좌표는 직전 `geo fix` 잔여값(37.5665, 126.978)과 사실상 같아 "문장이 안 바뀐 것"만으로는 주입 무시를 판단할 수 없고, 주입 직후 로그를 지운 뒤 확인하므로 "내 문장이 로그에 없다"도 근거가 못 된다.
  - r8 `r8-key.txt`(09:20:04 부근): 내비 앱이 받은 문장은 여전히 `$GPGGA,0020xx,3733.9899,N,12658.6799,E,1,6,,0.0,M,0.,M,,,*47`(서울시청 값, 붙은 체크섬 `*47` 고정)이고 `NMEA checksum mismatch: expected=47, actual=69`·`6A`·`7D`·`7E`(r8 실측 4건. 77은 r7 값). ② 강남 값(3729.88…)은 나타나지 않았다.
  - `r8-probe.txt`: `getCurrentLocationInfo`는 `37.4127212, 127.094681, 수정구 시흥동` 불변.
- 보조 관찰: 내비 앱 `GpsLocationSource`는 에뮬레이터가 보내는 NMEA를 **모두** 체크섬 불일치로 버린다(11/09부터 동일). 따라서 내비 위치는 처음부터 GNSS와 무관하게 기본값에 고정되고, "`geo nmea`가 효과 없다"는 "이 에뮬레이터의 GNSS → 내비 경로가 막혀 있다"의 한 사례다. 에뮬레이터가 입력을 그대로 넘기지 않고 자체 문장을 만든다고 해석할 수 있으나 HAL 내부는 못 봤다 [불확실].
- 결론: **`geo nmea`로는 이 내비 위치를 바꿀 수 없다.** 내비 시뮬레이션 주행은 시험하지 않았다 [미확인].

### 10.8 항목 6: 안내 중 다른 목적지로 `requestRoute` (r3)

`evidence/…/r3-probe.txt`:
```
(서울역 안내 중, remainTime=3900)
09:11:34.367 CB onDestinationInfo (37.5547,126.9707 ...)                       # 시작 전 확인
09:11:35.393 [로그 관찰 — API 아님] OnRouteRequest(routeInfo=RouteInfo(... latitude=37.4981, longitude=127.0282 ...))   # r3-key.txt
09:11:35.636 CB onRouteCancelled
09:11:35.746 CB onNavigationStatus FREE_DRIVE
09:11:35.760 CB onDrivingInfo (duration=0, distance=0 ...)                     # onDestinationInfo보다 먼저
09:11:35.764 CB onDestinationInfo (0 값)
09:11:35.819 CB onRouteStateInfo (SAFE_GUIDING)
09:11:42.641 CB onRouteStarted RouteStartInfo(duration=3360, distance=13000, tollFare=700, ...)   # 새 목적지, 7.2초
09:11:42.644 CB onRouteEnded
09:11:57.742 CB onDestinationInfo (37.4981, 127.0282, address=강남구 역삼동, remainDistance=13000, remainTime=3360)
```
- 기존 안내가 취소되고 새 목적지로 교체됐다. SDK 호출 쪽에는 사람 입력이 필요 없었고 약 7초 후 안내가 시작됐다(미리보기 카운트다운이 있었는지는 캡처가 없어 모른다). 순서는 (a)의 사람 조작과 거의 같다(`onDrivingInfo`가 `onDestinationInfo` 앞에 오는 점만 다름) → **콜백만으로 두 경우를 구분할 수 없다.**
- 이미 설정된 목적지를 우리 앱이 덮어쓸 수 있으므로 사용자 확인 없이 호출하면 안 되고, MVP 방침은 호출하지 않는 것이다(10.4).

### 10.9 정정 적용 내역 (다른 api-notes 파일에 직접 반영함: 파일:줄 → 바꾼 내용)

1차에서는 위치만 적었으나 리뷰(MAJOR-1) 후 아래를 직접 고쳤다. 줄 번호는 이 정정 이후 기준이다.

| 파일:줄 | 바꾼 내용 |
|---|---|
| eta-location.md:84 | "옵션 반영 시간은 `requestRoute(routeOption)` 후 `onRouteStarted.duration`으로만 얻는다"는 서술을 "이 환경에서는 성립하지 않음(FREE·SHORT_DISTANCE 무시)"으로 정정. `requestTrip`이 내비의 유료도로 우회 설정을 따름을 추가 |
| README.md:13 | 4차 실행 확인(10절) 요약과 증거 폴더 링크 추가 |
| README.md:29 | 문서 표에 10번(10절) 행 추가 |
| README.md:51 | NaviRepository 대응 줄: `RouteInfo.routeOption` 무시됨, 무료도로는 내비 토글 + `routeOption==FREE` 사후 확인으로 정정 |
| README.md:63 | "무료도로 선호 → FREE" 축소 규칙에 "SDK로 요청해도 적용되지 않음, 사용자 토글 안내 + 결과 확인이 주 흐름, 유료도로 우회 ≠ 고속도로 회피" 추가 |
| README.md:85 | "FREE 적용 후 tollFare·`changeRouteOption` [미확인]"을 "해소: 무시됨(700원, RECOMMENDED), `changeRouteOption` 효과 없음"으로 정정 |
| navi-route.md:16 | 확인 수준 줄: `requestRoute(FREE)`·`changeRouteOption` 미실행 → 실행했고 적용되지 않음 |
| navi-route.md:85 | `TripRequest` 빈 문자열 [미확인] → 허용 |
| navi-route.md:102 | FREE 요청·`changeRouteOption` [미확인] → 효과 없음(`onRouteOptionChanged` 안 옴, 요청에 옵션 필드 없음), 나머지 값 [미확인] |
| navi-route.md:104 | "회피 옵션은 문서에 없다"에 "SDK 기준이며, 내비 UI에는 유료도로 우회(`AOP_02`) 토글이 있고 SDK로 켤 방법은 확인 안 됨, 켜면 `routeOption=FREE`로 보고됨" 추가 |
| navi-route.md:165, 169 | 예제 코드 주석에 "이 환경에서는 무시/무효과" 경고 추가 |
| navi-route.md:179 | 안내 중 `requestRoute`는 덮어쓴다([미확인] 삭제), 이미 설정된 목적지에는 호출하지 않는다(옵션 무시 + 사용자 FREE 풀림 위험) |
| navi-route.md:186 | 포트 설계 시사점: FREE 요청 [미확인] → 무시됨, 사용자 토글 후 `getRouteStateInfo().routeOption`으로 판정, MVP 흐름 명시 |
| navi-runtime-check.md:172 | `geo nmea`는 시험했고 효과 없음, 시뮬레이션만 미확인 |
| navi-runtime-check.md:195 | 결론 표 `requestRoute(FREE)`·`changeRouteOption` 행: 시험함, 둘 다 무효 |
| navi-runtime-check.md:196 | `TripRequest` 빈 문자열 행: 허용 |
| navi-runtime-check.md:198 | `geo nmea` 행: 효과 없음, 시뮬레이션 미확인 |
| navi-runtime-check.md:211 | 6절 3번 "TripRequest 빈 문자열 [미확인]" → 허용(10.6) |
| navi-runtime-check.md:249 | 7.2절 "그 옵션은 … 우리가 요청한 FREE일 수도 있다" → 요청으로는 FREE가 되지 않는다 |
| navi-runtime-check.md:261 | 7.2절 `requestRoute` 덮어쓰기 [미확인] → 확인, MVP는 호출하지 않음 |
| navi-runtime-check.md:270 | 7.3절 사람이 바꿀 때 콜백 [미확인] → 10.5 순서(보조 신호 후보 3줄 270~273은 유지) |
| navi-runtime-check.md:194 | 5절 결론 표 `onDestinationChanged` 행: 사람이 바꿀 때 [미확인] → 오지 않음, 10.5 순서(2026-11-10, 터미널·캡처) |
| navi-runtime-check.md:289 | 9절 미확인 목록 → 10절에서 모두 시험함 |
| playground-recheck.md:79 | "FREE tollFare 미확인" → 확인(무시, 700원) |
| emulator-injection.md:73, 81 | `geo nmea` [미확인] → 시험했고 효과 없음, 시뮬레이션만 미확인 |

이 절(10절) 안의 이전 "정정 필요 목록"은 위 표로 대체했다. 11/09 6절·1~9절 본문은 위 줄만 최소로 고쳤다(그 외 서술은 11/09 기준 그대로).

### 10.10 부수 효과와 남은 [미확인]
- 상태: 모든 경로를 취소해 내비는 목적지 없음 상태(`screens/s22.png`). 프로브 `ai.probe.pleos`는 설치된 채. 내비 "최근" 목록에 이번 세션 항목이 추가됐다. "유료도로 우회" 토글은 꺼진 상태(`s21.png`). 에뮬레이터는 켜 둠.
- [미확인]:
  - 나머지 `RouteOption` 값(SHORT_TIME·HIGHWAY·COMFORTABLE·SCHOOL_ZONE·RECOMMENDED 외 모두)의 효과
  - "어린이 보호구역 우회"를 켜거나 두 토글을 함께 켰을 때의 `routeOption` 값
  - FREE 안내 중 같은 목적지로 `requestRoute`했을 때 옵션이 풀리는지(10.4 위험 추정)
  - 안내 경로가 없을 때 `requestTrip`의 옵션 기준
  - 내비 시뮬레이션 주행, FREE_DRIVE에서 사람이 처음 목적지를 고를 때 콜백, 미리보기 중 `getDestinationInfo` 값
  - 토글이 경로 한 건에만 속하는지(같은 목적지 재진입), 주행 중(DrivingState≠0), 실차, 다른 내비 버전


## 11. 2026-11-10 실행 확인 (경로 변경 중 getDestinationInfo 0값, 경로 변경 소요 시간)

- 실행: 2026-11-10 10:46~10:56 (에뮬레이터 시계). 담당 pleos-sdk-researcher(task #10). 환경은 10절과 같다(에뮬레이터 2.1.0, 내비 3.3.2-rc5-hotfix.3, 서비스 3.0.4, SDK 프로브 3.0.5). 주행 상태는 p3~p6 동안 0(P), p7만 1(Drive, 11.4절).
- 증거: `evidence/2026-11-10/task10/p3~p7-routechange.txt`(프로브 로그 발췌), `probe-added-steps.txt`(이번에 추가한 폴링 단계). 폴링 단계 `bg<초>`는 `getDestinationInfo()`를 약 0.7초마다 호출하는 스레드를 띄워 두고 다른 단계를 계속 실행한다(안내 교체 중 값을 보려는 용도).
- 조작: SDK `requestRoute`로 안내 중에 다른 목적지를 요청했다(사람 입력·미리보기 없음). 10절에서 이 방식은 기존 안내를 취소하고 덮어쓰는 것으로 확인했다.

### 11.1 질문 2: 경로 변경 중 `getDestinationInfo`는 0값인가

**그렇다. [실행 확인 2026-11-10 — 로그]** (p3·p4·p5 모두 같은 양상)

| 실행 | 변경 | `onRouteCancelled` | 폴링이 0값을 받은 구간 | 첫 정상 값 | 비고 |
|---|---|---|---|---|---|
| p3 | 서울역 → 강남(13km) | 10:48:28.855 | 28.918 ~ 37.171 직전 | 37.171(`강남`, remainTime=2640) | `onRouteStarted` 36.353, 취소부터 7.5초 |
| p4 | 강남 → 부산(375km) | 10:49:26.045 | 26.069 ~ 37.862 직전 | 37.862(`부산`, 17640) | `onRouteStarted` 37.206, 11.2초 |
| p5 | 부산 → 제주(513km) | 10:50:40.036 | 40.199 ~ 56.790 직전 | 56.790(`제주`, 32940) | `onRouteStarted` 56.343, 16.3초 |

- 0값의 모양: `DestinationInfo(latitude=0.0, longitude=0.0, name=, address=, remainDistance=0, remainTime=0, tollFare=0, poiId=, poiProvider=)`. 콜백은 **정상으로 오고(예외·`onError` 없음)** 값만 0이다. "응답 없음(타임아웃)"이 아니라 "응답은 오는데 목적지 없음"이다.
- 취소 직전까지는 이전 목적지 값이 오다가 `onRouteCancelled` 직후부터 0값, `onRouteStarted`(+`onRouteEnded`, `ROUTE_DRIVE`) 후 약 0.5초 뒤부터 새 값이다. 그 사이 `onNavigationStatus`는 `FREE_DRIVE` → `ROUTE_DRIVE`.
- 안내가 없는(FREE_DRIVE) 평상시에도 같은 0값이다(p1 시작 시점 10:47:01, p8 취소 후). 즉 **0값만으로 "경로 변경 중"과 "안내 없음"을 구분할 수 없다.** 10절 관측상 변경 중 `naviMode`도 안내 없음과 같은 `SAFE_GUIDING`이라 구분되지 않는다. 구분 신호는 `onRouteCancelled` 이후 `onRouteStarted`(또는 `onError`)가 올 때까지라는 콜백 시퀀스뿐이며, 앱이 계속 구독하고 있을 때만 쓸 수 있다.
- 이 구간의 `onDrivingInfo`도 `DrivingState(name=<이전 목적지>, duration=0, distance=0)`으로 먼저 오고, 새 안내 직후 `name=` 빈 값이 한 번(p4 10:49:37.576) 끼었다가 정상 값이 온다. 중간 값은 신뢰할 수 없다.

### 11.2 질문 3: 경로 변경이 30초를 넘을 수 있는가

**이 환경에서 정상 변경은 30초를 넘지 않았다. 다만 실패하면 0값이 무기한 유지된다.**

- 정상 변경: 취소부터 안내 시작까지 7.5초(13km) / 11.2초(375km) / 16.3초(513km). 10절의 사람 조작 관측(7~19초)을 합쳐 최대 약 19초. 거리가 길수록 늘었으나 국내 최장 수준(제주 513km)도 16초였다. **[실행 확인 2026-11-10 — 로그]** 단 에뮬레이터·서버 상태에 따른 편차, 실차 값은 모른다.
- 실패 변경(p6, 도로 없는 해상 좌표 34.0,128.0): 10:52:05.574 `onRouteCancelled`(기존 안내 취소) → 10:52:06.526 `onError CAUSE_SEARCH_ROUTE`(약 1초 뒤) → `FREE_DRIVE`로 돌아가고 `getDestinationInfo`는 **계속 0값**(관측 종료 10:53:01까지 약 55초, 새 경로 없음). 요청이 실패하면 이전 안내도 복구되지 않는다. `onRouteStarted`가 오지 않으므로 "곧 새 값이 온다"고 기다리면 끝나지 않는다. **[실행 확인 2026-11-10 — 로그]**
- 사람이 내비 화면에서 바꿀 때는 미리보기 카운트다운·팝업(10절)이 끼어 더 길어질 수 있고, 사용자가 안 누르면 안내가 시작되지 않는다. 이 경우의 상한은 시험하지 않았다 **[미확인 — UI 조작 미시도, 논리상 추정]**.
- 시도 횟수: 정상 3회(p3~p5) + 실패 1회(p6) + 주행 중 1회(11.4). UI 조작은 쓰지 않았다.

### 11.3 부수 관측
- p4·p5에서 프로브의 `requestRoute` 단계가 TIMEOUT(10초)으로 표시된 것은 프로브 타임아웃이 10초라서이고, 실제 `onRouteStarted`는 11.2·16.3초 후 정상 도착했다. SDK에 요청 완료 반환값이 없고 `onRouteStarted`/`onError`가 유일한 완료 신호다.

### 11.4 주행 중(DrivingState=1)에도 NaviHelper가 동작하는가
- p7: Virtual Console로 기어를 Drive로 바꾼 상태(`Current Driving State: 1`, UXR 0x50, 속도 0)에서 안내 없음 상태로 `requestRoute(서울역)` → 정상으로 `onRouteStarted`(10:54:46.165), `getDestinationInfo`도 `서울역`. **[실행 확인 2026-11-10 — 로그]** 기어 전환 방법은 vehicle-state.md 11절.
- 시험 후 기어를 Park으로 되돌렸고(`Current Driving State: 0`), 내비 안내를 `cancelRoute`로 취소했다(p8, 0값 확인). 에뮬레이터는 실행 중이고 내비는 안내 없음 상태다.

### 11.5 포트 설계 시사점 (관측 근거만)
- 경로 변경 중 목적지·ETA 조회는 "0값"일 수 있고 최대 약 19초(관측), 실패 시 무기한이다. 0값은 오류가 아니라 정상 응답이므로 어댑터가 `name.isEmpty() && remainTime == 0`을 "목적지 없음/전환 중(Unavailable)"으로 매핑해야 하며, 실제 도착(ETA 0분)과 혼동하면 안 된다.
- 재확인 정책 5초 × 6회(30초)는 정상 변경 최대 관측(19초)을 덮는다. 6회째에도 0값이면 `onError(CAUSE_SEARCH_ROUTE)`나 안내 종료일 가능성이 크므로 "경로 없음"으로 확정하는 흐름은 근거가 있다. 다만 사람이 미리보기에서 지체하는 경우는 [미확인]이라 30초가 보장은 아니다.
- 콜백을 구독할 수 있으면 `onRouteCancelled` ~ `onRouteStarted`(또는 `onError`)를 변경 구간 경계로 쓰는 편이 폴링보다 정확하다. `onDestinationChanged`는 오지 않는다(10.5).

- **감독관 결정(2026-11-10, 작업 #11)**: 위 권고의 "전환 중/목적지 없음 → Unavailable" 대신 포트에서는 `NotSet`으로 넘긴다(목적지 없음은 정상 상태라 Unavailable과 섞지 않는다는 task-01c 원칙). 경로 교체와의 구분은 화면 재확인(5초 × 6회)이 맡는다. 판정은 좌표 (0,0) + 빈 name 기준이며 도착 직전 값은 미관측 [미확인].
