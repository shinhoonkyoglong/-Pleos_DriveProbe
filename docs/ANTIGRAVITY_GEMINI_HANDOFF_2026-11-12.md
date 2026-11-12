# Antigravity · Gemini 개발 인수인계

작성일: 2026-11-12, Asia/Seoul  
프로젝트: Pleos DriveProbe / KeepDriving Android 앱  
인계 대상: Antigravity에서 이어서 작업하는 Gemini 개발 에이전트

이 문서는 현재 저장소의 구현과 실제로 수행한 검증을 구분해 정리한 개발 인수인계 자료다. 문서에 있는 향후 작업 제안과 재현 명령은 사용자의 새로운 실행 지시를 대신하지 않는다. 이후 사용자의 지시와 실제 코드가 우선한다.

## 1. 가장 먼저 알아야 할 현재 상태

사용자 담당 영역은 주로 **주행 중 피로 단계에 따른 개입**이다. MILD 공조, CAUTION 음성 질문, DANGER 휴게시설 제안 및 동의 후 경유지 추가, 이력 저장, 일반 앱 화면 연결을 구현했다. 최근에는 **공식 CSV 기반 휴게소·졸음쉼터 자동 확보**와 **실제 개입 이력 → 최신 main의 종료 요약·운전 리포트 연동**까지 추가했다.

현재 구현은 단순한 시연 UI만 있는 상태가 아니다. 실제 차량·음성·내비 SDK로 이어지는 생산 실행 경로가 있다. 다만 합성 테스트 통과, SDK 일부 조회 성공, 전체 실제 주행 성공은 서로 다르다. 특히 **공식 CSV 좌표를 이용한 실제 경유지 추가·재조회와 전체 DANGER 발화 흐름**, **새 공조 전원/AUTO 정책을 포함한 전체 MILD 기기 동작**은 추가 확인이 필요하다.

최근 사용자는 서버 연동을 요청했다. 확인 과정에서 최신 main의 요약 리포트가 외부 HTTP가 아니라 앱 내부 `RestAdviceSource`로 개입 데이터를 받는 구조임을 발견했고, 사용자의 진행 승인 후 우선 이 내부 연동을 구현했다. **실제 외부 서버 업로드는 아직 구현하지 않았다.**

| 영역 | 현재 상태 | 남은 부분 |
| --- | --- | --- |
| MILD 공조 | 생산 제어 경로 구현 | 최신 준비 정책 포함 전체 기기 검증 |
| CAUTION | 실제 TTS/STT 질문·인식·응답 시간·이력 | 실제 유효 피로 판단에 따른 장시간 자동 발동 확인 |
| DANGER | 시설 준비 → 이름/ETA 질문 → 명시 동의 → 같은 제안 적용 | 공식 CSV 좌표 기반 전체 기기 흐름 확인 |
| 휴게소 자동 확보 | 공식 파일 + 명시 방향 일치 구현 | TBT 방향 없는 경우 추가 근거 확보는 미구현 |
| 졸음쉼터 추천 | 휴게소와 함께 경로 순서대로 후보화 | 해당 시설을 포함한 실제 경로 기기 확인 |
| 일반 앱 진입 | 홈 → 주행 도움·휴게소 설정 | 별도 개발자 화면을 거치지 않아도 사용 가능 |
| 주행 개입 저장 | Room 실제/시험 구분 저장 | 프로세스 종료 직전 미저장 결과의 영속 큐 없음 |
| 종료 요약·운전 리포트 | 실제 개입·음성 응답 횟수 연동 | MILD/동의율/경로 성공률 등 상세 리포트 확장 없음 |
| 외부 서버 | API 계약 미확인, 업로드 미구현 | 서버 주소·인증·스키마 확인부터 필요 |

## 2. 저장소·브랜치·커밋

| 항목 | 값 |
| --- | --- |
| 로컬 저장소 | `C:\SSAFY\jyj\workspace\-Pleos_DriveProbe` |
| Git 원격 | `https://github.com/shinhoonkyoglong/-Pleos_DriveProbe.git` |
| 작업 브랜치 | `feat/hvac-control` |
| 최근 구현 커밋 | `ba5adf7f75cd49ca8981e00b6a46fc97ca2a1954` |
| 구현 커밋 제목 | `feat: 공식 휴게시설 자동 확보 및 실제 개입 리포트 연동` |
| 직전 main 통합 커밋 | `7e42018` — `Merge remote-tracking branch 'origin/main' into feat/hvac-control` |
| 통합한 main 기준 | `43f1e9f` — PR #6, `feat/trip-summary` |
| 일반 앱 도움/DANGER 시험 커밋 | `cd04713` — `feat: 주행 도움 앱 통합 및 DANGER 실제 효과 시험 추가` |
| main 통합 전 백업 브랜치 | `codex/before-main-trip-20261112` |

이 문서는 구현 커밋 이후 별도 문서 커밋으로 추가한다. 문서 자체를 포함한 최종 커밋 ID는 체크아웃한 저장소의 `git log -1`에서 확인한다. 문서가 스스로의 커밋 해시를 포함하도록 반복 수정할 필요는 없다.

마지막 원격 확인 시 `origin/main`은 `43f1e9f`였다. `origin/feat/trip-compliance` 브랜치가 새로 발견됐지만 **이 작업에서는 병합하지 않았다**. 운전 조건 준수 리포트 후속 작업과 관련될 가능성은 있으나 내용을 검토하지 않았으므로 구현 상태를 단정하지 않는다.

### 2.1 main 병합 시 처리한 사항

- 사용자의 요청으로 main을 다시 가져온 뒤 서버/리포트 작업을 진행했다.
- 공식 휴게시설 작업은 stash로 보관한 상태에서 main을 병합했다.
- `DriveProbeApplication.kt` 충돌은 `InterventionModule` 초기화와 새 `TripModule.init(this)`를 모두 유지해 해결했다.
- `TripModule.init`은 `ConditionModule.init` 및 `interventions` 생성 이후 실행한다.
- 병합 후 stash를 적용해 휴게시설 작업을 복원했고, 현재는 구현 커밋에 들어갔다.
- `TripReportComponents.kt`의 끝부분 불필요 공백도 병합 때 정리했다.

### 2.2 보존된 stash와 미추적 파일

로컬에는 다음 백업 stash가 남아 있다. **작업은 이미 복원·커밋됐으므로 다시 적용하지 않는다.** 번호는 다른 작업으로 바뀔 수 있어 해시로 식별한다.

| stash 해시 | 의미 |
| --- | --- |
| `d40590f3d2511107de92f1e065d97ae630dd6605` | main 리포트 병합 전 공식 휴게시설 작업 백업 |
| `a3c1f42` | 기존 `investigation/background-freeze`의 로컬 Android Studio 설정 백업 |
| `5f96096` | 2026-11-11 main pull 전 작업 백업 |

저장소 루트에 아래 과거 인계 입력 파일 2개가 미추적으로 남아 있다. 기존 입력 자료이므로 이번 코드 커밋에 넣지 않았고 삭제하지 않았다.

- `Pleos_Navi_Handoff_2026-11-12.md`
- `Pleos_Navi_Handoff_2026-11-12_Completed.md`

문서/코드 커밋 이후 `git status`에서 위 두 파일이 보이는 것은 예상된 상태다. 원격에는 없는 로컬 입력일 수 있으므로 새로운 개발 환경에서는 본 문서와 저장소 코드로 이어가면 된다.

## 3. 사용자 결정과 작업 범위

이 절은 과거 대화에서 확정된 제품 방향을 정리한 것이다.

1. 개발자 도구에서만 시험 가능한 상태를 넘어, 이미 구현·검증한 기능을 일반 앱에서 실제 사용할 수 있도록 연결한다.
2. 휴게소를 내비 즐겨찾기에 미리 등록하지 않아도 가능한 경우 앱이 좌표·시설 정보를 확보해 추천해야 한다.
3. 팀에는 현재 별도의 POI 검색 API 데이터나 API 키가 없다.
4. 사용자가 데이터 확보 옵션 중 **공식 파일을 앱에 포함하는 방식**을 선택했고 원본 CSV 두 개를 직접 제공했다.
5. **졸음쉼터도 추천 대상**이다. 과거 문서의 ‘졸음쉼터 제외’ 정책은 최신 요구가 아니다.
6. 방향을 확인할 근거가 없으면 임의로 반대편 시설을 선택하지 않는다. 공식 후보를 보여주고 수동 확인하는 보조 흐름은 유지한다.
7. DANGER의 실제 경로 변경에는 명확한 동의가 필요하다. 거절·불명확한 대답·무응답을 동의로 처리하지 않는다.
8. 생산 개입이나 DANGER 통합 시험에서 동의 후 추가한 경유지는 유지한다. 자동 삭제·자동 원복하지 않는다.
9. MILD는 공조 OFF면 ON, AUTO ON이면 OFF 확인 후 온도·풍량을 조절한다. 자동 원복은 추가하지 않았다.
10. 서버 연동 전 main을 다시 pull해 통합했다. 최신 리포트와 주행 개입 데이터 연결을 먼저 확인하고 구현하는 데 사용자가 동의했다.
11. 마지막 요청은 지금까지 변경을 커밋·푸시하고 Antigravity/Gemini 인수인계 문서를 아주 자세히 만드는 것이었다.

## 4. 개발 환경과 빌드

### 4.1 환경

| 항목 | 값 |
| --- | --- |
| OS / shell | Windows / PowerShell |
| 확인된 Gradle용 JDK | `C:\Users\SSAFY\.jdks\jbr-21.0.11` |
| JVM target | Java/Kotlin 17 |
| Android SDK | `C:\Users\SSAFY\AppData\Local\Android\Sdk` |
| adb | 위 SDK의 `platform-tools\adb.exe` |
| 에뮬레이터 | 마지막 확인 `emulator-5554` |
| AAOS 사용자 | 마지막 확인 `10`, 명령 실행 전 현재 사용자 확인 가능 |
| applicationId | `ai.pleos.playground.sample.vehicle.diagnostic` |
| 소스 namespace | `ai.pleos.playground.sample.vehicle` |
| MainActivity component | `ai.pleos.playground.sample.vehicle.diagnostic/ai.pleos.playground.sample.vehicle.MainActivity` |
| minSdk / compileSdk / targetSdk | 28 / 35 / 35 |

시스템 PATH에 Java 8이 잡혀 있던 환경이다. JDK를 지정하지 않으면 Gradle/AGP 실행 오류가 날 수 있다. Android Studio에 포함된 다른 JBR 버전을 무조건 쓰지 말고, 위에서 실제 빌드에 사용한 JDK 21을 우선 사용한다.

`local.properties`, `.gradle`, APK, 에뮬레이터 DB와 개인 환경 설정은 소스와 별도다. 새 환경에서는 Android SDK 위치를 설정해야 할 수 있다. 비밀 키를 문서나 Git에 넣지 않는다.

### 4.2 핵심 의존성

실제 버전의 기준 파일은 `gradle/libs.versions.toml`이다.

| 의존성 | 버전 |
| --- | --- |
| AGP | 8.8.2 |
| Kotlin | 2.0.0 |
| KSP | 2.0.0-1.0.24 |
| Room | 2.6.1 |
| Coroutines | 1.9.0 |
| Pleos Vehicle | 2.3.2 |
| Pleos NaviHelper | 3.0.5 |
| Pleos TextToSpeech / SpeechToText | 3.3.1 |

### 4.3 빌드와 단위 테스트

```powershell
Set-Location -LiteralPath 'C:\SSAFY\jyj\workspace\-Pleos_DriveProbe'
$env:JAVA_HOME = 'C:\Users\SSAFY\.jdks\jbr-21.0.11'

.\gradlew.bat :departure-core:test :departure-ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileReleaseKotlin --console=plain
```

최근 통과한 표준 테스트 합계는 **583개**다.

| 모듈 | 테스트 | 실패 / 오류 / 건너뜀 |
| --- | ---: | --- |
| `app` | 390 | 0 / 0 / 0 |
| `departure-core` | 149 | 0 / 0 / 0 |
| `departure-ui` | 44 | 0 / 0 / 0 |

이전 문서의 505, 514, 531개 등은 각각 당시의 결과다. 최신 main 리포트와 새 테스트가 통합된 현재 기준은 위 숫자다. 코드 변경 후에는 실제 XML 결과를 다시 확인한다.

`compileReleaseKotlin` 성공을 Release APK 설치·실행 검증으로 확대 해석하지 않는다. 실제 설치·실행한 APK는 Debug다.

### 4.4 APK 업데이트 설치와 실행

```powershell
$adb = 'C:\Users\SSAFY\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb -s emulator-5554 shell am get-current-user

& $adb -s emulator-5554 install --user 10 -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 install --user 10 -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

& $adb -s emulator-5554 shell am start --user 10 -W -n ai.pleos.playground.sample.vehicle.diagnostic/ai.pleos.playground.sample.vehicle.MainActivity
& $adb -s emulator-5554 shell pidof ai.pleos.playground.sample.vehicle.diagnostic
```

`applicationId`와 소스 package를 혼동하면 실행 대상이 틀린다. AAOS 사용자 10을 빼먹으면 설치했는데 앱이 보이지 않거나 다른 사용자 데이터로 실행되는 문제가 생길 수 있다. 위 사용자 값은 현재 기기에서 다시 확인한다.

기존 DB·설정·내비 경로 보존을 위해 `install -r`로 업데이트했다. 이번 인계 재현에 앱 삭제, `pm clear`, 에뮬레이터 초기화는 필요하지 않다.

## 5. 전체 코드 구조와 초기화 순서

모든 Kotlin 경로는 아래 디렉터리를 기준으로 적는다.

```text
app/src/main/java/ai/pleos/playground/sample/vehicle/
```

| 영역 | 주요 위치 | 역할 |
| --- | --- | --- |
| 전역 조립 | `DriveProbeApplication.kt` | DB·차량·내비·피로·개입·리포트 초기화 |
| 차량 SDK | `data/repository/VehicleRepositoryImpl.kt` 및 제어 관련 파일 | 차량 신호·공조 typed API·재조회 |
| 내비 SDK | `data/repository/NaviHelperRepositoryImpl.kt` | 현재 경로·TBT·즐겨찾기·최근 목적지·ETA·경유지 쓰기 |
| 피로 판단 | `condition/ConditionModule.kt`, `condition/engine/ConditionEngine.kt`, `condition/core/` | 생산/Replay 엔진·유효 창·피로 단계 |
| 자동 개입 | `intervention/InterventionController.kt`, `InterventionModule.kt` | 조건 게이트·쿨다운·취소·실행·저장 |
| 음성 | `intervention/AndroidVoiceConversation.kt`, `DrivingVoiceService.kt` 등 | 실제 TTS/STT·PCM 시간 추정·마이크 FGS |
| DANGER | `intervention/DangerNavigationAction.kt` | 제안·음성 동의·같은 제안 적용 |
| 시설·경로 | `navigation/` | TBT 순서·공식 파일·수동 확인·제안 토큰·경유지 검증 |
| 일반 UI | `ui/kd/DrivingAssistScreen.kt`, `DrivingAssistViewModel.kt`, `DrivingScreen.kt` | 주행 도움 준비·중지·시설 설정·주행 상태 |
| 개입 DB | `data/local/AppDatabase.kt`, `data/local/dao/InterventionHistoryDao.kt` | 실제/시험 이력 저장·조회·동기화 대기 |
| 주행 리포트 | `trip/TripModule.kt`, `TripRecorder.kt`, `source/` | 주행 집계·복구·실제 개입 결합·요약 발표 |

`DriveProbeApplication.onCreate()`의 순서:

1. `AppDatabase` 생성.
2. `VehicleRepositoryImpl.initialize()`.
3. 실제 `NaviHelperRepositoryImpl` 생성.
4. 공식 카탈로그와 수동 시설 확인 저장소를 같은 실제 내비 포트에 연결.
5. `ConditionModule.init(..., Mode.REAL)`.
6. `InterventionModule(...)` 생성.
7. `TripModule.init(this)`.

중요한 조립 구분:

- `realNaviHelperRepository`: 생산 휴게시설·실제 경로 변경용 SDK 인스턴스.
- `naviHelperRepository`: Debug 출발 전 화면에서는 `DemoNaviFallbackRepository`가 감쌀 수 있다.
- **생산 DANGER/시설 조회에 Demo fallback을 전달하지 않는다.** 가짜 경로·ETA로 쓰기 가능 제안을 만들면 안 된다.

## 6. 생산 피로 개입의 실행 조건과 단계

### 6.1 공통 게이트

생산 자동 개입은 다음 조건을 모두 확인한다.

- 자동 도움이 켜져 있다.
- 현재 `ConditionEngine.isDriving`이 true다.
- 기어가 `DRIVE`다.
- 현재 피로 판단 창 `lastWindowValid`가 true다.
- 단계가 `NORMAL`보다 높다.
- 현재 엔진과 같은 `TripRecorder` 주행 ID에 연결돼 있다.
- 실제 CAUTION/DANGER에는 `DrivingVoiceService.ready`도 필요하다.

`ConditionModule.engines`가 새 엔진을 알리면 이전 구독을 정리하고 새 엔진에 다시 연결한다. Replay와 생산을 같은 엔진으로 가정하지 않는다.

피로 엔진의 주행 구분은 P 이외 D/R/N에서 시작, P에서 종료다. 실제 개입은 그중 D에서만 허용한다. 주행 기록이 존재한다는 사실과 개입 허용 조건은 같지 않다.

### 6.2 쿨다운·상승·취소

- `InterventionPolicy`: 단계별 기본 쿨다운 5분.
- 성공뿐 아니라 실패·취소한 시도도 쿨다운을 사용한다.
- 아직 시도하지 않은 상위 단계는 다른 단계의 대기 시간과 관계없이 실행할 수 있다.
- 상위 단계 시작 전에 기존 음성·SDK 정리와 이력 저장이 끝나기를 기다린다.
- 주행 종료·모드 교체 시 세션과 쿨다운을 초기화한다.
- 데이터 무효화 등 같은 주행 내 취소는 같은 단계 쿨다운을 제거하지 않는다.
- `generation`으로 오래된 비동기 결과가 새 UI 상태를 덮어쓰지 못하게 한다.

### 6.3 MILD

현재 생산 정책:

1. 공조 전원·AUTO·온도·풍량 capability, 지원 영역과 값을 확인한다.
2. 공조 OFF면 ON 요청 후 실제 값 재조회로 확인한다.
3. 전원 확인 후 AUTO 현재값을 읽고, ON이면 OFF 요청 후 재조회한다.
4. 운전석 설정 온도 1℃ 하향.
5. 풍량 1단계 증가.
6. 지원 최소·최대 범위에서 제한하고 각 설정을 다시 조회한다.

capability 미지원, SDK 미초기화, 콜백 실패·시간 초과·재조회 불일치는 실제 실패다. 목업 성공으로 바꾸지 않는다. 앞 단계가 적용된 뒤 뒷 단계가 실패할 수 있으며 트랜잭션 원복은 없다.

과거에 온도/풍량/창문 개별 실제 왕복 제어를 확인한 이력이 있다. 그러나 새 전원 ON/AUTO OFF 준비까지 포함한 **전체 MILD 자동 실행을 실제 기기에서 성공했다고 보고하지 않는다**. 자동 창문 개방은 생산 MILD에 연결하지 않았다. `airCleaner`는 미구현이며 명시적으로 실패한다.

### 6.4 CAUTION

한국어 TTS 질문 → 실제 STT → 인식 문장·응답 시간·오류 저장 경로다. 문장이 인식되면 `ANSWERED`다. 현재 응답 유무나 반응 시간을 피로 점수에 다시 반영하지 않는다.

### 6.5 DANGER

`DangerNavigationAction`이 다음을 소유한다.

1. `RestNavigation.prepareProposal()`로 현재 경로의 시설과 실제 ETA를 확보.
2. 시설명·ETA·경유지 추가 여부를 음성으로 질문.
3. `VoiceResponse`로 명확한 동의/거절/불명확 응답을 구분.
4. 명확한 동의일 때 **같은 제안 토큰**을 `RestNavigation.accept()`에 전달.
5. 경로·목적지·옵션·시설 순서·최신성 등을 재검사하고 SDK 경유지 추가.
6. 경유지 재조회로 적용 여부 확인 후 저장.

`outcome=ACCEPTED`는 음성 동의 결과다. **내비 추가 성공과 동일하지 않다.** `navigationStatus=VERIFIED_WAYPOINT`와 실제 요청·재조회 근거를 별도로 확인한다. 경로 준비 실패 시 동의를 먼저 받고 나중에 임의 시설을 고르지 않는다.

## 7. 음성과 포그라운드 서비스

### 7.1 현재 백엔드와 과거 문서 차이

현재 `AndroidVoiceConversation`은 Pleos 음성 서비스 가용성을 확인하고, 실제 시간 측정 경로는 Pleos on-device 백엔드와 PCM/EPD 관련 경로를 사용한다. **초기 문서의 ‘Android 시스템 SpeechRecognizer만 연결했다’ 설명은 현재 생산 경로 전체의 설명으로 사용하면 안 된다.** 세부 fallback·가용성 정책은 `AndroidVoiceConversation.kt`, `SpeechBackendTest`, PCM/타이밍 테스트를 읽는다.

### 7.2 준비와 녹음

- 일반 앱의 정차 화면에서 마이크 권한과 음성 준비를 명시적으로 수행한다.
- `DrivingVoiceService`는 microphone 유형 FGS다.
- 준비만으로 녹음을 시작하지 않는다. 실제 질문 응답 구간에서만 마이크를 연다.
- 결과·시간 초과·취소 시 녹음과 SDK 세션을 정리한다.
- 알림으로 앱에 돌아오거나 준비를 해제할 수 있다.
- 앱 프로세스가 새로 시작됐다고 FGS를 자동 시작하지 않는다. 준비 상태는 다시 확인해야 한다.
- 화면이 보이지 않는 주행 중에 처음 마이크 서비스를 시작하는 방식으로 우회하지 않는다.

### 7.3 저장하는 시간 값

`responseMs`, `responseTiming`, `readyLatencyMs`, `captureLatencyMs`, `epdOnBytes`, `epdOffBytes`, `audioBytes`, `recognitionEnd`를 구분한다. 응답 시간은 발화 구간 및 SDK 이벤트 기반 추정치다. 시간 추정의 정확도나 운전자 피로 수준을 입증한 값으로 취급하지 않는다. PCM 본문을 주행 이력에 저장하거나 서버로 전송하는 기능은 추가하지 않았다.

과거 사용자 발화 확인 이력으로 CAUTION ‘괜찮아요’, DANGER 동의 ‘안내해’, 거절 ‘싫어’의 인식·결과·시간 표시를 확인했다. 당시 일부 DANGER 음성 시험은 경로를 쓰지 않는 계약이었다. 이를 새 공식 파일 기반 실제 경유지 추가까지 검증한 것으로 확대하지 않는다.

## 8. 공식 CSV 기반 휴게소·졸음쉼터 자동 확보

### 8.1 왜 파일이 필요한가

현재 사용한 NaviHelper 3.0.5의 TBT는 안내 종류·거리·설명으로 다음 시설의 경로 순서를 판단하는 데 쓴다. 일반 장소 검색 결과로 시설 좌표·native POI 전체를 얻는 API가 현재 연결돼 있지 않다. **TBT의 시설 이름만 가지고 실제 경유지에 필요한 좌표·POI가 자동으로 생기는 것은 아니다.**

이 빈 부분을 사용자가 제공한 공식 CSV로 보완했다. 별도의 장소 검색 API 키 없이 동작한다.

### 8.2 원본과 생성물

| 구분 | 원본 위치 | 건수 | 기준일 |
| --- | --- | ---: | --- |
| 휴게소 | `data/rest-stops/rest-areas.csv` | 215 | 2026-09-17 |
| 졸음쉼터 | `data/rest-stops/sleep-rest-areas.csv` | 253 | 2026-02-25 |

사용자가 제공했던 원래 파일 이름:

- `C:\Users\SSAFY\Downloads\전국휴게소정보표준데이터.csv`
- `C:\Users\SSAFY\Downloads\전국졸음쉼터표준데이터.csv`

저장소에는 원본 CP949 바이트를 보존한다. `.gitattributes`의 `data/rest-stops/*.csv -text`는 Git 줄바꿈 변환을 막는다. 생성기는 **원본 바이트 SHA-256**을 기록하므로 임의 UTF-8 변환이나 CRLF/LF 변경을 하지 않는다. 커밋 전 stash 복원 과정의 줄바꿈 차이를 확인해 원래 다운로드 파일 바이트로 복원했다.

현재 원본 해시:

```text
rest-areas.csv
361a42bf36bd1e3b1afe69cfff3ce34331d24ee5cc00a0d9929f56bcf7cbc31a

sleep-rest-areas.csv
e65632096e10fb363139202869cc4db87ce4c22aa6479a4d7d22570af0c7a432
```

생성:

```powershell
python scripts/build-rest-stop-catalog.py
```

출력:

```text
app/src/main/assets/navigation/public-rest-stops.json
schemaVersion = 1
facilities = 468
sources = 입력 파일별 파일명, SHA-256, 행 수
```

생성기는 위경도·필수 식별 항목·날짜 오류를 조용히 버리지 않고 실패시킨다. 원본을 갱신할 때 CSV와 JSON을 함께 커밋한다. 런타임은 JSON 자산을 읽으며 Python이나 CSV 다운로드가 필요하지 않다.

### 8.3 자동 연결 기준

`PublicRestStopAssets`가 IO에서 카탈로그를 지연 로딩하고 `PublicRestStopCatalog`가 시설 종류·정확한 이름·방향을 비교한다.

- `용인휴게소(강릉방향)`처럼 방향이 명시되고 해당 후보가 유일하면 자동 연결.
- 졸음쉼터도 종류를 유지하며 동일하게 비교.
- 방향 없는 TBT는 공식 데이터가 명시적 `양방향`이고 같은 이름 시설이 유일한 경우만 자동 연결.
- 이름만 유일하다는 사실로 같은 방향이라고 추측하지 않음.
- 같은 이름·방향 후보가 여러 개이면 자동 선택 거부.
- 현재 heading, 지도 직선거리, fuzzy 이름 유사도로 반대편 시설을 추정하지 않음.
- 기준일이 미래이거나 366일을 초과하면 자동 사용하지 않음.

`용인(강릉)` 같은 원본 이름은 앱 표시에서 `용인휴게소(강릉방향)`으로 정규화한다. 졸음쉼터 파일의 `인천기점 + 강릉종점` 같은 방향은 종점인 강릉을 방향 표기로 읽는다.

### 8.4 TBT 순서와 두 시설 종류

`TbtRoute.kt`:

- 휴게소: `TURN_ENTER_SERVICEAREA`, code 41 + 휴게소 이름 확인.
- 졸음쉼터: `TURN_RESTAREA`, code 99.
- code 41만 보고 휴게소라고 단정하지 않음. 하이패스센터를 구분.
- `restStopHints` / `nextRestStopHint`는 휴게소·졸음쉼터를 합쳐 **SDK 수신 순서**대로 처리.
- 앞에 분류 불가능 시설이 있으면 뒤 휴게소를 ‘다음 시설’이라며 건너뛰지 않음.
- 거리 숫자를 정렬해 최솟값을 다음 시설로 바꾸지 않음.
- 현재 구현은 마지막 `TURN_ARRIVE` code 37까지 확인하는 보수적 완전성 조건을 적용.

TBT에 후보가 없다고 전체 경로에 휴게시설이 없다고 단정하지 않는다. TBT 공급 범위가 제한될 수 있다. 실제 경로에서 반환 범위를 확인해야 한다.

### 8.5 POI와 공식 시설 ID를 혼동하면 안 됨

공식 CSV에는 Pleos native POI ID가 없다.

```text
RestPlace.id = PUBLIC_CATALOG:종류:노선번호:시설명:위도:경도
RestPlace.poiId = ""
RestPlace.poiSubId = ""
RestPlace.poiProvider = ""
```

`PUBLIC_CATALOG:...`, 도로 번호, 공공데이터 행 번호를 native POI로 넣지 않는다. 현재 방식은 **공식 시설 식별자 + 좌표**를 확보하는 것이다. 실제 native POI는 즐겨찾기·최근 목적지 등의 SDK 결과가 제공할 때만 그대로 쓴다.

### 8.6 보조 조회와 수동 확인

현재 순서는 `PleosTbtServiceAreaSource.query()`를 직접 확인하면 된다.

1. 공식 카탈로그 방향 일치.
2. 명시적 방향이 있고 공식 연결이 안 될 경우 실제 즐겨찾기·최근 목적지 자동 일치.
3. 현재 경로에 연결된 수동 확인 정보.

`automaticServiceAreaPlaces()`는 실제 즐겨찾기와 최근 목적지를 조회한다. 하나가 실패하면 다른 실제 결과를 사용할 수 있지만, 둘 다 실패하면 빈 성공으로 바꾸지 않는다.

방향이 모호할 때 일반 앱 휴게소 설정에서 공식 후보를 선택·확인할 수 있다. 즐겨찾기 등록이 필수는 아니다. `ReviewedServiceAreas`의 확인은 routeId·목적지·옵션·시설 순서·24시간 유효성에 묶인다. 과거 확인을 새 경로로 자동 이전하지 않는다. 확인·미리보기만으로 내비 경로를 쓰지 않는다.

## 9. 실제 내비 제안·쓰기·재조회 계약

핵심: `RestNavigation.kt`, `NaviHelperRepositoryImpl.kt`, 경유지 관찰/확인 관련 테스트.

- 현재 목적지와 실제 안내 상태가 있어야 한다.
- 기존 경유지가 있는 경우 현재 추가 흐름은 거부한다. 임의로 기존 경유지를 교체하지 않는다.
- 실제 `requestTrip`의 거리·ETA·경로 옵션을 확인한다.
- ETA 조회의 별도 경로 거리를 기존 경로를 따른 거리로 위장하지 않는다.
- `SOURCE_MAX_AGE_MS = 30_000`.
- `PROPOSAL_TTL_MS = 60_000`.
- `preview()`는 쓰기 가능한 동의 토큰을 생성하거나 기존 토큰을 바꾸지 않는다.
- `accept()`는 생성한 제안의 identity·만료·소비 여부와 경로 맥락을 다시 확인한다.
- 동의 이후 시설/방향/경로가 바뀌면 기존 동의로 새 시설을 추가하지 않는다.
- SDK 요청 완료만으로 적용 성공으로 간주하지 않고 경유지를 다시 읽는다.
- 유효 좌표가 있으면 시설과 약 50m 이내인지 확인한다. 좌표가 없을 때 native POI 정보가 실제로 존재하는 경우의 엄격한 비교 경로가 별도로 있다.
- 공식 파일 ID로 native POI 일치를 위장하거나 0/0 좌표를 정상 좌표로 보정하지 않는다.
- 요청 후 취소는 이미 경유지가 추가됐을 수 있다. `requestIssued`와 `CANCELLED_AFTER_REQUEST_UNCONFIRMED` 등을 보존하고 자동 삭제·자동 재시도하지 않는다.

현재 출처 기록 예:

| source | 의미 |
| --- | --- |
| `PLEOS_TBT_PUBLIC_REST_STOP_CATALOG` | 공식 파일 자동 방향 일치 |
| `PLEOS_TBT_REVIEWED_PUBLIC_CATALOG` | 공식 후보를 사람이 현재 경로에서 확인 |
| `PLEOS_TBT_AUTOMATIC_NATIVE_FACILITY` | 실제 내비 즐겨찾기/최근 목적지 자동 일치 |
| `PLEOS_TBT_REVIEWED_NAVI_BOOKMARK` | 실제 내비 시설 수동 확인 |

## 10. 일반 앱 사용자 흐름

일반 기능은 Debug 전용이 아니며 Release 코드에도 포함된다.

1. 정차 상태에서 홈 → **주행 도움**.
2. **주행 도움 켜기**로 권한 및 음성 준비. 첫 권한 허용 후 준비 동작을 이어서 처리한다.
3. 준비 상태와 자동 도움 상태 확인.
4. **휴게소 설정**에서 현재 경로의 자동 시설 확보 및 미리보기 확인. 모호한 시설은 공식 후보에서 수동 확인.
5. 실제 주행에서는 기존 주행 화면으로 전환되고 엔진 판단에 따라 자동 개입.
6. 음성 재생·응답 대기·인식·경유지 적용 확인 상태를 구분해 표시.
7. 정차 후 도움 중지. 이미 적용한 공조나 경유지는 유지.

설정 화면은 실제 차량 `P`, 알려진 속도 0, `UxRestrictionSource.CAR_SERVICE`, 화면 사용 가능 상태를 요구한다. 신호 미확인·목업 UX 제한을 정차 허용으로 취급하지 않는다.

사용자 자동 도움 선택은 `driving_assist_settings.automaticEnabled`에 저장한다. 기존 기본값은 ON이지만 음성 FGS 준비는 별도다. 디버그 시험이 잠깐 자동 도움을 끄고 복구하는 것은 사용자 영구 설정을 바꾸는 동작과 구분한다.

## 11. 진단 시연·음성 시험·실제 효과 시험 구분

| 기능 | 실제 음성 | 실제 경로 쓰기 | 종료 후 경유지 | 실제 주행 리포트 |
| --- | --- | --- | --- | --- |
| Replay / 단계 시연 | 없음 | 없음 | 해당 없음 | 제외 |
| 음성 시험 5·6번 | 있음 | 없음 | 해당 없음 | 제외 |
| 내비 추가·복원 시험 | 없음 | 있음 | 시험이 추가한 경유지 복원 경로 | 제외 |
| DANGER 통합 시험 | 있음 | 동의 시 있음 | 유지 | 제외 |
| 생산 자동 DANGER | 있음 | 동의 시 있음 | 유지 | 포함 |

`DangerNavigationProbeController`:

- Debug 에뮬레이터 전용 정차 시험.
- 홈 → 개발자 도구 → DANGER 통합.
- 생산과 같은 `DangerNavigationAction` 사용.
- 명시적인 실제 효과 확인과 정차·음성 준비 게이트.
- 실행 제한 90초, 취소 후 이력 저장 최대 10초.
- 요청 전/후 취소 메타데이터 보존.
- 처리 중 프로세스 종료 마커를 저장하고 재시작 시 미확인 상태 표시.
- 자동 재시도·경유지 자동 삭제 없음.
- 화면/탭 이탈만으로 해당 통합 시험을 취소하지 않음.

기존 음성 시험 6번에 경로 쓰기를 끼워 넣지 않는다. 현재 시험들의 계약이 다르기 때문에 버튼 이름만 보고 동일 기능으로 합치면 안 된다.

## 12. 개입 이력 DB와 JSON 계약

### 12.1 공용 DB

```text
파일: drive_probe_local.db
Room: AppDatabase
버전: 2
개입 테이블: intervention_histories
```

v1→v2에는 `driver_conditions`를 추가하는 migration이 있다. 이번 공식 파일/리포트 연결 때문에 schema version을 올리거나 사용자 DB를 초기화하지 않았다.

`InterventionHistoryEntity`:

| 필드 | 의미 |
| --- | --- |
| `id` | 로컬 자동 증가 Long |
| `sessionId` | 생산에서는 `TripRecorder.tripId`와 동일한 값 |
| `timestamp` | 현재 구현은 저장 시점의 wall clock 기본값. 시작 시점과 같다고 단정하지 않음 |
| `interventionLevel` | 1 MILD / 2 CAUTION / 3 DANGER |
| `fatigueScore` | 0~100. 엔진의 0~1 점수를 저장 시 변환 |
| `triggerReason` | 엔진 사유 코드 또는 시험 출처 |
| `actionDetails` | version 2 JSON 중심 실행 메타데이터 |
| `userResponse` | 인식된 응답 문장 또는 null |
| `isSynced` | 기본 false. 외부 서버 업로드 성공을 뜻하는 값은 아직 생성하지 않음 |

### 12.2 생산 actionDetails

`InterventionModule.save()`가 현재 계약의 기준이다.

```json
{
  "version": 2,
  "simulation": false,
  "outcome": "ACCEPTED",
  "details": "사람이 읽는 결과 설명",
  "responseMs": 1000,
  "responseTiming": "실제 저장된 측정 출처",
  "readyLatencyMs": null,
  "captureLatencyMs": null,
  "epdOnBytes": null,
  "epdOffBytes": null,
  "audioBytes": null,
  "recognitionEnd": null,
  "consent": "ACCEPT",
  "navigationStatus": "VERIFIED_WAYPOINT",
  "navigation": {},
  "error": null,
  "platformCode": null,
  "recognitionBackend": "실제 백엔드 enum 값",
  "calibrating": false
}
```

위 JSON은 필드 설명용 예시다. responseTiming/backend 값을 문자열 그대로 복사하거나 빈 navigation을 실제 성공 근거로 생성하지 않는다. `RestNavigationHistory.kt`의 실제 직렬화는 제안·시설 종류·데이터 기준일·native POI 가용성·실제 요청 여부·전후 경로 정보를 구분해 담는다.

### 12.3 실제/시험 조회

- `getAllHistories()`, `getHistoriesBySession()`: 진단 원본 조회.
- `getDrivingHistories()`: 생산 저장기의 `"simulation":false` 표식과 알려진 시험 trigger 제외.
- `getUnsyncedHistories()`: 같은 실제 이력 제한 + `isSynced=0`.
- `getRestAdviceForTrip(tripId)`: 해당 ID의 단계 2·3과 시험 trigger 제외. 이후 리포트 어댑터에서 실제 JSON boolean 및 source를 추가 확인.

새 리포트 어댑터는 SQL 문자열 검색만으로 진위를 판정하지 않고 JSON을 파싱한다. 반면 기존 일반 목록/동기화 대기 조회는 생산 직렬화 형태를 전제로 한 LIKE 필터를 쓴다. 향후 다른 writer나 공백이 다른 JSON을 도입하면 이 차이를 검토해야 한다. 이번 변경으로 모든 기존 조회를 JSON SQL 함수 기반으로 바꾸지는 않았다.

## 13. 최신 main의 주행 요약·리포트 연동

### 13.1 별도 HTTP 전송이 아니다

새 main의 5번 파트는 `trip/` 아래에 있다. 다른 파트의 값을 함수·DB 어댑터로 받는 구조이며, 개입 영역의 입력 계약은 아래다.

```kotlin
data class RestAdvice(
    val count: Int,
    val respondedCount: Int,
    val source: DataSource,
)

fun interface RestAdviceSource {
    suspend fun countFor(trip: CompletedTrip): RestAdvice
}
```

기존 `MockRestAdviceSource`는 CAUTION/DANGER 단계 진입 횟수를 권고 횟수로 간주하고 전부 응답했다고 처리했다. 현재 `TripModule.init()`은 이를 **`InterventionRestAdviceSource`로 교체**한다. Mock 클래스 자체는 다른 시연/테스트를 위해 남아 있지만 생산 모듈에 연결하지 않는다.

### 13.2 공통 ID와 종료 정산 순서

```text
TripRecorder가 tripId 생성 또는 체크포인트에서 복구
  → onTripStarted(engine, tripId)
  → InterventionModule.bindReportTrip(engine, tripId)
  → 같은 엔진의 자동 개입만 시작
  → InterventionRecord.sessionId = tripId
  → 실제/실패/취소 이력 저장

주행 종료
  → 마지막 TripSnapshot 저장
  → onTripEnding(tripId)
  → reportSession 연결 해제
  → controller.finishSession(tripId)
  → 해당 ID의 취소 정리 및 Room insert 완료 대기
  → InterventionRestAdviceSource.countFor(trip)
  → TripRecordEntity 저장 성공
  → checkpoint 삭제
  → lastRecord 및 요약 표시 이벤트
```

`InterventionModule`의 컨트롤러 접근은 Main dispatcher로 통일한다. `TripRecorder`는 별도 scope에서 동작하므로 콜백에서 Main으로 전환한다. 엔진 객체와 ID를 같이 연결하고, 오래된 combine emission이 종료된 ID를 재시작하지 않도록 현재 바인딩을 다시 확인한다.

`InterventionController.update(..., reportTripId=...)`가 공통 ID를 받는다. 인자를 주지 않는 기존 단위 테스트 등의 독립 사용은 자체 UUID 경로가 남아 있다. 생산 `InterventionModule`은 유효한 리포트 바인딩이 없을 때 `tripActive=false`로 전달해 별도 UUID 생산 개입을 시작하지 않는다.

`finishSession(id)`는 컨트롤러가 기어 구독에서 먼저 reset됐더라도 `inFlight`에 남아 있는 동일 ID 작업을 기다린다. 저장 완료 전 리포트가 0건으로 고정되는 경쟁 조건을 막는 핵심이다.

### 13.3 집계 의미

- `sessionId == tripId`로 묶는다. 단순 timestamp 범위로 결합하지 않는다.
- 단계 2·3만 포함한다. MILD는 별도 원본 이력으로 남는다.
- JSON `simulation`이 실제 boolean false인 기록만 포함한다. 누락, 문자열 `"false"`, 잘못된 JSON은 제외한다.
- `VOICE_TEST`, `DANGER_NAVIGATION_PROBE`, `BACKGROUND_STAGE_PROBE`를 trigger와 JSON source에서 제외한다.
- `userResponse`가 공백이 아닌 인식 문장일 때만 respondedCount를 증가시킨다.
- 거절·불명확한 대답도 음성 응답이다. 동의율과 같지 않다.
- 음성 답변 뒤 경로 적용이 실패·취소돼도 답변을 유지한다.
- `ACCEPTED` 같은 outcome만으로 인식 문장을 만들어 응답 횟수를 올리지 않는다.
- 실패·취소·사용 불가인 자동 시도도 저장된 이력이 있으면 count에 포함된다. 실제 휴식·도착·성공 횟수가 아니다.
- 실제 이력이 없으면 `0/0/REAL`.
- Replay 주행은 `0/0/MOCK`으로 별도 취급한다.

### 13.4 저장과 화면 소비

```text
파일: trip_local.db
Room: TripDatabase
버전: 2
테이블: trip_records
```

기존 `TripRecordEntity`의 필드를 그대로 사용하므로 새 migration이 필요하지 않았다.

- `restAdviceCount`
- `restRespondedCount`
- `restAdviceSource = REAL / MOCK`

`TripSummaryContent`, 기간 통계, 가족 요약 텍스트, 운전 리포트는 저장된 주행 레코드의 값을 사용한다. 다만 기존 main의 `familyShareSender`는 **`MockFamilyShareSender`**로 외부 전송 성공을 구현한 상태가 아니다. 운전 조건 준수와 일부 출발 전 결과에도 Mock fallback이 남아 있다. 휴식 권고가 REAL이 됐다고 리포트 전체가 모두 실제 연동이라고 말하면 안 된다.

### 13.5 복구와 현재 한계

- `trip_checkpoint`에 주행 중 약 15초 간격으로 스냅샷 저장.
- 실제 주행이 일정 시간 내 계속되면 저장된 `tripId`로 이어감. 현재 복구 기준은 최대 10분.
- 종료 직전 마지막 스냅샷을 남기고, 성공 저장 후에만 삭제.
- 집계/DB 실패 시 성공한 요약을 발표하지 않는다.
- 개입 저장 실패를 컨트롤러에서 감지하면 종료 정산에 오류를 전달한다.
- 기존 Mock 요약과 서로 다른 UUID의 과거 개입을 시간 추정으로 소급 연결하지 않는다. 새 연결 이후 주행부터 적용한다.
- 상세 동의/내비 결과는 원본 JSON에 있고 현재 리포트 필드는 횟수·응답 수·출처뿐이다.

추가 개발 시 살펴볼 구현 한계도 있다. 아래는 이번 기기 시험으로 모두 재현한 버그 목록이 아니라 코드 구조상 후속 검토가 필요한 지점이다.

1. 체크포인트 저장소는 진행 중 한 건을 보관한다. 집계 실패 후 바로 다음 주행이 시작되면 이전 실패 건 보존·재시도 정책을 별도 검토해야 한다.
2. 최종 스냅샷에 ‘종료 확정/집계 재시도 대기’를 구분하는 별도 상태가 없다. 실패 후 앱 재시작 때 복구 판정이 원래 의도와 맞는지 확인할 여지가 있다.
3. 프로세스 종료 전에 DB에 쓰지 못한 개입 본문을 재구성할 영속 큐가 없다. 체크포인트만으로 누락 본문이 복원되지는 않는다.
4. 마지막 저장 실패 표식은 컨트롤러 메모리에 있다. 프로세스가 종료되면 해당 실패의 세부 원인이 사라질 수 있다.

이 한계를 해결할 때 테스트 없이 정상 이력을 삭제하거나 실패 건을 0/0 성공으로 덮어쓰지 않는다.

## 14. 실제 수행한 검증과 미수행 검증

### 14.1 최신 리포트 연결 검증

최신 변경에서 다음 계측을 에뮬레이터에서 직접 실행해 **4개 테스트를 통과**했다.

```text
TripInterventionReportTest
  exactTripAndExplicitProductionSpeechDetermineCounts
  reportWaitsForLateInsertAndUsesRecoveredTripId
  failedAggregationKeepsCheckpointAndDoesNotPublishReport

DrivingHistoryIsolationTest
  onlyExplicitProductionRecordsAppearInDrivingReports
```

가짜 차량 엔진·실행 함수와 **인메모리 Room DB**를 사용했다. 실제 DAO·어댑터·Recorder 경로를 확인했으며, 사용자의 실제 주행 이력이나 내비 경로를 쓰지 않았다.

확인한 내용:

- 주행 ID가 다른 이력 제외.
- MILD 및 알 수 없는 단계 제외.
- 시연·시험·누락/잘못된 simulation 제외.
- 공백 있는 정상 JSON의 실제 false 처리.
- 거절/불명확 응답/응답 후 경로 실패·취소는 응답으로 유지.
- outcome만 있고 인식 문장이 없으면 응답으로 세지 않음.
- 저장 timestamp가 종료 범위 밖이어도 동일 ID 이력 포함.
- 컨트롤러 취소 이력이 늦게 저장될 때 report 발표 대기.
- 복구한 ID 사용.
- 집계 실패 시 레코드 미저장·미발행 및 체크포인트 유지.

단위 테스트에서도 공통 ID 사용·새 주행 쿨다운 초기화, 컨트롤러 reset 이후 저장 대기, 저장 실패 전달을 확인했다.

### 14.2 이전 시설/일반 앱 검증

공식 시설 구현 당시 다음을 확인했다.

- 앱 자산의 468개 시설 로딩.
- 용인휴게소 강릉 방향과 용인졸음쉼터 강릉 방향 구분.
- 실제 SDK에서 native POI 없이 휴게소 좌표로 ETA **1,798초** 반환. 읽기 전용이었다.
- 실제 즐겨찾기/최근 목적지 조회. 당시 결과는 각각 1개/1개였으며 현재 고정 데이터가 아니다.
- 일반 홈 → 주행 도움 → 실제 FGS 준비 → 시설 설정 조회 → 도움 중지 → 사용자 설정 복구.
- 새 DANGER 시험 화면 진입 및 시작 게이트.

용인 좌표 참고:

```text
휴게소:   37.245583,   127.2419801
졸음쉼터: 37.28861576, 127.1387267
```

위 수치는 개발 당시 조회 예시다. 현재 기기 경로와 무관하게 자동으로 목적지를 이 좌표로 바꾸지 않는다.

### 14.3 아직 성공했다고 말하면 안 되는 것

- 공식 CSV 좌표를 실제 경유지로 추가하고 재조회 확인하는 전체 기기 흐름.
- 휴게시설이 있는 실제 안내 경로에서의 전체 DANGER 이름/ETA 질문·동의·유지·취소 경계.
- 새 공조 전원/AUTO 준비를 포함한 전체 MILD 실제 자동 실행.
- 실제 유효 피로 판단부터 장시간 백그라운드 자동 개입까지의 연속 주행 시험.
- 실차 동작.
- 앱 프로세스가 종료된 뒤 자동 개입 지속 또는 자동 재시작.
- 외부 서버 인증·업로드·재시도·중복 방지.
- 리포트의 모든 항목 및 가족 공유가 실제 서버에 연결됐다는 주장.

## 15. 선택 계측 재현 명령

먼저 APK 둘을 빌드·업데이트 설치한다. 전체 `connectedDebugAndroidTest`에는 경로·설정을 변경하는 기존 테스트가 포함될 수 있으므로 읽기/집계 확인을 위해 무조건 전체 실행하지 않는다.

### 15.1 리포트 집계·시험 분리 4개

```powershell
& $adb -s emulator-5554 shell am instrument --user 10 -w -r -e class 'ai.pleos.playground.sample.vehicle.intervention.TripInterventionReportTest,ai.pleos.playground.sample.vehicle.intervention.DrivingHistoryIsolationTest' ai.pleos.playground.sample.vehicle.diagnostic.test/androidx.test.runner.AndroidJUnitRunner
```

### 15.2 공식 시설·실제 조회·일반 도움 화면

```powershell
& $adb -s emulator-5554 shell am instrument --user 10 -w -r -e class 'ai.pleos.playground.sample.vehicle.intervention.PublicRestStopAssetsTest,ai.pleos.playground.sample.vehicle.intervention.NativeServiceAreaDiscoveryTest,ai.pleos.playground.sample.vehicle.intervention.DrivingAssistAppTest' ai.pleos.playground.sample.vehicle.diagnostic.test/androidx.test.runner.AndroidJUnitRunner
```

일반 앱 시험은 음성 준비와 설정을 일시적으로 사용하고 복구하는 동작이 있다. 실행 전 해당 테스트 소스를 읽어 자신의 작업과 충돌하지 않는지 확인한다. 시설 조회 성공도 현재 내비 SDK 상태·서비스 연결에 영향을 받는다.

실제 쓰기 시험은 `docs/danger-navigation-probe.md`, `docs/rest-navigation.md`, 테스트 소스의 opt-in 조건을 확인하고 현재 목적지·경유지를 기록한 뒤 수행한다. 이번 문서 작성에서는 새 경로 쓰기 시험을 실행하지 않았다.

## 16. 외부 서버 연동: 다음에 실제로 구현해야 할 일

### 16.1 지금 없는 것

- 확인된 주행 개입 수신 API endpoint.
- base URL 및 환경 구분.
- driver/trip 식별자와 인증 방식 합의.
- 주행 개입 DTO 및 서버 스키마.
- HTTP client와 개입 업로드 워커.
- 서버 응답에 따른 실제 `isSynced` 변경 흐름.

저장소 조사 당시 `driver-condition`의 `/v1/drivers/me/driver-condition` 관련 TODO는 있었지만 주행 개입 API 계약으로 사용할 수는 없었다. 임의로 `/interventions` 경로를 만들거나 임시 200 성공을 넣지 않았다.

### 16.2 먼저 확인할 계약

백엔드 담당자 또는 연결된 API 문서에서 아래를 확인한다. 키/토큰 본문을 대화나 Git에 넣을 필요는 없다.

| 항목 | 확인 내용 |
| --- | --- |
| 수신 단위 | 개입 이벤트별, 주행 종료별 일괄, 두 가지 모두인지 |
| 식별자 | local tripId/sessionId를 서버 tripId와 어떻게 연결할지 |
| 인증 | 사용자 토큰/기기 인증/환경별 구성 방식 |
| 멱등성 | 재시도 때 같은 이벤트 중복 저장 방지 키 |
| 성공 판정 | 일부 성공 응답, 승인된 이벤트 ID 목록 여부 |
| 시간 | 시작/완료/저장 시각과 timezone·epoch 단위 |
| 점수 | 0~100인지, 엔진 0~1인지 |
| 단계 | 숫자 enum 또는 문자열 |
| 응답 | 인식 문장 본문 필요 여부, 응답 유무·시간만으로 충분한지 |
| 동의/내비 | consent, navigationStatus, requestIssued를 별도로 받을지 |
| 재시도 | 오프라인·만료 인증·4xx/5xx·backoff·중단 정책 |
| 보존 | 성공 후 로컬 데이터 보존/삭제와 보관 기간 |
| 시험 데이터 | 별도 환경에 보낼지, 완전히 제외할지 |

### 16.3 구현 방향 제안

API 합의 뒤 아래 순서로 구현하는 것이 현재 코드와 잘 맞는다. 이는 아직 구현한 기능이 아니다.

1. 실제 계약에 맞는 DTO와 mapper를 원본 Entity/JSON과 분리.
2. 시험/Replay/legacy 불명 이력 제외 정책을 mapper 또는 공통 필터에서 다시 확인.
3. 서버와 합의한 안정적인 이벤트 ID/멱등 키를 추가. 로컬 auto-increment id만으로 여러 기기의 이벤트를 전역 식별하지 않음.
4. 오프라인 큐·인증·재시도 가능한 작업 스케줄링을 조립.
5. 서버가 확실히 수신한 ID만 `markAsSynced(ids)` 처리. 부분 성공이면 미승인 건을 남김.
6. 전송 실패가 실제 공조/음성/내비 실행을 막거나 반복시키지 않도록 분리.
7. 중복 재시도, 부분 성공, 토큰 만료, 앱 재시작, 시험 제외를 테스트.
8. 앱 내부 리포트는 로컬 실제 이력으로 계속 계산. 네트워크 실패를 실제 개입 0건으로 바꾸지 않음.

현재 `getUnsyncedHistories()`는 미동기화 대상 조회 준비일 뿐이다. 워커가 있다는 뜻이 아니고, 외부 전송 성공 증거도 아니다.

## 17. 후속 업무 우선순위와 완료 기준

실제 순서는 이후 사용자 요청에 맞춘다. 다음은 현재 남은 일을 구현과 검증으로 나눠 제안한 목록이다.

### 17.1 구현이 남은 일

**A. 외부 서버 개입 업로드**

- 선행 조건: 실제 API 계약 확보.
- 범위: DTO/mapper/client/인증/재시도/멱등성/성공 mark.
- 완료 기준: 실제 서버 수신 확인, 실패 재시도 및 중복 방지 확인, 시험 이력 제외, 기존 로컬 리포트 정상 유지.

**B. 리포트 상세 개입 지표 확장**

- 현재 계약은 count/respondedCount/source만 있다.
- 추가할 수 있는 항목: MILD 시도·성공/실패, 응답 시간 요약, 동의/거절/불명확, 경유지 확인 성공/미확인, 실제 요청 여부.
- 먼저 팀과 지표 의미를 합의한다. 동의율을 실제 휴식률로 표시하지 않는다.
- 저장 필드를 늘리면 Room migration과 과거 값 의미를 설계한다.

**C. 종료 집계 실패의 영속 재시도**

- 현재 1건 체크포인트와 메모리 실패 표식의 한계 보완.
- 완료 기준: 실패 후 새 주행/앱 재시작에도 이전 실패 건이 조용히 사라지거나 정상 0건으로 발표되지 않음.

**D. TBT 방향 불충분 시 자동 선택 고도화**

- 현재는 수동 후보 확인으로 남는다.
- 실제 경로 링크/진행 차로와 시설을 연결할 검증 가능한 데이터가 필요하다.
- heading/직선거리/이름 유일성만으로 임의 자동 선택하는 변경은 현재 정책을 충족하지 않는다.

**E. 오래된 문서 정리 및 다른 파트와의 통합**

- 과거 문서의 목업/음성/졸음쉼터 제외 설명은 일부 오래됐다.
- 다른 파트의 조건 준수 구현 브랜치가 있다면 사용자 범위에 맞춰 검토한다.
- main 업데이트 시 `DriveProbeApplication` 초기화와 `TripModule` 실제 개입 source 연결을 보존한다.

### 17.2 구현은 있고 추가 검증이 남은 일

- 공식 좌표만으로 SDK 경유지 추가·재조회 가능 여부.
- 실제 휴게시설 경로의 전체 DANGER 음성 동의/거절/무응답/취소/경로 변경.
- 전원 ON·AUTO OFF를 포함한 전체 MILD.
- 백그라운드/내비 전면 전환 중 연속 실행과 저장.
- 실제 주행 종료 요약의 화면 표시와 기간 통계 반영을 실사용 조건에서 확인.

새 코드가 필요하지 않은 확인도 있으므로 ‘구현 미완료’와 ‘기기 검증 미완료’를 구분해 보고한다.

## 18. 자주 막히는 상황과 확인 순서

### 18.1 앱 실행/빌드가 안 됨

1. `JAVA_HOME`과 Gradle JDK가 맞는지 확인.
2. `gradlew.bat`를 저장소 루트에서 실행했는지 확인.
3. SDK 위치와 adb 연결 확인.
4. applicationId `.diagnostic`와 MainActivity component 확인.
5. 현재 AAOS 사용자 확인.
6. 설치 APK가 최신인지 확인.
7. `AndroidRuntime` 로그와 프로세스 존재 여부 확인.

원인을 확인하기 전에 앱 삭제나 전체 DB 초기화로 해결하려 하지 않는다. 이전 대화의 실행 문제 원인을 모두 하나로 단정할 수는 없으며 위는 현재 환경에서 흔히 혼동한 지점이다.

### 18.2 자동 개입이 안 됨

1. 실제 모드/현재 엔진인지.
2. P가 아닌 주행 세션인지와 D 기어인지.
3. `lastWindowValid`인지. 단순히 속도를 올렸다는 사실만으로 유효 피로 창은 아님.
4. 자동 도움 설정 ON인지.
5. 같은 엔진의 공통 tripId가 연결됐는지.
6. CAUTION/DANGER 음성 준비가 됐는지.
7. 해당 단계 5분 쿨다운인지.
8. 다른 취소 정리/시험이 자원을 쓰는지.

단계 시연 버튼은 실제 효과를 내는 우회 버튼이 아니다. 실제 개입이 안 된다고 시연이 성공한 결과를 생산 성공으로 대체하지 않는다.

### 18.3 휴게시설을 자동 확보하지 못함

1. 안내 중/현재 목적지/routeId가 실제인지.
2. TBT가 도착까지 반환됐는지.
3. 다음 시설 앞에 분류 불가능 항목이 있는지.
4. 시설 종류·정확한 이름이 공식 목록과 일치하는지.
5. TBT에 방향이 있는지 또는 공식 양방향 시설인지.
6. 파일 기준일이 유효한지.
7. 실제 SDK ETA가 양수이고 경로 옵션이 일치하는지.
8. 방향이 모호하면 정차 시설 설정에서 공식 후보 확인.

### 18.4 동의했는데 경유지 성공이 아님

`outcome`, `consent`, `navigationStatus`, `requestIssued`, 전후 snapshot을 같이 확인한다. 제안 만료, 경로 변경, 기존 경유지, SDK 권한/연결, 좌표/POI 재조회 미확인 등이 원인일 수 있다. 경유지가 실제로 생겼는데 확인만 실패한 경우도 있어 자동 재시도하면 중복될 수 있다.

### 18.5 리포트가 0건이거나 임시로 보임

- 새 연결 이후 종료된 생산 주행인지 확인.
- 원본 이력 `sessionId`와 보고서 `tripId` 비교.
- MILD만 실행했다면 휴식 권고는 0건이 맞음.
- 시연·음성/DANGER 시험이면 제외가 맞음.
- 응답 문장이 없으면 respondedCount는 0.
- 기존에 저장된 Mock 요약을 보고 있지 않은지 확인.
- 개입 저장/집계 실패 로그 확인.
- 다른 리포트 항목의 Mock 표시를 휴식 권고의 출처와 혼동하지 않음.

## 19. 로그와 디버그 명령

읽기 중심 로그:

```powershell
& $adb -s emulator-5554 logcat -d -t 200 -s AndroidRuntime:E Trip:I Intervention:I VehicleControl:I
```

과거 관련 태그로 `Condition`, `VoiceTest`, `DangerProbe`도 사용한다. 필요한 부분만 읽고 사용자의 전체 로그/음성 응답을 불필요하게 외부에 복사하지 않는다.

다음은 Debug 전용으로 **앱 상태나 주행 기록을 바꾸는** 명령이다. 지금 당장 실행하라는 뜻이 아니다.

```powershell
# 실제 효과 없이 별도 시연 이력 생성
& $adb shell am broadcast --user 10 -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_SIMULATE --es level MILD

# 진행 중인 기록을 강제 종료하므로 필요할 때만 실행
& $adb shell am broadcast --user 10 -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.END

# 마지막 요약 표시
& $adb shell am broadcast --user 10 -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.SHOW

# 로컬 HTML 리포트 생성. 로그에 저장 경로 표시
& $adb shell am broadcast --user 10 -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.REPORT
```

TripModule의 시연 기록 seed/clear 브로드캐스트는 사용자의 리포트 필터나 시연 기록을 바꾼다. 이번 인계를 확인하기 위해 실행할 필요가 없다.

## 20. 중요한 단위 테스트 위치

`app/src/test/java/ai/pleos/playground/sample/vehicle/` 기준:

| 파일/영역 | 보호하는 계약 |
| --- | --- |
| `data/repository/MildHvacPreparationTest.kt` | 전원/AUTO 준비 순서·실패·취소 |
| `data/repository/VehicleControlTest.kt` | capability·영역·범위·제어 결과 |
| `data/repository/ControlProbeTest.kt` | 실제 효과 시험과 복원 계약 |
| `intervention/InterventionControllerTest.kt` | 게이트·ID·저장·종료 대기·실패 |
| `intervention/InterventionCleanupTest.kt` | 선행 취소 정리와 새 작업 순서 |
| `intervention/InterventionPolicyTest.kt` | 단계별 쿨다운 |
| `intervention/DangerNavigationActionTest.kt` | 동일 제안 동의·적용·취소 결과 |
| `intervention/DangerNavigationProbeControllerTest.kt` | 시험 수명·90초 제한·복구 마커 |
| `intervention/InterventionSpeechActionTest.kt` | 응답/동의/무응답 구분 |
| `intervention/VoiceResponseTest.kt` | 명확한 긍정·거절·불명확 처리 |
| `intervention/PcmResponseTimingTest.kt` 등 | PCM/타이밍 출처·측정 한계 |
| `navigation/PublicRestStopCatalogTest.kt` | 종류·방향·기준일·공식 자동 후보 |
| `navigation/AutomaticServiceAreaResolverTest.kt` | 실제 시설의 명시 방향 일치 |
| `navigation/TbtRouteTest.kt` | TBT 종류·순서·완전성 |
| `navigation/RestNavigationTest.kt` | 제안·검증·동의 후 적용 |
| `navigation/ReviewedServiceAreasTest.kt` | 현재 경로 수동 확인·만료 |
| `navigation/WaypointObservationTest.kt` | 실제 재조회 시설 식별 |
| `navigation/WaypointWriteConfirmationTest.kt` | 요청 후 적용 확인 |
| `trip/TripAccumulatorTest.kt` | 주행 단계/시간/점수 집계 |
| `trip/TripCheckpointTest.kt` | 체크포인트 직렬화·복구 |
| `trip/TripSummaryContentTest.kt` | 요약 표시 의미 |
| `trip/report/TripReportTest.kt` | 기간 리포트 집계 |
| `ui/kd/InterventionSummaryTest.kt` | UI 시설 종류·내비 결과 요약 |

## 21. 문서 읽는 순서와 오래된 설명

권장 순서:

1. 본 문서.
2. `docs/trip-intervention-integration.md` — 가장 최근 리포트 연결.
3. `docs/public-rest-stop-catalog.md` — 공식 파일 자동 확보와 졸음쉼터.
4. `docs/driving-assist-app.md` — 일반 앱 사용과 게이트.
5. `docs/rest-navigation.md` — 제안·쓰기·검증.
6. `docs/danger-navigation-probe.md` — 실제 효과 시험.
7. `docs/trip-summary.md` — main에서 추가된 리포트 전체 API.
8. `docs/intervention-voice-flow.md`, `docs/voice-test.md`, `docs/driving-intervention.md` — 변경 이력·과거 시험.

과거 문서에서 특히 주의할 문장:

- ‘DANGER는 경로 변경 없음’: 초기/수동 음성 시험 설명일 수 있다. 현재 생산 DANGER는 실제 적용 경로가 있다.
- ‘NaviHelper 전체가 목업’: 현재 실제 포트 설명으로 사용하면 안 된다. 일부 legacy 메서드/Debug fallback과 구분한다.
- ‘졸음쉼터 제외’: 최신 사용자 요구와 다르다.
- ‘즐겨찾기 등록 필수’: 공식 파일 자동 일치/공식 수동 후보 경로에서는 필요 없다.
- ‘휴식 권고는 Mock’: 현재 생산 `TripModule`은 실제 이력 어댑터를 사용한다.
- ‘세션 ID는 별도 UUID’: 생산 경로는 현재 tripId와 동일하다. 독립 컨트롤러 테스트에는 fallback이 남는다.
- 옛 테스트 개수/기기 데이터 건수: 해당 시점 기록이며 최신 숫자로 고정하지 않는다.
- 옛 `.git` 권한 제한/충돌 미해결 메모: 현재는 병합과 커밋을 완료했다. 파일 내용과 index 상태를 직접 확인한다.

## 22. 다음 Gemini에 붙여 넣을 시작 프롬프트

아래 내용을 Antigravity 대화에 넣고 실제 다음 업무를 덧붙이면 된다.

```text
이 저장소에서 기존 개발을 이어서 진행해 줘.

저장소: C:\SSAFY\jyj\workspace\-Pleos_DriveProbe
작업 브랜치: feat/hvac-control
우선 docs/ANTIGRAVITY_GEMINI_HANDOFF_2026-11-12.md를 끝까지 읽고 현재 코드와 Git 상태를 확인해.
이 문서는 과거 구현·검증 사실과 후속 제안을 구분한 인계 자료이며, 실제 다음 작업 범위는 내 요청을 따라.

이미 구현된 것은 MILD 실제 공조, CAUTION 실제 음성, DANGER 이름/ETA 제안·명확한 동의 후 경유지 추가,
공식 CSV 468개 기반 휴게소·졸음쉼터 확보, 일반 앱 주행 도움 UI, 실제 개입 이력→종료 요약·리포트 연결이야.
최근 구현 커밋은 ba5adf7이고, 그 뒤 인계 문서 커밋도 있으니 git log를 확인해.
외부 서버 업로드는 아직 미구현이며 API 계약부터 확인해야 해.

자동 시설 선택과 자동 경로 변경 동의는 다르게 처리해야 해.
방향 모호한 시설을 임의 선택하거나 공식 시설 ID를 native POI로 위장하지 마.
시험/Replay 이력을 실제 주행 성과에 포함하지 마.
기존 음성 시험 6번은 경로를 쓰지 않는 계약이고, DANGER 통합 시험은 실제 효과가 있어.
현재 사용자 DB·경로·설정을 보존하고 앱을 삭제하거나 pm clear로 초기화하지 마.
stash는 이미 복원·커밋된 백업이므로 재적용하지 마.

빌드 JDK는 C:\Users\SSAFY\.jdks\jbr-21.0.11을 사용했어.
AAOS 앱 ID는 ai.pleos.playground.sample.vehicle.diagnostic이고 마지막 사용자는 10이었어.
최신 단위 테스트는 583개 통과, 리포트 관련 선택 계측은 4개 통과했어.
전체 connectedAndroidTest에는 경로 변경 시험이 있으므로 목적에 맞는 클래스만 골라.

기기 검증이 남은 항목과 구현이 남은 항목을 구분하고, 실제로 확인한 범위만 성공으로 보고해.
중요한 변경을 하기 전에는 기존 구현과 테스트를 읽고, 필요한 작업은 끝까지 진행해.

이어서 할 일: [여기에 내가 지금 원하는 다음 업무를 적음]
```

## 23. 인계 시점 최종 확인 항목

- 구현 커밋 `ba5adf7`: 공식 CSV 원본·생성기·앱 자산·자동 시설·졸음쉼터·실제 리포트 연결 포함.
- 본 문서는 별도 커밋으로 포함하며 같은 `feat/hvac-control` 브랜치로 푸시한다.
- 단위 테스트 583개, Debug/계측 APK 빌드, Release Kotlin 컴파일 성공.
- 최신 리포트 집계·시험 분리 선택 계측 4개 성공.
- Debug APK를 사용자 10에 업데이트 설치하고 MainActivity 시작 `Status: ok` 및 프로세스 존재 확인.
- 공공데이터 원본 파일의 Git 줄바꿈 변환 방지 및 원본 SHA-256 복원.
- 실제 서버 업로드 없음. 실제 CSV 경유지 쓰기 전체 검증은 남음.
- 기존 사용자 데이터·내비 경로를 삭제하지 않았음.
- 과거 루트 인계 파일 2개와 백업 stash는 로컬에 보존.

푸시 결과와 최종 브랜치 동기화 여부는 인계를 마치는 응답 및 `git status -sb`, `git log -2`, `git ls-remote origin refs/heads/feat/hvac-control`로 확인한다.
