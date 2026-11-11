# 주행 중 개입 구현·검증 기록

작업일: 2026-11-11 (한국 시간) · 브랜치: `feat/hvac-control`

## 구현된 실행 흐름

`ConditionModule.engines` → 엔진 교체 시 다시 구독 → 피로 단계/주행 세션/기어 확인 → `InterventionController` → 실제 동작 또는 시연 → Room 개입 이력.

| 조건/단계 | 현재 동작 |
| --- | --- |
| 실제 모드 + 주행 세션 + D + `lastWindowValid` | 자동 개입 허용 |
| NORMAL / 유효하지 않은 데이터 / D 이외 | 새 개입을 하지 않고 진행 중 작업 취소 |
| MILD | 운전석 설정 온도를 1℃ 낮추고 풍량 한 단계 증가. capability 범위 내에서 실행하고 각각 재조회 |
| CAUTION | 한국어 TTS 질문 후 STT 응답 수신. TTS 재생 완료부터 발화 시작까지 시간 측정 |
| DANGER | 음성으로 휴식·쉼터 안내 동의 확인. 실제 내비 미연동을 명시하며 경로 변경은 하지 않음 |
| DEMO / DEMO_WITH_SEED | 실제 차량·음성·내비 호출 없이 `SIMULATED` 이력만 저장 |
| 정차 중 디버그 시연 버튼 | 별도 시연 세션으로 해당 단계의 이력 저장. 실제 자동 개입 대기 시간에 영향을 주지 않음 |

기본 개입 수치(1℃/풍량 1단계/5분 대기)는 구현을 진행하기 위한 잠정 정책이다. 팀과 조정할 수 있다. 자동 창문 개방은 연결하지 않았으며 별도의 명시적 API로 남겼다.

같은 단계는 성공·실패·취소를 포함해 한 번 시도한 후 5분 동안 다시 시도하지 않는다. 처음 도달하는 상위 단계는 대기 시간과 관계없이 실행하며 기존 작업을 취소한다. 주행 종료 및 엔진 모드 교체 시 세션과 대기 시간을 초기화한다. 데이터가 무효화되어 취소되더라도 같은 주행의 대기 시간은 유지된다.

## 차량 제어 변경

- SDK 미초기화 시 목업 성공을 반환하지 않고 실패를 반환한다.
- 공개 typed API의 capability에서 쓰기·읽기 가능 여부, 실제 영역, 범위를 확인한다.
- 개별 좌석이 없으면 그 좌석이 포함된 지원 복합 영역을 사용한다. `SeatArea(ROW_1_LEFT, ROW_1_RIGHT)` 등 실제 capability를 따르며 `FRONT_BOTH`라는 SeatValue는 가정하지 않는다.
- SDK 콜백은 최대 5초 대기한다. null 완료 응답과 중복·취소 후 콜백을 처리한다.
- 설정 요청 완료 후 최대 5초 동안 재조회하여 실제 설정값을 확인한다. 시간 초과나 값 불일치는 실패다.
- 통합 제어는 첫 실패를 호출자에게 전달한다. 이전 단계에서 적용된 설정을 자동으로 되돌리는 트랜잭션은 아니다.
- 창문 raw 위치와 앱의 개방 비율을 구분한다. 예를 들어 capability가 0~10이면 15%를 정수 위치 2로 반올림한다.
- `closeAllWindows()`의 기존 구현 범위는 앞좌석 두 창문이므로 주석도 그 범위로 명시했다.
- `airCleaner`는 아직 미구현이므로 요청하면 명시적으로 실패한다.

## 화면과 음성

기존 API 탐색 화면 위에 **차량 API / 개입 상태** 탭을 추가했다. 개입 상태에서 자동 개입 중지·재개, 현재 결과, 최근 DB 기록을 볼 수 있다. 마이크 권한과 디버그 시연 버튼은 P 기어에서 사용한다.

음성은 Pleos 전용 신규 API를 추정하지 않고 Android 시스템 `TextToSpeech`와 `SpeechRecognizer`를 연결했다. 마이크 권한, 한국어 음성 데이터, 인식 서비스가 없으면 `UNAVAILABLE` 결과로 저장한다. 질문 재생 완료 후 응답 대기는 최대 10초다. 음성 반응 시간은 `TTS onDone → SpeechRecognizer onBeginningOfSpeech`이며 엔진 구동 지연이 포함될 수 있다. 이 값으로 피로 점수를 다시 계산하거나 정답 여부를 판정하는 정책은 아직 적용하지 않았다.

쉼터 안내는 명시적인 긍정 표현만 동의로 취급한다. 거절·불명확한 답·무응답을 동의로 처리하지 않는다. 현재 `NaviHelperRepositoryImpl`은 목업이므로 실제 경로 변경을 호출하지 않는다.

## 저장 계약

기존 DB 스키마와 Entity를 유지한다. 판단 모듈의 0~1 점수를 DB의 0~100 단위로 변환한다.

`actionDetails`는 version 1 JSON이며 `outcome`, `simulation`, `details`, `responseMs`, `responseTiming`, `calibrating`을 포함한다. 실패·취소·기능 미지원·거절·무응답도 구분한다. `isSynced=false`로 저장하며 서버 전송은 미구현이다. 추후 서버 연동 시 반드시 `simulation=true`인 시연 기록을 실제 운전 기록과 구분해야 한다.

## 실행 및 수동 확인

Android Studio에서 `app`을 Pleos 에뮬레이터에 실행한다. 실제 applicationId는 `ai.pleos.playground.sample.vehicle.diagnostic`이며 소스 package와 다르다.

1. **개입 상태**에서 MILD / CAUTION / DANGER 시연 버튼을 누른다.
2. 최근 이력이 해당 단계·시연으로 표시되는지 확인한다.
3. Logcat에서 `tag:Intervention | tag:VehicleControl`로 결과를 본다.
4. 음성은 정차 중 마이크 권한 허용 후, 실제 주행의 유효한 CAUTION/DANGER 상황에서 사람이 응답하여 확인해야 한다. 디버그 시연 버튼은 음성을 재생하지 않는다.

PowerShell에서 시연 결과 저장만 확인하려면:

```powershell
$adb = 'C:\Users\SSAFY\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$vehicleUser = (& $adb shell am get-current-user).Trim()
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_SIMULATE --es level MILD
& $adb logcat -s Intervention:I VehicleControl:I '*:S'
```

운전석 Repository 경로의 실제 온도 변경·복원 시험:

```powershell
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE
```

이 시험은 **P 기어 + 수신된 속도 0**에서만 동작한다. 원래 온도를 읽고 1℃ 변경·재조회 후 원래 값으로 복원·재조회한다. 범위 상한에서는 1℃ 낮춘다. 디버그 빌드에서만 수신하며 DUMP 권한을 가진 shell 등의 송신자만 허용한다. 시험 중 자동 개입을 중지하고, 시험과 복원 모두 성공하면 원래 활성 상태로 복귀한다. 실패하면 중지 상태로 남기므로 로그와 현재 설정을 확인한 뒤 재개한다.

## 검증 결과

- Debug APK 빌드와 전체 단위 테스트 **106개 통과**(기존 87 + 신규 19).
- 신규 테스트: invalid/역방향 입력 차단, 반복 상태 중복 실행 방지, 단계 상승·취소, 실패 후 대기, 새 세션, DB 실패, 시연에서 실제 동작 미호출, 동의 처리, 창문 비율 변환, SDK null·중복 콜백·취소 전파.
- 기존 시연 asset 비교 테스트가 Windows CRLF 때문에 실패하던 부분을 줄바꿈 정규화로 수정했다. 시연 데이터 자체는 변경하지 않았다.
- 이 실행 환경의 JVM 임시 경로 문제는 테스트 실행용 init script로만 우회했다. 앱 로직이나 팀원의 기준선 저장 로직은 변경하지 않았다.
- 에뮬레이터에 설치·기동하여 SDK/판단 엔진 시작, 새 화면 표시, MILD/CAUTION/DANGER 시연 기록 저장을 확인했다.
- 기기 DB를 읽어 단계 1/2/3, 점수 35/55/75, `simulation=true`, `outcome=SIMULATED`를 확인했다.
- 실제 운전석 온도 Repository 시험에서는 `before=20`, `requested=21`인데 최대 5초 재조회에서도 `actual=20`으로 남아 실패가 검출되었다. 원래 값 20℃ 복원 요청·재조회는 완료했다. capability는 `ROW_1_LEFT`, `ROW_1_RIGHT` 두 영역을 보고했다. 조수석 API 탐색기 경로의 이전 성공과 구분해야 한다. 이 검증을 실제 자동 공조 개입 성공으로 간주하지 않는다. 당시 설치본은 시험 실패 후 자동 개입을 중지했다. 이후 재검증 결과는 아래 후속 기록을 참고한다.
- 설치된 Android TTS 및 음성 인식 서비스의 존재는 확인했다. 실제 한국어 음성 재생·운전자 응답은 검증하지 않았다.
- Android lint는 오프라인 캐시에 `com.android.tools.lint:lint-gradle:31.8.2`가 없어 완료하지 못했다.

## 남은 작업

1. 운전석 온도 설정의 요청 완료/재조회 불일치 원인 분석. 지원 영역, 실제 차량 서비스, API 탐색기와 Repository 경로 비교.
2. 실제 풍량·창문 변경 및 주행 중 동작 검증.
3. 한국어 TTS/STT 실제 사용 검증 및 반응 결과를 판단 모듈에 반영할 계약 합의.
4. 쉼터 데이터·현재 위치·실제 NaviHelper 경유지 추가 연동.
5. 서버 API 계약·로컬 이력 업로드·재시도 구현.
6. 주행 중 화면 차단/앱 전환 시 연속 실행 검증과 foreground service 설계. 현재 코루틴은 Application 범위이며 프로세스가 살아 있는 동안만 실행된다. OS에 의한 프로세스 종료 후 지속 실행은 보장하지 않는다.


## 후속 온도 제어 진단 (2026-11-11)

### 수정 사항

- `MainActivity`의 heartbeat에서 `probeTemperatureRead()`를 자동 호출하던 코드를 제거했다. 이 함수는 `runFeatureRead(emptyMap())`를 호출하므로 사용자가 선택한 좌석과 무관하게 기본 좌석 값을 조회해 화면을 덮어쓸 수 있었다. heartbeat 로그 자체는 유지한다. 이제 API 탐색기의 온도 조회는 사용자가 Refresh를 누르거나 명시적으로 구독한 경우에 실행된다.
- Repository 온도 시험에 좌석 선택을 추가했다. 디버그 방송의 `--es seat ROW_1_LEFT` 또는 `--es seat ROW_1_RIGHT`로 영역을 고정할 수 있다. 기본은 운전석이다.
- SDK 설정 완료 콜백의 영역·요청값·반환값을 `VehicleControl` 로그에 추가했다. 완료 콜백과 재조회 성공을 계속 구분한다.

### 재검증 결과

새 APK 설치 후, 정차 상태에서 동일 프로세스로 운전석·조수석을 각각 두 번 시험했다.

| 좌석 | 원래 값 | 변경 요청 | 재조회 | 복원·재조회 | 반복 결과 |
| --- | --- | --- | --- | --- | --- |
| ROW_1_LEFT | 20℃ | 21℃ | 21℃ | 20℃ | 2/2 VERIFIED |
| ROW_1_RIGHT | 20℃ | 21℃ | 21℃ | 20℃ | 2/2 VERIFIED |

빌드 및 전체 단위 테스트 106개 통과, `git diff --check` 통과.

**해석 범위:** 기본 영역 자동 조회가 화면에 다른 좌석 값을 표시할 수 있는 결함은 코드에서 확인하여 제거했다. 다만 이 변경이 이전 Repository 경로의 재조회 불일치까지 해결한 직접 원인이라는 인과관계는 확정하지 않았다. APK 재설치·앱 재시작도 함께 이루어졌기 때문이다. 현재 설치본에서 온도 변경과 원상복원이 반복 성공했다는 사실까지 확인한 상태이며, 주행 중 동작·풍량·창문 검증을 대신하지 않는다.

조수석 시험 명령:

```powershell
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE --es seat ROW_1_RIGHT
```


## 풍량·창문 및 주행 중 제어 검증 (2026-11-11)

### 진단 코드 보완

- `INTERVENTION_PROBE`에 `kind=temperature|fan|window`를 추가했다. 기본은 기존과 같은 temperature다.
- 정차 시험은 P + 속도 0, 주행 시험은 **에뮬레이터 + D + 실제 수신 속도 > 0**을 요구한다. 속도 신호는 `VehicleRepository.signalEvents`의 AAOS 이벤트이며 2초 이내 수신된 값만 인정한다. 피로도 재생 모드의 속도·기어를 사용하지 않는다.
- 주행 시험은 `--ez driving true`를 명시해야 한다. 실차에서 이 디버그 주행 시험을 실행할 수 없도록 `ranchu`/`goldfish` 하드웨어에서만 허용한다.
- 시험 직전과 완료 시 실제 기어·속도·속도 데이터 나이·단조 시간(ms)을 로그에 남긴다. 쓰기 직전에 상태를 다시 확인한다.
- 세 종류의 시험 모두 공통 `controlProbe` 복원 흐름을 사용한다. 실패·취소 시에도 원래 설정으로 복원을 시도한다. 복원 자체가 실패하면 `restore FAILED`와 원래 실패 원인을 함께 남긴다.

### 환경 확인

현재 PC의 `http://127.0.0.1:8088/`은 Pleos Connect Virtual Console이다. 페이지는 `/browser/session`에 약 1초마다 heartbeat를 보내며, 세션 timeout은 3초다. 세션이 없을 때 `browser.active=false`, 차량 신호 bridge/emulator 플래그도 false였고, 시험용 세션을 유지하자 `availability.effective_success=true`와 실제 신호가 확인되었다.

따라서 재현 시험은 활성 콘솔 세션을 유지한 채 진행했다. 이것은 필요한 시험 환경 조건으로 확인한 것이며, 과거의 모든 온도 조회 불일치 원인을 이 항목 하나로 확정한 것은 아니다.

### 실측 결과

| 상태 | 기능 | 지원 영역/범위 | 변경 전 | 시험값·재조회 | 복원·재조회 |
| --- | --- | --- | --- | --- | --- |
| P / 0km/h | 풍량 | 앞좌석 공용 `ROW_1_LEFT, ROW_1_RIGHT` | 3 | 4 | 3 |
| P / 0km/h | 운전석 창문 | ROW_1_LEFT, raw 0~10 | 0 | 1 | 0 |
| D / 약 60km/h | 운전석 설정 온도 | ROW_1_LEFT | 20℃ | 21℃ | 20℃ |
| D / 약 60km/h | 풍량 | 앞좌석 공용 영역 | 3 | 4 | 3 |
| D / 약 60km/h | 운전석 창문 | ROW_1_LEFT, raw 0~10 | 0 | 1 | 0 |

주행 시험의 앱 수신 속도는 `60.000004km/h`였으며 시작·완료 로그 모두 D 기어, 신호 나이 18~168ms 이내였다. 세 제어 모두 SDK 요청 완료뿐 아니라 변경 후 재조회와 원상복원이 `VERIFIED`로 완료됐다.

동시에 에뮬레이터 화면에 **“주행 중에는 사용할 수 없습니다.”**가 표시되는 것을 확인했다. 이 화면 차단 상태에서도 해당 앱 프로세스의 진단 제어는 실행됐다. 주행 중 화면 조작 허용이나 정책 예외를 추가하지 않았다.

시험 종료 후 콘솔 상태에서도 **P / 0km/h / 운전석 20℃ / 풍량 3 / 창문 0 / Auto 유지**를 확인했다. 임시 콘솔 세션을 닫아 시험 전 세션 비활성 상태로 되돌렸다.

이 결과는 **에뮬레이터에서 이미 실행 중인 앱의 짧은 주행 구간 제어**를 확인한 것이다. 피로 감지부터 자동 개입까지의 전체 흐름, 실차 동작, 앱 프로세스 종료 후 지속 실행을 검증한 결과는 아니다.

### 재현 명령

Pleos Virtual Console을 열어 활성 세션을 유지한다. 앱이 실행 중이어야 한다.

```powershell
# 정차: P / 0km/h
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE --es kind fan
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE --es kind window

# 각 시험이 END ... VERIFIED로 끝난 뒤 다음 시험을 실행한다.
# 에뮬레이터를 D / 60km/h로 바꾼 후:
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE --es kind temperature --ez driving true
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE --es kind fan --ez driving true
& $adb shell am broadcast --user $vehicleUser -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.driveprobe.INTERVENTION_PROBE --es kind window --ez driving true
```

마지막에 속도를 0으로 내리고 P 기어로 복귀시킨다. `PROBE REJECTED`는 상태/신호 조건 불충족이며 실제 변경 시험 성공으로 기록하지 않는다.

추가 단위 테스트 7개는 복원 성공, 변경 실패 후 복원, 취소 후 복원, 복원 실패 원인 유지, 무효 상태에서 쓰기 차단, 정차/주행 게이트, 실차 및 오래된 신호 거절을 검증한다.


## 출발 전 모듈 PR 통합 확인 (2026-11-11)

- `feat/hvac-control`의 기준 커밋이 `5321ef1`에서 `73ab099`(PR #1, `feat/departure-core`)로 fast-forward 되었다. 기존 작업의 stash 백업은 유지한다.
- stash 적용 중 `MainActivity.kt`에 발생한 충돌은 작업 파일에서 해결했다. 새 `DriverConditionSettingsViewModel`, `DriverConditionSettingsScreen`, 화면 진입·뒤로가기 콜백과 기존 차량 API/개입 상태 탭을 모두 연결했다. 제거했던 heartbeat의 기본 영역 자동 온도 조회는 다시 추가하지 않았다.
- PR이 추가한 CarUxRestrictions 구독과 Repository 속성, `DriveProbeDeparture` 연결, DB v2 및 MIGRATION_1_2를 보존했다.
- minSdk는 PR 기준 API 28이며 새 모듈은 `:departure-core`, NaviHelper SDK는 3.0.5다.
- NaviHelper에는 목적지·경로 상태·여정 거리/ETA·현재 위치 조회가 추가되었다. 따라서 이전의 'NaviHelper 전체가 목업'이라는 설명은 최신 기준으로 유효하지 않다. 다만 `guideToNearestRestArea`, `searchConditionAdaptedRoute` 등 기존 쉼터 안내/조건 경로 생성 메서드는 여전히 목업이며, 4번 개입의 실제 경유지 추가는 별도 작업이다.
- 업그레이드 전 기기 DB는 버전 1, 개입 이력 26건이었다. 설치·마이그레이션 확인을 위해 기존 DB를 먼저 백업했다.
- 작업 환경의 `.git` 쓰기 제한 때문에 해결한 파일을 index에 등록하는 `git add`는 직접 실행할 수 없다. 소스 충돌 표식 제거와 Git의 충돌 해제 표시는 별개다. Android Studio Terminal에서 다음 명령으로 표시를 마무리한다.

```powershell
git add app/src/main/java/ai/pleos/playground/sample/vehicle/MainActivity.kt
```


### PR 통합 후 실제 검증 결과

- `:departure-core:test`: 147개 통과.
- `:app:testDebugUnitTest`: 171개 통과. 합계 318개, 실패 0개.
- `:app:assembleDebug` 성공 및 새 APK 업데이트 설치·기동 확인.
- 기기 DB 버전 1 → 2 업그레이드 후 기존 개입 이력 26건 유지, `driver_conditions` 테이블 생성 확인.
- 새 '운전 조건 설정' 버튼으로 설정 화면 진입·돌아가기 확인. 설정값은 저장하거나 변경하지 않았다.
- 기존 '개입 상태' 탭과 최근 이력 표시도 유지됨을 확인.
- 다운로드 프록시와 debug keystore 디렉터리 쓰기 제한은 로컬 검증용 환경/init script에서만 처리했다. 프로젝트의 의존성 버전·서명 설정은 수정하지 않았다.
- Git index에는 `.git` 쓰기 제한으로 MainActivity가 아직 UU로 남는다. 파일 내용의 충돌은 해결했으며 위 `git add` 한 줄로 해결 표시를 마무리할 수 있다. stash 백업은 유지했다.
