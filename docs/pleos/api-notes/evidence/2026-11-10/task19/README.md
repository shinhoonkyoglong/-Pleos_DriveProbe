# 작업 #19 — 내비 "유료도로 우회" 토글 반영 확인 (2026-11-10 17:28~17:35, 에뮬레이터 시계)

- 환경: `emulator-5554` Pleos Connect v2.1.0, 기어 P(`Current Driving State: 0`), 내비 앱·서비스 버전은 navi-runtime-check.md §10과 같은 에뮬레이터.
- 테스트: 1번 저장소(Pleos_DriveProbe) `app/src/androidTest/.../departure/TollAvoidanceToggleCheckTest.kt` (logcat 태그 `TollToggleCheck`).
  내비 상태를 바꾸지 않고 지금 경로를 `NaviHelperRepositoryImpl` + 2번 어댑터(`DriveProbeDeparture.createFeature`) + `ConditionRouteUseCase`(무료도로 선호)로 읽는다.
- 상태 준비는 adb 조작(사람 대신): 최근 목록 서울역 → 목적지로 → (OFF) 미리보기 자동 시작 / (ON) 미리보기 필터 버튼 → 경로 설정 시트 → 토글 swipe(`input swipe 1375 361 1415 361 150`, 1회에 켜짐) → 적용 → 후보 화면 "안내 시작" 탭.

| 파일 | 내용 |
|---|---|
| `run1-off-recommended.txt` | 토글 꺼짐 안내 중, `expectedRouteOption=RECOMMENDED` (gradle) |
| `run2-on-free-wait.txt` | 토글 켬, 테스트가 먼저 떠서 대기(`waitForGuidingSec=150`, `am instrument`) → 적용·안내 시작 → onRouteStarted tollFare 관측 |
| `run3-on-free-gradle.txt` | 같은 FREE 안내 중, `expectedRouteOption=FREE` (gradle, 대기 없음 → tollFare 미관측) |
| `s1-off-guiding.jpg` | 토글 꺼짐 안내(53분·23km) |
| `s2-toggle-on.jpg` | 경로 설정 시트, "유료도로 우회" 켜짐 |
| `s3-candidates-free.jpg` | 적용 후 후보 3개, 통행료 표시 없음 + "유료도로 우회" 배지 |
| `s4-free-guiding.jpg` | 유료도로 우회 안내(1시간 30분·26km) |

캡처는 오른쪽 내비 영역만 잘라 40%로 줄인 것이다.
