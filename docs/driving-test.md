# 주행 기능 테스트

`scripts/Test-Driving.ps1`은 에뮬레이터에 조향·속도·기어를 넣어 **주행 시작 → 피로 판단 → 주행 중 개입 → 정차·종료 요약**을 확인하는 PC 실행 도구다. 출발 전 반응 검사, 조건 설정, 장기 리포트 생성은 실행하지 않는다.

실제 에뮬레이터에서 실행한 [9분 주행·3분 음성 재시험 결과](driving-test-e2e.md)와 미검증 범위도 확인할 수 있다.

## 준비

- Windows PowerShell 5.1 이상과 Android SDK의 `adb.exe`.
- Pleos 에뮬레이터, 현재 AAOS 사용자에 설치된 이 프로젝트의 Debug 앱. 앱의 차량 속도 권한을 허용한다.
- 에뮬레이터의 8088 포트에서 `/browser/session`, `/vehicle/state`, `/vehicle/controls`, `/vehicle/gear`, `/vehicle/turn-signal`을 제공하는 Virtual Console REST. 이 서버 자체를 저장소에서 설치하는 도구는 아니다.
- Virtual Console을 **P·속도 0**으로 둔다. 실행 중 다른 브라우저·핸들·스크립트에서 같은 차량 입력을 보내면 결과가 달라질 수 있다.

공조·음성까지 시험하려면 정차 중 앱의 **주행 도움 → 주행 도움 켜기**로 음성을 준비한다. DANGER 안내에는 휴게시설이 포함된 실제 내비 경로와 시설 제안 준비가 필요하다. [주행 도움](driving-assist-app.md), [공식 휴게시설 목록](public-rest-stop-catalog.md)을 참고한다.

REAL 엔진 경로를 사용하므로 **에뮬레이터 앱의 주행 DB에 기록이 남고, 정상 주행은 개인 기준선에도 반영될 수 있다.** 자동 도움 설정에 따라 공조와 내비 경유지가 실제로 바뀔 수 있다. 기존 공조 설정·추가된 경유지는 자동 원복하지 않는다.

## 실행

저장소 루트의 PowerShell에서 실행한다. 앱 빌드·설치는 자동으로 수행하지 않는다.

```powershell
# 연결 확인만. 주행이나 엔진 모드를 바꾸지 않는다.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -CheckOnly

# 15초 연결 시험: D 전환 → 조향/속도 주입 → P → 종료 요약 저장
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Smoke

# 정상 주행 3분: 유효 창이 생기고 NORMAL을 유지하는지
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Normal

# 정상 3분 → 졸림 3분: CAUTION 이상 도달 (기본 시나리오)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Fatigue

# 정상 3분 → 졸림 3분 → 심한 졸림 3분: DANGER 도달
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Danger

# 공조 성공·음성 응답·DANGER 안내 동의까지 필수 판정
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Danger -RequireInterventions

# 실행 계획만 출력. adb와 REST를 호출하지 않는다.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Danger -DryRun
```

다른 에뮬레이터는 `-Serial emulator-5556`, 다른 SDK는 `-AdbPath 'D:\Android\platform-tools\adb.exe'`로 지정한다. `-Seed 42`가 기본이며 같은 조향 난수열을 사용한다. 실제 전송 시각, 기존 개인 기준선, 시간대, 다른 입력 때문에 피로 점수가 완전히 같아지는 것은 아니다. 임계값이나 점수를 강제로 바꾸지 않으므로 목표 단계 미도달도 FAIL로 남긴다. 첫 유효 판단에는 최소 60초짜리 창이 필요하며 신호 준비 상태에 따라 더 걸릴 수 있어 Smoke에서는 피로 단계 판정을 하지 않는다.

정상 구간을 이미 검증한 뒤 음성 경로만 반복 확인할 때는 `-Scenario Fatigue -NormalMinutes 0 -RequireInterventions`로 정상 준비 구간을 생략하고 졸림 3분을 실행할 수 있다. 판단 창 길이와 점수 계산은 그대로 유지한다.

`-RequireInterventions` 사용 시 질문이 끝나고 응답 대기 표시가 나오면 CAUTION에는 “괜찮아요”, DANGER에는 “안내해 줘”처럼 직접 답한다. 단계별 5분 쿨다운은 유지한다.

## 판정과 결과

실행마다 `artifacts/driving-tests/<시각-실행ID>/`를 만든다. 이 경로는 Git에서 제외된다.

| 파일 | 내용 |
| --- | --- |
| `run.json` | 시나리오, 시간 구성, seed, 대상 에뮬레이터 |
| `app.log` | 이번 실행 표식 이후, 해당 앱 PID의 Condition·Intervention·Trip·VehicleControl·VoiceTest·AndroidRuntime 로그 |
| `capture.log` | 수집기 준비 확인을 위한 고유 표식과 지정 태그의 원본 로그. 판정에는 표식 이후의 해당 앱 PID만 사용 |
| `logcat-errors.log` | 로그 수집 오류 |
| `console-before.json`, `console-after.json` | 시작 전·종료 후 차량 상태 |
| `result.md`, `result.json` | 항목별 PASS/FAIL, 주행 ID, 관측 단계, 같은 주행의 개입 결과, 수동 확인 항목 |

자동 판정은 새 REAL 엔진 시작, 새 주행 시작, 정차 상태 재조회, P 전환에 따른 비시연 주행 저장, 요약 로그를 확인한다. Smoke 외에는 유효 창과 저장된 `judgedMs > 0`도 요구한다. Normal은 관측된 유효 창 모두 NORMAL이어야 한다. Fatigue와 Danger는 각각 CAUTION 이상, DANGER에 도달해야 한다. 유효하지 않은 창, 이전 버퍼의 기록, 다른 주행 ID의 개입, `simulation=true` 개입으로 성공을 보완하지 않는다. 실패하면 종료 코드가 0이 아니다.

기본 실행에서는 개입 결과를 관측값으로 기록한다. `-RequireInterventions`는 같은 주행의 **MILD VERIFIED / CAUTION ANSWERED / (Danger만) DANGER ACCEPTED**까지 요구한다. **ACCEPTED는 음성 동의다. 경유지 적용 성공 판정은 아니다.** 주행 화면 자동 전환, 요약 화면 표시, 실제 경유지·원래 목적지 유지는 화면에서 직접 확인한다.

## 종료와 오류

종료·전송 오류·일반적인 Ctrl+C 중단 시 조향 0, 속도 0, P 전환을 각각 시도한다. 한 정리 요청이 실패해도 나머지를 시도하며 정차 재조회와 실패 사유를 결과에 남긴다. 터미널 강제 종료나 연결 단절에서는 정리를 보장할 수 없으므로 콘솔 상태를 확인한다. 로그 버퍼·사용자 DB·기존 내비 경로를 지우지 않으며, 이 실행이 생성한 포트 포워딩과 logcat 프로세스만 정리한다.

오류 확인 순서는 `result.md`의 실패 항목 → `app.log` → `logcat-errors.log`다. REAL 전환 실패는 Debug 앱 설치와 현재 사용자, 유효 창 없음은 차량 속도 권한·속도·방향지시등·조향 신호, 음성 결과 누락은 주행 도움 준비와 자동 개입 설정을 확인한다.

단일 ADB 명령의 기본 제한 시간은 15초다(`-AdbTimeoutSeconds`로 조정). 에뮬레이터가 응답하지 않으면 이 실행의 ADB 클라이언트만 종료하고 실패로 보고한다. 사용자 ADB 서버나 에뮬레이터를 강제로 재시작하지 않는다.

## 도구 자체의 회귀 검사

아래 검사는 에뮬레이터에 신호를 보내지 않는다. Python 검사는 PC 임시 포트의 가짜 REST 서버로 HTTP 오류·설정 중 오류·정차 요청 실패 시 중단과 나머지 정리 동작을 확인한다. 외부 패키지는 필요 없다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\Test-DrivingAssessment.ps1
python -m unittest discover -s scripts/tests -p test_driving_tools.py -v
```

완료된 실행의 실제 Room 저장값까지 확인하려면 다음 읽기 전용 검사를 사용할 수 있다. PC의 임시 SQLite 복사본에서 해당 주행 ID만 추출하고 `database-check.json`을 남긴다. 기기의 DB는 수정하지 않는다.

```powershell
python scripts/tests/check_driving_database.py artifacts/driving-tests/<실행폴더> --expect MILD:VERIFIED --expect CAUTION:ANSWERED
```
