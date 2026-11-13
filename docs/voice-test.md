# 정차 중 음성 시험

CAUTION·DANGER 공통 처리, 5·6번 단계 연결 시험, 출발 전 음성 준비는 [음성 개입 연결](intervention-voice-flow.md)을 참고한다.

## 최종 사용자 검증 결과 (2026-11-11)

사용자가 에뮬레이터에서 직접 듣고 말한 뒤 결과 화면으로 확인했다.

| 항목 | 확인 결과 | 저장 이력 |
| --- | --- | --- |
| 질문 재생 | 실제 질문 소리가 들림 | #54 재생 완료 |
| 응답 시험 | '괜찮아요' 인식, 결과 '응답 인식' | #57 |
| 안내 동의 | '안내해' 인식, 결과 '안내 동의' | #58 |
| 안내 거절 | '아니' 인식, 결과 '안내 거절' | #59 |
| 응답 시간 시험 1 | '괜찮아요' 인식, 발화 추정 1,027ms·녹음 시작 지연 27ms | #60 |
| 응답 시간 시험 2 | '괜찮아요' 인식, 발화 추정 2,023ms·녹음 시작 지연 23ms | #61 |

인식·동의 판별・추정 시간 표시・이력 저장은 위 범위에서 확인됐다. 동의 시험은 실제 경로를 변경하지 않는다. 기존 2·3번 시험의 응답 시간(ms)은 `측정 없음`으로 표시한다. 4번은 오디오 프레임 시각 기준으로 동작했다. #60·#61의 EPD 시작 오프셋은 각각 32,000·64,000 byte(1초·2초)였으므로, 이 두 사례만으로 밀리초 수준의 발화 검출 정확도를 입증하지는 않는다. 아래 내용은 구성 및 오류 수정 이력이다.

`feat/hvac-control`의 디버그 빌드에서 **음성 시험** 탭으로 진입한다. 피로 점수가 올라갈 때까지 기다릴 필요가 없다.

## 준비

- Pleos 에뮬레이터의 기어를 P, 속도를 0으로 둔다.
- CarUxRestrictions가 실제 car service에서 '화면 사용 가능'으로 확인되어야 버튼이 활성화된다. 미확인·주행 제한·Mock UX 값에서는 시작하지 않는다.
- 화면의 서비스 상태는 서비스 **등록 여부**다. 한국어 재생과 실제 마이크 입력 가능 여부는 버튼으로 시험한다.
- PC 마이크를 에뮬레이터에 연결하려면 emulator console 명령을 사용한다. 이 명령은 해당 SDK의 `adb emu help avd`에서 확인했다.

```powershell
$adb = 'C:\Users\SSAFY\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb emu avd hostmicon
# 시험 후 PC 마이크 입력 경로를 끄려면:
& $adb emu avd hostmicoff
```

앱의 **마이크 권한 허용** 버튼으로 Android 권한을 확인한다. 권한 허용만으로 앱이 녹음을 시작하지 않는다. 응답·동의 시험에서 질문 재생이 끝난 뒤 음성 인식 서비스에 듣기를 요청한다. 질문만 재생은 인식 서비스를 호출하지 않는다.

## 사람이 확인할 순서

| 버튼 | 할 일 | 화면에서 확인할 내용 |
| --- | --- | --- |
| 1. 질문만 재생 | 질문이 실제로 들리는지 확인 | 재생 중 → 재생 완료 콜백 수신 → 이력 저장 완료 |
| 2. 응답 시험 | '응답 대기 중'이 나오면 **괜찮아요**라고 말하기 | 발화 감지 → 인식 문장 → 응답 시간(ms) → 이력 저장 완료 |
| 4. 응답 시간 시험 | 대기 표시 후 **괜찮아요**라고 말하기 | 인식 문장, 발화 시작 추정 시간(ms), 시간 기준, 이력 저장 완료 |
| 3. 동의·거절 시험 | **안내해 줘**라고 말하기 | 안내 동의. 시험에서는 실제 경로가 변경되지 않음 |
| 3. 동의·거절 시험 재실행 | **아니요**라고 말하기 | 안내 거절 |
| 2. 응답 시험 재실행 | 말하지 않고 기다리기 | 최대 10초 후 시간 초과. 제공 서비스가 먼저 NO_MATCH를 반환하면 문장 인식 실패로 구분 |
| 시험 중지 | 질문 재생 또는 응답 대기 중 누르기 | 재생/인식 취소, 중지 결과 저장 |

화면을 다른 탭으로 바꾸거나 앱 화면을 나가거나 차량 상태가 주행으로 바뀌어도 수동 시험을 취소한다. 화면을 계속 켜 둔 상태로 시험한다. 현재 수동 시험은 주행 중 실행 기능을 검증하는 버튼이 아니다.

질문 재생만 성공했다고 음성 응답 성공으로 판단하지 않는다. TTS 완료 콜백도 실제 소리가 들렸다는 증거는 아니므로 사람이 소리를 확인해야 한다.

## 종료·실패 시나리오 검증 (2026-11-11)

아래는 에뮬레이터의 실제 앱·Room DB·차량 서비스·Android 녹음 이벤트로 확인했다. 녹음 시나리오는 PC 마이크 경로를 끈 무음 입력으로 실행해 실제 사용자의 목소리를 수집하지 않았다. 기존 성공 이력은 보존했다.

| 대상 | 시나리오 | 결과 | 이력 |
| --- | --- | --- | --- |
| 질문 재생 | 시험 중지 | TTS 중지 전달, CANCELLED 1건 | #63 |
| 질문 재생 | 다른 탭으로 이동 | TTS 중지 전달, CANCELLED 1건 | #64 |
| 질문 재생 | 앱 화면 종료(onStop) | TTS 중지 전달, CANCELLED 1건 | #65 |
| 질문 재생 | P·0 → D·60km/h | 조건 보호로 취소, TTS 중지 전달, CANCELLED 1건 | #66 |
| 시간 시험 녹음 | 시험 중지 | AudioRecord 종료, CANCELLED 1건 | #68 |
| 시간 시험 녹음 | 다른 탭으로 이동 | AudioRecord 종료, CANCELLED 1건 | #69 |
| 시간 시험 녹음 | 앱 화면 종료(onStop) | AudioRecord 종료, CANCELLED 1건 | #70 |
| 시간 시험 녹음 | P·0 → D·60km/h | AudioRecord 종료, CANCELLED 1건 | #71 |
| 시간 시험 녹음 | 무음 대기 | NO_RESPONSE 1건, responseMs=null, AudioRecord 종료 | #72 |
| 기존 응답 시험 | 녹음 중 시험 중지 | CaaS 녹음 종료, CANCELLED 1건 | #73 |
| 기존 응답 시험 | 무음 대기(독립 재시험) | NO_RESPONSE 1건, responseMs=null, CaaS 녹음 종료 | #75 |

- 정차 상태 복구 후 속도 0·P 기어 및 공조·창문 값 보존을 확인했다. 실제 자동차가 아니라 에뮬레이터에서의 주행 전환 검증이다.
- #74는 기존 응답 시험의 무음 대기 도중 차량/화면 조건 보호로 취소되었다. 녹음 종료와 단일 CANCELLED 저장은 확인했지만 무응답 시험 통과로 세지 않았다.
- 조건 보호 진단을 보강했다. 취소 결과에 실제 차단 조건(기어, 속도 신호 없음, 속도 0 아님, 화면 상태 미확인/제한)을 남기며, 앱 로그에 당시 신호 상태를 기록한다. 시험 허용 조건 자체는 바꾸지 않았다.
- 제어기 단위 테스트에는 모든 모드 × 중지·탭 이탈·onStop·차량 조건 변경의 16가지 조합을 추가했다. 비동기 녹음 정리가 끝나기 전 재시작 차단, 중복 취소 중 단일 이력 저장, 정리 후 재시작, 무응답 후 자동 개입 상태 복원을 검증한다. 실제 차단 사유가 취소 이력까지 보존되는 회귀 테스트도 추가했다.
- 최종 Debug APK 빌드 및 앱 단위 테스트 204개 통과(실패·오류·건너뜀 0). 진단 보강 APK를 설치했다. #75는 Virtual Console 세션 갱신을 제외한 독립 조건에서 실행했다. 시험 종료 후 Android 녹음 종료 이벤트를 확인하고 PC 마이크 연결을 다시 켰다. 실제 앱에서 음성 시험 허용 상태(P·속도 0·차량 화면 제한 없음)와 화면 재진입을 확인했다.
- 현재 커밋 기준은 `4e6fb74`(음성 시험 기능)이다. 이후 PCM 추정 시간·종료 시나리오 테스트·조건 보호 진단·이 문서가 변경되었다. 작업 환경의 `.git` 쓰기 제한 때문에 커밋/푸시는 Android Studio 터미널에서 수행해야 한다.

## 반응 시간

### 4번 응답 시간 시험: PCM 발화 추정

- `TIMING` 모드는 사용자가 버튼을 누르면 질문 재생 후 앱의 `AudioRecord`로 PCM16·16kHz·모노를 수집한다. 같은 바이트를 Pleos STT SDK `ON_DEVICE.sendAudio()`에 전달한다. SDK의 차량 마이크 `request()`는 이 모드에서 호출하지 않는다.
- 질문 재생 종료는 기존 `SystemClock.elapsedRealtime()` 단조 시간이다. 오디오 프레임 시각은 `AudioTimestamp.TIMEBASE_BOOTTIME`을 사용해 같은 시간 기준으로 맞춘다.
- 계산식: `(첫 PCM 프레임 시각 + EPD 시작 byte / 32,000초) - 질문 재생 종료 시각`.
- 오디오 타임스탬프가 없으면 첫 버퍼 수신 시각에서 누적 샘플 길이를 빼서 녹음 시작을 추정한다. 화면에 버퍼 수신 기준임을 표시한다. 둘 다 EPD 검출 오차가 있으므로 순수한 사람의 반응 시간이나 임상 측정치로 단정하지 않는다.
- SDK 최종 텍스트보다 EPD가 뒤에 오는 순서를 고려해 최종 문장 수신 후 최대 250ms 동안 EPD를 기다린다. 음성 인식 성공 여부와 시간 계산 성공 여부는 별개다.
- EPD 누락, 잘못된 오프셋, 수집 바이트 범위 초과, 질문 이전 발화 시각이면 `responseMs=null`이다. byte 0에서 시작하는 유효한 발화는 누락으로 취급하지 않는다.
- 저장: `mode=TIMING`, `responseMs`, `captureLatencyMs`, `epdOnBytes`, `epdOffBytes`, `audioBytes`, `responseTiming`. 시간 기준은 `PCM_AUDIO_TIMESTAMP_EPD_ESTIMATE` 또는 `PCM_READ_RECEIPT_EPD_ESTIMATE`이며 계산 불가는 `UNMEASURED_PCM_EPD`다.
- 화면·최근 이력의 숫자에 '발화 추정' 표시를 붙인다. 수집 PCM은 메모리에서만 처리하며 파일로 저장하지 않는다. 결과·중지·화면 이탈·주행 전환 시 로컬 AudioRecord를 중지·해제한 뒤 SDK 세션도 중지한다.
- 사용자 확인: 4번에서 대기 표시 직후 '괜찮아요'를 말하고, 다시 실행해 대기 표시 후 약 2초 기다렸다 말한다. 인식 문장·시간 기준·추정 ms·저장 완료를 함께 확인한다. 두 값은 녹음 준비 지연 차이도 포함하므로 차이가 정확히 2,000ms라고 단정하지 않는다.
- 빌드 및 앱 테스트 201개 통과. 추가 11개 테스트는 샘플 시간 환산, 오디오 타임스탬프 우선순위, byte 0 발화, EPD 누락·지연·잘못된 범위, 시간 기준 불일치·오버플로 입력, 중복 이벤트, TIMING 경로 선택·측정 출처 보존을 검증한다. 설치 후 4번 버튼 활성화를 확인했다. 이후 사용자가 #60·#61에서 실제 PCM 인식·추정 시간 표시·저장을 확인했다. 종료·실패 시나리오는 위 별도 표에서 확인한다. 시간 측정 정확도 검증은 남아 있다.

공식 근거: [Pleos PCM 전송](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data), [EPD 바이트 오프셋](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord).

### 기존 2·3번 시험

- Pleos STT SDK 경로에서는 발화 위치가 오디오 바이트 오프셋으로 제공된다. 현재는 실제 발화 시각으로 환산하지 않으며 `responseMs=null`, `responseTiming=UNMEASURED_PLEOS_EPD_BYTE_OFFSETS`로 저장한다. 인식 성공 여부와 시간 측정 여부를 구분한다.
- 아래 Android 콜백 기반 측정은 일반 Android 인식 경로에만 적용한다.
- 시작: Pleos SDK `EventListener.onDone`이 호출된 단조 시간. 일반 Android TTS 경로에서는 `UtteranceProgressListener.onDone`을 사용한다.
- 종료: `RecognitionListener.onBeginningOfSpeech`가 **처음** 호출된 단조 시간.
- `onReadyForSpeech`까지의 준비 지연도 별도로 저장한다.
- 인식 엔진의 준비·발화 감지 지연이 포함될 수 있다. 순수한 사람의 반응 시간으로 단정하지 않는다.
- 최종 문장은 수신했지만 발화 시작 콜백이 없으면 응답 시간은 null, 화면에는 **측정 없음**으로 표시한다. 0ms를 만들어 넣지 않는다.
- 인식 시작은 TTS 완료 이후다. 권한 오류·서비스 부재·한국어 데이터 없음·재생 시간 초과·인식 오류를 구분해 표시한다.

## 이력 저장

DB 스키마는 바꾸지 않고 `intervention_histories`를 사용한다. 테스트를 실제 운전 개입과 명확히 구분한다.

- `sessionId`: `voice-test-...`
- `triggerReason`: `VOICE_TEST`
- `actionDetails.source`: `VOICE_TEST`
- `actionDetails.simulation`: true — 실제 운전 리포트에서 제외하기 위한 테스트 표시다. 오디오 자체를 흉내 낸다는 뜻이 아니다.
- `actionDetails.audioMode`: `REAL_PLATFORM_TTS_STT`
- `actionDetails.ttsBackend`: `PLEOS_ON_DEVICE` / `ANDROID` / `NONE`
- `actionDetails.mode`: PLAYBACK / RESPONSE / CONSENT / TIMING
- `outcome`, `details`, `responseMs`, `readyLatencyMs`, `recognitionEnd`, `error`, `platformCode` 저장.
- `userResponse`: 실제 인식된 문장. 음성 원본은 앱에서 저장하지 않는다.
- 피로 점수는 측정하지 않는다. 기존 컬럼의 0은 미측정용 값이며 화면에서 테스트의 피로 점수로 표시하지 않는다.
- 최근 음성 시험은 `getLatestVoiceTest()`로 별도 조회하므로 앱을 다시 열어도 마지막 저장 결과를 확인할 수 있다.

수동 시험 중에는 자동 개입을 잠시 중지한다. 시험이 끝나면 기존 활성 상태로 복귀한다. 동일 어댑터의 Mutex가 겹치는 재생/인식 요청을 차단한다. 저장 실패가 나도 인식 결과 자체는 화면에 남기며, 다시 실행할 수 있다.

## 로그

```powershell
& $adb logcat -s VoiceTest:I Intervention:I '*:S'
```

`history=... mode=... outcome=... responseMs=... error=... platformCode=...`를 확인한다. 앱 로그에는 인식 문장 원문을 출력하지 않는다. 인식 문장은 시험 화면과 로컬 DB에서 확인한다.

## 검증 범위

빌드·단위 테스트와 화면/서비스 등록 상태 확인은 자동으로 수행할 수 있다. 실제 질문 소리, PC 마이크 입력, 인식 정확도와 무응답 결과는 위 순서대로 사람이 확인해야 한다. 이 문서의 시험 환경 구성만으로 음성 기능이 실제로 성공했다고 판단하지 않는다.


## 이번 구성에서 확인한 것 (2026-11-11)

- 최종 Debug APK 빌드 성공. 앱 단위 테스트 188개 + departure-core 147개 = 335개 통과.
- 추가 17개 테스트는 반응 시간의 누락·중복·잘못된 시각, 정차 게이트, 재생만 실행 시 STT 미호출, 중복 실행 방지, 차량 상태 변경 취소, 권한/서비스 오류, 무응답/NO_MATCH, 동의 판별, DB 실패, 저장 중 취소 시 이중 저장 방지, Pleos/Android 재생 경로 선택을 확인한다.
- 새 APK 설치·기동 및 음성 시험 탭 표시 확인. 정차 상태에서 세 시험 버튼이 활성화됨을 확인.
- 실제 기기에서 재생 서비스·인식 서비스 등록과 마이크 권한 허용 상태 표시를 확인.
- `adb emu avd hostmicon`은 OK를 반환했다. PC 마이크 입력 경로를 켜 둔 상태다.
- 연결 수정 후 '질문만 재생'을 실행해 SDK 초기화, `onReady → onStart → onDone`, `PLAYBACK_COMPLETED`, 이력 #45 저장을 확인했다. 기기 로그에서 출력 오디오 154,112 bytes, 언더런 증가 0을 확인했다.
- 응답·동의 시험은 자동 실행하지 않았다. 실제 소리가 들리는지, 마이크 입력, 인식 문장·반응 시간·무응답 결과는 사용자의 수동 확인이 남아 있다.

## Pleos 음성 엔진 연결 수정 (2026-11-11)

기존 APK에서 발생한 `NO_TTS`는 일반 Android TTS 연결을 사용한 결과다. 이 에뮬레이터의 `ai.pleos.playground.caas` 서비스는 조회되지만 Android의 기본 TTS 엔진 선택 조건에는 맞지 않았다. 서비스 등록 표시는 초기화 성공을 뜻하지 않는다.

- Pleos TextToSpeech SDK 3.3.1의 `Mode.ON_DEVICE`로 연결한다. 클라우드 등록 정보는 사용하지 않는다.
- `pleos.car.permission.TTS_SERVICE` 권한을 선언한다.
- Pleos SDK 서비스가 있는 기기는 SDK를 사용하고, 그 외 기기는 Android에서 실제 선택 가능한 TTS 엔진을 사용한다. 화면에서 재생 경로를 확인할 수 있다.
- SDK 3.3.1이 요구하는 CaaS 최소 버전은 4.1.29다. 현재 에뮬레이터의 설치 버전은 4.2.10이다.
- SDK 초기화 성공 후 이벤트 리스너를 등록한다. 초기화 전에 등록하면 SDK 내부 `lateinit property tts has not been initialized` 오류가 발생한다.
- 한국어 로케일은 `Locale.KOREA`(`ko-KR`)를 사용한다. `Locale.KOREAN`(`ko`)은 이 기기에서 `unsupported locale: ko-` → `ON_DEVICE_ENGINE_ERROR`를 반환함을 실제 로그로 확인했다.
- 오류가 발생하면 SDK 오류 이름을 그대로 보존한다. 초기화·재생 완료를 받지 못한 요청은 성공으로 기록하지 않는다.

SDK 제공 여부는 [Pleos 공식 리소스](https://developers.pleos.ai/playground/discover/resources), 버전과 공개 API는 공식 Maven 배포물 `ai.pleos.playground:TextToSpeech:3.3.1`에서 확인했다.

## 응답 시험 #53 이후 STT 연결 수정 (2026-11-11)

- 사용자가 질문 재생 소리를 확인했다. 기존 응답 시험 #53에서는 Android `AmbRecognitionService.onStartListening`만 호출되고 준비·발화·결과 콜백 없이 10초가 지나 취소되었다. 사용자 무응답이나 PC 마이크 고장으로 확정할 수 없다.
- Pleos `SpeechToText:3.3.1`을 직접 연결하고 `STT_SERVICE` 권한을 선언했다. `Mode.ON_DEVICE`, `Locale.KOREA`를 사용한다. SDK 초기화 → 리스너 등록 → 언어 설정 → request 순서다.
- `onReady`를 받아야 화면을 '응답 대기 중'으로 전환한다. 준비 확인 5초 초과는 인식 서비스 오류로 기록하고, 준비 후 10초 동안 최종 결과가 없을 때 응답 시간 초과로 기록한다. 준비 콜백보다 최종 결과가 먼저 오더라도 실제 결과는 보존한다. 일반 Android 경로도 준비 콜백 누락을 서비스 오류로 구분한다.
- 입력 레벨 콜백 횟수와 최대 RMS를 결과 화면과 이력에 추가했다. RMS는 dB가 아니며, 콜백 수신만으로 실제 발화가 정상 입력되었다고 단정하지 않는다. 인식 문장 원문은 앱 로그에 출력하지 않는다.
- `adb emu avd hostmicon`은 다시 OK를 반환했고, Android 마이크 음소거 플래그는 모두 false였다. Windows 입력 장치와 실제 입력 음량은 이 검사만으로 확인되지 않는다.
- 응답·동의 버튼을 도구로 실행하지 않는다. 설치 후 사용자 재시험으로 실제 인식 여부를 확인한다. SDK의 발화 바이트 오프셋을 정확한 응답 시간으로 바꾸는 작업은 남아 있다.
- 수정 APK 빌드 및 앱 단위 테스트 190개 통과(실패·오류·건너뜀 0). 설치 후 `STT_SERVICE: granted=true`, 화면의 'Pleos STT SDK · 기기 내 인식', 마이크 권한 허용 표시를 확인했다. 음성 시험 화면을 열어두었고 응답 버튼은 누르지 않았다.

공식 안내: [STT 개요](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro), [리스너](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [마이크 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator).

## 시작 판정 오류 수정 (#55·#56 이후)

- #55에서 SDK `onReady`, 입력 RMS 11회(최대 약 2527), 최종 텍스트 결과가 실제로 도착했다. 하지만 앱이 `onStartedRecognition`을 기다려 오류로 저장했다. #56도 `onReady`, RMS 46회(최대 약 1262)가 있었지만 같은 오류로 처리됐다. 실제 마이크 입력과 최종 결과가 존재했으므로 마이크 불통으로 판정하면 안 된다.
- 준비 판정을 `onReady`로 바로잡았다. `onStartedRecognition` 누락은 최종 텍스트 수신을 막지 않는다. 기존 실패 이력은 당시 동작 기록으로 유지한다.
- `sdk.stop()`이 비동기인데 곧바로 리스너 제거·release를 수행하면 중지 요청이 전달되기 전에 연결이 끊길 수 있었다. 취소 중에도 중지 요청 후 종료 이벤트를 최대 1.5초 기다리고 연결을 해제한다. Debug 앱 재기동 시 이 앱의 이전 인식 세션을 중지하는 정리 작업도 추가했다. 이 작업은 `request()`를 호출하지 않는다.
- 수정 중 PC 마이크 경로를 일시적으로 껐다. 이전 세션의 중지 전달을 기기 로그로 확인한 후 재시험용 입력 경로를 다시 켠다.
- 수정 빌드·앱 테스트 190개가 통과했다. 재설치·기동 후 `OnDeviceSttBinder.close(이 앱 패키지)` 수신을 확인했고, 이전 jobId=2의 반복 인식 로그가 그 시점에 멈췄다. 새 인식 요청은 실행하지 않았다. PC 마이크 경로는 다시 켰으며(`hostmicon: OK`) 음성 시험 탭을 열어두었다. 최종 문장 표시 성공은 사용자 재시험이 남아 있다.
