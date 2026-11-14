# 계속운전

> 장시간 운전자의 피로를 운전 조작 데이터로 먼저 감지하고, 공조·음성·내비게이션 경유지 안내로 단계별 개입하여 졸음운전 사고를 예방하는 Pleos Connect 차량용 앱

- 팀명: **계운**
- 공모전: 현대자동차그룹 차량용 앱 개발 공모전
- APK: [`apk/계운_계속운전_release.apk`](apk/계운_계속운전_release.apk)

## 주요 기능

| 구분 | 기능 |
|---|---|
| 출발 전 컨디션 확인 (선택) | 정차 중 브레이크 페달 반응 과제(최대 5회). 개인 평균과 비교한 결과를 피로 판단에 반영. 야간·새벽이거나 직전 주행에서 주의 단계 이상이 나왔을 때만 권하며 강제하지 않음 |
| 출발 전 운행 안내 | 내비 목적지·잔여 시간과 현재 위치의 일몰 시각으로 출발 마감 시각 산출. 운전자가 설정한 운행 조건(야간 운전 제한, 허용 반경, 무료도로 우선) 충족 여부 안내 |
| 주행 중 피로 감지 | 조향각 변화(미세 조정 감소·큰 조정 증가·급조향)와 연속 주행 시간·시간대·출발 전 반응 결과로 피로 점수(0~1) 산출. 개인 기준선 대비 판단 |
| 3단계 자동 개입 | 1단계(경미): 공조 작동, 운전석 온도 1℃ 하향, 풍량 1단계 상향<br>2단계(주의): 음성 질문 후 응답 지연 측정<br>3단계(위험): 경로 전방 휴게소·졸음쉼터 이름과 도착 예상 시간을 음성 안내, 동의하면 내비 경유지 자동 추가<br>같은 단계는 5분 안에 반복하지 않음 |
| 주행 후 기록 | 주행 요약 자동 저장, 내 기록. 가족 안심 요약과 8주 운전 리포트는 1차 제출 기준 **예시 데이터 화면**(서버 전송은 본선 범위) |

주행 중에는 화면 조작 없이 음성으로만 안내·응답하며, 실제 주행이 시작되면 주행 화면으로 자동 전환됩니다. 휴게시설은 공공데이터 휴게소 215곳·졸음쉼터 253곳과 대조해 진행 방향이 맞는 시설만 안내합니다.

## 개발 환경

| 항목 | 값 |
|---|---|
| Pleos Connect 에뮬레이터 | **v2.1.0** (Pleos Connect System Image, Android 14 / API 34, Automotive) |
| 화면 규격 | **2560 × 1440, 160 dpi** |
| 언어·UI | Kotlin 2.0.0, Jetpack Compose |
| 빌드 | Android Gradle Plugin 8.8.2, JDK 17, compileSdk / targetSdk 35, minSdk 28 |
| Pleos SDK | Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5, TextToSpeech / SpeechToText SDK 3.3.1 |
| 그 밖의 API | AAOS Car API (속도·기어·UX 제한) |
| 내비게이션 | 에뮬레이터 기본 내비 앱 (3.3.2 계열에서 확인) |

## APK로 실행하기

1. Android Studio Device Manager에서 Pleos Connect v2.1.0 에뮬레이터를 만들고 실행합니다.
   설치 방법은 [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) 문서를 따릅니다.
2. APK를 설치합니다.

   ```bash
   adb install -r apk/계운_계속운전_release.apk
   ```

   앱 목록에 보이지 않으면 운전자 사용자에 설치합니다: `adb install --user 10 -r apk/계운_계속운전_release.apk`
3. 앱(**Vehicle SDK Sample** 아이콘, 앱 안 이름 **계속운전**)을 실행합니다.
4. 처음 실행할 때 **차량 속도 권한**과 **마이크 권한**을 허용합니다.
   속도 권한이 없으면 주행·정차를 판단하지 못하고, 마이크 권한이 없으면 음성 개입을 할 수 없습니다.

## 소스로 빌드하기

JDK 17과 Android SDK Platform 35가 필요합니다. Pleos SDK는 `settings.gradle.kts`에 등록된 Pleos Maven 저장소에서 받습니다.

```bash
./gradlew :app:assembleRelease
```

```bash
./gradlew :app:installDebug
```

## 테스트 환경과 필요한 설정

### 차량 신호 넣기 (Virtual Console)

기어·조향 등 차량 신호는 Pleos Connect Virtual Console로 넣습니다.

```bash
adb forward tcp:8088 tcp:8088
```

PC 브라우저에서 `http://127.0.0.1:8088/`을 열고, 처음에 키를 한 번 눌러 브리지를 활성화합니다.
**P 기어·속도 0**이면 정차, D 기어로 바꾸고 속도가 생기면 주행으로 판단합니다.
자세한 내용은 [Virtual Console 사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)와 [에뮬레이터 주입 정리](docs/pleos/api-notes/emulator-injection.md)를 참고하세요.

### 기능별로 확인하는 방법

| 확인할 기능 | 준비 |
|---|---|
| 출발 전 컨디션 확인 | P 기어. 홈 → **컨디션 체크** |
| 출발 전 운행 안내 | 내비에 목적지를 설정하면 홈의 출발 카드에 출발 마감 시각·운행 조건 결과가 표시됨. 운행 조건은 홈 → **내 운전 조건** |
| 음성 개입 준비 | 정차 중 홈 → **주행 도움 → 주행 도움 켜기**. 마이크 권한 허용 후 음성 준비가 시작됨 |
| 3단계(위험) 휴게소 안내 | 진행 방향에 휴게소·졸음쉼터가 있는 실제 내비 경로가 필요. 홈 → 주행 도움 → **휴게소 설정**에서 현재 경로의 시설을 확인 |
| 주행 중 피로 감지·개입 | D 기어로 주행 신호를 넣으면 주행 화면으로 자동 전환. 첫 판단에는 최소 60초의 유효 주행 구간이 필요 |
| 주행 후 요약 | P 기어로 정차하면 홈에 주행 종료 요약이 표시됨. 홈 → **내 기록** |

### 주행 시나리오 자동 실행 (개발자용)

`scripts/Test-Driving.ps1`은 Virtual Console REST로 조향·속도·기어를 넣어 **주행 시작 → 피로 판단 → 개입 → 정차 요약**을 재현합니다.
엔진 모드 전환에 debug 빌드 전용 기능을 쓰므로 **debug APK**(`./gradlew :app:installDebug`)에서 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Smoke
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Danger -RequireInterventions
```

시나리오(Smoke·Normal·Fatigue·Danger)와 판정 기준은 [주행 기능 테스트 안내](docs/driving-test.md), 실제 실행 결과는 [주행 E2E 기록](docs/driving-test-e2e.md)에 있습니다.

### 알려진 제약

- 에뮬레이터에서는 내비 위치에 GPS를 주입할 수 없어, 휴게소 안내는 내비에 실제로 설정된 경로를 기준으로 동작합니다.
- 에뮬레이터 내비에서 무료도로 우선 옵션이 적용되지 않는 경우가 있어, 요청값이 아니라 실제 적용된 경로 옵션을 읽어 판단합니다.
- Pleos 음성 서비스가 없는 환경에서는 Android 기본 음성 기능으로 자동 전환합니다.
- 3단계에서 추가한 경유지와 1단계에서 바꾼 공조 설정은 자동으로 되돌리지 않습니다.

## 단위 테스트

```bash
./gradlew testDebugUnitTest
```

## 프로젝트 구조

```text
app/            앱 (화면, 피로 판단, 개입, 차량·내비·음성 SDK 연동, Room 저장)
departure-core/ 출발 전 판단 로직 (순수 Kotlin/JVM)
departure-ui/   출발 전 화면 (Compose)
data/           공공데이터 휴게소·졸음쉼터 목록 (rest-stops)
scripts/        에뮬레이터 주행 시험 도구
docs/           기능별 설계·검증 문서
```

주요 문서: [앱 화면](docs/app-ui/README.md) · [출발 전](docs/departure/README.md) · [주행 중 개입](docs/driving-intervention.md) · [주행 도움](docs/driving-assist-app.md) · [휴게시설 목록](docs/public-rest-stop-catalog.md) · [주행 요약](docs/trip-summary.md)
