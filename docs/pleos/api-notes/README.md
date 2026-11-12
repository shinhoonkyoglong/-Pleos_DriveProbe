# Pleos API 조사 노트 — 2번 '출발 전'

조사일 2026-11-09 · 담당 pleos-sdk-researcher · plan.md 작업 #1 · **재검토 2026-11-09 (Claude in Chrome, Playground 로그인 상태)** → [playground-recheck.md](playground-recheck.md)

## 조사 방법과 한계 (먼저 읽기)
- **1차 조사(정적)**: Claude in Chrome이 연결되지 않아 공개 문서 https://document.pleos.ai 를 정적으로 읽었다. 공모전 사이트 https://appdevchallenge.auto-hmg.io/guide/development 의 링크에서 출발했다. API Reference(Dokka) 본문은 사이트 JS 번들에서 원문을 추출했다. 리뷰어가 Maven aar(javap)로 표본 8건을 교차 확인했다.
- **2차 재검토(브라우저, 2026-11-09)**: 사용자의 실제 Chrome에서 Playground(https://developers.pleos.ai/playground — pleos.ai/playground는 여기로 리다이렉트)에 로그인한 상태로 확인했다. **읽기 전용**이었다.
  - My Project 「계운」의 SDK 상태를 확인했다. Vehicle State·NaviHelper는 사용 가능, FusedLocation·TTS·STT·Vehicle Control은 준비 중이다. 클라이언트 ID·시크릿은 발급돼 있다(값은 기록 안 함).
  - Playground 상단 Docs 메뉴는 **같은 document.pleos.ai**로 연결된다. 더 새로운 별도 문서는 없다. 릴리즈 노트도 2026-10-19판 그대로다.
  - 핵심 Reference 페이지를 브라우저 렌더링으로 다시 읽었고, 문서 사이트 검색으로 미확인 키워드를 찾아봤다. 정정 1건(`requestTrip` 설명 문구), 신규 사항(심사 가이드, Devbox, Vehicle Data API 지역 제한)이 있었다.
  - SDK 신청, 프로젝트 수정, 폼 제출 같은 상태 변경은 하지 않았다.
- **3차: 에뮬레이터 실행 확인(2026-11-09, [navi-runtime-check.md](navi-runtime-check.md))**: NaviHelper 3.0.5로 프로브 앱을 만들어 Pleos Connect 에뮬레이터 2.1.0(내비 앱 3.3.2)에서 `getDestinationInfo`·`requestRoute`·`requestTrip`·`getCurrentLocationInfo` 등을 호출했다. 이 확인 이후 아래 노트들의 해당 줄을 2026-11-10 리뷰 반영표대로 고쳤다. 실행하지 않은 것(FREE 요청, `changeRouteOption`, 주행 중, 실차 등)은 계속 [미확인]이다.
- **4차: 에뮬레이터 실행 확인 2(2026-11-10, [navi-runtime-check.md](navi-runtime-check.md) 10절)**: 미확인 6항목 시험. `requestRoute(FREE)`·`changeRouteOption`은 이 환경에서 무시됨, 무료도로는 내비 UI "유료도로 우회" 토글로만 적용(`RouteStateInfo.routeOption==FREE`로 확인), 사람이 목적지를 바꾸면 `onDestinationChanged` 없이 `onRouteCancelled → … → onRouteStarted`, `TripRequest` 빈 문자열 허용, `geo nmea` 효과 없음, 안내 중 `requestRoute`는 덮어씀. 원본 증거는 [evidence/2026-11-10/](evidence/2026-11-10/README.md).
- 노트의 확인 수준은 [문서 확인] / [문서 재확인(날짜)] / [Playground 확인(날짜)] / [실행 확인 날짜]로 구분한다. [Playground 확인]은 콘솔 화면 상태를 본 것이지 API 실행 결과가 아니다. 나머지 SDK(FusedLocation·TTS·STT·Vehicle)는 아직 실행 확인이 없다.
- 기준 SDK 버전(릴리즈 노트 2026-10-19, 11/09 재확인): Vehicle 2.3.2 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 · FusedLocation 1.1.3 · Emulator 2.1.0

## 목차
| # | 조사 항목 | 파일 | 핵심 결론 |
|---|---|---|---|
| 1 | 경로 요청·경로 옵션·목적지 | [navi-route.md](navi-route.md) | NaviHelper **사용 가능**(Playground). `RouteOption`은 **단일 enum**: RECOMMENDED / SHORT_DISTANCE / SHORT_TIME / **FREE(무료도로 우선)** / HIGHWAY(고속도로 우선) / COMFORTABLE / SCHOOL_ZONE. **회피 옵션(고속도로 회피 등)은 없음.** 요청은 반환값 없음, 결과는 콜백 |
| 2 | ETA·남은 거리·소요시간 | [eta-location.md](eta-location.md) §A | 사전 조회 후보 `requestTrip(TripRequest)` → `onTripInfo(distance m, eta s)`. 단 **경로 옵션 지정 불가**. 안내를 시작하지 않는 사전 조회이며 `eta`는 남은 초다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. **`onTripInfo` 도착은 문서상 Navi 앱 구현에 달려 있어(11/09 재확인)** 타임아웃 필수(실측 0.4~0.55초). 주차(P) 중에도 `getDestinationInfo`가 내비 목적지(좌표·이름·poiId·remainTime·remainDistance)를 주고, 목적지가 없으면 0 값 객체가 온다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. `remainDistance/remainTime`은 분·km 단위 값 |
| 3 | 현재 위치 | [eta-location.md](eta-location.md) §B·§C | **FusedLocation은 Playground에서 "준비 중"(11/09 확인)**. FusedLocation `getLastKnownLocation` / `registerFusedLocationCallback` → `LocationData?`. 갱신 주기 설정 없음. **반경 지오펜스 `addProximityAlert(lat, lng, radiusMeters, ENTER/EXIT)` 있음**. 정정(11/09 리뷰 반영): 이전 "FusedLocation 주 소스, NaviHelper 보조" → 위치 실제 소스 후보 1순위 NaviHelper `getCurrentLocationInfo`(「계운」 사용 가능), 2순위 FusedLocation(현재 '준비 중'). NaviHelper `getCurrentLocationInfo`는 에뮬레이터에서 응답한다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 단 에뮬레이터 위치는 geo fix를 따르지 않고 내비 기본 위치(성남 수정구 시흥동)에 고정된다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. FusedLocation은 에뮬레이터 동작 미확인. 이 우선순위는 설계 판단이며 문서 근거 아님. MVP 시연은 `departure.adapter=fake` 기준. |
| 4 | TTS / STT | [tts-stt.md](tts-stt.md) | **TTS·STT는 Playground에서 "준비 중"(11/09 확인)**, Client ID/Secret은 발급됨. `TextToSpeech(context, Mode)` → `initialize` → `speak(text, isFinal)` → `onDone`. `ON_DEVICE`는 앱 등록 불필요. 주행 중 TTS 금지 문구는 없음 |
| 5 | 차량 상태·주행 중 화면 제한 | [vehicle-state.md](vehicle-state.md) | Vehicle SDK 2.3.2에 **속도·기어 API 없음**(VehicleSpeedListener 삭제). 주행 중 UX 제한은 **AAOS `CarUxRestrictionsManager`** 기준. 속도는 AAOS `PERF_VEHICLE_SPEED`. DO 미선언 앱은 주행 중 사용 불가 |
| 6 | 에뮬레이터 주입 | [emulator-injection.md](emulator-injection.md) | Virtual Console(`adb forward tcp:8088`)로 기어·브레이크 등 차량 상태 주입 가능(카드 47개, 위치·속도 카드 없음 — 11/09 재확인). 주행 규제는 adb로 ON/OFF. `adb emu geo fix`는 Android 위치(FusedLocation 서비스 포함)에는 들어가지만 **내비(NaviHelper) 위치에는 들어가지 않는다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 경로 시뮬레이션 사용법은 [미확인] → MVP 시연은 Fake 전제 |
| 7 | SDK 설치·요구사항 (+알림·스케줄) | [sdk-setup.md](sdk-setup.md) | 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`, 좌표 `ai.pleos.playground:<SDK>:<ver>`, minSdk 28, Gradle 8.0+, 에뮬레이터 API 34. 예약 알림(AlarmManager/WorkManager)은 문서에 없음 [미확인, 11/09 문서 검색 0건]. 심사 제출 시 SDK 추가 권한에 프로젝트 ID 입력 |
| 8 | Playground 재검토 결과 | [playground-recheck.md](playground-recheck.md) | 프로젝트 SDK 상태표, 정정·신규·미확인 목록, 포트 영향(시그니처 변경 없음), 사용자 확인 항목 |
| 9 | NaviHelper 에뮬레이터 실행 확인 | [navi-runtime-check.md](navi-runtime-check.md) | 주차 중 `getDestinationInfo`로 내비 목적지 수신(사람이 설정한 것 포함), 목적지 없음 = 0 값 객체, `TripInfo.eta` = 남은 초, `requestTrip`은 안내 미시작, `requestRoute`는 확인 없이 바로 안내 시작(8~11초), 내비 위치는 geo fix 미반영. FREE 요청·`changeRouteOption`은 [미확인] |
| 10 | NaviHelper 에뮬레이터 실행 확인 2 | [navi-runtime-check.md](navi-runtime-check.md) 10절 | FREE 요청 무시, 무료도로는 내비 토글 + `routeOption==FREE` 사후 확인, 목적지 변경 콜백 순서, `TripRequest` 빈 문자열 허용 (증거: [evidence/2026-11-10/](evidence/2026-11-10/README.md)) |
| 11 | 앱 UI 구조 조사 (Pleos 디자인·심사 + Android for Cars) | [ui-structure-research.md](ui-structure-research.md) | 상태 기반 전환 지지, 기본 portrait 단일 window·landscape 확장, 대비 4.5:1·문단 3줄·응답 0.25초, 작업 5단계 이하(Android for Cars). 26sp·72dp 같은 sp/dp 수치는 문서에 없음(설계 판단). DO 선언 가능 여부 [미확인] |

---

## 포트 설계 시사점

### 0. 모든 SDK에 공통인 모양
- 모든 Pleos SDK는 `객체(context) → initialize() → 리스너 등록 → 요청(반환값 없음) → 콜백으로 결과 → release()` 구조다. **suspend/Flow API는 없다.**
- 콜백에 **요청 ID가 없다**(NaviHelper, TTS). 응답이 어느 요청에 대한 것인지 구분할 수 없다.
- 차량 모델·환경에 따라 **아예 없을 수 있다.** NaviHelper는 내비 탑재 차량만, FusedLocation은 `isSupported()`, Vehicle은 `checkXxxCapabilities`로 확인한다.
- 그래서 우리 포트는:
  1. `suspend` 함수(단발 결과)와 `Flow`(연속 값)로 정의한다. 콜백을 코루틴으로 바꾸는 일은 1번 Repository 또는 우리 어댑터가 맡는다.
  2. 결과 타입에 **"사용 불가/모름" 상태**를 포함한다(nullable 또는 sealed result). core 유스케이스는 이 경우의 대체 흐름을 가진다.
  3. 어댑터 안에서 **한 번에 한 요청만** 처리한다(Mutex). 콜백 대기에는 **타임아웃**을 둔다. 수치는 `DepartureConfig`에서 가져온다.
  4. 포트 시그니처에는 SDK 타입(`RouteInfo`, `RouteOption`, `LocationData` 등)을 노출하지 않는다. 우리 도메인 모델만 쓴다(CLAUDE.md 이식성 원칙).

### 1. Repository 매핑과 메서드 후보
아래 이름은 1번의 예상 이름에 맞춘 **후보**다. 확정 전까지 `CommonRepositoryContract.kt`에 `TODO(1번-연동)`으로 둔다.

| 예상 Repository | 감싸는 SDK/API | 메서드 후보 (우리 포트) | 비동기 방식 | 근거 노트 |
|---|---|---|---|---|
| **NaviRepository** | NaviHelper `requestTrip` / `onTripInfo` | `suspend fun estimateTrip(destination: Place): TripEstimate?` — 경로 옵션 파라미터 없음(SDK 미지원) | 콜백 → suspend + 타임아웃 | eta-location §A |
| NaviRepository | NaviHelper `requestRoute(RouteInfo(routeOption))` / `onRouteStarted` / `onError`(`RouteInfo.routeOption`은 이 환경에서 **무시됨**(FREE·SHORT_DISTANCE 시험) [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절]. 적용 옵션은 `onRouteOptionChanged`(한 번도 안 옴)가 아니라 조회·푸시 `RouteStateInfo.routeOption`. 무료도로는 내비 UI의 "유료도로 우회" 토글로만 FREE가 됨 → MVP는 "사용자 토글 안내 + `getRouteStateInfo().routeOption == FREE` 사후 확인") | `suspend fun startGuidance(destination: Place, preference: RoutePreference): GuidanceResult` — 결과에 **실제 적용된 옵션**과 `duration`·`distance`·`tollFare` 포함 | 콜백 → suspend | navi-route |
| NaviRepository | NaviHelper `getDestinationInfo` / `onDestinationInfo`, `onDrivingInfo` | `fun remainingTrip(): Flow<RemainingTrip>` (남은 거리 m, 남은 시간 s) | 리스너 → Flow | eta-location §A |
| NaviRepository (또는 LocationRepository) | 1순위 NaviHelper `getCurrentLocationInfo`, 2순위 FusedLocation `getLastKnownLocation` / `registerFusedLocationCallback` — 정정(11/09 리뷰 반영): 이전 "FusedLocation 주, NaviHelper 보조" → 위치 실제 소스 후보 1순위 NaviHelper `getCurrentLocationInfo`(「계운」 사용 가능), 2순위 FusedLocation(현재 '준비 중'). NaviHelper `getCurrentLocationInfo`는 에뮬레이터에서 응답한다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 단 에뮬레이터 위치는 geo fix를 따르지 않고 내비 기본 위치(성남 수정구 시흥동)에 고정된다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. FusedLocation은 에뮬레이터 동작 미확인. 이 우선순위는 설계 판단이며 문서 근거 아님. MVP 시연은 `departure.adapter=fake` 기준. | `suspend fun currentLocation(): GeoPoint?`, `fun locationUpdates(): Flow<GeoPoint>` | 콜백/리스너 → suspend/Flow | eta-location §B |
| (위치 쪽, 선택) | FusedLocation `addProximityAlert` / `removeProximityAlert` | `fun watchRadius(center: GeoPoint, radiusMeters: Float): Flow<RadiusTransition>` — 부가 기능용 | 리스너 → Flow | eta-location §C |
| **TtsRepository** | TextToSpeech `speak` / `EventListener.onDone/onStop/onError` | `suspend fun speak(text: String): SpeakResult` | 콜백 → suspend (발화 완료까지 대기) | tts-stt |
| **VehicleRepository** | AAOS `CarUxRestrictionsManager` (Pleos Vehicle SDK 아님) | `fun drivingRestriction(): Flow<DrivingRestriction>` — `Set<Restriction>` 포함 | 리스너 → Flow | vehicle-state |
| SttRepository | SpeechToText | '출발 전' 파트에서는 **지금 만들지 않음** | — | tts-stt |
| (Pleos 밖) ReminderScheduler | 문서 근거 없음 (AlarmManager 등은 미확인) | `schedule(at: Instant, reminder)` — Pleos 포트와 분리 | — | sdk-setup |

위치를 NaviRepository에 둘지 별도 LocationRepository로 둘지는 1번의 결정을 따른다. 1번 담당 SDK 목록에 FusedLocation이 명시되어 있지 않으므로 질문이 필요하다.

### 2. 도메인 모델에 주는 영향
- **RoutePreference → SDK 옵션은 1:1이 아니다.** SDK는 단일 enum이고 회피 옵션이 없다. 우리 "운전자 조건"(무료도로 선호, 고속도로 회피, 야간 금지 등)은 도메인에서 `Set`으로 받는다. 어댑터는 이를 **SDK 옵션 하나로 줄이는 규칙**(예: 무료도로 선호 → `FREE`)을 갖되, **SDK로 요청해도 FREE가 적용되지 않는다**(이 환경, 10절). 그러므로 "요청"이 아니라 "사용자 토글 안내 + 적용 결과(`routeOption`) 확인"이 주 흐름이다. 내비 "유료도로 우회"는 통행료만 피하고 통행료 없는 고속도로 구간은 남으므로 "고속도로 회피"와는 다르다. 근사 규칙은 우리 설계 판단이며 문서 근거가 아니다. `DepartureConfig` 또는 어댑터 한 곳에만 둔다.
- **목적지(Place)** 에는 좌표 외에 `poiId`, `poiName`, `address`, `poiSubId`를 담을 자리(nullable)가 필요하다. `RouteInfo`에서는 이 값들이 non-null 필수다.
- **ETA는 Duration(남은 시간)** 으로 표현한다. `TripInfo.eta`는 남은 초다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 단위 해석은 어댑터가 맡고, `eta > 10^9`이면 거부하는 정도의 검사는 설계 판단으로 둘 수 있다.
- **위치는 nullable**이다. `onChanged(LocationData?)`의 `null`은 "위치 없음"이고, 유효 위치로 쓰지 말라고 문서에 명시되어 있다.
- **반경 확인의 핵심 판단은 core의 순수 계산**(현재 위치 1회 + 집 좌표 + `DepartureConfig.허용반경`)으로 한다. ProximityAlert는 등록 시점에 초기 상태를 통지하는지 불확실해서, 출발 전 판정의 유일한 근거로 쓰기 어렵다.
- **출발 마감 계산**은 "ETA 모름"(내비 미탑재, `CAUSE_SEARCH_TRIP`, 타임아웃) 상태를 다뤄야 한다. 예: 보수적인 기본 이동 시간 사용. 기본값은 `DepartureConfig`에 둔다.

### 3. UX·안전 제약 (화면 설계에 반영)
- 주행 중에는 DO(Distraction Optimization) 미선언 앱을 쓸 수 없다. 우리 화면은 **출발 전(Parking)** 사용이 기본이고, 주행 중에는 TTS 안내 위주로 설계한다.
- 주행 판단은 속도·기어로 하지 않고 `CarUxRestrictions`를 따른다(문서 원칙). 에뮬레이터에서 `adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off`로 검증할 수 있다.
- 우리 앱이 자체 경로 음성 안내를 하면 정책 위반 위험이 있다(단일 경로 정책). 경로 안내는 NaviHelper로 OEM 내비에 맡긴다.
- 백그라운드 타이머·폴링은 최소화해야 한다. 위치 Flow는 화면이 보일 때만 구독한다.

### 4. 1번 담당에게 확인할 질문 (TODO(1번-연동) 후보)
1. NaviRepository가 `requestTrip`/`onTripInfo`(사전 ETA)를 노출할 예정인가? `RouteOption`을 우리 쪽에서 지정할 수 있게 열어 줄 것인가?
2. 위치는 FusedLocation을 누가 감싸는가? 1번의 SDK 목록에 없으면 우리 어댑터에서 직접 감싸도 되는가?
3. VehicleRepository가 Pleos Vehicle SDK만 감싸는가, AAOS `CarUxRestrictions`도 포함하는가?
4. TtsRepository의 Mode(ON_DEVICE/HYBRID)와 Client ID/Secret은 누가 관리하는가?
5. Repository들이 콜백을 suspend/Flow로 바꿔 주는가, 콜백을 그대로 노출하는가?

## 다음에 실행 확인이 필요한 것 (우선순위순)
1. ~~에뮬레이터에서 `requestTrip`을 호출하면 안내가 시작되지 않고 `onTripInfo`만 오는지, `eta` 값의 의미~~ → **해소(11/09)**: 안내를 시작하지 않고 `eta`는 남은 초 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]
2. ~~에뮬레이터에 NaviHelper 요청을 처리할 Navigation 앱이 있는지~~ → **해소**: 있고 시험한 요청에 응답 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. → 2026-11-10 해소: `RouteOption.FREE` 요청은 적용되지 않았고(`onRouteStarted.tollFare=700`, `routeOption=RECOMMENDED`) `changeRouteOption`도 효과 없음 [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절](빈 poiId는 `requestRoute`·`TripRequest` 모두 허용)
3. 에뮬레이터 Extended Controls > Location 값이 FusedLocation `onChanged`로 들어오는지 — 일부 해소: `adb emu geo fix` 값을 FusedLocation 서비스 로그가 받았으나 SDK `onChanged`는 호출하지 않았다 [미확인]
4. ~~Maven 저장소 sync에 인증이 필요한지~~ → **해소**: `NaviHelper:3.0.5`는 인증 없이 받았다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md] (다른 SDK는 미확인)
5. ~~Playground 로그인 후 My Project > SDK 화면에서 FusedLocation/TTS 사용 가능 상태~~ → **해소(11/09)**: 둘 다 "준비 중". 남은 것은 "준비 중" 상태에서 에뮬레이터 호출이 되는지(실행 확인)
