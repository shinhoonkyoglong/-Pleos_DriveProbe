# CAUTION·DANGER 음성 개입 연결

2026-11-11, 기준 커밋 `6399926` 이후 작업.

## 실제 개입 실행 조건

- 자동 개입 켜짐, 실제 주행 활성, D 기어, 유효한 피로 판단 창이 필요하다.
- 실제 CAUTION·DANGER에는 `DrivingVoiceService.ready=true`도 필요하다. 미준비 상태에서는 음성 요청을 실행하지 않으며 쿨다운을 소모하지 않는다.
- MILD 차량 제어의 기존 조건과 정책은 유지한다. Replay·기존 단계 시연 버튼은 실제 차량·음성·내비 API를 실행하지 않는다.
- 단계별 재시도 간격은 기존 5분 정책이다. 실제 요청 실패도 쿨다운에 포함된다.

## 출발 전 주행 음성 준비

1. 정차 상태에서 **개입 상태** 탭을 연다.
2. 마이크 권한을 허용한다. 처음 권한을 요청했다면 허용 후 준비 버튼을 다시 누른다.
3. **주행 음성 준비**를 누르고 **준비됨** 표시를 확인한다.
4. 사용을 마치면 **음성 준비 해제**, **개입 중지**, 또는 알림의 해제 동작을 사용한다.

서비스는 마이크 유형의 포그라운드 서비스다. 준비만으로는 AudioRecord를 열지 않는다. 실제 질문이 끝난 뒤 응답 구간에서만 마이크를 열고, 결과·시간 초과·취소 시 해제한다. 서비스가 종료되면 준비 상태가 해제되어 자동 음성 작업이 취소된다. 서비스는 OS 종료 후 자동 재시작하지 않는다.

주행 중 앱 화면이 가려진 뒤 서비스를 처음 시작하는 구조를 사용하지 않는다. Android는 사용 중 마이크 권한을 요구하는 서비스를 원칙적으로 앱의 화면이 보일 때 시작하도록 제한한다. [공식 Android 가이드](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## 단계별 처리

`InterventionModule.execute`의 CAUTION·DANGER는 `InterventionSpeechAction.execute`로 연결된다. 이 처리기는 PCM 응답 측정 경로(`askTimed`)로 질문·인식을 수행한다.

| 단계·입력 | 결과 | 내비 상태 |
| --- | --- | --- |
| CAUTION + 인식 문장 | ANSWERED, 문장·발화 시간 추정 및 출처 보존 | 해당 없음 |
| DANGER + 명확한 안내 동의 | ACCEPTED, consent=ACCEPT | NOT_CONNECTED: 실제 경로 변경 없음 |
| DANGER + 거절 | DECLINED, consent=DECLINE | NOT_REQUESTED |
| DANGER + 불명확한 대답 | UNKNOWN_RESPONSE, consent=UNKNOWN | NOT_REQUESTED |
| 시간 초과 | NO_RESPONSE | 경로 변경 없음 |
| 문장 없음·인식 실패 | NO_MATCH | 경로 변경 없음 |
| 서비스·권한 오류 | UNAVAILABLE, 오류 코드 보존 | 경로 변경 없음 |

`ACCEPTED`는 안내 동의를 받았다는 결과이며 내비 경로 변경 성공을 의미하지 않는다. 쉼터 검색 및 NaviHelper 경로 변경은 별도 작업이다. 응답 시간은 추정치로 저장하며 현재 피로 점수에는 반영하지 않는다.

## 취소와 단계 전환

- CAUTION을 중단하고 DANGER로 전환할 때 이전 작업의 녹음·SDK 정리와 이력 저장이 끝난 뒤 새 작업을 실행한다.
- 정리 중 다시 취소되거나 주행 세션이 바뀌어도 남아 있는 모든 선행 작업을 기다린다. 기존 generation 검사로 오래된 결과의 UI 덮어쓰기를 방지한다.
- 완료된 결과의 이력 저장은 NonCancellable 구간으로 처리한다. 저장 중 단계가 올라가도 완료된 결과가 중복 취소 이력으로 바뀌지 않는다.
- 정리 중에는 수동 음성 시험과 차량 제어 프로브를 시작하지 않는다.

## 저장 계약

실제 개입은 기존 `intervention_histories`에 원래 세션·피로 점수·조건 사유를 유지한다. `actionDetails`에 다음 정보를 저장한다.

- `outcome`, `details`, `responseMs`, `responseTiming`
- `readyLatencyMs`, `captureLatencyMs`, `epdOnBytes`, `epdOffBytes`, `audioBytes`, `recognitionEnd`
- `consent`, `navigationStatus`, `error`, `platformCode`, `recognitionBackend`
- 인식 문장은 기존 `userResponse` 컬럼에 저장한다. PCM 원본은 저장하지 않는다.

오프셋 또는 시간 기준이 없으면 문장이 있어도 `responseMs=null`일 수 있다. 추정 출처를 보존하며 측정법을 Android 발화 시작 콜백으로 잘못 표시하지 않는다.

## 정차 단계 연결 시험

음성 시험 탭의 새 버튼은 실제 단계와 같은 `InterventionSpeechAction`을 호출한다.

| 버튼 | 사용자 입력 | 기대 결과 |
| --- | --- | --- |
| 5. CAUTION 연결 시험 | 응답 대기 표시 후 '괜찮아요' | 응답 인식, 단계 결과 ANSWERED, 시간 추정 또는 측정 없음 |
| 6. DANGER 연결 시험 | '안내해 줘' | 안내 동의, 단계 결과 ACCEPTED, 내비 준비 중·경로 변경 없음 |
| 6. DANGER 연결 시험 재실행 | '아니요' | 안내 거절, 단계 결과 DECLINED |

시험은 P·속도 0·차량 화면 사용 가능 상태에서만 시작한다. 자동 개입을 잠시 중지했다가 복원하며 점수는 조작하지 않는다. 이력에는 `source=VOICE_TEST`, `simulation=true`, `executionPath=INTERVENTION_SPEECH_ACTION`, `stageOutcome`, `consent`, `navigationStatus`를 남겨 실제 주행 리포트에서 제외한다. `simulation=true`는 시험 이력 구분이며 음성을 가짜로 처리한다는 뜻은 아니다.

## 검증 범위

- APK 빌드 및 앱 단위 테스트 220개 통과. 단계별 처리, 동의·거절·불명확 응답, 시간 초과·인식 실패, 측정 출처, 오류 보존, Replay의 실제 음성 호출 차단을 검증했다.
- 지연되는 녹음 정리 중 단계 상승·취소·새 주행 시작·저장 중 단계 상승에서 순서와 중복 저장 방지를 검증했다.
- 에뮬레이터의 화면 버튼으로 준비 서비스를 시작하고 `isForeground=true`, 유형 `0x80`, `mAllowWhileInUsePermissionInFgsReason=PROC_STATE_TOP`을 확인했다. 다른 화면으로 이동한 뒤에도 유지됐고, 준비·배경 이동·해제 중 앱의 녹음 이벤트가 추가되지 않음을 확인했다.
- 서비스 해제와 5·6번 버튼 표시를 확인했다. 실제 피로 이벤트에 따른 주행 중 자동 발동은 추가 확인이 필요하다.
- PC 마이크 경로를 끈 무음 입력으로 5번 CAUTION(#76), 6번 DANGER(#77)를 실행했다. 각각 NO_RESPONSE 1건 저장, `stageOutcome=NO_RESPONSE`, `executionPath=INTERVENTION_SPEECH_ACTION`, `source=VOICE_TEST`, `simulation=true`, `responseMs=null`을 확인했다. Android 녹음 시작·종료 이벤트도 대응됐다. 종료 후 PC 마이크 연결을 다시 켜고 P·속도 0 상태에서 음성 시험 화면을 열어두었다.

### 사용자 발화 검증

| 이력 | 단계 | 인식 문장 | 결과 | 발화 추정 시간 | 내비 |
| --- | --- | --- | --- | --- | --- |
| #78 | CAUTION | 괜찮아요 | ANSWERED | 1,019ms | 요청하지 않음 |
| #79 | DANGER | 안내해 | ACCEPTED | 1,018ms | NOT_CONNECTED · 경로 변경 없음 |
| #80 | DANGER | 싫어 | DECLINED | 24ms | NOT_REQUESTED |

사용자가 제공한 결과 화면으로 문장·단계 결과·시간 표시·이력 저장을 확인했다. 이 값은 발화 위치에 기반한 추정치이며 시간 측정 정확도나 운전자 피로도를 입증하지 않는다. 특히 녹음 시작에 가까운 발화는 실제 시작이 수집 구간보다 빨랐는지 구분하지 못할 수 있다. 실제 주행 자동 발동·다른 앱 사용 중 장시간 동작·쉼터 경로 변경은 별도 검증/구현 대상이다.
