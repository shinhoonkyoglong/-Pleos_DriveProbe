# 2026-11-10 NaviHelper 실행 확인 증거

`../../navi-runtime-check.md` 10절이 인용하는 원본이다. 이 폴더의 파일은 빌드에 포함되지 않는다(코드 아님, 확장자 `.txt`·`.png`).

## 환경
| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 AVD, `emulator-5554`, API 34, `-gpu swiftshader_indirect` |
| 내비 앱 | `ai.umos.maps.android.navigation.app` 3.3.2-rc5-hotfix.3 (`dumpsys package`의 versionName) |
| NaviHelper 서비스 | `ai.pleos.playground.service.navi` 3.0.4 |
| SDK | `ai.pleos.playground:NaviHelper:3.0.5` (프로브 앱) |
| 주행 상태 | `Current Driving State: 0` (P, 0 km/h) 전 구간 |
| 시각 | 에뮬레이터 시계 2026-11-10 09:07~09:21 (로그 접두 `11-10 09:xx`) |

## 파일
| 파일 | 내용 |
|---|---|
| `rN-probe.txt` (N=1~8) | 프로브 앱 로그(태그 `PleosProbe`) 발췌. `CB onXxx`는 SDK 리스너 콜백, `STEP`은 프로브 단계 시작·종료 |
| `rN-navi.txt` | 내비 앱 디버그 로그 중 `NavigationUseCase`·`navi_engine` 태그(`mapmatching` 줄 제외). r5는 실행 중 정규식을 넓혀 `Navi42`·`TripManager`도 포함. **다른 앱의 디버그 로그이며 API가 아니다**(일반 앱은 READ_LOGS 불가). 판정 보조 근거로만 쓴다 |
| `rN-key.txt` | 전체 logcat(`-full.txt`, 저장소에는 넣지 않음)에서 `grep -F`(고정 문자열)로 뽑은 줄. 포함: 문자열 `MainViewModel`이 들어간 모든 줄(태그 `MainViewModel$startConnectNaviProviderCollect`의 `OnRouteRequest(routeInfo=…)`·`ChangeRouteOptionRequest(routeOption=…)`·`OnTripRequest(…)`와 그 밖의 `MainViewModel` 줄), `Navi42`(`tripTollAvoidance`), `[NaviProvider]`·`[NaviHelper][SDK]` 호출 줄, `Invalid NMEA`·`checksum mismatch`. 각 줄은 400자로 잘렸다. r1·r3에만 `OnRouteRequest`가, r2·r6에만 `ChangeRouteOptionRequest`가 있다(해당 호출을 한 실행) |
| `probe/ProbeActivity.kt.txt` | 프로브 앱 소스 한 파일(빌드 제외 확장자). 11/09 프로브에 `route:<lat>:<lon>:<OPTION>:<name>`, `opt:<OPTION>`, `tripEmpty` 단계를 추가한 것 |
| `probe/run.sh` | 실행 스크립트(`adb` 경로는 `ADB` 환경변수로 지정하도록 일반화함. 실제 실행에는 로컬 SDK 경로를 썼다) |
| `probe/nmea.txt`, `probe/nmea2.txt` | 주입한 NMEA 문장(체크섬은 XOR로 계산해 맞춤) |
| `screens/s7.png` | 09:13 서울역 안내 중 화면(1시간 6분·23km) |
| `screens/s8.png` | 안내 중 목적지 변경 시 "목적지로/경유지로" 3초 자동 선택 팝업 |
| `screens/s12.png` | 서울역 안내 시작 직후 화면(5초 카운트다운 소진 후) |
| `screens/s13.png` | 경로 설정 시트("유료도로 우회"·"어린이 보호구역 우회" 토글, 적용 버튼) |
| `screens/s15.png` | 경로 설정 적용 후 경로 후보 2개(토글이 안 켜진 상태, 700원 표시) |
| `screens/s20.png` | "유료도로 우회" 적용 후 경로 후보(통행료 표시 없음, "유료도로 우회" 배지) |
| `screens/s21.png` | 다른 목적지(강남)에서 다시 연 경로 설정 시트(토글 꺼짐) |
| `screens/s22.png` | 모든 경로 취소 후 내비 대기 화면 |

## 수집 방법과 한계
- `run.sh`는 매 실행 시작 때 `adb logcat -c`로 버퍼를 지운 뒤 프로브를 띄우고, 끝나면 `adb logcat -d -v time`을 저장한다. **그래서 직전 실행과 이번 실행 사이(adb input으로 내비 화면을 조작한 구간)의 로그는 남지 않는다.**
  - 특히 09:12~09:14의 "사람이 안내 중 목적지를 바꾼" 구간은 `wait150` 프로브의 터미널 출력으로만 봤고 파일이 없다. `s7.png`·`s8.png`가 결과만 보여 준다.
  - 주입한 NMEA 문장의 전송 시각과 직전 `adb emu geo` 호출도 같은 이유로 로그 파일에 없다(터미널 출력만).
- **로그는 마스킹하지 않은 에뮬레이터 원본이다.** 내비 앱의 네트워크 로그(`rustls` TLS 핸드셰이크, 내부 API 호스트)가 `r1-navi.txt`·`r3-navi.txt`에 그대로 있다. 자격 증명(토큰·헤더·쿠키·계정)은 검색되지 않았다. 판정과는 무관하다.
- 전체 logcat(`-full.txt`, 합 약 5.7MB)은 크기 때문에 넣지 않았다. 필요한 줄은 `-key.txt`·`-navi.txt`에 있다.
- 프로브 소스·빌드: Gradle 9.6 + AGP 9.5.1, JVM 17+ 필요(Android Studio jbr 사용). 의존성은 NaviHelper 3.0.5 AAR.
