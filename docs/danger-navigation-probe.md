# DANGER 실제 음성·내비 통합 시험

2026-11-12, `main` 병합 후 추가한 정차 Debug 에뮬레이터 시험. 실행 코드는 `DANGER_CONSENT_V1`이다.

## 실행 경로와 실제 효과

홈 → **개발자 도구 → DANGER 통합**에서 시작한다. 생산 DANGER와 같은 `DangerNavigationAction`을 사용해 현재 경로의 시설·이름·ETA 제안 → Pleos TTS/STT → 명확한 동의 → 같은 제안의 실제 경유지 추가·재조회를 수행한다. 시험에서는 추가 직전 SDK 경계에서도 정차·음성 준비 조건을 다시 확인한다. 피로 엔진·임계값·생산 판단 입력은 바꾸지 않는다.

- **음성 시험 6번**은 그대로 경로를 변경하지 않는다.
- **내비 조회 → 실제 추가·복원 시험**은 음성 없이 추가한 시험 경유지를 삭제한다.
- **DANGER 통합**은 음성 동의 시 실제 추가한 경유지를 **유지**한다. 취소했다고 이미 추가한 경유지를 자동 삭제하지 않는다.

## 사람이 수행할 시험

1. 휴게소가 있는 실제 안내 경로를 내비에서 준비한다. 기존 경유지가 있으면 이 시험은 거부한다. 원래 목적지를 기록해 둔다.
2. 앱의 **내비 조회**에서 현재 방향의 공식 시설 후보 또는 내비 즐겨찾기를 불러와 노선·방향을 확인 등록한다. 이름·ETA 미리보기를 먼저 확인한다. 동일 경로의 주기적 재탐색은 확인을 유지하며 목적지·노선·방향·다음 시설이 달라지면 다시 확인한다. [최신 수정·검증 기록](rest-stop-e2e-fix.md)을 참고한다.
3. **DANGER 통합**에서 P·속도 0을 유지하고 **주행 음성 준비**를 누른다. 마이크 권한을 새로 허용했다면 준비 버튼을 다시 누른다. 준비만으로 녹음하지 않는다.
4. 실제 효과 확인 체크 → **시험 시작**. 이름·ETA 질문이 끝나고 응답 대기 표시가 나오면 응답한다.
5. 완료 후 실행/결과 코드, 시험 ID, 음성 결과·동의, 내비 적용 상태·실제 요청 여부, 이력 번호를 확인한다. 내비에서 같은 휴게소·원래 목적지·경유지 유지를 직접 확인한다.
6. 다음 시나리오를 위해 필요한 경유지 제거·경로 변경은 내비 화면에서 사람이 명시적으로 수행하고, 시설 확인도 새 경로에 맞춰 갱신한다. 시험 종료 후 **개입 상태 → 음성 준비 해제**로 마이크 FGS를 종료할 수 있다.

| 시나리오 | 기대 결과 |
| --- | --- |
| ‘추가해 줘’ / ‘안내해 줘’ | ACCEPTED / ACCEPT와 실제 내비 상태를 따로 확인. VERIFIED_WAYPOINT일 때 추가 확인. 경유지는 유지 |
| ‘아니요’ / ‘추가하지 마’ | DECLINED, NOT_REQUESTED, 실제 요청 false |
| 불명확한 응답 | UNKNOWN_RESPONSE, NOT_REQUESTED, 실제 요청 false |
| 무음 / 인식 실패 | NO_RESPONSE 또는 NO_MATCH 등, 실제 요청 false |
| 질문 중 목적지·경로·다음 휴게소 변경 | 이전 제안으로 추가 금지. 새 제안과 새 동의 필요 |
| 취소 / 음성 준비 해제 / 정차 조건 이탈 | 음성 정리 후 1건 저장. 요청 전·후를 구분. 요청 뒤 취소는 적용 미확인 가능 |
| 추가 시 내비 전면 전환 | 앱 범위 처리와 microphone FGS가 이어지고 결과·이력이 보존됨 |
| 처리 중 프로세스 종료 | 재시작 시 완료 미확인 표시. 자동 재시도·자동 삭제 없음 |

실행은 90초 제한, 취소 후 이력 저장은 최대 10초다. SDK 취소 정리가 끝나고 이력 저장을 마칠 때까지 다른 실제 시험을 막고 이전 자동 개입 활성 상태를 복구한다. 화면·탭 이탈만으로 이 통합 시험을 취소하지 않는다. 기존 수동 음성 시험의 화면 이탈 취소는 유지한다.

## 이력·집계 분리

Room 스키마는 v2 그대로 유지한다. 새 이력은 `triggerReason/source=DANGER_NAVIGATION_PROBE`, `simulation=true`, `inputSource=MANUAL_PARKED_TRIGGER`, `effects=REAL_EMULATOR`, `executionPath=DANGER_NAVIGATION_ACTION`으로 저장한다. 피로 점수 0은 시험용 값이며 측정한 점수가 아니다. PCM은 저장하지 않는다.

`getAllHistories()`는 진단용 원본 전체를 유지한다. 새 `getDrivingHistories()`와 전송 대기 조회 `getUnsyncedHistories()`는 생산 저장기가 명시한 `"simulation":false` 기록만 허용하고 알려진 시험 source를 제외한다. 출처가 불분명한 과거 기록도 실제 성과로 추정하지 않는다. 새 홈의 권유·주행 안내·내 기록·요약은 필터된 이력을 사용한다. 서버 API 송수신 자체를 구현·검증한 것은 아니다.

## 자동 검증과 남은 기기 검증

이번 변경 최종 검증: 표준 Gradle 단위 테스트 **505개 통과**(departure-core 149, departure-ui 44, app 312), 앱·계측 APK 빌드 성공. 앱·시험 APK를 `install --user 10 -r`로 갱신 설치하고 아래 선택 계측 **3개를 직접 실행해 전부 통과**했다. 실제 음성 동의·경유지 쓰기를 실행한 결과는 포함하지 않는다.

- 단위 테스트: 동의·거절 계약, 중복 시작, 준비 조건, 즉시 취소, 요청 후 취소 정보 보존, 저장 대기·실패, 90초 제한, 프로세스 종료 복원. 합성 음성·내비 포트 결과이며 실제 발화를 대신하지 않는다.
- 기기 계측: `DangerProbeEntryTest`는 홈 → 개발자 도구 → 새 화면 진입과 초기 시작 비활성화를 확인한다. 재생·녹음·경로 쓰기는 하지 않는다.
- 기기 계측: `DrivingHistoryIsolationTest`는 별도 인메모리 Room에서 실제/시험 이력·전송 대기 분리를 검증한다. 사용자 DB는 변경하지 않는다.
- 실제 SDK: `NaviWaypointProbeTest`의 기본 조회 모드로 TBT·즐겨찾기를 읽었다. 현재 경로는 TBT 15개, 휴게소 후보 0개, 즐겨찾기 3개 중 휴게소 인식 1개·다음 시설 일치 0개였다. 따라서 이 경로로 음성 동의→실제 추가 성공을 검증했다고 할 수 없다. 시험을 위해 기존 목적지나 경로를 자동 변경하지 않았다.
- **남음:** 휴게소가 있는 경로를 사람이 준비한 뒤 위 표의 실제 발화, 경유지 유지, 전면 전환, 취소 경계를 기기에서 확인한다.

표준 빌드(Gradle JDK 17/21):

```powershell
.\gradlew.bat :departure-core:test :departure-ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

선택한 계측만 실행한다. 전체 `connectedDebugAndroidTest`에는 기존 경로를 변경하는 시험도 있으므로 이번 조회·화면 확인의 재현 명령으로 쓰지 않는다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install --user 10 -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb install --user 10 -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb shell am instrument --user 10 -w -e class 'ai.pleos.playground.sample.vehicle.intervention.DangerProbeEntryTest,ai.pleos.playground.sample.vehicle.intervention.DrivingHistoryIsolationTest,ai.pleos.playground.sample.vehicle.navigation.NaviWaypointProbeTest' ai.pleos.playground.sample.vehicle.diagnostic.test/androidx.test.runner.AndroidJUnitRunner
```
