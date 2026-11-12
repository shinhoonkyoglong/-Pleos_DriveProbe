# 2번 출발 전 — 1번 공통 기반 연결 가이드

> **11/12 이전 안내:** 이 문서는 2번 단독 저장소(`:departure-fake`, `DepartureWiring`, `departure.adapter`) 기준으로 쓰였습니다. 1번 저장소에서 실제로 연결된 방법은 `docs/departure/README.md`를 보세요. 이 문서는 포트 ↔ SDK 매핑 근거와 버전 이력으로 남깁니다.


## 결정 사항
- 2번의 외부 의존 포트는 **1번 공통 Repository에 맞춘다** (2026-11-09).
- 1번이 아직 없으므로 예상 이름으로 임시 계약을 둔다:
  `departure-core/src/main/kotlin/com/pleos/departure/port/CommonRepositoryContract.kt`
- 코드 내 수정 지점은 전부 `TODO(1번-연동)`으로 표시 → `grep -rn "TODO(1번-연동)" --include=*.kt .` 로 한 번에 찾는다.
- **실제 SDK 대상은 Playground '사용 가능'인 NaviHelper, Vehicle State뿐**(사용자 결정 11/09). 아래 "사용하지 않는 SDK" 참고.
  - **예외: 주행 상태 = AAOS 표준 `CarUxRestrictions`**(감독관 결정 #11). Pleos SDK에는 주행 상태·UX 제한 API가 없고, Pleos 주행 규제 개발 가이드가 앱에 `CarUxRestrictions` 기준 적용을 **요구**한다(vehicle-state.md §12.3·§12.4). 속도·기어·`DrivingMode`로 판단하지 않는다.
- MVP 시연은 `departure.adapter=fake` 기준.

## 1번 확정 시 할 일
1. 아래 매핑표의 "1번 실제" 열을 채운다.
2. 이름·시그니처가 다르면 `CommonRepositoryContract.kt`만 수정한다 (Fake·core 테스트 스텁도 컴파일 에러로 함께 드러남).
3. 1번 Repository를 감싸는 어댑터를 `:app`(또는 1번 모듈)에 작성한다. 어댑터는 1번 타입 → core 도메인 모델 변환만 한다.
4. `DepartureWiring.kt`의 `common` 분기(지금은 즉시 실패)를 어댑터 조립으로 바꾼다.
5. `gradle.properties`의 `departure.adapter=common`으로 바꿔 빌드·시연한다.
6. 끝나면 `TODO(1번-연동)` 메모를 제거하고 아래 "1번 연동 시 수정 목록"을 정리한다.

## 전환 지점 (config)
| 무엇 | 어디서 바꾸나 |
|---|---|
| Fake ↔ 1번 실제 구현 | `gradle.properties` → `departure.adapter` = `fake` 또는 `common` (`app/build.gradle.kts`가 검증 후 `BuildConfig.DEPARTURE_ADAPTER`로 노출. 다른 값이면 빌드 실패) |
| 조립 코드 | `app/src/main/java/com/example/myapplication/departure/DepartureWiring.kt` (유일). `common`은 1번 연동 전까지 `IllegalStateException("... 1번 공통 Repository 미연동 ...")`로 즉시 실패, fake 폴백 없음 |
| 동작 수치 (여유 시간, 반경, 타임아웃, 임계값, 시간대, 기본 경로 옵션) | `departure-core/src/main/kotlin/com/pleos/departure/DepartureConfig.kt` — `DepartureWiring`에서 주입 |
| 1번 Repository 이름·시그니처 | `departure-core/src/main/kotlin/com/pleos/departure/port/CommonRepositoryContract.kt` (유일) |
| 진입점 | `departure-core/src/main/kotlin/com/pleos/departure/DepartureDependencies.kt`, `DepartureFeature.kt`(#7: `departureCheck` = 조건 D-9 + 목적지 1회 조회 + 마감·반경·경로 묶음. **`DepartureFeature.VERSION` 0.6.0: 한국어 `statusText` 삭제**(소비자 영향 있는 변경 — 문구는 :departure-ui `DepartureDisplayMapper`가 만든다), `departureCheck`·`config`·`clock`·`observeDrivingRestriction()` 추가. **0.7.0(#11)**: 시그니처 변경 없음, 어댑터 의무 변경 — 주차 중이면 `UNRESTRICTED`를 명시(`treatUnknownDrivingAsRestricted` 기본 true라 UNKNOWN은 조작 숨김), 경로 교체 중·안내 없음의 0 값 목적지는 둘 다 `NotSet`). 화면은 :departure-ui `rememberDepartureScreenModel(feature)` + `DepartureScreen(model)`. **홈 카드(#24, 임시)**: 같은 model 로 `DepartureHomeCard(model, onOpen)` — 앱 홈에 '오늘 운전해도 되나요?' 요약 1장, 누르면 `onOpen`에서 전체 화면으로 이동. 요약 규칙은 `HomeCardSummary`(새 판정 없이 `DepartureUiState` 요약). 홈 카드와 전체 화면을 동시에 띄우지 말 것(둘 다 보일 때 `onVisible`). :app은 `DepartureWiring.createAssembly()`만 부르고, fake일 때만 `fakeScenarioController`가 non-null(시연 패널). **0.8.0(#12b)**: 포트 시그니처 변경 없음. 세션 동작을 core로 옮겨 공개 API 추가 — `DepartureFeature.createSession(scope: CoroutineScope): DepartureSession`, `session` 패키지(`DepartureSession`: `state: StateFlow<DepartureSessionState>`, 명령 `onVisible()`·`onHidden()`·`refresh()`·`confirmTollAvoidanceTurnedOn()`·`resetSession()` / `DepartureSessionState`·`CheckOutcome`·`RecheckProgress`). **세션에는 release 메서드가 없고 넘긴 CoroutineScope 가 취소될 때만 멈춘다**(viewModelScope·lifecycleScope·rememberCoroutineScope 처럼 수명이 끝나는 범위를 넘길 것). **스레드 가정**: 상태 갱신은 그 scope 의 dispatcher 에서 일어나고, 명령은 메인 스레드에서 부른다(내부 상태 동기화 없음). core 만 이식한 1번 저장소에서는 `DriveProbeApplication.instance.departureFeature.createSession(scope)`(#12c, 1번 저장소 `docs/departure/README.md`) **0.9.0(#27)**: 포트 시그니처 변경 없음. 모델 변경 — `RouteConditionCheck.TollAvoidanceUnverified` 추가, `RouteUserAction.REVIEW_TOLL_ON_ROUTE`·`RouteNote.TOLL_FARE_NOT_OBSERVED` 삭제, 무료도로 충족 = FREE + tollFare 0 일 때만(`when` 으로 전부 처리하는 소비자는 새 분기 필요). **0.10.0(#28)**: `NaviRepository.openNaviSettings(): DataResult<Unit>` 추가(기본 구현 NOT_SUPPORTED — 구현 안 해도 컴파일됨), `DepartureFeature.openNaviSettings()` 추가. 화면: `DepartureScreenContent(onOpenNaviSettings, naviSettingsFailed)` 인자 추가(기본값 있음). **0.11.0(#29)**: 포트 시그니처 변경 없음. 끝난 주행 조건 준수 판정 추가 — `DepartureFeature.evaluateTripCompliance(startedAt, endedAt, location?)`·`tripCompliance`, 모델 `TripCompliance` 등, 설정 `defaultSunReferencePoint`·`tripNightTolerance`(아래 "5번 연동 — 주행 조건 준수 판정") |
| Fake 팩토리 / 시나리오 | `departure-fake/src/main/kotlin/com/pleos/departure/fake/FakeDepartureDependencies.kt`, `FakeScenario.kt` (전환: `scenarioController.select(FakeScenarioId.X)`) |

## 매핑표 — 임시 계약 메서드 ↔ 예상 SDK
"1번 실제" 열은 1번 확정 시 채운다. 근거 경로는 `docs/pleos/api-notes/` 기준. 1번 공개 코드(2026-11-10)와의 대조는 아래 "1번 연동 시 수정 목록" 표와 [integration/part1-gap-analysis.md](integration/part1-gap-analysis.md) 1절 참고. **#12c(2026-11-10)부터 "1번 실제" 열은 1번 저장소 브랜치 `feat/departure-core`(미병합)에 2번이 추가한 멤버·어댑터 기준**이다(1번 확정 이름 아님 — 계약 이름 정렬은 병합 후, 결정 D-6).

| 임시 계약 (2번) | 메서드 | 예상 SDK 호출 → 콜백 | 결과 변환 / 실패 매핑 | 근거 | 1번 실제 | 상태 |
|---|---|---|---|---|---|---|
| VehicleRepository | `observeDrivingRestriction(): Flow<DrivingRestriction>` | **AAOS `CarUxRestrictionsManager`** (Pleos SDK 아님, Pleos가 요구 — 감독관 결정 #11): `Car.createCar` → `getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)` → `getCurrentCarUxRestrictions()`(첫 값) + `registerListener` → `onUxRestrictionsChanged(CarUxRestrictions)`. 호출 형태는 AOSP 지식 [불확실]. 권한명 **[미확인]** | `isRequiresDistractionOptimization()` true → RESTRICTED, false → UNRESTRICTED. 미수신·연결 실패·오류 → UNKNOWN(화면은 기본 설정에서 **제한으로 봄** — 플랫폼 unknown = 0x1ff와 같음) → **주차 중이면 반드시 UNRESTRICTED를 내보낼 것**. `getActiveRestrictions()` 비트는 지금 안 씀. 속도·기어·`DrivingModeType`(NORMAL/ECO/SPORT 성향 모드)으로 판단 금지. 에뮬레이터 baseline 설정값: Parked DO false 0x0, Idling·Moving DO true 0x50 [실행 확인 — 설정값만. 기어 전환으로는 Parked·Idling만 확인, Moving 전환 미확인] | vehicle-state.md §A·§11.1~11.4·§12.1·§12.3·§12.4 | `VehicleRepository.drivingState` → `DriveProbeVehicleAdapter`(DRIVING→RESTRICTED, IDLE/STOPPED→UNKNOWN) | 임시·소스 **결정됨**, 제공 주체 미정(수정 목록 5) → 연결됨(#12c, 의미 충돌 — 늘 제한) |
| NaviRepository | `getCurrentLocation(): DataResult<GeoPoint>` | NaviHelper `getCurrentLocationInfo()` → `onCurrentLocationInfo(CurrentLocationInfo)` | 위도·경도만 사용. 콜백 미도착 → TIMEOUT(`locationCallbackTimeout`, 실측 20~35ms), 내비 없음 → NOT_SUPPORTED. 에뮬레이터에서는 내비 기본 위치(성남)에 고정 [실행 확인] | eta-location.md §B "대안", navi-runtime-check.md 3.2·4절 | `NaviHelperRepository.getCurrentLocation(): Result<NaviLocation>`(#12c 추가) | 임시 → 연결됨(#12c) |
| NaviRepository | `getDestination(): DataResult<NaviDestination>` **(#5 추가)** | NaviHelper `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` | **(0,0) 판별은 어댑터**: `latitude == 0.0 && longitude == 0.0`(필요 시 poiId·address 빈 값) → `NaviDestination.NotSet`. 그 밖 → `Present(Place)`(displayName = name → address → "내비 목적지", 빈 문자열 → null). (0,0)을 Present로 넘기지 않는다(반경 오경고). "목적지 없음"을 Unavailable(NO_DATA)로 쓰지 않는다. naviMode는 보조로만(SIMULATION_GUIDING 오판). 미도착 → TIMEOUT(`destinationCallbackTimeout`, 실측 ≤약 1.0초), 내비 없음 → NOT_SUPPORTED. remainTime·tollFare는 담지 않음. **0 값 규칙(#11)**: 경로 교체 중(`onRouteCancelled`~`onRouteStarted`)과 안내 없음은 **똑같은 0 값 객체**가 오류 없이 와서 구분 불가 → 둘 다 NotSet. 구분은 화면 재확인(5초 × 6회 = 30초)이 맡는다(정상 교체 관측 최대 약 19초). 30초 뒤에도 0 값이면 "목적지 없음" 확정(경로 탐색 실패 `onError CAUSE_SEARCH_ROUTE` 시 0 값이 무기한 지속). **도착 직전과 혼동 금지**: 도착 직전 값(remainTime 0 여부, name·좌표 유지 여부)은 관측하지 않음 [미확인] → [설계 판단] NotSet은 좌표 (0,0) + 빈 name으로 판정하고 remainTime 0만으로 판정하지 않는다. 리스너를 계속 구독하면 `onRouteCancelled` → `onRouteStarted`/`onError`가 더 정확한 교체 구간 경계(선택) | navi-runtime-check.md 3.2·3.4·3.5·7.1·§11.1·§11.2·§11.5 [실행 확인] | `NaviHelperRepository.getDestinationInfo(): Result<NaviDestinationInfo?>`(#12c 추가, (0,0) → null) | 임시·이름 **추정** → 연결됨(#12c) |
| NaviRepository | `requestTrip(Place): DataResult<TripEstimate>` | NaviHelper `requestTrip(TripRequest)` → `onTripInfo(TripInfo)` | `distance`(m), `eta` = **남은 초**[실행 확인] → `TripEstimate(source=SDK_MEASURED, basis=수신 시각, routeOption=아래 규칙, destination=요청 좌표)`(destination은 비워도 core가 요청 좌표로 채움. 이전 ETA는 목적지 좌표가 같을 때만 재사용 — 리뷰 task-05 MAJOR-1). 안내를 시작하지 않음[실행 확인]. 미도착 → TIMEOUT(`tripCallbackTimeout`, 실측 0.4~0.55초), `CAUSE_SEARCH_TRIP` → SERVICE_ERROR. TripRequest 빈 문자열 허용 [실행 확인 2026-11-10]. ETA는 내비가 그 순간 쓰는 "유료도로 우회" 설정을 따름(안내 중 실행 확인 §10.6). **routeOption 규칙(감독관 결정 11/10)**: 안내 중이면 같은 시점에 읽은 `RouteStateInfo.routeOption`, 안내 중이 아니거나 못 읽으면 null(core가 RECOMMENDED로 보고 보수적 보정). FREE 기준 ETA에는 core가 보정을 다시 붙이지 않음(이중 보정 없음) | eta-location.md §A, navi-runtime-check.md 3.6·§10.6 | `NaviHelperRepository.requestTrip(lat, lng, poiName, poiId): Result<NaviTripInfo>`(#12c 추가, 같은 Mutex 안에서 routeOption 동시 조회) | 임시 → 연결됨(#12c) |
| NaviRepository | `getRouteState(): DataResult<RouteState>` **(#5b, requestRoute 대체)** | NaviHelper `getRouteStateInfo()` → `onRouteStateInfo(RouteStateInfo)` + 리스너로 받아 둔 `onRouteStarted(RouteStartInfo).tollFare` | `naviMode` ROUTE_GUIDING(·SIMULATION_GUIDING) → `Guiding(appliedOption = routeOption, tollFare)`, SAFE_GUIDING → `NotGuiding`, NOT_WORKING → Unavailable(NO_DATA)(의미 [미확인], 설계 판단). `appliedOption`은 **보고값**(주 신호, 다시 조회 가능). `tollFare`는 현재 경로의 onRouteStarted 값(다시 조회 불가). **캐시 규칙**: 못 받았거나, `onRouteCancelled`를 받았거나, 리스너를 (재)등록했거나, 받을 때 저장한 목적지 좌표가 현재 목적지와 다르면 null(리뷰 task-05 재검토 MINOR-R1). `DestinationInfo.tollFare`는 쓰지 않음(항상 0). 미도착 → TIMEOUT(`routeStateCallbackTimeout` 5초), 내비 없음 → NOT_SUPPORTED. 부작용 없음 | navi-runtime-check.md §10.4·10.5, reviews/task-01d MAJOR-4 [실행 확인 2026-11-10] | `NaviHelperRepository.getRouteStateInfo(): Result<NaviRouteState>`(#12c 추가, tollFare 캐시 규칙 구현) | 임시·이름 **추정** → 연결됨(#12c) |
| NaviRepository | `observeRemainingTrip(): Flow<RemainingTrip>` | NaviHelper `onDestinationInfo(DestinationInfo)` 푸시 / `getDestinationInfo()` | `remainDistance`·`remainTime`(분·km 단위 값)·`tollFare`(0일 수 있음, 유료 판단 금지). 취소 때 오는 0 값 푸시는 내보내지 않음(안내 끝) | eta-location.md §A, navi-runtime-check.md 3.8 | `NaviHelperRepository.currentDestination: StateFlow<NaviDestinationInfo?>`(#12c 추가) | 임시 → 연결됨(#12c, core 미사용) |
| NaviRepository | `openNaviSettings(): DataResult<Unit>` **(#28 추가, 기본 구현 = NOT_SUPPORTED)** | NaviHelper `requestNavigationScreen(NavigationScreenRequest.Settings)`(콜백 없음, 권한 `NAVI_CUSTOM_MAP`) | 호출 성공 → Available(Unit), 미연결·권한 없음·예외 → Unavailable. 설정값은 바꾸지 않음(차량 설정 "내비게이션" 탭을 열 뿐) | navi-route.md 끝 절 [실행 확인 2026-11-11] | 1번 `NaviHelperRepository.openNaviSettings()`(#28에서 추가) | 연결됨 |
| TtsRepository | `speak(String): SpeakResult` | TextToSpeech `speak(text, isFinal)` → `onDone/onStop/onError` | **사용 안 함**(TTS 준비 중). Fake는 항상 Unavailable | tts-stt.md | 없음 → `UnavailableTtsRepository` | 임시·미사용 → 미사용 |
| SttRepository | `recognizeOnce(): DataResult<String>` | SpeechToText `ResultListener.onUpdated(stt, completed=true)` | **사용 안 함**(STT 준비 중) | tts-stt.md | 없음 → `UnavailableSttRepository` | 임시·미사용·**추정** → 미사용 |
| DriverConditionRepository | `getCondition()`, `saveCondition()`, `observeCondition(): Flow<DataResult<DriverCondition?>>` (#5 재작업에서 변경: `Available(null)` = 확인된 미설정만, 조회 실패 = `Unavailable`) | Pleos SDK 무관. 1번 로컬 DB + 서버 동기화 | 404+CONDITION_NOT_SET → NO_DATA, 그 밖의 404·5xx·모르는 enum → SERVICE_ERROR, 400/415 → INVALID_REQUEST(계약 5.3 확정) | [contracts/driver-condition-api.md](contracts/driver-condition-api.md) — GET/PUT /v1/drivers/me/driver-condition, 오류 매핑 5.3, observeCondition 규칙 5.4, 1번 결정 목록 8절 | 없음 → `InMemoryDriverConditionRepository`(TODO, 1번 Room 미변경) | 임시·**추정** → 임시 인메모리(#12c) |

공통 구현 규칙(1번 또는 그 위 어댑터): 콜백 → suspend/Flow 변환, NaviHelper는 요청 ID가 없으므로 **Mutex로 한 번에 한 요청**, 타임아웃 값은 `DepartureConfig`에서 받는다, 일반 실패는 예외 대신 `DataResult.Unavailable`. 리스너 콜백은 binder 스레드에서 온다. Mutex로는 요청 대기 중 외부 사건 푸시(사람이 취소해서 온 0 값 `onDestinationInfo`)를 거르지 못한다 → 목적지·상태는 "마지막으로 받은 상태" 캐시로 다루거나 0 값이면 짧게 1회 재조회(navi-runtime-check.md 7.3).

### 무료도로 조건 — "안내 + 확인" (작업 #5b, 사용자 결정 11/10)
- **우리 앱은 경로 옵션을 적용하지 않는다.** 이미 내비에 설정된 목적지에 `requestRoute`를 자동으로도 버튼으로도 부르지 않는다. 포트에서 `requestRoute`를 뺐다.
- 근거(navi-runtime-check.md §10, reviews/task-01d "#5 설계에 미치는 영향" — 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 한정)
  - `requestRoute(FREE)`·`changeRouteOption(FREE)`는 모두 무시됐다(routeOption=RECOMMENDED, tollFare=700, onRouteOptionChanged 없음) [실행 확인 2026-11-10]
  - 안내 중 `requestRoute`는 확인 없이 기존 안내를 덮어쓴다 [실행 확인]. SDK 요청은 `avoid_fare:false`로 만들어져 사용자가 켜 둔 "유료도로 우회"를 되돌릴 위험이 있다 [미확인, 위험 방향 분명]
  - 확인된 유일한 경로: 사용자가 내비 "경로 설정"에서 **"유료도로 우회"**를 켜고 "적용" → `RouteStateInfo.routeOption == FREE`(다시 조회 가능), `onRouteStarted.tollFare == 0`(푸시 1회, 다시 조회 불가)
  - "유료도로 우회"를 켜도 통행료 없는 고속도로(7.2km)가 남는다 → **고속도로 회피는 보장 수단이 없다**
- 흐름(`ConditionRouteUseCase.check` → `RouteConditionCheck`)
  1. 조건 없음 → `NoRouteCondition`(조회 안 함)
  2. `getDestination()` → `DestinationNotSet` / `DestinationUnavailable`
  3. 고속도로 회피만 → `ReferenceOnly(notes=[AVOID_HIGHWAY_NOT_GUARANTEED])` — 결코 "충족"으로 보지 않음
  4. 무료도로 조건 → `getRouteState()`
     - Unavailable → `Unconfirmed`, 안내 중 아님 → `NotGuiding`(경로 교체 중일 수 있음, 잠시 뒤 재확인)
     - `appliedOption == RECOMMENDED`(토글 꺼짐 실측 값) → `NeedsUserAction([TURN_ON_TOLL_AVOIDANCE])`
     - 그 밖의 FREE 아닌 값(SCHOOL_ZONE 등) → `OtherRouteOption` — 충족 아님, "켜세요"로 단정하지 않고 확인만 권함(두 토글 조합 값 [미확인], §10.4 추천 3, 리뷰 task-05 재검토 MAJOR-R2)
     - **(#27부터) FREE 만으로는 충족이 아니다** — "어린이 보호구역 우회"만 켜도 FREE 가 오고 그 경로 통행료는 700원이었다(실행 확인 2026-11-11)
     - FREE + tollFare 0 → `Satisfied`
     - FREE + tollFare 모름 → `TollAvoidanceUnverified`(충족 아님, "유료도로 우회를 확인해 주세요" + [내비 설정 열기])
     - FREE + tollFare > 0 → `NeedsUserAction([TURN_ON_TOLL_AVOIDANCE])`(유료도로 우회가 꺼진 것)
     - (#27에서 삭제: `RouteUserAction.REVIEW_TOLL_ON_ROUTE`, `RouteNote.TOLL_FARE_NOT_OBSERVED`)
     - 고속도로 회피도 있으면 notes에 `AVOID_HIGHWAY_NOT_GUARANTEED`
- 문구는 키(`RouteUserAction`, `RouteNote`)로만 둔다. 한국어 문구·아이콘은 #7 화면 몫.
- 재확인 시점: 화면 진입·백그라운드 복귀, 사용자가 "켰어요"를 누른 뒤. 목적지·경로 변경 콜백(`onDestinationChanged`)은 오지 않는다(§10.5) → 조회 기반. `NotGuiding`(경로 교체 7~19초)의 재확인 간격·횟수는 `DepartureConfig.routeChangeRecheckInterval`(5초)·`routeChangeRecheckMaxAttempts`(6회)다(#7, 근거 §10.5 실측 7.3/16.0/18.9초, §11.2 SDK 변경 7.5/11.2/16.3초). 그 사이 `getDestination`은 NotSet이다([실행 확인 2026-11-10, §11.1] — 안내 없음과 같은 0 값) → 직전에 목적지가 있었으면 바로 "목적지 없음"으로 확정하지 않고, 30초 뒤에도 같으면 확정한다(탐색 실패면 0 값이 무기한, §11.2).
- 시연(Fake): `TOLL_FREE_NOT_APPLIED` 시나리오에서 `scenarioController.simulateUserTollAvoidanceToggle()`(사람의 내비 조작을 흉내)로 미적용 → 안내 → 적용 확인. 토글하면 requestTrip ETA도 FREE 기준(더 긴 값, routeOption=FREE)으로 바뀌어 출발 마감 보정이 빠진다. `ROUTE_NOT_GUIDING`, `ROUTE_STATE_UNKNOWN`, `ROUTE_OTHER_OPTION`(SCHOOL_ZONE 보고), `TOLL_FARE_UNOBSERVED`(FREE + tollFare 모름) 시나리오도 있다.
- 다른 내비 버전·실차에서 SDK 옵션이 적용될 가능성은 [미확인]. 그래도 판정은 보고값만 쓰므로 이 설계는 그대로 유효하다.
- **2번이 requestRoute를 다시 쓸 수 있는 경우**: 내비에 목적지가 없을 때 우리 앱이 새 목적지를 설정하는 기능. 지금 범위 밖(목적지 = 내비 목적지, 사용자 결정 11/09). 그때도 옵션은 무시되므로 위 "안내 + 확인" 흐름을 똑같이 탄다.

## 사용하지 않는 SDK (사용자 결정 11/09)
| SDK | 상태 | 2번에서의 처리 |
|---|---|---|
| FusedLocation (ProximityAlert 포함) | Playground '준비 중' | 사용 안 함. 위치는 NaviHelper `getCurrentLocationInfo`만. 반경 판정은 core 순수 계산 `GeoDistance.isWithinRadius(center, point, radiusMeters)`(Haversine, 경계 = 안, 작업 #5) |
| TextToSpeech | '준비 중' | 사용 안 함. 경고·출발 마감 안내는 화면 표시. `TtsRepository`는 1번 이름 정렬용 계약만 |
| SpeechToText | '준비 중' | 사용 안 함. `SttRepository` 계약만 |
| Vehicle Control | '준비 중' | 사용 안 함(2번 불필요) |
| Vehicle Data REST API | 유럽 한정, 공모전 허용 목록 밖 | 사용 안 함 |

- **Notification/예약 알림 포트 없음**(리뷰 task-03 MINOR-8): 출발 마감 "알림"은 `DepartureDeadlineUseCase`가 만드는 `DepartureDeadlineResult`(단계 + 마감 시각)를 화면에 표시하는 것으로 대체한다. TTS·시스템 알림·예약 알림은 하지 않는다(사용자 결정 11/09). 필요해지면 `port/DeparturePorts.kt`에 우리 전용 포트로 추가(감독관 결정).

## 1번 연동 시 수정 목록
(추정으로 잡은 시그니처, 1번에 확인·요청할 사항. 확정되면 항목 옆에 결과를 적는다.)

**1번 실제 코드 대조 결과 (작업 #12a, 2026-11-10)** — 상세·근거·1번 요청문: [integration/part1-gap-analysis.md](integration/part1-gap-analysis.md).
1번 저장소(단일 `:app`, `ai.pleos.playground.sample.vehicle`)의 `VehicleRepository`·`NaviHelperRepository`와 비교했다. `NaviHelperRepositoryImpl`은 SDK를 부르지 않는 목업이고, 빌드에 NaviHelper 의존성이 없다. **`CommonRepositoryContract.kt`는 결정(분석 문서 7절) 전까지 바꾸지 않는다.**
상태: 일치 / 이름만 다름 / 없음 / 충돌

| 항목 | 1번 대응 | 상태 |
|---|---|---|
| 1. NaviRepository 메서드 이름 | 대응 메서드 자체가 없음(이름 비교 불가). 1번 인터페이스 이름은 `NaviHelperRepository` | 없음 |
| 2. requestTrip | 없음. `searchConditionAdaptedRoute`의 `estimatedDurationMinutes`는 지어낸 고정값 | 없음 |
| 3. requestRoute(해당 없음) | `searchConditionAdaptedRoute(destination: String, conditions: DrivingConditions)`·`startNavigation(routeId)` — 옵션 무시(§10.1)·덮어쓰기(§10.8)·고속도로 회피 없음과 충돌. 2번은 호출 안 함 | 충돌 |
| 4. 위치 | `setMockLocation(lat, lng)`(쓰기 전용 목업)뿐, 조회 없음 | 없음 |
| 5. CarUxRestrictions | `drivingState: StateFlow<DrivingState>`(IDLE/DRIVING/STOPPED) — `setDrivingState()`로만 바뀜, 기본 IDLE. 안전 매핑은 DRIVING→RESTRICTED, 나머지→UNKNOWN뿐(→ 늘 제한) | 충돌 |
| 6. 콜백 → suspend/Flow | StateFlow + suspend `Result` 스타일 — 형태는 맞음(단 Navi는 SDK 미연결) | 일치 |
| 7. 실패 표현 | `kotlin.Result` + 일반 예외 → 어댑터가 `DataResult.Unavailable`로 변환. 사유 구분 불가(전부 SERVICE_ERROR) | 이름만 다름 |
| 8. TTS/STT | 없음(2번 미사용, 영향 없음) | 없음 |
| 9. DriverConditionRepository | Room에 조건 테이블 없음. `DrivingConditions`(avoidNightDriving·avoidHighways·maxSpeedLimitKmh·preferComfortableRoute)는 무료도로·반경이 없어 2번 모델과 다름 | 없음(모델은 충돌) |
| 11. 목적지 조회 | 없음(`currentRoute`는 앱이 만든 경로, 좌표 없음) | 없음 |
| 12. 경로 상태(routeOption·tollFare) | 없음(`navigationState`는 앱 내부 상태) | 없음 |
| 13. requestTrip + 경로 상태 같은 Mutex | 없음 | 없음 |
| 14. 푸시 콜백 소유 | NaviHelper 리스너 미등록 | 없음 |
| 16. **빌드 호환(신규)** | AGP 8.8.2 / Kotlin 2.0.0 / Gradle 8.10.2 / compileSdk 35 / minSdk 23 / Compose BOM 2024.04.01 vs 우리 AGP 9.5.1 / 2.2.10 / 9.6.0 / 37 / 28 / 2026.02.01. AGP 9 전용 DSL·minSdk 병합에서 실패 → 우리 build 파일을 1번 버전에 맞추고 1번 minSdk를 28로(Pleos 요구) | 충돌 |

**1번 저장소 연결 결과 (작업 #12c, 2026-11-10, 1번 저장소 브랜치 `feat/departure-core`, 미커밋)** — 사용자 결정(11/10): 빈 기능은 2번이 1번 Repository에 **추가만** 한다.
`:departure-core`를 1번 저장소에 이식(빌드 파일만 1번 버전에 맞춤, core 코드 변경은 `TripEstimate.isFresh` 1줄 — 아래 17), 1번 `NaviHelperRepository(Impl)`에 실제 NaviHelper SDK 조회 계열을 추가, 1번 `:app`의 `ai.pleos.playground.sample.vehicle.departure` 패키지에 어댑터를 두었다. 사용법·변경 파일 목록·한계: 1번 저장소 `docs/departure/README.md`.

| 항목 | #12c 결과 | 상태 |
|---|---|---|
| 1·11. 목적지 조회 | `NaviHelperRepository.getDestinationInfo(): Result<NaviDestinationInfo?>` 추가((0,0) → null) → 어댑터가 NotSet/Present 변환 | **연결됨** |
| 2·13. requestTrip + routeOption | `requestTrip(lat, lng, poiName, poiId): Result<NaviTripInfo>` 추가. 같은 Mutex 구간에서 `getRouteStateInfo` 를 이어 불러 안내 중이면 routeOption 을 채운다 | **연결됨** |
| 3. requestRoute | 1번 `searchConditionAdaptedRoute` 는 그대로 두고(수정 안 함) 2번은 호출하지 않음. 경고는 1번 README 6절 3 | 충돌(1번 판단 대기) |
| 4. 위치 | `getCurrentLocation(): Result<NaviLocation>` 추가 | **연결됨** |
| 5. CarUxRestrictions | 1번 `drivingState` 를 DRIVING→RESTRICTED, IDLE/STOPPED→UNKNOWN 으로 매핑(정직한 매핑) → 기본 설정에서 화면 조작 늘 숨김 | 충돌(1번에 V-1 요청 유지) |
| 6. 콜백 → suspend/Flow | 1번 Impl 이 콜백 → suspend `Result` + `StateFlow`(`currentDestination`·`currentRouteState`)로 변환, 메인 스레드 호출 | **연결됨** |
| 7. 실패 표현 | `NaviHelperException(reason = UNAVAILABLE/TIMEOUT/SERVICE_ERROR/PERMISSION_DENIED)` 추가 → 어댑터가 NOT_SUPPORTED/TIMEOUT/SERVICE_ERROR/PERMISSION_DENIED 로 변환 | **연결됨**(Navi 계열만) |
| 8. TTS/STT | `UnavailableTtsRepository`·`UnavailableSttRepository`(항상 NOT_SUPPORTED) | 미사용 |
| 9. DriverConditionRepository | `InMemoryDriverConditionRepository`(TODO). 1번 Room 스키마 미변경 | 임시 인메모리 |
| 12. 경로 상태·tollFare | `getRouteStateInfo(): Result<NaviRouteState>` 추가. tollFare 캐시: onRouteStarted 에서 저장, onRouteCancelled·리스너 (재)등록·다른 목적지 좌표면 버림(목적지 좌표는 onRouteStarted 이후 첫 목적지 값에 묶음) | **연결됨** |
| 14. 푸시 콜백 소유 | NaviHelper 인스턴스·리스너는 1번 `NaviHelperRepositoryImpl` 하나가 소유. 0 값 푸시는 `currentDestination = null` | **연결됨** |
| 16. 빌드 호환 | `:departure-core` build 파일만 1번 버전(Kotlin 2.0.0, JVM 17, 1번 카탈로그)에 맞춤. 1번 minSdk 23 → 28. NaviHelper 3.0.5 + `NAVI_ROUTE` 권한 추가 | **해소**(1번 툴체인 실빌드는 미확인 — dev-log #12c) |
| 17. **core 이식 수정(신규)** | `TripEstimate.isFresh`의 `Duration.isPositive`(Java 18 / Android API 34+)를 비교 연산으로 바꿈(동작 동일). JDK 17 빌드와 minSdk 28~33 기기에서 필요 → **이 저장소 core 에도 같은 수정 필요(역반영 대기)** | 역반영 필요 |
| Mock 값 | 1번 Mock Fallback 값(`isMock = true`)은 어댑터 기본 설정에서 NOT_SUPPORTED 로 바꿈(지어낸 위치·ETA 로 판정 금지). 시연만 `acceptMockNaviValues = true` | 결정(#12c) |

1. **NaviRepository 메서드 이름** — SDK 이름(`getCurrentLocation`/`getDestination`/`requestTrip`/`getRouteState`)을 따랐다. 1번 이름이 다르면 계약 파일만 수정.
2. **requestTrip 노출 여부** — 1번이 `requestTrip`/`onTripInfo`를 감싸 줄지(README §4-1). 없으면 출발 마감은 항상 `fallbackTripDuration`(기본값) 경로.
3. ~~requestRoute에 RouteOption 지정 가능 여부~~ → **해당 없음(#5b)**: 2번은 requestRoute를 쓰지 않는다. 1번 NaviRepository에 requestRoute가 있어도 2번 어댑터는 노출하지 않는다.
4. **위치의 소속** — NaviRepository 안(현재 가정)인지 별도 LocationRepository인지(README §4-2). 위치 소스는 NaviHelper만 전제.
5. **1번에 요청: VehicleRepository가 AAOS `CarUxRestrictions`를 감싸 제공**(README §4-3, 감독관 결정 #11).
   - **소스는 결정됨**: 주행 상태 = AAOS `CarUxRestrictionsManager`의 `isRequiresDistractionOptimization()`(필요하면 `getActiveRestrictions()`). Pleos 주행 규제 개발 가이드가 앱에 요구한다(vehicle-state.md §12.3). 속도·기어·`DrivingMode`는 쓰지 않는다(Pleos SDK에 속도·기어 없음, `DrivingModeType`은 NORMAL/ECO/SPORT 성향 모드 — §12.1).
   - **"사용 가능 Pleos SDK만" 규칙의 예외**다. Pleos SDK가 아니지만 Pleos가 요구하므로 쓴다(§12.4 권고). 2번 포트 시그니처는 그대로(`observeDrivingRestriction(): Flow<DrivingRestriction>`).
   - **필요 권한명 [미확인]**: Pleos 문서에 없음(§11.1·§12.3). 앱 프로세스에서 `Car.createCar`가 동작하는지도 [미확인] — 1번(또는 researcher)이 안드로이드 공식 문서·에뮬레이터 프로브로 확인 필요.
   - 1번이 제공하지 않으면: 2번 어댑터(:app 쪽)가 `android.car` 표준 API를 직접 감싸는 안을 감독관이 결정한다. core·ui는 바뀌지 않는다.
   - **현재 동작(#11)**: `DepartureConfig.treatUnknownDrivingAsRestricted` 기본값을 **true**로 바꿨다(플랫폼도 unknown = 전부 제한 0x1ff, §11.2). 그래서 소스가 연결되기 전 실제 연동(`common`)에서는 늘 UNKNOWN → **무료도로 단계 안내(①②③)·"켰어요"·"다시 확인"이 숨겨지고 "주행 중에는 화면 조작을 할 수 없어요"가 보인다**(안전 쪽). 어댑터는 주차 중이면 반드시 UNRESTRICTED를 내보내야 한다. Fake 시나리오는 모두 주차(UNRESTRICTED) 또는 주행(RESTRICTED, `DRIVING_RESTRICTED`)을 명시한다.
   - 에뮬레이터 시연: Virtual Console 기어 Park/Drive로 실제 규제(0x0/0x50)를 재현할 수 있다(adb로는 불가, §11.3).
6. **콜백 → suspend/Flow 변환을 1번이 해 주는지**(README §4-5). 콜백 그대로면 우리 어댑터에서 변환 + Mutex + 타임아웃.
7. **실패 표현** — 1번이 예외/Result/sealed 중 무엇을 쓰는지. 2번은 `DataResult.Unavailable(UnavailableReason)`로 변환해 받는다. 어댑터가 예외를 새어 나가게 해도 화면 상태 홀더(`DepartureScreenModel`)가 잡아 "출발 전 확인 불가"를 보여 주고 주기 확인을 계속한다(#9, 앱 종료 방지). 다만 사유가 사라지므로 어댑터에서 변환하는 것이 원칙.
8. **TTS/STT** — 1번 계약 이름만 맞춘다. SDK 제공 시(본선 등) 재검토.
9. **DriverConditionRepository** — 1번 로컬 DB/동기화 API 형태, 서버 계약(작업 #6)과 필드 일치 확인.
10. ~~TripInfo.eta 해석, RouteInfo·TripRequest 빈 문자열~~ → **해소**(navi-runtime-check.md 3.6·§10.6): eta = 남은 초, 빈 문자열 POI 허용.
11. **목적지 조회 노출 여부 (#5)** — 1번이 `getDestinationInfo`를 감싸 줄지, "목적지 없음"을 어떻게 표현할지. 2번은 `getDestination(): DataResult<NaviDestination>`(Present/NotSet)으로 받는다. 1번이 0 값 객체를 그대로 주면 우리 어댑터가 (0,0) → NotSet 변환을 한다(매핑표 규칙).
    - (#11) 경로 교체 중의 0 값도 안내 없음과 같은 NotSet으로 준다(구분 불가, §11.1). 1번이 "교체 중"을 따로 표현해 주면(예: `onRouteCancelled` 이후 `onRouteStarted`/`onError` 전) 화면 재확인을 그 신호로 바꿀 수 있다 — 지금은 포트에 표현이 없어 30초 재확인으로 처리.
12. **경로 상태 노출 (#5b)** — 1번이 `getRouteStateInfo`를 감싸 줄지, `onRouteStarted` 푸시의 tollFare를 "현재 경로의 마지막 값"으로 받아 둘 수 있는지(onRouteCancelled에서 버림). 못 하면 tollFare는 항상 null → 판정은 `TollAvoidanceUnverified`('확인해 주세요')가 된다(#27부터 FREE 만으로 충족하지 않음). 백그라운드에서 얼려진 앱은 깨어날 때 지난 onRouteStarted 를 받는다(실행 확인 2026-11-11).
13. **requestTrip ETA의 옵션 기준 (#5b, 결정됨 11/10)** — 어댑터는 안내 중이면 같은 시점 `RouteStateInfo.routeOption`을 `TripEstimate.routeOption`에 넣고, 안내 중이 아니면 null로 둔다(매핑표 requestTrip 행). 1번이 requestTrip과 getRouteStateInfo를 같은 Mutex 구간에서 부를 수 있는지 확인. 안내 없을 때 ETA 기준은 [미확인](null → 보수적 보정).
14. **푸시 콜백 소유 (리뷰 task-01c MINOR-6)** — 1번이 NaviHelper 리스너를 소유하면 0 값 푸시·"마지막 상태" 캐시 처리를 1번이 해 주는지 질문.
15. ~~requestRoute 타임아웃 후 재조회~~ → **해당 없음(#5b)**. 대신 `naviMode == NOT_WORKING`의 의미와 "어린이 보호구역 우회"를 함께 켰을 때 `routeOption` 값 [미확인] — 실차·1번 연동 때 확인.

## 5번 연동 — 주행 조건 준수 판정 (작업 #29, core 0.11.0)
5번(주행 후)의 `ComplianceSource`(1번 저장소 `trip/source/TripDataSources.kt`, `TODO(2번 연동)`)가 부를 함수. 2번은 **판정 함수만** 제공하고, 호출 시점·저장·표시는 5번 몫이다.

```kotlin
// 1번 저장소: DriveProbeApplication.instance.departureFeature
val result: DataResult<TripCompliance> = departureFeature.evaluateTripCompliance(
    startedAt = Instant.ofEpochMilli(trip.startedAt),
    endedAt = Instant.ofEpochMilli(trip.endedAt),
    location = null, // 주행 출발지를 알면 넘긴다. 없으면 집(반경 기준점) → 설정 기본 위치(서울시청)
)
```

| 결과 | 뜻 | 5번 `ComplianceStatus` 권장 매핑 |
|---|---|---|
| `Unavailable(reason)` | 운전자 조건 조회 실패(저장 이력 없음 NO_DATA 는 실패가 아니라 '조건 없음'으로 판정) | `UNKNOWN` |
| `overall == VIOLATED` | 어긴 조건 있음 — `violations`(NO_NIGHT_DRIVING 등) | `VIOLATED`, `Violation.NIGHT_DRIVING` 등으로 변환 |
| `overall == KEPT` | 판정한 조건은 모두 지킴. `hasUnjudged == true`면 판정 못 한 조건(고속도로·반경)이 있으니 "모두 지킴"으로 단정하지 말 것 | `COMPLIED` |
| `overall == NOT_JUDGED` | 조건은 있지만 판정할 자료 없음(고속도로·반경만 있는 경우) | `UNKNOWN` |
| `overall == NOT_APPLICABLE` | 조건 없음(야간 조건이 꺼진 운전자 포함) | `UNKNOWN` 권장(준수율 분모에서 빠짐) — 5번 결정 |

- 야간 = 일몰(`DepartureConfig.nightStartReference`, 기본 도로교통법 '해가 진 후')부터 다음 일출 전까지. 출발 마감 계산과 같은 기준이다. 주행이 1초라도 걸치면 위반(`tripNightTolerance` 기본 0).
- 길이 0인 주행(시작 = 종료)은 야간 시각이어도 야간 시간 0 → `KEPT`. 너무 짧은 주행을 버릴지는 5번이 정한다.
- `KEPT` + `hasUnjudged == true`일 때 표시 문구는 **판정한 조건만** 말한다(예: "야간 운전 안 함 지킴", "모두 지킴" 금지).
- 고속도로·반경: 주행 경로·위치 기록이 없어 지금은 `NOT_JUDGED`. 기록이 생기면 2번이 판정을 추가한다.
- 판정 기준은 **지금 저장된** 조건이다. 앱 재시작 후 복구 저장처럼 주행이 끝난 지 오래면 그사이 조건 변경이 반영될 수 있다.
- 출처 표시: 이 값을 쓰면 5번 `DataSource.REAL`. 1번 저장소 PR #6 리뷰 MAJOR-1(야간 조건 없는 운전자를 20~6시 위반으로 기록)이 이것으로 해결된다.

## 설계 메모 — 백그라운드 복귀 시 상태 복원 (심사 일반 조항, 구현은 작업 #7)
근거: 앱 심사 가이드 "앱이 백그라운드로 이동했다가 돌아온 경우, 마지막 사용 중이던 상태가 복원되어야 합니다." (reviews/task-01b-playground-recheck.md 4c)
- **무엇을 보관하나**: 화면 상태를 다시 계산할 "입력"만. 마지막으로 조회한 내비 목적지(`Place`, 복귀 시 재조회와 비교용), 마지막 `TripEstimate`(값 + `source` + `basis`), 마지막 `RouteState`(appliedOption, tollFare — **표시용으로만** 보관. 판정 입력으로 되돌려 넣지 않고 복귀 시 `getRouteState()`를 새로 조회한다. 이전 경로의 tollFare가 남아 거짓 판정을 만들 수 있기 때문 — 리뷰 task-05 재검토 MINOR-R1), 현재 화면 단계. 일몰·마감 같은 파생 값은 저장하지 않고 복귀 시 재계산한다(시계가 흘렀으므로).
- **어디에 보관하나**:
  - 프로세스 유지 중(일반 백그라운드 복귀): `:departure-ui`의 ViewModel이 들고 있다. 별도 저장 불필요.
  - 프로세스 종료 후 복귀: ViewModel의 `SavedStateHandle`(ui 모듈, Android 의존이므로 core에 두지 않음)에 위 입력을 기본 타입으로 저장한다. core 모델에 Android `Parcelable`을 붙이지 않는다. ui 모듈에 저장용 변환 함수를 둔다.
  - 운전자 조건은 `DriverConditionRepository`(1번 로컬 DB)가 원본이므로 화면 상태에 넣지 않는다.
- **복귀 시 규칙**: 복원한 `TripEstimate`가 `DepartureConfig.etaFreshnessLimit`를 넘었으면 다시 조회한다. 경로 조건은 `ConditionRouteUseCase.check`로 다시 확인한다(부작용 없음). 2번은 requestRoute를 부르지 않는다.
- **내비 목적지는 화면 진입·복귀마다 다시 조회한다**(#5). 사람이 내비 화면에서 목적지를 바꿔도 `onDestinationChanged`는 오지 않는다(§10.5). 이전 `TripEstimate`는 그대로 넘겨도 된다 — 목적지 좌표가 다르면 core가 재사용하지 않는다(리뷰 task-05 MAJOR-1).
