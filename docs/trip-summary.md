# 주행 요약 (5번 주행 후) — 화면 이식 가이드

주행이 끝나면 기록을 저장하고 요약을 보여주는 기능입니다. 요약은 **[가족에게 보내기]** 버튼으로 가족에게 보낼 수 있고,
쌓인 기록으로 **운전 리포트**(최근 8주, 그래프)와 **최근 7일 집계**(운전 횟수·조건 준수율 등, 화면 없이 값만)도 만듭니다.
**데이터(기록·요약 계산)** 와 **화면 조각**이 나뉘어 있어서, 다른 화면의 한 섹션에 그대로 넣을 수 있습니다.

## 1. 구조

결합도를 낮추려고 네 층으로 나눴습니다. 팀원 화면은 필요한 층만 골라 쓰면 됩니다.

| 층 | 무엇 | Android·DB 의존 | 팀원 UI가 쓰는 법 |
| --- | --- | --- | --- |
| ① 계산 | `TripSummaryContent`, `report/`, `share/FamilyShareText` | 없음(순수 Kotlin) | 값만 가져다 자기 디자인으로 그림 |
| ② 그리기 | `TripSummaryView`, `TripHistoryList`, `DrivingReportView`, `FamilyShareButton` | Compose만(값·콜백만 받음) | 자기 화면에 조각만 넣음 |
| ③ 연결 | `TripSummarySection`, `TripHistorySection`, `DrivingReportSection`, `FamilyShareSection`, `TripRecordsSection` | `TripModule` | 한 줄로 통째로 넣음 |
| ④ 임시 | `TripSummaryActivity`, `TripRecordsEntryButton` | 화면·Manifest | 이식 후 삭제 |

- 아래 층은 위 층을 모릅니다(①은 Compose를, ②는 TripModule을 모름).
- `TripModule`이 화면 클래스를 아는 곳은 `summaryPresenter` 기본값 **한 줄**뿐입니다(④를 지울 때 그 줄만 고침).
- 보내는 방법(서버·문자 등)은 `FamilyShareSender` 하나만 갈아 끼우면 됩니다.

```
trip/
├── TripModule.kt            진입점 + 공개 API (observeSummary / observeHistory / observeDrivingReport / observeWeekStats / shareToFamily / tripEnded)
├── TripRecorder.kt          3번 엔진 구독 → 주행 시작·종료 감지 → 저장
├── TripAccumulator.kt       주행 1회 집계(순수 로직)
├── TripCheckpoint.kt        주행 중 중간 저장·복구 판단(앱이 종료돼도 주행을 잃지 않게)
├── TripSummaryContent.kt    요약 문구·항목 계산(순수 로직) + TripHistoryItem
├── report/                  여러 주행을 모은 집계(순수 로직)
│   ├── TripPeriodStats.kt        기간 집계(횟수·시간·피로·휴식 권고·조건·반응)
│   ├── DrivingReport.kt          운전 리포트(주별 집계·변화 문장·운전 조건)
│   ├── ReportCharts.kt           그래프 3개의 내용(화면·HTML 공용)
│   ├── DrivingReportHtml.kt      인쇄용 HTML 한 장(SVG 그래프)
│   └── DemoHistoryGenerator.kt   시연용 가상 기록(8주)
├── share/FamilyShare.kt     가족에게 보낼 글(FamilyShareText) + 보내는 통로(FamilyShareSender, 지금은 Mock)
├── source/                  다른 파트 값 출처(지금은 일부 Mock) + 2번 운전자 조건 읽기
├── data/                    trip_local.db (공용 AppDatabase와 분리)
└── ui/
    ├── TripSummaryComponents.kt  값만 받아 그리는 조각: TripSummaryView, TripHistoryList
    ├── TripSummarySections.kt    DB에서 읽어 그리는 섹션: TripSummarySection, TripHistorySection
    ├── TripReportComponents.kt   값만 받아 그리는 조각: DrivingReportView
    ├── FamilyShareButton.kt      [가족에게 보내기] 버튼(값·콜백만 받음)
    ├── TripCharts.kt             주별 그래프(막대·선) Composable
    ├── TripReportSections.kt     DB에서 읽어 그리는 섹션: DrivingReportSection
    ├── TripSummaryScreen.kt      TripRecordsSection(요약·지난 기록·리포트 묶음) + 단독 화면 껍데기
    ├── KdTripSection.kt          "계속운전" 앱 디자인(ui/kd)으로 그린 주행 요약 카드 — 내 기록 화면에 들어감
    ├── KdDrivingReportSection.kt "계속운전" 앱 디자인으로 그린 운전 리포트 — 리포트 화면에 들어감
    ├── TripSummaryActivity.kt    [임시] 단독 화면 Activity
    └── TripRecordsEntryButton.kt [임시] 개발자 도구 헤더의 [주행 기록] 버튼
```

## '계속운전' 앱 화면에 연결된 곳 (팀원 UI, main PR #4)

**홈 → [내 기록]** 화면에 5번 데이터가 들어가 있습니다. 데이터는 5번, 모양은 팀원 디자인(`Kd*` 부품)입니다.

| 위치 | 내용 |
| --- | --- |
| "이번 주" 카드 | 운전 횟수·조건 준수율 = `rememberTripWeekNumbers()`(최근 7일 주행 기록). 조건 준수가 임시값이면 "조건 준수는 임시값", 시연 기록이면 "시연 기록" 꼬리표 |
| 그 아래 "최근 주행" | `KdTripSection()` — 주행 요약(한 줄 요약 + 항목 5개) + [가족에게 보내기] + 지난 주행 5건(누르면 위 요약이 그 주행으로 바뀜) |
| [운전 리포트] 버튼 → 리포트 화면 | `KdDrivingReportSection()` — 한 줄 변화, 숫자 4개(운전한 주·운전 횟수·조건 준수율·평균 피로), 주요 변화, 주별 그래프 3개(누르면 그 주 기록), 운전 조건, 안내문. 팀원의 "출발 전 반응 속도 추이" 그래프는 그 아래 그대로 |

- 팀원 파일 변경
  - `ui/kd/RecordsScreen.kt` 약 12줄: 예시값 `KdDemoData` 대신 5번 값, `KdTripSection()` 한 줄, 버튼 "조건 해제 리포트" → "운전 리포트 / 8주 동안의 변화를 그래프로"
  - `ui/kd/SummaryScreens.kt`의 `ReportScreen`: 제목 "운전 리포트", 예시 숫자 카드·월별 표·"목업" 꼬리표·"재평가 때 낼 수 있어요" 문구를 빼고 `KdDrivingReportSection()` 한 줄. 반응 속도 그래프·PDF 버튼은 그대로(`KdDemoData.months`, `TableRow`는 이제 안 쓰임)
  - 주석 `[5번]`으로 표시
- 그대로 둔 것: 출발 전 반응 속도 그래프, [가족 안심 요약] 버튼(주간 자동 발송 vs 주행마다 보내기 — 팀 합의 후 정리), 최근 도움 기록
- `KdTripSection.kt`·`KdDrivingReportSection.kt`는 팀원 디자인 부품(`Kd`, `KdCard`, `KdStat`, `KdStatusBadge`, `KdButton`, `KdTag`, `KdTone`, `formatDateTime`)을 씁니다. 그쪽 이름이 바뀌면 이 두 파일만 고치면 됩니다.
- 팀원 화면의 "휴식 권고"는 개입 이력 중 DANGER만 셉니다(5번 요약은 CAUTION 이상). 기준 합의 필요

## 지금 임시로 붙어 있는 곳 (팀원 UI 이식 후 정리)

| 위치 | 내용 | 이식 후 |
| --- | --- | --- |
| `ui/screen/ApiCatalogScreen.kt` 헤더(개발자 도구) | `TripRecordsEntryButton()` 호출 2줄(넓은/좁은 화면), 주석 `[5번 임시]`. main의 "← 홈" 버튼 옆 | 두 줄 삭제 |
| `AndroidManifest.xml` | `TripSummaryActivity` 등록 | 주행 종료 자동 표시를 팀원 화면이 맡으면 삭제 |
| `trip/TripModule.kt` | `summaryPresenter` 기본값(임시 Activity 띄우기) 한 줄 | 팀원 화면(내 기록) 이동으로 바꾸거나 `null` |
| `trip/ui/TripSummaryActivity.kt`, `TripRecordsEntryButton.kt` | 임시 호스트·입구 | 삭제 가능 |

개발자 도구 헤더 **[주행 기록]** → 지난 기록 목록 → 주행 선택 → 요약(임시 단독 화면).
요약 아래 **[가족에게 보내기]**, 그 아래 **[지난 기록] [운전 리포트]** 로 각 페이지에 가고, [요약으로]나 뒤로 가기로 돌아옵니다.

준비: `Application.onCreate`에서 `TripModule.init(this)`가 불려 있어야 합니다(이미 들어가 있음).

## 2. 다른 화면에 넣기

### 가장 쉬운 방법 — 한 줄

```kotlin
Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
    TripRecordsSection()   // 최근 주행 요약 + 가족에게 보내기 + 지난 기록·운전 리포트 이동까지 포함
}
```

`TripRecordsSection(initialTripId = id)`로 특정 주행부터, `startPage = TripRecordsPage.REPORT`처럼 원하는 페이지부터 보여줄 수 있습니다.

### 요약·목록을 따로 배치하고 싶을 때

```kotlin
var selectedTripId by rememberSaveable { mutableStateOf<String?>(null) }

Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
    TripSummarySection(tripId = selectedTripId, showTitle = false) // null = 가장 최근 주행
    TripHistorySection(onSelect = { selectedTripId = it })         // 지난 기록 목록
}
```

- 섹션은 **스스로 스크롤하지 않습니다.** 스크롤은 호스트 화면이 맡아 주세요.
  `LazyColumn`이면 섹션 하나를 `item { }` 하나에 넣으면 됩니다.
- 색·글꼴은 호스트의 `MaterialTheme`를 따릅니다. 좋음/확인/참고 상태색만 고정입니다.
- `showTitle = false`이면 "주행 요약" 제목을 숨깁니다(호스트 섹션에 제목이 있을 때).

조각을 따로 넣을 때:

```kotlin
TripSummarySection(showFamilyShare = false)  // 요약만(보내기 버튼 없이)
FamilyShareSection(tripId = null)            // [가족에게 보내기]만 다른 곳에(null = 가장 최근 주행)
DrivingReportSection(weeks = 8)              // 운전 리포트(최근 8주)
```

### 값을 직접 다루고 싶을 때 — ViewModel에서 Flow 받기

```kotlin
val summary: Flow<TripSummaryContent?> = TripModule.observeSummary(tripId)    // null = 최근
val history: Flow<List<TripHistoryItem>> = TripModule.observeHistory(limit = 30)
val week: Flow<TripPeriodStats> = TripModule.observeWeekStats()                // 최근 7일: tripCount, complianceRate, restAdviceCount …
val report: Flow<DrivingReport> = TripModule.observeDrivingReport(weeks = 8)
val html: String = TripModule.drivingReportHtml()                          // suspend, 인쇄용
val result: ShareResult = TripModule.shareToFamily(tripId)                 // suspend, 버튼을 눌렀을 때만
```

> 팀원 UI(`feat/app-ui`)의 "이번 주 운전 N회 · 조건 준수 N% · 휴식 권고 N회" 예시값(`KdDemoData`)은
> `observeWeekStats()`의 `tripCount`, `complianceRate`, `restAdviceCount`로 바꿔 끼울 수 있습니다.

화면은 `TripSummaryView(content)`, `TripHistoryList(items, onSelect)`, `DrivingReportView(report)`,
`FamilyShareButton(state, onClick)`으로 그리면 됩니다(DB를 모르는 순수 Composable).

### 주행이 끝났을 때 섹션으로 이동하기

기본값으로는 주행이 끝나면 임시 단독 Activity가 뜹니다(`TripModule.summaryPresenter`). 섹션으로 옮겼다면 바꾸세요.

```kotlin
// 방법 1: 주행이 끝나면 팀원 화면으로 이동
TripModule.summaryPresenter = TripModule.SummaryPresenter { context, tripId -> /* 팀원 화면 이동 */ }

// 방법 2: 끄고 이벤트만 받기
TripModule.summaryPresenter = null
LaunchedEffect(Unit) {
    TripModule.tripEnded.collect { tripId -> /* 요약 섹션으로 이동, selectedTripId = tripId */ }
}
```

섹션으로 완전히 옮기면 `AndroidManifest.xml`의 `TripSummaryActivity` 등록 한 줄은 지워도 됩니다(디버그용으로 남겨도 무방).

## 3. 요약 항목과 데이터 출처

| 항목 | 출처 | 상태 |
| --- | --- | --- |
| 운전 시간 | 5번 기록 + 3번 연속 주행 시간 | 실제 |
| 피로 점수(최고·평균, 0~100) | 3번 컨디션 엔진 | 실제 |
| 휴식 권고(CAUTION 이상)·응답 | 4번 자동 개입 이력 | **실제 연동** (`InterventionRestAdviceSource`) |
| 출발 전 반응 | 3번 반응 체크 | 엔진 값이 있으면 실제, 없으면 **Mock** |
| 운전 조건 준수 | 2번 조건 판정 | **Mock** (2번 판정 함수 생기면 교체) |

- Mock 값은 화면에 **"임시"** 로 표시되고, DB에 출처(`MOCK`/`REAL`)가 함께 저장됩니다.
- 피로 판정 구간(60km/h 이상 직진)이 한 번도 없으면 "정상"이 아니라 **"판정 없음"** 으로 보여줍니다.
- 위치(GPS)는 저장하지 않습니다.

### 언제 저장되나 (중간 저장)
- 기어 D·R·N → 주행 시작, P → 주행 종료. 끝나는 순간 `trip_local.db`에 1건 저장합니다.
- 주행 중에는 **15초마다 중간 저장**합니다(`TripCheckpoint.kt`, SharedPreferences `trip_checkpoint`. DB 구조는 그대로).
  주행 중에 앱 프로세스가 종료돼도(백그라운드 정리·오류) 다시 켜질 때 처리합니다.
  - 다시 켜졌을 때 **아직 주행 중이고 10분 안**이면 → 같은 주행으로 이어서 기록(주행 ID·시작 시각 유지)
  - 그 사이 P가 됐거나 10분이 지났으면 → **마지막 중간 저장 시각에 끝난 주행**으로 저장(요약 화면은 자동으로 띄우지 않음, 지난 기록에는 보임)
  - 시연 재생 주행은 다시 켜면 재생이 처음부터라 이어 붙이지 않고 마무리 저장
- 잃을 수 있는 것: 마지막 중간 저장 뒤 ~ 종료 사이(최대 15초), 종료돼 있던 동안의 주행.
- 주행 중 엔진 모드(실제↔시연)를 바꾸면 그 주행은 버립니다.

## 4. 가족에게 보내기 · 운전 리포트

### 가족에게 보내기
- 주행 요약 아래 **[가족에게 보내기]** 버튼. **운전자가 눌렀을 때만** 보냅니다(자동 전송 없음).
- 보내는 글(`FamilyShareText`)은 요약과 같은 항목의 값뿐입니다. 위치·경로·출발/도착 시각은 넣지 않고 날짜만 씁니다.
- **지금은 서버가 없어 실제로 보내지 않습니다**(`MockFamilyShareSender`: 로그에만 남기고 화면에 "임시"로 표시).
  서버·문자 등 방법이 정해지면 `FamilyShareSender`를 구현해 `TripModule.familyShareSender`에 넣으면 됩니다.
  (Pleos에서 문자·메시지를 보낼 수 있는지는 확인 필요)
- 팀원 UI 설계(매주 월요일 9시 주간 요약 자동 발송)와 방식이 다릅니다. 어느 쪽으로 갈지 팀 합의 필요.
  주간 숫자는 `observeWeekStats()`로 이미 받을 수 있습니다.

### 운전 리포트 (최근 8주, 그래프)
- 여러 주의 운전을 **주별 그래프 3개**와 **변화 문장**으로 보여주는 기간 요약입니다. 좋다/나쁘다 등급은 매기지 않습니다.
  1. 주별 운전 횟수(막대) 2. 주별 평균 피로 점수(선, 판정 없는 주는 끊김) 3. 주별 확인할 일(피로 신호·조건 못 지킴, 묶음 막대)
- 맨 위 한 문장은 피로 점수 변화(앞 4주 → 최근 4주)로 정합니다. 막대·점을 누르면 그 주의 기록이 위 칸에 나옵니다.
- 운전 조건은 2번 운전자 조건 설정 값을 **읽기만** 합니다(야간 금지·고속도로 회피·반경).
- 조건부 면허는 아직 시행 전이라 "조건 해제 근거"로 내세우지 않습니다. 인쇄하면 가족·의사와 이야기할 때 참고 자료로 쓸 수 있습니다.
- 그래프 색은 검증된 범주형 팔레트 1·2번(파랑·주황, 색약 구분 통과)만 씁니다.
- 인쇄용 HTML 예시: [trip-report-sample.html](trip-report-sample.html) (시연용 가상 기록, 브라우저에서 열어 인쇄 → PDF)

### 시연 기록과 실제 기록
운전 리포트·최근 7일 집계는 둘을 **섞지 않습니다**. 시연용 8주 기록을 넣으면 시연 기록만, 지우면 실제 기록만 씁니다(앱을 다시 켜도 유지).
시연 재생(DEMO 모드) 기록도 시연 기록으로 들어갑니다.

### 남은 일
- 서버(1번 서버 골격 이후): 주간 요약을 보내는 API, 가족 웹(6자리 초대 코드), 리포트 HTML 내려받기
- 2번 연동 후 고속도로·반경 조건 판정(지금은 "판정 안 함")

## 5. 확인 방법 (debug 빌드)

```bash
# 시연 재생(약 4분) → 끝나면 기록 저장·요약 표시
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.condition.MODE --es mode DEMO_WITH_SEED
# 진행 중인 주행 강제 종료
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.END
# 최근 요약 단독 화면 열기
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.SHOW
# 시연용 8주 기록 넣기 / 지우기 (integrated true = 모든 파트 연동 후 모습)
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.SEED_HISTORY
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.SEED_HISTORY --ez integrated true
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.CLEAR_HISTORY
# 리포트 HTML 만들기(경로는 로그에) → PC로 꺼내기
adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.REPORT
adb exec-out run-as ai.pleos.playground.sample.vehicle.diagnostic --user 10 cat files/reports/<파일 이름> > report.html
# 로그
adb logcat -s Trip
```

기어가 D이면 Pleos가 앱 화면을 가리므로, Virtual Console에서 P로 바꾼 뒤 확인하세요.
