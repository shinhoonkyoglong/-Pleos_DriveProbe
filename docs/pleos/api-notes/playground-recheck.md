# Playground·문서 재검토 (Claude in Chrome)

- 재검토일: 2026-11-09 · 담당 pleos-sdk-researcher · 대상: 작업 #1 노트 6개 + README
- 방법: 사용자의 실제 Chrome(Playground 로그인 상태)을 Claude in Chrome으로 조작했다. 새 탭에서 **읽기만** 했다(상태를 바꾸는 클릭, 신청, 폼 제출, 키 재발급은 하지 않음). 작업 후 탭은 닫았다.
- 비밀값 처리: 클라이언트 ID·시크릿은 **존재 여부만** 기록하고 값은 옮기지 않았다. 팀원 이메일도 기록하지 않았다.

## 1. 확인한 URL·화면

| 화면 | URL | 비고 |
|---|---|---|
| My Project 목록 | https://developers.pleos.ai/playground/console/projects | 프로젝트 1개 「계운」, 역할 Developer |
| 프로젝트 정보 | `.../console/projects/<프로젝트ID>/dashboard` | 카테고리 "모빌리티 서비스". 키 정보: 프로젝트 ID(Key), 클라이언트 ID, 클라이언트 시크릿, CRN 칸이 있음 |
| SDK > Vehicle State / NaviHelper / FusedLocation / TextToSpeech / SpeechToText / Vehicle Control | `.../permissions/sdk/<SDK별 ID>` | 항목별 상태표(아래 2절) |
| API 권한 관리 > Fleet / Vehicle Data / API 신청 내역 | `.../permissions/rest/...` | 아래 3절 |
| 팀 관리 | `.../members` | 5명 활성(Owner 1, Developer 4). 우리 기능과 무관 |
| Discover > Resources & Emulator | https://developers.pleos.ai/playground/discover/resources | SDK 소개, Emulator·Devbox 소개 |
| Docs > Pleos Connect | https://document.pleos.ai/docs/pleos-only/getting-started | **기존 노트와 같은 문서 사이트**(document.pleos.ai). 별도 신버전 없음 |
| Publish > App Submission / App Review Guidelines | https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide , `.../distribute/app-review-guide` 및 하위 상세 가이드 | 기존 노트에 없던 페이지 |
| 문서 재확인 | SDK 릴리즈 노트, Emulator 릴리즈 노트, Virtual Console 가이드, NaviHelper Routes, `requestTrip`, `TripInfo`, FusedLocation `addProximityAlert`, TTS App registration, 주행 규제 개발 가이드, 지역별 주행 규제 정책, Maps/Navigation 앱 개발 가이드 | 문서 사이트 검색창으로 키워드 검색도 함(4절) |

참고: Playground 상단 Docs 메뉴는 Pleos Connect / Pleos Fleet 두 항목이다. Pleos Connect는 document.pleos.ai로 이동한다. 문서 사이트 헤더는 비로그인("로그인" 버튼)으로 표시됐지만 문서는 공개라 내용 차이는 없었다.

## 2. 「계운」 프로젝트 SDK 상태표 [Playground 확인 2026-11-09]

> 감독관 재확인(2026-11-09): 6개 SDK 화면을 다시 열어 아래 표와 모두 일치함을 확인. 사이드바에 FusedLocation·TTS·STT·Vehicle Control "Coming Soon" 배지. 상세는 reviews/task-01b-playground-recheck.md "감독관 직접 확인 2"

| SDK | 항목 수 | 상태 | 우리 파트 관련 |
|---|---|---|---|
| Vehicle State | 11 | 전부 **사용 가능** | 속도·기어 항목 없음(주행 정보 = 브레이크 페달·드라이브 모드·크루즈·주행 거리). 주행 판단은 계속 AAOS CarUxRestrictions |
| NaviHelper | 2 (경로 정보 조회 / 경로 탐색 설정) | 전부 **사용 가능** | 경로·ETA·현재 위치(보조)의 유일한 즉시 사용 가능 SDK |
| FusedLocation | 1 (정밀 위치 정보 조회) | **준비 중** (신청 버튼 없음) | 현재 위치·ProximityAlert 주 소스 후보지만 사용 가능 상태 아님 |
| TextToSpeech | 1 | **준비 중** | 출발 안내 음성 |
| SpeechToText | 1 | **준비 중** | 사용 안 함 |
| Vehicle Control | 8 | 전부 **준비 중** | 사용 안 함 |

- 클라이언트 ID·클라이언트 시크릿: **발급되어 있음**(값 미기록). TTS/STT SERVER·HYBRID 모드 등록용.
- CRN: 프로젝트 정보 화면에 칸이 남아 있음. Emulator 2.1.0 릴리즈 노트는 "CRN 제공 종료" → 우리는 쓰지 않는다.
- "준비 중"에 대해 사용자가 할 수 있는 신청 동작은 화면에 없었다. 즉 **사용자 확인 필요 항목이 아니라 플랫폼 대기 상태**다.

## 3. 프로젝트의 다른 리소스

| 리소스 | 상태 | 출발 전 기능에 의미 |
|---|---|---|
| API 권한 관리 > Fleet (차량 관제) | 제휴 계약 필요 | 없음 |
| API 권한 관리 > Vehicle Data (REST, 10개 API: Get Location Info, Get Speed Data 등) | 미신청("신청하기" 버튼 있음). **유럽 지역에서만 이용 가능**하다고 명시 [Playground 확인 2026-11-09, 감독관 재확인: 원문 "이 API는 현재 유럽 지역에서만 이용 가능합니다."] · 공모전 FAQ의 사용 가능 목록(SDK 5종)에도 없음 | 서버 측 차량 위치 API지만 지역 제한으로 사용 불가. 신청하지 않음 |
| API 신청 내역 | 신청한 API 없음 | — |
| 팀 관리 | 5명 활성 | 없음 |

## 4. 기존 노트 대비 변경 / 신규 / 여전히 미확인

### 정정
- eta-location.md: 이전 "`requestTrip` Reference 설명은 'Requests trip.' 한 줄뿐" → 현재 렌더링 페이지에는 "onTripInfo 같은 이벤트가 리스너로 **전달될 수 있다**. 실제 이벤트는 NaviProvider SDK로 구현된 Navi 앱이 이 요청을 어떻게 처리하느냐에 따라 달라진다"는 문장이 더 있다. 이 문장은 navi-route.md에는 이미 다른 API의 공통 문구로 적혀 있었다. 결론은 같다. `onTripInfo` 도착 보장은 없으므로 타임아웃이 필수다.

### 재확인(변경 없음)
- SDK 버전: 릴리즈 노트 2026-10-19판 그대로(Vehicle 2.3.2 · NaviHelper 3.0.5 · TTS/STT 3.3.1 · FusedLocation 1.1.3). Emulator v2.1.0 그대로.
- `RouteOption` 7개(RECOMMENDED, SHORT_DISTANCE, SHORT_TIME, FREE "무료 도로 우선 경로", HIGHWAY, COMFORTABLE, SCHOOL_ZONE). 회피 옵션 없음.
- `TripInfo(distance: Int, eta: Int, estimatedSocAtDest: Int, energyConsumption: Double, summary: TripSummary)`. `eta` = "Estimated Time of Arrival (ETA) in seconds" → 의미는 여전히 모호.
- `requestTrip` 권한 `NAVI_ROUTE`, Protection level Normal.
- `addProximityAlert(request, listener)`: alert ID는 `onRegistered`, 잘못된 요청은 `onError`. 권한 `FUSED_LOCATION` dangerous.
- TTS: ON_DEVICE만 쓰면 앱 등록 불필요. SERVER와 HYBRID(서버 합성 사용 시)는 `registerApp(clientID, clientSecret, listener)` 후 `onConnected()`.
- 주행 규제: AAOS `CarUxRestrictionsManager.registerListener` → `onUxRestrictionsChanged`. DO 선언 방법은 여전히 문서에 없음.
- Virtual Console: 카드 47개. 위치·차량 속도 카드는 없다.

### 신규
- Playground SDK 상태표(2절). FusedLocation·TTS가 "준비 중"이라는 것은 이제 문서 설명이 아니라 **우리 프로젝트 화면에서 직접 확인한 사실**이다.
- 클라이언트 ID·시크릿 발급 완료(값 미기록).
- Vehicle Data REST API는 유럽 한정이라 대안이 되지 않는다.
- 앱 심사(Publish) 문서:
  - 심사 제출 항목에 "위치정보 활용", "앱 접근 권한", "SDK 추가 권한(프로젝트 ID 입력)"이 있다. 위치정보를 쓰면 위치정보 서비스 이용약관 URL이 조건부 필수다.
  - 앱 리뷰 가이드는 운전자 주의 분산과 사이버보안을 중점 심사한다고 명시한다. "운전과 관련되지 않은 알림 사용 불가"와 헤드업 알림 금지는 **미디어 앱** 조항이다.
  - 백그라운드에서 복귀하면 마지막 사용 상태를 복원해야 한다(앱 기능 및 성능 안정성).
  - 과도한 성능 점유와 배터리 소모에 유의해야 한다.
- Discover: NaviHelper를 "Pleos Connect에 탑재된 기본 내비게이션 앱과 연동"하는 API로 설명한다. Devbox(17인치, GPS 포함, 실차 기본 앱 탑재)가 실차 유사 테스트 수단으로 소개돼 있다.
- 문서 검색 결과(근거 없음 확인): `AlarmManager`, `WorkManager`, `distractionOptimized`, `모의 위치` → 0건. `알람` → 개인정보·보안 심사 가이드 1건, `예약` → Fleet·충전 예약만.

### 여전히 미확인(에뮬레이터 실행 필요)
1. 에뮬레이터에 NaviHelper 요청을 처리하는 기본 내비 앱이 있는지 → 해소: navi-runtime-check.md(11/09)
2. `requestTrip`의 부작용(안내 시작 여부)과 `eta` 의미 → 해소: navi-runtime-check.md(11/09)
3. `requestRoute(FREE)` 후 `onRouteStarted.tollFare`, 빈 poiId 허용 여부 → 해소: 빈 poiId 허용, **FREE 요청은 무시됨(tollFare 700원, routeOption=RECOMMENDED)** [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절] (11/09 이후 갱신)
4. Extended Controls > Location → FusedLocation 전달 여부. "준비 중" 상태에서 `isSupported()`·권한 팝업 동작 → 일부 해소: geo fix 값을 FusedLocation 서비스 로그가 받음, SDK `onChanged`는 미확인(navi-runtime-check.md, 11/09)
5. TTS ON_DEVICE가 "준비 중" 상태에서도 발화되는지
6. Gradle sync로 의존성이 실제로 해결되는지 → 일부 해소: `NaviHelper:3.0.5`는 인증 없이 받음, 다른 SDK 미확인(navi-runtime-check.md, 11/09)
7. 예약 알림(AlarmManager/WorkManager) 동작과 시동 off 시 동작
8. DO 선언 방법(AAOS `distractionOptimized` 메타데이터인지)

## 5. 포트 설계(#3)에 영향이 있는 변경 요약
- **시그니처 변경은 없다.** RouteOption 단일 enum, 콜백 구조, TripRequest에 옵션 없음, nullable 위치는 모두 그대로다.
- 결정을 강화하는 사실은 세 가지다.
  - (a) FusedLocation·TTS가 프로젝트에서 **"준비 중"으로 확정**됐다. 위치 포트와 TTS 포트는 Unavailable 결과가 기본 경로일 수 있다. 정정(11/09 리뷰 반영, 감독관 결정으로 통일): 위치 실제 소스 후보 1순위 NaviHelper `getCurrentLocationInfo`(「계운」 사용 가능), 2순위 FusedLocation(현재 '준비 중'). 단 둘 다 에뮬레이터에서 동작 미확인 — NaviHelper는 에뮬레이터 내 Navi 앱 존재가 전제. 이 우선순위는 설계 판단이며 문서 근거 아님. MVP 시연은 `departure.adapter=fake` 기준. TTS가 없으면 화면 안내로 대체하는 흐름이 core에 필요하다. → 해소/일부 해소: NaviHelper `getCurrentLocationInfo`는 에뮬레이터에서 응답함, 단 위치는 내비 기본 위치로 고정(navi-runtime-check.md, 11/09). FusedLocation은 미확인
  - (b) `requestTrip` 콜백 도착이 Navi 앱 구현에 달렸다고 문서가 명시한다 → 어댑터 타임아웃과 "ETA 모름" 상태가 필수다(리뷰 MAJOR-2·4와 일치).
  - (c) 예약 알림은 이번에도 근거가 없다 → `ReminderScheduler`를 Pleos 포트와 분리하는 결정을 유지한다.
- 심사 관점 추가: 위치를 쓰면 위치정보 약관 URL이 필요하다. 매니페스트 권한은 실제 사용분만 선언한다. 백그라운드 복귀 시 상태 복원이 필요하다(UI 상태 저장, #5/#7 참고).

## 6. 사용자 확인 필요 항목
- 리뷰 task-01의 "사용자가 직접 확인해야 할 항목" 대비:
  - **5번(Playground SDK 상태) — 해소.** FusedLocation·TTS·STT·Vehicle Control은 "준비 중", Vehicle State·NaviHelper는 "사용 가능"이다. 5번 중 "TTS ON_DEVICE가 등록 없이 발화되는지"는 에뮬레이터 실행이 필요해 남는다.
  - 1~4, 6번: 에뮬레이터 또는 Gradle 실행이 필요해 **그대로 남는다**.
- 새로 생긴 확인 항목:
  - "준비 중" SDK의 사용 시점을 공모전 주최 측에 문의할지 결정(사용자 또는 팀장 판단). Playground 화면에는 신청 수단이 없다.
  - Devbox를 대여하거나 사용할 수 있는지(공모전 안내 확인 필요).
  - Vehicle Data API는 유럽 한정이므로 **신청하지 말 것**("신청하기" 버튼이 있으나 누르지 않았다).
