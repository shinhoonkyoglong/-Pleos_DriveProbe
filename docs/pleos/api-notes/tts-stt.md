# TextToSpeech SDK (v3.3.1) / SpeechToText SDK (v3.3.1)

- 출처 (모두 확인일 2026-11-09):
  - TTS Introduction — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro
  - Importing TextToSpeech SDK — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk
  - Declaring permissions — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions
  - Initialization — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release
  - Event listeners — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners
  - Set locale — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale
  - App registration — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration
  - Start and stop synthesis — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop
  - Error code — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/error-code
  - TTS API Reference — `.../TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/entry`, `.../speak`, `.../set-speaker-type`
  - TTS / 음성 안내 선행 묵음 적용 가이드 — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide
  - 오디오 포커스 개발 가이드 — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development
  - STT Introduction / Importing / Declaring permissions / Event listeners / Start and stop — https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro (같은 폴더의 `importing-sdk`, `declaring-permissions`, `result-listeners`, `start-and-stop`)
  - 프로젝트 생성 및 SDK 사용 신청 — https://document.pleos.ai/docs/pleos-only/create-my-project
- 확인 수준: [문서 확인] + [문서 재확인(2026-11-09)] App registration 페이지 + [Playground 확인(2026-11-09)] SDK 상태. 실행 확인 안 함.

## 초보자용 개념 설명
- 우리 앱이 소리를 직접 재생하는 게 아니다. `speak("...")`로 텍스트를 Pleos Speech Service에 넘기면 **서비스가 합성해서 차량 스피커로 재생**한다("합성된 음성을 차량의 오디오 시스템으로 재생합니다").
- 모드 3개:
  - `HYBRID`(기본): 서버 우선, 네트워크 실패 시 온디바이스로 자동 전환. 서버 쪽을 쓰려면 앱 등록 필요.
  - `SERVER`: 서버만. 네트워크·앱 등록 필수, 실패해도 온디바이스로 안 넘어감.
  - `ON_DEVICE`: 차량 내장 엔진. **네트워크·앱 등록 불필요.**
- 앱 등록 = Pleos Playground My Project에서 받은 `Client ID`/`Client Secret`으로 `registerApp()` 호출. (Playground 로그인 필요 → 이번 조사에서는 진행하지 않음)

## 의존성/설정
- 저장소: `google()`, `mavenCentral()`, `maven("https://nexus-playground.pleos.ai/repository/maven-releases/")`
- 의존성: `implementation("ai.pleos.playground:TextToSpeech:3.3.1")` / `implementation("ai.pleos.playground:SpeechToText:3.3.1")`
- 권한: `pleos.car.permission.TTS_SERVICE` / `pleos.car.permission.STT_SERVICE` (각각 초기화·리스너·등록·요청 모든 API에 필요)

## 핵심 시그니처 — TextToSpeech (Reference 그대로)
```kotlin
class TextToSpeech(context: Context, mode: Mode = Mode.HYBRID)   // ai.pleos.playground.tts.TextToSpeech

@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun initialize()
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun initialize(listener: OnInitializeListener)   // onSuccess() / onFailure(errorType: ErrorType)
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun registerApp(clientID: String, clientSecret: String, listener: OnServerConnectionListener) // onConnected() / onFailed(errorType)
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun addEventListener(listener: EventListener)
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun removeEventListener(listener: EventListener)
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun speak(text: String, isFinal: Boolean = true)
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun stop()
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun release()
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun setAudioAttributes(audioAttributes: AudioAttributes)
@RequiresPermission("pleos.car.permission.TTS_SERVICE") fun setAudioZoneId(zoneId: Int)
fun setLocale(locale: Locale)           // 지원: ko-KR, en-US, fr-CA, es-US, es-MX
fun setSpeakerType(type: String)        // 한국어: seoyoon, eunwoo, haeun, sanghyeok (기본 haeun)

interface EventListener {
    fun onReady(); fun onStart(); fun onUpdatedRms(rms: Double)
    fun onDone(); fun onStop(); fun onError(errorType: ErrorType)
}
```
`ErrorType`: `AUDIO_ERROR`, `INVALID_REQUEST_ERROR`, `MIC_ERROR`, `NETWORK_ERROR`, `NOT_SUPPORTED_API`, `ON_DEVICE_ENGINE_ERROR`, `PERMISSION_DENIED`, `SERVER_INTERNAL_ERROR`, `TIMEOUT`, `UNAUTHENTICATED`, `UNAVAILABLE_LANGUAGE`, `UNKNOWN`. 문서 권고: "새로운 오류 타입이 추가될 수 있으므로 `when` 또는 `switch` 문에 기본 분기를 두는 것을 권장".

## 사용 예시 (문서 요약, Kotlin)
```kotlin
val textToSpeech = TextToSpeech(applicationContext, Mode.ON_DEVICE)   // 등록 없이 쓰려면 ON_DEVICE
textToSpeech.initialize(object : OnInitializeListener {
    override fun onSuccess() {
        textToSpeech.addEventListener(eventListener)
        textToSpeech.setLocale(Locale.KOREA)
        textToSpeech.speak("해 지기 전에 도착하려면 30분 안에 출발하세요.")
    }
    override fun onFailure(errorType: ErrorType) { /* 처리 */ }
})
// 여러 조각을 한 발화로: speak("안녕하세요. ", isFinal = false); speak("...", isFinal = true)
textToSpeech.stop()     // → onStop()
// 정리: removeEventListener 후 release. release 후엔 새 객체 + initialize
```
(문서 예제는 `Mode.HYBRID`. `ON_DEVICE` 선택과 문장은 우리 예시)

## SpeechToText 요약
```kotlin
class SpeechToText(context, mode: Mode = Mode.HYBRID)  // ai.pleos.playground.stt.SpeechToText
initialize() ; addListener(ResultListener) ; setLocale(...) ; request() ; stop() ; removeListener(...) ; release()
interface ResultListener {
    fun onReady(); fun onStartedRecognition()
    fun onUpdated(stt: String, completed: Boolean)   // completed=true면 최종 결과
    fun onUpdatedEpdData(on: Long, off: Long); fun onUpdatedRms(rms: Double)
    fun onEndedRecognition(); fun onError(errorType: ErrorType)
}
```
- STT는 텍스트 변환만 한다. "음악 재생과 같은 실제 동작은 실행하지 않습니다." 음성 명령 실행은 Gleo Interface 문서 소관.
- 에뮬레이터에서 STT 테스트 시 Extended Controls > Microphone에서 오디오 입력 허용 필요(Pleos Connect Emulator 설정 — https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator).
- '출발 전' 파트는 STT가 필수는 아님. 상세 시그니처(STT API Reference)는 이번에 자세히 보지 않았다.

## 제약·주의
- **주행 중 TTS 사용 제약**: TTS 자체를 주행 중 금지하는 문구는 찾지 못했다. 관련 정책은 다음뿐이다.
  - 선행 묵음: "모든 음성 안내 재생 시작 전 선행 묵음 150 ms ~ 200 ms 적용을 권장" (적용 범위에 "TTS 기반 음성 안내" 포함). TTS SDK가 서비스 측에서 재생하므로 앱이 묵음을 넣을 수 있는지/필요한지는 [불확실].
  - 오디오 포커스: 앱이 직접 재생하는 스트림은 `AudioAttributes` + `AudioFocusRequest`로 포커스를 요청해야 한다. TTS SDK 재생 시 포커스를 SDK가 처리하는지는 [불확실 — 문서 없음]. `setAudioAttributes()`로 오디오 용도(USAGE)를 지정할 수 있다.
  - 주행 중 화면 대신 음성 안내는 설계 가이드가 권장하는 방향("음성 명령이나 물리 버튼 등 … 입력 방식과 연계")이다.
- `HYBRID`/`SERVER`는 `onConnected()` 이후 `speak()` 호출해야 함.
- **SDK 사용 신청 상태**: TextToSpeech·SpeechToText는 "준비 중(Comming Soon)" 상태로 표시된다고 문서에 적혀 있다(create-my-project). [Playground 확인(2026-11-09)] 「계운」 프로젝트의 TextToSpeech·SpeechToText 페이지 상태는 모두 **"준비 중"**(신청 버튼 없음). 프로젝트 정보에는 클라이언트 ID·시크릿이 **이미 발급되어 있다**(값은 기록하지 않음). 그러나 SDK 상태가 "준비 중"이라 서버 모드 등록(`registerApp`)이 실제로 성공하는지는 [불확실 — 실행 확인 필요]. App registration 문서 재확인: ON_DEVICE 모드만 쓰면 앱 등록이 필요 없다는 문구는 그대로다. 그래서 우리 기본값은 계속 `ON_DEVICE`. 호환성은 "TextToSpeech SDK, SpeechToText SDK — 전체 호환"(API 호환성 및 가용성).

## 포트 설계 시사점
TTS는 "말해라 → 끝났다/실패했다"를 콜백(`onDone`/`onStop`/`onError`)으로 알려 주므로 TtsRepository 포트는 `suspend fun speak(text: String): SpeakResult` 하나면 충분하다(어댑터가 `onDone`까지 기다려 결과를 반환). 이벤트에 요청 ID가 없으니 어댑터는 한 번에 한 발화만 처리한다(큐 또는 Mutex). 초기화가 비동기(`OnInitializeListener`)이므로 어댑터 내부에서 지연 초기화하고, 실패하면 포트는 Unavailable을 돌려준다. 이렇게 하면 core가 TTS 없이도 동작한다. 모드(`ON_DEVICE`/`HYBRID`)와 Client ID·Secret은 포트 밖, 즉 1번 공통 기반이나 `:app` 조립 쪽 설정이다. 앱 등록 없이 확실히 동작하는 쪽은 `ON_DEVICE`다. `ErrorType`은 늘어날 수 있으므로 우리 도메인 오류는 "재시도 가능 / 권한 / 기타" 정도로 좁혀 매핑한다. 우리 파트에서 STT는 선택이므로 SttRepository 포트는 지금 만들지 않아도 된다.
