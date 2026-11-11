# 백그라운드 연속 동작 검증

검증 브랜치: `investigation/background-freeze`

## 1차 실제 결과: Home / Voice Off (2026-11-11)

사용자 터미널에서 빌드·설치 후 실행. 증거 폴더: `captures-background/20261111-161148-311-Home-Off`.

- `summary.json`: SAMPLE 24개, 마지막 공백 132,885ms, PID 3462, engine 139670597.
- 0초·30초 OS 스냅샷에서 대상 프로세스는 `curProcState=2`, `isFrozen=false`.
- 60초·180초 OS 스냅샷에서 **동일 PID의 해당 ProcessRecord에 `isFrozen=true`**. 180초 pidof 결과도 3462. 프로세스 종료가 아니라 동결 확인.
- 180초 서비스 조회는 `(nothing)`, SAMPLE도 `voiceReady=false`.
- 기기 단조 시각 기준 관찰 시작 254890ms, 마지막 SAMPLE 302485ms, 관찰 종료 435370ms. 관찰 시작 약 47.6초 뒤 마지막 로그. 실제 Activity onStop은 그보다 늦은 관찰 도중 발생했고, 이후 약 8초까지 SAMPLE이 있음. 따라서 '홈 명령 후 정확히 20초'라고 해석하지 않는다.
- 동결 직전까지 엔진 tick과 차량 신호 계수기는 증가. 정차(P), `driving=false`, `valid=false`, `NORMAL` 조건이므로 실제 피로 단계 전환·개입 검증은 하지 않음.
- 판정: **서비스 미준비 상태에서 앱 전체 동결 재현**. 다음은 동일 조건 Voice On 대조 실험. 내비 주행 중 지속 동작이나 서비스 켬의 효과는 아직 미검증.

## 1차 실제 결과: Home / Voice On (2026-11-11)

증거 폴더: `captures-background/20261111-161753-150-Home-On`. 사용자 실행 후 저장된 결과를 직접 대조했다.

- 3분 관찰: SAMPLE 90개, 최대 공백 2,006ms, 마지막 공백 280ms. 동일 PID 13746과 engine 21041327 유지, tick 진행.
- 0·60·180초 대상 ProcessRecord는 `isFrozen=false`, `mHasForegroundServices=true`. 60·180초 `curProcState=4`.
- 서비스는 각 스냅샷에서 `isForeground=true`, foregroundId 5901, microphone type 0x80 유지. Activity onStop 이후에도 최종 SAMPLE에서 `voiceReady=true`와 입력·계산 갱신 확인.
- 판정: 이번 에뮬레이터의 정차·홈 전환 조건에서 포그라운드 서비스 켬은 3분간 실행 연속성을 유지했다. 서비스 끔의 동결과 차이가 확인됐다. 1회 비교이므로 장시간·내비·주행 자동 개입 성공으로 확대하지 않는다.
- 다음: 내비 안내·조작 조건의 서비스 켬 연속 관찰, 이후 유효 차량 입력에 따른 피로 단계 전환과 실제 개입 확인.

## 10분 연속 관찰: Home / Voice On, 수동 내비 사용 절차 (2026-11-11)

증거 폴더: `captures-background/20261111-162223-079-Home-On`. 스크립트는 Home 전환 후 사용자가 내비를 열도록 안내했다. 메타데이터의 target은 Home이며, 내비 실행·안내·조작 자체는 이번 자동 로그에서 별도로 확정하지 않았다.

- SAMPLE 300개, 최대 공백 2,026ms, 마지막 공백 1,213ms. 동일 PID 13746, engine 21041327.
- 관찰 구간 첫/마지막 SAMPLE: ticks 2935→8805 (+5870), events 1991→5955 (+3964), speedEvents 1888→5660 (+3772), steeringEvents 98→290 (+192).
- 60·120·600초 대상 ProcessRecord는 `isFrozen=false`, `mHasForegroundServices=true`, `curProcState=4`. 서비스 `isForeground=true` 유지. 앱 onStop 이후에도 입력과 계산 진행.
- 최종 상태는 P, `driving=false`, `valid=false`, NORMAL, `voiceReady=true`. 따라서 단계 전환·음성/공조 자동 개입 성공은 확인하지 않았다.
- 판정: 서비스 켬 상태에서 백그라운드 실행·차량 신호 처리·엔진 tick이 10분간 지속. 다음 검증은 유효한 모의 주행 입력으로 단계 전환과 실제 개입 연결 확인이다.

## 단계별 백그라운드 개입 검증: 새 APK 필요

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
& 'C:\Users\SSAFY\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r .\app\build\outputs\apk\debug\app-debug.apk
.\scripts\Test-BackgroundContinuity.ps1 -Target Home -VoiceMode On -DurationSeconds 330 -StageProbe
```

실제 에뮬레이터 차량은 **P·0km/h 유지**. 앱 마이크·속도 권한을 허용한다. 스크립트는 PC 마이크 입력 경로(`hostmicon`)를 켠다. Windows 입력 장치가 음소거 상태이면 해제한다. `0s / 330s collected`가 나오면 내비를 열고 지도/목적지를 조작한다. 우리 앱과 음성 테스트 화면은 다시 열지 않는다.

- 시작 옵션 처리 후 20초를 기다려 모의 입력 재생을 시작한다. `BgStage ARMED`와 `STARTED`로 확인한다.
- 기존 `trip_demo.json`과 별도 메모리 seed를 사용한다. 테스트 문맥은 시각 3시·출발 전 반응 MUCH_SLOWER로 고정한다. 생산 임계값·분석 창·원래 엔진 설정은 변경하지 않는다. 원래 live 엔진·차량 상태·개인 baseline 파일은 교체하지 않는다.
- JVM 검증에서 입력 재생 후 약 105초 MILD, 155초 CAUTION, 175초 DANGER를 계산했다. 실제 실행 시 스케줄 지연으로 시점은 달라질 수 있다. 프리셋 점수를 단계마다 직접 넣는 방식이 아니다.
- MILD: 실제 에뮬레이터 공조 온도·풍량 변경과 재조회를 요청한다. API 실패는 FAILED로 남긴다. 공조의 원래 값은 자동 복원하지 않으므로 필요하면 앱/API 화면에서 복원한다.
- CAUTION: 질문이 끝난 뒤 '괜찮아요'라고 말한다. DANGER: 질문이 끝난 뒤 '안내해 줘' 또는 '아니요'라고 말한다. 듣지 못했거나 인식하지 못한 결과도 그대로 기록한다. 응답 기회를 놓치면 재실험하면 된다.
- 별도 `InterventionController` 인스턴스가 실제 `ConditionEngine` 결과를 받고, 기존 실제 공조 처리와 `InterventionSpeechAction`을 호출한다. 테스트 중 원래 자동 개입 컨트롤러를 일시 중지하고, 종료·실패 후 기존 활성 설정을 복원한다. 서비스 중지, 실제 기어/속도 조건 이탈, 신호 노후화 시 실험을 종료한다.
- 음성 서비스 자체는 실험 후에도 준비 상태를 유지한다. 실험 도중 중지하려면 앱의 음성 준비 종료 또는 알림의 종료 버튼을 사용한다. 터미널만 중단하면 앱의 실험은 정해진 종료 시점까지 계속될 수 있다.
- DB: `sessionId=background-probe-...`, `triggerReason=BACKGROUND_STAGE_PROBE`, `simulation=true`, `source=BACKGROUND_STAGE_PROBE`, `inputSource=SYNTHETIC_REPLAY`, `effects=REAL_EMULATOR`. `simulation=true`는 입력 출처이며 공조·음성이 가짜라는 뜻이 아니다. 실주행 성과/서버 집계에서는 테스트 행을 제외해야 한다.

추가 결과는 `stages-summary.json`에 저장된다. `computed`는 엔진 단계 계산, `actionResult`는 실제 처리 반환값, `saved`는 DB 저장 성공을 뜻한다. `PROBE_FINISHED_REVIEW_OUTCOMES`는 모든 기능의 합격을 뜻하지 않는다. `FAILED`, `UNAVAILABLE`, `NO_RESPONSE` 등을 각각 평가한다. `stages-before-resume.txt`와 `logcat.txt`에 실제 API readback·음성 결과가 남는다. DANGER 경로 변경은 현재 미연결이다.

이 실험은 **모의 입력 → 엔진 → 컨트롤러 → 실제 에뮬레이터 개입 → DB** 연결을 검증한다. 실제 주행 신호가 원래 live 엔진을 통해 개입하는 전체 경로와 실차 동작은 별도 검증해야 한다.

로컬에서 실제 엔진·컨트롤러로 3단계 자동 실행/이력 저장 순서와 seed 불변성을 검증한 JVM 테스트 1개가 통과했다. 수집·분석 스크립트 문법 검사와 단계 분석기 합성 로그 검증도 통과했다. 새 APK 전체 Gradle 빌드·설치·기기 실행은 사용자 터미널에서 진행해야 한다.

## 단계별 개입 실제 결과 (2026-11-11)

증거: `captures-background/20261111-171024-594-Home-On`, run `background-probe-6b637ade-1d78-4588-a5e7-39bc944677a1`.

- 330초 SAMPLE 165개, 최대 간격 2,010ms, 마지막 공백 917ms. PID 31429 유지. 최종 ProcessRecord `isFrozen=false`, 서비스 `isForeground=true`. 앱 onStop 이후 세 단계 모두 실행.
- 모의 입력 기반 엔진이 MILD(0.3478125), CAUTION(0.5131348), DANGER(0.7389087)를 자연 전환. 세 결과 모두 테스트 이력으로 저장(#81, #82, #83).
- MILD: FAILED. `PropertyNotAvailableException: Property ID: HVAC_TEMPERATURE_SET area ID: 0x1 - is not available because of vendor error code: 0`. temperature capability 조회 이후 약 250ms 안에 실패. `mild()` 호출 순서와 SET callback 부재로 보아 온도 변경 전 현재 온도 조회 실패로 판단된다. 실제 제어 성공은 확인되지 않았다. 공조 전원/에뮬레이터 property availability/전경과 백그라운드 차이를 별도 확인해야 하며 전원이 원인이라고 확정하지 않는다.
- CAUTION: ANSWERED, 발화 시간 추정치 1,059ms, 이력 #82. 질문·음성 인식·저장 경로 완료.
- DANGER: ACCEPTED, 발화 시간 추정치 3,035ms, 이력 #83. 안내 동의 처리 완료. 실제 쉼터 검색·경로 변경은 미연결.
- 전체 probe FINISHED, failures=[]는 실험 자체가 끝났다는 뜻이며 MILD 실패가 없다는 뜻이 아니다. 공조 실패 후에도 CAUTION·DANGER 처리가 계속됐다.
- 결론: 이번 에뮬레이터·테스트 범위에서 포그라운드 서비스 유지 시 백그라운드 동결이 방지되고 모의 계산→실제 음성 개입→DB 저장이 이어짐. 공조 제어는 별도 미해결. 실제 주행 입력·실차 보장으로 확대할 수 없음.

## 공조 ON 후 개별 제어 재검증 (2026-11-11, 사용자 제공 로그)

- 온도 PROBE: before=23.5, target=24.5, readback=24.5, restored=23.5. VERIFIED. 실제 P·속도 0·fresh speed 조건에서 성공.
- 풍량 PROBE: area=ROW_1_LEFT, ROW_1_RIGHT, range=1..8. 요청 4에 대해 5초 재조회 값이 3으로 유지되어 FAILED. 실제 P·속도 0이며 앱은 계속 실행 중.
- `controlProbe`는 실패한 요청에도 finally에서 원래 풍량을 재요청·검증한다. 이번 로그는 `restore FAILED`가 아니라 최초 요청의 mismatch이므로 원래 값 복원 검증은 예외 없이 끝난 것으로 판단한다. 목표 값 4가 실제 적용됐다는 증거는 없다.
- 현재 풍량 setter callback 값·AUTO 상태를 기록하지 않았으므로 미반영 원인은 미확정. 이전 성공 기록은 AUTO 꺼짐 조건이었다. 다음은 AUTO OFF·수동 풍량 고정 상태로 동일 probe를 실행해 자동 제어의 영향부터 분리한다.
- 온도·풍량을 함께 처리하는 MILD 자동 개입 전체는 아직 성공 판정하지 않는다.

### AUTO OFF 풍량 재검증 (사용자 제공 로그)

- 사용자가 AUTO OFF·수동 풍량 3 조건으로 재실행. `elapsedMs=4602880`에 시작해 `4602934`에 종료.
- `VERIFIED: fan area=ROW_1_LEFT, ROW_1_RIGHT before=3 target=4 readback=4 restored=3`.
- AUTO OFF 조건에서 풍량 setter·실제 재조회·복원 성공. 앞선 AUTO 관련 조건 변경 전 실패와 차이가 확인됐다. AUTO 상태를 SDK로 별도 수집하지 않았으므로 자동 제어의 내부 동작까지 확정하지는 않는다.
- 현재 검증된 공조 전제: HVAC ON에서 온도 개별 제어 성공, HVAC ON·사용자 설정 AUTO OFF에서 풍량 개별 제어 성공. 자동 MILD의 온도 감소+풍량 증가 전체 동작은 이 전제에서 한 번 더 통합 확인해야 한다.
- 제품 정책은 미결정: 공조 OFF/AUTO ON일 때 자동으로 전원을 켜거나 AUTO를 끌지, 해당 조작을 건너뛰고 실패·제약 사유를 기록할지 결정해야 한다. 이번 검증 과정에서는 생산 정책을 변경하지 않았다.

## 초기 자동 실행 시도 (아래는 실제 사용자 실행 이전 기록)

- 준비: Debug 전용 `BgProbe` 로그, 엔진 계수기, 에뮬레이터 음성 서비스 준비 옵션, PowerShell 수집·분석 스크립트.
- 정적 확인: PowerShell 파서 검사, `git diff --check` 통과.
- 분석기 검증: 합성 로그 3종(연속 실행, 종료 직전까지 로그가 끊긴 경우, PID 변경)을 각각 `CONTINUITY_OBSERVED`/`EXECUTION_GAP`/`PROCESS_CHANGED`로 구분하는지 실행하여 통과. 이는 기기 검증이 아니다.
- 빌드·단위 테스트: **미완료**. 기본 캐시는 `.gradle` lock 파일 쓰기 권한 오류. 작업 폴더의 별도 캐시와 설치된 Gradle을 사용한 시도도 `Could not connect to the Gradle daemon`으로 실패.
- ADB 연결: **실패**. `adb nodaemon server`에서 `failed to create fdevent interrupt socketpair: Invalid argument`. 기기 목록 확인도 실패.
- APK 설치·앱/홈/내비 실험: **미실행**. 20초 정지 재현 여부, 원인, 서비스 효과는 아직 판단할 수 없음.
- 실제 수집 스크립트 실행도 시도했으나 첫 `adb get-state`에서 동일한 ADB 서버 연결 오류로 중단. 관찰은 시작되지 않았다.

## 실행 준비

Android Studio에서 Debug APK를 빌드·설치한다. 프로젝트에서 쓰던 JDK/Gradle 설정을 사용한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
& 'C:\Users\SSAFY\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r .\app\build\outputs\apk\debug\app-debug.apk
```

앱을 열고 마이크와 차량 속도 권한을 허용한다. 정차 상태에서 준비한다. 앱 데이터 초기화, 재설치 삭제, freezer/배터리 정책 변경은 하지 않는다. 디버거는 연결하지 않는다. 여러 기기가 있으면 스크립트에 `-Serial`을 지정한다.

실제 applicationId는 `ai.pleos.playground.sample.vehicle.diagnostic`이다.

## 1차 실험

아래 명령은 각각 3분 관찰한다. 우선 한 번씩 비교하고, 차이가 보인 조건은 3회 반복한다.

```powershell
# A: 화면 유지, 서비스 끔
.\scripts\Test-BackgroundContinuity.ps1 -Target App -VoiceMode Off
# B: 홈 전환, 서비스 끔
.\scripts\Test-BackgroundContinuity.ps1 -Target Home -VoiceMode Off
# D 대조군: 홈 전환, 서비스 켬
.\scripts\Test-BackgroundContinuity.ps1 -Target Home -VoiceMode On
```

내비 package/activity는 기기에서 확인한 실제 값을 사용한다. 아래 `<내비 package/activity>`는 교체해야 할 자리 표시자다.

```powershell
# C: 내비 전환, 서비스 끔
.\scripts\Test-BackgroundContinuity.ps1 -Target Navi -NaviComponent '<내비 package/activity>' -VoiceMode Off
# D: 내비 전환, 서비스 켬
.\scripts\Test-BackgroundContinuity.ps1 -Target Navi -NaviComponent '<내비 package/activity>' -VoiceMode On
```

스크립트는 시작 시 앱 Activity를 새로 열어 서비스 준비 옵션을 전달한다. 이 옵션은 Debug 에뮬레이터에서만 처리한다. 자동 녹음·단계 강제 주입·차량 제어는 하지 않는다. 서비스 켬 실험은 실제 `isForeground=true` 확인 후에만 관찰을 시작한다. 수동 음성 테스트는 시작하지 않는다.

관찰 중 앱을 다시 열거나 테스트 브로드캐스트를 보내지 않는다. 내비 경로 안내·지도 조작은 가능하다. 스크립트는 내비 실행까지만 자동화한다. 실제 경로 안내·지도 조작 여부는 별도로 결과에 기록한다.

0·10·20·30·60·120·180초 OS 상태를 저장한다. 출력 폴더는 `captures-background/시각-대상-서비스`다. OS 조회로 얻은 정보도 프로세스 동결·해제 시각과 대조한다. 스크립트는 앱으로 자동 복귀하거나 실험 종료 후 서비스를 자동 중지하지 않는다. 필요하면 앱에서 음성 준비를 종료한다.

## 결과 읽기

- `summary.json`: 5초 초과 공백, 마지막 로그 이후 공백, 프로세스·엔진 변경, tick 진행 여부. `CONTINUITY_OBSERVED`는 계산 실행의 연속성 관찰일 뿐 전체 기능 합격이 아니다.
- `*-processes.txt`: 해당 프로세스와 frozen 목록/상태. OS 버전에 따라 형식이 다를 수 있다.
- `*-services.txt`: 포그라운드 서비스가 관찰 내내 유지됐는지.
- `probe-before-resume.txt`: 앱에 복귀하기 전 로그 버퍼. 복귀 후 몰아서 처리한 로그는 성공 근거에서 제외한다.
- `logcat.txt`: 메인 heartbeat, 피로 상태, 개입과 OS 종료 로그를 대조한다. 인식 원문과 PCM은 진단 로그에 추가하지 않았다.

`BgProbe SAMPLE`은 2초 주기다. `ticks`는 실제 엔진 tick, `events`/`speedEvents`/`steeringEvents`는 엔진이 받은 신호 횟수다. `lastInputMs`는 신호 이벤트의 시각이다. 엔진 교체 시 계수기는 초기화되므로 `engine` 식별자도 기록한다. Replay는 `replay=true`로 구분한다.

입력이 정상이어도 정차·동일 값의 on-change 신호는 갱신되지 않을 수 있다. 입력 중단은 동일한 변화 입력을 준 전경 대조군과 비교한다. 점수 고정·`valid=false`만으로 동결을 판정하지 않는다.

`ACTION_START` → `ACTION_RESULT` → `ACTION_END` → 기존 `Intervention history=...`/`DB_LATEST`를 연결한다. `DB_LATEST` 최초 출력은 기존 행일 수 있으므로 실험 전후 ID와 session을 비교한다. 완료되지 않은 예외·취소는 `ACTION_END`만 있을 수 있으며 기존 오류·이력 로그를 함께 읽는다.

## 2차와 장시간 실험

1차에서 연속 실행이 확인된 조건으로 동일한 차량 입력 시나리오를 주고 전경/내비 조건의 단계 전환을 비교한다. 자동 개입 활성, D 기어, 유효 분석 창, 음성 준비, 단계별 5분 쿨다운을 기록한다. 기존 Replay/시뮬레이션은 실제 개입을 호출하지 않으므로 실제 공조·음성 검증과 구분한다.

MILD는 실제 값 재조회, CAUTION은 TTS→마이크→응답·시간→저장, DANGER는 동의/거절/무응답을 확인한다. DANGER 쉼터 검색·경로 변경은 현재 미연결이므로 합격 범위에서 제외한다. 30분→1시간 연속 관찰은 1차 통과 후 진행한다. 현재는 어느 단계도 기기에서 실행하지 않았다.
