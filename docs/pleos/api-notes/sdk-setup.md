# SDK 설치·공통 설정·요구사항 (+ 알림·스케줄)

- 출처 (모두 확인일 2026-11-09):
  - 공모전 개발 환경 — https://appdevchallenge.auto-hmg.io/guide/development
  - 공모전 FAQ ("어떤 SDK와 API를 사용할 수 있나요?", "개발 환경 요구사항") — https://appdevchallenge.auto-hmg.io/faq
  - SDK 사용 가이드 Overview — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
  - Pleos Connect Emulator 설치 및 실행 (소프트웨어 개발 환경 섹션) — https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
  - Pleos Connect 앱 개발 시작하기 — https://document.pleos.ai/docs/pleos-only/getting-started
  - 프로젝트 생성 및 SDK 사용 신청 — https://document.pleos.ai/docs/pleos-only/create-my-project
  - SDK 릴리즈 노트 — https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
  - SDK API 정책 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api
  - 하드웨어 접근 정책 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture
  - 앱 구조 및 동작 모델 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model
  - 성능 및 리소스 효율 — https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt
  - System Notification (디자인 가이드) — https://document.pleos.ai/docs/connect/guide/docs-design/components/notification/system-notification
- 확인 수준: [문서 확인] + [Playground 확인(2026-11-09)] — SDK 사용 신청 상태는 로그인한 Playground My Project 「계운」 화면으로 확인. 기준 버전은 [문서 재확인(2026-11-09)] — 릴리즈 노트가 여전히 2026-10-19판(변경 없음). 상세: playground-recheck.md

## 요구사항
| 항목 | 값 | 근거 |
|---|---|---|
| minSdk | **API 28 이상** ("차량 사이버 보안 요건(사이닝 키 로테이션·리니지 지원)에 따른 요구사항") | 설치 및 실행 |
| compile/에뮬레이터 이미지 | Android SDK **API 34** + Pleos Connect system image | 시작하기 |
| Gradle | 8.0 이상 | 설치 및 실행 |
| 언어 | Java, Kotlin | 설치 및 실행 |
| Emulator | Pleos Connect Emulator 2.1.0 | 설치 및 실행 |
| 플랫폼 | AAOS(Android Automotive OS) 기반 | SDK Overview |

## Maven 저장소 (모든 SDK 공통)
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
```
저장소 접근에 인증이 필요한지: 문서 예제에 credentials 블록이 없고, 시작하기 문서에는 "의존성을 찾지 못하면 Maven 저장소 접근 권한과 SDK 버전을 확인하세요"라는 문구가 있다. 실제로 **`NaviHelper:3.0.5`는 인증 없이 받았다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 다른 SDK(Vehicle·TTS·STT·FusedLocation)는 확인하지 않았다 [미확인].

## 의존성 좌표 (릴리즈 노트 2026-10-19 기준 버전)
```kotlin
implementation("ai.pleos.playground:Vehicle:2.3.2")
implementation("ai.pleos.playground:NaviHelper:3.0.5")
implementation("ai.pleos.playground:TextToSpeech:3.3.1")
implementation("ai.pleos.playground:SpeechToText:3.3.1")
implementation("ai.pleos.playground:FusedLocation:1.1.3")
```
(시작하기 문서 예제는 `Vehicle:2.3.1`로 적혀 있다. 릴리즈 노트·Importing 페이지는 2.3.2)

NaviHelper 전이 의존성·설정 [aar 확인 2026-11-09 — Maven aar/POM 직접 열람, navi-runtime-check.md]:
- `NaviHelper:3.0.5` → `navi-data:3.0.4`(data·constants 패키지), `kotlin-stdlib:1.9.25`, 런타임 `navi-aidl:3.0.1`
- aar 매니페스트: `min_version_name=2.0.1`(서비스 최소 버전), `<queries>` action `ai.pleos.playground.intent.action.NAVI_START` — 앱 매니페스트 병합 시 참고
- 에뮬레이터의 NaviHelper 서비스는 3.0.4였고 SDK 3.0.5와 조합해 연결·호출 모두 동작했다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md].

## 매니페스트 권한 정리
| SDK | 권한 | 보호 수준 |
|---|---|---|
| NaviHelper | `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP` | Normal |
| FusedLocation | `pleos.car.permission.FUSED_LOCATION` | dangerous |
| TextToSpeech | `pleos.car.permission.TTS_SERVICE` | [불확실 — 문서에 보호 수준 미기재] |
| SpeechToText | `pleos.car.permission.STT_SERVICE` | [불확실] |
| Vehicle | `pleos.car.permission.CAR_*` (조회), `CONTROL_CAR_*` (제어) | [불확실 — 도메인별 Reference 확인 필요] |

- 정책: "API 사용 시 필요한 퍼미션을 앱에 선언해야 하며, 퍼미션은 Pleos App Market과 사전 협의·승인된 범위 내에서만 사용할 수 있습니다. 승인되지 않은 퍼미션을 선언하거나 해당 API를 호출하면 앱 심사를 통과할 수 없습니다." (SDK Overview) → **필요한 권한만 선언.**

## SDK 사용 신청 (Playground My Project)
- 사용 가능(신청 불필요): **Vehicle State, NaviHelper** — [Playground 확인(2026-11-09)] 「계운」 프로젝트에서 Vehicle State 11개 항목, NaviHelper 2개 항목(경로 정보 조회 / 경로 탐색 설정) 모두 "사용 가능".
- 준비 중("Comming Soon", 신청 불가): **FusedLocation, TextToSpeech, SpeechToText, Vehicle Control** — [Playground 확인(2026-11-09)] 각 SDK 페이지의 항목 상태가 "준비 중"이고 신청 버튼이 없다. 사이드바에도 "Coming Soon" 배지가 붙어 있다.
- TTS/STT 서버 모드용 `클라이언트 ID`/`클라이언트 시크릿`은 My Project > 프로젝트 정보 > 키 정보에서 확인한다. [Playground 확인(2026-11-09)] 「계운」 프로젝트에 프로젝트 ID(Key)·클라이언트 ID·클라이언트 시크릿이 **발급되어 있다**(값은 노트에 옮기지 않음). CRN은 Emulator 2.1.0부터 입력 불필요(릴리즈 노트 "CRN 제공 종료"). 단 프로젝트 정보 화면에는 CRN 칸이 아직 표시된다.
- 앱 심사 제출 시 "SDK 추가 권한" 항목에 My Project > 프로젝트 정보 > 프로젝트 ID를 입력한다(앱 심사 제출 가이드 — https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide). 위치정보를 활용하면 위치정보 서비스 이용약관 URL도 조건부 필수다. [문서 확인(2026-11-09)]
- 위 "준비 중" 상태가 에뮬레이터에서 해당 SDK를 못 쓴다는 뜻인지는 [불확실]. 각 SDK 문서는 에뮬레이터 개발을 안내하고 있다.

## 공통 사용 패턴 (모든 SDK)
`객체(context) 생성 → initialize() → 리스너 등록 → 요청 → 콜백으로 결과 → 리스너 해제 → release()`
- 결과는 전부 **콜백/리스너**로 온다. 코루틴·Flow를 지원하는 API는 문서에 없다.
- release 후 재사용 불가 → 새 객체 생성.

## 정책상 설계 제약
- AAOS 표준 우선: 속도·기어 등 표준 데이터는 AAOS Car API, Pleos SDK는 특화 기능만 (SDK API 정책).
- 하드웨어 직접 접근 금지, 모바일 전용 API(Telephony, SMS, 일부 센서, 캘린더, 주소록 등)는 제한될 수 있음 → Optional 구조로 설계(하드웨어 접근 정책).
- 주행 중 네트워크 끊김 대비: 캐싱, 재시도·타임아웃, 오프라인 안내 UI(하드웨어 접근 정책).

## 알림·스케줄 (출발 마감 알림용) — 대부분 [미확인]
- Pleos 문서에서 **AlarmManager, WorkManager, 예약 알림 지원 여부에 대한 언급을 찾지 못했다** [미확인].
  - 재검토 11/09: 문서 사이트 검색창으로 `AlarmManager`, `WorkManager` → 검색 결과 없음. `알람` → 개인정보·보안 심사 가이드 1건뿐(예약 알림과 무관). `예약` → Fleet API·충전 예약 관련만. 여전히 근거 없음.
  - 심사 가이드(운전자 주의 분산 요소 최소화 설계 — https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements)의 알림 문구는 **미디어 앱**(동영상·게임·브라우저) 대상이다: 헤드업 알림 게시 금지, 운전과 관련되지 않은 알림 사용 불가. 우리 앱은 미디어 앱이 아니므로 직접 적용 대상은 아니다. 다만 주행 중 알림은 운전과 관련된 것으로 한정하는 편이 안전하다(설계 판단, 문서 근거 아님).
- 관련 근거:
  - 디자인 가이드에 "System Notification — OS 시스템에서 기본적으로 제공하는 알림" 컴포넌트가 있다. 표준 Android 알림 UI가 존재한다는 근거다. 다만 앱에서 알림을 올리는 API 사용법은 없다.
  - "백그라운드에서 불필요한 상주, 타이머, 폴링 및 네트워크 요청을 최소화"(앱 구조 및 동작 모델), "백그라운드에서의 긴 작업, 불필요한 타이머·폴링은 최소화"(성능 및 리소스 효율).
  - 화면에서 사라진 앱은 종료되지 않고 백그라운드에 남을 수 있다(앱 구조 및 동작 모델).
  - 차량이 꺼진 상태(Suspend)에서 알람이 울리는지에 대한 문서는 없다. "Suspend 모드 - 오디오 포커스 개발 가이드"가 있으나 알람 관련 내용은 확인하지 않았다.
- 결론: 차량 시동이 꺼져 있으면 앱이 실행되지 않을 가능성이 높으므로, "출발 마감 알림"은 **앱이 떠 있는 동안(차량 탑승 후 출발 전)의 화면 안내/TTS**를 기본으로 설계하는 편이 안전하다. 이것은 문서 근거가 없는 설계 판단이다.

## 포트 설계 시사점
모든 Pleos SDK가 "initialize → 콜백" 구조이고 Flow나 suspend를 주지 않는다. 그래서 1번 공통 기반의 Repository가 이 콜백을 `suspend`/`Flow`로 감쌀 것이라고 가정하고 우리 포트도 `suspend`/`Flow`로 정의한다. 1번이 콜백을 그대로 노출하면 우리 어댑터에서 감싼다. SDK마다 미지원(`isSupported`, `checkXxxCapabilities`, 내비 미탑재)이나 사용 신청 "준비 중" 상태가 있다. 그러니 포트마다 "사용 불가" 결과를 표현할 수 있어야 하고, core 유스케이스는 이 경우의 대체 흐름(예: ETA 모름 → 보수적 마감 시각)을 가져야 한다. 예약 알림은 Pleos 문서에 근거가 없으므로 Pleos 포트로 만들지 말고 우리 쪽 `ReminderScheduler` 같은 별도 포트로 분리한다. 실제 구현(AlarmManager 등)은 실차/에뮬레이터 검증 후 정한다. 권한은 심사 대상이므로 `:app` 매니페스트에는 실제로 쓰는 것만 선언한다. 예: NaviHelper `NAVI_ROUTE` + `NAVI_ROUTE_SEARCH`, `FUSED_LOCATION`, `TTS_SERVICE`.
