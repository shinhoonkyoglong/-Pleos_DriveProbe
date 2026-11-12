# 앱 UI 구조 조사 (Pleos 디자인·심사 가이드 + 참고 차량 앱)

- 조사일 2026-11-11 · 담당 pleos-sdk-researcher · 읽기 전용 브라우징(로그인 불필요한 공개 문서만)
- 확인 수준 표기: [문서 확인] / [실행 확인] / [미확인] / [설계 판단]
- 기존 노트와 겹치는 내용은 다시 조사하지 않고 인용한다: 주행 규제 값은 [vehicle-state.md](vehicle-state.md) §11.2·§13.3, 알림 근거는 [sdk-setup.md](sdk-setup.md).

## 요약 — 감독관 초안 판정

| # | 초안 항목 | 판정 | 근거 |
|---|---|---|---|
| 1a | 차량 상태(주차/주행)가 화면을 결정한다 | **지지** | Pleos 문서는 앱이 `CarUxRestrictions`로 제한 상태를 확인해 제한 대상 UI를 축소·비활성화하라고 한다. DO 미선언 앱은 Drive에서 사용 불가. [문서 확인] A-2, A-3 |
| 1b | 주행 중 = 경고만 자동 전환 | **부분 지지** | 주행 중 표시를 허용하려면 DO 선언이 전제다(미선언 시 사용 불가). 경고 화면을 보이게 할지는 DO 선언 여부와 심사에 달렸다. [문서 확인] A-3 / 선언·심사 통과 여부 [미확인] |
| 1c | 홈 = "오늘 운전해도 되나" 판정 카드 1장 + 조건 요약 | **근거 없음(설계 판단)** | 공식 문서에 홈 구성 규정은 없다. 다만 "자주 쓰는 기능은 첫 화면·상단", "1~2초 안에 파악"과 방향이 같다. [설계 판단] |
| 1d | 설정·이력은 큰 버튼 2~3개로 | **지지** | "필요 기능에 최소 조작", "드롭다운·다단 메뉴 남용 금지". [문서 확인] A-2 |
| 1e | 내비게이션 깊이 최대 2 | **지지(더 느슨한 근거)** | Pleos는 단계 수 수치를 주지 않는다. Android for Cars는 작업 흐름 5단계 이하 MUST, 2~3단계 권장, 일반 작업 탭 3번 이하. [문서 확인] B-1 |
| 2a | 1번이 셸(앱바·상태 전환·디자인 토큰)을 소유 | **근거 없음(설계 판단)** | Pleos는 앱 하나가 Activity·window 방향을 소유한다는 구조만 말한다. 팀 분담은 문서 범위 밖이다. [설계 판단] |
| 2b | 토큰: 본문 ≥26sp, 터치 ≥72dp, 색+아이콘+텍스트 | **(정정, 하단 A 보강 참조) 26은 Pleos Body Small 26과 일치, 72dp는 근거 없음 / 방향 지지** (아래 원문은 1차 판정) | Pleos 문서에 sp·dp 수치 기준이 없다(아래 A-4). 색+아이콘+텍스트는 "아이콘은 텍스트 라벨과 함께"가 근거. 26sp·72dp는 문서 수치가 아니라 설계 판단으로 표기해야 한다. [문서 확인] / [설계 판단] |
| 2c | 각 파트가 홈 카드 + 풀스크린 1개 노출 | **근거 없음(설계 판단)** | 문서에 모듈형 카드 구조 규정 없음 |

## A. Pleos 공식 문서

### A-1. 화면 구성과 영역 (Display Layout)
- 출처: https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout (확인일 2026-11-11)
- 시스템 UI: Status bar, Driving view(Telltale·속도·차량 상태), GNB(App library·즐겨찾는 앱·공조), **Application window**. Application window에는 앱 1개 또는 2개가 보인다. [문서 확인]
- 가로 전용 게임 같은 예외를 빼면 **모든 앱은 Landscape·Portrait 두 레이아웃에 맞게 조절**돼야 한다. [문서 확인]
- 상태 표시줄·GNB 패널·키보드는 앱보다 상위 레이어라 앱 윈도우를 일시적으로 가릴 수 있다. [문서 확인]
- 디스플레이 유형: Connect-S(17", 14.6", 12.9"), Connect-W(와이드, 세로 확장형 Connect-W-Extended), Connect-WF(작은 해상도). [문서 확인]
- 에뮬레이터의 "왼쪽 클러스터/위젯 패널 + 오른쪽 앱 영역 약 1600x1200"과의 대응은 문서에 없다. [미확인]

### A-2. 앱 구조·동작 모델
- 출처: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model (2026-11-11)
- Driving view는 고정 영역이며 앱이 가리거나 종료할 수 없다. 앱 영역은 1~2개 window로 나뉜다. [문서 확인]
- `screenOrientation` 미지정이면 기본 **portrait, window 1개**. landscape로 지정하면 window 2개 영역을 쓴다. [문서 확인]
- portrait에서는 핵심 기능·주요 정보, landscape에서는 확장 영역에 부가 정보·확장 기능을 둔다. window bar·제스처로 크기·위치가 바뀌어도 핵심 기능이 동작해야 한다. [문서 확인]
- 앱은 포그라운드↔백그라운드를 자주 오간다. 진입 시 상태 복원, 백그라운드에서 애니메이션·센서·폴링 중단. [문서 확인] (기존 README 설계 시사점 §3과 일치)
- 심사 가이드: 백그라운드에서 복귀하면 마지막 사용 상태가 복원돼야 한다(미디어 앱 항목에 기재, 일반 앱 적용 여부는 [불확실]). https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements

### A-3. 주행 안전·주행 규제
- 출처: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline , .../driving-restiction-policy (원문 슬러그 오타 그대로), .../app-category (2026-11-11)
- 앱은 `CarUxRestrictions` 기준으로 제한 대상 기능의 UI·동작을 **축소하거나 비활성화**해야 한다. [문서 확인]
- DO 미선언 시: Parking 사용 가능, Drive 사용 불가. [문서 확인] (vehicle-state.md와 일치)
- 지역별 기능 표(Driving 중): 북미는 키보드·다이얼패드·설정·검색 등 거의 전부 제한(음성한정 1항목). 유럽은 키보드·다이얼패드·검색 허용, 설정·동영상 제한. 그 외 지역(한국 포함 여부 [불확실])은 키보드·다이얼패드·검색 허용, 미디어 로그인·설정·차량 설정·동영상·QR 로그인·대용량 텍스트 제한. [문서 확인] 한국이 "그 외 지역"에 속하는지는 문서에 명시 없음 [미확인]
- 대용량 텍스트(매뉴얼)는 `UX_RESTRICTIONS_LIMIT_CONTENT(32)`·`LIMIT_STRING_LENGTH(4)` 대상이다. [문서 확인]
- 앱 카테고리: Maps/Navigation, Mobility Services, Lifestyle, Entertainment, Business, Music/Audio, Kids, Social/Communication. 주행 중 기능 예외 허용 예시는 **Music/Audio, Maps/Navigation, Mobility Services**만 나열한다. 우리 앱에 맞는 카테고리(Lifestyle 또는 Mobility Services 추정)와 주행 중 허용 범위는 [미확인].
- UX 원칙(전부 [문서 확인]): 동영상·애니메이션·움직이는 이미지 최소화, 드롭다운·다단 메뉴 남용 금지, 자주 쓰는 기능은 첫 화면·상단, 큰 터치 영역·높은 대비·텍스트 최소화, 키보드보다 프리셋·최근 항목·음성 우선, 음성·물리 버튼 연계.
- 심사: 가이드 전반을 지켜도 운전자 주의 분산 위험이 있다고 판단되면 **등록이 거부될 수 있다**. [문서 확인] (심사 가이드 위 URL)

### A-4. 디자인 가이드 수치와 규칙
- 출처: https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro , .../foundations/typography , .../components/toast (2026-11-11)
- 1~2초 안에 정보 파악(Glanceability). 사용자 입력 후 시스템 응답 **0.25초 초과 금지**, 지연 시 응답 중 상태 표시. [문서 확인]
- 동영상·애니메이션·**텍스트 자동 스크롤** 금지, 한 손 조작 가능. [문서 확인]
- 대비율 **4.5:1 이상**(아이콘·텍스트·기타 이미지). [문서 확인]
- 텍스트: 영문 단일 메시지 **120자 또는 24단어 이하**, 한 줄 **80자 이하**, 문장·단락 **3줄 이내**. 중요 정보 기본 텍스트 높이 **5 mm 이상**, 보조 텍스트 **3 mm 이상**. [문서 확인] 한국어 글자 수 기준은 문서에 없다 [미확인]
- 타이포그래피 페이지: 최소 폰트 12sp 권장, 실제 픽셀 최소 높이 18px 이상, 폰트 Asta Sans(Korean, English), 위계 Headline·Title·Body·Label. **구체 sp 표는 본문 텍스트에 없다**(이미지일 수 있음, 본문 추출로 확인 불가). [문서 확인] / 위계별 sp 값 [미확인]
- 아이콘은 **텍스트 라벨과 함께** 쓴다. 터치 시 햅틱 또는 음성 피드백. [문서 확인]
- 야간/주간 모드: 조명 환경에 따라 UI 밝기·대비 자동 조정. 구체 방법(시스템 uiMode 따름 등)은 [미확인].
- 터치 영역 dp 수치: 본문 텍스트에 없음. Basic button 페이지는 상태(Pressed/Disabled/Loading)만 기술한다. https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button [미확인]
- Toast: 앱 패널 내 side/bottom padding 24, 전체 max width 792, 메시지 max width 712(단위 표기 없음, px/dp 불명). [문서 확인 / 단위 불확실]
- System Notification: 앱 아이콘·프로필·이미지·액션 타입, 버튼 정렬 Single/Vertical/Horizontal. 알림은 운전자 필요와 관련될 때만, 광고 금지. [문서 확인] (sdk-setup.md 참조)
- 컴포넌트에 앱바·카드·리스트·탭 컴포넌트가 있는지는 이번에 사이드바를 읽지 못해 확인하지 못했다(문서 사이트 사이드바가 좁은 창에서 비어 있음). 확인한 컴포넌트: Basic button, Toggle button, Spinner, Toast, System Notification, Shadow, Colors, Iconography. [미확인: 앱바·카드·리스트]
- 디자인 리소스(Pleos Connect display guide, design assets) 다운로드 링크가 있다(받지 않음). [문서 확인]

### A-5. 앱 템플릿·샘플
- Pleos 전용 "앱 템플릿"은 찾지 못했다. Android Studio의 Car/Automotive 카테고리 Empty Activity로 시작하라고 안내한다. https://document.pleos.ai/docs/pleos-only/getting-started [문서 확인]
- Vehicle SDK Sample App(1.0.0-sdk35)의 UI는 **좌측 분류 + 우측 기능 목록 + 상세 화면(지원 확인·현재값·변경·실시간 알림·API 로그)** 의 개발자 탐색기다. 디자인 가이드·UI 구성은 다루지 않는다고 명시. https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app [문서 확인] 제품 UI 참고용은 아니다.

## B. 참고 차량 앱

### B-1. Android for Cars (Car App Library)
- 출처: https://developer.android.com/design/ui/cars/guides/ux-requirements/overview , .../guides/templates/overview , .../guides/foundations/design-principles (2026-11-11, 한국어 번역판; 번역 오류 가능)
- **작업 흐름 5단계 이하(MUST)**, 길이 2~3단계 권장(SHOULD). 목록 기반 템플릿으로 5단계 흐름을 끝내지 말 것(5단계는 탐색·메시지·창 중 하나). 흐름이 화면 3개를 넘으면 이전 단계 바로가기 제공. [문서 확인]
- 일반 작업은 **탭 3번 이하**, 운전 중 텍스트 **1~3줄·120자**. [문서 확인]
- 상태가 있는 버튼(토글)은 작업 영역에 만들지 말 것(토글은 목록 행에서만). 템플릿을 열면 옵션 없는 빈 상태가 아니라 유용한 콘텐츠를 보일 것. [문서 확인]
- 내비게이션 앱: 지도 위 글자 24dp 이상 권장, 아이콘·마커 최소 36x36dp 권장. [문서 확인] (지도 대상 수치이고 일반 터치 영역 기준 아님)
- 범용 템플릿: 목록/그리드, 섹션 목록, 창(Pane), 메시지·긴 메시지, 검색, 로그인, 탭. 특수 템플릿: 탐색, 장소 목록(지도), 지도+콘텐츠, 미디어 재생. 카테고리는 탐색·POI·날씨·미디어 등으로 제한된다. [문서 확인] 우리 앱 유형(건강·운전 보조 대시보드)이 허용 카테고리인지는 [미확인].
- 설계 원칙: 운전 중에는 사용할 수 있는 앱만 사용 가능, 사용 가능/불가를 시각적으로 구분, 음성 제어, 중단 후 재개, 운전 중 알림은 신중히, 기동 중에는 알림 금지. [문서 확인]
- **주의**: Car App Library 템플릿은 Pleos Connect에서 쓸 수 있다는 문서 근거가 없다. Pleos는 일반 Activity(Empty Activity)를 전제한다. 여기서는 수치·원칙의 참고 근거로만 쓴다. [미확인]

### B-2~B-4. Pleos 외 참고 앱·시니어 UI
- 이번 조사에서는 B-1과 Pleos 공식 문서 우선으로 진행해 Google Maps/Assistant 드라이빙 모드, 현대·기아 인포테인먼트, Tesla, 고령자 UI 가이드는 읽지 않았다. 해당 항목은 모두 [미확인]이며 근거로 쓰지 말 것.

## 우리 앱 UI 구성 시사점

1. **상태 기반 전환은 공식 방향과 맞다.** 앱은 `CarUxRestrictions`(vehicle-state.md §13)로 제한을 읽어 UI를 축소해야 한다. 기본값(DO 미선언)에서 Drive 중 앱 사용 불가이므로, "주행 중 경고 화면"을 띄우려면 DO 선언 가능 여부를 Pleos 측에 확인해야 한다. 안 되면 주행 중 안내는 시스템 알림·TTS 쪽으로 가야 한다(TTS는 현재 Playground '준비 중'). [문서 확인 + 설계 판단]
2. **window·방향 설계가 필수다.** 기본은 portrait 단일 window. 홈 카드·핵심 판정은 portrait에, 이력·설정 같은 확장은 landscape에 두는 분리를 문서가 권한다. 1600x1200 에뮬레이터 앱 영역이 이 중 어느 것인지는 [미확인]. 따라서 반응형 레이아웃과 두 방향 미리보기를 셸의 요구사항에 넣을 것. [문서 확인 + 설계 판단]
3. **토큰 수치는 문서값이 아니라 팀 결정이다.** 문서 확정값은 대비 4.5:1, 문단 3줄·한 줄 80자(영문 기준)·메시지 120자, 12sp/18px 최소(권장), 응답 0.25초, 단계 5(MUST)·2~3(권장). "본문 ≥26sp·터치 ≥72dp"는 고령자 대상 팀 결정으로 두고 문서 근거로 인용하지 말 것. [설계 판단]
4. **금지·삼가 목록을 셸 체크리스트로**: 자동 스크롤 텍스트·애니메이션 금지, 드롭다운·다단 메뉴 삼가, 토글 버튼 남용 금지(Android for Cars 기준), 키보드 입력 대신 프리셋·음성. 설정 화면(조건 입력)은 Drive에서 `NO_SETUP`류 제한 대상이 될 수 있어 Park 전용으로 설계. [문서 확인 + 설계 판단]
5. **조건 설정 화면의 텍스트 입력**은 키보드 대신 프리셋·스텝퍼(예: 허용 반경 선택 버튼)로. 한국 지역 키보드 정책은 [미확인].
6. 셸 소유권(1번)·카드 규약은 문서에 근거가 없어 팀 합의 사항이다. 합의하려면 위 2번(window·방향)과 1번(DO 선언)을 먼저 1번 담당과 확인할 것.

## 추가: 5파트 흐름(시동 → 반응 테스트 → 주행 개입 → 주행 후 요약)에 대한 근거

팀 분담(1 공통 기반, 2 출발 전, 3 컨디션 체크, 4 주행 중 개입, 5 주행 후 요약) 확정에 따라 아래 3개를 추가로 확인했다. 범위는 최소로 했다.

### (a) 주차 중 시동 직후 인터랙티브 과제·테스트
- Pleos: Parking에서는 DO 미선언 앱도 사용 가능하고 제한 0x0이다(A-3, vehicle-state.md §13.3). 주차 중 반응 테스트 같은 과제의 **횟수·길이 제한 문구는 찾지 못했다**. [문서 확인: 제한 없음 = Parking 사용 가능 / 과제 전용 규칙 [미확인]]
- Pleos는 응답 0.25초 이내(지연 시 진행 표시), 햅틱·음성 피드백, 한 손 조작을 요구한다(A-4). 반응 시간 측정 화면은 이 응답 규칙의 영향을 받는다. [문서 확인]
- Android for Cars: 주차 중 환경은 웹·게임 등 폭넓게 허용되고, "주차 중에 로그인", "설정 만들기" 흐름 샘플이 있다(제목만 확인, 본문 미확인). https://developer.android.com/design/ui/cars/guides/flows/overview [문서 확인(제목 수준)]
- 주행으로 전환되는 순간 과제 중단: Android for Cars "작업을 쉽게 일시중지하고 다시 시작", 진행 상황 손실 금지. https://developer.android.com/design/ui/cars/guides/foundations/design-principles [문서 확인]
- 주의: 반응 테스트가 Pleos 심사의 "게임" 성격으로 분류되는지는 문서에 없다. 심사 가이드는 게임을 미디어 앱으로 보고 주행 중 실행 금지, 정차 중에만 허용, 주행 시작 시 UI 숨김·일시중지 해제 불가라고 한다. https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements [문서 확인] 분류 여부 [미확인]

### (b) 주행 중 음성 우선·알림·개입
- Pleos 디자인 가이드: 음성 인터랙션은 Gleo AI를 통해 지원, 핸즈프리로 말하고 들을 수 있어야 하며 대화 시작·종료·중단은 수동 조작 허용. 알림은 간결하게, 운전자 필요와 관련될 때만, 광고 금지. https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro [문서 확인]
- Pleos 심사 가이드: 미디어 앱은 헤드업 알림 게시 금지, 운전과 무관한 알림 금지(일반 앱 적용 여부 [불확실]). 위 심사 URL [문서 확인]
- Pleos 앱 구조: Driving view는 고정이며 앱이 가리지 못한다. 별도 오버레이/경고 창 규칙은 찾지 못했다. [미확인]
- 개입 단계 수(3단계 등)·허용 알림 개수에 대한 Pleos·Android for Cars 규정은 **없다**. 3단계 개입은 팀 설계 사항이다. [미확인]
- Android for Cars: 알림은 신중히 선택된 위치·시간에 표시, **운전자가 기동(조작) 중일 때는 알림 금지**, 운전 중 텍스트 1~3줄·120자. 내비게이션 알림은 ETA 위치에 표시해 경로를 가리지 않고, 진행률 표시기로 자동 닫힘 시간을 보이며 시간 초과 후 닫힌다. 자동 전환 시 콘텐츠를 지우기 전 최소 8초 표시 권장(SHOULD). https://developer.android.com/design/ui/cars/guides/components/nav-alerts [문서 확인]
- 음성 입력: 음성 입력 진입점(마이크 아이콘) 제공(SHOULD), 녹음 전 마이크 권한 MUST, 입력 중단 시 녹음 중지. https://developer.android.com/design/ui/cars/guides/ux-requirements/overview [문서 확인]
- TTS·STT는 Playground에서 '준비 중'이므로 음성 질의응답(파트 4)의 SDK 실행 근거는 아직 없다(tts-stt.md, README 4번 행).
- 에어컨·창문 제어: Vehicle Control SDK도 '준비 중'(README). 하드웨어 접근은 Pleos 측 승인 퍼미션 범위 내에서만 가능하다고 문서가 명시한다. https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/ [문서 확인]

### (c) 주행 종료 후 요약 화면
- 전용 규정이나 템플릿은 찾지 못했다. 주차 상태(제한 없음)에서의 일반 앱 화면이므로 A-1~A-4 규칙(대비, 문단 3줄, 큰 터치 영역)만 적용된다. [미확인: 전용 가이드 / 문서 확인: 일반 규칙]
- Android for Cars 템플릿 중 메시지·긴 메시지·창(Pane) 템플릿이 "요약 + 주요 작업"에 가장 가깝다(템플릿 설명 기준). [문서 확인 + 설계 판단]
- 주행 종료 직후 요약을 띄우려면 주행→주차 전환 감지가 필요하다. 감지 수단은 CarUxRestrictions 리스너(등록 직후 초기 콜백 없음, 직접 조회 필요, vehicle-state.md §13.3). [실행 확인]

### 5파트 흐름에 대한 매핑 (설계 판단, 근거 위 항목)
| 단계 | 상태 | 지침 매핑 |
|---|---|---|
| 시동 직후(파트 3 반응 테스트 + 파트 2 출발 판정) | Park | 제한 없음. 두 화면이 같은 Park 시간에 경쟁하므로 순서(판정 → 테스트 또는 반대)를 셸이 정해야 한다. 응답 0.25초·진행 표시, 주행 전환 시 일시중지·재개. |
| 주행 중 개입(파트 4) | Drive | DO 선언 없으면 앱 화면 사용 불가. 개입은 음성(TTS) 또는 시스템 알림 중심으로 설계, 화면 알림은 1~3줄·120자 이내·자동 닫힘 시 8초 이상, 운전자 기동 중 알림 금지. 3단계 수준은 팀이 정의. 음성 질의응답 측정은 STT '준비 중'으로 SDK 근거 없음. |
| 주행 종료(파트 5) | Park 복귀 | 주행→주차 전환을 감지해 요약 표시. 일반 규칙만 적용. |
| 공통(파트 1) | 전 구간 | 셸이 `CarUxRestrictions`를 구독해 상태를 파트에 전달, 주행 중 설정·키보드 화면은 숨김, 반응형 portrait/landscape 지원. |

## A 보강 — 디자인 가이드 수치 (2차, 2026-11-11)

- 출처: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography 의 Font Hierarchy 표(화면 렌더링에서 읽음; 본문 텍스트 추출에는 안 잡힘) [이미지 확인]. **단위 표기는 없음**(Size 열 숫자만) [불확실: sp/px 여부].

| 분류 | Large | Medium | Small | 굵기 |
|---|---|---|---|---|
| Headline | 60 | 56 | 48 | Extra Bold, Bold |
| Title | 40 | 36 | 32 | Extra Bold, Bold, Regular |
| Body | 30 (행높이 38) | 28 (36) | 26 (34) | Strong, Normal |
| Label | - | 24 (행높이 28) | 20 (24) | Normal |

- 따라서 팀이 정한 "본문 ≥26" 은 Pleos 타이포 Body Small(26)과 일치한다. 이전 판정 2b를 정정한다: 26은 Pleos 위계 수치와 맞음 [이미지 확인], 단위는 [불확실]. Headline·Title 수치는 Pleos 기본 위계라서 판정 카드 제목에 쓸 근거가 된다. 앞서 쓴 "12sp 최소 권장"은 일반 Android 접근성 기준이고 실제 Pleos 위계는 20 이상이다.
- 터치 타겟 dp: Basic button 페이지는 상태 표와 예시 이미지(Label 버튼, Enabled/Pressed/Disabled)만 있고 크기 수치는 없었다. [미확인]
- 컴포넌트 목록: 사이드바가 이 브라우저 창에서 렌더링되지 않았고(창 크기 변경 도구 없음), 사이트 검색도 디자인 문서를 색인하지 않았다("card", "Components" 결과 없음). **확인된 컴포넌트는 Basic button, Toggle button, Spinner, Toast, System Notification 5개와 Foundations(Colors, Typography, Iconography, Shadow)** 뿐이다. 앱바·카드·리스트·탭·다이얼로그 컴포넌트는 존재 여부 [미확인]. 없을 가능성이 있으므로 직접 만들 계획이 안전하다. [설계 판단]
- Toast는 Pleos 컴포넌트가 앱 패널 안(side/bottom padding 24)에 뜨는 형태라 앱 내 알림 표현은 이 컴포넌트를 쓰는 것이 일관성 기준에 맞다. [문서 확인]

## B(3). 참고 차량 앱 구조 (공개 페이지 기준)

조사 한계: 공개 설명·오너스 매뉴얼·검색 결과 요약만 읽었다. 스크린샷 영상은 보지 않았다. 홈 구성·주행/주차 차이·알림·설정 접근·주행 후 요약을 항목별로 채우지 못한 칸은 [미확인].

| 앱/플랫폼 | 홈 구조 | Park vs Drive | 알림·개입 | 설정·이력 접근 | 주행 후 요약 | 출처 |
|---|---|---|---|---|---|---|
| Hyundai ccNC (오너스 매뉴얼, EUR) | 여러 페이지 홈, 좌우 스와이프, 아이콘 위치 길게 눌러 재배치, 홈 왼쪽 스와이프로 전체 기능 목록, 메뉴바(뒤로/홈/메뉴 목록) [문서 확인] | 주행 중 동영상 화면 비활성, 일부 기능은 정차 중에만 동작 [문서 확인] | 알림은 별도 "Notifications" 메뉴 [문서 확인(목차)] | 설정이 시스템·차량·내비·사운드·프로필·음성·화면 등 항목별로 많음, 목차에 사용자 프로필·Vehicle diagnostics·Quiet Mode [문서 확인(목차)] | [미확인] | https://ownersmanual.hyundai.com/ivi/ccNC/AVNT/EUR/English/Systemoverview_home.html |
| Hyundai 그룹 UX 소개(검색 요약) | "고령층도 쉬운 간결한 UX", "주행 중 다단 메뉴 대신 물리·위젯 접근" 취지 [문서 확인(검색 요약만, 원문 미열람)] | - | - | - | - | https://talent.hyundai.com/eng/story/219/view.hc , 위 구글 검색 결과 |
| Tesla Model 3 (오너스 매뉴얼) | 차량 상태 영역에 주행 속도·경고 메시지 표시, 카드 영역 [문서 확인(검색 요약)] | 터치스크린 재시작은 정차·P단에서만 [문서 확인(검색 요약)] | **졸음 경고**: 터치스크린 카드 영역에 "Driver drowsiness detected / Take a break" 표시 + 경고음, 65 km/h 초과·10분 이상 주행 시 활성, 패턴이 사라지면 해제, 60 km/h 아래로 떨어지면 비활성. 단계 구분(레벨)은 문서에 없음 [문서 확인] | Controls > Safety > Driver Drowsiness Warning 에서 현재 주행 사이클 동안 끄기, 다음 주행 시작 때 자동 재활성. 카메라 데이터는 기본적으로 차량 밖으로 안 나감, 공유는 Controls > Software > Data Sharing [문서 확인] | [미확인] | https://www.tesla.com/ownersmanual/model3/en_gb/GUID-65BF21B8-50C5-4FA5-86A4-DA363DCD0484.html |
| Volvo Driver Alert Control (검색 요약) | - | 약 40 mph 이상에서 동작 취지 [미확인: 검색·동영상 요약만] | 불규칙 주행 시 **경고음 + 텍스트 "Driver Alert Time for a break"**, 개선 안 되면 시간 간격 후 반복 [문서 확인(검색 요약)] | - | - | https://www.volvocars.com (Driver Alert Control 지원 페이지, 검색 결과 요약만 확인) |
| AOSP / Google built-in (Android Automotive) | Google Maps·Assistant·Play 탑재 [문서 확인] | AOSP 기본 UX 제한: **Parked 제한 없음, Idling 동영상·설정 화면 금지, Moving 전면 제한**(검색 결과 요약) [문서 확인(요약)] | - | - | - | https://source.android.com (Car User Experience Restrictions rules, 검색 결과 요약) |
| Pleos/Playground 샘플·스토어 | Vehicle SDK Sample App은 개발자용 탐색기(좌 분류, 우 기능 목록, 상세) [문서 확인] | - | - | - | - | https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app |
| 보험 텔레매틱스·Polestar·Volvo AAOS 주행 요약 앱 | [미확인: 이번 조사에서 공개 설명을 찾지 못함] | | | | | |

### 참고 앱들의 공통점 (문서 근거 있는 것만)
1. **주행 중에는 기능을 줄이고 정차 중에만 여는 구조**가 공통이다(ccNC 동영상·일부 기능, AOSP 제한 표, Pleos). [문서 확인]
2. **졸음 경고는 경고음 + 짧은 텍스트 카드 한 장("휴식 필요")** 이고 단계 수는 공개돼 있지 않다(Tesla, Volvo 요약). 사용자가 끌 수 있되 다음 주행에서 자동 재활성(Tesla). [문서 확인]
3. **홈은 재배치 가능한 카드/아이콘 페이지**(ccNC)와 **상태 영역 + 카드 영역**(Tesla) 형태이며, 설정은 시스템 설정 계층 안에 있다. 우리 "홈 판정 카드 1장" 구조와 모순되지 않으나 직접 근거도 없다. [문서 확인 + 설계 판단]
4. 프라이버시: 카메라 기반 감지는 기본적으로 차량 내 처리, 외부 공유는 별도 동의(Tesla). 우리 서버 동기화(가족 웹)는 동의 UI 필요 [설계 판단]
5. 주행 후 요약 화면의 공개 패턴은 찾지 못했다. [미확인]

### 판정 변경
- 2b: "본문 ≥26" 수치는 Pleos Body Small 26과 일치하므로 **수치 지지**로 바꾼다(단위 [불확실]). "터치 ≥72dp"는 여전히 근거 없음.
- 1b: 주행 중 경고 화면은 참고 앱 모두 "경고음 + 짧은 카드" 패턴이라 방향은 지지되나, Pleos에서 앱 화면이 허용되는지는 DO 선언에 달려 있어 바뀌지 않음.
- 나머지 판정은 변경 없음.

## [미확인] 목록
0. (추가) 반응 테스트의 심사 분류(게임 해당 여부), 주행 중 오버레이/헤드업 허용 범위, 개입 단계 수 규정, 요약 화면 전용 가이드.
1. 앱바·카드·리스트·탭 등 컴포넌트 문서 존재 여부(사이드바 접근 불가).
2. 터치 타겟 dp, 타이포 위계별 sp 값(이미지로만 제공될 가능성).
3. 에뮬레이터 앱 영역 1600x1200과 window/orientation 대응.
4. 한국이 주행 규제 표의 "그 외 지역"인지.
5. 우리 앱의 카테고리와 DO 선언 가능 여부·절차, 주행 중 경고 화면 허용 여부.
6. 야간/주간 모드의 구체적 구현 방식(시스템 테마 연동 여부).
7. Car App Library 템플릿의 Pleos Connect 적용 가능 여부.
8. 한국어 텍스트 길이 기준(문서는 영문·일본어만 기재).
9. B(3)은 2차에서 일부 조사함(위 표). Google Maps/Assistant 드라이빙 모드 UI 상세, ccNC 알림·요약 화면, 보험 텔레매틱스·Polestar 주행 요약, Tesla 홈 화면 구조는 [미확인]. B(4) 고령자 UI 가이드 — 조사하지 않음.
10. 터치 타겟 dp, 앱바·카드·리스트·탭·다이얼로그 컴포넌트 존재 여부(사이드바 렌더링 실패, 사이트 검색 미색인).
