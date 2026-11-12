# 에뮬레이터 — 차량 데이터·주행 규제·위치·경로 주입/시뮬레이션

- 출처 (모두 확인일 2026-11-09):
  - Pleos Connect Emulator 설치 및 실행 — https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
  - Pleos Connect Emulator 설정 (마이크 접근 / Cold boot 섹션) — https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator
  - Pleos Connect Virtual Console 셋업 가이드 — https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator
  - Pleos Connect Virtual Console 사용 가이드 — https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
  - FAQ - 디버깅을 위한 주요 ADB Command 활용법 (주행 규제 활성화/해제 섹션) — https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/adb-command-reference-guide
  - Pleos Connect Emulator 릴리즈 노트 (v2.1.0, 2026-10-19) — https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note
  - 폼 팩터 테스트 가이드 — https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide
- 확인 수준: [문서 확인] + [문서 재확인(2026-11-09)] Virtual Console 카드 목록·Emulator 릴리즈 노트·Playground Discover > Resources & Emulator 페이지. 에뮬레이터 실행은 이 조사에서는 하지 않음. 이후 실행 확인: [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md] (§4·§5·'실행 확인' 절 정정 반영).

## 1. 에뮬레이터 설치 (요약)
- Android Studio > Tools > SDK Manager > SDK Update Sites > `+`
  - Name: `Pleos Connect System Image`
  - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
  - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
- SDK Platforms > **API 34**의 Pleos Connect system image 설치 → Android Studio 재시작
- Device Manager > Create device > Form Factor `Automotive` > `Pleos Connect v2.1.0`
- Advanced: Default boot **Cold** (필수. "`quick boot`로 설정할 경우에는 VHAL을 이용할 수 없습니다"), RAM **4 GB**
- 그래픽 카드 필수. 최소 RTX 3060 Laptop 6GB / RX 6600M 8GB급 (Emulator 설정 페이지)
- 에뮬레이터 기본 언어는 한국어.

## 2. 차량 데이터 주입 — Pleos Connect Virtual Console (Emulator v2.1.0+)
실행:
```
adb forward tcp:8088 tcp:8088
# PC 브라우저에서 http://127.0.0.1:8088/
```
도메인별 카드(사용 가이드 표에서 발췌):

| Domain | 카드 | 값 |
|---|---|---|
| Vehicle | Notification 1 | 차량 알림 표시/숨김 |
| Motion | Gear Selection | P, R, N, D |
| Motion | Brake Pedal | 0~100% |
| Motion | Odometer | 0~1,000,000km |
| Motion | Steering Angle / Steering Wheel Heat | -540~540° / Off~High |
| ADAS | Cruise Control State / Cruise Target Speed | Not ready~Full autonomous / 5.556~27.778 m/s |
| Convenience | Door/Window/Trunk/Lights/Seat Occupancy/Wiper/ETCS/Glove Box | 각 상태 |
| Safety | Telltales | 주차 브레이크, 안전벨트, ABS, TPMS 등 Off/On/Blink |
| Energy | Remaining Range, Battery SOC/Energy/Capacity, Charge … | 범위값 |
| Climate | HVAC 전반 | 범위값 |

- 셋업 가이드 소개에는 "차량 속도, 도어, 조명 등 차량 상태 변경"이라는 문구가 있으나, **사용 가이드의 카드 목록에는 속도(Vehicle Speed) 카드가 없다** [불확실 — 실제 콘솔 화면 확인 필요].
- 반복 테스트·데모 시나리오 실행을 지원한다고 소개되어 있다("반복 테스트 및 데모 시나리오 실행"). 시나리오 저장·자동 재생 방법은 문서에 없음 [불확실].

## 3. 주행 규제 강제 ON/OFF (ADB)
"모든 차종에서 공통으로 사용할 수 있는 명령어입니다."
```
adb shell cmd hmg_car_service --service drivingstate driving_regulation on
adb shell cmd hmg_car_service --service drivingstate driving_regulation off
```
→ CarUxRestrictions가 바뀌는지 확인하는 데 쓴다(vehicle-state.md). 기어 변경은 Virtual Console에서 한다("기어 상태 변경 — Pleos Connect Virtual Console에서 기어 모드를 변경합니다").

## 4. 위치(GPS) 주입 — `geo fix`는 Android 위치(FusedLocation 서비스 포함)에는 들어가지만 **내비(NaviHelper) 위치에는 들어가지 않는다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]
- Pleos 문서(Virtual Console 사용 가이드, Emulator 설정, FusedLocation 문서)에서 **위치 주입·GPS 경로 재생 방법을 찾지 못했다.** Virtual Console 카드 목록에 위치 항목이 없다.
  - 재검토 11/09: Virtual Console 카드 47개(Gear Selection, Brake Pedal, Odometer, Steering Angle, Cruise Control State/Target Speed, ETCS Power/Scenario, 도어·창문·라이트·좌석·공조·배터리·충전 관련 등)를 다시 열거했다. **위치·차량 속도 카드는 없다**(속도 관련은 Cruise Target Speed뿐). 문서 검색 `모의 위치` → 결과 없음. Emulator 설정 문서의 Extended Controls 언급은 여전히 마이크 설정 한 곳뿐.
  - 대안 후보(문서 근거): Playground Discover > Resources & Emulator의 **Devbox**(실차 유사 하드웨어) 사양에 GPS가 있고 실차 기본 앱들이 포함돼 있다고 적혀 있다. Devbox를 공모전 팀이 쓸 수 있는지는 [불확실].
- 일반 Android Emulator의 Extended Controls(문서는 마이크 설정에만 Extended Controls를 언급)에 Location 기능이 있지만, 그 값이 Pleos **FusedLocation Service**로 전달되는지는 문서에 없다. 부분 해소: `adb emu geo fix` 값을 FusedLocation **서비스** 로그(`FusedLocationData(37.566498…, 126.977998…)`)가 받았다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. SDK `onChanged` 호출은 하지 않았다(사용 안 하는 SDK) [미확인].

## 5. 경로·내비 시뮬레이션 — 내비 앱 있음·NaviHelper 요청 응답 확인, 시뮬레이션 사용법은 [미확인]
- (해소) 에뮬레이터에 Navigation 앱이 있고 NaviHelper 요청(init·get*Info 3종·requestRoute·requestTrip·cancelRoute)에 응답한다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 경로 주행 시뮬레이션은 `NaviMode.SIMULATION_GUIDING` 값만 있고(javap) 사용법은 [미확인]. 아래 재검토 기록은 당시 상태다.
  - 재검토 11/09: Discover 페이지는 NaviHelper를 "Pleos Connect에 탑재된 기본 내비게이션 앱과 연동되는 API"라고 설명하고, 에뮬레이터는 홈 화면·GNB·드라이빙 뷰·멀티 윈도우 등 시스템 UI를 제공한다고만 한다. 에뮬레이터에 기본 내비 앱이 포함되는지는 여전히 [미확인 — 에뮬레이터 실행 확인 필요]. 최신 Emulator 릴리즈는 계속 v2.1.0(2026-10-19).
- 실차에서 "내비게이션 탑재 여부 확인 방법은 현재 퍼블릭 오픈하지 않고 있습니다"(FAQ).

## 6. 기타
- 플랫폼 구분: `android.os.Build.DEVICE`는 "실차에서만 유효하게 제공되므로, 에뮬레이터에서는 플랫폼을 구분하는 용도로 사용할 수 없습니다"(FAQ).
- 폼 팩터 테스트: FormFactorConsole + host-display-bridge(Go 1.22+, 포트 8095/8096)로 가상 디스플레이를 만들어 portrait/landscape 레이아웃을 검증한다.
- v2.1.0에서 제거된 것: LLM SDK, CRN, 블루투스 동글 연동.

## 포트 설계 시사점
문서상 에뮬레이터로 확실히 주입할 수 있는 것은 **차량 상태(기어·브레이크 등)와 주행 규제 ON/OFF**뿐이다. 위치는 `adb emu geo fix`가 Android 위치(FusedLocation 서비스 포함)에는 들어가지만 **내비(NaviHelper) 위치에는 들어가지 않는다**([실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]). `adb emu geo nmea`(체크섬 맞춘 문장)도 내비 위치에는 효과가 없었다 [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절]. 경로·ETA 주입 방법과 내비 시뮬레이션은 [미확인]이다. 그러니 출발 마감·반경 확인 시연은 `:departure-fake`의 Fake 시나리오(고정 현재 위치, 고정 ETA, 일몰 시각)에 기대야 한다. Fake 시나리오에는 실제 SDK가 줄 수 있는 실패 상태도 넣는다. 위치 `null`, ETA 콜백 타임아웃(`requestTrip` 무응답), `CAUSE_SEARCH_TRIP` 같은 경우다. 그래야 어댑터로 교체해도 UI 흐름이 같다. 주행 규제는 문서상 adb 명령으로 켤 수 있으므로(단 이 이미지에서는 해당 명령이 실패 — 위 "실행 확인" 절, Virtual Console 경유 재확인 필요), `drivingRestriction` 포트만은 Fake와 실제 어댑터를 둘 다 에뮬레이터에서 검증할 수 있다.


## 실행 확인 (2026-11-09, 감독관, Pleos Connect Emulator / adb) [실행 확인]
환경: AVD `Pleos_Connect_v2.1.0`, 이미지 `system-images/android-34/pleos_car_emulator`(42dot), API 34, 모델 "Connect on x86_64 emulator".
- **호스트 GPU 문제**: Intel UHD 770 호스트 렌더링에서 부팅 멈춤(`OpenGL Core Profile not supported`, qemu 90MB에서 정지). `-gpu swiftshader_indirect`(소프트웨어 렌더링)로 35초 만에 부팅. Android Studio에서는 Device Manager > 편집 > Graphics = Software.
- **내비 앱 있음**: `ai.umos.maps.android.navigation.app` 3.3.2-rc5-hotfix.3, NaviHelper 서비스 `ai.pleos.playground.service.navi` 3.0.4 → 리뷰 task-01 "사용자 확인 1"(Navigation 앱 존재) 해소. 단 NaviHelper 요청에 실제 응답하는지는 → 해소: 응답한다 [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md].
- **FusedLocation 서비스 패키지도 있음**: `ai.pleos.playground.service.location.fused` 1.0.0, GPS 위치를 요청 중. Playground에서는 '준비 중'이므로 사용자 결정(사용 가능 SDK만)에 따라 계속 사용하지 않는다. 기록만.
- **위치 주입 가능**: `adb emu geo fix 126.9780 37.5665`(경도 먼저) → `dumpsys location`의 gps last location이 37.566498,126.977998로 바뀜. 기본값은 37.421998,-122.084(구글 본사). NaviHelper `getCurrentLocationInfo`는 이 값을 **따르지 않는다** [실행 확인 2026-11-09, 에뮬레이터 2.1.0 / 내비 3.3.2 / SDK 3.0.5 — navi-runtime-check.md]. 내비 `NmeaParser`가 에뮬레이터 NMEA를 체크섬 불일치로 버린다(문장 XOR = 0x7D, 붙은 값 *47 — 리뷰어 독립 계산 일치). 그래서 기본 위치 37.4127212, 127.094681(수정구 시흥동)에 고정된다. `adb emu geo nmea`(체크섬을 맞춘 문장, 강남 좌표로 3회 주입)는 2026-11-10에 시험했고 내비 앱이 받는 NMEA(에뮬레이터 자체 생성 문장)는 바뀌지 않았다 [실행 확인 2026-11-10, 에뮬레이터 2.1.0 / 내비 3.3.2-rc5-hotfix.3 / SDK 3.0.5 — navi-runtime-check.md 10절](10.7). 내비 시뮬레이션 주행은 시험하지 않았다 [미확인].
- **Virtual Console 포트 응답**: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/` → HTTP 200.
- **문서의 주행 규제 명령은 이 이미지에서 실패(정정)**: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` → `cmd: Can't find service: hmg_car_service`. `CarDrivingStateService` 현재 상태 0(Parked), `CarUxRestrictionsManagerService` Restrictions 0x1ff. 주행 상태 전환은 Virtual Console(기어·속도 등) 경유로 다시 확인 필요.

- **정정(2026-11-10, task #10)**: 주행 상태 전환은 Virtual Console HTTP(`POST /vehicle/gear?gear=Drive|Park`, 먼저 콘솔 페이지에서 키 입력으로 브릿지 활성화)로 가능하다. adb는 user 빌드라 불가. 자세히는 vehicle-state.md 11절.
