# '계속운전' 앱 화면 (`ui/kd`)

앱 첫 화면부터 주행 중·주행 후까지의 화면 뼈대와 디자인 시스템. 판단 로직은 하지 않고 각 파트 모듈을 그대로 쓴다.

## 화면 구성

| 화면 | 파일 | 데이터 | 상태 |
|---|---|---|---|
| 홈 | `HomeScreen.kt` | 2번 출발 전 카드(슬롯), Room `reaction_scores` | 2번 카드는 슬롯만, 지금은 자리 표시 카드 |
| 출발 전 컨디션 체크 | `ReactionCheckScreen.kt`, `ReactionCheckViewModel.kt` | 3번 `ReactionSession`·`ReactionEvaluator`·`ReactionGate` | 동작함. 결과 → `engine.setPreDrive()` + Room 저장 |
| 주행 중 | `DrivingScreen.kt` | `ConditionModule.engine.state`, 개입 이력 | 동작함. `engine.isDriving`이면 자동 전환, 누르는 곳 없음 |
| 내 기록 | `RecordsScreen.kt` | Room 반응 체크·개입 이력 | 운전 횟수·조건 준수율은 예시값(저장하는 곳 없음) |
| 가족 안심 요약 | `SummaryScreens.kt` | 예시값 | 목업(1차 제출 범위) |
| 조건 해제 리포트 | `SummaryScreens.kt` | 예시값 + 반응 체크 추이 | 목업(1차 제출 범위) |
| 운전 조건 설정 | 2번 `DriverConditionSettingsScreen` | — | 기존 화면을 그대로 띄움(밝은 테마) |
| 개발자 도구 | 1번 `SampleApp`(API 탐색기) | — | 홈 오른쪽 아래 [개발자 도구] |

화면 이동은 `KdAppViewModel`(`KdPage`) 하나가 맡는다. 주행 중에는 주행 화면에서 다른 화면으로 가지 않는다.

## 팀 결정을 반영한 표시 규칙

- **반응 체크는 강요하지 않는다**(팀 의견 "시동 후 체크는 시간을 뺏기는 느낌", 3번 파트 추천안 ①+③). 판단은 `CheckAdvice.kt`의 `checkAdvice()`(순수 함수, `CheckAdviceTest` 7건):
  | 상황 | 홈 '오늘 컨디션' 카드 |
  |---|---|
  | 오늘 체크함 | 결과(평소 대비) |
  | 지난 주행 최고 단계 CAUTION 이상, 또는 최근 24시간 개입 2·3단계 | "출발 전에 1분 체크할까요?" + [체크하기] [바로 출발] |
  | 밤·새벽(19시~6시 전, 한국 시간) | 같음(문구만 "어두운 시간이라…") |
  | 그 밖 / 오늘 [바로 출발]을 누름 | "바로 출발해도 괜찮아요" 한 줄, 버튼 강조 없음 |
- **이유 문구(`reasons`)는 피로 단계 MILD 이상일 때만** 주행 화면에 한 줄 보여 준다(NORMAL에서는 숨김).
- **재생(시연) 모드면 "시연 모드" 꼬리표를 반드시 보여 준다**: 홈, 반응 체크(안내·진행·결과), 주행 화면.
- 시연은 `DEMO_WITH_SEED` 모드로 한다.

## 내비 예시 데이터 (debug 빌드 전용)

에뮬레이터 내비가 "서비스 연결 중"에 멈추면 NaviHelper 는 연결은 되지만 조회 응답이 오지 않아 2번 화면이 전부 "응답이 없어요"가 된다.
그래서 `data/repository/DemoNaviFallbackRepository.kt`가 1번 `NaviHelperRepositoryImpl`을 감싼다(`DriveProbeApplication`에서 `BuildConfig.DEBUG`일 때만).

- 바꾸는 것은 조회 4개(현재 위치·목적지·경로 상태·ETA)뿐. **실제 내비에 먼저 묻고, 실패했을 때만** 예시 값을 쓴다. 실패 후 60초는 실제 조회를 건너뛴다.
- 예시 값 = 2번 실측값: 수정구 시흥동(37.4127, 127.0947) → 서울역 23.8km·약 46분, 추천 경로·통행료 700원
- 예시 값을 쓰는 동안 홈 머리와 '출발 전' 상세 화면에 **"내비 예시 데이터"** 꼬리표가 붙는다.
- 주의: 예시 위치를 운전 조건 화면에서 반경 중심으로 저장하면 그 값이 Room 에 남는다(에뮬레이터 내비 기본 위치와 같은 값).
- release 빌드에는 들어가지 않는다. logcat 태그 `DemoNavi`.

## 디자인 기준 (`KdTheme.kt`)

- 어두운 바탕 `#0B0D12`, 면 `#161A22`, 강조 `#8AB4FF`(2번 화면 버튼색과 같은 계열)
- 본문 24~26sp, 제목 32sp 이상, 누르는 곳 높이 96dp 이상
- 상태는 `KdTone`(색 + 기호 ✓ ! !! !!! ? + 글자)로만 표시한다. 색만으로 구분하지 않는다
- 저장된 값이 아닌 예시값에는 `KdTag("예시 …")`를 꼭 붙인다

## 다른 파트 화면 끼우기

`MainActivity`의 `KeepDrivingApp(...)` 인자로 넣는다.

```kotlin
KeepDrivingApp(
    ...,
    // 2번 feat/departure-home 병합 후
    departureCard = { modifier, onOpen -> DepartureHomeCard(model = departureModel, onOpen = onOpen, modifier = modifier) },
    departureDetail = { onBack -> DepartureDetailScreen(model = departureModel, onBack = onBack) },
)
```

4번 `feat/hvac-control`의 개입 상태 화면은 개발자 도구 쪽(디버그)으로 두고, 운전자에게는 `DrivingScreen`의 한 줄 안내와 내 기록의 "최근 도움 기록"으로 보여 주는 것을 제안한다.

## 에뮬레이터 확인 (2026-11-11, Pleos Connect v2.1.0)

- 홈 → 컨디션 체크(P 기어에서 시작 가능) → 1분 진행 → 결과 화면
- 내 기록 → 가족 안심 요약 / 조건 해제 리포트
- 시연 주행: `adb shell am broadcast --user 10 -a ai.pleos.condition.MODE --es mode DEMO_WITH_SEED` → 주행 화면으로 자동 전환, 끝나면 홈 + "운전 수고하셨어요" 요약
